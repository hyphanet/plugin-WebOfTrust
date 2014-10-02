/* This code is part of WoT, a plugin for Freenet. It is distributed
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import java.util.ArrayList;
import java.util.Random;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import freenet.crypt.DummyRandomSource;
import freenet.crypt.RandomSource;
import freenet.keys.FreenetURI;
import freenet.keys.InsertableClientSSK;

/**
 * The base class for all JUnit4 tests in WOT.<br>
 * Contains utilities useful for all tests such as deterministic random number generation. 
 * 
 * @author xor (xor@freenetproject.org)
 */
@Ignore("Is ignored so it can be abstract. If you need to add self-tests, use member classes, "
    +   "they likely won't be ignored. But then also check that to make sure.")
public abstract class AbstractJUnit4BaseTest {

    protected RandomSource mRandom;
    
    @Rule
    public final TemporaryFolder mTempFolder = new TemporaryFolder();
    
    
    @Before public void setupRandomNumberGenerator() {
        Random seedGenerator = new Random();
        long seed = seedGenerator.nextLong();
        mRandom = new DummyRandomSource(seed);
        System.out.println(this + " Random seed: " + seed);
    }

    /**
     * Will be used as backend by member functions which generate random
     * {@link Identity} / {@link Trust} / {@link Score} objects. 
     */
    protected abstract WebOfTrust getWebOfTrust();

    /**
     * Adds identities with random request URIs to the database.
     * Their state will be as if they have never been fetched: They won't have a nickname, edition
     * will be 0, etc.
     * 
     * TODO: Make sure that this function also adds random contexts & publish trust list flags.
     * 
     * @param count Amount of identities to add
     * @return An {@link ArrayList} which contains all added identities.
     */
    protected ArrayList<Identity> addRandomIdentities(int count) {
        ArrayList<Identity> result = new ArrayList<Identity>(count+1);
        
        while(count-- > 0) {
            try {
                result.add(getWebOfTrust().addIdentity(getRandomRequestURI().toString()));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        
        return result;
    }

    /**
     * Generates a random SSK request URI, suitable for being used when creating identities.
     */
    protected FreenetURI getRandomRequestURI() {
        return InsertableClientSSK.createRandom(mRandom, "").getURI();
    }
}
