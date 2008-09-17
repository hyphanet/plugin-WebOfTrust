/**
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */
package plugins.WoT;

import java.util.HashMap;

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
	 * Add the default configuration values to the database.
	 * @param overwrite If true, overwrite already set values with the default value.
	 */
	public void initDefault(boolean overwrite) {
		
		if (!params.containsKey("delayBetweenInserts") || overwrite) set("delayBetweenInserts", "30");

		db.store(this);
	}
}
