/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.network.input;

import static java.util.Collections.sort;
import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.Callable;

import plugins.WebOfTrust.Identity;
import plugins.WebOfTrust.Persistent.InitializingObjectSet;
import plugins.WebOfTrust.Trust;
import plugins.WebOfTrust.Trust.TrustID;
import plugins.WebOfTrust.WebOfTrust;
import plugins.WebOfTrust.exceptions.DuplicateObjectException;
import plugins.WebOfTrust.exceptions.NotInTrustTreeException;
import plugins.WebOfTrust.exceptions.UnknownEditionHintException;
import plugins.WebOfTrust.exceptions.UnknownIdentityException;
import plugins.WebOfTrust.util.AssertUtil;
import plugins.WebOfTrust.util.Daemon;

import com.db4o.ObjectSet;
import com.db4o.ext.ExtObjectContainer;
import com.db4o.query.Query;

import freenet.support.Logger;

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
public final class IdentityDownloaderSlow implements IdentityDownloader, Daemon {
	
	private final WebOfTrust mWoT;
	
	private final IdentityDownloaderController mLock;
	
	private final ExtObjectContainer mDB;

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
		
		// FIXME: Implement
		
		if(logDEBUG)
			testDatabaseIntegrity();
		
		Logger.normal(this, "start() finished.");
	}

	@Override public void terminate() {
		Logger.normal(this, "terminate() ...");
		
		// FIXME: Implement
		
		Logger.normal(this, "terminate() finished.");
	}

	@Override public void storeStartFetchCommandWithoutCommit(final Identity identity) {
		Logger.normal(this, "storeStartFetchCommandWithoutCommit(" + identity + ") ...");
		
		final String identityID = identity.getID();
		
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
				truster.getID(),
				identityID,
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
		
		Logger.normal(this, "storeStartFetchCommandWithoutCommit() finished.");
	}

	@Override public void storeAbortFetchCommandWithoutCommit(Identity identity) {
		Logger.normal(this, "storeAbortFetchCommandWithoutCommit(" + identity + ") ...");
		
		for(EditionHint h : getEditionHintsByTargetIdentityID(identity.getID())) {
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
		
		Logger.normal(this, "storeAbortFetchCommandWithoutCommit() finished");
	}

	@Override public void storeNewEditionHintCommandWithoutCommit(EditionHint newHint) {
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
			assert(oldHint.getSourceIdentityID().equals(newHint.getSourceIdentityID()));
			assert(oldHint.getTargetIdentityID().equals(newHint.getTargetIdentityID()));
			
			long oldEdition = oldHint.getEdition();
			long newEdition = newHint.getEdition();
			
			if(newEdition < oldEdition) {
				// FIXME: Track this in a counter and punish hint publishers if they do it too often
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
			
			oldHint.deleteWithoutCommit();
		} catch(UnknownEditionHintException e) {}
		
		newHint.storeWithoutCommit();
		
		// FIXME: Wakup network request thread.
	}

	@Override public boolean getShouldFetchState(String identityID) {
		if(getEditionHintsByTargetIdentityID(identityID).size() > 0)
			return true;
		
		// We don't explicitly keep track of which identities are *not* wanted, instead
		// storeNewEditionHintCommandWithoutCommit() will do a "shouldFetchIdentity()" check
		// whenever it is called, so we just do it here as well:
		try {
			return mWoT.shouldFetchIdentity(mWoT.getIdentityByID(identityID));
		} catch (UnknownIdentityException e) {
			throw new RuntimeException(e);
		}
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
	private ObjectSet<EditionHint> getEditionHintsByTargetIdentityID(String id) {
		Query q = mDB.query();
		q.constrain(EditionHint.class);
		q.descend("mTargetIdentityID").constrain(id);
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
