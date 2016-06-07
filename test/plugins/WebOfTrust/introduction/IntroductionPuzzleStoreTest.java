/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.introduction;

import java.io.IOException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import plugins.WebOfTrust.AbstractJUnit3BaseTest;
import plugins.WebOfTrust.OwnIdentity;
import plugins.WebOfTrust.exceptions.DuplicatePuzzleException;
import plugins.WebOfTrust.exceptions.UnknownIdentityException;
import plugins.WebOfTrust.exceptions.UnknownPuzzleException;
import plugins.WebOfTrust.introduction.IntroductionPuzzle.PuzzleType;
import plugins.WebOfTrust.introduction.captcha.CaptchaFactory1;
import freenet.keys.FreenetURI;
import freenet.support.CurrentTimeUTC;

/**
 * @author xor (xor@freenetproject.org)
 */
public final class IntroductionPuzzleStoreTest extends AbstractJUnit3BaseTest {

	private IntroductionPuzzleStore mPuzzleStore;
	private List<IntroductionPuzzleFactory> mPuzzleFactories;
	private List<OwnIdentity> mOwnIdentities;
	private OwnIdentity mOwnIdentity;
	
	@Override
	protected void setUp() throws Exception {
		super.setUp();
		
		mPuzzleStore = mWoT.getIntroductionPuzzleStore();
		
		mPuzzleFactories = new ArrayList<IntroductionPuzzleFactory>();		
		mPuzzleFactories.add(new CaptchaFactory1());
		mPuzzleFactories = Collections.unmodifiableList(mPuzzleFactories);
		assertEquals(1, mPuzzleFactories.size());
		
		// final String requestUriA = "SSK@JbL1YKGe7Db5nOKiCfepqzsYVHM6Wky1fy1nxGj1OiY,UWICDLtymysOXSO7LDgUDQtVfvx9434BiyJB0TtvxRc,AQACAAE/WebOfTrust";
		final String insertUriA = "SSK@AIF07w9GVcz70eciE8CoKpGbAv9wAxrUx8ZhDxm8~6Ve,UWICDLtymysOXSO7LDgUDQtVfvx9434BiyJB0TtvxRc,AQECAAE/WebOfTrust";
		// final String requestUriB = "SSK@LXT92Bbip~qlYtepgKjBDwnbBcNrGD9S8jUDzSF64XA,uC-6AoFB4laXLBsr2Eee8mw7BlrXbqQXvDJj~i8BM68,AQACAAE/WebOfTrust";
		final String insertUriB = "SSK@ZyR14uN74EFIL6EwIOcCifj17d5Z-l7lpJdfYNEeT4Y,uC-6AoFB4laXLBsr2Eee8mw7BlrXbqQXvDJj~i8BM68,AQECAAE/WebOfTrust";
		// final String requestUriC = "SSK@DFbvyoJPnUnNB4akjLfdSpxBB8tvHhZ3AZ7AP3TlzzE,l6xQzhaW2QtKA2MbWvjSFesx3XSLMDwCyP8qRTmOh1k,AQACAAE/WebOfTrust";
		final String insertUriC = "SSK@ct973RBN~JX1LmHRsZqP2w181V3gOixrun~7a6BqguE,l6xQzhaW2QtKA2MbWvjSFesx3XSLMDwCyP8qRTmOh1k,AQECAAE/WebOfTrust";
		
		mOwnIdentities = new ArrayList<OwnIdentity>();
		mOwnIdentities.add(mWoT.createOwnIdentity(new FreenetURI(insertUriA), "A", true, "Test"));
		mOwnIdentities.add(mWoT.createOwnIdentity(new FreenetURI(insertUriB), "B", true, "Test"));
		mOwnIdentities.add(mWoT.createOwnIdentity(new FreenetURI(insertUriC), "C", true, "Test"));
		mOwnIdentities = Collections.unmodifiableList(mOwnIdentities);
		
		mOwnIdentity = mOwnIdentities.get(0);
	
		assertEquals(1, mPuzzleFactories.size());
		assertEquals(3, mOwnIdentities.size());
		assertEquals(0, mPuzzleStore.getOwnCatpchaAmount(false));
		assertEquals(0, mPuzzleStore.getOwnCatpchaAmount(true));
	}
	
