/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.net.MalformedURLException;
import java.util.Date;

import plugins.WebOfTrust.exceptions.InvalidParameterException;
import freenet.keys.FreenetURI;
import freenet.support.CurrentTimeUTC;
import freenet.support.Logger;

/**
 * A local Identity (it belongs to the user)
 * 
 * @author xor (xor@freenetproject.org)
 * @author Julien Cornuwel (batosai@freenetproject.org)
 */
public final class OwnIdentity extends Identity implements Cloneable, Serializable {
	
	/** @see Serializable */
	private static final long serialVersionUID = 1L;

	protected FreenetURI mInsertURI;
	
	protected Date mLastInsertDate;
	
	
	/**
	 * Creates a new OwnIdentity with the given parameters.
	 * 
	 * @param insertURI A {@link FreenetURI} used to insert this OwnIdentity in Freenet
	 * @param nickName The nickName of this OwnIdentity
	 * @param publishTrustList Whether this OwnIdentity publishes its trustList or not 
	 * @throws InvalidParameterException If a given parameter is invalid
	 * @throws MalformedURLException If insertURI isn't a valid insert URI.
	 */
	public OwnIdentity (WebOfTrustInterface myWoT, FreenetURI insertURI, String nickName, boolean publishTrustList) throws InvalidParameterException, MalformedURLException {	
		super(myWoT,
				// If we don't set a document name, we will get "java.net.MalformedURLException: SSK URIs must have a document name (to avoid ambiguity)"
				// when calling  FreenetURI.deriveRequestURIFromInsertURI().
				// To make sure the code works, I have copypasted the URI normalization code which we have been using anyway instead of only 
				// adding a .setDocName() - I remember that it was tricky to get code which properly normalizes ALL existing URIs which
				// people shove into WOT
				insertURI.setKeyType("USK").setDocName(WebOfTrustInterface.WOT_NAME).setMetaString(null).deriveRequestURIFromInsertURI(),
				nickName, publishTrustList);
		// This is already done by super()
		// setEdition(0);
		
		if(!insertURI.isUSK() && !insertURI.isSSK())
			throw new InvalidParameterException("Identity URI keytype not supported: " + insertURI);
		
		// initializeTransient() was not called yet so we must use mRequestURI.getEdition() instead of this.getEdition()
		mInsertURI = insertURI.setKeyType("USK").setDocName(WebOfTrustInterface.WOT_NAME).setSuggestedEdition(mRequestURI.getEdition()).setMetaString(null);
		
		// Notice: Check that mInsertURI really is a insert URI is NOT necessary, FreenetURI.deriveRequestURIFromInsertURI() did that already for us.
		// InsertableUSK.createInsertable(mInsertURI, false);
		
		mLastInsertDate = new Date(0);

		// Must be set to "fetched" to prevent the identity fetcher from trying to fetch the current edition and to make the identity inserter
		// actually insert the identity. It won't insert it if the current edition is not marked as fetched to prevent inserts when restoring an
		// own identity.
		mCurrentEditionFetchState = FetchState.Fetched;
		
		// Don't check for mNickname == null to allow restoring of own identities
	}
	
	/**
	 * Creates a new OwnIdentity with the given parameters.
	 * insertURI and requestURI are converted from String to {@link FreenetURI}
	 * 
	 * @param insertURI A String representing the key needed to insert this OwnIdentity in Freenet
	 * @param nickName The nickName of this OwnIdentity
	 * @param publishTrustList Whether this OwnIdentity publishes its trustList or not 
	 * @throws InvalidParameterException If a given parameter is invalid
	 * @throws MalformedURLException If insertURI is not a valid FreenetURI or a request URI instead of an insert URI.
	 */
	public OwnIdentity(WebOfTrustInterface myWoT, String insertURI, String nickName, boolean publishTrustList) throws InvalidParameterException, MalformedURLException {
		this(myWoT, new FreenetURI(insertURI), nickName, publishTrustList);
	}
	
