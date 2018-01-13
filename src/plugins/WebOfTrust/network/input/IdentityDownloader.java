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
import plugins.WebOfTrust.Score;
import plugins.WebOfTrust.SubscriptionManager;
import plugins.WebOfTrust.Trust;
import plugins.WebOfTrust.WebOfTrust;
import plugins.WebOfTrust.util.Daemon;
import freenet.keys.FreenetURI;

/**
 * Downloads {@link Identity} objects from the P2P network.
 * They are then fed as {@link IdentityFile} to the {@link IdentityFileQueue}, which is consumed by
 * the {@link IdentityFileProcessor}.
 * 
 * FIXME: 
 * Implementations are allowed to and do store pointers to Identity objects in their database,
 * currently as part of {@link EditionHint} objects.
 * Thus we must introduce a new callback which gets called before deletion of an Identity to ensure
 * the IdentityDownloader implementations delete the objects pointing to the to-be-deleted Identity.
 * Further at least the following functions must be amended to call the new callback:
 * - {@link WebOfTrust#deleteWithoutCommit(Identity)}
 * - {@link WebOfTrust#deleteOwnIdentity(String)}
 * - {@link WebOfTrust#restoreOwnIdentityWithoutCommit(freenet.keys.FreenetURI)}
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
 * FIXME: Review the whole of class {@link IdentityFetcher} for any important JavaDoc and add it to
 * this interface. */
public interface IdentityDownloader extends Daemon {

	/**
	 * Called by {@link WebOfTrust}:
	 * - as soon as {@link WebOfTrust#shouldFetchIdentity(Identity)} changes from false to true for
	 *   the given {@link Identity}. This is usually the case when *any* {@link OwnIdentity} has
	 *   rated it as trustworthy enough for us to download it.
	 *   The {@link Trust} and {@link Score} database is guaranteed to be up to date when this
	 *   function is called and thus can be used by it.
	 * - in special cases such as creation/deletion/restoring of an OwnIdentity.
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
	 * - in special cases such as deletion/restoring of an OwnIdentity.
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
	 * - May not be called for all changes to attributes of the Trust but will be called upon:
	 *   * {@link Trust#getValue()} changes.
	 *   * {@link Trust#getTruster()} changes its type from OwnIdentity to Identity or vice versa.
	 *       FIXME: It may not actually be called in the above cases because the implementations of
	 *       {@link WebOfTrust#restoreOwnIdentity(FreenetURI)} and
	 *       {@link WebOfTrust#deleteOwnIdentity(String)} probably handle the type change by
	 *       deleting the trust objects and re-creating them in separate calls to
	 *       {@link WebOfTrust#removeTrust(String, String)} and
	 *       {@link WebOfTrust#setTrust(String, String, byte, String)}.
	 *   * a Trust is created or deleted.
	 * 
	 * - Synchronization requirements:
	 *   This function is guaranteed to be called while the following locks are being held in the
	 *   given order:
	 *   synchronized(Instance of WebOfTrust)
	 *   synchronized(WebOfTrust.getIdentityDownloaderController())
	 *   synchronized(Persistent.transactionLock(WebOfTrust.getDatabase()))
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
	 * 
	 * FIXME: Make the WebOfTrust actually call it. Find the places where to call it by using your
	 * IDE to look up where WoT calls the similar function at SubscriptionManager.
	 * Do not call it in the very same place but some lines later *after* Score computation is
	 * finished to obey that requirement as aforementioned. */
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
	 * = returns what was last instructed to this downloader using
	 * {@link #storeStartFetchCommandWithoutCommit(Identity)}
	 * or {@link #storeAbortFetchCommandWithoutCommit(Identity)}:
	 * True if the last command was one for starting the fetch, false if it was for stopping it.
	 * 
	 * This considers both queued commands as well as already processed commands.
	 * It will also check for contradictory commands in the command queue which would be a bug
	 * (= both start and stop command at once).
	 *
	 * Synchronization:
	 * This function is guaranteed to be called while the following locks are being held in the
	 * given order:
	 * synchronized(Instance of WebOfTrust)
	 * synchronized(WebOfTrust.getIdentityDownloaderController()) */
	boolean getShouldFetchState(Identity identity);

	/**
	 * ATTENTION: For debugging purposes only.
	 * 
	 * Specifically: {@link WebOfTrust#checkForDatabaseLeaks()} uses this for debugging. */
	void deleteAllCommands();

}
