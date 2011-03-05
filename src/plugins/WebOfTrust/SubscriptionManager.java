/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package plugins.WebOfTrust;

import java.util.UUID;

import plugins.WebOfTrust.ui.fcp.FCPInterface;

import com.db4o.ObjectSet;
import com.db4o.ext.ExtObjectContainer;
import com.db4o.query.Query;

import freenet.node.PrioRunnable;
import freenet.pluginmanager.PluginRespirator;
import freenet.support.Logger;
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
 * The {@link Notification}s are deployed strictly sequential per {@link Subscription}.
 * If a single Notification cannot be deployed, the processing of the Notifications for that Subscription is halted until the failed
 * Notification can be deployed successfully.
 * TODO: Allow out-of-order notifications of the client desires them
 * TODO: Allow coalescing of notifications: If a single object changes twice, only send one notification 
 * 
 * 
 * TODO: This should be used for powering the IntroductionClient/IntroductionServer.
 * 
 * @author xor (xor@freenetproject.org)
 */
public final class SubscriptionManager implements PrioRunnable {
	
	/**
	 * A subscription stores the information which client is subscribed to which content and how it is supposed
	 * to be notified about updates.
	 * Subscriptions are stored one per {@link Notification}-type and per way of notification:
	 * Because we want the notification queue to block on error, a single subscription does not support
	 * multiple ways of notifying the client.
	 */
	public static abstract class Subscription<NotificationType extends Notification> extends Persistent {
		
		/**
		 * @see getID()
		 */
		@IndexedField
		private final String mID;
		
		/**
		 * The way of notifying a client
		 */
		public static enum Type {
			FCP,
			Callback
		};
		
		/**
		 * The way the client of this subscription desires notification
		 */
		private final Type mType;
		
		/**
		 * An ID which associates this subscription with a FCP connection if the type is FCP.
		 */
		private final String mFCPKey;

		/**
		 * Each {@link Notification} is given an index upon creation. The indexes ensure sequential processing.
		 * If an error happens when trying to process a notification to a client, we might want to rety some time later.
		 * Therefore, the {@link Notification} queue exists per client and not globally - if a single {@link Notification} is created by WoT,
		 * multiple {@link Notification} objects are stored - one for each Subscription.
		 */
		private int mNextNotificationIndex = 0;
		
		/**
		 * Constructor for being used by child classes.
		 * @param myType The type of the Subscription
		 * @param fcpID The FCP ID of the subscription. Can be null if the type is not FCP.
		 */
		protected Subscription(Type myType, String fcpID) {
			mID = UUID.randomUUID().toString();
			mType = myType;
			mFCPKey = fcpID;
		}
		
		@Override
		public void startupDatabaseIntegrityTest() throws Exception {
			IfNull.thenThrow(mID, "mID");
			UUID.fromString(mID); // Throws if invalid
			
			IfNull.thenThrow(mType, "mType");
			
			if(mType == Type.FCP)
				IfNull.thenThrow(mFCPKey, "mFCPKey");
			
			if(mNextNotificationIndex < 0)
				throw new IllegalStateException("mNextNotificationIndex==" + mNextNotificationIndex);
		}
		
		/**
		 * @return The UUID of this Subscription. Stored as String for db4o performance, but must be valid in terms of the UUID class.
		 */
		public final String getID() {
			return mID;
		}
		
		/**
		 * Returns the next free index for a {@link Notification} in the queue of this Subscription
		 * and stores this Subscription object without committing the transaction.
		 */
		protected final int takeFreeNotificationIndexWithoutCommit() {
			final int index = mNextNotificationIndex++;
			storeWithoutCommit();
			return index;
		}
		
		/**
		 * @throws UnsupportedOperationException Is always thrown: Use {@link deleteWithoutCommit(SubscriptionManager manager)} instead.
		 */
		@Override
		protected void deleteWithoutCommit() {
			throw new UnsupportedOperationException("Need a SubscriptionManager");
		}
		
		/**
		 * Deletes this Subscription and - using the passed in {@link SubscriptionManager} - also deletes all
		 * queued {@link Notification}s of it. Does not commit the transaction.
		 * @param manager The {@link SubscriptionManager} to which this Subscription belongs.
		 */
		protected void deleteWithoutCommit(final SubscriptionManager manager) {
			for(final Notification notification : manager.getAllNotifications(this)) {
				notification.deleteWithoutCommit();
			}
			super.deleteWithoutCommit();
		}

