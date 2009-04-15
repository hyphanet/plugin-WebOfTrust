/*
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */
package plugins.WoT.introduction;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.UUID;

import plugins.WoT.CurrentTimeUTC;
import plugins.WoT.Identity;
import plugins.WoT.OwnIdentity;
import plugins.WoT.WoT;
import plugins.WoT.exceptions.InvalidParameterException;
import freenet.keys.FreenetURI;
import freenet.support.Logger;

public final class IntroductionPuzzle {
	
	public static enum PuzzleType { Captcha };
	
	public static final String INTRODUCTION_CONTEXT = "Introduction";
	public static final int MINIMAL_SOLUTION_LENGTH = 5;
	
	private static final SimpleDateFormat mDateFormat = new SimpleDateFormat("yyyy-MM-dd");
	
	/* Included in XML: */
	
	/**
	 * The ID of the puzzle is constructed as the concatenation of the  ID of the inserter and a random UUID
	 * This has to be done to prevent malicious users from inserting puzzles with the IDs of puzzles which someone else has already inserted.
	 */
	private final String mID;
	
	private final PuzzleType mType;
	
	private final String mMimeType;
	
	private final long mValidUntilTime;
	
	private final byte[] mData;
	
	/* Not included in XML, decoded from URI: */
	
	private final Identity mInserter;
	
	private final Date mDateOfInsertion;
	
	private final int mIndex;
	
	/* Supplied at creation time or by user: */

	/**
	 * We store the solver of the puzzle so that we can insert the solution even if the node is shutdown directly after solving puzzles.
	 */
	private OwnIdentity mSolver = null;
	
	private String mSolution = null;


	/**
	 * Set to true after it was used for introducing a new identity. We keep used puzzles in the database until they expire for the purpose of
	 * being able to figure out free index values of new puzzles. Storing a few KiB for some days will not hurt.
	 */
	private boolean iWasSolved = false;
	
	/**
	 * Get a list of fields which the database should create an index on.
	 */
	public static String[] getIndexedFields() {
		/* FIXME: Find out whether indexes are sorted, if not, remove the date and validUntilTime */
		return new String[] {"mID", "mInserter", "mDateOfInsertion", "mValidUntilTime", "mSolver"};
	}
	
	/**
	 * For construction from a received puzzle.
	 * @param newType
	 * @param newData
	 */
	@SuppressWarnings("deprecation")
	public IntroductionPuzzle(Identity newInserter, String newID, PuzzleType newType, String newMimeType, byte[] newData,
			long myValidUntilTime, Date myDateOfInsertion, int myIndex) {

		assert(	newInserter != null && newID != null && newType != null && newMimeType != null && !newMimeType.equals("") &&
				newData!=null && newData.length!=0 && myValidUntilTime > CurrentTimeUTC.getInMillis() && myDateOfInsertion != null &&
				myDateOfInsertion.getTime() < CurrentTimeUTC.getInMillis()&& myIndex >= 0);
		
		mID = newID;
		mInserter = newInserter;
		mType = newType;
		mMimeType = newMimeType;
		mData = newData;
		mSolution = null;
		mDateOfInsertion = new Date(myDateOfInsertion.getYear(), myDateOfInsertion.getMonth(), myDateOfInsertion.getDate());
		mValidUntilTime = myValidUntilTime;
		mIndex = myIndex;
		
		if(checkConsistency() == false)
			throw new IllegalArgumentException("Corrupted puzzle received.");
	}
	
	/**
	 * For construction of a puzzle which is meant to be inserted.
	 * @param newType
	 * @param newData
	 */
	public IntroductionPuzzle(Identity newInserter, PuzzleType newType, String newMimeType, byte[] newData, String newSolution,
			Date newDateOfInsertion, int myIndex) {
		
		this(newInserter, newInserter.getId() + UUID.randomUUID().toString(), newType, newMimeType, newData,
				newDateOfInsertion.getTime() + IntroductionServer.PUZZLE_INVALID_AFTER_DAYS * 24 * 60 * 60 * 1000, newDateOfInsertion, myIndex);
		
		assert(newSolution!=null && newSolution.length()>=MINIMAL_SOLUTION_LENGTH);
		
		mSolution = newSolution;
		
		if(checkConsistency() == false)
			throw new IllegalArgumentException("Trying to costruct a corrupted puzzle");
	}
	
