/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.Date;
import java.util.StringTokenizer;
import java.util.UUID;

import plugins.WebOfTrust.Identity.IdentityID;
import freenet.support.CurrentTimeUTC;


/**
 * The score of an Identity in an OwnIdentity's trust tree.
 * A score is the actual rating of how much an identity can be trusted from the point of view of the OwnIdentity which owns the score.
 * If the Score is negative, the identity is considered malicious, if it is zero or positive, it is trusted. 
 * 
 * @author xor (xor@freenetproject.org)
 * @author Julien Cornuwel (batosai@freenetproject.org)
 */
public final class Score extends Persistent implements Cloneable, EventSource {
	
	/** @see Serializable */
	private static transient final long serialVersionUID = 1L;
	
	/** The OwnIdentity which assigns this score to the trustee */
	@IndexedField
	private final OwnIdentity mTruster;
	
	/** The Identity which is rated by this score */
	@IndexedField
	private final Identity mTrustee;
	
	/**
	 * The ID of this Score in the database. Composed by:
	 * mTruster.getID() + "@" + mTrustee.getID()
	 * 
	 * We need this ID because the following query takes O(N) instead of O(1) with db4o:
	 * 
	 * final Query query = mDB.query();
	 * query.constrain(Score.class);
	 * query.descend("mTruster").constrain(truster).identity();
	 * query.descend("mTrustee").constrain(trustee).identity();
	 * final ObjectSet<Score> result = new Persistent.InitializingObjectSet<Score>(this, query);
	 * 
	 * (With N being the number of Score objects, the query takes O(N) because db4o either uses the index on mTruster and then has to check a worst case of
	 * N objects for the right mTrustee value - or vice versa with the mTrustee index)
	 * 
	 * With this composite ID, the same query can be executed in O(1) by doing:
	 * 
	 * final Query query = mDB.query();
	 * query.constrain(Score.class);
	 * query.descend("mID").constrain(mTruster.getID() + "@" + mTrustee.getID()).identity();
	 * final ObjectSet<Score> result = new Persistent.InitializingObjectSet<Score>(this, query); 
	 */
	@IndexedField
	private String mID;
	
	/** The actual score of the Identity. Used to decide if the OwnIdentity sees the Identity or not */
	@IndexedField
	private int mValue;
	
	/**
	 * How far the Identity is from the tree's root. Tells how much point it can add to its trustees
	 * score.
	 * @see WebOfTrust#computeRankFromScratch() */
	private int mRank;
	
	/** How much point the trusted Identity can add to its trustees score. Depends on its rank AND the trust given by the tree owner.
	 * If the truster sets a negative trust on the trusted identity, it gets zero capacity, even if it has a positive score. */
	private int mCapacity;
	
	/**
	 * The date when the value, rank or capacity was last changed.
	 */
	private Date mLastChangedDate;

    /** An {@link UUID} set by {@link EventSource#setVersionID(UUID)}. See its JavaDoc for an
     *  explanation of the purpose.<br>
     *  Stored as String to reduce db4o maintenance overhead. */
    private String mVersionID = null;


	/**
	 * A class for generating and validating Score IDs.
	 * Its purpose is NOT to be stored in the database: That would make the queries significantly slower.
	 * We store the IDs as Strings instead for fast queries.
	 * 
	 * Its purpose is to allow validation of ScoreIDs which we obtain from the database or from the network.
	 * 
	 * TODO: This was added after we already had manual ID-generation / checking in the code everywhere. Use this class instead. 
	 */
	protected static final class ScoreID {
		
		private static final int MAX_SCORE_ID_LENGTH = IdentityID.LENGTH + "@".length() + IdentityID.LENGTH;
		
		private final String mID;
		private final String mTrusterID;
		private final String mTrusteeID;
		
		public ScoreID(Identity truster, Identity trustee) {
			mTrusterID = truster.getID();
			mTrusteeID = trustee.getID();
			mID = truster.getID() + "@" + trustee.getID();
		}
		
		private ScoreID(String id) {
			if(id.length() > MAX_SCORE_ID_LENGTH)
				throw new IllegalArgumentException("ID is too long, length: " + id.length());

			mID = id;

			final StringTokenizer tokenizer = new StringTokenizer(id, "@");

			mTrusterID = IdentityID.constructAndValidateFromString(tokenizer.nextToken()).toString();
			mTrusteeID = IdentityID.constructAndValidateFromString(tokenizer.nextToken()).toString();

			if(tokenizer.hasMoreTokens())
				throw new IllegalArgumentException("Invalid MessageID: " + id);
		}
		
		public static ScoreID constructAndValidate(Score score, String id) {
			final ScoreID scoreID = new ScoreID(id);
			if(!score.getTruster().getID().equals(scoreID.mTrusterID))
				throw new RuntimeException("Truster ID mismatch for Score " + score + ": ScoreID is " + id);
			
			if(!score.getTrustee().getID().equals(scoreID.mTrusteeID))
				throw new RuntimeException("Trustee ID mismatch for Score " + score + ": ScoreID is " + id);
			
			return scoreID;
		}
		
