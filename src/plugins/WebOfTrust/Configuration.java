/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import java.util.Date;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

import freenet.support.CurrentTimeUTC;
import freenet.support.codeshortification.IfNull;

/* ATTENTION: This code is a duplicate of plugins.Freetalk.Config. Any changes there should also be done here! */

/**
 * Contains a HashMap<String, String> and HashMap<String, Integer> which maps configuration variable names to their values and stores them
 * in the database. Integer configuration values are stored separately because they might be needed very often per second and we should
 * save the time of converting String to Integer.
 * 
 * TODO: Code quality: The function names often lack the "WithoutCommit" suffix.
 * 
 * @author xor (xor@freenetproject.org)
 * @author Julien Cornuwel (batosai@freenetproject.org)
 */
@SuppressWarnings("serial")
public final class Configuration extends Persistent {

	/**
	 * At startup, we defragment the db4o database after this interval has expired.
	 * TODO: Code quality: Make configurable.
	 * 
	 * ATTENTION: If it ever becomes possible to set this to "infinite", for example in a
	 * user-accessible configuration menu, please ensure that
	 * {@link #scheduleDefragmentationWithoutCommit()} is updated to work with that. */
	public final static transient long DEFAULT_DEFRAG_INTERVAL = TimeUnit.DAYS.toMillis(7);

	/**
	 * At startup, we call {@link WebOfTrust#verifyAndCorrectStoredScores()} after this interval has
	 * expired.
	 * TODO: Code quality: Make configurable. */
	public final static transient long DEFAULT_VERIFY_SCORES_INTERVAL = TimeUnit.DAYS.toMillis(28);

	/**
	 * The database format version of this WoT-database.
	 * Stored in a primitive integer field to ensure that db4o does not lose it - I've observed the HashMaps to be null suddenly sometimes :(
	 */
	private int mDatabaseFormatVersion;

	/**
	 * The last time we defragmented the db4o database.
	 * It's an expensive operation, and thus we only run it once per
	 * {@link #DEFAULT_DEFRAG_INTERVAL}.
	 */
	private Date mLastDefragDate;

	/**
	 * The last time we ran {@link WebOfTrust#verifyAndCorrectStoredScores()}.
	 * It's an expensive operation, and thus we only run it once per
	 * {@link #DEFAULT_VERIFY_SCORES_INTERVAL}.
	 */
	private Date mLastVerificationOfScoresDate;

	/**
	 * The {@link HashMap} that contains all {@link String} configuration parameters
	 */
	private final HashMap<String, String> mStringParams;
	
	/**
	 * @see Configuration#activateStringParams
	 */
	private transient boolean mStringParamsActivated = false;
	
	/**
	 * The {@link HashMap} that contains all {@link Integer} configuration parameters
	 */
	private final HashMap<String, Integer> mIntParams;
	
	/**
	 * @see Configuration#activateIntParams
	 */
	private transient boolean mIntParamsActivated = false;


	/**
	 * Creates a new Config object and stores the default values in it.
	 */
	protected Configuration(WebOfTrust myWebOfTrust) {
		mDatabaseFormatVersion = WebOfTrust.DATABASE_FORMAT_VERSION;
		
		// This constructor can be assumed to be called upon first time use of WOT.
		// Thus, default to current time so defrag and score verification are not run for their
		// minimal delay: This ensures that the first time user experience is not impacted by
		// huge startup delays.
		mLastDefragDate = CurrentTimeUTC.get();
		mLastVerificationOfScoresDate = CurrentTimeUTC.get();
		
		mStringParams = new HashMap<String, String>();
		mIntParams = new HashMap<String, Integer>();
		initializeTransient(myWebOfTrust);
		setDefaultValues(false);
	}
	
	/**
	 * {@inheritDoc}
	 */
	@Override
	protected void activateFully() {
		// 4 is the maximal depth of all getter functions. You have to adjust this when introducing new member variables.
		checkedActivate(4);
		// Workaround for db4o bug
		activateStringParams();
		activateIntParams();
	}
	
	/**
	 * Workaround function for db4o HashMap activation bug
	 * 
	 * TODO: As soon as the db4o bug is fixed, remove this workaround function & replace with:
	 * checkedActivate(1);
	 * checkedActivate(mStringParams, 3);
	 */
	private synchronized final void activateStringParams() {
		// We must not deactivate the HashMaps if they were already modified by a setter so we need this guard
		if(mStringParamsActivated)
			return;

		checkedActivate(1);
		
		if(mDB.isStored(mStringParams)) {
			mDB.deactivate(mStringParams);
			checkedActivate(mStringParams, 3);
		}
		
		mStringParamsActivated = true;
	}

