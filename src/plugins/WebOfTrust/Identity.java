/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import plugins.WebOfTrust.exceptions.InvalidParameterException;
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
 * @param mContexts An ArrayList containing contexts (eg. client apps) an Identity is used for
 * @param mProperties A HashMap containing all custom properties o an Identity
 */
public class Identity implements Cloneable {
	
	public static final int MAX_CONTEXT_NAME_LENGTH = 32;
	public static final int MAX_CONTEXT_AMOUNT = 32;
	public static final int MAX_PROPERTY_NAME_LENGTH = 256;
	public static final int MAX_PROPERTY_VALUE_LENGTH = 10 * 1024;
	public static final int MAX_PROPERTY_AMOUNT = 64;

	/** A unique identifier used to query this Identity from the database. In fact, it is simply a String representing its routing key. */
	protected final String mID;
	
	/** The USK requestURI used to fetch this identity from Freenet. It's edition number is the one of the data which we have currently stored
	 * in the database (the values of this identity, trust values, etc.) if mCurrentEditionWasFetched is true, otherwise it is the next
	 * edition number which should be downloaded. */
	protected FreenetURI mRequestURI;
	
	/** True if the current edition stored in this identity's request URI was fetched, false if it yet has to be downloaded. */
	protected boolean mCurrentEditionWasFetched;
	
	/** When obtaining identities through other people's trust lists instead of identity introduction, we store the edition number they have specified
	 * and pass it as a hint to the USKManager. */
	protected long mLatestEditionHint;
	
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
	
	/** A list of contexts (eg. client apps) this Identity is used for */
	protected ArrayList<String> mContexts;	

	/** A list of this Identity's custom properties */
	protected HashMap<String, String> mProperties;
	
	
	/**
	 * Get a list of fields which the database should create an index on.
	 */
	public static String[] getIndexedFields() {
		return new String[] { "mID", "mLastFetchedDate" };
	}
	
	/**
	 * Creates an Identity. Only for being used by the WoT package and unit tests, not for user interfaces!
	 * 
	 * @param newRequestURI A {@link FreenetURI} to fetch this Identity 
	 * @param newNickname The nickname of this identity
	 * @param doesPublishTrustList Whether this identity publishes its trustList or not
	 * @throws InvalidParameterException if a supplied parameter is invalid
	 */
	protected Identity(FreenetURI newRequestURI, String newNickname, boolean doesPublishTrustList) throws InvalidParameterException {
		// We only use the passed edition number as a hint to prevent attackers from spreading bogus very-high edition numbers.
		setRequestURI(newRequestURI.setSuggestedEdition(0));
		try {
			mLatestEditionHint = newRequestURI.getEdition();
		} catch (IllegalStateException e) {
			mLatestEditionHint = 0;
		}
		mCurrentEditionWasFetched = false;
		
		mID = getIDFromURI(getRequestURI());
		
		mAddedDate = CurrentTimeUTC.get();
		mFirstFetchedDate = new Date(0);
		mLastFetchedDate = new Date(0);
		mLastChangedDate = new Date(0);
		
		if(newNickname == null) {
			mNickname = null;
		}
		else {
			setNickname(newNickname);
		}
		
		setPublishTrustList(doesPublishTrustList);
		mContexts = new ArrayList<String>(4); /* Currently we have: Introduction, Freetalk */
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
		if (mRequestURI != null && !newRequestURI.equalsKeypair(mRequestURI)) {
			throw new IllegalArgumentException("Cannot change the request URI of an existing identity.");
		}
		
		if (!newRequestURI.isUSK() && !newRequestURI.isSSK()) {
			throw new IllegalArgumentException("Identity URI keytype not supported: " + newRequestURI);
		}
		
		mRequestURI = newRequestURI.setKeyType("USK").setDocName(WebOfTrust.WOT_NAME).setMetaString(null);
		updated();
	}

	/**
	 * Get the edition number of the request URI of this identity.
	 */
	public synchronized long getEdition() {
		return getRequestURI().getEdition();
	}
	
