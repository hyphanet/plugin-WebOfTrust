/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.ui.fcp;

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import plugins.WebOfTrust.EventSource;
import plugins.WebOfTrust.Identity;
import plugins.WebOfTrust.Identity.FetchState;
import plugins.WebOfTrust.MockWebOfTrust;
import plugins.WebOfTrust.OwnIdentity;
import plugins.WebOfTrust.Score;
import plugins.WebOfTrust.SubscriptionManager;
import plugins.WebOfTrust.SubscriptionManager.BeginSynchronizationNotification;
import plugins.WebOfTrust.SubscriptionManager.EndSynchronizationNotification;
import plugins.WebOfTrust.SubscriptionManager.IdentitiesSubscription;
import plugins.WebOfTrust.SubscriptionManager.Notification;
import plugins.WebOfTrust.SubscriptionManager.ObjectChangedNotification;
import plugins.WebOfTrust.SubscriptionManager.ScoresSubscription;
import plugins.WebOfTrust.SubscriptionManager.Subscription;
import plugins.WebOfTrust.SubscriptionManager.TrustsSubscription;
import plugins.WebOfTrust.Trust;
import plugins.WebOfTrust.WebOfTrustInterface;
import plugins.WebOfTrust.exceptions.InvalidParameterException;
import plugins.WebOfTrust.util.jobs.DelayedBackgroundJob;
import plugins.WebOfTrust.util.jobs.TickerDelayedBackgroundJob;
import freenet.clients.fcp.FCPPluginConnection;
import freenet.clients.fcp.FCPPluginMessage;
import freenet.keys.FreenetURI;
import freenet.node.FSParseException;
import freenet.node.PrioRunnable;
import freenet.pluginmanager.FredPluginFCPMessageHandler;
import freenet.pluginmanager.PluginNotFoundException;
import freenet.pluginmanager.PluginRespirator;
import freenet.support.CurrentTimeUTC;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.SimpleFieldSet;
import freenet.support.Ticker;
import freenet.support.api.Bucket;
import freenet.support.codeshortification.IfNull;
import freenet.support.io.NativeThread;

/**
 * This is a reference implementation of how a FCP client application should interact with
 * Web Of Trust via event-notifications.<br>
 * The foundation of event-notifications is class {@link SubscriptionManager}, you should read the
 * JavaDoc of it to understand them.<br><br>
 * 
 * You can use this class in your client like this:<br>
 * - Copy-paste this class. Make sure to specify the hash of the commit which your copy is based on!
 *   <br>
 * - Do NOT modify it. Instead, implement a separate class which implements the required interfaces
 *   {@link ConnectionStatusChangedHandler}, {@link BeginSubscriptionSynchronizationHandler} and
 *   {@link EndSubscriptionSynchronizationHandler}, {@link SubscribedObjectChangedHandler}.
 *   In your separate class, create an object of this class here, and use its public functions to
 *   pass your handler implementations to it.<br>
 * - If you do need to modify this class for improvements, please ensure that they are backported
 *   to WOT.<br>
 * - It should periodically be checked if all client applications use the most up to date version
 *   of this class.<br>
 * - To simplify checking whether a client copy of this class is outdated, the hash of the commit
 *   which the copy was based on helps very much. Thats why we want to stress that you should
 *   include the hash in your copypasta!<br><br>
 * 
 * For understanding how to implement a user of this class, please just read the JavaDoc of the
 * public functions and interfaces. I tried to sort all function by order of execution and provide
 * full JavaDoc, so I hope the whole private internals of this class will be easy to understand as
 * well.<br><br>
 * 
 * NOTICE: This class was based upon class SubscriptionManagerFCPTest, which you can find in the unit tests. Please backport improvements.
 * [Its not possible to link it in the JavaDoc because the unit tests are not within the classpath.] 
 * <br><br>
 * 
 * TODO: JavaDoc: The part of this class about subscription synchronization could need more JavaDoc.
 *  
 * @see FCPInterface The "server" to which a FCP client connects.
 * @see SubscriptionManager The foundation of event-notifications and therefore the backend of all FCP traffic which this class does.
 * @author xor (xor@freenetproject.org)
 */
public final class FCPClientReferenceImplementation {
	
	/** This is the core class name of the Web Of Trust plugin. Used to connect to it via FCP */
	public static final String WOT_FCP_NAME = "plugins.WebOfTrust.WebOfTrust";

	/** The amount of milliseconds between each attempt to connect to the WoT plugin */
	private static final int WOT_RECONNECT_DELAY = 1 * 1000;
	
	/** The amount of milliseconds between sending pings to WOT to see if we are still connected */
	private static final int WOT_PING_DELAY = 60 * 1000;
	
	/** The amount of milliseconds after which assume the connection to WOT to be dead and try to reconnect */
	private static final int WOT_PING_TIMEOUT_DELAY = 2*WOT_PING_DELAY;
	
	/** The amount of milliseconds for waiting for "Unsubscribed" messages to arrive in {@link #stop()} */
	private static final int SHUTDOWN_UNSUBSCRIBE_TIMEOUT = 3*1000;
	
	/**
	 * The implementing child class provides this Map. It is used for obtaining the {@link Identity} objects which are used for
	 * constructing {@link Trust} and {@link Score} objects which are passed to its handlers.
	 */
	private final Map<String, Identity> mIdentityStorage;
	
	/** The interface for creating connections to WOT via FCP. Provided by the Freenet node */
	private final PluginRespirator mPluginRespirator;
	
	/** The function {@link KeepaliveLoop#run()} is periodically executed.
	 *  It sends a Ping to WOT and checks whether the existing subscriptions are OK.
	 *  If no reply to the Ping is received, it automatically reconnects. */
	private final KeepaliveLoop mKeepAliveLoop;
	
	/** For randomizing the delay between periodic execution of {@link KeepaliveLoop#run()} on {@link #mKeepAliveLoop} */
	private final Random mRandom;
	
	/**
	 * FCPClientReferenceImplementation can be in one of these states.
	 */
	private enum ClientState {
		/** {@link #start()} has not been called yet */
		NotStarted, 
		/** {@link #start()} has been called */
		Started, 
		/** {@link #stop()} has been called and is waiting for shutdown to complete. */
		StopRequested,
		/** {@link #stop()} has finished. */
		Stopped
	};
	
	/**
	 * Any public functions in this class which "change stuff" MUST check whether they should be allowed to execute in this {@link ClientState}.
	 * Examples:
	 * - Subscriptions MUST NOT be filed if this state is not {@link ClientState#Started}.
	 * - A connection to Web Of Trust MUST NOT be created if this state is not {@link ClientState#Started}. 
	 */
	private ClientState mClientState = ClientState.NotStarted;

	/**
	 * The connection to the Web Of Trust plugin. Null if we are disconnected.<br>
	 * MUST only be non-null if {@link #mClientState} equals {@link ClientState#Started} or
	 * {@link ClientState#StopRequested}.<br><br>
	 * 
	 * volatile because {@link #connected()} uses it without synchronization.<br>
	 * TODO: Optimization: I don't think it needs to be volatile anymore, I think we synchronize
	 * in all places which uses this. Validate that and remove the volatile if yes.
	 */
	private volatile FCPPluginConnection mConnection = null;
	
	/** Called when the connection to WOT is established or lost. Shall be used by the UI to display a "Please install Web Of Trust" warning. */
	private final ConnectionStatusChangedHandler mConnectionStatusChangedHandler;
	
	/** The value of {@link CurrentTimeUTC#get()} when we last sent a ping to the Web Of Trust plugin. */
	private long mLastPingSentDate = 0;
	
	/**
	 * All types of {@link Subscription}. The names match the FCP messages literally and are used for lookup, please do not change them.
	 * Further, the subscriptions are filed in the order which they appear hear: {@link Trust}/{@link Score} objects reference {@link Identity}
	 * objects and therefore cannot be created if the {@link Identity} object is not known yet. This would be the case if
	 * Trusts/Scores subscriptions were filed before the Identities subscription.
	 * 
	 * ATTENTION: The mandatory order which is documented here should as well be specified in
	 * {@link FCPClientReferenceImplementation#subscribe(Class,
	 * BeginSubscriptionSynchronizationHandler, EndSubscriptionSynchronizationHandler,
	 * SubscribedObjectChangedHandler)}.
	 */
	public enum SubscriptionType {
		/** @see IdentitiesSubscription */
		Identities(Identity.class),
		/** @see TrustsSubscription */
		Trusts(Trust.class),
		/** @see ScoresSubscription */
		Scores(Score.class);
		
		public final Class<? extends EventSource> subscribedObjectType;
		
		SubscriptionType(Class<? extends EventSource> mySubscribedObjectType) {
			subscribedObjectType = mySubscribedObjectType;
		}
		
