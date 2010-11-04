/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import java.net.MalformedURLException;

import plugins.WebOfTrust.exceptions.InvalidParameterException;
import plugins.WebOfTrust.exceptions.UnknownIdentityException;

import com.db4o.ObjectSet;

/**
 * @author xor (xor@freenetproject.org)
 * @author Julien Cornuwel (batosai@freenetproject.org)
 */
public class IdentityTest extends DatabaseBasedTest {
	
	private String uri = "USK@yGvITGZzrY1vUZK-4AaYLgcjZ7ysRqNTMfdcO8gS-LY,-ab5bJVD3Lp-LXEQqBAhJpMKrKJ19RnNaZMIkusU79s,AQACAAE/WoT/0";
	private String uriB = "USK@R3Lp2s4jdX-3Q96c0A9530qg7JsvA9vi2K0hwY9wG-4,ipkgYftRpo0StBlYkJUawZhg~SO29NZIINseUtBhEfE,AQACAAE/WoT/0";
	private Identity identity;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		
		identity = new Identity(uri, "test", true);
		identity.initializeTransient(mWoT);
		identity.addContext("bleh");
		identity.setProperty("testproperty","foo1a");
		identity.storeAndCommit();
		
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
		flushCaches();
		
		assertEquals(1, mWoT.getAllIdentities().size());
		
		Identity stored = mWoT.getIdentityByURI(uri);
		assertSame(identity, stored);
		
		identity.checkedActivate(10);
		
		stored = null;
		mWoT.terminate();
		mWoT = null;
		
		flushCaches();
		
		mWoT = new WebOfTrust(getDatabaseFilename());
		
		identity.initializeTransient(mWoT);  // Prevent DatabaseClosedException in .equals()
		
		assertEquals(1, mWoT.getAllIdentities().size());	
		
		stored = mWoT.getIdentityByURI(uri);
		assertNotSame(identity, stored);
		assertEquals(identity, stored);
		assertEquals(identity.getAddedDate(), stored.getAddedDate());
		assertEquals(identity.getLastChangeDate(), stored.getLastChangeDate());
	}
	
//	public void testEquals() {
//		do {
//			try {
//				Thread.sleep(1);
//			} catch (InterruptedException e) { }
//		} while(identity.getAddedDate().equals(CurrentTimeUTC.get()));
//		
//		assertEquals(identity, identity);
//		assertEquals(identity, identity.clone());
//		
//		
//	
//		
//		Object[] inequalObjects = new Object[] {
//			new Object(),
//			new Identity(uriB, identity.getNickname(), identity.doesPublishTrustList());
//		};
//		
//		for(Object other : inequalObjects)
//			assertFalse(score.equals(other));
//	}
//	
//	public void testClone() {
//		do {
//			try {
//				Thread.sleep(1);
//			} catch (InterruptedException e) { }
//		} while(identity.getAddedDate().equals(CurrentTimeUTC.get()));
//		
//		Identity clone = identity.clone();
//		assertNotSame(clone, identity);
//		assertEquals(identity.getEdition(), clone.getEdition());
//		assertEquals(identity.getID(), clone.getID());
//		assertEquals(identity.getLatestEditionHint(), clone.getLatestEditionHint());
//		assertNotSame(identity.getNickname(), clone.getNickname());
//		assertEquals(identity.getNickname(), clone.getNickname());
//		assertEquals(identity.getProperties())
//	}
}