	/**
	 * Generates two puzzles from each {@link IntroductionPuzzleFactory} for the given OwnIdentity.
	 * It generates two per factory because some test functions need at least two puzzles and there might be only one factory.
	 * Flushes the database caches before returning.
	 * Has its own test.
	 */
	private List<OwnIntroductionPuzzle> generateNewPuzzles(final OwnIdentity identity) throws IOException {

		final ArrayList<OwnIntroductionPuzzle> result = new ArrayList<OwnIntroductionPuzzle>(mPuzzleFactories.size() + 1); 
		
		for(int factory=0; factory < mPuzzleFactories.size(); ++factory) {
			// generatePuzzle also stores them and commits the transaction
			result.add(mPuzzleFactories.get(factory).generatePuzzle(mPuzzleStore, identity));
			result.add(mPuzzleFactories.get(factory).generatePuzzle(mPuzzleStore, identity));
		}
		
		flushCaches();
		
		return Collections.unmodifiableList(result);
	}
	
	/**
	 * TODO: There seems to be no java API for this. Sucks. Such simple stuff should only be used from libraroes-
	 */
	private List<OwnIntroductionPuzzle> deepCopy(final List<OwnIntroductionPuzzle> puzzles) {
		final ArrayList<OwnIntroductionPuzzle> result = new ArrayList<OwnIntroductionPuzzle>(puzzles.size() + 1);
		for(OwnIntroductionPuzzle p : puzzles) result.add(p.clone());
		return Collections.unmodifiableList(result);
	}
	
	/**
	 * Constructs a puzzle of the given identity with the given expiration date. Does not store the puzzle in the database.
	 * 
	 * NOTICE: A copypasta of this function exists as {@link IntroductionPuzzleTest#constructPuzzleWithExpirationDate(OwnIdentity, Date)
	 */
	private IntroductionPuzzle constructPuzzleWithExpirationDate(OwnIdentity identity, Date dateOfExpiration) {
		final Date dateOfInsertion = new Date(dateOfExpiration.getTime() - IntroductionServer.PUZZLE_INVALID_AFTER_DAYS * 24 * 60 * 60 * 1000);
		final IntroductionPuzzle p = new IntroductionPuzzle(mWoT, identity, UUID.randomUUID().toString() + "@" + identity.getID(), PuzzleType.Captcha, "image/jpeg", new byte[] { 0 }, 
				dateOfInsertion, dateOfExpiration, mPuzzleStore.getFreeIndex(identity, dateOfInsertion));
		return p;
	}
	
	/**
	 * Constructs a puzzle of the given identity with the given insertion date. Does not store the puzzle in the database.
	 */
	private IntroductionPuzzle constructPuzzleWithDate(OwnIdentity identity, Date dateOfInsertion) {
		final Date dateOfExpiration = new Date(dateOfInsertion.getTime() + IntroductionServer.PUZZLE_INVALID_AFTER_DAYS * 24 * 60 * 60 * 1000);
		final IntroductionPuzzle p =  new IntroductionPuzzle(mWoT, identity, UUID.randomUUID().toString() + "@" + identity.getID(), PuzzleType.Captcha, "image/jpeg", new byte[] { 0 }, 
				dateOfInsertion, dateOfExpiration, mPuzzleStore.getFreeIndex(identity, dateOfInsertion));
		return p;
	}
	
	/**
	 * Constructs a puzzle of the given OwnIdentity with the given insertion date. Does not store the puzzle in the database.
	 */
	private OwnIntroductionPuzzle constructOwnPuzzleWithDate(OwnIdentity identity, Date dateOfInsertion) {
		final OwnIntroductionPuzzle p =  new OwnIntroductionPuzzle(mWoT, identity, PuzzleType.Captcha, "image/jpeg", new byte[] { 0 }, "foobar",
				dateOfInsertion, mPuzzleStore.getFreeIndex(identity, dateOfInsertion));
		return p;
	}
	
	/**
	 * Constructs a puzzle of the given OwnIdentity with the given insertion date and idnex. Does not store the puzzle in the database.
	 */
	private OwnIntroductionPuzzle constructOwnPuzzleWithDateAndIndex(OwnIdentity identity, Date dateOfInsertion, int index) {
		final OwnIntroductionPuzzle p = new OwnIntroductionPuzzle(mWoT, identity, PuzzleType.Captcha, "image/jpeg", new byte[] { 0 }, "foobar",  dateOfInsertion, index);
		return p;
	}
	