		public static SubscriptionType fromClass(Class<? extends EventSource> clazz) {
		    for(SubscriptionType type : values()) {
		        if(type.subscribedObjectType == clazz)
		            return type;
		    }
		    
		    throw new IllegalArgumentException("Not a valid SubscriptionType: " + clazz);
		}
	};
	
	/** Contains the {@link SubscriptionType}s the client wants to subscribe to. */
	private EnumSet<SubscriptionType> mSubscribeTo = EnumSet.noneOf(SubscriptionType.class);

    /**
     * Each is of these handlers called at the begin of a Subscription to indicate that a series of
     * {@link ObjectChangedNotification} will follow which contain the full state of the WOT
     * database of the type to which we subscribed. After that, the appropriate
     * {@link EndSubscriptionSynchronizationHandler} is called, to signal to the client that it
     * shall delete all objects from its database which were not contained in the synchronization.
     * @see BeginSynchronizationNotification
     *          The SubscriptionManager.BeginSynchronizationNotification is the underlying
     *          source of this event.
     * @see #mEndSubscriptionSynchronizationHandlers
     *          The mEndSubscriptionSynchronizationHandlers table contains the said handlers for
     *          marking the end of the synchronization series.
     */
	private final EnumMap
	    <SubscriptionType, BeginSubscriptionSynchronizationHandler<? extends EventSource>>
	        mBeginSubscriptionSynchronizationHandlers = new EnumMap
	            <SubscriptionType, BeginSubscriptionSynchronizationHandler<? extends EventSource>>
	            (SubscriptionType.class);

    /**
     * Each is of these handlers at the end of a Subscription to indicate that the series of
     * {@link ObjectChangedNotification} which formed the initial state synchronization has ended.
     * Once the client receives the call of this handler, it shall delete all objects from its
     * database which were not contained in the synchronization.
     * @see EndSynchronizationNotification
     *          The SubscriptionManager.EndSynchronizationNotification is the underlying
     *          source of this event.
     * @see #mBeginSubscriptionSynchronizationHandlers
     *          The mBeginSubscriptionSynchronizationHandlers table contains the handlers which
     *          mark the begin of the synchronization series.
     */
	private final EnumMap
	    <SubscriptionType, EndSubscriptionSynchronizationHandler<? extends EventSource>>
	        mEndSubscriptionSynchronizationHandlers = new EnumMap
	            <SubscriptionType, EndSubscriptionSynchronizationHandler<? extends EventSource>>
	            (SubscriptionType.class);

	/**
	 * Each of these handlers is called when an object changes to whose type the client is subscribed.
	 * @see SubscribedObjectChangedHandler
	 */
	private final EnumMap
	    <SubscriptionType, SubscribedObjectChangedHandler<? extends EventSource>>
	        mSubscribedObjectChangedHandlers = new EnumMap
	            <SubscriptionType, SubscribedObjectChangedHandler<? extends EventSource>>
	            (SubscriptionType.class);
	
	/**
	 * The values are the IDs of the current subscriptions of the {@link SubscriptionType} which the key specifies.
	 * Null if the subscription for that type has not yet been filed.
	 * @see SubscriptionManager.Subscription#getID()
	 */
	private EnumMap<SubscriptionType, String> mSubscriptionIDs = new EnumMap<SubscriptionType, String>(SubscriptionType.class);

	
	/** Implements interface {@link FredPluginFCPMessageHandler.ClientSideFCPMessageHandler}:
	 *  Receives messages from WOT in a callback. */
	private final FCPMessageReceiver mFCPMessageReceiver = new FCPMessageReceiver();
	
	/** Maps the String name of WOT FCP messages to the handler which shall deal with them */
	private HashMap<String, FCPMessageHandler> mFCPMessageHandlers = new HashMap<String, FCPMessageHandler>();
	
	/**
	 * For each value of the enum {@link SubscriptionType}, contains a parser matching the class
	 * value of the following field of the SubscriptionType:<br>
	 * <code>Class{@literal <? extends EventSource>} {@link SubscriptionType#subscribedObjectType}
	 * </code><br><br>
	 * 
	 * The construct objects of those classes from data received by FCP. 
	 */
    private final EnumMap
	    <SubscriptionType, FCPEventSourceContainerParser<? extends EventSource>>
	        mParsers = new EnumMap
	        <SubscriptionType, FCPEventSourceContainerParser<? extends EventSource>>
            (SubscriptionType.class);

	/** Automatically set to true by {@link Logger} if the log level is set to {@link LogLevel#DEBUG} for this class.
	 * Used as performance optimization to prevent construction of the log strings if it is not necessary. */
	private static transient volatile boolean logDEBUG = false;
	
	/** Automatically set to true by {@link Logger} if the log level is set to {@link LogLevel#MINOR} for this class.
	 * Used as performance optimization to prevent construction of the log strings if it is not necessary. */
	private static transient volatile boolean logMINOR = false;
	
	static {
		// Necessary for automatic setting of logDEBUG and logMINOR
		Logger.registerClass(FCPClientReferenceImplementation.class);
	}


	/**
	 * TODO: Code quality: Document which functions of the {@link Map} interface have to be
	 * implemented: The user of this constructor will typically be a client plugin, so the author
	 * won't know much about the internals of this class. Also, the client plugin will likely use
	 * a database for storing Identity objects, so each function would require implementation of
	 * database query = be quite a bit of work. We likely only need a get(String key), so we could
	 * save the client author quite a bit of work.
	 */
	public FCPClientReferenceImplementation(final Map<String, Identity> myIdentityStorage,
			final PluginRespirator myPluginRespirator,
			final ConnectionStatusChangedHandler myConnectionStatusChangedHandler) {
		mIdentityStorage = myIdentityStorage;
		mPluginRespirator = myPluginRespirator;
		mKeepAliveLoop = new KeepaliveLoop(mPluginRespirator.getNode().getTicker());
		mRandom = mPluginRespirator.getNode().fastWeakRandom;
		
		mConnectionStatusChangedHandler = myConnectionStatusChangedHandler;
		
		final FCPMessageHandler[] handlers = {
				new FCPPongHandler(),
				new FCPSubscriptionSucceededHandler(),
				new FCPSubscriptionTerminatedHandler(),
				new FCPErrorHandler(),
				new FCPBeginSynchronizationEventHandler(),
				new FCPEndSynchronizationEventHandler(),
				new FCPObjectChangedEventHandler()
		};
		
		for(FCPMessageHandler handler : handlers)
			mFCPMessageHandlers.put(handler.getMessageName(), handler);
		
		// To prevent client-plugins which copy-paste this reference implementation from having to copy-paste the WebOfTrust class,
		// we use MockWebOfTrust as a replacement.
		final MockWebOfTrust wot = new MockWebOfTrust();
		
		mParsers.put(SubscriptionType.Identities, new IdentityParser(wot));
		mParsers.put(SubscriptionType.Trusts, new TrustParser(wot, mIdentityStorage));
		mParsers.put(SubscriptionType.Scores, new ScoreParser(wot, mIdentityStorage));
	}
	
	/**
	 * Tells the client to start connecting to WOT. Must be called at startup of your plugin.
	 * <br><br>
	 * 
	 * Must be called after your user object of this client is ready to process messages in its
	 * event handlers:<br>
	 * - {@link #handleConnectionEstablished()}
	 * - {@link #handleConnectionLost()}
	 * 
	 * You will not receive any event callbacks before start was called.
	 */
	public final synchronized void start() {
		Logger.normal(this, "Starting...");
		
		if(mClientState != ClientState.NotStarted)
			throw new IllegalStateException(mClientState.toString());
		
		mClientState = ClientState.Started;
		mKeepAliveLoop.triggerExecution(0);

		Logger.normal(this, "Started.");
	}
	
	/**
	 * Call this to file a {@link Subscription}.
	 * The type may be one of {@link Identity}.class, {@link Trust}.class, {@link Score}.class
	 * 
	 * ATTENTION: If you subscribe to multiple types, you must do so in the following order:
	 * - {@link Identity}.class
	 * - {@link Trust}.class
	 * - {@link Score}.class
	 * This is because {@link Trust}/{@link Score} objects hold references to {@link Identity} objects and therefore your database won't
	 * make sense if you don't know which the reference {@link Identity} objects are.
	 * <br><br>
	 * 
	 * ATTENTION: The passed handlers will be hard-referenced by this object even after
	 * {@link #unsubscribe(Class)} has been called. This is because unsubscribing is processed
	 * asynchronously: FCP messages might be received from WOT after you have called unsubscribe()
	 * but before the unsubscription has finished. In that case, this object might have to pass
	 * the messages to your handlers, so they must not be null. If you need the handlers to be
	 * garbage-collected, you should re-subscribe with different handlers, or throw away the whole
	 * client.
	 */
	public final synchronized <T extends EventSource> void subscribe(final Class<T> type,
			final BeginSubscriptionSynchronizationHandler<T> beginSyncHandler,
			final EndSubscriptionSynchronizationHandler<T> endSyncHandler,
			final SubscribedObjectChangedHandler<T> objectChangedHandler) {
	    
		if(mClientState != ClientState.Started)
			throw new IllegalStateException(mClientState.toString());
		
		final SubscriptionType realType = SubscriptionType.fromClass(type);
		if(mSubscribeTo.contains(realType))
			throw new IllegalStateException("Subscription for that type exists already!");
		
		mSubscribeTo.add(realType);
		
		IfNull.thenThrow(beginSyncHandler);
		IfNull.thenThrow(endSyncHandler);
		IfNull.thenThrow(objectChangedHandler);
		
		mBeginSubscriptionSynchronizationHandlers.put(realType, beginSyncHandler);
		mEndSubscriptionSynchronizationHandlers.put(realType, endSyncHandler);
		mSubscribedObjectChangedHandlers.put(realType, objectChangedHandler);
		
		mKeepAliveLoop.triggerExecution(0);
	}
	
