/* This code is part of WoT, a plugin for Freenet. It is distributed
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Random;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import plugins.WebOfTrust.exceptions.InvalidParameterException;
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
     * Generates {@link OwnIdentity}s with:<br>
     * - a valid random insert URI and request URI.<br>
     * - a valid a random Latin nickname with length of {@link Identity#MAX_NICKNAME_LENGTH}.<br>
     * - a random setting for {@link Identity#doesPublishTrustList()}.<br>
     * - a random context with length {@link Identity#MAX_CONTEXT_NAME_LENGTH}.<br><br>
     * 
     * The OwnIdentitys are stored in the WOT database, and the original (= non-cloned) objects are
     * returned in an {@link ArrayList}.
     * 
     * @throws MalformedURLException
     *             Should never happen.
     * @throws InvalidParameterException
     *             Should never happen.
     */
    protected ArrayList<OwnIdentity> addRandomOwnIdentities(int count)
            throws MalformedURLException, InvalidParameterException {
        
        ArrayList<OwnIdentity> result = new ArrayList<OwnIdentity>(count+1);
        
        while(count-- > 0) {
            final OwnIdentity ownIdentity = getWebOfTrust().createOwnIdentity(getRandomInsertURI(),
                getRandomLatinString(Identity.MAX_NICKNAME_LENGTH), mRandom.nextBoolean(),
                getRandomLatinString(Identity.MAX_CONTEXT_NAME_LENGTH));
            result.add(ownIdentity); 
        }
        
        return result;
        
    }
    
    /**
     * Returns a normally distributed value with a bias towards positive trust values.
     * TODO: Remove this bias once trust computation is equally fast for negative values;
     */
    private byte getRandomTrustValue() {
        final double trustRange = Trust.MAX_TRUST_VALUE - Trust.MIN_TRUST_VALUE + 1;
        long result;
        do {
            result = Math.round(mRandom.nextGaussian()*(trustRange/2) + (trustRange/3));
        } while(result < Trust.MIN_TRUST_VALUE || result > Trust.MAX_TRUST_VALUE);
        
        return (byte)result;
    }

    /**
     * Generates a random SSK insert URI, for being used when creating {@link OwnIdentity}s.
     */
    protected FreenetURI getRandomInsertURI() {
        return InsertableClientSSK.createRandom(mRandom, "").getInsertURI();
    }

    /**
     * Generates a random SSK request URI, suitable for being used when creating identities.
     */
    protected FreenetURI getRandomRequestURI() {
        return InsertableClientSSK.createRandom(mRandom, "").getURI();
    }
    
    /**
     * Generates a String containing random characters of the lowercase Latin alphabet.
     * @param The length of the returned string.
     */
    protected String getRandomLatinString(int length) {
        char[] s = new char[length];
        for(int i=0; i<length; ++i)
            s[i] = (char)('a' + mRandom.nextInt(26));
        return new String(s);
    }
}
