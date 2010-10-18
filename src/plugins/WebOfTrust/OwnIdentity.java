/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import java.net.MalformedURLException;
import java.util.Date;

import plugins.WebOfTrust.exceptions.InvalidParameterException;
import freenet.keys.FreenetURI;
import freenet.support.CurrentTimeUTC;

/**
 * A local Identity (it belongs to the user)
 * 
 * @author xor (xor@freenetproject.org), Julien Cornuwel (batosai@freenetproject.org)
 *
 */
public class OwnIdentity extends Identity {
	
	protected FreenetURI mInsertURI;
	
	protected final Date mCreationDate;
	
	protected Date mLastInsertDate;
	
	/**
	 * Get a list of fields which the database should create an index on.
	 */
	public static String[] getIndexedFields() {
		return new String[] { "mID" };
	}
	
	
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
		
		mCreationDate = CurrentTimeUTC.get();
		setInsertURI(insertURI); // Also sets the edition to the edition of the request URI
		mLastInsertDate = new Date(0);

		// Must be set to "fetched" to prevent the identity fetcher from trying to fetch the current edition and to make the identity inserter
		// actually insert the identity. It won't insert it if the current edition is not marked as fetched to prevent inserts when restoring an
		// own identity.
		mCurrentEditionFetchState = FetchState.Fetched;
		
		if(mRequestURI == null)
			throw new InvalidParameterException("Own identities must have a request URI.");
		
		if(mInsertURI == null)
			throw new InvalidParameterException("Own identities must have an insert URI.");
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
	public synchronized boolean needsInsert() {
		if(mCurrentEditionFetchState != FetchState.Fetched)
			return false;
		
		return (getLastChangeDate().after(getLastInsertDate()) ||
				(CurrentTimeUTC.getInMillis() - getLastInsertDate().getTime()) > IdentityInserter.MAX_UNCHANGED_TINE_BEFORE_REINSERT); 
	}

	/**
	 * @return This OwnIdentity's insertURI
	 */
	public synchronized FreenetURI getInsertURI() {
		return mInsertURI;
	}
	
	/**
	 * Sets this OwnIdentity's insertURI. 
	 * The key must be a USK or a SSK, and is stored as a USK anyway.
	 * 
	 * The edition number is set to the same edition number as the request URI of this own identity.
	 *  
	 * @param newInsertURI This OwnIdentity's insertURI
	 * @throws InvalidParameterException if the supplied key is neither a USK nor a SSK
	 */
	private void setInsertURI(FreenetURI newInsertURI) throws InvalidParameterException {
		if(mInsertURI != null && !newInsertURI.equalsKeypair(mInsertURI))
			throw new IllegalArgumentException("Cannot change the insert URI of an existing identity.");
		
		if(!newInsertURI.isUSK() && !newInsertURI.isSSK())
			throw new IllegalArgumentException("Identity URI keytype not supported: " + newInsertURI);
		
		mInsertURI = newInsertURI.setKeyType("USK").setDocName("WoT").setMetaString(null).setSuggestedEdition(getEdition());
		updated();
	}
	
	@Override
	protected synchronized void setEdition(long edition) throws InvalidParameterException {
		super.setEdition(edition);
		
		mCurrentEditionFetchState = FetchState.Fetched;
		
		if(edition > mInsertURI.getEdition()) {
			mInsertURI = mInsertURI.setSuggestedEdition(edition);
			updated();
		}
	}
	
	
	/**
	 * Only needed for normal identities.
	 */
	@Override
	protected synchronized void markForRefetch() {
		return;
	}
	
	/**
	 * Sets the edition to the given edition and marks it for re-fetching. Used for restoring own identities.
	 */
	protected synchronized void restoreEdition(long edition) throws InvalidParameterException {
		setEdition(edition);
		mCurrentEditionFetchState = FetchState.NotFetched;
	}
	
	public Date getCreationDate() {
		return (Date)mCreationDate.clone();
	}

	/**
	 * Get the Date of last insertion of this OwnIdentity, in UTC, null if it was not inserted yet.
	 */
	public synchronized Date getLastInsertDate() {
		return (Date)mLastInsertDate.clone();
	}
	
	/**
	 * Sets the last insertion date of this OwnIdentity to current time in UTC.
	 */
	protected synchronized void updateLastInsertDate() {
		mLastInsertDate = CurrentTimeUTC.get();
	}


	/**
	 * Checks whether two OwnIdentity objects are equal.
	 * This checks <b>all</b> properties of the identities <b>excluding</b> the {@link Date} properties.
	 */
	public boolean equals(Object obj) {
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
	public OwnIdentity clone() {
		try {
			OwnIdentity clone = new OwnIdentity(getInsertURI(), getRequestURI(), getNickname(), doesPublishTrustList());
			
			clone.mCurrentEditionFetchState = getCurrentEditionFetchState();
			clone.setNewEditionHint(getLatestEditionHint()); 
			clone.setContexts(getContexts());
			clone.setProperties(getProperties());
			
			return clone;
		} catch(InvalidParameterException e) {
			throw new RuntimeException(e);
		}
	}

}