	/**
	 * Call this to cancel a {@link Subscription}.
	 */
	public final synchronized <T extends EventSource> void unsubscribe(final Class<T> type) {
		if(mClientState != ClientState.Started)
			throw new IllegalStateException(mClientState.toString());
		
		mSubscribeTo.remove(SubscriptionType.fromClass(type));

		mKeepAliveLoop.triggerExecution(0);
	}

	/**
	 * The function {@link KeepaliveLoop#run()} is periodically executed by {@link #mJob}.
	 * It sends a Ping to WOT and checks whether the existing subscriptions are OK.
	 */
	private final class KeepaliveLoop implements DelayedBackgroundJob, PrioRunnable {
	    /** The actual implementation of DelayedBackgroundJob.<br>
	     *  For scheduling threaded, delayed execution of {@link KeepaliveLoop#run()}. */
	    private final DelayedBackgroundJob mJob;

	    public KeepaliveLoop(Ticker ticker) {
	        mJob = new TickerDelayedBackgroundJob(this, "WOT " + this.getClass().getSimpleName(),
	            0 /* We always specify a delay, so no default is needed */, ticker);
	    }

		/**
		 * Calls {@link #triggerExecution(long)} with the delay chosen as follows:<br>
		 * If connected to WOT, the delay will be randomized and roughly equal to {@link #WOT_PING_DELAY}.
		 * If not connected, it will be WOT_RECONNECT_DELAY.
		 */
	    @Override public void triggerExecution() {
			final long sleepTime = mConnection != null ? (WOT_PING_DELAY/2 + mRandom.nextInt(WOT_PING_DELAY)) : WOT_RECONNECT_DELAY;
			triggerExecution(sleepTime);
		}

        @Override public void triggerExecution(long sleepTime) {
            assert(!mJob.isTerminated())
                : "Should not be called after terminate() as mJob won't execute then";

            // If the given delay is shorter than an already scheduled run, this will implicitly
            // reschedule the run to the shorter delay as desired.
            // (We do desire this because subscribe/unsubscribe need it to happen immediately)
            mJob.triggerExecution(sleepTime);

			if(logMINOR) Logger.minor(this, "Sleeping for " + (sleepTime / (60*1000)) + " minutes.");
		}

		/**
		 * "Keepalive Loop": Checks whether we are connected to WOT. Connects to it if the connection is lost or did not exist yet.
		 * Then files all {@link Subscription}s.
		 * 
		 * Executed by {@link #mJob} as scheduled periodically:
		 * - Every {@link #WOT_RECONNECT_DELAY} seconds if we have no connection to WOT
		 * - Every {@link #WOT_PING_DELAY} if we have a connection to WOT <br><br>
		 * 
		 * Notice: This function does NOT honor {@link Thread#interrupted()} like a user of
		 * {@link DelayedBackgroundJob} should. This is because I do not see much potential for
		 * honoring it as this should be a very fast function in theory. However when changing this
		 * function to include more code please make sure to consider allowing thread interruption.
		 * When adapting this function to honor interruption, you must also adapt
		 * {@link FCPClientReferenceImplementation#stop()} to be even able to interrupt us by
		 * making it call {@link #terminate()} outside of
		 * synchronized(FCPClientReferenceImplementation.this). See the call to terminate() there
		 * for an in-depth explanation of the required changes.
		 */
		@Override
		public final void run() { 
			synchronized(FCPClientReferenceImplementation.this) {
			    try {
			        if(logMINOR) Logger.minor(this, "Connection-checking loop running...");

			        if(mClientState != ClientState.Started) {
                        // FCPClientReferenceImplementation.stop() sets ClientState.StopRequested
                        // before terminate()ing us so it is a valid state here.
                        assert mClientState == ClientState.StopRequested
                            : "Connection-checking loop executed in wrong state: " + mClientState;
			            return;
			        }

			        if(!connected() || pingTimedOut())
			            connect();

			        if(!connected())
			            return; // finally{} block schedules fast reconnecting.

			        try {
			            fcp_Ping();
			            checkSubscriptions();
			        } catch(IOException e) {
			            Logger.normal(this, "Connetion lost in connection-checking loop.", e);
			            force_disconnect();
			            return; // finally{} block schedules fast reconnecting.
			        }
			    } catch (Throwable e) {
			        // FIXME: Code quality: This used to be "catch(RuntimeException | Error e)" but
			        // was changed to catch(Throwable) because we need to be Java 6 compatible until
			        // the next build. Change it back to the Java7-style catch(). The following
			        // comment is a leftover of the Java7-style catch(), it will be valid again once
			        // you restore the Java7-catch():
			        // This catches every non-declare-able Exception to ensure that the thread
			        // doesn't die because of them: Keeping the connection alive is important so
			        // this thread must stay alive.
			        // We catch "RuntimeException | Error" instead of "Throwable" to exclude
			        // declare-able Exceptions to ensure that people are noticed by the compiler if
			        // they add code which forgets handling them.

			        Logger.error(this, "Error in connection-checking loop!", e);
			        force_disconnect();
			    } finally {
			        // Will schedule this function to be executed again.
			        // The delay is long if mConnection is alive and we are just waiting for a ping.
			        // The delay is short if mConnection == null and we need to reconnection.
			        triggerExecution();
			        if(logMINOR) Logger.minor(this, "Connection-checking finished.");
			    }
			}
		}
		
        @Override public void terminate() {
            mJob.terminate();
        }

        @Override public boolean isTerminated() {
            return mJob.isTerminated();
        }

        @Override public void waitForTermination(long delayMillis) throws InterruptedException {
            mJob.waitForTermination(delayMillis);
        }

		/**
		 * Determines the priority of the thread running {@link #run()}
		 * It is chosen to be {@link NativeThread.PriorityLevel#MIN_PRIORITY} as the loop is not latency-critical.
		 */
		@Override
		public final int getPriority() {
			return NativeThread.PriorityLevel.MIN_PRIORITY.value;
		}
	}

	/**
	 * Tries to connect to WOT.
	 * Safe to be called if a connection already exists - it will be replaced with a new one then.
	 */
	private synchronized void connect() {
		force_disconnect();
		
		Logger.normal(this, "connect()");

		try {
			mConnection = mPluginRespirator.connectToOtherPlugin(WOT_FCP_NAME, mFCPMessageReceiver);
			mSubscriptionIDs.clear();
			Logger.normal(this, "Connected to WOT, connection: " + mConnection);
			try {
				mConnectionStatusChangedHandler.handleConnectionStatusChanged(true);
			} catch(Throwable t) {
				Logger.error(this, "ConnectionStatusChangedHandler.handleConnectionStatusChanged() "
				                 + "threw up, please fix your handler!", t);
			}
		} catch(PluginNotFoundException e) {
			Logger.warning(this, "Cannot connect to WOT!");
			// The force_disconnect() at the beginning of the function already did this for us.
			/*
			try {
				mConnectionStatusChangedHandler.handleConnectionStatusChanged(false);
			} catch(Throwable t) {
				Logger.warning(this, "ConnectionStatusChangedHandler.handleConnectionStatusChanged() threw up, please fix your handler!", t);
			}
			*/
		}
	}
	
	/**
	 * Sends the given {@link SimpleFieldSet} via {@link #mConnection} by boxing it into a
	 * {@link FCPPluginMessage}.<br><br>
	 * 
	 * ATTENTION: This sends a <b>non-reply</b> {@link FCPPluginMessage}. You <b>must not</b> use
	 * this to send reply messages.<br>
	 * See {@link FCPPluginMessage#construct(SimpleFieldSet, Bucket)}.<br><br>
	 * 
	 * ATTENTION: Does not synchronize, does not check whether {@link #mConnection} is null.<br><br>
	 * 
	 * @throws IOException See {@link FCPPluginConnection#send(FCPPluginMessage)}.
	 */
	private void send(final SimpleFieldSet sfs) throws IOException {
		mConnection.send(FCPPluginMessage.construct(sfs, null));
	}
	
