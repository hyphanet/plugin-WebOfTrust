package plugins.WoT.introduction;

import java.text.ParseException;
import java.util.Date;

import plugins.WoT.Identity;
import plugins.WoT.OwnIdentity;
import plugins.WoT.WoT;
import plugins.WoT.exceptions.DuplicatePuzzleException;
import plugins.WoT.exceptions.UnknownIdentityException;
import plugins.WoT.exceptions.UnknownPuzzleException;
import plugins.WoT.introduction.IntroductionPuzzle.PuzzleType;

import com.db4o.ObjectSet;
import com.db4o.ext.ExtObjectContainer;
import com.db4o.query.Query;

import freenet.keys.FreenetURI;
import freenet.support.CurrentTimeUTC;
import freenet.support.Logger;


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
 * @author xor
 */
public final class IntroductionPuzzleStore {

	private final WoT mWoT;
	
	private final ExtObjectContainer mDB;

	public IntroductionPuzzleStore(WoT myWoT) {
		mWoT = myWoT;
		mDB = myWoT.getDB();
		
		deleteCorruptedPuzzles();
	}

	private synchronized void deleteCorruptedPuzzles() {		
		synchronized(mDB.lock()) {
			ObjectSet<IntroductionPuzzle> puzzles = mDB.queryByExample(IntroductionPuzzle.class);
			for(IntroductionPuzzle p : puzzles) {
				try {
					mDB.activate(p, 3);
					if(p.checkConsistency() == false) {
						Logger.error(this, "Deleting corrupted puzzle");
						deleteWithoutCommit(p);
						mDB.commit(); Logger.debug(this, "COMMITED.");
					}
				} catch(RuntimeException e) {
					mDB.rollback(); mDB.purge(); Logger.error(this, "ROLLED BACK!", e);
				}
			}
			
		}
	}
	
	/**
	 * Delete puzzles which can no longer be solved because they have expired.
	 */
	@SuppressWarnings("unchecked")
	protected synchronized void deleteExpiredPuzzles() {
		synchronized(mDB.lock()) {
			Query q = mDB.query();
			q.constrain(IntroductionPuzzle.class);
			q.descend("mValidUntilTime").constrain(CurrentTimeUTC.getInMillis()).smaller();
			ObjectSet<IntroductionPuzzle> result = q.execute();
			
			int deleted = 0;
			
			for(IntroductionPuzzle p : result) {
				try {
					Logger.debug(this, "Deleting expired puzzle, was valid until " + new Date(p.getValidUntilTime()));
					deleteWithoutCommit(p);
					mDB.commit(); Logger.debug(this, "COMMITED.");
					++deleted;					
				} catch(RuntimeException e) {
					mDB.rollback(); mDB.purge(); Logger.error(this, "ROLLED BACK!", e);
				}
			}
			
			/* TODO: Minor but interesting optimization: result.size() should take about O(N) time before the for() and O(1) after it
			 * if db4o is smart enough. Verify if it really calculates and stores the size during the iteration. If not, the log line
			 * should be prefixed with if(loglevel is debug) */
			Logger.debug(IntroductionPuzzle.class, "Deleted " + deleted + " of " + result.size() + " expired puzzles.");
		}
	}
	
