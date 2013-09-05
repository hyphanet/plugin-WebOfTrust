/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import java.net.MalformedURLException;

import plugins.WebOfTrust.exceptions.NotInTrustTreeException;
import plugins.WebOfTrust.exceptions.UnknownIdentityException;
import freenet.support.CurrentTimeUTC;

/**
 * @author xor (xor@freenetproject.org)
 * @author Julien Cornuwel (batosai@freenetproject.org)
 */
public class ScoreTest extends DatabaseBasedTest {
	
	private String requestUriA = "USK@Pn5K9Lt4pE0v5I3TDF40yPkDeE6IJP-nZ~zxxEq76Yc,t3vIf26txb~g6yP1f5cANe1Cb98uzcQBqCAG1PO03OQ,AQACAAE/WebOfTrust/0";
	private String insertUriA = "USK@f3bEbhW5xmevbzAE2sfAsioNQezrKeak6vUYWhHAoLk,t3vIf26txb~g6yP1f5cANe1Cb98uzcQBqCAG1PO03OQ,AQECAAE/WebOfTrust/0";
	private String requestUriB = "USK@a6dD~md7InpruJ3B98RiqRwPJ9L3w~N6l5Ad14neUVU,WZkyt7jgLFJLnVpQ7q7vWBCkz8t8O9JbU1Qsg9bLvdo,AQACAAE/WebOfTrust/0";
	private String insertUriB = "USK@CSkCsPeEqkRNbO~xtEpL~gMHzEydwwP6ofJBMMArZX4,WZkyt7jgLFJLnVpQ7q7vWBCkz8t8O9JbU1Qsg9bLvdo,AQECAAE/WebOfTrust/0";
	private OwnIdentity a;
	private OwnIdentity b;


	@Override
	protected void setUp() throws Exception {
		super.setUp();
		
		a = new OwnIdentity(mWoT, insertUriA, "A", true); a.storeAndCommit();
		b = new OwnIdentity(mWoT, insertUriB, "B", true); b.storeAndCommit();
		
		Score score = new Score(mWoT, a,b,100,1,40); score.storeWithoutCommit();
		Persistent.checkedCommit(mWoT.getDatabase(), this);
		
		// TODO: Modify the test to NOT keep a reference to the identities as member variables so the followig also garbage collects them.
		flushCaches();
	}
	
	public void testClone() throws NotInTrustTreeException, IllegalArgumentException, IllegalAccessException, InterruptedException {
		final Score original = mWoT.getScore(a, b);
		
		Thread.sleep(10); // Score contains Date mLastChangedDate which might not get properly cloned.
		assertFalse(CurrentTimeUTC.get().equals(original.getDateOfLastChange()));
		
		final Score clone = original.clone();
		
		assertEquals(original, clone);
		assertNotSame(original, clone);
		
		testClone(Score.class, original, clone);
	}

	public void testScoreCreation() throws NotInTrustTreeException {
		
		Score score = mWoT.getScore(a, b);
		
		assertTrue(score.getScore() == 100);
		assertTrue(score.getRank() == 1);
		assertTrue(score.getCapacity() == 40);
		assertTrue(score.getTruster() == a);
		assertTrue(score.getTrustee() == b);
	}
	
	// TODO: Move to WoTTest
	public void testScorePersistence() throws MalformedURLException, UnknownIdentityException, NotInTrustTreeException {
		a = mWoT.getOwnIdentityByURI(requestUriA);
		b = mWoT.getOwnIdentityByURI(requestUriB);
		final Score originalScore = mWoT.getScore(a, b);
		
		originalScore.checkedActivate(10);
		
		mWoT.terminate();
		mWoT = null;
		
		flushCaches();
		
		mWoT = new WebOfTrust(getDatabaseFilename());
		a = mWoT.getOwnIdentityByURI(requestUriA);
		b = mWoT.getOwnIdentityByURI(requestUriB);
		final Score score = mWoT.getScore(a, b);
		
		originalScore.initializeTransient(mWoT); // Prevent DatabaseClosedException in .equals()
		
		assertSame(score, mWoT.getScore(a, b));
		assertNotSame(score, originalScore);
		assertEquals(originalScore, score);
	}
	
	public void testEquals() {
		final Score score = new Score(mWoT, a, b, 100, 3, 2);
		
		do {
			try {
				Thread.sleep(1);
			} catch (InterruptedException e) { }
		} while(score.getDateOfCreation().equals(CurrentTimeUTC.get()));
		
		final Score equalScore = new Score(mWoT, score.getTruster().clone(), score.getTrustee().clone(), score.getScore(), score.getRank(), score.getCapacity());
		
		assertEquals(score, score);
		assertEquals(score, equalScore);
		
		
		final Object[] inequalObjects = new Object[] {
			new Object(),
			new Score(mWoT, (OwnIdentity)score.getTrustee(), score.getTruster(), score.getScore(), score.getRank(), score.getCapacity()),
			new Score(mWoT, score.getTruster(), score.getTruster(), score.getScore(), score.getRank(), score.getCapacity()),
			new Score(mWoT, (OwnIdentity)score.getTrustee(), score.getTrustee(), score.getScore(), score.getRank(), score.getCapacity()),
			new Score(mWoT, score.getTruster(), score.getTrustee(), score.getScore()+1, score.getRank(), score.getCapacity()),
			new Score(mWoT, score.getTruster(), score.getTrustee(), score.getScore(), score.getRank()+1, score.getCapacity()),
			new Score(mWoT, score.getTruster(), score.getTrustee(), score.getScore(), score.getRank(), score.getCapacity()+1),
		};
		
		for(Object other : inequalObjects) {
			assertFalse(score.equals(other));
		}
	}
}