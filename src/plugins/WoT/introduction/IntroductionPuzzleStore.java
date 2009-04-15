package plugins.WoT.introduction;

import java.text.ParseException;
import java.util.Date;
import java.util.List;

import plugins.WoT.CurrentTimeUTC;
import plugins.WoT.Identity;
import plugins.WoT.OwnIdentity;
import plugins.WoT.WoT;
import plugins.WoT.exceptions.UnknownIdentityException;
import plugins.WoT.introduction.IntroductionPuzzle.PuzzleType;

import com.db4o.ObjectSet;
import com.db4o.ext.ExtObjectContainer;
import com.db4o.query.Query;

import freenet.keys.FreenetURI;
import freenet.support.Logger;


/**
 * A manager for storing puzzles in the db4o database and retrieving them from it.
 * Used by the IntroductionServer and IntroductionClient for managging puzzles, not to be used by the UI directly.
 * 
 * The functions here are roughly ordered by the logical order in which they are needed in the plugin, here you can get a good overview:
 * 1. The IntroductionServer inserts puzzles (getFreeIndex).
 * 2. The IntroductionServer tries to download solutions of puzzles which it has inserted (getUnsolvedByInserter).
 * 3. The IntroductionServer checks whether it has to insert new puzzles and the client checks whether it can download new ones from a 
 * 		given identity (getOfTodayByInserter).
 * 4. The IntroductionClient finds a slot of today of which we do not have a puzzle from a given identity (getByInserterDateIndex) and 
 * 		tries to download them.
 * 5. The IntroductionClient gives solvable puzzles to the user and the UI lets him solve them (getUnsolvedPuzzles).
 * 6. The InrtoductionClient uploads solutions of solved puzzles (getSolvedPuzzles).
 * 7. The IntroductionClient and IntroductionServer delete expired puzzles (deleteExpiredPuzzles).
 * 8. The IntroductionClient deletes the oldest puzzles to replace them with new ones (deleteOldestPuzzles).
 *
 * As of SVN revision 26834, I have ensured that all functions are properly synchronized and any needed external synchronization is documented.
 *
 * @author xor
 */
public final class IntroductionPuzzleStore {

	private final ExtObjectContainer mDB;

	public IntroductionPuzzleStore(WoT myWoT) {
		mDB = myWoT.getDB();
		
		deleteCorruptedPuzzles();
	}

	private synchronized void deleteCorruptedPuzzles() {		
		synchronized(mDB.lock()) {
			ObjectSet<IntroductionPuzzle> puzzles = mDB.queryByExample(IntroductionPuzzle.class);
			for(IntroductionPuzzle p : puzzles) {
				mDB.activate(p, 3); /* FIXME: Is this the correct depth? */
				if(p.checkConsistency() == false) {
					Logger.error(this, "Deleting corrupted puzzle");
					mDB.delete(p);
				}
			}
			mDB.commit();
			/* Our goal is to delete the puzzles so we do not rollback here if an exception occurs, that would restore the deleted puzzles. */
		}
	}

	protected synchronized void storeAndCommit(IntroductionPuzzle puzzle) {
		/* TODO: Convert to assert() maybe when we are sure that this does not happen. Duplicate puzzles will be deleted after they
		 * expire anyway. Further, isn't there a db4o option which ensures that mID is a primary key and therefore no duplicates can exist? */
		synchronized(mDB.lock()) {
			IntroductionPuzzle existingPuzzle = getByID(puzzle.getID());
			if(existingPuzzle != null && existingPuzzle != puzzle)
				throw new IllegalArgumentException("Puzzle with ID " + puzzle.getID() + " already exists!");
	
			try {
				mDB.store(puzzle.getType());
				// mDB.store(puzzle.getDateOfInsertion()); /* Not stored because it is a primitive for db4o */ 
				mDB.store(puzzle);
				mDB.commit();
			}
			catch(RuntimeException e) {
				mDB.rollback();
				throw e;
			}
		}
	}