	private OwnIntroductionPuzzle constructOwnPuzzleOf(OwnIdentity identity) {
		return constructOwnPuzzleWithDate(identity, CurrentTimeUTC.get());
	}

	private IntroductionPuzzle constructPuzzleOf(OwnIdentity identity) {
		return constructPuzzleWithExpirationDate(identity, new Date(CurrentTimeUTC.getInMillis() + 24 * 60 * 60 * 1000));
	}
	
	/**
	 * NOTICE: A copypasta of this function exists as {@link IntroductionPuzzleTest#constructPuzzle()}
	 */
	private IntroductionPuzzle constructPuzzle() {
		return constructPuzzleWithExpirationDate(mOwnIdentity, new Date(CurrentTimeUTC.getInMillis() + 24 * 60 * 60 * 1000));
	}
	
	/**
	 * Test for utility function of this test class, not for a function of IntroductionPuzzleStore.
	 */
	public void testGenerateNewPuzzles() throws IOException {
		final OwnIdentity a = mOwnIdentities.get(0);
		final OwnIdentity b = mOwnIdentities.get(1);
		
		final int puzzleCountA = generateNewPuzzles(a).size();
		final int puzzleCountB = generateNewPuzzles(b).size() + generateNewPuzzles(b).size();
		
		assertEquals(mPuzzleFactories.size()*2, puzzleCountA);
		assertEquals(mPuzzleFactories.size()*2*2, puzzleCountB);
		
		assertEquals(puzzleCountA, mPuzzleStore.getUninsertedOwnPuzzlesByInserter(a).size());
		assertEquals(puzzleCountB, mPuzzleStore.getUninsertedOwnPuzzlesByInserter(b).size());
	}

	public void testDeleteExpiredPuzzles()
	        throws UnknownPuzzleException, IOException, InterruptedException {
	    
		final List<IntroductionPuzzle> deletedPuzzles = new ArrayList<IntroductionPuzzle>();
		final Date expirationDate = new Date(CurrentTimeUTC.getInMillis() + 500);
		deletedPuzzles.add(constructPuzzleWithExpirationDate(mOwnIdentity, expirationDate));
		deletedPuzzles.add(constructPuzzleWithExpirationDate(mOwnIdentity, expirationDate));
		for(IntroductionPuzzle p : deletedPuzzles) mPuzzleStore.storeAndCommit(p);

		final List<OwnIntroductionPuzzle> notDeletedPuzzles = generateNewPuzzles(mOwnIdentity);
		
		while(CurrentTimeUTC.get().before(expirationDate)) {
		    Thread.sleep(500);
		}

		mPuzzleStore.deleteExpiredPuzzles();
	
		flushCaches();
		
		for(IntroductionPuzzle puzzle : deletedPuzzles) {
			try {
				mPuzzleStore.getByID(puzzle.getID());
				fail("Puzzle was not delted");
			} catch(UnknownPuzzleException e) {}
		}
		
		for(OwnIntroductionPuzzle puzzle : notDeletedPuzzles) {
			mPuzzleStore.getByID(puzzle.getID());
		}
	}

	public void testDeleteOldestUnsolvedPuzzles() throws IOException, UnknownPuzzleException {
		long currentTime = CurrentTimeUTC.getInMillis();
		
		final List<IntroductionPuzzle> deletedPuzzles = new ArrayList<IntroductionPuzzle>();
		deletedPuzzles.add(constructPuzzleWithExpirationDate(mOwnIdentity, new Date(currentTime + 100*1000 + 1)));
		deletedPuzzles.add(constructPuzzleWithExpirationDate(mOwnIdentity, new Date(currentTime + 100*1000 + 2)));
		for(IntroductionPuzzle p : deletedPuzzles) mPuzzleStore.storeAndCommit(p);

		final List<IntroductionPuzzle> notDeletedPuzzles = new ArrayList<IntroductionPuzzle>();
		notDeletedPuzzles.add(constructPuzzleWithExpirationDate(mOwnIdentity, new Date(currentTime + 100*1000 + 3)));
		notDeletedPuzzles.add(constructPuzzleWithExpirationDate(mOwnIdentity, new Date(currentTime + 100*1000 + 4)));
		for(IntroductionPuzzle p :notDeletedPuzzles) mPuzzleStore.storeAndCommit(p);
		
		mPuzzleStore.deleteOldestUnsolvedPuzzles(2);
	
		flushCaches();
		
		for(IntroductionPuzzle puzzle : deletedPuzzles) {
			try {
				mPuzzleStore.getByID(puzzle.getID());
				fail("Puzzle was not delted");
			} catch(UnknownPuzzleException e) {}
		}
		
		for(IntroductionPuzzle puzzle : notDeletedPuzzles) {
			mPuzzleStore.getByID(puzzle.getID());
		}
	}

