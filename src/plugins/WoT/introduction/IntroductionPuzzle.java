/*
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */
package plugins.WoT.introduction;

import java.net.MalformedURLException;
import java.text.SimpleDateFormat;
import java.util.Date;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.query.Query;

import freenet.crypt.SHA1;
import freenet.keys.FreenetURI;

import plugins.WoT.Identity;
import plugins.WoT.OwnIdentity;
import plugins.WoT.WoT;

public class IntroductionPuzzle {
	
	public static final String INTRODUCTION_CONTEXT = "introduction";
	public static final int MINIMAL_SOLUTION_LENGTH = 5;
	
	private final String mMimeType;
	
	private final byte[] mData;
	
	private final String mSolution;
	
	private final Identity mInserter;
	
	private final long mValidUntilTime;
	
	private final Date mDateOfInsertion;
	
	private final int mIndex;
	
	
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
	public IntroductionPuzzle(Identity newInserter, String newMimeType, String newFilename, byte[] newData, long myValidUntilTime, Date myDateOfInsertion, int myIndex) {
		assert(	newInserter != null && newMimeType != null && !newMimeType.equals("") && newFilename!=null && !newFilename.equals("") &&
				newData!=null && newData.length!=0 && myValidUntilTime > System.currentTimeMillis() && myDateOfInsertion != null &&
				myDateOfInsertion.getTime() < System.currentTimeMillis() && myIndex >= 0);
		mInserter = newInserter;
		mMimeType = newMimeType;
		mData = newData;
		mSolution = null;
		mDateOfInsertion = myDateOfInsertion;
		mValidUntilTime = myValidUntilTime;
		mIndex = myIndex;
	}
	
	/**
	 * For construction of a puzzle which is meant to be inserted.
	 * @param newType
	 * @param newData
	 */
	public IntroductionPuzzle(Identity newInserter, String newMimeType, byte[] newData, String newSolution, int myIndex) {
		assert(	newInserter != null && newMimeType != null && !newMimeType.equals("") && newData!=null && newData.length!=0 &&
				newSolution!=null && newSolution.length()>=MINIMAL_SOLUTION_LENGTH && myIndex >= 0);
		mInserter = newInserter;
		mMimeType = newMimeType;
		mData = newData;
		mSolution = newSolution;
		mDateOfInsertion = new Date(); /* FIXME: get it in UTC */
		mValidUntilTime = System.currentTimeMillis() + IntroductionServer.PUZZLE_INVALID_AFTER_DAYS * 24 * 60 * 60 * 1000; /* FIXME: get it in UTC */
		mIndex = myIndex;
	}
	
	public String getMimeType() {
		return mMimeType;
	}
	
	/**
	 * Get the URI at which to insert this puzzle.
	 * SSK@asdfasdf.../WoT/introduction/yyyy-MM-dd|#.xml 
	 */
	public FreenetURI getURI() throws MalformedURLException {
		assert(mSolution != null); /* This function should only be needed by the introduction server, not by clients. */
		
		/* FIXME: I did not really understand the javadoc of FreenetURI. Please verify that the following code actually creates an URI
		 * which looks like the one I specified in the javadoc above this function. Thanks. */
		String dayOfInsertion = new SimpleDateFormat("yyyy-MM-dd").format(mDateOfInsertion);
		FreenetURI baseURI = ((OwnIdentity)mInserter).getInsertURI().setKeyType("KSK");
		baseURI = baseURI.setDocName(WoT.WOT_CONTEXT + "/" + INTRODUCTION_CONTEXT);
		return baseURI.setMetaString(new String[] {dayOfInsertion + "|" + mIndex + ".xml"} );
	}
	
	
	/**
	 * Get the URI at which to look for a solution of this puzzle (if someone solved it)
	 */
	public FreenetURI getSolutionURI() {
		return getSolutionURI(mSolution);
	}
	
	/**
	 * Get the URI at which to insert the solution of this puzzle.
	 */
	public FreenetURI getSolutionURI(String guessOfSolution) {
		String dayOfInsertion = new SimpleDateFormat("yyyy-MM-dd").format(mDateOfInsertion);
		return new FreenetURI("KSK", 	INTRODUCTION_CONTEXT + "|" +
								mInserter.getId() + "|" +
								dayOfInsertion + "|" +
								mIndex + "|" +
								guessOfSolution); /* FIXME: hash the solution!! */
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
	
	public Date getDateOfInsertion() {
		return mDateOfInsertion;
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
