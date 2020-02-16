/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.network.input;

import static java.lang.Thread.currentThread;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MINUTES;
import static plugins.WebOfTrust.XMLTransformer.MAX_IDENTITY_XML_TRUSTEE_AMOUNT;
import static plugins.WebOfTrust.util.AssertUtil.assertDidNotThrow;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicInteger;

import plugins.WebOfTrust.Identity;
import plugins.WebOfTrust.Identity.FetchState;
import plugins.WebOfTrust.Identity.IdentityID;
import plugins.WebOfTrust.IdentityFile;
import plugins.WebOfTrust.IdentityFileQueue;
import plugins.WebOfTrust.IdentityFileQueue.IdentityFileStream;
import plugins.WebOfTrust.OwnIdentity;
import plugins.WebOfTrust.Persistent;
import plugins.WebOfTrust.Persistent.InitializingObjectSet;
import plugins.WebOfTrust.Score;
import plugins.WebOfTrust.SubscriptionManager;
import plugins.WebOfTrust.Trust;
import plugins.WebOfTrust.WebOfTrust;
import plugins.WebOfTrust.XMLTransformer;
import plugins.WebOfTrust.exceptions.DuplicateObjectException;
import plugins.WebOfTrust.exceptions.NotTrustedException;
import plugins.WebOfTrust.exceptions.UnknownIdentityException;
import plugins.WebOfTrust.introduction.IntroductionPuzzle;
import plugins.WebOfTrust.util.Daemon;
import plugins.WebOfTrust.util.IdentifierHashSet;
import plugins.WebOfTrust.util.jobs.DelayedBackgroundJob;
import plugins.WebOfTrust.util.jobs.MockDelayedBackgroundJob;
import plugins.WebOfTrust.util.jobs.TickerDelayedBackgroundJob;

import com.db4o.ObjectSet;
import com.db4o.ext.Db4oException;
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
import freenet.support.PooledExecutor;
import freenet.support.PrioritizedTicker;
import freenet.support.Ticker;
import freenet.support.api.Bucket;
import freenet.support.io.Closer;
import freenet.support.io.NativeThread;

