/* This code is part of WoT, a plugin for Freenet. It is distributed
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import static org.junit.Assert.*;

import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.Before;
import org.junit.Test;

import plugins.WebOfTrust.exceptions.InvalidParameterException;
import plugins.WebOfTrust.exceptions.UnknownIdentityException;
import plugins.WebOfTrust.util.WOTUtil;
import freenet.support.TimeUtil;

public final class ScoreComputationBenchmark extends AbstractFullNodeTest {

	private static final int OWN_IDENTITY_COUNT = 1;

	private static final int IDENTITY_COUNT = 11985;

	/**
	 * For shorter execution time, reduce the copy of this in {@link #benchmark()} instead of this
	 * to not break {@link #getTrustDistribution()}. */
	private static final int TRUST_COUNT = 222122;

	/**
	 * Dataset was obtained using WOTUtil Trust histogram from a network dump of 2015-07-23 with:
	 * Identities: 11985
	 * Not fetched identities: 183
	 * Trusts: 222122
	 * 
	 * ATTENTION: When updating this, also update:
	 * {@link #IDENTITY_COUNT}
	 * {@link #TRUST_COUNT} */
	private static final int[][] TRUST_DISTRIBUTION =
		new int[][] {
			{ -100, 423 },
			{ -99, 1 },
			{ -98, 0 },
			{ -97, 0 },
			{ -96, 0 },
			{ -95, 0 },
			{ -94, 0 },
			{ -93, 0 },
			{ -92, 0 },
			{ -91, 0 },
			{ -90, 1 },
			{ -89, 0 },
			{ -88, 0 },
			{ -87, 0 },
			{ -86, 0 },
			{ -85, 0 },
			{ -84, 0 },
			{ -83, 0 },
			{ -82, 0 },
			{ -81, 0 },
			{ -80, 3 },
			{ -79, 0 },
			{ -78, 0 },
			{ -77, 0 },
			{ -76, 0 },
			{ -75, 1 },
			{ -74, 0 },
			{ -73, 0 },
			{ -72, 0 },
			{ -71, 0 },
			{ -70, 0 },
			{ -69, 0 },
			{ -68, 0 },
			{ -67, 0 },
			{ -66, 0 },
			{ -65, 0 },
			{ -64, 0 },
			{ -63, 0 },
			{ -62, 0 },
			{ -61, 0 },
			{ -60, 0 },
			{ -59, 0 },
			{ -58, 0 },
			{ -57, 0 },
			{ -56, 0 },
			{ -55, 1 },
			{ -54, 0 },
			{ -53, 0 },
			{ -52, 0 },
			{ -51, 0 },
			{ -50, 10 },
			{ -49, 0 },
			{ -48, 0 },
			{ -47, 0 },
			{ -46, 0 },
			{ -45, 0 },
			{ -44, 0 },
			{ -43, 0 },
			{ -42, 0 },
			{ -41, 0 },
			{ -40, 4 },
			{ -39, 0 },
			{ -38, 0 },
			{ -37, 0 },
			{ -36, 0 },
			{ -35, 0 },
			{ -34, 0 },
			{ -33, 2 },
			{ -32, 0 },
			{ -31, 0 },
			{ -30, 6 },
			{ -29, 0 },
			{ -28, 0 },
			{ -27, 0 },
			{ -26, 0 },
			{ -25, 140 },
			{ -24, 0 },
			{ -23, 0 },
			{ -22, 0 },
			{ -21, 0 },
			{ -20, 22 },
			{ -19, 2 },
			{ -18, 0 },
			{ -17, 0 },
			{ -16, 3 },
			{ -15, 0 },
			{ -14, 0 },
			{ -13, 0 },
			{ -12, 1 },
			{ -11, 1 },
			{ -10, 285 },
			{ -9, 2 },
			{ -8, 0 },
			{ -7, 0 },
			{ -6, 0 },
			{ -5, 7 },
			{ -4, 4 },
			{ -3, 1 },
			{ -2, 0 },
			{ -1, 445 },
			{ 0, 132876 },
			{ 1, 4520 },
			{ 2, 216 },
			{ 3, 52 },
			{ 4, 111 },
			{ 5, 92 },
			{ 6, 8 },
			{ 7, 6 },
			{ 8, 34 },
			{ 9, 13 },
			{ 10, 8760 },
			{ 11, 241 },
			{ 12, 80 },
			{ 13, 17 },
			{ 14, 19 },
			{ 15, 31 },
			{ 16, 18 },
			{ 17, 7 },
			{ 18, 13 },
			{ 19, 6 },
			{ 20, 1332 },
			{ 21, 62 },
			{ 22, 14 },
			{ 23, 9 },
			{ 24, 16 },
			{ 25, 433 },
			{ 26, 5 },
			{ 27, 1 },
			{ 28, 4 },
			{ 29, 3 },
			{ 30, 556 },
			{ 31, 26 },
			{ 32, 15 },
			{ 33, 14 },
			{ 34, 12 },
			{ 35, 78 },
			{ 36, 4 },
			{ 37, 3 },
			{ 38, 2 },
			{ 39, 1 },
			{ 40, 452 },
			{ 41, 7 },
			{ 42, 8 },
			{ 43, 1 },
			{ 44, 7 },
			{ 45, 21 },
			{ 46, 1 },
			{ 47, 1 },
			{ 48, 4 },
			{ 49, 3 },
			{ 50, 1348 },
			{ 51, 37 },
			{ 52, 9 },
			{ 53, 3 },
			{ 54, 8 },
			{ 55, 314 },
			{ 56, 2 },
			{ 57, 0 },
			{ 58, 3 },
			{ 59, 1 },
			{ 60, 365 },
			{ 61, 10 },
			{ 62, 2 },
			{ 63, 0 },
			{ 64, 3 },
			{ 65, 6 },
			{ 66, 9 },
			{ 67, 4 },
			{ 68, 0 },
			{ 69, 1 },
			{ 70, 239 },
			{ 71, 3 },
			{ 72, 3 },
			{ 73, 6 },
			{ 74, 3 },
			{ 75, 13392 },
			{ 76, 6 },
			{ 77, 141 },
			{ 78, 5 },
			{ 79, 0 },
			{ 80, 222 },
			{ 81, 6 },
			{ 82, 2 },
			{ 83, 1 },
			{ 84, 1 },
			{ 85, 38 },
			{ 86, 3 },
			{ 87, 0 },
			{ 88, 2 },
			{ 89, 0 },
			{ 90, 199 },
			{ 91, 9 },
			{ 92, 13 },
			{ 93, 0 },
			{ 94, 3 },
			{ 95, 17 },
			{ 96, 0 },
			{ 97, 0 },
			{ 98, 3 },
			{ 99, 20 },
			{ 100, 54080 }
	};

	@Before
	public void checkThatAssertionsAreDisabled() {
		assert(false)
			: "WOT has very sophisticated assertions which can impact performance a lot, so please "
			+ "disable them for all classes running these benchmarks. ";
	}

	@Test
	public void benchmark() throws MalformedURLException, InvalidParameterException,
			NumberFormatException, UnknownIdentityException {
		
		final int ownIdentityCount = OWN_IDENTITY_COUNT;
		final int identityCount = IDENTITY_COUNT / 10; // Reduce for shorter execution time.
		final int trustCount = TRUST_COUNT / (10*(10-1)); // Reduce for shorter execution time.
		
        assertTrue(trustCount < identityCount * (identityCount-1));
		
		WebOfTrust wot = getWebOfTrust();
		ArrayList<OwnIdentity> ownIds = new ArrayList<OwnIdentity>(ownIdentityCount + 1);
		ArrayList<Identity> ids = new ArrayList<Identity>(identityCount + ownIdentityCount + 1);
		ArrayList<Byte> trustDistribution;
		
		System.out.println("Creating " + ownIdentityCount + " OwnIdentitys ...");
		for(int i = 0; i < ownIdentityCount; ++i)
			ownIds.add(wot.createOwnIdentity(Integer.toString(i), true, null));
		
		ids.addAll(ownIds);
		
		System.out.println("Creating " + identityCount + " Identitys ...");
		for(int i = 0; i < identityCount; ++i)
			ids.add(wot.addIdentity(getRandomRequestURI().toString()));
		
		System.out.println("Computing trust distribution from " + TRUST_COUNT + " samples...");
		trustDistribution = getTrustDistribution();
		
		System.out.println("Setting " + trustCount + " Trusts...");
		long startTime = System.nanoTime();
		for(int i = 0; i < trustCount; ++i) {
			System.out.println("Setting trust " + i + " ...");
			
			int truster;
			int trustee;
			
			do {
				truster = mRandom.nextInt(identityCount);
				trustee = mRandom.nextInt(identityCount);
			} while(truster == trustee);
			
			wot.setTrust(
				ids.get(truster), ids.get(trustee), getRandomTrustValue(trustDistribution), "");
		}
		long endTime = System.nanoTime();
		long ms = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
		
		System.out.println("Total time: " + TimeUtil.formatTime(ms, 3, true));
		System.out.println("Full Score recomputations: "
			+ mWebOfTrust.getNumberOfFullScoreRecomputations());
		
		// Print Trust distribution histogram so you can check whether getTrustDistribution()
		// produces the same histogram as its internal backend histogram.
		WOTUtil.histogram(mWebOfTrust);
	}

	private byte getRandomTrustValue(ArrayList<Byte> trustDistribution) {
		return trustDistribution.get(mRandom.nextInt(trustDistribution.size()));
	}

	private static ArrayList<Byte> getTrustDistribution() {
		// Resulting trust values to meet the distribution.
		// If distribution says "100 occurrences of value 3", then value 3 will be added 100 times.
		ArrayList<Byte> result = new ArrayList<Byte>(TRUST_COUNT + 1);
		
		for(byte value = -100; value <= +100; ++value) {
			int index = value + 100;
			
			assertEquals(TRUST_DISTRIBUTION[index][0], value);
			
			int countOfOccurrencesOfValue = TRUST_DISTRIBUTION[index][1];
			
			while(countOfOccurrencesOfValue-- > 0)
				result.add(value);
		}
		
		// Return value is computed. Now test whether it is correct.
		
		assertEquals(TRUST_COUNT, result.size());
		
		int[] distribution = new int[201];
		
		for(Byte trust : result)
			++distribution[trust + 100];
		
		for(int value = -100; value <= +100; ++value) {
			int index = value + 100;
			assertEquals(TRUST_DISTRIBUTION[index][0], value);
			assertEquals(TRUST_DISTRIBUTION[index][1], distribution[index]);
		}
		
		return result;
	}

}
