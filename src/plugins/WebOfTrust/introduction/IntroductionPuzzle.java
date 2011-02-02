/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.introduction;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.TimeZone;

import plugins.WebOfTrust.Identity;
import plugins.WebOfTrust.OwnIdentity;
import plugins.WebOfTrust.Persistent;
import plugins.WebOfTrust.TimeUtil;
import plugins.WebOfTrust.WebOfTrust;
import plugins.WebOfTrust.exceptions.InvalidParameterException;
import freenet.keys.FreenetURI;
import freenet.support.CurrentTimeUTC;

/**
 * An introduction puzzle is a puzzle (for example a CAPTCHA) which can be solved by a new identity to get onto the trust list
 * of already existing identities. This is the only way to get onto the web of trust if you do not know someone who will add you manually.
 */
public class IntroductionPuzzle extends Persistent {
	
	public static enum PuzzleType { Captcha };
	
	public static transient final String INTRODUCTION_CONTEXT = "Introduction";
	public static transient final int MINIMAL_SOLUTION_LENGTH = 5;
	public static transient final int MAXIMAL_SOLUTION_LENGTH = 10;
	
	protected static transient final SimpleDateFormat mDateFormat = new SimpleDateFormat("yyyy-MM-dd");
	
	{
		mDateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
	}
	
	/* Included in XML: */
	
	/**
	 * The ID of the puzzle is constructed as: random UUID + "@" + ID of the inserter. 
	 * This has to be done to prevent malicious users from inserting puzzles with the IDs of puzzles which someone else has already inserted.
	 */
	@IndexedField
	private final String mID;
	
	private final PuzzleType mType;
	
	private final String mMimeType;
	
	@IndexedField
	private final Date mValidUntilDate;
	
	private final byte[] mData;
	
	/* Not included in XML, decoded from URI: */
	
	/**
	 * The inserter of the puzzle. If it is an OwnIdentity then this is a locally generated puzzle.
	 */
	@IndexedField
	private final Identity mInserter;
	
	@IndexedField
	private final Date mDateOfInsertion;
	
	private final int mIndex;

	/**
	 * Set to true after it was used for introducing a new identity. We keep used puzzles in the database until they expire for the purpose of
	 * being able to figure out free index values of new puzzles. Storing a few KiB for some days will not hurt.
	 */
	@IndexedField
	protected boolean mWasSolved;
	
	/* Supplied at creation time or by user: */

	/**
	 * The solution of the puzzle.
	 */
	protected String mSolution;
	
	/**  
	 * Class IntroductionPuzzle: We store the solver of the puzzle so that we can insert the solution even if the node is shutdown directly after solving puzzles.
	 * Class OwnIntroductionPuzzle: We store the solver of a puzzle for future use, we do not use it yet.
	 */
	protected Identity mSolver;
	
