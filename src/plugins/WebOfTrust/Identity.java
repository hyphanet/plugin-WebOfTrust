/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.UUID;

import plugins.WebOfTrust.exceptions.InvalidParameterException;
import plugins.WebOfTrust.util.Base32;
import freenet.keys.FreenetURI;
import freenet.keys.USK;
import freenet.support.Base64;
import freenet.support.CurrentTimeUTC;
import freenet.support.IllegalBase64Exception;
import freenet.support.Logger;
import freenet.support.StringValidityChecker;
import freenet.support.codeshortification.IfNull;

/**
 * An identity as handled by the WoT (a USK). 
 * 
 * It has a nickname and as many custom properties as needed (set by the user).
 * 
 * @author xor (xor@freenetproject.org)
 * @author Julien Cornuwel (batosai@freenetproject.org)
 */
public class Identity extends Persistent implements Cloneable, EventSource {

	/** @see Serializable */
	private static transient final long serialVersionUID = 1L;
	
	public static transient final int MAX_NICKNAME_LENGTH = 30;
	public static transient final int MAX_CONTEXT_NAME_LENGTH = 32;
	public static transient final int MAX_CONTEXT_AMOUNT = 32;
	public static transient final int MAX_PROPERTY_NAME_LENGTH = 256;
	public static transient final int MAX_PROPERTY_VALUE_LENGTH = 10 * 1024;
	public static transient final int MAX_PROPERTY_AMOUNT = 64;

	/** A unique identifier used to query this Identity from the database. In fact, it is simply a String representing its routing key. */
	@IndexedField
	protected final String mID;
	
	/** The USK requestURI used to fetch this identity from Freenet. It's edition number is the one of the data which we have currently stored
	 * in the database (the values of this identity, trust values, etc.) if mCurrentEditionFetchState is Fetched or ParsingFailed, otherwise it
     * is the next edition number which should be downloaded.
     * @deprecated Use {@link #mRequestURIString} instead.<br>
     *             See {@link WebOfTrust#upgradeDatabaseFormatVersion12345} for why this was
     *             replaced.
     *             <br>For newly constructed Identity objects, will always be null.<br>
     *             For Identity objects existing in old databases, will be null after
     *             {@link #upgradeDatabaseFormatVersion12345WithoutCommit()}.<br>
     *             <br>TODO: Remove this variable once the aforementioned database upgrade code is
     *             removed. When removing it, make sure to check the db4o manual for whether
     *             it is necessary to delete its backend database field manually using db4o API;
     *             and if necessary do that with another database format version upgrade. */
    @Deprecated
    protected FreenetURI mRequestURI = null;

    /**
     * The USK request {@link FreenetURI} used to fetch this identity from Freenet.<br><br>
     * 
     * The meaning of its edition number is as follows:<br>
     * - If mCurrentEditionFetchState is Fetched ParsingFailed, it's edition number is the one of
     *   the data which we have currently stored in the database (the values of this identity, trust
     *   values, etc.).<br>
     * - For other values of mCurrentEditionFetchState, it is the next edition number which should
     *   be downloaded.<br><br>
     * 
     * Converted to {@link String} via {@link FreenetURI#toString()}: We do not store this as
     * {@link FreenetURI} since the FreenetURI class is not part of WOT, and thus a black box for
     * which we cannot guarantee that db4o can store it properly.
     */
    protected String mRequestURIString;

	public static enum FetchState {
		NotFetched,
		ParsingFailed,
		Fetched
	};
	
	protected FetchState mCurrentEditionFetchState;
	
	/** When obtaining identities through other people's trust lists instead of identity introduction, we store the edition number they have
	 * specified and pass it as a hint to the USKManager. */
	protected long mLatestEditionHint;
	
	/** @see #getLastFetchedDate() */
	@IndexedField
	protected Date mLastFetchedDate;
	
	/** Date of this identity's last modification, for example when it has received new contexts, etc.*/
	protected Date mLastChangedDate;
	
	/** The nickname of this Identity */
	@IndexedField
	protected String mNickname;
	
	/** Whether this Identity publishes its trust list or not */
	protected boolean mDoesPublishTrustList;
	
	/** A list of contexts (eg. client apps) this Identity is used for */
	protected ArrayList<String> mContexts;	

	/** A list of this Identity's custom properties */
	protected HashMap<String, String> mProperties;
	
	/**
	 * @see Identity#activateProperties()
	 */
	private transient boolean mPropertiesActivated;

	/** An {@link UUID} set by {@link EventSource#setVersionID(UUID)}. See its JavaDoc for an
	 *  explanation of the purpose.<br>
	 *  Stored as String to reduce db4o maintenance overhead. */
	private String mVersionID = null;

	
	/* These booleans are used for preventing the construction of log-strings if logging is disabled (for saving some cpu cycles) */
	
	private static transient volatile boolean logDEBUG = false;
	private static transient volatile boolean logMINOR = false;
	
