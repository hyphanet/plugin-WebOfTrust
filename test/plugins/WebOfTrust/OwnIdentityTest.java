/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import static plugins.WebOfTrust.WebOfTrustInterface.WOT_NAME;
import static plugins.WebOfTrust.util.DateUtil.waitUntilCurrentTimeUTCIsAfter;

import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Date;

import plugins.WebOfTrust.Identity.FetchState;
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
		String uri = "SSK@ZTeIa1g4T3OYCdUFfHrFSlRnt5coeFFDCIZxWSb7abs,ZP4aASnyZax8nYOvCOlUebegsmbGQIXfVzw7iyOsXEc,AQECAAE/";
		String nickname = getRandomLatinString(OwnIdentity.MAX_NICKNAME_LENGTH);
		boolean publishesTrustList = true;
		
		OwnIdentity identity = new OwnIdentity(mWoT, uri, nickname, publishesTrustList);
		
		assertSame(mWoT, identity.getWebOfTrust());
		
		FreenetURI wotURI
			= new FreenetURI(uri).setKeyType("USK").setDocName(WOT_NAME).setSuggestedEdition(0);
		FreenetURI requestURI = wotURI.deriveRequestURIFromInsertURI();
		assertEquals(wotURI, identity.getInsertURI());
		assertEquals(requestURI, identity.getRequestURI());
		
		assertEquals(0, identity.getNextEditionToInsert());
		// The natural state of an OwnIdentity is to have its next edition to be inserted always be
		// marked as FetchState.Fetched before it is even inserted and thus couldn't really have
		// been fetched yet. This ensures multiple things:
		// - in the case of not even have inserted the first edition 0 yet, it makes sense because
		//   the OwnIdentity does exist locally already and contains data. Consider this especially
		//   from the perspective of another local user which views the OwnIdentity as a non-own
		//   one: He'll see a nickname, trust values, etc., and they couldn't be known if not a
		//   single edition was fetched yet.
		// - the IdentityDownloader might download the edition once we insert it. Having marked it
		//   as fetched already before ensures it won't be imported. And that ensures that we don't
		//   overwrite the trust list with the shortened version which was published on the network
		//   (there is a size limit so trust values might be left out).
		// - To distinguish an OwnIdentity which is pending to be restored from a normal one, the
		//   ones which are being restored are having their current edition marked as NotFetched,
		//   and the normal ones as Fetched. This also makes sense: Restoring is the same as not
		//   having fetched the identity yet and wanting to do so.
		assertEquals(FetchState.Fetched, identity.getCurrentEditionFetchState());
		assertEquals(0, identity.getLastFetchedEdition());
		assertEquals(0, identity.getLastFetchedMaybeValidEdition());
		assertEquals(1, identity.getNextEditionToFetch());
		
		assertEquals(0, identity.getLatestEditionHint());
		
		assertEquals(nickname, identity.getNickname());
		
		assertEquals(publishesTrustList, identity.doesPublishTrustList());
		
		// TODO: Code quality: Test the other constructor(s), currently only tested implicitly by
		// being called by the one we just tested.
		// TODO: Code quality: Test with different / invalid parameters
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
		
		FreenetURI rawInsertURI  = new FreenetURI(insertURIStringUSK);
		FreenetURI rawRequestURI = new FreenetURI(requestURIStringUSK);
		
		// The editions of the URIs we provide to the constructor must be ignored for security
		// reasons, see the JavaDoc of the constructor for why this is the case.
		assert(rawInsertURI.getEdition()  == 23);
		assert(rawRequestURI.getEdition() == 23);
		FreenetURI expectedInsertURI  = rawInsertURI.setSuggestedEdition(0);
		FreenetURI expectedRequestURI = rawRequestURI.setSuggestedEdition(0);
		
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
		
		// Besides ensuring non-default values for the various getters, we ensure that all Dates
		// are different so we can test for them being mixed up. We also ensure that
		// CurrentTimeUTC.get() is in the past so we can detect if a Date is re-initialized instead
		// of being copied.
		
		waitUntilCurrentTimeUTCIsAfter(original.getCreationDate());
		original.onInserted(9);
		assertEquals(9, original.getLastFetchedEdition());
		assertEquals(9, original.getLatestEditionHint());
		assertTrue(original.getLastInsertDate().after(original.getCreationDate()));
		
		waitUntilCurrentTimeUTCIsAfter(original.getLastInsertDate());
		original.onFetchedAndParsedSuccessfully(10);
		// FIXME: Code quality: Add OwnIdentity.getLastInsertedEdition() to make this more obvious.
		// Then also wire it in to class IdentityInserter.
		assertEquals(10, original.getLastFetchedEdition());
		assertEquals(10, original.getLatestEditionHint());
		assertTrue(original.getLastFetchedDate().after(original.getLastInsertDate()));
		
		waitUntilCurrentTimeUTCIsAfter(original.getLastFetchedDate());
		original.updated();
		assertTrue(original.getLastChangeDate().after(original.getLastFetchedDate()));
		
		waitUntilCurrentTimeUTCIsAfter(original.getLastChangeDate());
		
        original.addContext(getRandomLatinString(Identity.MAX_CONTEXT_NAME_LENGTH));
        original.setProperty(getRandomLatinString(Identity.MAX_PROPERTY_NAME_LENGTH),
                             getRandomLatinString(Identity.MAX_PROPERTY_VALUE_LENGTH));
		
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

	/**
	 * Tests {@link OwnIdentity#getNextEditionToInsert()}.
	 * Also tests {@link Identity#getNextEditionToFetch()} which we inherited from the parent class:
	 * In the parent class it is about the request URI, but OwnIdentity also has the insert URI and
	 * edition. As the parent function isn't aware of that we should check whether getNextEdition()
	 * still returns reasonable information in the context of looking at an OwnIdentity. */
	public void testGetNextEditionToInsert()
			throws MalformedURLException, InvalidParameterException, InterruptedException {
		
		OwnIdentity o = addRandomOwnIdentities(1).get(0);
		
		// Test state before first insert
		
		assertTrue(o.needsInsert());
		assertFalse(o.isRestoreInProgress());
		// No restore in progress -> Don't re-import what we will insert, mark it as fetched already
		// Or from the perspective of other local OwnIdentity identities, i.e. viewing this local
		// OwnIdentity as a non-own remote one:
		// Inserts of this OwnIdentity should be transparent to the other ones, it should look as
		// if the fetch has happened before the insert already. (Because we obviously *have* the
		// data already since it is the user's input.)
		assertEquals(0, o.getLastFetchedEdition());
		assertEquals(FetchState.Fetched, o.getCurrentEditionFetchState());
		assertEquals(o.getCreationDate(), o.getLastFetchedDate());
		assertEquals(new Date(0), o.getLastInsertDate());
		assertEquals(0, o.getNextEditionToInsert());
		assertEquals(1, o.getNextEditionToFetch());
		
		// Test state after insert of edition 0
		
		Date approxInsertDate = CurrentTimeUTC.get();
		o.onInserted(0);
		assertFalse(o.needsInsert());
		assertFalse(o.isRestoreInProgress());
		assertEquals(0, o.getLastFetchedEdition());
		assertEquals(FetchState.Fetched, o.getCurrentEditionFetchState());
		assertTrue(o.getLastFetchedDate().getTime() == o.getLastInsertDate().getTime());
		assertTrue(o.getLastInsertDate().getTime() >= approxInsertDate.getTime());
		try {
			o.getNextEditionToInsert();
			fail("Should throw before getLastChangeDate().after(getLastInsertDate())!");
		} catch(IllegalStateException e) {}
		waitUntilCurrentTimeUTCIsAfter(o.getLastInsertDate());
		o.updated();
		assertTrue(o.needsInsert());
		assertEquals(1, o.getNextEditionToInsert());
		assertEquals(1, o.getNextEditionToFetch());
		
		// Test state after insert of edition 1
		
		Date previousInsertDate = o.getLastInsertDate();
		waitUntilCurrentTimeUTCIsAfter(previousInsertDate);
		o.onInserted(1);
		assertFalse(o.needsInsert());
		assertFalse(o.isRestoreInProgress());
		assertEquals(1, o.getLastFetchedEdition());
		assertEquals(FetchState.Fetched, o.getCurrentEditionFetchState());
		assertTrue(o.getLastFetchedDate().getTime() == o.getLastInsertDate().getTime());
		assertTrue(o.getLastInsertDate().getTime()  >= previousInsertDate.getTime());
		try {
			o.getNextEditionToInsert();
			fail("Should throw before getLastChangeDate().after(getLastInsertDate())!");
		} catch(IllegalStateException e) {}
		waitUntilCurrentTimeUTCIsAfter(o.getLastInsertDate());
		o.updated();
		assertTrue(o.needsInsert());
		assertEquals(2, o.getNextEditionToInsert());
		assertEquals(2, o.getNextEditionToFetch());

		// Test what happens if we suddenly *fetch* an edition of the OwnIdentity now.
		// This could happen if the USK code supplies us with more than one edition when restoring,
		// or if the user (for whatever reason) runs multiple WoT instances with the same identity.
		
		try {
			o.onFetchedAndParsedSuccessfully(1);
			fail("Shouldn't accept an edition we already inserted!");
		} catch(IllegalStateException e) {}


		previousInsertDate = o.getLastInsertDate();
		waitUntilCurrentTimeUTCIsAfter(previousInsertDate);
		o.onFetchedAndParsedSuccessfully(2);
		// onFetchedAndParsedSuccessfully() calls updated(), which means needsInsert() will always
		// return true here.
		// FIXME: This means that every restore of an OwnIdentity will cause an insert right after
		// the restore - Should we prevent that? Also see the below FIXME. 
		assertTrue(o.needsInsert());
		assertFalse(o.isRestoreInProgress());
		assertEquals(2, o.getLastFetchedEdition());
		assertEquals(FetchState.Fetched, o.getCurrentEditionFetchState());
		assertTrue(o.getLastFetchedDate().getTime() >= previousInsertDate.getTime());
		// FIXME: onFetchedAndParsedSuccessfully() does not currently update the getLastInsertDate()
		// Like the above FIXME, this may cause a reinsert after a restore.
		assertTrue(o.getLastInsertDate().getTime() == previousInsertDate.getTime());
		assertEquals(3, o.getNextEditionToInsert());
		assertEquals(3, o.getNextEditionToFetch());
	}
}
