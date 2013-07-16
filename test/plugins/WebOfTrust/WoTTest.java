/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import java.net.MalformedURLException;
import java.util.Date;

import plugins.WebOfTrust.Identity.FetchState;
import plugins.WebOfTrust.exceptions.DuplicateTrustException;
import plugins.WebOfTrust.exceptions.InvalidParameterException;
import plugins.WebOfTrust.exceptions.NotInTrustTreeException;
import plugins.WebOfTrust.exceptions.NotTrustedException;
import plugins.WebOfTrust.exceptions.UnknownIdentityException;

import com.db4o.ObjectSet;
import com.db4o.ext.ExtObjectContainer;

import freenet.keys.FreenetURI;
import freenet.support.CurrentTimeUTC;

/**
 * @author xor (xor@freenetproject.org)
 * @author Julien Cornuwel (batosai@freenetproject.org)
 */
public class WoTTest extends DatabaseBasedTest {

	private final String requestUriO = "USK@sdFxM0Z4zx4-gXhGwzXAVYvOUi6NRfdGbyJa797bNAg,ZP4aASnyZax8nYOvCOlUebegsmbGQIXfVzw7iyOsXEc,AQACAAE/WebOfTrust/0";
	private final String insertUriO = "USK@ZTeIa1g4T3OYCdUFfHrFSlRnt5coeFFDCIZxWSb7abs,ZP4aASnyZax8nYOvCOlUebegsmbGQIXfVzw7iyOsXEc,AQECAAE/WebOfTrust/0"; 
	private final String requestUriS = "USK@hAOgofNsQEbT~aRqGuXwt8vI7tOeQVCrcIHrD9PvS6g,fG7LHRJhczCAApOwgaXNJO41L8wRIZj9oN37LSLZZY8,AQACAAE/WoT/0";
	private final String requestUriA = "SSK@uKbou7HXlFrQWMIMxOqYqdVcgPpTAeSaJtoAHtBk~Tg,yz5HdAA36MBOlmumfEFukNPwP1vHjwiWGy-GCMTIuT8,AQACAAE/WebOfTrust/0";
	private final String insertUriA = "USK@AJy2oHQ-W7wXVFmfYHY2igmC0SUEMgIetxL2HbNqaXT0,yz5HdAA36MBOlmumfEFukNPwP1vHjwiWGy-GCMTIuT8,AQECAAE/WebOfTrust/0";
	private final String requestUriB = "USK@9SWa5sYwQm2O738SPLva0wpHd7KECrOe8iQdts8auXI,Ond06NVRidGaLrvpbi~gIo5eigsSebVWPHEto7lGPgA,AQACAAE/WebOfTrust/0";
	private final String insertUriB = "USK@Rcb3gXZXynQOJmDNqKMEht87sr3mtTYl9wsyblTpt0k,Ond06NVRidGaLrvpbi~gIo5eigsSebVWPHEto7lGPgA,AQECAAE/WebOfTrust/0";
	private final String requestUriC = "USK@qd-hk0vHYg7YvK2BQsJMcUD5QSF0tDkgnnF6lnWUH0g,xTFOV9ddCQQk6vQ6G~jfL6IzRUgmfMcZJ6nuySu~NUc,AQACAAE/WoT/0";
	private final String requestUriM1 = "USK@XoOIYo6blZDb6qb2iaBKJVMSehnvxVnxkgFCtbT4yw4,92NJVhKYBK3B4oJkcSmDaau53vbzPMKxws9dC~fagFU,AQACAAE/WoT/0";
	private final String requestUriM2 = "USK@rhiNEDWcDXNvkT7R3K1zkr2FgMjW~6DudrAbuYbaY-w,Xl4nOxOzRyzHpEQwu--nb3PaLFSK2Ym9c~Un0rIdne4,AQACAAE/WoT/0";
	private final String requestUriM3 = "USK@9c57T1yNOi7aeK-6lorACBcOH4cC-vgZ6Ky~-f9mcUI,anOcB7Z05g55oViCa3LcClrXNcQcmR3SBooN4qssuPs,AQACAAE/WoT/0";

