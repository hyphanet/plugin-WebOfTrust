package plugins.WebOfTrust.introduction;

import java.util.Date;
import java.util.UUID;

import plugins.WebOfTrust.Identity;
import plugins.WebOfTrust.OwnIdentity;
import plugins.WebOfTrust.WebOfTrust;
import freenet.keys.FreenetURI;
import freenet.support.Logger;

public class OwnIntroductionPuzzle extends IntroductionPuzzle {
	
	/**
	 * For construction of a puzzle which is meant to be inserted.
	 */
	@SuppressWarnings("deprecation")
	public OwnIntroductionPuzzle(OwnIdentity newInserter, PuzzleType newType, String newMimeType, byte[] newData, String newSolution,
			Date newDateOfInsertion, int myIndex) {
		
		super(newInserter, UUID.randomUUID().toString() + "@" + newInserter.getID(), newType, newMimeType, newData, newDateOfInsertion, 
				 new Date(new Date(newDateOfInsertion.getYear(), newDateOfInsertion.getMonth(), newDateOfInsertion.getDate()).getTime() 
				 + IntroductionServer.PUZZLE_INVALID_AFTER_DAYS * 24 * 60 * 60 * 1000), myIndex);
		
		if(newSolution.length() < MINIMAL_SOLUTION_LENGTH)
			throw new IllegalArgumentException("Solution is too short (" + newSolution.length() + "), minimal length is " + 
					MINIMAL_SOLUTION_LENGTH);
		
		mSolver = null;
		mSolution = newSolution;
	}
	
	/**
	 * Get the URI at which to insert this puzzle.
	 * SSK@asdfasdf...|WebOfTrust.WOT_NAME|IntroductionPuzzle.INTRODUCTION_CONTEXT|yyyy-MM-dd|#
	 * 
	 * # = index of the puzzle.
	 */
	public FreenetURI getInsertURI() {
		assert(mWasInserted == false);
		assert(mWasSolved == false);
		
		String dayOfInsertion;
		synchronized (mDateFormat) {
			dayOfInsertion = mDateFormat.format(getDateOfInsertion());
		}
		FreenetURI baseURI = ((OwnIdentity)getInserter()).getInsertURI().setKeyType("SSK");
		baseURI = baseURI.setDocName(WebOfTrust.WOT_NAME + "|" + INTRODUCTION_CONTEXT + "|" + dayOfInsertion + "|" + getIndex());
		return baseURI.setMetaString(null);
	}
	
	/**
	 * Used by the IntroductionServer to mark a puzzle as solved when the parsing of the solution-XML failed 
	 * so it will not try to fetch solutions for it anymore.
	 * Sets the solver to null.
	 */
	public synchronized void setSolved() {
		if(wasSolved())
			throw new RuntimeException("The puzzle was already solved by " + mSolver);
		
		if(mSolver != null)
			throw new RuntimeException("mSolver==" + mSolver);
		
		mWasSolved = true;
		mSolver = null;
	}
	
	/**
	 * Used by the IntroductionServer to mark a puzzle as solved when importing an identity introduction.
	 */
	public synchronized void setSolved(Identity solver) {
		if(wasSolved())
			throw new RuntimeException("The puzzle was already solved by " + mSolver);
		
		if(mSolver != null)
			throw new RuntimeException("mSolver==" + mSolver);
		
		mWasSolved = true;
		mSolver = solver;
	}
	
	@Override
	public void startupDatabaseIntegrityTest() throws Exception {
		checkedActivate(2);
		super.startupDatabaseIntegrityTest();
		
		if(mSolution==null)
			throw new NullPointerException("mSolution==null");
		
		// We do not throw in the following case because it might happen if the node forgets to call the on-insert-succeeded function
		if(mWasSolved && !mWasInserted)
			Logger.error(this, "mWasSolved==true but mWasInserted==false");
	}
	
}
