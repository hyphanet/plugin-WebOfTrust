/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WoT;

import java.net.MalformedURLException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map.Entry;

import plugins.WoT.exceptions.InvalidParameterException;
import freenet.keys.FreenetURI;
import freenet.support.Base64;
import freenet.support.CurrentTimeUTC;
import freenet.support.Logger;
import freenet.support.StringValidityChecker;

/**
 * An identity as handled by the WoT (a USK). 
 * 
 * It has a nickname and as many custom properties as needed (set by the user).
 * 
 * @author xor (xor@freenetproject.org), Julien Cornuwel (batosai@freenetproject.org)
 * @param mID A unique identifier used to query an Identity from the database
 * @param mRequestURI The requestURI used to fetch this identity from Freenet
 * @param mNickname The nickname of an Identity
 * @param mDoesPublishTrustList Whether an identity publishes its trust list or not
 * @param mProperties A HashMap containing all custom properties o an Identity
 */
public class Identity {

	/** A unique identifier used to query this Identity from the database. In fact, it is simply a String representing its routing key. */
	protected final String mID;
	
	/** The USK requestURI used to fetch this identity from Freenet. It's edition number is the one of the data which we have currently stored
	 * in the database (the values of this identity, trust values, etc.) */
	protected FreenetURI mRequestURI;
	
	/** Date when this identity was added. */
	protected final Date mAddedDate;
	
	/** Date of the first time we successfully fetched the XML of this identity */
	protected Date mFirstFetchedDate;
	
	/** Date of the last time we successfully fetched the XML of this identity */
	protected Date mLastFetchedDate;
	
	/** Date of this identity's last modification, for example when it has received new contexts, etc.*/
	protected Date mLastChangedDate;
	
	/** The nickname of this Identity */
	protected String mNickname;
	
	/** Whether this Identity publishes its trust list or not */
	protected boolean mDoesPublishTrustList;

	/** A list of this Identity's custom properties */
	protected HashMap<String, String> mProperties;
	
	
	/**
	 * Get a list of fields which the database should create an index on.
	 */
	public static String[] getIndexedFields() {
		return new String[] { "mID", "mLastFetchedDate" };
	}
	
	/**
	 * Creates an Identity.
	 * 
	 * @param newRequestURI A {@link FreenetURI} to fetch this Identity 
	 * @param newNickname The nickname of this identity
	 * @param doesPublishTrustList Whether this identity publishes its trustList or not
	 * @throws InvalidParameterException if a supplied parameter is invalid
	 */
	protected Identity(FreenetURI newRequestURI, String newNickname, boolean doesPublishTrustList) throws InvalidParameterException {
		setRequestURI(newRequestURI);
		mID = getIDFromURI(getRequestURI());
		
		mAddedDate = CurrentTimeUTC.get();
		mFirstFetchedDate = new Date(0);
		mLastFetchedDate = new Date(0);
		mLastChangedDate = new Date(0);
		
		setNickname(newNickname);
		setPublishTrustList(doesPublishTrustList);
		mProperties = new HashMap<String, String>();
		
		Logger.debug(this, "New identity: " + getNickname() + ", URI: " + newRequestURI);
	}	

	/**
	 * Creates an Identity. Only for being used by the WoT package and unit tests, not for user interfaces!
	 * 
	 * @param newRequestURI A String that will be converted to {@link FreenetURI} before creating the identity
	 * @param newNickname The nickname of this identity
	 * @param doesPublishTrustList Whether this identity publishes its trustList or not
	 * @throws InvalidParameterException if a supplied parameter is invalid
	 * @throws MalformedURLException if the supplied requestURI isn't a valid FreenetURI
	 */
	public Identity(String newRequestURI, String newNickname, boolean doesPublishTrustList)
		throws InvalidParameterException, MalformedURLException {
		
		this(new FreenetURI(newRequestURI), newNickname, doesPublishTrustList);
	}

	/**
	 * Gets this Identity's ID, which is the routing key of the author encoded with the Freenet-variant of Base64.
	 * We use this to identify identities and perform requests on the database.
	 *  
	 * @return A unique identifier for this Identity.
	 */
	public String getID() {
		return mID;
	}

	/**
	 * Generates a unique IDfrom a {@link FreenetURI}, which is the routing key of the author encoded with the Freenet-variant of Base64
	 * We use this to identify identities and perform requests on the database. 
	 * 
	 * @param uri The requestURI of the Identity
	 * @return A string to uniquely identify the identity.
	 */
	public static final String getIDFromURI(FreenetURI uri) {
		/* WARNING: When changing this, also update Freetalk.WoT.WoTIdentity.getUIDFromURI()! */
		return Base64.encode(uri.getRoutingKey());
	}

