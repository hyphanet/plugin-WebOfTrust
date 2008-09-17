/**
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */
package plugins.WoT;

import java.util.HashMap;

import com.db4o.ObjectContainer;

/**
 * @author Julien Cornuwel (batosai@freenetproject.org)
 */
public class Config {

	private ObjectContainer db;
	private HashMap<String, String> params = null;
	
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
	
	public void initDefault(boolean overwrite) {
		
		if (!params.containsKey("delayBetweenInserts") || overwrite) set("delayBetweenInserts", "30");

		db.store(this);
	}
}
