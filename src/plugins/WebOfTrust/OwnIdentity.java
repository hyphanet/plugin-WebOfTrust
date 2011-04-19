/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import java.net.MalformedURLException;
import java.util.Arrays;
import java.util.Date;

import plugins.WebOfTrust.exceptions.InvalidParameterException;
import freenet.keys.FreenetURI;
import freenet.support.CurrentTimeUTC;

/**
 * A local Identity (it belongs to the user)
 * 
 * @author xor (xor@freenetproject.org)
 * @author Julien Cornuwel (batosai@freenetproject.org)
 */
public final class OwnIdentity extends Identity {
	
	protected FreenetURI mInsertURI;
	
	protected Date mLastInsertDate;
	
	
	/**
	 * Creates a new OwnIdentity with the given parameters.
	 * 
	 * @param insertURI A {@link FreenetURI} used to insert this OwnIdentity in Freenet
	 * @param requestURI A {@link FreenetURI} used to fetch this OwnIdentity in Freenet
	 * @param nickName The nickName of this OwnIdentity
	 * @param publishTrustList Whether this OwnIdentity publishes its trustList or not 
	 * @throws InvalidParameterException If a given parameter is invalid
	 */
	public OwnIdentity (FreenetURI insertURI, FreenetURI requestURI, String nickName, boolean publishTrustList) throws InvalidParameterException {	
		super(requestURI, nickName, publishTrustList);
		// This is already done by super()
		// setEdition(0);
		
		if(!insertURI.isUSK() && !insertURI.isSSK())
			throw new IllegalArgumentException("Identity URI keytype not supported: " + insertURI);
		
		// initializeTransient() was not called yet so we must use mRequestURI.getEdition() instead of this.getEdition()
		mInsertURI = insertURI.setKeyType("USK").setDocName(WebOfTrust.WOT_NAME).setSuggestedEdition(mRequestURI.getEdition()).setMetaString(null);
		
		if(!Arrays.equals(mRequestURI.getCryptoKey(), mInsertURI.getCryptoKey()))
			throw new RuntimeException("Request and insert URI do not fit together!");
		
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
	 * @param requestURI A String representing the key needed to fetch this OwnIdentity from Freenet
	 * @param nickName The nickName of this OwnIdentity
	 * @param publishTrustList Whether this OwnIdentity publishes its trustList or not 
	 * @throws InvalidParameterException If a given parameter is invalid
	 * @throws MalformedURLException If either requestURI or insertURI is not a valid FreenetURI
	 */
	public OwnIdentity(String insertURI, String requestURI, String nickName, boolean publishTrustList) throws InvalidParameterException, MalformedURLException {
		this(new FreenetURI(insertURI), new FreenetURI(requestURI), nickName, publishTrustList);
	}
	
	/**
	 * Whether this OwnIdentity needs to be inserted or not.
	 * We insert OwnIdentities when they have been modified AND at least once every three days.
	 * @return Whether this OwnIdentity needs to be inserted or not
	 */
	public final boolean needsInsert() {
		// If the current edition was fetched successfully OR if parsing of it failed, we may insert a new one
		// We may NOT insert a new one if it was not fetched: The identity might be in restore-mode
		if(getCurrentEditionFetchState() == FetchState.NotFetched)
			return false;
		
		return (getLastChangeDate().after(getLastInsertDate()) ||
				(CurrentTimeUTC.getInMillis() - getLastInsertDate().getTime()) > IdentityInserter.MAX_UNCHANGED_TINE_BEFORE_REINSERT); 
	}

	/**
	 * @return This OwnIdentity's insertURI
	 */
	public final FreenetURI getInsertURI() {
		checkedActivate(3);
		return mInsertURI;
	}
	
	
	@Override
	protected final void setEdition(long edition) throws InvalidParameterException {
		super.setEdition(edition);
		
		mCurrentEditionFetchState = FetchState.Fetched;
		
		checkedActivate(3);
		
		if(edition > mInsertURI.getEdition()) {
			mInsertURI = mInsertURI.setSuggestedEdition(edition);
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
	 */
	protected final void restoreEdition(long edition) throws InvalidParameterException {
		setEdition(edition);
		mCurrentEditionFetchState = FetchState.NotFetched;
	}

	/**
	 * Get the Date of last insertion of this OwnIdentity, in UTC, null if it was not inserted yet.
	 */
	public final Date getLastInsertDate() {
		// checkedActivate(depth) is not needed, Date is a db4o primitive type
		return (Date)mLastInsertDate.clone();
	}
	
	/**
	 * Sets the last insertion date of this OwnIdentity to current time in UTC.
	 */
	protected final void updateLastInsertDate() {
		// checkedActivate(depth) is not needed, Date is a db4o primitive type
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
	 * Clones this OwnIdentity. Does <b>not</b> clone the {@link Date} attributes, they are initialized to the current time!
	 */
	public final OwnIdentity clone() {
		try {
			OwnIdentity clone = new OwnIdentity(getInsertURI(), getRequestURI(), getNickname(), doesPublishTrustList());
			clone.initializeTransient(mWebOfTrust);
			
			checkedActivate(4); // For performance only
			
			clone.mCurrentEditionFetchState = getCurrentEditionFetchState();
			clone.setNewEditionHint(getLatestEditionHint()); 
			clone.setContexts(getContexts());
			clone.setProperties(getProperties());
			
			return clone;
		} catch(InvalidParameterException e) {
			throw new RuntimeException(e);
		}
	}
	
	/**
	 * Stores this identity in the database without committing the transaction
	 * You must synchronize on the WoT, on the identity and then on the database when using this function!
	 */
	protected final void storeWithoutCommit() {
		try {
			// 4 is the maximal depth of all getter functions. You have to adjust this when introducing new member variables.
			checkedActivate(4);
			
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
			// 4 is the maximal depth of all getter functions. You have to adjust this when introducing new member variables.
			checkedActivate(4);

			mInsertURI.removeFrom(mDB);
			// checkedDelete(mLastInsertDate); /* Not stored because db4o considers it as a primitive and automatically stores it. */
		}
		catch(RuntimeException e) {
			checkedRollbackAndThrow(e);
		}
		
		super.deleteWithoutCommit(); // Not in the try{} so we don't do checkedRollbackAndThrow twice
	}
	
	public void startupDatabaseIntegrityTest() {
		checkedActivate(4);
		super.startupDatabaseIntegrityTest();
		
		if(mInsertURI == null)
			throw new NullPointerException("mInsertURI==null");
		
		if(!Arrays.equals(mRequestURI.getCryptoKey(), mInsertURI.getCryptoKey()))
			throw new IllegalStateException("Request and insert URI do not fit together!");
		
		if(mInsertURI.getEdition() != mRequestURI.getEdition())
			throw new IllegalStateException("Insert and request editions do not match!");
		
		if(mLastInsertDate == null)
			throw new NullPointerException("mLastInsertDate==null");
		
		if(mLastInsertDate.after(CurrentTimeUTC.get()))
			throw new IllegalStateException("mLastInsertDate is in the future: " + mLastInsertDate);
	}

}
