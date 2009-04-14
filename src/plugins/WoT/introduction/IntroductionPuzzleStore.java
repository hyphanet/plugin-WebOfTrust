package plugins.WoT.introduction;

import java.text.ParseException;
import java.util.Date;
import java.util.List;

import com.db4o.ObjectSet;
import com.db4o.ext.ExtObjectContainer;
import com.db4o.query.Query;

import freenet.keys.FreenetURI;
import freenet.support.Logger;

import plugins.WoT.CurrentTimeUTC;
import plugins.WoT.Identity;
import plugins.WoT.OwnIdentity;
import plugins.WoT.WoT;


/**
 * A manager for storing puzzles in the db4o database and retrieving them from it.
 * 
 * @author xor
 */
public final class IntroductionPuzzleStore {

	private final WoT mWoT;
	
	private final ExtObjectContainer mDB;
	
	public IntroductionPuzzleStore(WoT myWoT) {
		mWoT = myWoT;
		mDB = myWoT.getDB();
	}
	
	private void deleteCorruptedPuzzles() {		
		ObjectSet<IntroductionPuzzle> puzzles = db.queryByExample(IntroductionPuzzle.class);
		for(IntroductionPuzzle p : puzzles) {
			db.activate(p, 3); /* FIXME: Is this the correct depth? */
			if(p.checkConsistency() == false) {
				db.delete(p);
				Logger.error(this, "Deleting corrupted puzzle.");
			}
		}
	}
	
	public void storeAndCommit(IntroductionPuzzle puzzle) {
		/* TODO: Convert to debug code maybe when we are sure that this does not happen. Duplicate puzzles will be deleted after they
		 * expire anyway. Further, isn't there a db4o option which ensures that mID is a primary key and therefore no duplicates can exist? */
		IntroductionPuzzle existing = getByID(puzzle.getID());
		if(existing != null && existing != this)
			throw new IllegalArgumentException("Puzzle with ID " + mID + " already exists!");

		db.store(mType);
		// db.store(mDateOfInsertion); /* Not stored because it is a primitive for db4o */ 
		db.store(this);
		db.commit();
	}
	
	public IntroductionPuzzle getByID(String id) {
		synchronized(db.lock()) {
		Query q = db.query();
		q.constrain(IntroductionPuzzle.class);
		q.descend("mID").constrain(id);
		ObjectSet<IntroductionPuzzle> result = q.execute();
		
		assert(result.size() <= 1);
		
		return (result.hasNext() ? result.next() : null);
		}
	}
	
	/**
	 * Used by the IntroductionServer for downloading solutions.
	 * @param db
	 * @param i
	 * @return
	 */
	public static ObjectSet<IntroductionPuzzle> getByInserter(ExtObjectContainer db, Identity i) {
		Query q = db.query();
		q.constrain(IntroductionPuzzle.class);
		q.descend("mInserter").constrain(i);
		q.descend("iWasSolved").constrain(false);
		return q.execute();
	}
	
	/**
	 * Get puzzles which are from today. FIXME: Add a integer parameter to specify the age in days.
	 * Used by for checking whether new puzzles have to be inserted / downloaded.
	 */
	@SuppressWarnings("deprecation")
	public static List<IntroductionPuzzle> getRecentByInserter(ObjectContainer db, Identity i) {
		Date maxAge = new Date(CurrentTimeUTC.getYear()-1900, CurrentTimeUTC.getMonth(), CurrentTimeUTC.getDayOfMonth());
		Query q = db.query();
		q.constrain(IntroductionPuzzle.class);
		q.descend("mInserter").constrain(i);
		q.descend("mDateOfInsertion").constrain(maxAge).smaller().not();
		return q.execute();
	}
	