	/**
	 * Nulls the connection to WOT.
	 * Unsubscribes all existing {@link Subscription}s so WOT does not not maintain them anymore. Does NOT remove them from {@link #mSubscribeTo}:
	 * The purpose of this is to make connections transparent to the child event handler. Once it has requested to be subscribed, it does
	 * not have to care about whether a connection exists or not. This class will automatically reconnect if the connection is lost and
	 * file the subscriptions again.
	 */
	private synchronized void force_disconnect() {
		Logger.normal(this, "force_disconnect()");
		
		if(mConnection == null)
			return;
		
		try {
		    for(SubscriptionType type : mSubscriptionIDs.keySet())
		        fcp_Unsubscribe(type);
		} catch (IOException e) {
		    // The connection is dead already, so we cannot unsubscribe and don't have to.
		    Logger.normal(this, "force_disconnect(): Disconnected already, not unsubscribing.");
		}

		// The "Unsubscribed" message would normally trigger the removal from the 
		// mSubscriptionIDs array but we cannot receive it anymore after we are
		// disconnected so we remove the ID ourselves
		for(SubscriptionType type : mSubscriptionIDs.keySet())
		    mSubscriptionIDs.remove(type);

		try {
		    mConnectionStatusChangedHandler.handleConnectionStatusChanged(false);
		} catch(Throwable t) {
		    Logger.warning(this, "ConnectionStatusChangedHandler.handleConnectionStatusChanged() "
		                       + "threw up, please fix your handler!", t);
		}

		// Notice: FCPPluginConnection has no explicit disconnection mechanism. The JavaDoc of
		// PluginRespirator.connectToOtherPlugin() instructs us that can and must drop all strong
		// references to the FCPPluginConnection to it to cause disconnection implicitly.
		mConnection = null;
	}
	
	/**
	 * @return True if we are connected to WOT, false if not. Does NOT tell whether the Subscriptions were filed yet.
	 */
	private final boolean connected()  {
		return mConnection != null;
	}
	
	/**
	 * @return True if the last ping didn't receive a reply within 2*{@link #WOT_PING_DELAY} milliseconds.
	 */
	private synchronized boolean pingTimedOut() {
		// This is set to 0 by the FCPPongHandler (which receives the ping reply) when:
		// - we never sent a ping yet. Obviously we can't blame timeout on the client then
		// - whenever we received a pong which marked the ping as successful
		if(mLastPingSentDate == 0)
			return false;
		
		return (CurrentTimeUTC.getInMillis() - mLastPingSentDate) > WOT_PING_TIMEOUT_DELAY;
	}
	
	/**
	 * Sends a "Ping" FCP message to WOT. It will reply with a "Pong" message which is then handled by the {@link FCPPongHandler}.
	 * Used for checking whether the connection to WOT is alive.<br><br>
	 * 
	 * TODO: Code quality: This is a good candidate for using
	 * {@link FCPPluginConnection#sendSynchronous(FCPPluginMessage, long)}. See
	 * {@link PluginRespirator#connectToOtherPlugin(String,
	 * FredPluginFCPMessageHandler.ClientSideFCPMessageHandler)} for an explanation.
	 * 
	 * @throws IOException See {@link #send(SimpleFieldSet)}.
	 */
	private synchronized void fcp_Ping() throws IOException {
		if(logMINOR) Logger.minor(this, "fcp_Ping()");
		
		final SimpleFieldSet sfs = new SimpleFieldSet(true);
		sfs.putOverwrite("Message", "Ping");
		send(sfs);
		mLastPingSentDate = CurrentTimeUTC.getInMillis();
	}
	
	/**
	 * Iterates over the {@link SubscriptionType} enum values. Checks whether the client has requested to subscribe / unsubscribe any of them and does so. 
	 * @throws IOException See {@link #fcp_Subscribe(SubscriptionType)} and
	 *                     {@link #fcp_Unsubscribe(SubscriptionType)}.
	 */
	private synchronized void checkSubscriptions() throws IOException {
		for(SubscriptionType type : SubscriptionType.values()) {
			final boolean shouldSubscribe = mSubscribeTo.contains(type);
			final boolean isSubscribed = mSubscriptionIDs.get(type) != null;
			if(shouldSubscribe && !isSubscribed) {
				fcp_Subscribe(type);
				// Only subscribe one at a time: If we subscribe to Scores/Trusts, we need to subscribe to Identities first: Otherwise
				// the Identity objects won't exist. Score/Trust objects reference Identity objects so they cannot be created if we didn't 
				// receive the identities yet. 
				// The SubscriptionType enum is ordered to contain subscriptions which need to be filed first at the beginning so it is
				// safe to just break.
				// Also, if the SubscriptionSucceededHandler is not triggered before the next scheduled execution, this function
				// will just try to subscribe to the identities again and abort again: The isSubscribed boolean will only be true
				// after the event handler has received the identities. WOT also prevents duplicate subscriptions so nothing bad can happen.
				// NOTICE: The SubscriptionSucceededHandler will immediately schedule the next execution of the loop which calls this 
				// function, so it is guaranteed that subscriptions are filed quickly.
				break;
			} else if(!shouldSubscribe && isSubscribed) {
				fcp_Unsubscribe(type);
			}
		}
	}
	
	/**
	 * Sends a "Subscribe" FCP message to WOT.<br>
	 * It will reply with a "Subscribed" message, which is handled by
	 * {@link FCPSubscriptionSucceededHandler}.
	 * 
	 * @param type The {@link SubscriptionType} to which you want to subscribe.
	 * @throws IOException See {@link #send(SimpleFieldSet)}.
	 */
	private void fcp_Subscribe(final SubscriptionType type) throws IOException {
		Logger.normal(this, "fcp_Subscribe(): " + type);
		
		final SimpleFieldSet sfs = new SimpleFieldSet(true);
		sfs.putOverwrite("Message", "Subscribe");
		sfs.putOverwrite("To", type.toString());
		send(sfs);
	}
	
	/**
	 * Sends a "Unsubscribe" FCP message to WOT. It will reply with an "Unsubscribed" message which is handled by {@link FCPSubscriptionTerminatedHandler}.
	 * 
	 * @param type The {@link SubscriptionType} which you want to unsubscribe. {@link #mSubscriptionIDs} must contain an ID for this type. 
	 * @throws IOException See {@link #send(SimpleFieldSet)}.
	 */
	private void fcp_Unsubscribe(final SubscriptionType type) throws IOException {
		Logger.normal(this, "fcp_Unsubscribe(): " + type);
		
		final SimpleFieldSet sfs = new SimpleFieldSet(true);
		sfs.putOverwrite("Message", "Unsubscribe");
		sfs.putOverwrite("SubscriptionID", mSubscriptionIDs.get(type));
		send(sfs);
	}

	/**
	 * Receives FCP messages from WOT:
	 * - In reply to messages sent to it via {@link FCPPluginConnection}
	 * - As events happen via event-{@link Notification}s
	 */
	private class FCPMessageReceiver
	    implements FredPluginFCPMessageHandler.ClientSideFCPMessageHandler {
	    
		/**
		 * Called by Freenet when it receives a FCP message from WOT.
		 * 
		 * The function will determine which {@link FCPMessageHandler} is responsible for the message type and call its
		 * {@link FCPMessageHandler#handle(SimpleFieldSet, Bucket).
		 */
        @Override
        public final FCPPluginMessage handlePluginFCPMessage(FCPPluginConnection connection,
                FCPPluginMessage message) {
			synchronized(FCPClientReferenceImplementation.this) {

			// Check whether we are actually connected. If we are not connected, we must not handle FCP messages.
			// We do NOT have to check mClientState: mConnection must only be non-null in states where it is acceptable.
			if(mConnection == null || connection != mConnection) {
				final String state = "My connection: " + mConnection
				                   + "; My ClientState:" + mClientState
				                   + "; Passed connection: " + connection
				                   + "; Passed FCPPluginMessage ==" + message;

				final String errorMessage =
				    "Received unexpected message, maybe because we reconnected and"
                  + " the old server is still alive? " + state;
				
				Logger.error(this, errorMessage);
				
				// There might be a dangling subscription at the side of the remote WOT for which we
				// are still receiving event notifications.
				// WOT terminates subscriptions automatically once their failure counter reaches a certain limit.
				// For allowing WOT to notice the failure, we must reply with an error reply (as 
				// long as the message wasn't a reply - Replying to replies is not allowed.)
                return !message.isReplyMessage() ? FCPPluginMessage.constructErrorReply(
                            message, "InternalError", errorMessage)
                       : null;
			}

			final String messageString = message.params.get("Message");
			final FCPMessageHandler handler = mFCPMessageHandlers.get(messageString);

			if(handler == null) {
			    String errorMessage =  "Unknown message type: " + messageString +
			                           "; full message: " + message;
			    
			    Logger.warning(this, errorMessage);
			    return !message.isReplyMessage() ? FCPPluginMessage.constructErrorReply(
			                message , "InternalError", errorMessage)
			           : null;
			}

			if(logMINOR) Logger.minor(this, "Handling message '" + messageString + "' with " + handler + " ...");
			try {
				handler.handle(message);
				return !message.isReplyMessage() ? FCPPluginMessage.constructSuccessReply(message)
				       : null;
			} catch(ProcessingFailedException e) {
			    String errorMessage = "Message handler failed and requested passing the error to"
			                        + " WOT, doing so: " + handler;
				Logger.error(this, errorMessage, e);
				return !message.isReplyMessage() ? FCPPluginMessage.constructErrorReply(
				           message, "InternalError", errorMessage)
				       : null;
			} finally {
				if(logMINOR) Logger.minor(this, "Handling message finished.");
			}
			
			} // synchronized(FCPClientReferenceImplementation.this) {
		}

	}
	/**
	 * Each FCP message sent by WOT contains a "Message" field in its {@link SimpleFieldSet}. For each value of "Message",
	 * a {@link FCPMessageHandler} implementation must exist.
	 * 
	 * Upon reception of a message,
	 * {@link FCPMessageReceiver#handlePluginFCPMessage(FCPPluginConnection, FCPPluginMessage)}
	 * calls {@link FCPMessageHandler#handle(FCPPluginMessage)} of the {@link FCPMessageHandler}
	 * which is responsible for it.
	 */
	private interface FCPMessageHandler {
		/**
		 * This function shall the value of the {@link SimpleFieldSet} "Message" field this handler belongs to with.
		 */
		public String getMessageName();
		