	public synchronized boolean currentEditionWasFetched() {
		return mCurrentEditionWasFetched;
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
		
		if (newEdition < currentEdition) {
			throw new InvalidParameterException("The edition of an identity cannot be lowered.");
		}
		
		if (newEdition > currentEdition) {
			mRequestURI = mRequestURI.setSuggestedEdition(newEdition);
			mCurrentEditionWasFetched = false;
			if (newEdition > mLatestEditionHint) {
				// Do not call setNewEditionHint() to prevent confusing logging.
				mLatestEditionHint = newEdition;
			}
			updated();
		}
	}
	
	public synchronized long getLatestEditionHint() {
		return mLatestEditionHint;
	}
	
	/**
	 * Set the "edition hint" of the identity to the given new one.
	 * The "edition hint" is an edition number of which other identities have told us that it is the latest edition.
	 * We only consider it as a hint because they might lie about the edition number, i.e. specify one which is way too high so that the identity won't be
	 * fetched anymore.
	 * 
	 * @return True, if the given hint was newer than the already stored one. You have to tell the {@link IdentityFetcher} about that then.
	 */
	protected synchronized boolean setNewEditionHint(long newLatestEditionHint) {
		if (newLatestEditionHint > mLatestEditionHint) {
			mLatestEditionHint = newLatestEditionHint;
			Logger.debug(this, "Received a new edition hint of " + newLatestEditionHint + " (current: " + mLatestEditionHint + ") for "+ this);
			return true;
		}
		
		return false;
	}
	
	/**
	 * Decrease the current edition by one. Used by {@link #markForRefetch()}.
	 */
	private synchronized void decreaseEdition() {
		mRequestURI = mRequestURI.setSuggestedEdition(Math.max(getEdition() - 1, 0));
		// TODO: I decided that we should not decrease the edition hint here. Think about that again.
	}
	
