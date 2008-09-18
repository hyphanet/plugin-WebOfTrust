/**
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */
package plugins.WoT;

import java.util.HashMap;
import java.util.Iterator;

import com.db4o.ObjectContainer;

/**
 * Contains a HashMap<String, String> which maps configuration variable names
 * to their values and stores them in the database.
 * 
 * @author Julien Cornuwel (batosai@freenetproject.org)
 */
public class Config {

	private ObjectContainer db;
	private HashMap<String, String> params = null;
	
	/**
	 * 
	 * @param db The database where the configuration is stored.
	 */
	public Config(ObjectContainer db) {
		this.db = db;
		if(params == null) {
			params = new HashMap<String, String>();
			initDefault(false);
		}
	}
	
	public void set(String key, String value) {
		params.put(key, value);
		db.store(params);
	}
	
	public String get(String key) {
		return params.get(key);
	}
	
	/**
	 * Get all valid configuration keys.
	 * @return A String array containing a copy of all keys in the database at the point of calling the function. Changes to the array do not change the database.
	 */
	public String[] getAllKeys() {
		/* We use toArray() to create a *copy* of the Set<String>. If we
		 * returned an iterator of the keySet, modifications on the
		 * configuration HashMap would be reflected in the iterator. This might
		 * lead to problems if the configuration is modified while someone is
		 * using an iterator returned by this function. Further the iterator
		 * would allow the user to delete keys from the configuration - xor */
		return (String[])params.keySet().toArray();
	}
	
	/**
	 * Add the default configuration values to the database.
	 * @param overwrite If true, overwrite already set values with the default value.
	 */
	public void initDefault(boolean overwrite) {
		
		if (!params.containsKey("delayBetweenInserts") || overwrite) set("delayBetweenInserts", "30");

		db.store(this);
	}
}