		/**
		 * @throws ProcessingFailedException
		 *             May be thrown if you want {@link FCPMessageReceiver#handlePluginFCPMessage(
		 *             FCPPluginConnection, FCPPluginMessage)} to signal to WOT that processing
		 *             failed. This only is suitable for handlers of event-notifications:<br>
		 *             WOT will send the event-notifications synchronously and therefore notice if
		 *             they failed. It will resend them for a certain amount of retries then.
		 */
		public void handle(final FCPPluginMessage message) throws ProcessingFailedException;
	}
	
	/**
	 * @see BeginSubscriptionSynchronizationHandler
	 * @see EndSubscriptionSynchronizationHandler
	 * @see SubscribedObjectChangedHandler
	 */
	@SuppressWarnings("serial")
    public final class ProcessingFailedException extends Exception {
		public ProcessingFailedException(Throwable t) {
			super(t);
		}
	}
	
	/**
	 * Handles the "Pong" message which we receive in reply to {@link FCPClientReferenceImplementation#fcp_Ping()}.
	 * Reception of this message indicates that the connection to WOT is alive.
	 */
	private final class FCPPongHandler implements FCPMessageHandler {
		@Override
		public String getMessageName() {
			return "Pong";
		}
		
		@Override
		public void handle(final FCPPluginMessage message) {
			if((CurrentTimeUTC.getInMillis() - mLastPingSentDate) <= WOT_PING_TIMEOUT_DELAY)
				mLastPingSentDate = 0; // Mark the ping as successful.
		}
	}
	
	/**
	 * Handles the "Subscribed" message which we receive in reply to {@link FCPClientReferenceImplementation#fcp_Subscribe(SubscriptionType)}.
	 * Reception of this message indicates that we successfully subscribed to the requested {@link SubscriptionType}.
	 */
	private final class FCPSubscriptionSucceededHandler implements FCPMessageHandler {
		@Override
		public String getMessageName() {
			return "Subscribed";
		}

		@Override
		public void handle(final FCPPluginMessage message) {
	    	final String id = message.params.get("SubscriptionID");
	    	final String to = message.params.get("To");
	    	
	    	assert(id != null && id.length() > 0);
	    	assert(to != null);
	    	
	    	final SubscriptionType type = SubscriptionType.valueOf(to);
	    	
	    	assert (!mSubscriptionIDs.containsKey(type) 
	    	    || "SubscriptionExistsAlready".equals(message.errorCode)) // See FCPErrorHandler
	    	    : "Subscription should not exist already";
	    	
	    	mSubscriptionIDs.put(type, id);
	    	
	    	// checkSubscriptions() only files one subscription at a time (see its code for an explanation).
	    	// Therefore, after subscription has succeeded, we need to schedule the KeepaliveLoop (which is run()) to be executed again
	    	// soon so it calls checkSubscriptions() to file the following subscriptions.
            mKeepAliveLoop.triggerExecution(0);
		}
	}
	
	/**
	 * Handles the "Unsubscribed" message which we receive in reply to {@link FCPClientReferenceImplementation#fcp_Unsubscribe(SubscriptionType)}.
	 * Reception of this message indicates that we successfully unsubscribed from the requested {@link SubscriptionType}.
	 */
	private final class FCPSubscriptionTerminatedHandler implements FCPMessageHandler {
		@Override
		public String getMessageName() {
			return "Unsubscribed";
		}

		@Override
		public void handle(final FCPPluginMessage message) {
	    	final String id = message.params.get("SubscriptionID");
	    	final String from = message.params.get("From");
	    	
	    	assert(id != null && id.length() > 0);
	    	assert(from != null);
	    	
	    	final SubscriptionType type = SubscriptionType.valueOf(from);
	    	assert mSubscriptionIDs.containsKey(type) : "Subscription should exist";
	    	mSubscriptionIDs.remove(type);

    		// The purpose of the StopRequested state is to to give pending "Unsuscribed" messages time to arrive.
    		// After we have received all of them, we must:
    		// - Update the mClientState
    		// - Notify the stop() function that shutdown is finished.
	    	if(mClientState == ClientState.StopRequested && mSubscriptionIDs.isEmpty())
	    		FCPClientReferenceImplementation.this.notifyAll();
		}
	}
	
	/**
	 * Handles the "Error" message which we receive when a FCP message which we sent triggered an error.
	 * Logs it as ERROR to the Freenet log file.
	 *
	 * There is one type of Error which is not severe and therefore logged as WARNING: If we tried to subscribed twice because of high latency.
	 * An explanation is in the source code of the function.
	 */
	private final class FCPErrorHandler implements FCPMessageHandler {
		@Override
		public String getMessageName() {
			return "Error";
		}

		@Override
		public void handle(final FCPPluginMessage message) throws ProcessingFailedException {
			if(message.errorCode.equals("SubscriptionExistsAlready")) {
			    // Subscription filing works like this:
			    // 1) The KeepAliveLoop executes periodically every few seconds, and checks whether
			    //    the user of the client has requested subscribing but there is no active
			    //    subscription.
			    // 2) If there is a user request to subscribe, a "Subscribe" message is sent to WOT.
			    //    The subscription is NOT marked active in our active subscriptions table.
			    // 3) Once WOT sends back a "Subscribed" message, the SubscriptionSucceededHandler
			    //    will mark the subscription as active.
			    // This implies that the following error condition can happen:
			    // - We are in stage 2), i.e. we sent a "Subscribe" request, but it has not been
			    //   confirmed by a "Subscribed" message yet, and thus the subscription table says
			    //   we are not subscribed.
			    // - WOT does not reply with a "Subscribed" message fast enough before the sleep
			    //   period of the KeepAliveLoop expires. Thus, the KeepAliveLoop executes and sees
			    //   that there is a subscription request but we are not subscribed, and tries to
			    //   file the subscription again with another "Subscribe" message.
			    // - WOT will reply to the second request with the "SubscriptionExistsAlready" error
			    //   which we are handling here, because it has received the same subscription
			    //   request twice.
			    //
			    // As a conclusion, we handle this error like a regular "Subscribed" message by
			    // passing it to a FCPSubscriptionSucceeded handler.
			    // (Of course we could just assume that the original "Subcribed" message from WOT
			    // will also arrive at some point in time, and thus ignore the error message. But
			    // that would be less robust: If the "Subscribed" message was lost somehow, for
			    // example due to a parser error at our side, we would be caught in an infinite
			    // loop of "Subscribe" messages being sent by us, and "SubscriptionExistsAlready"
			    // being replied.
			    // Additionally, it is likely that we want to change the WOT implementation in the
			    // future to allow subscriptions to be persistent across restarts of WOT, up to a
			    // certain time limit of a week or so. Then the client probably would receive a
			    // SubscriptionExistsAlready regularly at every restart: The client could not know
			    // whether the uptime of WOT was a lot higher than its own meanwhile, resulting in
			    // the expiration of the persistent subscription. Thus it would have to try to
			    // re-subscribe at every restart, resulting in a SubscriptionExistsAlready most 
			    // of the time.)
			    
				Logger.warning(this, "Received SubscriptionExistsAlready error message, marking "
				                   + "subscription as active: To=" + message.params.get("To")
				                   + "; SubscriptionID=" + message.params.get("SubscriptionID"));
				
				// The format of the message is the same as of the subscription succeed message,
				// so we can pass it to the FCPSubscriptionSucceededHandler without modification.
				new FCPSubscriptionSucceededHandler().handle(message);
			} else {
				Logger.error(this, "Unknown FCP error message: " + message);
			}
		}
	}
	
	
	/**
	 * Since we let the implementing child class of the abstract FCPClientReferenceImplementation handle the events, the handler might throw.
	 * In that case we need to gracefully tell WOT about that: In case of {@link Subscription}'s event {@link Notification}s, it will re-send them then. 
	 */
	private abstract class MaybeFailingFCPMessageHandler implements FCPMessageHandler {
		@Override
		public void handle(final FCPPluginMessage message) throws ProcessingFailedException {
			try {	
				handle_MaybeFailing(message.params, message.data);
			} catch(Throwable t) {
				throw new ProcessingFailedException(t); 
			}
		}
		
