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
import plugins.WebOfTrust.exceptions.InvalidParameterException;
import plugins.WebOfTrust.util.AssertUtil;
import freenet.support.CurrentTimeUTC;
import freenet.support.StringValidityChecker;

/**
 * A trust relationship between two Identities.
 * 
 * @author xor (xor@freenetproject.org)
 * @author Julien Cornuwel (batosai@freenetproject.org)
 */
public final class Trust extends Persistent implements Cloneable, EventSource {
	
	/** @see Serializable */
	private static transient final long serialVersionUID = 1L;

	public static transient final int MAX_TRUST_COMMENT_LENGTH = 256;
	
	/**
	 * TODO: We have hardcoded this value in many places. Get rid of the hardcoding and use this constant instead.
	 */
	public static transient final byte MAX_TRUST_VALUE = 100;
	
	/**
	 * TODO: We have hardcoded this value in many places. Get rid of the hardcoding and use this constant instead.
	 */
	public static transient final byte MIN_TRUST_VALUE = -MAX_TRUST_VALUE;
	
	/** The identity which gives the trust. */
	@IndexedField
	private final Identity mTruster;
	
	/** The identity which receives the trust. */
	@IndexedField
	private final Identity mTrustee;
	
	/**
	 * The ID of this Trust in the database. Composed by:
	 * mTruster.getID() + "@" + mTrustee.getID()
	 * 
	 * We need this ID because the following query takes O(N) instead of O(1) with db4o:
	 * 
	 * final Query query = mDB.query();
	 * query.constrain(Trust.class);
	 * query.descend("mTruster").constrain(truster).identity();
	 * query.descend("mTrustee").constrain(trustee).identity();
	 * final ObjectSet<Trust> result = new Persistent.InitializingObjectSet<Trust>(this, query);
	 * 
	 * (With N being the number of Trust objects, the query takes O(N) because db4o either uses the index on mTruster and then has to check a worst case of
	 * N objects for the right mTrustee value - or vice versa with the mTrustee index)
	 * 
	 * With this composite ID, the same query can be executed in O(1) by doing:
	 * 
	 * final Query query = mDB.query();
	 * query.constrain(Trust.class);
	 * query.descend("mID").constrain(mTruster.getID() + "@" + mTrustee.getID()).identity();
	 * final ObjectSet<Trust> result = new Persistent.InitializingObjectSet<Trust>(this, query); 
	 */
	@IndexedField
	private String mID;
	
	/** The value assigned with the trust, from -100 to +100 where negative means distrust */
	@IndexedField
	private byte mValue;
	
	/** An explanation of why the trust value was assigned */
	private String mComment;
	
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
	// TODO: Optimization: An index on this WOULD make sense if db4o supported joined indices. then we would create an index on
	// {mTruster,mTrusterTrustListEdition}. WITHOUT joined indicies, the way getGivenTrustsOlderThan works will make the query faster if
	// db4o uses the index on mTruster instead of the index on mTrusterTrustListEditon, so we don't create that index.
	// @IndexedField
	private long mTrusterTrustListEdition;

    /** An {@link UUID} set by {@link EventSource#setVersionID(UUID)}. See its JavaDoc for an
     *  explanation of the purpose.<br>
     *  Stored as String to reduce db4o maintenance overhead. */
    private String mVersionID = null;


	/**
	 * A class for generating and validating Trust IDs.
	 * Its purpose is NOT to be stored in the database: That would make the queries significantly slower.
	 * We store the IDs as Strings instead for fast queries.
	 * 
	 * Its purpose is to allow validation of TrustIDs which we obtain from the database or from the network.
	 * 
	 * TODO: This was added after we already had manual ID-generation / checking in the code everywhere. Use this class instead. 
	 */
	public static final class TrustID {
		
		private static final int MAX_TRUST_ID_LENGTH = IdentityID.LENGTH + "@".length() + IdentityID.LENGTH;
		
		private final String mID;
		private final String mTrusterID;
		private final String mTrusteeID;
		
		public TrustID(Identity truster, Identity trustee) {
			mTrusterID = truster.getID();
			mTrusteeID = trustee.getID();
			mID = truster.getID() + "@" + trustee.getID();
		}

		public TrustID(Trust trust) {
			mTrusterID = trust.getTruster().getID();
			mTrusteeID = trust.getTrustee().getID();
			mID = trust.getID();
		}