	static {
		Logger.registerClass(Identity.class);
	}
	
	
	/**
	 * A class for generating and validating Identity IDs.
	 * Its purpose is NOT to be stored in the database: That would make the queries significantly slower.
	 * We store the IDs as Strings instead for fast queries.
	 * 
	 * Its purpose is to allow validation of IdentityIDs which we obtain from the database or from the network.
	 * 
	 * TODO: This was added after we already had manual ID-generation / checking in the code everywhere. Use this class instead. 
	 */
	public static final class IdentityID {
		
		/**
		 * Length in characters of an ID, which is a SSK public key hash.
		 */
		public static transient final int LENGTH = 43;
		
		/**
		 * The {@link FreenetURI#getRoutingKey()} of the {@link FreenetURI} of the Identity.
		 * This is the backend data of the real ID {@link #mID}, which only differs in encoding. */
		private final byte[] mRoutingKey;
		
		/** {@link Base64}-encoded version of {@link #mRoutingKey}. */
		private final String mID;
		
		/**
		 * Constructs an identityID from the given String. This is the inverse of IdentityID.toString().
		 * Checks whether the String matches the length limit.
		 * Checks whether it is valid Base64-encoding.
		 */
		private IdentityID(String id) {
			if(id.length() > LENGTH)
				throw new IllegalArgumentException("ID is too long, length: " + id.length());
			
			try {
				mRoutingKey = Base64.decode(id);
			} catch (IllegalBase64Exception e) {
				throw new RuntimeException("ID does not contain valid Base64: " + id);
			}
			
			mID = id;
		}
		
		/**
		 * Constructs an IdentityID from the given {@link FreenetURI}.
		 * Checks whether the URI is of the right type: Only USK or SSK is accepted.
		 */
		private IdentityID(FreenetURI uri) {
			if(!uri.isUSK() && !uri.isSSK())
				throw new IllegalArgumentException("URI must be USK or SSK!");
			
			try {
				uri = uri.deriveRequestURIFromInsertURI();
			} catch(MalformedURLException e) {
				// It is already a request URI
			}
			
			/* WARNING: When changing this, also update Freetalk.WoT.WoTIdentity.getUIDFromURI()! */
			mRoutingKey = uri.getRoutingKey();
			// TODO: Performance: Only compute this on-demand from mRoutingKey in getters.
			// Also make sure that the opposite is possible for the constructor which only
			// receives the value of mID but not mRoutingKey: It should not compute the
			// mRoutingKey from the ID; getters should do that on demand.
			// Further, please check the call hierarchy of all functions of this class to ensure
			// that the lack of always decoding the Base64 which this will introduce does not cause
			// a lack of validation of the input data: This class is being used specifically to
			// validate data from the network in some places, so it must continue to do so there.
			// You should introduce additional validating constructAndValidate*() functions for
			// those cases.
			// Notice that the opposite applies as well:
			// Some of the existing constructAndValidate*() functions are used in places which do
			// not actually need validation. Those places should be changed to use the new
			// non-validating construction functions.
			mID = Base64.encode(mRoutingKey);
		}
		
		/**
		 * Constructs an identityID from the given String. This is the inverse of IdentityID.toString().
		 * Checks whether the String matches the length limit.
		 * Checks whether it is valid Base64-encoding.
		 */
		public static IdentityID constructAndValidateFromString(String id) {
			return new IdentityID(id);
		}
		
		/**
		 * Generates a unique ID from a {@link FreenetURI}, which is the routing key of the author encoded with the Freenet-variant of Base64
		 * We use this to identify identities and perform requests on the database. 
		 * 
		 * Checks whether the URI is of the right type: Only USK or SSK is accepted.
		 * 
		 * @param uri The requestURI or insertURI of the Identity
		 * @return An IdentityID to uniquely identify the identity.
		 */
		public static IdentityID constructAndValidateFromURI(FreenetURI uri) {
			return new IdentityID(uri);
		}
		
		/**
		 * @return The IdentityID encoded as {@link Base64}.
		 * @see #toStringBase32() */
		@Override
		public String toString() {
			return mID;
		}
		
		/**
		 * @return The IdentityID encoded as {@link Base32}
		 * @see #toString() Function for encoding as {@link Base64}. */
		public String toStringBase32() {
			return Base32.encode(mRoutingKey);
		}
		
		@Override
		public final boolean equals(final Object o) {
			if(o instanceof IdentityID)
				return mID.equals(((IdentityID)o).mID);
			
			if(o instanceof String)
				return mID.equals((String)o);
			
			return false;
		}

