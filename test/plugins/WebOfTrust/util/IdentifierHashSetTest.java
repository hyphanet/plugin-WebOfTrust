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

	List<List<? extends Persistent>> mUniques = new ArrayList<List<? extends Persistent>>();
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
		
		List<Identity> identityDuplicates = new ArrayList<Identity>(identities.size() * 2 + 1);
		List<Trust> trustDuplicates = new ArrayList<Trust>(trusts.size() * 2 + 1);
		
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
		}

		for(Trust t : trusts) {
			Trust clone = t.clone();
			Trust modifiedClone = t.clone();
			modifiedClone.forceSetTrusterEdition(t.getTrusterEdition() + 1);
			
			assertFalse(t.equals(modifiedClone));
			assertTrue(t.equals(clone));
			
			trustDuplicates.add(clone);
			trustDuplicates.add(modifiedClone);
		}
		
		mDuplicates.add(identityDuplicates);
		mDuplicates.add(trustDuplicates);
	}

	/** Tests {@link plugins.WebOfTrust.util.IdentifierHashSet#add(Persistent)}. */
	@Test public final void testAdd() {
		fail("Not yet implemented");
	}

	/** Tests {@link plugins.WebOfTrust.util.IdentifierHashSet#addAll(Collection)}. */
	@Test public final void testAddAll() {
		fail("Not yet implemented");
	}

	/** Tests {@link plugins.WebOfTrust.util.IdentifierHashSet#clear()}. */
	@Test public final void testClear() {
		fail("Not yet implemented");
	}

	/** Tests {@link plugins.WebOfTrust.util.IdentifierHashSet#contains(Object)}. */
	@Test public final void testContains() {
		fail("Not yet implemented");
	}

	/** Tests {@link plugins.WebOfTrust.util.IdentifierHashSet#containsAll(Collection)}. */
	@Test public final void testContainsAll() {
		fail("Not yet implemented");
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
