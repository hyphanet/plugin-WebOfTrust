/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import static java.lang.System.identityHashCode;
import static java.lang.Thread.sleep;
import static org.junit.Assert.*;
import static plugins.WebOfTrust.WebOfTrust.VALID_CAPACITIES;

import java.net.MalformedURLException;
import java.util.Date;

import org.junit.Before;
import org.junit.Test;

import plugins.WebOfTrust.exceptions.InvalidParameterException;
import freenet.support.CurrentTimeUTC;

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
		// Anything >= -1 is allowed.
		int[] goodRanks = { -1, 0, 1, 2, Integer.MAX_VALUE  };
		// Only those specific values are legal. Same as WebOfTrust.capacities.
		// We intentionally don't use the same array object here to ensure people who change it will
		// notice that this test fails and should be reviewed for whether the rest of it still makes
		// sense with the new capacity values.
		int[] goodCapacities = { 0, 1, 2, 6, 16, 40, 100 };

		// Anything < -1 is illegal
		int[] badRanks = new int[] { Integer.MIN_VALUE, -3, -2 } ;
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

	@Test public void testHashCode() throws MalformedURLException, InvalidParameterException {
		WebOfTrustInterface wot = mWebOfTrust;
		OwnIdentity truster = addRandomOwnIdentities(1).get(0);
		Identity trustee = addRandomIdentities(1).get(0);
		
		Score s1 = new Score(wot, truster, trustee, 100, 2, 16);
		assertNotEquals(identityHashCode(s1), s1.hashCode());
		assertEquals(s1.getID().hashCode(), s1.hashCode());
		assertNotEquals(identityHashCode(s1.getID().hashCode()), s1.getID().hashCode());
		
		Score s2 = new Score(wot, truster, trustee, 100, 2, 16);
		assertEquals(s1.hashCode(), s2.hashCode());
		s2 = null;
		
		Score s3 = new Score(wot, truster, trustee, 101, 3, 6);
		assertEquals(s1.hashCode(), s3.hashCode());
		s3 = null;
		
		Score s4 = new Score(wot, truster, addRandomIdentities(1).get(0), 100, 2, 16);
		assertNotEquals(s1.hashCode(), s4.hashCode());
		s4 = null;
		
		Score s5 = new Score(wot, addRandomOwnIdentities(1).get(0), trustee, 100, 2, 16);
		assertNotEquals(s1.hashCode(), s5.hashCode());
		s5 = null;
	}

	@Test public void testToString() throws MalformedURLException, InvalidParameterException {
		WebOfTrustInterface wot = mWebOfTrust;
		OwnIdentity truster = addRandomOwnIdentities(1).get(0);
		Identity trustee = addRandomIdentities(1).get(0);
		Score s = new Score(wot, truster, trustee, 100, 2, 16);
		s.storeWithoutCommit();
		
		int objectID = identityHashCode(s);
		long db4oID = wot.getDatabase().getObjectInfo(s).getInternalID();
		
		String expected =
			  "[Score: objectID: " + Integer.toHexString(objectID)
			+ "; databaseID: " + Long.toHexString(db4oID)
			+ "; mID: " + truster.getID() + "@" + trustee.getID()
			+ "; mValue: " + 100
			+ "; mRank: " + 2
			+ "; mCapacity: " + 16
			+ "]";
		
		assertEquals(expected, s.toString());
	}

	/** {@link #testGetTrustee()} is a copy-paste of this */
	@Test public void testGetTruster() throws MalformedURLException, InvalidParameterException {
		OwnIdentity truster = addRandomOwnIdentities(1).get(0);
		OwnIdentity trustee = addRandomOwnIdentities(1).get(0);
		Score s = new Score(mWebOfTrust, truster, trustee, 100, 2, 16);
		
		assertNotNull(s.getTruster());
		
		// Check for potential truster/trustee mixup
		assertNotSame(trustee, s.getTruster());
		assertNotEquals(trustee, s.getTruster());
		
		// Check whether Persistent.initializeTransient() is called on truster
		assertSame(mWebOfTrust, s.getTruster().getWebOfTrust());
		
		// Actual functionality check at the end - doing the other checks before eases debugging
		assertEquals(truster, s.getTruster());
		assertSame(truster, s.getTruster());
	}

	/** Copy-paste of {@link #testGetTruster()} */
	@Test public void testGetTrustee() throws MalformedURLException, InvalidParameterException {
		OwnIdentity truster = addRandomOwnIdentities(1).get(0);
		OwnIdentity trustee = addRandomOwnIdentities(1).get(0);
		Score s = new Score(mWebOfTrust, truster, trustee, 100, 2, 16);
		
		assertNotNull(s.getTrustee());
		
		// Check for potential truster/trustee mixup
		assertNotSame(truster, s.getTrustee());
		assertNotEquals(truster, s.getTrustee());
		
		// Check whether Persistent.initializeTransient() is called on trustee
		assertSame(mWebOfTrust, s.getTrustee().getWebOfTrust());
		
		// Actual functionality check at the end - doing the other checks before eases debugging
		assertEquals(trustee, s.getTrustee());
		assertSame(trustee, s.getTrustee());
	}

	@Test public void testGetID() throws MalformedURLException, InvalidParameterException {
		OwnIdentity truster = addRandomOwnIdentities(1).get(0);
		OwnIdentity trustee = addRandomOwnIdentities(1).get(0);
		Score s = new Score(mWebOfTrust, truster, trustee, 100, 2, 16);
		
		assertNotNull(s.getID());
		
		// Check for potential truster/trustee mixup
		assertNotEquals(trustee.getID() + "@" + truster.getID(), s.getID());
		
		assertEquals(truster.getID() + "@" + trustee.getID(), s.getID());
		
		// Test caching
		assertSame(s.getID(), s.getID());
	}

	/** {@link #testGetRank()} and {@link #testGetCapacity()} are copy-pastes of this. */
	@Test public void testGetValue() throws MalformedURLException, InvalidParameterException {
		OwnIdentity truster = addRandomOwnIdentities(1).get(0);
		Identity trustee = addRandomIdentities(1).get(0);
		
		int rank = 2;
		int capacity = 16;
		int value;
		do {
			value = mRandom.nextInt();
		} while(value == rank || value == capacity); // Prevent bogus detection of mixups
		
		Score s = new Score(mWebOfTrust, truster, trustee, value, rank, capacity);
		
		// Test for mixups
		assertNotEquals(rank, s.getValue());
		assertNotEquals(capacity, s.getValue());
		
		assertEquals(value, s.getValue());
	}

	/** {@link #testSetRankInt()} is a copy-paste of this. */
	@Test public void testSetValueInt()
			throws MalformedURLException, InvalidParameterException, InterruptedException {
		
		OwnIdentity truster = addRandomOwnIdentities(1).get(0);
		Identity trustee = addRandomIdentities(1).get(0);
		Score s = new Score(mWebOfTrust, truster, trustee, 100, 2, 16);
		
		// Test whether setValue() accepts all allowed values.
		
		// All integer values are allowed.
		int[] goodValues = { Integer.MIN_VALUE, -2, -1, 0, 1, 2, Integer.MAX_VALUE };
		
		for(int value : goodValues) {
			s.setValue(value);
			assertEquals(value, s.getValue());
		}
		
		// Test updating of date of last change
		
		Date oldDate = s.getDateOfLastChange();
		
		do {
			sleep(1);
		} while(oldDate.equals(CurrentTimeUTC.get()));
		
		s.setValue(s.getValue());
		assertEquals(oldDate, s.getDateOfLastChange());
		
		s.setValue(s.getValue() - 1);
		assertNotEquals(oldDate, s.getDateOfLastChange());
		assertTrue(oldDate.before(s.getDateOfLastChange()));
	}

	/** Copy-paste of {@link #testGetValue()} */
	@Test public void testGetRank() throws MalformedURLException, InvalidParameterException {
		OwnIdentity truster = addRandomOwnIdentities(1).get(0);
		Identity trustee = addRandomIdentities(1).get(0);
		
		int value = 100;
		int rank;
		int capacity = 16;
	
		do {
			// - Anything >= -1 is allowed.
			// - Add nextInt(1) because nextInt(Integer.MAX_VALUE) will only return MAX_VALUE - 1.
			rank = -1 + mRandom.nextInt(Integer.MAX_VALUE) + mRandom.nextInt(1);
		} while(rank == value || rank == capacity); // Prevent bogus detection of mixups
		
		Score s = new Score(mWebOfTrust, truster, trustee, value, rank, capacity);
		
		// Test for mixups
		assertNotEquals(value, s.getRank());
		assertNotEquals(capacity, s.getRank());
		
		assertEquals(rank, s.getRank());
	}

	/** Amended copy-paste of {@link #testSetValueInt()} */
	@Test public void testSetRankInt()
			throws MalformedURLException, InvalidParameterException, InterruptedException {
		
		OwnIdentity truster = addRandomOwnIdentities(1).get(0);
		Identity trustee = addRandomIdentities(1).get(0);
		Score s = new Score(mWebOfTrust, truster, trustee, 100, 2, 16);
		
		// Test whether setRank() accepts all allowed ranks.
		
		// Anything >= -1 is allowed.
		int[] goodRanks = { -1, 0, 1, 2, Integer.MAX_VALUE  };
		
		for(int rank : goodRanks) {
			s.setRank(rank);
			assertEquals(rank, s.getRank());
		}
		
		// Anything < -1 is illegal
		int[] badRanks = new int[] { Integer.MIN_VALUE, -3, -2 } ;
		
		for(int rank : badRanks) {
			int oldRank = s.getRank();
			
			try {
				s.setRank(rank);
				fail();
			} catch(IllegalArgumentException e) {}
			
			assertEquals(oldRank, s.getRank());
		}
		
		// Test updating of date of last change
		
		Date oldDate = s.getDateOfLastChange();
		
		do {
			sleep(1);
		} while(oldDate.equals(CurrentTimeUTC.get()));
		
		s.setRank(s.getRank());
		assertEquals(oldDate, s.getDateOfLastChange());
		
		try {
			s.setRank(badRanks[0]);
		} catch(IllegalArgumentException e) {}
		assertEquals(oldDate, s.getDateOfLastChange());
		
		s.setRank(s.getRank() - 1);
		assertNotEquals(oldDate, s.getDateOfLastChange());
		assertTrue(oldDate.before(s.getDateOfLastChange()));
	}

	/** Copy-paste of {@link #testGetValue()} */
	@Test public void testGetCapacity() throws MalformedURLException, InvalidParameterException {
		OwnIdentity truster = addRandomOwnIdentities(1).get(0);
		Identity trustee = addRandomIdentities(1).get(0);
		
		int value = -1;
		int rank = -1; // No need to match capacity currently, not enforced by class Score yet.
		int capacity = VALID_CAPACITIES[mRandom.nextInt(VALID_CAPACITIES.length)];
		
		// Prevent bogus detection of mixups.
		// No need to re-randomize if equals: Our value/rank are currently not in VALID_CAPACITIES. 
		assert(capacity != value && capacity != rank);
		
		Score s = new Score(mWebOfTrust, truster, trustee, value, rank, capacity);
		
		// Test for mixups
		assertNotEquals(value, s.getCapacity());
		assertNotEquals(rank, s.getCapacity());
		
		assertEquals(capacity, s.getCapacity());
	}

	@Override protected WebOfTrust getWebOfTrust() {
		return mWebOfTrust;
	}

}
