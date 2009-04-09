/**
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */
package plugins.WoT.test;

import java.net.MalformedURLException;

import plugins.WoT.Identity;
import plugins.WoT.exceptions.DuplicateIdentityException;
import plugins.WoT.exceptions.InvalidParameterException;
import plugins.WoT.exceptions.UnknownIdentityException;

import com.db4o.Db4o;
import com.db4o.ObjectSet;

/**
 * @author Julien Cornuwel (batosai@freenetproject.org)
 */
public class IdentityTest extends DatabaseBasedTest {
	
	private String uri = "USK@yGvITGZzrY1vUZK-4AaYLgcjZ7ysRqNTMfdcO8gS-LY,-ab5bJVD3Lp-LXEQqBAhJpMKrKJ19RnNaZMIkusU79s,AQACAAE/WoT/0";
	private Identity identity;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		
		identity = new Identity(uri, "test", true);
		identity.addContext(db, "bleh");
		identity.storeAndCommit(db);
	}
	
	/* FIXME: Add some logic to make db4o deactivate everything which is not used before loading the objects from the db!
	 * Otherwise these tests might not be sufficient. 
	 * Put this logic into the DatabaseBasedTest base class. */
	
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
		identity.addContext(db, "test");
		assertTrue(identity.hasContext("test"));
		identity.removeContext("test", db);
		assertFalse(identity.hasContext("test"));
	}

	public void testProperties() {
		try {
			identity.setProperty(db, "foo", "bar");
		} catch (InvalidParameterException e) {}
		
		try {
			assertTrue(identity.getProperty("foo").equals("bar"));
		} catch (InvalidParameterException e) { fail(); }
		
		try {
			identity.removeProperty("foo", db);
		} catch (InvalidParameterException e) {	fail();	}
		
		try {
			identity.getProperty("foo");
			fail();
		} catch (InvalidParameterException e) {}
	}
	
	public void testPersistence() throws MalformedURLException, DuplicateIdentityException {
		db.close();
		
		System.gc();
		System.runFinalization();
		
		db = Db4o.openFile(getDatabaseFilename());
		
		assertEquals(1, Identity.getNbIdentities(db));
		try {
			Identity.getByURI(db, uri);
		} catch (UnknownIdentityException e) {
			fail();
		}
	}
}