	public void testOnIdentityDeletion() throws IOException, UnknownIdentityException {
		final OwnIdentity a = mOwnIdentities.get(0);
		final OwnIdentity b = mOwnIdentities.get(1);
		
		final List<OwnIntroductionPuzzle> deletedPuzzles = generateNewPuzzles(a);
		final int puzzleCountB = generateNewPuzzles(b).size();
		
		mWoT.deleteOwnIdentity(a.getID());
		flushCaches();
		
		// We should not query for the puzzle count of the identity to ensure that we catch puzzles whose owner has become null as well.
		for(OwnIntroductionPuzzle puzzle : deletedPuzzles) {
			try {
				mPuzzleStore.getByID(puzzle.getID());
				fail("Puzzle was not deleted: " + puzzle);
			} catch(UnknownPuzzleException e) {}
		}

		assertEquals(puzzleCountB, mPuzzleStore.getUninsertedOwnPuzzlesByInserter(b).size());
	}

	public void testStoreAndCommit() throws UnknownPuzzleException {
		IntroductionPuzzle puzzle = constructPuzzle();
		
		mPuzzleStore.storeAndCommit(puzzle);
		puzzle = puzzle.clone();
		
		flushCaches();
		
		assertEquals(puzzle, mPuzzleStore.getByID(puzzle.getID()));
	}

	public void testGetByID() throws IOException, UnknownPuzzleException {
		final List<OwnIntroductionPuzzle> puzzles = generateNewPuzzles(mOwnIdentity);
		
		for(final OwnIntroductionPuzzle puzzle : puzzles) {
			assertSame(puzzle, mPuzzleStore.getByID(puzzle.getID()));
		}
		
		for(final OwnIntroductionPuzzle clone : deepCopy(puzzles)) {
			final IntroductionPuzzle original = mPuzzleStore.getByID(clone.getID());
			assertNotSame(clone, original);
			assertEquals(clone, original);
		}
		
		try {
			mPuzzleStore.getByID(UUID.randomUUID().toString());
			fail("No such puzzle should exist.");
		} catch(UnknownPuzzleException e) {} 
	}

	public void testGetPuzzleBySolutionURI() throws ParseException, UnknownIdentityException, UnknownPuzzleException, IOException {
		final List<OwnIntroductionPuzzle> puzzles = generateNewPuzzles(mOwnIdentity);
		
		for(final IntroductionPuzzle puzzle : puzzles) {
			assertSame(puzzle, mPuzzleStore.getPuzzleBySolutionURI(puzzle.getSolutionURI()));
		}
		
		for(final IntroductionPuzzle clone : deepCopy(puzzles)) {
			final IntroductionPuzzle original = mPuzzleStore.getPuzzleBySolutionURI(clone.getSolutionURI());
			assertNotSame(clone, original);
			assertEquals(clone, original);
		}
	}

	public void testGetOwnPuzzleByRequestURI() throws IOException, ParseException, UnknownIdentityException, UnknownPuzzleException {
		final List<OwnIntroductionPuzzle> puzzles = generateNewPuzzles(mOwnIdentity);
		
		for(final OwnIntroductionPuzzle puzzle : puzzles) {
			assertSame(puzzle, mPuzzleStore.getOwnPuzzleByRequestURI(puzzle.getRequestURI()));
		}
		
		for(final OwnIntroductionPuzzle clone : deepCopy(puzzles)) {
			final IntroductionPuzzle original = mPuzzleStore.getOwnPuzzleByRequestURI(clone.getRequestURI());
			assertNotSame(clone, original);
			assertEquals(clone, original);
		}
	}

