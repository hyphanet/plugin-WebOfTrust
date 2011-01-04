/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package plugins.WebOfTrust;

import java.util.UUID;

import com.db4o.ext.ExtObjectContainer;
import com.db4o.query.Query;

import freenet.node.PrioRunnable;
import freenet.pluginmanager.PluginRespirator;
import freenet.support.TrivialTicker;
import freenet.support.codeshortification.IfNull;
import freenet.support.io.NativeThread;

/**
 * The subscription manager allows client application to subscribe to certain data sets of WoT and get notified on change.
 * For example, if you subscribe to the list of identities, you will get a notification when an identity is added or removed.
 * 
 * The architecture of this class supports implementing different types of subscriptions: Currently, only FCP is implemented, but it is also technically possible to have subscriptions
 * which do a callback within the WoT plugin or maybe even via OSGI.
 * 
 * TODO: This should be used for powering the IntroductionClient/IntroductionServer.
 * 
 * @author xor (xor@freenetproject.org)
 */
public final class SubscriptionManager implements PrioRunnable {
	
	/**
	 * A subscription stores the information which client is subscribed to which content.
	 */
	private static abstract class Subscription<NotificationType extends Notification> extends Persistent {
		
		/**
		 * The UUID of this Subscription. Stored as String for db4o performance, but must be valid in terms of the UUID class.
		 */
		@IndexedField
		private final String mID;

		/**
		 * Each {@link Notification} is given an index upon creation. The indexes ensure sequential processing.
		 * If an error happens when trying to process a notification to a client, we might want to rety some time later.
		 * Therefore, the {@link Notification} queue exists per client and not globally - if a single {@link Notification} is created by WoT,
		 * multiple {@link Notification} objects are stored - one for each Subscription.
		 */
		private int mNextNotificationIndex = 0;
		
		
		public Subscription() {
			mID = UUID.randomUUID().toString();
		}
		
		@Override
		public void startupDatabaseIntegrityTest() throws Exception {
			IfNull.thenThrow(mID, "mID");
			UUID.fromString(mID); // Throws if invalid
			
			if(mNextNotificationIndex < 0)
				throw new IllegalStateException("mNextNotificationIndex==" + mNextNotificationIndex);
		}
		
		public final String getID() {
			return mID;
		}
		
		protected final int takeFreeNotificationIndex() {
			return mNextNotificationIndex++;
		}
		
		
		@Override
		protected void deleteWithoutCommit() {
			// TODO: Delete the notifications
			super.deleteWithoutCommit();
		}

		
		public abstract void notifySubscriber(NotificationType notification);

	}
	
	
	/**
	 * An object of type Notification is stored when an event happens to which a client is possibly subscribed.
	 * The SubscriptionManager will wake up some time after that, pull all notifications from the database and process them.
	 */
	private static abstract class Notification extends Persistent {
		
		@IndexedField
		private final String mSubscriptionID;
		
		@IndexedField
		private final int mIndex;
		
		public Notification(final Subscription<? extends Notification> mySubscription) {
			mSubscriptionID = mySubscription.getID();
			mIndex = mySubscription.takeFreeNotificationIndex();
		}
		
		@Override
		public void startupDatabaseIntegrityTest() throws Exception {
			IfNull.thenThrow(mSubscriptionID);
			UUID.fromString(mSubscriptionID);
			
			// TODO: Throw if no subscription exists with the given ID
			
			if(mIndex < 0)
				throw new IllegalStateException("mIndex==" + mIndex);
		}
		
	}
	/**
	 * This notification is issued when the attributes of an identity change.
	 * It is also used as a base class for {@link NewIdentityNotification} and {@link IdentityDeletedNotification}.
	 */
	private class IdentityChangedNotification extends Notification {
		private final String mIdentityID;
	
		public IdentityChangedNotification(final Subscription<? extends IdentityChangedNotification> mySubscription, Identity myIdentity) {
			super(mySubscription);
			mIdentityID = myIdentity.getID();
		}
		
		@Override
		public void startupDatabaseIntegrityTest() throws Exception {
			super.startupDatabaseIntegrityTest();
			
			IfNull.thenThrow(mIdentityID, "mIdentityID");
		}

	}

	/**
	 * This notification is issued when a new identity is discovered.
	 */
	private final class NewIdentityNotification extends IdentityChangedNotification {		
		
		public NewIdentityNotification(final Subscription<NewIdentityNotification> mySubscription, Identity myIdentity) {
			super(mySubscription, myIdentity);
		}
		
	}
	
	/**
	 * This notification is issued when a trust value is added, removed or changed.
	 */
	private final class TrustChangedNotification extends Notification {
		
		private final String mTrusterID;
		private final String mTrusteeID;
		
		public TrustChangedNotification(final Subscription<TrustChangedNotification> mySubscription, Trust myTrust) {
			super(mySubscription);
			mTrusterID = myTrust.getTruster().getID();
			mTrusteeID = myTrust.getTrustee().getID();
		}
		
