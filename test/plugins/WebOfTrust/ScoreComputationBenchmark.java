/* This code is part of WoT, a plugin for Freenet. It is distributed
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import static org.junit.Assert.*;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;

import org.junit.Before;
import org.junit.Test;

import plugins.WebOfTrust.exceptions.DuplicateTrustException;
import plugins.WebOfTrust.exceptions.InvalidParameterException;
import plugins.WebOfTrust.exceptions.NotTrustedException;
import plugins.WebOfTrust.exceptions.UnknownIdentityException;
import plugins.WebOfTrust.ui.terminal.WOTUtil;
import plugins.WebOfTrust.util.StopWatch;

public final class ScoreComputationBenchmark extends AbstractFullNodeTest {

	/**
	 * ATTENTION: File will not be deleted, it will be appended to so you can average multiple runs
	 * using the "smooth" option:<br>
	 * <code>
	 * gnuplot<br>
	 * > plot "ScoreComputationBenchmark.gnuplot" smooth unique
	 * </code>
	 */
	private static final File GNUPLOT_OUTPUT = new File("ScoreComputationBenchmark.gnuplot");

	/**
	 * Central parameter for tweaking size of benchmark data set.
	 * The amount of trusts will be computed from this and {@link #BENCHMARK_OWN_IDENTITY_COUNT}. */
	private static final int BENCHMARK_IDENTITY_COUNT = 1000;

	/**
	 * Parameter for tweaking size of benchmark dataset.
	 * Not as important as {@link #BENCHMARK_IDENTITY_COUNT} though: Each {@link OwnIdentity} has
	 * its own {@link Score} graph which does not interact with the Score graphs of the other
	 * own identities. Thus, incrementing {@link OwnIdentity} count likely only multiplies execution
	 * time by a constant factor.
	 * The amount of trusts will be computed from this and {@link #BENCHMARK_IDENTITY_COUNT}.*/
	private static final int BENCHMARK_OWN_IDENTITY_COUNT = 1;


	/**
	 * Amount of identities used to create the datasets {@link #TRUST_DISTRIBUTION_VALUES}
	 * and {@link #TRUST_DISTRIBUTION_TRUSTEES}. Must be correct!
	 * Do NOT use for changing the size of the benchmark dataset, instead use:
	 * {@link #BENCHMARK_IDENTITY_COUNT}
	 * {@link #BENCHMARK_OWN_IDENTITY_COUNT} */
	private static final int TRUST_DISTRIBUTION_IDENTITY_COUNT = 11985;

	/**
	 * Amount of trusts used to create the datasets {@link #TRUST_DISTRIBUTION_VALUES}
	 * and {@link #TRUST_DISTRIBUTION_TRUSTEES}. Must be correct!
	 * Do NOT use for changing the size of the benchmark dataset, instead use:
	 * {@link #BENCHMARK_IDENTITY_COUNT}
	 * {@link #BENCHMARK_OWN_IDENTITY_COUNT} */
	private static final int TRUST_DISTRIBUTION_TRUST_COUNT = 222122;

	/**
	 * Dimension 1 contains an entry for each Trust value between -100 and +100.
	 * Dimension 2 contains the amount of occurrences of this trust value in the sample dataset.
	 * 
	 * Dataset was obtained using "WOTUtil -trustValueHistogram" from a network dump of 2015-07-23
	 * with:
	 * Identities: 11985
	 * Not fetched identities: 183
	 * Trusts: 222122
	 * 
	 * ATTENTION: When updating this, also update:
	 * {@link #TRUST_DISTRIBUTION_IDENTITY_COUNT}
	 * {@link #TRUST_DISTRIBUTION_TRUST_COUNT} */
	private static final int[][] TRUST_DISTRIBUTION_VALUES =
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

	/**
	 * Dimension 1 specifies an amount of trustees an identity has.
	 * Dimension 2 contains the amount of occurrences of this amount of trustees among all
	 * identities in the sample dataset.
	 * 
	 * Dataset was obtained using "WOTUtil -trusteeCountHistogram" from a network dump of 2015-07-23
	 * with:
	 * Identities: 11985
	 * Not fetched identities: 183
	 * Trusts: 222122
	 * 
	 * ATTENTION: When updating this, also update:
	 * {@link #TRUST_DISTRIBUTION_IDENTITY_COUNT}
	 * {@link #TRUST_DISTRIBUTION_TRUST_COUNT} */
	private static final int[][] TRUST_DISTRIBUTION_TRUSTEES = new int[][] {
		{ 0, 813 },
		{ 1, 2 },
		{ 2, 10 },
		{ 3, 17 },
		{ 4, 4087 },
		{ 5, 3760 },
		{ 6, 591 },
		{ 7, 330 },
		{ 8, 201 },
		{ 9, 140 },
		{ 10, 141 },
		{ 11, 83 },
		{ 12, 90 },
		{ 13, 59 },
		{ 14, 61 },
		{ 15, 57 },
		{ 16, 57 },
		{ 17, 48 },
		{ 18, 50 },
		{ 19, 41 },
		{ 20, 41 },
		{ 21, 30 },
		{ 22, 24 },
		{ 23, 32 },
		{ 24, 32 },
		{ 25, 26 },
		{ 26, 26 },
		{ 27, 19 },
		{ 28, 28 },
		{ 29, 19 },
		{ 30, 17 },
		{ 31, 19 },
		{ 32, 15 },
		{ 33, 19 },
		{ 34, 14 },
		{ 35, 16 },
		{ 36, 13 },
		{ 37, 14 },
		{ 38, 13 },
		{ 39, 14 },
		{ 40, 12 },
		{ 41, 17 },
		{ 42, 11 },
		{ 43, 7 },
		{ 44, 13 },
		{ 45, 17 },
		{ 46, 12 },
		{ 47, 7 },
		{ 48, 11 },
		{ 49, 12 },
		{ 50, 14 },
		{ 51, 10 },
		{ 52, 10 },
		{ 53, 8 },
		{ 54, 10 },
		{ 55, 7 },
		{ 56, 10 },
		{ 57, 14 },
		{ 58, 12 },
		{ 59, 11 },
		{ 60, 6 },
		{ 61, 6 },
		{ 62, 6 },
		{ 63, 10 },
		{ 64, 11 },
		{ 65, 7 },
		{ 66, 6 },
		{ 67, 11 },
		{ 68, 8 },
		{ 69, 3 },
		{ 70, 3 },
		{ 71, 12 },
		{ 72, 5 },
		{ 73, 8 },
		{ 74, 6 },
		{ 75, 2 },
		{ 76, 6 },
		{ 77, 8 },
		{ 78, 5 },
		{ 79, 5 },
		{ 80, 8 },
		{ 81, 6 },
		{ 82, 3 },
		{ 83, 3 },
		{ 84, 6 },
		{ 85, 3 },
		{ 86, 8 },
		{ 87, 4 },
		{ 88, 5 },
		{ 89, 2 },
		{ 90, 1 },
		{ 91, 1 },
		{ 92, 1 },
		{ 93, 5 },
		{ 94, 9 },
		{ 95, 3 },
		{ 96, 3 },
		{ 97, 4 },
		{ 98, 2 },
		{ 99, 4 },
		{ 100, 4 },
		{ 101, 3 },
		{ 102, 3 },
		{ 103, 4 },
		{ 104, 6 },
		{ 105, 2 },
		{ 106, 6 },
		{ 107, 1 },
		{ 108, 6 },
		{ 109, 3 },
		{ 110, 3 },
		{ 111, 1 },
		{ 112, 4 },
		{ 113, 2 },
		{ 114, 2 },
		{ 115, 4 },
		{ 116, 4 },
		{ 117, 5 },
		{ 118, 2 },
		{ 119, 1 },
		{ 120, 1 },
		{ 121, 3 },
		{ 122, 7 },
		{ 123, 1 },
		{ 124, 3 },
		{ 125, 1 },
		{ 126, 5 },
		{ 127, 3 },
		{ 128, 1 },
		{ 129, 3 },
		{ 130, 3 },
		{ 131, 2 },
		{ 132, 3 },
		{ 133, 1 },
		{ 134, 3 },
		{ 135, 2 },
		{ 136, 1 },
		{ 137, 4 },
		{ 139, 4 },
		{ 140, 4 },
		{ 141, 2 },
		{ 142, 1 },
		{ 143, 5 },
		{ 144, 2 },
		{ 145, 4 },
		{ 146, 2 },
		{ 147, 1 },
		{ 148, 1 },
		{ 149, 1 },
		{ 150, 2 },
		{ 151, 2 },
		{ 152, 1 },
		{ 153, 3 },
		{ 154, 2 },
		{ 155, 1 },
		{ 156, 1 },
		{ 157, 1 },
		{ 158, 1 },
		{ 160, 2 },
		{ 161, 2 },
		{ 162, 2 },
		{ 164, 2 },
		{ 165, 3 },
		{ 166, 3 },
		{ 167, 4 },
		{ 168, 3 },
		{ 169, 1 },
		{ 170, 1 },
		{ 172, 2 },
		{ 173, 2 },
		{ 175, 1 },
		{ 176, 2 },
		{ 177, 2 },
		{ 178, 2 },
		{ 179, 1 },
		{ 180, 2 },
		{ 181, 3 },
		{ 182, 2 },
		{ 183, 2 },
		{ 185, 1 },
		{ 186, 2 },
		{ 188, 1 },
		{ 190, 3 },
		{ 191, 1 },
		{ 192, 4 },
		{ 193, 1 },
		{ 198, 4 },
		{ 199, 1 },
		{ 200, 2 },
		{ 201, 3 },
		{ 203, 1 },
		{ 204, 1 },
		{ 205, 2 },
		{ 207, 2 },
		{ 208, 1 },
		{ 210, 2 },
		{ 211, 2 },
		{ 212, 2 },
		{ 214, 4 },
		{ 215, 2 },
		{ 218, 1 },
		{ 220, 1 },
		{ 221, 1 },
		{ 222, 1 },
		{ 223, 2 },
		{ 224, 1 },
		{ 225, 1 },
		{ 226, 1 },
		{ 228, 1 },
		{ 231, 2 },
		{ 232, 1 },
		{ 233, 2 },
		{ 234, 1 },
		{ 239, 1 },
		{ 241, 1 },
		{ 244, 2 },
		{ 246, 1 },
		{ 251, 2 },
		{ 252, 1 },
		{ 253, 3 },
		{ 254, 1 },
		{ 255, 1 },
		{ 256, 2 },
		{ 257, 3 },
		{ 258, 2 },
		{ 261, 1 },
		{ 262, 2 },
		{ 264, 1 },
		{ 265, 1 },
		{ 266, 1 },
		{ 267, 1 },
		{ 268, 2 },
		{ 270, 2 },
		{ 272, 1 },
		{ 276, 2 },
		{ 277, 2 },
		{ 281, 1 },
		{ 286, 2 },
		{ 287, 2 },
		{ 292, 1 },
		{ 293, 1 },
		{ 294, 1 },
		{ 295, 1 },
		{ 296, 2 },
		{ 297, 1 },
		{ 298, 1 },
		{ 299, 1 },
		{ 307, 1 },
		{ 308, 1 },
		{ 309, 1 },
		{ 311, 1 },
		{ 312, 1 },
		{ 318, 2 },
		{ 321, 2 },
		{ 323, 1 },
		{ 325, 2 },
		{ 326, 1 },
		{ 330, 2 },
		{ 331, 1 },
		{ 332, 3 },
		{ 333, 1 },
		{ 334, 1 },
		{ 336, 1 },
		{ 339, 2 },
		{ 342, 3 },
		{ 347, 1 },
		{ 351, 1 },
		{ 353, 2 },
		{ 354, 1 },
		{ 357, 1 },
		{ 359, 1 },
		{ 361, 1 },
		{ 363, 1 },
		{ 364, 4 },
		{ 368, 1 },
		{ 369, 2 },
		{ 370, 1 },
		{ 375, 1 },
		{ 377, 1 },
		{ 382, 1 },
		{ 385, 2 },
		{ 387, 1 },
		{ 389, 2 },
		{ 391, 1 },
		{ 393, 1 },
		{ 394, 1 },
		{ 401, 1 },
		{ 402, 1 },
		{ 403, 1 },
		{ 405, 1 },
		{ 407, 1 },
		{ 409, 2 },
		{ 412, 2 },
		{ 413, 1 },
		{ 428, 2 },
		{ 429, 1 },
		{ 432, 1 },
		{ 439, 1 },
		{ 440, 1 },
		{ 446, 1 },
		{ 451, 1 },
		{ 452, 1 },
		{ 454, 2 },
		{ 460, 1 },
		{ 465, 1 },
		{ 466, 1 },
		{ 473, 1 },
		{ 474, 1 },
		{ 481, 1 },
		{ 484, 2 },
		{ 488, 1 },
		{ 489, 1 },
		{ 491, 2 },
		{ 499, 1 },
		{ 506, 1 },
		{ 511, 1 },
		{ 512, 74 },
		{ 513, 1 },
		{ 526, 1 },
		{ 596, 1 },
		{ 757, 1 },
		{ 1607, 1 }
	};

	@Before
	public void checkThatAssertionsAreDisabled() {
		assert(false)
			: "WOT has very sophisticated assertions which can impact performance a lot, so please "
			+ "disable them for all classes running these benchmarks. ";
	}

	@Before public void setUpWOT() throws UnknownIdentityException, MalformedURLException {
	    // Delete the seed identities since the test assumes the WOT database to be empty.
	    deleteSeedIdentities();
	}

	/**
	 * FIXME: Not tested. Not reviewed.
	 * FIXME: Should be changed to respect truster count distribution, not only trustee count
	 * distribution. This could be done by when setting trusts chosen the trustee according
	 * to the truster count distribution. I.e. chose an identity at index X in the identity list
	 * where X is computed according to the truster count distribution. By this, we would get the
	 * same effect as with seed identities in the regular network: There would be a small amount
	 * of identities which have received very many trust values.  */
	@Test
	public void benchmark_updateScoresAfterDistrust() throws InvalidParameterException,
			NumberFormatException, UnknownIdentityException, DuplicateTrustException,
			NotTrustedException, IOException {
		
		
		final int ownIdentityCount = BENCHMARK_OWN_IDENTITY_COUNT;
		final int identityCount = BENCHMARK_IDENTITY_COUNT;

		// Dataset created from paramenters:
		
		WebOfTrust wot = getWebOfTrust();
		ArrayList<OwnIdentity> ownIds = new ArrayList<OwnIdentity>(ownIdentityCount + 1);
		ArrayList<Identity> ids = new ArrayList<Identity>(identityCount + ownIdentityCount + 1);
		ArrayList<Byte> trusValueDistribution;
		ArrayList<Integer> trusteeCountDistribution;
		int trustCount = 0;
		
		System.out.println("Creating " + ownIdentityCount + " OwnIdentitys ...");
		for(int i = 0; i < ownIdentityCount; ++i)
			ownIds.add(wot.createOwnIdentity(Integer.toString(i), true, null));
		
		ids.addAll(ownIds);
		
		System.out.println("Creating " + identityCount + " Identitys ...");
		for(int i = 0; i < identityCount; ++i)
			ids.add(wot.addIdentity(getRandomRequestURI().toString()));
		
		System.out.println("Computing trust value distribution from "
						   + TRUST_DISTRIBUTION_TRUST_COUNT + " samples...");
		trusValueDistribution = getTrustDistribution();
		
		System.out.println("Computing truste count distribution from "
						   + TRUST_DISTRIBUTION_TRUST_COUNT + " samples...");
		trusteeCountDistribution = getTrusteeCountDistribution();
		
		
		System.out.println("Creating random Trust graph for " + ids.size() + " identities ...");

		StopWatch setupTime = new StopWatch();
		// Setup is not part of the benchmark, so to speed up setup, we use
		// begin/finishTrustListImport() to ensure that only one full recomputation happens for all
		// trusts.
		wot.beginTrustListImport();
		int currentIdentity = 0;
		for(Identity truster : ids) {
			int trusteeCount = Math.min(getRandomTrusteeCount(trusteeCountDistribution),
										ids.size() - 1);
			
			System.out.println("Setting trusts for Identity " + ++currentIdentity);
			
			for(int j=0; j < trusteeCount; ++j) {
				Identity trustee;
				do {
					trustee = ids.get(mRandom.nextInt());
				} while(truster == trustee);

				wot.setTrustWithoutCommit(truster, trustee, getRandomTrustValue(trusValueDistribution), "");
				++trustCount;
			}
		}
		System.out.println("finishTrustListImport() ...");
		wot.finishTrustListImport();
		setupTime.stop();
		
		int fullRecomputationsForSetup = mWebOfTrust.getNumberOfFullScoreRecomputations();
		
		System.out.println("Setup time: " + setupTime);
		System.out.println("Trusts created: " + trustCount);
		System.out.println("Full Score recomputations: " + fullRecomputationsForSetup);
		
		// Print Trust distribution histograms so you can check whether
		// getRandomTrusteeCount() / getRandomTrustValue() produce the same histograms
		// as the sample dataset.
		WOTUtil.trustValueHistogram(mWebOfTrust);
		WOTUtil.trusteeCountHistogram(mWebOfTrust);
		
		// Setup complete. Now the actual benchmark follows: 
		// We remove all trusts in the graph one-by-one, in random order.
		
		System.out.println("Removing complete graph of " + trustCount + " Trusts...");
	
		ArrayList<Trust> trusts = new ArrayList<Trust>(trustCount + 1);
		// Workaround for https://bugs.freenetproject.org/view.php?id=6596 
		for(Trust trust : mWebOfTrust.getAllTrusts())
			trusts.add(trust.clone());
		
		Collections.shuffle(trusts, mRandom);
		
		FileWriter output = new FileWriter(GNUPLOT_OUTPUT, true);
		
		assertEquals(trustCount, trusts.size());
		int i = trustCount;
		StopWatch benchmarkTime = new StopWatch();
		for(Trust trust : trusts) {
			System.out.println("Processing Trust: " + i);
			
			// Try to exclude GC peaks from single trust benchmarks
			System.gc();
			
			String trusterID = trust.getTruster().getID();
			String trusteeID = trust.getTrustee().getID();
			
			StopWatch individualBenchmarkTime = new StopWatch(); 
			wot.removeTrustIncludingNonOwn(trusterID, trusteeID);
			individualBenchmarkTime.stop();
			
			double seconds = (double)individualBenchmarkTime.getNanos() / (1000000000d);
			output.write(i + " " + seconds + '\n');
			
			--i;
		}
		benchmarkTime.stop();
		int fullRecomputationsForRemoval
			= wot.getNumberOfFullScoreRecomputations() - fullRecomputationsForSetup;
		
		output.close();
		
		System.out.println("Benchmark result time: " + benchmarkTime);
		System.out.println("Full Score recomputations: " + fullRecomputationsForRemoval);
	}

	private byte getRandomTrustValue(ArrayList<Byte> trustDistribution) {
		return trustDistribution.get(mRandom.nextInt(trustDistribution.size()));
	}

	private static ArrayList<Byte> getTrustDistribution() {
		// Resulting trust values to meet the distribution.
		// If distribution says "100 occurrences of value 3", then value 3 will be added 100 times.
		ArrayList<Byte> result = new ArrayList<Byte>(TRUST_DISTRIBUTION_TRUST_COUNT + 1);
		
		for(byte value = -100; value <= +100; ++value) {
			int index = value + 100;
			
			assertEquals(TRUST_DISTRIBUTION_VALUES[index][0], value);
			
			int countOfOccurrencesOfValue = TRUST_DISTRIBUTION_VALUES[index][1];
			
			while(countOfOccurrencesOfValue-- > 0)
				result.add(value);
		}
		
		// Return value is computed. Now test whether it is correct.
		
		assertEquals(TRUST_DISTRIBUTION_TRUST_COUNT, result.size());
		
		int[] distribution = new int[201];
		
		for(Byte trust : result)
			++distribution[trust + 100];
		
		for(int value = -100; value <= +100; ++value) {
			int index = value + 100;
			assertEquals(TRUST_DISTRIBUTION_VALUES[index][0], value);
			assertEquals(TRUST_DISTRIBUTION_VALUES[index][1], distribution[index]);
		}
		
		return result;
	}

	/** FIXME: Not tested. Not reviewed. */
	private int getRandomTrusteeCount(ArrayList<Integer> trusteeCountDistribution) {
		return trusteeCountDistribution.get(mRandom.nextInt(trusteeCountDistribution.size()));
	}

	/** FIXME: Not tested. Not reviewed. */
	private static ArrayList<Integer> getTrusteeCountDistribution() {
		// Resulting trustee counts to meet the distribution.
		// If distribution says "100 occurrences of count 3", then count 3 will be added 100 times.
		ArrayList<Integer> result = new ArrayList<Integer>(TRUST_DISTRIBUTION_IDENTITY_COUNT + 1);
		
		for(int i=0; i < TRUST_DISTRIBUTION_TRUSTEES.length; ++i) {
			int trusteeCount = TRUST_DISTRIBUTION_TRUSTEES[i][0];
			int countOfOccurrencesOfTrusteeCount = TRUST_DISTRIBUTION_TRUSTEES[i][1];
			
			while(countOfOccurrencesOfTrusteeCount-- > 0)
				result.add(trusteeCount);
		}
		
		// Return value is computed. Now test whether it is correct.
		
		assertEquals(TRUST_DISTRIBUTION_IDENTITY_COUNT, result.size());
		
		HashMap<Integer, Integer> distribution = new HashMap<Integer, Integer>();
		int distributionTrustCount = 0;
		for(Integer count : result) {
			Integer currentCount = distribution.get(count);
			distribution.put(count, currentCount != null ? currentCount + 1 : 1);
			distributionTrustCount += count;
		}
		
		assertEquals(TRUST_DISTRIBUTION_TRUST_COUNT, distributionTrustCount);
		
		for(int[] pair : TRUST_DISTRIBUTION_TRUSTEES) {
			assertTrue(distribution.containsKey(pair[0]));
			assertEquals(pair[1], (int)distribution.get(pair[0]));
		}
		
		return result;
	}

}
