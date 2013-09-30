/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.ui.fcp;

import java.util.HashSet;
import java.util.Random;

import plugins.WebOfTrust.Identity;
import plugins.WebOfTrust.Persistent;
import plugins.WebOfTrust.Score;
import plugins.WebOfTrust.SubscriptionManager;
import plugins.WebOfTrust.SubscriptionManager.Subscription;
import plugins.WebOfTrust.Trust;
import plugins.WebOfTrust.WebOfTrust;

import com.db4o.ObjectSet;
import com.db4o.query.Query;

import freenet.node.PrioRunnable;
import freenet.pluginmanager.PluginNotFoundException;
import freenet.support.Executor;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.SimpleFieldSet;
import freenet.support.TrivialTicker;
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
public abstract class FCPClientReferenceImplementation implements PrioRunnable {

	/** The amount of milliseconds between each attempt to connect to the WoT plugin */
	private static final int WOT_RECONNECT_DELAY = 1 * 1000;
	
	/** The amount of milliseconds between sending pings to WOT to see if we are still connected */
	private static final int WOT_PING_DELAY = 30 * 1000;
	
	private final WebOfTrust mWebOfTrust;
	private final TrivialTicker mTicker;
	private final Random mRandom;

	private PluginTalkerBlocking mConnection;	
	
	private static transient volatile boolean logDEBUG = false;
	private static transient volatile boolean logMINOR = false;
	
	static {
		Logger.registerClass(FCPClientReferenceImplementation.class);
	}

	public FCPClientReferenceImplementation(WebOfTrust myWebOfTrust, Executor myExecutor) {
		mWebOfTrust = myWebOfTrust;
		mTicker = new TrivialTicker(myExecutor);
		mRandom = mWebOfTrust.getPluginRespirator().getNode().fastWeakRandom;
	}
	
	public void start() {
		Logger.normal(this, "Starting...");
		
		mTicker.queueTimedJob(this, "WOT " + this.getClass().getSimpleName(), 0, false, true);

		Logger.normal(this, "Started.");
	}
	
	public void terminate() {
		Logger.normal(this, "Terminating ...");
		
		mTicker.shutdown();
		
		Logger.normal(this, "Terminated.");
	}
	
	/**
	 * Checks whether we are connected to WOT. Connects to it if the connection is lost or did not exist yet.
	 * Then files all {@link Subscription}s.
	 * 
	 * Executed by {@link #mTicker} as scheduled periodically:
	 * - Every {@link #WOT_RECONNECT_DELAY} seconds if we have no connection to WOT
	 * - Every {@link #WOT_PING_DELAY} if we have a connection to WOT
	 */
	public void run() { 
		if(logMINOR) Logger.minor(this, "Connection-checking loop running...");
		 
		boolean connectedToWOT;
		try {
			connectedToWOT = connectToWOT();
		
			if(connectedToWOT) {
				try {
					checkSubscriptions();
				} catch (Exception e) {
					Logger.error(this, "Checking subscriptions failed", e);
				}
			}
		} finally {
			final long sleepTime =  connectedToWOT ? (WOT_PING_DELAY/2 + mRandom.nextInt(WOT_PING_DELAY)) : WOT_RECONNECT_DELAY;
			mTicker.queueTimedJob(this, "WOT " + this.getClass().getSimpleName(), sleepTime, false, true);
			if(logMINOR) Logger.debug(this, "Sleeping for " + (sleepTime / (60*1000)) + " minutes.");
		}
		
		if(logMINOR) Logger.minor(this, "Connection-checking finished.");
	}
	
	private void checkSubscriptions() {
		
	}

	private synchronized boolean connectToWOT() {
		if(mConnection != null) { /* Old connection exists */
			SimpleFieldSet sfs = new SimpleFieldSet(true);
			sfs.putOverwrite("Message", "Ping");
			try {
				mConnection.sendBlocking(sfs, null); /* Verify that the old connection is still alive */
				return true;
			}
			catch(PluginNotFoundException e) {
				mConnection = null;
				/* Do not return, try to reconnect in next try{} block */
			}
		}
		
		try {
			mConnection = new PluginTalkerBlocking(mWebOfTrust.getPluginRespirator());
			handleConnectionEstablished();
			return true;
		} catch(PluginNotFoundException e) {
			handleConnectionLost();
			return false;
		}
	}
	
	abstract void handleConnectionEstablished();
	
	abstract void handleConnectionLost();
	
	public int getPriority() {
		return NativeThread.MIN_PRIORITY;
	}


}
