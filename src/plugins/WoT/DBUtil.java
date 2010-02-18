/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package plugins.WoT;

import com.db4o.ext.ExtObjectContainer;

import freenet.support.Logger;

/**
 * ATTENTION: This code is a duplicate of plugins.Freetalk.DBUtil. Please keep the both versions synchronized.
 */
public class DBUtil {

	/**
	 * Used to check whether an object is active before storing it.
	 * 
	 * Logs an error if the object is not active.
	 * 
	 * Activates the object to the specified depth.
	 */
	public static void checkedActivate(ExtObjectContainer db, Object object, int depth) {
		if(db.isStored(object)) {
			if(!db.isActive(object))
				Logger.error(object, "Trying to store a non-active object!");
				
			db.activate(object, depth);
		}
	}
	
	public static void throwIfNotStored(ExtObjectContainer db, Object object) {
		if(object == null) {
			Logger.error(DBUtil.class, "Mandatory object is null!");
			throw new RuntimeException("Mandatory object is null!"); 
		}
		
		if(!db.isStored(object)) {
			Logger.error(object, "Mandatory object is not stored!");
			throw new RuntimeException("Mandatory object is not stored!");
		}
	}
	
	public static void checkedDelete(ExtObjectContainer db, Object object) {
		if(db.isStored(object))
			db.delete(object);
		else
			Logger.error(object, "Trying to delete a inexistent object!");
	}
	
	public static void rollbackAndThrow(ExtObjectContainer db, Object loggingObject, RuntimeException error) {
		db.rollback(); db.purge(); Logger.error(loggingObject, "ROLLED BACK!", error);
		throw error;
	}
}