        public TrustID(final String trusterID, final String trusteeID) {
            AssertUtil.assertDidNotThrow(new Runnable() {
                @Override public void run() {
                    IdentityID.constructAndValidateFromString(trusterID);
                    IdentityID.constructAndValidateFromString(trusteeID);
                }
            });
            
            mTrusterID = trusterID;
            mTrusteeID = trusteeID;
            mID = trusterID  + "@" + trusteeID;
        }

		private TrustID(String id) {
			if(id.length() > MAX_TRUST_ID_LENGTH)
				throw new IllegalArgumentException("ID is too long, length: " + id.length());

			mID = id;

			final StringTokenizer tokenizer = new StringTokenizer(id, "@");

			mTrusterID = IdentityID.constructAndValidateFromString(tokenizer.nextToken()).toString();
			mTrusteeID = IdentityID.constructAndValidateFromString(tokenizer.nextToken()).toString();

			if(tokenizer.hasMoreTokens())
				throw new IllegalArgumentException("Invalid MessageID: " + id);
		}
		
		public static TrustID constructAndValidate(Trust trust, String id) {
			final TrustID trustID = new TrustID(id);
			
			if(!trust.getTruster().getID().equals(trustID.mTrusterID))
				throw new RuntimeException("Truster ID mismatch for Trust " + trust + ": TrustID is " + id);
			
			if(!trust.getTrustee().getID().equals(trustID.mTrusteeID))
				throw new RuntimeException("Trustee ID mismatch for Trust " + trust + ": TrustID is " + id);
			
			return trustID;
		}

		public final String getTrusterID() {
			return mTrusterID;
		}

		public final String getTrusteeID() {
			return mTrusteeID;
		}

		@Override
		public final String toString() {
			return mID;
		}
		
		@Override
		public final boolean equals(final Object o) {
			if(o instanceof TrustID)
				return mID.equals(((TrustID)o).mID);
			
			if(o instanceof String)
				return mID.equals((String)o);
			
			return false;
		}
		
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
	public Trust(WebOfTrustInterface myWoT, Identity truster, Identity trustee, byte value, String comment) throws InvalidParameterException {
		initializeTransient(myWoT);
		
		if(truster == null)
			throw new NullPointerException();
		
		if(trustee == null)
			throw new NullPointerException();
		
		if(truster == trustee)
			throw new InvalidParameterException("Trust values cannot be self-referential!");
		
		mTruster = truster;
		mTrustee = trustee;
		mID = new TrustID(mTruster, mTrustee).toString();
		setValue(value);
		mComment = "";	// Simplify setComment
		setComment(comment);
		
		mLastChangedDate = (Date)mCreationDate.clone();	// Clone it because date is mutable
		mTrusterTrustListEdition = truster.getEdition(); 
	}
	
	@Override
	public int hashCode() {
		return getID().hashCode();
	}

    /** @return A String containing everything which {@link #equals(Object)} would compare. */
	@Override
	public String toString() {
	    activateFully();
		return "[Trust: " + super.toString()
		     + "; mID: " + mID
		     + "; mValue:" + mValue
             + "; mTrusterTrustListEdition: " + mTrusterTrustListEdition
		     + "; mComment: \""  + mComment+ "\""
		     + "]";
	}

	/** @return The Identity that gives this trust. */
	public Identity getTruster() {
		checkedActivate(1);
		mTruster.initializeTransient(mWebOfTrust);
		return mTruster;
	}

	/** @return The Identity that receives this trust. */
	public Identity getTrustee() {
		checkedActivate(1);
		mTrustee.initializeTransient(mWebOfTrust);
		return mTrustee;
	}
	
	/**
	 * @see {@link TrustID}
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
		mID = new TrustID(getTruster(), getTrustee()).toString();
	}

	/** @return value Numeric value of this trust relationship. The allowed range is -100 to +100, including both limits. 0 counts as positive. */
	public synchronized byte getValue() {
		checkedActivate(1); // byte is a db4o primitive type so 1 is enough
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

		checkedActivate(1); // byte is a db4o primitive type so 1 is enough
		
		if(mValue != newValue) {
			mValue = newValue;
			mLastChangedDate = CurrentTimeUTC.get();
		}
	}

	/** @return The comment associated to this Trust relationship. */
	public synchronized String getComment() {
		checkedActivate(1); // String is a db4o primitive type so 1 is enough
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

		checkedActivate(1); // String is a db4o primitive type so 1 is enough
		
		if(!mComment.equals(newComment)) {
			mComment = newComment;
			mLastChangedDate = CurrentTimeUTC.get();
		}
	}
	
