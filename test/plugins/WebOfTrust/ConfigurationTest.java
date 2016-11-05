/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import static org.junit.Assert.*;

import java.io.File;
import java.util.Arrays;

import org.junit.Test;

/** Tests {@link Configuration} */
public final class ConfigurationTest extends AbstractJUnit4BaseTest {

	private WebOfTrust mWoT = null;

	/** Tests {@link Configuration#getRandomPad()}. */
	@Test public void testGetRandomPad() {
		mWoT = constructEmptyWebOfTrust();
		File db = mWoT.getDatabaseFile();
		byte[] pad = mWoT.getConfig().getConstantRandomPad();
		
		assertEquals(32, pad.length);
		
		// Arrays are mutable so it should return a copy
		assertNotSame(pad, mWoT.getConfig().getConstantRandomPad());
		// Should stay the same across multiple invocations
		assertArrayEquals(pad, mWoT.getConfig().getConstantRandomPad());
		
		boolean hasNonDefaultMember = false;
		for(int i = 0; i < pad.length; ++i)
			hasNonDefaultMember |= (pad[i] != 0);
		assertTrue(hasNonDefaultMember);
		
		WebOfTrust otherWoT = constructEmptyWebOfTrust();
		assertFalse(Arrays.equals(pad, otherWoT.getConfig().getConstantRandomPad()));
		otherWoT.terminate();
		otherWoT = null;
		
		// Check whether the random pad is constant across restarts as it must be
		
		mWoT.terminate();
		mWoT = null;
		flushCaches();
		
		mWoT = new WebOfTrust(db.toString());
		byte[] newPad = mWoT.getConfig().getConstantRandomPad();
		
		assertNotSame(pad, newPad);
		assertArrayEquals(pad, newPad);
	}

	@Override protected WebOfTrust getWebOfTrust() {
		return mWoT;
	}

}
