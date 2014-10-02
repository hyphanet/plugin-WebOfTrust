/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.ui.fcp;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import plugins.WebOfTrust.Identity;
import plugins.WebOfTrust.Identity.FetchState;
import plugins.WebOfTrust.MockWebOfTrust;
import plugins.WebOfTrust.OwnIdentity;
import plugins.WebOfTrust.Persistent;
import plugins.WebOfTrust.Score;
import plugins.WebOfTrust.SubscriptionManager;
import plugins.WebOfTrust.SubscriptionManager.IdentitiesSubscription;
import plugins.WebOfTrust.SubscriptionManager.Notification;
import plugins.WebOfTrust.SubscriptionManager.ScoresSubscription;
import plugins.WebOfTrust.SubscriptionManager.Subscription;
import plugins.WebOfTrust.SubscriptionManager.TrustsSubscription;
import plugins.WebOfTrust.Trust;
import plugins.WebOfTrust.WebOfTrustInterface;
import plugins.WebOfTrust.exceptions.InvalidParameterException;
import freenet.keys.FreenetURI;
import freenet.node.FSParseException;
import freenet.node.PrioRunnable;
import freenet.pluginmanager.FredPluginTalker;
import freenet.pluginmanager.PluginNotFoundException;
import freenet.pluginmanager.PluginRespirator;
import freenet.pluginmanager.PluginTalker;
import freenet.support.CurrentTimeUTC;
import freenet.support.Executor;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.SimpleFieldSet;
import freenet.support.TrivialTicker;
import freenet.support.api.Bucket;
import freenet.support.codeshortification.IfNull;
import freenet.support.io.NativeThread;

