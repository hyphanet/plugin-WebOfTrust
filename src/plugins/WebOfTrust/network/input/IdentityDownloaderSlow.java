/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.network.input;

import static java.util.Collections.sort;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MINUTES;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map.Entry;
import java.util.concurrent.Callable;

import plugins.WebOfTrust.Identity;
import plugins.WebOfTrust.IdentityFile;
import plugins.WebOfTrust.IdentityFileQueue;
import plugins.WebOfTrust.IdentityFileQueue.IdentityFileStream;
import plugins.WebOfTrust.OwnIdentity;
import plugins.WebOfTrust.Persistent;
import plugins.WebOfTrust.Persistent.InitializingObjectSet;
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
import freenet.node.NodeClientCore;
import freenet.node.PrioRunnable;
import freenet.node.RequestClient;
import freenet.node.RequestStarter;
import freenet.pluginmanager.PluginRespirator;
import freenet.support.Logger;
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
 * twice.
 * 
 * Some of the storage policy of {@link EditionHint} objects:
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
 *     The complexity of this actually wouldn't be that bad s a possible future revision of this
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
 * - further event handlers of this class. */
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
	 * are likely the most expensive operation. */
	public static transient final long QUEUE_BATCHING_DELAY_MS = MINUTES.toMillis(1);

	/**
	 * Priority of network requests, relative to {@link IdentityDownloaderFast} as we use a single
	 * {@link RequestClient} for that downloader and this one.
	 * 
	 * Also see the file "developer-documentation/RequestClient and priority map.txt" */
	public static transient final short DOWNLOAD_PRIORITY
		= RequestStarter.BULK_SPLITFILE_PRIORITY_CLASS;

	/**
	 * Priority of the {@link #run()} thread which starts downloads of the edition hint queue.
	 * 
	 * Below NORM_PRIORITY because we are a background thread and not relevant to UI actions.
	 * Above MIN_PRIORITY because we're not merely a cleanup thread.
	 * -> LOW_PRIORITY is the only choice. */
	private static transient final int DOWNLOADER_THREAD_PRIORITY = NativeThread.LOW_PRIORITY;

	private final WebOfTrust mWoT;

	private final NodeClientCore mNodeClientCore;
	
	private final HighLevelSimpleClient mHighLevelSimpleClient;

	/** @see #getRequestClient() */
	private final RequestClient mRequestClient;

	private final IdentityDownloaderController mLock;
	
	private final ExtObjectContainer mDB;
	
	/** Fetched {@link IdentityFile}s are stored for processing at this {@link IdentityFileQueue}.*/
	private final IdentityFileQueue mQueue;
	
	/** FIXME: Document similarly to {@link SubscriptionManager#mJob} */
	private volatile DelayedBackgroundJob mJob = null;

	private final HashMap<FreenetURI, ClientGetter> mDownloads;

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


	public IdentityDownloaderSlow(WebOfTrust wot) {
		requireNonNull(wot);
		
		mWoT = wot;
		PluginRespirator pr = mWoT.getPluginRespirator();
		mNodeClientCore = (pr != null ? pr.getNode().clientCore : null);
		mHighLevelSimpleClient = (pr != null ? pr.getHLSimpleClient() : null);
		mRequestClient = mWoT.getRequestClient();
		mLock = mWoT.getIdentityDownloaderController();
		mDB = mWoT.getDatabase();
		mQueue = mWoT.getIdentityFileQueue();
		mDownloads = new HashMap<>(getMaxRunningDownloadCount() * 2);
	}

	@Override public void start() {
		Logger.normal(this, "start() ...");
		
		// FIXME: Implement similarly to SubscriptionManager.
		
		synchronized(mWoT) {
		synchronized(mLock) {
			if(logDEBUG)
				testDatabaseIntegrity();
			
			mTotalQueuedDownloadsInSession = getQueue().size();
		}
		}
		
		Logger.normal(this, "start() finished.");
	}

	/**
	 * The actual downloader. Starts fetches for the head of {@link #getQueue()}.
	 * 
	 * Executed on an own thread after {@link DelayedBackgroundJob#triggerExecution()} was called.
	 * 
	 * FIXME: Document similarly to {@link SubscriptionManager#run()} */
	@Override public void run() {
		if(logMINOR) Logger.minor(this, "run()...");

		synchronized(mWoT) {
		synchronized(mLock) {
			// We don't have to take a database transaction lock:
			// We don't write anything to the database here.
			
			try {
				int downloadsToSchedule = getMaxRunningDownloadCount() - getRunningDownloadCount();
				// Check whether we actually need to do something before the loop, not just inside
				// it: The getQueue() database query is much more expensive than the duplicate code.
				if(downloadsToSchedule > 0) {
					for(EditionHint h : getQueue()) {
						if(!isDownloadInProgress(h)) {
							try {
								download(h);
								if(--downloadsToSchedule <= 0)
									break;
							} catch(FetchException e) {
								Logger.error(this, "FetchException for: " + h, e);
							}
						}
					}
				}
			} catch(RuntimeException | Error e) {
				// Not necessary as we don't write anything to the database
				/* Persistent.checkedRollback(mDB, this, e); */
				
				Logger.error(this, "Error in run()!", e);
				mJob.triggerExecution(QUEUE_BATCHING_DELAY_MS);
			}
		}
		}
		
		if(logMINOR) Logger.minor(this, "run() finished.");
	}

	/** You must synchronize upon {@link #mLock} when using this! */
	private int getRunningDownloadCount() {
		return mDownloads.size();
	}

	/**
	 * Number of SSK requests for USK {@link EditionHint}s which this downloader will do in
	 * parallel.
	 * 
	 * Can be configured on the fred web interface in advanced mode at "Configuration" / "Core
	 * settings" by the value:
	 * "Maximum number of temporary  USK fetchers: ...
	 * Maximum number of temporary background fetches for recently visited USKs
	 * (e.g. freesites). Note that clients and plugins (e.g. WebOfTrust) can subscribe to USKs,
	 * which does not count towards the limit."
	 * 
	 * Defaults to 64 as of 2017-04-14.
	 * 
	 * TODO: Performance / Code quality:
	 * - Instead of using a fixed value ask the fred load management code how many SSK requests
	 *   it can currently handle.
	 * - or at least make this configurable on the WoT web interface. */
	public int getMaxRunningDownloadCount() {
		// Valid in unit tests
		if(mNodeClientCore == null) {
			Logger.warning(this,
				"getMaxRunningDownloadCount() called with mNodeClientCore == null, returning 0");
			// Without a node it is impossible to start downloads so 0 is consistent.
			return 0;
		}
		
		return mNodeClientCore.maxBackgroundUSKFetchers();
	}

	/** Must be called while synchronized on {@link #mLock}. */
	private boolean isDownloadInProgress(EditionHint h) {
		return mDownloads.containsKey(h.getURI());
	}

	/**
	 * Must be called while synchronized on {@link #mLock}.
	 * Must not be called if {@link #isDownloadInProgress(EditionHint)} == true.*/
	private void download(EditionHint h) throws FetchException {
		FreenetURI fetchURI = h.getURI();
		assert(fetchURI.isSSK());
		
		FetchContext fetchContext = mHighLevelSimpleClient.getFetchContext();
		// Because archives can become huge and WOT does not use them, we should disallow them.
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
			
			// IdentityFileStream currently does not need to be close()d so we don't store it
			mQueue.add(new IdentityFileStream(uri, inputStream));
			
			deleteEditionHints(uri, true, null);
		} catch (IOException | Error | RuntimeException e) {
			Logger.error(this, "onSuccess(): Failed for URI: " + uri, e);
		} finally {
			Closer.close(inputStream);
			Closer.close(bucket);
			
			synchronized(mLock) {
				ClientGetter removed = (uri != null ? mDownloads.remove(uri) : null);
				assert(state == removed);
			}
			
			// Wake up this.run() to schedule a new download
			// 
			// TODO: Performance: Estimate how long it will take until all downloads have finished
			// and use min(QUEUE_BATCHING_DELAY_MS, estimate) as execution delay.
			mJob.triggerExecution();
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
			}
			
			// Wake up this.run() to schedule a new download
			mJob.triggerExecution();
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

	@Override public void terminate() {
		Logger.normal(this, "terminate() ...");
		
		// FIXME: Implement similarly to SubscriptionManager.
		
		// FIXME: Terminate running ClientGetters (stored in mDownloads). See class TransferThread.
		
		Logger.normal(this, "terminate() finished.");
	}

	@Override public void storeStartFetchCommandWithoutCommit(final Identity identity) {
		// FIXME: This is called during Score computation but relies upon the results of it.
		// Change interface specification to have Score computation call it after it is finished and
		// change the implementations to do so.
		
		Logger.normal(this, "storeStartFetchCommandWithoutCommit(" + identity + ") ...");
		
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
			AssertUtil.assertDidThrow(new Callable<EditionHint>() {
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
		
		// FIXME: Add handling for the case "identity instanceof OwnIdentity", e.g. when this was
		// called by restoreOwnIdentity().
		
		// Wake up this.run() - the actual downloader 
		mJob.triggerExecution();
		
		Logger.normal(this, "storeStartFetchCommandWithoutCommit() finished.");
	}

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
		// We call mJob.triggerExecution() to schedule run() to execute to start new downloads.
		// run() will execute on a different thread with a transaction of its own, and thus can only
		// execute once this transaction here is finished. If this transaction gets rolled back,
		// run() will see the old, pre-rollback download queue which includes the downloads we
		// wrongly cancelled and will just restart them.
		if(wasQueuedForDownload) {
			FreenetURI identityURI = identity.getRequestURI();
			// To prevent concurrent modification of mDownloads while we iterate over it we must
			// copy it: While we iterate over it we want to call ClientGetter.cancel() on some
			// entries, but that will call this.onFailure() on the same thread, which will remove
			// the relevant entries from mDownloads.
			HashMap<FreenetURI, ClientGetter> downloads = new HashMap<>(mDownloads);
			for(Entry<FreenetURI, ClientGetter> download : downloads.entrySet()) {
				if(!download.getKey().equalsKeypair(identityURI))
					continue;
				
				if(logMINOR) {
					Logger.minor(this,
						"storeAbortFetchCommandWithoutCommit(): Cancelling download: "
							+ download.getKey());
				}

				// Schedule the download queue processing thread to start more downloads.
				// Must be called before cancel() to ensure the aforementioned assumption about
				// transaction rollback applies.
				mJob.triggerExecution();

				download.getValue().cancel(mNodeClientCore.clientContext);
			}
		}
		
		// Also because the Identity isn't trustworthy enough to be fetched anymore we cannot trust
		// its hints either and thus must delete them.
		// Technically their amount is constant, i.e. O(max number of trusts per Identity), so we
		// would only risk a constant amount of bogus fetches if we didn't do this - but
		// getEditionHintsBySourceIdentity() needs to exist anyway for the purpose of being able
		// to handle deletion of an Identity so we may as well just use it here, too.
		for(EditionHint h : getEditionHintsBySourceIdentity(identity)) {
			if(logMINOR)
				Logger.minor(this, "storeAbortFetchCommandWithoutCommit(): Deleting " + h);
			
			h.deleteWithoutCommit();
			--mTotalQueuedDownloadsInSession;
		}

		Logger.normal(this, "storeAbortFetchCommandWithoutCommit() finished");
	}

	@Override public void storeNewEditionHintCommandWithoutCommit(EditionHint newHint) {
		if(logMINOR)
			Logger.minor(this, "storeNewEditionHintCommandWithoutCommit()...");
		
		try {
		
		// Class EditionHint should enforce this in theory, but it has a legacy codepath which
		// doesn't, so we better check whether the enforcement works.
		assert(newHint.getSourceCapacity() > 0);
		
		try {
			Identity target = mWoT.getIdentityByID(newHint.getID());
			
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
			if(!mWoT.shouldFetchIdentity(target)) {
				if(logMINOR)
					Logger.minor(this, "EditionHint has non-trusted target, ignoring: " + newHint);
				return;
			}
		} catch(UnknownIdentityException e) {
			// Should not happen
			throw new RuntimeException(e);
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
		
		// Wake up this.run() - the actual downloader 
		mJob.triggerExecution();
		
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

	/** You must synchronize upon {@link #mWoT} and {@link #mLock} when using this! */
	public ObjectSet<EditionHint> getQueue() {
		Query q = mDB.query();
		q.constrain(EditionHint.class);
		q.descend("mPriority").orderDescending();
		return new InitializingObjectSet<>(mWoT, q);
	}

	private void testDatabaseIntegrity() {
		synchronized(mWoT) {
		synchronized(mLock) {
		ObjectSet<EditionHint> queueSortedByDb4o = getQueue();
		ArrayList<EditionHint> queueSortedWithReferenceImpl = new ArrayList<>(getAllEditionHints());
		
		sort(queueSortedWithReferenceImpl, new Comparator<EditionHint>() {
			@Override public int compare(EditionHint h1, EditionHint h2) {
				return h1.compareTo_ReferenceImplementation(h2);
			}
		});
		
		if(!queueSortedWithReferenceImpl.equals(queueSortedByDb4o)) {
			Logger.error(this, "Sorting EditionHints by mPriority returns wrong order: ");
			
			for(EditionHint h : queueSortedByDb4o)
				Logger.error(this, h.toString());
		}
		
		// FIXME: Check storage policy as described by class level JavaDoc.
		}
		}
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
