/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package plugins.WebOfTrust;

import java.io.Serializable;
import java.util.NoSuchElementException;
import java.util.UUID;

import plugins.WebOfTrust.exceptions.DuplicateObjectException;

import com.db4o.ObjectSet;
import com.db4o.ext.ExtObjectContainer;
import com.db4o.query.Query;

import freenet.node.PrioRunnable;
import freenet.node.fcp.FCPCallFailedException;
import freenet.pluginmanager.PluginNotFoundException;
import freenet.pluginmanager.PluginRespirator;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
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
 * - There is exactly one SubscriptionManager object running in the WOT plugin. It is the interface for {@link Client}s.
 * - Subscribing to something yields a {@link Subscription} object which is stored by the SubscriptionManager in the database. Clients do not need to keep track of it. They only need to know its ID.
 * - When an event happens, a {@link Notification} object is created for each {@link Subscription} which matches the type of event. The Notification is stored in the database.
 * - After a delay, the SubscriptionManager deploys the notifications to the clients.
 * 
 * The {@link Notification}s are deployed strictly sequential per {@link Client}.
 * If a single Notification cannot be deployed, the processing of the Notifications for that Client is halted until the failed Notification can
 * be deployed successfully. There will be {@link #DISCONNECT_CLIENT_AFTER_FAILURE_COUNT} retries, then the Client is disconnected.
 * 
 * Further, at each deployment run, the order of deployment is guaranteed to "make sense":
 * A {@link TrustChangedNotification} which creates a {@link Trust} will not deployed before the {@link IdentityChangedNotification} which creates
 * the identities which are referenced by the trust.
 * This allows you to assume that any identity IDs (see {@link Identity#getID()}} you receive in trust / score notifications are valid when you receive them.
 * 
 * This is a very important principle which makes client design easy: You do not need transaction-safety when caching things such as score values
 * incrementally. For example your client might need to do mandatory actions due to a score-value change, such as deleting messages from identities
 * which have a bad score now. If the score-value import succeeds but the message deletion fails, you can just return "ERROR!" to the WOT-callback-caller
 * (and maybe even keep your score-cache as is) - you will continue to receive the notification about the changed score value for which the import failed,
 * you will not receive change-notifications after that. This ensures that your consistency is not destroyed: There will be no missing slot
 * in the incremental change chain.
 * 
 * <b>Synchronization:</b>
 * The locking order must be:
 * 	synchronized(instance of WebOfTrust) {
 *	synchronized(instance of IntroductionPuzzleStore) {
 *	synchronized(instance of IdentityFetcher) {
 *	synchronized(instance of SubscriptionManager) {
 *	synchronized(Persistent.transactionLock(instance of ObjectContainer)) {
 * This does not mean that you need to take all of those locks when calling functions of the SubscriptionManager:
 * Its just the general order of locks which is used all over Web Of Trust to prevent deadlocks.
 * Any functions which require synchronization upon some of the locks will mention it.
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
	
	public static final class Client extends Persistent {
		
		/** @see Serializable */
		private static final long serialVersionUID = 1L;

		/**
		 * The way of notifying a client
		 */
		public static enum Type {
			FCP,
			Callback /** Not implemented yet. */
		};
		
		/**
		 * The way the client desires notification.
		 * 
		 * @see #getType()
		 */
		private final Type mType;
		
		/**
		 * An ID which associates this client with a FCP connection if the type is FCP.
		 * 
		 * @see #getFCP_ID()
		 */
		@IndexedField
		private final String mFCP_ID;

		/**
		 * Each {@link Notification} is given an index upon creation. The indexes ensure sequential processing.
		 * The indexed queue exists per {@link Client} and not per {@link Subscription}:
		 * Events of different types of {@link Subscription} might be dependent upon each other. 
		 * For example if we want to notify a client about a new trust value via {@link TrustChangedNotification}, it doesn't make
		 * sense to deploy such a notification if the identity which created the trust value does not exist yet.
		 * It must be guaranteed that the {@link IdentityChangedNotification} which creates the identity is deployed first.
		 * Events are issued by the core of WOT in proper order, so as long as we keep a queue per Client which preserves
		 * this order everything will be fine.
		 */
		private long mNextNotificationIndex = 0;
		
		/**
		 * If deploying the {@link Notification} queue fails, for example due to connectivity issues, this is incremented.
		 * After a retry limit of {@link SubscriptionManager#DISCONNECT_CLIENT_AFTER_FAILURE_COUNT}, the client will be disconnected.
		 */
		private byte mSendNotificationsFailureCount = 0;
		
		public Client(final String myFCP_ID) {
			mType = Type.FCP;
			mFCP_ID = myFCP_ID;
			
			assert(mFCP_ID != null && mFCP_ID.length() > 0);
		}
		
		/**
		 * {@inheritDoc}
		 */
		@Override
		public void startupDatabaseIntegrityTest() throws Exception {
			checkedActivate(1); // 1 is the maximum needed depth of all stuff we use in this function
			
			IfNull.thenThrow(mType, "mType");
			
			if(mType == Type.FCP)
				IfNull.thenThrow(mFCP_ID, "mFCP_ID");
			
			if(mNextNotificationIndex < 0)
				throw new IllegalStateException("mNextNotificationIndex==" + mNextNotificationIndex);
			
			if(mSendNotificationsFailureCount < 0 || mSendNotificationsFailureCount > SubscriptionManager.DISCONNECT_CLIENT_AFTER_FAILURE_COUNT)
				throw new IllegalStateException("mSendNotificationsFailureCount==" + mSendNotificationsFailureCount);
		}
		
		/**
		 * @throws UnsupportedOperationException Always because it is not implemented.
		 */
		@Override
		public final String getID() {
			throw new UnsupportedOperationException("Not implemented.");
		}
		
		/**
		 * You must call {@link #initializeTransient} before using this!
		 */
		protected final SubscriptionManager getSubscriptionManager() {
			return mWebOfTrust.getSubscriptionManager();
		}
				
		/**
		 * @return The {@link Type} of this Client.
		 * @see #mType
		 */
		public final Type getType() {
			checkedActivate(1);
			return mType;
		}
		
		/**
		 * @return An ID which associates this Client with a FCP connection if the type is FCP.
		 * @see #mFCP_ID
		 */
		public final String getFCP_ID() {
			if(getType() != Type.FCP)
				throw new UnsupportedOperationException("Type is not FCP:" + getType());
			
			checkedActivate(1);
			return mFCP_ID;
		}
		
		/**
		 * Returns the next free index for a {@link Notification} in the queue of this Client.
		 * 
		 * Stores this Client object without committing the transaction.
		 * Schedules processing of the Notifications of the SubscriptionManger via {@link SubscriptionManager#scheduleNotificationProcessing()}.
		 */
		protected final long takeFreeNotificationIndexWithoutCommit() {
			checkedActivate(1);
			final long index = mNextNotificationIndex++;
			storeWithoutCommit();
			getSubscriptionManager().scheduleNotificationProcessing();
			return index;
		}
		
		/**
		 * Increments {@link #mSendNotificationsFailureCount} and returns the new value.
		 * Use this for disconnecting a client if {@link #sendNotifications(SubscriptionManager)} has failed too many times.
		 * 
		 * @return The value of {@link #mSendNotificationsFailureCount} after incrementing it.
		 */
		private final byte incrementSendNotificationsFailureCountWithoutCommit()  {
			checkedActivate(1);
			++mSendNotificationsFailureCount;
			storeWithoutCommit();
			return mSendNotificationsFailureCount;
		}

		/**
		 * Sends out the notification queue for this Client, in sequence.
		 * 
		 * If a notification is sent successfully, it is deleted and the transaction is committed.
		 * 
		 * If sending a single notification fails, the failure counter {@link #mSendNotificationsFailureCount} is incremented
		 * and {@link SubscriptionManager#scheduleNotificationProcessing()} is executed to retry sending the notification after some time.
		 * If the failure counter exceeds the limit {@link SubscriptionManager#DISCONNECT_CLIENT_AFTER_FAILURE_COUNT}, false is returned
		 * to indicate that the SubscriptionManager should delete this Client.
		 * 
		 * You have to synchronize on the SubscriptionManager and the database lock before calling this function!
		 * You don't have to commit the transaction after calling this function.
		 * 
		 * @param manager The {@link SubscriptionManager} from which to query the {@link Notification}s of this Client.
		 * @return False if this Client should be deleted.
		 */
		protected boolean sendNotifications(SubscriptionManager manager) {
			if(SubscriptionManager.logMINOR) Logger.minor(manager, "sendNotifications() for " + this);
			
			switch(mType) {
				case FCP:
					for(final Notification notification : manager.getNotifications(this)) {
						if(SubscriptionManager.logDEBUG) Logger.debug(manager, "Sending notification via FCP: " + notification);
						try {
							try {
								notification.getSubscription().notifySubscriberByFCP(notification);
								notification.deleteWithoutCommit();
							} catch(Exception e) {
								Persistent.checkedRollback(mDB, this, e, LogLevel.WARNING);
								
								final byte failureCount = incrementSendNotificationsFailureCountWithoutCommit();
								Persistent.checkedCommit(mDB, this);
								
								boolean deleteSubscription = false;
								
								if(e instanceof PluginNotFoundException) {
									Logger.warning(manager, "sendNotifications() failed, client has disconnected, failure count: " + failureCount, e);
									deleteSubscription = true;
								} else  {
									Logger.error(manager, "sendNotifications() failed, failure count: " + failureCount, e);
									if(failureCount >= DISCONNECT_CLIENT_AFTER_FAILURE_COUNT) 
										deleteSubscription = true;
								}
								
								if(!deleteSubscription)
									manager.scheduleNotificationProcessing();
								
								return deleteSubscription;
								
							}
							// If processing of a single notification fails, we do not want the previous notifications
							// to be sent again when the failed notification is retried. Therefore, we commit after
							// each processed notification but do not catch RuntimeExceptions here
							
							Persistent.checkedCommit(mDB, this);
						} catch(RuntimeException e) {
							Persistent.checkedRollbackAndThrow(mDB, this, e);
						}
						if(SubscriptionManager.logDEBUG) Logger.debug(manager, "Sending notification via FCP finished: " + notification);
					}
					break;
				default:
					throw new UnsupportedOperationException("Unknown Type: " + mType);
			}
			
			return true;
		}
		
		/**
		 * Deletes this Client and also deletes all {@link Subscription} and {@link Notification} objects belonging to it.
		 * 
		 * @param subscriptionManager The {@link SubscriptionManager} to which this Client belongs.
		 */
		protected void deleteWithoutCommit(final SubscriptionManager subscriptionManager) {
			for(final Subscription<? extends Notification> subscription : subscriptionManager.getSubscriptions(this)) {
				subscription.deleteWithoutCommit(subscriptionManager);
			}
			super.deleteWithoutCommit();
		}
		
		@Override
		public String toString() {
			return super.toString() + " { Type=" + getType() + "; FCP ID=" + getFCP_ID() + " }"; 
		}
	}
	
	/**
	 * A subscription stores the information which client is subscribed to which content and how it is supposed
	 * to be notified about updates.
	 * For each {@link Client}, one subscription is stored one per {@link Notification}-type.
	 * A {@link Client} cannot have multiple subscriptions of the same type.
	 * 
	 * Notice: Even though this is an abstract class, it contains code specific <b>all</>b> types of subscription clients such as FCP and callback.
	 * At first glance, this looks like a violation of abstraction principles. But it is not:
	 * Subclasses of this class shall NOT be created for different types of clients such as FCP and callbacks.
	 * Subclasses are created for different types of content to which the subscriber is subscribed: There is a subclass for subscriptions to the
	 * list of {@link Identity}s, the list of {@link Trust}s, and so on. Each subclass has to implement the code for notifying <b>all</b> types
	 * of clients (FCP, callback, etc.).
	 * Therefore, this base class also contains code for <b>all</b> kinds of clients.
	 */
	@SuppressWarnings("serial")
	public static abstract class Subscription<NotificationType extends Notification> extends Persistent {
		
		/**
		 * The {@link Client} which created this {@link Subscription}.
		 */
		private final Client mClient;
		
		/**
		 * The UUID of this Subscription. Stored as String for db4o performance, but must be valid in terms of the UUID class.
		 * 
		 * @see #getID()
		 */
		@IndexedField
		private final String mID;
		
		/**
		 * Constructor for being used by child classes.
		 * @param myClient The {@link Client} to which this Subscription belongs.
		 */
		protected Subscription(final Client myClient) {
			mClient = myClient;
			mID = UUID.randomUUID().toString();
			
			assert(mClient != null);
		}
		
		/**
		 * {@inheritDoc}
		 */
		@Override
		public void startupDatabaseIntegrityTest() throws Exception {
			checkedActivate(1); // 1 is the maximum needed depth of all stuff we use in this function
			
			IfNull.thenThrow(mClient);
			
			IfNull.thenThrow(mID, "mID");
			UUID.fromString(mID); // Throws if invalid
		}
		
		/**
		 * You must call {@link #initializeTransient} before using this!
		 */
		protected final SubscriptionManager getSubscriptionManager() {
			return mWebOfTrust.getSubscriptionManager();
		}
		
		/**
		 * Gets the {@link Client} which created this {@link Subscription}
		 * @see #mClient
		 */
		protected final Client getClient() {
			checkedActivate(1);
			mClient.initializeTransient(mWebOfTrust);
			return mClient;
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
		 * ATTENTION: This does NOT delete the {@link Notification} objects associated with this Subscription!
		 * Only use it if you delete them manually before!
		 * 
		 * - Deletes this {@link Subscription} from the database. Does not commit the transaction and does not take care of synchronization.
		 */
		@Override
		protected void deleteWithoutCommit() {
			super.deleteWithoutCommit();
		}
		
		/**
		 * Deletes this Subscription and - using the passed in {@link SubscriptionManager} - also deletes all
		 * queued {@link Notification}s of it. Does not commit the transaction.
		 * 
		 * @param manager The {@link SubscriptionManager} to which this Subscription belongs.
		 */
		protected void deleteWithoutCommit(final SubscriptionManager manager) {
			for(final Notification notification : manager.getNotifications(this)) {
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
		 * Called by the {@link SubscriptionManager} before storing a new Subscription.
		 * 
		 * When real events happen, we only want to send a "diff" between the last state of the database before the event happened and the new state.
		 * For being able to only send a diff, the subscriber must know what the <i>initial</i> state of the database was.
		 * And thats the purpose of this function: To send the full initial state of the database to the client.
		 * 
		 * The implementation MUST throw a {@link FCPCallFailedException} if the client did not signal that the processing was successful:
		 * This will allow the client to use failing database transactions in the event handlers and just rollback and throw if the transaction fails. 
		 * The implementation MUST use synchronous FCP communication to allow the client to signal an error.
		 * Also, synchronous communication is necessary for guaranteeing the notifications to arrive after the synchronization at the client.
		 * 
		 * For example, if a client subscribes to the list of identities, it must always receive a full list of all existing identities at first.
		 * As new identities appear afterwards, the client can be kept up to date by sending each single new identity as it appears. 
		 * 
		 * Thread synchronization:
		 * This must be called with synchronization upon the {@link WebOfTrust} and the SubscriptionManager.
		 * Therefore it may perform database queries on the WebOfTrust to obtain the dataset.
		 * 
		 * @throws PluginNotFoundException If the FCP client has disconnected. Subscribing must fail if this happens.
		 * @throws FCPCallFailedException If processing failed at the client. Subscribing must fail if this happens.
		 */
		protected abstract void synchronizeSubscriberByFCP() throws FCPCallFailedException, PluginNotFoundException;

		/**
		 * Called by this Subscription when the type of it is FCP and a {@link Notification} shall be sent via FCP. 
		 * The implementation MUST throw a {@link FCPCallFailedException} if the client did not signal that the processing was successful:
		 * Not only shall the {@link Notification} be resent if transmission fails but also if the client fails processing it. This
		 * will allow the client to use failing database transactions in the event handlers and just rollback and throw if the transaction
		 * fails. 
		 * The implementation MUST use synchronous FCP communication to allow the client to signal an error.
		 * Also, synchronous communication is necessary for guaranteeing the notifications to arrive in proper order at the client.
		 * 
		 * <b>Thread synchronization:</b>
		 * This must be called with synchronization upon the SubscriptionManager.
		 * The {@link WebOfTrust} object shall NOT be locked:
		 * The {@link Notification} objects which this function receives contain serialized clones of the objects from WebOfTrust.
		 * Therefore, the notifications are self-contained and this function should and must NOT call any database query functions of the WebOfTrust. 
		 * 
		 * @param notification The {@link Notification} to send out via FCP. Must be cast-able to NotificationType.
		 * @throws PluginNotFoundException If the FCP client has disconnected. The SubscriptionManager then won't retry deploying this Notification, the {@link Subscription} will be terminated.
		 * @throws FCPCallFailedException If processing failed at the client.
		 */
		protected abstract void notifySubscriberByFCP(Notification notification) throws FCPCallFailedException, PluginNotFoundException;

		@Override
		public String toString() {
			return super.toString() + " { ID=" + getID() + "; Client=" + getClient() + " }";
		}
	}
	
	/**
	 * An object of type Notification is stored when an event happens to which a client is possibly subscribed.
	 * The SubscriptionManager will wake up some time after that, pull all notifications from the database and process them.
	 * 
	 * It provides two clones of the {@link Persistent} object about whose change the client shall be notified:
	 * - A version of it before the change via {@link Notification#getOldObject()}
	 * - A version of it after the change via {@link Notification#getNewObject()}
	 * 
	 * If one of the before/after getters returns null, this is because the object was added/deleted.
	 * If both do return an non-null object, the object was modified.
	 * NOTICE: Modification can also mean that its class has changed!
	 * 
	 * NOTICE: Both Persistent objects are not stored in the database and must not be stored there to prevent duplicates!
	 */
	@SuppressWarnings("serial")
	public static abstract class Notification extends Persistent {
		
		/**
		 * The {@link Client} to which this Notification belongs
		 */
		@IndexedField
		private final Client mClient;
		
		/**
		 * The {@link Subscription} to which this Notification belongs
		 */
		@IndexedField
		private final Subscription<? extends Notification> mSubscription;
		
		/**
		 * The index of this Notification in the queue of its {@link Client}:
		 * Notifications are supposed to be sent out in proper sequence, therefore we use incremental indices.
		 */
		@IndexedField
		private final long mIndex;
		
		
		/**
		 * A serialized copy of the changed {@link Persistent} object before the change.
		 * Null if the change was the creation of the object.
		 * If non-null its {@link Persistent#getID()} must be equal to the one of {@link #mNewObject} if that member is non-null as well.
		 * 
		 * @see Persistent#serialize()
		 * @see #getOldObject() The public getter for this.
		 */
		private final byte[] mOldObject;
		
		/**
		 * A serialized copy of the changed {@link Persistent} object after the change.
		 * Null if the change was the deletion of the object.
		 * If non-null its {@link Persistent#getID()} must be equal to the one of {@link #mOldObject} if that member is non-null as well.
		 * 
		 * @see Persistent#serialize()
		 * @see #getNewObject() The public getter for this.
		 */
		private final byte[] mNewObject;
		
		/**
		 * Constructs a Notification in the queue of the given Client.
		 * Takes a free notification index from it with {@link Client#takeFreeNotificationIndexWithoutCommit}
		 * 
		 * Only one of oldObject or newObject may be null.
		 * If both are non-null, their {@link Persistent#getID()} must be equal.
		 * 
		 * @param mySubscription The {@link Subscription} which requested this type of Notification.
		 * @param oldObject The version of the changed {@link Persistent} object before the change.
		 * @param newObject The version of the changed {@link Persistent} object after the change.
		 */
		protected Notification(final Subscription<? extends Notification> mySubscription,
				final Persistent oldObject, final Persistent newObject) {
			mSubscription = mySubscription;
			mClient = mSubscription.getClient();
			mIndex = mClient.takeFreeNotificationIndexWithoutCommit();
			
			assert	(
						(oldObject == null ^ newObject == null) ||
						(oldObject != null && newObject != null && oldObject.getID().equals(newObject.getID()))
					);
			
			mOldObject = (oldObject != null ? oldObject.serialize() : null);
			mNewObject = (newObject != null ? newObject.serialize() : null);
		}
		
		/**
		 * {@inheritDoc}
		 */
		@Override
		public void startupDatabaseIntegrityTest() throws Exception {
			checkedActivate(1); // 1 is the maximum needed depth of all stuff we use in this function
			
			IfNull.thenThrow(mClient, "mClient");
			IfNull.thenThrow(mSubscription, "mSubscription");
			
			if(mClient != getSubscription().getClient())
				throw new IllegalStateException("mClient does not match client of mSubscription");
			
			if(mIndex < 0)
				throw new IllegalStateException("mIndex==" + mIndex);
			
			if(mOldObject == null && mNewObject == null)
				throw new NullPointerException("Only one of mOldObject and mNewObject may be null!");

			if(mOldObject != null)
				getOldObject().startupDatabaseIntegrityTest();
			
			if(mNewObject != null)
				getNewObject().startupDatabaseIntegrityTest();

			if(mOldObject != null && mNewObject != null && !getOldObject().getID().equals(getNewObject().getID()))
				throw new IllegalStateException("The ID of mOldObject and mNewObject must match!");
		}
		
		/**
		 * @deprecated Not implemented because we don't need it.
		 */
		@Deprecated()
		public String getID() {
			throw new UnsupportedOperationException();
		}
		
		/**
		 * @return The {@link Subscription} which requested this type of Notification.
		 */
		public Subscription<? extends Notification> getSubscription() {
			checkedActivate(1);
			mSubscription.initializeTransient(mWebOfTrust);
			return mSubscription;
		}

		/**
		 * @return The changed {@link Persistent} object before the change. Null if the change was the creation of the object.
		 * @see #mOldObject The backend member variable of this getter.
		 */
		public final Persistent getOldObject() throws NoSuchElementException {
			checkedActivate(1);
			return mOldObject != null ? Persistent.deserialize(mWebOfTrust, mOldObject) : null;
		}
		
		/**
		 * @return The changed {@link Persistent} object after the change. Null if the change was the deletion of the object.
		 * @see #mNewObject The backend member variable of this getter.
		 */
		public final Persistent getNewObject() throws NoSuchElementException {
			checkedActivate(1);
			return mNewObject != null ? Persistent.deserialize(mWebOfTrust, mNewObject) : null;
		}

		@Override
		public String toString() {
			return super.toString() + " { oldObject=" + getOldObject() + "; newObject=" + getNewObject() + " }";
		}
	}
	
	/**
	 * This notification is issued when an {@link Identity} is added/deleted or its attributes change.
	 * 
	 * It provides a {@link Identity#clone()} of the identity:
	 * - before the change via {@link Notification#getOldObject()}
	 * - and and after the change via ({@link Notification#getNewObject()}
	 * 
	 * If one of the before/after getters returns null, this is because the identity was added/deleted.
	 * If both return an identity, the identity was modified.
	 * NOTICE: Modification can also mean that its class changed from {@link OwnIdentity} to {@link Identity} or vice versa!
	 * 
	 * NOTICE: Both Identity objects are not stored in the database and must not be stored there to prevent duplicates!
	 * 
	 * @see IdentitiesSubscription The type of {@link Subscription} which deploys this notification.
	 */
	@SuppressWarnings("serial")
	public static class IdentityChangedNotification extends Notification {

		/**
		 * Only one of oldIentity and newIdentity may be null. If both are non-null, their {@link Identity#getID()} must match.
		 * 
		 * @param mySubscription The {@link Subscription} to whose {@link Notification} queue this {@link Notification} belongs.
		 * @param oldIdentity The version of the {@link Identity} before the change.
		 * @param newIdentity The version of the {@link Identity} after the change.
		 */
		protected IdentityChangedNotification(final Subscription<? extends IdentityChangedNotification> mySubscription, 
				final Identity oldIdentity, final Identity newIdentity) {
			super(mySubscription, oldIdentity, newIdentity);
		}

	}
	
	/**
	 * This notification is issued when a {@link Trust} is added/deleted or its attributes change.
	 * 
	 * It provides a {@link Trust#clone()} of the trust:
	 * - before the change via {@link Notification#getOldObject()}
	 * - and and after the change via ({@link Notification#getNewObject()}
	 * 
	 * If one of the before/after getters returns null, this is because the trust was added/deleted.
	 * 
	 * NOTICE: Both Trust objects are not stored in the database and must not be stored there to prevent duplicates!
	 * 
	 * @see TrustsSubscription The type of {@link Subscription} which deploys this notification.
	 */
	@SuppressWarnings("serial")
	public static final class TrustChangedNotification extends Notification {
		
		/**
		 * Only one of oldTrust and newTrust may be null. If both are non-null, their {@link Trust#getID()} must match.
		 * 
		 * @param mySubscription The {@link Subscription} to whose {@link Notification} queue this {@link Notification} belongs.
		 * @param oldTrust The version of the {@link Trust} before the change.
		 * @param newTrust The version of the {@link Trust} after the change.
		 */
		protected TrustChangedNotification(final Subscription<TrustChangedNotification> mySubscription, 
				final Trust oldTrust, final Trust newTrust) {
			super(mySubscription, oldTrust, newTrust);
		}
		
	}
	
	/**
	 * This notification is issued when a {@link Score} is added/deleted or its attributes change.
	 * 
	 * It provides a {@link Score#clone()} of the score:
	 * - before the change via {@link Notification#getOldObject()}
	 * - and and after the change via ({@link Notification#getNewObject()}
	 * 
	 * If one of the before/after getters returns null, this is because the score was added/deleted.
	 * 
	 * NOTICE: Both Score objects are not stored in the database and must not be stored there to prevent duplicates!
	 * 
	 * @see ScoresSubscription The type of {@link Subscription} which deploys this notification.
	 */
	@SuppressWarnings("serial")
	public static final class ScoreChangedNotification extends Notification {

		/**
		 * Only one of oldScore and newScore may be null. If both are non-null, their {@link Score#getID()} must match.
		 * 
		 * @param mySubscription The {@link Subscription} to whose {@link Notification} queue this {@link Notification} belongs.
		 * @param oldScore The version of the {@link Score} before the change.
		 * @param newScore The version of the {@link Score} after the change.
		 */
		protected ScoreChangedNotification(final Subscription<ScoreChangedNotification> mySubscription,
				final Score oldScore, final Score newScore) {
			super(mySubscription, oldScore, newScore);
		}

	}

	/**
	 * A subscription to the set of all {@link Identity} and {@link OwnIdentity} instances.
	 * If an identity gets added/deleted or if its attributes change the subscriber is notified by a {@link IdentityChangedNotification}.
	 * 
	 * @see IdentityChangedNotification The type of {@link Notification} which is deployed by this subscription.
	 */
	@SuppressWarnings("serial")
	public static final class IdentitiesSubscription extends Subscription<IdentityChangedNotification> {

		/**
		 * @param myClient The {@link Client} which created this Subscription. 
		 */
		protected IdentitiesSubscription(final Client myClient) {
			super(myClient);
		}

		/**
		 * {@inheritDoc} 
		 */
		@Override
		protected void synchronizeSubscriberByFCP() throws FCPCallFailedException, PluginNotFoundException {
			mWebOfTrust.getFCPInterface().sendAllIdentities(getClient().getFCP_ID());
		}
		
		/**
		 * {@inheritDoc}
		 */
		@Override
		protected void notifySubscriberByFCP(Notification notification) throws FCPCallFailedException, PluginNotFoundException {
			assert(notification instanceof IdentityChangedNotification);
			mWebOfTrust.getFCPInterface().sendIdentityChangedNotification(getClient().getFCP_ID(), (IdentityChangedNotification)notification);
		}

		/**
		 * Stores a {@link IdentityChangedNotification} to the {@link Notification} queue of this {@link Client}.
		 * 
		 * @param oldIdentity The version of the {@link Identity} before the change. Null if it was newly created.
		 * @param newIdentity The version of the {@link Identity} after the change. Null if it was deleted.
		 */
		private void storeNotificationWithoutCommit(Identity oldIdentity, Identity newIdentity) {
			final IdentityChangedNotification notification = new IdentityChangedNotification(this, oldIdentity, newIdentity);
			notification.initializeTransient(mWebOfTrust);
			notification.storeWithoutCommit();
		}

	}
	
	/**
	 * A subscription to the set of all {@link Trust} instances.
	 * If a trust gets added/deleted or if its attributes change the subscriber is notified by a {@link TrustChangedNotification}.
	 * 
	 * @see TrustChangedNotification The type of {@link Notification} which is deployed by this subscription.
	 */
	@SuppressWarnings("serial")
	public static final class TrustsSubscription extends Subscription<TrustChangedNotification> {

		/**
		 * @param myClient The {@link Client} which created this Subscription. 
		 */
		protected TrustsSubscription(final Client myClient) {
			super(myClient);
		}
		
		/**
		 * {@inheritDoc} 
		 */
		@Override
		protected void synchronizeSubscriberByFCP() throws FCPCallFailedException, PluginNotFoundException {
			mWebOfTrust.getFCPInterface().sendAllTrustValues(getClient().getFCP_ID());
		}
		
		/**
		 * {@inheritDoc} 
		 */
		@Override
		protected void notifySubscriberByFCP(final Notification notification) throws FCPCallFailedException, PluginNotFoundException {
			assert(notification instanceof TrustChangedNotification);
			mWebOfTrust.getFCPInterface().sendTrustChangedNotification(getClient().getFCP_ID(), (TrustChangedNotification)notification);
		}

		/**
		 * Stores a {@link TrustChangedNotification} to the {@link Notification} queue of this {@link Client}.
		 * 
		 * @param oldTrust The version of the {@link Trust} before the change. Null if it was newly created.
		 * @param newTrust The version of the {@link Trust} after the change. Null if it was deleted.
		 */
		public void storeNotificationWithoutCommit(final Trust oldTrust, final Trust newTrust) {
			final TrustChangedNotification notification = new TrustChangedNotification(this, oldTrust, newTrust);
			notification.initializeTransient(mWebOfTrust);
			notification.storeWithoutCommit();
		}

	}
	
	/**
	 * A subscription to the set of all {@link Score} instances.
	 * If a score gets added/deleted or if its attributes change the subscriber is notified by a {@link ScoreChangedNotification}.
	 * 
	 * @see ScoreChangedNotification The type of {@link Notification} which is deployed by this subscription.
	 */
	@SuppressWarnings("serial")
	public static final class ScoresSubscription extends Subscription<ScoreChangedNotification> {

		/**
		 * @param myClient The {@link Client} which created this Subscription.
		 */
		protected ScoresSubscription(final Client myClient) {
			super(myClient);
		}
		
		/**
		 * {@inheritDoc}
		 */
		@Override
		protected void synchronizeSubscriberByFCP() throws FCPCallFailedException, PluginNotFoundException {
			mWebOfTrust.getFCPInterface().sendAllScoreValues(getClient().getFCP_ID());
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		protected void notifySubscriberByFCP(final Notification notification) throws FCPCallFailedException, PluginNotFoundException {
			assert(notification instanceof ScoreChangedNotification);
			mWebOfTrust.getFCPInterface().sendScoreChangedNotification(getClient().getFCP_ID(), (ScoreChangedNotification)notification);
		}

		/**
		 * Stores a {@link ScoreChangedNotification} to the {@link Notification} queue of this {@link Client}.
		 * 
		 * @param oldScore The version of the {@link Score} before the change. Null if it was newly created.
		 * @param newScore The version of the {@link Score} after the change. Null if it was deleted.
		 */
		public void storeNotificationWithoutCommit(final Score oldScore, final Score newScore) {
			final ScoreChangedNotification notification = new ScoreChangedNotification(this, oldScore, newScore);
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
	 * If {@link Client#sendNotifications(SubscriptionManager)} fails, the failure counter of the subscription is incremented.
	 * If the counter reaches this value, the client is disconnected.
	 */
	private static final byte DISCONNECT_CLIENT_AFTER_FAILURE_COUNT = 5;
	
	
	/**
	 * The {@link WebOfTrust} to which this SubscriptionManager belongs.
	 */
	private final WebOfTrust mWoT;

	/**
	 * The database in which to store {@link Client}, {@link Subscription} and {@link Notification} objects.
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
	
	/** Automatically set to true by {@link Logger} if the log level is set to {@link LogLevel#DEBUG} for this class.
	 * Used as performance optimization to prevent construction of the log strings if it is not necessary. */
	private static transient volatile boolean logDEBUG = false;
	
	/** Automatically set to true by {@link Logger} if the log level is set to {@link LogLevel#MINOR} for this class.
	 * Used as performance optimization to prevent construction of the log strings if it is not necessary. */
	private static transient volatile boolean logMINOR = false;
	
	static {
		// Necessary for automatic setting of logDEBUG and logMINOR
		Logger.registerClass(SubscriptionManager.class);
	}
	
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
	 * Thrown when a single {@link Client} tries to file a {@link Subscription} of the same class of event {@link Notification}.
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
	 * Thrown by various functions which query the database for a certain {@link Client} if none exists matching the given filters.
	 */
	@SuppressWarnings("serial")
	public static final class UnknownClientException extends Exception {
		
		/**
		 * @param message A description of the filters which were set in the database query for the {@link Client}.
		 */
		public UnknownClientException(String message) {
			super(message);
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
	 * Throws when a single {@link Client} tries to file a {@link Subscription} which has the same class as the given Subscription and thereby the same class of event {@link Notification}.
	 * 
	 * Used to ensure that each client can only subscribe once to each type of event.
	 * 
	 * @param subscription The new subscription which the client is trying to create. The database is checked for an existing one with similar properties as specified above.
	 * @throws SubscriptionExistsAlreadyException If a {@link Subscription} exists which matches the attributes of the given Subscription as specified in the description of the function.
	 */
	@SuppressWarnings("unchecked")
	private synchronized void throwIfSimilarSubscriptionExists(final Subscription<? extends Notification> subscription) throws SubscriptionExistsAlreadyException {
		try {
			final Client client = subscription.getClient();
			if(!mDB.isStored(client))
				return; // The client was newly created just for this subscription so there cannot be any similar subscriptions on it. 
			final Subscription<? extends Notification> existing = getSubscription((Class<? extends Subscription<? extends Notification>>) subscription.getClass(), client);
			throw new SubscriptionExistsAlreadyException(existing);
		} catch (UnknownSubscriptionException e) {
			return;
		}
	}
	
	/**
	 * Calls {@link Subscription#synchronizeSubscriberByFCP()} on the Subscription, stores it and commits the transaction.
	 * 
	 * Takes care of all required synchronization.
	 * Shall be used as back-end for all front-end functions for creating subscriptions.
	 * 
	 * @throws SubscriptionExistsAlreadyException Thrown if a subscription of the same type for the same client exists already. See {@link #throwIfSimilarSubscriptionExists(Subscription)}
	 */
	private void storeNewSubscriptionAndCommit(final Subscription<? extends Notification> subscription) throws SubscriptionExistsAlreadyException {
		subscription.initializeTransient(mWoT);
		synchronized(mWoT) { // For synchronizeSubscriberByFCP()
		synchronized(this) {
			throwIfSimilarSubscriptionExists(subscription);
	
			try {
				subscription.synchronizeSubscriberByFCP();
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
			subscription.storeAndCommit();
			Logger.normal(this, "Subscribed: " + subscription);
		}
		}
	}
	
	/**
	 * The client is notified when an identity is added, changed or deleted.
	 * 
	 * Some of the changes which result in a notification:
	 * - Fetching of a new edition of an identity and all changes which result upon the {@link Identity} object because of that.
	 * - Change of contexts, see {@link Identity#mContexts}
	 * - Change of properties, see {@link Identity#mProperties}
	 * 
	 * Changes which do NOT result in a notification:
	 * - New trust value from an identity. Use {@link #subscribeToTrusts(String)} instead.
	 * - New edition hint for an identity. Edition hints are only useful to WOT, this shouldn't matter to clients. Also, edition hints are
	 *   created by other identities, not by the identity which is their subject. The identity itself did not change. 
	 * 
	 * @param fcpID The identifier of the FCP connection of the client. Must be unique among all FCP connections!
	 * @return The {@link IdentitiesSubscription} which is created by this function.
	 * @see IdentityChangedNotification The type of {@link Notification} which is sent when an event happens.
	 */
	public synchronized IdentitiesSubscription subscribeToIdentities(String fcpID) throws SubscriptionExistsAlreadyException {
		// We don't have to take the database lock because getOrCreateClient won't store it to the database yet
		// Storage will happen in storeNewSubscriptionAndCommit()
		final IdentitiesSubscription subscription = new IdentitiesSubscription(getOrCreateClient(fcpID));
		storeNewSubscriptionAndCommit(subscription);
		return subscription;
	}
	
	/**
	 * The client is notified when a trust value changes, is created or removed.
	 * The client is NOT notified when the comment on a trust value changes.
	 * 
	 * @param fcpID The identifier of the FCP connection of the client. Must be unique among all FCP connections!
	 * @return The {@link TrustsSubscription} which is created by this function.
	 * @see TrustChangedNotification The type of {@link Notification} which is sent when an event happens.
	 */
	public synchronized TrustsSubscription subscribeToTrusts(String fcpID) throws SubscriptionExistsAlreadyException {
		// We don't have to take the database lock because getOrCreateClient won't store it to the database yet
		// Storage will happen in storeNewSubscriptionAndCommit()
		final TrustsSubscription subscription = new TrustsSubscription(getOrCreateClient(fcpID));
		storeNewSubscriptionAndCommit(subscription);
		return subscription;
	}
	
	/**
	 * The client is notified when a score value changes, is created or removed.
	 * 
	 * @param fcpID The identifier of the FCP connection of the client. Must be unique among all FCP connections!
	 * @return The {@link ScoresSubscription} which is created by this function.
	 * @see ScoreChangedNotification The type of {@link Notification} which is sent when an event happens.
	 */
	public synchronized ScoresSubscription subscribeToScores(String fcpID) throws SubscriptionExistsAlreadyException {
		// We don't have to take the database lock because getOrCreateClient won't store it to the database yet
		// Storage will happen in storeNewSubscriptionAndCommit()
		final ScoresSubscription subscription = new ScoresSubscription(getOrCreateClient(fcpID));
		storeNewSubscriptionAndCommit(subscription);
		return subscription;
	}
	
	/**
	 * Deletes the given {@link Subscription}.
	 * 
	 * @param subscriptionID See {@link Subscription#getID()}
	 * @return The class of the terminated {@link Subscription}
	 * @throws UnknownSubscriptionException If no subscription with the given ID exists.
	 */
	@SuppressWarnings("unchecked")
	public Class<Subscription<? extends Notification>> unsubscribe(String subscriptionID) throws UnknownSubscriptionException {
		synchronized(this) {
		final Subscription<? extends Notification> subscription = getSubscription(subscriptionID);
		synchronized(Persistent.transactionLock(mDB)) {
			try {
				subscription.deleteWithoutCommit(this);
				
				final Client client = subscription.getClient();
				if(getSubscriptions(client).size() == 0) {
					Logger.normal(this, "Last subscription of client removed, deleting it: " + client);
					client.deleteWithoutCommit();
				}
				
				Persistent.checkedCommit(mDB, this);
				Logger.normal(this, "Unsubscribed: " + subscription);
			} catch(RuntimeException e) {
				Persistent.checkedRollbackAndThrow(mDB, this, e);
			}
		}
		return (Class<Subscription<? extends Notification>>) subscription.getClass();
		}
	}
	
	/**
	 * Typically used by {@link #run()}.
	 * 
	 * @return All existing {@link Client}s.
	 */
	private ObjectSet<Client> getAllClients() {
		final Query q = mDB.query();
		q.constrain(Client.class);
		return new Persistent.InitializingObjectSet<Client>(mWoT, q);
	}
	
	private Client getClient(final String fcpID) throws UnknownClientException {
		final Query q = mDB.query();
		q.constrain(Client.class);
		q.descend("mFCP_ID").constrain(fcpID);
		final ObjectSet<Client> result = new Persistent.InitializingObjectSet<Client>(mWoT, q);
		
		switch(result.size()) {
			case 1: return result.next();
			case 0: throw new UnknownClientException(fcpID);
			default: throw new DuplicateObjectException(fcpID);
		}
	}
	
	private Client getOrCreateClient(final String fcpID) {
		try {
			return getClient(fcpID);
		} catch(UnknownClientException e) {
			return new Client(fcpID);
		}
	}
	
	/**
	 * Typically used at startup by {@link #deleteAllClients()}.
	 * 
	 * @return All existing {@link Subscription}s.
	 */
	private ObjectSet<Subscription<? extends Notification>> getAllSubscriptions() {
		final Query q = mDB.query();
		q.constrain(Subscription.class);
		return new Persistent.InitializingObjectSet<Subscription<? extends Notification>>(mWoT, q);
	}
	
	private ObjectSet<Subscription<? extends Notification>> getSubscriptions(final Client client) {
		final Query q = mDB.query();
		q.constrain(Subscription.class);
		q.descend("mClient").constrain(client).identity();
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
	
	/**
	 * Gets a  {@link Subscription} which matches the given parameters:
	 * - the given class of Subscription and thereby event {@link Notification}
	 * - the given FCP identificator, see {@link Subscription#getFCP_ID()}.
	 * 
	 * Only one {@link Subscription} which matches both of these can exist: Each FCP client can only subscribe once to a type of event.
	 * 
	 * Typically used by {@link #throwIfSimilarSubscriptionExists(Subscription)}.
	 * 
	 * @param clazz The class of the Subscription.
	 * @param fcpID The identificator of the FCP connection. See {@link Subscription#getFCP_ID()}.
	 * @return See description.
	 * @throws UnknownSubscriptionException If no matching {@link Subscription} exists.
	 */
	private Subscription<? extends Notification> getSubscription(final Class<? extends Subscription<? extends Notification>> clazz, final Client client) throws UnknownSubscriptionException {
		final Query q = mDB.query();
		q.constrain(clazz);
		q.descend("mClient").constrain(client).identity();		
		ObjectSet<Subscription<? extends Notification>> result = new Persistent.InitializingObjectSet<Subscription<? extends Notification>>(mWoT, q);
		
		switch(result.size()) {
			case 1: return result.next();
			case 0: throw new UnknownSubscriptionException(clazz.getSimpleName().toString() + " with Client " + client);
			default: throw new DuplicateObjectException(clazz.getSimpleName().toString() + " with Client " + client);
		}
	}
	
	/**
	 * Deletes all existing {@link Client} objects.
	 * 
	 * As a consequence, all {@link Subscription} and {@link Notification} objects associated with the clients become useless and are also deleted.
	 * 
	 * Typically used at {@link #start()} - we lose connection to all clients when restarting so their subscriptions are worthless.
	 */
	private synchronized final void deleteAllClients() {
		Logger.normal(this, "Deleting all clients...");
		
		synchronized(Persistent.transactionLock(mDB)) {
			try {
				for(Notification n : getAllNotifications()) {
					n.deleteWithoutCommit();
				}
				
				for(Subscription<? extends Notification> s : getAllSubscriptions()) {
					s.deleteWithoutCommit();
				}
				
				for(Client client : getAllClients()) {
					client.deleteWithoutCommit();
				}
				Persistent.checkedCommit(mDB, this);
			} catch(RuntimeException e) {
				Persistent.checkedRollbackAndThrow(mDB, this, e);
			}
		}
		
		Logger.normal(this, "Finished deleting all clients.");
	}
	
	/**
	 * Typically used by {@link #deleteAllClients()}.
	 * 
	 * @return All objects of class Notification which are stored in the database.
	 */
	private ObjectSet<? extends Notification> getAllNotifications() {
		final Query q = mDB.query();
		q.constrain(Notification.class);
		return new Persistent.InitializingObjectSet<Notification>(mWoT, q);
	}
	
	/**
	 * Gets all {@link Notification} objects in the queue of the given {@link Subscription}.
	 * They are ordered ascending by the time of when the event which triggered them happened.
	 * 
	 * Precisely, they are ordered by their {@link Notification#mIndex}.
	 * 
	 * Typically used for:
	 * - Deploying the notification queue of a Subscription in {@link Subscription#sendNotifications(SubscriptionManager)}
	 * - Deleting a subscription in {@link Subscription#deleteWithoutCommit(SubscriptionManager)}
	 * 
	 * @param subscription The {@link Subscription} of whose queue to return notifications from.
	 * @return All {@link Notification}s on the queue of the subscription, ordered ascending by time of happening of their inducing event.
	 */
	private ObjectSet<? extends Notification> getNotifications(final Subscription<? extends Notification> subscription) {
		final Query q = mDB.query();
		q.constrain(Notification.class);
		q.descend("mSubscription").constrain(subscription).identity();
		q.descend("mIndex").orderAscending();
		return new Persistent.InitializingObjectSet<Notification>(mWoT, q);
	}
	
	private ObjectSet<? extends Notification> getNotifications(final Client client) {
		final Query q = mDB.query();
		q.constrain(Notification.class);
		q.descend("mClient").constrain(client).identity();
		q.descend("mIndex").orderAscending();
		return new Persistent.InitializingObjectSet<Notification>(mWoT, q);
	}
	
	/**
	 * Interface for the core of WOT to deploy an {@link IdentityChangedNotification} to clients. 
	 * 
	 * Typically called when a {@link Identity} or {@link OwnIdentity} is added, deleted or its attributes are modified.
	 * See {@link #subscribeToIdentities(String)} for a list of the changes which do or do not trigger a notification.
	 * 
	 * This function does not store a reference to the given identity object in the database, it only stores the ID.
	 * You are safe to pass non-stored objects or objects which must not be stored.
	 * 
	 * You must synchronize on this {@link SubscriptionManager} and the {@link Persistent#transactionLock(ExtObjectContainer)} when calling this function!
	 * 
	 * @param oldIdentity A {@link Identity#clone()} of the {@link Identity} BEFORE the changes happened. In other words the old version of it.
	 * @param newIdentity The new version of the {@link Identity} as stored in the database now. 
	 */
	protected void storeIdentityChangedNotificationWithoutCommit(final Identity oldIdentity, final Identity newIdentity) {
		if(logDEBUG) Logger.debug(this, "storeIdentityChangedNotificationWithoutCommit(): old=" + oldIdentity + "; new=" + newIdentity);
		
		@SuppressWarnings("unchecked")
		final ObjectSet<IdentitiesSubscription> subscriptions = (ObjectSet<IdentitiesSubscription>)getSubscriptions(IdentitiesSubscription.class);
		
		for(IdentitiesSubscription subscription : subscriptions) {
			subscription.storeNotificationWithoutCommit(oldIdentity, newIdentity);
		}
		
		if(logDEBUG) Logger.debug(this, "storeIdentityChangedNotificationWithoutCommit() finished.");
	}
	
	/**
	 * Interface for the core of WOT to deploy a {@link TrustChangedNotification} to clients. 
	 * 
	 * Typically called when a {@link Trust} is added, deleted or its attributes are modified.
	 * 
	 * This function does not store references to the passed objects in the database, it only stores their IDs.
	 * You are safe to pass non-stored objects or objects which must not be stored.
	 * 
	 * You must synchronize on this {@link SubscriptionManager} and the {@link Persistent#transactionLock(ExtObjectContainer)} when calling this function!
	 * 
	 * @param oldTrust A {@link Trust#clone()} of the {@link Trust} BEFORE the changes happened. In other words the old version of it.
	 * @param newTrust The new version of the {@link Trust} as stored in the database now.
	 */
	protected void storeTrustChangedNotificationWithoutCommit(final Trust oldTrust, final Trust newTrust) {
		if(logDEBUG) Logger.debug(this, "storeTrustChangedNotificationWithoutCommit(): old=" + oldTrust + "; new=" + newTrust);
		
		@SuppressWarnings("unchecked")
		final ObjectSet<TrustsSubscription> subscriptions = (ObjectSet<TrustsSubscription>)getSubscriptions(TrustsSubscription.class);
		
		for(TrustsSubscription subscription : subscriptions) {
			subscription.storeNotificationWithoutCommit(oldTrust, newTrust);
		}
		
		if(logDEBUG) Logger.debug(this, "storeTrustChangedNotificationWithoutCommit() finished.");
	}
	
	/**
	 * Interface for the core of WOT to deploy a {@link ScoreChangedNotification} to clients. 
	 * 
	 * Typically called when a {@link Score} is added, deleted or its attributes are modified.
	 * 
	 * This function does not store references to the passed objects in the database, it only stores their IDs.
	 * You are safe to pass non-stored objects or objects which must not be stored.
	 * 
	 * You must synchronize on this {@link SubscriptionManager} and the {@link Persistent#transactionLock(ExtObjectContainer)} when calling this function!
	 * 
	 * @param oldScore A {@link Score#clone()} of the {@link Score} BEFORE the changes happened. In other words the old version of it.
	 * @param newScore The new version of the {@link Score} as stored in the database now.
	 */
	protected void storeScoreChangedNotificationWithoutCommit(final Score oldScore, final Score newScore) {
		if(logDEBUG) Logger.debug(this, "storeScoreChangedNotificationWithoutCommit(): old=" + oldScore + "; new=" + newScore);
		
		@SuppressWarnings("unchecked")
		final ObjectSet<ScoresSubscription> subscriptions = (ObjectSet<ScoresSubscription>)getSubscriptions(ScoresSubscription.class);
		
		for(ScoresSubscription subscription : subscriptions) {
			subscription.storeNotificationWithoutCommit(oldScore, newScore);
		}
		
		if(logDEBUG) Logger.debug(this, "storeScoreChangedNotificationWithoutCommit() finished.");
	}

	/**
	 * Sends out the {@link Notification} queue of each {@link Subscription} to its clients.
	 * 
	 * Typically called by the Ticker {@link #mTicker} on a separate thread. This is triggered by {@link #scheduleNotificationProcessing()}
	 * - the scheduling function should be called whenever a {@link Notification} is stored to the database.
	 *  
	 * If deploying the notifications for a subscription fails, this function is scheduled to be run again after some time.
	 * If deploying for a certain subscription fails N times, the Subscription is deleted.
	 * 
	 * @see Subscription#sendNotifications(SubscriptionManager) This function is called on each subscription to deploy the {@link Notification} queue.
	 */
	public void run() {
		if(logMINOR) Logger.minor(this, "run()...");
		
		/* We do NOT allow database queries on the WebOfTrust object in sendNotifications: 
		 * Notification objects contain serialized clones of all required objects for deploying them, they are self-contained.
		 * Therefore, we don't have to take the WebOfTrust lock and can execute in parallel to threads which need to lock the WebOfTrust.*/
		// synchronized(mWoT) {
		synchronized(this) {
			for(Client client : getAllClients()) {
				try {
					if(client.sendNotifications(this)) {
						// Persistent.checkedCommit(mDB, this);	/* sendNotifications() does this already */
					} else {
						Logger.warning(this, "sendNotifications tells us to delete the Client, deleting it: " + client);
						client.deleteWithoutCommit(this);
						Persistent.checkedCommit(mDB, this);
					}
				} catch(Exception e) {
					Persistent.checkedRollback(mDB, this, e);
				}
			}
		}
		//}
		
		if(logMINOR) Logger.minor(this, "run() finished.");
	}
	
	/**
	 * {@inheritDoc}
	 */
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
		Logger.normal(this, "start()...");
		deleteAllClients();
		
		final PluginRespirator respirator = mWoT.getPluginRespirator();
		
		if(respirator != null) { // We are connected to a node
			mTicker = new TrivialTicker(respirator.getNode().executor);
		} else { // We are inside of a unit test
			mTicker = null;
		}
		Logger.normal(this, "start() finished.");
	}
	
	/**
	 * Shuts down this SubscriptionManager by aborting all queued notification processing and waiting for running
	 * processing to finish.
	 */
	protected synchronized void stop() {
		Logger.normal(this, "stop()...");
		
		if(mTicker != null)
			mTicker.shutdown();
		
		Logger.normal(this, "stop() finished.");
	}
}
