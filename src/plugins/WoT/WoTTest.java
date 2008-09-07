/**
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */
package plugins.WoT;

import java.io.File;
import java.net.MalformedURLException;

import com.db4o.Db4o;
import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;

import junit.framework.TestCase;

/**
 * @author Julien Cornuwel (batosai@freenetproject.org)
 */
public class WoTTest extends TestCase {
	
	private ObjectContainer db;
	
	private String uriA = "USK@MF2Vc6FRgeFMZJ0s2l9hOop87EYWAydUZakJzL0OfV8,fQeN-RMQZsUrDha2LCJWOMFk1-EiXZxfTnBT8NEgY00,AQACAAE/WoT/0";
	private String uriB = "USK@R3Lp2s4jdX-3Q96c0A9530qg7JsvA9vi2K0hwY9wG-4,ipkgYftRpo0StBlYkJUawZhg~SO29NZIINseUtBhEfE,AQACAAE/WoT/0";
	private String uriC = "USK@qd-hk0vHYg7YvK2BQsJMcUD5QSF0tDkgnnF6lnWUH0g,xTFOV9ddCQQk6vQ6G~jfL6IzRUgmfMcZJ6nuySu~NUc,AQACAAE/WoT/0";
		
	public WoTTest(String name) {
		super(name);
	}
	
	protected void setUp() throws Exception {
		
		super.setUp();
		db = Db4o.openFile("wotTest.db4o");
	}
	
	protected void tearDown() throws Exception {
		db.close();
		new File("wotTest.db4o").delete();
	}
	
	public void testInitTrustTree() throws DuplicateScoreException, NotInTrustTreeException, MalformedURLException, InvalidParameterException {
		
		OwnIdentity a = new OwnIdentity(uriA, uriA, "A", "true", "test");
		db.store(a);
		a.initTrustTree(db);
		
		assertTrue(Identity.getNbIdentities(db) == 0);
		assertTrue(OwnIdentity.getNbOwnIdentities(db) == 1);
		assertTrue(Trust.getNb(db) == 0);
		assertTrue(Score.getNb(db) == 1);

		assertTrue(a.getScore(a, db).getScore() == 100);
		assertTrue(a.getScore(a, db).getRank() == 0);
		assertTrue(a.getScore(a, db).getCapacity() == 100);
		assertTrue(a.getScore(a, db).getTreeOwner() == a);
		assertTrue(a.getScore(a, db).getTarget() == a);
		
		// Empty the database
		ObjectSet<Object> all = db.queryByExample(new Object());
		while(all.hasNext()) db.delete(all.next());
	}
	
