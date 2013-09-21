/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.introduction;


import java.io.IOException;
import java.net.MalformedURLException;

import org.junit.Before;
import org.junit.Test;

import plugins.WebOfTrust.DatabaseBasedTest;
import plugins.WebOfTrust.Persistent;
import plugins.WebOfTrust.exceptions.InvalidParameterException;
import plugins.WebOfTrust.introduction.captcha.CaptchaFactory1;
import freenet.support.CurrentTimeUTC;

/**
 * @author xor (xor@freenetproject.org)
 */
public final class OwnIntroductionPuzzleTest extends DatabaseBasedTest {

	private IntroductionPuzzleStore mPuzzleStore;
	private IntroductionPuzzleFactory mPuzzleFactory;
	
	@Before
	protected void setUp() throws Exception {
		super.setUp();
		
		mPuzzleStore = mWoT.getIntroductionPuzzleStore();
		
		mPuzzleFactory = new CaptchaFactory1();
	}
	
	
	protected OwnIntroductionPuzzle constructPuzzle() throws MalformedURLException, IOException, InvalidParameterException {
		return mPuzzleFactory.generatePuzzle(mPuzzleStore, addRandomOwnIdentities(1).get(0));
	}

	@Test
	public void testClone() throws MalformedURLException, IOException, InvalidParameterException, InterruptedException, IllegalArgumentException, IllegalAccessException {
		final OwnIntroductionPuzzle original = constructPuzzle();
		
		Thread.sleep(10); // Persistent contains Date mCreationDate which might not get properly cloned.
		assertFalse(CurrentTimeUTC.get().equals(original.getCreationDate()));
		
		final IntroductionPuzzle clone = original.clone();
		
		assertEquals(original, clone);
		assertNotSame(original, clone);
		
		testClone(Persistent.class, original, clone);
		testClone(IntroductionPuzzle.class, original, clone);
		testClone(OwnIntroductionPuzzle.class, original, clone);
	}

}
