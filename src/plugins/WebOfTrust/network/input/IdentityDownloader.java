/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.network.input;

import plugins.WebOfTrust.Identity;
import plugins.WebOfTrust.IdentityFetcher;
import plugins.WebOfTrust.IdentityFile;
import plugins.WebOfTrust.IdentityFileProcessor;
import plugins.WebOfTrust.IdentityFileQueue;
import plugins.WebOfTrust.OwnIdentity;
import plugins.WebOfTrust.Persistent;
import plugins.WebOfTrust.Persistent.NeedsTransaction;
import plugins.WebOfTrust.Score;
import plugins.WebOfTrust.SubscriptionManager;
import plugins.WebOfTrust.Trust;
import plugins.WebOfTrust.WebOfTrust;
import plugins.WebOfTrust.network.input.IdentityDownloaderFast.DownloadSchedulerCommand;
import plugins.WebOfTrust.util.Daemon;
import freenet.keys.FreenetURI;

/**
 * Downloads {@link Identity} objects from the P2P network.
 * They are then fed as {@link IdentityFile} to the {@link IdentityFileQueue}, which is consumed by
 * the {@link IdentityFileProcessor}.
 * 
 * Implementations are allowed to and do store pointers to {@link Identity} and {@link OwnIdentity}
 * objects in their database, e.g. as part of {@link EditionHint} objects and
 * {@link DownloadSchedulerCommand}s.
 * They must not store references to any other objects which are not a type managed by their own
 * database, e.g. {@link Trust} or {@link Score} (because this interface only has callbacks for
 * changes to Identity objects).
 * 
 * <b>Locking:</b>
 * Implementations  must synchronize transactions by taking the following locks in the given order:
 * - the {@link WebOfTrust} object
 * - the {@link WebOfTrust#getIdentityDownloaderController()} object, notably INSTEAD OF locking
 *   upon themselves. This is to allow the {@link IdentityDownloaderController} to be the central
 *   lock in case multiple types of IdentityDownloader are running in parallel. That in turn allows
 *   the WoT core to not have to synchronize upon whichever specific IdentityDownloader
 *   implementations are being used currently. It can instead just synchronize upon the single
 *   {@link IdentityDownloaderController} instance.
 * - the {@link Persistent#transactionLock(com.db4o.ext.ExtObjectContainer)}
 * 
 * TODO: Code quality: Rename the event handlers to "on...()".
 *
 * TODO: Code quality: Sort callbacks in an order which makes their purpose the most easy to
 * understand, also in implementing classes.
 *
 * FIXME: Review the whole of class {@link IdentityFetcher} for any important JavaDoc and add it to
 * this interface. */
public interface IdentityDownloader extends Daemon {

	/**
	 * Called by {@link WebOfTrust#deleteOwnIdentity(String)} before any action is taken towards
	 * deleting an {@link OwnIdentity}.
	 * 
	 * After the callback returns the oldIdentity will be deleted from the database.
	 * It will be replaced by a non-own {@link Identity} object. Its given and received
	 * {@link Trust}s, and its received {@link Score}s will keep existing by being replaced with
	 * objects which to point to the replacement Identity.
	 * Any Scores the oldIdentity has given to other Identitys as specified by
	 * {@link WebOfTrust#getGivenScores(OwnIdentity)} will be deleted then.
	 * 
	 * After this callback has returned, and once the replacement Identity has been created and the
	 * {@link Trust} and Score database fully adapted to it, WoT will call
	 * {@link #storePostDeleteOwnIdentityCommand(Identity)} in order to allow implementations to
	 * start download of the replacement Identity if it is eligible for download.
	 * 
	 * Thus implementations have to:
	 * - remove any object references to the oldIdentity object from their db4o database as they
	 *   would otherwise be nulled by the upcoming deletion of it.
	 * - stop downloading of any Identitys who aren't eligible for download anymore because
	 *   they were eligible solely due to one of the to-be-deleted Scores (see the JavaDoc of
	 *   {@link Score} for when Scores justify downloading an Identity).
	 * - stop downloading the oldIdentity (if it was eligible for download due to having received
	 *   a self-assigned Score, see {@link WebOfTrust#initTrustTreeWithoutCommit(OwnIdentity}).
	 * 
	 * ATTENTION: Identitys which had received a Score from the oldIdentity may still be eligible
	 * for download due to a Score received by a different OwnIdentity! Before aborting their
	 * download check their other received Scores using {@link WebOfTrust#getScores(Identity)} and
	 * {@link WebOfTrust#shouldMaybeFetchIdentity(Score)} for whether any of them justifies to keep
	 * downloading the Identity.
	 * 
	 * Implementations can assume that when this function is called:
	 * - the OwnIdentity still is stored in the database, the replacement Identity object has not
	 *   been created yet.
	 * - the Trust and Score database has not been changed yet.
	 * 
	 * Synchronization:
	 * This function is guaranteed to be called while the following locks are being held in the
	 * given order:
	 * synchronized(Instance of WebOfTrust)
	 * synchronized(WebOfTrust.getIdentityDownloaderController())
	 * synchronized(Persistent.transactionLock(WebOfTrust.getDatabase())) */
	@NeedsTransaction void storePreDeleteOwnIdentityCommand(OwnIdentity oldIdentity);

