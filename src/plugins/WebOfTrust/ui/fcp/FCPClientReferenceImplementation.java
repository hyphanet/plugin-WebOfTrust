/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.ui.fcp;

import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import plugins.WebOfTrust.Identity;
import plugins.WebOfTrust.Identity.FetchState;
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
import plugins.WebOfTrust.WebOfTrust;
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
public abstract class FCPClientReferenceImplementation implements PrioRunnable, FredPluginTalker {
	
	/** This is the core class name of the Web Of Trust plugin. Used to connect to it via FCP */
	private static final String WOT_FCP_NAME = "plugins.WebOfTrust.WebOfTrust";

	/** The amount of milliseconds between each attempt to connect to the WoT plugin */
	private static final int WOT_RECONNECT_DELAY = 1 * 1000;
	
	/** The amount of milliseconds between sending pings to WOT to see if we are still connected */
	private static final int WOT_PING_DELAY = 30 * 1000;
	
	/** The amount of milliseconds after which assume the connection to WOT to be dead and try to reconnect */
	private static final int WOT_PING_TIMEOUT_DELAY = 2*WOT_PING_DELAY;
	
	/**
	 * The implementing child class provides this Map. It is used for obtaining the {@link Identity} objects which are used for
	 * constructing {@link Trust} and {@link Score} objects which are passed to its handlers.
	 */
	private final Map<String, Identity> mIdentityStorage;
	
	/** The interface for creating connections to WOT via FCP. Provided by the Freenet node */
	private final PluginRespirator mPluginRespirator;
	
	/** For scheduling threaded execution of {@link #run()}. */
	private final TrivialTicker mTicker;
	
	/** For randomizing the delay between periodic execution of {@link #run()} */
	private final Random mRandom;

	/** The connection to the Web Of Trust plugin. Null if we are disconnected.  */
	private PluginTalker mConnection = null;
	
	/** A random {@link UUID} which identifies the connection to the Web Of Trust plugin. Randomized upon every reconnect. */
	private String mConnectionIdentifier = null;
	
	/** The value of {@link CurrentTimeUTC#get()} when we last sent a ping to the Web Of Trust plugin. */
	private long mLastPingSentDate = 0;
	
	/**
	 * All types of {@link Subscription}. The names match the FCP messages literally and are used for lookup, please do not change them.
	 * Further, the subscriptions are filed in the order which they appear hear: {@link Trust}/{@link Score} objects reference {@link Identity}
	 * objects and therefore cannot be created if the {@link Identity} object is not known yet. This would be the case if
	 * Trusts/Scores subscriptions were filed before the Identities subscription.
	 */
	public enum SubscriptionType {
		/** @see IdentitiesSubscription */
		Identities,
		/** @see TrustsSubscription */
		Trusts,
		/** @see ScoresSubscription */
		Scores
	};
	
	/** Contains the {@link SubscriptionType}s the client wants to subscribe to. */
	private EnumSet<SubscriptionType> mSubscribeTo = EnumSet.noneOf(SubscriptionType.class);

