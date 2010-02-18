/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WoT;

import java.util.HashMap;

import com.db4o.ObjectSet;
import com.db4o.ext.ExtObjectContainer;

import freenet.support.Logger;

/* ATTENTION: This code is a duplicate of plugins.Freetalk.Config. Any changes there should also be done here! */

/**
 * Contains a HashMap<String, String> and HashMap<String, Integer> which maps configuration variable names to their values and stores them
 * in the database. Integer configuration values are stored separately because they might be needed very often per second and we should
 * save the time of converting String to Integer.
 * 
 * @author xor (xor@freenetproject.org), Julien Cornuwel (batosai@freenetproject.org)
 */
public final class Config {

	/* Names of the config parameters */
	
	public static final String DATABASE_FORMAT_VERSION = "DatabaseFormatVersion";
	

	/**
	 * The HashMap that contains all cofiguration parameters
	 */
	private final HashMap<String, String> mStringParams;
	
	private final HashMap<String, Integer> mIntParams;
	
	private transient WoT mWoT;
	
	private transient ExtObjectContainer mDB;

	/**
	 * Creates a new Config object and stores the default values in it.
	 */
	protected Config(WoT myWoT) {
		mWoT = myWoT;
		mDB = mWoT.getDB();
		mStringParams = new HashMap<String, String>();
		mIntParams = new HashMap<String, Integer>();
		setDefaultValues(false);
	}
	
	protected void initializeTransient(WoT myWoT) {
		mWoT = myWoT;
		mDB = myWoT.getDB();
	}
	
	/**
	 * Loads an existing Config object from the database and adds any missing default values to it, creates and stores a new one if none exists.
	 * @return The config object.
	 */
	public static Config loadOrCreate(WoT myWoT) {
		ExtObjectContainer db = myWoT.getDB();
		synchronized(db.lock()) {
			Config config;
			ObjectSet<Config> result = db.queryByExample(Config.class);
			
			if(result.size() == 0) {
				Logger.debug(myWoT, "Creating new Config...");
				config = new Config(myWoT);
				config.storeAndCommit();
			}
			else {
				if(result.size() > 1) /* Do not throw, we do not want to prevent WoT from starting up. */
					Logger.error(myWoT, "Multiple config objects stored!");
				
				Logger.debug(myWoT, "Loaded config.");
				config = result.next();
				config.initializeTransient(myWoT);
				config.setDefaultValues(false);
			}
			
			return config;
		}
	}
	
	/**
	 * Stores the config object in the database. Please call this after any modifications to the config, it is not done automatically
	 * because the user interface will usually change many values at once.
	 */
	public synchronized void storeAndCommit() {
		synchronized(mDB.lock()) {
			try {
				mDB.store(mStringParams, 3);
				mDB.store(mIntParams, 3);
				mDB.store(this);
				mDB.commit();
			}
			catch(RuntimeException e) {
				mDB.rollback(); mDB.purge(); Logger.error(this, "ROLLED BACK!", e);
				throw e;
			}
		}
	}

	/**
	 * Sets a String configuration parameter. You have to call storeAndCommit to write it to disk.
	 * 
	 * @param key Name of the config parameter.
	 * @param value Value of the config parameter.
	 */
	public synchronized void set(String key, String value) {
		mStringParams.put(key, value);
	}
	
    /**
     * Sets a boolean configuration parameter. You have to call storeAndCommit to write it to disk.
     * 
     * @param key Name of the config parameter.
     * @param value Value of the config parameter.
     */
    public synchronized void set(String key, boolean value) {
        mStringParams.put(key, Boolean.toString(value));
    }
	
	/**
	 * Sets an Integer configuration parameter and stores it in the database. You have to call storeAndCommit to write it to disk.
	 * 
	 * @param key Name of the config parameter.
	 * @param value Value of the config parameter.
	 */
	public synchronized void set(String key, int value) {
		mIntParams.put(key, value);
	}

	/**
	 * Gets a String configuration parameter.
	 */
	public synchronized String getString(String key) {
		return mStringParams.get(key);
	}
	
	/**
	 * Gets an Integer configuration parameter.
	 */
	public synchronized int getInt(String key) {
		return mIntParams.get(key);
	}

    /**
     * Gets a boolean configuration parameter.
     */
    public synchronized boolean getBoolean(String key) {
        return Boolean.valueOf( mStringParams.get(key) );
    }

	/**
	 * Check wheter a String config parameter exists.
	 */
	public synchronized boolean containsString(String key) {
		return mStringParams.containsKey(key);
	}
	
	/**
	 * Check wheter an Integer config parameter exists.
	 */
	public synchronized boolean containsInt(String key) {
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
		/* We return a copy of the keySet. If we returned an iterator of the
		 * keySet, modifications on the configuration HashMap would be reflected
		 * in the iterator. This might lead to problems if the configuration is
		 * modified while someone is using an iterator returned by this
		 * function. Further the iterator would allow the user to delete keys
		 * from the configuration.
		 */

		// FIXME: there is a null pointer somewhere in here. i don't have the
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
		/* We return a copy of the keySet. If we returned an iterator of the
		 * keySet, modifications on the configuration HashMap would be reflected
		 * in the iterator. This might lead to problems if the configuration is
		 * modified while someone is using an iterator returned by this
		 * function. Further the iterator would allow the user to delete keys
		 * from the configuration.
		 */

		// FIXME: there is a null pointer somewhere in here. i don't have the
		// time for fixing it right now
		return mIntParams.keySet().toArray(new String[mIntParams.size()]);
	}

	/**
	 * Add the default configuration values to the database.
	 * 
	 * @param overwrite If true, overwrite already set values with the default value.
	 */
	public synchronized void setDefaultValues(boolean overwrite) {
		/* Do not overwrite, it shall only be overwritten when the database has been converted to a new format */
		if(!containsInt(DATABASE_FORMAT_VERSION))
			set(DATABASE_FORMAT_VERSION, WoT.DATABASE_FORMAT_VERSION);
	}
}