	/**
	 * Called by {@link WebOfTrust#deleteOwnIdentity(String)} as the very last step of deleting
	 * an {@link OwnIdentity}.
	 * This implies that:
	 * - the OwnIdentity has been deleted from the the database, the given replacement
	 *   {@link Identity} object has been stored.
	 * - the {@link Trust} and {@link Score} database has been fully updated to reflect the
	 *   necessary changes.
	 * 
	 * FIXME: {@link #storePreDeleteOwnIdentityCommand(OwnIdentity)} documents more of the duties
	 * of this callback, should be documented here as well.
	 * 
	 * Synchronization:
	 * This function is guaranteed to be called while the following locks are being held in the
	 * given order:
	 * synchronized(Instance of WebOfTrust)
	 * synchronized(WebOfTrust.getIdentityDownloaderController())
	 * synchronized(Persistent.transactionLock(WebOfTrust.getDatabase())) */
	@NeedsTransaction void storePostDeleteOwnIdentityCommand(Identity newIdentity);

	/**
	 * Called by {@link WebOfTrust#deleteWithoutCommit(Identity)} before any action is taken towards
	 * deleting an {@link Identity}.
	 * 
	 * After the callback returns the oldIdentity will be deleted from the database.
	 * In opposite to {@link WebOfTrust#deleteOwnIdentity(String)} there will be no replacement
	 * Identity object created for the deleted Identity - even if it was an {@link OwnIdentity}!
	 * Any {@link Trust}s and {@link Score}s it has given or received will be deleted, see:
	 * - {@link WebOfTrust#getGivenTrusts(Identity)}
	 * - {@link WebOfTrust#getReceivedTrusts(Identity)}
	 * - {@link WebOfTrust#getGivenScores(OwnIdentity)} if the Identity was an {@link OwnIdentity}.
	 * - {@link WebOfTrust#getScores(Identity)}
	 * 
	 * After this callback has returned, in opposite to the other callbacks of this interface, no
	 * such callback as "storePostDeleteIdentityCommand()" will be called. This is because:
	 * - there will be no replacement Identity to pass by a callback.
	 * - deletion of an Identity can only cause abortion of downloads, not starting - which would
	 *   typically be the job of a Post-deletion version of this callback with starting the download
	 *   of the replacement Identity if necessary, but there will be none.
	 * 
	 * Thus implementations have to:
	 * - remove any object references to the oldIdentity object from their db4o database as they
	 *   would otherwise be nulled by the upcoming deletion of it.
	 * - stop downloading of any Identitys who aren't eligible for download anymore because
	 *   they were eligible solely due to one of the to-be-deleted Scores (see the JavaDoc of
	 *   {@link Score} for when Scores justify downloading an Identity).
	 * - stop downloading the oldIdentity.
	 * 
	 * ATTENTION: Identitys which had received a Score from the oldIdentity may still be eligible
	 * for download due to a Score received by a different OwnIdentity! Before aborting their
	 * download check their other received Scores using {@link WebOfTrust#getScores(Identity)} and
	 * {@link WebOfTrust#shouldMaybeFetchIdentity(Score)} for whether any of them justifies to keep
	 * downloading the Identity.
	 * 
	 * Implementations can assume that when this function is called:
	 * - the Identity still is stored in the database.
	 * - the Trust and Score database has not been changed yet.
	 * 
	 * Synchronization:
	 * This function is guaranteed to be called while the following locks are being held in the
	 * given order:
	 * synchronized(Instance of WebOfTrust)
	 * synchronized(WebOfTrust.getIdentityDownloaderController())
	 * synchronized(Persistent.transactionLock(WebOfTrust.getDatabase())) */
	@NeedsTransaction void storePreDeleteIdentityCommand(Identity oldIdentity);

