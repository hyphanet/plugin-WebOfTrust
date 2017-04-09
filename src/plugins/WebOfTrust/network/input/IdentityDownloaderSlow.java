/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.network.input;

import static java.util.Collections.sort;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MINUTES;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.Callable;

import plugins.WebOfTrust.Identity;
import plugins.WebOfTrust.Persistent.InitializingObjectSet;
import plugins.WebOfTrust.SubscriptionManager;
import plugins.WebOfTrust.Trust;
import plugins.WebOfTrust.Trust.TrustID;
import plugins.WebOfTrust.WebOfTrust;
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

import freenet.keys.FreenetURI;
import freenet.node.PrioRunnable;
import freenet.support.Logger;
import freenet.support.io.NativeThread;

/**
 * Uses USK edition hints to download {@link Identity}s from the network for which we have a
 * significant confidence that a certain edition exists.
 * For an explanation of what an edition hint is, see {@link EditionHint}.
 * 
 * The downloads happen as a direct SSK request, and thus don't cause as much network load as the
 * USK subscriptions which {@link IdentityDownloaderFast} would do.
 * 
 * This class only deals with the {@link Identity}s which {@link IdentityDownloaderFast} does not
 * download, so in combination the both of these classes download all {@link Identity}s.
 * 
 * FIXME: Do we deduplicate hints by edition? We should do that so we don't have multiple hints for
 * the same edition in the queue download queue - it only makes sense to download a single edition
 * once. This space optimization is necessary because each Trust value we receive from the network
 * ships with a hint, so there are at most O(N*512) hints where N is the number of identities.
 * This is a very large number, in fact the Trust table is the largest table we store in
 * the database (by number of entries, not necessarily by used space) - so it would be nice to avoid
 * having that many EditionHint objects as well.
 * EDIT: It may actually make sense to keep the duplicate hints: If the giver of a hint becomes
 * distrusted we maybe should delete their hints - but this does not mean the editions they hint at
 * are not valid. So we should try to fetch those editions by keeping the hints of other people
 * who hinted at the same edition. If we go for that behavior we just have to delete all hints of
 * a give edition once it is fetched by any IdentityDownloader.
 * OTOH one goal of having EditionHint objects not reference the Identity objects directly but only
 * contain their string IDs was to avoid having to delete them when an Identity becomes distrusted
 * (as they only can cause a temporary disturbance of 512 bogus fetch attempts per distrusted
 * Identity). Not only would this keep the codebase simple but also greatly reduce lock contention
 * as the IdentityDownloaderSlow would not have to sync against the lock of the Identity database
 * (= the WebOfTrust).
 * We should decide which path we want to go down and act accordingly. */
public final class IdentityDownloaderSlow implements IdentityDownloader, Daemon, PrioRunnable {
	
	/** 
	 * Once we have stored an {@link EditionHint} to the database, {@link #run()} is scheduled to
	 * execute and start downloading of the pending hint queue after this delay.
	 * As we download multiple hints in parallel, the delay is non-zero to ensure we don't have to
	 * do multiple database queries if multiple hints arrive in a short timespan - database queries
	 * are likely the most expensive operation. */
	public static transient final long QUEUE_BATCHING_DELAY_MS = MINUTES.toMillis(1);

	private final WebOfTrust mWoT;
	
	private final IdentityDownloaderController mLock;
	
	private final ExtObjectContainer mDB;
	
	/** FIXME: Document similarly to {@link SubscriptionManager#mJob} */
	private volatile DelayedBackgroundJob mJob = null;

	private static transient volatile boolean logDEBUG = false;

	private static transient volatile boolean logMINOR = false;

	static {
		Logger.registerClass(IdentityDownloaderSlow.class);
	}


	public IdentityDownloaderSlow(WebOfTrust wot) {
		requireNonNull(wot);
		
		mWoT = wot;
		mLock = mWoT.getIdentityDownloaderController();
		mDB = mWoT.getDatabase();
	}

