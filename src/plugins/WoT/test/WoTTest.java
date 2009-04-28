/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WoT.test;

import java.net.MalformedURLException;

import plugins.WoT.Identity;
import plugins.WoT.OwnIdentity;
import plugins.WoT.Score;
import plugins.WoT.Trust;
import plugins.WoT.exceptions.DuplicateScoreException;
import plugins.WoT.exceptions.DuplicateTrustException;
import plugins.WoT.exceptions.InvalidParameterException;
import plugins.WoT.exceptions.NotInTrustTreeException;
import plugins.WoT.exceptions.NotTrustedException;
import plugins.WoT.exceptions.UnknownIdentityException;

import com.db4o.ext.ExtObjectContainer;

/**
 * @author xor (xor@freenetproject.org), Julien Cornuwel (batosai@freenetproject.org)
 */
public class WoTTest extends DatabaseBasedTest {

	private String uriA = "USK@MF2Vc6FRgeFMZJ0s2l9hOop87EYWAydUZakJzL0OfV8,fQeN-RMQZsUrDha2LCJWOMFk1-EiXZxfTnBT8NEgY00,AQACAAE/WoT/0";
	private String uriB = "USK@R3Lp2s4jdX-3Q96c0A9530qg7JsvA9vi2K0hwY9wG-4,ipkgYftRpo0StBlYkJUawZhg~SO29NZIINseUtBhEfE,AQACAAE/WoT/0";
	private String uriC = "USK@qd-hk0vHYg7YvK2BQsJMcUD5QSF0tDkgnnF6lnWUH0g,xTFOV9ddCQQk6vQ6G~jfL6IzRUgmfMcZJ6nuySu~NUc,AQACAAE/WoT/0";

	/* FIXME: Add some logic to make db4o deactivate everything which is not used before loading the objects from the db!
	 * Otherwise these tests might not be sufficient. 
	 * Put this logic into the DatabaseBasedTest base class. */
	
	public void testInitTrustTree() throws MalformedURLException, InvalidParameterException, UnknownIdentityException, NotInTrustTreeException {
		mWoT.createOwnIdentity(uriA, uriA, "A", true, "Test"); /* This also initializes the trust tree */
		
		assertTrue(mWoT.getAllNonOwnIdentities().size() == 0);
		assertTrue(mWoT.getAllOwnIdentities().size() == 1);
		assertTrue(mWoT.getAllTrusts().size() == 0);
		assertTrue(mWoT.getAllScores().size() == 1);
		
		OwnIdentity a = mWoT.getOwnIdentityByURI(uriA);

		Score score = mWoT.getScore(a,a);
		assertTrue(score.getScore() == 100);
		assertTrue(score.getRank() == 0);
		assertTrue(score.getCapacity() == 100);
		assertTrue(score.getTreeOwner() == a);
		assertTrue(score.getTarget() == a);
		
		// Empty the database
		/* FIXME: This is not needed because DatabaseBasedTest does that in setUp(), right? 
		ObjectSet<Object> all = db.queryByExample(new Object());
		while(all.hasNext()) db.delete(all.next());
		*/
	}
	
	public void testSetTrust1() throws InvalidParameterException, MalformedURLException {
		OwnIdentity a = new OwnIdentity(uriA, uriA, "A", true);
		Identity b = new Identity(uriB, "B", true);
		/* We store them manually so that the WoT does not initialize the trust tree */
		mWoT.getDB().store(a, 5);
		mWoT.getDB().store(b, 5);
		
		// With A's trust tree not initialized, B shouldn't get a Score.
		mWoT.setTrust(a, b, (byte)10, "Foo");

		assertTrue(mWoT.getAllNonOwnIdentities().size() == 1);
		assertTrue(mWoT.getAllOwnIdentities().size() == 1);
		assertTrue(mWoT.getAllTrusts().size() == 1);
		assertTrue(mWoT.getAllScores().size() == 0);
	}
	