		/**
		 * Gets the routing key to which this ID is equivalent.
		 * 
		 * It is equivalent because:
		 * An identity is uniquely identified by the USK URI which belongs to it and an USK URI is uniquely identified by its routing key.
		 */
		public byte[] getRoutingKey() throws IllegalBase64Exception {
			return Base64.decode(mID);
		}

	}
	
	
	/**
	 * Creates an Identity. Only for being used by the WoT package and unit tests, not for user interfaces!
	 * 
	 * @param newRequestURI A {@link FreenetURI} to fetch this Identity 
	 * @param newNickname The nickname of this identity
	 * @param doesPublishTrustList Whether this identity publishes its trustList or not
	 * @throws InvalidParameterException if a supplied parameter is invalid
	 * @throws MalformedURLException if newRequestURI isn't a valid request URI
	 */
	protected Identity(WebOfTrustInterface myWoT, FreenetURI newRequestURI, String newNickname, boolean doesPublishTrustList) throws InvalidParameterException, MalformedURLException {
		initializeTransient(myWoT);
		
        // Also takes care of setting the edition to 0 - see below for explanation
        final FreenetURI normalizedRequestURI = testAndNormalizeRequestURI(newRequestURI);
        mRequestURIString = normalizedRequestURI.toString();
		
        mID = IdentityID.constructAndValidateFromURI(normalizedRequestURI).toString();
		
		try {
			// We only use the passed edition number as a hint to prevent attackers from spreading bogus very-high edition numbers.
			mLatestEditionHint = Math.max(newRequestURI.getEdition(), 0);
		} catch (IllegalStateException e) {
			mLatestEditionHint = 0;
		}
		mCurrentEditionFetchState = FetchState.NotFetched;
		
		mLastFetchedDate = new Date(0);
		mLastChangedDate = (Date)mCreationDate.clone(); // Clone it because date is mutable
		
		if(newNickname == null) {
			mNickname = null;
		}
		else {
			setNickname(newNickname);
		}
		
		setPublishTrustList(doesPublishTrustList);
		mContexts = new ArrayList<String>(4); /* Currently we have: Introduction, Freetalk */
		mProperties = new HashMap<String, String>();
	}	

	/**
	 * Creates an Identity. Only for being used by the WoT package and unit tests, not for user interfaces!
	 * 
	 * @param newRequestURI A String that will be converted to {@link FreenetURI} before creating the identity
	 * @param newNickname The nickname of this identity
	 * @param doesPublishTrustList Whether this identity publishes its trustList or not
	 * @throws InvalidParameterException if a supplied parameter is invalid
	 * @throws MalformedURLException if the supplied requestURI isn't a valid request URI
	 */
	public Identity(WebOfTrustInterface myWoT, String newRequestURI, String newNickname, boolean doesPublishTrustList)
		throws InvalidParameterException, MalformedURLException {
		
		this(myWoT, new FreenetURI(newRequestURI), newNickname, doesPublishTrustList);
	}

	/**
	 * Gets this Identity's ID, which is the routing key of the author encoded with the Freenet-variant of Base64.
	 * We use this to identify identities and perform requests on the database.
	 *  
	 * @return A unique identifier for this Identity.
	 */
	@Override
	public final String getID() {
		checkedActivate(1); // String is a db4o primitive type so 1 is enough
		return mID;
	}

	/**
	 * @return The requestURI ({@link FreenetURI}) to fetch this Identity 
	 */
	public final FreenetURI getRequestURI() {
        checkedActivate(1); // String is a db4o primitive type so 1 is enough
        try {
            return new FreenetURI(mRequestURIString);
        } catch (MalformedURLException e) {
            // Should never happen: We never store invalid URIs.
            throw new RuntimeException(e);
        }
	}
	
	/**
	 * Checks whether the given URI is a valid identity request URI and throws if is not.
	 * 
	 * TODO: L10n
	 * 
	 * @return A normalized WOT Identity USK version of the URI with edition set to 0. We use 0 instead of the passed edition number to prevent
	 *		attackers from spreading bogus very-high edition numbers. You should use received edition numbers as edition hints though, see
	 *		{@link #setNewEditionHint(long)}.
	 */
	public static final FreenetURI testAndNormalizeRequestURI(final FreenetURI uri) throws MalformedURLException {
		try {
			if(!uri.isUSK() && !uri.isSSK())
				throw new MalformedURLException("Invalid identity request URI, it is neither USK nor SSK: " + uri);
			
			final FreenetURI normalized = uri.setKeyType("USK").setDocName(WebOfTrustInterface.WOT_NAME).setSuggestedEdition(0).setMetaString(null);
			
			// Check that it really is a request URI
			USK.create(normalized);
			
			return normalized;
		} catch(RuntimeException e) {
			throw new MalformedURLException("Invalid identity request URI: " + e + ", URI was: " + uri.toString());
		}
	}

	/**
	 * Get the edition number of the request URI of this identity.
	 * Safe to be called without any additional synchronization.
	 */
	public final long getEdition() {
		return getRequestURI().getEdition();
	}
	
	public final FetchState getCurrentEditionFetchState() {
		checkedActivate(1);
		return mCurrentEditionFetchState;
	}
	
	/**
	 * ATTENTION: Only use this when you need to construct arbitrary Identity objects - for example when writing an FCP parser.
	 * It won't guarantee semantic integrity of the identity object because it does not update related things such as the date when it was fetched.
	 * Instead, use the event handlers such as {@link #onFetched()}, {@link #onFetched(Date)} and {@link #onParsingFailed()}.
	 * 
	 * @param fetchState The desired fetch state.
	 */
	public final void forceSetCurrentEditionFetchState(final FetchState fetchState) {
		checkedActivate(1);
		mCurrentEditionFetchState = fetchState;
	}

