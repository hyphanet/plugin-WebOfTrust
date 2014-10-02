/* This code is part of WoT, a plugin for Freenet. It is distributed
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import static org.junit.Assert.*;

import java.util.Random;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import freenet.crypt.DummyRandomSource;
import freenet.crypt.RandomSource;

/**
 * The base class for all JUnit4 tests in WOT.<br>
 * Contains utilities useful for all tests such as deterministic random number generation. 
 * 
 * @author xor (xor@freenetproject.org)
 */
public /* abstract (Not used so JUnit doesn't complain) */ class AbstractJUnit4BaseTest {

    protected RandomSource mRandom;
    
    @Rule
    protected final TemporaryFolder mTempFolder = new TemporaryFolder();
    
    
    @Before public void setupRandomNumberGenerator() {
        Random seedGenerator = new Random();
        long seed = seedGenerator.nextLong();
        mRandom = new DummyRandomSource(seed);
        System.out.println(this + " Random seed: " + seed);
    }
    
    @Test public void testSelf() {
        assertNotNull(mRandom);
    }

}