	@SuppressWarnings("unchecked")
	protected synchronized IntroductionPuzzle getByID(String id) {
		Query q = mDB.query();
		q.constrain(IntroductionPuzzle.class);
		q.descend("mID").constrain(id);
		ObjectSet<IntroductionPuzzle> result = q.execute();

		/* TODO: Decide whether we maybe should throw to get bug reports if this happens ... OTOH puzzles are not so important ;) */
		assert(result.size() <= 1);

		return (result.hasNext() ? result.next() : null);
	}

	protected IntroductionPuzzle getByURI(FreenetURI uri) throws ParseException, UnknownIdentityException {
		Identity inserter = Identity.getByURI(mDB, uri);
		Date date = IntroductionPuzzle.getDateFromRequestURI(uri);
		int index = IntroductionPuzzle.getIndexFromRequestURI(uri);
		
		return getByInserterDateIndex(inserter, date, index);
	}
	
	
	 /**
	  * Get a puzzle by it's solution URI.
	  * 
	  * Used by the IntroductionServer when a solution was downloaded from the given URI to retrieve the IntroductionPuzzle object which
	  * belongs to the URI.
	  * 
	  * @param db
	  * @param uri
	  * @return
	  * @throws ParseException
	  */
	protected IntroductionPuzzle getBySolutionURI(FreenetURI uri) throws ParseException {
		return getByID(IntroductionPuzzle.getIDFromSolutionURI(uri));
	}

	/**
	 * Used by the IntroductionServer for inserting new puzzles.
	 */
	@SuppressWarnings({ "deprecation", "unchecked" })
	protected synchronized int getFreeIndex(OwnIdentity inserter, Date date) {
		Query q = mDB.query();
		q.constrain(IntroductionPuzzle.class);
		q.descend("mInserter").descend("id").constrain(inserter.getId());
		q.descend("mDateOfInsertion").constrain(new Date(date.getYear(), date.getMonth(), date.getDate()));
		q.descend("mIndex").orderDescending();
		ObjectSet<IntroductionPuzzle> result = q.execute();
		
		return result.size() > 0 ? result.next().getIndex()+1 : 0;
	}

	/**
	 * Get all not solved puzzles which where inserted by the given identity.
	 * You have to put a synchronized(this IntroductionPuzzleStore) statement around the call to this function and the processing of the
	 * List which was returned by it!
	 * 
	 * Used by the IntroductionServer for downloading solutions.
	 */
	@SuppressWarnings("unchecked")
	protected synchronized List<IntroductionPuzzle> getUnsolvedByInserter(OwnIdentity inserter) {
		Query q = mDB.query();
		q.constrain(IntroductionPuzzle.class);
		q.descend("mInserter").constrain(inserter);
		q.descend("iWasSolved").constrain(false);
		return q.execute();
	}
	
	/**
	 * Get a list of puzzles which are from today.
	 * You have to put a synchronized(this IntroductionPuzzleStore) statement around the call to this function and the processing of the
	 * List which was returned by it!
	 * 
	 * Used by for checking whether new puzzles have to be inserted for a given OwnIdentity or can be downloaded from a given Identity.
	 */
	@SuppressWarnings({ "deprecation", "unchecked" })
	protected synchronized List<IntroductionPuzzle> getOfTodayByInserter(Identity inserter) {
		Date maxAge = new Date(CurrentTimeUTC.getYear()-1900, CurrentTimeUTC.getMonth(), CurrentTimeUTC.getDayOfMonth());
		
		Query q = mDB.query();
		q.constrain(IntroductionPuzzle.class);
		q.descend("mInserter").constrain(inserter);
		q.descend("mDateOfInsertion").constrain(maxAge).smaller().not();
		return q.execute();
	}
	
