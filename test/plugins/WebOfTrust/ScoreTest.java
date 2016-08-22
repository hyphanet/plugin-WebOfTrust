/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import static org.junit.Assert.*;

import java.net.MalformedURLException;

import org.junit.Before;
import org.junit.Test;

import plugins.WebOfTrust.exceptions.InvalidParameterException;

/** Tests {@link Score}. */
public class ScoreTest extends AbstractJUnit4BaseTest {

	/** A random WebOfTrust: Random {@link OwnIdentity}s, {@link Trust}s, {@link Score}s */
	private WebOfTrust mWebOfTrust;

	@Before public void setUp() {
		mWebOfTrust = constructEmptyWebOfTrust();
	}

	@Test public void testScoreWebOfTrustInterfaceOwnIdentityIdentityIntIntInt()
			throws MalformedURLException, InvalidParameterException {
		
		WebOfTrustInterface wot = mWebOfTrust;
		OwnIdentity truster = addRandomOwnIdentities(1).get(0);
		Identity trustee = addRandomIdentities(1).get(0);
		
		// Test basic valid construction
		
		assert(WebOfTrust.capacities[2] == 16);
		Score s1 = new Score(wot, truster, trustee, 100, 2, 16);
		assertEquals(wot,     s1.getWebOfTrust());
		assertEquals(truster, s1.getTruster());
		assertEquals(trustee, s1.getTrustee());
		assertEquals(100,     s1.getScore());
		assertEquals(2,       s1.getRank());
		assertEquals(16,      s1.getCapacity());
		s1 = null;

		// Test for throwing NullPointerException upon nulls in first 3 params
		// TODO: Java 8: Could be simplified with lambda expressions:
		// assertTrue(doesNotThrow(() -> new Score(...));
		
		try {
			new Score(null, truster, trustee, 100, 2, 16);
			fail();
		} catch(NullPointerException e) {}
		
		try {
			new Score(wot,   null,   trustee, 100, 2, 16);
			fail();
		} catch(NullPointerException e) {}
		
		try {
			new Score(wot,   truster, null,   100, 2, 16);
			fail();
		} catch(NullPointerException e) {}
		
		// Allow special case of an OwnIdentity assigning a score to itself.
		// This is used for Score computation at class WebOfTrust
		assert(WebOfTrust.capacities[0] == 100);
		Score s2 = new Score(wot, truster, truster, 100, 0, 100);
		assertEquals(truster, s2.getTruster());
		assertEquals(truster, s2.getTrustee());
		s2 = null;
	}

	@Override protected WebOfTrust getWebOfTrust() {
		return mWebOfTrust;
	}

}
