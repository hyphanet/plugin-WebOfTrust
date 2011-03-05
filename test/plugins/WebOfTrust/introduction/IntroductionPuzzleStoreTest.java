package plugins.WebOfTrust.introduction;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.UUID;

import plugins.WebOfTrust.DatabaseBasedTest;
import plugins.WebOfTrust.OwnIdentity;
import plugins.WebOfTrust.exceptions.UnknownIdentityException;
import plugins.WebOfTrust.exceptions.UnknownPuzzleException;
import plugins.WebOfTrust.introduction.IntroductionPuzzle.PuzzleType;
import plugins.WebOfTrust.introduction.captcha.CaptchaFactory1;
import freenet.support.CurrentTimeUTC;

public final class IntroductionPuzzleStoreTest extends DatabaseBasedTest {

	private IntroductionPuzzleStore mPuzzleStore;
	private ArrayList<IntroductionPuzzleFactory> mPuzzleFactories;
	private ArrayList<OwnIdentity> mOwnIdentities;
	
	protected void setUp() throws Exception {
		super.setUp();
		
		mPuzzleStore = mWoT.getIntroductionPuzzleStore();
		mPuzzleFactories = new ArrayList<IntroductionPuzzleFactory>();
		mOwnIdentities = new ArrayList<OwnIdentity>();
		
		mPuzzleFactories.add(new CaptchaFactory1());
		
		final String uriA = "SSK@MF2Vc6FRgeFMZJ0s2l9hOop87EYWAydUZakJzL0OfV8,fQeN-RMQZsUrDha2LCJWOMFk1-EiXZxfTnBT8NEgY00,AQACAAE/";
		final String uriB = "SSK@R3Lp2s4jdX-3Q96c0A9530qg7JsvA9vi2K0hwY9wG-4,ipkgYftRpo0StBlYkJUawZhg~SO29NZIINseUtBhEfE,AQACAAE/";
		
		mOwnIdentities.add(mWoT.createOwnIdentity(uriA, uriA, "B", true, "Test"));
		mOwnIdentities.add(mWoT.createOwnIdentity(uriB, uriB, "B", true, "Test"));
	
		assertEquals(2, mOwnIdentities.size());
		assertEquals(1, mPuzzleFactories.size());
		assertEquals(0, mPuzzleStore.getOwnCatpchaAmount(false));
		assertEquals(0, mPuzzleStore.getOwnCatpchaAmount(true));
		
		System.out.println("setUp()");
	}
	
	/**
	 * Generates two puzzles from each {@link IntroductionPuzzleFactory} for the given OwnIdentity.
	 * It generates two per factory because some test functions need at least two puzzles and there might be only one factory.
	 * Flushes the database caches before returning.
	 * Has its own test.
	 */
	private ArrayList<OwnIntroductionPuzzle> generateNewPuzzles(final OwnIdentity identity) throws IOException {

		final ArrayList<OwnIntroductionPuzzle> result = new ArrayList<OwnIntroductionPuzzle>(mPuzzleFactories.size() + 1); 
		
		for(int factory=0; factory < mPuzzleFactories.size(); ++factory) {
			result.add(mPuzzleFactories.get(factory).generatePuzzle(mPuzzleStore, identity));
			result.add(mPuzzleFactories.get(factory).generatePuzzle(mPuzzleStore, identity));
		}
		
		flushCaches();
		
		return result;
	}
	
	/**
	 * Test for utility function of this test class, not for a function of IntroductionPuzzleStore.
	 */
	public void testGenerateNewPuzzles() throws IOException {
		OwnIdentity a = mOwnIdentities.get(0);
		OwnIdentity b = mOwnIdentities.get(1);
		
		final int puzzleCountA = generateNewPuzzles(a).size();
		final int puzzleCountB = generateNewPuzzles(b).size() + generateNewPuzzles(b).size();
		
		assertEquals(mPuzzleFactories.size()*2, puzzleCountA);
		assertEquals(mPuzzleFactories.size()*2*2, puzzleCountB);
		
		assertEquals(puzzleCountA, mPuzzleStore.getUninsertedOwnPuzzlesByInserter(a).size());
		assertEquals(puzzleCountB, mPuzzleStore.getUninsertedOwnPuzzlesByInserter(b).size());
	}

	public void testDeleteExpiredPuzzles() {
		// FIXME: Implement
	}

	public void testDeleteOldestUnsolvedPuzzles() {
		// FIXME: Implement
	}

	public void testOnIdentityDeletion() throws IOException, UnknownIdentityException {
		final OwnIdentity a = mOwnIdentities.get(0);
		final OwnIdentity b = mOwnIdentities.get(1);
		
		final ArrayList<OwnIntroductionPuzzle> deletedPuzzles = generateNewPuzzles(a);
		final int puzzleCountB = generateNewPuzzles(b).size();
		
		mWoT.deleteIdentity(a);
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
		final Date date = CurrentTimeUTC.get();
		
		OwnIntroductionPuzzle puzzle = new OwnIntroductionPuzzle(
				mOwnIdentities.get(0), PuzzleType.Captcha, "image/jpeg", new byte[] { 0 }, "foobar", 
					date, mPuzzleStore.getFreeIndex(mOwnIdentities.get(0), date));
		
		mPuzzleStore.storeAndCommit(puzzle);
		puzzle = puzzle.clone();
		
		flushCaches();
		
		assertEquals(puzzle, mPuzzleStore.getByID(puzzle.getID()));
	}

	public void testGetByID() throws IOException, UnknownPuzzleException {
		for(OwnIntroductionPuzzle puzzle : generateNewPuzzles(mOwnIdentities.get(0))) {
			assertSame(puzzle, mPuzzleStore.getByID(puzzle.getID()));
		}
		
		try {
			mPuzzleStore.getByID(UUID.randomUUID().toString());
			fail("No such puzzle should exist.");
		} catch(UnknownPuzzleException e) {} 
	}

	public void testGetPuzzleBySolutionURI() {
		// FIXME: Implement
	}

	public void testGetOwnPuzzleByRequestURI() {
		// FIXME: Implement
	}

	public void testGetOwnPuzzleBySolutionURI() {
		// FIXME: Implement
	}

	public void testGetFreeIndex() {
		// FIXME: Implement
	}

	public void testGetUninsertedOwnPuzzlesByInserter() {
		// FIXME: Implement
	}

	public void testGetUnsolvedByInserter() {
		// FIXME: Implement
	}

	public void testGetOfTodayByInserter() {
		// FIXME: Implement
	}

	public void testGetByInserterDateIndex() {
		// FIXME: Implement
	}

	public void testGetOwnPuzzleByInserterDateIndex() {
		// FIXME: Implement
	}

	public void testGetUnsolvedPuzzles() {
		// FIXME: Implement
	}

	public void testGetUninsertedSolvedPuzzles() {
		// FIXME: Implement
	}

	public void testGetOwnCatpchaAmount() {
		// FIXME: Implement
	}

	public void testGetNonOwnCaptchaAmount() {
		// FIXME: Implement
	}

}
