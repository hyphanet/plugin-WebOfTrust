/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.ui.fcp;

import java.util.Random;
import java.util.UUID;

import plugins.WebOfTrust.SubscriptionManager;
import plugins.WebOfTrust.SubscriptionManager.Subscription;
import plugins.WebOfTrust.WebOfTrust;
import freenet.node.PrioRunnable;
import freenet.pluginmanager.FredPluginTalker;
import freenet.pluginmanager.PluginNotFoundException;
import freenet.pluginmanager.PluginRespirator;
import freenet.pluginmanager.PluginTalker;
import freenet.support.CurrentTimeUTC;
import freenet.support.Executor;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.TrivialTicker;
import freenet.support.api.Bucket;
import freenet.support.io.NativeThread;

/**
 * This is a reference implementation of how a FCP client application should interact with WOT via event-notifications.
 * The foundation of event-notifications is class {@link SubscriptionManager}, you should read the JavaDoc of it to understand them.
 * 
 * You can this class in your client like this:
 * - Copy-paste this abstract base class.
 * - Do NOT modify it. Instead, implement a child class which implements the abstract functions.
 * - Any improvements you have to the abstract base class should be backported to WOT! 
 * 
 * NOTICE: This class was based upon class SubscriptionManagerFCPTest, which you can find in the unit test. Its not possible to link it in the
 * JavaDoc because the unit tests are not within the classpath. 
 * 
 * NOTICE: The connect-loop was based upon class plugins.Freetalk.WoT.WoTIdentityManager. Please backport improvements to it.
 * 
 * @see FCPInterface The "server" to which a FCP client connects.
 * @see SubscriptionManager The foundation of event-notifications and therefore the backend of all FCP traffic which this class does.
 * @author xor (xor@freenetproject.org)
 */
public abstract class FCPClientReferenceImplementation implements PrioRunnable, FredPluginTalker {
	
	private static final String WOT_FCP_NAME = "plugins.WebOfTrust.WebOfTrust";

	/** The amount of milliseconds between each attempt to connect to the WoT plugin */
	private static final int WOT_RECONNECT_DELAY = 1 * 1000;
	
	/** The amount of milliseconds between sending pings to WOT to see if we are still connected */
	private static final int WOT_PING_DELAY = 30 * 1000;
	
	private static final int WOT_PING_TIMEOUT_DELAY = 2*WOT_PING_DELAY;
	
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
	
	private static transient volatile boolean logDEBUG = false;
	private static transient volatile boolean logMINOR = false;
	
	static {
		Logger.registerClass(FCPClientReferenceImplementation.class);
	}

	public FCPClientReferenceImplementation(final PluginRespirator myPluginRespirator, final Executor myExecutor) {
		mPluginRespirator = myPluginRespirator;
		mTicker = new TrivialTicker(myExecutor);
		mRandom = mPluginRespirator.getNode().fastWeakRandom;
	}
	
	public void start() {
		Logger.normal(this, "Starting...");
		
		scheduleKeepaliveLoopExecution();

		Logger.normal(this, "Started.");
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
				sendPing();
				checkSubscriptions();
			}
		} catch (Exception e) {
			Logger.error(this, "Error in connection-checking loop!", e);
		} finally {
			scheduleKeepaliveLoopExecution();
		}
		
		if(logMINOR) Logger.minor(this, "Connection-checking finished.");
	}

	private synchronized boolean connect() {
		try {
			mConnectionIdentifier = UUID.randomUUID().toString();
			mConnection = mPluginRespirator.getPluginTalker(this, WOT_FCP_NAME, mConnectionIdentifier);
			Logger.normal(this, "Connected to WOT, identifier: " + mConnectionIdentifier);
			handleConnectionEstablished();
			return true;
		} catch(PluginNotFoundException e) {
			mConnectionIdentifier = null;
			mConnection = null; // Not necessary but we anyway make sure that this happens in case we someday catch more than PluginNotFoundException
			Logger.warning(this, "Cannot connect to WOT!");
			handleConnectionLost();
			return false;
		}
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
	
	
	private synchronized void sendPing() {
		final SimpleFieldSet sfs = new SimpleFieldSet(true);
		sfs.putOverwrite("Message", "Ping");
		mConnection.send(sfs, null);
		mLastPingSentDate = CurrentTimeUTC.getInMillis();
	}
	
	private void checkSubscriptions() {
		
	}
	
	@Override
	public synchronized final void onReply(final String pluginname, final String indentifier, final SimpleFieldSet params, final Bucket data) {
		if(!WOT_FCP_NAME.equals(pluginname))
			throw new RuntimeException("Plugin is not supposed to talk to us: " + pluginname);
		
		if(mConnection == null || !mConnectionIdentifier.equals(indentifier)) {
			Logger.error(this, "Received out of band message, maybe because we reconnected and the old server is still alive? Identifier: " + indentifier);
			return;
		}
		
		assert(data==null);
		
		final String message = params.get("Message");
		assert(message != null);
		
		if("Pong".equals(message)) {
			if((CurrentTimeUTC.getInMillis() - mLastPingSentDate) <= WOT_PING_TIMEOUT_DELAY)
				mLastPingSentDate = 0; // Mark the ping as successful.
		} else
			Logger.warning(this, "Unknown message type: " + message);
	}
	
	abstract void handleConnectionEstablished();
	
	abstract void handleConnectionLost();
	
	public void terminate() {
		Logger.normal(this, "Terminating ...");
		
		// This will wait for run() to exit.
		mTicker.shutdown();
		
		Logger.normal(this, "Terminated.");
	}
	
	public int getPriority() {
		return NativeThread.MIN_PRIORITY;
	}


}