		/**
		 * Takes the database lock to begin a transaction, stores this object and commits the transaction.
		 * You must synchronize on the {@link SubscriptionManager} while calling this function.
		 */
		protected void storeAndCommit() {
			synchronized(mDB.lock()) {
				try {
					storeWithoutCommit();
					checkedCommit(this);
				} catch(RuntimeException e) {
					Persistent.checkedRollbackAndThrow(mDB, this, e);
				}
			}
		}
		
		
		/**
		 * Called by this subscription when processing an {@link InitialSynchronizationNotification}.
		 * Such a notification is stored as the first notification for all subscriptions upon creation.
		 * When processing it, this function shall synchronize the state of the subscriber if this is
		 * required before it can be kept up to date by event notifications.
		 * For example, if a client subscribes to the list of identities, it must always receive a full list of
		 * all existing identities at first. Then it can be kept up to date by sending single new identities
		 * as they eventually appear.
		 */
		protected abstract void synchronizeSubscriberByFCP();

		/**
		 * Called by this Subscription when the type of it is FCP and a {@link Notification} shall be sent via FCP. 
		 * The implementation MUST throw a {@link RuntimeException} if the FCP message was not sent successfully: 
		 * Subscriptions are supposed to be reliable, if transmitting a {@link Notification} fails it shall
		 * be resent.
		 */
		protected abstract void notifySubscriberByFCP(NotificationType notification);

		/**
		 * Sends out the notification queue for this Subscription, in sequence.
		 * If a notification is sent successfully, it is deleted and the transaction is committed.
		 * If sending a single notification fails, the transaction for the current Notification is rolled back
		 * and an exception is thrown.
		 * You have to synchronize on the WoT, the SubscriptionManager and the database lock before calling this
		 * function!
		 */
		@SuppressWarnings("unchecked")
		protected void sendNotifications(SubscriptionManager manager) {
			switch(mType) {
				case FCP:
					for(final Notification notification : manager.getAllNotifications(this)) {
						try {
							if(notification instanceof InitialSynchronizationNotification) {
								synchronizeSubscriberByFCP();
							} else {
								notifySubscriberByFCP((NotificationType)notification);
								notification.deleteWithoutCommit();
							}
							// If processing of a single notification fails, we do not want the previous notifications
							// to be sent again when the failed notification is retried. Therefore, we commit after
							// each processed notification but do not catch RuntimeExceptions here
							
							Persistent.checkedCommit(mDB, this);
						} catch(RuntimeException e) {
							Persistent.checkedRollbackAndThrow(mDB, this, e);
						}
					}
					break;
				default:
					throw new UnsupportedOperationException("Unknown Type: " + mType);
			}
		}

	}
	
	
	/**
	 * An object of type Notification is stored when an event happens to which a client is possibly subscribed.
	 * The SubscriptionManager will wake up some time after that, pull all notifications from the database and process them.
	 */
	public static abstract class Notification extends Persistent {
		
		/**
		 * The {@link Subscription} to which this Notification belongs
		 */
		@IndexedField
		private final Subscription<? extends Notification> mSubscription;
		
		/**
		 * The index of this Notification in the queue of its {@link Subscription}:
		 * Notifications are supposed to be sent out in proper sequence, therefore we use incremental indices.
		 */
		@IndexedField
		private final int mIndex;
		
		/**
		 * Constructs a Notification in the queue of the given subscription.
		 * Takes a free notification index from it with {@link Subscription.takeFreeNotificationIndexWithoutCommit}
		 */
		protected Notification(final Subscription<? extends Notification> mySubscription) {
			mSubscription = mySubscription;
			mIndex = mySubscription.takeFreeNotificationIndexWithoutCommit();
		}
		
		@Override
		public void startupDatabaseIntegrityTest() throws Exception {
			IfNull.thenThrow(mSubscription);
			
			// TODO: Throw if no subscription exists with the given ID
			
			if(mIndex < 0)
				throw new IllegalStateException("mIndex==" + mIndex);
		}
		
	}
	
	/**
	 * This {@link Notification} is stored as the first Notification for all types of Subscription which require
	 * an initial synchronization with the client.
	 * For example, if a client subscribes to the list of identities, it should always first receive a full list of
	 * all existing identities and after that be notified about each single new identity which eventually appears.
	 */
	protected static class InitialSynchronizationNotification extends Notification {
		
