/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.ui.terminal;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.UUID;

import plugins.WebOfTrust.Identity;
import plugins.WebOfTrust.Trust;
import plugins.WebOfTrust.Trust.TrustID;
import plugins.WebOfTrust.WebOfTrust;
import plugins.WebOfTrust.exceptions.NotTrustedException;
import plugins.WebOfTrust.exceptions.UnknownIdentityException;
import plugins.WebOfTrust.ui.fcp.FCPInterface;
import plugins.WebOfTrust.util.StopWatch;

import com.db4o.ObjectSet;

import freenet.clients.fcp.FCPPluginConnection;
import freenet.clients.fcp.FCPPluginMessage;

/**
 * Command-line tool for maintenance and analysis of WOT databases.
 * 
 * Run and show syntax by:
 *     ./wotutil.sh
 */
public final class WOTUtil {
	
	public static void benchmarkRemoveTrustDestructive(WebOfTrust wot, File gnuplot, long seed)
			throws IOException, UnknownIdentityException, NotTrustedException,
				   InterruptedException {
		
		assert(false)
			: "WOT has very sophisticated assertions which can impact performance a lot, so please "
			+ "disable them for all classes running these benchmarks. ";
		
		// Use fixed seed for deterministic comparison against Score computation rewrite:
		// https://bugs.freenetproject.org/view.php?id=5757
		// Notice: When resuming by restarting upon the same database, specifying the same seed will
		// not yield deterministic results: Collections.shuffle() will be run upon a smaller set of
		// Trusts and thus likely yield different results.
		final Random random = new Random(seed); 
		final List<TrustID> trusts = Collections.unmodifiableList(getTrustsRandomized(wot, random));
		final int trustCount = trusts.size();
		final FileWriter output = new FileWriter(gnuplot, true);
		
		System.out.println("Removing complete graph of " + trustCount + " Trusts...");
		
		int i = trustCount;
		for(TrustID trustID : trusts) {
			System.out.println("Removing Trust: " + i);
			
			// Try to exclude GC peaks from single trust benchmarks
			System.gc();
			
			StopWatch individualBenchmarkTime = new StopWatch(); 
			wot.removeTrustIncludingNonOwn(trustID.getTrusterID(), trustID.getTrusteeID());
			individualBenchmarkTime.stop();
			
			double seconds = (double)individualBenchmarkTime.getNanos() / (1000000000d);
			output.write(i + " " + seconds + '\n');
			output.flush();
			
			--i;
			
			if(System.in.available() > 0) {
				System.in.read();
				
				System.err.println("ENTER pressed to request pause - exiting ...");
				break;
			}
		}
		
		System.out.println("Full recomputations: " + wot.getNumberOfFullScoreRecomputations());
		output.close();
	}
	
	/**
	 * Sends a {@link FCPPluginMessage} to the {@link FCPInterface} of the given {@link WebOfTrust}
	 * and returns the reply {@link FCPPluginMessage}.<br><br>
	 * 
	 * ATTENTION: This will not work for FCP API calls which cause WoT to send FCP replies
	 * asynchronously. This for example applies to the event-notifications API, i.e. you must not
	 * use "Message=Subscribe" (in the {@link FCPPluginMessage#params}). */
	public static FCPPluginMessage fcpCall(WebOfTrust wot, FCPPluginMessage message) {
		@SuppressWarnings("serial")
		class NoConnectionException extends UnsupportedOperationException {
			public NoConnectionException() {
				super("WOTUtil doesn't support FCP calls which require a FCPPluginConnection!");
			}
		}
		
		FCPPluginConnection connection = new FCPPluginConnection() {
			@Override public FCPPluginMessage sendSynchronous(FCPPluginMessage message,
					long timeoutNanoSeconds) throws IOException, InterruptedException {
				throw new NoConnectionException();
			}
			
			@Override public FCPPluginMessage sendSynchronous(SendDirection direction,
					FCPPluginMessage message, long timeoutNanoSeconds) throws IOException,
					InterruptedException {
				throw new NoConnectionException();
			}
			
			@Override public void send(FCPPluginMessage message) throws IOException {
				throw new NoConnectionException();
			}
			
			@Override public void send(SendDirection direction, FCPPluginMessage message)
					throws IOException {
				throw new NoConnectionException();
			}
			
			@Override public UUID getID() {
				throw new NoConnectionException();
			}
		};
		
		// The message handler implements FredPluginFCPMessageHandler.ServerSideFCPMessageHandler.
		// The standard use of it is: Input message is passed, reply message is returned.
		// The connection is *not* used then. It is only used if the server wants to asynchronously
		// send another message in the future.
		// So we can safely pass our fake connection which doesn't work, it likely won't be used.
		return wot.getFCPInterface().handlePluginFCPMessage(connection, message);
	}

