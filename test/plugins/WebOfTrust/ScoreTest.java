/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import static org.junit.Assert.*;

import java.net.MalformedURLException;
import java.util.Arrays;

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
		
		// Test integer params
		
		// All integer values are allowed.
		int[] goodValues = { Integer.MIN_VALUE, -2, -1, 0, 1, 2, Integer.MAX_VALUE };
		// Anything >= 0 is allowed.
		int[] goodRanks = { 0, 1, 2, Integer.MAX_VALUE  };
		// Only those specific values are legal. Same as WebOfTrust.capacities.
		// We intentionally don't use the same array object here to ensure people who change it will
		// notice that this test fails and should be reviewed for whether the rest of it still makes
		// sense with the new capacity values.
		int[] goodCapacities = { 0, 1, 2, 6, 16, 40, 100 };

		// Anything < 0 is illegal
		int[] badRanks = new int[] { Integer.MIN_VALUE, -2, -1 } ;
		// Anything not in goodCapacities is illegal
		int[] badCapacities = new int[] { Integer.MIN_VALUE, -2, -1, 3, 7, 101, Integer.MAX_VALUE };
		
		// Score has 3 separate setters for rank, value and capacity. This means that as a design
		// decision, it does not check whether the whole combination of value/rank/capacity does
		// make sense from a Score computation point of view as specified by
		// WebOfTrust.computeAllScoresWithoutCommit().
		// Since the setters are also used in the constructor, we thus don't check for combinations
		// which don't make sense.
		// TODO: Code quality: Change Score to have a single setter with cross-checks to fix this:
		//    set(int value, int rank, int capacity)
		// Then add explicit tests for:
		// - illegal combinations of legal value/rank/score
		// - the capacity not matching the rank.
		// Also, unfold the loop to not produce such combinations anymore.
		for(int value : goodValues) {
			for(int rank : goodRanks) {
				for(int capacity : goodCapacities) {
					Score s3 = new Score(wot, truster, trustee, value, rank, capacity);
					assertEquals(value, s3.getScore());
					assertEquals(rank, s3.getRank());
					assertEquals(capacity, s3.getCapacity());
					s3 = null;
					
					for(int badRank : badRanks) {
						try {
							new Score(wot, truster, trustee, value, badRank, capacity);
							fail("Rank: " + badRank);
						} catch(IllegalArgumentException e) {}
					}
					
					for(int badCapacity : badCapacities) {
						try {
							new Score(wot, truster, trustee, value, rank, badCapacity);
							fail("Capacity: " + badCapacity);
						} catch(IllegalArgumentException e) {}
					}
				}
			}
		}
	}

	@Override protected WebOfTrust getWebOfTrust() {
		return mWebOfTrust;
	}

}