	/**
	 * Sets the edition of the last fetched version of this identity.
	 * That number is published in trustLists to limit the number of editions a newbie has to fetch before he actually gets ans Identity.
	 * 
	 * @param newEdition A long representing the last fetched version of this identity.
	 * @throws InvalidParameterException If the new edition is less than the current one. TODO: Evaluate whether we shouldn't be throwing a RuntimeException instead
	 */
	protected void setEdition(long newEdition) throws InvalidParameterException {
        // If we did not call checkedActivate(), db4o would not notice and not store the modified
        // mRequestURIString - But checkedActivate() is done by the following getRequestURI()
        // already, so we do not call it again here.
        /* checkedActivate(1); */
        final FreenetURI requestURI = getRequestURI();

		// checkedActivate(mCurrentEditionFetchState, 1); is not needed, has no members
		// checkedActivate(mLatestEditionHint, 1); is not needed, long is a db4o primitive type 
		
        long currentEdition = requestURI.getEdition();
		
		if (newEdition < currentEdition) {
			throw new InvalidParameterException("The edition of an identity cannot be lowered.");
		}
		
		if (newEdition > currentEdition) {
            // String is a db4o primitive type, and thus automatically deleted. This also applies
            // to the enum and long which we set in the following code.
            /* checkedDelete(mRequestURIString); */
            mRequestURIString = requestURI.setSuggestedEdition(newEdition).toString();
			mCurrentEditionFetchState = FetchState.NotFetched;
			if (newEdition > mLatestEditionHint) {
				// Do not call setNewEditionHint() to prevent confusing logging.
				mLatestEditionHint = newEdition;
			}
			updated();
		}
	}

	/**
	 * ATTENTION: Only use this when you need to construct arbitrary Identity objects - for example when writing an FCP parser.
	 * It won't guarantee semantic integrity of the identity object, for example it allows lowering of the edition.
	 * Instead, use {@link #setEdition(long)} whenever possible.
	 */
	public void forceSetEdition(final long newEdition) {
        // If we did not call checkedActivate(), db4o would not notice and not store the modified
        // mRequestURIString - But checkedActivate() is done by the following getRequestURI()
        // already, so we do not call it again here.
        /* checkedActivate(1); */
        final FreenetURI requestURI = getRequestURI();
		
        final long currentEdition = requestURI.getEdition();
		
		if(newEdition != currentEdition) {
            // String is a db4o primitive type, and thus automatically deleted. This also applies
            // to the long which we set in the following code.
            /* checkedDelete(mRequestURIString); */
            mRequestURIString = requestURI.setSuggestedEdition(newEdition).toString();
			if (newEdition > mLatestEditionHint) {
				// Do not call setNewEditionHint() to prevent confusing logging.
				mLatestEditionHint = newEdition;
			}
			updated();
		}
	}
	