	/**
	 * NOTICE: When changing this function, please also take care of {@link WebOfTrust.restoreOwnIdentity()}
	 * 
	 * @see {@link WebOfTrust.restoreOwnIdentity()}
	 * @return True if getCurrentEditionFetchState()==FetchState.NotFetched/FetchState.ParsingFailed, false for FetchState.Fetched.
	 */
	public final boolean isRestoreInProgress() {
		switch(getCurrentEditionFetchState()) {
			case Fetched:
					// Normal state for an OwnIdentity: When the IdentityInserted has inserted a new edition,
					// it uses setEdition() which immediately sets the FetchState to Fetched
					return false;
			case NotFetched:
					// The identity is definitely in restore mode: When restoreOwnIdentity() converts a non-own
					// identity to an own one, it sets FetchState to NotFetched.
					// Nothing else shall set this state on an OwnIdentity.
					return true;
			case ParsingFailed:
					// We tried to restore the current edition but it didn't parse successfully.
					// We should keep it in restore mode until we have successfully imported an edition:
					// The nickname can be null if no edition of the identity was ever imported.
					return true;
			default:
				throw new IllegalStateException("Unknown FetchState: " + getCurrentEditionFetchState());
		}
	}
	
	/**
	 * Whether this OwnIdentity needs to be inserted or not.
	 * We insert OwnIdentities when they have been modified AND at least once every three days.
	 * @return Whether this OwnIdentity needs to be inserted or not
	 */
	public final boolean needsInsert() {
		if(isRestoreInProgress())
			return false;
		
		// TODO: Instead of only deciding by date whether the current edition was inserted, we should store both the date of
		// the last insert and the date of the next scheduled insert AND the reason for the scheduled insert.
		// There should be different reasons because some changes are not as important as others so we can have larger
		// delays for unimportant reasons.
		
		return (getLastChangeDate().after(getLastInsertDate()) ||
				(CurrentTimeUTC.getInMillis() - getLastInsertDate().getTime()) > IdentityInserter.MAX_UNCHANGED_TINE_BEFORE_REINSERT); 
	}

	/**
	 * @return This OwnIdentity's insertURI
	 */
	public final FreenetURI getInsertURI() {
		checkedActivate(1);
		checkedActivate(mInsertURI, 2);
		return mInsertURI;
	}
	
	
	@Override
	protected final void setEdition(long edition) throws InvalidParameterException {
		super.setEdition(edition);
		
		checkedActivate(1);
		
		mCurrentEditionFetchState = FetchState.Fetched;
		
		checkedActivate(mInsertURI, 2);
		
		if(edition > mInsertURI.getEdition()) {
			mInsertURI = mInsertURI.setSuggestedEdition(edition);
			updated();
		}
	}
	
	/**
	 * ATTENTION: Only use this when you need to construct arbitrary Identity objects - for example when writing an FCP parser.
	 * It won't guarantee semantic integrity of the identity object, for example it allows lowering of the edition and doesn't correct the FetchState.
	 * Instead, use {@link #setEdition(long)} whenever possible.
	 */
	@Override
	public void forceSetEdition(final long newEdition) {
		super.forceSetEdition(newEdition);
		
		checkedActivate(1);
		checkedActivate(mInsertURI, 2);
		
		final long currentEdition = mInsertURI.getEdition();
		
		if(newEdition != currentEdition) {
			mInsertURI.removeFrom(mDB);
			mInsertURI = mInsertURI.setSuggestedEdition(newEdition);
			updated();
		}
	}
	
	
	/**
	 * Only needed for normal identities.
	 */
	@Override
	protected final void markForRefetch() {
		return;
	}
	
	/**
	 * Sets the edition to the given edition and marks it for re-fetching. Used for restoring own identities.
	 * @param fetchedDate The date when the given edition was fetched. Null if it was not fetched yet.
	 */
	protected final void restoreEdition(long edition, Date fetchedDate) throws InvalidParameterException {
		setEdition(edition);
		checkedActivate(1);
		mCurrentEditionFetchState = FetchState.NotFetched;
		
		// checkedDelete(mLastFetchedDate); /* Not stored because db4o considers it as a primitive */
		mLastFetchedDate = fetchedDate != null ? (Date)fetchedDate.clone() : new Date(0);	// Clone it because date is mutable
		
		// This is not really necessary because needsInsert() returns false if mCurrentEditionFetchState == NotFetched
		// However, we still do it because the user might have specified URIs with old edition numbers: Then the IdentityInserter would
		// start insertion the old trust lists immediately after the first one was fetched. With the last insert date being set to current
		// time, this is less likely to happen because the identity inserter has a minimal delay between last insert and next insert.
		updateLastInsertDate();
	}

	/**
	 * Get the Date of last insertion of this OwnIdentity, in UTC, null if it was not inserted yet.
	 */
	public final Date getLastInsertDate() {
		checkedActivate(1); // Date is a db4o primitive type so 1 is enough
		return (Date)mLastInsertDate.clone();	// Clone it because date is mutable
	}
	
