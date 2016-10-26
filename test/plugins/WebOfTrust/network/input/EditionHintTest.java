/* This code is part of WoT, a plugin for Freenet. It is distributed
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.network.input;

import static java.lang.Integer.signum;
import static java.util.Collections.sort;
import static java.util.concurrent.TimeUnit.HOURS;
import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;

import org.junit.Before;
import org.junit.Test;

import plugins.WebOfTrust.AbstractJUnit4BaseTest;
import plugins.WebOfTrust.Identity;
import plugins.WebOfTrust.WebOfTrust;
import freenet.support.CurrentTimeUTC;

/** Tests {@link EditionHint}. */
public class EditionHintTest extends AbstractJUnit4BaseTest {

	private WebOfTrust mWebOfTrust;


	@Before public void setUp() {
		mWebOfTrust = constructEmptyWebOfTrust();
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
			dates[i] = new Date(now.getTime() - HOURS.toMillis(24 / 3));
		}
		
		int[] capacities = WebOfTrust.capacities;
		// Scores should be rounded to {-1, 1} depending on whether they're positive/negative, so
		// we need collisions on that as well.
		int[] scores = { -Integer.MAX_VALUE, -2, -1, 0, 1, 2, Integer.MAX_VALUE };
		long[] editions = { 0, 1, 2, 3, Long.MAX_VALUE };
		
		int potentialCombinations = 
			  sourceIdentities.size() * targetIdentities.size() * dates.length * capacities.length
				* scores.length * editions.length;
		
		ArrayList<EditionHint> unsorted = new ArrayList<>(potentialCombinations);
		
		// Step 2: Sort EditionHints of which only the fields relevant for sorting are varied.
		
		for(Identity targetIdentity : targetIdentities) {
			for(Date date : dates) {
				for(int sourceCapacity : capacities) {
					for(int sourceScore : scores) {
						for(long edition : editions) {
							EditionHint h =  EditionHint.constructSecure(
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
		
		// FIXME: Add EditionHints with varying irrelevant parameters and test whether this doesn't
		// change the sorting.
	}

	@Override
	protected WebOfTrust getWebOfTrust() {
		return mWebOfTrust;
	}

}