	public void testSetTrust() throws DuplicateTrustException, InvalidParameterException, DuplicateScoreException, NotTrustedException, NotInTrustTreeException, MalformedURLException {

		OwnIdentity a = new OwnIdentity(uriA, uriA, "A", "true", "test");
		Identity b = new Identity(uriB, "B", "true", "test");
		db.store(a);
		db.store(b);
		
		// With A's trust tree not initialized, B shouldn't get a Score.
		a.setTrust(db, b, 10, "Foo");

		assertTrue(Identity.getNbIdentities(db) == 1);
		assertTrue(OwnIdentity.getNbOwnIdentities(db) == 1);
		assertTrue(Trust.getNb(db) == 1);
		assertTrue(Score.getNb(db) == 0);
		
		// Initialize A's trust tree and set the trust relationship
		a.initTrustTree(db);
		a.setTrust(db, b, 100, "Foo");
		
		// Check we have the correct number of objects
		assertTrue(Identity.getNbIdentities(db) == 1);
		assertTrue(OwnIdentity.getNbOwnIdentities(db) == 1);
		assertTrue(Trust.getNb(db) == 1);
		assertTrue(Score.getNb(db) == 2);
		
		// Check the Trust object
		assertTrue(b.getReceivedTrust(a, db).getTruster() == a);
		assertTrue(b.getReceivedTrust(a, db).getTrustee() == b);
		assertTrue(b.getReceivedTrust(a, db).getValue() == 100);
		assertTrue(b.getReceivedTrust(a, db).getComment().equals("Foo"));

		// Check a's Score object
		assertTrue(a.getScore(a, db).getScore() == 100);
		assertTrue(a.getScore(a, db).getRank() == 0);
		assertTrue(a.getScore(a, db).getCapacity() == 100);
		
		// Check B's Score object
		assertTrue(b.getScore(a, db).getScore() == 100);
		assertTrue(b.getScore(a, db).getRank() == 1);
		assertTrue(b.getScore(a, db).getCapacity() == 40);
		
		// Change the trust value and comment
		a.setTrust(db, b, 50, "Bar");
		
		// Check we have the correct number of objects
		assertTrue(Identity.getNbIdentities(db) == 1);
		assertTrue(OwnIdentity.getNbOwnIdentities(db) == 1);
		assertTrue(Trust.getNb(db) == 1);
		assertTrue(Score.getNb(db) == 2);
		
		// Check the Trust object
		assertTrue(b.getReceivedTrust(a, db).getTruster() == a);
		assertTrue(b.getReceivedTrust(a, db).getTrustee() == b);
		assertTrue(b.getReceivedTrust(a, db).getValue() == 50);
		assertTrue(b.getReceivedTrust(a, db).getComment().equals("Bar"));

		// Check a's Score object
		assertTrue(a.getScore(a, db).getScore() == 100);
		assertTrue(a.getScore(a, db).getRank() == 0);
		assertTrue(a.getScore(a, db).getCapacity() == 100);
		
		// Check B's Score object
		assertTrue(b.getScore(a, db).getScore() == 50);
		assertTrue(b.getScore(a, db).getRank() == 1);
		assertTrue(b.getScore(a, db).getCapacity() == 40);
		
		// Empty the database
		ObjectSet<Object> all = db.queryByExample(new Object());
		while(all.hasNext()) db.delete(all.next());
	}
	
	public void testRemoveTrust() throws MalformedURLException, InvalidParameterException, DuplicateScoreException, DuplicateTrustException, NotTrustedException, NotInTrustTreeException {
		OwnIdentity a = new OwnIdentity(uriA, uriA, "A", "true", "test");
		Identity b = new Identity(uriB, "B", "true", "test");
		Identity c = new Identity(uriC, "C", "true", "test");
		db.store(a);
		db.store(b);
		db.store(c);
		a.initTrustTree(db);
		a.setTrust(db, b, 100, "Foo");
		b.setTrust(db, c, 50, "Bar");
		
		// Check we have the correct number of objects
		assertTrue(OwnIdentity.getNbOwnIdentities(db) == 1);
		assertTrue(Identity.getNbIdentities(db) == 2);
		assertTrue(Trust.getNb(db) == 2);
		assertTrue(Score.getNb(db) == 3);
		
		// Check a's Score object
		assertTrue(a.getScore(a, db).getScore() == 100);
		assertTrue(a.getScore(a, db).getRank() == 0);
		assertTrue(a.getScore(a, db).getCapacity() == 100);
		
		// Check B's Score object
		assertTrue(b.getScore(a, db).getScore() == 100);
		assertTrue(b.getScore(a, db).getRank() == 1);
		assertTrue(b.getScore(a, db).getCapacity() == 40);
		
		// Check C's Score object
		assertTrue(c.getScore(a, db).getScore() == 20);
		assertTrue(c.getScore(a, db).getRank() == 2);
		assertTrue(c.getScore(a, db).getCapacity() == 16);
		
		a.setTrust(db, b, -1, "Bastard");
		
		// Check we have the correct number of objects
		assertTrue(OwnIdentity.getNbOwnIdentities(db) == 1);
		assertTrue(Identity.getNbIdentities(db) == 2);
		assertTrue(Trust.getNb(db) == 2);
		assertTrue(Score.getNb(db) == 2);
		
		// Check a's Score object
		assertTrue(a.getScore(a, db).getScore() == 100);
		assertTrue(a.getScore(a, db).getRank() == 0);
		assertTrue(a.getScore(a, db).getCapacity() == 100);
		
		// Check B's Score object
		assertTrue(b.getScore(a, db).getScore() == -1);
		assertTrue(b.getScore(a, db).getRank() == 1);
		assertTrue(b.getScore(a, db).getCapacity() == 0);
		
		// C should not have a score anymore
		try {
			c.getScore(a, db);
			fail();
		}
		catch (NotInTrustTreeException e) {}
		
		// Empty the database
		ObjectSet<Object> all = db.queryByExample(new Object());
		while(all.hasNext()) db.delete(all.next());
	}
	
