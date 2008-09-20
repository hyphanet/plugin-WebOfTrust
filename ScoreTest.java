/**
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */
package plugins.WoT;

import java.io.File;
import java.net.MalformedURLException;

import plugins.WoT.exceptions.DuplicateIdentityException;
import plugins.WoT.exceptions.DuplicateScoreException;
import plugins.WoT.exceptions.NotInTrustTreeException;
import plugins.WoT.exceptions.UnknownIdentityException;

import junit.framework.TestCase;

import com.db4o.Db4o;
import com.db4o.ObjectContainer;

/**
 * @author Julien Cornuwel (batosai@freenetproject.org)
 */
public class ScoreTest extends TestCase {
	
	private String uriA = "USK@MF2Vc6FRgeFMZJ0s2l9hOop87EYWAydUZakJzL0OfV8,fQeN-RMQZsUrDha2LCJWOMFk1-EiXZxfTnBT8NEgY00,AQACAAE/WoT/0";
	private String uriB = "USK@R3Lp2s4jdX-3Q96c0A9530qg7JsvA9vi2K0hwY9wG-4,ipkgYftRpo0StBlYkJUawZhg~SO29NZIINseUtBhEfE,AQACAAE/WoT/0";
	OwnIdentity a;
	Identity b;
	
	private ObjectContainer db;
	
	public ScoreTest(String name) {
		super(name);
	}
	
	protected void setUp() throws Exception {
		
		super.setUp();
		db = Db4o.openFile("scoreTest.db4o");
		
		a = new OwnIdentity(uriA, uriA, "A", "true");
		b = new Identity(uriB, "B", "true");
		db.store(a);
		db.store(b);
		Score score = new Score(a,b,100,1,40);
		db.store(score);
		db.commit();
	}
	
	protected void tearDown() throws Exception {
		db.close();
		new File("scoreTest.db4o").delete();
	}
	
	public void testScoreCreation() throws NotInTrustTreeException, DuplicateScoreException  {
		
		Score score = b.getScore(a, db);
		
		assertTrue(score.getScore() == 100);
		assertTrue(score.getRank() == 1);
		assertTrue(score.getCapacity() == 40);
		assertTrue(score.getTreeOwner() == a);
		assertTrue(score.getTarget() == b);
	}
	
	public void testScorePersistence() throws NotInTrustTreeException, DuplicateScoreException, MalformedURLException, UnknownIdentityException, DuplicateIdentityException {
		
		db.close();
		
		System.gc();
		System.runFinalization();
		
		db = Db4o.openFile("scoreTest.db4o");
		
		a = OwnIdentity.getByURI(db, uriA);
		b = Identity.getByURI(db, uriB);
		Score score = b.getScore(a, db);
		
		assertTrue(score.getScore() == 100);
		assertTrue(score.getRank() == 1);
		assertTrue(score.getCapacity() == 40);
		assertTrue(score.getTreeOwner() == a);
		assertTrue(score.getTarget() == b);
	}
}