/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import static java.lang.System.identityHashCode;
import static java.util.UUID.randomUUID;
import static org.junit.Assert.*;
import static plugins.WebOfTrust.WebOfTrust.VALID_CAPACITIES;
import static plugins.WebOfTrust.util.DateUtil.waitUntilCurrentTimeUTCIsAfter;

import java.lang.reflect.Field;
import java.net.MalformedURLException;
import java.util.Arrays;
import java.util.Date;

import org.junit.Before;
import org.junit.Test;

import plugins.WebOfTrust.exceptions.InvalidParameterException;
import plugins.WebOfTrust.exceptions.NotInTrustTreeException;
import plugins.WebOfTrust.exceptions.UnknownIdentityException;

import com.db4o.ext.ExtObjectContainer;

import freenet.support.CurrentTimeUTC;

/** Tests {@link Score}. */
public final class ScoreTest extends AbstractJUnit4BaseTest {

	/** A random WebOfTrust: Random {@link OwnIdentity}s, {@link Trust}s, {@link Score}s */
	private WebOfTrust mWebOfTrust;
	
	private OwnIdentity truster;
	
	private Identity trustee;
	

	@Before public void setUp() throws MalformedURLException, InvalidParameterException {
		mWebOfTrust = constructEmptyWebOfTrust();
		truster = addRandomOwnIdentities(1).get(0);
		trustee = addRandomIdentities(1).get(0);
	}

