/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import java.util.HashMap;

import freenet.support.codeshortification.IfNull;

/* ATTENTION: This code is a duplicate of plugins.Freetalk.Config. Any changes there should also be done here! */

/**
 * Contains a HashMap<String, String> and HashMap<String, Integer> which maps configuration variable names to their values and stores them
 * in the database. Integer configuration values are stored separately because they might be needed very often per second and we should
 * save the time of converting String to Integer.
 * 
 * @author xor (xor@freenetproject.org)
 * @author Julien Cornuwel (batosai@freenetproject.org)
 */
@SuppressWarnings("serial")
public final class Configuration extends Persistent {

	/**
	 * The database format version of this WoT-database.
	 * Stored in a primitive integer field to ensure that db4o does not lose it - I've observed the HashMaps to be null suddenly sometimes :(
	 */
	private int mDatabaseFormatVersion;
	
	/**
	 * The {@link HashMap} that contains all {@link String} configuration parameters
	 */
	private final HashMap<String, String> mStringParams;
	
	/**
	 * The {@link HashMap} that contains all {@link Integer} configuration parameters
	 */
	private final HashMap<String, Integer> mIntParams;
	

	/**
	 * Creates a new Config object and stores the default values in it.
	 */
	protected Configuration(WebOfTrust myWebOfTrust) {
		mDatabaseFormatVersion = WebOfTrust.DATABASE_FORMAT_VERSION;
		mStringParams = new HashMap<String, String>();
		mIntParams = new HashMap<String, Integer>();
		initializeTransient(myWebOfTrust);
		setDefaultValues(false);
	}
	
	/**
	 * @deprecated Not implemented because we don't need it.
	 */
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
				checkedActivate(4);
				
				checkedStore(mStringParams);
				checkedStore(mIntParams);
				
				checkedStore(this);
				checkedCommit(this);
			}
			catch(RuntimeException e) {
				checkedRollbackAndThrow(e);
			}
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

	/**
	 * Sets a String configuration parameter. You have to call storeAndCommit to write it to disk.
	 * 
	 * @param key Name of the config parameter.
	 * @param value Value of the config parameter.
	 */
	public synchronized void set(String key, String value) {
		IfNull.thenThrow(key, "Key");
		IfNull.thenThrow(value, "Value");
		checkedActivate(4);
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
    	checkedActivate(4);
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
		checkedActivate(4);
		mIntParams.put(key, value);
	}

	/**
	 * Gets a String configuration parameter.
	 */
	public synchronized String getString(String key) {
		checkedActivate(4);
		return mStringParams.get(key);
	}
	
	/**
	 * Gets an Integer configuration parameter.
	 */
	public synchronized int getInt(String key) {
		checkedActivate(4);
		return mIntParams.get(key);
	}

    /**
     * Gets a boolean configuration parameter.
     */
    public synchronized boolean getBoolean(String key) {
    	checkedActivate(4);
        return Boolean.valueOf( mStringParams.get(key) );
    }

	/**
	 * Check wheter a String config parameter exists.
	 */
	public synchronized boolean containsString(String key) {
		checkedActivate(4);
		return mStringParams.containsKey(key);
	}
	
	/**
	 * Check wheter an Integer config parameter exists.
	 */
	public synchronized boolean containsInt(String key) {
		checkedActivate(4);
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
		checkedActivate(4);
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
		checkedActivate(4);
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
		checkedActivate(4);
		
		if(mDatabaseFormatVersion != WebOfTrust.DATABASE_FORMAT_VERSION)
			throw new IllegalStateException("FATAL: startupDatabaseIntegrityTest called with wrong database format version! is: " 
					+ mDatabaseFormatVersion + "; should be: " + WebOfTrust.DATABASE_FORMAT_VERSION);
		
		if(mIntParams == null)
			throw new NullPointerException("mIntParams==null");
		
		if(mStringParams == null)
			throw new NullPointerException("mStringParams==null");
		
		// TODO: Validate the content
	}

}
