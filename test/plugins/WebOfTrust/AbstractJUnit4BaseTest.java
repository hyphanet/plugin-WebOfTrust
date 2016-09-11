/* This code is part of WoT, a plugin for Freenet. It is distributed
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.lang.Thread.UncaughtExceptionHandler;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import com.db4o.ext.ExtObjectContainer;

import plugins.WebOfTrust.Trust.TrustID;
import plugins.WebOfTrust.exceptions.DuplicateTrustException;
import plugins.WebOfTrust.exceptions.InvalidParameterException;
import plugins.WebOfTrust.exceptions.NotTrustedException;
import plugins.WebOfTrust.exceptions.UnknownIdentityException;
import plugins.WebOfTrust.util.IdentifierHashSet;
import plugins.WebOfTrust.util.RandomGrabHashSet;
import plugins.WebOfTrust.util.ReallyCloneable;
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
    
    /** @see #setupUncaughtExceptionHandler() */
    protected final AtomicReference<Throwable> uncaughtException
        = new AtomicReference<Throwable>(null);
    
    
    @Before public void setupRandomNumberGenerator() {
        Random seedGenerator = new Random();
        long seed = seedGenerator.nextLong();
        mRandom = new DummyRandomSource(seed);
        System.out.println(this + " Random seed: " + seed);
    }
    
    /**
     * JUnit will by default ignore uncaught Exceptions in threads other than the ones it
     * created itself, so we must register a handler for them to pass them to the main JUnit
     * threads. We pass them by setting {@link #uncaughtException}, and checking its value in
     * {@code @After} {@link #testUncaughtExceptions()}.
     */
    @Before public void setupUncaughtExceptionHandler() {
        Thread.setDefaultUncaughtExceptionHandler(new UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread t, Throwable e) {
                uncaughtException.compareAndSet(null, e);
            }
        });
    }
    
    /** @see #setupUncaughtExceptionHandler() */
    @After public void testUncaughtExceptions() {
        Throwable t = uncaughtException.get();
        if(t != null)
            fail(t.toString());
    }

    /**
     * Will be used as backend by member functions which generate random
     * {@link Identity} / {@link Trust} / {@link Score} objects. 
     */
    protected abstract WebOfTrust getWebOfTrust();

    /**
     * Returns a new {@link WebOfTrust} instance with an empty database. 
     * Multiple calls to this are guaranteed to use a different database file each.
     */
    protected WebOfTrust constructEmptyWebOfTrust() {
    	try {
    		File dataDir = mTempFolder.newFolder();
    		File database = new File(dataDir, dataDir.getName() + ".db4o");
    		assertFalse(database.exists());
    		return new WebOfTrust(database.toString());
    	} catch(IOException e) {
    		fail(e.toString());
    		throw new RuntimeException(e);
    	}
    }

    @After public void testDatabaseIntegrityAfterTermination() {
        WebOfTrust wot = getWebOfTrust();
        if(wot == null) // For testSetupUncaughtExceptionHandler() for example.
            return;
        
        // We cannot use Node.exit() because it would terminate the whole JVM.
        // TODO: Code quality: Once fred supports shutting down a Node without killing the JVM,
        // use that instead of only unloading WoT. https://bugs.freenetproject.org/view.php?id=6683
        /* mNode.exit("JUnit tearDown()"); */
        
        File database = wot.getDatabaseFile();
        wot.terminate();
        assertTrue(wot.isTerminated());
        wot = null;
        
        // The following commented-out assert would yield a false failure:
        // - setUpNode() already called terminate() upon various subsystems of WoT.
        // - When killPlugin() calls WebOfTrust.terminate(), that function will try to terminate()
        //   those subsystems again. This will fail because they are terminated already.
        // - WebOfTrust.terminate() will mark termination as failed due to subsystem termination
        //   failure. Thus, isTerminated() will return false.
        // TODO: Code quality: Find a way to avoid this so we can enable the assert.
        /* assertTrue(mWebOfTrust.isTerminated()); */
        
        WebOfTrust reopened = new WebOfTrust(database.toString());
        assertTrue(reopened.verifyDatabaseIntegrity());
        assertTrue(reopened.verifyAndCorrectStoredScores());
        reopened.terminate();
        assertTrue(reopened.isTerminated());
    }

    /**
     * Returns the union of {@link #addRandomOwnIdentities(int)} and
     * {@link #addRandomIdentities(int)}. */
    protected ArrayList<Identity> addRandomIdentities(int ownIdentityCount, int nonOwnIdentityCount)
            throws MalformedURLException, InvalidParameterException {
        
        ArrayList<Identity> result
            = new ArrayList<Identity>(ownIdentityCount + nonOwnIdentityCount);
        result.addAll(addRandomOwnIdentities(ownIdentityCount));
        result.addAll(addRandomIdentities(nonOwnIdentityCount));
        return result;
    }

    /**
     * Adds identities with random request URIs to the database.
     * Their state will be as if they have never been fetched: They won't have a nickname, edition
     * will be 0, etc.
     * 
     * TODO: Make sure that this function also adds random contexts & publish trust list flags.
     * 
     * NOTICE: In tests where you need {@link Score} objects to exist, you should ensure that there
     * are also {@link OwnIdentity} objects, because only they can cause Score objects to be
     * created.
     * Use {@link #addRandomIdentities(int, int)} to create both non-own and own identities.
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
     * @throws MalformedURLException        Upon test failure. Don't catch this, let it hit JUnit.
     * @throws InvalidParameterException    Upon test failure. Don't catch this, let it hit JUnit.
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
     * TODO: Adapt this to respect {@link Identity#doesPublishTrustList()}. First you need to adapt
     * the callers of this function to actually use identities which have set this to true - most
     * callers generate identities with the default value which is false.
     *
     * @throws InvalidParameterException    Upon test failure. Don't catch this, let it hit JUnit.
     */
    protected ArrayList<Trust> addRandomTrustValues(
            final List<Identity> identities, final int trustCount)
            throws InvalidParameterException, NotTrustedException {
        
        assert(trustCount <= identities.size()*(identities.size()-1))
            : "There can only be a single trust value between each pair of identities. The amount"
            + " of such pairs is identities * (identities-1). If you could use a trustCount which is"
            + " higher than this value then this function would run into an infinite loop.";
        
        final int identityCount = identities.size();
        
        ArrayList<Trust> result = new ArrayList<Trust>(trustCount + 1);
        
        getWebOfTrust().beginTrustListImport();
        for(int i=0; i < trustCount; ++i) {
            Identity truster = identities.get(mRandom.nextInt(identityCount));
            Identity trustee = identities.get(mRandom.nextInt(identityCount));
            
            if(truster == trustee) { // You cannot assign trust to yourself
                --i;
                continue;
            }
            
            try {
                // Only one trust value can exist between a given pair of identities:
                // We are bound to generate an amount of trustCount values,
                // so we have to check whether this pair of identities already has a trust.
                getWebOfTrust().getTrust(truster, trustee);
                --i;
                continue;
            } catch(NotTrustedException e) {}
            
            
            getWebOfTrust().setTrustWithoutCommit(truster, trustee, getRandomTrustValue(),
                getRandomLatinString(mRandom.nextInt(Trust.MAX_TRUST_COMMENT_LENGTH+1)));
            
            result.add(getWebOfTrust().getTrust(truster, trustee));
        }
        getWebOfTrust().finishTrustListImport();
        Persistent.checkedCommit(getWebOfTrust().getDatabase(), this);
        
        return result;
    }

    /**
     * General purpose stress test: Attempts to hit all primary WOT codepaths by doing the given
     * amount of events selected from the following operations:<br>
     * - {@link WebOfTrust#createOwnIdentity(FreenetURI, String, boolean, String)}.<br>
     * - {@link WebOfTrust#deleteOwnIdentity(String)}.<br>
     * - {@link WebOfTrust#restoreOwnIdentity(FreenetURI)}.<br>
     * - {@link WebOfTrust#restoreOwnIdentity(FreenetURI)} with existing colliding non-own Identity.
     *   This is interesting because restoreOwnIdentity() then ought to properly delete the non-own
     *   version of the identity first to prevent duplication.<br>
     * - {@link WebOfTrust#addIdentity(String)}.<br>
     * - {@link WebOfTrust#addContext(String, String)}.<br>
     * - {@link WebOfTrust#setProperty(String, String, String)}.<br>
     * - {@link WebOfTrust#setTrustWithoutCommit(Identity, Identity, byte, String)}.<br>
     * - {@link WebOfTrust#removeTrustWithoutCommit(Trust)}.<br><br>
     * 
     * This can be used for testing client interfaces such as {@link SubscriptionManager}:<br>
     * Doing most operations which WOT can do in a random manner should also cause this operations
     * to be propagated to the client properly. By doing a snapshot of the WOT database after
     * calling this function, and comparing it with what has been received at the client, you can
     * validate that the client interface properly passed everything to the client.  
     * 
     * @throws DuplicateTrustException      Upon test failure. Don't catch this, let it hit JUnit.
     * @throws NotTrustedException          Upon test failure. Don't catch this, let it hit JUnit.
     * @throws InvalidParameterException    Upon test failure. Don't catch this, let it hit JUnit.
     * @throws UnknownIdentityException     Upon test failure. Don't catch this, let it hit JUnit.
     * @throws MalformedURLException        Upon test failure. Don't catch this, let it hit JUnit.
     */
    protected void doRandomChangesToWOT(int eventCount)
            throws DuplicateTrustException, NotTrustedException, InvalidParameterException,
            UnknownIdentityException, MalformedURLException {

        final WebOfTrust mWoT = getWebOfTrust();
            
        @Ignore
        class Randomizer {
            final RandomGrabHashSet<String> allOwnIdentities
                = new RandomGrabHashSet<String>(mRandom);
            final RandomGrabHashSet<String> allIdentities
                = new RandomGrabHashSet<String>(mRandom);
            final RandomGrabHashSet<String> allTrusts
                = new RandomGrabHashSet<String>(mRandom);
            
            Randomizer() { 
                for(Identity identity : mWoT.getAllIdentities())
                    allIdentities.addOrThrow(identity.getID());
                
                for(OwnIdentity ownIdentity : mWoT.getAllOwnIdentities())
                    allOwnIdentities.addOrThrow(ownIdentity.getID());
                
                for(Trust trust : mWoT.getAllTrusts())
                    allTrusts.addOrThrow(trust.getID());
            }
        }
        final Randomizer randomizer = new Randomizer();
        
        final int eventTypeCount = 15;
        final long[] eventDurations = new long[eventTypeCount];
        final int[] eventIterations = new int[eventTypeCount];
        
        for(int i=0; i < eventCount; ++i) {
            final int type = mRandom.nextInt(eventTypeCount);
            final long startTime = System.nanoTime();
            switch(type) {
                case 0: // WebOfTrust.createOwnIdentity()
                    {
                        final OwnIdentity identity = mWoT.createOwnIdentity(
                                    getRandomInsertURI(), 
                                    getRandomLatinString(Identity.MAX_NICKNAME_LENGTH), 
                                    mRandom.nextBoolean(),
                                    getRandomLatinString(Identity.MAX_CONTEXT_NAME_LENGTH)
                                );
                        randomizer.allIdentities.addOrThrow(identity.getID());
                        randomizer.allOwnIdentities.addOrThrow(identity.getID());
                    }
                    break;
                case 1: // WebOfTrust.deleteOwnIdentity()
                    {
                        if(randomizer.allOwnIdentities.size() < 1) {
                            --i;
                            continue;
                        }

                        final String original = randomizer.allOwnIdentities.getRandom();
                        mWoT.deleteOwnIdentity(original);
                        randomizer.allIdentities.remove(original);
                        randomizer.allOwnIdentities.remove(original);
                        // Dummy non-own identity which deleteOwnIdenity() has replaced it with.
                        final Identity surrogate = mWoT.getIdentityByID(original);
                        assertFalse(surrogate.getClass().equals(OwnIdentity.class));
                        randomizer.allIdentities.addOrThrow(surrogate.getID());
                    }
                    break;
                case 2: // WebOfTrust.restoreOwnIdentity()
                    {
                        final InsertableClientSSK keypair 
                            = InsertableClientSSK.createRandom(mRandom, "");
                        mWoT.restoreOwnIdentity(keypair.getInsertURI());
                        final String id = mWoT.getOwnIdentityByURI(keypair.getURI()).getID();
                        randomizer.allIdentities.addOrThrow(id);
                        randomizer.allOwnIdentities.addOrThrow(id);
                    }
                    break;
                case 3: // WebOfTrust.restoreOwnIdentity() with existing colliding non-own Identity
                    {
                        final InsertableClientSSK keypair 
                            = InsertableClientSSK.createRandom(mRandom, "");
                        mWoT.addIdentity(keypair.getURI().toString());
                        mWoT.restoreOwnIdentity(keypair.getInsertURI());
                        final String id = mWoT.getOwnIdentityByURI(keypair.getURI()).getID();
                        randomizer.allIdentities.addOrThrow(id);
                        randomizer.allOwnIdentities.addOrThrow(id);
                    }
                    break;
                case 4: // WebOfTrust.addIdentity()
                    randomizer.allIdentities.addOrThrow(
                        mWoT.addIdentity(getRandomRequestURI().toString()).getID());
                    break;
                case 5: // WebOfTrust.addContext() (adds context to identity)
                    {
                        if(randomizer.allOwnIdentities.size() < 1) {
                            --i;
                            continue;
                        }
                        
                        final String ownIdentityID = randomizer.allOwnIdentities.getRandom();
                        final String context
                            = getRandomLatinString(Identity.MAX_CONTEXT_NAME_LENGTH);
                        
                        mWoT.addContext(ownIdentityID, context);
                        if(mRandom.nextBoolean())
                            mWoT.removeContext(ownIdentityID, context);
                    }
                    break;
                case 6: // WebOfTrust.setProperty (adds property to identity)
                    {
                        if(randomizer.allOwnIdentities.size() < 1) {
                            --i;
                            continue;
                        }
                        
                        final String ownIdentityID = randomizer.allOwnIdentities.getRandom();
                        final String propertyName
                            = getRandomLatinString(Identity.MAX_PROPERTY_NAME_LENGTH);
                        final String propertyValue
                            = getRandomLatinString(Identity.MAX_PROPERTY_VALUE_LENGTH);
                        
                        mWoT.setProperty(ownIdentityID, propertyName, propertyValue);
                        if(mRandom.nextBoolean())
                            mWoT.removeProperty(ownIdentityID, propertyName);
                    }
                    break;
                // Add/change trust value. Higher probability because trust values are the most
                // changes which will happen on the real network
                case 7: 
                case 8:
                case 9:
                case 10:
                case 11:
                case 12:
                case 13:
                    {
                        if(randomizer.allIdentities.size() < 2) {
                            --i;
                            continue;
                        }
                        
                        Identity truster;
                        Identity trustee;
                        do {
                            truster = mWoT.getIdentityByID(randomizer.allIdentities.getRandom());
                            trustee = mWoT.getIdentityByID(randomizer.allIdentities.getRandom());
                        } while(truster == trustee);
                        
                        mWoT.beginTrustListImport();
                        mWoT.setTrustWithoutCommit(truster, trustee, getRandomTrustValue(),
                            getRandomLatinString(Trust.MAX_TRUST_COMMENT_LENGTH));
                        mWoT.finishTrustListImport();
                        Persistent.checkedCommit(mWoT.getDatabase(), this);
                        
                        final String trustID = new TrustID(truster, trustee).toString();
                        // We selected the truster/trustee randomly so a value may have existed
                        if(!randomizer.allTrusts.contains(trustID))
                            randomizer.allTrusts.addOrThrow(trustID); 
                    }
                    break;
                case 14: // Remove trust value
                    {
                        if(randomizer.allTrusts.size() < 1) {
                            --i;
                            continue;
                        }
                        
                        mWoT.beginTrustListImport();
                        final Trust trust = mWoT.getTrust(randomizer.allTrusts.getRandom());
                        mWoT.removeTrustWithoutCommit(trust);
                        mWoT.finishTrustListImport();
                        Persistent.checkedCommit(mWoT.getDatabase(), this);
                        
                        randomizer.allTrusts.remove(trust.getID());
                    }
                    break;
                default:
                    throw new RuntimeException("Please adapt eventTypeCount above!");
            }
            final long endTime = System.nanoTime();
            eventDurations[type] += (endTime-startTime);
            ++eventIterations[type];
        }
        
        for(int i=0; i < eventTypeCount; ++i) {
            System.out.println("Event type " + i + ": Happend " + eventIterations[i] + " times; "
                    + "avg. seconds: "
                    + (((double)eventDurations[i])/eventIterations[i]) / (1000*1000*1000));
        }
    }

    /**
     * NOTICE: {@link #listToSetWithDuplicateCheck(List, boolean)} provides important
     * information about using the returned HashSet. */
    protected HashSet<Identity> getAllIdentities() {
        return listToSetWithDuplicateCheck(getWebOfTrust().getAllIdentities());
    }

    /**
     * NOTICE: {@link #listToSetWithDuplicateCheck(List, boolean)} provides important
     * information about using the returned HashSet. */
    protected HashSet<Trust> getAllTrusts() {
        return listToSetWithDuplicateCheck(getWebOfTrust().getAllTrusts());
    }

    /**
     * NOTICE: {@link #listToSetWithDuplicateCheck(List, boolean)} provides important
     * information about using the returned HashSet. */
    protected HashSet<Score> getAllScores() {
        return listToSetWithDuplicateCheck(getWebOfTrust().getAllScores());
    }

    /** Calls {@link #listToSetWithDuplicateCheck(List, boolean)} with returnClones = false */
    protected <T extends Persistent & ReallyCloneable<T>> HashSet<T> listToSetWithDuplicateCheck(
            List<T> list) {
        
        return listToSetWithDuplicateCheck(list, false);
    }

    /**
     * NOTICE: HashSet is generally not safe for use with {@link Persistent} due to the
     * implementations of {@link Persistent#equals(Object)} in many of its child classes:
     * They typically compare not only object identity but also object state. Thus multiple
     * instances of the same object with different state could enter HashSets. For a detailed
     * explanation, see class {@link IdentifierHashSet}.<br>
     * This function can return a HashSet safely, as it validates whether the passed list only
     * contains unique instances of the objects and thus the problems of equality checks cannot
     * arise. The function guarantees that it will cause test failure if duplicates are passed.<br>
     * However, when doing anything with the returned HashSet, please be aware of the behavior of
     * {@link Persistent#equals(Object)} implementations. */
    protected <T extends Persistent & ReallyCloneable<T>> HashSet<T> listToSetWithDuplicateCheck(
            List<T> list, boolean returnClones) {
        
        final HashSet<T> result = new HashSet<T>(list.size() * 2);
        final IdentifierHashSet<T> uniquenessTest = new IdentifierHashSet<T>(list.size() * 2);
        
        for(T object : list) {
            if(returnClones)
                object = object.cloneP();
            
            // Check whether the calling code delivered a list of unique objects.
            // We need to test this with an IdentifierHashSet due to the aforementioned issues of
            // Persistent.equals().
            assertTrue(uniquenessTest.add(object));
            // Also, it is critical to ensure we don't just overwrite a potential duplicate in the
            // set, because the calling unit test code typically wants to detect duplicates as they
            // are usually bugs.
            // This is implicitly checked by the above assert already. But we get the return value
            // for free - so let's just test it.
            assertTrue(result.add(object));
        }
        
        return result;
    }

    /**
     * Returns a normally distributed value with a bias towards positive trust values.
     * TODO: Remove this bias once trust computation is equally fast for negative values;
     */
    protected byte getRandomTrustValue() {
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

	protected void flushCaches() {
		System.gc();
		System.runFinalization();
		WebOfTrust wot = getWebOfTrust();
		if(wot != null) {
			ExtObjectContainer db = wot.getDatabase();
			Persistent.checkedRollback(db, this, null);
			db.purge();
		}
		System.gc();
		System.runFinalization();
	}

	/**
	 * Uses reflection to check assertEquals() and assertNotSame() on all member fields of an original and its clone().
	 * Does not check assertNotSame() for:
	 * - enum field
	 * - String fields
	 * - transient fields
	 * 
	 * ATTENTION: Only checks the fields of the given clazz, NOT of its parent class.
	 * If you need to test the fields of an object of class B with parent class A, you should call this two times:
	 * Once with clazz set to A and once for B.
	 * 
	 * @param class The clazz whose fields to check. The given original and clone must be an instance of this or a subclass of it. 
	 * @param original The original object.
	 * @param clone A result of <code>original.clone();</code>
	 */
	protected static void testClone(Class<?> clazz, Object original, Object clone)
			throws IllegalArgumentException, IllegalAccessException {
		
		for(Field field : clazz.getDeclaredFields()) {
			field.setAccessible(true);
			if(!field.getType().isArray()) {
				assertEquals(field.toGenericString(), field.get(original), field.get(clone));
			} else { // We need to check it deeply if it is an array
				// Its not possible to cast primitive arrays such as byte[] to Object[]
				// Therefore, we must store them as Object which is possible, and then use Array.get()
				final Object originalArray = field.get(original);
				final Object clonedArray = field.get(clone);
				
				assertEquals(Array.getLength(originalArray), Array.getLength(clonedArray));
				for(int i=0; i < Array.getLength(originalArray); ++i) {
					testClone(originalArray.getClass(), Array.get(originalArray, i), Array.get(clonedArray, i));
				}
			}
				
			
			if(!field.getType().isEnum() // Enum objects exist only once
				&& field.getType() != String.class // Strings are interned and therefore might also exist only once
				&& !Modifier.isTransient(field.getModifiers())) // Persistent.mWebOfTurst/mDB are transient field which have the same value everywhere
			{
				final Object originalField = field.get(original);
				final Object clonedField = field.get(clone);
				if(originalField != null)
					assertNotSame(field.toGenericString(), originalField, clonedField);
				else
					assertNull(field.toGenericString(), clonedField); // assertNotSame would fail if both are null because null and null are the same
			}
		}
	}
}
