/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import java.net.MalformedURLException;

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
	 * Tests whether {@link OwnIdentity.clone()} returns an OwnIdentity which {@link equals()} the original.
	 */
	public void testClone() throws MalformedURLException, InvalidParameterException {
		final OwnIdentity original = new OwnIdentity(mWoT, getRandomSSKPair()[0], getRandomLatinString(OwnIdentity.MAX_NICKNAME_LENGTH), true);
		final OwnIdentity clone = original.clone();
		
		assertEquals(original, clone);
	}
}
