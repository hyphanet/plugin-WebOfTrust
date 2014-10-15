/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import java.net.MalformedURLException;
import java.util.ArrayList;

import plugins.WebOfTrust.exceptions.InvalidParameterException;
import freenet.keys.FreenetURI;
import freenet.support.CurrentTimeUTC;

/**
 * @author xor (xor@freenetproject.org)
 */
public class OwnIdentityTest extends AbstractJUnit3BaseTest {
	
	private final String requestURIStringUSK = "USK@sdFxM0Z4zx4-gXhGwzXAVYvOUi6NRfdGbyJa797bNAg,ZP4aASnyZax8nYOvCOlUebegsmbGQIXfVzw7iyOsXEc,AQACAAE/WebOfTrust/23";
	private final String requestURIStringUSKNonWOT = "USK@sdFxM0Z4zx4-gXhGwzXAVYvOUi6NRfdGbyJa797bNAg,ZP4aASnyZax8nYOvCOlUebegsmbGQIXfVzw7iyOsXEc,AQACAAE/Test/23";
	private final String requestURIStringSSK = "SSK@sdFxM0Z4zx4-gXhGwzXAVYvOUi6NRfdGbyJa797bNAg,ZP4aASnyZax8nYOvCOlUebegsmbGQIXfVzw7iyOsXEc,AQACAAE/WebOfTrust-23";
	private final String requestURIStringSSKNonWOT = "SSK@sdFxM0Z4zx4-gXhGwzXAVYvOUi6NRfdGbyJa797bNAg,ZP4aASnyZax8nYOvCOlUebegsmbGQIXfVzw7iyOsXEc,AQACAAE/Test-23";
	private final String requestURIStringSSKPlain = "SSK@sdFxM0Z4zx4-gXhGwzXAVYvOUi6NRfdGbyJa797bNAg,ZP4aASnyZax8nYOvCOlUebegsmbGQIXfVzw7iyOsXEc,AQACAAE/";
	private final String insertURIStringUSK = "USK@ZTeIa1g4T3OYCdUFfHrFSlRnt5coeFFDCIZxWSb7abs,ZP4aASnyZax8nYOvCOlUebegsmbGQIXfVzw7iyOsXEc,AQECAAE/WebOfTrust/23";
	private final String insertURIStringUSKNonWOT = "USK@ZTeIa1g4T3OYCdUFfHrFSlRnt5coeFFDCIZxWSb7abs,ZP4aASnyZax8nYOvCOlUebegsmbGQIXfVzw7iyOsXEc,AQECAAE/Test/23";
	private final String insertURIStringSSK = "SSK@ZTeIa1g4T3OYCdUFfHrFSlRnt5coeFFDCIZxWSb7abs,ZP4aASnyZax8nYOvCOlUebegsmbGQIXfVzw7iyOsXEc,AQECAAE/WebOfTrust-23";
	private final String insertURIStringSSKNonWOT = "SSK@ZTeIa1g4T3OYCdUFfHrFSlRnt5coeFFDCIZxWSb7abs,ZP4aASnyZax8nYOvCOlUebegsmbGQIXfVzw7iyOsXEc,AQECAAE/Test-23";
	private final String insertURIStringSSKPlain = "SSK@ZTeIa1g4T3OYCdUFfHrFSlRnt5coeFFDCIZxWSb7abs,ZP4aASnyZax8nYOvCOlUebegsmbGQIXfVzw7iyOsXEc,AQECAAE/";

	public void testConstructors() throws MalformedURLException, InvalidParameterException {
		final OwnIdentity identity = new OwnIdentity(mWoT, "SSK@ZTeIa1g4T3OYCdUFfHrFSlRnt5coeFFDCIZxWSb7abs,ZP4aASnyZax8nYOvCOlUebegsmbGQIXfVzw7iyOsXEc,AQECAAE/",
				getRandomLatinString(OwnIdentity.MAX_NICKNAME_LENGTH), true);
		
		assertEquals(0, identity.getEdition());
		assertEquals(0, identity.getLatestEditionHint());
	}

	public void testConstructorURIProcessing() throws MalformedURLException, InvalidParameterException {
		// Test: Constructors shouldn't accept request URIs
		
		String[] inacceptableURIs = new String[] {
				requestURIStringUSK,
				requestURIStringUSKNonWOT,
				requestURIStringSSK,
				requestURIStringSSKNonWOT,
				requestURIStringSSKPlain
		};
		
		for(String uri : inacceptableURIs) {
			try {
				new OwnIdentity(mWoT, uri, "test", true);
				fail("OwnIdentity should only construct with insert URIs");
			} catch(MalformedURLException e) {}
		}
		
		// Test: Constructors should normalize all different kinds of insert URIs
		
		ArrayList<OwnIdentity> identities = new ArrayList<OwnIdentity>(5 + 1);
		
		identities.add(new OwnIdentity(mWoT, insertURIStringUSK, "test", true));
		identities.add(new OwnIdentity(mWoT, insertURIStringUSKNonWOT, "test", true));
		identities.add(new OwnIdentity(mWoT, insertURIStringSSK, "test", true));
		identities.add(new OwnIdentity(mWoT, insertURIStringSSKNonWOT, "test", true));
		identities.add(new OwnIdentity(mWoT, insertURIStringSSKPlain, "test", true));
		
		FreenetURI expectedInsertURI = new FreenetURI(insertURIStringUSK).setSuggestedEdition(0);
		FreenetURI expectedRequestURI = new FreenetURI(requestURIStringUSK).setSuggestedEdition(0);
		
		for(OwnIdentity identity : identities) {
			assertEquals(expectedInsertURI, identity.getInsertURI());
			assertEquals(expectedRequestURI, identity.getRequestURI());
		}
	}

	/**
	 * Tests whether {@link OwnIdentity.clone()} returns an OwnIdentity:
	 * - which {@link equals()} the original.
	 * - which is not the same object.
	 * - which meets the requirements of {@link AbstractJUnit3BaseTest#testClone(Class, Object, Object)}
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
		
		testClone(Persistent.class, original, clone);
		testClone(Identity.class, original, clone);
		testClone(OwnIdentity.class, original, clone);
	}
	
	public void testSerializeDeserialize() throws MalformedURLException, InvalidParameterException {
		final OwnIdentity original = new OwnIdentity(mWoT, getRandomSSKPair()[0], getRandomLatinString(OwnIdentity.MAX_NICKNAME_LENGTH), true);
		final OwnIdentity deserialized = (OwnIdentity)Persistent.deserialize(mWoT, original.serialize());
		
		assertNotSame(original, deserialized);
		assertEquals(original, deserialized);
	}
}