	/**
	 * Workaround function for db4o HashMap activation bug
	 * 
	 * TODO: As soon as the db4o bug is fixed, remove this workaround function & replace with:
	 * checkedActivate(1);
	 * checkedActivate(mIntParams, 3);
	 */
	private synchronized final void activateIntParams() {
		// We must not deactivate the HashMaps if they were already modified by a setter so we need this guard
		if(mIntParamsActivated)
			return;

		checkedActivate(1);
		
		if(mDB.isStored(mIntParams)) {
			mDB.deactivate(mIntParams);
			checkedActivate(mIntParams, 3);
		}
		
		mIntParamsActivated = true;
	}
	
	
	/**
	 * @deprecated Not implemented because we don't need it.
	 */
	@Override
	@Deprecated()
	public String getID() {
		throw new UnsupportedOperationException();
	}

	/**
	 * Stores the config object in the database. Please call this after any modifications to the config, it is not done automatically
	 * because the user interface will usually change many values at once.
	 */
	public synchronized void storeAndCommit() {
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

	@Override protected void storeWithoutCommit() {
		activateFully();
		
		checkedStore(mStringParams);
		checkedStore(mIntParams);
		
		checkedStore(this);
	}

	/**
	 * ATTENTION: A WOT database should ALWAYS contain a Configuration object. This function is only for debugging purposes
	 * - namely {@link WebOfTrust#checkForDatabaseLeaks()}
	 */
	@Override
	protected void deleteWithoutCommit() {
		try {
			activateFully();
			checkedDelete(mStringParams);
			checkedDelete(mIntParams);
			checkedDelete();
		}
		catch(RuntimeException e) {
			checkedRollbackAndThrow(e);
		}
	}
	
	public int getDatabaseFormatVersion() {
		checkedActivate(1); // int is a db4o primitive type so 1 is enough
		return mDatabaseFormatVersion;
	}
	
	protected void setDatabaseFormatVersion(int newVersion) {
		checkedActivate(1); // int is a db4o primitive type so 1 is enough
		if(newVersion <= mDatabaseFormatVersion)
			throw new RuntimeException("mDatabaseFormatVersion==" + mDatabaseFormatVersion + "; newVersion==" + newVersion);
		
		mDatabaseFormatVersion = newVersion;
	}

	public Date getLastDefragDate() {
		checkedActivate(1); // int is a db4o primitive type so 1 is enough
		return (Date)mLastDefragDate.clone(); // Clone it because date is mutable
	}
	
	/**
	 * Schedules a defragmentation of the database at the next restart of WoT.
	 * Not only for maintenance purposes but also for security:
	 * After {@link WebOfTrust#deleteOwnIdentity(String)}, it is a good idea to close holes in the
	 * database structures to erase leftover data of the deleted {@link OwnIdentity}. */
	void scheduleDefragmentationWithoutCommit() {
		// Date is a db4o primitive type so activation depth of 1 is enough. We also don't need
		// to delete because of that, db4o will do it automatically.
		checkedActivate(1);
		// checkedDelete(mLastDefragDate);
		
		mLastDefragDate = new Date(0);
	}

	public void updateLastDefragDate() {
		// Date is a db4o primitive type so activation depth of 1 is enough. We also don't need
		// to delete because of that, db4o will do it automatically.
		checkedActivate(1);
		// checkedDelete(mLastDefragDate);
		
		mLastDefragDate = CurrentTimeUTC.get();
	}

	public Date getLastVerificationOfScoresDate() {
		checkedActivate(1); // int is a db4o primitive type so 1 is enough
		return (Date)mLastVerificationOfScoresDate.clone(); // Clone it because date is mutable
	}

	public void updateLastVerificationOfScoresDate() {
		// Date is a db4o primitive type so activation depth of 1 is enough. We also don't need
		// to delete because of that, db4o will do it automatically.
		checkedActivate(1);
		// checkedDelete(mLastVerificationOfScores);
		
		mLastVerificationOfScoresDate = CurrentTimeUTC.get();
	}

	/**
	 * Sets a String configuration parameter. You have to call storeAndCommit to write it to disk.
	 * 
	 * @param key Name of the config parameter.
	 * @param value Value of the config parameter.
	 */
	public synchronized void set(String key, String value) {
		IfNull.thenThrow(key, "Key");
		IfNull.thenThrow(value, "Value");
		activateStringParams();
		mStringParams.put(key, value);
	}
	
    /**
     * Sets a boolean configuration parameter. You have to call storeAndCommit to write it to disk.
     * 
     * @param key Name of the config parameter.
     * @param value Value of the config parameter.
     */
    public synchronized void set(String key, boolean value) {
		IfNull.thenThrow(key, "Key");
		activateStringParams();
        mStringParams.put(key, Boolean.toString(value));
    }
	
	/**
	 * Sets an Integer configuration parameter and stores it in the database. You have to call storeAndCommit to write it to disk.
	 * 
	 * @param key Name of the config parameter.
	 * @param value Value of the config parameter.
	 */
	public synchronized void set(String key, int value) {
		IfNull.thenThrow(key, "Key");
		activateIntParams();
		mIntParams.put(key, value);
	}

	/**
	 * Gets a String configuration parameter.
	 */
	public synchronized String getString(String key) {
		activateStringParams();
		return mStringParams.get(key);
	}
	
	/**
	 * Gets an Integer configuration parameter.
	 */
	public synchronized int getInt(String key) {
		activateIntParams();
		return mIntParams.get(key);
	}

    /**
     * Gets a boolean configuration parameter.
     */
    public synchronized boolean getBoolean(String key) {
    	activateStringParams();
        return Boolean.valueOf( mStringParams.get(key) );
    }

	/**
	 * Check wheter a String config parameter exists.
	 */
	public synchronized boolean containsString(String key) {
		activateStringParams();
		return mStringParams.containsKey(key);
	}
	
	/**
	 * Check wheter an Integer config parameter exists.
	 */
	public synchronized boolean containsInt(String key) {
		activateIntParams();
		return mIntParams.containsKey(key);
	}

	/**
	 * Get all valid String configuration keys.
	 * 
	 * @return A String array containing a copy of all keys in the database at
	 *         the point of calling the function. Changes to the array do not
	 *         change the database.
	 */
	public synchronized String[] getAllStringKeys() {
		activateStringParams();
		/* We return a copy of the keySet. If we returned an iterator of the
		 * keySet, modifications on the configuration HashMap would be reflected
		 * in the iterator. This might lead to problems if the configuration is
		 * modified while someone is using an iterator returned by this
		 * function. Further the iterator would allow the user to delete keys
		 * from the configuration.
		 */

		// TODO: there is a null pointer somewhere in here. i don't have the
		// time for fixing it right now
		return mStringParams.keySet().toArray(new String[mStringParams.size()]);
	}
	
	/**
	 * Get all valid String configuration keys.
	 * 
	 * @return A String array containing a copy of all keys in the database at
	 *         the point of calling the function. Changes to the array do not
	 *         change the database.
	 */
	public synchronized String[] getAllIntKeys() {
		activateIntParams();
		/* We return a copy of the keySet. If we returned an iterator of the
		 * keySet, modifications on the configuration HashMap would be reflected
		 * in the iterator. This might lead to problems if the configuration is
		 * modified while someone is using an iterator returned by this
		 * function. Further the iterator would allow the user to delete keys
		 * from the configuration.
		 */

		// TODO: there is a null pointer somewhere in here. i don't have the
		// time for fixing it right now
		return mIntParams.keySet().toArray(new String[mIntParams.size()]);
	}

	/**
	 * Add the default configuration values to the database.
	 * 
	 * @param overwrite If true, overwrite already set values with the default value.
	 */
	public synchronized void setDefaultValues(boolean overwrite) {

	}

	@Override
	public void startupDatabaseIntegrityTest() {
		activateFully();
		
		if(mDatabaseFormatVersion != WebOfTrust.DATABASE_FORMAT_VERSION)
			throw new IllegalStateException("FATAL: startupDatabaseIntegrityTest called with wrong database format version! is: " 
					+ mDatabaseFormatVersion + "; should be: " + WebOfTrust.DATABASE_FORMAT_VERSION);
		
		
		IfNull.thenThrow(mLastDefragDate);
		IfNull.thenThrow(mLastVerificationOfScoresDate);
		
		Date now = CurrentTimeUTC.get();
		
		if(mLastDefragDate.after(now))
			throw new IllegalStateException("mLastDefragDate is in the future: " + mLastDefragDate);
		
		if(mLastVerificationOfScoresDate.after(now)) {
			throw new IllegalStateException("mLastVerificationOfScoresDate is in the future: "
			                               + mLastVerificationOfScoresDate);
		}
		

		if(mIntParams == null)
			throw new NullPointerException("mIntParams==null");
		
		if(mStringParams == null)
			throw new NullPointerException("mStringParams==null");
		
		// TODO: Validate the content
	}

}
