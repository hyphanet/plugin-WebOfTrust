/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import java.net.MalformedURLException;

import plugins.WebOfTrust.exceptions.NotInTrustTreeException;
import plugins.WebOfTrust.exceptions.UnknownIdentityException;
import freenet.keys.FreenetURI;
import freenet.support.CurrentTimeUTC;

/**
 * @author xor (xor@freenetproject.org)
 * @author Julien Cornuwel (batosai@freenetproject.org)
 */
public class ScoreTestOld extends AbstractJUnit3BaseTest {
	
	private String requestUriA = "USK@Pn5K9Lt4pE0v5I3TDF40yPkDeE6IJP-nZ~zxxEq76Yc,t3vIf26txb~g6yP1f5cANe1Cb98uzcQBqCAG1PO03OQ,AQACAAE/WebOfTrust/0";
	private String insertUriA = "USK@f3bEbhW5xmevbzAE2sfAsioNQezrKeak6vUYWhHAoLk,t3vIf26txb~g6yP1f5cANe1Cb98uzcQBqCAG1PO03OQ,AQECAAE/WebOfTrust/0";
	private String requestUriB = "USK@a6dD~md7InpruJ3B98RiqRwPJ9L3w~N6l5Ad14neUVU,WZkyt7jgLFJLnVpQ7q7vWBCkz8t8O9JbU1Qsg9bLvdo,AQACAAE/WebOfTrust/0";
	private String insertUriB = "USK@CSkCsPeEqkRNbO~xtEpL~gMHzEydwwP6ofJBMMArZX4,WZkyt7jgLFJLnVpQ7q7vWBCkz8t8O9JbU1Qsg9bLvdo,AQECAAE/WebOfTrust/0";
	private OwnIdentity a;
	private OwnIdentity b;


	@Override
	protected void setUp() throws Exception {
		super.setUp();
		
		a = mWoT.createOwnIdentity(new FreenetURI(insertUriA), "A", true, null);
		b = mWoT.createOwnIdentity(new FreenetURI(insertUriB), "B", true, null);
		
		// Not used for our purposes. Merely justifies the Score object whose creation is our
		// actual goal. This justification is done to prevent failure of
		// assertTrue(mWoT.verifyAndCorrectStoredScores()), which will be run after the tests
		// by AbstractJUnit3BaseTest.tearDown().
		Trust trust = new Trust(mWoT, a, b, (byte)100, ""); trust.storeWithoutCommit();
		
		Score score = new Score(mWoT, a,b,100,1,40); score.storeWithoutCommit();
		Persistent.checkedCommit(mWoT.getDatabase(), this);
		
		// TODO: Modify the test to NOT keep a reference to the identities as member variables so the followig also garbage collects them.
		flushCaches();
	}

}