		@Override
		public void startupDatabaseIntegrityTest() throws Exception {
			super.startupDatabaseIntegrityTest();
			
			IfNull.thenThrow(mTrusterID, "mTrusterID");
			IfNull.thenThrow(mTrusteeID, "mTrusteeID");
		}
		
	}
	
	/**
	 * This notification is issued when a score value is added, removed or changed.
	 */
	private final class ScoreChangedNotification extends Notification {
		
		private final String mTrusterID;
		private final String mTrusteeID;
		
		public ScoreChangedNotification(final Subscription<ScoreChangedNotification> mySubscription, Score myScore) {
			super(mySubscription);
			mTrusterID = myScore.getTruster().getID();
			mTrusteeID = myScore.getTrustee().getID();
		}
		
		@Override
		public void startupDatabaseIntegrityTest() throws Exception {
			super.startupDatabaseIntegrityTest();
			
			IfNull.thenThrow(mTrusterID, "mTrusterID");
			IfNull.thenThrow(mTrusteeID, "mTrusteeID");
		}
	}

	/**
	 * A subscription to the attributes of all identities.
	 * If the attributes of an identity change, the subscriber gets notified.
	 * The subscriber will also get notified if a new identity is created.
	 */
	private static abstract class IdentityAttributeListSubscription extends Subscription<IdentityChangedNotification> {

		public IdentityAttributeListSubscription() {
			super();
		}

	}
	
	/**
	 * A subscription to the list of identities.
	 * If an identity gets added or deleted, the subscriber is notified.
	 */
	private static abstract class IdentityListSubscription extends Subscription<NewIdentityNotification> {

		public IdentityListSubscription() {
			super();
		}

	}
	
	/**
	 * A subscription to the list of trust values.
	 * If a trust value gets changed, is added or deleted, the subscriber is notified.
	 */
	private static abstract class TrustListSubscription extends Subscription<TrustChangedNotification> {

		public TrustListSubscription() {
			super();
		}

	}
	
	/**
	 * A subscription to the list of scores.
	 * If a score value gets changed, is added or deleted, the subscriber is notified.
	 */
	private static abstract class ScoreListSubscription extends Subscription<ScoreChangedNotification> {

		public ScoreListSubscription() {
			super();
		}

	}
	
	private final class FCPIdentityAttributeListSubscription extends IdentityAttributeListSubscription {

		public FCPIdentityAttributeListSubscription() {
			super();
		}

		@Override
		public void notifySubscriber(IdentityChangedNotification notification) {
			// TODO Auto-generated method stub
			
		}
		
	}
	
	private final class FCPIdentityListSubscription extends IdentityListSubscription {

		public FCPIdentityListSubscription() {
			super();
		}
		
		@Override
		public void notifySubscriber(NewIdentityNotification notification) {
			// TODO Auto-generated method stub
		}
		
	}
	
	private final class FCPTrustListSubscription extends TrustListSubscription {

		
		public FCPTrustListSubscription() {
			super();
		}
		
		
		@Override
		public void notifySubscriber(TrustChangedNotification notification) {
			// TODO Auto-generated method stub
		}

		
	}
	
	private final class FCPScoreListSubscription extends ScoreListSubscription {
		
		public FCPScoreListSubscription() {
			super();
		}

		@Override
		public void notifySubscriber(ScoreChangedNotification notification) {
			// TODO Auto-generated method stub
		}

	}

	
	/**
	 * After a notification command is stored, we wait this amount of time before processing the commands.
	 */
	private static final long PROCESS_NOTIFICATIONS_DELAY = 60 * 1000;
	
	
	private final WebOfTrust mWoT;
	
	private final ExtObjectContainer mDB;
	
	private final TrivialTicker mTicker;
	
	
	public SubscriptionManager(WebOfTrust myWoT) {
		mWoT = myWoT;
		mDB = mWoT.getDatabase();
		
		PluginRespirator respirator = mWoT.getPluginRespirator();
		
		if(respirator != null) { // We are connected to a node
			mTicker = new TrivialTicker(respirator.getNode().executor);
		} else {
			mTicker = null;
		}
		
		deleteAllSubscriptions();
	}

	
	private synchronized final void deleteAllSubscriptions() {
		final Query q = mDB.query();
		q.constrain(Subscription.class);
		
		synchronized(mDB.lock()) {
			try {
				for(Subscription<? extends Notification> s : new Persistent.InitializingObjectSet<Subscription<? extends Notification>>(mWoT, q)) {
					s.deleteWithoutCommit();
				}
				Persistent.checkedCommit(mDB, this);
			} catch(RuntimeException e) {
				Persistent.checkedRollbackAndThrow(mDB, this, e);
			}
		}
	}
	

	public int getPriority() {
		return NativeThread.LOW_PRIORITY;
	}

	public void run() {
		// TODO Auto-generated method stub
		
	}
}