	public final long getLatestEditionHint() {
		checkedActivate(1); // long is a db4o primitive type so 1 is enough
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
	protected final boolean setNewEditionHint(long newLatestEditionHint) {
		checkedActivate(1); // long is a db4o primitive type so 1 is enough
		
		if (newLatestEditionHint > mLatestEditionHint) {
			mLatestEditionHint = newLatestEditionHint;
			if(logDEBUG) Logger.debug(this, "Received a new edition hint of " + newLatestEditionHint + " (current: " + mLatestEditionHint + ") for "+ this);
			return true;
		}
		
		return false;
	}
	
	/**
	 * ATTENTION: Only use this when you need to construct arbitrary Identity objects - for example when writing an FCP parser.
	 * It won't guarantee semantic integrity of the identity object, for example it allows lowering of the edition hint.
	 * Instead, use {@link #setNewEditionHint(long)} whenever possible.
	 */
	public void forceSetNewEditionHint(long newLatestEditionHint) {
		checkedActivate(1); // long is a db4o primitive type so 1 is enough
		mLatestEditionHint = newLatestEditionHint;
	}
	
	/**
	 * Decrease the current edition by one. Used by {@link #markForRefetch()}.
	 */
	private final void decreaseEdition() {
        // If we did not call checkedActivate(), db4o would not notice and not store the modified
        // mRequestURIString - But checkedActivate() is done by the following getRequestURI()
        // already, so we do not call it again here.
        /* checkedActivate(1); */
        FreenetURI requestURI = getRequestURI();

        requestURI = requestURI.setSuggestedEdition(Math.max(requestURI.getEdition() - 1, 0));

        // String is a db4o primitive type, and thus automatically deleted.
        /* checkedDelete(mRequestURIString); */
        mRequestURIString = requestURI.toString();

		// TODO: I decided that we should not decrease the edition hint here. Think about that again.
	}
	
	/**
	 * Marks the current edition of this identity as not fetched if it was fetched already.
	 * If it was not fetched, decreases the edition of the identity by one.
	 * 
	 * Called by the {@link WebOfTrust} when the {@link Score} of an identity changes from negative or 0 to > 0 to make the {@link IdentityFetcher} re-download it's
	 * current trust list. This is necessary because we do not create the trusted identities of someone if he has a negative score. 
	 */
	protected void markForRefetch() {
		checkedActivate(1);
		// checkedActivate(mCurrentEditionFetchState, 1); not needed, it has no members
		
		if (mCurrentEditionFetchState == FetchState.Fetched) {
			mCurrentEditionFetchState = FetchState.NotFetched;
		} else {
			decreaseEdition();
		}
	}
	
	/**
	 * @return The date when this identity was first seen in a trust list of someone.
	 */
	public final Date getAddedDate() {
		return (Date)getCreationDate().clone();	// Clone it because date is mutable
	}

	/**
	 * Returns the date when we last fetched an {@link IdentityFile} for this Identity.<br>
	 * If the Identity was never fetched yet, this will be <code>new Date(0)</code>. */
	public final Date getLastFetchedDate() {
		checkedActivate(1); // Date is a db4o primitive type so 1 is enough
		return (Date)mLastFetchedDate.clone();	// Clone it because date is mutable
	}

	/**
	 * @return The date of this Identity's last modification.
	 */
	public final Date getLastChangeDate() {
		checkedActivate(1);  // Date is a db4o primitive type so 1 is enough
		return (Date)mLastChangedDate.clone();	// Clone it because date is mutable
	}
	
	/**
	 * Has to be called when the identity was fetched and parsed successfully. Must not be called before setEdition!
	 */
	protected final void onFetched() {
		onFetched(CurrentTimeUTC.get());
	}
	
	/**
	 * Can be used for restoring the last-fetched date from a copy of the identity.
	 * When an identity is fetched in normal operation, please use the version without a parameter. 
	 * 
	 * Must not be called before setEdition!
	 */
	protected final void onFetched(Date fetchDate) {
		checkedActivate(1);
		
		mCurrentEditionFetchState = FetchState.Fetched;
		
		// checkedDelete(mLastFetchedDate); /* Not stored because db4o considers it as a primitive */
		mLastFetchedDate = (Date)fetchDate.clone();	// Clone it because date is mutable
		
		updated();
	}
	
	/**
	 * Has to be called when the identity was fetched and parsing failed. Must not be called before setEdition!
	 */
	protected final void onParsingFailed() {
		checkedActivate(1);
		
		mCurrentEditionFetchState = FetchState.ParsingFailed;
		
		// checkedDelete(mLastFetchedDate); /* Not stored because db4o considers it as a primitive */
		mLastFetchedDate = CurrentTimeUTC.get();
		
		updated();
	}

	/**
	 * @return The Identity's nickName
	 */
	public final String getNickname() {
		checkedActivate(1); // String is a db4o primitive type so 1 is enough
		return mNickname;
	}
	
	/** 
	 * @return The nickname suffixed with "@" and part of {@link #getID()}. The length of the part of the ID is chosen as short as possible so that the
	 *         resulting nickname is unique among all identities currently known to WOT.
	 *         TODO: We currently just suffix the full ID, implement actual shortest unique nickname computation.
	 *         Relevant bugtracker entry is https://bugs.freenetproject.org/view.php?id=6072 
	 */
	public final String getShortestUniqueNickname(){
		return getNickname() + "@" + getID();
	}
	
	/**
	 * Throws if the nickname is given nickname is invalid.
	 * 
	 * IMPORTANT: This code is duplicated in plugins.Freetalk.WoT.WoTIdentity.validateNickname().
	 * Please also modify it there if you modify it here.
	 * 
	 * TODO: L10n
	
	 * @throws InvalidParameterException Contains a message which describes what is wrong with the nickname.  
	 */
	public static void validateNickname(final String newNickname) throws InvalidParameterException {
		if(newNickname.length() == 0)
			throw new InvalidParameterException("Nickname cannot be empty.");
		
		if(newNickname.length() > MAX_NICKNAME_LENGTH)
			throw new InvalidParameterException("Nickname is too long, the limit is " + MAX_NICKNAME_LENGTH + " characters.");
	
		class CharacterValidator  {
			boolean isInvalid(String nickname) {
				return !StringValidityChecker.containsNoIDNBlacklistCharacters(nickname)
						|| !StringValidityChecker.containsNoInvalidCharacters(nickname)
						|| !StringValidityChecker.containsNoLinebreaks(nickname)
						|| !StringValidityChecker.containsNoControlCharacters(nickname)
						|| !StringValidityChecker.containsNoInvalidFormatting(nickname)
						|| nickname.contains("@"); // Must not be allowed since we use it to generate "identity@public-key-hash" unique nicknames
			}
		};
		
		CharacterValidator validator = new CharacterValidator();
		
		if(validator.isInvalid(newNickname)) 
		{
			for(Character c : newNickname.toCharArray()) {
				if(validator.isInvalid(c.toString()))
					throw new InvalidParameterException("Nickname contains invalid character: '" + c + "'");	
			}
			
			// Unicode allows composite characters, i.e. characters which consist of multiple characters.
			// I suspect that this could cause CharacterValidator.isInvalid() to accept an invalid String if we feed it one by one as above.
			// To guard against that, we always throw here:
			throw new InvalidParameterException("Nickname contains invalid unicode characters or formatting.");
		}
	}


	/**
	 * Sets the nickName of this Identity. 
	 * 
	 * @param newNickname A String containing this Identity's NickName. Setting it to null means that it was not retrieved yet.
	 * @throws InvalidParameterException If the nickname contains invalid characters, is empty or longer than MAX_NICKNAME_LENGTH characters.
	 */
	public final void setNickname(String newNickname) throws InvalidParameterException {
		IfNull.thenThrow("Nickname is null");		
		newNickname = newNickname.trim();
		
		validateNickname(newNickname);
		
		checkedActivate(1); // String is a db4o primitive type so 1 is enough
		
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
	public final boolean doesPublishTrustList() {
		checkedActivate(1); // boolean is a db4o primitive type so 1 is enough
		return mDoesPublishTrustList;
	}

	/**
	 * Sets if this Identity publishes its trust list or not. 
	 */
	public final void setPublishTrustList(boolean doesPublishTrustList) {
		checkedActivate(1); // boolean is a db4o primitive type so 1 is enough
		
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
	public final boolean hasContext(String context) {
		checkedActivate(1);
		checkedActivate(mContexts, 2);
		return mContexts.contains(context.trim());
	}

	/**
	 * Gets all this Identity's contexts.
	 * 
	 * @return A copy of the ArrayList<String> of all contexts of this identity.
	 */
	@SuppressWarnings("unchecked")
	public final ArrayList<String> getContexts() {
		/* TODO: If this is used often - which it probably is, we might verify that no code corrupts the HashMap and return the original one
		 * instead of a copy */
		checkedActivate(1);
		checkedActivate(mContexts, 2);
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
	public final void addContext(String newContext) throws InvalidParameterException {
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
		
		checkedActivate(1);
		checkedActivate(mContexts, 2);
		
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
	protected final void setContexts(List<String> newContexts) {
		checkedActivate(1);
		checkedActivate(mContexts, 2);
		
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
	public final void removeContext(String context) throws InvalidParameterException {
		context = context.trim();
		
		checkedActivate(1);
		checkedActivate(mContexts, 2);
		
		if (mContexts.contains(context)) {
			mContexts.remove(context);
			updated();
		}
	}
	
	private synchronized final void activateProperties() {
		// We must not deactivate mProperties if it was already modified by a setter so we need this guard
		if(mPropertiesActivated)
			return;
		
		// TODO: As soon as the db4o bug with hashmaps is fixed, remove this workaround function & replace with:
		// checkedActivate(1);
		// checkedActivate(mProperties, 3);
		checkedActivate(1);
		
		if(mDB.isStored(mProperties)) {
			mDB.deactivate(mProperties);
			checkedActivate(mProperties, 3);
		}
		
		mPropertiesActivated = true;
	}

	/**
	 * Gets the value of one of this Identity's properties.
	 * 
	 * @param key The name of the requested custom property
	 * @return The value of the requested custom property
	 * @throws InvalidParameterException if this Identity doesn't have the required property
	 */
	public final String getProperty(String key) throws InvalidParameterException {
		key = key.trim();
		
		activateProperties();
		
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
	public final HashMap<String, String> getProperties() {
		activateProperties();
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
	public final void setProperty(String key, String value) throws InvalidParameterException {
		// Double check in case someone removes the implicit checks...
		IfNull.thenThrow(key, "Key");
		IfNull.thenThrow(value, "Value");
		
		key = key.trim();
		
		final int keyLength = key.length();
		
		if (keyLength == 0) {
			throw new InvalidParameterException("Property names must not be empty.");
		}
		
		if (keyLength > MAX_PROPERTY_NAME_LENGTH) {
			throw new InvalidParameterException("Property names must not be longer than " + MAX_PROPERTY_NAME_LENGTH + " characters.");
		}
		
		String[] keyTokens = key.split("[.]", -1); // The 1-argument-version wont return empty tokens
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
		
		activateProperties();
		
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
	protected final void setProperties(HashMap<String, String> newProperties) {
		activateProperties();
		if(mDB.isStored(mProperties)) // Prevent logging about deletion of non-stored object in clone()
			checkedDelete(mProperties);
		mProperties = new HashMap<String, String>(newProperties.size() * 2);
		
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
	public final void removeProperty(String key) throws InvalidParameterException {
		activateProperties();
		
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
	public final void updated() {
		checkedActivate(1); // Date is a db4o primitive type so 1 is enough
		// checkedDelete(mLastChangedDate); /* Not stored because db4o considers it as a primitive */
		mLastChangedDate = CurrentTimeUTC.get();
	}

	/** @return A String containing everything which {@link #equals(Object)} would compare. */
	@Override
	public String toString() {
		activateFully(); 
		return "[Identity: " + super.toString()
		     + "; mID: " + mID
		     + "; mRequestURIString: " + mRequestURIString
		     + "; mRequestURI: " + mRequestURI
		     + "; mCurrentEditionFetchState: " + mCurrentEditionFetchState
		     + "; mLatestEditionHint: " + mLatestEditionHint
		     + "; mNickname: " + mNickname
		     + "; mDoesPublishTrustList: " + mDoesPublishTrustList
		     + "; mContexts: " + mContexts
		     + "; mProperties: " + mProperties
		     + "]";
	}

	/**
	 * Compares whether two identities are equal.
	 * This checks <b>all</b> properties of the identities <b>excluding</b> the {@link Date} properties.
     * <br><br>
     * 
     * Notice: {@link #toString()} returns a String which contains the same data as this function
     * compares. This can ease debugging.
	 */
	@Override
	public boolean equals(Object obj) {
		if (obj == this) {
			return true;
		}
		
		// - We need to return false when someone tries to compare an OwnIdentity to a non-own one.
		// - We must also make sure that OwnIdentity can safely use this equals() function as foundation.
		// Both cases are ensured by this check:
		if (obj.getClass() != this.getClass()) {
			return false;
		}
	
		Identity other = (Identity)obj;
		
		if (!getID().equals(other.getID())) {
			return false;
		}
		
		if (!getRequestURI().equals(other.getRequestURI())) {
			return false;
		}
		
		if (getCurrentEditionFetchState() != other.getCurrentEditionFetchState()) {
			return false;
		}
		
		if (getLatestEditionHint() != other.getLatestEditionHint()) {
			return false;
		}
		
		final String nickname = getNickname();
		final String otherNickname = other.getNickname();
		if ((nickname == null) != (otherNickname == null)) {
			return false;
		}
		
		if(nickname != null && !nickname.equals(otherNickname)) {
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
	
	@Override
	public int hashCode() {
		return getID().hashCode();
	}
	
	/**
	 * {@inheritDoc}
	 */
	@Override
	protected void activateFully() {
		// 4 is the maximal depth of all getter functions. You have to adjust this when introducing new member variables.
		checkedActivate(4);
		// Workaround for db4o bug
		activateProperties();
	}
	
	/**
	 * Clones this identity. Does <b>not</b> clone the {@link Date} attributes, they are initialized to the current time!
	 */
	@Override
	public Identity clone() {
		try {
			Identity clone = new Identity(mWebOfTrust, getRequestURI(), getNickname(), doesPublishTrustList());
			
			activateFully(); // For performance only
			clone.setEdition(getEdition());
			clone.setNewEditionHint(getLatestEditionHint());
			clone.setCreationDate(getCreationDate());
			clone.mCurrentEditionFetchState = getCurrentEditionFetchState();
			clone.mLatestEditionHint = getLatestEditionHint(); // Don't use the setter since it won't lower the current edition hint.
			clone.setContexts(getContexts());
			clone.setProperties(getProperties());
			// Clone it because date is mutable. Set it *after* calling all setters since they would
			// update it to the current time otherwise.
	        clone.mLastChangedDate = (Date)mLastChangedDate.clone();
	        
			return clone;
			
		} catch (InvalidParameterException e) {
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
	@Override
	protected void storeWithoutCommit() {
		try {
			activateFully();

			// checkedStore(mID); /* Not stored because db4o considers it as a primitive and automatically stores it. */

            assert(mRequestURI == null)
                : "upgradeDatabaseFormatVersion5WithoutCommit() should delete mRequestURI";

            /* String is a db4o primitive type, and thus automatically stored. */
            // checkedStore(mRequestURIString);

			// checkedStore(mFirstFetchedDate); /* Not stored because db4o considers it as a primitive and automatically stores it. */
			// checkedStore(mLastFetchedDate); /* Not stored because db4o considers it as a primitive and automatically stores it. */
			// checkedStore(mLastChangedDate); /* Not stored because db4o considers it as a primitive and automatically stores it. */
			// checkedStore(mNickname); /* Not stored because db4o considers it as a primitive and automatically stores it. */
			// checkedStore(mDoesPublishTrustList); /* Not stored because db4o considers it as a primitive and automatically stores it. */
			checkedStore(mProperties);
			checkedStore(mContexts);
			checkedStore();
		}
		catch(final RuntimeException e) {
			checkedRollbackAndThrow(e);
		}
	}

    /** @see WebOfTrust#upgradeDatabaseFormatVersion5 */
    protected void upgradeDatabaseFormatVersion12345WithoutCommit() {
        checkedActivate(1);
        
        if(mRequestURIString != null) {
            // This object has had its mRequestURI migrated to mRequestURIString already.
            // Might happen during very old database format version upgrade codepaths which
            // create fresh Identity objects - newly constructed objects will not need migration.
            assert(mRequestURI == null);
            return;
        }
        
        assert(mRequestURI != null);
        checkedActivate(mRequestURI, 2);
        mRequestURIString = mRequestURI.toString();

        // A FreenetURI currently only contains db4o primitive types (String, arrays, etc.) and thus
        // we can delete it having to delete its member variables explicitly.
        mDB.delete(mRequestURI);
        mRequestURI = null;

        // OwnIdentity.upgradeDatabaseFormatVersion12345WithoutCommit() needs this, don't remove it
        storeWithoutCommit();
    }

	/**
	 * Locks the WoT and the database and stores the identity.
	 */
	protected final void storeAndCommit() {
		synchronized(mWebOfTrust) {
		synchronized(Persistent.transactionLock(mDB)) {
			try {
				storeWithoutCommit();
				checkedCommit(this);
			}
			catch(RuntimeException e) {
				checkedRollbackAndThrow(e);
			}
		}
		}
	}
	
	/**
	 * You have to lock the WoT and the IntroductionPuzzleStore before calling this function.
	 * @param identity
	 */
	@Override
	protected void deleteWithoutCommit() {
		try {
			activateFully();
			
			// checkedDelete(mID); /* Not stored because db4o considers it as a primitive and automatically stores it. */

            assert(mRequestURI == null)
                : "upgradeDatabaseFormatVersion5WithoutCommit() should delete mRequestURI";
            // checkedDelete(mRequestURI);

            /* String is a db4o primitive type, and thus automatically deleted. */
            // checkedDelete(mRequestURIString);

			checkedDelete(mCurrentEditionFetchState); // TODO: Is this still necessary?
			// checkedDelete(mLastFetchedDate); /* Not stored because db4o considers it as a primitive and automatically stores it. */
			// checkedDelete(mLastChangedDate); /* Not stored because db4o considers it as a primitive and automatically stores it. */
			// checkedDelete(mNickname); /* Not stored because db4o considers it as a primitive and automatically stores it. */
			// checkedDelete(mDoesPublishTrustList); /* Not stored because db4o considers it as a primitive and automatically stores it. */
			checkedDelete(mProperties);
			checkedDelete(mContexts);
			checkedDelete();
		}
		catch(RuntimeException e) {
			checkedRollbackAndThrow(e);
		}
	}

	@Override
	public void startupDatabaseIntegrityTest() {
		activateFully();

		if(mID == null)
			throw new NullPointerException("mID==null");

        if(mRequestURI != null) {
            throw new IllegalStateException(
                "upgradeDatabaseFormatVersion5WithoutCommit() should delete mRequestURI");
        }

		if(mRequestURIString == null)
			throw new NullPointerException("mRequestURIString==null");
		
        final FreenetURI requestURI = getRequestURI();
		
		try {
            if(!testAndNormalizeRequestURI(requestURI).equals(requestURI.setSuggestedEdition(0)))
                throw new IllegalStateException("Request URI is not normalized: " + requestURI);
		} catch (MalformedURLException e) {
            throw new IllegalStateException("Request URI is invalid: " + e);
		}
		
        if(!mID.equals(IdentityID.constructAndValidateFromURI(requestURI).toString()))
            throw new IllegalStateException("ID does not match request URI!");
		
		IdentityID.constructAndValidateFromString(mID); // Throws if invalid
		
		if(mCurrentEditionFetchState == null)
			throw new NullPointerException("mCurrentEditionFetchState==null");
		
        if(mLatestEditionHint < 0 || mLatestEditionHint < requestURI.getEdition()) {
            throw new IllegalStateException("Invalid edition hint: " + mLatestEditionHint
                                          + "; current edition: " + requestURI.getEdition());
        }

		if(mLastFetchedDate == null)
			throw new NullPointerException("mLastFetchedDate==null");
		
		if(mLastFetchedDate.after(CurrentTimeUTC.get()))
			throw new IllegalStateException("mLastFetchedDate is in the future: " + mLastFetchedDate);
		
		if(mLastChangedDate == null)
			throw new NullPointerException("mLastChangedDate==null");
		
		if(mLastChangedDate.before(mCreationDate))
			throw new IllegalStateException("mLastChangedDate is before mCreationDate!");
		
		if(mLastChangedDate.before(mLastFetchedDate))
			throw new IllegalStateException("mLastChangedDate is before mLastFetchedDate!");
		
		if(mLastChangedDate.after(CurrentTimeUTC.get()))
			throw new IllegalStateException("mLastChangedDate is in the future: " + mLastChangedDate);
		
		if(mNickname != null) {
			try {
				validateNickname(mNickname);
			} catch(InvalidParameterException e) {
				throw new IllegalStateException(e);
			}
		}
		
		if(mContexts == null)
			throw new NullPointerException("mContexts==null");
		
		if(mProperties == null)
			throw new NullPointerException("mProperties==null");
		
		if(mContexts.size() > MAX_CONTEXT_AMOUNT)
			throw new IllegalStateException("Too many contexts: " + mContexts.size());
		
		if(mProperties.size() > MAX_PROPERTY_AMOUNT)
			throw new IllegalStateException("Too many properties: " + mProperties.size());
			
		// TODO: Verify context/property names/values 
	}
	
	/** @see Persistent#serialize() */
	private void writeObject(ObjectOutputStream stream) throws IOException {
		activateFully();
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
