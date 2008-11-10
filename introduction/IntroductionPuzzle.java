/*
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */
package plugins.WoT.introduction;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.query.Query;

import plugins.WoT.Identity;

public class IntroductionPuzzle {
	
	enum PuzzleType {
		Image,
		Audio
	};
	
	private final PuzzleType mType;
	
	private final String mMimeType;
	
	private final String mFilename;
	
	private final byte[] mData;
	
	private final String mSolution;
	
	private final Identity mInserter;
	
	private final long mValidUntilTime;
	
	/* FIXME: wire this in */
	/**
	 * Get a list of fields which the database should create an index on.
	 */
	public static String[] getIndexedFields() {
		return new String[] {"mInserter"};
	}
	
	/**
	 * For construction from a received puzzle.
	 * @param newType
	 * @param newData
	 */
	public IntroductionPuzzle(Identity newInserter, PuzzleType newType, String newMimeType, String newFilename, byte[] newData, long newValidUntilTime) {
		mInserter = newInserter;
		mType = newType;
		mMimeType = newMimeType;
		mFilename = newFilename;
		mData = newData;
		mSolution = null;
		mValidUntilTime = newValidUntilTime;
	}
	
	/**
	 * For construction of a puzzle which is meant to be inserted.
	 * @param newType
	 * @param newData
	 */
	public IntroductionPuzzle(Identity newInserter, PuzzleType newType, String newMimeType, String newFilename, byte[] newData, String newSolution) {
		mInserter = newInserter;
		mType = newType;
		mMimeType = newMimeType;
		mFilename = newFilename;
		mData = newData;
		mSolution = newSolution;
		mValidUntilTime = System.currentTimeMillis() + IntroductionServer.PUZZLE_INVALID_AFTER_DAYS * 24 * 60 * 60 * 1000;
	}
	
	public PuzzleType getPuzzleType() {
		return mType;
	}
	
	public String getMimeType() {
		return mMimeType;
	}
	
	public String getFilename() {
		/* FIXME: include date etc. */
		return mFilename;
	}
	
	public byte[] getPuzzle() {
		return mData;
	}
	
	/**
	 * Get the solution of the puzzle. Null if the puzzle was received and not locally generated.
	 */
	public String getSolution() {
		assert(mSolution != null); /* Whoever uses this function should not need to call it when there is no solution available */
		return mSolution;
	}
	
	public Identity getInserter() {
		return mInserter;
	}
	
	public long getValidUntilTime() {
		return mValidUntilTime;
	}
	
	
	public void store(ObjectContainer db) {
		db.store(this);
		db.commit();
	}
	
	public static void deleteOldPuzzles(ObjectContainer db) {
		Query q = db.query();
		q.constrain(IntroductionPuzzle.class);
		q.descend("mValidUntilTime").constrain(System.currentTimeMillis()).smaller();
		ObjectSet<IntroductionPuzzle> result = q.execute();
		
		for(IntroductionPuzzle p : result)
			db.delete(p);
		
		db.commit();
	}
}
