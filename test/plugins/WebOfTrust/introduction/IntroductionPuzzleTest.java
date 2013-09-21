/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.introduction;


import java.net.MalformedURLException;
import java.util.Date;
import java.util.UUID;

import org.junit.Before;
import org.junit.Test;

import plugins.WebOfTrust.DatabaseBasedTest;
import plugins.WebOfTrust.OwnIdentity;
import plugins.WebOfTrust.Persistent;
import plugins.WebOfTrust.exceptions.InvalidParameterException;
import plugins.WebOfTrust.introduction.IntroductionPuzzle.PuzzleType;
import freenet.support.CurrentTimeUTC;

/**
 * @author xor (xor@freenetproject.org)
 */
public class IntroductionPuzzleTest extends DatabaseBasedTest {

	private IntroductionPuzzleStore mPuzzleStore;
	
	@Before
	protected void setUp() throws Exception {
		super.setUp();
		
		mPuzzleStore = mWoT.getIntroductionPuzzleStore();
	}

	/**
	 * Constructs a puzzle of the given identity with the given expiration date. Does not store the puzzle in the database.
	 * 
	 * NOTICE: A copypasta of this function exists as {@link IntroductionPuzzleStoreTest#constructPuzzleWithExpirationDate(OwnIdentity, Date)
	 */
	private IntroductionPuzzle constructPuzzleWithExpirationDate(OwnIdentity identity, Date dateOfExpiration) {
		final Date dateOfInsertion = new Date(dateOfExpiration.getTime() - IntroductionServer.PUZZLE_INVALID_AFTER_DAYS * 24 * 60 * 60 * 1000);
		final IntroductionPuzzle p = new IntroductionPuzzle(mWoT, identity, UUID.randomUUID().toString() + "@" + identity.getID(), PuzzleType.Captcha, "image/jpeg", new byte[] { 0 }, 
				dateOfInsertion, dateOfExpiration, mPuzzleStore.getFreeIndex(identity, dateOfInsertion));
		return p;
	}
	
	/**
	 * NOTICE: A copypasta of this function exists as {@link IntroductionPuzzleStoreTest#constructPuzzle()}.
	 */
	private IntroductionPuzzle constructPuzzle() throws MalformedURLException, InvalidParameterException {
		return constructPuzzleWithExpirationDate(addRandomOwnIdentities(1).get(0), new Date(CurrentTimeUTC.getInMillis() + 24 * 60 * 60 * 1000));
	}
	
	@Test
	public void testClone() throws IllegalArgumentException, IllegalAccessException, MalformedURLException, InvalidParameterException, InterruptedException {
		final IntroductionPuzzle original = constructPuzzle();
		
		Thread.sleep(10); // Persistent contains Date mCreationDate which might not get properly cloned.
		assertFalse(CurrentTimeUTC.get().equals(original.getCreationDate()));
		
		final IntroductionPuzzle clone = original.clone();
		
		assertEquals(original, clone);
		assertNotSame(original, clone);
		
		testClone(Persistent.class, original, clone);
		testClone(IntroductionPuzzle.class, original, clone);
	}

}
