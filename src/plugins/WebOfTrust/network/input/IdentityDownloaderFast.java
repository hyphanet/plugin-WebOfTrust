/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.network.input;

import static java.util.Objects.requireNonNull;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;

import plugins.WebOfTrust.Identity;
import plugins.WebOfTrust.OwnIdentity;
import plugins.WebOfTrust.Score;
import plugins.WebOfTrust.Trust;
import plugins.WebOfTrust.WebOfTrust;
import plugins.WebOfTrust.XMLTransformer;
import plugins.WebOfTrust.exceptions.NotTrustedException;
import plugins.WebOfTrust.util.Daemon;
import plugins.WebOfTrust.util.IdentifierHashSet;
import plugins.WebOfTrust.util.jobs.DelayedBackgroundJob;
import freenet.client.HighLevelSimpleClient;
import freenet.client.async.ClientContext;
import freenet.client.async.USKManager;
import freenet.client.async.USKRetriever;
import freenet.node.NodeClientCore;
import freenet.node.RequestClient;
import freenet.node.RequestStarter;
import freenet.pluginmanager.PluginRespirator;

/**
 * Uses {@link USKManager} to subscribe to the USK of all "directly trusted" {@link Identity}s.
 * 
 * Directly trusted hereby means: At least one {@link OwnIdentity} has assigned a
 * {@link Trust#getValue()} of >= 0.
 * Further, for the purpose of {@link WebOfTrust#restoreOwnIdentity(freenet.keys.FreenetURI)},
 * all {@link OwnIdentity}s are also considered as directly trusted.
 * See {@link #shouldDownload(Identity)}.
 * 
 * This notably is only a small subset of the total set of {@link Identity}s.
 * That's necessary because USK subscriptions are expensive, they create a constant load of
 * polling on the network.
 * The lack of this class subscribing to all {@link Identity}s is compensated by
 * {@link IdentityDownloaderSlow} which deals with the rest of them in a less expensive manner.
 * 
 * FIXME: Add logging to callbacks and {@link DownloadScheduler#run()} */
final class IdentityDownloaderFast implements IdentityDownloader, Daemon {

	/**
	 * Priority of USK subscription network requests, relative to {@link IdentityDownloaderSlow} as
	 * we use a single {@link RequestClient} for that downloader and this one.
	 * 
	 * Compared to {@link #DOWNLOAD_PRIORITY_PROGRESS}, this priority here is for periodic blind
	 * search of new USK editions. The other priority is for download of the actual payload data of
	 * the new editions once we discovered them.
	 * 
	 * Also see the file "developer-documentation/RequestClient and priority map.txt" */
	public static transient final short DOWNLOAD_PRIORITY_POLLING
		= RequestStarter.UPDATE_PRIORITY_CLASS;

	/**
	 * Priority of USK subscription network requests, relative to {@link IdentityDownloaderSlow} as
	 * we use a single {@link RequestClient} for that downloader and this one.
	 * 
	 * Compared to {@link #DOWNLOAD_PRIORITY_POLLING}, this priority here is for download of the
	 * actual payload data of the new editions once we have discovered them. The other priority is
	 * for periodic blind search of new USK editions.
	 * 
	 * Also see the file "developer-documentation/RequestClient and priority map.txt" */
	public static transient final short DOWNLOAD_PRIORITY_PROGRESS
		= RequestStarter.IMMEDIATE_SPLITFILE_PRIORITY_CLASS;


	private final WebOfTrust mWoT;

	/** Is null in unit tests */
	private final USKManager mUSKManager;

	/** Is null in unit tests */
	private final ClientContext mClientContext;

	/** Is null in unit tests */
	private final HighLevelSimpleClient mHighLevelSimpleClient;

	/** @see WebOfTrust#getRequestClient() */
	private final RequestClient mRequestClient;

	private final IdentityDownloaderController mLock;

	/**
	 * Executes the {@link DownloadScheduler} on a thread of its own.
	 * 
	 * FIXME: Document similarly to {@link IdentityDownloaderSlow#mJob}.
	 * FIXME: Initialize & implement. */
	private volatile DelayedBackgroundJob mJob = null;

	private final HashMap<String, USKRetriever> mDownloads = new HashMap<>();


	public IdentityDownloaderFast(WebOfTrust wot) {
		requireNonNull(wot);
		
		mWoT = wot;
		
		PluginRespirator pr = mWoT.getPluginRespirator();
		if(pr != null) {
			NodeClientCore clientCore = pr.getNode().clientCore;
			mUSKManager = clientCore.uskManager;
			mClientContext = clientCore.clientContext;
			mHighLevelSimpleClient = pr.getHLSimpleClient();
		} else { // Unit test
			mUSKManager = null;
			mClientContext = null;
			mHighLevelSimpleClient = null;
		}
		
		mRequestClient = mWoT.getRequestClient();
		mLock = mWoT.getIdentityDownloaderController();
	}

	@Override public void start() {
		// FIXME
	}

	@Override public void terminate() {
		// FIXME
	}

