/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import java.util.HashMap;

import com.db4o.ext.ExtObjectContainer;

/**
 * Db4o has the weird convention of committing the transaction upon shutdown which can cause incomplete transactions being
 * committed - this should NEVER happen in a database system because databases normally guarantee ACID.
 * 
 * This behavior can be disabled in the db4o configuration which WOT has been doing for a long time.
 * 
 * But unfortunately testing has shown that db4o ignores this setting. This test demonstrates this issue.
 * TODO FIXME:
 * 	A fix shall be committed soon which should make WOT rollback() upon re-opening the database.
 * 	It is NOT enough to rollback() upon shutdown as {@link WebOfTrust#terminate()} already does that and the issue
 *  did happen anyway. I don't know why yet - maybe fred force-terminates WOT if WOT's terminate() takes too long
 *  which causes db.close() to happen?
 * 
 * @author xor (xor@freenetproject.org)
 */
public class DatabaseShutdownRollbackTest extends DatabaseBasedTest {

	private static final class Leak {
		/**
		 * The issue has originally appeared to cause leakage of:
		 * - HashMap objects
		 * - db4o's internal class com.db4o.config.Entry. It was being returned by database queries which should never happen 
		 *   for db4o internal classes.
		 * Therefore, we use a HashMap in the demonstration of this issue.
		 */
		private final HashMap<String, String> map = new HashMap<String,String>();
		
		public Leak() {
			map.put("test", "value");
		}
	}
	
	public void testShutdownRollback() {
		ExtObjectContainer db = mWoT.getDatabase();
		db.store(new Leak());
		// Db4o out to rollback the transaction when we close the database now because WOT explicitly configures it to do so.
		db.close();
		
		mWoT = new WebOfTrust(getDatabaseFilename());
		// However this test shows that it WON'T rollback and cause the Leak object to exist after opening the database again.
		assertFalse(mWoT.checkForDatabaseLeaks());
	}

}
