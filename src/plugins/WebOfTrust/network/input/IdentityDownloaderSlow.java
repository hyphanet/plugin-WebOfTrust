/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.network.input;

import static java.lang.Math.max;
import static java.lang.Thread.currentThread;
import static java.lang.Thread.sleep;
import static java.util.Collections.sort;
import static java.util.concurrent.TimeUnit.MINUTES;
import static plugins.WebOfTrust.util.AssertUtil.assertDidThrow;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map.Entry;
import java.util.concurrent.Callable;

import plugins.WebOfTrust.Identity;
import plugins.WebOfTrust.Identity.IdentityID;
import plugins.WebOfTrust.IdentityFetcher;
import plugins.WebOfTrust.IdentityFile;
import plugins.WebOfTrust.IdentityFileQueue;
import plugins.WebOfTrust.IdentityFileQueue.IdentityFileStream;
import plugins.WebOfTrust.OwnIdentity;
import plugins.WebOfTrust.Persistent;
import plugins.WebOfTrust.Persistent.InitializingObjectSet;
import plugins.WebOfTrust.Score;
import plugins.WebOfTrust.SubscriptionManager;
import plugins.WebOfTrust.Trust;
import plugins.WebOfTrust.Trust.TrustID;
import plugins.WebOfTrust.WebOfTrust;
import plugins.WebOfTrust.XMLTransformer;
import plugins.WebOfTrust.exceptions.DuplicateObjectException;
import plugins.WebOfTrust.exceptions.NotInTrustTreeException;
import plugins.WebOfTrust.exceptions.UnknownEditionHintException;
import plugins.WebOfTrust.exceptions.UnknownIdentityException;
import plugins.WebOfTrust.util.AssertUtil;
import plugins.WebOfTrust.util.Daemon;
import plugins.WebOfTrust.util.jobs.DelayedBackgroundJob;
import plugins.WebOfTrust.util.jobs.MockDelayedBackgroundJob;
import plugins.WebOfTrust.util.jobs.TickerDelayedBackgroundJob;

import com.db4o.ObjectSet;
import com.db4o.ext.ExtObjectContainer;
import com.db4o.query.Query;

import freenet.client.FetchContext;
import freenet.client.FetchException;
import freenet.client.FetchException.FetchExceptionMode;
import freenet.client.FetchResult;
import freenet.client.HighLevelSimpleClient;
import freenet.client.async.ClientContext;
import freenet.client.async.ClientGetCallback;
import freenet.client.async.ClientGetter;
import freenet.keys.FreenetURI;
import freenet.node.PrioRunnable;
import freenet.node.RequestClient;
import freenet.node.RequestStarter;
import freenet.pluginmanager.PluginRespirator;
import freenet.support.CurrentTimeUTC;
import freenet.support.Logger;
import freenet.support.PooledExecutor;
import freenet.support.PrioritizedTicker;
import freenet.support.Ticker;
import freenet.support.api.Bucket;
import freenet.support.io.Closer;
import freenet.support.io.NativeThread;
import freenet.support.io.ResumeFailedException;

/**
 * Uses USK edition hints to download {@link Identity}s from the network for which we have a
 * significant confidence that a certain edition exists.
 * For an explanation of what an edition hint is, see {@link EditionHint}.
 * This class is the manager for storage of {@link EditionHint} objects.
 * 
 * The downloads happen as a direct SSK request, and thus don't cause as much network load as the
 * USK subscriptions which {@link IdentityDownloaderFast} would do.
 * 
 * This class only deals with the {@link Identity}s which {@link IdentityDownloaderFast} does not
 * download, so in combination the both of these classes download all {@link Identity}s.
 * FIXME: Actually "only" isn't true, it is rather "mostly": This class is unaware of the efforts of
 * the {@link IdentityDownloaderFast} currently, i.e. it also fetches identities with are direct
 * trustees of an {@link OwnIdentity}.
 * We should either feed our related {@link EditionHint}s to the USK fetching fred code which the
 * other class uses and not fetch those identities ourselves, or just blindly keep fetching them
 * on our own here instead of relaying the EditionHints to fred's USK code.
 * Edition hints are critically important for USK fetching either way as Freenet's date-based
 * hinting isn't as precise.
 * FIXME: If some overlapping continues to exist make sure to communicate
 * {@link #onSuccess(FetchResult, ClientGetter)} and
 * {@link #onFailure(FetchException, ClientGetter)} to the {@link IdentityDownloaderFast}, and
 * also have that one do the same for us - this avoids both class trying to download the same stuff
 * twice. Notice: An *invalid* approach for handling a downloaded or failed to download edition in
 * IdentityDownloaderFast would be to call fred's function for passing an edition hint to its USK
 * code with edition = fetchedOrFailedEdition + 1: fred considers edition hints as non-mandatory so
 * it would continue trying to fetch lower editions if downloading the hint fails, which it may do
 * as the edition may not have been published yet.
 * It may actually not be necessary to do anything in IdentityDownloaderFast: Fred probably passes
 * the knowledge about an edition which was fetched by SSK to its USK code internally, though I am
 * not sure about that. It is possible it only does that if the USK code was already running a
 * request for that edition. This is something we should ask toad_ about - which I've done, I will
 * add his reply here once I have it.
 * 
 * Some of the storage policy of {@link EditionHint} objects:
 * - An Identity is eligible for download if {@link WebOfTrust#shouldFetchIdentity(Identity)} is
 *   true. See {@link #shouldDownload(Identity)}.
 * - Identitys which are not eligible for download will not have their received EditionHints stored.
 *   I.e. EditionHints can only exist for values of {@link EditionHint#getTargetIdentity()} where
 *   that Identity is eligible for download. Thereby the set of all EditionHints represents our
 *   download queue {@link #getQueue()}.
 * - All Identitys which are eligible for download will have their *given* EditionHints (hints
 *   where {@link EditionHint#getSourceIdentity()} == the identity) accepted, i.e. stored, if
 *   {@link WebOfTrust#getBestCapacity(Identity)} of the Identity is greater or equals to
 *   {@link EditionHint#MIN_CAPACITY}.
 *   See {@link #shouldAcceptHintsOf(Identity)}.
 * - An Identity which is not eligible for download is not eligible for giving EditionHints to other
 *   Identitys. I.e. if {@link WebOfTrust#shouldFetchIdentity(Identity)} is false for an Identity,
 *   then no EditionHint objects will be stored with {@link EditionHint#getSourceIdentity()} ==
 *   the given Identity.
 *   Also see {@link #shouldAcceptHintsOf(Identity)}.
 * - For a given pair of an Identity as specified by {@link EditionHint#getSourceIdentity()} and an
 *   Identity as specified by {@link EditionHint#getTargetIdentity()} there can only be a single
 *   EditionHint object stored. This is because there can only be a single latest edition of a given
 *   targetIdentity, and the sourceIdentity thus cannot say that there are multiple.
 * - Once an edition of a given targetIdentity is fetched, all EditionHints of that edition or a
 *   lower one are deleted. In other words: EditionHint objects are only stored for new editions.
 * - For a given targetIdentity, there *CAN* be multiple EditionHint objects stored for the same
 *   edition! This is because:
 *   * The priority/position in the download queue of all hints is affected by lots of attributes
 *     (see {@link EditionHint#compareTo(EditionHint)}) such as especially the trustworthiness of
 *     the sourceIdentity - and it thus is easier to just store multiple EditionHint objects for the
 *     same edition to let the database compute the one with the highest priority by their natural
 *     ordering than to manually decide before storing them which one has the highest priority and
 *     not storing all others.
 *   * Further, when a sourceIdentity is distrusted by the WebOfTrust calling
 *     {@link #storeAbortFetchCommandWithoutCommit(Identity)}), we have to delete all EditionHints
 *     it gave. But their editions may be valid, e.g. also hinted at by a different sourceIdentity
 *     - so if we didn't keep all EditionHint objects for the same edition we would have to figure
 *     out if there was a different sourceIdentity providing a hint for that edition to store
 *     an EditionHint object for it. And we would have to do that for all trustees of the distrusted
 *     Identity by iterating over all their received trusts - so that would be an
 *     O(number_of_trustees_of_distrusted_identity * number_of_trusters_of_each_trustee) = O(N*N)
 *     = O(N^2) operation.
 *     TODO: Performance:
 *     The complexity of this actually wouldn't be that bad so a possible future revision of this
 *     class may implement it to save the disk space of having multiple EditionHint objects for the
 *     same edition: One of the factors N, the number of trustees, is actually limited to a constant
 *     value ({@link XMLTransformer#MAX_IDENTITY_XML_TRUSTEE_AMOUNT}), so the complexity may be
 *     bearable.
 *     It may also be worthy to trade this time for the disk space as someone becoming distrusted
 *     should hopefully not happen very often - but the disk usage we currently have is taken all
 *     the time, and it is in fact also O(N*512): Each truster/trustee pair constitutes an
 *     EditionHint.
 * More details about the EditionHint storage policy can be seen at:
 * - {@link #storeStartFetchCommandWithoutCommit(Identity)}
 * - {@link #storeAbortFetchCommandWithoutCommit(Identity)}
 * - {@link #storeNewEditionHintCommandWithoutCommit(EditionHint)}
 * - further event handlers of this class.
 * - {@link #testDatabaseIntegrity()} and {@link EditionHint#startupDatabaseIntegrityTest()} */
