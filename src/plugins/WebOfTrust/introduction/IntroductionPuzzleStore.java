package plugins.WebOfTrust.introduction;

import java.text.ParseException;
import java.util.Date;

import plugins.WebOfTrust.Identity;
import plugins.WebOfTrust.OwnIdentity;
import plugins.WebOfTrust.Persistent;
import plugins.WebOfTrust.WebOfTrust;
import plugins.WebOfTrust.exceptions.DuplicatePuzzleException;
import plugins.WebOfTrust.exceptions.UnknownIdentityException;
import plugins.WebOfTrust.exceptions.UnknownPuzzleException;
import plugins.WebOfTrust.introduction.IntroductionPuzzle.PuzzleType;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.ext.ExtObjectContainer;
import com.db4o.query.Query;

import freenet.keys.FreenetURI;
import freenet.node.RequestClient;
import freenet.support.CurrentTimeUTC;
import freenet.support.Logger;
import freenet.support.TimeUtil;


/**
 * A manager for storing puzzles in the db4o database and retrieving them from it.
 * Used by the IntroductionServer and IntroductionClient for managging puzzles, not to be used by the UI directly.
 * 
 * The functions here are roughly ordered by the logical order in which they are needed in the plugin, here you can get a good overview:
 * 1. The IntroductionServer/Client deletes expired puzzles (deleteExpiredPuzzles()).
 * 2. The IntroductionServer tries to download solutions of puzzles which it has inserted (getUnsolvedByInserter).
 * 3. The IntroductionServer checks whether it has to insert new puzzles and the client checks whether it can download new ones from a 
 * 		given identity (getOfTodayByInserter / getUninsertedOwnPuzzlesByInserter).
 * 4. The IntroductionClient finds a slot of today of which we do not have a puzzle from a given identity (getByInserterDateIndex) and 
 * 		tries to download them.
 * 5. The IntroductionClient gives solvable puzzles to the user and the UI lets him solve them (getUnsolvedPuzzles).
 * 6. The InrtoductionClient uploads solutions of solved puzzles (getUninsertedSolvedPuzzles).
 * 8. The IntroductionClient deletes the oldest puzzles to replace them with new ones (deleteOldestPuzzles).
 *
 * As of SVN revision 26940, I have ensured that all functions are properly synchronized and any needed external synchronization is documented.
 *
 * @author xor (xor@freenetproject.org)
 */
public final class IntroductionPuzzleStore {

	private final WebOfTrust mWoT;
	
	private final ExtObjectContainer mDB;
	
	private final RequestClient mRequestClient;
	
	/* These booleans are used for preventing the construction of log-strings if logging is disabled (for saving some cpu cycles) */
	
	private static transient volatile boolean logDEBUG = false;
	private static transient volatile boolean logMINOR = false;
	
	static {
		Logger.registerClass(IntroductionPuzzleStore.class);
	}
	
	
	public IntroductionPuzzleStore(final WebOfTrust myWoT) {
		mWoT = myWoT;
		mDB = myWoT.getDatabase();
		mRequestClient = new RequestClient() {
			
			@Override
			public boolean persistent() {
				return false;
			}

			@Override
			public void removeFrom(ObjectContainer container) {
				throw new UnsupportedOperationException();
			}

			@Override
			public boolean realTimeFlag() {
				return false;
			}
			
		};
		
		
		verifyDatabaseIntegrity();
	}

	private synchronized void verifyDatabaseIntegrity() {		
		// TODO: Implement.
	}
	
	public WebOfTrust getWebOfTrust() {
		return mWoT;
	}
	
	protected RequestClient getRequestClient() {
		return mRequestClient;
	}
	
	/**
	 * Delete puzzles which can no longer be solved because they have expired.
	 */
	protected synchronized void deleteExpiredPuzzles() {
			final Query q = mDB.query();
			q.constrain(IntroductionPuzzle.class);
			q.descend("mValidUntilDate").constrain(CurrentTimeUTC.get()).smaller();
			final ObjectSet<IntroductionPuzzle> result = new Persistent.InitializingObjectSet<IntroductionPuzzle>(mWoT, q);
			
			int deleted = 0;
			
			for(IntroductionPuzzle p : result) {
				synchronized(Persistent.transactionLock(mDB)) {
				try {
					if(logDEBUG) Logger.debug(this, "Deleting expired puzzle, was valid until " + p.getValidUntilDate());
					p.deleteWithoutCommit();
					Persistent.checkedCommit(mDB, this);
					++deleted;					
				} catch(RuntimeException e) {
					Persistent.checkedRollback(mDB, this, e);
				}
				}
			}
			
			
			/* TODO: Minor but interesting optimization: In lazy query evaluation mode, result.size() should take about O(N) time
			 * before the for() and O(1) after it if db4o is smart enough. Verify if it really calculates and stores the size
			 * during the iteration. If not, the log line should be prefixed with if(loglevel is debug) */
			if(logDEBUG) Logger.debug(this, "Deleted " + deleted + " of " + result.size() + " expired puzzles.");
	}
	