		@Override
		public final String toString() {
			return mID;
		}
		
		@Override
		public final boolean equals(final Object o) {
			if(o instanceof ScoreID)
				return mID.equals(((ScoreID)o).mID);
			
			if(o instanceof String)
				return mID.equals((String)o);
			
			return false;
		}
		
	}

	/**
	 * Creates a Score from given parameters. Only for being used by the WoT package and unit tests, not for user interfaces!
	 * 
	 * @param myTruster The owner of the trust tree
	 * @param myTrustee The Identity that has the score
	 * @param myValue The actual score of the Identity. 
	 * @param myRank How far the Identity is from the tree's root. 
	 * @param myCapacity How much point the trusted Identity can add to its trustees score.
	 */
	public Score(WebOfTrustInterface myWoT, OwnIdentity myTruster, Identity myTrustee, int myValue, int myRank, int myCapacity) {
		initializeTransient(myWoT);
		
		if(myTruster == null)
			throw new NullPointerException();
			
		if(myTrustee == null)
			throw new NullPointerException();
			
		mTruster = myTruster;
		mTrustee = myTrustee;
		mID = new ScoreID(mTruster, mTrustee).toString();
		setValue(myValue);
		setRank(myRank);
		setCapacity(myCapacity);
		
		// setValue() etc. might not set this if the value matches the defaults.
		if(mLastChangedDate == null)
			mLastChangedDate = CurrentTimeUTC.get();
	}
	
	@Override
	public int hashCode() {
		return getID().hashCode();
	}
	
	/** @return A String containing everything which {@link #equals(Object)} would compare. */
	@Override
	public String toString() {
	    activateFully();
		return "[Score: " + super.toString()
		     + "; mID: " + mID
		     + "; mValue: " + mValue
		     + "; mRank: " + mRank
		     + "; mCapacity: " + mCapacity
		     + "]";
	}

	/**
	 * @return in which OwnIdentity's trust tree this score is
	 */
	public OwnIdentity getTruster() {
		checkedActivate(1);
		mTruster.initializeTransient(mWebOfTrust);
		return mTruster;
	}

	/**
	 * @return Identity that has this Score
	 */
	public Identity getTrustee() {
		checkedActivate(1);
		mTrustee.initializeTransient(mWebOfTrust);
		return mTrustee;
	}
	
	/**
	 * @see {@link ScoreID}
	 */
	@Override
	public String getID() {
		checkedActivate(1); // String is a db4o primitive type so 1 is enough
		return mID;
	}
	
	/**
	 * @deprecated Only for being used in {@link WebOfTrust.upgradeDB()}
	 */
	@Deprecated
	protected void generateID() {
		checkedActivate(1);
		if(mID != null)
			throw new RuntimeException("ID is already set for " + this);
		mID = new ScoreID(getTruster(), getTrustee()).toString();
	}

	/**
	 * @return the numeric value of this Score
	 */
	/* XXX: Rename to getValue */
	public synchronized int getScore() {
		checkedActivate(1); // int is a db4o primitive type so 1 is enough
		return mValue;
	}

	/**
	 * Sets the numeric value of this Score.
	 */
	protected synchronized void setValue(int newValue) {
		checkedActivate(1); // int/Date is a db4o primitive type so 1 is enough
		
		if(mValue == newValue)
			return;
		
		mValue = newValue;
		mLastChangedDate = CurrentTimeUTC.get();
	}

	/**
	 * @return The minimal distance in steps of {@link Trust} values from the truster to the trustee
	 * @see WebOfTrust#computeRankFromScratch()
	 */
	public synchronized int getRank() {
		checkedActivate(1); // int is a db4o primitive type so 1 is enough
		return mRank;
	}

	/**
	 * Sets the distance of how far the trusted Identity is from the truster, measured in minimal steps of {@link Trust} values.
	 */
	protected synchronized void setRank(int newRank) {		
		if(newRank < -1)
			throw new IllegalArgumentException("Illegal rank.");
		
		checkedActivate(1); // int is a db4o primitive type so 1 is enough
		
		if(newRank == mRank)
			return;
		
		mRank = newRank;
		mLastChangedDate = CurrentTimeUTC.get();
	}

	/**
	 * @return how much points the trusted Identity can add to its trustees score
	 */
	public synchronized int getCapacity() {
		checkedActivate(1); // int is a db4o primitive type so 1 is enough
		return mCapacity;
	}

	/**
	 * Sets how much points the trusted Identity can add to its trustees score.
	 */
	protected synchronized void setCapacity(int newCapacity) {
		if(newCapacity < 0)
			throw new IllegalArgumentException("Negative capacities are not allowed.");
		
		checkedActivate(1); // int/Date is a db4o primitive type so 1 is enough
		
		if(newCapacity == mCapacity)
			return;
		
		mCapacity = newCapacity;
		mLastChangedDate = CurrentTimeUTC.get();
	}
	