	private static ArrayList<TrustID> getTrustsRandomized(WebOfTrust wot, Random random) {
		System.out.println("Loading trusts...");
		
		ObjectSet<Trust> trusts = wot.getAllTrusts();
		
		System.out.println("Cloning trust IDs...");
		
		ArrayList<TrustID> clones = new ArrayList<TrustID>(trusts.size() + 1);
		// Workaround for https://bugs.freenetproject.org/view.php?id=6596 was to clone the Trust
		// objects. Since that is slow and takes a lot of memory, we just clone the IDs.
		for(Trust trust : trusts)
			clones.add(new TrustID(trust));
		
		System.out.println("Shuffling trust IDs...");
		
		Collections.shuffle(clones, random);
		
		return clones;
	}

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

	private static void printSyntax() {
		PrintStream err = System.err;
		err.println("Syntax: ");
		err.println("WOTUtil -benchmarkRemoveTrustDestructive INPUT_DATABASE OUTPUT_GNUPLOT SEED");
		err.println("    ATTENTION: Destroys the given database!");
		err.println("    ATTENTION: OUTPUT_GNUPLOT will be appended to, not overwritten.");
		err.println("    Push ENTER to exit for pause. Resume by restarting with same parameters.");
		err.println("    Deterministic execution by SEED is not supported with resume.");
		err.println("WOTUtil -testAndRepair INPUT_DATABASE");
		err.println("WOTUtil -trustValueHistogram INPUT_DATABASE");
		err.println("WOTUtil -trusteeCountHistogram INPUT_DATABASE");
	}
	
	public static int mainWithReturnValue(String[] args) {
		WebOfTrust wot = null;
		
		try {
			if(args.length < 2) {
				printSyntax();
				return 1;
			}
			
			String databaseFile = args[1];
			if(!new File(databaseFile).isFile())
				throw new FileNotFoundException(databaseFile);
			
			wot = new WebOfTrust(databaseFile);
			
			System.out.println("Checking database for corruption...");
			
			if(!wot.verifyDatabaseIntegrity() || !wot.verifyAndCorrectStoredScores()) {
				System.err.println("Damaged database, exiting!");
				return 2;
			}
			
			if(args[0].equalsIgnoreCase("-testAndRepair"))
				System.out.println("Database OK!"); // Test happened above already.
			else if(args[0].equalsIgnoreCase("-trustValueHistogram"))
				trustValueHistogram(wot);
			else if(args[0].equalsIgnoreCase("-trusteeCountHistogram"))
				trusteeCountHistogram(wot);
			else if(args[0].equalsIgnoreCase("-benchmarkRemoveTrustDestructive")) {
				if(args.length != 4) {
					printSyntax();
					return 1;
				}
				benchmarkRemoveTrustDestructive(wot, new File(args[2]), Long.parseLong(args[3]));
			} else {
				printSyntax();
				return 1;
			}
			
			return 0; // Success
		} catch(Exception e) {
			System.err.println(e);
			return 3;
		} finally {
			if(wot != null)
				wot.terminate();
		}
	}
			
	public static void main(String[] args) {
		System.exit(mainWithReturnValue(args));
	}
}