	public synchronized Date getDateOfCreation() {
		checkedActivate(1); // Date is a db4o primitive type so 1 is enough
		return (Date)mCreationDate.clone();	// Clone it because date is mutable
	}
	
	public synchronized Date getDateOfLastChange() {
		checkedActivate(1); // Date is a db4o primitive type so 1 is enough
		return (Date)mLastChangedDate.clone();	// Clone it because date is mutable
	}
	
	/**
	 * Called by the XMLTransformer when a new trust list of the truster has been imported. Stores the edition number of the trust list in this trust object.
	 * For an explanation for what this is needed please read the description of {@link #mTrusterTrustListEdition}.
	 */
	protected synchronized void trusterEditionUpdated() {
		checkedActivate(1); // long is a db4o primitive type so 1 is enough
		mTrusterTrustListEdition = getTruster().getEdition();
	}
	
	public synchronized long getTrusterEdition() {
		checkedActivate(1); // long is a db4o primitive type so 1 is enough
		return mTrusterTrustListEdition;
	}
	
	/**
	 * ATTENTION: Only use this when you need to construct arbitrary Trust objects - for example when writing an FCP parser.
	 * It won't guarantee semantic integrity of the Trust object because the edition can mismatch the actual edition of the Truster.
	 * Use {@link #trusterEditionUpdated()} instead.
	 */
	public void forceSetTrusterEdition(final long trusterEdition) {
		checkedActivate(1);
		mTrusterTrustListEdition = trusterEdition;
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
	 * Test if two trust objects are equal.<br />
	 * - <b>All</b> attributes are compared <b>except</b> the dates.<br />
	 * - <b>The involved identities are compared by {@link Identity#getID()}</b>, the objects do not have to be same or equals().
	 * 	Also, this check is done only implicitly by comparing {@link Trust#getID()}.
     * <br><br>
     * 
     * Notice: {@link #toString()} returns a String which contains the same data as this function
     * compares. This can ease debugging.
	 */
	@Override
	public boolean equals(final Object obj) {
		if(obj == this)
			return true;
		
		if(!(obj instanceof Trust))
			return false;
		
		final Trust other = (Trust)obj;
		
		if(!getID().equals(other.getID()))
			return false;
		
		// Since we have already compared the ID of the Trust objects, we have implicitly checked whether the truster/trustee IDs match:
		// The TrustID is a concatenation of their IDs
		
		assert(getTruster().getID().equals(other.getTruster().getID()));
		assert(getTrustee().getID().equals(other.getTrustee().getID()));
		
		if(getValue() != other.getValue())
			return false;
		
		if(getTrusterEdition() != other.getTrusterEdition())
			return false;
		
		if(!getComment().equals(other.getComment()))
			return false;
		
		return true;
	}
	
	@Override
	public Trust clone() {
		try {
			activateFully();
			Trust clone = new Trust(mWebOfTrust, getTruster().clone(), getTrustee().clone(), getValue(), getComment());
			clone.setCreationDate(getCreationDate());
			clone.mLastChangedDate = (Date)mLastChangedDate.clone();	// Clone it because date is mutable
			clone.mTrusterTrustListEdition = mTrusterTrustListEdition; // Don't use the getter since it will re-query it from the actual Identity object which might have changed
			return clone;
		} catch (InvalidParameterException e) {
			throw new RuntimeException(e);
		}
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
		
		TrustID.constructAndValidate(this, mID); // Throws if invalid
		
		if(mValue < -100 || mValue > 100)
			throw new IllegalStateException("Invalid value: " + mValue);
		
		if(mComment == null)
			throw new NullPointerException("mComment==null");
		
		if(mComment.length() > MAX_TRUST_COMMENT_LENGTH)
			throw new IllegalStateException("Comment is too long: " + mComment.length());
		
		if(mLastChangedDate == null)
			throw new IllegalStateException("mLastChangedDate==null");
		
		if(mLastChangedDate.before(mCreationDate))
			throw new IllegalStateException("mLastChangedDate is before mCreationDate");
		
		if(mLastChangedDate.after(CurrentTimeUTC.get()))
			throw new IllegalStateException("mLastChangedDate is in the future");
		
		if(mTrusterTrustListEdition != getTruster().getEdition() && getTruster().getCurrentEditionFetchState() == Identity.FetchState.Fetched
				&& !(getTruster() instanceof OwnIdentity)) // We do not update mTrusterTrustListEdition for OwnIdentities, they do not need it.
			throw new IllegalStateException("mTrusterTrustListEdition is invalid: " + mTrusterTrustListEdition);
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
