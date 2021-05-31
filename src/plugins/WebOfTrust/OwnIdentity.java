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
import plugins.WebOfTrust.network.input.EditionHint;
import plugins.WebOfTrust.network.input.IdentityDownloaderController;
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

    /**
     * @deprecated Use {@link #mInsertURIString} instead.<br>
     *             See {@link WebOfTrust#upgradeDatabaseFormatVersion12345} for why this was
     *             replaced.
     *             <br>For newly constructed OwnIdentity objects, will always be null.<br>
     *             For OwnIdentity objects existing in old databases, will be null after
     *             {@link #upgradeDatabaseFormatVersion12345WithoutCommit()}.<br>
     *             <br>TODO: Remove this variable once the aforementioned database upgrade code is
     *             removed. When removing it, make sure to check the db4o manual for whether
     *             it is necessary to delete its backend database field manually using db4o API;
     *             and if necessary do that with another database format version upgrade. */
    @Deprecated
    protected FreenetURI mInsertURI = null;

    protected String mInsertURIString;

	protected Date mLastInsertDate;
	
	
	/**
	 * Creates a new OwnIdentity with the given parameters.
	 * 
	 * @param insertURI A {@link FreenetURI} used to insert this OwnIdentity in Freenet.  
	 *    **NOTICE:** The edition of it is NOT used to initialize the edition of this Identity!  
	 *    It will always be initialized to 0.  
	 *    You must manually take care of:
	 *    - using {@link #restoreEdition(long, Date)} if a pre-existing OwnIdentity is being
	 *      restored from the network and it can be guaranteed that the edition exists, e.g. if it
	 *      has been downloaded previously or provided by the user.
	 *    - notifying the {@link IdentityDownloaderController} about the restored edition via
	 *      {@link IdentityDownloaderController#storePostRestoreOwnIdentityCommand(OwnIdentity)}.
	 *    
	 *    The reason for initializing to 0 is security: It prevents remote peers from maliciously
	 *    causing an Identity to never be downloaded by publishing a very high, non-existent edition
	 *    in their trust list.  
	 *    Yes, OwnIdentitys technically cannot be created by receiving a remote Trust value, but
	 *    only by user action instead. But the user can use
	 *    {@link WebOfTrust#restoreOwnIdentity(FreenetURI)} which *will* typically run into
	 *    pre-existing non-own versions of the Identity in the database which we've obtained
	 *    from the network. And it *will* re-use their URI's edition as a hint, so it might
	 *    be wrongly passed to this constructor and therefore the constructor must be safe against
	 *    that mistake.
	 *    
	 *    TODO: Code quality: Throw {@link IllegalArgumentException} when edition is non-zero so
	 *    we're guarded against callers having wrong assumptions by code, not merely documentation.
	 * @param nickName Can be null if not known yet, i.e. when restoring an OwnIdentity from the
	 *     network.
	 * @throws MalformedURLException If insertURI isn't a valid insert URI or a request URI instead of an insert URI.
	 */
	public OwnIdentity (WebOfTrustInterface myWoT, FreenetURI insertURI, String nickName, boolean publishTrustList) throws InvalidParameterException, MalformedURLException {	
		super(myWoT,
				// If we don't set a document name, we will get "java.net.MalformedURLException: SSK URIs must have a document name (to avoid ambiguity)"
				// when calling  FreenetURI.deriveRequestURIFromInsertURI().
				// So to make sure that deriveRequestURIFromINsertURI() works, we just pass the URI through testAndNormalizeInsertURI() which
				// ought to be robust against all kinds of URIs which people shove into WOT.
				testAndNormalizeInsertURI(insertURI).deriveRequestURIFromInsertURI(),
				nickName, publishTrustList);
		// This is already done by super()
		// setEdition(0);
		
        // TODO: Code quality: Can this be moved to testAndNormalizeInsertURI without side effects?
        // Please be very careful to review all code paths which use the function, the URI code
        // is rather fragile because users can shove all kinds of bogus URIs into it.
		if(!insertURI.isUSK() && !insertURI.isSSK())
			throw new InvalidParameterException("Identity URI keytype not supported: " + insertURI);
		
        FreenetURI normalizedInsertURI = testAndNormalizeInsertURI(insertURI);

        // We need this.getEdition() but initializeTransient() was not called yet so it won't work.
        // So instead, we manually obtain the edition from the request URI.
        // TODO: Code quality: super() does call initializeTransient(), it ought to work? Check the
        // git history if it was added after the above comment - if it was added before then there
        // is maybe another issue involved, e.g. some things passing myWoT == null to this
        // constructor.
        final FreenetURI requestURI;
        try {
            requestURI = new FreenetURI(mRequestURIString);
        } catch(MalformedURLException e) {
            // Should not happen: Class Identity shouldn't store an invalid mRequestURIString
            throw new RuntimeException(e);
        }
        // Ensure the edition of the insert URI is the same as the one of the request URI which is
        // stored in the parent class.
        // Technically we could just set it to 0 because the parent constructor will always set the
        // request URI's edition to 0 (see their and our JavaDoc) - but to be more robust against
        // future changes it is better to blindly copy the edition without assumptions about its
        // value.
        normalizedInsertURI = normalizedInsertURI.setSuggestedEdition(requestURI.getEdition());
        mInsertURIString = normalizedInsertURI.toString();

		mLastInsertDate = new Date(0);

		// Must be set to "fetched" to prevent the identity fetcher from trying to fetch the current edition and to make the identity inserter
		// actually insert the identity. It won't insert it if the current edition is not marked as fetched to prevent inserts when restoring an
		// own identity.
		mCurrentEditionFetchState = FetchState.Fetched;
		// Don't keep it at the default "new Date(0)", that would indicate a FetchState of
		// NotFetched and thus wouldn't match the fact that we just set it to Fetched.
		mLastFetchedDate = (Date)mCreationDate.clone(); // Clone it because date is mutable
		
		// Don't check for mNickname == null to allow restoring of own identities
	}

	/** @see #OwnIdentity(WebOfTrustInterface, FreenetURI, String, boolean) */
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
				(CurrentTimeUTC.getInMillis() - getLastInsertDate().getTime()) > IdentityInserter.MAX_UNCHANGED_TIME_BEFORE_REINSERT); 
	}

	/**
	 * @return This OwnIdentity's insertURI
	 */
	public final FreenetURI getInsertURI() {
        checkedActivate(1); // String is a db4o primitive type so 1 is enough
        try {
            return new FreenetURI(mInsertURIString);
        } catch (MalformedURLException e) {
            // Should never happen: We never store invalid URIs.
            throw new RuntimeException(e);
        }
	}
	
	/**
	 * Checks whether the given URI is a valid identity insert URI and throws if is not.
	 * 
	 * TODO: L10n
	 * 
	 * @return A normalized WOT Identity USK version of the URI with edition set to 0
	 */
	public static final FreenetURI testAndNormalizeInsertURI(final FreenetURI uri) throws MalformedURLException {
		try {
			final FreenetURI normalized = uri.setKeyType("USK").setDocName(WebOfTrustInterface.WOT_NAME).setSuggestedEdition(0).setMetaString(null);
			
			// Make sure that it is an insert URI and not a request URI: If it isn't the following will throw.
			normalized.deriveRequestURIFromInsertURI();
			
			return normalized;
		} catch(RuntimeException e) {
			throw new MalformedURLException("Invalid identity insert URI: " + e + ", URI was: " + uri.toString());
		}
	}

	@Override protected final void onFetched(long edition, boolean parsingSucceeded, Date when) {
		// Will throw for us if the edition is invalid, so we don't check it here.
		super.onFetched(edition, parsingSucceeded, when);
		
		// String is a db4o primitive type so 1 is enough and we don't have to delete the old one.
		checkedActivate(1);
		mInsertURIString = getInsertURI().setSuggestedEdition(edition).toString();
	}

	/**
	 * ATTENTION: Only use this when you need to construct arbitrary Identity objects - for example when writing an FCP parser.
	 * It won't guarantee semantic integrity of the identity object, for example it allows lowering of the edition and doesn't correct the FetchState.
	 * Instead, when possible use one of:
	 * - {@link #onInserted(long)}
	 * - {@link #restoreEdition(long, Date)} 
	 * - {@link #onFetchedAndParsedSuccessfully(long)}
	 * - {@link #onFetchedAndParsingFailed(long)}
	 * - {@link #onFetched(long, boolean, Date)}. */
	@Override
	public void forceSetEdition(final long newEdition) {
		super.forceSetEdition(newEdition);

        final FreenetURI insertURI = getInsertURI();
        final long currentEdition = insertURI.getEdition();
		
		if(newEdition != currentEdition) {
            // Strings are a db4o primitive type, and thus automatically deleted.
            /* checkedDelete(mInsertURIString); */
            mInsertURIString = insertURI.setSuggestedEdition(newEdition).toString();
			updated();
		}
	}
	
	
	/**
	 * Only needed for normal identities.
	 */
	@Override
	protected final void markForRefetch() {
        // TODO: Code quality: This function should throw UnsupportedOperationException instead of
        // returning as it does not make sense to call this upon an OwnIdentity, it only makes sense
        // for the parent class Identity. However, I was too lazy for making sure that the function
        // does not get called during special conditions of score computation, so I merely added
        // error logging instead of making it throw. If log file analysis shows that the function is
        // in fact never called, replace the logging with a throw.
        Logger.error(this, "markForRefetch() should not be used upon OwnIdentity",
            new UnsupportedOperationException() /* Add exception for logging a stack trace */);
		return;
	}
	
	/**
	 * Sets the edition to the given edition and marks it for re-fetching. Used for restoring own identities.
	 * 
	 * ATTENTION: Does NOT call {@link #setCreationDate(Date)} and
	 * {@link #forceSetLastChangeDate(Date)}, you have to do that on your own!
	 *
	 * @param fetchedDate The date when the given edition was fetched. Null if it was not fetched yet.
	 */
	protected final void restoreEdition(long edition, Date fetchedDate) {
		checkedActivate(1);
		
		forceSetEdition(edition);
		
		mCurrentEditionFetchState = FetchState.NotFetched;
		
		// checkedDelete(mLastFetchedDate); /* Not stored because db4o considers it as a primitive */
		mLastFetchedDate = fetchedDate != null ? (Date)fetchedDate.clone() : new Date(0);	// Clone it because date is mutable
		
		// Make sure we do not re-insert the empty version of this OwnIdentity before the
		// restoring procedure has downloaded anything.
		// Technically this is not really necessary because needsInsert() returns false if
		// mCurrentEditionFetchState == NotFetched, which we did just set and will stay like that
		// until we downloaded something.
		// However, we still do it anyway because the user might have wrongly told us a very old
		// edition number: If we fetch a too old edition as first step of restoring, then the
		// mCurrentEditionFetchState would be changed to Fetched, which means that the edition would
		// become eligible for inserting.
		// The IdentityInserter would then upload it to the highest USK slot, causing restoring to
		// result in the OwnIdentity being overwritten with very old data completely.
		// To prevent this, we set mLastInsertDate to current time, which prevents the
		// IdentityInserter from uploading a new edition for a while.
		// This hopefully gives the restoring code time to acquire a second, newer edition.
		// TODO: Code quality: Enforce this more strictly. This will become possible once we not
		// only keep track of a mLastInsertDate, but also of a "mNextInsertDate". Then set the next
		// insert date to CurrentTimeUTC.get() + some TimeUnit.DAYS. Though it probably wouldn't be
		// sufficient to merely have mNextInsertDate as that may then be decreased by the user
		// doing a modification in the UI. The proper approach would probably be to separately
		// track a mMinNextInsertDate and mMaxNextInsertDate. mMaxNextInsertDate would be
		// decreased by unimportant stuff such as changes of getProperties() or insignificant Trust
		// changes (+3 -> +4). mMinNextInsertDate would be what this function sets and it could
		// only be decreased by important changes such as new negative Trust values.
		// Also see https://bugs.freenetproject.org/view.php?id=5374
		mLastInsertDate = CurrentTimeUTC.get();
	}

	/**
	 * Get the Date of last insertion of this OwnIdentity, in UTC, new Date(0) if it was not
	 * inserted yet. */
	public final Date getLastInsertDate() {
		checkedActivate(1); // Date is a db4o primitive type so 1 is enough
		return (Date)mLastInsertDate.clone();	// Clone it because date is mutable
	}

	/**
	 * Returns the next edition which the {@link IdentityInserter} should upload.
	 * 
	 * @throws IllegalStateException
	 *     If no edition should be inserted according to {@link #needsInsert()} or
	 *     {@link #isRestoreInProgress()}, or if the maximum edition has been reached. */
	public final long getNextEditionToInsert() {
		if(!needsInsert()) {
			throw new IllegalStateException(
				"The next edition to insert cannot be computed if needsInsert() is false!");
		}
		
		// While restoring isn't finished we don't know what the last edition was so it is crucial
		// we don't insert before. This is implicitly checked by needsInsert() above, but let's keep
		// an assert here as insurance against someone wrongly removing the needsInsert() check for
		// reasons such as wanting the actual return value in unit tests.
		// TODO: Code quality: Move this to a unit test.
		assert(!isRestoreInProgress());
		
		long edition = getRawEdition();
		
		if(getLastInsertDate().after(new Date(0))) {
			++edition;
			
			if(edition < 0)
				throw new IllegalStateException("Maximum edition was inserted already!");
		}
		
		return edition;
	}

	/**
	 * Updates the value of {@link #getLastInsertDate()} and calls
	 * {@link #onFetchedAndParsedSuccessfully(long)} */
	protected final void onInserted(long edition) {
		checkedActivate(1); // Date is a db4o primitive type so 1 is enough
		// checkedDelete(mLastInsertDate); /* Not stored because db4o considers it as a primitive */
		mLastInsertDate = CurrentTimeUTC.get();
		
		// We update the edition, URI, mLastFetchedDate, etc. by just pretending a fetch happened
		// by calling onFetched().
		try {
			if(edition == 0) {
				// 0 being the first edition to insert is a special case:
				// The edition will have to be marked as fetched already at creation of this
				// OwnIdentity (to distinguish from a restore being in progress).
				// But we want to call onFetched(), and it would throw if the edition was marked as
				// fetched, so we mark it as not fetched before calling onFetched().
				assert(getLastFetchedEdition() == 0);
				assert(mCurrentEditionFetchState == FetchState.Fetched);
				mCurrentEditionFetchState = FetchState.NotFetched;
			}
				
			// To prevent needsInsert() from wrongly reporting that an insert is due, we must ensure
			// the mLastChangedDate matches mLastInsertDate by the millisecond.
			// - thus instead of the regular onFetchedAndParsedSuccessfully() we use the special
			// onFetched*() version which consumes a Date.
			// This also ensures that the mLastFetchedDate will be the same as well, which makes
			// sense because the edition being marked as fetched is a consequence of the insert,
			// so it should have the same Date as well.
			onFetched(edition, true, mLastInsertDate);
		} catch(IllegalStateException e) {
			Logger.error(this,
			    "onFetched() failed in onInserted(), likely because we got "
			  + "an edition which was fetched already. This might be caused by the race condition "
			  + "of the IdentityDownloader receiving the 'USK was fetched' callback before "
			  + "the OwnIdentity received the onInserted() callback. this: " + this, e);
			
			// For unit tests
			assert(false) : e;
			
			// We not throw it out because otherwise the next IdentityInserter.insert() would fail
			// to increments the edition number and try to insert to an already existing URI:
			// It only increments the edition if getLastInsertDate().after(new Date(0)) - which can
			// only be the case if we ALWAYS update the last insert date even if this failure
			// happens. But our update to it would have no effect if we threw this out because it
			// would cause the whole transaction to be rolled back by the caller.
			// FIXME: Perhaps prevent this race condition by having the new IdentityDownloader
			// implementations *not* download an OwnIdentity if no restore is in progress.
			// This may be difficult though: There's also the on-disk IdentityFile queue which might
			// still contain data from the previous run.
		}
	}

	/**
	 * Checks whether two OwnIdentity objects are equal.
	 * This checks <b>all</b> properties of the identities <b>excluding</b> the {@link Date} properties.
     * <br><br>
     * 
     * Notice: {@link #toString()} returns a String which contains the same data as this function
     * compares. This can ease debugging.
	 */
	@Override
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
	
	/** @return A String containing everything which {@link #equals(Object)} would compare. */
	@Override
	public final String toString() {
        activateFully(); 
        return "[OwnIdentity: " + super.toString()
             + "; mInsertURIString: " + mInsertURIString
             + "; mInsertURI: " + mInsertURI
             + "]";
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
	@Override
	public final OwnIdentity clone() {
		try {
			// FIXME: Performance: Add constructor which consumes an OwnIdentity so the constructor
			// doesn't have to validate the passed data anymore. IIRC that slows down clone() a lot
			// according to my profiling.
			// IIRC the reason is the constructor's usage of deriveRequestURIFromInsertURI(), though
			// you might have to profile clone() again, I don't remember the details precisely.
			// I think the context where I spotted the slowness was profiling of bootstrapping using
			// the IdentityDownloaderFast/Slow.
			// Also check if classes Identity, Trust and Score could be improved in the same
			// fashion.
			OwnIdentity clone = new OwnIdentity(mWebOfTrust, getInsertURI(), getNickname(), doesPublishTrustList());
			
			activateFully(); // For performance only
			
			clone.forceSetEdition(getRawEdition());
			clone.setNewEditionHint(getLatestEditionHint());
			clone.setCreationDate(getCreationDate());
			clone.mCurrentEditionFetchState = getCurrentEditionFetchState();
			clone.mLastInsertDate = (Date)mLastInsertDate.clone();	// Clone it because date is mutable
			clone.mLastFetchedDate = (Date)mLastFetchedDate.clone();
			clone.mLatestEditionHint = getLatestEditionHint(); // Don't use the setter since it won't lower the current edition hint.
			clone.setContexts(getContexts());
			clone.setProperties(getProperties());
            // Clone it because date is mutable. Set it *after* calling all setters since they would
            // update it to the current time otherwise.
            clone.mLastChangedDate = (Date)mLastChangedDate.clone();
            
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
	@Override
	protected final void storeWithoutCommit() {
		try {
			activateFully();
			
            assert(mInsertURI == null)
                : "upgradeDatabaseFormatVersion5WithoutCommit() should delete mInsertURI";

            /* String is a db4o primitive type, and thus automatically stored. */
            // checkedStore(mInsertURIString);

			// checkedStore(mLastInsertDate); /* Not stored because db4o considers it as a primitive and automatically stores it. */
		}
		catch(RuntimeException e) {
			checkedRollbackAndThrow(e);
		}
		
		super.storeWithoutCommit(); // Not in the try{} so we don't do checkedRollbackAndThrow twice
	}

    /** @see WebOfTrust#upgradeDatabaseFormatVersion5 */
    @Override protected void upgradeDatabaseFormatVersion12345WithoutCommit() {
        checkedActivate(1);
        
        if(mInsertURIString != null) {
            // This object has had its mInsertURI migrated to mInsertURIString already.
            // Might happen during very old database format version upgrade codepaths which
            // create fresh OwnIdentity objects - newly constructed objects will not need migration.
            assert(mInsertURI == null);
            return;
        }
        
        assert(mInsertURI != null);
        checkedActivate(mInsertURI, 2);
        mInsertURIString = mInsertURI.toString();

        // A FreenetURI currently only contains db4o primitive types (String, arrays, etc.) and thus
        // we can delete it having to delete its member variables explicitly.
        mDB.delete(mInsertURI);
        mInsertURI = null;
        
        // Do this after we've migrated mInsertURI because it will storeWithoutCommit() and
        // storeWithoutCommit() contains an assert(mInsertURI == null)
        super.upgradeDatabaseFormatVersion12345WithoutCommit();
        
        // Done by the previous call, not needed.
        /* storeWithoutCommit(); */
    }

	@Override
	protected final void deleteWithoutCommit() {
		try {
			activateFully();

            assert(mInsertURI == null)
                : "upgradeDatabaseFormatVersion5WithoutCommit() should delete mInsertURI";
            // checkedDelete(mInsertURI);

            /* String is a db4o primitive type, and thus automatically deleted. */
            // checkedDelete(mInsertURIString);

			// checkedDelete(mLastInsertDate); /* Not stored because db4o considers it as a primitive and automatically stores it. */
		}
		catch(RuntimeException e) {
			checkedRollbackAndThrow(e);
		}
		
		super.deleteWithoutCommit(); // Not in the try{} so we don't do checkedRollbackAndThrow twice
	}
	
	@Override
	public void startupDatabaseIntegrityTest() {
		activateFully();
		super.startupDatabaseIntegrityTest();
		
        if(mInsertURI != null) {
            throw new IllegalStateException(
                "upgradeDatabaseFormatVersion5WithoutCommit() should delete mInsertURI");
        }

        if(mInsertURIString == null)
            throw new NullPointerException("mInsertURIString==null");

        final FreenetURI insertURI = getInsertURI();
		
		try {
            final FreenetURI normalizedInsertURI
                = testAndNormalizeInsertURI(insertURI).setSuggestedEdition(insertURI.getEdition());
            if(!normalizedInsertURI.equals(insertURI))
                throw new IllegalStateException("Insert URI is not normalized: " + insertURI);
		} catch (MalformedURLException e) {
            throw new IllegalStateException("Insert URI is invalid: " + e);
		}
		
		try {
            if(!insertURI.deriveRequestURIFromInsertURI().equals(getRequestURI()))
				throw new IllegalStateException("Insert and request URI do not fit together!");
		} catch (MalformedURLException e) {
            throw new IllegalStateException("Insert URI is not an insert URI!");
		}
		
		if(mLastInsertDate == null)
			throw new NullPointerException("mLastInsertDate==null");
		
		// The special value 0 is allowed to signal that the OwnIdentity wasn't inserted yet.
		if(mLastInsertDate.before(new Date(0))) {
			throw new IllegalStateException("mLastInsertDate is before new Date(0): "
				+ mLastInsertDate);
		} else if(mLastInsertDate.after(CurrentTimeUTC.get()))
			throw new IllegalStateException("mLastInsertDate is in the future: " + mLastInsertDate);
	}
	
	/** @see Persistent#serialize() */
	private void writeObject(ObjectOutputStream stream) throws IOException {
		activateFully();
		stream.defaultWriteObject();
	}

}
