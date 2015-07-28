/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.util;

import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

import plugins.WebOfTrust.Identity;
import plugins.WebOfTrust.Trust;
import plugins.WebOfTrust.WebOfTrust;

import com.db4o.ObjectSet;

/**
 * Command-line tool for maintenance and analysis of WOT databases.
 * 
 * Run and show syntax by:
 *     ./wotutil.sh
 */
public final class WOTUtil {

	public static void trustValueHistogram(WebOfTrust wot) {
		// Counts number of occurrences of each possible Trust value. +1 for value of 0.
		int[] histogram = new int[Trust.MAX_TRUST_VALUE + Math.abs(Trust.MIN_TRUST_VALUE) + 1];
		Arrays.fill(histogram, 0);
		
		// Compute histogram
		
		final ObjectSet<Trust> trusts = wot.getAllTrusts();
		final int trustCount = trusts.size();
		int processedTrusts = 0;
		
		for(Trust trust : trusts) {
			int value = trust.getValue();

			value -= Trust.MIN_TRUST_VALUE;

			++histogram[value];

			++processedTrusts;

			if(processedTrusts % (trustCount / 100) == 0)
				System.out.println("Progress: " + processedTrusts / (trustCount / 100) + "% ...");
		}
		
		// Compute amount of "no trust" identity pairs
		
		int identityCount = wot.getAllIdentities().size();
		int totalPossibleTrustCount = identityCount * (identityCount - 1); // Self-trust not allowed
		int noTrust = totalPossibleTrustCount - trustCount;
		
		// Print output
		System.out.println();
		System.out.println("Identities: " + identityCount);
		System.out.println("Not fetched identities: " + wot.getNumberOfUnfetchedIdentities());
		System.out.println("Trusts: " + trustCount);
		System.out.println("Trust histogram follows ...");
		System.out.println("None: " + noTrust);
		
		for(int i = 0; i < histogram.length; ++i) {
			int value = i + Trust.MIN_TRUST_VALUE;
			System.out.println(value + ": " + histogram[i]);
		}
	}

	public static void trusteeCountHistogram(WebOfTrust wot) {
		// Key = Amount of trustees
		// Value = Number of identities which count of trustees as specified by the key 
		Map<Integer, Integer> histogram = new TreeMap<Integer, Integer>();
		
		// Compute histogram
		
		final ObjectSet<Identity> trusters = wot.getAllIdentities();
		final int trusterCount = trusters.size();
		final int onePercent = (trusterCount / 100);
		int processedTrusters = 0;
		
		for(Identity truster : trusters) {
			Integer trustees = wot.getGivenTrusts(truster).size();
			Integer oldSum = histogram.get(trustees); 
			histogram.put(trustees, oldSum != null ? oldSum + 1 : 1);
			
			++processedTrusters;

			if(processedTrusters % onePercent == 0)
				System.out.println("Progress: " + processedTrusters / onePercent + "% ...");
		}
		
		// Print output
		
		System.out.println();
		System.out.println("Identities: " + trusterCount);
		System.out.println("Not fetched identities: " + wot.getNumberOfUnfetchedIdentities());
		System.out.println("Trusts: " + wot.getAllTrusts().size());
		System.out.println("Trustee count histogram follows ...");
		
		for(Map.Entry<Integer, Integer> entry : histogram.entrySet()) {
			System.out.println(entry.getKey() + ": " + entry.getValue());
		}
	}

	public static void main(String[] args) {
		if(args.length != 2
			||
				   (!args[0].equalsIgnoreCase("-trustValueHistogram")
				 && !args[0].equalsIgnoreCase("-trusteeCountHistogram"))
			) {
			
			System.err.println("Syntax: ");
			System.err.println("WOTUtil -trustValueHistogram DATABASE_FILENAME");
			System.err.println("WOTUtil -trusteeCountHistogram DATABASE_FILENAME");
			System.exit(1);
			return;
		}
		
		WebOfTrust wot = null;
		try {
			wot = new WebOfTrust(args[1]);
			
			if(args[0].equalsIgnoreCase("-trustValueHistogram"))
				trustValueHistogram(wot);
			else
				trusteeCountHistogram(wot);
		} finally {
			if(wot != null)
				wot.terminate();
		}
	}
}
