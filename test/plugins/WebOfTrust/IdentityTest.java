/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import java.net.MalformedURLException;

import plugins.WebOfTrust.Identity.FetchState;
import plugins.WebOfTrust.Identity.IdentityID;
import plugins.WebOfTrust.exceptions.InvalidParameterException;
import plugins.WebOfTrust.exceptions.UnknownIdentityException;

import com.db4o.ObjectSet;

import freenet.keys.FreenetURI;
import freenet.support.Base64;

/**
 * @author xor (xor@freenetproject.org) where not specified otherwise
 */
public final class IdentityTest extends DatabaseBasedTest {
	
	private final String uriString = "USK@yGvITGZzrY1vUZK-4AaYLgcjZ7ysRqNTMfdcO8gS-LY,-ab5bJVD3Lp-LXEQqBAhJpMKrKJ19RnNaZMIkusU79s,AQACAAE/WebOfTrust/23";
	private FreenetURI uri;
	private Identity identity;
	
	private final String uriB = "USK@R3Lp2s4jdX-3Q96c0A9530qg7JsvA9vi2K0hwY9wG-4,ipkgYftRpo0StBlYkJUawZhg~SO29NZIINseUtBhEfE,AQACAAE/WebOfTrust/0";
	
	/**
	 * @author Julien Cornuwel (batosai@freenetproject.org)
	 */
	@Override
	protected void setUp() throws Exception {
		super.setUp();
		
		uri = new FreenetURI(uriString);

		identity = new Identity(mWoT, uri, "test", true);
		identity.addContext("bleh");
		identity.setProperty("testproperty","foo1a");
		identity.storeAndCommit();
		
		// TODO: Modify the test to NOT keep a reference to the identities as member variables so the following also garbage collects them.
		flushCaches();
	}
	
	public void testInsertRequestUriMixup() throws InvalidParameterException {
		final String requestURI = "SSK@i-cMLfOF0D5kDf3OpV~W~S57TkJHmLNGRYAcAfxJ7VM,4i-eSsxL7UMUwKOegQfBJ3yIqxaQJzOCxujY0Snr8YA,AQACAAE/WebOfTrust";
		final String insertURI = "SSK@IxTDudcTeNlorAr0jFaoQVxV-Mtu-Dln42-2fIXoLK4,4i-eSsxL7UMUwKOegQfBJ3yIqxaQJzOCxujY0Snr8YA,AQECAAE/WebOfTrust";
		
		try {
			new Identity(mWoT, insertURI, "test", true);
			fail("Identity creation with insert URI allowed!");
		} catch (MalformedURLException e) {
			// This is what we expect.
		}
		
		try {
			new OwnIdentity(mWoT, requestURI, insertURI, "test", true);
			fail("OwnIdentity creation with mixed up request/insert URI allowed!");
		} catch (MalformedURLException e) {
			// This is what we expect.
		}
	}
	
	/**
	 * @author Julien Cornuwel (batosai@freenetproject.org)
	 */
	public void testIdentityStored() {
		ObjectSet<Identity> result = mWoT.getAllIdentities();
		assertEquals(1, result.size());
		
		assertEquals(identity, result.next());
	}

	/**
	 * @author Julien Cornuwel (batosai@freenetproject.org)
	 */
	public void testGetByURI() throws MalformedURLException, UnknownIdentityException {
		assertEquals(identity, mWoT.getIdentityByURI(uri));
		assertEquals(identity, mWoT.getIdentityByURI(uriString));
	}

	/**
	 * @author Julien Cornuwel (batosai@freenetproject.org)
	 */
	public void testContexts() throws InvalidParameterException {
		assertFalse(identity.hasContext("foo"));
		identity.addContext("test");
		assertTrue(identity.hasContext("test"));
		identity.removeContext("test");
		assertFalse(identity.hasContext("test"));
		
		/* TODO: Obtain the identity from the db between each line ... */
	}

	/**
	 * @author Julien Cornuwel (batosai@freenetproject.org)
	 */
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
	
	/**
	 * @author Julien Cornuwel (batosai@freenetproject.org)
	 */
	public void testPersistence() throws MalformedURLException, UnknownIdentityException {
		flushCaches();
		
		assertEquals(1, mWoT.getAllIdentities().size());
		
		Identity stored = mWoT.getIdentityByURI(uriString);
		assertSame(identity, stored);
		
		identity.checkedActivate(10);
		
		stored = null;
		mWoT.terminate();
		mWoT = null;
		
		flushCaches();
		
		mWoT = new WebOfTrust(getDatabaseFilename());
		
		identity.initializeTransient(mWoT);  // Prevent DatabaseClosedException in .equals()
		
		assertEquals(1, mWoT.getAllIdentities().size());	
		
		stored = mWoT.getIdentityByURI(uriString);
		assertNotSame(identity, stored);
		assertEquals(identity, stored);
		assertEquals(identity.getAddedDate(), stored.getAddedDate());
		assertEquals(identity.getLastChangeDate(), stored.getLastChangeDate());
	}
	
