/**
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */
package plugins.WoT;

import java.io.File;

import junit.framework.TestCase;

import com.db4o.Db4o;
import com.db4o.ObjectContainer;

/**
 * @author Julien Cornuwel (batosai@freenetproject.org)
 */
public class ScoreTest extends TestCase {
	
	private String uriA = "USK@MF2Vc6FRgeFMZJ0s2l9hOop87EYWAydUZakJzL0OfV8,fQeN-RMQZsUrDha2LCJWOMFk1-EiXZxfTnBT8NEgY00,AQACAAE/WoT/0";
	private String uriB = "USK@R3Lp2s4jdX-3Q96c0A9530qg7JsvA9vi2K0hwY9wG-4,ipkgYftRpo0StBlYkJUawZhg~SO29NZIINseUtBhEfE,AQACAAE/WoT/0";
	
	private OwnIdentity a;
	private Identity b;
	
	private ObjectContainer db;
	private boolean firstRun = false;
	
	public ScoreTest(String name) {
		super(name);
	}
	
	protected void setUp() throws Exception {
		
		super.setUp();
		db = Db4o.openFile("scoreTest.db4o");
		
		try {
			a = OwnIdentity.getByURI(db, uriA);
			b = Identity.getByURI(db, uriB);
		} catch (UnknownIdentityException e) {
			a = new OwnIdentity(uriA, uriA, "A", "true", "test");
			b = new Identity(uriB, "B", "true", "test");
			db.store(a);
			db.store(b);
			firstRun = true;
			System.out.println("First run of the score test. Run it again to check data persistency");
		}
	}
	
	protected void tearDown() throws Exception {
		db.close();
		if(!firstRun) new File("scoreTest.db4o").delete();
	}
	
	/*
	 * You have to run 'ant' twice in order to really perform this test. The goal is to check if db4o 
	 * is able to find a Trust object by its Identities pointers, even across Database restarts. 
	 */
	public void testScore() throws DuplicateTrustException, InvalidParameterException, DuplicateScoreException, NotInTrustTreeException {
		Score score;
		
		if(firstRun) {
			score = new Score(a,b,100,1,40);
			db.store(score);
		} else {
			score = b.getScore(a, db);
		}
		
		assertTrue(score.getScore() == 100);
		assertTrue(score.getRank() == 1);
		assertTrue(score.getCapacity() == 40);
		assertTrue(score.getTreeOwner() == a);
		assertTrue(score.getTarget() == b);
	}
}