	/**
	 * The values are the IDs of the current subscriptions of the {@link SubscriptionType} which the key specifies.
	 * Null if the subscription for that type has not yet been filed.
	 * @see SubscriptionManager.Subscription#getID()
	 */
	private EnumMap<SubscriptionType, String> mSubscriptionIDs = new EnumMap<SubscriptionType, String>(SubscriptionType.class);
	
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
	 * FIXME: Don't require a WebOfTrust because the client which copypastes this class won't have one.
	 * We merely need it for feeding {@link Persistent#initializeTransient(WebOfTrust)} (indirectly through constructors of Identity/Trust/Score).
	 * We could deal with this by implementing a class MockWebOfTrust which only provides whats needed for initializeTransient to work.
	 */
	public FCPClientReferenceImplementation(final WebOfTrust myMockWebOfTrust, Map<String, Identity> myIdentityStorage, final PluginRespirator myPluginRespirator, final Executor myExecutor) {
		mIdentityStorage = myIdentityStorage;
		mPluginRespirator = myPluginRespirator;
		mTicker = new TrivialTicker(myExecutor);
		mRandom = mPluginRespirator.getNode().fastWeakRandom;
		
		final FCPMessageHandler[] handlers = {
				new PongHandler(),
				new SubscriptionSucceededHandler(),
				new SubscriptionTerminatedHandler(),
				new ErrorHandler(),
				new IdentitiesSynchronizationHandler(),
				new TrustsSynchronizationHandler(),
				new ScoresSynchronizationHandler(),
				new IdentityChangedNotificationHandler(),
				new TrustChangedNotificationHandler(),
				new ScoreChangedNotificationHandler()
		};
		
		for(FCPMessageHandler handler : handlers)
			mFCPMessageHandlers.put(handler.getMessageName(), handler);
		
		mIdentityParser = new IdentityParser(myMockWebOfTrust);
		mTrustParser = new TrustParser(myMockWebOfTrust, mIdentityStorage);
		mScoreParser = new ScoreParser(myMockWebOfTrust, mIdentityStorage);
	}
	
	/**
	 * Tells the client to start connecting to WOT.
	 * 
	 * Must be called after your child class is ready to process messages in the event handlers:
	 * - {@link #handleConnectionEstablished()}
	 * - {@link #handleConnectionLost()}
	 * 
	 * You will not receive any event callbacks before start was called.
	 */
	public void start() {
		Logger.normal(this, "Starting...");
		
		scheduleKeepaliveLoopExecution();

		Logger.normal(this, "Started.");
	}
	
	/**
	 * Call this to file a {@link Subscription}.
	 * You will receive the following callbacks while being subscribed - depending on the {@link SubscriptionType}:
	 * - {@link #handleIdentitiesSynchronization(Collection)}
	 * - {@link #handleIdentityChangedNotification(Identity, Identity)}
	 * - {@link #handleTrustsSynchronization(Collection)}
	 * - {@link #handleTrustChangedNotification(Trust, Trust)}
	 * - {@link #handleScoresSynchronization(Collection)}
	 * - {@link #handleScoreChangedNotification(Score, Score)}
	 * 
	 * ATTENTION: If you subscribe to multiple {@link SubscriptionType}s, you must call this function in the same order as they appear in the
	 * enum: For example {@link Trust} objects which you will receive from {@link SubscriptionType#Trusts} reference {@link Identity} objects
	 * Therefore your event handler cannot create them if you don't subscribe to {@link SubscriptionType#Identities} first.
	 */
	public synchronized void subscribe(final SubscriptionType type) {
		mSubscribeTo.add(type);
		scheduleKeepaliveLoopExecution(0);
	}
	
	/**
	 * Call this to cancel a {@link Subscription}.
	 */
	public synchronized void unsubscribe(final SubscriptionType type) {
		mSubscribeTo.remove(type);

		scheduleKeepaliveLoopExecution(0);
	}

	/**
	 * Schedules execution of {@link #run()} via {@link #mTicker} after a delay:
	 * If connected to WOT, the delay will be randomized and roughly equal to {@link #WOT_PING_DELAY}.
	 * If not connected, it will be WOT_RECONNECT_DELAY.
	 */
	private void scheduleKeepaliveLoopExecution() {
		scheduleKeepaliveLoopExecution(mConnection != null ? (WOT_PING_DELAY/2 + mRandom.nextInt(WOT_PING_DELAY)) : WOT_RECONNECT_DELAY);
	}
	
	/**
	 * Schedules execution of {@link #run()} via {@link #mTicker} after a delay.
	 */
	private void scheduleKeepaliveLoopExecution(long sleepTime) {
		mTicker.queueTimedJob(this, "WOT " + this.getClass().getSimpleName(), sleepTime, false, true);
		
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
	public synchronized void run() { 
		if(logMINOR) Logger.minor(this, "Connection-checking loop running...");

		try {
			if(!connected() || pingTimedOut())
				connect();
			
			if(connected()) {
				fcp_Ping();
				checkSubscriptions();
			}
		} catch (Exception e) {
			Logger.error(this, "Error in connection-checking loop!", e);
			disconnect();
		} finally {
			scheduleKeepaliveLoopExecution();
		}
		
		if(logMINOR) Logger.minor(this, "Connection-checking finished.");
	}

	/**
	 * Tries to connect to WOT.
	 * Safe to be called if a connection already exists - it will be replaced with a new one then.
	 */
	private synchronized void connect() {
		disconnect();
		
		Logger.normal(this, "connect()");
		
		try {
			mConnectionIdentifier = UUID.randomUUID().toString();
			mConnection = mPluginRespirator.getPluginTalker(this, WOT_FCP_NAME, mConnectionIdentifier);
			Logger.normal(this, "Connected to WOT, identifier: " + mConnectionIdentifier);
			handleConnectionEstablished();
		} catch(PluginNotFoundException e) {
			Logger.warning(this, "Cannot connect to WOT!");
			handleConnectionLost();
		}
	}
	
	/**
	 * Nulls the connection to WOT.
	 * Unsubscribes all existing {@link Subscription}s so WOT does not not maintain them anymore. Does NOT remove them from {@link #mSubscribeTo}:
	 * The purpose of this is to make connections transparent to the child event handler. Once it has requested to be subscribed, it does
	 * not have to care about whether a connection exists or not. This class will automatically reconnect if the connection is lost and
	 * file the subscriptions again.
	 */
	private synchronized void disconnect() {
		Logger.normal(this, "disconnect()");
		
		if(mConnection != null) {
			for(SubscriptionType type : mSubscriptionIDs.keySet()) {
				fcp_Unsubscribe(type);
				// The "Unsubscribed" message would normally trigger the removal from the mSubscriptionIDs array but we cannot
				// receive it anymore after we are disconnected so we remove the ID ourselves
				// FIXME: Check whether it still arrives at onReply() anyway. If it does so, it will trigger error logging -
				// we then should find a way to prevent that.
				mSubscriptionIDs.remove(type);
			}
		}
		
		// Notice: PluginTalker has no disconnection mechanism, we can must drop references to existing connections and then they will be GCed
		mConnection = null;
		mConnectionIdentifier = null;
	}
	
	/**
	 * @return True if we are connected to WOT, false if not. Does NOT tell whether the Subscriptions were filed yet.
	 */
	public boolean connected()  {
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
	
	
	private synchronized void fcp_Ping() {
		if(logMINOR) Logger.minor(this, "fcp_Ping()");
		
		final SimpleFieldSet sfs = new SimpleFieldSet(true);
		sfs.putOverwrite("Message", "Ping");
		mConnection.send(sfs, null);
		mLastPingSentDate = CurrentTimeUTC.getInMillis();
	}
	
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
	
	private void fcp_Subscribe(final SubscriptionType type) {
		Logger.normal(this, "fcp_Subscribe(): " + type);
		
		final SimpleFieldSet sfs = new SimpleFieldSet(true);
		sfs.putOverwrite("Message", "Subscribe");
		sfs.putOverwrite("To", type.toString());
		mConnection.send(sfs, null);
	}
	
	private void fcp_Unsubscribe(final SubscriptionType type) {
		Logger.normal(this, "fcp_Unsubscribe(): " + type);
		
		final SimpleFieldSet sfs = new SimpleFieldSet(true);
		sfs.putOverwrite("Message", "Unsubscribe");
		sfs.putOverwrite("SubscriptionID", mSubscriptionIDs.get(type));
		mConnection.send(sfs, null);
	}

	@Override
	public synchronized final void onReply(final String pluginname, final String indentifier, final SimpleFieldSet params, final Bucket data) {
		if(!WOT_FCP_NAME.equals(pluginname))
			throw new RuntimeException("Plugin is not supposed to talk to us: " + pluginname);
		
		if(mConnection == null || !mConnectionIdentifier.equals(indentifier)) {
			Logger.error(this, "Received out of band message, maybe because we reconnected and the old server is still alive? Identifier: " + indentifier + "; SimpleFieldSet: " + params);
			// FIXME: Do something which makes WOT cancel maybe-existing subscriptions so it doesn't keep collecting data for them.
			return;
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
	 * @see FCPMessageHandler#handle(SimpleFieldSet, Bucket)
	 */
	public final class ProcessingFailedException extends Exception {
		public ProcessingFailedException(Throwable t) {
			super(t);
		}

		private static final long serialVersionUID = 1L;
	}
	
	private final class PongHandler implements FCPMessageHandler {
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
	
	private final class SubscriptionSucceededHandler implements FCPMessageHandler {
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
	    	scheduleKeepaliveLoopExecution(0);
		}
	}
	
	private final class SubscriptionTerminatedHandler implements FCPMessageHandler {
		@Override
		public String getMessageName() {
			return "Unubscribed";
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
		}
	}
	
	private final class ErrorHandler implements FCPMessageHandler {
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
	
	private final class IdentitiesSynchronizationHandler extends MaybeFailingFCPMessageHandler {
		@Override
		public String getMessageName() {
			return "Identities";
		}
		
		@Override
		public void handle_MaybeFailing(final SimpleFieldSet sfs, final Bucket data) throws MalformedURLException, FSParseException, InvalidParameterException {
			handleIdentitiesSynchronization(mIdentityParser.parseSynchronization(sfs));
		}
	}
	
	private final class TrustsSynchronizationHandler extends MaybeFailingFCPMessageHandler {
		@Override
		public String getMessageName() {
			return "Trusts";
		}

		@Override
		public void handle_MaybeFailing(SimpleFieldSet sfs, Bucket data) throws MalformedURLException, FSParseException, InvalidParameterException {
			handleTrustsSynchronization(mTrustParser.parseSynchronization(sfs));
		}
	}
	
	private final class ScoresSynchronizationHandler extends MaybeFailingFCPMessageHandler {
		@Override
		public String getMessageName() {
			return "Scores";
		}

		@Override
		public void handle_MaybeFailing(SimpleFieldSet sfs, Bucket data) throws MalformedURLException, FSParseException, InvalidParameterException {
			handleScoresSynchronization(mScoreParser.parseSynchronization(sfs));
		}
	}


	private final class IdentityChangedNotificationHandler extends MaybeFailingFCPMessageHandler {
		@Override
		public String getMessageName() {
			return "IdentityChangedNotification";
		}
		
		@Override
		public void handle_MaybeFailing(SimpleFieldSet sfs, Bucket data) throws MalformedURLException, FSParseException, InvalidParameterException {
			final ChangeSet<Identity> changeSet = mIdentityParser.parseNotification(sfs);
			handleIdentityChangedNotification(changeSet.beforeChange, changeSet.afterChange);
		}
	}
	
	private final class TrustChangedNotificationHandler extends MaybeFailingFCPMessageHandler  {
		@Override
		public String getMessageName() {
			return "TrustChangedNotification";
		}
		
		@Override
		public void handle_MaybeFailing(SimpleFieldSet sfs, Bucket data) throws MalformedURLException, FSParseException, InvalidParameterException {
			final ChangeSet<Trust> changeSet = mTrustParser.parseNotification(sfs);
			handleTrustChangedNotification(changeSet.beforeChange, changeSet.afterChange);
		}
	}
	
	private final class ScoreChangedNotificationHandler extends MaybeFailingFCPMessageHandler {
		@Override
		public String getMessageName() {
			return "ScoreChangedNotification";
		}
		
		@Override
		public void handle_MaybeFailing(SimpleFieldSet sfs, Bucket data) throws MalformedURLException, FSParseException, InvalidParameterException {
			final ChangeSet<Score> changeSet = mScoreParser.parseNotification(sfs);
			handleScoreChangedNotification(changeSet.beforeChange, changeSet.afterChange);
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
	
	public static abstract class FCPParser<T extends Persistent> {
		
		protected final WebOfTrust mWoT;
		
		public FCPParser(final WebOfTrust myWebOfTrust) {
			mWoT = myWebOfTrust;
		}
		
		public ArrayList<T> parseSynchronization(final SimpleFieldSet sfs) throws FSParseException, MalformedURLException, InvalidParameterException {
			final int amount = sfs.getInt("Amount");
			final ArrayList<T> result = new ArrayList<T>(amount+1);
			for(int i=0; i < amount; ++i) {
				result.add(parseSingle(sfs, i));
			}
			return result;
		}

		public ChangeSet<T> parseNotification(final SimpleFieldSet notification) throws MalformedURLException, FSParseException, InvalidParameterException {
			final SimpleFieldSet beforeChange = notification.subset("BeforeChange");
			final SimpleFieldSet afterChange = notification.subset("AfterChange");
			
			return new ChangeSet<T>(parseSingle(beforeChange, 0), parseSingle(afterChange, 0));
		}
		
		abstract protected T parseSingle(SimpleFieldSet sfs, int index) throws FSParseException, MalformedURLException, InvalidParameterException;
	
	}
	
	public static final class IdentityParser extends FCPParser<Identity> {

		public IdentityParser(final WebOfTrust myWebOfTrust) {
			super(myWebOfTrust);
		}
		
		@Override
		protected Identity parseSingle(final SimpleFieldSet sfs, final int index) throws FSParseException, MalformedURLException, InvalidParameterException {
			final String suffix = Integer.toString(index);
			
			final String type = sfs.get("Type" + suffix);
	    	
			if(type.equals("Inexistent"))
	    		return null;
	    	
	        final String nickname = sfs.get("Nickname" + suffix);
	        final String requestURI = sfs.get("RequestURI" + suffix);
	    	final String insertURI = sfs.get("InsertURI" + suffix);
	    	final boolean doesPublishTrustList = sfs.getBoolean("PublishesTrustList" + suffix);
	        final String id = sfs.get("ID" + suffix); 
	 	
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
	 		
	 		final int contextAmount = sfs.getInt("Contexts" + suffix + ".Amount");
	        final int propertyAmount = sfs.getInt("Properties" + suffix + ".Amount");
	 		
	        for(int i=0; i < contextAmount; ++i) {
	        	identity.addContext(sfs.get("Contexts" + suffix + ".Context" + i));
	        }

	        for(int i=0; i < propertyAmount; ++i)  {
	            identity.setProperty(sfs.get("Properties" + suffix + ".Property" + i + ".Name"),
	            		sfs.get("Properties" + suffix + ".Property" + i + ".Value"));
	        }
	        
	    	identity.forceSetCurrentEditionFetchState(FetchState.valueOf(sfs.get("CurrentEditionFetchState" + suffix)));

	        return identity;
		}
		
	}

	public static final class TrustParser extends FCPParser<Trust> {

		private final Map<String, Identity> mIdentities;
		
		public TrustParser(final WebOfTrust myWebOfTrust, final Map<String, Identity> myIdentities) {
			super(myWebOfTrust);
			mIdentities = myIdentities;
		}
		
		@Override
		protected Trust parseSingle(final SimpleFieldSet sfs, final int index) throws FSParseException, InvalidParameterException {
			final String suffix = Integer.toString(index);
			
	    	if(sfs.get("Value" + suffix).equals("Inexistent"))
	    		return null;
	    	
			final String trusterID = sfs.get("Truster" + suffix);
			final String trusteeID = sfs.get("Trustee" + suffix);
			final byte value = sfs.getByte("Value" + suffix);
			final String comment = sfs.get("Comment" + suffix);
			final long trusterEdition = sfs.getLong("TrusterEdition" + suffix);
			
			final Trust trust = new Trust(mWoT, mIdentities.get(trusterID), mIdentities.get(trusteeID), value, comment);
			trust.forceSetTrusterEdition(trusterEdition);
			
			return trust;
		}
		
	}
	
	public static final class ScoreParser extends FCPParser<Score> {
		
		private final Map<String, Identity> mIdentities;
		
		public ScoreParser(final WebOfTrust myWebOfTrust, final Map<String, Identity> myIdentities) {
			super(myWebOfTrust);
			mIdentities = myIdentities;
		}
		
		@Override
		protected Score parseSingle(final SimpleFieldSet sfs, final int index) throws FSParseException {
			final String suffix = Integer.toString(index);
			
	    	if(sfs.get("Value" + suffix).equals("Inexistent"))
	    		return null;
	    	
			final String trusterID = sfs.get("Truster" + suffix);
			final String trusteeID = sfs.get("Trustee" + suffix);
			final int capacity = sfs.getInt("Capacity" + suffix);
			final int rank = sfs.getInt("Rank" + suffix);
			final int value = sfs.getInt("Value" + suffix);
			
			return new Score(mWoT, (OwnIdentity)mIdentities.get(trusterID), mIdentities.get(trusteeID), value, rank, capacity);
		}
		
	}

	
	abstract void handleConnectionEstablished();
	
	abstract void handleConnectionLost();
	
	abstract void handleIdentitiesSynchronization(Collection<Identity> allIdentities);
	
	abstract void handleTrustsSynchronization(Collection<Trust> allTrusts);
	
	abstract void handleScoresSynchronization(Collection<Score> allScores);
	
	abstract void handleIdentityChangedNotification(Identity oldIdentity, Identity newIdentity);
	
	abstract void handleTrustChangedNotification(Trust oldTrust, Trust newTrust);
	
	abstract void handleScoreChangedNotification(Score oldScore, Score newScore);
	
	/**
	 * Must be called at shutdown of your plugin. 
	 */
	public synchronized void stop() {
		Logger.normal(this, "Terminating ...");
		
		// This will wait for run() to exit.
		mTicker.shutdown();
		disconnect();
		
		Logger.normal(this, "Terminated.");
	}
	
	public int getPriority() {
		return NativeThread.MIN_PRIORITY;
	}


}