	public void testInitTrustTree() throws MalformedURLException, InvalidParameterException, UnknownIdentityException, NotInTrustTreeException {
		mWoT.createOwnIdentity(new FreenetURI(insertUriA), "A", true, "Test"); /* This also initializes the trust tree */
		
		flushCaches();
		assertEquals(0, mWoT.getAllNonOwnIdentities().size());
		assertEquals(1, mWoT.getAllOwnIdentities().size());
		assertEquals(0, mWoT.getAllTrusts().size());
		assertEquals(1, mWoT.getAllScores().size());
		
		flushCaches();
		OwnIdentity a = mWoT.getOwnIdentityByURI(requestUriA);

		Score score = mWoT.getScore(a,a);
		assertEquals(Integer.MAX_VALUE, score.getScore());
		assertEquals(0, score.getRank());
		assertEquals(100, score.getCapacity());
		assertSame(a, score.getTruster());
		assertSame(a, score.getTrustee());
	}
	
	public void testSetTrust1() throws InvalidParameterException, MalformedURLException {
		/* We store A manually instead of using createOwnIdentity() so that the WoT does not initialize it's trust tree (it does not have a score for itself). */
		OwnIdentity a = new OwnIdentity(mWoT, insertUriA, "A", true); a.storeAndCommit();
		Identity b = new Identity(mWoT, requestUriB, "B", true); b.storeAndCommit();
		
		// With A's trust tree not initialized, B shouldn't get a Score.
		mWoT.setTrust(a, b, (byte)10, "Foo");
		
		flushCaches();
		assertEquals(1, mWoT.getAllNonOwnIdentities().size());
		assertEquals(1, mWoT.getAllOwnIdentities().size());
		assertEquals(1, mWoT.getAllTrusts().size());
		assertEquals(0, mWoT.getAllScores().size());
	}
	
