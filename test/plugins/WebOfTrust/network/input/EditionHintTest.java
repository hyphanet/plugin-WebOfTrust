/* This code is part of WoT, a plugin for Freenet. It is distributed
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.network.input;

import static java.lang.Integer.signum;
import static java.util.Collections.sort;
import static java.util.concurrent.TimeUnit.HOURS;
import static org.junit.Assert.*;

import java.io.File;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;

import org.junit.Before;
import org.junit.Test;

import plugins.WebOfTrust.AbstractJUnit4BaseTest;
import plugins.WebOfTrust.Identity;
import plugins.WebOfTrust.WebOfTrust;
import plugins.WebOfTrust.exceptions.InvalidParameterException;
import plugins.WebOfTrust.exceptions.UnknownIdentityException;
import freenet.keys.FreenetURI;
import freenet.support.CurrentTimeUTC;

/** Tests {@link EditionHint}. */
public class EditionHintTest extends AbstractJUnit4BaseTest {

	private WebOfTrust mWebOfTrust;


	@Before public void setUp() {
		mWebOfTrust = constructEmptyWebOfTrust();
	}

	/**
	 * Tests {@link EditionHint#encryptIdentityID(WebOfTrust, String)} and
	 * {@link EditionHint#decryptIdentityID(WebOfTrust, String)}.
	 * 
	 * (As a consequence {@link #testDencryptIdentityIDWebOfTrustString()} is empty.) */
	@Test public final void testEncryptIdentityIDWebOfTrustString() throws UnknownIdentityException,
			MalformedURLException, InvalidParameterException {
		
		File database;
		FreenetURI requestURI;
		String id;
		String encryptedID;
		// Test basic encrypt/decrypt cycle
		{
			database = mWebOfTrust.getDatabaseFile();
			Identity identity = addRandomIdentities(1).get(0);
			requestURI = identity.getRequestURI();
			
			id = identity.getID();
			encryptedID = EditionHint.encryptIdentityID(mWebOfTrust, id);
			String decryptedID = EditionHint.decryptIdentityID(mWebOfTrust, encryptedID);
			
			assertNotEquals(id, encryptedID);
			assertEquals(id, decryptedID);
			
			mWebOfTrust.terminate();
			mWebOfTrust = null;
		}
		
		// Test whether decryption works across restarts
		{
			mWebOfTrust = new WebOfTrust(database.toString());
			Identity identity = mWebOfTrust.getIdentityByURI(requestURI);
			assertEquals(id, identity.getID());
			
			assertEquals(identity.getID(), EditionHint.decryptIdentityID(mWebOfTrust, encryptedID));
			
			// Bonus checks: Encryption should also work the same
			assertEquals(encryptedID, EditionHint.encryptIdentityID(mWebOfTrust, identity.getID()));
			
			mWebOfTrust.terminate();
			mWebOfTrust = null;
		}
		
		// Test whether encryption with a different WebOfTrust instance uses a different key as it
		// is supposed to.
		{
			mWebOfTrust = constructEmptyWebOfTrust();
			Identity identity = mWebOfTrust.addIdentity(requestURI.toString());
			assertEquals(id, identity.getID());
			
			assertNotEquals(encryptedID,
				EditionHint.encryptIdentityID(mWebOfTrust, identity.getID()));
		}
	}

	/**
	 * Does nothing:
	 * {@link EditionHint#decryptIdentityID(WebOfTrust, String)} is implicitly tested by 
	 * {@link #testEncryptIdentityIDWebOfTrustString()} as well. */
	@Test public final void testDecryptIdentityIDWebOfTrustString() {
		
	}