	public void testSetTrust2() throws MalformedURLException, InvalidParameterException, DuplicateTrustException, NotTrustedException, NotInTrustTreeException {

		OwnIdentity a = mWoT.createOwnIdentity(uriA, uriA, "A", true, "Test"); /* Initializes it's trust tree */
		Identity b = new Identity(uriB, "B", true);
		mWoT.getDB().store(b, 5);
		

		mWoT.setTrust(a, b, (byte)100, "Foo");
		
		// Check we have the correct number of objects
		assertTrue(mWoT.getAllNonOwnIdentities().size() == 1);
		assertTrue(mWoT.getAllOwnIdentities().size() == 1);
		assertTrue(mWoT.getAllTrusts().size() == 1);
		assertTrue(mWoT.getAllScores().size() == 2);
		
		// Check the Trust object
		Trust t = mWoT.getTrust(a, b);
		assertTrue(t.getTruster() == a);
		assertTrue(t.getTrustee() == b);
		assertTrue(t.getValue() == 100);
		assertTrue(t.getComment().equals("Foo"));

		// Check a's Score object
		Score scoreA = mWoT.getScore(a, a);
		assertTrue(scoreA.getScore() == 100);
		assertTrue(scoreA.getRank() == 0);
		assertTrue(scoreA.getCapacity() == 100);
		
		// Check B's Score object
		Score scoreB = mWoT.getScore(a, b);
		assertTrue(scoreB.getScore() == 100);
		assertTrue(scoreB.getRank() == 1);
		assertTrue(scoreB.getCapacity() == 40);
		
		// Change the trust value and comment
		mWoT.setTrust(a, b, (byte)50, "Bar");
		
		// Check we have the correct number of objects
		assertTrue(mWoT.getAllNonOwnIdentities().size() == 1);
		assertTrue(mWoT.getAllOwnIdentities().size() == 1);
		assertTrue(mWoT.getAllTrusts().size() == 1);
		assertTrue(mWoT.getAllScores().size() == 2);
		
		// Check the Trust object
		
		t = mWoT.getTrust(a, b);
		assertTrue(t.getTruster() == a);
		assertTrue(t.getTrustee() == b);
		assertTrue(t.getValue() == 50);
		assertTrue(t.getComment().equals("Bar"));

		// Check a's Score object
		scoreA = mWoT.getScore(a, a);
		assertTrue(scoreA.getScore() == 100);
		assertTrue(scoreA.getRank() == 0);
		assertTrue(scoreA.getCapacity() == 100);

		// Check B's Score object
		scoreB = mWoT.getScore(a, b);
		assertTrue(scoreB.getScore() == 50);
		assertTrue(scoreB.getRank() == 1);
		assertTrue(scoreB.getCapacity() == 40);
		
		// Empty the database
		/* FIXME: This is not needed because DatabaseBasedTest does that in setUp(), right? 
		ObjectSet<Object> all = db.queryByExample(new Object());
		while(all.hasNext()) db.delete(all.next());
		*/
	}
	
	public void testRemoveTrust() throws MalformedURLException, InvalidParameterException, UnknownIdentityException,
		NotInTrustTreeException {
		
		ExtObjectContainer db = mWoT.getDB();
		
		OwnIdentity a = mWoT.createOwnIdentity(uriA, uriA, "A", true, "Test");
		Identity b = new Identity(uriB, "B", true); /* Do not init the trust tree */
		Identity c = new Identity(uriC, "C", true); /* Do not init the trust tree */
		db.store(b);
		db.store(c);
		
		mWoT.setTrust(a, b, (byte)100, "Foo");
		mWoT.setTrust(b, c, (byte)50, "Bar");
		
		// Check we have the correct number of objects
		assertTrue(mWoT.getAllOwnIdentities().size() == 1);
		assertTrue(mWoT.getAllNonOwnIdentities().size() == 2);
		assertTrue(mWoT.getAllTrusts().size() == 2);
		assertTrue(mWoT.getAllScores().size() == 3);
		
		// Check a's Score object
		Score scoreA = mWoT.getScore(a, a);
		assertTrue(scoreA.getScore() == 100);
		assertTrue(scoreA.getRank() == 0);
		assertTrue(scoreA.getCapacity() == 100);
		
		// Check B's Score object
		Score scoreB = mWoT.getScore(a, b);
		assertTrue(scoreB.getScore() == 100);
		assertTrue(scoreB.getRank() == 1);
		assertTrue(scoreB.getCapacity() == 40);
		
		// Check C's Score object
		Score scoreC = mWoT.getScore(a, c);
		assertTrue(scoreC.getScore() == 20);
		assertTrue(scoreC.getRank() == 2);
		assertTrue(scoreC.getCapacity() == 16);
		
		mWoT.setTrust(a, b, (byte)-1, "Bastard");
		
		// Check we have the correct number of objects
		assertTrue(mWoT.getAllOwnIdentities().size() == 1);
		assertTrue(mWoT.getAllNonOwnIdentities().size() == 2);
		assertTrue(mWoT.getAllTrusts().size() == 2);
		assertTrue(mWoT.getAllScores().size() == 2);
		
		// Check a's Score object
		scoreA = mWoT.getScore(a, a);
		assertTrue(scoreA.getScore() == 100);
		assertTrue(scoreA.getRank() == 0);
		assertTrue(scoreA.getCapacity() == 100);
		
		// Check B's Score object
		scoreB = mWoT.getScore(a, b);
		assertTrue(scoreB.getScore() == -1);
		assertTrue(scoreB.getRank() == 1);
		assertTrue(scoreB.getCapacity() == 0);
		
		// C should not have a score anymore
		try {
			mWoT.getScore(a, c);
			fail();
		}
		catch (NotInTrustTreeException e) {}
		
		// Empty the database
		/* FIXME: This is not needed because DatabaseBasedTest does that in setUp(), right? 
		ObjectSet<Object> all = db.queryByExample(new Object());
		while(all.hasNext()) db.delete(all.next());
		*/
	}
	
