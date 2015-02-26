/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package plugins.WebOfTrust;

import java.io.IOException;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import plugins.WebOfTrust.exceptions.DuplicateObjectException;
import plugins.WebOfTrust.ui.fcp.FCPInterface.FCPCallFailedException;
import plugins.WebOfTrust.util.jobs.BackgroundJob;
import plugins.WebOfTrust.util.jobs.DelayedBackgroundJob;
import plugins.WebOfTrust.util.jobs.MockDelayedBackgroundJob;
import plugins.WebOfTrust.util.jobs.TickerDelayedBackgroundJob;

import com.db4o.ObjectSet;
import com.db4o.ext.ExtObjectContainer;
import com.db4o.query.Query;

import freenet.node.PrioRunnable;
import freenet.pluginmanager.FredPluginFCPMessageHandler;
import freenet.pluginmanager.PluginRespirator;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.PooledExecutor;
import freenet.support.PrioritizedTicker;
import freenet.support.Ticker;
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
	
	@SuppressWarnings("serial")
	public static final class Client extends Persistent {

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
		 * An ID which associates this client with a FCP connection if the type is FCP.<br><br>
		 * 
		 * Must be a valid {@link UUID}, see {@link PluginRespirator#getPluginConnectionByID(UUID)}.
		 * <br> (Stored as String so it is a db4o native type and doesn't require explicit
		 * management). 
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
		
		/** @param myFCP_ID See {@link #mFCP_ID} */
		public Client(final UUID myFCP_ID) {
            assert(myFCP_ID != null);
            
			mType = Type.FCP;
			mFCP_ID = myFCP_ID.toString();
		}
		
		/** {@inheritDoc} */
		@Override
		public void startupDatabaseIntegrityTest() throws Exception {
			activateFully();
			
			IfNull.thenThrow(mType, "mType");
			
			if(mType == Type.FCP) {
				IfNull.thenThrow(mFCP_ID, "mFCP_ID");
				UUID.fromString(mFCP_ID); // Throws if invalid.
			}
			
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
		public final UUID getFCP_ID() {
			if(getType() != Type.FCP)
				throw new UnsupportedOperationException("Type is not FCP:" + getType());
			
			checkedActivate(1);
			return UUID.fromString(mFCP_ID);
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
		 * @see #mSendNotificationsFailureCount
		 */
		public final byte getSendNotificationsFailureCount() {
			checkedActivate(1);
			return mSendNotificationsFailureCount;
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
         * @throws InterruptedException
         *             If an external thread requested the current thread to terminate via
         *             {@link Thread#interrupt()} while the data was being transfered to the client.
         *             <br>This is a necessary shutdown mechanism as clients can be attached by
         *             network and thus transfers can take a long time. Please honor it by 
         *             terminating the thread so WOT can shutdown quickly.<br>
         *             You do not have to rollback the transaction if this happens.
		 */
		protected boolean sendNotifications(SubscriptionManager manager)
		        throws InterruptedException {
		    
			if(SubscriptionManager.logMINOR) Logger.minor(manager, "sendNotifications() for " + this);
			
			// ATTENTION: When adding another type, make sure that you check the
			// Thread.interrupted() state after deploying each Notification, and exit the function
			// via InterruptedException if the thread was interrupted.
			// This is necessary for SubscriptionManager.stop() to be fast.
			switch(getType()) {
				case FCP:
					for(final Notification notification : manager.getNotifications(this)) {
						if(SubscriptionManager.logDEBUG) Logger.debug(manager, "Sending notification via FCP: " + notification);
						try {
							try {
								notification.getSubscription().notifySubscriberByFCP(notification);
								notification.deleteWithoutCommit();
							} catch(FCPCallFailedException | IOException | RuntimeException e) {
								Persistent.checkedRollback(mDB, this, e, LogLevel.WARNING);
								
								final byte failureCount = incrementSendNotificationsFailureCountWithoutCommit();
								Persistent.checkedCommit(mDB, this);
								
								boolean doNotDeleteClient = true;
								
								// Check whether the client has disconnected. If so, we must delete
								// it immediately. If not, we must only delete it after the failure
								// counter has passed the limit.
                                if(e instanceof IOException) {
									Logger.warning(manager, "sendNotifications() failed, client has disconnected, failure count: " + failureCount, e);
									doNotDeleteClient = false;
								} else {
								    if(e instanceof FCPCallFailedException) {
								        Logger.warning(manager, "sendNotifications() failed because"
								            + " the client indicated failure at its side."
								            + " Failure count: " + failureCount, e);
								    } else {
								        assert(e instanceof RuntimeException);
                                        Logger.error(manager, "Bug in sendNotifications()!", e);
								    }
									if(failureCount >= DISCONNECT_CLIENT_AFTER_FAILURE_COUNT) 
										doNotDeleteClient = false;
								}
								
								if(doNotDeleteClient)
									manager.scheduleNotificationProcessing();
								
								return doNotDeleteClient;
							} catch(InterruptedException e) {
							    // Shutdown of WOT was requested. This is normal mode of operation,
							    // and not the fault of the client, so we do not increment its
							    // failure counter.
							    Persistent.checkedRollback(mDB, this, e, LogLevel.NORMAL);
							    throw e;
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
					throw new UnsupportedOperationException("Unknown Type: " + getType());
			}
			
			return true;
		}
		
		/**
		 * Sends a message to the client which indicates that a {@link Subscription} has been forcefully terminated.
		 * This can happen if the client exceeds the limit of {@link SubscriptionManager#DISCONNECT_CLIENT_AFTER_FAILURE_COUNT} failures
		 * to process a {@link Notification}.
		 * 
		 * Exceptions which happen if sending the message fails are swallowed.
		 */
		@SuppressWarnings("unchecked")
		private void notifyClientAboutDeletion(
		        final Subscription<? extends EventSource> deletedSubscriptoin) {
		    
			try {
				Logger.warning(getSubscriptionManager(), "notifyClientAboutDeletion() for " + deletedSubscriptoin);
				
				switch(getType()) {
					case FCP:
						mWebOfTrust.getFCPInterface().sendUnsubscribedMessage(getFCP_ID(),
								(Class<Subscription<? extends EventSource>>) deletedSubscriptoin
								    .getClass(),
								deletedSubscriptoin.getID());
						break;
					default:
						throw new UnsupportedOperationException("Unknown Type: " + getType());
				}
			} catch(IOException | RuntimeException | Error e) {
				Logger.error(getSubscriptionManager(), "notifyClientAboutDeletion() failed!", e);
			}
		}
		
		/**
		 * Deletes this Client and also deletes all {@link Subscription} and {@link Notification} objects belonging to it.
		 * Attempts to notify the {@link Client} about the deletion of each subscription so it can re-subscribe.
		 * 
		 * Typically used to forcefully disconnect a client if it exceeds {@link SubscriptionManager#DISCONNECT_CLIENT_AFTER_FAILURE_COUNT}
		 * failures when processing a {@link Notification}.
		 * 
		 * @param subscriptionManager The {@link SubscriptionManager} to which this Client belongs.
		 */
		protected void deleteWithoutCommit(final SubscriptionManager subscriptionManager) {
			for(final Subscription<? extends EventSource> subscription
			        : subscriptionManager.getSubscriptions(this)) {
			    
				subscription.deleteWithoutCommit(subscriptionManager);
				notifyClientAboutDeletion(subscription);
			}
			super.deleteWithoutCommit();
		}

		/** {@inheritDoc} */
		@Override protected void activateFully() {
			checkedActivate(1);
		}

		@Override
		public String toString() {
			return super.toString() + " { Type=" + getType() + "; FCP ID=" + getFCP_ID() + " }"; 
		}
	}
	
	/**
	 * A subscription stores the information which client is subscribed to which content.<br>
	 * For each {@link Client}, one subscription is stored one per {@link EventSource}-type.
	 * A {@link Client} cannot have multiple subscriptions of the same type.
	 * 
	 * Notice: Even though this is an abstract class, it contains code specific <b>all</>b> types of subscription clients such as FCP and callback.
	 * At first glance, this looks like a violation of abstraction principles. But it is not:
	 * Subclasses of this class shall NOT be created for different types of clients such as FCP and callbacks.
	 * Subclasses are created for different types of EventSource to which the subscriber is
	 * subscribed: There is a subclass for subscriptions to the list of {@link Identity}s, the list
	 * of {@link Trust}s, and so on. Each subclass has to implement the code for notifying
	 * <b>all</b> types of clients (FCP, callback, etc.).
	 * Therefore, this base class also contains code for <b>all</b> kinds of clients.
	 */
	@SuppressWarnings("serial")
	public static abstract class Subscription<EventType extends EventSource> extends Persistent {
		
		/**
		 * The {@link Client} which created this {@link Subscription}.
		 */
		@IndexedField
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
		
		/** {@inheritDoc} */
		@Override
		public void startupDatabaseIntegrityTest() throws Exception {
			activateFully();
			
			IfNull.thenThrow(mClient);
			
			IfNull.thenThrow(mID, "mID");
			UUID.fromString(mID); // Throws if invalid
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
		@Override
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
		 * Called by the {@link SubscriptionManager} before storing a new Subscription.
		 * <br><br>
		 * 
		 * When real events happen, we only want to send a "diff" between the last state of the database before the event happened and the new state.
		 * For being able to only send a diff, the subscriber must know what the <i>initial</i> state of the database was.
		 * <br><br>
		 * 
         * For example, if a client subscribes to the list of identities, it must always receive a full list of all existing identities at first.
         * As new identities appear afterwards, the client can be kept up to date by sending each single new identity as it appears.
         * <br><br>
         * 
		 * The job of this function is to store the initial state of the WOT database in this
		 * Subscription, as a clone of all relevant objects, serialized into a series of
		 * {@link ObjectChangedNotification}s.<br>
		 * The actual deployment of the data to the client will happen in the future, as part of
		 * regular {@link Notification} deployment: The synchronization can be large in size, and
		 * thus sending it over the network can take a long time. Therefore, it would be bad if we
		 * sent it directly from the main {@link WebOfTrust} database since that would require us
		 * to take the main {@link WebOfTrust} lock during the whole time.<br>
		 * By separating the part of copying the data from the {@link WebOfTrust} into a local
		 * operation, we can keep the time we have to take the main lock as short as possible.<br>
		 * <br>
		 * 
         * <b>Thread safety:</b><br>
		 * This must be called while locking upon the {@link WebOfTrust}, the SubscriptionManager
		 * and the {@link Persistent#transactionLock(ExtObjectContainer)}.<br>
		 * Therefore it may perform database queries on the WebOfTrust to obtain the dataset.
		 */
		protected final void storeSynchronizationWithoutCommit() {
            final BeginSynchronizationNotification<EventType> beginMarker
                = new BeginSynchronizationNotification<>(this);
                
            beginMarker.initializeTransient(mWebOfTrust);
            beginMarker.storeWithoutCommit();
            
            // All objects part of a synchronization need to receive a call to
            // EventSource.setVersionID() with the version ID of the
            // BeginSynchronizationNotification beginMarker. See JavaDoc of
            // EventSource.setVersionID() and BeginSynchronizationNotification.
            final UUID synchronizationID
                = UUID.fromString(beginMarker.getID());
            
            // We require thread locking upon the WebOfTrust per JavaDoc, so we may now call
            // getSynchronization().
            for(EventType eventSource : getSynchronization()) {
                // We need to call setVersionID() on the EventSource, but we must not modify the
                // main EventSource object stored in the mWebOfTrust. Thus, we clone() the
                // EventSource and call the setter upon the temporary clone.
                @SuppressWarnings("unchecked")
                EventType eventSourceWithProperVersionID = (EventType) eventSource.clone();
                eventSourceWithProperVersionID.setVersionID(synchronizationID);
                
                storeNotificationWithoutCommit(null, eventSourceWithProperVersionID);
            }
            
            final EndSynchronizationNotification<EventType> endMarker
                = new EndSynchronizationNotification<>(beginMarker);
            
            endMarker.initializeTransient(mWebOfTrust);
            endMarker.storeWithoutCommit();
        }
		
		/**
		 * Must return all objects of a given EventType which form a valid synchronization.<br>
		 * This is all objects of the EventType stored in the {@link WebOfTrust}.<br><br>
		 * 
         * <b>Thread safety:</b><br>
         * This must be called while locking upon the {@link WebOfTrust}.<br>
         * Therefore it may perform database queries on the WebOfTrust to obtain the dataset.<br>
         * 
         * @see #storeSynchronizationWithoutCommit()
         *         storeSynchronizationWithoutCommit() will use this function to obtain the dataset
         *         of this function. Its JavaDoc also explains what a "synchronization" is in more
         *         detail.
		 */
		abstract List<EventType> getSynchronization();

        /**
         * Shall store a {@link ObjectChangedNotification} constructed via
         * {@link ObjectChangedNotification#ObjectChangedNotification(Subscription, Persistent,
         * Persistent)} with parameters oldObject = oldEventSource, newObject = newEventSource.<br>
         * <br> 
         * 
         * The type parameter of the {@link ObjectChangedNotification} shall match the type
         * parameter EventType extends EventSource of this {@link Subscription}.
         * <br><br>
         * 
         * TODO: Code quality: Rename to storeObjectChangedNotificationWithoutCommit
         */
        abstract void storeNotificationWithoutCommit(
            final EventType oldEventSource, final EventType newEventSource);

		/**
		 * Called by this Subscription when the type of it is FCP and a {@link Notification} shall be sent via FCP. 
		 * The implementation MUST throw a {@link FCPCallFailedException} if the client did not signal that the processing was successful:
		 * Not only shall the {@link Notification} be resent if transmission fails but also if the client fails processing it. This
		 * will allow the client to use failing database transactions in the event handlers and just rollback and throw if the transaction
		 * fails. 
		 * The implementation MUST use synchronous FCP communication to allow the client to signal an error.
		 * Also, synchronous communication is necessary for guaranteeing the notifications to arrive in proper order at the client.
		 * <br><br>
		 * 
         * If this function fails by an exception, the caller MUST NOT proceed to deploy subsequent
         * {@link Notification}s to the client. Instead, it must retry calling this function upon
         * the same Notification until it succeeds or the retry limit is exceeded.<br>
         * This is necessary because Notifications must be deployed in proper order to guarantee
         * that they make sense to the client.<br>
         * Notice that the above does not apply to all types of exceptions. See the JavaDoc of
         * the various thrown exceptions for details.<br><br>
		 * 
         * <b>Thread safety:</b><br>
         * This must be called while locking upon the SubscriptionManager.<br>
		 * The {@link WebOfTrust} object shall NOT be locked:
		 * The {@link Notification} objects which this function receives contain serialized clones of the objects from WebOfTrust.
		 * Therefore, the notifications are self-contained and this function should and must NOT call any database query functions of the WebOfTrust. 
		 * 
		 * @param notification 
		 *             The {@link Notification} to send out via FCP. Must be cast-able to one of:
		 *             <br>
		 *             - {@link ObjectChangedNotification} containing objects matching the type
		 *               of the type parameter EventType of this Subscription.<br>
		 *             - {@link BeginSynchronizationNotification} with type parameter matching the
         *               type of the type parameter EventType of this Subscription.<br>
		 *             - {@link EndSynchronizationNotification} with type parameter matching the
         *               type of the type parameter EventType of this Subscription.<br>
         * @throws IOException
         *             If the FCP client has disconnected. The SubscriptionManager then should not
         *             retry deploying this Notification, the {@link Subscription} should be
         *             terminated instead.
         * @throws InterruptedException
         *             If an external thread requested the current thread to terminate via
         *             {@link Thread#interrupt()} while the data was being transfered to the client.
         *             <br>This is a necessary shutdown mechanism as clients can be attached by
         *             network and thus transfers can take a long time. Please honor it by 
         *             terminating the thread so WOT can shutdown quickly.
		 * @throws FCPCallFailedException
		 *             If processing failed at the client.<br>
         *             The SubscriptionManager should call
         *             {@link Client#incrementSendNotificationsFailureCountWithoutCommit()} upon
         *             {@link #mClient} then, and retry calling this function upon the same
         *             Notification at the next iteration of the notification sending thread
         *             - until the limit is reached.
		 */
        protected abstract void notifySubscriberByFCP(Notification notification)
            throws FCPCallFailedException, IOException, InterruptedException;

		/** {@inheritDoc} */
		@Override protected void activateFully() {
			checkedActivate(1);
		}

		@Override
		public String toString() {
			return super.toString() + " { ID=" + getID() + "; Client=" + getClient() + " }";
		}
	}
	
	/**
	 * An object of type Notification is stored when an event happens to which a client is possibly subscribed.
	 * The SubscriptionManager will wake up some time after that, pull all notifications from the database and process them.
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
		private final Subscription<? extends EventSource> mSubscription;
		
		/**
		 * The index of this Notification in the queue of its {@link Client}:
		 * Notifications are supposed to be sent out in proper sequence, therefore we use incremental indices.
		 */
		@IndexedField
		private final long mIndex;
	
        /**
         * Constructs a Notification in the queue of the given Client.<br>
         * Takes a free Notification index from it with
         * {@link Client#takeFreeNotificationIndexWithoutCommit}.
         * 
         * @param mySubscription The {@link Subscription} which requested this type of Notification.
         */
        Notification(final Subscription<? extends EventSource> mySubscription) {
            mSubscription = mySubscription;
            mClient = mSubscription.getClient();
            mIndex = mClient.takeFreeNotificationIndexWithoutCommit();
        }
        
        /** {@inheritDoc} */
        @Override public void startupDatabaseIntegrityTest() throws Exception {
            activateFully();
            
            IfNull.thenThrow(mClient, "mClient");
            IfNull.thenThrow(mSubscription, "mSubscription");
            
            if(mClient != getSubscription().getClient())
                throw new IllegalStateException("mClient does not match client of mSubscription");
            
            if(mIndex < 0)
                throw new IllegalStateException("mIndex==" + mIndex);
        }

        /**
         * @deprecated Not implemented because we don't need it.
         */
        @Override
        @Deprecated()
        public String getID() {
            throw new UnsupportedOperationException();
        }
        
        /**
         * @return The {@link Subscription} which requested this type of Notification.
         */
        public Subscription<? extends EventSource> getSubscription() {
            checkedActivate(1);
            mSubscription.initializeTransient(mWebOfTrust);
            return mSubscription;
        }
        
        /** {@inheritDoc} */
        @Override protected void activateFully() {
            checkedActivate(1);
        }
	}
	
	/**
     * It provides two clones of the {@link Persistent} object about whose change the client shall be notified:
     * - A version of it before the change via {@link ObjectChangedNotification#getOldObject()}<br>
     * - A version of it after the change via {@link ObjectChangedNotification#getNewObject()}<br>
     * 
     * If one of the before/after getters returns null, this is because the object was added/deleted.
     * If both do return an non-null object, the object was modified.
     * NOTICE: Modification can also mean that its class has changed!
     * 
     * NOTICE: Both Persistent objects are not stored in the database and must not be stored there to prevent duplicates!
     * <br><br>
     * 
     * ATTENTION: {@link ObjectChangedNotification#getOldObject()}==null does NOT mean that the
     * object did not exist before the notification for ObjectChangedNotifications which are
     * deployed as part of a {@link Subscription} synchronization.<br>
     * See {@link Subscription#storeSynchronizationWithoutCommit()} and
     * {@link BeginSynchronizationNotification}.
	 */
	@SuppressWarnings("serial")
	public static abstract class ObjectChangedNotification extends Notification {
		
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
		 * Only one of oldObject or newObject may be null.
		 * If both are non-null, their {@link Persistent#getID()} must be equal.
		 * 
		 * TODO: Code quality: oldObject and newObject should be EventSource, not Persistent.
		 * 
		 * @param mySubscription The {@link Subscription} which requested this type of Notification.
		 * @param oldObject The version of the changed {@link Persistent} object before the change.
		 * @param newObject The version of the changed {@link Persistent} object after the change.
		 * @see Notification#Notification(Subscription) This parent constructor is also called.
		 */
		ObjectChangedNotification(final Subscription<? extends EventSource> mySubscription,
		        final Persistent oldObject, final Persistent newObject) {
		    
			super(mySubscription);
			
			assert	(
						(oldObject == null ^ newObject == null) ||
						(oldObject != null && newObject != null && oldObject.getID().equals(newObject.getID()))
					);
			
			mOldObject = (oldObject != null ? oldObject.serialize() : null);
			mNewObject = (newObject != null ? newObject.serialize() : null);
		}
		
		/** {@inheritDoc} */
		@Override
		public void startupDatabaseIntegrityTest() throws Exception {
			super.startupDatabaseIntegrityTest();
			
			activateFully();
			
			if(mOldObject == null && mNewObject == null)
				throw new NullPointerException("Only one of mOldObject and mNewObject may be null!");

			// mOldObject / mNewObject are serialized copies of Persistent objects.
			// Because they are serialized, the startupDatabaseIntegrityTest() won't be called automatically on them by WOT
			// - we have to do it manually.
			if(mOldObject != null)
				getOldObject().startupDatabaseIntegrityTest();
			
			if(mNewObject != null)
				getNewObject().startupDatabaseIntegrityTest();

			if(mOldObject != null && mNewObject != null && !getOldObject().getID().equals(getNewObject().getID()))
				throw new IllegalStateException("The ID of mOldObject and mNewObject must match!");
		}

		/**
		 * Returns the changed {@link Persistent} object before the change.<br>
		 * Null if the change was the creation of the object.<br><br>
		 * 
		 * ATTENTION: A return value of null does NOT mean that the object did not exist before the
		 * notification for ObjectChangedNotifications which are deployed as part of a
		 * {@link Subscription} synchronization.<br>
		 * See {@link Subscription#storeSynchronizationWithoutCommit()} and
		 * {@link BeginSynchronizationNotification}.
		 * 
		 * @see #mOldObject The backend member variable of this getter.
		 */
		public final Persistent getOldObject() throws NoSuchElementException {
			checkedActivate(1); // byte[] is a db4o primitive type so 1 is enough
			return mOldObject != null ? Persistent.deserialize(mWebOfTrust, mOldObject) : null;
		}
		
		/**
		 * @return The changed {@link Persistent} object after the change. Null if the change was the deletion of the object.
		 * @see #mNewObject The backend member variable of this getter.
		 */
		public final Persistent getNewObject() throws NoSuchElementException {
			checkedActivate(1); // byte[] is a db4o primitive type so 1 is enough
			return mNewObject != null ? Persistent.deserialize(mWebOfTrust, mNewObject) : null;
		}

		/** {@inheritDoc} */
		@Override protected void activateFully() {
		    super.activateFully();
		    // super.activateFully() will probably always activate to at least level 1, as
		    // activating to level 0 does not make any sense. So we don't have to do this twice.
			/* checkedActivate(1); */
		}

		@Override
		public String toString() {
			return super.toString() + " { oldObject=" + getOldObject() + "; newObject=" + getNewObject() + " }";
		}
	}
	
	/**
	 * Shall mark the begin of a series of synchronization {@link ObjectChangedNotification}s. See
	 * {@link Subscription#storeSynchronizationWithoutCommit()} for a description what
	 * "synchronization" means here.<br><br>
	 * 
	 * All {@link ObjectChangedNotification}s following this marker notification shall be considered
	 * as part of the synchronization, up to the end marker of type
	 * {@link EndSynchronizationNotification}.<br><br>
	 * 
	 * Attention: The {@link EndSynchronizationNotification} is a child class of this, not a
	 * different class. Make sure to avoid accidentally matching it by 
	 * "instanceof BeginSynchronizationNotification".<br>
	 * TODO: Code quality: The only reason for the above ambiguity is to eliminate code duplication
	 * because the End* class needs some functions from Begin*. Resolve the ambiguity by adding a
	 * third class AbstractSynchronizationNotification as parent class for both to contain the
	 * common code; or by having a single class which contains a boolean which tells whether its
	 * begin or end.
	 */
	@SuppressWarnings("serial")
    public static class BeginSynchronizationNotification<EventType extends EventSource>
	        extends Notification {
        /**
         * All {@link EventSource} objects which are stored inside of
         * {@link ObjectChangedNotification} as part of the synchronization which is marked by this
         * {@link BeginSynchronizationNotification} shall be bound to this ID by calling
         * {@link EventSource#setVersionID(UUID)}.<br>
         * This allows the client to use a "mark-and-sweep" garbage collection mechanism to delete
         * obsolete {@link EventSource} objects which existed in its database before the
         * synchronization: After having received the end-marker
         * {@link EndSynchronizationNotification}, any object of type EventType whose
         * {@link EventSource#getVersionID(UUID)} does not match the version ID of the current
         * synchronization is an obsolete object and must be deleted.<br><br>
         * 
         * (The {@link UUID} is stored as {@link String} for simplifying usage of db4o: Strings are
         * native objects and thus do not have to be manually deleted.)
         */
	    private final String mVersionID;
	    
	    
        BeginSynchronizationNotification(Subscription<EventType> mySubscription) {
            super(mySubscription);
            mVersionID = UUID.randomUUID().toString();
        }
        
        /** Only for being used by {@link EndSynchronizationNotification}. */
        BeginSynchronizationNotification(Subscription<EventType> mySubscription, String versionID) {
            super(mySubscription);
            mVersionID = versionID;
        }

        /** @see #mVersionID */
        @Override public String getID() {
            checkedActivate(1);
            return mVersionID;
        }
        
        @Override
        public void startupDatabaseIntegrityTest() throws Exception {
            super.startupDatabaseIntegrityTest();
            
            UUID.fromString(getID()); // Will throw if ID is no valid UUID.
        }
        
        @Override
        public String toString() {
            return super.toString() + " { mVersionID=" + getID() + " }";
        }
	}

	/**
	 * @see BeginSynchronizationNotification
	 */
    @SuppressWarnings("serial")
    public static class EndSynchronizationNotification<EventType extends EventSource>
            extends BeginSynchronizationNotification<EventType> {
        
        @SuppressWarnings("unchecked")
        EndSynchronizationNotification(BeginSynchronizationNotification<EventType> begin) {
            super((Subscription<EventType>) begin.getSubscription(), begin.getID());
            
            assert !(begin instanceof EndSynchronizationNotification);
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
	public static class IdentityChangedNotification extends ObjectChangedNotification {
		/**
		 * Only one of oldIentity and newIdentity may be null. If both are non-null, their {@link Identity#getID()} must match.
		 * 
		 * @param mySubscription The {@link Subscription} to whose {@link Notification} queue this {@link Notification} belongs.
		 * @param oldIdentity The version of the {@link Identity} before the change.
		 * @param newIdentity The version of the {@link Identity} after the change.
		 */
		protected IdentityChangedNotification(final Subscription<Identity> mySubscription, 
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
	public static final class TrustChangedNotification extends ObjectChangedNotification {
		/**
		 * Only one of oldTrust and newTrust may be null. If both are non-null, their {@link Trust#getID()} must match.
		 * 
		 * @param mySubscription The {@link Subscription} to whose {@link Notification} queue this {@link Notification} belongs.
		 * @param oldTrust The version of the {@link Trust} before the change.
		 * @param newTrust The version of the {@link Trust} after the change.
		 */
		protected TrustChangedNotification(final Subscription<Trust> mySubscription, 
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
	public static final class ScoreChangedNotification extends ObjectChangedNotification {
		/**
		 * Only one of oldScore and newScore may be null. If both are non-null, their {@link Score#getID()} must match.
		 * 
		 * @param mySubscription The {@link Subscription} to whose {@link Notification} queue this {@link Notification} belongs.
		 * @param oldScore The version of the {@link Score} before the change.
		 * @param newScore The version of the {@link Score} after the change.
		 */
		protected ScoreChangedNotification(final Subscription<Score> mySubscription,
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
	public static final class IdentitiesSubscription extends Subscription<Identity> {

		/**
		 * @param myClient The {@link Client} which created this Subscription. 
		 */
		protected IdentitiesSubscription(final Client myClient) {
			super(myClient);
		}


		/** {@inheritDoc} */
        @Override List<Identity> getSynchronization() {
            return mWebOfTrust.getAllIdentities();
        }

        // TODO: Code quality: This function is almost the same in TrustsSubscription and
        // ScoresSubscription. Add a type parameter <T extends EventSource> to Notification and use
        // it to move this function upwards to the Subscription base class.
		/** {@inheritDoc} */
        @Override
        protected void notifySubscriberByFCP(Notification notification)
                throws FCPCallFailedException, IOException, InterruptedException {

		    final UUID clientID = getClient().getFCP_ID();
		    
			if(notification instanceof IdentityChangedNotification) {
			    mWebOfTrust.getFCPInterface().sendIdentityChangedNotification(
			        clientID, (IdentityChangedNotification)notification);
			} else if(notification instanceof BeginSynchronizationNotification<?>) {
			    // EndSynchronizationNotification is a child of BeginSynchronizationNotification.
			    
			    // Would be a false positive:
			    // The type parameter is not known at runtime due to the way Java is implemented.
			    /* assert((BeginSynchronizationNotification<Identity>)notification != null)
			        : "The IdentitiesSubscription should only receive Identity notifications"; */
	             
			    mWebOfTrust.getFCPInterface().sendBeginOrEndSynchronizationNotification(
			        clientID,
			        (BeginSynchronizationNotification<?>)notification);
			} else {
			    throw new UnsupportedOperationException("Unknown notification type: "
			        + notification);
			}
		}

		/**
		 * Stores a {@link IdentityChangedNotification} to the {@link Notification} queue of this {@link Client}.
		 * 
		 * @param oldIdentity The version of the {@link Identity} before the change. Null if it was newly created.
		 * @param newIdentity The version of the {@link Identity} after the change. Null if it was deleted.
		 */
		@Override void storeNotificationWithoutCommit(
		        final Identity oldIdentity, final Identity newIdentity) {
		    
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
	public static final class TrustsSubscription extends Subscription<Trust> {

		/**
		 * @param myClient The {@link Client} which created this Subscription. 
		 */
		protected TrustsSubscription(final Client myClient) {
			super(myClient);
		}

        /** {@inheritDoc} */
        @Override List<Trust> getSynchronization() {
            return mWebOfTrust.getAllTrusts();
        }

		/** {@inheritDoc} */
        @Override
        protected void notifySubscriberByFCP(final Notification notification)
                throws FCPCallFailedException, IOException, InterruptedException {

            final UUID clientID = getClient().getFCP_ID();
            
            if(notification instanceof TrustChangedNotification) {
                mWebOfTrust.getFCPInterface().sendTrustChangedNotification(
                    clientID, (TrustChangedNotification)notification);
            } else if(notification instanceof BeginSynchronizationNotification<?>) {
                // EndSynchronizationNotification is a child of BeginSynchronizationNotification.
                
                // Would be a false positive:
                // The type parameter is not known at runtime due to the way Java is implemented.
                /* assert((BeginSynchronizationNotification<Trust>)notification != null)
                    : "The TrustsSubscription should only receive Trust notifications"; */
                
                mWebOfTrust.getFCPInterface().sendBeginOrEndSynchronizationNotification(
                    clientID,
                    (BeginSynchronizationNotification<?>)notification);
            } else {
                throw new UnsupportedOperationException("Unknown notification type: "
                    + notification);
            }
		}

		/**
		 * Stores a {@link TrustChangedNotification} to the {@link Notification} queue of this {@link Client}.
		 * 
		 * @param oldTrust The version of the {@link Trust} before the change. Null if it was newly created.
		 * @param newTrust The version of the {@link Trust} after the change. Null if it was deleted.
		 */
		@Override void storeNotificationWithoutCommit(final Trust oldTrust, final Trust newTrust) {
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
	public static final class ScoresSubscription extends Subscription<Score> {

		/**
		 * @param myClient The {@link Client} which created this Subscription.
		 */
		protected ScoresSubscription(final Client myClient) {
			super(myClient);
		}

        /** {@inheritDoc} */
        @Override List<Score> getSynchronization() {
            return mWebOfTrust.getAllScores();
        }

		/** {@inheritDoc} */
        @Override
        protected void notifySubscriberByFCP(final Notification notification)
                throws FCPCallFailedException, IOException, InterruptedException {

            final UUID clientID = getClient().getFCP_ID();
            
            if(notification instanceof ScoreChangedNotification) {
                mWebOfTrust.getFCPInterface().sendScoreChangedNotification(
                    clientID, (ScoreChangedNotification)notification);
            } else if(notification instanceof BeginSynchronizationNotification<?>) {
                // EndSynchronizationNotification is a child of BeginSynchronizationNotification.
                
                // Would be a false positive:
                // The type parameter is not known at runtime due to the way Java is implemented.
                /* assert((BeginSynchronizationNotification<Score>)notification != null)
                    : "The ScoresSubscription should only receive Score notifications"; */
                 
                mWebOfTrust.getFCPInterface().sendBeginOrEndSynchronizationNotification(
                    clientID,
                    (BeginSynchronizationNotification<?>)notification);
            } else {
                throw new UnsupportedOperationException("Unknown notification type: "
                    + notification);
            }
		}

		/**
		 * Stores a {@link ScoreChangedNotification} to the {@link Notification} queue of this {@link Client}.
		 * 
		 * @param oldScore The version of the {@link Score} before the change. Null if it was newly created.
		 * @param newScore The version of the {@link Score} after the change. Null if it was deleted.
		 */
		@Override void storeNotificationWithoutCommit(final Score oldScore, final Score newScore) {
			final ScoreChangedNotification notification = new ScoreChangedNotification(this, oldScore, newScore);
			notification.initializeTransient(mWebOfTrust);
			notification.storeWithoutCommit();
		}

	}

	
	/**
	 * After a {@link Notification} command is stored, we wait this amount of time before processing it.
	 * This is to allow some coalescing when multiple notifications happen in a short interval.
	 * This is usually the case as the import of trust lists often causes multiple changes.
	 * 
	 * Further, if deploying a {@link Notification} fails and its resend-counter is not exhausted, it will be resent after this delay.
	 */
	public static final long PROCESS_NOTIFICATIONS_DELAY = 60 * 1000;
	
	/**
	 * If {@link Client#sendNotifications(SubscriptionManager)} fails, the failure counter of the subscription is incremented.
	 * If the counter reaches this value, the client is disconnected.
	 */
	public static final byte DISCONNECT_CLIENT_AFTER_FAILURE_COUNT = 5;
	
	
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
	 * The SubscriptionManager schedules execution of its notification deployment thread on this
	 * {@link DelayedBackgroundJob}.<br>
	 * The execution typically is scheduled after a delay of {@link #PROCESS_NOTIFICATIONS_DELAY}.
	 * <br><br>
	 * 
     * The value distinguishes the run state of this SubscriptionManager as follows:<br>
     * - Until {@link #start()} was called, defaults to {@link MockDelayedBackgroundJob#DEFAULT}
     *   with {@link DelayedBackgroundJob#isTerminated()} == true.<br>
     * - Once {@link #start()} has been called, becomes a
     *   {@link TickerDelayedBackgroundJob} with {@link DelayedBackgroundJob#isTerminated()}
     *   == false.<br>
     * - Once {@link #stop()} has been called, stays a {@link TickerDelayedBackgroundJob} but has
     *   {@link DelayedBackgroundJob#isTerminated()} == true for ever.<br><br>
     * 
     * There can be exactly one start() - stop() lifecycle, a SubscriptionManager cannot be
     * recycled.<br><br>
     * 
     * Volatile since {@link #stop()} needs to use it without synchronization.
	 */
    private volatile DelayedBackgroundJob mJob = MockDelayedBackgroundJob.DEFAULT;


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
	 * Thrown when a single {@link Client} tries to file a {@link Subscription} of the same class of
	 * {@link EventSource}.
	 * 
	 * TODO: Performance: Do not generate a stack trace, as this is a planned Exception.
	 * 
	 * @see #throwIfSimilarSubscriptionExists
	 */
	@SuppressWarnings("serial")
	public static final class SubscriptionExistsAlreadyException extends Exception {
		public final Subscription<? extends EventSource> existingSubscription;
		
		public SubscriptionExistsAlreadyException(
		        Subscription<? extends EventSource> existingSubscription) {
		    
			this.existingSubscription = existingSubscription;
		}
	}
	
	/**
	 * Thrown by various functions which query the database for a certain {@link Client} if none exists matching the given filters.
	 * TODO: Performance: Look at the throwers and see whether this Exception is predictable enough
	 * to justify not generating a stack trace.
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
	 * TODO: Performance: Look at the throwers and see whether this Exception is predictable enough
	 * to justify not generating a stack trace.
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
	 * Throws when a single {@link Client} tries to file a {@link Subscription} which has the same
	 * class as the given Subscription (= the same class of {@link EventSource} as type parameter).
	 * 
	 * Used to ensure that each client can only subscribe once to each type of event.
	 * 
	 * @param subscription The new subscription which the client is trying to create. The database is checked for an existing one with similar properties as specified above.
	 * @throws SubscriptionExistsAlreadyException If a {@link Subscription} exists which matches the attributes of the given Subscription as specified in the description of the function.
	 */
	@SuppressWarnings("unchecked")
	private synchronized void throwIfSimilarSubscriptionExists(
	        final Subscription<? extends EventSource> subscription)
	            throws SubscriptionExistsAlreadyException {
	    
		try {
			final Client client = subscription.getClient();
			if(!mDB.isStored(client))
				return; // The client was newly created just for this subscription so there cannot be any similar subscriptions on it. 
			final Subscription<? extends EventSource> existing
			    = getSubscription(
			        (Class<? extends Subscription<? extends EventSource>>) subscription.getClass()
			        , client
			      );
			throw new SubscriptionExistsAlreadyException(existing);
		} catch (UnknownSubscriptionException e) {
			return;
		}
	}
	
	/**
	 * Calls {@link Subscription#synchronizeSubscriberByFCP()} on the Subscription, stores it and commits the transaction.
	 * 
	 * Shall be used as back-end for all front-end functions for creating subscriptions.
	 * 
	 * <b>Thread safety:</b><br>
	 * This must be called while locking upon the {@link WebOfTrust}, the SubscriptionManager
	 * and the {@link Persistent#transactionLock(ExtObjectContainer)}.<br>
	 * You must take care of transaction management.<br>
	 * 
	 * @throws SubscriptionExistsAlreadyException
	 *             Thrown if a subscription of the same type for the same client exists already.<br>
	 *             See {@link #throwIfSimilarSubscriptionExists(Subscription)}.<br>
	 *             Thrown before the database is modified in any way - you do not have to rollback
	 *             the transaction.
     * @throws InterruptedException
     *             If an external thread requested the current thread to terminate via
     *             {@link Thread#interrupt()} while the data was being transfered to the client.
     *             <br>This is a necessary shutdown mechanism as synchronization of a Subscription
     *             transfers possibly the whole WOT database to the client and therefore can take
     *             a very long time. Please honor it by terminating the thread so WOT can shutdown
     *             quickly.<br>
     *             TODO: Performance: Throwing InterruptedException is not implemented yet.
     *             It should be thrown by {@link Subscription#storeSynchronizationWithoutCommit()}
     *             as that is the callee of this function which can take very long.<br>
     *             It would be trivial to implement it to check {@link Thread#interrupted()} in
     *             its main loop and throw if it returns true. What's difficult is determining in
     *             {@link SubscriptionManager#stop()} which *are* the Threads which need to be
     *             interrupted - we do not store them anywhere, they are created by fred when
     *             calling the FCP interface's
     *             {@link FredPluginFCPMessageHandler.ServerSideFCPMessageHandler}.
	 */
	private void storeNewSubscriptionWithoutCommit(
	        final Subscription<? extends EventSource> subscription)
	            throws InterruptedException, SubscriptionExistsAlreadyException {
	    
		subscription.initializeTransient(mWoT);

		throwIfSimilarSubscriptionExists(subscription);
		
		// Needs the lock on mWoT which the JavaDoc requests
		subscription.storeSynchronizationWithoutCommit();
		
		subscription.storeWithoutCommit();
		Logger.normal(this, "Subscribed: " + subscription);
	}
	
	/**
	 * The {@link Client} is notified when an {@link Identity} or {@link OwnIdentity} is added, changed or deleted.
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
	 * <br><br>
	 *  
	 * TODO: Code quality: Code duplication at subscribeToTrusts() and subscribeToScores()
	 * TODO: Code quality: Rename to subscribeToIdentitiesByFCP() or similar.
	 * 
	 * @param fcpID The identifier of the FCP connection of the {@link Client}. Must be unique among all FCP connections!
	 * @return The return value of {@link Subscription#getID()} of the created subscription.<br>
	 *         TODO: Return the Subscription object after this is fixed:
	 *         https://bugs.freenetproject.org/view.php?id=6247
	 * @throws InterruptedException
	 *             If an external thread requested the current thread to terminate via
	 *             {@link Thread#interrupt()} while the data was being transfered to the client.
	 *             <br>This is a necessary shutdown mechanism as synchronization of a Subscription
	 *             possibly creates a full copy of the whole WOT database and therefore can take a
	 *             very long time. Please honor it by terminating the thread so WOT can shutdown
	 *             quickly. 
	 * @see IdentityChangedNotification The type of {@link Notification} which is sent when an event happens.
	 */
    public String subscribeToIdentities(UUID fcpID)
            throws InterruptedException, SubscriptionExistsAlreadyException {

		synchronized(mWoT) {
		synchronized(this) {
		synchronized(Persistent.transactionLock(mDB)) {
		    try {
    			final IdentitiesSubscription subscription
    			    = new IdentitiesSubscription(getOrCreateClient(fcpID));
    			storeNewSubscriptionWithoutCommit(subscription);
    			subscription.checkedCommit(this);
    			return subscription.getID();
		    } catch(RuntimeException e) {
		        Persistent.checkedRollbackAndThrow(mDB, this, e);
		        throw e; // Satisfy the compiler: Without, it would complain about missing return.
		    } catch(InterruptedException e) {
		        // Shutdown of WOT was requested. This is normal mode of operation.
		        Persistent.checkedRollback(mDB, this, e, LogLevel.NORMAL);
		        throw e;
		    } catch(SubscriptionExistsAlreadyException e) {
		        // This is thrown before anything is stored to the database, rollback not needed.
		        throw e;
		    }
		}
		}
		}
	}
	
	/**
	 * The {@link Client} is notified when a {@link Trust} changes, is created or removed.
	 * 
	 * @param fcpID The identifier of the FCP connection of the {@link Client}. Must be unique among all FCP connections!
     * @return The return value of {@link Subscription#getID()} of the created subscription.<br>
     *         TODO: Return the Subscription object after this is fixed:
     *         https://bugs.freenetproject.org/view.php?id=6247
     * @throws InterruptedException
     *             If an external thread requested the current thread to terminate via
     *             {@link Thread#interrupt()} while the data was being transfered to the client.
     *             <br>This is a necessary shutdown mechanism as synchronization of a Subscription
     *             possibly creates a full copy of the whole WOT database and therefore can take a
     *             very long time. Please honor it by terminating the thread so WOT can shutdown
     *             quickly.
	 * @see TrustChangedNotification The type of {@link Notification} which is sent when an event happens.
	 */
	public String subscribeToTrusts(UUID fcpID)
	    throws InterruptedException, SubscriptionExistsAlreadyException {
	    
		synchronized(mWoT) {
		synchronized(this) {
		synchronized(Persistent.transactionLock(mDB)) {
	        try {
    			final TrustsSubscription subscription
    			    = new TrustsSubscription(getOrCreateClient(fcpID));
    			storeNewSubscriptionWithoutCommit(subscription);
    			subscription.checkedCommit(this);
    			return subscription.getID();
	        } catch(RuntimeException e) {
                Persistent.checkedRollbackAndThrow(mDB, this, e);
                throw e; // Satisfy the compiler: Without, it would complain about missing return.
	        } catch(InterruptedException e) {
	            // Shutdown of WOT was requested. This is normal mode of operation.
	            Persistent.checkedRollback(mDB, this, e, LogLevel.NORMAL);
	            throw e;
	        } catch(SubscriptionExistsAlreadyException e) {
	            // This is thrown before anything is stored to the database, rollback not needed.
	            throw e;
	        }
		}
		}
		}
	}
	
	/**
	 * The {@link Client} is notified when a {@link Score} changes, is created or removed.
	 * 
	 * @param fcpID The identifier of the FCP connection of the {@link Client}. Must be unique among all FCP connections!
     * @return The return value of {@link Subscription#getID()} of the created subscription.<br>
     *         TODO: Return the Subscription object after this is fixed:
     *         https://bugs.freenetproject.org/view.php?id=6247
     * @throws InterruptedException
     *             If an external thread requested the current thread to terminate via
     *             {@link Thread#interrupt()} while the data was being transfered to the client.
     *             <br>This is a necessary shutdown mechanism as synchronization of a Subscription
     *             possibly creates a full copy of the whole WOT database and therefore can take a
     *             very long time. Please honor it by terminating the thread so WOT can shutdown
     *             quickly.
	 * @see ScoreChangedNotification The type of {@link Notification} which is sent when an event happens.
	 */
	public String subscribeToScores(UUID fcpID)
	        throws InterruptedException, SubscriptionExistsAlreadyException {
	    
		synchronized(mWoT) {
		synchronized(this) {
	    synchronized(Persistent.transactionLock(mDB)) {
	        try {
	            final ScoresSubscription subscription
	                = new ScoresSubscription(getOrCreateClient(fcpID));
	            storeNewSubscriptionWithoutCommit(subscription);
	            subscription.checkedCommit(this);
	            return subscription.getID();
	        } catch(RuntimeException e) {
	            Persistent.checkedRollbackAndThrow(mDB, this, e);
	            throw e; // Satisfy the compiler: Without, it would complain about missing return.
	        } catch(InterruptedException e) {
	            // Shutdown of WOT was requested. This is normal mode of operation.
	            Persistent.checkedRollback(mDB, this, e, LogLevel.NORMAL);
	            throw e;
	        } catch(SubscriptionExistsAlreadyException e) {
	            // This is thrown before anything is stored to the database, rollback not needed.
	            throw e;
	        }
	    }
		}
		}
	}
	
	/**
	 * Deletes the given {@link Subscription}.
	 * 
	 * @param subscriptionID See {@link Subscription#getID()}
	 * @return The class of the terminated {@link Subscription}
	 * @throws UnknownSubscriptionException If no subscription with the given ID exists.
	 */
	@SuppressWarnings("unchecked")
	public Class<Subscription<? extends EventSource>> unsubscribe(String subscriptionID)
	        throws UnknownSubscriptionException {
	    
		synchronized(this) {
		final Subscription<? extends EventSource> subscription = getSubscription(subscriptionID);
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
		return (Class<Subscription<? extends EventSource>>) subscription.getClass();
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
	
	/**
	 * @see Client#getFCP_ID()
	 */
	private Client getClient(final UUID fcpID) throws UnknownClientException {
		final Query q = mDB.query();
		q.constrain(Client.class);
		q.descend("mFCP_ID").constrain(fcpID.toString());
		final ObjectSet<Client> result = new Persistent.InitializingObjectSet<Client>(mWoT, q);
		
		switch(result.size()) {
			case 1: return result.next();
			case 0: throw new UnknownClientException(fcpID.toString());
			default: throw new DuplicateObjectException(fcpID.toString());
		}
	}
	
	/**
	 * Gets the {@link Client} with the given FCP ID, see {@link Client#getFCP_ID()}. If none exists, it is created.
	 * It will NOT be stored to the database if it was created.
	 */
	private Client getOrCreateClient(final UUID fcpID) {
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
	private ObjectSet<Subscription<? extends EventSource>> getAllSubscriptions() {
		final Query q = mDB.query();
		q.constrain(Subscription.class);
		return new Persistent.InitializingObjectSet<Subscription<? extends EventSource>>(mWoT, q);
	}
	
	/**
	 * @return All subscriptions of the given {@link Client}.
	 */
	private ObjectSet<Subscription<? extends EventSource>> getSubscriptions(final Client client) {
		final Query q = mDB.query();
		q.constrain(Subscription.class);
		q.descend("mClient").constrain(client).identity();
		return new Persistent.InitializingObjectSet<Subscription<? extends EventSource>>(mWoT, q);
	}
	
	/**
	 * Get all {@link Subscription}s to a certain {@link EventSource} type.
	 * 
	 * Typically used by the functions store*NotificationWithoutCommit() for storing a type of
	 * {@link Notification} which fits the given the {@link EventSource} type; and those
	 * Notifications are there stored to the queues of all Subscriptions which are subscribed to
	 * the given {@link EventSource} type.
	 * 
	 * @param clazz The type of {@link EventSource} to filter by.
	 * @return Get all {@link Subscription}s to a certain {@link EventSource} type.
	 */
	private ObjectSet<? extends Subscription<? extends EventSource>> getSubscriptions(
	        final Class<? extends Subscription<? extends EventSource>> clazz) {
	    
		final Query q = mDB.query();
		q.constrain(clazz);
		return new Persistent.InitializingObjectSet<Subscription<? extends EventSource>>(mWoT, q);
	}
	
	/**
	 * @param id The unique identificator of the desired {@link Subscription}. See {@link Subscription#getID()}.
	 * @return The {@link Subscription} with the given ID. Only one Subscription can exist for a single ID.
	 * @throws UnknownSubscriptionException If no {@link Subscription} exists with the given ID.
	 */
	private Subscription<? extends EventSource> getSubscription(final String id)
	        throws UnknownSubscriptionException {
	    
		final Query q = mDB.query();
		q.constrain(Subscription.class);
		q.descend("mID").constrain(id);	
		ObjectSet<Subscription<? extends EventSource>> result
		    = new Persistent.InitializingObjectSet<Subscription<? extends EventSource>>(mWoT, q);
		
		switch(result.size()) {
			case 1: return result.next();
			case 0: throw new UnknownSubscriptionException(id);
			default: throw new DuplicateObjectException(id);
		}
	}
	
	/**
	 * Gets a  {@link Subscription} which matches the given parameters:
	 * - the given class of Subscription and thereby class of its generic param {@link EventSource}.
	 * - the given {@link Client}
	 * 
	 * Only one {@link Subscription} which matches both of these can exist: Each {@link Client} can
	 * only subscribe once to a type of {@link EventSource}.
	 * 
	 * Typically used by {@link #throwIfSimilarSubscriptionExists(Subscription)}.
	 * 
	 * @param clazz The class of the {@link Subscription}.
	 * @param client The {@link Client} which has created the {@link Subscription}.
	 * @return See description.
	 * @throws UnknownSubscriptionException If no matching {@link Subscription} exists.
	 */
	private Subscription<? extends EventSource> getSubscription(
	        final Class<? extends Subscription<? extends EventSource>> clazz, final Client client)
	            throws UnknownSubscriptionException {
	    
		final Query q = mDB.query();
		q.constrain(clazz);
		q.descend("mClient").constrain(client).identity();		
		ObjectSet<Subscription<? extends EventSource>> result
		    = new Persistent.InitializingObjectSet<Subscription<? extends EventSource>>(mWoT, q);
		
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
	 * 
	 * ATTENTION: Outside classes should only use this for debugging purposes such as {@link WebOfTrust#checkForDatabaseLeaks()}.
	 */
	protected synchronized final void deleteAllClients() {
		Logger.normal(this, "Deleting all clients...");
		
		synchronized(Persistent.transactionLock(mDB)) {
			try {
				for(Notification n : getAllNotifications()) {
					n.deleteWithoutCommit();
				}
				
				for(Subscription<? extends EventSource> s : getAllSubscriptions()) {
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
	 * 
	 * Typically used for:
	 * - Deleting a subscription in {@link Subscription#deleteWithoutCommit(SubscriptionManager)}
	 * 
	 * @param subscription The {@link Subscription} of whose queue to return notifications from.
	 * @return All {@link Notification}s on the queue of the subscription.
	 */
	private ObjectSet<? extends Notification> getNotifications(
	        final Subscription<? extends EventSource> subscription) {
	    
		final Query q = mDB.query();
		q.constrain(Notification.class);
		q.descend("mSubscription").constrain(subscription).identity();
		return new Persistent.InitializingObjectSet<Notification>(mWoT, q);
	}
	
 	/**
 	 * Gets all {@link Notification} objects in the queue of the given {@link Client}.
	 * They are ordered ascending by the time of when the event which triggered them happened.
	 * 
	 * Precisely, they are ordered by their {@link Notification#mIndex}.
 	 * 
 	 * Typically used for deploying the notification queue of a Subscription in {@link Client#sendNotifications(SubscriptionManager)}
 	 * 
 	 * @param subscription The {@link Client} of whose queue to return notifications from.
	 * @return All {@link Notification}s on the queue of the {@link Client}, ordered ascending by time of happening of their inducing event.
 	 */
	private ObjectSet<? extends Notification> getNotifications(final Client client) {
		final Query q = mDB.query();
		q.constrain(Notification.class);
		q.descend("mClient").constrain(client).identity();
		q.descend("mIndex").orderAscending();
		return new Persistent.InitializingObjectSet<Notification>(mWoT, q);
	}
	
	/**
	 * Interface for the core of WOT to queue an {@link IdentityChangedNotification} to be deployed to all {@link Client}s subscribed to that type of notification. 
	 * 
	 * Typically called when a {@link Identity} or {@link OwnIdentity} is added, deleted or its attributes are modified.
	 * See {@link #subscribeToIdentities(String)} for a list of the changes which do or do not trigger a notification.
	 * 
     * <br><br>This function does not store the given objects as real database entries, it
     * only stores a copy of them serialized into a byte[] by {@link Persistent#serialize()},
     * encapsulated into a {@link Notification} database object.<br>
     * Thus, the passed objects will be invisible to regular database queries and you are safe to
     * pass object such as clones which must not be stored in the database for consistency reasons
     * (= not duplicating the objects in the main tables).<br><br>
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
	 * Interface for the core of WOT to queue a {@link TrustChangedNotification} to be deployed to all {@link Client}s subscribed to that type of notification. 
	 * 
	 * Typically called when a {@link Trust} is added, deleted or its attributes are modified.
	 * 
     * <br><br>This function does not store the given objects as real database entries, it
     * only stores a copy of them serialized into a byte[] by {@link Persistent#serialize()},
     * encapsulated into a {@link Notification} database object.<br>
     * Thus, the passed objects will be invisible to regular database queries and you are safe to
     * pass object such as clones which must not be stored in the database for consistency reasons
     * (= not duplicating the objects in the main tables).<br><br>
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
	 * Interface for the core of WOT to queue a {@link ScoreChangedNotification} to be deployed to all {@link Client}s subscribed to that type of notification. 
	 * 
	 * Typically called when a {@link Score} is added, deleted or its attributes are modified.
	 * 
     * <br><br>This function does not store the given objects as real database entries, it
     * only stores a copy of them serialized into a byte[] by {@link Persistent#serialize()},
     * encapsulated into a {@link Notification} database object.<br>
     * Thus, the passed objects will be invisible to regular database queries and you are safe to
     * pass object such as clones which must not be stored in the database for consistency reasons
     * (= not duplicating the objects in the main tables).<br><br>
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
	 * Sends out the {@link Notification} queue of each {@link Client}.
	 * 
	 * Typically called by the DelayedBackgroundJob {@link #mJob} on a separate thread. This is
	 * triggered by {@link #scheduleNotificationProcessing()} - that scheduling function should be
	 * called whenever a {@link Notification} is stored to the database. <br>
	 * {@link Thread#interrupt()} may be called by {@link DelayedBackgroundJob#terminate()} to
	 * request the thread to exit soon for speeding up shutdown.<br><br>
	 * 
	 * If deploying the notifications for a {@link Client} fails, this function is scheduled to be run again after some time.
	 * If deploying for a certain {@link Client} fails more than {@link #DISCONNECT_CLIENT_AFTER_FAILURE_COUNT} times, the {@link Client} is deleted.
	 * 
	 * @see Client#sendNotifications(SubscriptionManager) This function is called on each {@link Client} to deploy the {@link Notification} queue.
	 */
	@Override
	public void run() {
		if(logMINOR) Logger.minor(this, "run()...");
		
		/* We do NOT allow database queries on the WebOfTrust object in sendNotifications: 
		 * Notification objects contain serialized clones of all required objects for deploying them, they are self-contained.
		 * Therefore, we don't have to take the WebOfTrust lock and can execute in parallel to threads which need to lock the WebOfTrust.*/
		// synchronized(mWoT) {
		synchronized(this) {
		    // TODO: Optimization: We should investigate whether we can deploy notifications in
		    // a thread for each client instead of one thread which iterates over all clients:
		    // This will prevent a single slow client from causing all others to starve.
			for(Client client : getAllClients()) {
				try {
					if(client.sendNotifications(this)) {
						// Persistent.checkedCommit(mDB, this);	/* sendNotifications() does this already */
					} else {
						Logger.warning(this, "sendNotifications tells us to delete the Client, deleting it: " + client);
						client.deleteWithoutCommit(this);
						Persistent.checkedCommit(mDB, this);
					}
				} catch(InterruptedException e) {
				    Logger.normal(this, "run(): Got InterruptedException, exiting thread.", e);
				    // Rollback is already done by sendNotifications().
				    return;
				} catch(RuntimeException e) {
					Persistent.checkedRollback(mDB, this, e);
				}
			}
		}
		//}
		
		if(logMINOR) Logger.minor(this, "run() finished.");
	}
	
	/** {@inheritDoc} */
	@Override
	public int getPriority() {
		return NativeThread.LOW_PRIORITY;
	}
	
	/**
	 * Schedules the {@link #run()} method to be executed after a delay of {@link #PROCESS_NOTIFICATIONS_DELAY}
	 */
	private void scheduleNotificationProcessing() {
        assert(mJob != MockDelayedBackgroundJob.DEFAULT)
            : "Should not be called before start() as mJob won't execute then!";
        
        // We do not do this because some unit tests intentionally stop() us before they run.
        /*  assert (!mJob.isTerminated()) : "Should not be called after stop()!"; */
        
        mJob.triggerExecution();
	}
	

	/**
	 * Deletes all old {@link Client}s, {@link Subscription}s and {@link Notification}s and enables subscription processing. 
	 * Enables usage of {@link #scheduleNotificationProcessing()()}.
	 * 
	 * You must call this before any subscriptions are created, so for example before FCP is available.
	 * 
	 * ATTENTION: Does NOT work in unit tests - you must manually trigger subscription processing by calling {@link #run()} there.
	 * 
	 * TODO: Code quality: start() and {@link #stop()} are partly duplicated in class
	 * IdentityFetcher. It might thus make sense to add basic functionality of start() / stop()
	 * to {@link BackgroundJob}. This might for example include the ability to specify pre-startup
	 * and pre-shutdown callbacks which the {@link BackgroundJob} calls upon us so we can do
	 * our own initialization / cleanup.
	 */
	protected synchronized void start() {
		Logger.normal(this, "start()...");

        // This is thread-safe guard against concurrent multiple calls to start() / stop() since
        // stop() does not modify the job and start() is synchronized. 
        if(mJob != MockDelayedBackgroundJob.DEFAULT)
            throw new IllegalStateException("start() was already called!");
        
        // This must be called while synchronized on this SubscriptionManager, and the lock must be
        // held until mJob is set (which we do by having the whole function be synchronized):
        // Holding the lock prevents Clients or Notifications from being created before
        // scheduleNotificationProcessing() is made functioning by setting mJob.
        // It is critically necessary for scheduleNotificationProcessing() to be working before
        // any Clients/Notifications can be created: Notifications to Clients will only be sent out
        // if scheduleNotificationProcssing() is functioning at the moment a Notification is
        // created.
		deleteAllClients();
		
		final PluginRespirator respirator = mWoT.getPluginRespirator();
        final Ticker ticker;
        final Runnable jobRunnable;
        
		if(respirator != null) { // We are connected to a node
            ticker = respirator.getNode().getTicker();
            jobRunnable = this;
		} else { // We are inside of a unit test
		    Logger.warning(this, "No PluginRespirator available, will never run job. "
		                       + "This should only happen in unit tests!");
            
            // Generate our own Ticker so we can set mJob to be a real TickerDelayedBackgroundJob.
            // This is better than leaving it be a MockDelayedBackgroundJob because it allows us to
            // clearly distinguish the run state (start() not called, start() called, stop() called)
            ticker = new PrioritizedTicker(new PooledExecutor(), 0);
            jobRunnable = new Runnable() { @Override public void run() {
                 // Do nothing because:
                 // - We shouldn't do work on custom executors, we should only ever use the main
                 //   one of the node.
                 // - Unit tests execute instantly after loading the WOT plugin, so delayed jobs
                 //   should not happen since their timing cannot be guaranteed to match the unit
                 //   tests execution state.
               };
            };
		}
		
        // Set the volatile mJob after all of startup is finished to ensure that stop() can use it
        // *without* synchronization to check whether start() was called already.
        mJob = new TickerDelayedBackgroundJob(
            jobRunnable, "WoT SubscriptionManager", PROCESS_NOTIFICATIONS_DELAY, ticker);
		
		Logger.normal(this, "start() finished.");
	}
	
	/**
	 * Shuts down this SubscriptionManager by aborting all queued notification processing and waiting for running processing to finish.
	 * 
	 * Notice: Not synchronized so it can be run in parallel with {@link #run()}. This will allow it
	 * to call {@link DelayedBackgroundJob#terminate()} while run() is executing, which calls
	 * {@link Thread#interrupt()} on the run()-thread to cause it to exit quickly.
	 */
	protected void stop() {
		Logger.normal(this, "stop()...");
		
        // The following code intentionally does NOT write to the mJob variable so it does not have
        // to use synchronized(this). We do not want to synchronize because:
        // 1) run() is synchronized(this), so we would not get the lock until run() is finished.
        //    But we want to call mJob.terminate() immediately while run() is still executing to
        //    make it call Thread.interrupt() upon run() to speed up its termination. So we
        //    shouldn't require acquisition of the lock before terminate().
        // 2) Keeping mJob as is makes sure that start() is not possible anymore so this object can
        //    only have a single lifecycle. Recycling being impossible reduces complexity and is not
        //    needed for normal operation of WOT anyway.
        
        
        // Since mJob can only transition from not "not started yet" as implied by the "==" here
        // to "started" as implied by "!=", but never backwards, and is set by start() after
        // everything is completed, this is thread-safe against concurrent start() / stop().
        if(mJob == MockDelayedBackgroundJob.DEFAULT)
            throw new IllegalStateException("start() not called yet!");
        
        // We cannot guard against concurrent stop() here since we don't synchronize, we can only
        // probabilistically detect it by assert(). Concurrent stop() is not a problem though since
        // restarting jobs is not possible: We cannot run into a situation where we accidentally
        // stop the wrong lifecycle. It can only happen that we do cleanup the cleanup which a
        // different thread would have done, but they won't care since all used functions below will
        // succeed silently if called multiple times.
        assert !mJob.isTerminated() : "stop() called already";
        
        mJob.terminate();
		try {
		    // TODO: Performance: Decrease if it doesn't interfere with plugin unloading. I would
		    // rather not though: Plugin unloading unloads the JAR of the plugin, and thus all its
		    // classes. That will probably cause havoc if threads of it are still running.
            mJob.waitForTermination(Long.MAX_VALUE);
        } catch (InterruptedException e) {
            // We are a shutdown function, there is no sense in sending a shutdown signal to us.
            Logger.error(this, "stop() should not be interrupt()ed.", e);
        }

		Logger.normal(this, "stop() finished.");
	}


    // Public getters for statistics

    /**
     * @return The total amount of all {@link Notification}s which are queued for sending.
     */
    public synchronized int getPendingNotificationAmount() {
        return getAllNotifications().size();
    }

    /**
     * @return The total amount of all {@link Notification}s which have been created, including
     *         unsent ones.<br>
     *         ATTENTION: This excludes Notifications of already disconnected {@link Client}s!
     */
    public synchronized long getTotalNotificationsAmountForCurrentClients() {
        long amount = 0;
        for(Client client : getAllClients()) {
            amount += client.mNextNotificationIndex;
        }
        return amount;
    }

}