	public void testSetTrust2() throws MalformedURLException, InvalidParameterException, DuplicateTrustException, NotTrustedException, NotInTrustTreeException {

		OwnIdentity a = mWoT.createOwnIdentity(new FreenetURI(insertUriA), "A", true, "Test"); /* Initializes it's trust tree */
		
		Identity b = new Identity(mWoT, requestUriB, "B", true); b.storeAndCommit();
		
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
		
		OwnIdentity a = mWoT.createOwnIdentity(new FreenetURI(insertUriA), "A", true, "Test");
		
		Identity b = new Identity(mWoT, requestUriB, "B", true); b.storeAndCommit();
		Identity c = new Identity(mWoT, requestUriC, "C", true); c.storeAndCommit();
		
		mWoT.setTrust(a, b, (byte)100, "Foo");
		// There is no committing setTrust() for non-OwnIdentity (trust-list import uses rollback() on error)
		mWoT.setTrustWithoutCommit(b, c, (byte)50, "Bar");
		
		Persistent.checkedCommit(mWoT.getDatabase(), this);
		
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
		OwnIdentity a = mWoT.createOwnIdentity(new FreenetURI(insertUriA), "A", true, "Test");
		
		Identity b = new Identity(mWoT, requestUriB, "B", true); b.storeAndCommit();
		Identity c = new Identity(mWoT, requestUriC, "C", true); c.storeAndCommit();
		
		mWoT.setTrust(a, b, (byte)100, "Foo");
		mWoT.setTrustWithoutCommit(b, c, (byte)50, "Bar"); // There is no committing setTrust() for non-OwnIdentity (trust-list import uses rollback() on error)
		mWoT.setTrustWithoutCommit(c, a, (byte)100, "Bleh");
		mWoT.setTrustWithoutCommit(c, b, (byte)50, "Oops");
		Persistent.checkedCommit(mWoT.getDatabase(), this);
		
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
		OwnIdentity a = mWoT.createOwnIdentity(new FreenetURI(insertUriA), "A", true, "Test");
		OwnIdentity b = mWoT.createOwnIdentity(new FreenetURI(insertUriB), "B", true, "Test");

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
		ExtObjectContainer db = mWoT.getDatabase();
			
		OwnIdentity o = mWoT.createOwnIdentity(new FreenetURI(insertUriO), "O", true, "Test"); // Tree owner
		Identity s = new Identity(mWoT, requestUriS, "S", true); s.storeAndCommit(); // Seed identity
		// A / B are downloaded in different orders.
		Identity a = new Identity(mWoT, requestUriA, "A", true); a.storeAndCommit();
		Identity b = new Identity(mWoT, requestUriB, "B", true); b.storeAndCommit();
		Identity c = new Identity(mWoT, requestUriC, "C", true); c.storeAndCommit();
		
		// You get all the identities from the seed identity.
		mWoT.setTrust(o, s, (byte)100, "I trust the seed identity.");
		// A trust of 4 gives them a minimal score of 1. Their score must be minimal so they can influence each other's score (by assigning trust values)
		// enough to be negative. We don't use 0 so we catch special problems with conditions based on positive/negative decision.
		mWoT.setTrustWithoutCommit(s, a, (byte)4, "Minimal trust");
		mWoT.setTrustWithoutCommit(s, b, (byte)4, "Minimal trust");
		mWoT.setTrustWithoutCommit(s, c, (byte)4, "Minimal trust");
		Persistent.checkedCommit(db, this);

		// First you download A. A distrusts B and trusts C
		mWoT.setTrustWithoutCommit(a, b, (byte)-100, "Distrust");
		mWoT.setTrustWithoutCommit(a, c, (byte)100, "Trust");
		Persistent.checkedCommit(db, this);
		
		// Then you download B who distrusts A and C
		mWoT.setTrustWithoutCommit(b, a, (byte)-100, "Distrust");
		mWoT.setTrustWithoutCommit(b, c, (byte)-100, "Trust");
		Persistent.checkedCommit(db, this);
		
		final Score oldScoreA = mWoT.getScore(o, a);
		final Score oldScoreB = mWoT.getScore(o, b);
		final Score oldScoreC = mWoT.getScore(o, c);
		
		oldScoreA.checkedActivate(10);
		oldScoreB.checkedActivate(10);
		oldScoreC.checkedActivate(10);
		
		// Now we want a fresh WoT.
		tearDown();
		setUp();
		db = mWoT.getDatabase();
		
		o = mWoT.createOwnIdentity(new FreenetURI(insertUriO), "O", true, "Test");
		s = new Identity(mWoT, requestUriS, "S", true); s.storeAndCommit();
		a = new Identity(mWoT, requestUriA, "A", true); a.storeAndCommit();
		b = new Identity(mWoT, requestUriB, "B", true); b.storeAndCommit();
		c = new Identity(mWoT, requestUriC, "C", true); c.storeAndCommit();
		
		// You get all the identities from the seed identity.
		mWoT.setTrust(o, s, (byte)100, "I trust the seed identity.");
		// A trust of 4 gives them a minimal score of 1. Their score must be minimal so they can influence each other's score (by assigning trust values)
		// enough to be negative. We don't use 0 so we catch special problems with conditions based on positive/negative decision.
		mWoT.setTrustWithoutCommit(s, a, (byte)4, "Minimal trust");
		mWoT.setTrustWithoutCommit(s, b, (byte)4, "Minimal trust");
		mWoT.setTrustWithoutCommit(s, c, (byte)4, "Minimal trust");
		Persistent.checkedCommit(db, this);
		
		// Alternative download order: First B...
		mWoT.setTrustWithoutCommit(b, a, (byte)-100, "Distrust");
		mWoT.setTrustWithoutCommit(b, c, (byte)-100, "Trust");
		Persistent.checkedCommit(db, this);
		
		// .. then A
		mWoT.setTrustWithoutCommit(a, b, (byte)-100, "Distrust");
		mWoT.setTrustWithoutCommit(a, c, (byte)100, "Trust");
		Persistent.checkedCommit(db, this);
		
		final Score newScoreA = mWoT.getScore(o, a);
		final Score newScoreB = mWoT.getScore(o, b);
		final Score newScoreC = mWoT.getScore(o, c);
		
		oldScoreA.initializeTransient(mWoT); // Prevent DatabaseClosedException from getters
		oldScoreB.initializeTransient(mWoT);
		oldScoreC.initializeTransient(mWoT);
		
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
		ExtObjectContainer db = mWoT.getDatabase();
			
		OwnIdentity o = mWoT.createOwnIdentity(new FreenetURI(insertUriO), "O", true, "Test"); // Tree owner
		Identity s = new Identity(mWoT, requestUriS, "S", true); s.storeAndCommit(); // Seed identity
		Identity a = new Identity(mWoT, requestUriA, "A", true); a.storeAndCommit();
		Identity b = new Identity(mWoT, requestUriB, "B", true); b.storeAndCommit();
		Identity c = new Identity(mWoT, requestUriC, "C", true); c.storeAndCommit();
		
		// You get all the identities from the seed identity.
		mWoT.setTrust(o, s, (byte)100, "I trust the seed identity.");
		// A trust of 4 gives them a minimal score of 1. Their score must be minimal so they can influence each other's score (by assigning trust values)
		// enough to be negative. We don't use 0 so we catch special problems with conditions based on positive/negative decision.
		mWoT.setTrustWithoutCommit(s, a, (byte)4, "Minimal trust");
		mWoT.setTrustWithoutCommit(s, b, (byte)4, "Minimal trust");
		mWoT.setTrustWithoutCommit(s, c, (byte)4, "Minimal trust");
		Persistent.checkedCommit(db, this);

		// First you download A. A distrusts B and trusts C
		mWoT.setTrustWithoutCommit(a, b, (byte)-100, "Distrust");
		mWoT.setTrustWithoutCommit(a, c, (byte)100, "Trust");
		Persistent.checkedCommit(db, this);
		
		// Then you download B who distrusts A and C
		mWoT.setTrustWithoutCommit(b, a, (byte)-100, "Distrust");
		mWoT.setTrustWithoutCommit(b, c, (byte)-100, "Trust");
		Persistent.checkedCommit(db, this);
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
			
		OwnIdentity o = mWoT.createOwnIdentity(new FreenetURI(insertUriO), "O", true, "Test"); // Tree owner
		Identity s = new Identity(mWoT, requestUriS, "S", true); s.storeAndCommit(); // Seed identity
		Identity a = new Identity(mWoT, requestUriA, "A", true); a.storeAndCommit();
		Identity b = new Identity(mWoT, requestUriB, "B", true); b.storeAndCommit();
		
		Identity m1 = new Identity(mWoT, requestUriM1, "M1", true); m1.storeAndCommit(); //malicious identity
		Identity m2 = new Identity(mWoT, requestUriM2, "M2", true); m2.storeAndCommit(); //malicious identity
		Identity m3 = new Identity(mWoT, requestUriM3, "M3", true); m3.storeAndCommit(); //malicious identity
		
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

		Persistent.checkedCommit(mWoT.getDatabase(), this);
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
		ExtObjectContainer db = mWoT.getDatabase();
		
		OwnIdentity o = mWoT.createOwnIdentity(new FreenetURI(insertUriO), "O", true, "Test"); // Tree owner
		Identity s = new Identity(mWoT, requestUriS, "S", true); s.storeAndCommit(); // Seed identity
		Identity a = new Identity(mWoT, requestUriA, "A", true); a.storeAndCommit();
		Identity b = new Identity(mWoT, requestUriB, "B", true); b.storeAndCommit();
		Identity c = new Identity(mWoT, requestUriC, "C", true); c.storeAndCommit();
		
		// You get all the identities from the seed identity.
		mWoT.setTrust(o, s, (byte)100, "I trust the seed identity.");

		mWoT.setTrustWithoutCommit(s, a, (byte)4, "Minimal trust");
		mWoT.setTrustWithoutCommit(a, b, (byte)100, "trust");
		mWoT.setTrustWithoutCommit(b, c, (byte)100, "trust");
		mWoT.setTrustWithoutCommit(c, a, (byte)-100, "distrust");
		Persistent.checkedCommit(db, this);

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
		Persistent.checkedCommit(db, this);
		flushCaches();

		mWoT.setTrustWithoutCommit(s, a, (byte)4, "Minimal trust");
		mWoT.setTrustWithoutCommit(c, a, (byte)-100, "distrust");
		mWoT.setTrustWithoutCommit(b, c, (byte)100, "trust");
		mWoT.setTrustWithoutCommit(a, b, (byte)100, "trust");
		Persistent.checkedCommit(db, this);
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
		ExtObjectContainer db = mWoT.getDatabase();
			
		OwnIdentity o = mWoT.createOwnIdentity(new FreenetURI(insertUriO), "O", true, "Test"); // Tree owner
		Identity s = new Identity(mWoT, requestUriS, "S", true); s.storeAndCommit(); // Seed identity
		Identity a = new Identity(mWoT, requestUriA, "A", true); a.storeAndCommit();
		Identity b = new Identity(mWoT, requestUriB, "B", true); b.storeAndCommit();
		
		Identity m1 = new Identity(mWoT, requestUriM1, "M1", true); m1.storeAndCommit(); //malicious identity
		Identity m2 = new Identity(mWoT, requestUriM2, "M2", true); m2.storeAndCommit(); //malicious identity
		Identity m3 = new Identity(mWoT, requestUriM3, "M3", true); m3.storeAndCommit(); //malicious identity
		
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

		Persistent.checkedCommit(db, this);
		flushCaches();
		mWoT.computeAllScoresWithoutCommit();
		Persistent.checkedCommit(db, this);

		int scoreA = mWoT.getScore(o, a).getScore();
		int scoreB = mWoT.getScore(o, b).getScore();
		assertTrue("A score: " + scoreA, scoreA > 0);
		assertTrue("B score: " + scoreB, scoreB > 0);
	}