	/**
	 * Get a puzzle of a given identity from a given date with a given index.
	 * 
	 * Used by the IntroductionClient to check whether we already have a puzzle from the given date and index, if yes then we do not
	 * need to download that one.
	 */
	@SuppressWarnings("unchecked")
	protected synchronized IntroductionPuzzle getByInserterDateIndex(Identity inserter, Date date, int index) {
		Query q = mDB.query();
		q.constrain(IntroductionPuzzle.class);
		q.descend("mInserter").constrain(inserter);
		q.descend("mDateOfInsertion").constrain(date);
		q.descend("mIndex").constrain(index);
		ObjectSet<IntroductionPuzzle> result = q.execute();
		
		/* TODO: Decide whether we maybe should throw to get bug reports if this happens ... OTOH puzzles are not so important ;) */
		assert(result.size() <= 1);
		
		return (result.hasNext() ? result.next() : null);
	}

	/**
	 * Get a list of puzzles which were downloaded and not solved yet, of a given type.
	 * You have to put a synchronized(this IntroductionPuzzleStore) statement around the call to this function and the processing of the
	 * List which was returned by it!
	 */
	@SuppressWarnings("unchecked")
	protected synchronized List<IntroductionPuzzle> getUnsolvedPuzzles(PuzzleType puzzleType) {
		Query q = mDB.query();
		q.constrain(IntroductionPuzzle.class);
		q.descend("mValidUntilTime").orderDescending();
		q.descend("mSolution").constrain(null).identity();
		q.descend("mType").constrain(puzzleType);
		ObjectSet<IntroductionPuzzle> puzzles = q.execute();
		
		return puzzles;
	}
	
	/**
	 * Get a List of all solved puzzles.
	 * You have to put a synchronized(this IntroductionPuzzleStore) statement around the call to this function and the processing of the
	 * List which was returned by it!
	 * 
	 * Used by the IntroductionClient for inserting solutions of solved puzzles.
	 */
	@SuppressWarnings("unchecked")
	protected synchronized List<IntroductionPuzzle> getSolvedPuzzles() {
		Query q = mDB.query();
		q.constrain(IntroductionPuzzle.class);
		q.descend("mSolver").constrain(null).identity().not();
		return q.execute();
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
			
			for(IntroductionPuzzle p : result)
				mDB.delete(p);
			
			/* TODO: Minor but interesting optimization: result.size() should take about O(N) time before the for() and O(1) after it
			 * if db4o is smart enough. Verify if it really calculates and stores the size during the iteration. If not, the log line
			 * should be prefixed with if(loglevel is debug) */
			Logger.debug(IntroductionPuzzle.class, "Deleted " + result.size() + " expired puzzles.");
			
			mDB.commit();
			/* Our goal is to delete the puzzles so we do not rollback here if an exception occurs, that would restore the deleted puzzles. */
		}
	}
	
	/**
	 * Delete the oldest puzzles so that only an amount of <code>puzzlePoolSize</code> is left.
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
			q.descend("mValidUntilTime").orderAscending();
			ObjectSet<IntroductionPuzzle> result = q.execute();
			
			int deleteCount = result.size() - puzzlePoolSize;
			
			Logger.debug(IntroductionPuzzle.class, "Deleting " + deleteCount + " old puzzles, keeping " + puzzlePoolSize);
			while(deleteCount > 0 && result.hasNext()) {
				IntroductionPuzzle puzzle = result.next();
				/* We DO NOT handle the following check in the query so that db4o can use the index on mValidUntilTime to run the query and
				 * size() in O(1) instead of O(amount of puzzles in the database).
				 * Unfortunately toad_ said that it does not really do that even though it is logically possible => TODO: tell it to do so */
				if(puzzle.getSolution() == null) {
					mDB.delete(result.next());
					deleteCount--;
				}
			}
			
			mDB.commit();
			/* Our goal is to delete the puzzles so we do not rollback here if an exception occurs, that would restore the deleted puzzles. */ 
		}
	}
}
