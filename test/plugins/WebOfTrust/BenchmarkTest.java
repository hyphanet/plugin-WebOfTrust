package plugins.WebOfTrust;

import java.net.MalformedURLException;
import java.util.ArrayList;

import freenet.keys.FreenetURI;

import plugins.WebOfTrust.exceptions.InvalidParameterException;


/**
 * This is NOT an actual unit test. It is a set of benchmarks to measure the performance of WOT.
 * 
 * Also, this is NOT run in the default test suite which is run by Ant when building.
 * To run it, put "benchmark=true" into the "override.properties" build confiugration file. If it does not exist, create it in the root of the project.
 * 
 * @author xor (xor@freenetproject.org)
 */
public class BenchmarkTest extends DatabaseBasedTest {

	/**
	 * Benchmarks {@link WebOfTrust.verifyAndCorrectStoredScores}.
	 * This function is a glue wrapper around the actual function which we benchmark: {@link WebOfTrust.computeAllScoresWithoutCommit}.
	 * It currently seems to be the major bottleneck in WOT: As of build0012, it takes ~100 seconds for the existing on-network identities.
	 */
	public void test_BenchmarkVerifyAndCorrectStoredScores() throws MalformedURLException, InvalidParameterException {		
		// Benchmark parameters...
		
		int identityCount = 6600;
		int trustCount = 142 * 1000;
		int iterations = 100;
		
		// Random trust graph setup...
		
		FreenetURI[] keypair = getRandomSSKPair();
		Identity ownIdentity = mWoT.createOwnIdentity(keypair[0], "Test", true, "Test");
				
		ArrayList<Identity> identities = addRandomIdentities(identityCount);
		identities.add(ownIdentity);
		
		mWoT.beginTrustListImport();
		for(int i=0; i < trustCount; ++i) {
			Identity truster = identities.get(mRandom.nextInt(identityCount));
			Identity trustee = identities.get(mRandom.nextInt(identityCount));
			if(truster == trustee) { // You cannot assign trust to yourself
				--i;
				continue;
			}
			
			mWoT.setTrustWithoutCommit(truster, trustee, (byte)(mRandom.nextInt(201) - 100), "");
		}
		mWoT.finishTrustListImport();
		Persistent.checkedCommit(mWoT.getDatabase(), this);
		
		// The actual benchmark
		
		long totalTime = 0;
		for(int i=0; i < iterations; ++i) {
			flushCaches();
			
			long startTime = System.nanoTime();
			mWoT.verifyAndCorrectStoredScores();
			long endTime = System.nanoTime();
			
			totalTime += endTime-startTime;
		}
		
		double seconds = (double)totalTime/(1000*1000*1000); 
		
		System.out.println("Benchmarked " + iterations + " iterations of verifyAndCorrectStoredScores: " + (seconds/iterations) + " seconds/iteration");
	}
}