/**
 * This is a reference implementation of how a FCP client application should interact with Web Of Trust via event-notifications.
 * The foundation of event-notifications is class {@link SubscriptionManager}, you should read the JavaDoc of it to understand them.
 * 
 * You can use this class in your client like this:
 * - Copy-paste this abstract base class. Make sure to specify the hash of the commit which your copy is based on!
 * - Do NOT modify it. Instead, implement a child class which implements the abstract functions.
 * - Any improvements you have to the abstract base class should be backported to WOT!
 * - It should periodically be checked if all client applications use the most up to date version of this class.
 * - To simplify checking whether a client copy of this class is outdated, the hash of the commit which the copy was based on helps very much.
 *   Thats why we want to stress that you should include the hash in your copypasta!
 * 
 * For understanding how to implement a child class of this, plese just read the class. I tried to sort it by order of execution and
 * provide full JavaDoc - so I hope it will be easy to understand.
 * 
 * NOTICE: This class was based upon class SubscriptionManagerFCPTest, which you can find in the unit tests. Please backport improvements.
 * [Its not possible to link it in the JavaDoc because the unit tests are not within the classpath.] 
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
	private static final int WOT_PING_DELAY = 30 * 1000;
	
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
	
	/** The function {@link KeepaliveLoop#run()} is periodically executed by {@link #mTicker}.
	 *  It sends a Ping to WOT and checks whether the existing subscriptions are OK. */
	private final KeepaliveLoop mKeepAliveLoop = new KeepaliveLoop();
	
	/** For scheduling threaded execution of {@link KeepaliveLoop#run()} on {@link #mKeepAliveLoop}. */
	private final TrivialTicker mTicker;
	
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

	/** The connection to the Web Of Trust plugin. Null if we are disconnected.
	 * volatile because {@link #connected()} uses it without synchronization. 
	 * MUST only be non-null if {@link #mClientState} equals {@link ClientState#Started} or {@link ClientState#StopRequested} */
	private volatile PluginTalker mConnection = null;
	
	/** A random {@link UUID} which identifies the connection to the Web Of Trust plugin. Randomized upon every reconnect. */
	private String mConnectionIdentifier = null;
	
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
	 * {@link FCPClientReferenceImplementation#subscribe(Class, SubscriptionSynchronizationHandler, SubscribedObjectChangedHandler)
	 */
	private enum SubscriptionType {
		/** @see IdentitiesSubscription */
		Identities(Identity.class),
		/** @see TrustsSubscription */
		Trusts(Trust.class),
		/** @see ScoresSubscription */
		Scores(Score.class);
		
		public Class<? extends Persistent> subscribedObjectType;
		
		SubscriptionType(Class<? extends Persistent> mySubscribedObjectType) {
			subscribedObjectType = mySubscribedObjectType;
		}
		
		public static SubscriptionType fromClass(Class<? extends Persistent> clazz) {
			if(clazz == Identity.class)
				return Identities;
			else if(clazz == Trust.class)
				return Trusts;
			else if(clazz == Score.class)
				return Scores;
			else
				throw new IllegalArgumentException("Not a valid SubscriptionType: " + clazz);
		}
	};
	
	/** Contains the {@link SubscriptionType}s the client wants to subscribe to. */
	private EnumSet<SubscriptionType> mSubscribeTo = EnumSet.noneOf(SubscriptionType.class);

	/**
	 * Each of these handlers is called at the begin of a subscription. The "synchronization" contains all objects in the WOT
	 * database of the type to which we subscribed.
	 * @see SubscriptionSynchronizationHandler
	 */
	private final EnumMap<SubscriptionType, SubscriptionSynchronizationHandler<? extends Persistent>> mSubscriptionSynchronizationHandlers
		= new EnumMap<SubscriptionType, SubscriptionSynchronizationHandler<? extends Persistent>>(SubscriptionType.class);
	
	/**
	 * Each of these handlers is called when an object changes to whose type the client is subscribed.
	 * @see SubscribedObjectChangedHandler
	 */
	private final EnumMap<SubscriptionType, SubscribedObjectChangedHandler<? extends Persistent>> mSubscribedObjectChangedHandlers 
		= new EnumMap<SubscriptionType, SubscribedObjectChangedHandler<? extends Persistent>>(SubscriptionType.class);
	
	/**
	 * The values are the IDs of the current subscriptions of the {@link SubscriptionType} which the key specifies.
	 * Null if the subscription for that type has not yet been filed.
	 * @see SubscriptionManager.Subscription#getID()
	 */
	private EnumMap<SubscriptionType, String> mSubscriptionIDs = new EnumMap<SubscriptionType, String>(SubscriptionType.class);

	
	/** Implements interface {@link FredPluginTalker}: Receives messages from WOT in a callback. */
	private final FCPMessageReceiver mFCPMessageReceiver = new FCPMessageReceiver();
	
	/** Maps the String name of WOT FCP messages to the handler which shall deal with them */
	private HashMap<String, FCPMessageHandler> mFCPMessageHandlers = new HashMap<String, FCPMessageHandler>();
	
	/** Constructs {@link Identity} objects from {@link SimpleFieldSet} data received via FCP. */
	private final IdentityParser mIdentityParser;
	
	/** Constructs {@link Trust} objects from {@link SimpleFieldSet} data received via FCP. */
	private final TrustParser mTrustParser;
	
	/** Constructs {@link Score} objects from {@link SimpleFieldSet} data received via FCP. */
	private final ScoreParser mScoreParser;
	
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
	 * Set this to true for debugging:
	 * Will enable dumping of the {@link SimpleFieldSet} FCP traffic to a text file. The filename will be:
	 * <code>this.getClass().getSimpleName() + " FCP dump.txt"</code>
	 */
	public final boolean mDumpFCPTraffic = false;
	
	/**
	 * Used for dumping the {@link SimpleFieldSet} FCP traffic to a text file for debugging.
	 * @see #mDumpFCPTraffic
	 */
	private final PrintWriter mFCPTrafficDump;


	public FCPClientReferenceImplementation(Map<String, Identity> myIdentityStorage,
			final PluginRespirator myPluginRespirator, final Executor myExecutor,
			final ConnectionStatusChangedHandler myConnectionStatusChangedHandler) {
		mIdentityStorage = myIdentityStorage;
		mPluginRespirator = myPluginRespirator;
		mTicker = new TrivialTicker(myExecutor);
		mRandom = mPluginRespirator.getNode().fastWeakRandom;
		
		mConnectionStatusChangedHandler = myConnectionStatusChangedHandler;
		
		final FCPMessageHandler[] handlers = {
				new FCPPongHandler(),
				new FCPSubscriptionSucceededHandler(),
				new FCPSubscriptionTerminatedHandler(),
				new FCPErrorHandler(),
				new FCPIdentitiesSynchronizationHandler(),
				new FCPTrustsSynchronizationHandler(),
				new FCPScoresSynchronizationHandler(),
				new FCPIdentityChangedNotificationHandler(),
				new FCPTrustChangedNotificationHandler(),
				new FCPScoreChangedNotificationHandler()
		};
		
		for(FCPMessageHandler handler : handlers)
			mFCPMessageHandlers.put(handler.getMessageName(), handler);
		
		// To prevent client-plugins which copy-paste this reference implementation from having to copy-paste the WebOfTrust class,
		// we use MockWebOfTrust as a replacement.
		final MockWebOfTrust wot = new MockWebOfTrust();
		mIdentityParser = new IdentityParser(wot);
		mTrustParser = new TrustParser(wot, mIdentityStorage);
		mScoreParser = new ScoreParser(wot, mIdentityStorage);
		
		if(mDumpFCPTraffic) {
			PrintWriter dump;
			try {
				dump = new PrintWriter(new BufferedWriter(new FileWriter(this.getClass().getSimpleName() + " FCP dump.txt")));
			} catch (IOException e) {
				Logger.error(this, "Failed to create debug FCP dump file",e);
				dump = null;
			}
			mFCPTrafficDump = dump;
		} else {
			mFCPTrafficDump = null;
		}
	}
	
	/**
	 * Tells the client to start connecting to WOT. Must be called at startup of your plugin.
	 * ATTENTION: If you override this, you must call <code>super.start()</code>.
	 * 
	 * Must be called after your child class is ready to process messages in the event handlers:
	 * - {@link #handleConnectionEstablished()}
	 * - {@link #handleConnectionLost()}
	 * 
	 * You will not receive any event callbacks before start was called.
	 */
	public synchronized void start() {
		Logger.normal(this, "Starting...");
		
		if(mClientState != ClientState.NotStarted)
			throw new IllegalStateException(mClientState.toString());
		
		mClientState = ClientState.Started;
		mKeepAliveLoop.scheduleKeepaliveLoopExecution();

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
	 */
	public final synchronized <T extends Persistent> void subscribe(final Class<T> type,
			final SubscriptionSynchronizationHandler<T> synchronizationHandler, SubscribedObjectChangedHandler<T> objectChangedHandler) {
		if(mClientState != ClientState.Started)
			throw new IllegalStateException(mClientState.toString());
		
		final SubscriptionType realType = SubscriptionType.fromClass(type);
		if(mSubscribeTo.contains(realType))
			throw new IllegalStateException("Subscription for that type exists already!");
		
		mSubscribeTo.add(SubscriptionType.fromClass(type));
		
		IfNull.thenThrow(synchronizationHandler);
		IfNull.thenThrow(objectChangedHandler);
		
		mSubscriptionSynchronizationHandlers.put(realType, synchronizationHandler);
		mSubscribedObjectChangedHandlers.put(realType, objectChangedHandler);
		
		mKeepAliveLoop.scheduleKeepaliveLoopExecution(0);
	}
	
	/**
	 * Call this to cancel a {@link Subscription}.
	 */
	public final synchronized <T extends Persistent> void unsubscribe(final Class<T> type) {
		if(mClientState != ClientState.Started)
			throw new IllegalStateException(mClientState.toString());
		
		mSubscribeTo.remove(SubscriptionType.fromClass(type));

		mKeepAliveLoop.scheduleKeepaliveLoopExecution(0);
	}

	/**
	 * The function {@link KeepaliveLoop#run()} is periodically executed by {@link FCPClientReferenceImplementation#mTicker}.
	 * It sends a Ping to WOT and checks whether the existing subscriptions are OK.
	 */
	private final class KeepaliveLoop implements PrioRunnable {
		/**
		 * Schedules execution of {@link #run()} via {@link #mTicker} after a delay:
		 * If connected to WOT, the delay will be randomized and roughly equal to {@link #WOT_PING_DELAY}.
		 * If not connected, it will be WOT_RECONNECT_DELAY.
		 */
		private void scheduleKeepaliveLoopExecution() {
			final long sleepTime = mConnection != null ? (WOT_PING_DELAY/2 + mRandom.nextInt(WOT_PING_DELAY)) : WOT_RECONNECT_DELAY;
			mTicker.queueTimedJob(this, "WOT " + this.getClass().getSimpleName(), sleepTime, false, true);
		}

		/**
		 * Schedules execution of {@link #run()} via {@link #mTicker} after a delay.
		 */
		private void scheduleKeepaliveLoopExecution(long sleepTime) {
			// Re-schedule because subscribe/unsubscribe need it to happen immediately.
			mTicker.rescheduleTimedJob(this, "WOT " + this.getClass().getSimpleName(), sleepTime);

			if(logMINOR) Logger.minor(this, "Sleeping for " + (sleepTime / (60*1000)) + " minutes.");
		}

		/**
		 * "Keepalive Loop": Checks whether we are connected to WOT. Connects to it if the connection is lost or did not exist yet.
		 * Then files all {@link Subscription}s.
		 * 
		 * Executed by {@link #mTicker} as scheduled periodically:
		 * - Every {@link #WOT_RECONNECT_DELAY} seconds if we have no connection to WOT
		 * - Every {@link #WOT_PING_DELAY} if we have a connection to WOT 
		 */
		@Override
		public final synchronized void run() { 
			if(logMINOR) Logger.minor(this, "Connection-checking loop running...");

			if(mClientState != ClientState.Started) {
				Logger.error(this, "Connection-checking loop executed in wrong ClientState: " + mClientState);
				return;
			}

			try {
				if(!connected() || pingTimedOut())
					connect();

				if(connected()) {
					fcp_Ping();
					checkSubscriptions();
				}
			} catch (Exception e) {
				Logger.error(this, "Error in connection-checking loop!", e);
				force_disconnect();
			} finally {
				scheduleKeepaliveLoopExecution();
			}

			if(logMINOR) Logger.minor(this, "Connection-checking finished.");
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
		
		if(mFCPTrafficDump != null) {
			mFCPTrafficDump.println("---------------- " + new Date() + " Connected. ---------------- ");
		}
		
		try {
			mConnectionIdentifier = UUID.randomUUID().toString();
			mConnection = mPluginRespirator.getPluginTalker(mFCPMessageReceiver, WOT_FCP_NAME, mConnectionIdentifier);
			mSubscriptionIDs.clear();
			Logger.normal(this, "Connected to WOT, identifier: " + mConnectionIdentifier);
			try {
				mConnectionStatusChangedHandler.handleConnectionStatusChanged(true);
			} catch(Throwable t) {
				Logger.warning(this, "ConnectionStatusChangedHandler.handleConnectionStatusChanged() threw up, please fix your handler!", t);
			}
		} catch(PluginNotFoundException e) {
			Logger.warning(this, "Cannot connect to WOT!");
			// force_disconnect() does this for us.
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
	 * Sends the given {@link SimpleFieldSet} via {@link #mConnection}.
	 * Attention: Does not synchronize, does not check whether {@link #mConnection} is null.
	 * 
	 * If {@link #mFCPTrafficDump} is non-null, the SFS is dumped to it.
	 */
	private void send(final SimpleFieldSet sfs) {
		mConnection.send(sfs, null);
		if(mFCPTrafficDump != null) {
			mFCPTrafficDump.println("---------------- " + new Date() + " Sent: ---------------- ");
			mFCPTrafficDump.println(sfs.toOrderedString());
		}
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
		
		if(mConnection != null) {
			for(SubscriptionType type : mSubscriptionIDs.keySet()) {
				fcp_Unsubscribe(type);
				// The "Unsubscribed" message would normally trigger the removal from the mSubscriptionIDs array but we cannot
				// receive it anymore after we are disconnected so we remove the ID ourselves
				mSubscriptionIDs.remove(type);
			}
			
			try {
				mConnectionStatusChangedHandler.handleConnectionStatusChanged(false);
			} catch(Throwable t) {
				Logger.warning(this, "ConnectionStatusChangedHandler.handleConnectionStatusChanged() threw up, please fix your handler!", t);
			}
		}
		
		// Notice: PluginTalker has no disconnection mechanism, we can must drop references to existing connections and then they will be GCed
		mConnection = null;
		mConnectionIdentifier = null;
		
		if(mFCPTrafficDump != null) {
			mFCPTrafficDump.println("---------------- " + new Date() + " Disconnected. ---------------- ");
			mFCPTrafficDump.flush();
		}
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
		// This is set to 0 by the onReply() handler (which receives the ping reply) when:
		// - we never sent a ping yet. Obviously we can't blame timeout on the client then
		// - whenever we received a pong which marked the ping as successful
		if(mLastPingSentDate == 0)
			return false;
		
		return (CurrentTimeUTC.getInMillis() - mLastPingSentDate) > WOT_PING_TIMEOUT_DELAY;
	}
	
	/**
	 * Sends a "Ping" FCP message to WOT. It will reply with a "Pong" message which is then handled by the {@link FCPPongHandler}.
	 * Used for checking whether the connection to WOT is alive.
	 */
	private synchronized void fcp_Ping() {
		if(logMINOR) Logger.minor(this, "fcp_Ping()");
		
		final SimpleFieldSet sfs = new SimpleFieldSet(true);
		sfs.putOverwrite("Message", "Ping");
		send(sfs);
		mLastPingSentDate = CurrentTimeUTC.getInMillis();
	}
	
	/**
	 * Iterates over the {@link SubscriptionType} enum values. Checks whether the client has requested to subscribe / unsubscribe any of them and does so. 
	 */
	private synchronized void checkSubscriptions() {
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
	 * Sends a "Subscribe" FCP message to WOT. It will reply with:
	 * - A synchronization message, which is handled by {@link FCPIdentitiesSynchronizationHandler} / {@link FCPTrustsSynchronizationHandler} / {@link FCPScoresSynchronizationHandler} - depending on the {@link SubscriptionType}.
	 * - A "Subscribed" message, which is handled by {@link FCPSubscriptionSucceededHandler}.
	 * 
	 * @param type The {@link SubscriptionType} to which you want to subscribe.
	 */
	private void fcp_Subscribe(final SubscriptionType type) {
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
	 */
	private void fcp_Unsubscribe(final SubscriptionType type) {
		Logger.normal(this, "fcp_Unsubscribe(): " + type);
		
		final SimpleFieldSet sfs = new SimpleFieldSet(true);
		sfs.putOverwrite("Message", "Unsubscribe");
		sfs.putOverwrite("SubscriptionID", mSubscriptionIDs.get(type));
		send(sfs);
	}

	/**
	 * Receives FCP messages from WOT:
	 * - In reply to messages sent to it via {@link PluginTalker}
	 * - As events happen via event-{@link Notification}s
	 */
	private class FCPMessageReceiver implements FredPluginTalker {
		/**
		 * Called by Freenet when it receives a FCP message from WOT.
		 * 
		 * The function will determine which {@link FCPMessageHandler} is responsible for the message type and call its
		 * {@link FCPMessageHandler#handle(SimpleFieldSet, Bucket).
		 */
		@Override
		public synchronized final void onReply(final String pluginname, final String indentifier, final SimpleFieldSet params, final Bucket data) {
			if(mFCPTrafficDump != null) {
				mFCPTrafficDump.println("---------------- " + new Date() + " Received: ---------------- ");
				mFCPTrafficDump.println(params.toOrderedString());
			}
			
			if(!WOT_FCP_NAME.equals(pluginname))
				throw new RuntimeException("Plugin is not supposed to talk to us: " + pluginname);

			// Check whether we are actually connected. If we are not connected, we must not handle FCP messages.
			// We must also check whether the identifier of the connection matches. If it does not, the message belongs to an old connection.
			// We do NOT have to check mClientState: mConnection must only be non-null in states where it is acceptable.
			if(mConnection == null || !mConnectionIdentifier.equals(indentifier)) {
				final String state = "connected==" + (mConnection!=null) + "; identifier==" + indentifier
						+ "ClientState==" + mClientState + "; SimpleFieldSet: " + params;

				Logger.error(this, "Received out of band message, maybe because we reconnected and the old server is still alive? " + state);
				// There might be a dangling subscription for which we are still receiving event notifications.
				// WOT terminates subscriptions automatically once their failure counter reaches a certain limit.
				// For allowing WOT to notice the failure, we must throw a RuntimeException().
				throw new RuntimeException("Out of band message: You are not connected or your identifier mismatches: " + state);
			}

			final String messageString = params.get("Message");
			final FCPMessageHandler handler = mFCPMessageHandlers.get(messageString);

			if(handler == null) {
				Logger.warning(this, "Unknown message type: " + messageString + "; SimpleFieldSet: " + params);
				return;
			}

			if(logMINOR) Logger.minor(this, "Handling message '" + messageString + "' with " + handler + " ...");
			try {
				handler.handle(params, data);
			} catch(ProcessingFailedException e) {
				Logger.error(FCPClientReferenceImplementation.this, "Message handler failed and requested throwing to WOT, doing so: " + handler, e);
				throw new RuntimeException(e);
			} finally {
				if(logMINOR) Logger.minor(this, "Handling message finished.");
			}
		}

	}
	/**
	 * Each FCP message sent by WOT contains a "Message" field in its {@link SimpleFieldSet}. For each value of "Message",
	 * a {@link FCPMessageHandler} implementation must exist.
	 * 
	 * Upon reception of a message, {@link FCPClientReferenceImplementation#onReply(String, String, SimpleFieldSet, Bucket) calls
	 * {@link FCPMessageHandler#handle(SimpleFieldSet, Bucket)} of the {@link FCPMessageHandler} which is responsible for it. 
	 */
	private interface FCPMessageHandler {
		/**
		 * This function shall the value of the {@link SimpleFieldSet} "Message" field this handler belongs to with.
		 */
		public String getMessageName();
		
		/**
		 * @throws ProcessingFailedException May be thrown if you want {@link FCPClientReferenceImplementation#onReply(String, String, SimpleFieldSet, Bucket)}
		 * to signal to WOT that processing failed. This only is suitable for handlers of event-notifications:
		 * WOT will send the event-notifications synchronously and therefore notice if they failed. It will resend them for a certain amount
		 * of retries then.
		 */
		public void handle(final SimpleFieldSet sfs, final Bucket data) throws ProcessingFailedException;
	}
	
	/**
	 * @see SubscriptionSynchronizationHandler
	 * @see SubscribedObjectChangedHandler
	 */
	public final class ProcessingFailedException extends Exception {
		public ProcessingFailedException(Throwable t) {
			super(t);
		}

		private static final long serialVersionUID = 1L;
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
		public void handle(final SimpleFieldSet sfs, final Bucket data) {
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
		public void handle(SimpleFieldSet sfs, Bucket data) {
	    	final String id = sfs.get("SubscriptionID");
	    	final String to = sfs.get("To");
	    	
	    	assert(id != null && id.length() > 0);
	    	assert(to != null);
	    	
	    	final SubscriptionType type = SubscriptionType.valueOf(to);
	    	assert !mSubscriptionIDs.containsKey(type) : "Subscription should not exist already";
	    	mSubscriptionIDs.put(type, id);
	    	
	    	// checkSubscriptions() only files one subscription at a time (see its code for an explanation).
	    	// Therefore, after subscription has succeeded, we need to schedule the KeepaliveLoop (which is run()) to be executed again
	    	// soon so it calls checkSubscriptions() to file the following subscriptions.
	    	mKeepAliveLoop.scheduleKeepaliveLoopExecution(0);
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
		public void handle(SimpleFieldSet sfs, Bucket data) {
	    	final String id = sfs.get("SubscriptionID");
	    	final String from = sfs.get("From");
	    	
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
	    		notifyAll();
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
		public void handle(SimpleFieldSet sfs, Bucket data) throws ProcessingFailedException {
			final String description = sfs.get("Description");
			
			if(description.equals("plugins.WebOfTrust.SubscriptionManager$SubscriptionExistsAlreadyException")) {
		    	// checkSubscriptions() only files one subscription at a time to guarantee proper order of synchronization:
		    	// Trust objects reference identity objects, so we cannot create them if we didn't get to know the identities first.
		    	// So because it only files one at a time, after subscription has succeeded, the KeepAliveLoop will be scheduled for
				// execution to file the next one.
				// If subscribing succeed early enough and the KeepAliveLoop executes due to its normal period, it will try to file
				// the subscription a second time and this condition happens:
				Logger.warning(this, "Subscription exists already: To=" + sfs.get("To") + "; SubscriptionID=" + sfs.get("SubscriptionID"));
			} else {
				Logger.error(this, "Unknown FCP error: " + description);
			}
		}
	}
	
	
	/**
	 * Since we let the implementing child class of the abstract FCPClientReferenceImplementation handle the events, the handler might throw.
	 * In that case we need to gracefully tell WOT about that: In case of {@link Subscription}'s event {@link Notification}s, it will re-send them then. 
	 */
	private abstract class MaybeFailingFCPMessageHandler implements FCPMessageHandler {
		public void handle(final SimpleFieldSet sfs, final Bucket data) throws ProcessingFailedException {
			try {	
				handle_MaybeFailing(sfs, data);
			} catch(Throwable t) {
				throw new ProcessingFailedException(t); 
			}
		}
		
		abstract void handle_MaybeFailing(final SimpleFieldSet sfs, final Bucket data) throws Throwable;
	}
	
	/**
	 * Handles the "Identities" message which we receive in reply to {@link FCPClientReferenceImplementation#fcp_Subscribe(SubscriptionType)}
	 * with {@link SubscriptionType#Identities}.
	 * 
	 * Parses the contained set of all WOT {@link Identity}s & passes it to the event handler 
	 * {@link FCPClientReferenceImplementation#handleIdentitiesSynchronization(Collection)}.
	 */
	private final class FCPIdentitiesSynchronizationHandler extends MaybeFailingFCPMessageHandler {
		@Override
		public String getMessageName() {
			return "Identities";
		}
		
		@SuppressWarnings("unchecked")
		@Override
		public void handle_MaybeFailing(final SimpleFieldSet sfs, final Bucket data) throws MalformedURLException, FSParseException, InvalidParameterException, ProcessingFailedException {
			((SubscriptionSynchronizationHandler<Identity>)mSubscriptionSynchronizationHandlers.get(SubscriptionType.Identities))
				.handleSubscriptionSynchronization(mIdentityParser.parseSynchronization(sfs));
		}
	}

	/**
	 * Handles the "Trusts" message which we receive in reply to {@link FCPClientReferenceImplementation#fcp_Subscribe(SubscriptionType)}
	 * with {@link SubscriptionType#Trusts}.
	 * 
	 * Parses the contained set of all WOT {@link Trust}s & passes it to the event handler 
	 * {@link FCPClientReferenceImplementation#handleTrustsSynchronization(Collection)}.
	 */
	private final class FCPTrustsSynchronizationHandler extends MaybeFailingFCPMessageHandler {
		@Override
		public String getMessageName() {
			return "Trusts";
		}

		@SuppressWarnings("unchecked")
		@Override
		public void handle_MaybeFailing(SimpleFieldSet sfs, Bucket data) throws MalformedURLException, FSParseException, InvalidParameterException, ProcessingFailedException {
			((SubscriptionSynchronizationHandler<Trust>)mSubscriptionSynchronizationHandlers.get(SubscriptionType.Trusts))
					.handleSubscriptionSynchronization(mTrustParser.parseSynchronization(sfs));
		}
	}

	/**
	 * Handles the "Scores" message which we receive in reply to {@link FCPClientReferenceImplementation#fcp_Subscribe(SubscriptionType)}
	 * with {@link SubscriptionType#Scores}.
	 * 
	 * Parses the contained set of all WOT {@link Score}s & passes it to the event handler 
	 * {@link FCPClientReferenceImplementation#handleScoresSynchronization(Collection)}.
	 */
	private final class FCPScoresSynchronizationHandler extends MaybeFailingFCPMessageHandler {
		@Override
		public String getMessageName() {
			return "Scores";
		}

		@SuppressWarnings("unchecked")
		@Override
		public void handle_MaybeFailing(SimpleFieldSet sfs, Bucket data) throws MalformedURLException, FSParseException, InvalidParameterException, ProcessingFailedException {
			((SubscriptionSynchronizationHandler<Score>)mSubscriptionSynchronizationHandlers.get(SubscriptionType.Scores))
				.handleSubscriptionSynchronization(mScoreParser.parseSynchronization(sfs));
		}
	}

	/**
	 * Handles the "IdentityChangedNotification" message which WOT sends when an {@link Identity} or {@link OwnIdentity} was changed, added or deleted.
	 * This will be send if we are subscribed to {@link SubscriptionType#Identities}.
	 * 
	 * Parses the contained {@link Identity} & passes it to the event handler 
	 * {@link FCPClientReferenceImplementation#handleIdentityChangedNotification(Identity, Identity)}.
	 */
	private final class FCPIdentityChangedNotificationHandler extends MaybeFailingFCPMessageHandler {
		@Override
		public String getMessageName() {
			return "IdentityChangedNotification";
		}
		
		@SuppressWarnings("unchecked")
		@Override
		public void handle_MaybeFailing(SimpleFieldSet sfs, Bucket data) throws MalformedURLException, FSParseException, InvalidParameterException, ProcessingFailedException {
			((SubscribedObjectChangedHandler<Identity>)mSubscribedObjectChangedHandlers.get(SubscriptionType.Identities))
				.handleSubscribedObjectChanged(mIdentityParser.parseNotification(sfs));
		}
	}
	
	/**
	 * Handles the "TrustChangedNotification" message which WOT sends when a {@link Trust} was changed, added or deleted.
	 * This will be send if we are subscribed to {@link SubscriptionType#Trusts}.
	 * 
	 * Parses the contained {@link Trust} & passes it to the event handler 
	 * {@link FCPClientReferenceImplementation#handleTrustChangedNotification(Trust, Trust)}.
	 */
	private final class FCPTrustChangedNotificationHandler extends MaybeFailingFCPMessageHandler  {
		@Override
		public String getMessageName() {
			return "TrustChangedNotification";
		}
		
		@SuppressWarnings("unchecked")
		@Override
		public void handle_MaybeFailing(SimpleFieldSet sfs, Bucket data) throws MalformedURLException, FSParseException, InvalidParameterException, ProcessingFailedException {
			((SubscribedObjectChangedHandler<Trust>)mSubscribedObjectChangedHandlers.get(SubscriptionType.Trusts))
				.handleSubscribedObjectChanged(mTrustParser.parseNotification(sfs));
		}
	}

	/**
	 * Handles the "ScoreChangedNotification" message which WOT sends when a {@link Score} was changed, added or deleted.
	 * This will be send if we are subscribed to {@link SubscriptionType#Scores}.
	 * 
	 * Parses the contained {@link Score} & passes it to the event handler 
	 * {@link FCPClientReferenceImplementation#handleScoreChangedNotification(Score, Score)}.
	 */
	private final class FCPScoreChangedNotificationHandler extends MaybeFailingFCPMessageHandler {
		@Override
		public String getMessageName() {
			return "ScoreChangedNotification";
		}
		
		@SuppressWarnings("unchecked")
		@Override
		public void handle_MaybeFailing(SimpleFieldSet sfs, Bucket data) throws MalformedURLException, FSParseException, InvalidParameterException, ProcessingFailedException {
			((SubscribedObjectChangedHandler<Score>)mSubscribedObjectChangedHandlers.get(SubscriptionType.Scores))
				.handleSubscribedObjectChanged(mScoreParser.parseNotification(sfs));
		}
	}

	/**
	 * Represents the data of a {@link SubscriptionManager.Notification}
	 */
	public static final class ChangeSet<CT extends Persistent> {
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
	 * Baseclass for parsing synchronization and notification messages which WOT sends:
	 * - Synchronization messages are at the beginning of a {@link Subscription} and contain all data in the WOT database of the subscribed
	 * type such as all {@link Identity}s / all {@link Trust}s / all {@link Score}s.
	 * - {@link Notification} messages are sent if an {@link Identity} etc. has changed and contain the old / new version of it.
	 * 
	 * The implementing child classes only have to implement parsing of a single Identity/Trust/Score object. The format of the 
	 * messages which contain multiple of them is a superset so the single-element parser can be used.
	 */
	public static abstract class FCPParser<T extends Persistent> {
		
		protected final WebOfTrustInterface mWoT;
		
		public FCPParser(final WebOfTrustInterface myWebOfTrust) {
			mWoT = myWebOfTrust;
		}
		
		public ArrayList<T> parseSynchronization(final SimpleFieldSet wholeSfs) throws FSParseException, MalformedURLException, InvalidParameterException {
			final SimpleFieldSet sfs = getOwnSubset(wholeSfs);
			final int amount = sfs.getInt("Amount");
			final ArrayList<T> result = new ArrayList<T>(amount+1);
			for(int i=0; i < amount; ++i) {
				result.add(parseSingle(sfs, i));
			}
			return result;
		}

		public ChangeSet<T> parseNotification(final SimpleFieldSet notification) throws MalformedURLException, FSParseException, InvalidParameterException {
			final SimpleFieldSet beforeChange = getOwnSubset(notification.subset("BeforeChange"));
			final SimpleFieldSet afterChange = getOwnSubset(notification.subset("AfterChange"));
			
			return new ChangeSet<T>(parseSingle(beforeChange, 0), parseSingle(afterChange, 0));
		}
		
		/** For example if this was an {@link IdentityParser}, if the input contained "Identities.*", it would return all * fields without the prefix. */
		abstract protected SimpleFieldSet getOwnSubset(final SimpleFieldSet sfs) throws FSParseException;
		
		abstract protected T parseSingle(SimpleFieldSet sfs, int index) throws FSParseException, MalformedURLException, InvalidParameterException;
	
	}
	
	/**
	 * Parser for FCP messages which describe an {@link Identity} or {@link OwnIdentity} object.
	 */
	public static final class IdentityParser extends FCPParser<Identity> {

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
	    	
			if(type.equals("Inexistent"))
	    		return null;
	    	
	        final String nickname = sfs.get("Nickname");
	        final String requestURI = sfs.get("RequestURI");
	    	final String insertURI = sfs.get("InsertURI");
	    	final boolean doesPublishTrustList = sfs.getBoolean("PublishesTrustList");
	        final String id = sfs.get("ID"); 
	 	
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

	        return identity;
		}
	}

	/**
	 * Parser for FCP messages which describe a {@link Trust} object.
	 */
	public static final class TrustParser extends FCPParser<Trust> {

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
			
	    	if(sfs.get("Value").equals("Inexistent"))
	    		return null;
	    	
			final String trusterID = sfs.get("Truster");
			final String trusteeID = sfs.get("Trustee");
			final byte value = sfs.getByte("Value");
			final String comment = sfs.get("Comment");
			final long trusterEdition = sfs.getLong("TrusterEdition");
			
			final Trust trust = new Trust(mWoT, mIdentities.get(trusterID), mIdentities.get(trusteeID), value, comment);
			trust.forceSetTrusterEdition(trusterEdition);
			
			return trust;
		}
		
	}

	/**
	 * Parser for FCP messages which describe a {@link Score} object.
	 */
	public static final class ScoreParser extends FCPParser<Score> {
		
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
			
	    	if(sfs.get("Value").equals("Inexistent"))
	    		return null;
	    	
			final String trusterID = sfs.get("Truster");
			final String trusteeID = sfs.get("Trustee");
			final int capacity = sfs.getInt("Capacity");
			final int rank = sfs.getInt("Rank");
			final int value = sfs.getInt("Value");
			
			return new Score(mWoT, (OwnIdentity)mIdentities.get(trusterID), mIdentities.get(trusteeID), value, rank, capacity);
		}
		
	}

	public interface ConnectionStatusChangedHandler {
		/**
		 * Called when the client has connected to WOT successfully or the connection was lost. 
		 * This handler should update your user interface remove or show a "Please install the Web Of Trust plugin!" warning.
		 * If this handler was not called yet, you should assume that there is no connection and display the warning as well. 
		 * 
		 * ATTENTION: You do NOT have to call {@link FCPClientReferenceImplementation#subscribe(Class, SubscriptionSynchronizationHandler, SubscribedObjectChangedHandler)}
		 * in this handler! Subscriptions will be filed automatically by the client whenever the connection is established.
		 * It will also automatically reconnect if the connection is lost.
		 * ATTENTION: The client will automatically try to reconnect, you do NOT have to call {@link FCPClientReferenceImplementation#start()}
		 * or anything else in this handler!
		 */
		void handleConnectionStatusChanged(boolean connected);
	}
	
	public interface SubscriptionSynchronizationHandler<T extends Persistent> {
		/**
		 * Called very soon after you have subscribed via {@link FCPClientReferenceImplementation#subscribe(Class, SubscriptionSynchronizationHandler, SubscribedObjectChangedHandler)}
		 * The type T matches the Class parameter of the above subscribe function.
		 * The passed {@link Collection} contains ALL objects in the WOT database of whose type T you have subscribed to:
		 * - For {@link Identity}, all {@link Identity} and {@link OwnIdentity} objects in the WOT database.
		 * - For {@link Trust}, all {@link Trust} objects in the WOT database.
		 * - For {@link Score}, all {@link Score} objects in the WOT database.
		 * You should store any of them which you need.
		 * 
		 * WOT sends ALL objects to this handler because this will cut down future traffic very much: For example, if an {@link Identity}
		 * changes, WOT will only have to send the new version of it for allowing you to make your database completely up-to-date again.
		 * This means that this handler is only called once at the beginning of a {@link Subscription}, all changes after that will trigger
		 * a {@link SubscribedObjectChangedHandler} instead.
		 * 
		 * @throws ProcessingFailedException You are free to throw this. The failure of the handler will be signaled to WOT. It will cause the
		 * subscription to fail. The client will automatically retry subscribing after a typical delay of roughly
		 * {@link FCPClientReferenceImplementation#WOT_PING_DELAY}. You can use this mechanism for programming your client in a transactional
		 * style: If anything in the transaction which processes this handler fails, roll it back and make the handler throw.
		 * You can then expect to receive the same call again after the delay and hope that the transaction will succeed the next time.
		 */
		void handleSubscriptionSynchronization(Collection<T> allObjects) throws ProcessingFailedException;
	}
	
	public interface SubscribedObjectChangedHandler<T extends Persistent> {
		/**
		 * Called if an object is changed/added/deleted to whose class you subscribed to via @link FCPClientReferenceImplementation#subscribe(Class, SubscriptionSynchronizationHandler, SubscribedObjectChangedHandler)}.
		 * The type T matches the Class parameter of the above subscribe function.
		 * You will receive notifications about changed objects for the given values of T:
		 * - For {@link Identity}, changed {@link Identity} and {@link OwnIdentity} objects in the WOT database.
		 * - For {@link Trust}, changed {@link Trust} objects in the WOT database.
		 * - For {@link Score}, changed {@link Score} objects in the WOT database.
		 * The passed {@link ChangeSet} contains the version of the object  before the change and after the change.
		 * 
		 * ATTENTION: The type of an {@link Identity} can change from {@link OwnIdentity} to {@link Identity} or vice versa.
		 * This will also trigger a call to this event handler.
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
	 * ATTENTION: If you override this, you must call <code>super.stop()</code>!
	 */
	public synchronized void stop() {
		Logger.normal(this, "stop() ...");
		
		if(mClientState != ClientState.Started) {
			Logger.warning(this, "stop(): Not even started, current state = " + mClientState);
			return;
		}
		
		// The purpose of having this state is so we can wait for the confirmations of fcp_Unsubscribe() to arrive from WOT.
		mClientState = ClientState.StopRequested;
		
		// Prevent run() from executing again. It cannot be running right now because this function is synchronized
		mTicker.shutdown();
		
		// Call fcp_Unsubscribe() on any remaining subscriptions and wait() for the "Unsubscribe" messages to arrive
		if(!mSubscriptionIDs.isEmpty() && mConnection != null) {
			for(SubscriptionType type : mSubscriptionIDs.keySet()) {
				fcp_Unsubscribe(type);
				// The handler for "Unsubscribed" messages will notifyAll() once there are no more subscriptions 
			}

			Logger.normal(this, "stop(): Waiting for fcp_Unsubscribe() calls to be confirmed...");
			try {
				// Releases the lock on this object - which is why we needed to set mClientState = ClientState.StopRequested:
				// To prevent new subscriptions from happening in between
				wait(SHUTDOWN_UNSUBSCRIBE_TIMEOUT);
			} catch (InterruptedException e) {
				Thread.interrupted();
			}			
		}
		
		if(!mSubscriptionIDs.isEmpty()) {
			Logger.warning(this, "stop(): Waiting for fcp_Unsubscribe() calls timed out, now forcing disconnect."
					+ " If log messages about out-of-band messages follow, you can ignore them.");
		}
		
		// We call force_disconnect() even if shutdown worked properly because there is no non-forced function and it won't force
		// anything if all subscriptions have been terminated properly before.
		force_disconnect();
		
		mClientState = ClientState.Stopped;
		
		Logger.normal(this, "stop() finished.");
	}

}
