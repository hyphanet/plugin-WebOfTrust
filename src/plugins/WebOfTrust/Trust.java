/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WoT;

import java.util.Date;

import plugins.WoT.exceptions.InvalidParameterException;
import freenet.support.CurrentTimeUTC;
import freenet.support.StringValidityChecker;

/**
 * A trust relationship between two Identities.
 * 
 * @author xor (xor@freenetproject.org)
 * @author Julien Cornuwel (batosai@freenetproject.org)
 */
public final class Trust implements Cloneable {
	
	public static final int MAX_TRUST_COMMENT_LENGTH = 256;

	/** The identity which gives the trust. */
	private final Identity mTruster;
	
	/** The identity which receives the trust. */
	private final Identity mTrustee;
	
	/** The value assigned with the trust, from -100 to +100 where negative means distrust */
	private byte mValue;
	
	/** An explanation of why the trust value was assigned */
	private String mComment;
	
	/**
	 * The date when this trust relationship was seen for the first time. This stays constant if the trust value changes.
	 */
	private Date mCreationDate; // FIXME: Add "final" as soon as we remove the code for upgrading legacy databases.
	
	/**
	 * The date when the value of this trust relationship changed for the last time.
	 */
	private Date mLastChangedDate;
	
	/**
	 * The edition number of the trust list in which this trust was published the last time.
	 * This is used to speed up the import of new trust lists: When importing them, we need to delete removed trust values. We cannot just
	 * delete all trust values of the truster from the database  and then import the trust list because deleting a trust causes recalculation
	 * of the score of the trustee. So for trust values which were not really removed from the trust list we would recalculate the score twice:
	 * One time when the old trust object is deleted and one time when the new trust is imported. Not only that we might recalculate one
	 * time without any necessity, most of the time even any recalculation would not be needed because the trust value has not changed.
	 * 
	 * To prevent this, we do the following: When creating new trusts, we store the edition number of the trust list from which we obtained it.
	 * When importing a new trust list, for each trust value we query the database whether a trust value to this trustee already exists and 
	 * update it if it does - we also update the trust list edition member variable. After having imported all trust values we query the 
	 * database for trust objects from the truster which have an old trust list edition number and delete them - the old edition number
	 * means that the trust has been removed from the latest trust list.
	 */
	private long mTrusterTrustListEdition;
	
	
	/**
	 * Get a list of fields which the database should create an index on.
	 */
	protected static String[] getIndexedFields() {
		return new String[] { "mTruster", "mTrustee" };
	}


	/**
	 * Creates a Trust from given parameters. Only for being used by the WoT package and unit tests, not for user interfaces!
	 * 
	 * @param truster Identity that gives the trust
	 * @param trustee Identity that receives the trust
	 * @param value Numeric value of the Trust
	 * @param comment A comment to explain the numeric trust value
	 * @throws InvalidParameterException if the trust value is not between -100 and +100
	 */
	public Trust(Identity truster, Identity trustee, byte value, String comment) throws InvalidParameterException {
		if(truster == null)
			throw new NullPointerException();
		
		if(trustee == null)
			throw new NullPointerException();
		
		if(truster == trustee)
			throw new InvalidParameterException("Trust values cannot be self-referential!");
		
		mTruster = truster;
		mTrustee = trustee;
		setValue(value);
		mComment = "";	// Simplify setComment
		setComment(comment);
		
		mCreationDate = CurrentTimeUTC.get();
		
		// mLastChangedDate = CurrentTimeUTC.get();	// Done by setValue / setComment.
		mTrusterTrustListEdition = truster.getEdition(); 
	}

	@Override
	public synchronized String toString() {
		return getTruster().getNickname() + " trusts " + getTrustee().getNickname() + " with value " + getValue() + " (comment: " + getComment() + ")";
	}

	/** @return The Identity that gives this trust. */
	public Identity getTruster() {
		return mTruster;
	}

	/** @return The Identity that receives this trust. */
	public Identity getTrustee() {
		return mTrustee;
	}

	/** @return value Numeric value of this trust relationship. The allowed range is -100 to +100, including both limits. 0 counts as positive. */
	public synchronized byte getValue() {
		return mValue;
	}