	@Override public void start() {
		Logger.normal(this, "start() ...");
		
		// FIXME: Implement similarly to SubscriptionManager.
		
		if(logDEBUG)
			testDatabaseIntegrity();
		
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
							download(h);
							if(--downloadsToSchedule <= 0)
								break;
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

	public int getRunningDownloadCount() {
		// Don't require callers to synchronize so we can use it from the Statistics web interface
		synchronized(mLock) {
			// FIXME: Implement
			return 0;
		}
	}

	public int getMaxRunningDownloadCount() {
		// FIXME: Implement. Use what is configured on the fred web interface by
		// "Maximum number of temporary  USK fetchers" (thanks to ArneBab for the idea!)
		return 0;
	}

	private boolean isDownloadInProgress(EditionHint h) {
		// FIXME: Implement similarly to IntroductionClient
		return true;
	}

	private void download(EditionHint h) {
		FreenetURI requestURI
			= h.getTargetIdentity().getRequestURI().setSuggestedEdition(h.getEdition());
		
		// FIXME: Implement similarly to IntroductionClient
	}

	@Override public int getPriority() {
		// Below NORM_PRIORITY because we are a background thread and not relevant to UI actions.
		// Above MIN_PRIORITY because we're not merely a cleanup thread.
		// -> LOW_PRIORITY is the only choice.
		return NativeThread.LOW_PRIORITY;
	}

	@Override public void terminate() {
		Logger.normal(this, "terminate() ...");
		
		// FIXME: Implement similarly to SubscriptionManager.
		
		Logger.normal(this, "terminate() finished.");
	}

	@Override public void storeStartFetchCommandWithoutCommit(final Identity identity) {
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
		
		for(EditionHint h : getEditionHintsByTargetIdentity(identity)) {
			if(logMINOR)
				Logger.minor(this, "Deleting " + h);
			
			h.deleteWithoutCommit();
		}
		
		// We intentionally don't tell the network request thread to abort already running requests:
		// A single running request for a hint doesn't cause any further request when it finishes.
		// So we will only do O(constant number of concurrent requests) = O(1) requests of unwanted
		// data, which is the lowest imaginable amount above 0 and thus acceptable.
		// And most importantly: The XMLTransformer will ignore data of unwanted identities anyway.
		// Not canceling requests spares us from having to implement a in-database "command queue"
		// for the network thread: We couldn't cancel the requests right away on this thread here
		// because the transaction isn't committed yet and may be aborted after we return. We would
		// then have wrongly canceled a request which should still be running.
		
		// FIXME: Should this.run(), i.e. the downloading thread, be capable of aborting pending
		// fetches or can we just let it finish them assuming their amount is O(1) anyway?
		/* mJob.triggerExecution(); */
		
		Logger.normal(this, "storeAbortFetchCommandWithoutCommit() finished");
	}

	@Override public void storeNewEditionHintCommandWithoutCommit(EditionHint newHint) {
		if(logMINOR)
			Logger.minor(this, "storeNewEditionHintCommandWithoutCommit(" + newHint + ") ...");
		
		// Class EditionHint should enforce this in theory, but it has a legacy codepath which
		// doesn't, so we better check whether the enforcement works.
		assert(newHint.getSourceCapacity() > 0);
		
		try {
			Identity target = mWoT.getIdentityByID(newHint.getID());
			
			if(target.getLastFetchedEdition() >= newHint.getEdition()) {
				if(logMINOR)
					Logger.minor(this, "Received obsolete hint, discarding: " + newHint);
				return;
			}
			
			// FIXME: I'm rather sure that XMLTransformer won't check this, but please validate it
			if(!mWoT.shouldFetchIdentity(target)) {
				Logger.normal(this, "Received hint for non-trusted target, discarding: " + newHint);
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
				Logger.warning(this, "Received hint older than current, discarding:");
				Logger.warning(this, "oldHint: " + oldHint);
				Logger.warning(this, "newHint: " + newHint);
				return;
			} else if(newEdition == oldEdition) {
				// FIXME: Decide whether we can handle this in the callers
				Logger.warning(this, "Received same hint as currently stored, bug?");
				Logger.warning(this, "oldHint: " + oldHint);
				Logger.warning(this, "newHint: " + newHint);
				return;
			}
			
			if(logMINOR)
				Logger.minor(this, "Deleting old hint: " + oldHint);
			
			oldHint.deleteWithoutCommit();
		} catch(UnknownEditionHintException e) {}
		
		if(logMINOR)
			Logger.minor(this, "Storing new hint: " + newHint);
		
		newHint.storeWithoutCommit();
		
		// Wake up this.run() - the actual downloader 
		mJob.triggerExecution();
		
		if(logMINOR)
			Logger.minor(this, "storeNewEditionHintCommandWithoutCommit() finished.");
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
		// FIXME
	}

	/** You must synchronize upon {@link #mLock} when using this! */
	private ObjectSet<EditionHint> getAllEditionHints() {
		Query q = mDB.query();
		q.constrain(EditionHint.class);
		return new InitializingObjectSet<>(mWoT, q);
	}

	/** Convenient frontend for {@link #getEditionHintByID(String)} */
	private EditionHint getEditionHint(Identity sourceIdentity, Identity targetIdentity)
			throws UnknownEditionHintException {
		
		String id = new TrustID(sourceIdentity.getID(), targetIdentity.getID()).toString();
		return getEditionHintByID(id);
	}

	/** You must synchronize upon {@link #mLock} when using this! */
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

	/** You must synchronize upon {@link #mLock} when using this! */
	private ObjectSet<EditionHint> getEditionHintsByTargetIdentity(Identity identity) {
		Query q = mDB.query();
		q.constrain(EditionHint.class);
		q.descend("mTargetIdentity").constrain(identity).identity();
		return new InitializingObjectSet<>(mWoT, q);
	}

	/** You must synchronize upon {@link #mLock} when using this! */
	private ObjectSet<EditionHint> getQueue() {
		Query q = mDB.query();
		q.constrain(EditionHint.class);
		q.descend("mPriority").orderDescending();
		return new InitializingObjectSet<>(mWoT, q);
	}

	private void testDatabaseIntegrity() {
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
		}
	}
}