	public void testTrustLoop() throws MalformedURLException, InvalidParameterException, DuplicateScoreException, DuplicateTrustException, NotInTrustTreeException {
		OwnIdentity a = new OwnIdentity(uriA, uriA, "A", "true", "test");
		Identity b = new Identity(uriB, "B", "true", "test");
		Identity c = new Identity(uriC, "C", "true", "test");
		db.store(a);
		db.store(b);
		db.store(c);
		a.initTrustTree(db);
		a.setTrust(db, b, 100, "Foo");
		b.setTrust(db, c, 50, "Bar");
		c.setTrust(db, a, 100, "Bleh");
		c.setTrust(db, b, 50, "Oops");
		
		// Check we have the correct number of objects
		assertTrue(OwnIdentity.getNbOwnIdentities(db) == 1);
		assertTrue(Identity.getNbIdentities(db) == 2);
		assertTrue(Trust.getNb(db) == 4);
		assertTrue(Score.getNb(db) == 3);
		
		// Check a's Score object
		assertTrue(a.getScore(a, db).getScore() == 100);
		assertTrue(a.getScore(a, db).getRank() == 0);
		assertTrue(a.getScore(a, db).getCapacity() == 100);
		
		// Check B's Score object
		assertTrue(b.getScore(a, db).getScore() == 108);
		assertTrue(b.getScore(a, db).getRank() == 1);
		assertTrue(b.getScore(a, db).getCapacity() == 40);
		
		// Check C's Score object
		assertTrue(c.getScore(a, db).getScore() == 20);
		assertTrue(c.getScore(a, db).getRank() == 2);
		assertTrue(c.getScore(a, db).getCapacity() == 16);
	}
	
	public void testOwnIndentitiesTrust() throws MalformedURLException, InvalidParameterException, DuplicateScoreException, DuplicateTrustException, NotTrustedException, NotInTrustTreeException {
		OwnIdentity a = new OwnIdentity(uriA, uriA, "A", "true", "test");
		OwnIdentity b = new OwnIdentity(uriB, uriB, "B", "true", "test");
		db.store(a);
		db.store(b);
		a.initTrustTree(db);
		b.initTrustTree(db);
		a.setTrust(db, b, 100, "Foo");
		b.setTrust(db, a, 100, "Bar");
		
		// Check we have the correct number of objects
		assertTrue(OwnIdentity.getNbOwnIdentities(db) == 2);
		assertTrue(Identity.getNbIdentities(db) == 0);
		assertTrue(Trust.getNb(db) == 2);
		assertTrue(Score.getNb(db) == 4);
		
		// Check a's own Score object
		assertTrue(a.getScore(a, db).getScore() == 100);
		assertTrue(a.getScore(a, db).getRank() == 0);
		assertTrue(a.getScore(a, db).getCapacity() == 100);
		
		// Check a's Score object
		assertTrue(a.getScore(b, db).getScore() == 100);
		assertTrue(a.getScore(b, db).getRank() == 1);
		assertTrue(a.getScore(b, db).getCapacity() == 40);
				
		// Check B's own Score object
		assertTrue(b.getScore(b, db).getScore() == 100);
		assertTrue(b.getScore(b, db).getRank() == 0);
		assertTrue(b.getScore(b, db).getCapacity() == 100);

		// Check B's Score object
		assertTrue(b.getScore(a, db).getScore() == 100);
		assertTrue(b.getScore(a, db).getRank() == 1);
		assertTrue(b.getScore(a, db).getCapacity() == 40);
	}
}
