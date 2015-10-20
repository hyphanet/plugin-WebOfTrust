/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.util;

import static org.junit.Assert.*;

import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
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

	/** Same as {@link #mUniques}, just different random objects */
	List<List<? extends Persistent>> mOtherUniques = new ArrayList<List<? extends Persistent>>();

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

	/** Contains an element which is null. */
	final List<Persistent> mContainsNull = new ArrayList<Persistent>();

	/** Contains nothing. */
	IdentifierHashSet<Persistent> mEmptyIdentifierHashSet;


	@Before public void setUp()
			throws InvalidParameterException, NotTrustedException, MalformedURLException {
		
		mWebOfTrust = constructEmptyWebOfTrust();
		
		// Compute mUniques
		
		List<Identity> identities = new ArrayList<Identity>(addRandomIdentities(5));
		List<Trust> trusts = new ArrayList<Trust>(addRandomTrustValues(identities, 15));
		
		mUniques.add(identities);
		mUniques.add(trusts);
		
		// Compute mOtherUniques
		
		List<Identity> otherIdentities = new ArrayList<Identity>(addRandomIdentities(5));
		List<Trust> otherTrusts = new ArrayList<Trust>(addRandomTrustValues(otherIdentities, 15));
		
		mOtherUniques.add(otherIdentities);
		mOtherUniques.add(otherTrusts);
		
		// Compute mDuplicates
		
		List<Identity> identityDuplicates = new ArrayList<Identity>(identities.size() + 1);
		List<Trust> trustDuplicates = new ArrayList<Trust>(trusts.size() + 1);
		
		for(Identity i : identities) {
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
			identityDuplicates.add(modifiedClone);
		}

		for(Trust t : trusts) {
			Trust modifiedClone = t.clone();
			modifiedClone.forceSetTrusterEdition(t.getTrusterEdition() + 1);
			
			assertFalse(t.equals(modifiedClone));
			trustDuplicates.add(modifiedClone);
		}
		
		Collections.shuffle(identityDuplicates, mRandom);
		Collections.shuffle(trustDuplicates, mRandom);
		
		mDuplicates.add(identityDuplicates);
		mDuplicates.add(trustDuplicates);
		
		assertEquals(mUniques.size(), mDuplicates.size());
		assertEquals(mUniques.get(0).get(0).getClass(), mDuplicates.get(0).get(0).getClass());
		assertEquals(mUniques.get(1).get(0).getClass(), mDuplicates.get(1).get(0).getClass());
		assertNotEquals(mUniques.get(1).get(0).getClass(), mDuplicates.get(0).get(0).getClass());
		
		// Compute mEmptyIdentifierHashSet
		
		mEmptyIdentifierHashSet = new IdentifierHashSet<Persistent>();
		// Important to not confuse catch(NullPointerException) in following tests.
		assertNotNull(mEmptyIdentifierHashSet);
		
		// Compute mContainsNull
		
		mContainsNull.add(null);
		assertTrue(mContainsNull.contains(null));
	}

	/** Tests {@link plugins.WebOfTrust.util.IdentifierHashSet#add(Persistent)}. */
	@Test public final void testAdd() {
		for(int i=0; i < mUniques.size(); ++i) {
			List<? extends Persistent> uniques = mUniques.get(i);
			List<? extends Persistent> duplicates = mDuplicates.get(i);
			IdentifierHashSet<Persistent> h = new IdentifierHashSet<Persistent>();
			
			for(Persistent u : uniques)    assertTrue(h.add(u));
			for(Persistent u : uniques)    assertFalse(h.add(u));
			for(Persistent d : duplicates) assertFalse(h.add(d));
		}
		
		try {
			mEmptyIdentifierHashSet.add(null);
			fail("Adding null should not be allowed");
		} catch(NullPointerException e) {
			// Success
		}
	}

	/** Tests {@link plugins.WebOfTrust.util.IdentifierHashSet#addAll(Collection)}. */
	@Test public final void testAddAll() {
		for(int i=0; i < mUniques.size(); ++i) {
			List<? extends Persistent> uniques = mUniques.get(i);
			List<? extends Persistent> otherUniques = mOtherUniques.get(i);
			List<? extends Persistent> duplicates = mDuplicates.get(i);
			IdentifierHashSet<Persistent> h = new IdentifierHashSet<Persistent>();
			
			assertTrue(h.addAll(uniques));
			assertFalse(h.addAll(uniques));
			assertFalse(h.addAll(duplicates));
			assertTrue(h.addAll(otherUniques)); // Check whether it still accepts new stuff
		}
		
		try {
			mEmptyIdentifierHashSet.addAll(mContainsNull);
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
		mEmptyIdentifierHashSet.clear();
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
		
		try {
			mEmptyIdentifierHashSet.contains(null);
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
		
		try {
			mEmptyIdentifierHashSet.containsAll(mContainsNull);
			fail("containsAll() with null element should not be allowed!");
		} catch(NullPointerException e) {
			// Success
		}
	}

	/** Tests {@link plugins.WebOfTrust.util.IdentifierHashSet#isEmpty()}. */
	@Test public final void testIsEmpty() {
		for(int i=0; i < mUniques.size(); ++i) {
			List<? extends Persistent> uniques = mUniques.get(i);
			List<? extends Persistent> duplicates = mDuplicates.get(i);
			IdentifierHashSet<Persistent> h = new IdentifierHashSet<Persistent>();
			
			assertTrue(h.isEmpty());
			assertTrue(h.addAll(duplicates));
			assertFalse(h.addAll(uniques));
			assertFalse(h.isEmpty());
			h.clear();
			assertTrue(h.isEmpty());
			
			for(Persistent u : uniques) {
				assertTrue(h.add(u));
				assertFalse(h.isEmpty());
			}
			
			for(Persistent u : uniques) {
				assertFalse(h.isEmpty());
				assertTrue(h.remove(u));
			}
			
			assertTrue(h.isEmpty());
		}
		
		try {
			mEmptyIdentifierHashSet.add(null);
			fail("Adding null should not be allowed");
		} catch(NullPointerException e) {
			assertTrue(mEmptyIdentifierHashSet.isEmpty());
		}
	}

	/** Tests {@link plugins.WebOfTrust.util.IdentifierHashSet#iterator()}. */
	@Test public final void testIterator() {
		for(int i = 0; i < mUniques.size(); ++i) {
			List<? extends Persistent> uniques = mUniques.get(i);
			List<? extends Persistent> duplicates = mDuplicates.get(i);
			IdentifierHashSet<Persistent> h = new IdentifierHashSet<Persistent>();
			
			assertTrue(h.addAll(uniques));
			
			// See below "Notice" for why we do these
			assertFalse(h.addAll(duplicates));
			for(Persistent d : duplicates)
				assertFalse(h.add(d));
			
			ArrayList<Persistent> fromIterator
				= new ArrayList<Persistent>(uniques.size() + 1);
			
			for(Persistent p : h)
				fromIterator.add(p);
			
			assertEquals(uniques.size(), fromIterator.size());
			// This is O(N^2), but it should run in reasonable time since setUp() does not create a
			// huge data set.
			// Notice: This also ensures that addAll(duplicates) / add(duplicate) did not wrongly
			// replace the originals with the duplicates even though they returned false (this
			// bug did exist!)
			assertTrue(fromIterator.containsAll(uniques));
		}
	}

	/** Tests {@link plugins.WebOfTrust.util.IdentifierHashSet#remove(Object)}. */
	@Test public final void testRemove() {
		for(int i=0; i < mUniques.size(); ++i) {
			List<? extends Persistent> uniques = mUniques.get(i);
			List<? extends Persistent> otherUniques = mOtherUniques.get(i);
			List<? extends Persistent> duplicates = mDuplicates.get(i);
			IdentifierHashSet<Persistent> h = new IdentifierHashSet<Persistent>();
			
			for(Persistent u : uniques)        assertTrue(h.add(u));
			for(Persistent ou : otherUniques)  assertFalse(h.remove(ou));
			
			// Check that removal of the not-even-added otherUniques didn't have any effect
			assertEquals(uniques.size(), h.size()); 
			assertFalse(h.isEmpty());
			
			for(Persistent u : uniques)    assertTrue(h.remove(u));
			
			assertEquals(0, h.size());
			assertTrue(h.isEmpty());
			
			assertTrue(h.addAll(duplicates));
			
			for(Persistent u : uniques)    assertTrue(h.remove(u));
			
			assertEquals(0, h.size());
			assertTrue(h.isEmpty());
		}
		
		try {
			mEmptyIdentifierHashSet.remove(null);
			fail("remove(null) should not be allowed");
		} catch(NullPointerException e) {
			// Success
		}
	}

	/** Tests {@link plugins.WebOfTrust.util.IdentifierHashSet#removeAll(Collection)}. */
	@Test public final void testRemoveAll() {
		for(int i=0; i < mUniques.size(); ++i) {
			List<? extends Persistent> uniques = mUniques.get(i);
			List<? extends Persistent> otherUniques = mOtherUniques.get(i);
			List<? extends Persistent> duplicates = mDuplicates.get(i);
			IdentifierHashSet<Persistent> h = new IdentifierHashSet<Persistent>();
			
			assertTrue(h.addAll(uniques));
			assertFalse(h.removeAll(otherUniques)); // Must not have any effect
			assertFalse(h.isEmpty());
			assertEquals(uniques.size(), h.size());
			assertTrue(h.removeAll(uniques));
			assertTrue(h.isEmpty());
			assertEquals(0, h.size());
			
			assertTrue(h.addAll(uniques));
			assertFalse(h.isEmpty());
			assertEquals(uniques.size(), h.size());
			assertTrue(h.removeAll(duplicates));
			assertTrue(h.isEmpty());
			assertEquals(0, h.size());
			
			assertFalse(h.removeAll(uniques));
			assertTrue(h.isEmpty());
			assertEquals(0, h.size());
		}
		
		try {
			mEmptyIdentifierHashSet.removeAll(mContainsNull);
			fail("removeAll(containing null) should not be allowed!");
		} catch(NullPointerException e) {
			// Success
		}
	}

	/** Tests {@link plugins.WebOfTrust.util.IdentifierHashSet#retainAll(Collection)}. */
	@Test public final void testRetainAll() {
		// retainAll() was not implemented yet, so we only test whether it still is not.
		try {
			mEmptyIdentifierHashSet.retainAll(new ArrayList<Persistent>());
			fail("When implementing retainAll(), please also implement this test for it!");
		} catch(UnsupportedOperationException e) {
			// Success
			assertEquals("Not implemented yet.", e.getMessage());
		}
	}

	/** Tests {@link plugins.WebOfTrust.util.IdentifierHashSet#size()}. */
	@Test public final void testSize() {
		for(int i=0; i < mUniques.size(); ++i) {
			List<? extends Persistent> uniques = mUniques.get(i);
			List<? extends Persistent> otherUniques = mOtherUniques.get(i);
			List<? extends Persistent> duplicates = mDuplicates.get(i);
			IdentifierHashSet<Persistent> h = new IdentifierHashSet<Persistent>();
			
			assertEquals(0, h.size());
			assertTrue(h.addAll(uniques));
			assertFalse(h.isEmpty());
			assertEquals(uniques.size(), h.size());
			
			assertFalse(h.addAll(duplicates));
			assertFalse(h.removeAll(otherUniques));
			assertFalse(h.isEmpty());
			assertEquals(uniques.size(), h.size());
			
			h.clear();
			assertTrue(h.isEmpty());
			assertEquals(0, h.size());
			
			int size = 0;
			
			for(Persistent u : uniques) {
				assertEquals(size, h.size());
				assertTrue(h.add(u));
				assertEquals(++size, h.size());
			}
			
			assertFalse(h.isEmpty());
			
			for(Persistent u : uniques) {
				assertEquals(size, h.size());
				assertTrue(h.remove(u));
				assertEquals(--size, h.size());
			}
			
			assertTrue(h.isEmpty());
			assertEquals(0, h.size());
			
			h.remove(duplicates.get(0));
			assertTrue(h.isEmpty());
			assertEquals(0, h.size());
			
			h.removeAll(uniques);
			assertTrue(h.isEmpty());
			assertEquals(0, h.size());
		}
		
		try {
			mEmptyIdentifierHashSet.add(null);
			fail("Adding null should not be allowed");
		} catch(NullPointerException e) {
			assertTrue(mEmptyIdentifierHashSet.isEmpty());
			assertEquals(0, mEmptyIdentifierHashSet.size());
		}
	}

	/** Tests {@link plugins.WebOfTrust.util.IdentifierHashSet#toArray()}. */
	@Test public final void testToArray() {
		try {
			mEmptyIdentifierHashSet.toArray();
			fail("When implementing toArray(), please also implement this test for it!");
		} catch(UnsupportedOperationException e) {
			assertEquals("Not implemented yet.", e.getMessage());
		}
	}

	/** Tests {@link plugins.WebOfTrust.util.IdentifierHashSet#toArray(T[])}. */
	@Test public final void testToArrayTArray() {
		try {
			mEmptyIdentifierHashSet.toArray(new Persistent[1]);
			fail("When implementing toArray(), please also implement this test for it!");
		} catch(UnsupportedOperationException e) {
			assertEquals("Not implemented yet.", e.getMessage());
		}
	}

	/** Tests {@link plugins.WebOfTrust.util.IdentifierHashSet#hashCode()}. */
	@Test public final void testHashCode() {
		final int emptyHashCode = mEmptyIdentifierHashSet.hashCode();
		
		assertEquals(emptyHashCode, mEmptyIdentifierHashSet.hashCode()); // Idempotent?
		
		for(int i=0; i < mUniques.size(); ++i) {
			List<? extends Persistent> uniques = mUniques.get(i);
			List<? extends Persistent> otherUniques = mOtherUniques.get(i);
			List<? extends Persistent> duplicates = mDuplicates.get(i);
			IdentifierHashSet<Persistent> h1 = new IdentifierHashSet<Persistent>();
			IdentifierHashSet<Persistent> h2 = new IdentifierHashSet<Persistent>();
			
			assertEquals(emptyHashCode, h1.hashCode());
			assertEquals(emptyHashCode, h2.hashCode());
			assertTrue(h1.addAll(uniques));
			assertTrue(h2.addAll(duplicates));
			assertNotEquals(emptyHashCode, h1.hashCode());
			assertNotEquals(emptyHashCode, h2.hashCode());
			assertEquals(h1.hashCode(), h2.hashCode());
			
			h1.remove(otherUniques.get(0));
			assertEquals(h1.hashCode(), h2.hashCode());
			
			h1.remove(duplicates.get(0));
			assertNotEquals(h1.hashCode(), h2.hashCode());
			
			h1.add(duplicates.get(0));
			assertEquals(h1.hashCode(), h2.hashCode());
			
			IdentifierHashSet<Persistent> h3 = new IdentifierHashSet<Persistent>();
			h3.addAll(h1);
			assertEquals(h1.hashCode(), h3.hashCode());
			h3.addAll(h2);
			assertEquals(h1.hashCode(), h3.hashCode());
		}
		
		try {
			mEmptyIdentifierHashSet.add(null);
			fail("Adding null should not be allowed");
		} catch(NullPointerException e) {
			assertEquals(emptyHashCode, mEmptyIdentifierHashSet.hashCode());
		}
		
		try {
			mEmptyIdentifierHashSet.remove(null);
			fail("Removing null should not be allowed");
		} catch(NullPointerException e) {
			assertEquals(emptyHashCode, mEmptyIdentifierHashSet.hashCode());
		}
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