	/**
	 * @return The requestURI ({@link FreenetURI}) to fetch this Identity 
	 */
	public synchronized FreenetURI getRequestURI() {
		return mRequestURI;
	}

	
	/**
	 * Sets the requestURI of this Identity.
	 * The given {@link FreenetURI} is converted to USK and the document name is forced to WoT.
	 * 
	 * @param newRequestURI The FreenetURI used to fetch this identity 
	 * @throws IllegalArgumentException If the given FreenetURI is neither a SSK nor a USK or if the keypair does not match the old one.
	 */
	protected synchronized void setRequestURI(FreenetURI newRequestURI) {
		if(mRequestURI != null && !newRequestURI.equalsKeypair(mRequestURI))
			throw new IllegalArgumentException("Cannot change the request URI of an existing identity.");
		
		if(!newRequestURI.isUSK() && !newRequestURI.isSSK())
			throw new IllegalArgumentException("Identity URI keytype not supported: " + newRequestURI);
		
		mRequestURI = newRequestURI.setKeyType("USK").setDocName("WoT").setMetaString(null);
		updated();
	}

	/**
	 * Get the edition number of the request URI of this identity.
	 */
	public synchronized long getEdition() {
		return getRequestURI().getSuggestedEdition();
	}

	/**
	 * Sets the edition of the last fetched version of this identity.
	 * That number is published in trustLists to limit the number of editions a newbie has to fetch before he actually gets ans Identity.
	 * 
	 * @param newEdition A long representing the last fetched version of this identity.
	 * @throws InvalidParameterException If the new edition is less than the current one.
	 */
	protected synchronized void setEdition(long newEdition) throws InvalidParameterException {
		long currentEdition = mRequestURI.getEdition();
		
		if(newEdition < currentEdition)
			throw new InvalidParameterException("The edition of an identity cannot be lowered.");
		
		if(newEdition > currentEdition) {
			mRequestURI = mRequestURI.setSuggestedEdition(newEdition);
			updated();
		}
	}
	
	
	/**
	 * Decrease the currentEdition by one.
	 * 
	 * Called by the WoT when the score of an identity changes from negative or 0 to > 0 to make the IdentityFetcher re-download
	 * it's current trust list. This is necessary because we do not create the trusted identities of someone if he has a negative score. 
	 */
	protected synchronized void decreaseEdition() {
		long newEdition = getEdition() - 1;
		
		if(newEdition < 0) {
			newEdition = 0;
			/* If the edition is 0, the fetcher decides via last fetched date whether to fetch current or next */
			mLastFetchedDate = new Date(0);
		}
		
		mRequestURI = mRequestURI.setSuggestedEdition(newEdition);
	}
	
	/**
	 * @return The date when this identity was first seen in a trust list of someone.
	 */
	public Date getAddedDate() {
		return (Date)mAddedDate.clone();
	}

	/**
	 * @return The date when the identity was fetched successfully for the first time,
	 * equal to "new Date(0)" if it was never fetched.
	 */
	public Date getFirstFetchedDate() {
		return (Date)mFirstFetchedDate.clone();
	}
	
	/**
	 * @return The date of this Identity's last modification.
	 */
	public synchronized Date getLastFetchedDate() {
		return (Date)mLastFetchedDate.clone();
	}

	/**
	 * @return The date of this Identity's last modification.
	 */
	public synchronized Date getLastChangeDate() {
		return (Date)mLastChangedDate.clone();
	}
	
	/**
	 * Has to be called when the identity was fetched.
	 */
	protected synchronized void onFetched() {
		if(mFirstFetchedDate.equals(new Date(0)))
			mFirstFetchedDate = CurrentTimeUTC.get();
		
		mLastFetchedDate = CurrentTimeUTC.get();
		updated();
	}

	/**
	 * @return The Identity's nickName
	 */
	public synchronized String getNickname() {
		return mNickname;
	}

	/* IMPORTANT: This code is duplicated in plugins.Freetalk.WoT.WoTIdentity.validateNickname().
	 * Please also modify it there if you modify it here */
	public boolean isNicknameValid(String newNickname) {
		return newNickname.length() > 0 && newNickname.length() < 50 && 
				StringValidityChecker.containsNoIDNBlacklistCharacters(newNickname);
	}