	/**
	 * Delete the oldest unsolved puzzles so that only an amount of <code>puzzlePoolSize</code> of unsolved puzzles is left.
	 * 
	 * Used by the introduction client to delete old puzzles and replace them with new ones.
	 * 
	 * @param puzzlePoolSize The amount of puzzles which should not be deleted.
	 */
	@SuppressWarnings("unchecked")
	protected synchronized void deleteOldestUnsolvedPuzzles(int puzzlePoolSize) {
		synchronized(mDB.lock()) {
			Query q = mDB.query();
			q.constrain(IntroductionPuzzle.class);
			q.constrain(OwnIntroductionPuzzle.class).not();
			q.descend("mValidUntilTime").orderAscending();
			q.descend("mWasSolved").constrain(false);
			ObjectSet<IntroductionPuzzle> result = q.execute();
			
			int deleteCount = Math.max(result.size() - puzzlePoolSize, 0);
			
			Logger.debug(this, "Deleting " + deleteCount + " old puzzles, keeping " + puzzlePoolSize);
			
			while(deleteCount > 0 && result.hasNext()) {
				IntroductionPuzzle puzzle = result.next();

				try {
					deleteWithoutCommit(puzzle);
					mDB.commit(); Logger.debug(this, "COMMITED.");
					deleteCount--;
				}
				catch(RuntimeException e) {
					mDB.rollback(); mDB.purge(); Logger.error(this, "ROLLED BACK!", e);	
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
	@SuppressWarnings("unchecked")
	public void onIdentityDeletion(Identity identity) {
		Query q = mDB.query();
		q.constrain(IntroductionPuzzle.class);
		q.descend("mInserter").constrain(identity).identity();
		ObjectSet<IntroductionPuzzle> puzzles = q.execute();
		
		for(IntroductionPuzzle puzzle : puzzles)
			deleteWithoutCommit(puzzle);
		
		if(identity instanceof OwnIdentity) {
			q = mDB.query();
			q.constrain(IntroductionPuzzle.class);
			q.descend("mWasSolved").constrain(true);
			q.descend("mSolver").constrain((OwnIdentity)identity);
			puzzles = q.execute();
			
			for(IntroductionPuzzle puzzle : puzzles)
				deleteWithoutCommit(puzzle);
		}
	}
	
	private synchronized void deleteWithoutCommit(IntroductionPuzzle puzzle) {
		try {
			mDB.delete(puzzle.getType());
			mDB.delete(puzzle);
		}
		catch(RuntimeException e) {
			mDB.rollback(); mDB.purge(); Logger.error(this, "ROLLED BACK!", e);
			throw e;
		}
	}


	public synchronized void storeAndCommit(IntroductionPuzzle puzzle) {
		/* TODO: Convert to assert() maybe when we are sure that this does not happen. Duplicate puzzles will be deleted after they
		 * expire anyway. Further, isn't there a db4o option which ensures that mID is a primary key and therefore no duplicates can exist? */
		synchronized(puzzle) {
		synchronized(mDB.lock()) {
			try {
				IntroductionPuzzle existingPuzzle = getByID(puzzle.getID());
				if(existingPuzzle != puzzle)
					throw new IllegalArgumentException("Puzzle with ID " + puzzle.getID() + " already exists!");
			}
			catch(UnknownPuzzleException e) { }
			
			if(mDB.isStored(puzzle) && !mDB.isActive(puzzle))
				throw new RuntimeException("Trying to store an inactive IntroductionPuzzle object!");
	
			try {
				// IMPORTANT: When adding new .store() calls here, also add .delete() in deleteWithoutCommit()
				
				mDB.store(puzzle.getType());
				// mDB.store(puzzle.getDateOfInsertion()); /* Not stored because it is a primitive for db4o */ 
				mDB.store(puzzle);
				mDB.commit();
				Logger.debug(this, "COMMITED.");
			}
			catch(RuntimeException e) {
				mDB.rollback(); mDB.purge(); Logger.error(this, "ROLLED BACK!", e);
				throw e;
			}
		}
		}
	}

	/**
	 * Get an IntroductionPuzzle or OwnIntroductionPuzzle by it's ID.
	 * @throws UnknownPuzzleException If there is no puzzle with the given id.
	 */
	@SuppressWarnings("unchecked")
	public synchronized IntroductionPuzzle getByID(String id) throws UnknownPuzzleException {
		Query q = mDB.query();
		q.constrain(IntroductionPuzzle.class);
		q.descend("mID").constrain(id);
		ObjectSet<IntroductionPuzzle> result = q.execute();

		switch(result.size()) {
			case 1:
				return result.next();
			case 0:
				throw new UnknownPuzzleException(id);
			default:
				throw new DuplicatePuzzleException(id);
		}
	}
	
	protected IntroductionPuzzle getPuzzleBySolutionURI(FreenetURI uri) throws ParseException, UnknownIdentityException, UnknownPuzzleException {
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
	protected OwnIntroductionPuzzle getOwnPuzzleByRequestURI(FreenetURI uri) throws ParseException, UnknownIdentityException, UnknownPuzzleException {
		OwnIdentity inserter = mWoT.getOwnIdentityByURI(uri);
		Date date = IntroductionPuzzle.getDateFromRequestURI(uri);
		int index = IntroductionPuzzle.getIndexFromRequestURI(uri);
		
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
	protected OwnIntroductionPuzzle getOwnPuzzleBySolutionURI(FreenetURI uri) throws ParseException, UnknownPuzzleException {
		return (OwnIntroductionPuzzle)getByID(OwnIntroductionPuzzle.getIDFromSolutionURI(uri));
	}

	/**
	 * Used by the IntroductionPuzzleFactories for creating new puzzles.
	 */
	@SuppressWarnings({ "deprecation", "unchecked" })
	public synchronized int getFreeIndex(OwnIdentity inserter, Date date) {
		Query q = mDB.query();
		q.constrain(OwnIntroductionPuzzle.class);
		q.descend("mInserter").constrain(inserter).identity();
		q.descend("mDateOfInsertion").constrain(new Date(date.getYear(), date.getMonth(), date.getDate()));
		q.descend("mIndex").orderDescending();
		ObjectSet<IntroductionPuzzle> result = q.execute();
		
		return result.size() > 0 ? result.next().getIndex()+1 : 0;
	}
	
	/**
	 * Get all not inserted puzzles of the given identity.
	 * You have to put a synchronized(this IntroductionPuzzleStore) statement around the call to this function and the processing of the
	 * List which was returned by it!
	 * 
	 * Used by the IntroductionServer for inserting puzzles.
	 */
	@SuppressWarnings("unchecked")
	public synchronized ObjectSet<OwnIntroductionPuzzle> getUninsertedOwnPuzzlesByInserter(OwnIdentity identity) {
		Query q = mDB.query();
		q.constrain(OwnIntroductionPuzzle.class);
		q.descend("mInserter").constrain(identity).identity();
		q.descend("mWasInserted").constrain(false);
		return q.execute();
	}

	/**
	 * Get all not solved puzzles which where inserted by the given identity.
	 * You have to put a synchronized(this IntroductionPuzzleStore) statement around the call to this function and the processing of the
	 * List which was returned by it!
	 * 
	 * Used by the IntroductionServer for downloading solutions.
	 */
	@SuppressWarnings("unchecked")
	protected synchronized ObjectSet<OwnIntroductionPuzzle> getUnsolvedByInserter(OwnIdentity inserter) {
		Query q = mDB.query();
		q.constrain(OwnIntroductionPuzzle.class);
		q.descend("mInserter").constrain(inserter).identity();
		q.descend("mWasSolved").constrain(false);
		return q.execute();
	}
	
	/**
	 * Get a list of puzzles or own puzzles which are from today.
	 * You have to put a synchronized(this IntroductionPuzzleStore) statement around the call to this function and the processing of the
	 * List which was returned by it!
	 * 
	 * Used by for checking whether new puzzles have to be inserted for a given OwnIdentity or can be downloaded from a given Identity.
	 */
	@SuppressWarnings({ "deprecation", "unchecked" })
	protected synchronized ObjectSet<IntroductionPuzzle> getOfTodayByInserter(Identity inserter) {
		Date now = CurrentTimeUTC.get();
		Date today = new Date(now.getYear(), now.getMonth(), now.getDate());
		
		Query q = mDB.query();
		q.constrain(IntroductionPuzzle.class);
		q.descend("mInserter").constrain(inserter).identity();
		q.descend("mDateOfInsertion").constrain(today);
		return q.execute();
	}
	
	/**
	 * Get a puzzle or own puzzle of a given identity from a given date with a given index.
	 * 
	 * Used by the IntroductionClient to check whether we already have a puzzle from the given date and index, if yes then we do not
	 * need to download that one.
	 */
	@SuppressWarnings({ "unchecked", "deprecation" })
	protected synchronized IntroductionPuzzle getByInserterDateIndex(Identity inserter, Date date, int index) throws UnknownPuzzleException {
		Query q = mDB.query();
		q.constrain(IntroductionPuzzle.class);
		q.descend("mInserter").constrain(inserter).identity();
		q.descend("mDateOfInsertion").constrain(new Date(date.getYear(), date.getMonth(), date.getDate()));
		q.descend("mIndex").constrain(index);
		ObjectSet<IntroductionPuzzle> result = q.execute();
		
		switch(result.size()) {
			case 1:
				return result.next();
			case 0:
				throw new UnknownPuzzleException("inserter=" + inserter + "; date=" + date + "; index=" + index);
			default:
				throw new DuplicatePuzzleException("inserter=" + inserter + "; date=" + date + "; index=" + index);
		}
	}
	
	/**
	 * Get a puzzle of a given OwnIdentity from a given date with a given index.
	 */
	@SuppressWarnings({ "unchecked", "deprecation" })
	protected synchronized OwnIntroductionPuzzle getOwnPuzzleByInserterDateIndex(OwnIdentity inserter, Date date, int index) throws UnknownPuzzleException {
		Query q = mDB.query();
		q.constrain(OwnIntroductionPuzzle.class);
		q.descend("mInserter").constrain(inserter).identity();
		q.descend("mDateOfInsertion").constrain(new Date(date.getYear(), date.getMonth(), date.getDate()));
		q.descend("mIndex").constrain(index);
		ObjectSet<OwnIntroductionPuzzle> result = q.execute();
		
		switch(result.size()) {
			case 1:
				return result.next();
			case 0:
				throw new UnknownPuzzleException("inserter=" + inserter + "; date=" + date + "; index=" + index);
			default:
				throw new DuplicatePuzzleException("inserter=" + inserter + "; date=" + date + "; index=" + index);
		}
	}

	/**
	 * Get a list of non-own puzzles which were downloaded and not solved yet, of a given type.
	 * You have to put a synchronized(this IntroductionPuzzleStore) statement around the call to this function and the processing of the
	 * List which was returned by it!
	 */
	@SuppressWarnings("unchecked")
	protected synchronized ObjectSet<IntroductionPuzzle> getUnsolvedPuzzles(PuzzleType puzzleType) {
		Query q = mDB.query();
		q.constrain(IntroductionPuzzle.class);
		q.constrain(OwnIntroductionPuzzle.class).not();
		q.descend("mValidUntilTime").orderDescending();
		q.descend("mWasSolved").constrain(false);
		q.descend("mType").constrain(puzzleType);
		ObjectSet<IntroductionPuzzle> puzzles = q.execute();
		
		return puzzles;
	}
	
	/**
	 * Get a List of all solved non-own puzzles.
	 * You have to put a synchronized(this IntroductionPuzzleStore) statement around the call to this function and the processing of the
	 * List which was returned by it!
	 * 
	 * Used by the IntroductionClient for inserting solutions of solved puzzles.
	 */
	@SuppressWarnings("unchecked")
	public synchronized ObjectSet<IntroductionPuzzle> getUninsertedSolvedPuzzles() {
		Query q = mDB.query();
		q.constrain(IntroductionPuzzle.class);
		q.constrain(OwnIntroductionPuzzle.class).not();
		q.descend("mWasSolved").constrain(true);
		q.descend("mWasInserted").constrain(false);
		return q.execute();
	}
	
	public synchronized int getOwnCatpchaAmount(boolean solved) {
		Query q = mDB.query();
		q.constrain(OwnIntroductionPuzzle.class);
		q.descend("mWasSolved").constrain(solved);
		return q.execute().size();
	}

	public synchronized int getNonOwnCaptchaAmount(boolean solved) {
		Query q = mDB.query();
		q.constrain(IntroductionPuzzle.class);
		q.constrain(OwnIntroductionPuzzle.class).not();
		q.descend("mWasSolved").constrain(solved);
		return q.execute().size();
	}
	
}