	public void testTrustLoop() throws MalformedURLException, InvalidParameterException, NotInTrustTreeException {
		ExtObjectContainer db = mWoT.getDB();
		
		OwnIdentity a = mWoT.createOwnIdentity(uriA, uriA, "A", true, "Test");
		Identity b = new Identity(uriB, "B", true); /* Do not init the trust tree */
		Identity c = new Identity(uriC, "C", true); /* Do not init the trust tree */
		db.store(b);
		db.store(c);
		
		mWoT.setTrust(a, b, (byte)100, "Foo");
		mWoT.setTrust(b, c, (byte)50, "Bar");
		mWoT.setTrust(c, a, (byte)100, "Bleh");
		mWoT.setTrust(c, b, (byte)50, "Oops");
		
		// Check we have the correct number of objects
		assertTrue(mWoT.getAllOwnIdentities().size() == 1);
		assertTrue(mWoT.getAllNonOwnIdentities().size() == 2);
		assertTrue(mWoT.getAllTrusts().size() == 4);
		assertTrue(mWoT.getAllScores().size() == 3);

		// Check a's Score object
		Score scoreA = mWoT.getScore(a, a);
		assertTrue(scoreA.getScore() == 100);
		assertTrue(scoreA.getRank() == 0);
		assertTrue(scoreA.getCapacity() == 100);
		
		// Check B's Score object
		Score scoreB = mWoT.getScore(a, b);
		assertTrue(scoreB.getScore() == 108);
		assertTrue(scoreB.getRank() == 1);
		assertTrue(scoreB.getCapacity() == 40);
		
		// Check C's Score object
		Score scoreC = mWoT.getScore(a, c);
		assertTrue(scoreC.getScore() == 20);
		assertTrue(scoreC.getRank() == 2);
		assertTrue(scoreC.getCapacity() == 16);
	}
	
	public void testOwnIndentitiesTrust() throws MalformedURLException, InvalidParameterException, NotInTrustTreeException {
		OwnIdentity a = mWoT.createOwnIdentity(uriA, uriA, "A", true, "Test");
		OwnIdentity b = mWoT.createOwnIdentity(uriB, uriB, "B", true, "Test");

		mWoT.setTrust(a, b, (byte)100, "Foo");
		mWoT.setTrust(b, a, (byte)100, "Bar");
		
		// Check we have the correct number of objects
		assertTrue(mWoT.getAllOwnIdentities().size() == 2);
		assertTrue(mWoT.getAllNonOwnIdentities().size() == 0);
		assertTrue(mWoT.getAllTrusts().size() == 2);
		assertTrue(mWoT.getAllScores().size() == 4);
		
		// Check a's own Score object
		Score scoreA = mWoT.getScore(a, a);
		assertTrue(scoreA.getScore() == 100);
		assertTrue(scoreA.getRank() == 0);
		assertTrue(scoreA.getCapacity() == 100);
		
		// Check a's Score object
		Score scoreAfromB = mWoT.getScore(b, a);
		assertTrue(scoreAfromB.getScore() == 100);
		assertTrue(scoreAfromB.getRank() == 1);
		assertTrue(scoreAfromB.getCapacity() == 40);
				
		// Check B's own Score object
		Score scoreB = mWoT.getScore(a, a);
		assertTrue(scoreB.getScore() == 100);
		assertTrue(scoreB.getRank() == 0);
		assertTrue(scoreB.getCapacity() == 100);

		// Check B's Score object
		Score scoreBfromA = mWoT.getScore(b, a);
		assertTrue(scoreBfromA.getScore() == 100);
		assertTrue(scoreBfromA.getRank() == 1);
		assertTrue(scoreBfromA.getCapacity() == 40);
	}
}