	// There is no replacement Identity when a non-own Identity is deleted.
	/* @NeedsTransaction void storePostDeleteIdentityCommand(Identity newIdentity); */

	/**
	 * Called by {@link WebOfTrust#restoreOwnIdentity(FreenetURI)} before any action is taken
	 * towards restoring an {@link OwnIdentity}.
	 * 
	 * After the callback returns the non-own oldIdentity will be deleted from the database.
	 * It will be replaced by an {@link OwnIdentity} object. Its given and received
	 * {@link Trust}s, and its received {@link Score}s will keep existing by being replaced with
	 * objects which to point to the replacement OwnIdentity.
	 * (No given Scores could have existed for the oldIdentity because only OwnIdentitys are allowed
	 * to give Scores.)
	 * 
	 * After this callback has returned, and once the replacement OwnIdentity has been created and
	 * the {@link Trust} and Score database fully adapted to it, WoT will call
	 * {@link #storePostRestoreOwnIdentityCommand(OwnIdentity)} in order to allow implementations to
	 * start download of both the replacement OwnIdentity if it is eligible for download as well as
	 * the recipients of its newly created positive {@link WebOfTrust#getGivenScores(OwnIdentity)}.
	 * 
	 * Thus implementations have to:
	 * - remove any object references to the oldIdentity object from their db4o database as they
	 *   would otherwise be nulled by the upcoming deletion of it.
	 * - stop downloading the oldIdentity.
	 * 
	 * Implementations can assume that when this function is called:
	 * - the Identity still is stored in the database, the replacement OwnIdentity object has not
	 *   been created yet.
	 * - the Trust and Score database has not been changed yet.
	 * 
	 * Synchronization:
	 * This function is guaranteed to be called while the following locks are being held in the
	 * given order:
	 * synchronized(Instance of WebOfTrust)
	 * synchronized(WebOfTrust.getIdentityDownloaderController())
	 * synchronized(Persistent.transactionLock(WebOfTrust.getDatabase())) */
	@NeedsTransaction void storePreRestoreOwnIdentityCommand(Identity oldIdentity);

	/**
	 * Called by {@link WebOfTrust#restoreOwnIdentity(FreenetURI)} after an {@link OwnIdentity}
	 * was restored, either by replacing a non-own {@link Identity} with it or by creating it from
	 * scratch.
	 * 
	 * This implies that:
	 * - the non-own Identity has been deleted from the the database, the given replacement
	 *   OwnIdentity object has been stored.
	 * - the {@link Trust} and {@link Score} database has been fully updated to reflect the
	 *   necessary changes.
	 * 
	 * For understanding the surrounding conditions of restoreOwnIdentity() please read the
	 * documentation of {@link #storePreRestoreOwnIdentityCommand(Identity)} which is called before
	 * this callback here.
	 * 
	 * Implementations have to:
	 * - start the download of the given newIdentity.
	 * - If a download is already running adjust the edition to the
	 *   {@link Identity#getNextEditionToFetch()} of the newIdentity:
	 *   The user may have provided a {@link FreenetURI#getSuggestedEdition()} in the
	 *   USK URI when restoring the OwnIdentity.
	 * 
	 * NOTICE: Implementations do NOT have to start the download of the {@link Trust} and
	 * {@link Score} recipients of the OwnIdentity.
	 * This is because for technical reasons the downloads to be started from the given Trusts and
	 * Scores of the newIdentity will have already been started by other callbacks having been
	 * triggered by restoreOwnidentity().
	 * These callbacks might e.g. be:
	 * - {@link #storeStartFetchCommandWithoutCommit(Identity)}
	 * - {@link #storeTrustChangedCommandWithoutCommit(Trust, Trust)}
	 * Implementations of this callback here must be safe against side effects from that.
	 * 
	 * Synchronization:
	 * This function is guaranteed to be called while the following locks are being held in the
	 * given order:
	 * synchronized(Instance of WebOfTrust)
	 * synchronized(WebOfTrust.getIdentityDownloaderController())
	 * synchronized(Persistent.transactionLock(WebOfTrust.getDatabase())) */
	@NeedsTransaction void storePostRestoreOwnIdentityCommand(OwnIdentity newIdentity);