public final class IdentityDownloaderSlow implements
		IdentityDownloader,
		Daemon,
		PrioRunnable,
		ClientGetCallback {

	/** 
	 * Once we have stored an {@link EditionHint} to the database, {@link #run()} is scheduled to
	 * execute and start downloading of the pending hint queue after this delay.
	 * As we download multiple hints in parallel, the delay is non-zero to ensure we don't have to
	 * do multiple database queries if multiple hints arrive in a short timespan - database queries
	 * are likely the most expensive operation.
	 * FIXME: Performance: Use a delay of 0 if no downloads are running currently, i.e. if
	 * mDownloads.size() == 0.
	 * TODO: Code quality: Make configurable. */
	public static final long QUEUE_BATCHING_DELAY_MS = MINUTES.toMillis(1);

	/**
	 * Priority of network requests, relative to {@link IdentityDownloaderFast} as we use a single
	 * {@link RequestClient} for that downloader and this one.
	 * 
	 * Also see the file "developer-documentation/RequestClient and priority map.txt" */
	public static final short DOWNLOAD_PRIORITY = RequestStarter.BULK_SPLITFILE_PRIORITY_CLASS;

	/** @see #getMaxRunningDownloadCount() */
	public static final int DOWNLOAD_AMOUNT= 64;

	/**
	 * When a download completes new downloads will be started immediately if the number of still
	 * running downloads is less than {@link #getMaxRunningDownloadCount()} divided by this
	 * constant.
	 * See {@link #getMinRunningDownloadCount()} and its callers.
	 * 
	 * TODO: Performance: Profile the performance impact of various values of this - refilling the
	 * downloads does a dababase query by {@link #getQueue()}, which returns all {@link EditionHint}
	 * objects in the database. Those can be in the multiple thousands so it may take some CPU time
	 * while we're not configuring db4o to do lazy query evaluation yet. */
	public static final int DOWNLOAD_REFILL_THRESHOLD = 2;

	/**
	 * Priority of the {@link #run()} thread which starts downloads of the edition hint queue.
	 * 
	 * Below NORM_PRIORITY because we are a background thread and not relevant to UI actions.
	 * Above MIN_PRIORITY because we're not merely a cleanup thread.
	 * -> LOW_PRIORITY is the only choice. */
	public static transient final int DOWNLOADER_THREAD_PRIORITY = NativeThread.LOW_PRIORITY;


	private final WebOfTrust mWoT;

	/** Is null in unit tests */
	private final ClientContext mClientContext;

	/** Is null in unit tests */
	private final HighLevelSimpleClient mHighLevelSimpleClient;

	/** @see #getRequestClient() */
	private final RequestClient mRequestClient;

	/**
	 * The central lock upon which we use synchronized() during concurrent read/write access of the
	 * non-constant members of this class and the database entries managed by it, i.e. objects of
	 * class {@link EditionHint}.
	 * 
	 * Is set to the value of {@link WebOfTrust#getIdentityDownloaderController()}.
	 * Thereby is equal to the lock which is specified in the {@link IdentityDownloader} interface
	 * as lock for its callbacks. This means we can assume this lock to be held in all
	 * IdentityDownloader callbacks which are implemented here, and thus there we can safely access
	 * our EditionHint queue and member variables without further locking. */
	private final IdentityDownloaderController mLock;
	
	private final ExtObjectContainer mDB;

	/**
	 * When we download an {@link Identity} the resulting {@link IdentityFile} is stored for
	 * processing at this {@link IdentityFileQueue}. */
	private final IdentityFileQueue mOutputQueue;

	/**
	 * The IdentityDownloaderSlow schedules execution of its download queue processing thread
	 * {@link #run()} on this {@link DelayedBackgroundJob}.
	 * The execution typically is scheduled after a delay of {@link #QUEUE_BATCHING_DELAY_MS}.
	 * 
	 * The value distinguishes the run state of this IdentityDownloaderSlow as follows:
	 * - Until {@link #start()} was called, defaults to {@link MockDelayedBackgroundJob#DEFAULT}
	 *   with {@link DelayedBackgroundJob#isTerminated()} == true.
	 * - Once {@link #start()} has been called, becomes a
	 *   {@link TickerDelayedBackgroundJob} with {@link DelayedBackgroundJob#isTerminated()}
	 *   == false.
	 * - Once {@link #stop()} has been called, stays a {@link TickerDelayedBackgroundJob} but has
	 *   {@link DelayedBackgroundJob#isTerminated()} == true for ever.
	 * 
	 * There can be exactly one start() - stop() lifecycle, an IdentityDownloaderSlow cannot be
	 * recycled.
	 * 
	 * Concurrent write access to this variable by start() is guarded by {@link #mLock}.
	 * Volatile since stop() needs to read it without synchronization.
	 * 
	 * {@link IdentityDownloaderFast#mDownloadSchedulerThread} and {@link SubscriptionManager#mJob}
	 * are related to this, please apply changes there as well. */
	private volatile DelayedBackgroundJob mDownloadSchedulerThread
		= MockDelayedBackgroundJob.DEFAULT;

	private final HashMap<FreenetURI, ClientGetter> mDownloads;

	/**
	 * TODO: Code quality: This is incremented inside transactions but not decremented upon
	 * rollback. Fix by computing it from the database contents. E.g. use the number of still queued
	 * EditionHints minus the numbers of succeeded and failed downloads.
	 * On the other hand perhaps it is good to have this as an indicator in the UI: If queued
	 * downloads are rolled back before they are processed that will show up by the other numbers
	 * not adding up to this, which allows users to notice rollbacks. Perhaps a tradeoff would
	 * be to show the difference in the UI.
	 * The same issue may or may not apply to the other statistics. */
	private int mTotalQueuedDownloadsInSession = 0;

	private int mSucceededDownloads = 0;

	private int mSkippedDownloads = 0;

	private int mFailedTemporarilyDownloads = 0;

	private int mFailedPermanentlyDownloads = 0;
	
	private int mDataNotFoundDownloads = 0;

	private static transient volatile boolean logDEBUG = false;

	private static transient volatile boolean logMINOR = false;

	static {
		Logger.registerClass(IdentityDownloaderSlow.class);
	}


	public IdentityDownloaderSlow(IdentityDownloaderController controller) {
		assert(controller != null);
		
		mWoT = controller.getWebOfTrust();
		
		PluginRespirator pr = mWoT.getPluginRespirator();
		if(pr != null) {
			mClientContext = pr.getNode().clientCore.clientContext;
			mHighLevelSimpleClient = pr.getHLSimpleClient();
		} else { // Unit test
			mClientContext = null;
			mHighLevelSimpleClient = null;
		}
		
		mRequestClient = mWoT.getRequestClient();
		mLock = controller;
		mDB = mWoT.getDatabase();
		mOutputQueue = mWoT.getIdentityFileQueue();
		mDownloads = new HashMap<>(getMaxRunningDownloadCount() * 2);
	}

	/**
	 * Enables processing of the download queue.
	 * Commands enqueued before this was called will be obeyed.
	 * 
	 * Based on {@link SubscriptionManager#start()}, which is related to
	 * {@link IdentityFetcher#start()}, please apply changes there as well. */
	@Override public void start() {
		Logger.normal(this, "start() ...");
		
		synchronized(mWoT) {
		synchronized(mLock) {
		// A transaction block is not necessary: We only do read access.
		/* synchronized(Persistent.transactionLock(mDB)) {
		   try { */
			
			// This is thread-safe guard against concurrent multiple calls to start() / stop() since
			// stop() does not modify the variable and start() is synchronized. 
			if(mDownloadSchedulerThread != MockDelayedBackgroundJob.DEFAULT)
				throw new IllegalStateException("start() was already called!");
			
			if(logDEBUG)
				testDatabaseIntegrity();
			
			// No need to schedule downloads by creating EditionHints, we can keep using the
			// EditionHints of the last run of WoT:
			// We don't delete EditionHints when having started a download from the queue, we only
			// delete them once the download is finished. So the running downloads of the previous
			// session which were aborted during shutdown are not lost, they're still scheduled in
			// the queue.
			// FIXME: What we hereby put into it contradicts the naming of the variable. Perhaps fix
			// this by removing the "InSession" suffix. Optionally introduce a similar separate
			// variable as IdentityFileQueueStatistics.mLeftoverFilesOfLastSession, if only for
			// display on the StatisticsPage?
			mTotalQueuedDownloadsInSession = getQueue().size();
			
			PluginRespirator respirator = mWoT.getPluginRespirator();
			Ticker ticker;
			Runnable jobRunnable;
			
			if(respirator != null) { // We are connected to a node
				ticker = respirator.getNode().getTicker();
				jobRunnable = this;
			} else { // We are inside of a unit test
				Logger.warning(this,
				    "No PluginRespirator available, will never run mDownloadSchedulerThread. "
				  + "This should only happen in unit tests!");
				
				// Generate our own Ticker so we can set mDownloadSchedulerThread to be a real
				// TickerDelayedBackgroundJob. This is better than leaving it be a
				// MockDelayedBackgroundJob because it allows us to clearly distinguish the run
				// state (start() not called, start() called, terminate() called) by checking
				// whether mDownloadSchedulerThread is at the default or not, and if not checking
				// the run state of mDownloadSchedulerThread itself.
				ticker = new PrioritizedTicker(new PooledExecutor(), 0);
				jobRunnable = new Runnable() { @Override public void run() {
					 // Do nothing because:
					 // - We shouldn't do work on custom executors, we should only ever use the main
					 //   one of the node.
					 // - Unit tests execute instantly after loading the WoT plugin, so delayed jobs
					 //   should not happen since their timing cannot be guaranteed to match the
					 //   unit tests execution state.
				}};
			}
			
			// Set the volatile mDownloadSchedulerThread after everything which terminate() must
			// cleanup is initialized to ensure that terminate() can use the variable (without
			// synchronization) to check whether cleanup will cover everything.
			mDownloadSchedulerThread = new TickerDelayedBackgroundJob(
				jobRunnable, "WoT IdentityDownloaderSlow", QUEUE_BATCHING_DELAY_MS, ticker);
			
			// If downloads are enqueued from the previous session schedule run() to execute to
			// start them.
			if(mTotalQueuedDownloadsInSession > 0) {
				// Use 0 as delay instead of the QUEUE_BATCHING_DELAY_MS which is > 0 as no network
				// requests are running yet and thus we cannot download any EditionHints which would
				// make sense to batch-process along with the existing ones.
				// (Doing triggerExecution() after the above greenlight for terminate() isn't a
				// problem because terminate will use mDownloadSchedulerThread.waitForTermination()
				// to take account for triggerExecution() maybe being called concurrently.)
				mDownloadSchedulerThread.triggerExecution(0);
			}
		}
		}
		
		Logger.normal(this, "start() finished.");
	}

	/**
	 * Shuts down the IdentityDownloaderSlow by aborting all running downloads and also interrupting
	 * {@link #run()}'s efforts to enqueue more downloads.
	 * The downloads queue will be preserved in the database for the next session. 
	 * Blocking: Once it returns shutdown is guaranteed to be finished.
	 * 
	 * Based on {@link SubscriptionManager#stop()}, which is based on
	 * {@link IdentityFetcher#stop()}, please apply changes there as well. */
	private void stop() {
		Logger.normal(this, "stop() ...");
		
		// The following code intentionally does NOT write to the mDownloadSchedulerThread variable
		// so it does not have to use synchronized(mLock). We do not want to synchronize because:
		// 1) run() is synchronized(mLock), so we would not get the lock until run() is finished.
		//    But we want to call mDownloadSchedulerThread.terminate() immediately while run() is
		//    still executing to make it call Thread.interrupt() upon run() to speed up its
		//    termination. So we shouldn't require acquisition of the lock before
		//    mDownloadSchedulerThread.terminate().
		// 2) Keeping mDownloadSchedulerThread as is makes sure that start() is not possible anymore
		//    so this object can only have a single lifecycle. Recycling being impossible reduces
		//    complexity and is not needed for normal operation of WoT anyway.
		
		
		// Since mDownloadSchedulerThread can only transition from not "not started yet", as implied
		// by the "==" here, to "started" as implied by "!=", but never backwards, is volatile, and
		// is set by start() *after* everything is initialized, this is safe against concurrent
		// start() / stop().
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
			// TODO: Performance: Decrease if it doesn't interfere with plugin unloading. I would
			// rather not though: Plugin unloading unloads the JAR of the plugin, and thus all its
			// classes. That will probably cause havoc if threads of it are still running.
			mDownloadSchedulerThread.waitForTermination(Long.MAX_VALUE);
		} catch (InterruptedException e) {
			// We are a shutdown function, there is no sense in sending a shutdown signal to us.
			Logger.error(this, "stop() should not be interrupt()ed.", e);
		}
		
		// Nothing can start downloads anymore so we can now cancel the running ones.
		// We still do need to acquire locks: Downloads may finish concurrently via the onSuccess()
		// and onFailure() callbacks - which will access the same data structure (mDownloads) as we
		// do now.
		synchronized(mWoT) { // For onFailure(), see comment inside the block
		synchronized(mLock) { // For access of mDownloads
			// To prevent modification of mDownloads while we iterate over it we must copy it: While
			// we iterate over it we want to call ClientGetter.cancel() on the entries, but that
			// will call this.onFailure() on the same thread, which will remove the relevant entries
			// from mDownloads.
			ClientGetter[] downloads
				= mDownloads.values().toArray(new ClientGetter[mDownloads.size()]);
			for(ClientGetter download : downloads) {
				if(logMINOR)
					Logger.minor(this, "stop(): Cancelling download: " + download.getURI());
				
				download.cancel(mClientContext);
				
				// We don't need to store the download in the queue database:
				// The code for starting downloads doesn't remove them from the queue.
			}
		}
		}
		
		// For some downloads onFailure() / onSuccess() may have already been in progress while we
		// tried to cancel all downloads above.
		// The functions cannot have returned in the above synchronized{} then because they need
		// the same locks.
		// Thus, to ensure no more onFailure() / onSuccess() are running and all downloads are
		// really gone, we must wait until mDownloads.size() == 0.
		// FIXME: Fix the lack of this in all other WoT/FT classes which start fetches/inserts, and
		// also in fred class TransferThread.
		while(true) {
			synchronized(mLock) {
				if(mDownloads.size() == 0)
					break;
			}
			
			try {
				sleep(100);
			} catch (InterruptedException e) {
				throw new RuntimeException("Interrupting stop() to stop doesn't make sense!", e);
			}
		}
		
		Logger.normal(this, "stop() finished.");
	}

	@Override public void terminate() {
		// terminate() is merely a wrapper around stop() in preparation of the TODO at
		// Daemon.terminate() which requests renaming it to stop().
		stop();
	}

	/**
	 * ATTENTION: For internal use only! TODO: Code quality: Wrap in a private class to hide it like
	 * e.g. {@link IdentityDownloaderFast.DownloadScheduler}. Update the documentation of
	 * {@link #mDownloadSchedulerThread} to reflect that.
	 * 
	 * The actual downloader. Starts fetches for the head of {@link #getQueue()}.
	 * 
	 * Executed on an own thread after {@link DelayedBackgroundJob#triggerExecution()} was called
	 * on {@link #mDownloadSchedulerThread}. That function is called by this class whenever the
	 * download queue changes, namely when the event handlers of interface
	 * {@link IdentityDownloader} are called on it.
	 * 
	 * Respects {@link Thread#interrupt()} to speed up shutdown, which
	 * {@link DelayedBackgroundJob#terminate()} will make use of. */
	@Override public void run() {
		if(logMINOR) Logger.minor(this, "run()...");

		synchronized(mWoT) {
		synchronized(mLock) {
			// We don't have to take a database transaction lock:
			// We don't write anything to the database here.
			// We nevertheless try/catch to re-schedule the thread for execution in case of
			// unexpected exceptions being thrown: Keeping downloads running is important to
			// ensure WoT keeps working, some defensive programming cannot hurt.
			try {
				int maxDownloads = getMaxRunningDownloadCount();
				int downloadsToSchedule = maxDownloads - getRunningDownloadCount();
				// Check whether we actually need to do something before the expensive getQueue().
				if(downloadsToSchedule <= 0)
					return;
				
				// TODO: Performance: Use ArraySet here once we have one, is small enough.
				HashSet<String> identitiesBeingDownloaded = new HashSet<>(maxDownloads * 2);
				// TODO: Performance: Key mDownloads by the ID of the Identity, not the URI of
				// the specific edition being downloaded, so we can get rid of this loop because
				// mDownloads' keyset is equal to the hereby populated HashSet then.
				for(FreenetURI u : mDownloads.keySet()) {
					// FIXME: Performance: We don't need URI validation here.
					identitiesBeingDownloaded.add(
						IdentityID.constructAndValidateFromURI(u).toString());
				}
				
				for(EditionHint h : getQueue()) {
					// Test if our logic of storing only eligible EditionHints is correct.
					assert(shouldAcceptHintsOf(h.getSourceIdentity()));
					assert(shouldDownload(h.getTargetIdentity()));
					
					// Only run one download per identity at once.
					// This is necessary because:
					// - it is fair.
					// - it can easily occur that we have many EditionHints in the queue for the
					//   same Identity which all have almost the same EditionHint.getPriority()
					//   value = will be next to each other in the queue, so we would end up trying
					//   to download lots of hints for the same Identity at once which:
					//   - doesn't make sense because the first of them may have a higher edition
					//     than all the others and we're only interested in the highest edition.
					//   - blocks the download of other identities.
					// (We could avoid having to skip these via "continue;" by instead ensuring that
					// no EditionHints are stored which have almost the same priority as others.
					// However skipping once here is a lot easier than changing the storage
					// logic across the whole class, especially considering that we would have to
					// restore the non-stored hints from the Identity's received Trusts as soon as
					// the single stored one fails to download or gets deleted due to a trust
					// change.)
					String targetIdentityID = h.getTargetIdentityID();
					if(identitiesBeingDownloaded.contains(targetIdentityID))
						continue;
					
					try {
						download(h);
						identitiesBeingDownloaded.add(targetIdentityID);
						if(--downloadsToSchedule <= 0)
							break;
					} catch(FetchException e) {
						Logger.error(this, "FetchException for: " + h, e);
					}
					
					if(currentThread().isInterrupted()) {
						Logger.normal(this, "run(): Received interrupt, aborting.");
						break;
					}
				}
			} catch(RuntimeException | Error e) {
				// Not necessary as we don't write anything to the database
				/* Persistent.checkedRollback(mDB, this, e); */
				
				Logger.error(this, "Error in run()! Retrying later...", e);
				mDownloadSchedulerThread.triggerExecution(QUEUE_BATCHING_DELAY_MS);
			} finally {
				assert(getRunningDownloadCount() <= getMaxRunningDownloadCount());
				// TODO: Performance: Remove this after it has not failed for some time.
				assert(getRunningDownloadCount() <= getQueue().size());
				
				if(logMINOR) Logger.minor(this, "run() finished.");
			}
		}
		}
	}

	/** You must synchronize upon {@link #mLock} when using this! */
	private int getRunningDownloadCount() {
		return mDownloads.size();
	}

	/**
	 * Number of SSK requests for USK {@link EditionHint}s which this downloader will do in
	 * parallel.
	 * 
	 * TODO: Performance / Code quality:
	 * - Instead of using a fixed value ask the fred load management code how many SSK requests
	 *   it can currently handle.
	 * - or at least make this configurable on the WoT web interface.
	 * - or use a dynamic limit as suggested at https://bugs.freenetproject.org/view.php?id=7117 */
	public int getMaxRunningDownloadCount() {
		return DOWNLOAD_AMOUNT;
	}

	/** @see #DOWNLOAD_REFILL_THRESHOLD */
	public int getMinRunningDownloadCount() {
		int maxDownloads = getMaxRunningDownloadCount();
		
		if(maxDownloads == 0)
			return 0;
		
		// Because maxDownloads can be configured by the user it is possible that they configure it
		// so low that integer division by the threshold results in 0. Thus return at least 1.
		return max(1, maxDownloads / DOWNLOAD_REFILL_THRESHOLD);
	}

	/** Must be called while synchronized on {@link #mLock}
	 *  = {@link WebOfTrust#getIdentityDownloaderController()} */
	public boolean isDownloadInProgress(EditionHint h) {
		return mDownloads.containsKey(h.getURI());
	}

	/** True if this class would accept {@link EditionHint}s which the given Identity has
	 *  propagated into the queue of hints to download.
	 *  Those are hints where:
	 *  - {@link EditionHint#getSourceIdentity()} == the given Identity
	 *  - and the given Identity is eligible for download not just by us but in WoT's global terms
	 *    for all IdentityDownloader implementations according to
	 *    {@link WebOfTrust#shouldFetchIdentity(Identity)} (if it weren't eligible then its hints
	 *    should not be possible to be obtain anyway).
	 *  - and the given Identity has sufficient capacity as compared to
	 *    {@link EditionHint#MIN_CAPACITY}.
	 *  
	 *  TODO: Code quality: Has been added after most of the class had been written already. Its
	 *  code is thus probably duplicated across the class and should be replaced by calls to this.
	 *  TODO: Code quality: Add a version of this which only consumes a capacity and use it in
	 *  XMLTransformer instead of hardcoding the logic there. (It also won't be necessary there to
	 *  check shouldFetchIdentity() because shouldFetchIdentity() will be true for all identities
	 *  which XMLTransformer obtains hints of because XML is only downloaded for precisely those
	 *  Identities for which shouldFetchIdentity() is true.)
	 *  
	 *  Must be called while synchronized on {@link #mWoT}. */
	private boolean shouldAcceptHintsOf(Identity i) {
		boolean hasEnoughCapacity;
		try {
			hasEnoughCapacity = mWoT.getBestCapacity(i) >= EditionHint.MIN_CAPACITY;
		} catch (NotInTrustTreeException e) {
			hasEnoughCapacity = false;
		}
		
		if(hasEnoughCapacity) {
			// No need to check mWoT.shouldFetchIdentity(i) to return false if it is false:
			// We hereby only return true if the capacity is > 0, and if an Identity has
			// capacity > 0 then shouldFetchIdentity() will always be true.
			// FIXME: It is dangerous to only assert() that it will stay like this because both the
			// JavaDoc of this function as well as the related JavaDoc at class level promises that
			// the function does check shouldFetchIdentity(). Either do check it or remove those
			// promises.
			assert(EditionHint.MIN_CAPACITY > 0);
			assert(mWoT.shouldFetchIdentity(i));
			return true;
		} else
			return false;
	
	}

	/** True if this class would accept {@link EditionHint}s which have this Identity as
	 *  {@link EditionHint#getTargetIdentity()} into its queue of hints to download.
	 *  
	 *  TODO: Code quality: Has been added after most of the class had been written already. Its
	 *  code is thus probably duplicated across the class and should be replaced by calls to this.
	 *  
	 *  Must be called while synchronized on {@link #mWoT}. */
	private boolean shouldDownload(Identity i) {
		return mWoT.shouldFetchIdentity(i);
	}

	/**
	 * Must be called while synchronized on {@link #mLock}.
	 * Must not be called if {@link #isDownloadInProgress(EditionHint)} == true.*/
	private void download(EditionHint h) throws FetchException {
		assert(!isDownloadInProgress(h));
		
		FreenetURI fetchURI = h.getURI();
		assert(fetchURI.isSSK());
		
		FetchContext fetchContext = mHighLevelSimpleClient.getFetchContext();
		// Because archives can become huge and WoT does not use them, we should disallow them.
		// See JavaDoc of the variable.
		fetchContext.maxArchiveLevels = 0;
		// EditionHints can maliciously point to inexistent editions so we only do one download
		// attempt. (The retry-count does not include the first attempt.)
		fetchContext.maxSplitfileBlockRetries = 0;
		fetchContext.maxNonSplitfileRetries = 0;
		fetchContext.maxOutputLength = XMLTransformer.MAX_IDENTITY_XML_BYTE_SIZE;
		short fetchPriority = DOWNLOAD_PRIORITY;
		
		if(logMINOR)
			Logger.minor(this, "Downloading: " + fetchURI + " from: " + h);
		
		ClientGetter getter = mHighLevelSimpleClient.fetch(
			fetchURI,
			fetchContext.maxOutputLength,
			this,
			fetchContext,
			fetchPriority);
		
		ClientGetter g = mDownloads.put(fetchURI, getter);
		assert(g == null);
	}

	/** @return {@link WebOfTrust#getRequestClient()} */
	@Override public RequestClient getRequestClient() {
		return mRequestClient;
	}

	@Override public void onSuccess(FetchResult result, ClientGetter state) {
		FreenetURI uri = null;
		Bucket bucket = null;
		InputStream inputStream = null;
		
		try {
			uri = state.getURI();
			
			if(logMINOR)
				Logger.minor(this, "onSuccess(): Downloaded Identity: " + uri);
			
			bucket = result.asBucket();
			inputStream = bucket.getInputStream();
			
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
			mOutputQueue.add(new IdentityFileStream(uri, inputStream));
			
			// FIXME: This deleteEditionHints() breaks the convention of not relying on any disk
			// storage to be reliable except the database in order to keep the database consistent
			// = it breaks the ACID properties of the database:
			// If the IdentityFileDiskQueue gets its files deleted after the call (e.g. by the user
			// or power loss) then we won't try downloading the hints again even though we should
			// because the files aren't available for import anymore. This also causes
			// WebOfTrust.db4o to not be self-contained anymore, our defrag code and users would
			// also have to copy the queue directory for backup purposes.
			// There are two potential fixes:
			// 1. Ensure the IdentityFileDiskQueue does fsync before returning from the above add().
			//    This doesn't fix the backup code though and is still not 100% theoretically
			//    precise because it doesn't take account for other types of transactional
			//    decoherence between the database and the IdentityFileQueue besides the already
			//    mentioned ones, e.g. filesystem corruption which causes the queue directory to be
			//    lost *after* the fsync. (Notice that saying "But the filesystem could also cause
			//    corruption in the database file!" is not an argument against this: The database
			//    implementation can contain checksums and other mechanisms to ensure the ACID
			//    properties of transactions. Outside files such as our output queue which aren't
			//    visible to the database code cannot be guarded by the database code.)
			//    It also wouldn't work with IdentityFileMemoryQueue.
			//    Further fsync is an expensive operation as it prevents write caching.
			// 2. Don't do the deleteEditionHints() here but when actually importing the
			//    IdentityFile from the IdentityFileQueue. This can be done by adding a new callback
			//    onIdentityEditionChanged() to IdentityDownloader which is called by WoT upon
			//    import and whose implementation in this class then looks at the new value of
			//    Identity.getLastFetchedEdition() to call deleteEditionHints() accordingly (make
			//    sure to add and use a special version of it which it doesn't commit(), the
			//    callback will likely be part of a transaction which is started by
			//    IdentityFileProcessor and ought to be committed by it!).
			//    To prevent the mDownloadSchedulerThread from starting a download for the same
			//    edition again while it is still queued for processing you must add code to it
			//    which before starting a download checks the mOutputQueue for whether the edition
			//    (or a higher one!) is queue there already. (Alternatively the delay for which
			//    IdentityFileProcessor waits after mOutputQueue.add() before processing the queue
			//    could be made sufficiently smaller as compared to the delay of the below
			//    mDownloadSchedulerThread.triggerExecution() but in practice that race condition
			//    could very often result in duplicate downloads.)
			//    Further advantages beyond ACID of this approach would be:
			//    - it will also allow the IdentityDownloaderFast to notice when the
			//      IdentityDownloaderSlow has found a new edition, which is a pending FIXME of this
			//      class already anyway: We must implement this somehow so the other downloader
			//      doesn't unnecessarily duplicate our efforts.
			//    - it allows the same in reverse: Processing of editions which the
			//      IdentityDownloaderFast has downloaded will cause the onIdentityEdtionChanged()
			//      callback to be called upon this class here which allows it to
			//      deleteEditionHints() upon the hints older than the processed edition.
			//    - it fixes this function to obey the aforementioned concept of not requiring the
			//      mLock.
			//    - Duplicate checking of IdentityDownloaderSlow then also happens against the
			//      files queued for processing, not just against the hints queued for downloading.
			//      This prevents usage of outdated hints which could be stored after we've done
			//      deleteEditionHints() here because the XMLTransformer will import any hints which
			//      seem fresh as compared to the edition the target Identity has stored in the
			//      database and it will take some time for its edition in the database to match
			//      what we've just downloaded because IdentityFile processing usually is much
			//      slower than downloading.
			// Thereby I would prefer the latter approach to be implemented.
			// When doing so please consider recycling this FIXME into documentation: Don't remove
			// the deleteEditionHints() call but comment it out, with the recycled FIXME explaining
			// why it is commented out.
			// Also adapt the class-level JavaDoc "Once an edition of a given targetIdentity is
			// fetched [...]" then.
			deleteEditionHints(uri, true, null);
		} catch (IOException | Error | RuntimeException e) {
			Logger.error(this, "onSuccess(): Failed for URI: " + uri, e);
		} finally {
			Closer.close(inputStream);
			Closer.close(bucket);
			
			synchronized(mLock) {
				ClientGetter removed = (uri != null ? mDownloads.remove(uri) : null);
				assert(state == removed);
				
				if(mDownloads.size() < getMinRunningDownloadCount())
					mDownloadSchedulerThread.triggerExecution(0);
			}
		}
	}

	@Override public void onFailure(FetchException e, ClientGetter state) {
		FreenetURI uri = null;
		
		try {
			uri = state.getURI();
			
			if(e.isDNF()) {
				Logger.warning(this,
					"Download failed with DataNotFound, bogus EditionHint? URI: " + uri);
				
				// Someone gave us a fake EditionHint to an edition which doesn't actually exist
				// -> Doesn't make sense to retry.
				deleteEditionHints(uri, false, e);
				
				// FIXME: Punish the publisher of the bogus hint
			} else if(e.getMode() == FetchExceptionMode.CANCELLED) {
				if(logMINOR)
					Logger.minor(this, "Download cancelled: " + uri);
					
				// This happening is a normal part of terminate()ing -> Don't delete the hint
			} else if(e.isDefinitelyFatal()) {
				Logger.error(this, "Download failed fatally, possibly due to corrupt remote data: "
					+ uri, e);
				
				// isDefinitelyFatal() includes post-download problems such as errors in the archive
				// metadata so we must delete the hint to ensure it doesn't clog the download queue.
				deleteEditionHints(uri, false, e);
			} else if(e.isFatal()) {
				Logger.error(this, "Download failed fatally: " + uri, e);
				
				// isFatal() includes temporary problems such as running out of disk space.
				// Thus don't delete the hint so we can try downloading it again. 
				
				synchronized(mLock) {
					++mFailedTemporarilyDownloads;
				}
			} else {
				Logger.warning(this, "Download failed non-fatally: " + uri, e);
				
				// What remains here is problems such as lack of network connectivity so we must
				// not delete the hint as retrying will likely work.
				
				synchronized(mLock) {
					++mFailedTemporarilyDownloads;
				}
			}
		} catch(Error | RuntimeException e2) {
			Logger.error(this, "onFailure(): Double fault for: " + uri, e2);
		} finally {
			synchronized(mLock) {
				ClientGetter removed = (uri != null ? mDownloads.remove(uri) : null);
				assert(state == removed);
				
				// Schedule new downloads. Intentionally don't not do that if the failure mode is
				// CANCELLED, i.e. if we are shutting down:
				// The shutdown function stop() will disable mDownloadSchedulerThread anyway,
				// so we don't need to introduce additional check here which would get executed
				// during the whole uptime only for being useful for a finite amount of executions
				// during shutdown.
				if(mDownloads.size() < getMinRunningDownloadCount())
					mDownloadSchedulerThread.triggerExecution(0);
			}
		}
	}

	/**
	 * If downloadSucceeded == false, deletes all {@link EditionHint}s with:
	 *      EditionHint.getTargetIdentity() == WebOfTrust.getIdentityByURI(uri)
	 *   && EditionHint.getEdition() == uri.getEdition()
	 * and increments {@link #mFailedPermanentlyDownloads}.
	 * If {@link FetchException#isDNF()} applies to failureReason, {@link #mDataNotFoundDownloads}
	 * is also incremented.
	 * 
	 * If downloadSucceeded == true, deletes all {@link EditionHint}s with:
	 *      EditionHint.getTargetIdentity() == WebOfTrust.getIdentityByURI(uri)
	 *   && EditionHint.getEdition() <= uri.getEdition() 
	 * and increments {@link #mSucceededDownloads} and increases {@link #mSkippedDownloads}
	 * accordingly.
	 */
	private void deleteEditionHints(
			FreenetURI uri, boolean downloadSucceeded, FetchException failureReason) {
		
		assert(downloadSucceeded ? failureReason == null : failureReason != null);
		
		synchronized(mWoT) {
		synchronized(mLock) {
		synchronized(Persistent.transactionLock(mDB)) {
			try {
				Identity i = null;
				try {
					i = mWoT.getIdentityByURI(uri);
				} catch (UnknownIdentityException e) {
					Logger.warning(this,
						"deleteEditionHints() called for Identity which doesn't exist anymore!", e);
					
					// No need to delete anything or throw an exception:
					// The hints will already have been deleted when the Identity was deleted.
					
					// The Identity having been deleted means it was distrusted which means we don't
					// want to download it anymore so there's no point in counting the attempt.
					// However that only applies to failed attempts:
					// Succeeded attempts will already have resulted in our caller onSuccess()
					// having enqueued an IdentityFile for processing at the IdentityFileQueue.
					// It would be confusing for readers of its statistics if there were more files
					// enqueued than the IdentityDownloaderSlow statistics report as having been
					// downloaded.
					if(downloadSucceeded)
						++mSucceededDownloads;
					/*
					else {
						++mFailedPermanentlyDownloads;
						if(failureReason.isDNF())
							++mDataNotFoundDownloads;
					}
					*/
					
					return;
				}
				
				long edition = uri.getEdition();
				
				if(logMINOR) {
					Logger.minor(this,
						"deleteEditionHints() for edition" + (downloadSucceeded ? " <= " : " == ") 
						+ edition + " of " + i + " ...");
				}
				
				// FIXME: We potentially store multiple EditionHints for each edition, thus this
				// counter is a wrong input for our purpose of increasing mSkippedDownloads:
				// Even if we store multiple hints for an edition we will only try downloading it
				// once as on the first successful download this function here will delete the
				// other hints pointing to it. Thus exclude the duplicates in this counter.
				int deleted = 0;
				for(EditionHint h: getEditionHints(i, edition, downloadSucceeded)) {
					if(logMINOR)
						Logger.minor(this, "deleteEditionHints(): Deleting " + h);
					h.deleteWithoutCommit();
					++deleted;
				}
				assert(deleted >= 1);
				
				Persistent.checkedCommit(mDB, this);
				// The member variables must be changed after the commit() as this class isn't
				// stored in the database and thus they won't be rolled back upon failure of the
				// transaction.
				// FIXME: Some of the statistics counters should be changed by onSuccess() /
				// onFailure() instead probably, but take the above comment w.r.t. to transactions
				// into consideration first.
				if(downloadSucceeded) {
					++mSucceededDownloads;
					if(deleted > 1)
						mSkippedDownloads += deleted - 1;
				} else {
					++mFailedPermanentlyDownloads;
					if(failureReason.isDNF())
						++mDataNotFoundDownloads;
				}
			} catch(RuntimeException e) {
				Persistent.checkedRollbackAndThrow(mDB, this, e);
			} finally {
				if(logMINOR)
					Logger.minor(this, "deleteEditionHints() finished.");
			}
		}
		}
		}
	}

	@Override public void onResume(ClientContext context) throws ResumeFailedException {
		throw new UnsupportedOperationException(
			"onResume() called even though WoT doesn't do persistent requests?");
	}

	@Override public int getPriority() {
		return DOWNLOADER_THREAD_PRIORITY;
	}

	/**
	 * In addition to the purpose as specified by the parent interface this is also safe to use
	 * for the purpose of handling
	 * {@link IdentityDownloader#storePostRestoreOwnIdentityCommand(OwnIdentity)}.
	 * 
	 * Using this function for that purpose is handled by this class itself at
	 * {@link #storePostRestoreOwnIdentityCommand(OwnIdentity)}, there is no need for outside
	 * classes to call it for that purpose directly.
	 * 
	 * FIXME: There's other local stuff which uses this function as well, document that like
	 * the above documentation. Also review IdentityDownloaderFast for similar lack of doc. */
	@Override public void storeStartFetchCommandWithoutCommit(final Identity identity) {
		Logger.normal(this, "storeStartFetchCommandWithoutCommit(" + identity + ") ...");
		
		// FIXME: Performance: Remove these two debug loops, they're covered by assert()s in run().
		// Keeping them for now as I think I added them to debug a specific bug which I have
		// encountered but not yet fixed probably.
		
		for(EditionHint h : getEditionHintsBySourceIdentity(identity)) {
			Logger.error(this, "Hint found for previously untrusted Identity: " + h,
				new RuntimeException("Exception for stack trace only"));
			assert(false);
			h.deleteWithoutCommit();
		}
		
		for(EditionHint h : getEditionHintsByTargetIdentity(identity)) {
			Logger.error(this, "Hint found for previously untrusted Identity: " + h,
				new RuntimeException("Exception for stack trace only"));
			assert(false);
			h.deleteWithoutCommit();
		}
		
		for(Trust trust : mWoT.getReceivedTrusts(identity)) {
			final long editionHint = trust.getTrusteeEdition();
			if(editionHint < 0 || editionHint <= identity.getLastFetchedEdition())
				continue;
			
			final Identity truster = trust.getTruster();
			int trusterCapacity;
			
			try {
				trusterCapacity = mWoT.getBestCapacity(truster);
			} catch(NotInTrustTreeException e) { 
				trusterCapacity = 0;
			}
			
			if(trusterCapacity < EditionHint.MIN_CAPACITY)
				continue;
			
			int trusterScore;
			try {
				trusterScore = mWoT.getBestScore(truster);
			} catch(NotInTrustTreeException e) {
				assert(EditionHint.MIN_CAPACITY > 0);
				// This shouldn't happen as per the WoT Score computation rules:
				// If the trusterCapacity was > 0, then the truster must have had a score.
				// Thus it is an error in Score computation.
				throw new RuntimeException(
					"Illegal State: trusterCapacity > 0: " + trusterCapacity +
					" - but truster has no Score. truster: " + truster);
			}
			
			// We're using data from our database, not from the network, so we can use *Insecure().
			EditionHint h = EditionHint.constructInsecure(
				mWoT,
				truster,
				identity,
				/* FIXME: Transfer across the network? If yes, validate before storing at Trust
				 * or use constructSecure() above. */
				trust.getDateOfLastChange(),
				trusterCapacity,
				trusterScore,
				editionHint);

			// FIXME: This will likely fail in the case of this function having been called merely
			// to tell us that markForRefetch() was called upon the passed identity, i.e. if the
			// Identity *was* already eligible for fetching before the call.
			// This also applies to the similar assertDidThrow() below in this function.
			//
			// Further, in the case of this function having been called by
			// storePostRestoreOwnIdentityCommand() the EditionHint may have also already been
			// created because, as specified for that callback at IdentityDownloader,
			// restoreOwnIdentity() might have called this function here for the trustees of the
			// OwnIdentity. One of them might have provided the EditionHint we're trying to store
			// here.
			assertDidThrow(new Callable<EditionHint>() {
				@Override public EditionHint call() throws Exception {
					return getEditionHint(truster, identity);
				}
			}, UnknownEditionHintException.class);
			
			h.storeWithoutCommit();
			++mTotalQueuedDownloadsInSession;
			
			if(logMINOR)
				Logger.minor(this, "Created EditionHint from Trust database: " + h);
		}
		
		// No need to do the below: We do not store the given Trusts of an Identity while it is 
		// not considered as trustworthy. Thus whenever the "is the trust list, and thus the edition
		// hints, of the identity eligible for import?"-state of an Identity changes from false to
		// true (which is also when this handler here is called), WoT calls
		// Identity.markForRefetch() on the Identity. That will cause the trust list of the identity
		// to be downloaded again, which causes the EditionHints to be imported from it later on.
		// FIXME: Make the parent interface specification of this function document that it can
		// rely on this behavior.
		/*
		for(Trust trust : mWoT.getGivenTrusts(identity)) {
			... create EditionHints for trustees ... 
		}
		*/
		
		// When this is called by storePostRestoreOwnIdentityCommand() the given Identity is the
		// restored OwnIdentity. The user may have provided an edition number in the URI of the
		// OwnIdentity which is stored at identity.getNextEditionToFetch(). Thus we should create an
		// EditionHint from that.
		if(identity instanceof OwnIdentity) {
			int sourceCapacity;
			int sourceScore;
			try {
				Score selfScore = mWoT.getScore((OwnIdentity)identity, identity);
				
				sourceCapacity = selfScore.getCapacity();
				sourceScore = selfScore.getValue();
			} catch (NotInTrustTreeException e) {
				// Non self-trusting OwnIdentitys aren't used by the codebase yet but may be used
				// in the future for purposes such as temporarily disabling an OwnIdentity, so
				// we will obey that by not causing a download if it doesn't trust itself.
				// Log a warning nevertheless so whoever implements the usage of not having a
				// self-assigned Score will notice the places in the code to review for whether they
				// handle it as desired for whatever purpose is then chosen for it.
				Logger.warning(this, "No self-assigned Score found for OwnIdentity: " + identity,
					new RuntimeException("Exception not thrown, for stack trace only."));
				
				sourceCapacity = 0;
				assert(0 < EditionHint.MIN_CAPACITY);
				sourceScore = Integer.MIN_VALUE; // Just to prevent uninitialized variable error.
			}
			
			if(sourceCapacity > EditionHint.MIN_CAPACITY) {
				// Don't use identity.getLastChangeDate() because if the restored OwnIdentity
				// replaced a non-own Identity it may be far in the past - the time is the most
				// significant factor in the download priority EditionHint.getPriority() so using
				// the current time is a good idea:
				// Restoring an OwnIdentity is something which the user will like to be fast.
				// Also factually it is correct to use the current time as the user did just provide
				// the hint to us by using restoreOwnIdentity().
				Date hintDate = CurrentTimeUTC.get();
				
				EditionHint h = EditionHint.constructInsecure(mWoT, identity, identity, hintDate,
					sourceCapacity, sourceScore, identity.getNextEditionToFetch());
				
				AssertUtil.assertDidThrow(new Callable<EditionHint>() {
					@Override public EditionHint call() throws Exception {
						return getEditionHint(identity, identity);
					}
				}, UnknownEditionHintException.class);
				
				h.storeWithoutCommit();
				++mTotalQueuedDownloadsInSession;
				
				if(logMINOR) {
					Logger.minor(this,
						"Created EditionHint from OwnIdentity.getNextEditionToFetch(): " + h);
				}
			}
		}
		
		mDownloadSchedulerThread.triggerExecution();
		
		Logger.normal(this, "storeStartFetchCommandWithoutCommit() finished.");
	}

	/**
	 * In addition to the purpose as specified by the parent interface this is also safe to use
	 * for the purpose of preparing the deletion of the given Identity:
	 * It deletes all object references to the Identity in the db4o database table of this
	 * class, i.e. it deletes all {@link EditionHint} objects which point to it by
	 * {@link EditionHint#getSourceIdentity()} or {@link EditionHint#getTargetIdentity()}.
	 * 
	 * Using this function for that purpose is handled by this class itself at
	 * {@link #storePreDeleteOwnIdentityCommand(OwnIdentity)},
	 * {@link #storePreDeleteIdentityCommand(Identity)} and
	 * {@link #storePreRestoreOwnIdentityCommand(Identity)}, there is no need for outside classes to
	 * call it for that purpose directly. */
	@Override public void storeAbortFetchCommandWithoutCommit(Identity identity) {
		Logger.normal(this, "storeAbortFetchCommandWithoutCommit(" + identity + ") ...");
		
		boolean wasQueuedForDownload = false;
		// Remove entries for the Identity from the hint download queue
		for(EditionHint h : getEditionHintsByTargetIdentity(identity)) {
			if(logMINOR)
				Logger.minor(this, "storeAbortFetchCommandWithoutCommit(): Deleting " + h);
			
			h.deleteWithoutCommit();
			// We realized we don't actually need to download the Identity, so the statistics which
			// serve as progress indicator for the UI - comparing getQueue.size() to
			// mTotalQueuedDownloadsInSession - shouldn't include the deleted hints.
			// (There is no variable to update for getQueue.size(), it is computed from a database
			// query.)
			--mTotalQueuedDownloadsInSession;
			
			wasQueuedForDownload = true;
		}
		
		// We now cancel any running downloads of hints about the identity.
		// In theory we shouldn't be doing that because we're inside a transaction which isn't
		// committed yet and may be rolled back if anything we do afterwards throws: We would then
		// have wrongly canceled requests which should still be running as canceling requests at
		// fred isn't transactional and thus not rolled back.
		// But luckily this doesn't matter:
		// We call mDownloadSchedulerThread.triggerExecution() to start new downloads on a different
		// thread of its own, and thus with a transaction of its own, and that thread hence can only
		// execute once this transaction here is finished as we hold the necessary locks currently.
		// If this transaction gets rolled back, the download scheduler thread will see the old,
		// pre-rollback download queue which includes the downloads we wrongly cancelled and will
		// just restart them.
		if(wasQueuedForDownload) {
			FreenetURI identityURI = identity.getRequestURI();
			// To prevent concurrent modification of mDownloads while we iterate over it we must
			// copy it: While we iterate over it we want to call ClientGetter.cancel() on some
			// entries, but that will call this.onFailure() on the same thread, which will remove
			// the relevant entries from mDownloads.
			HashMap<FreenetURI, ClientGetter> downloads = new HashMap<>(mDownloads);
			boolean downloadSchedulingTriggered = false;
			for(Entry<FreenetURI, ClientGetter> download : downloads.entrySet()) {
				if(!download.getKey().equalsKeypair(identityURI))
					continue;
				
				if(logMINOR) {
					Logger.minor(this,
						"storeAbortFetchCommandWithoutCommit(): Cancelling download: "
							+ download.getKey());
				}
				
				if(!downloadSchedulingTriggered) {
					// Must be called before cancel() to ensure the aforementioned assumption about
					// transaction rollback applies!
					mDownloadSchedulerThread.triggerExecution();
					downloadSchedulingTriggered = true;
				}

				download.getValue().cancel(mClientContext);
			}
		}
		
		// Because the Identity isn't trustworthy enough to be fetched anymore we cannot trust
		// its given hints either and thus must delete them.
		// Technically their amount is constant, i.e. O(max number of trusts per Identity), so we
		// would only risk a constant amount of bogus fetches if we didn't do this - but this
		// function is also used for the purpose of handling deletion of an identity so we must
		// delete all object references to it in the db4o database by deleting all its given hints.
		// FIXME: Also cancel the downloads of those hints.
		for(EditionHint h : getEditionHintsBySourceIdentity(identity)) {
			if(logMINOR)
				Logger.minor(this, "storeAbortFetchCommandWithoutCommit(): Deleting " + h);
			
			h.deleteWithoutCommit();
			--mTotalQueuedDownloadsInSession;
		}

		Logger.normal(this, "storeAbortFetchCommandWithoutCommit() finished");
	}

	@Override public void storePreDeleteOwnIdentityCommand(OwnIdentity oldIdentity) {
		// Both stops the download of the Identity by deleting all EditionHint objects it has
		// received as well as deleting all EditionHint objects it has given to other Identitys.
		// Thus complies with our job of deleting all objects in the db4o database which point to
		// the oldIdentity and those induced by the Scores it has given (in our case only by the
		// given Trusts as we don't store anything due to Scores, but Scores are a consequence of
		// Trusts and thus are also dealt with implicitly).
		storeAbortFetchCommandWithoutCommit(oldIdentity);
	}

	@Override public void storePostDeleteOwnIdentityCommand(Identity newIdentity) {
		if(mWoT.shouldFetchIdentity(newIdentity)) {
			// Enqueues EditionHints for the Identity from its received Trusts
			storeStartFetchCommandWithoutCommit(newIdentity);
			// No need to enqueue EditionHints for other Identitys from the given Trusts of the
			// newIdentity: An OwnIdentity only updates its edition hint when an edition of a remote
			// identity is actually downloaded. The Identity was previously an OwnIdentity which
			// means that its hinted editions have all been downloaded locally already and thus
			// they won't be useful.
			// FIXME: This might not apply to seed identities?
		} else {
			// No need to create any EditionHints: An Identity which is not trustworthy enough for
			// download is also not trustworthy enough for accepting its hints (and we'd have to
			// download it to obtain the hints).
		}
	}

	@Override public void storePreDeleteIdentityCommand(Identity oldIdentity) {
		if(oldIdentity instanceof OwnIdentity) {
			storePreDeleteOwnIdentityCommand((OwnIdentity)oldIdentity);
		} else {
			// Both stops the download of the Identity by deleting all EditionHint objects it has
			// received as well as deleting all EditionHint objects it has given to other Identitys.
			// Thus complies with our job of deleting all objects in the db4o database which point
			// to the oldIdentity and those induced by Trusts/Scores it has given/received (in our
			// case only by the Trusts as we don't store anything due to Scores, but Scores are a
			// consequence of Trusts and thus are also dealt with implicitly).
			storeAbortFetchCommandWithoutCommit(oldIdentity);
		}
	}

	@Override public void storePreRestoreOwnIdentityCommand(Identity oldIdentity) {
		// Deletes all EditionHint objects it has received or given to other Identitys.
		// Thus complies with our job of deleting all objects in the db4o database which point to
		// the oldIdentity, as well as with aborting the download of it.
		storeAbortFetchCommandWithoutCommit(oldIdentity);
	}

	@Override public void storePostRestoreOwnIdentityCommand(OwnIdentity newIdentity) {
		// Enqueues EditionHints for the OwnIdentity from its received Trusts.
		// Also deals with storing an EditionHint for the newIdentity.getNextEditionToFetch() as
		// that was potentially supplied by the user.
		storeStartFetchCommandWithoutCommit(newIdentity);
	}

	/** This callback is not used by this class. */
	@Override public void storeTrustChangedCommandWithoutCommit(Trust oldTrust, Trust newTrust) {
	}

	@Override public void storeNewEditionHintCommandWithoutCommit(EditionHint newHint) {
		if(logMINOR)
			Logger.minor(this, "storeNewEditionHintCommandWithoutCommit()...");
		
		try {
		Identity target = newHint.getTargetIdentity();

		if(target.getLastFetchedEdition() >= newHint.getEdition()) {
			if(logMINOR)
				Logger.minor(this, "EditionHint is obsolete, ignoring: " + newHint);
			return;
		}

		// XMLTransformer, our typical caller, does checks whether the source Identity is
		// allowed to store hints and doesn't call us if it is not - but it doesn't check
		// whether the target Identity should be fetched so we must do that on our own.
		// (This is because the primary validation job of the XMLTransformer is to decide
		// whether it should accept input of the Identity which was downloaded - interpreting
		// the input isn't its job, and deciding whether to download the target is
		// interpretation.)
		assert(shouldAcceptHintsOf(newHint.getSourceIdentity()));
		if(!shouldDownload(target)) {
			if(logMINOR)
				Logger.minor(this, "EditionHint has non-trusted target, ignoring: " + newHint);
			return;
		}
		
		try {
			EditionHint oldHint = getEditionHintByID(newHint.getID());
			assert(oldHint.getSourceIdentity() == newHint.getSourceIdentity());
			assert(oldHint.getTargetIdentity() == newHint.getTargetIdentity());
			
			long oldEdition = oldHint.getEdition();
			long newEdition = newHint.getEdition();
			
			if(newEdition < oldEdition) {
				// FIXME: Track this in a counter and punish hint publishers if they do it too often
				// EDIT: It probably can happen due to Identity.markForRefetch() having been called
				// multiple times at our remote peers.
				Logger.warning(this, "Received EditionHint older than current hint, ignoring:");
				Logger.warning(this, "oldHint: " + oldHint);
				Logger.warning(this, "newHint: " + newHint);
				return;
			} else if(newEdition == oldEdition) {
				if(logMINOR)
					Logger.minor(this, "EditionHint hasn't changed, ignoring: " + newHint);
				
				return;
			}
			
			if(logMINOR)
				Logger.minor(this, "Deleting old EditionHint: " + oldHint);
			
			oldHint.deleteWithoutCommit();
			++mSkippedDownloads;
		} catch(UnknownEditionHintException e) {}
		
		if(logMINOR)
			Logger.minor(this, "Storing new EditionHint: " + newHint);
		
		newHint.storeWithoutCommit();
		++mTotalQueuedDownloadsInSession;
		
		mDownloadSchedulerThread.triggerExecution();
		
		} finally {
			if(logMINOR)
				Logger.minor(this, "storeNewEditionHintCommandWithoutCommit() finished.");
		}
	}

	@Override public boolean getShouldFetchState(Identity identity) {
		if(getEditionHintsByTargetIdentity(identity).size() > 0)
			return true;
		
		// We don't explicitly keep track of which identities are *not* wanted, instead
		// storeNewEditionHintCommandWithoutCommit() will do a "shouldFetchIdentity()" check
		// whenever it is called, so we just do it here as well:
		// FIXME: This function is supposed to be called during Score computation. Thus the Score
		// database isn't valid and we cannot use shouldFetchIdentity()!
		return mWoT.shouldFetchIdentity(identity);
	}

	@Override public void deleteAllCommands() {
		synchronized(mWoT) {
		synchronized(mLock) {
		synchronized(Persistent.transactionLock(mDB)) {
			try {
				Logger.warning(this, "deleteAllCommands() (NOTICE: Use only for debugging!)...");
				
				// getQueue() is a better input than getAllEditionHints():
				// getQueue() is the central input for run() to decide what to download().
				// If deletion of all hints returned by getQueue() results in a database leak
				// (remember: we're being called to check for leaks), then what was leaked is
				// likely something which getQueue() failed to supply to the downloading code
				// - and such bugs are the most important to fix as downloading everything we're
				// supposed to download is our main job.
				int deleted = 0;
				for(EditionHint h: getQueue()) {
					if(logMINOR)
						Logger.minor(this, "deleteAllCommands(): Deleting " + h);
					h.deleteWithoutCommit();
					++deleted;
				}
				
				// It doesn't make much sense to try to keep the statistics up to date when a debug
				// function like this one is used, but it makes sense to at least count the
				// downloads as failed to ensure developers may notice on the web interface if this
				// function is being wrongly called in a release which wasn't meant for debugging
				// only.
				mFailedPermanentlyDownloads += deleted;
				
				Persistent.checkedCommit(mDB, this);
			} catch(RuntimeException e) {
				Persistent.checkedRollbackAndThrow(mDB, this, e);
			} finally {
				if(logMINOR)
					Logger.minor(this, "deleteAllCommands() finished.");
			}
		}
		}
		}
	}

	@Override public void scheduleImmediateCommandProcessing() {
		mDownloadSchedulerThread.triggerExecution(0);
	}

	/** You must synchronize upon {@link #mWoT} and {@link #mLock} when using this! */
	private ObjectSet<EditionHint> getAllEditionHints() {
		Query q = mDB.query();
		q.constrain(EditionHint.class);
		return new InitializingObjectSet<>(mWoT, q);
	}

	/**
	 * Convenient frontend for {@link #getEditionHintByID(String)}.
	 * You must synchronize upon {@link #mWoT} and {@link #mLock} when using this! */
	private EditionHint getEditionHint(Identity sourceIdentity, Identity targetIdentity)
			throws UnknownEditionHintException {
		
		String id = new TrustID(sourceIdentity.getID(), targetIdentity.getID()).toString();
		return getEditionHintByID(id);
	}

	/** You must synchronize upon {@link #mWoT} and {@link #mLock} when using this! */
	EditionHint getEditionHintByID(String id) throws UnknownEditionHintException {
		Query query = mDB.query();
		query.constrain(EditionHint.class);
		query.descend("mID").constrain(id);
		ObjectSet<EditionHint> result = new InitializingObjectSet<>(mWoT, query);
		
		switch(result.size()) {
			case 1:
				EditionHint hint = result.next();
				assert(hint.getID().equals(id));
				return hint;
			case 0:  throw new UnknownEditionHintException(id);
			default: throw new DuplicateObjectException(id);
		}
	}

	/** You must synchronize upon {@link #mWoT} and {@link #mLock} when using this! */
	private ObjectSet<EditionHint> getEditionHintsBySourceIdentity(Identity identity) {
		Query q = mDB.query();
		q.constrain(EditionHint.class);
		q.descend("mSourceIdentity").constrain(identity).identity();
		return new InitializingObjectSet<>(mWoT, q);
	}

	/** You must synchronize upon {@link #mWoT} and {@link #mLock} when using this! */
	private ObjectSet<EditionHint> getEditionHintsByTargetIdentity(Identity identity) {
		Query q = mDB.query();
		q.constrain(EditionHint.class);
		q.descend("mTargetIdentity").constrain(identity).identity();
		return new InitializingObjectSet<>(mWoT, q);
	}

	/** You must synchronize upon {@link #mWoT} and {@link #mLock} when using this! */
	private ObjectSet<EditionHint> getEditionHints(
			Identity targetIdentity, long edition, boolean includeLowerEditions) {
		
		// TODO: Performance: Once we use a database which supports multi-field indices configure it
		// to have such an index on the fields used here.
		
		Query q = mDB.query();
		q.constrain(EditionHint.class);
		q.descend("mTargetIdentity").constrain(targetIdentity).identity();
		if(includeLowerEditions)
			q.descend("mEdition").constrain(edition).greater().not();
		else
			q.descend("mEdition").constrain(edition);
		
		return new InitializingObjectSet<>(mWoT, q);
	}

	/** You must synchronize upon {@link #mWoT} and {@link #mLock} =
	 *  = {@link WebOfTrust#getIdentityDownloaderController()} while calling this and consuming the
	 *  returned ObjectSet! */
	public ObjectSet<EditionHint> getQueue() {
		Query q = mDB.query();
		q.constrain(EditionHint.class);
		q.descend("mPriority").orderDescending();
		return new InitializingObjectSet<>(mWoT, q);
	}

	/**
	 * Executed at startup if {@link #logDEBUG} is true, i.e. if you configure a log level of
	 * "plugins.WebOfTrust.network.input.IdentityDownloaderSlow:DEBUG" on the fred web interface.
	 * 
	 * NOTICE: Checks which are OK to be executed for each single {@link EditionHint} object
	 * should be placed at {@link EditionHint#startupDatabaseIntegrityTest()}! That function is the
	 * canonical place for putting integrity tests. This one here was merely added to be able to
	 * test things which must not be executed for each single hint. */
	private void testDatabaseIntegrity() {
		Logger.debug(this, "testDatabaseIntegrity() ...");
		
		synchronized(mWoT) {
		synchronized(mLock) {
		// Check whether the download queue is sorted properly
		
		ObjectSet<EditionHint> queueSortedByDb4o = getQueue();
		ArrayList<EditionHint> queueSortedWithReferenceImpl = new ArrayList<>(getAllEditionHints());
		
		sort(queueSortedWithReferenceImpl, new Comparator<EditionHint>() {
			@Override public int compare(EditionHint h1, EditionHint h2) {
				return h1.compareTo_ReferenceImplementation(h2);
			}
		});
		
		// FIXME: This does fail in my test runs, debug the issue behind that!
		if(!queueSortedWithReferenceImpl.equals(queueSortedByDb4o)) {
			Logger.error(this, "Sorting EditionHints by mPriority returns wrong order: ");
			
			// TODO: Performance: Don't re-query this from the database once the issue which caused
			// this workaround is fixed: https://bugs.freenetproject.org/view.php?id=6646
			queueSortedByDb4o = getQueue();
			for(EditionHint h : queueSortedByDb4o)
				Logger.error(this, h.toString());
		}
		
		// Checking if the download queue only contains eligible hints is not necessary here,
		// EditionHint.startupDatabaseIntegrityTest() does that.
		}
		}
		
		Logger.debug(this, "testDatabaseIntegrity() finished.");
	}

	public final class IdentityDownloaderSlowStatistics {
		public final int mQueuedDownloads;
		
		public final int mTotalQueuedDownloadsInSession;
		
		public final int mRunningDownloads;
		
		public final int mMaxRunningDownloads;

		public final int mSucceededDownloads;

		/**
		 * When choosing which {@link EditionHint} to download first, we sort them in a "smart"
		 * order (see {@link EditionHint#compareTo(EditionHint)}) to hopefully download the latest
		 * editions first so we don't need to try to download many old editions. Once we download an
		 * edition of an Identity which is newer than some pending queued hints they will be
		 * deleted. Deleting each thus spares us a single download which is nice. */
		public final int mSkippedDownloads;

		/** E.g. lack of network connection */
		public final int mFailedTemporarilyDownloads;

		/** E.g. invalid low level file format of the downloaded data, data not found, etc. */
		public final int mFailedPermanentlyDownloads;

		/**
		 * The edition hints which led us to those downloads were fake, or so old that the
		 * data already fell out of the network.
		 * Notice that these are included in {@link #mFailedPermanentlyDownloads}. */
		public final int mDataNotFoundDownloads;


		public IdentityDownloaderSlowStatistics() {
			synchronized(IdentityDownloaderSlow.this.mWoT) {
			synchronized(IdentityDownloaderSlow.this.mLock) {
				mQueuedDownloads = getQueue().size();
				this.mTotalQueuedDownloadsInSession
					= IdentityDownloaderSlow.this.mTotalQueuedDownloadsInSession;
				mRunningDownloads = getRunningDownloadCount();
				mMaxRunningDownloads = getMaxRunningDownloadCount();
				this.mSucceededDownloads = IdentityDownloaderSlow.this.mSucceededDownloads;
				this.mSkippedDownloads = IdentityDownloaderSlow.this.mSkippedDownloads;
				this.mFailedTemporarilyDownloads
					= IdentityDownloaderSlow.this.mFailedTemporarilyDownloads;
				this.mFailedPermanentlyDownloads
					= IdentityDownloaderSlow.this.mFailedPermanentlyDownloads;
				this.mDataNotFoundDownloads = IdentityDownloaderSlow.this.mDataNotFoundDownloads;
			}
			}
		}
	}
}