	public String getID() {
		return mID;
	}
	
	public PuzzleType getType() {
		return mType;
	}

	public String getMimeType() {
		return mMimeType;
	}

	/**
	 * Get the URI at which to insert this puzzle.
	 * SSK@asdfasdf...|WoT|introduction|yyyy-MM-dd|#
	 * 
	 * # = index of the puzzle.
	 */
	public FreenetURI getInsertURI() {
		assert(mSolution != null); /* This function should only be needed by the introduction server, not by clients. */
		
		/* FIXME: I did not really understand the javadoc of FreenetURI. Please verify that the following code actually creates an URI
		 * which looks like the one I specified in the javadoc above this function. Thanks. */
		String dayOfInsertion;
		synchronized (mDateFormat) {
			dayOfInsertion = mDateFormat.format(mDateOfInsertion);
		}
		FreenetURI baseURI = ((OwnIdentity)mInserter).getInsertURI().setKeyType("SSK");
		baseURI = baseURI.setDocName(WoT.WOT_NAME + "|" + INTRODUCTION_CONTEXT + "|" + dayOfInsertion + "|" + mIndex);
		return baseURI.setMetaString(null);
	}

	public FreenetURI getRequestURI() {
		return generateRequestURI(mInserter, mDateOfInsertion, mIndex);
	}

	public static FreenetURI generateRequestURI(Identity inserter, Date dateOfInsertion, int index) {
		assert(dateOfInsertion.before(CurrentTimeUTC.get()));
		assert(index >= 0);
		
		/* FIXME: I did not really understand the javadoc of FreenetURI. Please verify that the following code actually creates an URI
		 * which looks like the one I specified in the javadoc above this function. Thanks. */
		String dayOfInsertion;
		synchronized (mDateFormat) {
			dayOfInsertion = mDateFormat.format(dateOfInsertion);
		}
		FreenetURI baseURI = inserter.getRequestURI().setKeyType("SSK");
		baseURI = baseURI.setDocName(WoT.WOT_NAME + "|" + INTRODUCTION_CONTEXT + "|" + dayOfInsertion + "|" + index);
		return baseURI.setMetaString(null);
	}

	public static Date getDateFromRequestURI(FreenetURI requestURI) throws ParseException {
		String tokens[] = requestURI.getDocName().split("[|]");
		synchronized (mDateFormat) {
			return mDateFormat.parse(tokens[2]);
		}
	}

	public static int getIndexFromRequestURI(FreenetURI requestURI) {
		String tokens[] = requestURI.getDocName().split("[|]");
		return Integer.parseInt(tokens[3]);
	}

	/**
	 * Get the URI at which to look for a solution of this puzzle (if someone solved it)
	 */
	public FreenetURI getSolutionURI() {
		return getSolutionURI(mSolution);
	}
	
	/**
	 * Get the URI at which to insert the solution of this puzzle.
	 * 
	 * Format: "KSK@WoT|Introduction|id|guessOfSolution"
	 * id = the ID of the puzzle (which itself contains the ID of the inserter and the UUID of the puzzle)
	 * guessOfSolution = the guess of the solution which is passed to the function.
	 */
	public FreenetURI getSolutionURI(String guessOfSolution) {
		return new FreenetURI("KSK",	WoT.WOT_NAME + "|" +
										INTRODUCTION_CONTEXT + "|" +
										mID + "|" +
										guessOfSolution);
	}
	
	public static String getIDFromSolutionURI(FreenetURI uri) {
		return uri.getDocName().split("[|]")[2];
	}
	
	public byte[] getData() {
		return mData;
	}
	
	/* TODO: This function probably does not need to be synchronized because the current "outside" code will not use it without locking.
	 * However, if one only knows this class and not how it is used by the rest, its logical to synchronize it. */
	/**
	 * Get the solution of the puzzle. Null if the puzzle was received and not locally generated.
	 */
	public synchronized String getSolution() {
		assert(mSolution != null); /* Whoever uses this function should not need to call it when there is no solution available */
		return mSolution;
	}

