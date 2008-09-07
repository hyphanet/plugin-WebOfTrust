/**
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */
package plugins.WoT;

import java.io.File;
import java.net.MalformedURLException;

import com.db4o.Db4o;
import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;

import junit.framework.TestCase;

/**
 * @author Julien Cornuwel (batosai@freenetproject.org)
 */
public class IdentityTest extends TestCase {
	
	private Identity identity;
	private ObjectContainer db;
	
	public IdentityTest(String name) {
		super(name);
	}
	
	protected void setUp() throws Exception {
		super.setUp();
		identity = new Identity("USK@MF2Vc6FRgeFMZJ0s2l9hOop87EYWAydUZakJzL0OfV8,fQeN-RMQZsUrDha2LCJWOMFk1-EiXZxfTnBT8NEgY00,AQACAAE/WoT/0",
				"Seed Identity",
				"true",
				"boostrap");
		
		db = Db4o.openFile("identityTest.db4o");
		db.store(identity);
	}

	protected void tearDown() throws Exception {
		db.close();
		new File("identityTest.db4o").delete();
	}
	
	public void testIdentityStored() {
		ObjectSet<Identity> result = db.queryByExample(Identity.class);
		assertEquals(result.size(), 1);
	}
	
	public void testGetByURI() throws MalformedURLException, UnknownIdentityException, DuplicateIdentityException {
		assertNotNull(Identity.getByURI(db, "USK@MF2Vc6FRgeFMZJ0s2l9hOop87EYWAydUZakJzL0OfV8,fQeN-RMQZsUrDha2LCJWOMFk1-EiXZxfTnBT8NEgY00,AQACAAE/WoT/0"));
	}

	public void testContexts() throws InvalidParameterException  {
		assertFalse(identity.hasContext("foo"));
		identity.addContext("test", db);
		assertTrue(identity.hasContext("test"));
		identity.removeContext("test", db);
		assertFalse(identity.hasContext("test"));
	}

	public void testProperties() {
		try {
			identity.setProp("foo", "bar", db);
		} catch (InvalidParameterException e) {}
		
		try {
			assertTrue(identity.getProp("foo").equals("bar"));
		} catch (InvalidParameterException e) { fail(); }
		
		try {
			identity.removeProp("foo", db);
		} catch (InvalidParameterException e) {	fail();	}
		
		try {
			identity.getProp("foo");
			fail();
		} catch (InvalidParameterException e) {}
	}

}