	private boolean shouldDownload(Identity identity) {
		for(Score s : mWoT.getScores(identity)) {
			// Rank 1:
			//   The Identity is directly trusted by an OwnIdentity and thereby from our primary
			//   target group of identities which we should download - as long as the Score
			//   considers it as trustworthy, which shouldMaybeFetchIdentity() checks.
			// Rank 0:
			//   The Identity is an OwnIdentity. We download it as well for the purpose of
			//   WebOfTrust.restoreOwnIdentity(). We do also check shouldMaybeFetchIdentity() to
			//   allow disabling of USK subscriptions once restoring is finished (not implemented
			//   yet).
			if(s.getRank() <= 1 && mWoT.shouldMaybeFetchIdentity(s))
				return true;
			
			// Rank Integer.MAX_VALUE:
			//   This is a special rank which is given to signal that an identity isn't allowed to
			//   introduce other identities (to avoid the sybil attack). It is given to identities
			//   which only have received a trust value of <= 0. Trust < 0 is considered as "don't
			//   download", but trust 0 is still considered as download-worthy for the purpose of
			//   allowing the IntroductionPuzzle mechanism: Solving a puzzle should allow an
			//   identity to be downloaded but disallow it from introducing other identities to
			//   ensure they need to solve puzzles as well.
			//   This rank can both be caused by an OwnIdentity's trust OR by a trust of a non-own
			//   Identity. So we must check the trust database both for whether the trust is from
			//   an OwnIdentity and whether it is not < 0.
			if(s.getRank() == Integer.MAX_VALUE) {
				try {
					Trust t = mWoT.getTrust(s.getTruster(), s.getTrustee());
					if(!(t.getValue() < 0)) // Should download if not distrusted.
						return true;
				} catch(NotTrustedException e) {
					// The rank did not come from the OwnIdentity which provided the Score.
				}
			}
		}
		
		return false;
	}

	@Override public void storeStartFetchCommandWithoutCommit(Identity identity) {
		// While a call to this function means that any OwnIdentity wants it to be downloaded
		// indeed we do *not* know whether that desire is due to a direct trust value from any
		// OwnIdentity. Thus we need to check with shouldDownload().
		if(shouldDownload(identity))
			mJob.triggerExecution();
	}

	@Override public void storeAbortFetchCommandWithoutCommit(Identity identity) {
		// This callback is called when absolutely no OwnIdentity wants the Identity to be
		// downloaded. Thus we don't need to do a shouldDownload() check like
		// storeStartFetchCommandWithoutCommit(), we can instead just call triggerExecution() right
		// away.
		mJob.triggerExecution();
	}

	@Override public void storeNewEditionHintCommandWithoutCommit(EditionHint hint) {
		// This callback isn't subject of our interest - it is completely handled by class
		// IdentityDownloaderSlow.
	}

	/**
	 * The download scheduler thread which syncs the running downloads with the database.
	 * One would expect this to be done on the same thread which our download scheduling callbacks
	 * (= functions of our implemented interface {@link IdentityDownloader}) are called upon.
	 * But we must NOT immediately modify the set of running downloads there as what fred does cannot
	 * be undone by a rollback of the pending database transaction during which they are called
	 * - it may be rolled back after the callbacks return and thus we could e.g. keep running
	 * downloads which we shouldn't actually run.
	 * This scheduler thread here will obtain a fresh lock on the database so any pending
	 * transactions are guaranteed to be finished and it can assume that the database is correct
	 * and safely start downloads as indicated by it. */
	private final class DownloadScheduler implements Runnable {
		@Override public void run() {
			synchronized(mWoT) {
			synchronized(mLock) {
				// No need to take a database transaction lock as we don't write anything to the db.
				
				// Determine all downloads which should be running in the end
				IdentifierHashSet<Identity> allToDownload = new IdentifierHashSet<>();
				for(OwnIdentity i : mWoT.getAllOwnIdentities()) {
					for(Trust t : mWoT.getGivenTrusts(i)) {
						if(t.getValue() >= 0)
							allToDownload.add(t.getTrustee());
					}
				}
				
				// Now that we know which downloads should be running in the end we compare that
				// against which ones are currently running and stop/start what's differing.
				
				HashSet<String> toStop = new HashSet<>(mDownloads.keySet());
				toStop.removeAll(allToDownload.identifierSet());
				stopDownloads(toStop);
				
				IdentifierHashSet<Identity> toStart = new IdentifierHashSet<>(allToDownload);
				toStart.removeAllByIdentifier(mDownloads.keySet());
				startDownloads(toStart);
				
				// TODO: Performance: Once this assert has proven to not fail, remove it to replace
				// the above
				//     IdentifierHashSet<Identity> toStart = new IdentifierHashSet<>(allToDownload);
				// with:
				//     IdentifierHashSet<Identity> toStart = allToDownload;
				// We don't need to construct a fresh set if we don't use allToDownload anymore
				// afterwards.
				assert(mDownloads.keySet().equals(allToDownload.identifierSet()));
			}
			}
		}
	}

	private void startDownloads(Collection<Identity> identities) {
		// FIXME: Implement
	}

	private void stopDownloads(Collection<String> identityIDs) {
		// FIXME: Implement
	}

	@Override public boolean getShouldFetchState(Identity identity) {
		// FIXME
		return false;
	}

	@Override public void deleteAllCommands() {
		// FIXME
	}

}
