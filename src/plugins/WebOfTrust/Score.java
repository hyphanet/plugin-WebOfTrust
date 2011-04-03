/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import java.util.Date;

import freenet.support.CurrentTimeUTC;
import freenet.support.Logger;


/**
 * The score of an Identity in an OwnIdentity's trust tree.
 * A score is the actual rating of how much an identity can be trusted from the point of view of the OwnIdentity which owns the score.
 * If the Score is negative, the identity is considered malicious, if it is zero or positive, it is trusted. 
 * 
 * @author xor (xor@freenetproject.org)
 * @author Julien Cornuwel (batosai@freenetproject.org)
 */
public final class Score extends Persistent implements Cloneable {
	
	/** The OwnIdentity which assigns this score to the trustee */
	@IndexedField
	private final OwnIdentity mTruster;
	
	/** The Identity which is rated by this score */
	@IndexedField
	private final Identity mTrustee;
	
	/** The actual score of the Identity. Used to decide if the OwnIdentity sees the Identity or not */
	@IndexedField
	private int mValue;
	
	/** How far the Identity is from the tree's root. Tells how much point it can add to its trustees score. */
	private int mRank;
	
	/** How much point the trusted Identity can add to its trustees score. Depends on its rank AND the trust given by the tree owner.
	 * If the truster sets a negative trust on the trusted identity, it gets zero capacity, even if it has a positive score. */
	private int mCapacity;
	
	/**
	 * The date when the value, rank or capacity was last changed.
	 */
	private Date mLastChangedDate;


	/**
	 * Creates a Score from given parameters. Only for being used by the WoT package and unit tests, not for user interfaces!
	 * 
	 * @param myTruster The owner of the trust tree
	 * @param myTrustee The Identity that has the score
	 * @param myValue The actual score of the Identity. 
	 * @param myRank How far the Identity is from the tree's root. 
	 * @param myCapacity How much point the trusted Identity can add to its trustees score.
	 */
	public Score(OwnIdentity myTruster, Identity myTrustee, int myValue, int myRank, int myCapacity) {
		if(myTruster == null)
			throw new NullPointerException();
			
		if(myTrustee == null)
			throw new NullPointerException();
			
		mTruster = myTruster;
		mTrustee = myTrustee;
		setValue(myValue);
		setRank(myRank);
		setCapacity(myCapacity);
		
		// mLastChangedDate = CurrentTimeUTC.get(); <= setValue() etc do this already.
	}
	
	@Override
	public synchronized String toString() {
		// This function locks very much stuff and is synchronized. The lock on Score objects should always be taken last by our locking policy right now,
		// otherwise this function might be dangerous to be used for example with logging. Therefore TODO: Ensure that locks on Score are really taken last.
		
		/* We do not synchronize on truster and trustee because nickname changes are not allowed, the only thing which can happen
		 * is that we get a blank nickname if it has not been received yet, that is not severe though.*/
		
		return "[Score " + super.toString() + ": truster: " + getTruster().getNickname() + "@" + getTruster().getID() +
				"; trustee: " + getTrustee().getNickname() + "@" + getTrustee().getID() +
				"; value: " + getScore() +  "; rank: " + getRank() + "; capacity : " + getCapacity() + "]";
	}

	/**
	 * @return in which OwnIdentity's trust tree this score is
	 */
	public OwnIdentity getTruster() {
		checkedActivate(2);
		mTruster.initializeTransient(mWebOfTrust);
		return mTruster;
	}

	/**
	 * @return Identity that has this Score
	 */
	public Identity getTrustee() {
		checkedActivate(2);
		mTrustee.initializeTransient(mWebOfTrust);
		return mTrustee;
	}

	/**
	 * @return the numeric value of this Score
	 */
	/* XXX: Rename to getValue */
	public synchronized int getScore() {
		// checkedActivate(depth) is not needed, int is a db4o primitive type
		return mValue;
	}

	/**
	 * Sets the numeric value of this Score.
	 */
	protected synchronized void setValue(int newValue) {
		// checkedActivate(depth) is not needed, int is a db4o primitive type
		
		if(mValue == newValue)
			return;
		
		mValue = newValue;
		mLastChangedDate = CurrentTimeUTC.get();
	}

	/**
	 * @return The minimal distance in steps of {@link Trust} values from the truster to the trustee
	 */
	public synchronized int getRank() {
		// checkedActivate(depth) is not needed, int is a db4o primitive type
		return mRank;
	}

	/**
	 * Sets the distance of how far the trusted Identity is from the truster, measured in minimal steps of {@link Trust} values.
	 */
	protected synchronized void setRank(int newRank) {		
		if(newRank < -1)
			throw new IllegalArgumentException("Illegal rank.");
		
		// checkedActivate(depth) is not needed, int is a db4o primitive type
		
		if(newRank == mRank)
			return;
		
		mRank = newRank;
		mLastChangedDate = CurrentTimeUTC.get();
	}

	/**
	 * @return how much points the trusted Identity can add to its trustees score
	 */
	public synchronized int getCapacity() {
		// checkedActivate(depth) is not needed, int is a db4o primitive type
		return mCapacity;
	}

	/**
	 * Sets how much points the trusted Identity can add to its trustees score.
	 */
	protected synchronized void setCapacity(int newCapacity) {
		if(newCapacity < 0)
			throw new IllegalArgumentException("Negative capacities are not allowed.");
		
		// checkedActivate(depth) is not needed, int is a db4o primitive type
		
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
		// checkedActivate(depth) is not needed, Date is a db4o primitive type
		return mCreationDate;
	}
	
	/**
	 * Gets the {@link Date} when the value, capacity or rank of this score was last changed.
	 */
	public synchronized Date getDateOfLastChange() {
		// checkedActivate(depth) is not needed, Date is a db4o primitive type
		return mLastChangedDate;
	}
	
	@Override
	protected void storeWithoutCommit() {
		try {		
			// 2 is the maximal depth of all getter functions. You have to adjust this when introducing new member variables.
			checkedActivate(2);
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
	 * - <b>The involved identities are compared in terms of equals()</b>, the objects do not have to be the same.
	 */
	public boolean equals(Object obj) {
		if(obj == this)
			return true;

		if(!(obj instanceof Score))
			return false;
		
		Score other = (Score)obj;
	
		if(getScore() != other.getScore())
			return false;
		
		if(getRank() != other.getRank())
			return false;
		
		if(getCapacity() != other.getCapacity())
			return false;
		
		// Compare the involved identities after the numerical values because getting them might involve activating objects from the database.
		
		if(!getTruster().equals(other.getTruster()))
			return false;
		
		if(!getTrustee().equals(other.getTrustee()))
			return false;
		
		return true;
	}

	public Score clone() {
		final Score clone = new Score(getTruster(), getTrustee(), getScore(), getRank(), getCapacity());
		clone.initializeTransient(mWebOfTrust);
		return clone;
	}

	@Override
	public void startupDatabaseIntegrityTest() throws Exception {
		checkedActivate(2);
		
		if(mTruster == null)
			throw new NullPointerException("mTruster==null");
		
		if(mTrustee == null)
			throw new NullPointerException("mTrustee==null");
		
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
}
