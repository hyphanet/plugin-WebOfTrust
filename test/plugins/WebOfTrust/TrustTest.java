/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import java.net.MalformedURLException;

import plugins.WebOfTrust.exceptions.DuplicateTrustException;
import plugins.WebOfTrust.exceptions.InvalidParameterException;
import plugins.WebOfTrust.exceptions.NotTrustedException;
import plugins.WebOfTrust.exceptions.UnknownIdentityException;

/**
 * @author xor (xor@freenetproject.org)
 * @author Julien Cornuwel (batosai@freenetproject.org)
 */
public class TrustTest extends DatabaseBasedTest {
	
	private String uriA = "USK@MF2Vc6FRgeFMZJ0s2l9hOop87EYWAydUZakJzL0OfV8,fQeN-RMQZsUrDha2LCJWOMFk1-EiXZxfTnBT8NEgY00,AQACAAE/WoT/0";
	private String uriB = "USK@R3Lp2s4jdX-3Q96c0A9530qg7JsvA9vi2K0hwY9wG-4,ipkgYftRpo0StBlYkJUawZhg~SO29NZIINseUtBhEfE,AQACAAE/WoT/0";
	private Identity a;
	private Identity b;


	@Override
	protected void setUp() throws Exception {
		super.setUp();

		a = new Identity(uriA, "A", true); a.initializeTransient(mWoT); a.storeWithoutCommit();
		b = new Identity(uriB, "B", true); b.initializeTransient(mWoT); b.storeWithoutCommit();
		
		Trust trust = new Trust(a,b,(byte)100,"test"); trust.initializeTransient(mWoT);trust.storeWithoutCommit();
		Persistent.checkedCommit(mWoT.getDatabase(), this);
		
		// TODO: Modify the test to NOT keep a reference to the identities as member variables so the followig also garbage collects them.
		flushCaches();
	}
	
	public void testConstructor() throws InvalidParameterException {		
		try {
			new Trust(a, null, (byte)100, "test");
			fail("Constructor allows trustee to be null");
		}
		catch(NullPointerException e) { }
		
		try {
			new Trust(null, a, (byte)100, "test");
			fail("Constructor allows truster to be null");
		}
		catch(NullPointerException e) {}
		
		try {
			new Trust(a, b, (byte)-101, "test");
			fail("Constructor allows values less than -100");
		}
		catch(InvalidParameterException e) {}
		
		try {
			new Trust(a, b, (byte)101, "test");
			fail("Constructor allows values higher than 100");
		}
		catch(InvalidParameterException e) {}
		
		try { 
			new Trust(a, a, (byte)100, "test");
			fail("Constructor allows self-referential trust values");
		}
		catch(InvalidParameterException e) { }
	}

	public void testTrust() throws DuplicateTrustException, NotTrustedException {

		Trust trust = mWoT.getTrust(a, b); 
		assertTrue(trust.getTruster() == a);
		assertTrue(trust.getTrustee() == b);
		assertTrue(trust.getValue() == 100);
		assertEquals("test", trust.getComment());
	}
	
	public void testTrustPersistence() throws MalformedURLException, UnknownIdentityException, DuplicateTrustException, NotTrustedException {
		
		mWoT.terminate();
		mWoT = null;
		
		System.gc();
		System.runFinalization();
		
		mWoT = new WebOfTrust(getDatabaseFilename());
		
		a = mWoT.getIdentityByURI(uriA);
		b = mWoT.getIdentityByURI(uriB);
		Trust trust = mWoT.getTrust(a, b);
		
		assertTrue(trust.getTruster() == a);
		assertTrue(trust.getTrustee() == b);
		assertTrue(trust.getValue() == 100);
		assertEquals("test", trust.getComment());
	}
}