	/**
	 * Set to true after the solution was inserted (for OwnIntroductionPuzzles after the puzzle was inserted.)
	 */
	@IndexedField
	protected boolean mWasInserted; 

	
	/**
	 * For construction from a received puzzle.
	 * @param newType
	 * @param newData
	 */
	public IntroductionPuzzle(Identity newInserter, String newID, PuzzleType newType, String newMimeType, byte[] newData,
			Date myDateOfInsertion, Date myExpirationDate, int myIndex) {
		
		if(newInserter == null)
			throw new NullPointerException("No inserter specified.");
		
		if(!newInserter.hasContext(INTRODUCTION_CONTEXT))
			throw new IllegalArgumentException("The given inserter does not have the " + INTRODUCTION_CONTEXT + " context.");
		
		if(newID == null)
			throw new NullPointerException("No puzzle ID specified");

		if(!newID.endsWith(newInserter.getID()))
			throw new IllegalArgumentException("Invalid puzzle ID, does not end with inserter ID: " + newID);
		
		// Verification that the rest of the ID is an UUID is not necessary: If a client inserts a puzzle with the ID just being his
		// identity ID (or other bogus stuff) he will just shoot himself in the foot by possibly only allowing 1 puzzle of him to
		// be available because the databases of the downloaders check whether the ID already exists.

		if(newType == null)
			throw new NullPointerException("No puzzle type specified.");

		if(newMimeType == null)
			throw new NullPointerException("No mimetype specified.");
		
		if(!newMimeType.equals("image/jpeg"))
			throw new IllegalArgumentException("Invalid mime type specified.");

		if(newData == null || newData.length == 0)
			throw new NullPointerException("No data specified.");
		
		if(myExpirationDate.before(CurrentTimeUTC.get()))
			throw new IllegalArgumentException("The puzzle is expired already.");
			
		if(myDateOfInsertion == null)
			throw new NullPointerException("No date of insertion specified.");
		
		if(myDateOfInsertion.after(CurrentTimeUTC.get()))
			throw new IllegalArgumentException("Date of insertion is in the future.");
					
		if(myIndex < 0)
			throw new IllegalArgumentException("Puzzle index is negative");
		
		mID = newID;
		mInserter = newInserter;
		mType = newType;
		mMimeType = newMimeType;
		mDateOfInsertion = TimeUtil.setTimeToZero(myDateOfInsertion);
		mValidUntilDate = myExpirationDate;
		mIndex = myIndex;
		mData = newData;
		mWasSolved = false; mSolution = null; mSolver = null;
		mWasInserted = false;
	}
	
	/**
	 * Used by the IntroductionClient for guessing the request URIs of puzzles to be able to download them.
	 */
	public static FreenetURI generateRequestURI(Identity inserter, Date dateOfInsertion, int index) {
		assert(!dateOfInsertion.after(CurrentTimeUTC.get()));
		assert(index >= 0);
		
		String dayOfInsertion;
		synchronized (mDateFormat) {
			dayOfInsertion = mDateFormat.format(dateOfInsertion);
		}
		FreenetURI baseURI = inserter.getRequestURI().setKeyType("SSK");
		baseURI = baseURI.setDocName(WebOfTrust.WOT_NAME + "|" + INTRODUCTION_CONTEXT + "|" + dayOfInsertion + "|" + index);
		return baseURI.setMetaString(null);
	}
	
	/**
	 * Get the URI where this puzzle was downloaded from.
	 */
	public FreenetURI getRequestURI() {
		return generateRequestURI(getInserter(), getDateOfInsertion(), getIndex());
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
		// checkedActivate(depth) is not needed, String is a db4o primitive type
		return mID;
	}
	
	public PuzzleType getType() {
		checkedActivate(2); // TODO: Optimization: Do we really need to activate for enums?
		return mType;
	}

	public String getMimeType() {
		// checkedActivate(depth) is not needed, String is a db4o primitive type
		return mMimeType;
	}
	
	public byte[] getData() {
		// checkedActivate(depth) is not needed, byte[] is a db4o primitive type
		return mData;
	}
	
	public Identity getInserter() {
		checkedActivate(2);
		mInserter.initializeTransient(mWebOfTrust);
		return mInserter;
	}
	
	public Date getDateOfInsertion() {
		// checkedActivate(depth) is not needed, Date is a db4o primitive type
		return mDateOfInsertion;
	}
	
	public Date getValidUntilDate() {
		// checkedActivate(depth) is not needed, long is a db4o primitive type
		return mValidUntilDate;
	}
	
	public int getIndex() {
		// checkedActivate(depth) is not needed, int is a db4o primitive type
		return mIndex;
	}

	/**
	 * Used by the IntroductionClient to mark a puzzle as solved
	 * 
	 * @param solver The identity which solved the puzzle correctly.
	 * @param solution The solution which was passed by the solver.
	 * @throws InvalidParameterException If the puzzle was already solved.
	 */
	public synchronized void setSolved(OwnIdentity solver, String solution) {
		if(wasSolved())
			throw new IllegalStateException("Puzzle is already solved!"); 
		
		mWasSolved = true;
		mSolver = solver;
		mSolution = solution;
	}
	
