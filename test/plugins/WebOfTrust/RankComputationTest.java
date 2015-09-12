/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import static org.junit.Assert.*;

import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;

import org.junit.Before;
import org.junit.Test;

import plugins.WebOfTrust.exceptions.InvalidParameterException;
import plugins.WebOfTrust.exceptions.NotInTrustTreeException;
import plugins.WebOfTrust.util.StopWatch;
import freenet.support.TimeUtil;

/**
 * Tests whether the 4 implementations of rank computation yield the same results:
 * - {@link WebOfTrust#computeRankFromScratch_Caching(OwnIdentity, Identity, java.util.Map)}
 * - {@link WebOfTrust#computeRankFromScratch(OwnIdentity, Identity)}
 * - {@link WebOfTrust#computeRankFromScratch_Forward(OwnIdentity, Identity)}
 * - {@link WebOfTrust#computeAllScoresWithoutCommit()}
 * 
 * For the caching function, tests whether the cache it produces is correct.
 * Notice: For using this to debug wrong cache entries, you might have to comment out the assert
 * which checks the returned rank before checking the cache. This is because if it produces wrong
 * cache entries, the assert which tests its returned rank value (and determine it
 * to be wrong maybe) could make this test fail before it reaches the stage of testing the cache.
 * 
 * Also measures the execution time per rank for the first 3 of them. The last currently only
 * receives measurement of the total time for a Score, which includes more computation than a rank.
 * TODO: Performance: Measure rank computation time of
 * {@link WebOfTrust#computeAllScoresWithoutCommit()}. This requires extracting a function
 * from it which only computes ranks, not Scores. Also the assert()s in it which compare
 * its results against {@link WebOfTrust#computeRankFromScratch(OwnIdentity, Identity)}) need to
 * be removed. */
public final class RankComputationTest extends AbstractJUnit4BaseTest {

	private WebOfTrust mWebOfTrust = null;


	@Before public void setUp() {
		mWebOfTrust = constructEmptyWebOfTrust();
	}

	@Test public void testAndBenchmarkRankComputationImplementations()
			throws MalformedURLException, InvalidParameterException {
		
		int ownIdentityCount = 2;
		int identityCount = 100;
		int trustCount = (identityCount*identityCount) / 10;
		// OwnIdentities do assign a Score to themselves, we must include that.
		int scoreCount = ownIdentityCount * (identityCount + ownIdentityCount);
		int rankCount = scoreCount;
		
		ArrayList<OwnIdentity> ownIdentitys = addRandomOwnIdentities(ownIdentityCount);
		ArrayList<Identity> identitys = addRandomIdentities(identityCount);
		identitys.addAll(ownIdentitys);
		assertEquals(identitys.size(), mWebOfTrust.getAllIdentities().size());
		addRandomTrustValues(identitys, trustCount);
		
		System.out.println("Ranks: " + rankCount);
		
		StopWatch computeAllScoresTime = new StopWatch();
		mWebOfTrust.verifyAndCorrectStoredScores(); // Calls computeAllScoresWithoutCommit()
		computeAllScoresTime.stop();
		computeAllScoresTime.divideNanosBy(scoreCount);
		// Cannot measure computeAllScoresWithoutCommit() per-rank time, see function JavaDoc
		System.out.println("computeAllScores() avg. time per SCORE: " + computeAllScoresTime);
		
		long time_rank_computeRankFromScratch_Caching = 0;
		long time_rank_computeRankFromScratch = 0;
		long time_rank_computeRankFromScratch_Forward = 0;
		
		// For WebOfTrust.computeRankFromScratch_Caching()
		final HashMap<String, Integer> rankCache = new HashMap<String, Integer>();
		
		for(OwnIdentity source : ownIdentitys) {
			for(Identity target : identitys) {
				int rank_computeAllScores;
				try {
					rank_computeAllScores = mWebOfTrust.getScore(source, target).getRank();
				} catch(NotInTrustTreeException e) {
					rank_computeAllScores = -1;
				}
				
				StopWatch t0 = new StopWatch();
				int rank_computeRankFromScratch_Caching
					= mWebOfTrust.computeRankFromScratch_Caching(source, target, rankCache);
				time_rank_computeRankFromScratch_Caching += t0.getNanos();
				
				// System.out.println("computeRankFromScratch_Caching() time: " + t0);
				
				StopWatch t1 = new StopWatch();
				int rank_computeRankFromScratch
					= mWebOfTrust.computeRankFromScratch(source, target);
				time_rank_computeRankFromScratch += t1.getNanos();
				
				// System.out.println("computeRankFromScratch() time: " + t1);
				
				StopWatch t2 = new StopWatch();
				int rank_computeRankFromScratch_Forward
					= mWebOfTrust.computeRankFromScratch_Forward(source, target);
				time_rank_computeRankFromScratch_Forward += t2.getNanos();
				
				// System.out.println("computeRankFromScratch_Forward() time: " + t2);
				
				assertEquals(rank_computeAllScores, rank_computeRankFromScratch_Caching);
				assertEquals(rank_computeAllScores, rank_computeRankFromScratch);
				assertEquals(rank_computeAllScores, rank_computeRankFromScratch_Forward);
				
				for(Entry<String, Integer> cacheEntry : rankCache.entrySet()) {
					try {
						assertEquals(mWebOfTrust.getScore(cacheEntry.getKey()).getRank(),
							cacheEntry.getValue().intValue());
					} catch (NotInTrustTreeException e) {
						assertEquals(-1, cacheEntry.getValue().intValue());
					}
				}
			}
		}
		
		// Make sure the above for() loop didn't falsely indicate a correct cache when the
		// cache was just empty and thus invalid.
		assertEquals(rankCount, rankCache.size());
		
		time_rank_computeRankFromScratch_Caching /= rankCount;
		time_rank_computeRankFromScratch /= rankCount;
		time_rank_computeRankFromScratch_Forward /= rankCount;
		
		// TimeUtil wants millis, not nanos
		time_rank_computeRankFromScratch_Caching
			= TimeUnit.NANOSECONDS.toMillis(time_rank_computeRankFromScratch_Caching);

		time_rank_computeRankFromScratch
			= TimeUnit.NANOSECONDS.toMillis(time_rank_computeRankFromScratch);

		time_rank_computeRankFromScratch_Forward
			= TimeUnit.NANOSECONDS.toMillis(time_rank_computeRankFromScratch_Forward);
		
		System.out.println("computeRankFromScratch_Caching() avg. time per rank: "
			+ TimeUtil.formatTime(time_rank_computeRankFromScratch_Caching, 3, true));
		
		System.out.println("computeRankFromScratch() avg. time per rank: "
			+ TimeUtil.formatTime(time_rank_computeRankFromScratch, 3, true));
		
		System.out.println("computeRankFromScratch_Forward() avg. time per rank: "
			+ TimeUtil.formatTime(time_rank_computeRankFromScratch_Forward, 3, true));
	}

	@Override protected WebOfTrust getWebOfTrust() {
		return mWebOfTrust;
	}

}