	/**
	 * Delete the oldest unsolved puzzles so that only an amount of <code>puzzlePoolSize</code> of unsolved puzzles is left.
	 * 
	 * Used by the introduction client to delete old puzzles and replace them with new ones.
	 * 
	 * @param puzzlePoolSize The amount of puzzles which should not be deleted.
	 */
	protected synchronized void deleteOldestUnsolvedPuzzles(final int puzzlePoolSize) {
			final Query q = mDB.query();
			q.constrain(IntroductionPuzzle.class);
			q.constrain(OwnIntroductionPuzzle.class).not();
			q.descend("mValidUntilDate").orderAscending();
			q.descend("mWasSolved").constrain(false);
			final ObjectSet<IntroductionPuzzle> result = new Persistent.InitializingObjectSet<IntroductionPuzzle>(mWoT, q);
			
			int deleteCount = Math.max(result.size() - puzzlePoolSize, 0);
			
			if(logDEBUG) Logger.debug(this, "Deleting " + deleteCount + " old puzzles, keeping " + puzzlePoolSize);
			
			while(deleteCount > 0 && result.hasNext()) {
				final IntroductionPuzzle puzzle = result.next();

				synchronized(Persistent.transactionLock(mDB)) {
				try {
					puzzle.deleteWithoutCommit();
					Persistent.checkedCommit(mDB, this);
					deleteCount--;
				}
				catch(RuntimeException e) {
					Persistent.checkedRollback(mDB, this, e);	
				}
				}
			}
	}
	
	/**
	 * Called by the WoT before an identity is deleted.
	 * Deletes all puzzles it has published or solved. Does not commit the transaction.
	 * 
	 * You have to lock this IntroductionPuzzleStore and the database before calling this function.
	 *  
	 * @param identity The identity which is being deleted. It must still be stored in the database.
	 */
	public void onIdentityDeletion(final Identity identity) {
		Query q = mDB.query();
		q.constrain(IntroductionPuzzle.class);
		q.descend("mInserter").constrain(identity).identity();
		ObjectSet<IntroductionPuzzle> puzzles = new Persistent.InitializingObjectSet<IntroductionPuzzle>(mWoT, q);
		
		for(IntroductionPuzzle puzzle : puzzles)
			puzzle.deleteWithoutCommit();
		
		q = mDB.query();
		q.constrain(IntroductionPuzzle.class);
		q.descend("mSolver").constrain(identity).identity();
		puzzles = new Persistent.InitializingObjectSet<IntroductionPuzzle>(mWoT, q);
			
		for(IntroductionPuzzle puzzle : puzzles)
			puzzle.deleteWithoutCommit();
	}

	public synchronized void storeAndCommit(final IntroductionPuzzle puzzle) {
		puzzle.initializeTransient(mWoT);
		/* TODO: Convert to assert() maybe when we are sure that this does not happen. Duplicate puzzles will be deleted after they
		 * expire anyway. Further, isn't there a db4o option which ensures that mID is a primary key and therefore no duplicates can exist? */
		synchronized(puzzle) {
		synchronized(Persistent.transactionLock(mDB)) {
			try {
				final IntroductionPuzzle existingPuzzle = getByID(puzzle.getID());
				if(existingPuzzle != puzzle)
					throw new IllegalArgumentException("Puzzle with ID " + puzzle.getID() + " already exists!");
			}
			catch(UnknownPuzzleException e) { }
			
			try {
				puzzle.storeWithoutCommit();
				Persistent.checkedCommit(mDB, this);
			}
			catch(RuntimeException e) {
				Persistent.checkedRollbackAndThrow(mDB, this, e);
			}
		}
		}
	}

	/**
	 * Get an IntroductionPuzzle or OwnIntroductionPuzzle by it's ID.
	 * @throws UnknownPuzzleException If there is no puzzle with the given id.
	 */
	public synchronized IntroductionPuzzle getByID(final String id) throws UnknownPuzzleException {
		final Query q = mDB.query();
		q.constrain(IntroductionPuzzle.class);
		q.descend("mID").constrain(id);
		final ObjectSet<IntroductionPuzzle> result = new Persistent.InitializingObjectSet<IntroductionPuzzle>(mWoT, q);

		switch(result.size()) {
			case 1: return result.next();
			case 0: throw new UnknownPuzzleException(id);
			default: throw new DuplicatePuzzleException(id);
		}
	}
	
