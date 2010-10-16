/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import java.net.MalformedURLException;

import plugins.WebOfTrust.exceptions.NotInTrustTreeException;
import plugins.WebOfTrust.exceptions.UnknownIdentityException;
import freenet.support.CurrentTimeUTC;

/**
 * @author xor (xor@freenetproject.org), Julien Cornuwel (batosai@freenetproject.org)
 */
public class ScoreTest extends DatabaseBasedTest {
	
	private String uriA = "USK@MF2Vc6FRgeFMZJ0s2l9hOop87EYWAydUZakJzL0OfV8,fQeN-RMQZsUrDha2LCJWOMFk1-EiXZxfTnBT8NEgY00,AQACAAE/WoT/0";
	private String uriB = "USK@R3Lp2s4jdX-3Q96c0A9530qg7JsvA9vi2K0hwY9wG-4,ipkgYftRpo0StBlYkJUawZhg~SO29NZIINseUtBhEfE,AQACAAE/WoT/0";
	private OwnIdentity a;
	private OwnIdentity b;


	@Override
	protected void setUp() throws Exception {
		super.setUp();
		
		a = new OwnIdentity(uriA, uriA, "A", true);
		b = new OwnIdentity(uriB, uriB, "B", true);
		mWoT.storeAndCommit(a);
		mWoT.storeAndCommit(b);
		
		Score score = new Score(a,b,100,1,40);
		mWoT.getDB().store(score);
		mWoT.getDB().commit();
		
		// TODO: Modify the test to NOT keep a reference to the identities as member variables so the followig also garbage collects them.
		flushCaches();
	}

	public void testScoreCreation() throws NotInTrustTreeException {
		
		Score score = mWoT.getScore(a, b);
		
		assertTrue(score.getScore() == 100);
		assertTrue(score.getRank() == 1);
		assertTrue(score.getCapacity() == 40);
		assertTrue(score.getTreeOwner() == a);
		assertTrue(score.getTarget() == b);
	}
	
	// TODO: Move to WoTTest
	public void testScorePersistence() throws MalformedURLException, UnknownIdentityException, NotInTrustTreeException {
		a = mWoT.getOwnIdentityByURI(uriA);
		b = mWoT.getOwnIdentityByURI(uriB);
		final Score originalScore = mWoT.getScore(a, b);
		
		mWoT.terminate();
		mWoT = null;
		
		flushCaches();
		
		mWoT = new WebOfTrust(getDatabaseFilename());
		a = mWoT.getOwnIdentityByURI(uriA);
		b = mWoT.getOwnIdentityByURI(uriB);
		final Score score = mWoT.getScore(a, b);
		
		assertSame(score, mWoT.getScore(a, b));
		assertNotSame(score, originalScore);
		assertEquals(originalScore, score);
	}
	
	public void testEquals() {
		final Score score = new Score(a, b, 100, 3, 2);
		
		do {
			try {
				Thread.sleep(1);
			} catch (InterruptedException e) { }
		} while(score.getDateOfCreation().equals(CurrentTimeUTC.get()));
		
		final Score equalScore = new Score(score.getTreeOwner().clone(), score.getTarget().clone(), score.getScore(), score.getRank(), score.getCapacity());
		
		assertEquals(score, score);
		assertEquals(score, equalScore);
		
		
		final Object[] inequalObjects = new Object[] {
			new Object(),
			new Score((OwnIdentity)score.getTarget(), score.getTreeOwner(), score.getScore(), score.getRank(), score.getCapacity()),
			new Score(score.getTreeOwner(), score.getTreeOwner(), score.getScore(), score.getRank(), score.getCapacity()),
			new Score((OwnIdentity)score.getTarget(), score.getTarget(), score.getScore(), score.getRank(), score.getCapacity()),
			new Score(score.getTreeOwner(), score.getTarget(), score.getScore()+1, score.getRank(), score.getCapacity()),
			new Score(score.getTreeOwner(), score.getTarget(), score.getScore(), score.getRank()+1, score.getCapacity()),
			new Score(score.getTreeOwner(), score.getTarget(), score.getScore(), score.getRank(), score.getCapacity()+1),
		};
		
		for(Object other : inequalObjects)
			assertFalse(score.equals(other));
	}
}