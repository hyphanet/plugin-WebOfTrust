/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package plugins.WebOfTrust;

import java.util.UUID;

import plugins.WebOfTrust.exceptions.DuplicateObjectException;

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
 * The class/object model is as following:
 * - There is exactly one SubscriptionManager object running in the WOT plugin. It is the interface for clients.
 * - Subscribing to something yields a {@link Subscription} object which is stored by the SubscriptionManager in the database. Clients do not need to keep track of it. They only need to know its ID.
 * - When an event happens, a {@link Notification} object is created for each {@link Subscription} which matches the type of event. The Notification is stored in the database.
 * - After a delay, the SubscriptionManager deploys the notifications to the clients.
 * 
 * The {@link Notification}s are deployed strictly sequential per {@link Subscription}.
 * If a single Notification cannot be deployed, the processing of the Notifications for that Subscription is halted until the failed
 * Notification can be deployed successfully.
 * This especially applies to the FCP-client-interface and all other client interfaces: If a client returns "ERROR!" from the callback it has received,
 * the notification queue is halted and the previous notification is re-sent a few times until it can be imported successfully by the client
 * or the connection is dropped due to too many failures.
 * 
 * TODO: What to do, if the client does not support a certain message?
 * 
 * This is a very important principle which makes client design easy: You do not need transaction-safety when caching things such as score values
 * incrementally. For example your client might need to do mandatory actions due to a score-value change, such as deleting messages from identities
 * which have a bad score now. If the score-value import succeeds but the message deletion fails, you can just return "ERROR!" to the WOT-callback-caller
 * (and maybe even keep your score-cache as is) - you will continue to receive the notification about the changed score value for which the import failed,
 * you will not receive change-notifications after that. This ensures that your consistency is not destroyed: There will be no missing slot
 * in the incremental change chain.
 * FIXME: AFAIK the FCP-client does not support the above yet, this is a MUST-have feature needed by Freetalk, check whether it works.
 * 
 * TODO: Allow out-of-order notifications if the client desires them
 * TODO: Optimization: Allow coalescing of notifications: If a single object changes twice, only send one notification
 * TODO: Optimization: Allow the client to specify filters to reduce traffic: - Context of identities, etc. 
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
	 * 
	 * Notice: Even though this is an abstract class, it contains code specific <b>all</>b> types of subscription clients such as FCP and callback.
	 * At first glance, this looks like a violation of abstraction principles. But it is not:
	 * Subclasses of this class shall NOT be created for different types of clients such as FCP and callbacks.
	 * Subclasses are created for different types of content to which the subscriber is subscribed: There is a subclass for subscriptions to the
	 * list of {@link Identity}s, the list of {@link Trust}s, and so on. Each subclass has to implement the code for notifying <b>all</b> types
	 * of clients (FCP, callback, etc.).
	 * Therefore, this base class also contains code for <b>all</b> kinds of clients.
	 */
	public static abstract class Subscription<NotificationType extends Notification> extends Persistent {
		
		/**
		 * The UUID of this Subscription. Stored as String for db4o performance, but must be valid in terms of the UUID class.
		 * 
		 * @see #getID()
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
		 * The way the client of this subscription desires notification.
		 * 
		 * @see #getType()
		 */
		private final Type mType;
		
		/**
		 * An ID which associates this subscription with a FCP connection if the type is FCP.
		 * 
		 * FIXME: Rename to mFCP_ID because we don't use "key" anywhere. 
		 * 
		 * @see #getFCPKey()
		 */
		@IndexedField
		private final String mFCPKey;

		/**
		 * Each {@link Notification} is given an index upon creation. The indexes ensure sequential processing.
		 * If an error happens when trying to process a notification to a client, we might want to retry some time later.
		 * Therefore, the {@link Notification} queue exists per client and not globally - if a single {@link Notification} is created by WoT,
		 * multiple {@link Notification} objects are stored - one for each Subscription.
		 */
		private long mNextNotificationIndex = 0;
		
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
		
		/**
		 * {@inheritDoc}
		 */
		@Override
		public void startupDatabaseIntegrityTest() throws Exception {
			checkedActivate(1); // 1 is the maximum needed depth of all stuff we use in this function
			
			IfNull.thenThrow(mID, "mID");
			UUID.fromString(mID); // Throws if invalid
			
			IfNull.thenThrow(mType, "mType");
			
			if(mType == Type.FCP)
				IfNull.thenThrow(mFCPKey, "mFCPKey");
			
			if(mNextNotificationIndex < 0)
				throw new IllegalStateException("mNextNotificationIndex==" + mNextNotificationIndex);
		}
		
		/**
		 * You must call {@link #initializeTransient} before using this!
		 */
		protected final SubscriptionManager getSubscriptionManager() {
			return mWebOfTrust.getSubscriptionManager();
		}
		
		/**
		 * @return The UUID of this Subscription. Stored as String for db4o performance, but must be valid in terms of the UUID class.
		 * @see #mID
		 */
		public final String getID() {
			checkedActivate(1);
			return mID;
		}
		
		/**
		 * @return The {@link Type} of this Subscription.
		 * @see #mType
		 */
		public final Type getType() {
			checkedActivate(1);
			return mType;
		}
		
		/**
		 * @return An ID which associates this subscription with a FCP connection if the type is FCP.
		 * @see #mFCPKey
		 */
		public final String getFCPKey() {
			if(getType() != Type.FCP)
				throw new UnsupportedOperationException("Type is not FCP:" + getType());
			
			checkedActivate(1);
			return mFCPKey;
		}
		
		/**
		 * Returns the next free index for a {@link Notification} in the queue of this Subscription
		 * and stores this Subscription object without committing the transaction.
		 * 
		 * Schedules processing of the Notifications of the SubscriptionManger.
		 */
		protected final long takeFreeNotificationIndexWithoutCommit() {
			checkedActivate(1);
			final long index = mNextNotificationIndex++;
			storeWithoutCommit();
			getSubscriptionManager().scheduleNotificationProcessing();
			return index;
		}
		
		/**
		 * @throws UnsupportedOperationException Is always thrown: Use {@link #deleteWithoutCommit(SubscriptionManager)} instead.
		 */
		@Override
		protected void deleteWithoutCommit() {
			throw new UnsupportedOperationException("Need a SubscriptionManager");
		}
		
		/**
		 * Deletes this Subscription and - using the passed in {@link SubscriptionManager} - also deletes all
		 * queued {@link Notification}s of it. Does not commit the transaction.
		 * 
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
			synchronized(Persistent.transactionLock(mDB)) {
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
		 * 
		 * Upon creation of a Subscription, such a notification is stored immediately, i.e. not triggered by an event.
		 * When real events happen, we only want to send a "diff" between the last state of the database before the event happened and the new state.
		 * For being able to only send a diff, the subscriber must know what the <i>initial</i> state of the database was.
		 * And thats the purpose of this function: To send the full initial state of the database to the client.
		 * 
		 * For example, if a client subscribes to the list of identities, it must always receive a full list of all existing identities at first.
		 * As new identities appear afterwards, the client can be kept up to date by sending each single new identity as it appears. 
		 * 
		 * @see InitialSynchronizationNotification
		 */
		protected abstract void synchronizeSubscriberByFCP() throws Exception;

		/**
		 * Called by this Subscription when the type of it is FCP and a {@link Notification} shall be sent via FCP. 
		 * The implementation MUST throw a {@link RuntimeException} if the FCP message was not sent successfully: 
		 * Subscriptions are supposed to be reliable, if transmitting a {@link Notification} fails it shall
		 * be resent.
		 * 
		 * @param notification The {@link Notification} to send out via FCP.
		 */
		protected abstract void notifySubscriberByFCP(NotificationType notification) throws Exception;

		/**
		 * Sends out the notification queue for this Subscription, in sequence.
		 * 
		 * If a notification is sent successfully, it is deleted and the transaction is committed.
		 * If sending a single notification fails, the transaction for the current Notification is rolled back
		 * and an exception is thrown.
		 * 
		 * You have to synchronize on the WoT, the SubscriptionManager and the database lock before calling this
		 * function!
		 * You don't have to commit the transaction after calling this function.
		 * 
		 * @param manager The {@link SubscriptionManager} from which to query the {@link Notification}s of this Subscription.
		 */
		@SuppressWarnings("unchecked")
		protected void sendNotifications(SubscriptionManager manager) {
			switch(mType) {
				case FCP:
					for(final Notification notification : manager.getAllNotifications(this)) {
						try {
							try {
								if(notification instanceof InitialSynchronizationNotification) {
									synchronizeSubscriberByFCP();
								} else {
									notifySubscriberByFCP((NotificationType)notification);
									notification.deleteWithoutCommit();
								}
							} catch(Exception e) {
								// Disconnected etc.
								throw new RuntimeException(e);
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
		private final long mIndex;
		
		/**
		 * Constructs a Notification in the queue of the given subscription.
		 * Takes a free notification index from it with {@link Subscription#takeFreeNotificationIndexWithoutCommit}
		 * 
		 * @param mySubscription The {@link Subscription} to whose Notification queue this Notification belongs.
		 */
		protected Notification(final Subscription<? extends Notification> mySubscription) {
			mSubscription = mySubscription;
			mIndex = mySubscription.takeFreeNotificationIndexWithoutCommit();
		}
		
		/**
		 * {@inheritDoc}
		 */
		@Override
		public void startupDatabaseIntegrityTest() throws Exception {
			checkedActivate(1); // 1 is the maximum needed depth of all stuff we use in this function
			
			IfNull.thenThrow(mSubscription);
			
			if(mIndex < 0)
				throw new IllegalStateException("mIndex==" + mIndex);
		}
		
	}
	
	/**
	 * This {@link Notification} is stored as the first Notification for all types of {@link Subscription} which require an initial synchronization of the client.
	 * 
	 * Upon creation of a {@link Subscription}, such a notification is stored immediately, i.e. not triggered by an event.
	 * When real events happen, we only want to send a "diff" between the last state of the database before the event happened and the new state.
	 * For being able to only send a diff, the subscriber must know what the <i>initial</i> state of the database was.
	 * And thats the purpose of this notification: To send the full initial state of the database to the client.
	 * 
	 * For example, if a client subscribes to the list of identities, it must always receive a full list of all existing identities at first.
	 * As new identities appear afterwards, the client can be kept up to date by sending each single new identity as it appears.
	 * 
	 * @see Subscription#synchronizeSubscriberByFCP() The function which typically deploys this Notification.
	 */
	protected static class InitialSynchronizationNotification extends Notification {
		
		/**
		 * @param mySubscription The {@link Subscription} to whose {@link Notification} queue this {@link Notification}  will belong.
		 */
		protected InitialSynchronizationNotification(Subscription<? extends Notification> mySubscription) {
			super(mySubscription);
		}
		
	}
	
	/**
	 * This notification is issued when the attributes of an identity change.
	 * It is also used as a base class for {@link IdentityListChangedNotification}.
	 * 
	 * FIXME: Rename to IdentityAttributesChangedNotification to be coherent with IdentityAttributeListSubscription.
	 */
	protected static class IdentityChangedNotification extends Notification {
		
		/**
		 * The ID of the identity which has changed.
		 * 
		 * @see Identity#getID()
		 * @see #getIdentityID()
		 */
		final String mIdentityID;
	
		/**
		 * @param mySubscription The {@link Subscription} to whose {@link Notification} queue this {@link Notification} belongs.
		 * @param myIdentity The {@link Identity} which has changed.
		 */
		protected IdentityChangedNotification(final Subscription<? extends IdentityChangedNotification> mySubscription, Identity myIdentity) {
			super(mySubscription);
			mIdentityID = myIdentity.getID();
		}
		
		/**
		 * {@inheritDoc}
		 */
		@Override
		public void startupDatabaseIntegrityTest() throws Exception {
			super.startupDatabaseIntegrityTest();
			
			checkedActivate(1); // 1 is the maximum needed depth of all stuff we use in this function
			
			IfNull.thenThrow(mIdentityID, "mIdentityID");
		}
		
		/**
		 * @return The ID of the identity which has changed.
		 * @see Identity#getID()
		 * @see #mIdentityID
		 */
		protected final String getIdentityID() {
			checkedActivate(1);
			return mIdentityID;
		}

	}

	/**
	 * This notification is issued when a new identity is discovered or deleted.
	 * FIXME: What about restoreOwnIdentity / deleteOwnIdentity? They replace an OwnIdentity object with an Identity object and vice versa.
	 */
	protected static final class IdentityListChangedNotification extends IdentityChangedNotification {		
		
		/**
		 * @param mySubscription The {@link Subscription} to whose {@link Notification} queue this {@link Notification} belongs.
		 * @param myIdentity The {@link Identity} which was added or deleted.
		 */
		protected IdentityListChangedNotification(final Subscription<IdentityListChangedNotification> mySubscription, Identity myIdentity) {
			super(mySubscription, myIdentity);
		}
		
	}
	
	/**
	 * This notification is issued when a {@link Trust} is added, deleted or changed.
	 * FIXME: We use IdentityChangedNotification if the attributes of an Identity have changed and IdentityListChangedNotification if an identity
	 * 		is added or deleted. But we use TrustChangedNotification for all cases. This decoherent. Either get rid of the two Identity notification
	 * 		types or introduce two types for Trust. Same applies to ScoreChangedNotification.
	 */
	protected static final class TrustChangedNotification extends Notification {
		
		/**
		 * The ID of the truster of the changed {@link Trust}.
		 * 
		 * @see Identity#getID()
		 * @see Trust#getTruster()
		 * @see #getTrusterID()
		 */
		private final String mTrusterID;
		
		/**
		 * The ID of the trustee of the changed {@link Trust}.
		 * 
		 * @see Identity#getID()
		 * @see Trust#getTrustee()
		 * @see #getTrusteeID()
		 */
		private final String mTrusteeID;
		
		/**
		 * @param mySubscription The {@link Subscription} to whose {@link Notification} queue this {@link Notification} belongs.
		 * @param myTrust The {@link Trust} which was added, removed or changed.
		 */
		protected TrustChangedNotification(final Subscription<TrustChangedNotification> mySubscription, Trust myTrust) {
			super(mySubscription);
			mTrusterID = myTrust.getTruster().getID();
			mTrusteeID = myTrust.getTrustee().getID();
		}
		
		/**
		 * {@inheritDoc}
		 */
		@Override
		public void startupDatabaseIntegrityTest() throws Exception {
			super.startupDatabaseIntegrityTest();
			
			checkedActivate(1); // 1 is the maximum needed depth of all stuff we use in this function
			
			IfNull.thenThrow(mTrusterID, "mTrusterID");
			IfNull.thenThrow(mTrusteeID, "mTrusteeID");
		}

		/**
		 * The ID of the truster of the changed {@link Trust}.
		 * 
		 * @see Identity#getID()
		 * @see Trust#getTruster()
		 * @see #mTrusterID
		 */
		protected final String getTrusterID() {
			checkedActivate(1);
			return mTrusterID;
		}

		/**
		 * The ID of the trustee of the changed {@link Trust}.
		 * 
		 * @see Identity#getID()
		 * @see Trust#getTrustee()
		 * @see #mTrusteeID
		 */
		protected final String getTrusteeID() {
			checkedActivate(1);
			return mTrusteeID;
		}
		
	}
	
	/**
	 * This notification is issued when a score value is added, deleted or changed.
	 * FIXME: We use IdentityChangedNotification if the attributes of an Identity have changed and IdentityListChangedNotification if an identity
	 * 		is added or deleted. But we use ScoreChangedNotification for all cases. This decoherent. Either get rid of the two Identity notification
	 * 		types or introduce two types for Score. Same applies to TrustChangedNotification.
	 */
	protected static final class ScoreChangedNotification extends Notification {
		
		/**
		 * The ID of the truster of the changed {@link Score}.
		 * 
		 * @see Identity#getID()
		 * @see Score#getTruster()
		 * @see #getTrusterID()
		 */
		private final String mTrusterID;
		
		/**
		 * The ID of the trustee of the changed {@link Score}.
		 * 
		 * @see Identity#getID()
		 * @see Score#getTrustee()
		 * @see #getTrusteeID()
		 */
		private final String mTrusteeID;

		/**
		 * @param mySubscription The {@link Subscription} to whose {@link Notification} queue this {@link Notification} belongs.
		 * @param myScore The {@link Score} which was added, deleted or changed.
		 */
		protected ScoreChangedNotification(final Subscription<ScoreChangedNotification> mySubscription, Score myScore) {
			super(mySubscription);
			mTrusterID = myScore.getTruster().getID();
			mTrusteeID = myScore.getTrustee().getID();
		}
		
		/**
		 * {@inheritDoc}
		 */
		@Override
		public void startupDatabaseIntegrityTest() throws Exception {
			super.startupDatabaseIntegrityTest();
			
			checkedActivate(1); // 1 is the maximum needed depth of all stuff we use in this function
			
			IfNull.thenThrow(mTrusterID, "mTrusterID");
			IfNull.thenThrow(mTrusteeID, "mTrusteeID");
		}
		
		/**
		 * The ID of the truster of the changed {@link Score}.
		 * 
		 * @see Identity#getID()
		 * @see Score#getTruster()
		 * @see #mTrusterID
		 */
		protected final String getTrusterID() {
			checkedActivate(1);
			return mTrusterID;
		}
		
		
		/**
		 * The ID of the trustee of the changed {@link Score}.
		 * 
		 * @see Identity#getID()
		 * @see Score#getTrustee()
		 * @see #mTrusteeID
		 */
		protected final String getTrusteeID() {
			checkedActivate(1);
			return mTrusteeID;
		}
	}

	/**
	 * A subscription to the attributes of all identities.
	 * 
	 * The attributes are things such as the nickname, the URIs, the contexts, the properties
	 * - Basically anything which you can get from an {@link Identity} object.
	 * 
	 * If the attributes of an identity change, the subscriber gets notified by a {@link IdentityChangedNotification}.
	 * The subscriber will also get notified if a new identity is created or if an identity is deleted. FIXME: Is this correct? We have IdentityListSubscription for this. 
	 * 
	 * TODO: Does this effectively send an ID object?
	 */
	public static final class IdentityAttributeListSubscription extends Subscription<IdentityChangedNotification> {

		/**
		 * @param fcpID See {@link Subscription#getFCPKey()}. 
		 */
		protected IdentityAttributeListSubscription(String fcpID) {
			super(Subscription.Type.FCP, fcpID);
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		protected void synchronizeSubscriberByFCP() throws Exception {
			mWebOfTrust.getFCPInterface().sendAllIdentities(getFCPKey());
		}
		
		/**
		 * {@inheritDoc}
		 */
		@Override
		protected void notifySubscriberByFCP(IdentityChangedNotification notification) throws Exception {
			mWebOfTrust.getFCPInterface().sendIdentityChangedNotification(getFCPKey(), notification.getIdentityID());
		}

		/**
		 * Stores a {@link IdentityChangedNotification} to the {@link Notification} queue of this {@link Subscription}.
		 * 
		 * @param identity The {@link Identity} whose attributes have changed.
		 */
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
	public static final class IdentityListSubscription extends Subscription<IdentityListChangedNotification> {

		/**
		 * @param fcpID See {@link Subscription#getFCPKey()}. 
		 */
		protected IdentityListSubscription(String fcpID) {
			super(Subscription.Type.FCP, fcpID);
		}
		
		/**
		 * {@inheritDoc}
		 */
		@Override
		protected void synchronizeSubscriberByFCP() throws Exception {
			mWebOfTrust.getFCPInterface().sendAllIdentities(getFCPKey());
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		protected void notifySubscriberByFCP(IdentityListChangedNotification notification) throws Exception {
			mWebOfTrust.getFCPInterface().sendIdentityListChangedNotification(getFCPKey(), notification.getIdentityID());
		}

		/**
		 * Stores a {@link IdentityListChangedNotification} to the {@link Notification} queue of this {@link Subscription}.
		 * 
		 * @param identity The {@link Identity} which was added or deleted.
		 */
		public void storeNotificationWithoutCommit(Identity identity) {
			final IdentityListChangedNotification notification = new IdentityListChangedNotification(this, identity);
			notification.initializeTransient(mWebOfTrust);
			notification.storeWithoutCommit();
		}
		
	}
	
	/**
	 * A subscription to the list of trust values.
	 * If a trust value gets changed, is added or deleted, the subscriber is notified.
	 */
	public static final class TrustListSubscription extends Subscription<TrustChangedNotification> {

		/**
		 * @param fcpID See {@link Subscription#getFCPKey()}. 
		 */
		protected TrustListSubscription(String fcpID) {
			super(Subscription.Type.FCP, fcpID);
		}
		
		/**
		 * {@inheritDoc}
		 */
		@Override
		protected void synchronizeSubscriberByFCP() throws Exception {
			mWebOfTrust.getFCPInterface().sendAllTrustValues(getFCPKey());
		}
		
		/**
		 * {@inheritDoc}
		 */
		@Override
		protected void notifySubscriberByFCP(TrustChangedNotification notification) throws Exception {
			mWebOfTrust.getFCPInterface().sendTrustChangedNotification(getFCPKey(), notification.getTrusterID(), notification.getTrusteeID());
		}

		/**
		 * Stores a {@link TrustChangedNotification} to the {@link Notification} queue of this {@link Subscription}.
		 * 
		 * @param trust The {@link Trust} which was added, changed deleted.
		 */
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

		/**
		 * @param fcpID See {@link Subscription#getFCPKey()}. 
		 */
		protected ScoreListSubscription(String fcpID) {
			super(Subscription.Type.FCP, fcpID);
		}
		
		/**
		 * {@inheritDoc}
		 */
		@Override
		protected void synchronizeSubscriberByFCP() throws Exception {
			mWebOfTrust.getFCPInterface().sendAllScoreValues(getFCPKey());
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		protected void notifySubscriberByFCP(ScoreChangedNotification notification) throws Exception {
			mWebOfTrust.getFCPInterface().sendScoreChangedNotification(getFCPKey(), notification.getTrusterID(), notification.getTrusteeID());
		}

		/**
		 * Stores a {@link ScoreChangedNotification} to the {@link Notification} queue of this {@link Subscription}.
		 * 
		 * @param score The {@link Score} which was added, changed deleted.
		 */
		public void storeNotificationWithoutCommit(Score score) {
			final ScoreChangedNotification notification = new ScoreChangedNotification(this, score);
			notification.initializeTransient(mWebOfTrust);
			notification.storeWithoutCommit();
		}

	}

	
	/**
	 * After a {@link Notification} command is stored, we wait this amount of time before processing it.
	 * This is to allow some coalescing when multiple notifications happen in a short interval.
	 * This is usually the case as the import of trust lists often causes multiple changes. 
	 */
	private static final long PROCESS_NOTIFICATIONS_DELAY = 60 * 1000;
	
	
	/**
	 * The {@link WebOfTrust} to which this SubscriptionManager belongs.
	 */
	private final WebOfTrust mWoT;

	/**
	 * The database in which to store {@link Subscription} and {@link Notification} objects.
	 * Same as <code>mWoT.getDatabase();</code>
	 */
	private final ExtObjectContainer mDB;

	/**
	 * The SubscriptionManager schedules execution of its notification deployment thread on this {@link TrivialTicker}.
	 * The execution typically is scheduled after a delay of {@link #PROCESS_NOTIFICATIONS_DELAY}.
	 * 
	 * Is null until {@link #start()} was called.
	 */
	private TrivialTicker mTicker = null;
	
	
	/**
	 * Constructor both for regular in-node operation as well as operation in unit tests.
	 * 
	 * @param myWoT The {@link WebOfTrust} to which this SubscriptionManager belongs. Its {@link WebOfTrust#getPluginRespirator()} may return null in unit tests.
	 */
	public SubscriptionManager(WebOfTrust myWoT) {
		mWoT = myWoT;
		mDB = mWoT.getDatabase();
	}

	
	/**
	 * Thrown when a single client tries to file a {@link Subscription} of the same class of event {@link Notification} and the same {@link Subscription.Type} of notification deployment.
	 * 
	 * @see #throwIfSimilarSubscriptionExists
	 */
	@SuppressWarnings("serial")
	public static final class SubscriptionExistsAlreadyException extends Exception {
		public final Subscription<? extends Notification> existingSubscription;
		
		public SubscriptionExistsAlreadyException(Subscription<? extends Notification> existingSubscription) {
			this.existingSubscription = existingSubscription;
		}
	}
	
	/**
	 * Thrown by various functions which query the database for a certain {@link Subscription} if none exists matching the given filters.
	 */
	@SuppressWarnings("serial")
	public static final class UnknownSubscriptionException extends Exception {
		
		/**
		 * @param message A description of the filters which were set in the database query for the {@link Subscription}.
		 */
		public UnknownSubscriptionException(String message) {
			super(message);
		}
	}
	
	/**
	 * Throws when a single client tries to file a {@link Subscription} of the same class of event {@link Notification} and the same {@link Subscription.Type} of notification deployment.
	 * 
	 * @param subscription The new subscription which the client is trying to create. The database is checked for an existing one with similar properties as specified above.
	 * @see SubscriptionExistsAlreadyException
	 */
	@SuppressWarnings("unchecked")
	private synchronized void throwIfSimilarSubscriptionExists(final Subscription<? extends Notification> subscription) throws SubscriptionExistsAlreadyException {
		switch(subscription.getType()) {
			case FCP:
				try {
					throw new SubscriptionExistsAlreadyException(
								getSubscription((Class<? extends Subscription<? extends Notification>>)subscription.getClass(), subscription.getFCPKey())
							);
				} catch (UnknownSubscriptionException e) {
					return;
				}
			default:
				throw new UnsupportedOperationException("Unknown type: " + subscription.getType());
		}
	}
	
	/**
	 * Creates an {@link InitialSynchronizationNotification} for the given subscription, stores it and the subscription and commits the transaction.
	 * Takes care of all required synchronization.
	 * Shall be used as back-end for all front-end functions for creating subscriptions.
	 * 
	 * @throws SubscriptionExistsAlreadyException Thrown if a subscription of the same type for the same client exists already. See {@link #throwIfSimilarSubscriptionExists(Subscription)}
	 */
	private synchronized void storeNewSubscriptionAndCommit(final Subscription<? extends Notification> subscription) throws SubscriptionExistsAlreadyException {
		subscription.initializeTransient(mWoT);
		throwIfSimilarSubscriptionExists(subscription);
		
		final InitialSynchronizationNotification notification = new InitialSynchronizationNotification(subscription);
		notification.initializeTransient(mWoT);
		notification.storeWithoutCommit();
		subscription.storeAndCommit();
	}
	
	/**
	 * The client is notified when a new identity XML was fetched. TODO: Also notify if internal things such as edition change.
	 * 
	 * @param fcpID The identifier of the FCP connection of the client. Must be unique among all FCP connections!
	 */
	public IdentityAttributeListSubscription subscribeToIdentityAttributeList(String fcpID) throws SubscriptionExistsAlreadyException {
		final IdentityAttributeListSubscription subscription = new IdentityAttributeListSubscription(fcpID);
		storeNewSubscriptionAndCommit(subscription);
		return subscription;
	}
	
	/**
	 * The client is notified when an identity is created or deleted.
	 * 
	 * @param fcpID The identifier of the FCP connection of the client. Must be unique among all FCP connections!
	 */
	public IdentityListSubscription subscribeToIdentityList(String fcpID) throws SubscriptionExistsAlreadyException {
		final IdentityListSubscription subscription = new IdentityListSubscription(fcpID);
		storeNewSubscriptionAndCommit(subscription);
		return subscription;
	}
	
	/**
	 * The client is notified when a trust value changes, is created or removed.
	 * The client is NOT notified when the comment on a trust value changes.
	 * 
	 * @param fcpID The identifier of the FCP connection of the client. Must be unique among all FCP connections!
	 */
	public TrustListSubscription subscribeToTrustList(String fcpID) throws SubscriptionExistsAlreadyException {
		final TrustListSubscription subscription = new TrustListSubscription(fcpID);
		storeNewSubscriptionAndCommit(subscription);
		return subscription;
	}
	
	/**
	 * The client is notified when a score value changes, is created or removed.
	 * 
	 * @param fcpID The identifier of the FCP connection of the client. Must be unique among all FCP connections!
	 */
	public ScoreListSubscription subscribeToScoreList(String fcpID) throws SubscriptionExistsAlreadyException {
		final ScoreListSubscription subscription = new ScoreListSubscription(fcpID);
		storeNewSubscriptionAndCommit(subscription);
		return subscription;
	}
	
	/**
	 * Deletes the given {@link Subscription}.
	 * 
	 * @param subscriptionID See {@link Subscription#getID()}
	 * @throws UnknownSubscriptionException If no subscription with the given ID exists.
	 */
	public void unsubscribe(String subscriptionID) throws UnknownSubscriptionException {
		synchronized(mWoT) { // FIXME: Remove this synchronization when resolving the inner FIXME
		synchronized(this) {
		final Subscription<? extends Notification> subscription = getSubscription(subscriptionID);
		synchronized(Persistent.transactionLock(mDB)) {
			try {
				// FIXME: This is debug code and removing it is a critical optimization:
				// To make debugging easier, we also send an final InitialSynchronizationNotification when disconnecting a client
				// instead of only sending one at the beginning of the connection.
				// Sending an InitialSynchronizationNotification usually sends the full stored dataset to synchronize the client.
				// The client can use it to check whether the state of WOT which he received through notifications was correct.
				{
					final InitialSynchronizationNotification notification = new InitialSynchronizationNotification(subscription);
					notification.initializeTransient(mWoT);
					notification.storeWithoutCommit();
					subscription.sendNotifications(this);
				}

				subscription.deleteWithoutCommit(this);
				Persistent.checkedCommit(mDB, this);
			} catch(RuntimeException e) {
				Persistent.checkedRollbackAndThrow(mDB, this, e);
			}
		}
		}
		}
	}
	
	/**
	 * Typically used at startup by {@link #deleteAllSubscriptions()} and {@link #run()}
	 * 
	 * @return All existing {@link Subscription}s.
	 */
	private ObjectSet<Subscription<? extends Notification>> getAllSubscriptions() {
		final Query q = mDB.query();
		q.constrain(Subscription.class);
		return new Persistent.InitializingObjectSet<Subscription<? extends Notification>>(mWoT, q);
	}
	
	/**
	 * Get all {@link Subscription}s to a certain {@link Notification} type.
	 * 
	 * Typically used by the functions store*NotificationWithoutCommit() for storing the given {@link Notification} to the queues of all Subscriptions
	 * which are subscribed to the type of the given notification.
	 * 
	 * @param clazz The type of {@link Notification} to filter by.
	 * @return Get all {@link Subscription}s to a certain {@link Notification} type.
	 */
	private ObjectSet<? extends Subscription<? extends Notification>> getSubscriptions(final Class<? extends Subscription<? extends Notification>> clazz) {
		final Query q = mDB.query();
		q.constrain(clazz);
		return new Persistent.InitializingObjectSet<Subscription<? extends Notification>>(mWoT, q);
	}
	
	/**
	 * @param id The unique identificator of the desired {@link Subscription}. See {@link Subscription#getID()}.
	 * @return The {@link Subscription} with the given ID. Only one Subscription can exist for a single ID.
	 * @throws UnknownSubscriptionException If no {@link Subscription} exists with the given ID.
	 */
	private Subscription<? extends Notification> getSubscription(final String id) throws UnknownSubscriptionException {
		final Query q = mDB.query();
		q.constrain(Subscription.class);
		q.descend("mID").constrain(id);	
		ObjectSet<Subscription<? extends Notification>> result = new Persistent.InitializingObjectSet<Subscription<? extends Notification>>(mWoT, q);
		
		switch(result.size()) {
			case 1: return result.next();
			case 0: throw new UnknownSubscriptionException(id);
			default: throw new DuplicateObjectException(id);
		}
	}
	
	private Subscription<? extends Notification> getSubscription(final Class<? extends Subscription<? extends Notification>> clazz, String fcpKey) throws UnknownSubscriptionException {
		final Query q = mDB.query();
		q.constrain(clazz);
		q.descend("mFCPKey").constrain(fcpKey);		
		ObjectSet<Subscription<? extends Notification>> result = new Persistent.InitializingObjectSet<Subscription<? extends Notification>>(mWoT, q);
		
		switch(result.size()) {
			case 1: return result.next();
			case 0: throw new UnknownSubscriptionException(clazz.getSimpleName().toString() + " with fcpKey: " + fcpKey);
			default: throw new DuplicateObjectException(clazz.getSimpleName().toString() + " with fcpKey:" + fcpKey);
		}
	}
	
	private synchronized final void deleteAllSubscriptions() {
		Logger.normal(this, "Deleting all subscriptions...");
		
		synchronized(Persistent.transactionLock(mDB)) {
			try {
				for(Subscription<? extends Notification> s : getAllSubscriptions()) {
					s.deleteWithoutCommit(this);
				}
				Persistent.checkedCommit(mDB, this);
			} catch(RuntimeException e) {
				Persistent.checkedRollbackAndThrow(mDB, this, e);
			}
		}
		
		Logger.normal(this, "Finished deleting all subscriptions.");
	}
	
	private ObjectSet<? extends Notification> getAllNotifications(final Subscription<? extends Notification> subscription) {
		final Query q = mDB.query();
		q.constrain(Notification.class);
		q.descend("mSubscription").constrain(subscription).identity();
		q.descend("mIndex").orderAscending();
		return new Persistent.InitializingObjectSet<Notification>(mWoT, q);
	}
	
	/**
	 * This function does not store a reference to the given identity object in the database, it only stores the ID.
	 * You are safe to pass non-stored objects or objects which must not be stored.
	 */
	protected void storeIdentityChangedNotificationWithoutCommit(final Identity identity) {
		@SuppressWarnings("unchecked")
		final ObjectSet<IdentityAttributeListSubscription> subscriptions = (ObjectSet<IdentityAttributeListSubscription>)getSubscriptions(IdentityAttributeListSubscription.class);
		
		for(IdentityAttributeListSubscription subscription : subscriptions) {
			subscription.storeNotificationWithoutCommit(identity);
		}
	}
	
	/**
	 * This function does not store a reference to the given identity object in the database, it only stores the ID.
	 * You are safe to pass non-stored objects or objects which must not be stored.
	 */
	protected void storeNewIdentityNotificationWithoutCommit(final Identity identity) {
		@SuppressWarnings("unchecked")
		final ObjectSet<IdentityListSubscription> subscriptions = (ObjectSet<IdentityListSubscription>)getSubscriptions(IdentityListSubscription.class);
		
		for(IdentityListSubscription subscription : subscriptions) {
			subscription.storeNotificationWithoutCommit(identity);
		}
		
		storeIdentityChangedNotificationWithoutCommit(identity);
	}
	
	/**
	 * This function does not store a reference to the given identity object in the database, it only stores the ID.
	 * You are safe to pass non-stored objects or objects which must not be stored.
	 */
	protected void storeDeletedIdentityNotificationWithoutCommit(final Identity identity) {
		@SuppressWarnings("unchecked")
		final ObjectSet<IdentityListSubscription> subscriptions = (ObjectSet<IdentityListSubscription>)getSubscriptions(IdentityListSubscription.class);
		
		for(IdentityListSubscription subscription : subscriptions) {
			subscription.storeNotificationWithoutCommit(identity);
		}
		
		storeIdentityChangedNotificationWithoutCommit(identity);
	}
	
	/**
	 * This function does not store references to the passed objects in the database, it only stores their IDs.
	 * You are safe to pass non-stored objects or objects which must not be stored.
	 */
	protected void storeTrustChangedNotificationWithoutCommit(final Trust oldTrust, final Trust newTrust) {
		@SuppressWarnings("unchecked")
		final ObjectSet<TrustListSubscription> subscriptions = (ObjectSet<TrustListSubscription>)getSubscriptions(TrustListSubscription.class);
		
		for(TrustListSubscription subscription : subscriptions) {
			subscription.storeNotificationWithoutCommit(oldTrust);
		}
	}
	
	/**
	 * This function does not store references to the passed objects in the database, it only stores their IDs.
	 * You are safe to pass non-stored objects or objects which must not be stored.
	 */
	protected void storeScoreChangedNotificationWithoutCommit(final Score oldScore, final Score newScore) {
		@SuppressWarnings("unchecked")
		final ObjectSet<ScoreListSubscription> subscriptions = (ObjectSet<ScoreListSubscription>)getSubscriptions(ScoreListSubscription.class);
		
		for(ScoreListSubscription subscription : subscriptions) {
			subscription.storeNotificationWithoutCommit(oldScore);
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
					// Persistent.checkedCommit(mDB, this);	/* sendNotifications() does this already */
				} catch(Exception e) {
					Persistent.checkedRollback(mDB, this, e);
					// FIXME: After a certain number of retries, delete the subscription
					scheduleNotificationProcessing();
				}
			}
		}
		}
	}
	
	public int getPriority() {
		return NativeThread.LOW_PRIORITY;
	}
	
	/**
	 * Schedules the {@link #run()} method to be executed after a delay of {@link #PROCESS_NOTIFICATIONS_DELAY}
	 */
	private void scheduleNotificationProcessing() {
		if(mTicker != null)
			mTicker.queueTimedJob(this, "WoT SubscriptionManager", PROCESS_NOTIFICATIONS_DELAY, false, true);
		else
			Logger.warning(this, "Cannot schedule notification processing: Ticker is null.");
	}
	

	/**
	 * Deletes all old subscriptions and enables subscription processing. 
	 * 
	 * You must call this before any subscriptions are created, so for example before FCP is available.
	 * 
	 * Does NOT work in unit tests - you must manually trigger subscription processing by calling {@link #run()} there.
	 */
	protected synchronized void start() {
		deleteAllSubscriptions();
		
		final PluginRespirator respirator = mWoT.getPluginRespirator();
		
		if(respirator != null) { // We are connected to a node
			mTicker = new TrivialTicker(respirator.getNode().executor);
		} else { // We are inside of a unit test
			mTicker = null;
		}
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
