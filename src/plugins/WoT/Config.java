/**
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */
package plugins.WoT;

import java.util.HashMap;
import java.util.Set;

/**
 * Contains a HashMap<String, String> which maps configuration variable names
 * to their values and stores them in the database.
 * 
 * @author Julien Cornuwel (batosai@freenetproject.org)
 */
public class Config {

        /**
         * The HashMap that contains all cofiguration parameters
         */
	private HashMap<String, String> params = null;
	
	/**
         * Creates a new Config object.
	 * 
	 * @param db The database where the configuration is stored.
	 */
	public Config() {
		
		if(params == null) {
			params = new HashMap<String, String>();
			initDefault(false);
		}
	}
	
	/**
	 * Sets a configuration parameter and stores it in the database.
	 *  
	 * @param key Name of the config parameter.
	 * @param value Value of the config parameter.
	 */
	public synchronized void set(String key, String value) {
		params.put(key, value);
	}
	
	/**
	 * Gets a configuration parameter.
	 * 
	 * @param key
	 * @return value of the configuration parameter
	 */
	public synchronized String get(String key) {
		return params.get(key);
	}
	
	/**
         * Check wheter a config parameter exists.
         * 
	 * @param key name of the configuration parameter
	 * @return whether it exists or not
	 */
	public synchronized boolean contains(String key) {
		return params.containsKey(key);
	}
	
	/**
	 * Get all valid configuration keys.
	 * @return A String array containing a copy of all keys in the database at the point of calling the function. Changes to the array do not change the database.
	 */
	public synchronized String[] getAllKeys() {
		/* We return a *copy* of the keySet. If we returned an iterator of the
		 * keySet, modifications on the configuration HashMap would be reflected
		 * in the iterator. This might lead to problems if the configuration is
		 * modified while someone is using an iterator returned by this function.
		 * Further the iterator would allow the user to delete keys from the
		 * configuration.
		 */
		
		// FIXME: there is a null pointer somewhere in here. i don't have the time for fixing it right now
		
		Set<String> keySet = params.keySet();
		String[]	keys = new String[keySet.size()];
			
		return keySet.toArray(keys);
	}
	
	/**
	 * Add the default configuration values to the database.
	 * @param overwrite If true, overwrite already set values with the default value.
	 */
	public synchronized void initDefault(boolean overwrite) {
		if (!contains("delayBetweenInserts") || overwrite)
			set("delayBetweenInserts", "30");
	}
}
