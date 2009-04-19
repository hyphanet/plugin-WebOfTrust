package plugins.WoT.introduction;

import java.util.Date;
import java.util.UUID;

import plugins.WoT.OwnIdentity;
import plugins.WoT.WoT;
import freenet.keys.FreenetURI;

public class OwnIntroductionPuzzle extends IntroductionPuzzle {
	
	/**
	 * For construction of a puzzle which is meant to be inserted.
	 * @param newType
	 * @param newData
	 */
	public OwnIntroductionPuzzle(OwnIdentity newInserter, PuzzleType newType, String newMimeType, byte[] newData, String newSolution,
			Date newDateOfInsertion, int myIndex) {
		
		super(newInserter, UUID.randomUUID().toString() + "@" + newInserter.getID(), newType, newMimeType, newData,
				newDateOfInsertion.getTime() + IntroductionServer.PUZZLE_INVALID_AFTER_DAYS * 24 * 60 * 60 * 1000,
				newDateOfInsertion, myIndex);
		
		if(newSolution.length() < MINIMAL_SOLUTION_LENGTH)
			throw new IllegalArgumentException("Solution is too short (" + newSolution.length() + "), minimal length is " + 
					MINIMAL_SOLUTION_LENGTH);
		
		mSolver = null;
		mSolution = newSolution;
		
		if(checkConsistency() == false)
			throw new IllegalArgumentException("Trying to costruct a corrupted puzzle");
	}
	
	/**
	 * Get the URI at which to insert this puzzle.
	 * SSK@asdfasdf...|WoT|introduction|yyyy-MM-dd|#
	 * 
	 * # = index of the puzzle.
	 */
	public FreenetURI getInsertURI() {
		assert(mWasInserted == false && mWasSolved == false);
		
		String dayOfInsertion;
		synchronized (mDateFormat) {
			dayOfInsertion = mDateFormat.format(getDateOfInsertion());
		}
		FreenetURI baseURI = ((OwnIdentity)getInserter()).getInsertURI().setKeyType("SSK");
		baseURI = baseURI.setDocName(WoT.WOT_NAME + "|" + INTRODUCTION_CONTEXT + "|" + dayOfInsertion + "|" + getIndex());
		return baseURI.setMetaString(null);
	}
	
	/**
	 * Used by the IntroductionServer to mark a puzzle as solved so it will not try to fetch solutions for it anymore.
	 */
	public synchronized void setSolved() {
		if(mWasSolved)
			throw new RuntimeException("The puzzle was already solved by " + mSolver);
		
		mWasSolved = true;
	}
	
}
