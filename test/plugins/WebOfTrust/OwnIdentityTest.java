/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import java.net.MalformedURLException;

import freenet.support.CurrentTimeUTC;

import plugins.WebOfTrust.exceptions.InvalidParameterException;

/**
 * @author xor (xor@freenetproject.org)
 */
public class OwnIdentityTest extends DatabaseBasedTest {
	
	public void testConstructors() throws MalformedURLException, InvalidParameterException {
		final OwnIdentity identity = new OwnIdentity(mWoT, "SSK@ZTeIa1g4T3OYCdUFfHrFSlRnt5coeFFDCIZxWSb7abs,ZP4aASnyZax8nYOvCOlUebegsmbGQIXfVzw7iyOsXEc,AQECAAE/",
				getRandomLatinString(OwnIdentity.MAX_NICKNAME_LENGTH), true);
		
		assertEquals(0, identity.getEdition());
		assertEquals(0, identity.getLatestEditionHint());
	}

	/**
	 * Tests whether {@link OwnIdentity.clone()} returns an OwnIdentity:
	 * - which {@link equals()} the original.
	 * - which is not the same object.
	 * - which meets the requirements of {@link DatabaseBasedTest#testClone(Class, Object, Object)}
	 */
	public void testClone() throws MalformedURLException, InvalidParameterException, InterruptedException, IllegalArgumentException, IllegalAccessException {
		final OwnIdentity original = new OwnIdentity(mWoT, getRandomSSKPair()[0], getRandomLatinString(OwnIdentity.MAX_NICKNAME_LENGTH), true);
		original.setEdition(10); // Make sure to use a non-default edition
		original.setNewEditionHint(10); // Make sure to use a non-default edition hint
		original.updateLastInsertDate();
		
		Thread.sleep(10); // Identity contains Date mLastChangedDate which might not get properly cloned.
		assertFalse(CurrentTimeUTC.get().equals(original.getLastChangeDate()));
		
		final OwnIdentity clone = original.clone();
		
		assertEquals(original, clone);
		assertNotSame(original, clone);
		
		/* Would fail because we don't clone the mCreationDate and I don't think its necessary to do so */
		// testClone(Persistent.class, original, clone);
		testClone(Identity.class, original, clone);
		testClone(OwnIdentity.class, original, clone);
	}
}
