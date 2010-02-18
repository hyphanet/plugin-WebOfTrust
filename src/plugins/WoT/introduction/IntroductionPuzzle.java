/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WoT.introduction;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

import plugins.WoT.Identity;
import plugins.WoT.OwnIdentity;
import plugins.WoT.WoT;
import plugins.WoT.exceptions.InvalidParameterException;
import freenet.keys.FreenetURI;
import freenet.support.CurrentTimeUTC;
import freenet.support.Logger;

/**
 * An introduction puzzle is a puzzle (for example a CAPTCHA) which can be solved by a new identity to get onto the trust list
 * of already existing identities. This is the only way to get onto the web of trust if you do not know someone who will add you manually.
 */
public class IntroductionPuzzle {
	
	public static enum PuzzleType { Captcha };
	
	public static final String INTRODUCTION_CONTEXT = "Introduction";
	public static final int MINIMAL_SOLUTION_LENGTH = 5;
	public static final int MAXIMAL_SOLUTION_LENGTH = 10;
	
	protected static final SimpleDateFormat mDateFormat = new SimpleDateFormat("yyyy-MM-dd");
	
	/* Included in XML: */
	
	/**
	 * The ID of the puzzle is constructed as: random UUID + "@" + ID of the inserter. 
	 * This has to be done to prevent malicious users from inserting puzzles with the IDs of puzzles which someone else has already inserted.
	 */
	private final String mID;
	
	private final PuzzleType mType;
	
	private final String mMimeType;
	
	private final long mValidUntilTime;
	
	private final byte[] mData;
	
	/* Not included in XML, decoded from URI: */
	
	/**
	 * The inserter of the puzzle. If it is an OwnIdentity then this is a locally generated puzzle.
	 */
	private final Identity mInserter;
	
	private final Date mDateOfInsertion;
	
	private final int mIndex;

	/**
	 * Set to true after it was used for introducing a new identity. We keep used puzzles in the database until they expire for the purpose of
	 * being able to figure out free index values of new puzzles. Storing a few KiB for some days will not hurt.
	 */
	protected boolean mWasSolved;
	
	/* Supplied at creation time or by user: */

	/**
	 * The solution of the puzzle.
	 */
	protected String mSolution;
	
	/**  
	 * We store the solver of the puzzle so that we can insert the solution even if the node is shutdown directly after solving puzzles.
	 */
	protected OwnIdentity mSolver;
	
	/**
	 * Set to true after the solution was inserted (for OwnIntroductionPuzzles after the puzzle was inserted.)
	 */
	protected boolean mWasInserted; 
	
	/**
	 * Get a list of fields which the database should create an index on.
	 */
	public static String[] getIndexedFields() {
		return new String[] {"mID",
							"mInserter",
							"mDateOfInsertion",
							"mValidUntilTime",
							"mWasSolved",
							"mWasInserted"};
	}
	
	/**
	 * For construction from a received puzzle.
	 * @param newType
	 * @param newData
	 */
	@SuppressWarnings("deprecation")
	public IntroductionPuzzle(Identity newInserter, String newID, PuzzleType newType, String newMimeType, byte[] newData,
			Date myDateOfInsertion, long myValidUntilTime, int myIndex) {

		assert(newInserter != null);
		assert(newID != null);
		assert(newType != null);
		assert(newMimeType != null);
		assert(!newMimeType.equals(""));
		assert(newData!=null);
		assert(newData.length!=0);
		assert(myValidUntilTime > CurrentTimeUTC.getInMillis());
		assert(myDateOfInsertion != null);
		assert(myDateOfInsertion.getTime() <= CurrentTimeUTC.getInMillis());
		assert(myIndex >= 0);
		
		mID = newID;
		mInserter = newInserter;
		mType = newType;
		mMimeType = newMimeType;
		mDateOfInsertion = new Date(myDateOfInsertion.getYear(), myDateOfInsertion.getMonth(), myDateOfInsertion.getDate());
		mValidUntilTime = myValidUntilTime;
		mIndex = myIndex;
		mData = newData;
		mWasSolved = false; mSolution = null; mSolver = null;
		mWasInserted = false;

		if(checkConsistency() == false)
			throw new IllegalArgumentException("Corrupted puzzle received.");
	}
	