	public void testGetOwnPuzzleBySolutionURI() throws ParseException, UnknownPuzzleException, IOException {
		final List<OwnIntroductionPuzzle> puzzles = generateNewPuzzles(mOwnIdentity);
		
		for(final IntroductionPuzzle puzzle : puzzles) {
			assertSame(puzzle, mPuzzleStore.getOwnPuzzleBySolutionURI(puzzle.getSolutionURI()));
		}
		
		for(final IntroductionPuzzle clone : deepCopy(puzzles)) {
			final IntroductionPuzzle original = mPuzzleStore.getOwnPuzzleBySolutionURI(clone.getSolutionURI());
			assertNotSame(clone, original);
			assertEquals(clone, original);
		}
	}

	public void testGetFreeIndex() throws IOException {
		int[] freeIndices = new int[mOwnIdentities.size()];
		 
		final Date date = CurrentTimeUTC.get();
		
		for(int i=0; i < mOwnIdentities.size(); ++i) {
			freeIndices[i] = 0;
			
			// 0. identity => 0 puzzles
			// 1. identity => 1 puzzle
			// 2. identity => 2 puzzles
			for(int index=0; index < i; ++index) {
				mPuzzleStore.storeAndCommit(constructOwnPuzzleWithDateAndIndex(mOwnIdentities.get(i), date, index));
				freeIndices[i] = index+1;
			}
			
			// To check whether the free index of other days does not make the free index of today go wrong, we store a puzzle for a different day for each identity.
			mPuzzleStore.storeAndCommit(constructPuzzleWithExpirationDate(mOwnIdentities.get(i), new Date(date.getTime() + 10 * IntroductionServer.PUZZLE_INVALID_AFTER_DAYS * 10 * 24 * 60 * 60 * 1000)));
		}
		
		final Date nullDate = new Date(0);
	
		for(int i=0; i < mOwnIdentities.size(); ++i) {
			assertEquals(freeIndices[i], mPuzzleStore.getFreeIndex(mOwnIdentities.get(i), date));
			assertEquals(0, mPuzzleStore.getFreeIndex(mOwnIdentities.get(i), nullDate));
		}
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	public void testGetUninsertedOwnPuzzlesByInserter() throws IOException {
		// We need those Lists to implement ReallyCloneable<> for listToSetWithDuplicateCheck().
		// OwnIntroductionPuzzle cannot implement ReallyCloneable<OwnIntroduction> because the
		// parent class IntroductionPuzzle already implements ReallyCloneable<IntroductionPuzzle>.
		// So we do a '@SuppressWarnings({ "unchecked", "rawtypes" })' cast from
		// List<OwnIdentroductionPuzzle> to List<IntroductionPuzzle>.
		// TODO: Code quality: Can the generics of ReallyCloneable or listToSetWithDuplicateCheck()
		// be improved to avoid having to do this unchecked raw cast?
		List<IntroductionPuzzle> uninsertedPuzzles0
			= (List)deepCopy(generateNewPuzzles(mOwnIdentities.get(0)));
		List<IntroductionPuzzle> uninsertedPuzzles1
			= (List)deepCopy(generateNewPuzzles(mOwnIdentities.get(1)));
		
		List<OwnIntroductionPuzzle> insertedPuzzles0 = generateNewPuzzles(mOwnIdentities.get(0));
		List<OwnIntroductionPuzzle> insertedPuzzles1 = generateNewPuzzles(mOwnIdentities.get(1));
		
		for(OwnIntroductionPuzzle p : insertedPuzzles0) {
			p.setInserted();
			mPuzzleStore.storeAndCommit(p);
		}
		
		for(OwnIntroductionPuzzle p : insertedPuzzles1) {
			p.setInserted();
			mPuzzleStore.storeAndCommit(p);
		}

		insertedPuzzles0 = null;
		insertedPuzzles1 = null;
		
		flushCaches();
		
		List<IntroductionPuzzle> query0
			= (List)mPuzzleStore.getUninsertedOwnPuzzlesByInserter(mOwnIdentities.get(0));
		List<IntroductionPuzzle> query1
			= (List)mPuzzleStore.getUninsertedOwnPuzzlesByInserter(mOwnIdentities.get(1));
		
		assertEquals(listToSetWithDuplicateCheck(uninsertedPuzzles0),
		             listToSetWithDuplicateCheck(query0));
		assertEquals(listToSetWithDuplicateCheck(uninsertedPuzzles1),
		             listToSetWithDuplicateCheck(query1));
	}

	public void testGetUnsolvedByInserter() throws IOException {
		final List<OwnIntroductionPuzzle> unsolvedPuzzles0 = deepCopy(generateNewPuzzles(mOwnIdentities.get(0)));
		final List<OwnIntroductionPuzzle> unsolvedPuzzles1 = deepCopy(generateNewPuzzles(mOwnIdentities.get(1)));
		List<OwnIntroductionPuzzle> solvedPuzzles0 = generateNewPuzzles(mOwnIdentities.get(0));
		List<OwnIntroductionPuzzle> solvedPuzzles1 = generateNewPuzzles(mOwnIdentities.get(1));
		
		for(OwnIntroductionPuzzle p : solvedPuzzles0) {
			p.setSolved();
			mPuzzleStore.storeAndCommit(p);
		}
		
		for(OwnIntroductionPuzzle p : solvedPuzzles1) {
			p.setSolved();
			mPuzzleStore.storeAndCommit(p);
		}
		
		solvedPuzzles0 = null;
		solvedPuzzles1 = null;
		
		flushCaches();
		
		assertEquals(new HashSet<OwnIntroductionPuzzle>(unsolvedPuzzles0), new HashSet<OwnIntroductionPuzzle>(mPuzzleStore.getUnsolvedByInserter(mOwnIdentities.get(0))));
		assertEquals(new HashSet<OwnIntroductionPuzzle>(unsolvedPuzzles1), new HashSet<OwnIntroductionPuzzle>(mPuzzleStore.getUnsolvedByInserter(mOwnIdentities.get(1))));
	}

	public void testGetOfTodayByInserter() throws IOException { 
		final Date today = CurrentTimeUTC.get();
		final Date yesterday = new Date (CurrentTimeUTC.getInMillis() - 24 * 60 * 60 * 1000);
		
		final List<IntroductionPuzzle> ofToday = new ArrayList<IntroductionPuzzle>();
		
		for(int i=0; i < mOwnIdentities.size(); ++i) {
			final IntroductionPuzzle p1 = constructPuzzleWithDate(mOwnIdentities.get(i), today);
			mPuzzleStore.storeAndCommit(p1);
			
			if(i == 0)
				ofToday.add(p1.clone());
			
			mPuzzleStore.storeAndCommit(constructPuzzleWithDate(mOwnIdentities.get(i), yesterday));
		}
		
		flushCaches();
		
		assertEquals(new HashSet<IntroductionPuzzle>(ofToday), new HashSet<IntroductionPuzzle>(mPuzzleStore.getOfTodayByInserter(mOwnIdentities.get(0))));
	}

	public void testGetByInserterDateIndex() throws UnknownPuzzleException, UnknownIdentityException {
		final Date today = CurrentTimeUTC.get();
		final Date yesterday = new Date (CurrentTimeUTC.getInMillis() - 24 * 60 * 60 * 1000);
		final Date tomorrow = new Date (CurrentTimeUTC.getInMillis() + 24 * 60 * 60 * 1000);
		
		final List<IntroductionPuzzle> puzzles = new ArrayList<IntroductionPuzzle>();
		
		for(int i=0; i < mOwnIdentities.size()-1; ++i) {
			IntroductionPuzzle p;
						
			p = constructPuzzleWithDate(mOwnIdentities.get(i), today);
			mPuzzleStore.storeAndCommit(p);
			puzzles.add(p.clone());
			
			p = constructPuzzleWithDate(mOwnIdentities.get(i), yesterday);
			mPuzzleStore.storeAndCommit(p);
			puzzles.add(p.clone());
		}
		
		flushCaches();
		
		for(IntroductionPuzzle p : puzzles) {
			// We have to re-query the identity because the cloned puzzle also only contains a clone of the identity.
			final OwnIdentity inserter = mWoT.getOwnIdentityByID(p.getInserter().getID());
			
			assertEquals(p, mPuzzleStore.getByInserterDateIndex(inserter, p.getDateOfInsertion(), p.getIndex()));

			try {
				fail("Puzzle should not exist:" + mPuzzleStore.getByInserterDateIndex(mOwnIdentities.get(mOwnIdentities.size()-1), p.getDateOfInsertion(), p.getIndex()));
			} catch(UnknownPuzzleException e) {}
			
			try {
				fail("Puzzle should not exist:" + mPuzzleStore.getByInserterDateIndex(inserter, tomorrow, p.getIndex()));
			} catch(UnknownPuzzleException e) {}
			
			try {
				fail("Puzzle should not exist:" + mPuzzleStore.getByInserterDateIndex(inserter, p.getDateOfInsertion(), 2));
			} catch(UnknownPuzzleException e) {}
			
			mPuzzleStore.storeAndCommit(constructOwnPuzzleWithDateAndIndex(inserter, p.getDateOfInsertion(), p.getIndex()));
			try {
				mPuzzleStore.getByInserterDateIndex(inserter, p.getDateOfInsertion(), p.getIndex());
				fail("Duplicate-Exception should have been thrown.");
			} catch(DuplicatePuzzleException e) {}
		}
	}

	public void testGetOwnPuzzleByInserterDateIndex() throws UnknownPuzzleException, UnknownIdentityException {
		final Date today = CurrentTimeUTC.get();
		final Date yesterday = new Date (CurrentTimeUTC.getInMillis() - 24 * 60 * 60 * 1000);
		final Date tomorrow = new Date (CurrentTimeUTC.getInMillis() + 24 * 60 * 60 * 1000);
		
		final List<OwnIntroductionPuzzle> puzzles = new ArrayList<OwnIntroductionPuzzle>();
		
		for(int i=0; i < mOwnIdentities.size()-1; ++i) {
			OwnIntroductionPuzzle p;
			
			p = constructOwnPuzzleWithDate(mOwnIdentities.get(i), today);
			mPuzzleStore.storeAndCommit(p);
			puzzles.add(p.clone());
			
			p = constructOwnPuzzleWithDate(mOwnIdentities.get(i), yesterday);
			mPuzzleStore.storeAndCommit(p);
			puzzles.add(p.clone());
		}
		
		flushCaches();
		
		for(OwnIntroductionPuzzle p : puzzles) {
			// We have to re-query the identity because the cloned puzzle also only contains a clone of the identity.
			final OwnIdentity inserter = mWoT.getOwnIdentityByID(p.getInserter().getID());
			
			assertEquals(p, mPuzzleStore.getOwnPuzzleByInserterDateIndex(inserter, p.getDateOfInsertion(), p.getIndex()));

			try {
				fail("Puzzle should not exist:" + mPuzzleStore.getByInserterDateIndex(mOwnIdentities.get(mOwnIdentities.size()-1), p.getDateOfInsertion(), p.getIndex()));
			} catch(UnknownPuzzleException e) {}
			
			try {
				fail("Puzzle should not exist:" + mPuzzleStore.getByInserterDateIndex(inserter, tomorrow, p.getIndex()));
			} catch(UnknownPuzzleException e) {}
			
			try {
				fail("Puzzle should not exist:" + mPuzzleStore.getByInserterDateIndex(inserter, p.getDateOfInsertion(), 2));
			} catch(UnknownPuzzleException e) {}
		}
	}

	public void testGetUnsolvedPuzzles() throws IOException {
		final List<IntroductionPuzzle> unsolvedPuzzles = new ArrayList<IntroductionPuzzle>();
		
		for(OwnIdentity ownId : mOwnIdentities.subList(1, mOwnIdentities.size())) {
			IntroductionPuzzle p;
			
			p = constructPuzzleOf(ownId);
			mPuzzleStore.storeAndCommit(p);
			unsolvedPuzzles.add(p.clone());
			
			// non own but solved
			p = constructPuzzleOf(ownId);
			p.setSolved(mOwnIdentities.get(0), "blehblah");
			mPuzzleStore.storeAndCommit(p);
			
			// unsolved but own one
			p = constructOwnPuzzleOf(ownId);
			mPuzzleStore.storeAndCommit(p);
		}
		
		flushCaches();

		// TODO: As soon as we have more puzzle types, test them here
		assertEquals(new HashSet<IntroductionPuzzle>(unsolvedPuzzles), new HashSet<IntroductionPuzzle>(mPuzzleStore.getUnsolvedPuzzles(PuzzleType.Captcha)));
	}

	public void testGetUninsertedSolvedPuzzles() throws IOException {
		final List<IntroductionPuzzle> uninsertedSolvedPuzzles = new ArrayList<IntroductionPuzzle>();
		
		for(OwnIdentity ownId : mOwnIdentities.subList(1, mOwnIdentities.size())) {
			IntroductionPuzzle p;
			
			// solved but not uninserted
			p = constructPuzzleOf(ownId);
			p.setInserted();
			p.setSolved(mOwnIdentities.get(0), "blehblah");
			mPuzzleStore.storeAndCommit(p);
			
			// uninserted but not solved
			p = constructPuzzleOf(ownId);
			mPuzzleStore.storeAndCommit(p);
			
			// uninserted, solved but an own one
			OwnIntroductionPuzzle ownPuzzle = constructOwnPuzzleOf(ownId);
			ownPuzzle.setSolved();
			mPuzzleStore.storeAndCommit(ownPuzzle);
			
			// uninserted, solved
			p = constructPuzzleOf(ownId);
			p.setSolved(ownId, "blehblah");
			mPuzzleStore.storeAndCommit(p);
			uninsertedSolvedPuzzles.add(p.clone());
		}
		
		flushCaches();
		// TODO: As soon as we have more puzzle types, test them here
		assertEquals(new HashSet<IntroductionPuzzle>(uninsertedSolvedPuzzles), new HashSet<IntroductionPuzzle>(mPuzzleStore.getUninsertedSolvedPuzzles()));
	}

	public void testGetOwnCatpchaAmount() throws IOException {
		int unsolvedOwnPuzzleCount = 0;
		int solvedOwnPuzzleCount = 0;
		
		for(OwnIdentity ownId : mOwnIdentities.subList(1, mOwnIdentities.size())) {			
			mPuzzleStore.storeAndCommit(constructOwnPuzzleOf(ownId));
			mPuzzleStore.storeAndCommit(constructOwnPuzzleOf(ownId));
			unsolvedOwnPuzzleCount += 2;
			
			final OwnIntroductionPuzzle solvedOwn = constructOwnPuzzleOf(ownId);
			solvedOwn.setSolved();
			mPuzzleStore.storeAndCommit(solvedOwn);
			++solvedOwnPuzzleCount;
			
			// Non-own ones should not be counted.
			
			final IntroductionPuzzle solvedNonOwn = constructPuzzleOf(ownId);
			solvedNonOwn.setSolved(mOwnIdentities.get(0), "foobar");
			mPuzzleStore.storeAndCommit(solvedNonOwn);
			
			mPuzzleStore.storeAndCommit(constructPuzzleOf(ownId));
		}
		
		flushCaches();
		
		assertEquals(unsolvedOwnPuzzleCount, mPuzzleStore.getOwnCatpchaAmount(false));
		assertEquals(solvedOwnPuzzleCount, mPuzzleStore.getOwnCatpchaAmount(true));
	}

	public void testGetNonOwnCaptchaAmount() {
		int unsolvedPuzzleCount = 0;
		int solvedPuzzleCount = 0;
		
		for(OwnIdentity ownId : mOwnIdentities.subList(1, mOwnIdentities.size())) {			
			mPuzzleStore.storeAndCommit(constructPuzzleOf(ownId));
			mPuzzleStore.storeAndCommit(constructPuzzleOf(ownId));
			unsolvedPuzzleCount += 2;
			
			final IntroductionPuzzle solvedNonOwn = constructPuzzleOf(ownId);
			solvedNonOwn.setSolved(mOwnIdentities.get(0), "foobar");
			mPuzzleStore.storeAndCommit(solvedNonOwn);
			++solvedPuzzleCount;
			
			// Own ones should not be counted.
			
			final OwnIntroductionPuzzle solvedOwn = constructOwnPuzzleOf(ownId);
			solvedOwn.setSolved();
			mPuzzleStore.storeAndCommit(solvedOwn);
			
			mPuzzleStore.storeAndCommit(constructOwnPuzzleOf(ownId));
		}
		
		flushCaches();
		
		assertEquals(unsolvedPuzzleCount, mPuzzleStore.getNonOwnCaptchaAmount(false));
		assertEquals(solvedPuzzleCount, mPuzzleStore.getNonOwnCaptchaAmount(true));
	}

}
