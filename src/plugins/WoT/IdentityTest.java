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
	
	private String uri = "USK@yGvITGZzrY1vUZK-4AaYLgcjZ7ysRqNTMfdcO8gS-LY,-ab5bJVD3Lp-LXEQqBAhJpMKrKJ19RnNaZMIkusU79s,AQACAAE/WoT/0";
	private Identity identity;
	private ObjectContainer db;
	
	public IdentityTest(String name) {
		super(name);
	}
	
	protected void setUp() throws Exception {
		super.setUp();
		identity = new Identity(uri, "test", "true", "bar");
		
		db = Db4o.openFile("identityTest.db4o");
		db.store(identity);
		db.commit();
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
		assertNotNull(Identity.getByURI(db, uri));
	}
	
	public void testGetNbIdentities() {
		assertEquals(Identity.getNbIdentities(db), 1);
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
	
	public void testPersistence() throws MalformedURLException, DuplicateIdentityException {
		db.close();
		
		System.gc();
		System.runFinalization();
		try{ Thread.sleep(2000); } 
		catch (InterruptedException e){}
		
		db = Db4o.openFile("identityTest.db4o");
		
		assertEquals(Identity.getNbIdentities(db), 1);
		try {
			Identity.getByURI(db, uri);
		} catch (UnknownIdentityException e) {
			fail();
		}
	}
}