/**
 * Uses {@link USKManager} to subscribe to the USK of all "directly trusted" {@link Identity}s.
 * 
 * Directly trusted hereby means: At least one {@link OwnIdentity} has assigned a
 * {@link Trust#getValue()} of >= 0.
 * Further, for the purpose of {@link WebOfTrust#restoreOwnIdentity(freenet.keys.FreenetURI)},
 * all {@link OwnIdentity}s are also considered as directly trusted (as long as
 * {@link WebOfTrust#shouldFetchIdentity(Identity)} returns true for them, which it currently
 * always does but may not do for some if we implement code to detect when restoring is finished).
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
	 * Once we have stored a {@link DownloadSchedulerCommand}, the {@link DownloadScheduler} is
	 * scheduled to execute and start/stop downloading of the specified command's {@link Identity}
	 * after this delay.
	 * The delay is non-zero to ensure we don't have to do multiple database queries if multiple
	 * commands arrive in a short timespan - database queries are likely the most expensive
	 * operation.
	 * FIXME: Performance: Use a delay of 0 if no downloads are running currently, i.e. if
	 * mDownloads.size() == 0.
	 * TODO: Code quality: Make configurable. */
	public static final long QUEUE_BATCHING_DELAY_MS = MINUTES.toMillis(1);

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

	/**
	 * Uses as lock to guard concurrent read/write access to {@link #mDownloads} and write access to
	 * {@link #mDownloadSchedulerThread}.
	 * Also uses as lock for the database table of our download scheduling queue, i.e. objects of
	 * class {@link DownloadSchedulerCommand} and its child classes.
	 * 
	 * Is set to the value of {@link WebOfTrust#getIdentityDownloaderController()}.
	 * Thereby is equal to the lock which is specified in the {@link IdentityDownloader} interface
	 * as lock for its callbacks. This means we can assume this lock to be held in all
	 * IdentityDownloader callbacks which are implemented here, and thus there we can safely access
	 * our downloads, download scheduler command queue and download scheduler without further
	 * locking.
	 * 
	 * FIXME: Remove documentation about locking from the rest of the class similar to the one
	 * removed by the commit which added this FIXME and the above documentation.
	 * Consider a similar approach for IdentityDownloaderSlow as it has a similar JavaDoc at its
	 * mLock. */
	private final IdentityDownloaderController mLock;

	private final ExtObjectContainer mDB;

	/**
	 * When we download an {@link Identity} the resulting {@link IdentityFile} is stored for
	 * processing at this {@link IdentityFileQueue}. */
	private final IdentityFileQueue mOutputQueue;

	/** Starts/stops downloads according to {@link DownloadSchedulerCommand}s.
	 *  @see DownloadScheduler */
	private final DownloadScheduler mDownloadScheduler = new DownloadScheduler();

	/**
	 * The thread which runs the {@link DownloadScheduler} {@link #mDownloadScheduler}.
	 * The execution typically is scheduled after a delay of {@link #QUEUE_BATCHING_DELAY_MS}.
	 * 
	 * The value distinguishes the run state of this IdentityDownloader as follows:
	 * - Until {@link #start()} was called, defaults to {@link MockDelayedBackgroundJob#DEFAULT}
	 *   with {@link DelayedBackgroundJob#isTerminated()} == true.
	 * - Once {@link #start()} has been called, becomes a
	 *   {@link TickerDelayedBackgroundJob} with {@link DelayedBackgroundJob#isTerminated()}
	 *   == false.
	 * - Once {@link #stop()} has been called, stays a {@link TickerDelayedBackgroundJob} but has
	 *   {@link DelayedBackgroundJob#isTerminated()} == true for ever.
	 * 
	 * There can be exactly one start() - stop() lifecycle, an IdentityDownloaderFast cannot be
	 * recycled.
	 * 
	 * Concurrent write access to this variable by start() is guarded by {@link #mLock}. 
	 * Volatile since stop() needs to read it without synchronization.
	 * 
	 * {@link IdentityDownloaderSlow#mDownloadSchedulerThread} and {@link SubscriptionManager#mJob}
	 * are related to this, please apply changes there as well. */
	private volatile DelayedBackgroundJob mDownloadSchedulerThread
		= MockDelayedBackgroundJob.DEFAULT;

	/**
	 * Key = {@link Identity#getID()}.
	 * 
	 * Concurrent read/write access to the HashMap is guarded by {@link #mLock}. */
	private final HashMap<String, USKRetriever> mDownloads = new HashMap<>();

	/**
	 * See {@link IdentityDownloaderFastStatistics#mDownloadedEditions}.
	 * 
	 * Is not guarded by {@link #mLock} but an {@link AtomicInteger} instead to allow
	 * {@link #onFound(USK, long, FetchResult)} to not have to wait for mLock. */
	private final AtomicInteger mDownloadedEditions = new AtomicInteger(0);

	/**
	 * See {@link IdentityDownloaderFastStatistics#mDownloadProcessingFailures}.
	 * 
	 * Is not guarded by {@link #mLock} but an {@link AtomicInteger} instead to allow
	 * {@link #onFound(USK, long, FetchResult)} to not have to wait for mLock. */
	private final AtomicInteger mDownloadProcessingFailures = new AtomicInteger(0);

	private static transient volatile boolean logDEBUG = false;

	private static transient volatile boolean logMINOR = false;

	static {
		Logger.registerClass(IdentityDownloaderFast.class);
	}


	public IdentityDownloaderFast(IdentityDownloaderController controller) {
		assert(controller != null);
		
		mWoT = controller.getWebOfTrust();
		
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
		mLock = controller;
		mDB = mWoT.getDatabase();
		mOutputQueue = mWoT.getIdentityFileQueue();
	}

	/**
	 * Initializes and enables the {@link DownloadScheduler} and hence enables automated starting
	 * and stopping of downloads based on the received callbacks of interface
	 * {@link IdentityDownloader}.
	 * 
	 * Any callbacks received before this function was called are ignored and unnecessary, the state
	 * of which Identitys to download is fully initialized from the {@link WebOfTrust} database.
	 * 
	 * ATTENTION: Does NOT enable {@link DownloadSchedulerCommand} processing in unit tests if
	 * {@link WebOfTrust#getPluginRespirator()} is null, i.e. if the plugin wasn't loaded into a
	 * full node. If you want processing to happen then you must manually trigger it by calling
	 * {@link DownloadScheduler#run()} on {@link #mDownloadScheduler}.
	 * 
	 * Related to {@link SubscriptionManager#start()} and {@link IdentityDownloaderSlow#start()}
	 * - please apply changes there as well.
	 * FIXME: There have been improvements to this here, diff against the above versions and
	 * backport the changes. */
	@Override public void start() {
		Logger.normal(this, "start()...");
		
		synchronized(mWoT) {
		synchronized(mLock) {
		synchronized(Persistent.transactionLock(mDB)) {
			try {
				// This is thread-safe guard against concurrent multiple calls to start() / stop()
				// since stop() does not modify the job and start() is synchronized(mLock). 
				if(mDownloadSchedulerThread != MockDelayedBackgroundJob.DEFAULT)
					throw new IllegalStateException("start() was already called!");
				
				// Delete all DownloadSchedulerCommands of the previous WoT execution as they've
				// become invalid by restarting Freenet:
				// We don't do persistent downloads, so all downloads are lost upon restart and we
				// must recreate them. This means that commands of the previous session are invalid
				// as well because they were relative to the set of already running downloads.
				//
				// This must be called while synchronized on mLock, and the lock must be
				// held until mDownloadSchedulerThread is set:
				// Holding the lock prevents DownloadSchedulerCommands from being created before
				// the mDownloadSchedulerThread is enabled later on. 
				// It is critically necessary for the thread to be initialized before
				// any commands can be created: Commands will only be processed if the thread's
				// triggerExecution() is enabled at the moment a command is created.
				deleteAllCommandsWithoutCommit();
				
				assert(mDownloads.size() == 0);
				
				// Now we'll store a StartDownloadCommand for all Identitys which are eligible for
				// download. Not starting the downloads here but scheduling them to be started by
				// the mDownloadSchedulerThread speeds up startup.
				// We'll create a set of the IDs of all Identitys for which we already stored a
				// StartDownloadCommand to avoid issuing multiple commands for the same Identity.
				// If this some day grows too large to fit into memory you can instead query the
				// database for whether a command already exists. See what e.g.
				// storeStartFetchCommandWithoutCommit_Checked() does.
				// Since WoT currently is intended to be used by a single user this will typically
				// at most contain the number of trustees an OwnIdentity has on average so for now
				// this will fit into memory just fine and we can avoid the database queries to
				// speed up startup.
				// TODO: Code quality: Replace initial size with AVERAGE_IDENTITY_TRUSTEE_AMOUNT * 2
				// or MAX_OWNIDENTITY_TRUSTEE_AMOUNT * 2 once such a constant exists.
				HashSet<String> alreadyStarted = new HashSet<>(MAX_IDENTITY_XML_TRUSTEE_AMOUNT * 2);
				Logger.normal(this, "start(): Scheduling downloads of rank=1 Identitys...");
				for(OwnIdentity truster : mWoT.getAllOwnIdentities()) {
					if(!alreadyStarted.contains(truster.getID())
							&& mWoT.shouldFetchIdentity(truster)) {
						
						// For the purpose of WebOfTrust.restoreOwnIdentity()
						new StartDownloadCommand(mWoT, truster).storeWithoutCommit();
						alreadyStarted.add(truster.getID());
					}
					
					for(Trust trust : mWoT.getGivenTrusts(truster, 1)) {
						assert(trust.getValue() >= 0);
						
						Identity toDownload = trust.getTrustee();
						if(!alreadyStarted.contains(toDownload.getID())) {
							if(logMINOR)
								Logger.minor(this, "start(): Scheduling download of " + toDownload);
							
							new StartDownloadCommand(mWoT, toDownload).storeWithoutCommit();
							alreadyStarted.add(toDownload.getID());
						} else {
							if(logMINOR) {
								Logger.minor(this, "start(): Already scheduled download, ignoring: "
									+ toDownload);
							}
						}
					}
				}
				Logger.normal(this, "start(): Finished scheduling downloads.");
				
				PluginRespirator respirator = mWoT.getPluginRespirator();
				Ticker ticker;
				Runnable jobRunnable;
				
				if(respirator != null) { // We are connected to a real Freenet node
					ticker = respirator.getNode().getTicker();
					jobRunnable = mDownloadScheduler;
				} else { // We are inside of a unit test
					Logger.warning(this, "No PluginRespirator available, will never run job. "
						+ "This should only happen in unit tests!");
					
					// Generate our own Ticker so we can set mDownloadSchedulerThread to be a real
					// TickerDelayedBackgroundJob.
					// This is better than leaving it be a MockDelayedBackgroundJob because it
					// allows us to clearly distinguish the run state (start() not called, start()
					// called, stop() called) by checking whether mDownloadSchedulerThread is at the
					// default value or not, and if not checking the run state of it.
					ticker = new PrioritizedTicker(new PooledExecutor(), 0);
					jobRunnable = new Runnable() { @Override public void run() {
						// Do nothing because:
						// - Unit tests execute instantly after loading the WoT plugin, so delayed
						//   jobs should not happen since their timing cannot be guaranteed to match
						//   the unit tests execution state.
						// - We shouldn't do work on custom executors, we should only ever use the
						//   main one of the node.
						}
					};
				}
				
				// Set the volatile mDownloadSchedulerThread after everything which stop() must
				// cleanup is initialized to ensure that stop() can use the variable (without
				// synchronization) to check whether cleanup will cover everything.
				mDownloadSchedulerThread = new TickerDelayedBackgroundJob(
					jobRunnable, "WoT IdentityDownloaderFast", QUEUE_BATCHING_DELAY_MS, ticker);
				
				// Use 0 as delay instead of the QUEUE_BATCHING_DELAY_MS which is > 0 as the UI
				// isn't active yet and thus the user cannot assign any further trusts which would
				// make sense to batch-process along with the existing ones.
				// (Doing triggerExecution() after the above greenlight for stop() isn't a
				// problem because stop() will use mDownloadSchedulerThread.waitForTermination()
				// to take account for triggerExecution() maybe being called concurrently.)
				mDownloadSchedulerThread.triggerExecution(0);
				
				Persistent.checkedCommit(mDB, this);
			} catch(RuntimeException e) {
				Persistent.checkedRollbackAndThrow(mDB, this, e);
			} finally {
				Logger.normal(this, "start() finished.");
			}
		}}}
	}

	/**
	 * Is merely a wrapper around stop() in preparation of the TODO at {@link Daemon#terminate()}
	 * which requests renaming it to stop(). */
	@Override public void terminate() {
		stop();
	}

	/**
	 * Related to {@link SubscriptionManager#stop()} and {@link IdentityDownloaderSlow#stop()},
	 * please apply changes there as well.
	 * FIXME: There have been improvements to this here, diff against the above versions and
	 * backport the changes. */
	private void stop() {
		Logger.normal(this, "stop()...");
		
		// The following code intentionally does NOT write to the mDownloadSchedulerThread variable
		// so it does not have to use synchronized(mLock). We do not want to synchronize because:
		// 1) DownloadScheduler.run() is synchronized(mLock), so we would not get the lock until
		//    run() is finished. But we want to call mDownloadSchedulerThread.terminate()
		//    immediately while run() is still executing to make it call Thread.interrupt() upon
		//    run() to speed up its termination. So we shouldn't require acquisition of the lock
		//    before mDownloadSchedulerThread.terminate().
		// 2) Keeping mDownloadSchedulerThread as is makes sure that start() is not possible anymore
		//    so this object can only have a single lifecycle. Recycling needs to be impossible:
		//    If we allowed restarting, the cleanup of the USKRetrievers at the end of this function
		//    could damage the new lifecycle because its synchronization block does not include
		//    mDownloadSchedulerThread.terminate() and thus it would not be possible to guarantee
		//    that we kill the USKRetrievers of the same cycle.
		
		// Since mDownloadSchedulerThread can only transition from not "not started yet", as implied
		// by the "==" here, to "started" as implied by "!=", but never backwards, is volatile, and
		// is set by start() *after* everything is initialized, this makes this function safe
		// against concurrent calls to start().
		if(mDownloadSchedulerThread == MockDelayedBackgroundJob.DEFAULT)
			throw new IllegalStateException("start() not called/finished yet!");
		
		// We cannot guard against concurrent stop() here since we don't synchronize, we can only
		// probabilistically detect it by assert(). Concurrent stop() is not a problem though since
		// restarting jobs is not possible: We cannot run into a situation where we accidentally
		// stop the wrong lifecycle. It can only happen that we do cleanup the cleanup which a
		// different thread would have done, but they won't care since all actions below will
		// succeed silently if done multiple times.
		assert !mDownloadSchedulerThread.isTerminated() : "stop() called already";
		
		mDownloadSchedulerThread.terminate();
		try {
			// We must wait without timeout since we need to cancel our requests at the core of
			// Freenet (see below synchronized(mLock)) and the job thread might create requests
			// until it is terminated.
			mDownloadSchedulerThread.waitForTermination(Long.MAX_VALUE);
		} catch (InterruptedException e) {
			// We are a shutdown function, there is no sense in sending a shutdown signal to us.
			Logger.error(this, "stop() should not be interrupt()ed.", e);
		}
		
		// We are safe now to terminate all existing Freenet requests since no new ones can be
		// created anymore:
		// DownloadScheduler.run() can only be executed by mDownloadSchedulerThread, and that will
		// not do so after waitForTermination().
		// It can also not be re-enabled since start() does not allow restarting.
		// 
		// Nevertheless we must keep guarding access to mDownloads by synchronized(mLock) as usual
		// because WebOfTrust.terminate() is fully parallelized and thus all our callbacks may be
		// called concurrently - and some of them will access mDownloads to check whether a download
		// for a certain Identity is running (but not start a download if not, they merely queue
		// DownloadSchedulerCommands, which doesn't matter since the DownloadScheduler is off).
		synchronized(mLock) {
			if(logMINOR)
				Logger.minor(this, "stop(): Stopping running USK subscriptions...");
			
			// TODO: Code quality: IdentityFetcher.stop(), which this code is based on, did copy the
			// map values() like we do here, instead of iterating over them directly. Why did it do
			// that? Perhaps this merely is cargo cult programming: SSK-based downloaders such as
			// IdentityDownloaderSlow have to copy their request map before canceling requests
			// because with SSK requests there is an onFailure() callback which gets called when
			// canceling a request. Their implementation of onFailure() will typically remove the
			// request from the map as well so they need to copy it first before canceling the
			// requests to avoid concurrent modification of the map. USK subscriptions do not have
			// such a callback IIRC so it likely is not necessary to copy the map.
			USKRetriever[] retrievers
				= mDownloads.values().toArray(new USKRetriever[mDownloads.size()]);
			int counter = 0;
			for(USKRetriever r : retrievers) {
				if(r == null) {
					// fetch(USK) returns null in tests.
					// FIXME: The above was the case for IdentityFetcher which inspired this
					// function, IIRC for unit tests. It's not implemented for
					// IdentityDownloaderFast yet I think. Determine whether it is needed for it.
					// Remove the assert(false) if yes.
					assert(false);
					continue;
				}
				r.cancel(mClientContext);
				mUSKManager.unsubscribeContent(r.getOriginalUSK(), r, true);
				++counter;
			}
			mDownloads.clear();
			
			if(logMINOR)
				Logger.minor(this, "stop(): Stopped " + counter + " USK subscriptions.");
		}
		
		Logger.normal(this, "stop() finished.");
	}

	/** Must be called while synchronized on {@link #mWoT}. */
	private boolean shouldDownload(Identity identity) {
		for(Score s : mWoT.getScores(identity)) {
			if(shouldDownload(s))
				return true;
		}
		
		return false;
	}

	/**
	 * True if the Identity {@link Score#getTrustee()} of the given Score should be downloaded.
	 * 
	 * Must be called while synchronized on {@link #mWoT}. */
	private boolean shouldDownload(Score s) {
			// Rank 1:
			//   The Identity is directly trusted by an OwnIdentity and thereby from our primary
			//   target group of identities which we should download - as long as the Score
			//   considers it as trustworthy, which shouldMaybeFetchIdentity() checks.
			// Rank 0:
			//   The Identity is an OwnIdentity. We download it as well for the purpose of
			//   WebOfTrust.restoreOwnIdentity() as demanded by IdentityDownloaderFast's JavaDoc.
			//   We do also check shouldMaybeFetchIdentity() to allow disabling of USK subscriptions
			//   by that function once restoring is finished (not implemented yet).
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
		
		return false;
	}

	/**
	 * NOTICE: If an Identity was already eligible for download in general previously but not by
	 * this class then this won't be called again by {@link WebOfTrust} if it becomes of interest to
	 * this class when an OwnIdentity starts trusting it.
	 * See {@link #storeTrustChangedCommandWithoutCommit(Trust, Trust)} which handles that case. */
	@Override public void storeStartFetchCommandWithoutCommit(Identity identity) {
		// While a call to this function as by the interface specification means that an OwnIdentity
		// wants the Identity to be downloaded indeed we do *not* know whether that desire is due
		// to a direct Trust value from an OwnIdentity (which is this class' sole target set of
		// Identitys):
		// The interface only demands that WoT should want to download the Identity in general, it
		// may be due to a non-own Identity's Trust. Thus we need to check with shouldDownload().
		if(shouldDownload(identity)) {
			DownloadSchedulerCommand c = getQueuedCommand(identity);
			
			if(c != null) {
				if(c instanceof StartDownloadCommand) {
					// Two or more calls to this function in a row are valid for the purpose of
					// Identity.markForRefetch()
					// - so we can already have a StartDownloadCommand command stored when we're
					// called again.
					// Hence this code is commented out.
					/*
					Logger.warning(this, "storeStartFetchCommandWithoutCommit(): Called more than "
						+ "once for: " + identity,
						new RuntimeException("Exception not thrown, for logging trace only!"));
					*/
					
					return;
				} else {
					assert(c instanceof StopDownloadCommand);
					
					c.deleteWithoutCommit();
					// At first glance we'd put a "return;" here since the StopDownloadCommand
					// wasn't processed yet and thus there'd be no need to store a
					// StartDownloadCommand to start the download as it is still running.
					// But a second StartDownloadCommand is also used for the purpose of handling
					// Identity.markForRefetch() when a download is already running so we do need
					// to store one and cannot return.
					// Further commands are only deleted after processing if nothing threw during
					// processing - so it is possible we did stop the download already even if the
					// command wasn't deleted.
				}
			}
			
			c = new StartDownloadCommand(mWoT, identity);
			c.storeWithoutCommit();
			
			mDownloadSchedulerThread.triggerExecution();
		}
	}

	/**
	 * Same specification and requirements as
	 * {@link #storeStartFetchCommandWithoutCommit(Identity)} except for these differences:
	 * - "Checked" means that we for sure know that this class wants to download the Identity and
	 *   that this function is guaranteed to be called when an Identity becomes of interest for this
	 *   class to download, not just when it becomes of interest for the whole of WoT to download.
	 *   However callers still don't need to ensure that no download for it is running yet / no
	 *   command is already queued to start one.
	 * - This function is not to be used for handling {@link Identity#markForRefetch()}.
	 * - This is a private function, not a public callback. Intended to be used by
	 *   {@link #storeTrustChangedCommandWithoutCommit(Trust, Trust)} and
	 *   {@link #storeDeleteOwnIdentityCommandWithoutCommit(OwnIdentity, Identity)}. */
	private void storeStartFetchCommandWithoutCommit_Checked(Identity identity) {
		DownloadSchedulerCommand c = getQueuedCommand(identity);
		
		if(c != null) {
			if(c instanceof StartDownloadCommand) {
				// From the perspective of our caller storeTrustChangedCommandWithoutCommit() it is
				// not an error to already have a command stored:
				// We don't know for sure whether we didn't want to download the Identity before
				// the Trust changed: It may have been eligible for download due to a different
				// Trust.
				return;
			} else {
				assert(c instanceof StopDownloadCommand);
				
				c.deleteWithoutCommit();
				// At first glance we could always return here because if a StopDownloadCommand
				// exists that means the download is still running - but the loop which processes
				// the StopDownloadCommands only deletes them if none of the code for aborting a
				// download at fred did throw. So if the command wasn't deleted yet that doesn't
				// mean that fred didn't abort the download yet. Thus we better program defensively
				// and check whether the download is *really* still running - especially considering
				// that the case of switching between an Identity being eligible for download, then
				// not being eligible, and then being eligible again, all in a sufficiently short
				// timespan for commands to not have been processed yet, should be rare and thus the
				// extra check doesn't impair performance a lot.
				// (mDownloads is valid to use from a concurrency perspective, is guarded by mLock
				// which callers are required to hold.)
				if(mDownloads.containsKey(identity.getID()))
					return;
				else {
					assert(false)
						: "StopDownloadCommand queued but no download found for " + identity;
				}
			}
		}
		
		// Again: This function can be called if the download is already running (for purposes of
		// storeTrustChangedCommandWithoutCommit()) so we must return if it is (- not only for
		// performance reasons but also to not wrongly signal Identity.markForRefetch() if the
		// download is already running, not returning and thus storing a secondary
		// StartDownloadCommand would do that!)
		// In other words: The pre-existing StartDownloadCommand we checked for above may have
		// already been processed and caused a download to be in mDownloads.
		// (mDownloads is valid to use from a concurrency perspective, is guarded by mLock which
		// callers are required to hold.)
		if(mDownloads.containsKey(identity.getID()))
			return;
		
		c = new StartDownloadCommand(mWoT, identity);
		c.storeWithoutCommit();
		
		mDownloadSchedulerThread.triggerExecution();
	}

	/**
	 * NOTICE: This is NOT called if the sole direct Trust from an OwnIdenity to the given Identity
	 * is deleted but the Identity is still trusted due to indirect trusts - but in that case this
	 * downloader is not responsible for fetching this Identity anymore!
	 * {@link #storeTrustChangedCommandWithoutCommit(Trust, Trust)} handles that case. */
	@Override public void storeAbortFetchCommandWithoutCommit(Identity identity) {
		DownloadSchedulerCommand c = getQueuedCommand(identity);
		
		if(c != null) {
			if(c instanceof StopDownloadCommand) {
				// Score computation should only call this when the "should fetch?" state changes
				// from true to false, but it can only do so once, not twice in a row, so it
				// shouldn't call us twice and we shouldn't observe a StopDownloadCommand already
				// existing here.
				// I'm however not sure whether this is an error - I think it may be possible that
				// the current implementation of Score computation is written in a way which can
				// cause this to happen for valid reasons.
				// Thus not doing an assert(false) here, only logging a warning.
				// TODO: Investigate whether that is the case, and if not change to an assert().
				Logger.warning(this, "storeAbortFetchCommandWithoutCommit(): Called more than "
					+ "once for: " + identity,
					new RuntimeException("Exception not thrown, for logging trace only!"));
				return;
			} else {
				assert(c instanceof StartDownloadCommand);
				
				c.deleteWithoutCommit();
				// At first glance we'd put a "return;" here since the StartDownloadCommand
				// wasn't processed yet and thus there seems to be no need to store a
				// StopDownloadCommand to stop the download as it not seems to be running yet.
				// But a second StartDownloadCommand is also used for the purpose of handling
				// Identity.markForRefetch() when a download is already running so one could be
				// running already indeed and thus we do need to store a StopDownloadCommand and
				// cannot return.
				// Further commands are only deleted after processing if nothing threw during
				// processing - so it is possible we did start a download already even if the
				// command wasn't deleted.
			}
		}
		
		// While a call to this function means that no OwnIdentity wants it to be downloaded
		// we do *not* know whether the previous desire to download it was due to a direct trust
		// value from any OwnIdentity, i.e. we don't know whether we were actually responsible
		// for downloading it and thus whether there even could be a download to cancel.
		// Also as we did not return if a pre-existing StartDownloadCommand wasn't processed yet
		// a download may not have been started yet.
		// Thus we should check with mDownloads before uselessly invoking the DownloadScheduler
		// for Identitys which weren't interesting to us anyway.
		// (mDownloads is valid to use from a concurrency perspective, is guarded by mLock which
		// callers are required to hold.)
		if(mDownloads.containsKey(identity.getID())) {
			// We cannot just cancel the running download here:
			// This function is being called as part of an unfinished transaction which may still be
			// rolled back after we return, and that would mean that the download needs to continue.
			// Thus we need to cancel the download in a separate transaction, which is what the
			// DownloadScheduler does.
			
			new StopDownloadCommand(mWoT, identity.getID())
				.storeWithoutCommit();
			
			mDownloadSchedulerThread.triggerExecution();
		}
	}

	/**
	 * Same specification and requirements as
	 * {@link #storeAbortFetchCommandWithoutCommit(Identity)} except for these differences:
	 * - "Checked" means that we for sure know that this class wanted to download the Identity and
	 *   that this function is guaranteed to be called when an Identity stops being of interest for
	 *   this class to download, not just when the whole of WoT wants to stop downloading it.
	 *   However callers still don't need to ensure that the download wasn't already aborted yet /
	 *   no command is already queued to abort it.
	 * - This is a private function, not a public callback. Intended to be used by
	 *   {@link #storeTrustChangedCommandWithoutCommit(Trust, Trust)} and
	 *   {@link #storeDeleteOwnIdentityCommandWithoutCommit(OwnIdentity, Identity)}. */
	private void storeAbortFetchCommandWithoutCommit_Checked(Identity identity) {
		DownloadSchedulerCommand c = getQueuedCommand(identity);
		
		if(c != null) {
			if(c instanceof StopDownloadCommand) {
				// This function should only be called when the "should fetch?" state changes from
				// true to false, but it can only do so once, not twice in a row, so we shouldn't
				// be called twice and we shouldn't observe a StopDownloadCommand already existing
				// here.
				// However we're called by storeTrustChangedCommandWithoutCommit(), which is called
				// *after* Score computation is finished, so we may already have received a
				// StopDownloadCommand by storeAbortFetchCommandWithoutCommit() which was called
				// by Score computation before storeTrustChangedCommandWithoutCommit().
				// Thus this is not an error.
				return;
			} else {
				assert(c instanceof StartDownloadCommand);
				
				c.deleteWithoutCommit();
				// At first glance we'd put a "return;" here since the StartDownloadCommand
				// wasn't processed yet and thus there seems to be no need to store a
				// StopDownloadCommand to stop the download as it not seems to be running yet.
				// But a second StartDownloadCommand is also used for the purpose of handling
				// Identity.markForRefetch() when a download is already running so one could be
				// running already indeed and thus we do need to store a StopDownloadCommand and
				// cannot return.
				// Further commands are only deleted after processing if nothing threw during
				// processing - so it is possible we did start a download already even if the
				// command wasn't deleted.
			}
		}
		
		// As we did not return if a pre-existing StartDownloadCommand wasn't processed yet a
		// download may not have been started yet.
		// Thus we should check with mDownloads before uselessly invoking the DownloadScheduler
		// for Identitys which weren't interesting to us anyway.
		// (mDownloads is valid to use from a concurrency perspective, is guarded by mLock which
		// callers are required to hold.)
		if(mDownloads.containsKey(identity.getID())) {
			// We cannot just cancel the running download here:
			// This function is being called as part of an unfinished transaction which may still be
			// rolled back after we return, and that would mean that the download needs to continue.
			// Thus we need to cancel the download in a separate transaction, which is what the
			// DownloadScheduler does.
			
			new StopDownloadCommand(mWoT, identity.getID())
				.storeWithoutCommit();
			
			mDownloadSchedulerThread.triggerExecution();
		}
	}

	@Override public void storePreDeleteOwnIdentityCommand(OwnIdentity oldIdentity) {
		for(Trust t : mWoT.getGivenTrusts(oldIdentity)) {
			if(t.getValue() >= 0) {
				// The trustee could possibly have been eligible for download solely due to having
				// received this positive Trust from the Identity because it was an OwnIdentity.
				// As it isn't an OwnIdentity anymore the Trust isn't a justification for
				// downloading it anymore. So we need to check whether another Trust of a different
				// OwnIdentity justifies to keep downloading it, and if not abort the download.
				
				Identity trustee = t.getTrustee();
				boolean keepDownloadingTrustee = false;
				
				// Don't iterate over all OwnIdentitys to check whether the trustee had received a
				// positive Trust but instead iterate over all Scores:
				// That's faster because a Score's rank tells if the trustee has received a direct
				// trust, so we only need one database query for the for() loop, not secondary ones
				// for each Trust like we would need with a for() on the OwnIdentitys.
				// Notice: This also covers the case of the trustee being an OwnIdentity, they
				// assign a Score to themselves, see WebOfTrust.initTrustTreeWithoutCommit().
				for(Score s : mWoT.getScores(trustee)) {
					if(s.getTruster() == oldIdentity)
						continue;
					
					if(shouldDownload(s)) {
						keepDownloadingTrustee = true;
						break;
					}
				}
				
				if(!keepDownloadingTrustee)
					storeAbortFetchCommandWithoutCommit_Checked(trustee);
			}
		}
		
		// Given that the oldIdentity was an OwnIdentity it was probably eligible for download
		// for the purpose of WebOfTrust.restoreOwnIdentity(). Thus we new to abort a potentially
		// pre-existing download.
		storeAbortFetchCommandWithoutCommit_Checked(oldIdentity);
	}

	@Override public void storePostDeleteOwnIdentityCommand(Identity newIdentity) {
		// The replacement Identity may still be eligible for download if another OwnIdentity trusts
		// it.
		if(shouldDownload(newIdentity))
			storeStartFetchCommandWithoutCommit_Checked(newIdentity);
	}

	@Override public void storePreDeleteIdentityCommand(Identity oldIdentity) {
		if(oldIdentity instanceof OwnIdentity)
			storePreDeleteOwnIdentityCommand((OwnIdentity)oldIdentity);
		else {
			if(shouldDownload(oldIdentity))
				storeAbortFetchCommandWithoutCommit_Checked(oldIdentity);
		}
	}

	@Override public void storePreRestoreOwnIdentityCommand(Identity oldIdentity) {
		// The fact that the ID is equal for both the oldIdentity and the upcoming replacement
		// OwnIdentity means pre-existing DownloadSchedulerCommands will continue to affect the
		// replacement.
		// Thus we must ensure the following call to storePostRestoreOwnIdentityCommand() is able
		// to start the download by deleting pre-existing commands for the oldIdentity.
		String id = oldIdentity.getID();
		DownloadSchedulerCommand pendingCommand = getQueuedCommand(id);
		if(pendingCommand != null) {
			if(pendingCommand instanceof StartDownloadCommand) {
				// StartDownloadCommands store a pointer to the Identity object in the database
				// which would become null by the upcoming deletion of the Identity.
				// Thus we must delete the stale command to create a fresh one later on.
				pendingCommand.deleteWithoutCommit();
				
				// Not valid here: StartDownloadCommands may be stored when there already is a
				// download - for handling of Identity.markForRefetch().
				/* assert(!mDownloads.containsKey(id)); */
			} else {
				assert(pendingCommand instanceof StopDownloadCommand);
				
				pendingCommand.deleteWithoutCommit();
				
				assert(mDownloads.containsKey(id))
					: "StopDownloadCommand queued but no download running for " + id;
			}
		}
	}

	@Override public void storePostRestoreOwnIdentityCommand(OwnIdentity newIdentity) {
		// Keep a potentially running download as is so we don't lose fred's progress on polling the
		// USK.
		// Notice: When entering the USK for restoring the user may have supplied a higher edition
		// than what we had passed to the running USK subscription so it is necessary to somehow
		// make the USK code aware of that. There is no need to do so in this class:
		// The IdentityDownloaderSlow by contract of the interface will have to store an EditionHint
		// for the edition of the newIdentity in its implementation of this callback.
		// FIXME: Ensure it really does that.
		if(!mDownloads.containsKey(newIdentity.getID())) {
			new StartDownloadCommand(mWoT, newIdentity).storeWithoutCommit();
			mDownloadSchedulerThread.triggerExecution();
		}
	}

	/**
	 * By implementing this callback we're processing the following events to adjust our decision
	 * of whether we download the {@link Trust#getTrustee()} of the changed {@link Trust}:
	 * - {@link #storeStartFetchCommandWithoutCommit()} was already called for the {@link Identity}
	 *   because a non-own Identity's {@link Trust} caused it to be trusted enough for being
	 *   downloaded by WoT in general BUT as it was a non-own Trust this class wasn't interested in
	 *   downloading it and thus its storeStartFetchCommandWithoutCommit() didn't start the
	 *   download.
	 *   If an {@link OwnIdentity} starts trusting the Identity now and it thus becomes eligible
	 *   for download by this class then storeStartFetchCommandWithoutCommit() won't be called again
	 *   by the WebOfTrust as the Identity was already eligible for download in general - just not
	 *   by this class, which the WebOfTrust doesn't care about when deciding whether to deploy
	 *   the callback.
	 *   So to be able to notice such changes we must process Trust changes as well which is
	 *   what this callback deals with.
	 * - An Identity was eligible for download by this class due to being directly trusted by an
	 *   OwnIdentity. Now the Trust of the OwnIdentity is removed and it thus isn't eligible anymore
	 *   for download by this class but {@link #storeAbortFetchCommandWithoutCommit(Identity)} is
	 *   not called by the WebOfTrust because the Identity is still eligible to be downloaded in
	 *   general due to indirect Trusts, i.e. Trusts of non-own Identitys.
	 *   This callback must hence stop the download by observing Trust removal of OwnIdentitys.
	 *   (Notice that this is sort of the inverse of the above case.) */
	@Override public void storeTrustChangedCommandWithoutCommit(Trust oldTrust, Trust newTrust) {
		// Check whether the Trust change could cause our "download the trustee?" decision to
		// change, if not return. This is only "maybe" because there may be other Trusts to the
		// trustee.
		
		boolean maybeWouldDownloadBefore =
		    (oldTrust != null && oldTrust.getTruster() instanceof OwnIdentity
		  && oldTrust.getValue() >= 0);
		
		boolean maybeWouldDownloadNow =
		    (newTrust != null && newTrust.getTruster() instanceof OwnIdentity
		  && newTrust.getValue() >= 0);
		
		if(maybeWouldDownloadNow == maybeWouldDownloadBefore)
			return;
		
		Identity identity = newTrust != null ? newTrust.getTrustee() : oldTrust.getTrustee();

		// Starting and stopping the download of OwnIdentitys is not decided by Trust values
		// as they're eligible for download merely if the user wants to restore them from the
		// network, even if nobody trusts them, so we need not to do anything and let
		// storePostRestoreOwnIdentityCommandWithoutCommit() handle it instead.
		// We also *must* not do anything because in the future it shall be possible to disable
		// the download of OwnIdentitys once restoring is finished, see
		// https://bugs.freenetproject.org/view.php?id=7129, so if we did not return here then
		// this function might wrongly start the download if they've received a Trust from another
		// OwnIdentity.
		if(identity instanceof OwnIdentity)
			return;
		
		if(newTrust == null) {
			// The identity was taken from oldTrust which is a clone() - so the identity also is
			// a clone().
			// Our callees will store a reference to the Identity object in the database so to avoid
			// its duplication we must re-query the original object from the database by ID.
			try {
				identity = mWoT.getIdentityByID(identity.getID());
			} catch(UnknownIdentityException e) {
				throw new RuntimeException("Called with a Trust to an inexistent identity!", e);
			}
		}
		
		// As an optimization he value of maybeWouldDownloadNow was for now computed only
		// considering the single Trust we are looking at here. So even if doesn't justify
		// downloading the Identity a Trust of another OwnIdentity may justify downloading it, so to
		// validate the imprecise preliminary value we must now using the more expensive
		// shouldDownload() to check all Trusts.
		// ATTENTION: For performance reasons shouldDownload() uses not only the Trust but also the
		// Score database, so this function must only be called when the Score database has already
		// been updated to reflect the changes due to the changed Trust.
		//
		// TODO: It may be possible that the Score database is actually irrelevant:
		// shouldDownload() is only used if the "download it?" decision changed from true to false
		// (due to opportunistic one-sided evaluation of "||") - which can only be due to a Trust
		// removal or distrust. But one OwnIdentity removing a trust or distrusting an Identity
		// cannot cause another OwnIdentity's direct trusts to change.
		//   EDIT: The above isn't true for our below call to storeStartFetchCommandWithoutCommit(),
		//   it does always call shouldDownload() - but that's redundant as we already checked
		//   this here, so we could remove the shouldDownload() call by duplicating the function.
		// So the idea to require the Score database to be up-to-date is merely from a theoretical
		// point of view of code-cleanness: Treating shouldDownload() as a black box only determines
		// it uses the Score database, not how.
		// Making this function not require the Score database to be fully correct could be of great
		// benefit when we split the trust list import transaction from Score processing as
		// will likely be a result of implementing https://bugs.freenetproject.org/view.php?id=6848
		// When you change the above comment to not require the Score database to be up to date
		// ensure to also update the JavaDoc of this function which is the main point where it is
		// demanded. Further amend the JavaDoc to state that this callback is suitable to have
		// its calling mechanism changed to be empowered by SubscriptionManager, as that has also
		// a "Trust has changed!" callback which is called when the Scores are not yet updated.
		boolean reallyWouldDownloadNow = maybeWouldDownloadNow || shouldDownload(identity);
		
		if(reallyWouldDownloadNow == maybeWouldDownloadBefore)
			return;
		
		// What we know now:
		// - reallyWouldDownloadNow for sure is equal to whether we should download the Identity.
		// - If reallyWouldDownloadNow is true, then maybeWouldDownloadBefore must have been
		//   false, otherwise we would have returned. Thus the new Trust is a positive one which
		//   might have been the first to request downloading the Identity. However there may also
		//   have already been another Trust which triggered downloading it before. In other words:
		//   If reallyWouldDownloadNow is true we should try starting a download but expect that
		//   the download may already been running / a command for starting it may already be
		//   scheduled.
		//   This is obeyed by storeStartFetchCommandWithoutCommit_Checked().
		// - If reallyWouldDownloadNow is false, then maybeWouldDownloadBefore must have been true.
		//   In that case we did check shouldDownload() and it returned false to indicate no other
		//   Trust justifies downloading the Identity, so we know that the removed Trust was the
		//   only reason for wanting to download the Identity. In other words:
		//   If reallyWouldDownloadNow is false, then we must abort the download and not expect it
		//   to be queued for aborting yet.
		//   However in practice this is invalidated by the fact that this function is called before
		//   Score computation and Score computation may have already aborted the download by
		//   storeAbortFetchCommandWithoutCommit() if the Trust was the only reason the Identity
		//   was being downloaded.
		//   This is obeyed by storeAbortFetchCommandWithoutCommit_Checked().
		
		if(reallyWouldDownloadNow)
			storeStartFetchCommandWithoutCommit_Checked(identity);
		else
			storeAbortFetchCommandWithoutCommit_Checked(identity);
	}

	/** This callback is not used by this class. */
	@Override public void storeNewEditionHintCommandWithoutCommit(EditionHint hint) {
		// This callback isn't subject of our interest - it is completely handled by class
		// IdentityDownloaderSlow.
	}

	/**
	 * Storage and access of these objects and their functions must be guarded by synchronizing on
	 * {@link IdentityDownloaderFast#mWoT} (as they point to objects of class {@link Identity})
	 * and {@link IdentityDownloaderFast#mLock} (as IdentityDownloaderFast is the class which
	 * manages these objects and thus its main lock is the lock for the database table of objects
	 * of this class). */
	@SuppressWarnings("serial")
	public static abstract class DownloadSchedulerCommand extends Persistent {
		@IndexedField private final String mIdentityID;

		DownloadSchedulerCommand(WebOfTrust wot, final String identityID) {
			assert(wot != null);
			// TODO: Code quality: Java 8: Replace with lambda expression
			assertDidNotThrow(new Runnable() { @Override public void run() {
				IdentityID.constructAndValidateFromString(identityID);
			}});
			initializeTransient(wot);
			mIdentityID = identityID;
		}

		/** Returns the {@link Identity#getID()} of the associated {@link Identity}. */
		@Override public final String getID() {
			checkedActivate(1); // String is a db4o native type so 1 is enough
			return mIdentityID;
		}

		@Override public void startupDatabaseIntegrityTest() {
			checkedActivate(1); // String is a db4o native type so 1 is enough
			requireNonNull(mIdentityID);
			IdentityID.constructAndValidateFromString(mIdentityID);
			
			// Check whether only a single DownloadSchedulerCommand exists for mIdentityID by using
			// getQueuedCommand() on mIdentityID - it will throw if there is more than one.
			
			DownloadSchedulerCommand queriedFromDB =
				((WebOfTrust)mWebOfTrust).getIdentityDownloaderController()
				.getIdentityDownloaderFast().getQueuedCommand(mIdentityID);
			
			// As we now have the return value anyway let's check it.
			if(queriedFromDB != this) {
				throw new RuntimeException("getQueuedCommand() returned wrong result for " + this
					+ ": " + queriedFromDB);
			}
		}
		
		@Override public String toString() {
			return "[" + super.toString()
			     + "; mIdentityID: " + getID()
			     + "]";
		}

		/** ATTENTION: Make sure to call {@link DelayedBackgroundJob#triggerExecution()} upon
		 *  {@link IdentityDownloaderFast#mDownloadSchedulerThread} after using this!  
		 *  TODO: Code quality: Call it here right away, remove all calls of it next to calls
		 *  of this function. */
		@Override protected void storeWithoutCommit() {
			super.storeWithoutCommit();
		}

		/** Overriden for visibility only. */
		@Override protected void deleteWithoutCommit() {
			super.deleteWithoutCommit();
		}
	}

	@SuppressWarnings("serial")
	public static final class StartDownloadCommand extends DownloadSchedulerCommand {
		/**
		 * Users of StartDownloadCommand will not only need the {@link Identity#getID()} which we
		 * store in the parent class already but also the Identity object itself:
		 * They will have to use e.g. {@link Identity#getNextEditionToFetch()} so by storing the
		 * Identity object here we can avoid a database query to obtain it.
		 * 
		 * (Would have to be null for {@link StopDownloadCommand} which is why we don't store it
		 * in the parent class: If the Identity object is to be deleted the StopDownloadCommand will
		 * be processed after that and thus pointers to the Identity would be nulled by db4o.) */
		private final Identity mIdentity;

		StartDownloadCommand(WebOfTrust wot, Identity identity) {
			super(wot, identity.getID());
			mIdentity = identity;
		}

		Identity getIdentity() {
			checkedActivate(1);
			mIdentity.initializeTransient(mWebOfTrust);
			return mIdentity;
		}

		@Override public void startupDatabaseIntegrityTest() {
			super.startupDatabaseIntegrityTest();
			
			checkedActivate(1);
			requireNonNull(mIdentity);
			
			if(!getIdentity().getID().equals(getID())) {
				throw new IllegalStateException("mIdentity.getID() does not match mIdentityID: "
					+ getIdentity().getID() + " != " + getID());
			}
		}
	}

	@SuppressWarnings("serial")
	public static final class StopDownloadCommand extends DownloadSchedulerCommand {
		StopDownloadCommand(WebOfTrust wot, String identityID) {
			super(wot, identityID);
		}
	}

	/**
	 * The download scheduler thread which syncs the running downloads with the database.
	 * One would expect this to be done on the same thread which our download scheduling callbacks
	 * (= functions of our implemented interface {@link IdentityDownloader}) are called upon.
	 * But we must NOT immediately modify the set of running downloads there as what fred does
	 * cannot be undone by a rollback of the pending database transaction during which they are
	 * called - it may be rolled back after the callbacks return and thus we could e.g. keep running
	 * downloads which we shouldn't actually run.
	 * This scheduler thread here will obtain a fresh lock on the database so any pending
	 * transactions are guaranteed to be finished and it can assume that the database is correct
	 * and safely start downloads as indicated by it. */
	private final class DownloadScheduler implements PrioRunnable {
		@Override public void run() {
			Thread thread = currentThread();
			
			synchronized(mWoT) {
			synchronized(mLock) {
			synchronized(Persistent.transactionLock(mDB)) {
				try {
					if(logMINOR)
						Logger.minor(this, "Processing DownloadSchedulerCommands ...");
					
					for(StopDownloadCommand c : getQueuedCommands(StopDownloadCommand.class)) {
						// Have a try/catch block for each individual download to ensure potential
						// bugs cannot break all processing. (The outer try/catch is still required
						// as any database transaction should be fenced by one.)
						try {
							stopDownload(c.getID());
							c.deleteWithoutCommit();
						} catch(RuntimeException | Error e) {
							Logger.error(this, "Processing failed for (will retry): " + c, e);
							mDownloadSchedulerThread.triggerExecution(MINUTES.toMillis(1));
							
							// We shouldn't want to reach the point of committing the transaction
							// if the database told us it had a problem, that may corrupt it.
							if(e instanceof Db4oException)
								throw e;
						}
						
						if(thread.isInterrupted())
							break;
					}
					
					for(StartDownloadCommand c : getQueuedCommands(StartDownloadCommand.class)) {
						try {
							startDownload(c.getIdentity());
							c.deleteWithoutCommit();
						} catch(RuntimeException | Error e) {
							Logger.error(this, "Processing failed for (will retry): " + c, e);
							mDownloadSchedulerThread.triggerExecution(MINUTES.toMillis(1));
							
							if(e instanceof Db4oException)
								throw e;
						}
						
						if(thread.isInterrupted())
							break;
					}
					
					// isInterrupted() does not clear the interruption flag so we can do the logging
					// here instead of duplicating it at each of the above interruption checks.
					if(thread.isInterrupted())
						Logger.normal(this, "Shutdown requested, aborting command processing...");
					else
						assert(testSelf()); // Would fail if interrupted due to unprocessed commands
					
					Persistent.checkedCommit(mDB, this);
				} catch(RuntimeException | Error e) {
					Persistent.checkedRollback(mDB, this, e);
					
					Logger.error(this, "Error in DownloadScheduler.run()! Retrying later...", e);
					mDownloadSchedulerThread.triggerExecution(MINUTES.toMillis(1));
				} finally {
					if(logMINOR)
						Logger.minor(this, "Processing DownloadSchedulerCommands finished.");
				}
			}
			}
			}
		}
		
		/** Must be called while synchronized on {@link #mWoT} and {@link #mLock}. */
		private boolean testSelf() {
			// Determine all downloads which should be running
			
			IdentifierHashSet<Identity> allToDownload = new IdentifierHashSet<>();
			for(OwnIdentity i : mWoT.getAllOwnIdentities()) {
				for(Trust t : mWoT.getGivenTrusts(i)) {
					if(t.getValue() >= 0)
						allToDownload.add(t.getTrustee());
				}
			}
			
			for(OwnIdentity i : mWoT.getAllOwnIdentities()) {
				// OwnIdentitys are always eligible for download for the purpose of
				// WebOfTrust.restoreOwnIdentity() as demanded by IdentityDownloaderFast's JavaDoc.
				// We nevertheless do also check shouldFetchIdentity() to prepare for the future
				// feature of disabling the download once restoring is finished, see:
				// https://bugs.freenetproject.org/view.php?id=7129
				if(mWoT.shouldFetchIdentity(i))
					allToDownload.add(i);
				else {
					// It may have been marked as eligible for download by the above loop which
					// checked the Trusts of other OwnIdentitys but shouldFetchIdentity() might say
					// that we actually shouldn't download it anymore, so remove it then.
					allToDownload.remove(i);
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
		Logger.normal(this, "startDownload() called for: " + i);
		
		if(mUSKManager == null) {
			Logger.warning(this,
				"startDownload(): mUSKManager == null, not downloading anything! Valid in tests.");
			return;
		}
		
		USKRetriever existingDownload = mDownloads.get(i.getID());
		
		// Check whether we were called due to Identity.markForRefetch().
		// FIXME: The way we detect this is rather fragile guesswork -> write unit tests for it.
		if(existingDownload != null) {
			if(i.getCurrentEditionFetchState() == FetchState.NotFetched) {
				// markForRefetch() was called to request that an edition of the Identity which we
				// had potentially downloaded already previously is downloaded again.
				// It signals this by setting the FetchState to NotFetched.
				// So we must restart the request because the requested edition number might be
				// lower than the last one which the USKRetriever has fetched.
				
				Logger.normal(this, "startDownload(): markForRefetch() suspected, restarting "
				                  + "download for: " + i);
				
				existingDownload = null;
				stopDownload(i.getID());
			} else { 
				assert(i.getCurrentEditionFetchState() == FetchState.Fetched
				    || i.getCurrentEditionFetchState() == FetchState.ParsingFailed);
				
				// This valid to happen if the following sequence of events happens:
				// 1. markForRefetch() is called and thus the Identity's FetchState becomes
				//    NotFetched and a StartDownloadCommand is enqueued to schedule this function
				//    here to run.
				// 2. Before the command is processed the Identity publishes a new edition which our
				//    existing USKRetriever finds. Processing of the downloaded edition causes the
				//    FetchState to become Fetched.
				// 3. The command is processed and we thus reach this point here: The FetchState is
				//    Fetched (or ParsingFailed) but existingDownload is != null.
				// In that case we do not need to refetch the edition:
				// The purpose of markForRefetch() is to ensure a trust list gets imported after an
				// Identity's trust changed from "not eligible to have its trust list imported" to
				// "eligible to have its trust list imported". It doesn't matter *which* trust list
				// it is, we just want the latest we can get - which was accomplished by downloading
				// a trust list higher than what markForRefetch() processing would request now.
				
				Logger.normal(this, "startDownload(): markForRefetch() suspected but we already "
				                  + "found a new edition, keeping existing download for: " + i);
				
				return;
			}
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

		Logger.normal(this, "startDownload(): Downloading by USK subscription: " + i
		                  + "; Using URI: " + usk);

		USKRetriever download = mUSKManager.subscribeContent(
			usk, this, true, fetchContext, fetchPriority, mRequestClient);

		existingDownload = mDownloads.put(i.getID(), download);
		assert(existingDownload == null);
	}

	@Override public short getPollingPriorityNormal() {
		return DOWNLOAD_PRIORITY_POLLING;
	}

	@Override public short getPollingPriorityProgress() {
		return DOWNLOAD_PRIORITY_PROGRESS;
	}

	/** Must be called while synchronized on {@link #mLock}. */
	private void stopDownload(String identityID) {
		Logger.normal(this, "stopDownload() running for identity ID: " + identityID);
		
		USKRetriever retriever = mDownloads.get(identityID);
		
		if(retriever == null) {
			assert(!mDownloads.containsKey(identityID)) : "Bugs could map the key to null";
			
			// Not an error: Can happen in unit tests as startDownload() won't be able to create
			// USKRetrievers there.
			Logger.warning(this, "stopDownload() called but there is no download!",
				new RuntimeException("Exception not thrown, only for logging a trace!"));
			
			return;
		}
		
		// Useful for debugging markForRefetch() handling of startDownload()
		Logger.normal(this, "stopDownload(): URI with edition with which the request was started: "
			+ retriever.getOriginalUSK());
		
		retriever.cancel(mClientContext);
		mUSKManager.unsubscribeContent(retriever.getOriginalUSK(), retriever, true);
		
		// We didn't remove() it right at the beginning instead of get() to ensure we can attempt to
		// stop the download multiple times if something throws at the first attempt.
		mDownloads.remove(identityID);
		
		Logger.normal(this, "stopDownload() finished.");
	}

	@Override public void onFound(USK origUSK, long edition, FetchResult data) {
		Bucket bucket = null;
		InputStream inputStream = null;
		
		try {
			bucket = data.asBucket();
			inputStream = bucket.getInputStream();
			
			FreenetURI realURI = origUSK.getURI().setSuggestedEdition(edition);
			
			if(logMINOR)
				Logger.minor(this, "onFound(): Downloaded Identity XML: " + realURI);
			
			// Thanks to IdentityFileQueue we do NOT have to take mLock while consuming the
			// InputStream:
			// The queue exists to allow delaying import of the IdentityFileStream into the db4o
			// database by serializing it to disk and having the separate thread of
			// IdentityFileProcessor do the importing.
			// This was implemented because download of IdentityFiles is usually faster than
			// their processing and happens in parallel, so possibly many threads would call this 
			// function here concurrently - but processing requires mLock so hundreds of threads
			// would stall here if we did the processing in this function already.
			//
			// IdentityFileStream currently does not need to be close()d so we don't store it
			// FIXME: It is very unsafe to assume the stream implementation won't be changed to
			// need closure, just close it here anyway.
			mOutputQueue.add(new IdentityFileStream(realURI, inputStream));
			
			mDownloadedEditions.incrementAndGet();
		} catch(IOException | RuntimeException | Error e) {
			mDownloadProcessingFailures.incrementAndGet();
			Logger.error(this, "onFound(): Failed for URI: " + origUSK +
				               "; edition: " + edition, e);
		} finally {
			Closer.close(inputStream);
			Closer.close(bucket);
		}
	}

	@Override public boolean getShouldFetchState(Identity identity) {
		// Will implicitly check for contradicting commands, i.e. both StartDownloadCommand
		// and StopDownloadCommand existing at once, or duplicate commands, and throw upon that.
		DownloadSchedulerCommand c = getQueuedCommand(identity);
		
		// If a command is stored then the interface specification demands that our return value is
		// computed as if the command was already processed, i.e. we must ignore whether mDownloads
		// contains a running download or not.
		if(c != null) {
			if(c instanceof StopDownloadCommand) {
				// storeAbortFetchCommandWithoutCommit() should only store a StopDownloadCommand if
				// a download is actually running.
				assert(mDownloads.containsKey(identity.getID()));
				
				return false;
			} else {
				assert(c instanceof StartDownloadCommand);
				
				// This assert would currently be invalid:
				// storeStartFetchCommandWithoutCommit() will also store a command even if a
				// download is already running - for the purpose of handling
				// Identity.markForRefetch().
				/* assert(!mDownloads.containsKey(identity.getID())); */
				
				return true;
			}
		}
		
		// If no command is stored then the return value is determined by whether we are currently
		// running a download as the result of already processed commands.
		return mDownloads.containsKey(identity.getID());
	}

	@Override public void deleteAllCommands() {
		synchronized(mWoT) { // DownloadSchedulerCommands point to objects of class Identity
		synchronized(mLock) {
		synchronized(Persistent.transactionLock(mDB)) {
			try {
				Logger.warning(this, "Deleting all DownloadSchedulerCommands, should only happen "
				                   + " for debugging purposes! ...");
				
				deleteAllCommandsWithoutCommit();
				Persistent.checkedCommit(mDB, this);
			}
			catch(RuntimeException e) {
				Persistent.checkedRollbackAndThrow(mDB, this, e);
			}
		}
		}
		}
	}

	private void deleteAllCommandsWithoutCommit() {
		Logger.normal(this, "deleteAllCommandsWithoutCommit()...");
		
		int amount = 0;
		for(DownloadSchedulerCommand c : getAllQueuedCommands()) {
			c.deleteWithoutCommit();
			++amount;
		}
		
		Logger.normal(this, "deleteAllCommandsWithoutCommit(): Deleted " + amount +
			" DownloadSchedulerCommands.");
	}

	@Override public void scheduleImmediateCommandProcessing() {
		mDownloadSchedulerThread.triggerExecution(0);
	}

	/** @see #getQueuedCommand(String) */
	private DownloadSchedulerCommand getQueuedCommand(Identity identity) {
		return getQueuedCommand(identity.getID());
	}

	/** Must be called while synchronized on {@link #mWoT} and {@link #mLock}. */
	private DownloadSchedulerCommand getQueuedCommand(String identityID) {
		Query q = mDB.query();
		q.constrain(DownloadSchedulerCommand.class);
		q.descend("mIdentityID").constrain(identityID);
		InitializingObjectSet<DownloadSchedulerCommand> result
			= new InitializingObjectSet<>(mWoT, q);
		
		switch(result.size()) {
			case 0:
				return null;
			case 1:
				DownloadSchedulerCommand c = result.next();
				assert(c.getID().equals(identityID));
				return c;
			default:
				throw new DuplicateObjectException(
					"Multiple DownloadSchedulerCommand objects stored for Identity " + identityID);
		}
	}

	/** Must be called while synchronized on {@link #mWoT} and {@link #mLock}. */
	private <T extends DownloadSchedulerCommand> ObjectSet<T> getQueuedCommands(Class<T> type) {
		Query q = mDB.query();
		q.constrain(type);
		return new InitializingObjectSet<>(mWoT, q);
	}

	/** Must be called while synchronized on {@link #mWoT} and {@link #mLock}. */
	private ObjectSet<DownloadSchedulerCommand> getAllQueuedCommands() {
		return getQueuedCommands(DownloadSchedulerCommand.class);
	}

	public final class IdentityDownloaderFastStatistics {
		/** Number of queued {@link StartDownloadCommand}s which request us to start downloading an
		 *  {@link Identity}. */
		public final int mScheduledForStartingDownloads;
	
		/** Number of queued {@link StopDownloadCommand}s which request us to stop downloading an
		 *  {@link Identity}. */
		public final int mScheduledForStoppingDownloads;
	
		/** Number of running Freenet {@link USKManager} subscriptions, i.e. running permanent
		 *  download requests which ship all updates of an {@link Identity}.
		 *  
		 *  This should be equal to the number of {@link Identity}s to which the user's
		 *  {@link OwnIdentity}s have assigned a {@link Trust} with {@link Trust#getValue()} >= 0,
		 *  plus the number of OwnIdentitys as they are subscribed to as well for
		 *  {@link WebOfTrust#restoreOwnIdentity(FreenetURI)}.
		 *  
		 *  Downloads are not counted into this while they are in the scheduling queue, see
		 *  {@link #mScheduledForStartingDownloads}. */
		public final int mRunningDownloads;
	
		/** Number of USK editions, i.e. {@link IdentityFile}s, which the {@link USKManager}
		 *  subscriptions have downloaded. */
		public final int mDownloadedEditions;
	
		/** Number of editions which were downloaded successfully but for which adding them to the
		 *  {@link IdentityFileQueue} failed, either due to errors at its side or ours. */
		public final int mDownloadProcessingFailures;
	
	
		public IdentityDownloaderFastStatistics() {
			synchronized(IdentityDownloaderFast.this.mWoT) { // For getQueuedCommands()
			synchronized(IdentityDownloaderFast.this) {
				mRunningDownloads = IdentityDownloaderFast.this
					.mDownloads.size();
				mScheduledForStartingDownloads = IdentityDownloaderFast.this
					.getQueuedCommands(StartDownloadCommand.class).size();
				mScheduledForStoppingDownloads = IdentityDownloaderFast.this
					.getQueuedCommands(StopDownloadCommand.class).size();
			}}
			mDownloadedEditions = IdentityDownloaderFast.this
				.mDownloadedEditions.get();
			mDownloadProcessingFailures = IdentityDownloaderFast.this
				.mDownloadProcessingFailures.get();
		}
	}
}
