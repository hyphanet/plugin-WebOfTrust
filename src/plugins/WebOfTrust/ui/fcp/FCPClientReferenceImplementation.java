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
import plugins.WebOfTrust.SubscriptionManager.ScoresSubscription;
import plugins.WebOfTrust.SubscriptionManager.Subscription;
import plugins.WebOfTrust.SubscriptionManager.TrustsSubscription;
import plugins.WebOfTrust.Trust;
import plugins.WebOfTrust.WebOfTrust;
import plugins.WebOfTrust.exceptions.InvalidParameterException;
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
	
	/** All types of {@link Subscription}. The names match the FCP messages literally and are used for lookup, please do not change them.*/
	private enum SubscriptionType {
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
	private final TrustParser mTrustParser = null;
	
	/** Constructs {@link Score} objects from {@link SimpleFieldSet} data received via FCP. */
	private final ScoreParser mScoreParser = null;
	
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
	public FCPClientReferenceImplementation(final WebOfTrust myMockWebOfTrust, final PluginRespirator myPluginRespirator, final Executor myExecutor) {
		mPluginRespirator = myPluginRespirator;
		mTicker = new TrivialTicker(myExecutor);
		mRandom = mPluginRespirator.getNode().fastWeakRandom;
		
		final FCPMessageHandler[] handlers = {
				new PongHandler(),
				new IdentitiesSynchronizationHandler(),
				new IdentityChangedNotificationHandler() };
		
		for(FCPMessageHandler handler : handlers)
			mFCPMessageHandlers.put(handler.getMessageName(), handler);
		
		mIdentityParser = new IdentityParser(myMockWebOfTrust);
	}
	
	/**
	 * Tells the client to start connecting to WOT and filing the requested subscriptions.
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
	 */
	public synchronized void subscribe(final SubscriptionType type) {
		mSubscribeTo.add(type);
		scheduleKeepaliveLoopExecution();
	}
	
	/**
	 * Call this to cancel a {@link Subscription}.
	 */
	public synchronized void unsubscribe(final SubscriptionType type) {
		mSubscribeTo.remove(type);
		scheduleKeepaliveLoopExecution();
	}
	
	/**
	 * Schedules execution of {@link #run()} via {@link #mTicker}
	 */
	private void scheduleKeepaliveLoopExecution() {
		final long sleepTime = mConnection != null ? (WOT_PING_DELAY/2 + mRandom.nextInt(WOT_PING_DELAY)) : WOT_RECONNECT_DELAY;
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
	
	private synchronized void disconnect() {
		if(mConnection != null) {
			for(SubscriptionType type : mSubscriptionIDs.keySet()) {
				unsubscribe(type);
				mSubscriptionIDs.remove(type);
			}
		}
		
		// Notice: PluginTalker has no disconnection mechanism, we can must drop references to existing connections and then they will be GCed
		mConnection = null;
		mConnectionIdentifier = null;
	}
	
	private boolean connected()  {
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
			} else if(!shouldSubscribe && isSubscribed) {
				fcp_Unsubscribe(type);
			}
		}
	}
	
	private void fcp_Subscribe(final SubscriptionType type) {
		final SimpleFieldSet sfs = new SimpleFieldSet(true);
		sfs.putOverwrite("Message", "Subscribe");
		sfs.putOverwrite("To", type.toString());
		mConnection.send(sfs, null);
	}
	
	private void fcp_Unsubscribe(final SubscriptionType type) {
		final SimpleFieldSet sfs = new SimpleFieldSet(true);
		sfs.putOverwrite("Message", "Unsubscribe");
		sfs.putOverwrite("Subscription", mSubscriptionIDs.get(type));
		mConnection.send(sfs, null);
	}

	@Override
	public synchronized final void onReply(final String pluginname, final String indentifier, final SimpleFieldSet params, final Bucket data) {
		if(!WOT_FCP_NAME.equals(pluginname))
			throw new RuntimeException("Plugin is not supposed to talk to us: " + pluginname);
		
		if(mConnection == null || !mConnectionIdentifier.equals(indentifier)) {
			Logger.error(this, "Received out of band message, maybe because we reconnected and the old server is still alive? Identifier: " + indentifier);
			// FIXME: Do something which makes WOT cancel maybe-existing subscriptions so it doesn't keep collecting data for them.
			return;
		}

		final String messageString = params.get("Message");
		final FCPMessageHandler handler = mFCPMessageHandlers.get(messageString);
		
		if(handler == null) {
			Logger.warning(this, "Unknown message type: " + messageString);
			return;
		}
		
		handler.handle(params, data);
	}
	
	private interface FCPMessageHandler {
		/**
		 * This function shall the value of the {@link SimpleFieldSet} "Message" field this handler belongs to with.
		 */
		public String getMessageName();
		
		public void handle(final SimpleFieldSet sfs, final Bucket data);
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
	
	private final class IdentitiesSynchronizationHandler implements FCPMessageHandler {
		@Override
		public String getMessageName() {
			return "Identities";
		}
		
		@Override
		public void handle(final SimpleFieldSet sfs, final Bucket data) {
			try {
				handleIdentitiesSynchronization(mIdentityParser.parseSynchronization(sfs));
			} catch(Exception e) {
				// FIXME: Pass it through to WOT.
			}
		}
	}
	
	private final class IdentityChangedNotificationHandler implements FCPMessageHandler {
		@Override
		public String getMessageName() {
			return "IdentityChangedNotification";
		}
		
		@Override
		public void handle(SimpleFieldSet sfs, Bucket data) {
			try {
				final ChangeSet<Identity> changeSet = mIdentityParser.parseNotification(sfs);
				handleIdentityChangedNotification(changeSet.beforeChange, changeSet.afterChange);
			} catch(Exception e) {
				// FIXME: Pass it through to WOT.
			}
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
			
			return new Trust(mWoT, mIdentities.get(trusterID), mIdentities.get(trusteeID), value, comment);
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
	public synchronized void terminate() {
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
