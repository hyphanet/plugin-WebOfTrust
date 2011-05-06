package plugins.WebOfTrust.introduction;

import java.util.Date;
import java.util.UUID;

import plugins.WebOfTrust.Identity;
import plugins.WebOfTrust.OwnIdentity;
import plugins.WebOfTrust.WebOfTrust;
import freenet.keys.FreenetURI;
import freenet.support.Logger;
import freenet.support.TimeUtil;

public class OwnIntroductionPuzzle extends IntroductionPuzzle {
	
	/**
	 * For construction of a puzzle which is meant to be inserted.
	 */
	public OwnIntroductionPuzzle(OwnIdentity newInserter, PuzzleType newType, String newMimeType, byte[] newData, String newSolution,
			Date newDateOfInsertion, int myIndex) {
		this(newInserter, UUID.randomUUID().toString() + "@" + newInserter.getID(), newType, newMimeType, newData, newSolution, newDateOfInsertion, myIndex);
	}
	
	/**
	 * Clone() needs to set the ID.
	 */
	private OwnIntroductionPuzzle(OwnIdentity newInserter, String newID, PuzzleType newType, String newMimeType, byte[] newData, String newSolution,
			Date newDateOfInsertion, int myIndex) {
		super(newInserter, newID, newType, newMimeType, newData, newDateOfInsertion,
				new Date(TimeUtil.setTimeToZero(newDateOfInsertion).getTime() + IntroductionServer.PUZZLE_INVALID_AFTER_DAYS * 24 * 60 * 60 * 1000), 
				myIndex);
		
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
		checkedActivate(1); // Needed for the getters below anyway so we can do it here for the assert()
		assert(mWasInserted == false);  // checkedActivate(depth) is not needed, boolean is a db4o primitive type
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
		checkedActivate(1);
		
		if(mWasSolved)
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
		checkedActivate(1);
		
		if(mWasSolved)
			throw new RuntimeException("The puzzle was already solved by " + mSolver);
		
		if(mSolver != null)
			throw new RuntimeException("mSolver==" + mSolver);
		
		mWasSolved = true;
		mSolver = solver;
	}
	
	/**
	 * Get the Identity which solved this puzzle. It is set by the IntroductionServer when a puzzle solution to this puzzle was fetched. 
	 * Returns null if the puzzle was solved but the parsing of the solution failed.
	 */
	public Identity getSolver() {
		return super.getSolver();
	}
	
	@Override
	public void startupDatabaseIntegrityTest() throws Exception {
		checkedActivate(1);
		super.startupDatabaseIntegrityTest();
		
		if(mSolution==null)
			throw new NullPointerException("mSolution==null");
		
		// We do not throw in the following case because it might happen if the node forgets to call the on-insert-succeeded function
		if(mWasSolved && !mWasInserted)
			Logger.error(this, "mWasSolved==true but mWasInserted==false");
	}
	
	
	@Override
	public OwnIntroductionPuzzle clone() {
		// TODO: Optimization: If this is used often, make it use the member variables instead of the getters - do proper activation before.
		// checkedActivate(depth) for mSolution is not needed, String is a db4o primitive type
		final OwnIntroductionPuzzle copy = new OwnIntroductionPuzzle((OwnIdentity)getInserter(), getID(), getType(), getMimeType(), getData(), getSolution(), getDateOfInsertion(), getIndex());
		
		if(wasSolved()) {
			if(getSolver() != null)
				copy.setSolved(getSolver());
			else
				copy.setSolved();
		}
		
		if(wasInserted()) copy.setInserted();
		
		copy.initializeTransient(mWebOfTrust);
		
		return copy;
	}
	
	@Override
	public boolean equals(Object o) {
		return super.equals(o);
	}
}
