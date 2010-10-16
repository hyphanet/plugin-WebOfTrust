/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WoT;

import java.net.MalformedURLException;

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

	private final String uriO = "USK@8VTguDZehMlShIb7Q~F1wYpOnDK7pSZVwrGArACP~04,MK0wfPtNud~nWyp~oy0Kr1~kFuYfJ9~LlxNribWD4Us,AQACAAE/WoT/0";
	private final String uriS = "USK@hAOgofNsQEbT~aRqGuXwt8vI7tOeQVCrcIHrD9PvS6g,fG7LHRJhczCAApOwgaXNJO41L8wRIZj9oN37LSLZZY8,AQACAAE/WoT/0";
	private final String uriA = "USK@MF2Vc6FRgeFMZJ0s2l9hOop87EYWAydUZakJzL0OfV8,fQeN-RMQZsUrDha2LCJWOMFk1-EiXZxfTnBT8NEgY00,AQACAAE/WoT/0";
	private final String uriB = "USK@R3Lp2s4jdX-3Q96c0A9530qg7JsvA9vi2K0hwY9wG-4,ipkgYftRpo0StBlYkJUawZhg~SO29NZIINseUtBhEfE,AQACAAE/WoT/0";
	private final String uriC = "USK@qd-hk0vHYg7YvK2BQsJMcUD5QSF0tDkgnnF6lnWUH0g,xTFOV9ddCQQk6vQ6G~jfL6IzRUgmfMcZJ6nuySu~NUc,AQACAAE/WoT/0";
	private final String uriM1 = "USK@XoOIYo6blZDb6qb2iaBKJVMSehnvxVnxkgFCtbT4yw4,92NJVhKYBK3B4oJkcSmDaau53vbzPMKxws9dC~fagFU,AQACAAE/WoT/0";
	private final String uriM2 = "USK@rhiNEDWcDXNvkT7R3K1zkr2FgMjW~6DudrAbuYbaY-w,Xl4nOxOzRyzHpEQwu--nb3PaLFSK2Ym9c~Un0rIdne4,AQACAAE/WoT/0";
	private final String uriM3 = "USK@9c57T1yNOi7aeK-6lorACBcOH4cC-vgZ6Ky~-f9mcUI,anOcB7Z05g55oViCa3LcClrXNcQcmR3SBooN4qssuPs,AQACAAE/WoT/0";

	public void testInitTrustTree() throws MalformedURLException, InvalidParameterException, UnknownIdentityException, NotInTrustTreeException {
		mWoT.createOwnIdentity(uriA, uriA, "A", true, "Test"); /* This also initializes the trust tree */
		
		flushCaches();
		assertEquals(0, mWoT.getAllNonOwnIdentities().size());
		assertEquals(1, mWoT.getAllOwnIdentities().size());
		assertEquals(0, mWoT.getAllTrusts().size());
		assertEquals(1, mWoT.getAllScores().size());
		
		flushCaches();
		OwnIdentity a = mWoT.getOwnIdentityByURI(uriA);

		Score score = mWoT.getScore(a,a);
		assertEquals(Integer.MAX_VALUE, score.getScore());
		assertEquals(0, score.getRank());
		assertEquals(100, score.getCapacity());
		assertSame(a, score.getTreeOwner());
		assertSame(a, score.getTarget());
	}
	
	public void testSetTrust1() throws InvalidParameterException, MalformedURLException {
		OwnIdentity a = new OwnIdentity(uriA, uriA, "A", true);
		Identity b = new Identity(uriB, "B", true);
		/* We store A manually instead of using createOwnIdentity() so that the WoT does not initialize it's trust tree (it does not have a score for itself). */
		mWoT.storeAndCommit(a);
		mWoT.storeAndCommit(b);
		
		// With A's trust tree not initialized, B shouldn't get a Score.
		mWoT.setTrust(a, b, (byte)10, "Foo");
		
		flushCaches();
		assertEquals(1, mWoT.getAllNonOwnIdentities().size());
		assertEquals(1, mWoT.getAllOwnIdentities().size());
		assertEquals(1, mWoT.getAllTrusts().size());
		assertEquals(0, mWoT.getAllScores().size());
	}
	
	public void testSetTrust2() throws MalformedURLException, InvalidParameterException, DuplicateTrustException, NotTrustedException, NotInTrustTreeException {

		OwnIdentity a = mWoT.createOwnIdentity(uriA, uriA, "A", true, "Test"); /* Initializes it's trust tree */
		Identity b = new Identity(uriB, "B", true);
		mWoT.storeAndCommit(b);
		
		mWoT.setTrust(a, b, (byte)100, "Foo");
		
		// Check we have the correct number of objects
		flushCaches();
		assertEquals(1, mWoT.getAllNonOwnIdentities().size());
		assertEquals(1, mWoT.getAllOwnIdentities().size());
		assertEquals(1, mWoT.getAllTrusts().size());
		assertEquals(2, mWoT.getAllScores().size());
		
		// Check the Trust object
		flushCaches();
		Trust t = mWoT.getTrust(a, b);
		assertSame(a, t.getTruster());
		assertSame(b, t.getTrustee());
		assertEquals(100, t.getValue());
		assertEquals("Foo", t.getComment());
		
		// Check a's Score object
		flushCaches();
		Score scoreA = mWoT.getScore(a, a);
		assertEquals(Integer.MAX_VALUE, scoreA.getScore());
		assertEquals(0, scoreA.getRank());
		assertEquals(100, scoreA.getCapacity());
		
		// Check B's Score object
		flushCaches();
		Score scoreB = mWoT.getScore(a, b);
		assertEquals(100, scoreB.getScore());
		assertEquals(1, scoreB.getRank());
		assertEquals(40, scoreB.getCapacity());
		
		// Change the trust value and comment
		mWoT.setTrust(a, b, (byte)50, "Bar");
		
		// Check we have the correct number of objects
		flushCaches();
		assertEquals(1, mWoT.getAllNonOwnIdentities().size());
		assertEquals(1, mWoT.getAllOwnIdentities().size());
		assertEquals(1, mWoT.getAllTrusts().size());
		assertEquals(2, mWoT.getAllScores().size());
		
		// Check the Trust object
		flushCaches();
		t = mWoT.getTrust(a, b);
		assertSame(a, t.getTruster());
		assertSame(b, t.getTrustee());
		assertEquals(50, t.getValue());
		assertEquals("Bar", t.getComment());
		
		// Check a's Score object
		flushCaches();
		scoreA = mWoT.getScore(a, a);
		assertEquals(Integer.MAX_VALUE, scoreA.getScore());
		assertEquals(0, scoreA.getRank());
		assertEquals(100, scoreA.getCapacity());

		// Check B's Score object
		flushCaches();
		scoreB = mWoT.getScore(a, b);
		assertEquals(50, scoreB.getScore());
		assertEquals(1, scoreB.getRank());
		assertEquals(40, scoreB.getCapacity());
	}
	
	public void testRemoveTrust() throws MalformedURLException, InvalidParameterException, UnknownIdentityException,
		NotInTrustTreeException {
		
		ExtObjectContainer db = mWoT.getDB();
		
		OwnIdentity a = mWoT.createOwnIdentity(uriA, uriA, "A", true, "Test");
		Identity b = new Identity(uriB, "B", true); mWoT.storeAndCommit(b);
		Identity c = new Identity(uriC, "C", true); mWoT.storeAndCommit(c);
		
		mWoT.setTrust(a, b, (byte)100, "Foo");
		mWoT.setTrustWithoutCommit(b, c, (byte)50, "Bar"); // There is no committing setTrust() for non-OwnIdentity (trust-list import uses rollback() on error)
		db.commit();
		
		// Check we have the correct number of objects
		flushCaches();
		assertEquals(1, mWoT.getAllOwnIdentities().size());
		assertEquals(2, mWoT.getAllNonOwnIdentities().size());
		assertEquals(2, mWoT.getAllTrusts().size());
		assertEquals(3, mWoT.getAllScores().size());
		
		// Check a's Score object
		flushCaches();
		Score scoreA = mWoT.getScore(a, a);
		assertEquals(Integer.MAX_VALUE, scoreA.getScore());
		assertEquals(0, scoreA.getRank());
		assertEquals(100, scoreA.getCapacity());
		
		// Check B's Score object
		flushCaches();
		Score scoreB = mWoT.getScore(a, b);
		assertEquals(100, scoreB.getScore());
		assertEquals(1, scoreB.getRank());
		assertEquals(40, scoreB.getCapacity());
		
		// Check C's Score object
		flushCaches();
		Score scoreC = mWoT.getScore(a, c);
		assertEquals(20, scoreC.getScore());
		assertEquals(2, scoreC.getRank());
		assertEquals(16, scoreC.getCapacity());
		
		mWoT.setTrust(a, b, (byte)-1, "Bastard");
		
		// Check we have the correct number of objects
		flushCaches();
		assertEquals(1, mWoT.getAllOwnIdentities().size());
		assertEquals(2, mWoT.getAllNonOwnIdentities().size());
		assertEquals(2, mWoT.getAllTrusts().size());
		assertEquals(2, mWoT.getAllScores().size());
		
		// Check a's Score object
		flushCaches();
		scoreA = mWoT.getScore(a, a);
		assertEquals(Integer.MAX_VALUE, scoreA.getScore());
		assertEquals(0, scoreA.getRank());
		assertEquals(100, scoreA.getCapacity());
		
		// Check B's Score object
		flushCaches();
		scoreB = mWoT.getScore(a, b);
		assertEquals(-1, scoreB.getScore());
		assertEquals(Integer.MAX_VALUE, scoreB.getRank());
		assertEquals(0, scoreB.getCapacity());
		
		// C should not have a score anymore
		try {
			mWoT.getScore(a, c);
			fail();
		}
		catch (NotInTrustTreeException e) {}
	}
	
	public void testTrustLoop() throws MalformedURLException, InvalidParameterException, NotInTrustTreeException {
		ExtObjectContainer db = mWoT.getDB();
		
		OwnIdentity a = mWoT.createOwnIdentity(uriA, uriA, "A", true, "Test");
		Identity b = new Identity(uriB, "B", true); mWoT.storeAndCommit(b);
		Identity c = new Identity(uriC, "C", true); mWoT.storeAndCommit(c);
		
		mWoT.setTrust(a, b, (byte)100, "Foo");
		mWoT.setTrustWithoutCommit(b, c, (byte)50, "Bar"); // There is no committing setTrust() for non-OwnIdentity (trust-list import uses rollback() on error)
		mWoT.setTrustWithoutCommit(c, a, (byte)100, "Bleh");
		mWoT.setTrustWithoutCommit(c, b, (byte)50, "Oops");
		db.commit();
		
		// Check we have the correct number of objects
		flushCaches();
		assertEquals(1, mWoT.getAllOwnIdentities().size());
		assertEquals(2, mWoT.getAllNonOwnIdentities().size());
		assertEquals(4, mWoT.getAllTrusts().size());
		assertEquals(3, mWoT.getAllScores().size());

		// Check a's Score object
		flushCaches();
		Score scoreA = mWoT.getScore(a, a);
		assertEquals(Integer.MAX_VALUE, scoreA.getScore());
		assertEquals(0, scoreA.getRank());
		assertEquals(100, scoreA.getCapacity());
		
		// Check B's Score object
		flushCaches();
		Score scoreB = mWoT.getScore(a, b);
		assertEquals(100, scoreB.getScore()); // 100 and not 108 because own trust values override calculated scores.
		assertEquals(1, scoreB.getRank());
		assertEquals(40, scoreB.getCapacity());
		
		// Check C's Score object
		flushCaches();
		Score scoreC = mWoT.getScore(a, c);
		assertEquals(20, scoreC.getScore());
		assertEquals(2, scoreC.getRank());
		assertEquals(16, scoreC.getCapacity());
	}
	
	public void testOwnIndentitiesTrust() throws MalformedURLException, InvalidParameterException, NotInTrustTreeException {
		OwnIdentity a = mWoT.createOwnIdentity(uriA, uriA, "A", true, "Test");
		OwnIdentity b = mWoT.createOwnIdentity(uriB, uriB, "B", true, "Test");

		mWoT.setTrust(a, b, (byte)100, "Foo");
		mWoT.setTrust(b, a, (byte)80, "Bar");
		
		// Check we have the correct number of objects
		flushCaches();
		assertEquals(2, mWoT.getAllOwnIdentities().size());
		assertEquals(0, mWoT.getAllNonOwnIdentities().size());
		assertEquals(2, mWoT.getAllTrusts().size());
		assertEquals(4, mWoT.getAllScores().size());
		
		// Check a's own Score object
		flushCaches();
		Score scoreA = mWoT.getScore(a, a);
		assertEquals(Integer.MAX_VALUE, scoreA.getScore());
		assertEquals(0, scoreA.getRank());
		assertEquals(100, scoreA.getCapacity());
		
		// Check a's Score object
		flushCaches();
		Score scoreAfromB = mWoT.getScore(b, a);
		assertEquals(80, scoreAfromB.getScore());
		assertEquals(1, scoreAfromB.getRank());
		assertEquals(40, scoreAfromB.getCapacity());
				
		// Check B's own Score object
		flushCaches();
		Score scoreB = mWoT.getScore(b, b);
		assertEquals(Integer.MAX_VALUE, scoreB.getScore());
		assertEquals(0, scoreB.getRank());
		assertEquals(100, scoreB.getCapacity());

		// Check B's Score object
		flushCaches();
		Score scoreBfromA = mWoT.getScore(a, b);
		assertEquals(100, scoreBfromA.getScore());
		assertEquals(1, scoreBfromA.getRank());
		assertEquals(40, scoreBfromA.getCapacity());
	}
	
	/**
	 * Test whether the same scores are calculated if trust lists are fetched in different order.
	 */
	public void testStability() throws Exception {
		ExtObjectContainer db = mWoT.getDB();
			
		OwnIdentity o = mWoT.createOwnIdentity(uriO, uriO, "O", true, "Test"); // Tree owner
		Identity s = new Identity(uriS, "S", true); mWoT.storeAndCommit(s); // Seed identity
		Identity a = new Identity(uriA, "A", true); mWoT.storeAndCommit(a); // A / B are downloaded in different orders.
		Identity b = new Identity(uriB, "B", true); mWoT.storeAndCommit(b);
		Identity c = new Identity(uriC, "C", true); mWoT.storeAndCommit(c);
		
		// You get all the identities from the seed identity.
		mWoT.setTrust(o, s, (byte)100, "I trust the seed identity.");
		// A trust of 4 gives them a minimal score of 1. Their score must be minimal so they can influence each other's score (by assigning trust values)
		// enough to be negative. We don't use 0 so we catch special problems with conditions based on positive/negative decision.
		mWoT.setTrustWithoutCommit(s, a, (byte)4, "Minimal trust");
		mWoT.setTrustWithoutCommit(s, b, (byte)4, "Minimal trust");
		mWoT.setTrustWithoutCommit(s, c, (byte)4, "Minimal trust");
		db.commit();

		// First you download A. A distrusts B and trusts C
		mWoT.setTrustWithoutCommit(a, b, (byte)-100, "Distrust");
		mWoT.setTrustWithoutCommit(a, c, (byte)100, "Trust");
		db.commit();
		
		// Then you download B who distrusts A and C
		mWoT.setTrustWithoutCommit(b, a, (byte)-100, "Distrust");
		mWoT.setTrustWithoutCommit(b, c, (byte)-100, "Trust");
		db.commit();
		
		final Score oldScoreA = mWoT.getScore(o, a);
		final Score oldScoreB = mWoT.getScore(o, b);
		final Score oldScoreC = mWoT.getScore(o, c);
		
		// Now we want a fresh WoT.
		tearDown();
		setUp();
		db = mWoT.getDB();
		
		o = mWoT.createOwnIdentity(uriO, uriO, "O", true, "Test");
		s = new Identity(uriS, "S", true); mWoT.storeAndCommit(s);		
		a = new Identity(uriA, "A", true); mWoT.storeAndCommit(a);
		b = new Identity(uriB, "B", true); mWoT.storeAndCommit(b);
		c = new Identity(uriC, "C", true); mWoT.storeAndCommit(c);
		
		// You get all the identities from the seed identity.
		mWoT.setTrust(o, s, (byte)100, "I trust the seed identity.");
		// A trust of 4 gives them a minimal score of 1. Their score must be minimal so they can influence each other's score (by assigning trust values)
		// enough to be negative. We don't use 0 so we catch special problems with conditions based on positive/negative decision.
		mWoT.setTrustWithoutCommit(s, a, (byte)4, "Minimal trust");
		mWoT.setTrustWithoutCommit(s, b, (byte)4, "Minimal trust");
		mWoT.setTrustWithoutCommit(s, c, (byte)4, "Minimal trust");
		db.commit();
		
		// Alternative download order: First B...
		mWoT.setTrustWithoutCommit(b, a, (byte)-100, "Distrust");
		mWoT.setTrustWithoutCommit(b, c, (byte)-100, "Trust");
		db.commit();
		
		// .. then A
		mWoT.setTrustWithoutCommit(a, b, (byte)-100, "Distrust");
		mWoT.setTrustWithoutCommit(a, c, (byte)100, "Trust");
		db.commit();
		
		final Score newScoreA = mWoT.getScore(o, a);
		final Score newScoreB = mWoT.getScore(o, b);
		final Score newScoreC = mWoT.getScore(o, c);
		
		// Test whether the test has correctly flushed the database
		assertNotSame(newScoreA, oldScoreA);
		assertNotSame(newScoreB, oldScoreB);
		assertNotSame(newScoreC, oldScoreC);
		
		// Score overrides equals() so this tests whether value, rank and capacity are correct.
		assertEquals(oldScoreA, newScoreA);
		assertEquals(oldScoreB, newScoreB);
		assertEquals(oldScoreC, newScoreC);
	}


	/**
	 * Test whether removing trusts works properly.
	 */
	public void testRemoveTrust2() throws Exception {
		//same setup routine as testStability
		ExtObjectContainer db = mWoT.getDB();
			
		OwnIdentity o = mWoT.createOwnIdentity(uriO, uriO, "O", true, "Test"); // Tree owner
		Identity s = new Identity(uriS, "S", true); mWoT.storeAndCommit(s); // Seed identity
		Identity a = new Identity(uriA, "A", true); mWoT.storeAndCommit(a); 
		Identity b = new Identity(uriB, "B", true); mWoT.storeAndCommit(b);
		Identity c = new Identity(uriC, "C", true); mWoT.storeAndCommit(c);
		
		// You get all the identities from the seed identity.
		mWoT.setTrust(o, s, (byte)100, "I trust the seed identity.");
		// A trust of 4 gives them a minimal score of 1. Their score must be minimal so they can influence each other's score (by assigning trust values)
		// enough to be negative. We don't use 0 so we catch special problems with conditions based on positive/negative decision.
		mWoT.setTrustWithoutCommit(s, a, (byte)4, "Minimal trust");
		mWoT.setTrustWithoutCommit(s, b, (byte)4, "Minimal trust");
		mWoT.setTrustWithoutCommit(s, c, (byte)4, "Minimal trust");
		db.commit();

		// First you download A. A distrusts B and trusts C
		mWoT.setTrustWithoutCommit(a, b, (byte)-100, "Distrust");
		mWoT.setTrustWithoutCommit(a, c, (byte)100, "Trust");
		db.commit();
		
		// Then you download B who distrusts A and C
		mWoT.setTrustWithoutCommit(b, a, (byte)-100, "Distrust");
		mWoT.setTrustWithoutCommit(b, c, (byte)-100, "Trust");
		db.commit();
		flushCaches();

		mWoT.setTrust(o, s, (byte)-1, "Removed trust for seed identity.");
		flushCaches();

		try {
			mWoT.getScore(o, a);
			fail();
		}
		catch (NotInTrustTreeException e) {}
		try {
			mWoT.getScore(o, b);
			fail();
		}
		catch (NotInTrustTreeException e) {}
		try {
			mWoT.getScore(o, c);
			fail();
		}
		catch (NotInTrustTreeException e) {}
	}

	/**
	 * Test whether spammer resistance works properly.
	 */
	public void testMalicious() throws Exception {
		//same setup routine as testStability
		ExtObjectContainer db = mWoT.getDB();
			
		OwnIdentity o = mWoT.createOwnIdentity(uriO, uriO, "O", true, "Test"); // Tree owner
		Identity s = new Identity(uriS, "S", true); mWoT.storeAndCommit(s); // Seed identity
		Identity a = new Identity(uriA, "A", true); mWoT.storeAndCommit(a); 
		Identity b = new Identity(uriB, "B", true); mWoT.storeAndCommit(b);
		
		Identity m1 = new Identity(uriM1, "M1", true); mWoT.storeAndCommit(m1); //malicious identity
		Identity m2 = new Identity(uriM2, "M2", true); mWoT.storeAndCommit(m2); //malicious identity
		Identity m3 = new Identity(uriM3, "M3", true); mWoT.storeAndCommit(m3); //malicious identity
		
		// You get all the identities from the seed identity.
		mWoT.setTrust(o, s, (byte)100, "I trust the seed identity.");

		mWoT.setTrustWithoutCommit(s, a, (byte)100, "Trusted");
		mWoT.setTrustWithoutCommit(s, b, (byte)100, "Trusted");
		mWoT.setTrustWithoutCommit(s, m1, (byte)-100, "M1 is malicious.");
		mWoT.setTrustWithoutCommit(s, m2, (byte)-100, "M2 is malicious.");
		mWoT.setTrustWithoutCommit(s, m3, (byte)-100, "M3 is malicious.");

		mWoT.setTrustWithoutCommit(m1, a, (byte)-100, "Maliciously set");
		mWoT.setTrustWithoutCommit(m1, b, (byte)-100, "Maliciously set");
		mWoT.setTrustWithoutCommit(m2, a, (byte)-100, "Maliciously set");
		mWoT.setTrustWithoutCommit(m2, b, (byte)-100, "Maliciously set");
		mWoT.setTrustWithoutCommit(m3, a, (byte)-100, "Maliciously set");
		mWoT.setTrustWithoutCommit(m3, b, (byte)-100, "Maliciously set");

		db.commit();
		flushCaches();

		boolean wasCorrect = mWoT.computeAllScoresWithoutCommit();
		flushCaches();
		boolean isConsistent = mWoT.computeAllScoresWithoutCommit();

		int scoreA = mWoT.getScore(o, a).getScore();
		int scoreB = mWoT.getScore(o, b).getScore();
		assertTrue("A score: " + scoreA + " wasCorrect: " + wasCorrect + " isConsistent: " + isConsistent, scoreA > 0);
		assertTrue("B score: " + scoreB + " wasCorrect: " + wasCorrect + " isConsistent: " + isConsistent, scoreB > 0);
		assertTrue("Consistency check.", isConsistent);
		assertTrue("Correctness check.", wasCorrect);
	}

	/**
	 * Test whether the algorithm is stable.
	 */
	public void testStability2() throws Exception {
		ExtObjectContainer db = mWoT.getDB();
			
		OwnIdentity o = mWoT.createOwnIdentity(uriO, uriO, "O", true, "Test"); // Tree owner
		Identity s = new Identity(uriS, "S", true); mWoT.storeAndCommit(s); // Seed identity
		Identity a = new Identity(uriA, "A", true); mWoT.storeAndCommit(a); 
		Identity b = new Identity(uriB, "B", true); mWoT.storeAndCommit(b);
		Identity c = new Identity(uriC, "C", true); mWoT.storeAndCommit(c);
		
		// You get all the identities from the seed identity.
		mWoT.setTrust(o, s, (byte)100, "I trust the seed identity.");

		mWoT.setTrustWithoutCommit(s, a, (byte)4, "Minimal trust");
		mWoT.setTrustWithoutCommit(a, b, (byte)100, "trust");
		mWoT.setTrustWithoutCommit(b, c, (byte)100, "trust");
		mWoT.setTrustWithoutCommit(c, a, (byte)-100, "distrust");
		db.commit();

		flushCaches();

		final Score oldScoreA = mWoT.getScore(o, a).clone();
		final Score oldScoreB = mWoT.getScore(o, b).clone();
		final Score oldScoreC = mWoT.getScore(o, c).clone();

		//assertTrue("a score: " + oldScoreA + " c score: " + oldScoreC, false);

		//force some recomputation
		
		mWoT.setTrustWithoutCommit(s, a, (byte)0, "Minimal trust");
		mWoT.setTrustWithoutCommit(a, b, (byte)0, "trust");
		mWoT.setTrustWithoutCommit(b, c, (byte)0, "trust");
		mWoT.setTrustWithoutCommit(c, a, (byte)0, "distrust");
		db.commit();
		flushCaches();

		mWoT.setTrustWithoutCommit(s, a, (byte)4, "Minimal trust");
		mWoT.setTrustWithoutCommit(c, a, (byte)-100, "distrust");
		mWoT.setTrustWithoutCommit(b, c, (byte)100, "trust");
		mWoT.setTrustWithoutCommit(a, b, (byte)100, "trust");
		db.commit();
		flushCaches();

		final Score newScoreA = mWoT.getScore(o, a);
		final Score newScoreB = mWoT.getScore(o, b);
		final Score newScoreC = mWoT.getScore(o, c);

		assertEquals(oldScoreA, newScoreA);
		assertEquals(oldScoreB, newScoreB);
		assertEquals(oldScoreC, newScoreC);

	}

	/**
	 * Test whether spammer resistance works properly.
	 * Like testMalicious except the malicious nodes trust each other.
	 */
	public void testMalicious2() throws Exception {
		//same setup routine as testStability
		ExtObjectContainer db = mWoT.getDB();
			
		OwnIdentity o = mWoT.createOwnIdentity(uriO, uriO, "O", true, "Test"); // Tree owner
		Identity s = new Identity(uriS, "S", true); mWoT.storeAndCommit(s); // Seed identity
		Identity a = new Identity(uriA, "A", true); mWoT.storeAndCommit(a); 
		Identity b = new Identity(uriB, "B", true); mWoT.storeAndCommit(b);
		
		Identity m1 = new Identity(uriM1, "M1", true); mWoT.storeAndCommit(m1); //malicious identity
		Identity m2 = new Identity(uriM2, "M2", true); mWoT.storeAndCommit(m2); //malicious identity
		Identity m3 = new Identity(uriM3, "M3", true); mWoT.storeAndCommit(m3); //malicious identity
		
		// You get all the identities from the seed identity.
		mWoT.setTrust(o, s, (byte)100, "I trust the seed identity.");

		mWoT.setTrustWithoutCommit(s, a, (byte)100, "Trusted");
		mWoT.setTrustWithoutCommit(s, b, (byte)100, "Trusted");
		mWoT.setTrustWithoutCommit(s, m1, (byte)-100, "M1 is malicious.");
		mWoT.setTrustWithoutCommit(s, m2, (byte)-100, "M2 is malicious.");
		mWoT.setTrustWithoutCommit(s, m3, (byte)-100, "M3 is malicious.");

		mWoT.setTrustWithoutCommit(m1, a, (byte)-100, "Maliciously set");
		mWoT.setTrustWithoutCommit(m1, b, (byte)-100, "Maliciously set");
		mWoT.setTrustWithoutCommit(m2, a, (byte)-100, "Maliciously set");
		mWoT.setTrustWithoutCommit(m2, b, (byte)-100, "Maliciously set");
		mWoT.setTrustWithoutCommit(m3, a, (byte)-100, "Maliciously set");
		mWoT.setTrustWithoutCommit(m3, b, (byte)-100, "Maliciously set");
		mWoT.setTrustWithoutCommit(m1, m2, (byte)100, "Collusion");
		mWoT.setTrustWithoutCommit(m2, m3, (byte)100, "Collusion");
		mWoT.setTrustWithoutCommit(m3, m1, (byte)100, "Collusion");

		db.commit();
		flushCaches();
		mWoT.computeAllScoresWithoutCommit();
		db.commit();

		int scoreA = mWoT.getScore(o, a).getScore();
		int scoreB = mWoT.getScore(o, b).getScore();
		assertTrue("A score: " + scoreA, scoreA > 0);
		assertTrue("B score: " + scoreB, scoreB > 0);
	}

	/** Another test of resistance to malicious identities.
	  */
	public void testMalicious3() throws Exception {
		ExtObjectContainer db = mWoT.getDB();
			
		OwnIdentity o = mWoT.createOwnIdentity(uriO, uriO, "O", true, "Test"); // Tree owner
		Identity s = new Identity(uriS, "S", true); mWoT.storeAndCommit(s); // Seed identity
		Identity a = new Identity(uriA, "A", true); mWoT.storeAndCommit(a); 
		Identity b = new Identity(uriB, "B", true); mWoT.storeAndCommit(b);
		Identity m1 = new Identity(uriM1, "M1", true); mWoT.storeAndCommit(m1); //known malicious identity
		Identity m2 = new Identity(uriM2, "M2", true); mWoT.storeAndCommit(m2); //known malicious identity
		
		// You get all the identities from the seed identity.
		mWoT.setTrust(o, s, (byte)100, "I trust the seed identity.");

		mWoT.setTrustWithoutCommit(s, a, (byte)100, "Trusted");
		mWoT.setTrustWithoutCommit(s, m1, (byte)-100, "M1 is malicious.");
		mWoT.setTrustWithoutCommit(s, m2, (byte)-100, "M2 is malicious.");

		mWoT.setTrustWithoutCommit(a, b, (byte)20, "minimal trust (eg web interface)");
		mWoT.setTrustWithoutCommit(b, a, (byte)20, "minimal trust (eg web interface)");

		mWoT.setTrustWithoutCommit(a, m1, (byte) 0, "captcha");
		mWoT.setTrustWithoutCommit(a, m2, (byte) 0, "captcha");

		mWoT.setTrustWithoutCommit(m1, m2, (byte)100, "Collusion");
		mWoT.setTrustWithoutCommit(m2, m1, (byte)100, "Collusion");

		mWoT.setTrustWithoutCommit(m1, b, (byte)-100, "Maliciously set");
		mWoT.setTrustWithoutCommit(m2, b, (byte)-100, "Maliciously set");

		db.commit();
		mWoT.computeAllScoresWithoutCommit();
		db.commit();
		flushCaches();

		int scoreM1 = mWoT.getScore(o, m1).getScore();
		int scoreM2 = mWoT.getScore(o, m2).getScore();
		assertTrue("M1 score: " + scoreM1, scoreM1 < 0);
		assertTrue("M2 score: " + scoreM2, scoreM2 < 0);
		int scoreA = mWoT.getScore(o, a).getScore();
		int scoreB = mWoT.getScore(o, b).getScore();
		assertTrue("A score: " + scoreA, scoreA > 0);
		assertTrue("B score: " + scoreB, scoreB > 0);
	}
}