	/* TODO: This function probably does not need to be synchronized because the current "outside" code will not use it without locking.
	 * However, if one only knows this class and not how it is used by the rest, its logical to synchronize it. */
	/**
	 * Get the OwnIdentity which solved this puzzle. Used by the IntroductionClient for inserting solutions.
	 */
	public synchronized OwnIdentity getSolver() {
		assert(mSolver != null);
		return mSolver;
	}
	
	/**
	 * Used by the IntroductionServer to mark a puzzle as solved.
	 */
	public synchronized void setSolved() {
		iWasSolved = true;
	}
	
	/* TODO: This function probably does not need to be synchronized because the current "outside" code will not use it without locking.
	 * However, if one only knows this class and not how it is used by the rest, its logical to synchronize it. */
	/**
	 * Used by the IntroductionClient to mark a puzzle as solved
	 * 
	 * @param solver The identity which solved the puzzle correctly.
	 * @param solution The solution which was passed by the solver.
	 * @throws InvalidParameterException If the puzzle was already solved.
	 */
	public synchronized void setSolved(OwnIdentity solver, String solution) throws InvalidParameterException {
		if(iWasSolved)
			throw new InvalidParameterException("Puzzle is already solved!"); /* TODO: create a special exception for that */
		
		iWasSolved = true;
		mSolver = solver;
		mSolution = solution;
	}
	
	public Identity getInserter() {
		return mInserter;
	}
	
	public Date getDateOfInsertion() {
		return mDateOfInsertion;
	}
	
	public long getValidUntilTime() {
		return mValidUntilTime;
	}
	
	public int getIndex() {
		return mIndex;
	}
	
	/* TODO: Write an unit test which uses this function :) */
	/* TODO: This code sucks, checkConsistency should throw a descriptive message */
	/* FIXME: check for validity of the jpeg */
	@SuppressWarnings("deprecation")
	public boolean checkConsistency() {
		boolean result = true;
		if(mID == null) 
			{ Logger.error(this, "mID == null!"); result = false; }
		else { /* Verify the UID */
			if(mInserter != null) {
				String inserterID = mInserter.getId();
				if(mID.startsWith(inserterID) == false) { Logger.error(this, "mID does not start with InserterID: " + mID); result = false; }
				/* Verification that the rest of the ID is an UUID is not necessary: If a client inserts a puzzle with the ID just being his
				 * identity ID (or other bogus stuff) he will just shoot himself in the foot by possibly only allowing 1 puzzle of him to
				 * be available because the databases of the downloaders check whether the ID already exists. */
			}
		}
		if(mType == null)
			{ Logger.error(this, "mType == null!"); result = false; }
		if(mMimeType == null || !mMimeType.equals("image/jpeg"))
			{ Logger.error(this, "mMimeType == " + mMimeType); result = false; }
		if(new Date(mValidUntilTime).before(new Date(2008-1900, 10, 10)))
			{ Logger.error(this, "mValidUntilTime == " + new Date(mValidUntilTime)); result = false; }
		if(mData == null || mData.length<100)
			{ Logger.error(this, "mData == " + mData); result = false; }
		if(mInserter == null)
			{ Logger.error(this, "mInserter == null"); result = false; }
		if(mDateOfInsertion == null || mDateOfInsertion.before(new Date(2008-1900, 10, 10)) || mDateOfInsertion.after(CurrentTimeUTC.get()))
			{ Logger.error(this, "mDateOfInsertion == " + mDateOfInsertion + "currentTime == " + CurrentTimeUTC.get()); result = false; }
		if(mIndex < 0)
			{ Logger.error(this, "mIndex == " + mIndex); result = false; }
		if(iWasSolved == true && (mSolver == null || mSolution == null))
			{ Logger.error(this, "iWasSolved but mSolver == " + mSolver + ", " + "mSolution == " + mSolution); result = false; }
		
		return result;
	}

}
