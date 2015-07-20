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
import plugins.WebOfTrust.exceptions.NotInTrustTreeException;
import plugins.WebOfTrust.util.StopWatch;
import freenet.support.TimeUtil;

/**
 * Tests whether the 3 implementations of rank computation yield the same results:
 * - {@link WebOfTrust#computeRankFromScratch(OwnIdentity, Identity)}
 * - {@link WebOfTrust#computeRankFromScratch_Forward(OwnIdentity, Identity)}
 * - {@link WebOfTrust#computeAllScoresWithoutCommit()}
 * 
 * Also measures the execution time per rank for the first two of them. The last currently only
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
		int rankCount = ownIdentityCount * identityCount, scoreCount = rankCount;
		
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
		
		long time_rank_computeRankFromScratch = 0;
		long time_rank_computeRankFromScratch_Forward = 0;
		
		for(OwnIdentity source : ownIdentitys) {
			for(Identity target : identitys) {
				int rank_computeAllScores;
				try {
					rank_computeAllScores = mWebOfTrust.getScore(source, target).getRank();
				} catch(NotInTrustTreeException e) {
					rank_computeAllScores = -1;
				}
				
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
				
				assertEquals(rank_computeAllScores, rank_computeRankFromScratch);
				assertEquals(rank_computeAllScores, rank_computeRankFromScratch_Forward);
			}
		}
		
		time_rank_computeRankFromScratch /= rankCount;
		time_rank_computeRankFromScratch_Forward /= rankCount;
		
		// TimeUtil wants millis, not nanos
		time_rank_computeRankFromScratch
			= TimeUnit.NANOSECONDS.toMillis(time_rank_computeRankFromScratch);

		time_rank_computeRankFromScratch_Forward
			= TimeUnit.NANOSECONDS.toMillis(time_rank_computeRankFromScratch_Forward);
		
		System.out.println("computeRankFromScratch() avg. time per rank: "
			+ TimeUtil.formatTime(time_rank_computeRankFromScratch, 3, true));
		
		System.out.println("computeRankFromScratch_Forward() avg. time per rank: "
			+ TimeUtil.formatTime(time_rank_computeRankFromScratch_Forward, 3, true));
	}

	@Override protected WebOfTrust getWebOfTrust() {
		return mWebOfTrust;
	}

}