	/**
	 * Gets the {@link Date} when this score object was created. The date of creation does never change for an existing score object, so if the value, rank
	 * or capacity of a score changes then its date of creation stays constant.
	 */
	public synchronized Date getDateOfCreation() {
		checkedActivate(1); // Date is a db4o primitive type so 1 is enough
		return (Date)mCreationDate.clone();	// Clone it because date is mutable
	}
	
	/**
	 * Gets the {@link Date} when the value, capacity or rank of this score was last changed.
	 */
	public synchronized Date getDateOfLastChange() {
		checkedActivate(1); // Date is a db4o primitive type so 1 is enough
		return (Date)mLastChangedDate.clone();	// Clone it because date is mutable
	}
	
	/**
	 * {@inheritDoc}
	 */
	@Override
	protected void activateFully() {
		// 1 is the maximal depth of all getter functions. You have to adjust this when introducing new member variables.
		checkedActivate(1);
		mTruster.initializeTransient(mWebOfTrust);
		mTrustee.initializeTransient(mWebOfTrust);
	}
	
	@Override
	protected void storeWithoutCommit() {
		try {
			activateFully();
			throwIfNotStored(mTruster);
			throwIfNotStored(mTrustee);
			checkedStore();
		}
		catch(final RuntimeException e) {
			checkedRollbackAndThrow(e);
		}
	}
	
	/**
	 * Test if two scores are equal.
	 * - <b>All</b> attributes are compared <b>except</b> the dates.<br />
	 * - <b>The involved identities are compared by {@link Identity#getID()}</b>, the objects do not have to be same or equals().
	 * 	Also, this check is done only implicitly by comparing {@link Score#getID()}.
     * <br><br>
     * 
     * Notice: {@link #toString()} returns a String which contains the same data as this function
     * compares. This can ease debugging.
	 */
	@Override
	public boolean equals(Object obj) {
		if(obj == this)
			return true;

		if(!(obj instanceof Score))
			return false;
		
		Score other = (Score)obj;
		
		if(!getID().equals(other.getID()))
			return false;
		
		// Since we have already compared the ID of the Score objects, we have implicitly checked whether the truster/trustee IDs match:
		// The ScoreID is a concatenation of their IDs.
		
		assert(getTruster().getID().equals(other.getTruster().getID()));
		assert(getTrustee().getID().equals(other.getTrustee().getID()));
		
		if(getScore() != other.getScore())
			return false;
		
		if(getRank() != other.getRank())
			return false;
		
		if(getCapacity() != other.getCapacity())
			return false;

		return true;
	}

	@Override
	public Score clone() {
		activateFully();
		final Score clone = new Score(mWebOfTrust, getTruster().clone(), getTrustee().clone(), getScore(), getRank(), getCapacity());
		clone.setCreationDate(getCreationDate());
		clone.mLastChangedDate = (Date)mLastChangedDate.clone();	// Clone it because date is mutable
		return clone;
	}

	@Override
	public void startupDatabaseIntegrityTest() throws Exception {
		activateFully();
		
		if(mTruster == null)
			throw new NullPointerException("mTruster==null");
		
		if(mTrustee == null)
			throw new NullPointerException("mTrustee==null");
		
		if(mID == null)
			throw new NullPointerException("mID==null");
		
		ScoreID.constructAndValidate(this, mID); // Throws if invalid
		
		if(mRank < -1)
			throw new IllegalStateException("Invalid rank: " + mRank);
	
		if(mCapacity < 0)
			throw new IllegalStateException("Negative capacity: " + mCapacity);
		
		if(mLastChangedDate == null)
			throw new NullPointerException("mLastChangedDate==null");
		
		if(mLastChangedDate.before(mCreationDate))
			throw new IllegalStateException("mLastChangedDate is before mCreationDate.");
		
		if(mLastChangedDate.after(CurrentTimeUTC.get()))
			throw new IllegalStateException("mLastChangedDate is in the future: " + mLastChangedDate);
	}
	
	/** @see Persistent#serialize() */
	private void writeObject(ObjectOutputStream stream) throws IOException {
		activateFully();
		mTruster.activateFully();
		mTrustee.activateFully();
		stream.defaultWriteObject();
	}

    /** {@inheritDoc} */
    @Override public void setVersionID(UUID versionID) { 
        checkedActivate(1);
        // No need to delete the old value from db4o: Its a String, and thus a native db4o value.
        mVersionID = versionID.toString();
    }

    /** {@inheritDoc} */
    @Override public UUID getVersionID() {
        checkedActivate(1);
        // FIXME: Validate whether this yields proper results using an event-notifications FCP dump
        return mVersionID != null ? UUID.fromString(mVersionID) : UUID.randomUUID();
    }
}