	@Test public void testScoreWebOfTrustInterfaceOwnIdentityIdentityIntIntInt() {
		WebOfTrustInterface wot = mWebOfTrust;
		
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
		// Only those specific values are legal. Same as WebOfTrust.VALID_CAPACITIES.
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

	@Test public void testToString() {
		WebOfTrustInterface wot = mWebOfTrust;
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
	@Test public void testGetValue() {
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

	/** {@link #testSetRankInt()} and {@link #testSetCapacityInt()} are copy-pastes of this. */
	@Test public void testSetValueInt() throws InterruptedException {
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
		waitUntilCurrentTimeUTCIsAfter(oldDate);
		s.setValue(s.getValue());
		assertEquals(oldDate, s.getDateOfLastChange());
		
		s.setValue(s.getValue() - 1);
		assertNotEquals(oldDate, s.getDateOfLastChange());
		assertTrue(oldDate.before(s.getDateOfLastChange()));
	}

	/** Copy-paste of {@link #testGetValue()} */
	@Test public void testGetRank() {
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
	@Test public void testSetRankInt() throws InterruptedException {
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
		waitUntilCurrentTimeUTCIsAfter(oldDate);
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
	@Test public void testGetCapacity() {
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
	
	/**
	 * Copy-paste of {@link #testSetRankInt()}, which itself is an amended copy-paste of
	 * {@link #testSetValueInt()} */
	@Test public void testSetCapacityInt() throws InterruptedException {
		Score s = new Score(mWebOfTrust, truster, trustee, 100, 2, 16);
		
		// Test whether setCapacity() accepts all allowed capacities.
		
		// Only those specific values are legal. Same as WebOfTrust.VALID_CAPACITIES.
		// We intentionally don't use the same array object here to ensure people who change it will
		// notice that this test fails and should be reviewed for whether the rest of it still makes
		// sense with the new capacity values.
		int[] goodCapacities = { 0, 1, 2, 6, 16, 40, 100 };
		
		for(int capacity : goodCapacities) {
			s.setCapacity(capacity);
			assertEquals(capacity, s.getCapacity());
		}
		
		// Anything not in goodCapacities is illegal
		int[] badCapacities = new int[] { Integer.MIN_VALUE, -2, -1, 3, 7, 101, Integer.MAX_VALUE };
		
		for(int capacity : badCapacities) {
			int oldCapacity = s.getCapacity();
			
			try {
				s.setCapacity(capacity);
				fail();
			} catch(IllegalArgumentException e) {}
			
			assertEquals(oldCapacity, s.getCapacity());
		}
		
		// Test updating of date of last change
		
		Date oldDate = s.getDateOfLastChange();
		waitUntilCurrentTimeUTCIsAfter(oldDate);
		s.setCapacity(s.getCapacity());
		assertEquals(oldDate, s.getDateOfLastChange());
		
		try {
			s.setCapacity(badCapacities[0]);
		} catch(IllegalArgumentException e) {}
		assertEquals(oldDate, s.getDateOfLastChange());
		
		s.setCapacity(goodCapacities[0]);
		assertNotEquals(oldDate, s.getDateOfLastChange());
		assertTrue(oldDate.before(s.getDateOfLastChange()));
	}
	
	@Test public void testGetDateOfLastChange() throws Exception {
		Date beforeCreation = CurrentTimeUTC.get();
		
		Score s = new Score(mWebOfTrust, truster, trustee, 100, 2, 16);
		
		assertNotNull(s.getDateOfLastChange());
		assertNotSame("Date is a mutable class, so the getter should return clones.",
			s.getDateOfLastChange(), s.getDateOfLastChange());

		Date lastChange = s.getDateOfLastChange();
		assertTrue(lastChange.after(beforeCreation) || lastChange.equals(beforeCreation));
		
		final Date creation = (Date) s.getCreationDate().clone();
		assertEquals(creation, lastChange);
		
		// Test whether functions in Score don't accidentally modify the date
		
		lastChange = s.getDateOfLastChange();
		waitUntilCurrentTimeUTCIsAfter(lastChange);
		s.activateFully();
		s.clone();
		s.cloneP();
		s.equals(new Score(mWebOfTrust, truster, trustee, 100, 2, 16));
		s.getCapacity();
		s.getDateOfLastChange();
		s.getID();
		s.getRank();
		s.getTrustee();
		s.getTruster();
		s.getValue();
		s.getVersionID();
		s.hashCode();
		// EventSource.setVersionID() is used by SubscriptionManager even when no actual changes
		// happened to the the content of the object. So it must NOT update the date of last change.
		s.setVersionID(randomUUID());
		s.startupDatabaseIntegrityTest();
		s.storeWithoutCommit();
		s.toString();
		s.serialize(); // Score.writeObject() can be accessed by this function of the parent class
		assertEquals(lastChange, s.getDateOfLastChange());
		
		// Test whether the Date is updated when it should be
		
		lastChange = s.getDateOfLastChange();
		waitUntilCurrentTimeUTCIsAfter(lastChange);
		s.setCapacity(40);
		assertTrue(s.getDateOfLastChange().after(lastChange));
		
		lastChange = s.getDateOfLastChange();
		waitUntilCurrentTimeUTCIsAfter(lastChange);
		s.setRank(1);
		assertTrue(s.getDateOfLastChange().after(lastChange));
		
		lastChange = s.getDateOfLastChange();
		waitUntilCurrentTimeUTCIsAfter(lastChange);
		s.setValue(50);
		assertTrue(s.getDateOfLastChange().after(lastChange));
		
		assertEquals("Update must not mix up creation/lastChange", creation, s.getCreationDate());
	}
	
	@Test public void testActivateFully() throws NotInTrustTreeException {
		// activateFully() is difficult to test:
		// Intuitively we would use the following algorithm:
		//    Check mWebOfTrust.getDatabase().isActive(score).
		//    If yes, check isActive() for all member variables of Score.
		//    If yes, check for members of members, and so on.
		// Unfortunately, db4o has bugs such as HashMaps not being properly activated if you start
		// activating them with a too low depth and then activate with a higher one afterwards.
		// You need to activate them to the necessary depth with the *first* call to activate().
		// So whether a particular implementation of activateFully() is sufficient depends a lot
		// on the particular member types of the class.
		// (Also, WoT by default operates with a default activation depth of 1. Testing
		// activateFully() would currently require changing Persistent.DEFAULT_ACTIVATION_DEPTH to 0
		// and recompiling.)
		// Thus, this test here is not a real test:
		// We merely aim at notifying developers that they should manually tweak and test
		// activateFully() whenever they add new member variables to the class.
		// So what this test does is:
		// - having a hardcoded list of all current fields of the class.
		// - checking whether the list of actual fields matches that.
		// - if not, notifying the developer to review & test activateFully() and update the
		//   hardcoded list.
		
		Field[] scoreFieldObjects = Score.class.getDeclaredFields();
		String[] scoreFields = new String[scoreFieldObjects.length];
		
		for(int i = 0; i < scoreFieldObjects.length; ++i)
			scoreFields[i] = scoreFieldObjects[i].toGenericString();
		
		Arrays.sort(scoreFields);
		
		String[] expectedScoreFields = {
			"private final plugins.WebOfTrust.Identity plugins.WebOfTrust.Score.mTrustee",
			"private final plugins.WebOfTrust.OwnIdentity plugins.WebOfTrust.Score.mTruster",
			"private int plugins.WebOfTrust.Score.mCapacity",
			"private int plugins.WebOfTrust.Score.mRank",
			"private int plugins.WebOfTrust.Score.mValue",
			"private java.lang.String plugins.WebOfTrust.Score.mID",
			"private java.lang.String plugins.WebOfTrust.Score.mVersionID",
			"private java.util.Date plugins.WebOfTrust.Score.mLastChangedDate",
			"private static final transient long plugins.WebOfTrust.Score.serialVersionUID",
			"static final boolean plugins.WebOfTrust.Score.$assertionsDisabled"
		};
		
		assertArrayEquals(
			  "It seems you have changed the fields of class Score. Please review activateFully() "
			+ "for whether it uses sufficient activation depth for its given fields. "
			+ "If activateFully() is correct, update the list of expected fields and the expected "
			+ "activation depth in this test. Also, test activateFully() carefully in a manual "
			+ "fashion, automated testing is not possible. See the source code of this test for an "
			+ "explanation.",
			expectedScoreFields, scoreFields);
		
		int expectedActivationDepth = 1;
		
		Score score = mWebOfTrust.getScore(truster, truster);
		score.activateFully();
		assertTrue(mWebOfTrust.getDatabase().isActive(score));
		assertEquals(expectedActivationDepth, score.getActivationDepth());
	}

	@Test public void testStoreWithoutCommit()
			throws NotInTrustTreeException, InterruptedException, UnknownIdentityException,
			       MalformedURLException, InvalidParameterException {
		
		ExtObjectContainer db = mWebOfTrust.getDatabase();
		
		Score s = new Score(mWebOfTrust, truster, trustee, 100, 2, 16);
		s.setVersionID(randomUUID()); // Remove when resolving fix request at Score.getVersionID()
		Score expectedScore = s.clone();
		s.storeWithoutCommit();
		Persistent.checkedCommit(db, this);
		s = null;
		flushCaches();
		// Notice: The truster / truster objects have been decoupled from db4o by flushCaches().
		// So we need to re-query them from the database to ensure they match the ones db4o will
		// give us on the Score object when we query it from the database again.
		truster = mWebOfTrust.getOwnIdentityByID(truster.getID());
		trustee = mWebOfTrust.getIdentityByID(trustee.getID());
		
		waitUntilCurrentTimeUTCIsAfter(expectedScore.getCreationDate());
		waitUntilCurrentTimeUTCIsAfter(expectedScore.getDateOfLastChange());
		
		s = mWebOfTrust.getScore(truster, trustee);
		assertTrue(s.startupDatabaseIntegrityTestBoolean());
		assertEquals(expectedScore, s);
		// The following are not checked by Score.equals(), so we do it on our own.
		assertEquals(expectedScore.getCreationDate(), s.getCreationDate());
		assertEquals(expectedScore.getDateOfLastChange(), s.getDateOfLastChange());
		assertEquals(expectedScore.getVersionID(), s.getVersionID());
		assertSame(truster, s.getTruster());
		assertSame(trustee, s.getTrustee());
		
		// Prevent failure of AbstractJUnit4BaseTest.testDatabaseIntegrityAfterTermination() due
		// to the Score not making sense because there are no Trust values to justify it. 
		s.deleteWithoutCommit();
		Persistent.checkedCommit(db, this);
		
		// Test whether storeWithoutCommit() refuses identities which are not stored yet.

		OwnIdentity unstoredIdentity
			= new OwnIdentity(mWebOfTrust, getRandomInsertURI(), "a", false);
		
		try {
			new Score(mWebOfTrust, unstoredIdentity, trustee, 100, 2, 16).storeWithoutCommit();
			fail();
		} catch(AssertionError e) {}
		
		try {
			new Score(mWebOfTrust, truster, unstoredIdentity, 100, 2, 16).storeWithoutCommit();
			fail();
		} catch(AssertionError e) {}
	}

	@Test public void testEquals()
			throws InterruptedException, MalformedURLException, InvalidParameterException {
		
		WebOfTrust w = mWebOfTrust;
		trustee = addRandomOwnIdentities(1).get(0);
		int value = 100;
		int rank = 3;
		int capacity = 2;
		Score s = new Score(w, truster, trustee, value, rank, capacity);
		Score equalScore = new Score(w, truster.clone(), trustee.clone(), value, rank, capacity);
		
		// "Reflexive" property of equals()
		assertEquals(s, s);
		// Basic functionality of equals() and "symmetric" property
		assertEquals(s, equalScore);
		assertEquals(equalScore, s);
		// "Transitive" property of equals
		Score equalScore2 = new Score(w, truster.clone(), trustee.clone(), value, rank, capacity);
		assertTrue(s.equals(equalScore));
		assertTrue(equalScore.equals(equalScore2));
		assertTrue(s.equals(equalScore2));
		// "Consistent" property of equals
		assertTrue(s.equals(equalScore));
		assertTrue(s.equals(equalScore));
		// Handling of invalid objects
		assertNotEquals(equalScore, null);
		
		Object[] inequalObjects = {
			new Object(),
			new Score(w, (OwnIdentity)trustee, truster, value, rank, capacity),
			new Score(w, truster, truster, value, rank, capacity),
			new Score(w, (OwnIdentity)trustee, trustee, value, rank, capacity),
			new Score(w, truster, trustee, value+1, rank, capacity),
			new Score(w, truster, trustee, value, rank+1, capacity),
			new Score(w, truster, trustee, value, rank, capacity-1),
		};
		
		for(Object other : inequalObjects) {
			assertNotEquals(s, other);
			assertNotEquals(other, s);
		}
		
		waitUntilCurrentTimeUTCIsAfter(equalScore.getDateOfLastChange());
		s.setValue(value - 1);
		s.setValue(value);
		assertNotEquals(equalScore.getDateOfLastChange(), s.getDateOfLastChange());
		assertEquals("Modification of date of last change shouldn't matter", equalScore, s);
		
		waitUntilCurrentTimeUTCIsAfter(equalScore.getCreationDate());
		s = new Score(w, truster, trustee, value, rank, capacity);
		assertNotEquals(equalScore.getCreationDate(), s.getCreationDate());
		assertEquals("Modification of date of creation shouldn't matter", equalScore, s);
		
		s.setVersionID(randomUUID());
		assertNotEquals(equalScore.getVersionID(), s.getVersionID());
		assertEquals("Modification of versionID shouldn't matter", equalScore, s);
		
		// Score.equals() should only compare the ID of the truster/trustee, not their equals().
		s.getTruster().setEdition(s.getTruster().getEdition() + 1);
		assertNotEquals(equalScore.getTruster(), s.getTruster());
		assertEquals(equalScore, s);
		s.getTrustee().setEdition(s.getTrustee().getEdition() + 1);
		assertNotEquals(equalScore.getTrustee(), s.getTrustee());
		assertEquals(equalScore, s);
	}

	@Test public void testClone()
			throws NotInTrustTreeException, IllegalArgumentException,
			       IllegalAccessException, InterruptedException {
		
		final Score original          = new Score(mWebOfTrust, truster, trustee, 100, 2, 16);
		final Score originalDuplicate = new Score(mWebOfTrust, truster, trustee, 100, 2, 16);
		final Date originalCreation   = original.getCreationDate();
		
		// Force date of creation and date of last change to mismatch:
		// clone() might mix them up by accident, we want to test that.
		waitUntilCurrentTimeUTCIsAfter(original.getCreationDate());
		original.setValue(101);
		original.setValue(100);
		final Date originalLastChange = original.getDateOfLastChange();
		assertTrue(original.getCreationDate().before(originalLastChange));

		// Force all dates to be in the past to ensure their cloning gets tested.
		waitUntilCurrentTimeUTCIsAfter(originalLastChange);
		
		// The mVersionID member variable is initialized to null by the constructor which also
		// is the default value of type UUID. So a clone() implementation which forgets to copy
		// the field would appear to be working if we tested with null: The constructor would
		// initialize to the default null which would be the same as the original null.
		// So let's use a random UUID.
		original.setVersionID(randomUUID());
		
		final Score clone = original.clone();
		
		assertEquals(original, clone);
		assertNotSame(original, clone);
		
		// Test whether clone() maybe wrongly called setters upon the original instead of the clone
		assertEquals(originalDuplicate, original);
		assertEquals(originalCreation, original.getCreationDate());
		assertEquals(originalLastChange, original.getDateOfLastChange());
		
		testClone(Persistent.class, original, clone);
		testClone(Score.class, original, clone);
	}

	@Override protected WebOfTrust getWebOfTrust() {
		return mWebOfTrust;
	}

}