	public void testIsNicknameValid() {
		assertFalse(Identity.isNicknameValid("a@b"));
		// TODO: Implement a full test.
	}

	public final void testGetID() {
		assertEquals(Base64.encode(identity.getRequestURI().getRoutingKey()), identity.getID());
	}

	// TODO: Move to a seperate test class for IdentityID
	public final void testGetIDFromURI() {
		assertEquals(Base64.encode(uri.getRoutingKey()), IdentityID.constructAndValidateFromURI(uri).toString());
	}

	public final void testGetRequestURI() throws InvalidParameterException, MalformedURLException {
		// We generate a new identity because we want to make sure that the edition of the URI is 0 - the identity constructor will set it to 0 otherwise
		// We need the edition not to change so the assertNotSame test makes sense.
		
		final FreenetURI uriWithProperEdition = new FreenetURI("USK@R3Lp2s4jdX-3Q96c0A9530qg7JsvA9vi2K0hwY9wG-4,ipkgYftRpo0StBlYkJUawZhg~SO29NZIINseUtBhEfE,AQACAAE/WebOfTrust/0");
		final Identity identity = new Identity(mWoT, uriWithProperEdition, "test", true);
		
		assertEquals(uriWithProperEdition, identity.getRequestURI());
		
		// Constructors should clone all objects which they receive to ensure that deletion of one object does not destruct the database integrity of another.
		assertNotSame(uriWithProperEdition, identity.getRequestURI());
	}
	
	public final void testGetEdition() throws InvalidParameterException {
		assertEquals(23, uri.getSuggestedEdition());
		// The edition which is passed in during construction of the identity MUST NOT be stored as the current edition
		// - it should be stored as an edition hint as we cannot be sure whether the edition really exists because
		// identity URIs are usually obtained from not trustworthy sources.
		assertEquals(0, identity.getEdition());
		identity.setEdition(10);
		assertEquals(10, identity.getEdition());
	}
	
	public final void testGetCurrentEditionFetchState() {
		assertEquals(FetchState.NotFetched, identity.getCurrentEditionFetchState());
		
		identity.onFetched();
		assertEquals(FetchState.Fetched, identity.getCurrentEditionFetchState());
		
		identity.onParsingFailed();
		assertEquals(FetchState.ParsingFailed, identity.getCurrentEditionFetchState());
		
		identity.onFetched();
		assertEquals(FetchState.Fetched, identity.getCurrentEditionFetchState());
		identity.markForRefetch();
		assertEquals(FetchState.NotFetched, identity.getCurrentEditionFetchState());
	}

	// FIXME: Test whether updated() is called as well.
	public final void testtSetEdition() throws InvalidParameterException {
		// Test preconditions
		assertEquals(0, identity.getEdition());
		assertEquals(identity.getEdition(), identity.getRequestURI().getEdition());
		assertEquals(23, uri.getEdition());
		assertEquals(uri.getEdition(), identity.getLatestEditionHint());
		identity.onFetched();
		
		// Test fetching of a new edition while edition hint stays valid and fetch state gets invalidated
		identity.setEdition(10);
		assertEquals(10, identity.getEdition());
		assertEquals(identity.getEdition(), identity.getRequestURI().getEdition());
		assertEquals(23, identity.getLatestEditionHint());
		assertEquals(FetchState.NotFetched, identity.getCurrentEditionFetchState());
		
		// Test fetching of a new edition which invalidates the edition hint
		identity.setEdition(24);
		assertEquals(24, identity.getLatestEditionHint());
		
		identity.setNewEditionHint(50);
		
		// Test whether decreasing of edition is correctly disallowed
		try {
			identity.setEdition(23);
			fail("Decreasing the edition should not be allowed");
		} catch(InvalidParameterException e) {
			assertEquals(24, identity.getEdition());
			assertEquals(identity.getEdition(), identity.getRequestURI().getEdition());
			assertEquals(50, identity.getLatestEditionHint());
		}
		
		// Test setEdition(currentEdition) - should not touch the edition hint.
		assertEquals(50, identity.getLatestEditionHint());
		identity.onFetched();
		identity.setEdition(identity.getEdition());
		assertEquals(FetchState.Fetched, identity.getCurrentEditionFetchState());
		assertEquals(24, identity.getEdition());
		assertEquals(identity.getEdition(), identity.getRequestURI().getEdition());
		assertEquals(50, identity.getLatestEditionHint());
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