		abstract void handle_MaybeFailing(final SimpleFieldSet sfs, final Bucket data) throws Throwable;
	}

	/**
     * Handles the "BeginSynchronizationEvent" message which we receive in reply to
     * {@link FCPClientReferenceImplementation#fcp_Subscribe(SubscriptionType)}.
     * 
	 * @see SubscriptionManager.BeginSynchronizationNotification */
	private class FCPBeginSynchronizationEventHandler
	        extends MaybeFailingFCPMessageHandler {

	    @Override public String getMessageName() {
	        return "BeginSynchronizationEvent";
	    }

	    @Override void handle_MaybeFailing(final SimpleFieldSet sfs, final Bucket data)
	            throws ProcessingFailedException {
	        
	        mBeginSubscriptionSynchronizationHandlers.get(parseSubscriptionType(sfs))
	            .handleBeginSubscriptionSynchronization(parseVersionID(sfs));
	    }
        
        final SubscriptionType parseSubscriptionType(final SimpleFieldSet sfs) {
            return SubscriptionType.valueOf(sfs.get("To"));
        }
        
        final UUID parseVersionID(final SimpleFieldSet sfs) {
            return UUID.fromString(sfs.get("VersionID"));
        }
	}

	/**
	 * @see FCPBeginSynchronizationEventHandler
	 * @see SubscriptionManager.EndSynchronizationNotification
	 */
	private final class FCPEndSynchronizationEventHandler
	        extends FCPBeginSynchronizationEventHandler {

	    @Override public String getMessageName() {
	        return "EndSynchronizationEvent";
	    }

	    @Override void handle_MaybeFailing(final SimpleFieldSet sfs, final Bucket data)
	            throws ProcessingFailedException {
	        
            mEndSubscriptionSynchronizationHandlers.get(parseSubscriptionType(sfs))
                .handleEndSubscriptionSynchronization(parseVersionID(sfs));
	    }
	}

	/**
	 * Handles the "ObjectChangedEvent" message which WOT sends when an
	 * {@link Identity}, {@link Trust} or {@link Score} was changed, added or deleted.
	 * This will be send when we are subscribed to one of the above three classes.<br><br>
	 * 
	 * Parses the contained {@link Identity} / {@link Trust} / {@link Score} and 
	 * passes it to the event handler {@link SubscribedObjectChangedHandler} with the type parameter
	 * matching Identity / Trust / Score.
	 */
	private final class FCPObjectChangedEventHandler
	        extends MaybeFailingFCPMessageHandler {
	    
		@Override
		public String getMessageName() {
			return "ObjectChangedEvent";
		}
		
		@SuppressWarnings("unchecked")
		@Override
		public void handle_MaybeFailing(SimpleFieldSet sfs, Bucket data)
		        throws FSParseException, InvalidParameterException, MalformedURLException,
		               ProcessingFailedException {
		    
		    final SubscriptionType subscriptionType = parseSubscriptionType(sfs);
		    
            final FCPEventSourceContainerParser<? extends EventSource> parser
                = mParsers.get(subscriptionType);
            
		    final SubscribedObjectChangedHandler<EventSource> handler
		        = (SubscribedObjectChangedHandler<EventSource>)
		            mSubscribedObjectChangedHandlers.get(subscriptionType);
		    
		    final ChangeSet<EventSource> changeSet
		        = (ChangeSet<EventSource>) parser.parseObjectChangedEvent(sfs);

			handler.handleSubscribedObjectChanged(changeSet);
		}
		
        private final SubscriptionType parseSubscriptionType(final SimpleFieldSet sfs) {
            return SubscriptionType.valueOf(sfs.get("SubscriptionType"));
        }
	}

	/**
	 * Represents the data of a {@link SubscriptionManager.Notification}
	 */
	public static final class ChangeSet<CT extends EventSource> {
		/**
		 * @see SubscriptionManager.Notification#getOldObject()
		 */
		public final CT beforeChange;
		
		/**
		 * @see SubscriptionManager.Notification#getNewbject()
		 */
		public final CT afterChange;
		
		public ChangeSet(CT myBeforeChange, CT myAfterChange) {
			beforeChange = myBeforeChange;
			afterChange = myAfterChange;
			
			assert((beforeChange != null && afterChange != null)
					|| (beforeChange == null ^ afterChange == null));
			
			assert(!(beforeChange != null && afterChange != null) || 
					(beforeChange.getID().equals(afterChange.getID())));
		}
		
		@Override
		public String toString() {
			return "ChangeSet { beforeChange: " + beforeChange + "; afterChange: " + afterChange + "}"; 
		}
	}

	/**
	 * Baseclass for parsing messages from WOT containing Identity/Trust/Score objects.<br>
	 * This currently is limited to ObjectChangedEvent messages but might be more in
	 * the future.<br><br>
	 * 
	 * The implementing child classes only have to implement parsing of a single Identity/Trust/Score object. The format of the 
	 * messages which contain multiple of them is a superset so the single-element parser can be used.
	 */
	public static abstract class FCPEventSourceContainerParser<T extends EventSource> {
		
		protected final WebOfTrustInterface mWoT;
		
		public FCPEventSourceContainerParser(final WebOfTrustInterface myWebOfTrust) {
			mWoT = myWebOfTrust;
		}
		
		/**
		 * @Deprecated TODO: Currently unused. Could be put to use if we implement public functions
		 * in {@link FCPClientReferenceImplementation} to allow the user to get multiple
		 * objects from WOT by FCP when they are needed due to a current demand, not due to
		 * event-notifications. For example "getIntroductionPuzzles()" maybe.
		 */
		@Deprecated
		public ArrayList<T> parseMultiple(final SimpleFieldSet wholeSfs)
		        throws FSParseException, MalformedURLException, InvalidParameterException {
		    
			final SimpleFieldSet sfs = getOwnSubset(wholeSfs);
			final int amount = sfs.getInt("Amount");
			final ArrayList<T> result = new ArrayList<T>(amount+1);
			for(int i=0; i < amount; ++i) {
				result.add(parseSingle(sfs, i));
			}
			return result;
		}

		public ChangeSet<T> parseObjectChangedEvent(final SimpleFieldSet notification)
		        throws MalformedURLException, FSParseException, InvalidParameterException {
		    
			final SimpleFieldSet beforeChange = getOwnSubset(notification.subset("Before"));
			final SimpleFieldSet afterChange = getOwnSubset(notification.subset("After"));
			
			return new ChangeSet<T>(parseSingle(beforeChange, 0), parseSingle(afterChange, 0));
		}
		
		/** For example if this was an {@link IdentityParser}, if the input contained "Identities.*", it would return all * fields without the prefix. */
		abstract protected SimpleFieldSet getOwnSubset(final SimpleFieldSet sfs) throws FSParseException;
		
		abstract protected T parseSingle(SimpleFieldSet sfs, int index) throws FSParseException, MalformedURLException, InvalidParameterException;
	
	}
	
	/**
	 * Parser for FCP messages which describe an {@link Identity} or {@link OwnIdentity} object.
	 */
	public static final class IdentityParser extends FCPEventSourceContainerParser<Identity> {

		public IdentityParser(final WebOfTrustInterface myWebOfTrust) {
			super(myWebOfTrust);
		}
		
		@Override
		protected SimpleFieldSet getOwnSubset(SimpleFieldSet sfs) throws FSParseException {
			return sfs.getSubset("Identities");
		}
		
