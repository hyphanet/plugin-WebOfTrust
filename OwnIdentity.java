/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WoT;

import java.net.MalformedURLException;
import java.util.Date;

import plugins.WoT.exceptions.InvalidParameterException;
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
		mCreationDate = CurrentTimeUTC.get();
		setInsertURI(insertURI);
		mLastInsertDate = new Date(0);
		setEdition(0);
		
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
	 * @param requestURIA String representing the key needed to fetch this OwnIdentity from Freenet
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
		return (getLastChangeDate().after(getLastInsertDate()) || (new Date().getTime() - getLastInsertDate().getTime()) > 1000*60*60*24*3); 
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
	 * @param key this OwnIdentity's insertURI
	 * @throws InvalidParameterException if the supplied key is neither a USK nor a SSK
	 */
	private void setInsertURI(FreenetURI newInsertURI) throws InvalidParameterException {
		if(mInsertURI != null && !newInsertURI.equalsKeypair(mInsertURI))
			throw new IllegalArgumentException("Cannot change the insert URI of an existing identity.");
		
		if(!newInsertURI.isUSK() && !newInsertURI.isSSK())
			throw new IllegalArgumentException("Identity URI keytype not supported: " + newInsertURI);
		
		mInsertURI = newInsertURI.setKeyType("USK").setDocName("WoT").setMetaString(null);
		updated();
	}
	
	@Override
	protected synchronized void setEdition(long edition) throws InvalidParameterException {
		super.setEdition(edition);
		
		if(edition > mInsertURI.getEdition()) {
			mInsertURI = mInsertURI.setSuggestedEdition(edition);
			updated();
		}
	}
	
	public Date getCreationDate() {
		return mCreationDate;
	}

	/**
	 * Get the Date of last insertion of this OwnIdentity, in UTC.
	 */
	public synchronized Date getLastInsertDate() {
		return mLastInsertDate;
	}
	
	/**
	 * Sets the last insertion date of this OwnIdentity to current time in UTC.
	 */
	protected synchronized void updateLastInsertDate() {
		mLastInsertDate = CurrentTimeUTC.get();
	}

}
