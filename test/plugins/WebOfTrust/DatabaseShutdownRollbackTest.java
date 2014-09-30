/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import java.io.File;
import java.util.HashMap;

import org.junit.Ignore;

import com.db4o.Db4o;
import com.db4o.ObjectContainer;
import com.db4o.ObjectServer;
import com.db4o.ext.DatabaseClosedException;
import com.db4o.ext.ExtObjectContainer;

/**
 * <p>TODO FIXME: This test is designed to always fail to ensure that the following issue is fixed before we release a new WOT version:
 * <p><a href="https://bugs.freenetproject.org/view.php?id=6247">0006247: The way we use ObjectContainer.rollback() is fundamentally wrong</a></p>
 * After the issue is fixed, please remove this test.</p> 
 * <br>
 * <p>During shutdown, WOT does a manual {@link ObjectContainer#rollback()} before {@link ObjectContainer#close()}, with the intention of aborting any pending
 * transactions. But this will now work:
 * {@link ObjectContainer#close()} will do {@link ObjectContainer#commit()} implicitly. This is documented intended behavior of db4o and cannot be disabled.
 * This test's {@link #test_WillAlwaysFail()} shows that this can cause leaks which are similar to a leak bug which I was unable to find the source of yet:
 * <p><a href="https://bugs.freenetproject.org/view.php?id=6187">0006187: Database leaks objects of type HashMap and com.db4o.config.Entry</a></p>
 * It might also apply to <p><a href="https://bugs.freenetproject.org/view.php?id=5964">0005964: Class Identity leaks FreenetURI</a></p>
 * </<p>
 * 
 * <p>It can be fixed by using {@link Db4o#openServer(com.db4o.config.Configuration, String, int)} instead of 
 * {@link Db4o#openFile(com.db4o.config.Configuration, String)}. Then each transaction receives an own container via {@link ObjectServer#openClient()}.
 * If we do {@link ObjectServer#close()}, impartial transactions are NOT committed.
 * This is demonstrated at {@link #test_WillNotFail()}
 * 
 * 
 * @author xor (xor@freenetproject.org)
 */
public class DatabaseShutdownRollbackTest extends DatabaseBasedTest {

	@Ignore
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
	
	/**
	 * Will always fail, not an error: Db4o is designed that way.
	 */
	public void test_WillAlwaysFail() {
		ExtObjectContainer db = mWoT.getDatabase();
		db.store(new Leak());
		db.close();
		
		mWoT = new WebOfTrust(getDatabaseFilename());
		// This test shows that close() does implicit commit() and cause the Leak object to exist after opening the database again.
		assertFalse(mWoT.checkForDatabaseLeaks());
	}
	
	
	
	protected File secondaryDatabaseFile = null;
	
	@Override
	protected void setUp() throws Exception {
		super.setUp();
		
		File file = new File(getName() + getRandomLatinString(24) + ".db4o");
		if(file.exists())
			file.delete();
		assertFalse(file.exists());
			
		file.deleteOnExit();
		
		secondaryDatabaseFile = file;
	}

	@Ignore
	static final class Thing {
		int stuff = 1;
	}
	
	/**
	 * The proper way to use db4o 7.4. Shouldn't be used with 8.x, there ObjectContainer.openSession() should be used for each transaction. 
	 */
	public void test_WillNotFail() {
		ObjectServer server = Db4o.openServer(Db4o.newConfiguration(), secondaryDatabaseFile.toString(), 0);		
		
		ObjectContainer transaction = server.openClient();
		Thing thing = new Thing();
		transaction.store(thing);
		transaction.commit();
		transaction.close();
		
		transaction = server.openClient();
		thing.stuff = 2;
		transaction.store(thing);
		
		server.close();
		
		try {
			transaction.commit();
			fail("commit() should not work anymore because the server is closed");
		} catch(DatabaseClosedException e) {}
		transaction.close();
		
		server = Db4o.openServer(Db4o.newConfiguration(), secondaryDatabaseFile.toString(), 0);
		
		for(Thing queriedThing : server.openClient().query(Thing.class)) {
			assertEquals(1, queriedThing.stuff);
		}
	}

}
