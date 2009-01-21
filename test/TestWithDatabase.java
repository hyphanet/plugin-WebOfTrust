/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package plugins.WoT.test;

import java.io.File;

import com.db4o.Db4o;
import com.db4o.ObjectContainer;

import junit.framework.TestCase;

/**
 * A JUnit <code>TestCase</code> which opens a db4o database in setUp() and closes it in tearDown().
 * The filename of the database is chosen as the name of the test function currently run by db4o and the
 * file is deleted after the database has been closed. When setting up the test, it is assured that the database
 * file does not exist, the test will fail if it cannot be deleted.
 * 
 * The database can be accessed through the member variable <code>db</code>.
 * 
 * You have to call super.setUp() and super.tearDown() if you override one of those methods.
 * 
 * @author xor
 */
public abstract class TestWithDatabase extends TestCase {

	/**
	 * The database used by this test.
	 */
	protected ObjectContainer db;

	/**
	 * @return Returns the filename of the database. This is the name of the current test function plus ".db4o".
	 */
	private String getDatabaseFilename() {
		return getName() + ".db4o";
	}

	/**
	 * You have to call super.setUp() if you override this method.
	 */
	protected void setUp() throws Exception {
		super.setUp();
		
		File databaseFile = new File(getDatabaseFilename());
		if(databaseFile.exists())
			databaseFile.delete();
		assertFalse(databaseFile.exists());
		
		db = Db4o.openFile(getDatabaseFilename());
	}

	/**
	 * You have to call super.tearDown() if you override this method. 
	 */
	protected void tearDown() throws Exception {
		super.tearDown();
		
		db.close();
		db = null;
		new File(getDatabaseFilename()).delete();
	}

}