	public static IntroductionPuzzle getByRequestURI(ObjectContainer db, FreenetURI uri) throws ParseException {
		String[] tokens = uri.getDocName().split("[|]");
		Date date;
		synchronized (mDateFormat) {
			date = mDateFormat.parse(tokens[2]);
		}
		int index = Integer.parseInt(tokens[3]);
		
		Query q = db.query();
		q.constrain(IntroductionPuzzle.class);
		q.descend("mInserter").descend("id").constrain(Identity.getIdFromURI(uri));
		q.descend("mDateOfInsertion").constrain(date);
		q.descend("mIndex").constrain(index);
		ObjectSet<IntroductionPuzzle> result = q.execute();
		
		assert(result.size() <= 1);
		
		return (result.hasNext() ? result.next() : null);
	}
	
	public static IntroductionPuzzle getByInserterDateIndex(ObjectContainer db, Identity inserter, Date date, int index) {
		Query q = db.query();
		q.constrain(IntroductionPuzzle.class);
		q.descend("mInserter").descend("id").constrain(inserter.getId());
		q.descend("mDateOfInsertion").constrain(date);
		q.descend("mIndex").constrain(index);
		ObjectSet<IntroductionPuzzle> result = q.execute();
		
		assert(result.size() <= 1);
		
		return (result.hasNext() ? result.next() : null);
	}
	
	 /**
	  * Used by the IntroductionServer when a solution was downloaded to retrieve the IntroductionPuzzle object.
	  * @param db
	  * @param uri
	  * @return
	  * @throws ParseException
	  */
	public static IntroductionPuzzle getBySolutionURI(ObjectContainer db, FreenetURI uri) throws ParseException {
		String id = uri.getDocName().split("[|]")[2];
	
		return getByID(db, id);
	}
	
	/**
	 * Used by the IntroductionServer for inserting new puzzles.
	 * @param db
	 * @param id
	 * @param date
	 * @return
	 */
	public static int getFreeIndex(ObjectContainer db, OwnIdentity id, Date date) {
		Query q = db.query();
		q.constrain(IntroductionPuzzle.class);
		q.descend("mInserter").descend("id").constrain(id.getId());
		q.descend("mDateOfInsertion").constrain(new Date(date.getYear(), date.getMonth(), date.getDate()));
		q.descend("mIndex").orderDescending();
		ObjectSet<IntroductionPuzzle> result = q.execute();
		
		return result.size() > 0 ? result.next().getIndex()+1 : 0;
	}

	/**
	 * Used by the IntroductionClient for inserting solutions of solved puzzles.
	 * @param db
	 * @return
	 */
	public static ObjectSet<IntroductionPuzzle> getSolvedPuzzles(ObjectContainer db) {
		Query q = db.query();
		q.constrain(IntroductionPuzzle.class);
		q.descend("mSolver").constrain(null).identity().not();
		return q.execute();
	}
	
	public static void deleteExpiredPuzzles(ObjectContainer db) {
		Query q = db.query();
		q.constrain(IntroductionPuzzle.class);
		q.descend("mValidUntilTime").constrain(CurrentTimeUTC.getInMillis()).smaller();
		ObjectSet<IntroductionPuzzle> result = q.execute();
		
		Logger.debug(IntroductionPuzzle.class, "Deleting " + result.size() + " expired puzzles.");
		for(IntroductionPuzzle p : result)
			db.delete(p);
		
		db.commit();
	}
	
	/**
	 * Used by the introduction client to delete old puzzles and replace them with new ones.
	 * @param db
	 * @param puzzlePoolSize
	 */
	public static void deleteOldestPuzzles(ObjectContainer db, int puzzlePoolSize) {
		Query q = db.query();
		q.constrain(IntroductionPuzzle.class);
		q.descend("mSolution").constrain(null).identity(); /* FIXME: toad said constrain(null) is maybe broken. If this is true: Alternative would be: q.descend("mIdentity").constrain(OwnIdentity.class).not(); */
		q.descend("mValidUntilTime").orderAscending();
		ObjectSet<IntroductionPuzzle> result = q.execute();
		
		int deleteCount = result.size() - puzzlePoolSize;
		
		Logger.debug(IntroductionPuzzle.class, "Deleting " + deleteCount + " old puzzles.");
		while(deleteCount > 0) {
			db.delete(result.next());
			deleteCount--;
		}
		
		db.commit();
	}
}
