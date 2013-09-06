/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.introduction;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.TimeZone;

import plugins.WebOfTrust.Identity;
import plugins.WebOfTrust.OwnIdentity;
import plugins.WebOfTrust.Persistent;
import plugins.WebOfTrust.WebOfTrust;
import plugins.WebOfTrust.exceptions.InvalidParameterException;
import freenet.keys.FreenetURI;
import freenet.support.CurrentTimeUTC;
import freenet.support.Logger;
import freenet.support.TimeUtil;

/**
 * An introduction puzzle is a puzzle (for example a CAPTCHA) which can be solved by a new identity to get onto the trust list
 * of already existing identities. This is the only way to get onto the web of trust if you do not know someone who will add you manually.
 */
@SuppressWarnings("serial")
public class IntroductionPuzzle extends Persistent implements Cloneable {
	
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
	
	/**
	 * The Unix-date of the day when this puzzle was inserted.
	 * For example if a puzzle is generated on "2011-02-28 14:28:16.123" this will be the unix time "2011-02-28 00:00:00.000"
	 * We store as long/unix-time instead of Date because we need database queries on the exact day to work and they do not work very
	 * well with Date objects because their internal structure is messy (they store as localtime even when we tell them to use UTC).
	 * TODO: Get rid of unix-time before 2038 :|
	 */
	@IndexedField
	private final long mDayOfInsertion;
	
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
	
	/* These booleans are used for preventing the construction of log-strings if logging is disabled (for saving some cpu cycles) */
	
	private static transient volatile boolean logDEBUG = false;
	private static transient volatile boolean logMINOR = false;
	
	static {
		Logger.registerClass(IntroductionPuzzle.class);
	}
	
	
	/**
	 * For construction from a received puzzle.
	 * @param newType
	 * @param newData
	 */
	public IntroductionPuzzle(WebOfTrust myWebOfTrust, Identity newInserter, String newID, PuzzleType newType, String newMimeType, byte[] newData,
			Date myDateOfInsertion, Date myExpirationDate, int myIndex) {
		
		initializeTransient(myWebOfTrust);
		
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
		mDayOfInsertion = TimeUtil.setTimeToZero(myDateOfInsertion).getTime();
		mValidUntilDate = (Date)myExpirationDate.clone();
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
		return uri.
				getDocName().
				split("[|]")[2];
	}
	
	public String getID() {
		checkedActivate(1); // String is a db4o primitive type so 1 is enough
		return mID;
	}
	
	public PuzzleType getType() {
		checkedActivate(1);
		return mType;
	}

	public String getMimeType() {
		checkedActivate(1); // String is a db4o primitive type so 1 is enough
		return mMimeType;
	}
	
	public byte[] getData() {
		checkedActivate(1); // byte[] is a db4o primitive type so 1 is enough
		return mData;
	}
	
	public Identity getInserter() {
		checkedActivate(1);
		mInserter.initializeTransient(mWebOfTrust);
		return mInserter;
	}
	
	public Date getDateOfInsertion() {
		checkedActivate(1); // Date is a db4o primitive type so 1 is enough
		return new Date(mDayOfInsertion);
	}
	
	public Date getValidUntilDate() {
		checkedActivate(1); // Date is a db4o primitive type so 1 is enough
		return (Date)mValidUntilDate.clone();
	}
	
	public int getIndex() {
		checkedActivate(1); // int is a db4o primitive type so 1 is enough
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
		checkedActivate(1);
		
		if(mWasSolved)
			throw new IllegalStateException("Puzzle is already solved!"); 
		
		mWasSolved = true;
		mSolver = solver;
		mSolution = solution;
	}
	
	public synchronized boolean wasSolved() {
		checkedActivate(1); // boolean is a db4o primitive type so 1 is enough
		return mWasSolved;
	}
	
	protected synchronized String getSolution() {
		checkedActivate(1); // String is a db4o primitive type so 1 is enough
		return mSolution;
	}

