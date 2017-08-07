/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.network.input;

import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MINUTES;

import java.net.MalformedURLException;
import java.util.Collection;
import java.util.HashMap;

import plugins.WebOfTrust.Identity;
import plugins.WebOfTrust.IdentityFetcher;
import plugins.WebOfTrust.OwnIdentity;
import plugins.WebOfTrust.Persistent;
import plugins.WebOfTrust.Persistent.InitializingObjectSet;
import plugins.WebOfTrust.Score;
import plugins.WebOfTrust.Trust;
import plugins.WebOfTrust.WebOfTrust;
import plugins.WebOfTrust.XMLTransformer;
import plugins.WebOfTrust.exceptions.DuplicateObjectException;
import plugins.WebOfTrust.exceptions.NotTrustedException;
import plugins.WebOfTrust.introduction.IntroductionPuzzle;
import plugins.WebOfTrust.util.Daemon;
import plugins.WebOfTrust.util.IdentifierHashSet;
import plugins.WebOfTrust.util.jobs.DelayedBackgroundJob;

import com.db4o.ext.ExtObjectContainer;
import com.db4o.query.Query;

import freenet.client.FetchContext;
import freenet.client.FetchResult;
import freenet.client.HighLevelSimpleClient;
import freenet.client.async.ClientContext;
import freenet.client.async.USKManager;
import freenet.client.async.USKRetriever;
import freenet.client.async.USKRetrieverCallback;
import freenet.keys.FreenetURI;
import freenet.keys.USK;
import freenet.node.NodeClientCore;
import freenet.node.PrioRunnable;
import freenet.node.RequestClient;
import freenet.node.RequestStarter;
import freenet.pluginmanager.PluginRespirator;
import freenet.support.Logger;
import freenet.support.io.NativeThread;

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
public final class IdentityDownloaderFast implements
		IdentityDownloader,
		Daemon,
		USKRetrieverCallback {

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

	/**
	 * Priority of the {@link DownloadScheduler} thread which starts/stops downloads.
	 * 
	 * HIGH_PRIORITY instead of a lower one because we only download Identitys to which the user has
	 * manually assigned Trust values and thus our processing is the result of UI interaction and
	 * thereby quite important.
	 * 
	 * TODO: Performance: Actually the trust values of an OwnIdentity can also change due to a
	 * remote user having solved an {@link IntroductionPuzzle} - should we thus use a different
	 * thread priority constant, or perhaps even chose priority individually for the next execution
	 * of {@link DownloadScheduler} by introducing a member variable there to determine the return
	 * value of {@link DownloadScheduler#getPriority()}?
	 * This also raises the question of whether we should maybe have a batch processing delay like
	 * {@link IdentityDownloaderSlow#QUEUE_BATCHING_DELAY_MS} which is used when we receive an
	 * {@link #storeStartFetchCommandWithoutCommit(Identity)} callback merely due to a remove puzzle
	 * solution.
	 * I.e. we should execute the {@link DownloadScheduler} with a delay > 0 for remote changes in
	 * addition to using a delay of 0 for local changes by the user as we currently do already. */
	public static transient final int DOWNLOADER_THREAD_PRIORITY = NativeThread.HIGH_PRIORITY;


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

	private final ExtObjectContainer mDB;

	/**
	 * FIXME: Document similarly to {@link IdentityDownloaderSlow#mJob}.
	 * FIXME: Initialize & implement.
	 * 
	 * @see DownloadScheduler */
	private volatile DelayedBackgroundJob mDownloadSchedulerThread = null;

	private final HashMap<String, USKRetriever> mDownloads = new HashMap<>();


	private static transient volatile boolean logDEBUG = false;

	private static transient volatile boolean logMINOR = false;

	static {
		Logger.registerClass(IdentityDownloaderFast.class);
	}


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
		mDB = mWoT.getDatabase();
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
		if(shouldDownload(identity)) {
			DownloadSchedulerCommand c = getQueuedCommand(identity);
			
			if(c != null) {
				if(c instanceof StartDownloadCommand)
					return;
				
				if(c instanceof StopDownloadCommand) {
					c.deleteWithoutCommit();
					// At first glance we'd put a "return;" here since the StopDownloadCommand
					// wasn't processed yet and thus there'd be no need to store a
					// StartDownloadCommand to start the download as it is still running.
					// But a second StartDownloadCommand is also used for the purpose of handling
					// Identity.markForRefetch() when a download is already running so we do need
					// to store one and cannot return.
				}
			}
			
			c = new StartDownloadCommand(mWoT, identity);
			c.storeWithoutCommit();
			
			mDownloadSchedulerThread.triggerExecution();
		}
	}

	// FIXME: This is NOT called if the sole direct Trust from an OwnIdenity to the given
	// Identity is deleted but the Identity is still trusted due to indirect trusts.
	// But in that case this fetcher is not responsible for fetching this Identity anymore!
	// Thus we need to introduce a new callback to handle that case, e.g. "onTrustDeleted()".
	@Override public void storeAbortFetchCommandWithoutCommit(Identity identity) {
		DownloadSchedulerCommand c = getQueuedCommand(identity);
		
		if(c != null) {
			if(c instanceof StopDownloadCommand)
				return;
			
			if(c instanceof StartDownloadCommand) {
				c.deleteWithoutCommit();
				// At first glance we'd put a "return;" here since the StartDownloadCommand
				// wasn't processed yet and thus there seems to be no need to store a
				// StopDownloadCommand to stop the download as it not seems to be running yet.
				// But a second StartDownloadCommand is also used for the purpose of handling
				// Identity.markForRefetch() when a download is already running so one could be
				// running already indeed and thus we do need to store a StopDownloadCommand and
				// cannot return.
			}
		}
		
		// While a call to this function means that no OwnIdentity wants it to be downloaded
		// we do *not* know whether the previous desire to download it was due to a direct trust
		// value from any OwnIdentity, i.e. we don't know whether we were actually responsible
		// for downloading it and thus whether there even could be a download to cancel.
		// Thus we should check with mDownloads before uselessly invoking the DownloadScheduler
		// for Identitys which weren't interesting to us anyway.
		// (This variable is valid to use here from a concurrency perspective as the interface
		// specification ensures that our mLock is held while we are called - which is also the lock
		// which guards mDownloads.)
		if(mDownloads.containsKey(identity.getID())) {
			// We cannot just cancel the running download here:
			// This function is being called as part of an unfinished transaction which may still be
			// rolled back after we return, and that would mean that the download needs to continue.
			// Thus we need to cancel the download in a separate transaction, which is what the
			// DownloadScheduler does.
			
			new StopDownloadCommand(mWoT, identity)
				.storeWithoutCommit();
			
			mDownloadSchedulerThread.triggerExecution();
		}
	}

	@Override public void storeNewEditionHintCommandWithoutCommit(EditionHint hint) {
		// This callback isn't subject of our interest - it is completely handled by class
		// IdentityDownloaderSlow.
	}

	@SuppressWarnings("serial")
	public static abstract class DownloadSchedulerCommand extends Persistent {
		@IndexedField private final Identity mIdentity;
		
		DownloadSchedulerCommand(WebOfTrust wot, Identity identity) {
			assert(wot != null);
			assert(identity != null);
			initializeTransient(wot);
			mIdentity = identity;
		}
		
		final Identity getIdentity() {
			checkedActivate(1);
			mIdentity.initializeTransient(mWebOfTrust);
			return mIdentity;
		}
		
		@Override public final String getID() {
			throw new UnsupportedOperationException("Not implemented!");
		}
		
		@Override public void startupDatabaseIntegrityTest() {
			checkedActivate(1);
			requireNonNull(mIdentity);
			
			// Check whether only a single DownloadSchedulerCommand exists for mIdentity by using
			// getQueuedCommand() on mIdentity - it will throw if there is more than one.
			
			DownloadSchedulerCommand queriedFromDB =
				((WebOfTrust)mWebOfTrust).getIdentityDownloaderController()
				.getIdentityDownloaderFast().getQueuedCommand(mIdentity);
			
			// As we now have the return value anyway let's check it.
			if(queriedFromDB != this) {
				throw new RuntimeException("getQueuedCommand() returned wrong result for " + this
					+ ": " + queriedFromDB);
			}
		}
	}

	@SuppressWarnings("serial")
	public static final class StartDownloadCommand extends DownloadSchedulerCommand {
		StartDownloadCommand(WebOfTrust wot, Identity identity) {
			super(wot, identity);
		}
	}

	@SuppressWarnings("serial")
	public static final class StopDownloadCommand extends DownloadSchedulerCommand {
		StopDownloadCommand(WebOfTrust wot, Identity identity) {
			super(wot, identity);
		}
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
	 * and safely start downloads as indicated by it.
	 * 
	 * FIXME: Currently, whenever the set of Identitys to download is changed (by the callbacks
	 * specified in {@link IdentityDownloader}) execution of this thread is scheduled.
	 * It does then look at all directly trusted Identitys to determine which to start/stop
	 * downloading - it is NOT told for which the "should download?" state has changed.
	 * Besides this being inefficient, it is buggy:
	 * As a result it is NOT able to comply with the request of re-downloading old editions of
	 * certain Identitys as it should do when {@link Identity#markForRefetch()} had been called for
	 * an Identity - also see the FIXME inside.
	 * To fix change the way the scheduler determines what to download by using a command-based
	 * system like the old IdentityFetcher does by using
	 * {@link IdentityFetcher.IdentityFetcherCommand}s.
	 * EDIT: To understand this FIXME, look at the code of this class as of the previous commit
	 * a8e8ff2e5a5e14eac66fcabcdc662d23e1a24e28. */
	private final class DownloadScheduler implements PrioRunnable {
		@Override public void run() {
			synchronized(mWoT) {
			synchronized(mLock) {
			synchronized(Persistent.transactionLock(mDB)) {
				try {
					// FIXME: Implement like the FIXME at the containing class demands.
					
					assert(testSelf());
				} catch(RuntimeException | Error e) {
					Persistent.checkedRollback(mDB, this, e);
					
					Logger.error(this, "Error in DownloadScheduler.run()! Retrying later...", e);
					mDownloadSchedulerThread.triggerExecution(MINUTES.toMillis(1));
				}
			}
			}
			}
		}
		
		private boolean testSelf() {
			// Determine all downloads which should be running
			IdentifierHashSet<Identity> allToDownload = new IdentifierHashSet<>();
			for(OwnIdentity i : mWoT.getAllOwnIdentities()) {
				for(Trust t : mWoT.getGivenTrusts(i)) {
					if(t.getValue() >= 0)
						allToDownload.add(t.getTrustee());
				}
			}

			// Check "mDownloads.keySet().equals(allToDownload.identifierSet()))", i.e. whether we
			// are downloading what we should be downloading, plus some more stuff.

			if(mDownloads.size() != allToDownload.size())
				return false;

			for(Identity i : allToDownload) {
				USKRetriever r = mDownloads.get(i.getID());

				if(r == null)
					return false;

				// The URI with the edition which was originally passed when starting the download,
				// not the latest found edition!
				FreenetURI uri = r.getOriginalUSK().getURI();

				if(!uri.equalsKeypair(i.getRequestURI()))
					return false;

				// Test whether Identity.markForRefetch() was handled.
				if(uri.getEdition() > i.getNextEditionToFetch())
					return false;
			}

			return true;
		}

		@Override public int getPriority() {
			return DOWNLOADER_THREAD_PRIORITY;
		}
	}

	/**
	 * Must not be called if a download is already running for the Identity.
	 * Must be called while synchronized on {@link #mWoT} and {@link #mLock}. */
	private void startDownload(Identity i) {
		if(mUSKManager == null) {
			Logger.warning(this, "mUSKManager == null, not downloading anything! Valid in tests.");
			return;
		}
		
		USK usk;
		try {
			usk = USK.create(i.getRequestURI().setSuggestedEdition(i.getNextEditionToFetch()));
		} catch (MalformedURLException e) {
			throw new RuntimeException(e); // Should not happen: getRequestURI() returns an USK.
		}

		FetchContext fetchContext = mHighLevelSimpleClient.getFetchContext();
		// Because archives can become huge and WoT does not use them, we should disallow them.
		fetchContext.maxArchiveLevels = 0;
		fetchContext.maxSplitfileBlockRetries = -1; // retry forever
		fetchContext.maxNonSplitfileRetries = -1; // retry forever
		fetchContext.maxOutputLength = XMLTransformer.MAX_IDENTITY_XML_BYTE_SIZE;
		assert(fetchContext.ignoreUSKDatehints == false); // FIXME: Remove if it doesn't fail.

		// FIXME: IdentityFetcher has always been setting this priority to the polling priority.
		// Toad likely instructed me to do so and/or saw it due to review of the code.
		// But now that I had a look at the JavaDoc of subscribeCotent() below it looks like it
		// could also be the PROGRESS priority, i.e. the priority for retrieving the data once
		// we're sure an edition exists. Which one is it?!
		// Bonus TODO: Why does subscribePriority() require specifying a priority, considering
		// that the USKRetrieverCallback callback interface implementation we're supposed to
		// pass to it has to implement getPollingPriorityNormal() and
		// getPollingPriorityProgress() which it could use to obtain the priority?
		short fetchPriority = DOWNLOAD_PRIORITY_POLLING;

		if(logMINOR)
			Logger.minor(this, "Downloading by USK subscription: " + usk);

		USKRetriever download = mUSKManager.subscribeContent(
			usk, this, true, fetchContext, fetchPriority, mRequestClient);

		USKRetriever existingDownload = mDownloads.put(i.getID(), download);
		assert(existingDownload == null);
	}

	@Override public short getPollingPriorityNormal() {
		return DOWNLOAD_PRIORITY_POLLING;
	}

	@Override public short getPollingPriorityProgress() {
		return DOWNLOAD_PRIORITY_PROGRESS;
	}

	private void stopDownload(String identityID) {
		// FIXME: Implement
	}

	@Override public void onFound(USK origUSK, long edition, FetchResult data) {
		// FIXME: Implement
	}

	@Override public boolean getShouldFetchState(Identity identity) {
		// FIXME
		return false;
	}

	@Override public void deleteAllCommands() {
		// FIXME
	}

	private DownloadSchedulerCommand getQueuedCommand(Identity identity) {
		Query q = mDB.query();
		q.constrain(DownloadSchedulerCommand.class);
		q.descend("mIdentity").constrain(identity)
			.identity(); // Not about class Identity, refers to wanting the same object!
		InitializingObjectSet<DownloadSchedulerCommand> result
			= new InitializingObjectSet<>(mWoT, q);
		
		switch(result.size()) {
			case 0:
				return null;
			case 1:
				DownloadSchedulerCommand c = result.next();
				assert(c.getIdentity() == identity);
				return c;
			default:
				throw new DuplicateObjectException(
					"Multiple DownloadSchedulerCommand objects stored for " + identity);
		}
	}

}