		@Override
		protected Identity parseSingle(final SimpleFieldSet wholeSfs, final int index) throws FSParseException, MalformedURLException, InvalidParameterException {
			final SimpleFieldSet sfs = wholeSfs.getSubset(Integer.toString(index));
			
			final String type = sfs.get("Type");
	    	
			if(type.equals("Nonexistent"))
	    		return null;
	    	
	        final String nickname = sfs.get("Nickname");
	        final String requestURI = sfs.get("RequestURI");
	    	final String insertURI = sfs.get("InsertURI");
	    	final boolean doesPublishTrustList = sfs.getBoolean("PublishesTrustList");
	        final String id = sfs.get("ID");
	        final UUID versionID = UUID.fromString(sfs.get("VersionID"));
	 	
	 		final Identity identity;
	 		
	 		if(type.equals("Identity"))
	 			identity = new Identity(mWoT, requestURI, nickname, doesPublishTrustList);
	 		else if(type.equals("OwnIdentity"))
	 			identity = new OwnIdentity(mWoT, insertURI, nickname, doesPublishTrustList);
	 		else
	 			throw new RuntimeException("Unknown type: " + type);
	 		
	 		assert(identity.getID().equals(id));
	 		
	 		// The Identity constructor will use the edition in the URI only as edition hint so we have to set it manually.
	 		assert(insertURI == null || new FreenetURI(requestURI).getEdition() == new FreenetURI(insertURI).getEdition());
	 		identity.forceSetEdition(new FreenetURI(requestURI).getEdition());
	 		
	 		final int contextAmount = sfs.getInt("Contexts.Amount");
	        final int propertyAmount = sfs.getInt("Properties.Amount");
	 		
	        for(int i=0; i < contextAmount; ++i) {
	        	identity.addContext(sfs.get("Contexts." + i + ".Name"));
	        }

	        for(int i=0; i < propertyAmount; ++i)  {
	            identity.setProperty(sfs.get("Properties." + i + ".Name"),
	            		sfs.get("Properties." + i + ".Value"));
	        }
	        
	    	identity.forceSetCurrentEditionFetchState(FetchState.valueOf(sfs.get("CurrentEditionFetchState")));
	    	
	    	identity.setVersionID(versionID);

	        return identity;
		}
	}

	/**
	 * Parser for FCP messages which describe a {@link Trust} object.
	 */
	public static final class TrustParser extends FCPEventSourceContainerParser<Trust> {

		private final Map<String, Identity> mIdentities;
		
		public TrustParser(final WebOfTrustInterface myWebOfTrust, final Map<String, Identity> myIdentities) {
			super(myWebOfTrust);
			mIdentities = myIdentities;
		}

		@Override
		protected SimpleFieldSet getOwnSubset(SimpleFieldSet sfs) throws FSParseException {
			return sfs.getSubset("Trusts");
		}

		@Override
		protected Trust parseSingle(final SimpleFieldSet wholeSfs, final int index) throws FSParseException, InvalidParameterException {
			final SimpleFieldSet sfs = wholeSfs.getSubset(Integer.toString(index));
			
	    	if(sfs.get("Value").equals("Nonexistent"))
	    		return null;
	    	
			final String trusterID = sfs.get("Truster");
			final String trusteeID = sfs.get("Trustee");
			final byte value = sfs.getByte("Value");
			final String comment = sfs.get("Comment");
			final long trusterEdition = sfs.getLong("TrusterEdition");
			final UUID versionID = UUID.fromString(sfs.get("VersionID"));
			
			final Trust trust = new Trust(mWoT, mIdentities.get(trusterID), mIdentities.get(trusteeID), value, comment);
			trust.forceSetTrusterEdition(trusterEdition);
			trust.setVersionID(versionID);
			
			return trust;
		}
		
	}

	/**
	 * Parser for FCP messages which describe a {@link Score} object.
	 */
	public static final class ScoreParser extends FCPEventSourceContainerParser<Score> {
		
		private final Map<String, Identity> mIdentities;
		
		public ScoreParser(final WebOfTrustInterface myWebOfTrust, final Map<String, Identity> myIdentities) {
			super(myWebOfTrust);
			mIdentities = myIdentities;
		}

		@Override
		protected SimpleFieldSet getOwnSubset(SimpleFieldSet sfs) throws FSParseException {
			return sfs.getSubset("Scores");
		}

		@Override
		protected Score parseSingle(final SimpleFieldSet wholeSfs, final int index) throws FSParseException {
			final SimpleFieldSet sfs = wholeSfs.getSubset(Integer.toString(index));
			
	    	if(sfs.get("Value").equals("Nonexistent"))
	    		return null;
	    	
			final String trusterID = sfs.get("Truster");
			final String trusteeID = sfs.get("Trustee");
			final int capacity = sfs.getInt("Capacity");
			final int rank = sfs.getInt("Rank");
			final int value = sfs.getInt("Value");
			final UUID versionID = UUID.fromString(sfs.get("VersionID"));
			
			final OwnIdentity truster = (OwnIdentity)mIdentities.get(trusterID);
			final Identity trustee = mIdentities.get(trusteeID);
			final Score score = new Score(mWoT, truster, trustee, value, rank, capacity);
			score.setVersionID(versionID);
			
			return score;
		}
		
	}

	public interface ConnectionStatusChangedHandler {
		/**
		 * Called when the client has connected to WOT successfully or the connection was lost. 
		 * This handler should update your user interface remove or show a "Please install the Web Of Trust plugin!" warning.
		 * If this handler was not called yet, you should assume that there is no connection and display the warning as well. 
		 * 
		 * ATTENTION: You do NOT have to call {@link FCPClientReferenceImplementation#
		 * subscribe(Class, BeginSubscriptionSynchronizationHandler,
		 * EndSubscriptionSynchronizationHandler, SubscribedObjectChangedHandler)} in this handler!
		 * Subscriptions will be filed automatically by the client whenever the connection is
		 * established.
		 * It will also automatically reconnect if the connection is lost.<br><br>
		 * 
		 * ATTENTION: The client will automatically try to reconnect, you do NOT have to call {@link FCPClientReferenceImplementation#start()}
		 * or anything else in this handler!
		 */
		void handleConnectionStatusChanged(boolean connected);
	}

	public interface BeginSubscriptionSynchronizationHandler<T extends EventSource>  {
        /**
         * Called very soon after you have subscribed via {@link FCPClientReferenceImplementation#
         * subscribe(Class, BeginSubscriptionSynchronizationHandler,
         * EndSubscriptionSynchronizationHandler, SubscribedObjectChangedHandler)}.
         * The type T matches the Class parameter of the above subscribe function.
         * 
         * After this, a series of calls to your {@link SubscribedObjectChangedHandler} will follow,
         * in total shipping ALL objects in the WOT database of whose type T you have subscribed to:
         * - For {@link Identity}, all {@link Identity} and {@link OwnIdentity} objects in the WOT database.
         * - For {@link Trust}, all {@link Trust} objects in the WOT database.
         * - For {@link Score}, all {@link Score} objects in the WOT database.
         * You should store any of them which you need.
         * 
         * WOT sends ALL objects at the beginning of a connection because this will cut down future traffic very much: For example, if an {@link Identity}
         * changes, WOT will only have to send the new version of it for allowing you to make your database completely up-to-date again.
         * This means that this handler is only called once at the beginning of a {@link Subscription}, all changes after that will trigger
         * a {@link SubscribedObjectChangedHandler} instead.
         * 
         * @throws ProcessingFailedException You are free to throw this. The failure of the handler will be signaled to WOT. It will cause the
         * same notification to be re-sent after a delay of roughly {@link SubscriptionManager#PROCESS_NOTIFICATIONS_DELAY}.
         * You can use this mechanism for programming your client in a transactional style: If anything in the transaction which processes 
         * this handler fails, roll it back and make the handler throw. You can then expect to receive the same call again after the delay
         * and hope that the transaction will succeed the next time.
         */
	    void handleBeginSubscriptionSynchronization(final UUID versionID)
	            throws ProcessingFailedException;
	}

	public interface EndSubscriptionSynchronizationHandler<T extends EventSource>  {
	    /**
	     * Marks the end of the series of {@link ObjectChangedNotification} started by
	     * {@link BeginSubscriptionSynchronizationHandler#}. See its JavaDoc for the purpose of
	     * this mechanism.<br><br>
	     * 
	     * Upon processing of this callback, you must delete all objects of the type T from your
	     * database whose versionID does not match the passed versionID:<br>
	     * Each of the objects passed during the series to your
	     * {@link SubscribedObjectChangedHandler} had been specified with the same versionID. As
	     * the subscription synchronization contains the FULL dataset of all objects of the type T
	     * in the WOT database, anything which is only contained in your database from a previous
	     * connection but was not contained in the synchronization is an obsolete object which
	     * does not exist in the WOT database anymore.<br>
	     * The versionID serves as a "mark-and-sweep" garbage collection mechanism to allow you to
	     * determine which those obsolete objects are, and delete them.
	     * 
         * @throws ProcessingFailedException You are free to throw this. The failure of the handler will be signaled to WOT. It will cause the
         * same notification to be re-sent after a delay of roughly {@link SubscriptionManager#PROCESS_NOTIFICATIONS_DELAY}.
         * You can use this mechanism for programming your client in a transactional style: If anything in the transaction which processes 
         * this handler fails, roll it back and make the handler throw. You can then expect to receive the same call again after the delay
         * and hope that the transaction will succeed the next time.
	     */
	    void handleEndSubscriptionSynchronization(final UUID versionID)
	            throws ProcessingFailedException;
	}