	/**
	 * Called by {@link WebOfTrust}:
	 * - as soon as {@link WebOfTrust#shouldFetchIdentity(Identity)} changes from false to true for
	 *   the given {@link Identity}. This is usually the case when *any* {@link OwnIdentity} has
	 *   rated it as trustworthy enough for us to download it.
	 *   The {@link Trust} and {@link Score} database is guaranteed to be up to date when this
	 *   function is called and thus can be used by it.
	 * - when an OwnIdentity is created (but not when it is deleted/restored, see the other
	 *   callbacks for that).
	 * - May also be called to notify the IdentityDownloader about a changed
	 *   {@link Identity#getNextEditionToFetch()} (e.g. due to  {@link Identity#markForRefetch()})
	 *   even if the Identity was already eligible for fetching before.
	 * 
	 * Synchronization:
	 * This function is guaranteed to be called while the following locks are being held in the
	 * given order:
	 * synchronized(Instance of WebOfTrust)
	 * synchronized(WebOfTrust.getIdentityDownloaderController())
	 * synchronized(Persistent.transactionLock(WebOfTrust.getDatabase())) */
	void storeStartFetchCommandWithoutCommit(Identity identity);

	/**
	 * Called by {@link WebOfTrust}:
	 * - as soon as {@link WebOfTrust#shouldFetchIdentity(Identity)} changes from true to false for
	 *   the given {@link Identity}. This is usually the case when not even one {@link OwnIdentity}
	 *   has rated it as trustworthy enough for us to download it.
	 *   The {@link Trust} and {@link Score} database is guaranteed to be up to date when this
	 *   function is called and thus can be used by it.
	 * - but is not called upon deletion/restoring of an OwnIdentity, see the other callbacks for
	 *   that.
	 * 
	 * Synchronization:
	 * This function is guaranteed to be called while the following locks are being held in the
	 * given order:
	 * synchronized(Instance of WebOfTrust)
	 * synchronized(WebOfTrust.getIdentityDownloaderController())
	 * synchronized(Persistent.transactionLock(WebOfTrust.getDatabase())) */
	void storeAbortFetchCommandWithoutCommit(Identity identity);

	/**
	 * Called under almost the same circumstances as
	 * {@link SubscriptionManager#storeTrustChangedNotificationWithoutCommit()} except for the
	 * following differences:
	 * 
	 * - The {@link Trust} *and* {@link Score} database is guaranteed to be up to date when this
	 *   function is called and thus can be used by it.
	 *   Especially the Score database shall already have been updated to reflect the changes due to
	 *   the changed Trust.
	 *   The SubscriptionManager's callback is called before the Score database is updated because:
	 *   Its job is to deploy events to clients in the order they occurred, and if the Score events
	 *   were deployed before the Trust events then clients couldn't see the cause of the Score 
	 *   events before their effect which logically doesn't make sense.
	 *   However the existing implementation of this callback here don't care about this, and in
	 *   fact it does need the Scores, so this difference is hereby required.
	 * 
	 * - Is not called for all changes to attributes of the Trust but will only be called upon:
	 *   * {@link Trust#getValue()} changes.
	 *   * a Trust is created or deleted.
	 * 
	 * - Is NOT called if the {@link Trust#getTruster()} / {@link Trust#getTrustee()} is deleted,
	 *   {@link #storePreDeleteIdentityCommand(Identity)} is called for that case.
	 *   For the truster / trustee changing their type due to deleting / restoring an
	 *   {@link OwnIdentity} there are also separate callbacks.
	 * 
	 * ATTENTION: The passed {@link Trust} objects may be {@link Trust#clone()}s of the original
	 * objects. Hence when you want to do database queries using e.g. them, their
	 * {@link Trust#getTruster()} or {@link Trust#getTrustee()} you need to first re-query those
	 * objects from the database by their ID as the clones are unknown to the database.
	 * FIXME: Review implementations of this function for whether they are safe w.r.t. this.
	 * Alternatively, if {@link WebOfTrust#deleteWithoutCommit(Identity)} is the only function which
	 * passes a clone for newTrust, consider to change it to not call this callback as suggested by
	 * the comments there, and relax the "ATTENTION" to only be about oldTrust (which usually always
	 * be a clone because it represents a historical state).
	 * EDIT: The above FIXME was written before the case of truster/trustee changing their class
	 * between OwnIdentity and Identity was moved to separate callbacks, which also includes
	 * deleteWithoutCommit(Identity). Thus what it assumes about deleteWithoutCommit() likely
	 * doesn't apply anymore.
	 * 
	 * FIXME: Make the WebOfTrust actually call it. Find the places where to call it by using your
	 * IDE to look up where WoT calls the similar function at SubscriptionManager.
	 * Also use use Eclipse's "Open Call Hierarchy" feature to inspect all places where
	 * {@link Trust#storeWithoutCommit()} and {@link Trust#deleteWithoutCommit()} are called.
	 * Do not call it in the very same place but some lines later *after* Score computation is
	 * finished to obey that requirement as aforementioned.
	 * Further an inspiration for determining whether everything is covered is
	 * AbstractJUnit4BaseTest's function doRandomChangesToWoT(), it attempts to cover all types of
	 * changes to the database.
	 * 
	 * FIXME: It might make sense to change the JavaDoc of this callback here to not compare it
	 * to SubscriptionManager's callback anymore as the set of differences has already become too
	 * large.
	 * 
	 * FIXME: Rename to storeOwnTrustChanged...(), make callers only call it for Trusts where
	 * the truster is an OwnIdentity.
	 * They currently are the only ones which IdentityDownloaderFast is interested in, and it likely
	 * will stay as is for a long time.
	 * 
	 * Synchronization:
	 * This function is guaranteed to be called while the following locks are being held in the
	 * given order:
	 * synchronized(Instance of WebOfTrust)
	 * synchronized(WebOfTrust.getIdentityDownloaderController())
	 * synchronized(Persistent.transactionLock(WebOfTrust.getDatabase())) */
	void storeTrustChangedCommandWithoutCommit(Trust oldTrust, Trust newTrust);