	public synchronized boolean wasSolved() {
		// checkedActivate(depth) is not needed, boolean is a db4o primitive type
		return mWasSolved;
	}

	/**
	 * Get the Identity which solved this puzzle. Used by the IntroductionClient for inserting identity introductions.
	 */
	public synchronized Identity getSolver() {
		if(!wasSolved())
			throw new IllegalStateException("The puzzle is not solved");
		
		checkedActivate(2);
		mSolver.initializeTransient(mWebOfTrust);
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
		// checkedActivate(depth) is not needed, String is a db4o primitive type
		
		if(mSolution == null)
			throw new IllegalStateException("The puzzle is not solved.");
		
		return new FreenetURI("KSK",	WebOfTrust.WOT_NAME + "|" +
										INTRODUCTION_CONTEXT + "|" +
										mID + "|" +
										mSolution);
	}
	
	public synchronized boolean wasInserted() {
		// checkedActivate(depth) is not needed, boolean is a db4o primitive type
		return mWasInserted;
	}
	
	public synchronized void setInserted() {
		if(wasInserted())
			throw new RuntimeException("The puzzle was already inserted.");
		
		mWasInserted = true;
	}
	
	protected void storeWithoutCommit() {
		try {		
			// 2 is the maximal depth of all getter functions. You have to adjust this when introducing new member variables.
			checkedActivate(2);
			throwIfNotStored(mInserter);
			if(wasSolved() && mSolver != null) // Solver is null if parsing of his introduction XML failed. 
				throwIfNotStored(mSolver);
			checkedStore();
		}
		catch(final RuntimeException e) {
			checkedRollbackAndThrow(e);
		}
	}
	
	protected void deleteWithoutCommit() {
		super.deleteWithoutCommit();
	}

	@Override
	public void startupDatabaseIntegrityTest() throws Exception {
		checkedActivate(2);
		
		if(mInserter == null)
			throw new NullPointerException("mInserter==null");
		
		if(mID == null)
			throw new NullPointerException("mID==null");

		if(!mID.endsWith(mInserter.getID()))
			throw new IllegalStateException("Invalid puzzle ID, does not end with inserter ID: " + mID);

		if(mType == null)
			throw new NullPointerException("mType==null");

		if(mMimeType == null)
			throw new NullPointerException("mMimeType==null");
		
		if(!mMimeType.equals("image/jpeg"))
			throw new IllegalStateException("Invalid mime type: " + mMimeType);

		if(mData == null)
			throw new NullPointerException("mData==null");
		
		if(mData.length == 0)
			throw new IllegalStateException("mData is empty");
		
		if(mValidUntilDate == null)
			throw new NullPointerException("mValidUntilDate==null");
			
		if(mValidUntilDate.before(mCreationDate))
			throw new IllegalStateException("mValidUntilDate is before mCreationDate");
			
		if(mDateOfInsertion == null)
			throw new NullPointerException("mDateOfInsertion==null");
		
		if(mDateOfInsertion.after(mCreationDate))
			throw new IllegalStateException("mDateOfInsertion is in after mCreationDate");
		
		if(mDateOfInsertion.after(CurrentTimeUTC.get()))
			throw new IllegalStateException("mDateOfInsertion is in the future");
					
		if(mIndex < 0)
			throw new IllegalStateException("Puzzle index is negative");
		
		if(mWasSolved) {
			if(mSolution==null)
				throw new NullPointerException("mWasSolved==true but mSolution==null");
			
			if(!(this instanceof OwnIntroductionPuzzle) && mSolver==null)
				throw new NullPointerException("mWasSolved==true but mSolver==null");
		} else if(!mWasSolved && !(this instanceof OwnIntroductionPuzzle)) {
			if(mWasInserted)
				throw new IllegalStateException("mWasSolved==false but mWasInserted==true");
		}
	}

}