	/**
	 * Get the Identity which solved this puzzle. Used by the IntroductionClient for inserting identity introductions.
	 */
	public synchronized Identity getSolver() {
		checkedActivate(1);
		
		if(!mWasSolved)
			throw new IllegalStateException("The puzzle is not solved");
		
		if(mSolver == null)
			return null;
		
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
		checkedActivate(1); // String is a db4o primitive type so 1 is enough
		
		if(mSolution == null)
			throw new IllegalStateException("The puzzle is not solved.");
		
		return new FreenetURI("KSK",	WebOfTrust.WOT_NAME + "|" +
										INTRODUCTION_CONTEXT + "|" +
										mID + "|" +
										mSolution);
	}
	
	public synchronized boolean wasInserted() {
		checkedActivate(1); // boolean is a db4o primitive type so 1 is enough
		return mWasInserted;
	}
	
	public synchronized void setInserted() {
		if(logDEBUG) Logger.debug(this, "Marking puzzle as inserted: " + this);
		
		checkedActivate(1); // boolean is a db4o primitive type so 1 is enough
		
		if(mWasInserted)
			throw new RuntimeException("The puzzle was already inserted.");
		
		mWasInserted = true;
	}
	
	protected void storeWithoutCommit() {
		try {		
			// 1 is the maximal depth of all getter functions. You have to adjust this when introducing new member variables.
			checkedActivate(1);
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
		checkedActivate(1);
		
		if(mInserter == null)
			throw new NullPointerException("mInserter==null");
		
		if(mID == null)
			throw new NullPointerException("mID==null");

		if(!mID.endsWith(getInserter().getID()))
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
			
		if(mDayOfInsertion < 0)
			throw new NullPointerException("mDayOfInsertion==" + mDayOfInsertion);
		
		if(new Date(mDayOfInsertion).after(mCreationDate))
			throw new IllegalStateException("mDateOfInsertion is in after mCreationDate");
		
		if(new Date(mDayOfInsertion).after(CurrentTimeUTC.get()))
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

	@Override
	public String toString() {
		return "[" + super.toString() + ": mID:" + mID + "; mDayOfInsertion: " + mDayOfInsertion + "; mRequestURI: " + getRequestURI().toString() + "]";
	}
	
	@Override
	public IntroductionPuzzle clone() {
		// TODO: Optimization: If this is used often, make it use the member variables instead of the getters - do proper activation before.
		final IntroductionPuzzle copy = new IntroductionPuzzle(mWebOfTrust, getInserter(), getID(), getType(), getMimeType(), getData(), getDateOfInsertion(), getValidUntilDate(), getIndex());
		if(wasSolved()) copy.setSolved((OwnIdentity)getSolver(), getSolution());
		if(wasInserted()) copy.setInserted();
		copy.initializeTransient(mWebOfTrust);
		return copy;
	}
	
	@Override
	public boolean equals(Object o) {
		if(o == this)
			return true;
		
		if(!(o instanceof IntroductionPuzzle))
			throw new IllegalArgumentException();
		
		final IntroductionPuzzle other = (IntroductionPuzzle)o;
		
		
		return (
					getID().equals(other.getID()) &&
					getType().equals(other.getType()) &&
					getMimeType().equals(other.getMimeType()) &&
					getValidUntilDate().equals(other.getValidUntilDate()) &&
					Arrays.equals(getData(), other.getData()) &&
					getInserter().equals(other.getInserter()) &&
					getDateOfInsertion().equals(other.getDateOfInsertion()) &&
					getIndex() == other.getIndex() &&
					wasSolved() == other.wasSolved() &&
					(
							!wasSolved() || 
							(getSolver() == null || getSolver().equals(other.getSolver())) && getSolutionURI().equals(other.getSolutionURI())
					) &&
					wasInserted() == other.wasInserted()
				);
	}
	
	@Override
	public int hashCode() {
		return getID().hashCode();
	}
}
