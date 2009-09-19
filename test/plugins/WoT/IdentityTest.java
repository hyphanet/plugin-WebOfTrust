/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WoT;

import java.net.MalformedURLException;

import plugins.WoT.Identity;
import plugins.WoT.WoT;
import plugins.WoT.exceptions.InvalidParameterException;
import plugins.WoT.exceptions.UnknownIdentityException;

import com.db4o.ObjectSet;

/**
 * @author xor (xor@freenetproject.org) Julien Cornuwel (batosai@freenetproject.org)
 */
public class IdentityTest extends DatabaseBasedTest {
	
	private String uri = "USK@yGvITGZzrY1vUZK-4AaYLgcjZ7ysRqNTMfdcO8gS-LY,-ab5bJVD3Lp-LXEQqBAhJpMKrKJ19RnNaZMIkusU79s,AQACAAE/WoT/0";
	private Identity identity;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		
		identity = new Identity(uri, "test", true);
		identity.addContext("bleh");
		identity.setProperty("testproperty","foo1a");
		mWoT.storeAndCommit(identity);
		
		// TODO: Modify the test to NOT keep a reference to the identities as member variables so the followig also garbage collects them.
		flushCaches();
	}
	
	public void testIdentityStored() {
		ObjectSet<Identity> result = mWoT.getAllIdentities();
		assertEquals(1, result.size());
		
		assertEquals(identity, result.next());
	}
	
	public void testGetByURI() throws MalformedURLException, UnknownIdentityException {
		assertEquals(identity, mWoT.getIdentityByURI(uri));
	}

	public void testContexts() throws InvalidParameterException {
		assertFalse(identity.hasContext("foo"));
		identity.addContext("test");
		assertTrue(identity.hasContext("test"));
		identity.removeContext("test");
		assertFalse(identity.hasContext("test"));
		
		/* TODO: Obtain the identity from the db between each line ... */
	}

	public void testProperties() throws InvalidParameterException {
		identity.setProperty("foo", "bar");
		assertEquals("bar", identity.getProperty("foo"));
		identity.removeProperty("foo");
		
		try {
			identity.getProperty("foo");
			fail();
		} catch (InvalidParameterException e) {
			
		}
	}
	
	public void testPersistence() throws MalformedURLException, UnknownIdentityException {
		mWoT.terminate();
		mWoT = null;
		
		flushCaches();
		
		mWoT = new WoT(getDatabaseFilename());
		
		assertEquals(1, mWoT.getAllIdentities().size());	
		
		Identity stored = mWoT.getIdentityByURI(uri);
		assertEquals(identity.getID(), stored.getID());
		assertEquals(identity.getRequestURI(), stored.getRequestURI());
		assertEquals(identity.getEdition(), stored.getEdition());
		assertEquals(identity.getNickname(), stored.getNickname());
		assertEquals(identity.getAddedDate(), stored.getAddedDate());
		assertEquals(identity.getLastChangeDate(), stored.getLastChangeDate());
		assertEquals(identity.getContexts(), stored.getContexts());
		assertEquals(identity.getProperties(), stored.getProperties());
	}
}