	protected IntroductionPuzzle getPuzzleBySolutionURI(final FreenetURI uri) throws ParseException, UnknownIdentityException, UnknownPuzzleException {
		return getByID(IntroductionPuzzle.getIDFromSolutionURI(uri));
	}
	
	 /**
	  * Get an own puzzle by it's request URI.
	  * 
	  * If you synchronize on the puzzle store while calling this function you have to synchronize on the WoT before synchronizing
	  * on the puzzle store because this function locks the WoT. If you did not lock it before dead locks might occur.
	  * 
	  * Used by the IntroductionServer to obtain the corresponding puzzle object when an insert succeeded or failed.
	  */
	protected OwnIntroductionPuzzle getOwnPuzzleByRequestURI(final FreenetURI uri) throws ParseException, UnknownIdentityException, UnknownPuzzleException {
		final OwnIdentity inserter = mWoT.getOwnIdentityByURI(uri);
		final Date date = IntroductionPuzzle.getDateFromRequestURI(uri);
		final int index = IntroductionPuzzle.getIndexFromRequestURI(uri);
		
		return getOwnPuzzleByInserterDateIndex(inserter, date, index);
	}
	
	
	 /**
	  * Get a puzzle by its solution URI.
	  * 
	  * Used by the IntroductionServer when a solution was downloaded from the given URI to retrieve the IntroductionPuzzle object which
	  * belongs to the URI.
	  * 
	  * @param db
	  * @param uri
	  * @return The puzzle
	  * @throws ParseException
	  * @throws UnknownPuzzleException 
	  */
	protected OwnIntroductionPuzzle getOwnPuzzleBySolutionURI(final FreenetURI uri) throws ParseException, UnknownPuzzleException {
		return (OwnIntroductionPuzzle)getByID(OwnIntroductionPuzzle.getIDFromSolutionURI(uri));
	}

	/**
	 * Used by the IntroductionPuzzleFactories for creating new puzzles.
	 * You have to synchronize on this IntroductionPuzzleStore surrounding the call to this function and the storage of a puzzle which uses
	 * the index to ensure that the index is not taken in between.
	 */
	public int getFreeIndex(final Identity inserter, Date date) {
		date = TimeUtil.setTimeToZero(date);
		
		final Query q = mDB.query();
		q.constrain(IntroductionPuzzle.class);
		q.descend("mInserter").constrain(inserter).identity();
		q.descend("mDayOfInsertion").constrain(date.getTime());
		q.descend("mIndex").orderDescending();
		final ObjectSet<IntroductionPuzzle> result = new Persistent.InitializingObjectSet<IntroductionPuzzle>(mWoT, q);
		
		return result.size() > 0 ? result.next().getIndex()+1 : 0;
	}
	
	/**
	 * Get all not inserted puzzles of the given identity.
	 * You have to put a synchronized(this IntroductionPuzzleStore) statement around the call to this function and the processing of the
	 * List which was returned by it!
	 * 
	 * Used by the IntroductionServer for inserting puzzles.
	 */
	public ObjectSet<OwnIntroductionPuzzle> getUninsertedOwnPuzzlesByInserter(final OwnIdentity identity) {
		final Query q = mDB.query();
		q.constrain(OwnIntroductionPuzzle.class);
		q.descend("mInserter").constrain(identity).identity();
		q.descend("mWasInserted").constrain(false);
		return new Persistent.InitializingObjectSet<OwnIntroductionPuzzle>(mWoT, q);
	}

	/**
	 * Get all not solved puzzles which where inserted by the given identity.
	 * You have to put a synchronized(this IntroductionPuzzleStore) statement around the call to this function and the processing of the
	 * List which was returned by it!
	 * 
	 * Used by the IntroductionServer for downloading solutions.
	 */
	protected ObjectSet<OwnIntroductionPuzzle> getUnsolvedByInserter(final OwnIdentity inserter) {
		final Query q = mDB.query();
		q.constrain(OwnIntroductionPuzzle.class);
		q.descend("mInserter").constrain(inserter).identity();
		q.descend("mWasSolved").constrain(false);
		return new Persistent.InitializingObjectSet<OwnIntroductionPuzzle>(mWoT, q);
	}
	
	/**
	 * Get a list of puzzles or own puzzles which are from today.
	 * You have to put a synchronized(this IntroductionPuzzleStore) statement around the call to this function and the processing of the
	 * List which was returned by it!
	 * 
	 * Used by for checking whether new puzzles have to be inserted for a given OwnIdentity or can be downloaded from a given Identity.
	 */
	protected ObjectSet<IntroductionPuzzle> getOfTodayByInserter(final Identity inserter) {
		final Date today = TimeUtil.setTimeToZero(CurrentTimeUTC.get());
		
		final Query q = mDB.query();
		q.constrain(IntroductionPuzzle.class);
		q.descend("mInserter").constrain(inserter).identity();
		q.descend("mDayOfInsertion").constrain(today.getTime());
		return new Persistent.InitializingObjectSet<IntroductionPuzzle>(mWoT, q);
	}
	
