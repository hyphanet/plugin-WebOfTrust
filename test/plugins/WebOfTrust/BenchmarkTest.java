package plugins.WebOfTrust;

import java.net.MalformedURLException;
import java.util.ArrayList;

import freenet.keys.FreenetURI;

import plugins.WebOfTrust.exceptions.InvalidParameterException;


public class BenchmarkTest extends DatabaseBasedTest {

	/**
	 * Benchmarks {@link WebOfTrust.verifyAndCorrectStoredScores}.
	 * This function is a glue wrapper around the actual function which we benchmark: {@link WebOfTrust.computeAllScoresWithoutCommit}.
	 * It currently seems to be the major bottleneck in WOT: As of build0012, it takes ~100 seconds for the existing on-network identities.
	 */
	public void test_BenchmarkVerifyAndCorrectStoredScores() throws MalformedURLException, InvalidParameterException {		
		// Benchmark parameters...
		
		int identityCount = 1000;
		int trustCount = 50 * 1000;
		int iterations = 1000;
		
		// Random trust graph setup...
		
		FreenetURI[] keypair = getRandomSSKPair();
		Identity ownIdentity = mWoT.createOwnIdentity(keypair[1].toString(), keypair[0].toString(), "Test", true, "Test");
				
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
		
		// The actual benchmark
		
		long startTime = System.nanoTime();
		for(int i=0; i < iterations; ++i) {
			mWoT.verifyAndCorrectStoredScores();
		}
		long endTime = System.nanoTime();
		
		double seconds = (double)(endTime-startTime)/(1000*1000*1000); 
		
		System.out.println("Benchmarked " + iterations + " iterations of verifyAndCorrectStoredScores: " + (seconds/iterations) + " seconds/iteration");
	}
}
