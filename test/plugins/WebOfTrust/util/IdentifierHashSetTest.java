/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.util;

import static org.junit.Assert.*;

import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import plugins.WebOfTrust.AbstractJUnit4BaseTest;
import plugins.WebOfTrust.Identity;
import plugins.WebOfTrust.Persistent;
import plugins.WebOfTrust.Trust;
import plugins.WebOfTrust.WebOfTrust;
import plugins.WebOfTrust.exceptions.InvalidParameterException;
import plugins.WebOfTrust.exceptions.NotTrustedException;

/** Tests {@link IdentifierHashSet}. */
public final class IdentifierHashSetTest extends AbstractJUnit4BaseTest {

	WebOfTrust mWebOfTrust;

	/**
	 * Each slot of the outer list is one "test dataset":
	 * Iterating over the outer lists will yield inner lists of different subclasses of Persistent
	 * each. I.e. for each inner list, the subclass is the same for the contained objects. Among
	 * different inner lists, the subclass of contained Persistent objects changes.
	 * Each inner list should contain a set of unique objects of its class.
	 * "Unique" means that {@link IdentifierHashSet#add(Persistent)} should return true for each
	 * when adding all of them to a single set.*/
	List<List<? extends Persistent>> mUniques = new ArrayList<List<? extends Persistent>>();

	/**
	 * The nesting of the lists is the same as at {@link #mUniques}: Each slot of the outer list
	 * is one "test dataset".
	 * The difference is that each of the inner lists contains duplicates of elements which the list
	 * with the same index at {@link #mUniques} contains.
	 * Duplicates means:
	 * - Clones of the equivalent {@link #mUniques} object.
	 * - Clones with state modified to make {@link Persistent#equals(Object)} return false, such as
	 *   changing the {@link Identity#getEdition()}.
	 * - The very same object as in {@link #mUniques}. */
	List<List<? extends Persistent>> mDuplicates = new ArrayList<List<? extends Persistent>>();


	@Before public void setUp()
			throws InvalidParameterException, NotTrustedException, MalformedURLException {
		
		mWebOfTrust = constructEmptyWebOfTrust();
		
		// Compute mUniques
		
		List<Identity> identities = new ArrayList<Identity>(addRandomIdentities(5));
		List<Trust> trusts = new ArrayList<Trust>(addRandomTrustValues(identities, 15));
		
		mUniques.add(identities);
		mUniques.add(trusts);
		
		// Compute mDuplicates
		
		List<Identity> identityDuplicates = new ArrayList<Identity>(identities.size() * 3 + 1);
		List<Trust> trustDuplicates = new ArrayList<Trust>(trusts.size() * 3 + 1);
		
		for(Identity i : identities) {
			Identity clone = i.clone();
			Identity modifiedClone = i.clone();
			modifiedClone.forceSetEdition(i.getEdition() + 1);
			
			// The central motivation behind using IdentifierHashSet instead of HashSet is that
			// Identity.equals() / Trust.equals() / Score.equals() do not only compare the ID of
			// the objects, but also their state. So using them with HashSet would not work because
			// they would return false for clone()s with a modified object state, and thus cause
			// the set to store clones even though it should not.
			// Thus, to ensure that we properly test IdentifierHashSet, we have to feed the set
			// with clones which purposefully cause equals() to return false.
			assertFalse(i.equals(modifiedClone));
			assertTrue(i.equals(clone));
			
			identityDuplicates.add(clone);
			identityDuplicates.add(modifiedClone);
			
			// For purposes of testing IdentifierHashSet.add() / addAll(), it is also interesting
			// to know whether they correctly refuse the same object which was already added.
			assertTrue(i.equals(i));
			identityDuplicates.add(i);
		}

		for(Trust t : trusts) {
			Trust clone = t.clone();
			Trust modifiedClone = t.clone();
			modifiedClone.forceSetTrusterEdition(t.getTrusterEdition() + 1);
			
			assertFalse(t.equals(modifiedClone));
			assertTrue(t.equals(clone));
			
			trustDuplicates.add(clone);
			trustDuplicates.add(modifiedClone);
			
			assertTrue(t.equals(t));
			trustDuplicates.add(t);
		}
		
		mDuplicates.add(identityDuplicates);
		mDuplicates.add(trustDuplicates);
		
		assertEquals(mUniques.size(), mDuplicates.size());
		assertEquals(mUniques.get(0).get(0).getClass(), mDuplicates.get(0).get(0).getClass());
		assertEquals(mUniques.get(1).get(0).getClass(), mDuplicates.get(1).get(0).getClass());
		assertNotEquals(mUniques.get(1).get(0).getClass(), mDuplicates.get(0).get(0).getClass());
	}