	/**
	 * Get a puzzle or own puzzle of a given identity from a given date with a given index.
	 * 
	 * Used by the IntroductionClient to check whether we already have a puzzle from the given date and index, if yes then we do not
	 * need to download that one.
	 */
	protected synchronized IntroductionPuzzle getByInserterDateIndex(final Identity inserter, Date date, final int index) throws UnknownPuzzleException {
		date = TimeUtil.setTimeToZero(date);
		
		final Query q = mDB.query();
		q.constrain(IntroductionPuzzle.class);
		q.descend("mInserter").constrain(inserter).identity();
		q.descend("mDayOfInsertion").constrain(date.getTime());
		q.descend("mIndex").constrain(index);
		final ObjectSet<IntroductionPuzzle> result = new Persistent.InitializingObjectSet<IntroductionPuzzle>(mWoT, q);
		
		switch(result.size()) {
			case 1: return result.next();
			case 0: throw new UnknownPuzzleException("inserter=" + inserter + "; date=" + date.getTime() + "; index=" + index);
			default: throw new DuplicatePuzzleException("inserter=" + inserter + "; date=" + date.getTime() + "; index=" + index);
		}
	}
	
	/**
	 * Get a puzzle of a given OwnIdentity from a given date with a given index.
	 */
	protected synchronized OwnIntroductionPuzzle getOwnPuzzleByInserterDateIndex(final OwnIdentity inserter, Date date, final int index) throws UnknownPuzzleException {
		date = TimeUtil.setTimeToZero(date);
		
		final Query q = mDB.query();
		q.constrain(OwnIntroductionPuzzle.class);
		q.descend("mInserter").constrain(inserter).identity();
		q.descend("mDayOfInsertion").constrain(date.getTime());
		q.descend("mIndex").constrain(index);
		final ObjectSet<OwnIntroductionPuzzle> result = new Persistent.InitializingObjectSet<OwnIntroductionPuzzle>(mWoT, q);
		
		switch(result.size()) {
			case 1: return result.next();
			case 0: throw new UnknownPuzzleException("inserter=" + inserter + "; date=" + date.getTime() + "; index=" + index);
			default: throw new DuplicatePuzzleException("inserter=" + inserter + "; date=" + date.getTime() + "; index=" + index);
		}
	}

	/**
	 * Get a list of non-own puzzles which were downloaded and not solved yet, of a given type.
	 * You have to put a synchronized(this IntroductionPuzzleStore) statement around the call to this function and the processing of the
	 * List which was returned by it!
	 */
	protected ObjectSet<IntroductionPuzzle> getUnsolvedPuzzles(final PuzzleType puzzleType) {
		final Query q = mDB.query();
		q.constrain(IntroductionPuzzle.class);
		q.constrain(OwnIntroductionPuzzle.class).not();
		q.descend("mValidUntilDate").orderDescending();
		q.descend("mWasSolved").constrain(false);
		q.descend("mType").constrain(puzzleType);
		return new Persistent.InitializingObjectSet<IntroductionPuzzle>(mWoT, q);
	}
	
	/**
	 * Get a List of all solved non-own puzzles.
	 * You have to put a synchronized(this IntroductionPuzzleStore) statement around the call to this function and the processing of the
	 * List which was returned by it!
	 * 
	 * Used by the IntroductionClient for inserting solutions of solved puzzles.
	 */
	public ObjectSet<IntroductionPuzzle> getUninsertedSolvedPuzzles() {
		final Query q = mDB.query();
		q.constrain(IntroductionPuzzle.class);
		q.constrain(OwnIntroductionPuzzle.class).not();
		q.descend("mWasSolved").constrain(true);
		q.descend("mWasInserted").constrain(false);
		return new Persistent.InitializingObjectSet<IntroductionPuzzle>(mWoT, q);
	}
	
	public synchronized int getOwnCatpchaAmount(final boolean solved) {
		final Query q = mDB.query();
		q.constrain(OwnIntroductionPuzzle.class);
		q.descend("mWasSolved").constrain(solved);
		return q.execute().size();
	}

	public synchronized int getNonOwnCaptchaAmount(final boolean solved) {
		final Query q = mDB.query();
		q.constrain(IntroductionPuzzle.class);
		q.constrain(OwnIntroductionPuzzle.class).not();
		q.descend("mWasSolved").constrain(solved);
		return q.execute().size();
	}
	
}
