/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WoT;

import java.net.MalformedURLException;

import plugins.WoT.Identity;
import plugins.WoT.OwnIdentity;
import plugins.WoT.Score;
import plugins.WoT.WoT;
import plugins.WoT.exceptions.NotInTrustTreeException;
import plugins.WoT.exceptions.UnknownIdentityException;

/**
 * @author xor (xor@freenetproject.org), Julien Cornuwel (batosai@freenetproject.org)
 */
public class ScoreTest extends DatabaseBasedTest {
	
	private String uriA = "USK@MF2Vc6FRgeFMZJ0s2l9hOop87EYWAydUZakJzL0OfV8,fQeN-RMQZsUrDha2LCJWOMFk1-EiXZxfTnBT8NEgY00,AQACAAE/WoT/0";
	private String uriB = "USK@R3Lp2s4jdX-3Q96c0A9530qg7JsvA9vi2K0hwY9wG-4,ipkgYftRpo0StBlYkJUawZhg~SO29NZIINseUtBhEfE,AQACAAE/WoT/0";
	private OwnIdentity a;
	private Identity b;


	@Override
	protected void setUp() throws Exception {
		super.setUp();
		
		a = new OwnIdentity(uriA, uriA, "A", true);
		b = new Identity(uriB, "B", true);
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
	
	public void testScorePersistence() throws MalformedURLException, UnknownIdentityException, NotInTrustTreeException {
		
		mWoT.terminate();
		mWoT = null;
		
		flushCaches();
		
		mWoT = new WoT(getDatabaseFilename());
		
		a = mWoT.getOwnIdentityByURI(uriA);
		b = mWoT.getIdentityByURI(uriB);
		Score score = mWoT.getScore(a, b);
		
		assertTrue(score.getScore() == 100);
		assertTrue(score.getRank() == 1);
		assertTrue(score.getCapacity() == 40);
		assertTrue(score.getTreeOwner() == a);
		assertTrue(score.getTarget() == b);
	}
}