	/**
	 * Sets the last insertion date of this OwnIdentity to current time in UTC.
	 */
	protected final void updateLastInsertDate() {
		checkedActivate(1); // Date is a db4o primitive type so 1 is enough
		// checkedDelete(mLastInsertDate); /* Not stored because db4o considers it as a primitive */
		mLastInsertDate = CurrentTimeUTC.get();
	}


	/**
	 * Checks whether two OwnIdentity objects are equal.
	 * This checks <b>all</b> properties of the identities <b>excluding</b> the {@link Date} properties.
	 */
	public final boolean equals(Object obj) {
		if(!super.equals(obj))
			return false;
		
		if(!(obj instanceof OwnIdentity))
			return false;
		
		OwnIdentity other = (OwnIdentity)obj;
		
		if(!getInsertURI().equals(other.getInsertURI()))
			return false;
		
		return true;
	}
	
	/**
	 * {@inheritDoc}
	 */
	@Override
	protected void activateFully() {
		super.activateFully();
	}
	
	/**
	 * Clones this OwnIdentity. Does <b>not</b> clone the {@link Date} attributes, they are initialized to the current time!
	 */
	public final OwnIdentity clone() {
		try {
			OwnIdentity clone = new OwnIdentity(mWebOfTrust, getInsertURI(), getNickname(), doesPublishTrustList());
			
			activateFully(); // For performance only
			
			clone.setEdition(getEdition());
			clone.setNewEditionHint(getLatestEditionHint());
			clone.setCreationDate(getCreationDate());
			clone.mCurrentEditionFetchState = getCurrentEditionFetchState();
			clone.mLastChangedDate = (Date)mLastChangedDate.clone();	// Clone it because date is mutable
			clone.mLastInsertDate = (Date)mLastInsertDate.clone();	// Clone it because date is mutable
			clone.mLatestEditionHint = getLatestEditionHint(); // Don't use the setter since it won't lower the current edition hint.
			clone.setContexts(getContexts());
			clone.setProperties(getProperties());
			
			return clone;
		} catch(InvalidParameterException e) {
			throw new RuntimeException(e);
		} catch (MalformedURLException e) {
			/* This should never happen since we checked when this object was created */
			Logger.error(this, "Caugth MalformedURLException in clone()", e);
			throw new IllegalStateException(e); 
		}
	}
	
	/**
	 * Stores this identity in the database without committing the transaction
	 * You must synchronize on the WoT, on the identity and then on the database when using this function!
	 */
	protected final void storeWithoutCommit() {
		try {
			activateFully();
			
			checkedStore(mInsertURI);
			// checkedStore(mLastInsertDate); /* Not stored because db4o considers it as a primitive and automatically stores it. */
		}
		catch(RuntimeException e) {
			checkedRollbackAndThrow(e);
		}
		
		super.storeWithoutCommit(); // Not in the try{} so we don't do checkedRollbackAndThrow twice
	}
	
	protected final void deleteWithoutCommit() {
		try {
			activateFully();

			mInsertURI.removeFrom(mDB);
			// checkedDelete(mLastInsertDate); /* Not stored because db4o considers it as a primitive and automatically stores it. */
		}
		catch(RuntimeException e) {
			checkedRollbackAndThrow(e);
		}
		
		super.deleteWithoutCommit(); // Not in the try{} so we don't do checkedRollbackAndThrow twice
	}
	
	public void startupDatabaseIntegrityTest() {
		activateFully();
		super.startupDatabaseIntegrityTest();
		
		if(mInsertURI == null)
			throw new NullPointerException("mInsertURI==null");
		
		try {
			if(!mInsertURI.deriveRequestURIFromInsertURI().equals(mRequestURI))
				throw new IllegalStateException("Insert and request URI do not fit together!");
		} catch (MalformedURLException e) {
			throw new IllegalStateException("mInsertURI is not an insert URI!");
		}
		
		if(mLastInsertDate == null)
			throw new NullPointerException("mLastInsertDate==null");
		
		if(mLastInsertDate.after(CurrentTimeUTC.get()))
			throw new IllegalStateException("mLastInsertDate is in the future: " + mLastInsertDate);
	}
	
	/** @see Persistent#serialize() */
	private void writeObject(ObjectOutputStream stream) throws IOException {
		activateFully();
		stream.defaultWriteObject();
	}

}
