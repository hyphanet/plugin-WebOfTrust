/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WoT;


/**
 * The score of an Identity in an OwnIdentity's trust tree.
 * A score is the actual rating of how much an identity can be trusted from the point of view of the OwnIdentity which owns the score.
 * If the Score is negative, the identity is considered malicious, if it is zero or positive, it is trusted. 
 * 
 * @author xor (xor@freenetproject.org), Julien Cornuwel (batosai@freenetproject.org)
 */
public final class Score {

	/** The OwnIdentity which assigns this score to the target */
	private final OwnIdentity mTreeOwner;
	
	/** The Identity which is rated by this score */
	private final Identity mTarget;
	
	/** The actual score of the Identity. Used to decide if the OwnIdentity sees the Identity or not */
	private int mValue;
	
	/** How far the Identity is from the tree's root. Tells how much point it can add to its trustees score. */
	private int mRank;
	
	/** How much point the target Identity can add to its trustees score. Depends on its rank AND the trust given by the tree owner.
	 * If the tree owner sets a negative trust on the target identity, it gets zero capacity, even if it has a positive score. */
	private int mCapacity;
	
	/**
	 * Creates a Score from given parameters.
	 * 
	 * @param myTreeOwner The owner of the trust tree
	 * @param myTarget The Identity that has the score
	 * @param myValue The actual score of the Identity. 
	 * @param myRank How far the Identity is from the tree's root. 
	 * @param myCapacity How much point the target Identity can add to its trustees score.
	 */
	protected Score(OwnIdentity myTreeOwner, Identity myTarget, int myValue, int myRank, int myCapacity) {
		if(myTreeOwner == null)
			throw new NullPointerException();
			
		if(myTarget == null)
			throw new NullPointerException();
			
		mTreeOwner = myTreeOwner;
		mTarget = myTarget;
		setValue(myValue);
		setRank(myRank);
		setCapacity(myCapacity);
	}
	
	@Override
	public synchronized String toString() {
		return getTarget().getNickName() + " has " + getScore() + " points in " + getTreeOwner().getNickName() + "'s trust tree" +
				"(rank : " + getRank() + ", capacity : " + getCapacity() + ")";
	}

	/**
	 * @return in which OwnIdentity's trust tree this score is
	 */
	public OwnIdentity getTreeOwner() {
		return mTreeOwner;
	}

	/**
	 * @return Identity that has this Score
	 */
	public Identity getTarget() {
		return mTarget;
	}

	/**
	 * @return the numeric value of this Score
	 */
	public synchronized int getScore() {
		return mValue;
	}

	/**
	 * Sets the numeric value of this Score.
	 */
	protected synchronized void setValue(int newValue) {
		mValue = newValue;
	}

	/**
	 * @return How far the target Identity is from the trust tree's root.
	 */
	public synchronized int getRank() {
		return mRank;
	}

	/**
	 * Sets how far the target Identity is from the trust tree's root.
	 */
	protected synchronized void setRank(int newRank) {
		if(newRank < 0)
			throw new IllegalArgumentException("Negative rank is not allowed");
		
		mRank = newRank;
	}

	/**
	 * @return how much points the target Identity can add to its trustees score
	 */
	public synchronized int getCapacity() {
		return mCapacity;
	}

	/**
	 * Sets how much points the target Identity can add to its trustees score.
	 */
	protected synchronized void setCapacity(int newCapacity) {
		if(newCapacity < 0)
			throw new IllegalArgumentException("Negative capacities are not allowed.");
		
		mCapacity = newCapacity;
	}
}