	/**
	 * Tests {@link EditionHint#compareTo(EditionHint)} against
	 * {@link EditionHint#compareTo_ReferenceImplementation(EditionHint)}.
	 */
	@Test public final void testCompareTo() {
		
		// EditionHints consume the following input data in the constructor:
		//     sourceIdentityID, targetIdentityID, date, sourceCapacity, sourceScore, edition.
		// Comparison should be upon:
		//     date, sourceCapacity, sourceScore, targetIdentityID, edition
		// We check sorting both for varying input values of the sorting keys as well as for
		// changes upon irrelevant values.
		
		// First step: Set up sample values for all input parameters of the constructor.
		
		ArrayList<Identity> sourceIdentities = addRandomIdentities(3);
		ArrayList<Identity> targetIdentities = addRandomIdentities(3);
		
		Date[] dates = new Date[3*3];
		Date now = CurrentTimeUTC.get();
		for(int i=0; i < 3*3; i++) {
			// Use steps of one third of a day to get multiple entries for the same day:
			// compareTo() sorting should be based upon the dates rounded to the day, hours should
			// be irrelevant. So we want collisions of days.
			dates[i] = new Date(now.getTime() - HOURS.toMillis(i * 24/3));
		}
		
		int[] capacities = WebOfTrust.capacities;
		// Scores should be rounded to {-1, 1} depending on whether they're positive/negative, so
		// we need collisions on that as well.
		int[] scores = { -Integer.MAX_VALUE, -2, -1, 0, 1, 2, Integer.MAX_VALUE };
		long[] editions = { 0, 1, 2, 3, Long.MAX_VALUE };
		
		int potentialCombinations = 
			  targetIdentities.size() * dates.length * capacities.length * scores.length
			* editions.length;
		
		ArrayList<EditionHint> unsorted = new ArrayList<>(potentialCombinations);
		
		// Step 2: Sort EditionHints of which only the fields relevant for sorting are varied.
		
		for(Identity targetIdentity : targetIdentities) {
			for(Date date : dates) {
				for(int sourceCapacity : capacities) {
					for(int sourceScore : scores) {
						for(long edition : editions) {
							EditionHint h =  EditionHint.constructSecure(
								mWebOfTrust,
								sourceIdentities.get(0).getID(),
								targetIdentity.getID(),
								date,
								sourceCapacity,
								sourceScore,
								edition
							);
							h.initializeTransient(mWebOfTrust);
							unsorted.add(h);
						}
					}
				}
			}
		}
		
		assert(unsorted.size() == potentialCombinations);
		
		ArrayList<EditionHint> sortedWithReference1 = new ArrayList<>(unsorted);
		ArrayList<EditionHint> sortedWithCompareTo1 = new ArrayList<>(unsorted);
		
		sort(sortedWithReference1, new Comparator<EditionHint>() {
			@Override public int compare(EditionHint o1, EditionHint o2) {
				return o1.compareTo_ReferenceImplementation(o2);
			}
		});
		
		sort(sortedWithCompareTo1, new Comparator<EditionHint>() {
			@Override public int compare(EditionHint o1, EditionHint o2) {
				return o1.compareTo(o2);
			}
		});
		
		assertEquals(sortedWithReference1, sortedWithCompareTo1);
		
		// Testing sorting actually is not perfectly strict: It may not hit all combinations of
		// compareTo() and thus in testing did not catch all bugs.
		// So now we test compareTo() upon all possible combinations of two of our EditionHint
		// objects.
		for(EditionHint h1 : unsorted) {
			for(EditionHint h2 : unsorted) {
				// Optimized underlying compareTo() implementations may not always return -1 or 1 in
				// the case of smaller/greater but for example the difference of the first
				// differing character of a String, see String.compareTo(). Thus use signum().
				assertEquals(
					signum(h1.compareTo_ReferenceImplementation(h2)),
					signum(h1.compareTo(h2)));
			}
		}
		
		// Step 3: Vary the fields which are irrelevant for sorting (= only the sourceIdentity
		// field) and check whether the sorting is the same as of they were constant

		// Outer loop = Vary fields which *are* relevant
		for(Identity targetIdentity : targetIdentities) {
			for(Date date : dates) {
				for(int sourceCapacity : capacities) {
					for(int sourceScore : scores) {
						for(long edition : editions) {
							EditionHint previous = null;
							
							// Inner loop = Vary fields which are not relevant
							for(Identity sourceIdentity : sourceIdentities) {
								EditionHint h =  EditionHint.constructSecure(
									mWebOfTrust,
									sourceIdentity.getID(),
									targetIdentity.getID(),
									date,
									sourceCapacity,
									sourceScore,
									edition
								);
								h.initializeTransient(mWebOfTrust);
								
								if(previous != null) {
									// compareTo() should say all are equal for the whole chain
									assertEquals(0, previous.compareTo_ReferenceImplementation(h));
									assertEquals(0, previous.compareTo(h));
								}
								
								previous = h;
							}
						}
					}
				}
			}
		}
	}

	@Override
	protected WebOfTrust getWebOfTrust() {
		return mWebOfTrust;
	}

}