	/** Tests {@link plugins.WebOfTrust.util.IdentifierHashSet#add(Persistent)}. */
	@Test public final void testAdd() {
		for(int i=0; i < mUniques.size(); ++i) {
			List<? extends Persistent> uniques = mUniques.get(i);
			List<? extends Persistent> duplicates = mDuplicates.get(i);
			IdentifierHashSet<Persistent> h = new IdentifierHashSet<Persistent>();
			
			for(Persistent u : uniques)    assertTrue(h.add(u));
			for(Persistent d : duplicates) assertFalse(h.add(d));
		}
		
		IdentifierHashSet<Persistent> h = new IdentifierHashSet<Persistent>();
		try {
			assertNotNull(h);
			h.add(null);
			fail("Adding null should not be allowed");
		} catch(NullPointerException e) {
			// Success
		}
	}

	/** Tests {@link plugins.WebOfTrust.util.IdentifierHashSet#addAll(Collection)}. */
	@Test public final void testAddAll() {
		for(int i=0; i < mUniques.size(); ++i) {
			List<? extends Persistent> uniques = mUniques.get(i);
			List<? extends Persistent> duplicates = mDuplicates.get(i);
			IdentifierHashSet<Persistent> h = new IdentifierHashSet<Persistent>();
			
			assertTrue(h.addAll(uniques));
			assertFalse(h.addAll(duplicates));
		}
		
		IdentifierHashSet<Persistent> h = new IdentifierHashSet<Persistent>();
		ArrayList<Persistent> containsNull = new ArrayList<Persistent>();
		containsNull.add(null);
		try {
			assertNotNull(h);
			assertTrue(containsNull.contains(null));
			h.addAll(containsNull);
			fail("Adding null should not be allowed");
		} catch(NullPointerException e) {
			// Success
		}
	}

	/** Tests {@link plugins.WebOfTrust.util.IdentifierHashSet#clear()}. */
	@Test public final void testClear() {
		// Since it is difficult to test clear() in an isolated way, i.e. by only assuming clear()
		// works, it relies upon not only one other function, but 3: addAll(), contains(), size().
		// The probability of all of those 3 not working properly at once to hide errors in clear()
		// is low.
		// Also, we additionally test whether those functions work.
		
		for(int i=0; i < mUniques.size(); ++i) {
			List<? extends Persistent> uniques = mUniques.get(i);
			IdentifierHashSet<Persistent> h = new IdentifierHashSet<Persistent>();
			
			assertTrue(h.addAll(uniques));
			// Test that every element of uniques is now refused before we clear().
			assertFalse(h.addAll(uniques));
			
			assertEquals(uniques.size(), h.size());
			h.clear();
			assertEquals(0, h.size());
			
			// Test that every element of uniques is now accepted again after clear().
			// Check add() return value instead of addAll(): addAll() would return true if adding
			// succeeded for *any* of the elements. But we want to test whether it succeeds for
			// *all* of them. Also, we want to check whether contains() correctly returns false.
			for(Persistent p : uniques) {
				assertFalse(h.contains(p));
				assertTrue(h.add(p));
				assertTrue(h.contains(p));
			}
		}
		
		// Test whether clear() upon empty set does not throw.
		new IdentifierHashSet<Persistent>().clear();
	}