	/**
	 * @param newValue Numeric value of this trust relationship. The allowed range is -100 to +100, including both limits. 0 counts as positive. 
	 * @throws InvalidParameterException if value isn't in the range
	 */
	protected synchronized void setValue(byte newValue) throws InvalidParameterException {
		// TODO: Use l10n Trust.InvalidValue
		if(newValue < -100 || newValue > 100) 
			throw new InvalidParameterException("Invalid trust value ("+ newValue +"). Trust values must be in range of -100 to +100.");
		
		if(mValue != newValue) {
			mValue = newValue;
			mLastChangedDate = CurrentTimeUTC.get();
		}
	}

	/** @return The comment associated to this Trust relationship. */
	public synchronized String getComment() {
		return mComment;
	}

	/**
	 * @param newComment Comment on this trust relationship.
	 */
	protected synchronized void setComment(String newComment) throws InvalidParameterException {
		assert(newComment != null);
		
		newComment = newComment != null ? newComment.trim() : "";
		
		if(newComment.length() > MAX_TRUST_COMMENT_LENGTH)
			throw new InvalidParameterException("Comment is too long (maximum is " + MAX_TRUST_COMMENT_LENGTH + " characters).");
		
		if(!StringValidityChecker.containsNoInvalidCharacters(newComment)
			|| !StringValidityChecker.containsNoLinebreaks(newComment)
			|| !StringValidityChecker.containsNoControlCharacters(newComment)
			|| !StringValidityChecker.containsNoInvalidFormatting(newComment))
			throw new InvalidParameterException("Comment contains illegal characters.");
		
		if(!mComment.equals(newComment)) {
			mComment = newComment;
			mLastChangedDate = CurrentTimeUTC.get();
		}
	}
	
	public synchronized Date getDateOfCreation() {
		return mCreationDate;
	}
	
	public synchronized Date getDateOfLastChange() {
		return mLastChangedDate;
	}
	
	/**
	 * Only for being used in upgradeDatabase(). FIXME: Remove when we leave the beta stage
	 */
	protected synchronized void setDateOfCreation(Date date) {
		mCreationDate = date;
	}
	
	/**
	 * Only for being used in upgradeDatabase(). FIXME: Remove when we leave the beta stage
	 */
	protected synchronized void setDateOfLastChange(Date date) {
		mLastChangedDate = date;
	}

	
	/**
	 * Called by the XMLTransformer when a new trust list of the truster has been imported. Stores the edition number of the trust list in this trust object.
	 * For an explanation for what this is needed please read the description of {@link #mTrusterTrustListEdition}.
	 */
	protected synchronized void trusterEditionUpdated() {
		mTrusterTrustListEdition = mTruster.getEdition();
	}
	
	protected synchronized long getTrusterEdition() {
		return mTrusterTrustListEdition;
	}

	/**
	 * Test if two trust objects are equal.<br />
	 * - <b>All</b> attributes are compared <b>except</b> the dates.<br />
	 * - <b>The involved identities are compared in terms of equals()</b>, the objects do not have to be the same.
	 */
	public boolean equals(final Object obj) {
		if(obj == this)
			return true;
		
		if(!(obj instanceof Trust))
			return false;
		
		final Trust other = (Trust)obj;
		
		if(getValue() != other.getValue())
			return false;
		
		if(getTrusterEdition() != other.getTrusterEdition())
			return false;
		
		if(!getComment().equals(other.getComment()))
			return false;
		
		// Compare the involved identities after the numerical values because getting them might involve activating objects from the database.
		
		if(!getTruster().equals(other.getTruster()))
			return false;
		
		if(!getTrustee().equals(other.getTrustee()))
			return false;
		
		return true;
	}
	
	public Trust clone() {
		try {
			Trust clone = new Trust(getTruster(), getTrustee(), getValue(), getComment());
			clone.mTrusterTrustListEdition = this.mTrusterTrustListEdition;
			return clone;
		} catch (InvalidParameterException e) {
			throw new RuntimeException(e);
		}
	}
}