	/**
	 * Used by the IntroductionClient for guessing the request URIs of puzzles to be able to download them.
	 */
	public static FreenetURI generateRequestURI(Identity inserter, Date dateOfInsertion, int index) {
		assert(dateOfInsertion.before(CurrentTimeUTC.get()));
		assert(index >= 0);
		
		String dayOfInsertion;
		synchronized (mDateFormat) {
			dayOfInsertion = mDateFormat.format(dateOfInsertion);
		}
		FreenetURI baseURI = inserter.getRequestURI().setKeyType("SSK");
		baseURI = baseURI.setDocName(WoT.WOT_NAME + "|" + INTRODUCTION_CONTEXT + "|" + dayOfInsertion + "|" + index);
		return baseURI.setMetaString(null);
	}
	
	/**
	 * Get the URI where this puzzle was downloaded from.
	 */
	public FreenetURI getRequestURI() {
		return generateRequestURI(mInserter, mDateOfInsertion, mIndex);
	}

	/**
	 * Get the date of a puzzle from it's URI.
	 * 
	 * Used for being able to query the database for an existing IntroductionPuzzleObject when a new one was downloaded. 
	 */
	public static Date getDateFromRequestURI(FreenetURI requestURI) throws ParseException {
		String tokens[] = requestURI.getDocName().split("[|]");
		synchronized (mDateFormat) {
			return mDateFormat.parse(tokens[2]);
		}
	}

	/**
	 * Get the index of a puzzle from it's URI.
	 * 
	 * Used for being able to query the database for an existing IntroductionPuzzleObject when a new one was downloaded. 
	 */
	public static int getIndexFromRequestURI(FreenetURI requestURI) {
		String tokens[] = requestURI.getDocName().split("[|]");
		return Integer.parseInt(tokens[3]);
	}
	
	/**
	 * Get the ID of a puzzle from the URI of it's solution. Used for querying the database for the associated puzzle after a solution
	 * was fetched or inserted successfully.
	 */
	public static String getIDFromSolutionURI(FreenetURI uri) {
		return uri.getDocName().split("[|]")[2];
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
	
	public byte[] getData() {
		return mData;
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
		if(mWasSolved)
			throw new InvalidParameterException("Puzzle is already solved!"); /* TODO: create a special exception for that */
		
		mWasSolved = true;
		mSolver = solver;
		mSolution = solution;
	}
	
	public synchronized boolean wasSolved() {
		return mWasSolved;
	}

	/* TODO: This function probably does not need to be synchronized because the current "outside" code will not use it without locking.
	 * However, if one only knows this class and not how it is used by the rest, its logical to synchronize it. */
	/**
	 * Get the OwnIdentity which solved this puzzle. Used by the IntroductionClient for inserting identity introductions.
	 */
	public synchronized OwnIdentity getSolver() {
		assert(mWasSolved);
		return mSolver;
	}

	/**
	 * Get the URI at which to insert the solution of this puzzle if you have solved it / at which the solution is trying to be fetched
	 * 
	 * 
	 * Format: "KSK@WoT|Introduction|id|guessOfSolution"
	 * id = the ID of the puzzle (which itself contains the UUID of the puzzle and the ID of the inserter.)
	 * guessOfSolution = the guess of the solution which is passed to the function.
	 */
	public synchronized FreenetURI getSolutionURI() {
		if(mSolution == null)
			throw new RuntimeException("The puzzle is not solved.");
		
		return new FreenetURI("KSK",	WoT.WOT_NAME + "|" +
										INTRODUCTION_CONTEXT + "|" +
										mID + "|" +
										mSolution);
	}
	
	public synchronized boolean wasInserted() {
		return mWasInserted;
	}
	
	public synchronized void setInserted() {
		if(mWasInserted)
			throw new RuntimeException("The puzzle was already inserted.");
		
		mWasInserted = true;
	}
	
	/* TODO: Write an unit test which uses this function :) */
	/* TODO: This code sucks, checkConsistency should throw a descriptive message */
	@SuppressWarnings("deprecation")
	public boolean checkConsistency() {
		boolean result = true;
		if(mID == null) 
			{ Logger.error(this, "mID == null!"); result = false; }
		else { /* Verify the UID */
			if(mInserter != null) {
				String inserterID = mInserter.getID();
				if(mID.endsWith(inserterID) == false) { Logger.error(this, "mID does not start with InserterID: " + mID); result = false; }
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
		
		return result;
	}

}