	/** Another test of resistance to malicious identities.
	  */
	public void testMalicious3() throws Exception {
		ExtObjectContainer db = mWoT.getDatabase();
			
		OwnIdentity o = mWoT.createOwnIdentity(new FreenetURI(insertUriO), "O", true, "Test"); // Tree owner		
		Identity s = new Identity(mWoT, requestUriS, "S", true); s.storeAndCommit(); // Seed identity
		Identity a = new Identity(mWoT, requestUriA, "A", true); a.storeAndCommit();
		Identity b = new Identity(mWoT, requestUriB, "B", true); b.storeAndCommit();
		
		Identity m1 = new Identity(mWoT, requestUriM1, "M1", true); m1.storeAndCommit(); //known malicious identity
		Identity m2 = new Identity(mWoT, requestUriM2, "M2", true); m2.storeAndCommit(); //known malicious identity
		
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

		Persistent.checkedCommit(db, this);
		mWoT.computeAllScoresWithoutCommit();
		Persistent.checkedCommit(db, this);
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
	
	public void testGetGivenTrustsSortedDescendingByLastSeen() throws MalformedURLException, InvalidParameterException, InterruptedException {
		OwnIdentity o = mWoT.createOwnIdentity(new FreenetURI(insertUriO), "O", true, "Test"); // Tree owner

		Identity a = new Identity(mWoT, requestUriA, "A", true); Thread.sleep(10); a.onFetched(); a.storeAndCommit();
		Identity b = new Identity(mWoT, requestUriB, "B", true); Thread.sleep(10); b.onFetched(); b.storeAndCommit();
		Identity c = new Identity(mWoT, requestUriC, "C", true); Thread.sleep(10); c.onFetched(); c.storeAndCommit();
		
		mWoT.setTrust(o, a, (byte)0, "");
		mWoT.setTrust(o, b, (byte)1, "");
		mWoT.setTrust(o, c, (byte)2, "");
		
		{
			ObjectSet<Trust> abc = mWoT.getGivenTrustsSortedDescendingByLastSeen(o);
			assertTrue(abc.hasNext()); assertSame(c, abc.next().getTrustee());
			assertTrue(abc.hasNext()); assertSame(b, abc.next().getTrustee());
			assertTrue(abc.hasNext()); assertSame(a, abc.next().getTrustee());
			assertFalse(abc.hasNext());
		}
		
		a.onFetched(); a.storeAndCommit();
		
		{
			ObjectSet<Trust> abc = mWoT.getGivenTrustsSortedDescendingByLastSeen(o);
			assertTrue(abc.hasNext()); assertSame(a, abc.next().getTrustee());
			assertTrue(abc.hasNext()); assertSame(c, abc.next().getTrustee());
			assertTrue(abc.hasNext()); assertSame(b, abc.next().getTrustee());
			assertFalse(abc.hasNext());
		}

	}
	
	/**
	 * Test for {@link restoreIdentity}: Using a request URI instead of an insert URI. Restoring should fail. 
	 */
	public void testRestoreIdentity_URIMixup() throws InvalidParameterException {
		try {
			mWoT.restoreIdentity(new FreenetURI(requestUriO));
			fail("Restoring an identity with request URI instead of insert URI should fail!");
		} catch(MalformedURLException e) {
			// Success.
		}
	}
	
	/**
	 * Test for {@link restoreIdentity}: The identity to restore already exists as own identity. Restoring should fail.
	 */
	public void testRestoreIdentity_AlreadyExisting() throws MalformedURLException, InvalidParameterException {
		mWoT.createOwnIdentity(new FreenetURI(insertUriO), "O", true, "Test"); // Tree owner
		try {
			mWoT.restoreIdentity(new FreenetURI(insertUriO));
			fail("Restoring an already existing own identity should fail!");
		} catch(InvalidParameterException e) {
			// Success.
		}
	}
	
	/**
	 * Test for {@link restoreIdentity}: No identity with the given ID exists.
	 */
	public void testRestoreIdentity_Inexistent() throws MalformedURLException, InvalidParameterException, UnknownIdentityException {
		final FreenetURI insertURI = new FreenetURI("USK@ZTeIa1g4T3OYCdUFfHrFSlRnt5coeFFDCIZxWSb7abs,ZP4aASnyZax8nYOvCOlUebegsmbGQIXfVzw7iyOsXEc,AQECAAE/WebOfTrust/10");
		
		mWoT.restoreIdentity(insertURI);
		final OwnIdentity restored = mWoT.getOwnIdentityByURI(insertURI);
		
		assertEquals("The edition of the supplied URI can be used because the owner of the identity supplied it.", insertURI.getEdition(), restored.getEdition());
		assertEquals("Edition hint should be equal to the edition.", restored.getEdition(), restored.getLatestEditionHint());
		assertEquals("The current edition should be marked as not fetched.", FetchState.NotFetched, restored.getCurrentEditionFetchState());
		assertEquals("The current edition should NOT be marked for inserting.", false, restored.needsInsert());
		
		assertEquals("The identity was not fetched yet so the last-fetched date should be zero.", new Date(0), restored.getLastFetchedDate());
		assertTrue("The last insert date of the identity should be set to current time to prevent reinsert of old editions", 
				(CurrentTimeUTC.getInMillis() - restored.getLastInsertDate().getTime()) < 10*1000); // Allow some delta to compensate execution time between restoreIdentity() and this line.
		
		assertEquals("We cannot know the nickname yet", null, restored.getNickname());
		assertEquals("We should assume the identity does not insert a trust list for as long as we don't know", false, restored.doesPublishTrustList());
		
		assertEquals("We cannot know the contexts yet", 0, restored.getContexts().size());
		assertEquals("We cannot know the properties yet", 0, restored.getProperties().size());
		

	}
	
	/**
	 * - The identity to restore already exists as a non-own identity. The non-own one should be replaced. The new own-one should inherit the data of the old non-own one.
	 */
	public void testRestoreIdentity_ExistingAsNonOwnIdentityAlready() {
		 throw new UnsupportedOperationException("Not implemented yet!");
	}
}