	/**
	 * Sets the nickName of this Identity. 
	 * 
	 * @param mNickname A String containing this Identity's NickName. Setting it to null means that it was not retrieved yet.
	 * @throws InvalidParameterException if the nickName's length is bigger than 50, or if it empty
	 */
	public synchronized void setNickname(String newNickname) throws InvalidParameterException {
		if(newNickname != null)
			newNickname = newNickname.trim();
		
		if(newNickname != null) {
			if(newNickname.length() == 0) throw new InvalidParameterException("Blank nickname");
			if(newNickname.length() > 50) throw new InvalidParameterException("Nickname is too long (50 chars max)");
			
			if(!isNicknameValid(newNickname))
				throw new InvalidParameterException("Nickname contains illegal characters.");
		}
		
		if(mNickname != null && !mNickname.equals(newNickname))
			throw new InvalidParameterException("Changing the nickname of an identity is not allowed.");
	
		mNickname = newNickname;
		updated();
	}

	/**
	 * Checks whether this identity publishes a trust list.
	 * 
	 * @return Whether this Identity publishes its trustList or not.
	 */
	public synchronized boolean doesPublishTrustList() {
		return mDoesPublishTrustList;
	}

	/**
	 * Sets if this Identity publishes its trust list or not. 
	 */
	public synchronized void setPublishTrustList(boolean doesPublishTrustList) {
		if(mDoesPublishTrustList == doesPublishTrustList)
			return;
		
		mDoesPublishTrustList = doesPublishTrustList;
		updated();
	}



	/**
	 * Gets the value of one of this Identity's properties.
	 * 
	 * @param key The name of the requested custom property
	 * @return The value of the requested custom property
	 * @throws InvalidParameterException if this Identity doesn't have the required property
	 */
	public synchronized String getProperty(String key) throws InvalidParameterException {
		key = key.trim();
		
		if(!mProperties.containsKey(key))
			throw new InvalidParameterException("The property '" + key +"' isn't set on this identity.");
		
		return mProperties.get(key);
	}

	/**
	 * Gets all custom properties from this Identity.
	 * 
	 * @return A copy of the HashMap<String, String> referencing all this Identity's custom properties.
	 */
	@SuppressWarnings("unchecked")
	public synchronized HashMap<String, String> getProperties() {
		/* TODO: If this is used often, we might verify that no code corrupts the HashMap and return the original one instead of a copy */
		return (HashMap<String, String>)mProperties.clone();
	}
	
	/**
	 * Sets a custom property on this Identity. Custom properties keys have to be unique.
	 * This can be used by client applications that need to store additional informations on their Identities (crypto keys, avatar, whatever...).
	 * The key is always trimmed before storage, the value is stored as passed.
	 *
	 * @param db A reference to the database.
	 * @param key Name of the custom property.
	 * @param value Value of the custom property.
	 * @throws InvalidParameterException If the key or the value is empty.
	 */
	public synchronized void setProperty(String key, String value) throws InvalidParameterException {
		key = key.trim();
		
		if(key.length() == 0 || value.length() == 0)
			throw new InvalidParameterException("Blank key or value in this property");
		
		/* FIXME: Limit the length of key and value */
		
		String oldValue = mProperties.get(key);
		if(oldValue == null || oldValue.equals(value) == false) {
			/* FIXME: Limit the amount of properties */
			mProperties.put(key, value);
			updated();
		}
	}

	/**
	 * Clears the list of properties and sets it to the new list of properties which was passed to the function.
	 * For invalid properties an error is logged, all valid ones will be added.
	 * 
	 * IMPORTANT: This always marks the identity as updated so it should not be used on OwnIdentities because it would result in
	 * a re-insert even if nothing was changed.
	 */
	protected synchronized void setProperties(HashMap<String, String> newProperties) {
		mProperties = new HashMap<String, String>();
		
		for(Entry<String, String> property : newProperties.entrySet()) {
			try {
				setProperty(property.getKey(), property.getValue());
			}
			catch(InvalidParameterException e) {
				Logger.error(this, "setProperties(): setProperty() failed.", e);
			}
		}
	}

	/**
	 * Removes a custom property from this Identity, does nothing if it does not exist.
	 * 
	 * @param db A reference to the database.
	 * @param key Name of the custom property.
	 */
	public synchronized void removeProperty(String key) throws InvalidParameterException {
		key = key.trim();		
		if(mProperties.remove(key) != null)
			updated();
	}
		
	/**
	 * Tell that this Identity has been updated.
	 * 
	 * Updated OwnIdentities will be reinserted by the IdentityInserter automatically.
	 */
	public synchronized void updated() {
		mLastChangedDate = CurrentTimeUTC.get();
	}

}