	public interface SubscribedObjectChangedHandler<T extends EventSource> {
		/**
		 * Called if an object is changed/added/deleted to whose class you subscribed to via
		 * {@link FCPClientReferenceImplementation#subscribe(Class,
		 * BeginSubscriptionSynchronizationHandler, EndSubscriptionSynchronizationHandler,
		 * SubscribedObjectChangedHandler)}.
		 * The type T matches the Class parameter of the above subscribe function.
		 * You will receive notifications about changed objects for the given values of T:
		 * - For {@link Identity}, changed {@link Identity} and {@link OwnIdentity} objects in the WOT database.
		 * - For {@link Trust}, changed {@link Trust} objects in the WOT database.
		 * - For {@link Score}, changed {@link Score} objects in the WOT database.
		 * The passed {@link ChangeSet} contains the version of the object  before the change and after the change.
		 * 
		 * ATTENTION: The type of an {@link Identity} can change from {@link OwnIdentity} to {@link Identity} or vice versa.
		 * This will also trigger a call to this event handler.<br><br>
		 * 
		 * ATTENTION: If the notification is sent as part of series marked by
		 * {@link BeginSubscriptionSynchronizationHandler} and
		 * {@link EndSubscriptionSynchronizationHandler}, the {@link ChangeSet#beforeChange} will
		 * always be null, even if the object had existed previously.<br>
		 * This is because the purpose of the synchronization is to fix an unsynchronized state of
		 * your client: Because WOT and the client are not in sync, WOT cannot know whether your
		 * client already knew about the object before the synchronization. So it just sets it to
		 * null.
		 * 
		 * @throws ProcessingFailedException You are free to throw this. The failure of the handler will be signaled to WOT. It will cause the
		 * same notification to be re-sent after a delay of roughly {@link SubscriptionManager#PROCESS_NOTIFICATIONS_DELAY}.
		 * You can use this mechanism for programming your client in a transactional style: If anything in the transaction which processes 
		 * this handler fails, roll it back and make the handler throw. You can then expect to receive the same call again after the delay
		 * and hope that the transaction will succeed the next time.
		 */
		 void handleSubscribedObjectChanged(ChangeSet<T> changeSet) throws ProcessingFailedException;
	}
	
	/**
	 * Must be called at shutdown of your plugin.
	 */
	public final void stop() {
		Logger.normal(this, "stop() ...");

        // The following comment is only for readers who came here because they are planing to 
        // change KeepAliveLoop.run() to honor Thread.interrupted():
        // For the said purpose, this synchronized() block should be somehow eliminated because it
        // prevents us from causing the interrupt while run() is still executing - we call
        // terminate() to cause the interrupt AFTER this block but run() will have the lock which
        // this block wants, so we won't reach terminate() before run() is finished already.
        // When trying to eliminate this synchronized() block, please notice:
        // - It is important to somehow store the StopRequested state before we begin to terminate()
        //   stuff to ensure that this IdentityFetcher cannot appear to be in a working state even
        //   though it is not if waitFortermination() fails but terminate() had succeeded.
        // - We do need to check mClientState != ClientState.Started here before terminate() because
        //   otherwise we might terminate() the KeepAliveLoop before we even have been start()ed,
        //   which would cause start() to appear to be working but the KeepAliveLoop never executing
        // Overall, changing this would be somehow complex. Possible solutions:
        // - Using an AtomicReference for the mClientState so it has its own concurrency domain. See
        //   class SubscriptionManager's variable mJob. However, this would require review &
        //   adaption of this whole class since mClientState is assumed to be synchronized against
        //   this.
        // - Getting rid of mClientState and using mKeepAliveLoop.isTerminated() as state.
        //   This might be possible because one of the primary purposes of mClientState is claimed
        //   to be to prevent new subscriptions from happening while this function is waiting for
        //   the existing ones to be terminated. This doesn't seem to be actually necessary:
        //   Terminating the KeepAliveLoop prevents new subscriptions from being transported to WOT
        //   as subscribe() only requests us to subscribe at WOT, but doesn't send the FCP message,
        //   it leaves that job to the KeepAliveLoop
        //   However, it is possible that mClientState still serves other important purposes, so
        //   please review the class carefully.
        synchronized(this) {
            if(mClientState != ClientState.Started) {
                Logger.warning(this, "stop(): Not even started, current state = " + mClientState);
                return;
            }

            // The purpose of having this state is so we can wait for the confirmations of
            // fcp_Unsubscribe() to arrive from WOT.
            // Also, we do this now already instead of before unsubscribing to make sure that this
            // object is marked as stopping before we terminate the KeepAliveLoop. See the above
            // large comment for why this is a good idea.
            mClientState = ClientState.StopRequested;
        }

        // Prevent mKeepAliveLoop.run() from executing again.
        mKeepAliveLoop.terminate();
        
        // Notice: We MUST do this outside of synchronized(this) because run() is synchronized(this)
        // as well, and thus a deadlock would occur if we called waitForTermination() while holding
        // the lock because run() might already be executing and waiting for the lock.
        try {
            mKeepAliveLoop.waitForTermination(Long.MAX_VALUE);
        } catch (InterruptedException e) {
            // We are a shutdown function, it does not make any sense to interrupt us.
            Logger.error(this, "stop() should not be Thread.interrupt()ed.", e);
            // We partly honor the shutdown request by:
            // - not calling the waitForTermination() again
            // - Saving the state of the Thread being interrupted in case something outside
            //   is expecting it, by calling interrupt() below.
            Thread.currentThread().interrupt(); 
        }

        synchronized(this) {
        // ClientState.StopRequested prevents new subscriptions from being filed. It is necessary
        // that this is the case before we now start to unsubscribe the existing subscriptions so
        // new ones cannot happen afterwards
        assert(mClientState == ClientState.StopRequested);
        
		// Call fcp_Unsubscribe() on any remaining subscriptions and wait() for the "Unsubscribe" messages to arrive
		if(!mSubscriptionIDs.isEmpty() && mConnection != null) {
		    try {
		        for(SubscriptionType type : mSubscriptionIDs.keySet()) {
		            fcp_Unsubscribe(type);
		            // The handler for "Unsubscribed" messages will notifyAll() once there are no
		            // more subscriptions 
		        }

		        Logger.normal(this, "stop(): Waiting for replies to fcp_Unsubscribe() calls...");
		        try {
		            // Releases the lock on this object - which is why we needed to set
		            // mClientState = ClientState.StopRequested:
		            // To prevent new subscriptions from happening in between
		            wait(SHUTDOWN_UNSUBSCRIBE_TIMEOUT);
		        } catch (InterruptedException e) {
		            Logger.error(this, "stop(): Received InterruptedException while waiting for "
		                             + "replies to fcp_Unsubscribe(). The shutdown thread should "
		                             + "not be interrupted: Requesting the shutdown thread to "
		                             + "shutdown does not make any sense!", e);
		            
		            // We partly honor the shutdown request by:
		            // - not calling the wait(SHUTDOWN_UNSUBSCRIBE_TIMEOUT) again
		            // - Saving the state of the Thread being interrupted in case something outside
                    //   is expecting it, by calling interrupt() below.
		            Thread.currentThread().interrupt(); 
		            
		            // We do NOT honor the shutdown request by an immediate "return;" though:
		            // As stated in the above Logger.warning(), the InterruptedException which
		            // requests this the stop() thread to stop() is nonsense: The purpose of stop() 
		            // is stopping the client, so requesting it to stop is redundant. So a middle
		            // path in dealing with it while still doing clean shutdown is respecting the
		            // aborted wait(), which is what would have taken the longest of this function,
		            // but at least continuing to do the remaining cleanup which this function would
		            // do after the wait() - it should be fast anyway. Notice that the wait()
		            // could have timed out (= returned before the data has arrived) in regular
		            // operation, so it is OK if we accept the interruption of it: The below code
		            // must be able to deal with not having the data yet anyway.
		            
		            /* return; */
		        }			
	        } catch(IOException e) {
	            // We catch this here instead of closer to the fcp_Unsubscribe() call to ensure that
	            // we don't enter the wait(): Waiting for replies to confirm the unsubscription
	            // does not make any sense if the connection is closed - replies won't arrive then.
	            Logger.normal(this, "stop(): Disconnected during fcp_Unsubscribe().");
	        }
		}
		
        if(!mSubscriptionIDs.isEmpty()) {
            if(mConnection != null) {
                Logger.warning(this, "stop(): Waiting for fcp_Unsubscribe() failed or timed out, "
                    + "now forcing disconnect. If log messages about out-of-band messages follow, "
                    + "you can ignore them.");
            } else
                Logger.warning(this, "stop(): Unsubscribing failed, mConnection == null already.");
        }
		
		// We call force_disconnect() even if shutdown worked properly because there is no non-forced function and it won't force
		// anything if all subscriptions have been terminated properly before.
		force_disconnect();
		
		mClientState = ClientState.Stopped;
		
		Logger.normal(this, "stop() finished.");
        }
	}

}