	/**
	 * Marks the current edition of this identity as not fetched if it was fetched already.
	 * If it was not fetched, decreases the edition of the identity by one.
	 * 
	 * Called by the {@link WebOfTrust} when the {@link Score} of an identity changes from negative or 0 to > 0 to make the {@link IdentityFetcher} re-download it's
	 * current trust list. This is necessary because we do not create the trusted identities of someone if he has a negative score. 
	 */
	protected synchronized void markForRefetch() {
		if (mCurrentEditionWasFetched) {
			mCurrentEditionWasFetched = false;
		} else {
			decreaseEdition();
		}
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
	 * Has to be called when the identity was fetched. Must not be called before setEdition!
	 */
	protected synchronized void onFetched() {
		mCurrentEditionWasFetched = true;
		
		if (mFirstFetchedDate.equals(new Date(0))) {
			mFirstFetchedDate = CurrentTimeUTC.get();
		}
		
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
		return newNickname.length() > 0 && newNickname.length() <= 30
			&& StringValidityChecker.containsNoIDNBlacklistCharacters(newNickname)
			&& StringValidityChecker.containsNoInvalidCharacters(newNickname)
			&& StringValidityChecker.containsNoLinebreaks(newNickname)
			&& StringValidityChecker.containsNoControlCharacters(newNickname)
			&& StringValidityChecker.containsNoInvalidFormatting(newNickname);
	}

	/**
	 * Sets the nickName of this Identity. 
	 * 
	 * @param newNickname A String containing this Identity's NickName. Setting it to null means that it was not retrieved yet.
	 * @throws InvalidParameterException If the nickname contains invalid characters, is empty or longer than 30 characters.
	 */
	public synchronized void setNickname(String newNickname) throws InvalidParameterException {
		if (newNickname == null) {
			throw new NullPointerException("Nickname is null");
		}
	
		newNickname = newNickname.trim();
		
		if(newNickname.length() == 0) {
			throw new InvalidParameterException("Blank nickname");
		}
		
		if(newNickname.length() > 30) {
			throw new InvalidParameterException("Nickname is too long (30 chars max)");
		}
			
		if(!isNicknameValid(newNickname)) {
			throw new InvalidParameterException("Nickname contains illegal characters.");
		}
		
		if (mNickname != null && !mNickname.equals(newNickname)) {
			throw new InvalidParameterException("Changing the nickname of an identity is not allowed.");
		}
	
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
		if (mDoesPublishTrustList == doesPublishTrustList) {
			return;
		}
		
		mDoesPublishTrustList = doesPublishTrustList;
		updated();
	}
	
	/**
	 * Checks whether this identity offers the given contexts.
	 * 
	 * @param context The context we want to know if this Identity has it or not
	 * @return Whether this Identity has that context or not
	 */
	public synchronized boolean hasContext(String context) {
		return mContexts.contains(context.trim());
	}

	/**
	 * Gets all this Identity's contexts.
	 * 
	 * @return A copy of the ArrayList<String> of all contexts of this identity.
	 */
	@SuppressWarnings("unchecked")
	public synchronized ArrayList<String> getContexts() {
		/* TODO: If this is used often - which it probably is, we might verify that no code corrupts the HashMap and return the original one
		 * instead of a copy */
		return (ArrayList<String>)mContexts.clone();
	}

	/**
	 * Adds a context to this identity. A context is a string, the identities contexts are a set of strings - no context will be added more than
	 * once.
	 * Contexts are used by client applications to identify what identities are relevant for their use.
	 * Currently known contexts:
	 * - WoT adds the "Introduction" context if an identity publishes catpchas to allow other to get on it's trust list
	 * - Freetalk, the messaging system for Freenet, adds the "Freetalk" context to identities which use it.
	 * 
	 * @param newContext Name of the context. Must be latin letters and numbers only.
	 * @throws InvalidParameterException If the context name is empty
	 */
	public synchronized void addContext(String newContext) throws InvalidParameterException {
		newContext = newContext.trim();
		
		final int length = newContext.length();
		
		if (length == 0) {
			throw new InvalidParameterException("A blank context cannot be added to an identity.");
		}
		
		if (length > MAX_CONTEXT_NAME_LENGTH) {
			throw new InvalidParameterException("Context names must not be longer than " + MAX_CONTEXT_NAME_LENGTH + " characters.");
		}
		
		if (!StringValidityChecker.isLatinLettersAndNumbersOnly(newContext)) {
			throw new InvalidParameterException("Context names must be latin letters and numbers only");
		}
		
		if (!mContexts.contains(newContext)) {
			if (mContexts.size() >= MAX_CONTEXT_AMOUNT) {
				throw new InvalidParameterException("An identity may not have more than " + MAX_CONTEXT_AMOUNT + " contexts.");
			}
			
			mContexts.add(newContext);
			updated();
		}
	}

	/**
	 * Clears the list of contexts and sets it to the new list of contexts which was passed to the function.
	 * Duplicate contexts are ignored. For invalid contexts an error is logged, all valid ones will be added.
	 * 
	 * IMPORTANT: This always marks the identity as updated so it should not be used on OwnIdentities because it would result in
	 * a re-insert even if nothing was changed.
	 */
	protected synchronized void setContexts(List<String> newContexts) {
		mContexts.clear();
		
		for (String context : newContexts) {
			try {
				addContext(context);
			} catch (InvalidParameterException e) {
				Logger.error(this, "setContexts(): addContext() failed.", e);
			}
		}
		
		mContexts.trimToSize();
	}

	/**
	 * Removes a context from this Identity, does nothing if it does not exist.
	 * If this Identity is no longer used by a client application, the user can tell it and others won't try to fetch it anymore.
	 * 
	 * @param context Name of the context.
	 */
	public synchronized void removeContext(String context) throws InvalidParameterException {
		context = context.trim();
		
		if (mContexts.contains(context)) {
			mContexts.remove(context);
			updated();
		}
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
		
		if (!mProperties.containsKey(key)) {
			throw new InvalidParameterException("The property '" + key +"' isn't set on this identity.");
		}
		
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
	 * @param key Name of the custom property. Must be latin letters, numbers and periods only. Periods may only appear if surrounded by other characters.
	 * @param value Value of the custom property.
	 * @throws InvalidParameterException If the key or the value is empty.
	 */
	public synchronized void setProperty(String key, String value) throws InvalidParameterException {
		key = key.trim();
		
		final int keyLength = key.length();
		
		if (keyLength == 0) {
			throw new InvalidParameterException("Property names must not be empty.");
		}
		
		if (keyLength > MAX_PROPERTY_NAME_LENGTH) {
			throw new InvalidParameterException("Property names must not be longer than " + MAX_PROPERTY_NAME_LENGTH + " characters.");
		}
		
		String[] keyTokens = key.split("[.]");
		for (String token : keyTokens) {
			if (token.length() == 0) {
				throw new InvalidParameterException("Property names which contain periods must have at least one character before and after each period.");
			}
			
			if(!StringValidityChecker.isLatinLettersAndNumbersOnly(token))
				throw new InvalidParameterException("Property names must contain only latin letters, numbers and periods.");
		}
		
		final int valueLength = value.length();
		
		if (valueLength == 0) {
			throw new InvalidParameterException("Property values must not be empty.");
		}
		
		if (valueLength > MAX_PROPERTY_VALUE_LENGTH) {
			throw new InvalidParameterException("Property values must not be longer than " + MAX_PROPERTY_VALUE_LENGTH + " characters");
		}
		
		
		String oldValue = mProperties.get(key);
		if (oldValue == null && mProperties.size() >= MAX_PROPERTY_AMOUNT) {
			throw new InvalidParameterException("An identity may not have more than " + MAX_PROPERTY_AMOUNT + " properties.");
		}
		
		if (oldValue == null || oldValue.equals(value) == false) {
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
		
		for (Entry<String, String> property : newProperties.entrySet()) {
			try {
				setProperty(property.getKey(), property.getValue());
			} catch (InvalidParameterException e) {
				Logger.error(this, "setProperties(): setProperty() failed.", e);
			}
		}
	}

	/**
	 * Removes a custom property from this Identity, does nothing if it does not exist.
	 * 
	 * @param key Name of the custom property.
	 */
	public synchronized void removeProperty(String key) throws InvalidParameterException {
		key = key.trim();		
		if (mProperties.remove(key) != null) {
			updated();
		}
	}
		
	/**
	 * Tell that this Identity has been updated.
	 * 
	 * Updated OwnIdentities will be reinserted by the IdentityInserter automatically.
	 */
	public synchronized void updated() {
		mLastChangedDate = CurrentTimeUTC.get();
	}

	public String toString() {
		return mNickname + "(" + mID + ")";
	}

	/**
	 * Compares whether two identities are equal.
	 * This checks <b>all</b> properties of the identities <b>excluding</b> the {@link Date} properties.
	 */
	@SuppressWarnings("unchecked")
	public boolean equals(Object obj) {
		if (obj == this) {
			return true;
		}
		
		if (!(obj instanceof Identity)) {
			return false;
		}
	
		Identity other = (Identity)obj;
		
		if (!getID().equals(other.getID())) {
			return false;
		}
		
		if (!getRequestURI().equals(other.getRequestURI())) {
			return false;
		}
		
		if (currentEditionWasFetched() != other.currentEditionWasFetched()) {
			return false;
		}
		
		if (getLatestEditionHint() != other.getLatestEditionHint()) {
			return false;
		}
		
		if (!getNickname().equals(other.getNickname())) {
			return false;
		}
		
		if (doesPublishTrustList() != other.doesPublishTrustList()) {
			return false;
		}
		
		
		String[] myContexts = (String[])getContexts().toArray(new String[1]);
		String[] otherContexts = (String[])other.getContexts().toArray(new String[1]);
		
		Arrays.sort(myContexts);
		Arrays.sort(otherContexts);
		
		if (!Arrays.deepEquals(myContexts, otherContexts)) {
			return false;
		}
		
		if (!getProperties().equals(other.getProperties())) {
			return false;
		}
		
		return true;
	}
	
	/**
	 * Clones this identity. Does <b>not</b> clone the {@link Date} attributes, they are initialized to the current time!
	 */
	public Identity clone() {
		try {
			Identity clone = new Identity(getRequestURI(), getNickname(), doesPublishTrustList());
			
			clone.mCurrentEditionWasFetched = currentEditionWasFetched();
			clone.setNewEditionHint(getLatestEditionHint()); 
			clone.setContexts(getContexts());
			clone.setProperties(getProperties());
			
			return clone;
			
		} catch (InvalidParameterException e) {
			throw new RuntimeException(e);
		}
	}
	
}