		protected InitialSynchronizationNotification(Subscription<? extends Notification> mySubscription) {
			super(mySubscription);
		}
		
	}
	
	/**
	 * This notification is issued when the attributes of an identity change.
	 * It is also used as a base class for {@link NewIdentityNotification} and {@link IdentityDeletedNotification}.
	 */
	protected static class IdentityChangedNotification extends Notification {
		private final String mIdentityID;
	
		protected IdentityChangedNotification(final Subscription<? extends IdentityChangedNotification> mySubscription, Identity myIdentity) {
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
	protected static final class NewIdentityNotification extends IdentityChangedNotification {		
		
		protected NewIdentityNotification(final Subscription<NewIdentityNotification> mySubscription, Identity myIdentity) {
			super(mySubscription, myIdentity);
		}
		
	}
	
	/**
	 * This notification is issued when a trust value is added, removed or changed.
	 */
	protected static final class TrustChangedNotification extends Notification {
		
		private final String mTrusterID;
		private final String mTrusteeID;
		
		protected TrustChangedNotification(final Subscription<TrustChangedNotification> mySubscription, Trust myTrust) {
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
	protected static final class ScoreChangedNotification extends Notification {
		
		private final String mTrusterID;
		private final String mTrusteeID;
		
		protected ScoreChangedNotification(final Subscription<ScoreChangedNotification> mySubscription, Score myScore) {
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
	public static final class IdentityAttributeListSubscription extends Subscription<IdentityChangedNotification> {

		protected IdentityAttributeListSubscription(String fcpID) {
			super(Subscription.Type.FCP, fcpID);
		}

		@Override
		protected void synchronizeSubscriberByFCP() {
			// TODO Auto-generated method stub
			
		}
		
		@Override
		protected void notifySubscriberByFCP(IdentityChangedNotification notification) {
			// TODO Auto-generated method stub
			
		}

		private void storeNotificationWithoutCommit(Identity identity) {
			final IdentityChangedNotification notification = new IdentityChangedNotification(this, identity);
			notification.initializeTransient(mWebOfTrust);
			notification.storeWithoutCommit();
		}

	}
	
	/**
	 * A subscription to the list of identities.
	 * If an identity gets added or deleted, the subscriber is notified.
	 */
	public static final class IdentityListSubscription extends Subscription<NewIdentityNotification> {

		protected IdentityListSubscription(String fcpID) {
			super(Subscription.Type.FCP, fcpID);
		}
		
		@Override
		protected void synchronizeSubscriberByFCP() {
			// TODO Auto-generated method stub
			
		}
		
		@Override
		protected void notifySubscriberByFCP(NewIdentityNotification notification) {
			// TODO Auto-generated method stub
			
		}

		public void storeNotificationWithoutCommit(Identity identity) {
			final NewIdentityNotification notification = new NewIdentityNotification(this, identity);
			notification.initializeTransient(mWebOfTrust);
			notification.storeWithoutCommit();
		}
		
	}
	
	/**
	 * A subscription to the list of trust values.
	 * If a trust value gets changed, is added or deleted, the subscriber is notified.
	 */
	public static final class TrustListSubscription extends Subscription<TrustChangedNotification> {

		protected TrustListSubscription(String fcpID) {
			super(Subscription.Type.FCP, fcpID);
		}
		
		@Override
		protected void synchronizeSubscriberByFCP() {
			// TODO Auto-generated method stub
			
		}

		@Override
		protected void notifySubscriberByFCP(TrustChangedNotification notification) {
			// TODO Auto-generated method stub
			
		}

		public void storeNotificationWithoutCommit(Trust trust) {
			final TrustChangedNotification notification = new TrustChangedNotification(this, trust);
			notification.initializeTransient(mWebOfTrust);
			notification.storeWithoutCommit();
		}

	}
	
	/**
	 * A subscription to the list of scores.
	 * If a score value gets changed, is added or deleted, the subscriber is notified.
	 */
	public static final class ScoreListSubscription extends Subscription<ScoreChangedNotification> {

		protected ScoreListSubscription(String fcpID) {
			super(Subscription.Type.FCP, fcpID);
		}
		
		@Override
		protected void synchronizeSubscriberByFCP() {
			// TODO Auto-generated method stub
			
		}

		@Override
		protected void notifySubscriberByFCP(ScoreChangedNotification notification) {
			// TODO Auto-generated method stub
			
		}
		
		public void storeNotificationWithoutCommit(Score score) {
			final ScoreChangedNotification notification = new ScoreChangedNotification(this, score);
			notification.initializeTransient(mWebOfTrust);
			notification.storeWithoutCommit();
		}

	}

	
	/**
	 * After a notification command is stored, we wait this amount of time before processing the commands.
	 */
	private static final long PROCESS_NOTIFICATIONS_DELAY = 60 * 1000;
	
	
	private final WebOfTrust mWoT;
	
	private final FCPInterface mFCPInterface;
	
	private final ExtObjectContainer mDB;
	
	private final TrivialTicker mTicker;
	
	
	public SubscriptionManager(WebOfTrust myWoT) {
		mWoT = myWoT;
		mFCPInterface = mWoT.getFCPInterface();
		mDB = mWoT.getDatabase();
		
		PluginRespirator respirator = mWoT.getPluginRespirator();
		
		if(respirator != null) { // We are connected to a node
			mTicker = new TrivialTicker(respirator.getNode().executor);
		} else {
			mTicker = null;
		}
		
		deleteAllSubscriptions();
	}
	
	public static final class SubscriptionExistsAlreadyException extends Exception {
		private static final long serialVersionUID = 1L;
		public final Subscription<? extends Notification> existingSubscription;
		
		public SubscriptionExistsAlreadyException(Subscription<? extends Notification> existingSubscription) {
			this.existingSubscription = existingSubscription;
		}
	}
	
	private synchronized void throwIfSimilarSubscriptionExists(final Subscription<? extends Notification> subscription) throws SubscriptionExistsAlreadyException {
		// FIXME: Implement.
		final Subscription<? extends Notification> existingSubscription = null;
		throw new SubscriptionExistsAlreadyException(existingSubscription);
	}
	
	/**
	 * Creates an {@link InitialSynchronizationNotification} for the given subscription, stores it and
	 * the subscription and commits the transaction.
	 * Takes care of all required synchronization
	 * @throws SubscriptionExistsAlreadyException If a subscription of the same type for the same client exists already.
	 */
	private synchronized void storeNewSubscriptionAndCommit(final Subscription<? extends Notification> subscription) throws SubscriptionExistsAlreadyException {
		throwIfSimilarSubscriptionExists(subscription);
		
		subscription.initializeTransient(mWoT); // takeFreeNotificationIndex requires transient init
		final InitialSynchronizationNotification notification = new InitialSynchronizationNotification(subscription);
		notification.initializeTransient(mWoT);
		notification.storeWithoutCommit();
		subscription.storeAndCommit();
	}
	
	public IdentityAttributeListSubscription subscribeToIdentityAttributeList(String fcpID) throws SubscriptionExistsAlreadyException {
		final IdentityAttributeListSubscription subscription = new IdentityAttributeListSubscription(fcpID);
		storeNewSubscriptionAndCommit(subscription);
		return subscription;
	}
	
	public IdentityListSubscription subscribeToIdentityList(String fcpID) throws SubscriptionExistsAlreadyException {
		final IdentityListSubscription subscription = new IdentityListSubscription(fcpID);
		storeNewSubscriptionAndCommit(subscription);
		return subscription;
	}
	
	public TrustListSubscription subscribeToTrustList(String fcpID) throws SubscriptionExistsAlreadyException {
		final TrustListSubscription subscription = new TrustListSubscription(fcpID);
		storeNewSubscriptionAndCommit(subscription);
		return subscription;
	}
	
	public ScoreListSubscription subscribeToScoreList(String fcpID) throws SubscriptionExistsAlreadyException {
		final ScoreListSubscription subscription = new ScoreListSubscription(fcpID);
		storeNewSubscriptionAndCommit(subscription);
		return subscription;
	}
	
	private ObjectSet<Subscription<? extends Notification>> getAllSubscriptions() {
		final Query q = mDB.query();
		q.constrain(Subscription.class);
		return new Persistent.InitializingObjectSet<Subscription<? extends Notification>>(mWoT, q);
	}
	
	private ObjectSet<? extends Subscription<? extends Notification>> getSubscriptions(final Class<? extends Subscription<? extends Notification>> clazz) {
		final Query q = mDB.query();
		q.constrain(clazz);
		return new Persistent.InitializingObjectSet<Subscription<? extends Notification>>(mWoT, q);
	}
	
	private synchronized final void deleteAllSubscriptions() {
		synchronized(mDB.lock()) {
			try {
				for(Subscription<? extends Notification> s : getAllSubscriptions()) {
					s.deleteWithoutCommit(this);
				}
				Persistent.checkedCommit(mDB, this);
			} catch(RuntimeException e) {
				Persistent.checkedRollbackAndThrow(mDB, this, e);
			}
		}
	}
	
	private ObjectSet<? extends Notification> getAllNotifications(final Subscription<? extends Notification> subscription) {
		final Query q = mDB.query();
		q.constrain(Notification.class);
		q.descend("mSubscription").constrain(subscription).identity();
		q.descend("mIndex").orderAscending();
		return new Persistent.InitializingObjectSet<Notification>(mWoT, q);
	}
	

	public int getPriority() {
		return NativeThread.LOW_PRIORITY;
	}
	
	protected void storeIdentityChangedNotificationWithoutCommit(final Identity identity) {
		@SuppressWarnings("unchecked")
		final ObjectSet<IdentityAttributeListSubscription> subscriptions = (ObjectSet<IdentityAttributeListSubscription>)getSubscriptions(IdentityAttributeListSubscription.class);
		
		for(IdentityAttributeListSubscription subscription : subscriptions) {
			subscription.storeNotificationWithoutCommit(identity);
		}
	}
	
	protected void storeNewIdentityNotificationWithoutCommit(final Identity identity) {
		@SuppressWarnings("unchecked")
		final ObjectSet<IdentityListSubscription> subscriptions = (ObjectSet<IdentityListSubscription>)getSubscriptions(IdentityListSubscription.class);
		
		for(IdentityListSubscription subscription : subscriptions) {
			subscription.storeNotificationWithoutCommit(identity);
		}
		
		storeIdentityChangedNotificationWithoutCommit(identity);
	}
	
	protected void storeTrustChangedNotificationWithoutCommit(final Trust trust) {
		@SuppressWarnings("unchecked")
		final ObjectSet<TrustListSubscription> subscriptions = (ObjectSet<TrustListSubscription>)getSubscriptions(TrustListSubscription.class);
		
		for(TrustListSubscription subscription : subscriptions) {
			subscription.storeNotificationWithoutCommit(trust);
		}
	}
	
	protected void storeScoreChangedNotificationWithoutCommit(final Score score) {
		@SuppressWarnings("unchecked")
		final ObjectSet<ScoreListSubscription> subscriptions = (ObjectSet<ScoreListSubscription>)getSubscriptions(ScoreListSubscription.class);
		
		for(ScoreListSubscription subscription : subscriptions) {
			subscription.storeNotificationWithoutCommit(score);
		}
	}

	/**
	 * Processes the notifications of each {@link Subscription}
	 * If deploying the notifications of a Subscription fails, processing is re-scheduled and the next
	 * Subscription is processed.
	 */
	public void run() {
		synchronized(mWoT) {
		synchronized(this) {
			for(Subscription<? extends Notification> subscription : getAllSubscriptions()) {
				try {
					subscription.sendNotifications(this);
					Persistent.checkedCommit(mDB, this);
				} catch(Exception e) {
					Persistent.checkedRollback(mDB, this, e);
					// FIXME: After a certain number of retries, delete the subscription
					scheduleNotificationProcessing();
				}
			}
		}
		}
	}
	
	/**
	 * Schedules the {@link run()} method to be executed after a delay of {@link PROCESS_NOTIFICATIONS_DELY}
	 */
	private void scheduleNotificationProcessing() {
		if(mTicker != null)
			mTicker.queueTimedJob(this, "WoT SubscriptionManager", PROCESS_NOTIFICATIONS_DELAY, false, true);
		else
			Logger.warning(this, "Cannot schedule notification processing: Ticker is null.");
	}
	
	/**
	 * Shuts down this SubscriptionManager by aborting all queued notification processing and waiting for running
	 * processing to finish.
	 */
	protected synchronized void stop() {
		Logger.normal(this, "Aborting all pending notifications");
		
		mTicker.shutdown();
		
		Logger.normal(this, "Stopped.");
	}
}