	/** Tests {@link plugins.WebOfTrust.util.IdentifierHashSet#contains(Object)}. */
	@Test public final void testContains() {
		for(int i=0; i < mUniques.size(); ++i) {
			List<? extends Persistent> uniques = mUniques.get(i);
			List<? extends Persistent> duplicates = mDuplicates.get(i);
			IdentifierHashSet<Persistent> h = new IdentifierHashSet<Persistent>();
			
			for(Persistent u : uniques) {
				assertFalse(h.contains(u));
				assertTrue(h.add(u));
				assertTrue(h.contains(u));
			}
			
			for(Persistent d : duplicates)
				assertTrue(h.contains(d));
		}
		
		IdentifierHashSet<Persistent> h = new IdentifierHashSet<Persistent>();
		try {
			assertNotNull(h);
			h.contains(null);
			fail("contains(null) should not be allowed");
		} catch(NullPointerException e) {
			// Success
		}
	}

	/** Tests {@link plugins.WebOfTrust.util.IdentifierHashSet#containsAll(Collection)}. */
	@Test public final void testContainsAll() {
		for(int i=0; i < mUniques.size(); ++i) {
			List<? extends Persistent> uniques = mUniques.get(i);
			List<? extends Persistent> duplicates = mDuplicates.get(i);
			IdentifierHashSet<Persistent> h = new IdentifierHashSet<Persistent>();
			
			assertFalse(h.containsAll(uniques));
			assertFalse(h.containsAll(duplicates));
			
			assertTrue(h.addAll(uniques));
			
			assertTrue(h.containsAll(uniques));
			assertTrue(h.containsAll(duplicates));
			
			h.remove(uniques.get(mRandom.nextInt(uniques.size())));
			
			assertFalse(h.containsAll(uniques));
			assertFalse(h.containsAll(duplicates));			
		}
		
		IdentifierHashSet<Persistent> h = new IdentifierHashSet<Persistent>();
		ArrayList<Persistent> containsNull = new ArrayList<Persistent>();
		containsNull.add(null);
		assertNotNull(h);
		assertTrue(containsNull.contains(null));
		try {
			h.containsAll(containsNull);
			fail("containsAll() with null element should not be allowed!");
		} catch(NullPointerException e) {
			// Success
		}
	}

	/** Tests {@link plugins.WebOfTrust.util.IdentifierHashSet#isEmpty()}. */
	@Test public final void testIsEmpty() {
		fail("Not yet implemented");
	}

	/** Tests {@link plugins.WebOfTrust.util.IdentifierHashSet#iterator()}. */
	@Test public final void testIterator() {
		fail("Not yet implemented");
	}

	/** Tests {@link plugins.WebOfTrust.util.IdentifierHashSet#remove(Object)}. */
	@Test public final void testRemove() {
		fail("Not yet implemented");
	}

	/** Tests {@link plugins.WebOfTrust.util.IdentifierHashSet#removeAll(Collection)}. */
	@Test public final void testRemoveAll() {
		fail("Not yet implemented");
	}

	/** Tests {@link plugins.WebOfTrust.util.IdentifierHashSet#retainAll(Collection)}. */
	@Test public final void testRetainAll() {
		fail("Not yet implemented");
	}

	/** Tests {@link plugins.WebOfTrust.util.IdentifierHashSet#size()}. */
	@Test public final void testSize() {
		fail("Not yet implemented");
	}

	/** Tests {@link plugins.WebOfTrust.util.IdentifierHashSet#toArray()}. */
	@Test public final void testToArray() {
		fail("Not yet implemented");
	}

	/** Tests {@link plugins.WebOfTrust.util.IdentifierHashSet#toArray(T[])}. */
	@Test public final void testToArrayTArray() {
		fail("Not yet implemented");
	}

	/** Tests {@link plugins.WebOfTrust.util.IdentifierHashSet#hashCode()}. */
	@Test public final void testHashCode() {
		fail("Not yet implemented");
	}

	/** Tests {@link plugins.WebOfTrust.util.IdentifierHashSet#equals(Object)}. */
	@Test public final void testEqualsObject() {
		fail("Not yet implemented");
	}

	@Override
	protected WebOfTrust getWebOfTrust() {
		return mWebOfTrust;
	}

}