	/**
	 * Called by {@link WebOfTrust} when we've downloaded the list of {@link Trust} values of a
	 * remote {@link Identity} and as a bonus payload have received an {@link EditionHint} for
	 * another {@link Identity} it has assigned a {@link Trust} to. An edition hint is the number of
	 * the latest {@link FreenetURI#getEdition()} of the given {@link Identity} as claimed by a
	 * remote identity. We can try to download the hint and if it is indeed downloadable, we are
	 * lucky - but it may very well be a lie. In that case, to avoid DoS, we must discard it and try
	 * the next lower hint we received from someone else.
	 * 
	 * The {@link Trust} and {@link Score} database is guaranteed to be up to date when this
	 * function is called and thus can be used by it.
	 * 
	 * Synchronization:
	 * This function is guaranteed to be called while the following locks are being held in the
	 * given order:
	 * synchronized(Instance of WebOfTrust)
	 * synchronized(WebOfTrust.getIdentityDownloaderController())
	 * synchronized(Persistent.transactionLock(WebOfTrust.getDatabase())) */
	void storeNewEditionHintCommandWithoutCommit(EditionHint hint);

	/**
	 * ATTENTION: For debugging purposes only.
	 * 
	 * Returns the effective state of whether the downloader will download an {@link Identity}
	 * = returns what was last instructed to this downloader using all the callbacks in this
	 * interface.
	 *
	 * Synchronization:
	 * This function is guaranteed to be called while the following locks are being held in the
	 * given order:
	 * synchronized(Instance of WebOfTrust)
	 * synchronized(WebOfTrust.getIdentityDownloaderController())
	 * synchronized(Persistent.transactionLock(WebOfTrust.getDatabase())) */
	boolean getShouldFetchState(Identity identity);

	/**
	 * ATTENTION: For debugging purposes only.
	 * 
	 * Specifically: {@link WebOfTrust#checkForDatabaseLeaks()} uses this for debugging.
	 * 
	 * Synchronization:
	 * This function is NOT called with any locks held! It has to create a database transaction of
	 * its own as follows, while taking the thereby listed locks:
	 * <code>
	 * synchronized(Instance of WebOfTrust) {
	 * synchronized(WebOfTrust.getIdentityDownloaderController()) {
	 * synchronized(Persistent.transactionLock(WebOfTrust.getDatabase())) {
	 *     try {
	 *        deleteTheCommands();
	 *        Persistent.checkedCommit(database, this);
	 *     } catch(RuntimeException e) {
	 *         Persistent.checkedRollbackAndThrow(database, this, e);
	 *     }
	 * }}}
	 * </code> */
	void deleteAllCommands();

}
