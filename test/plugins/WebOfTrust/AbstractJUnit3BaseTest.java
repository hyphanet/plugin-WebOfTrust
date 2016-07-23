/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

import junit.framework.TestCase;

import org.junit.rules.TemporaryFolder;

import plugins.WebOfTrust.exceptions.InvalidParameterException;
import plugins.WebOfTrust.exceptions.NotTrustedException;
import plugins.WebOfTrust.util.IdentifierHashSet;
import plugins.WebOfTrust.util.ReallyCloneable;
import freenet.crypt.DummyRandomSource;
import freenet.crypt.RandomSource;
import freenet.keys.FreenetURI;
import freenet.keys.InsertableClientSSK;

/**
 * A JUnit <code>TestCase</code> which opens a db4o database in setUp() and closes it in tearDown().
 * The filename of the database is chosen as the name of the test function currently run by db4o and the
 * file is deleted after the database has been closed. When setting up the test, it is assured that the database
 * file does not exist, the test will fail if it cannot be deleted.
 * 
 * The database can be accessed through the member variable <code>db</code>.
 * 
 * You have to call super.setUp() and super.tearDown() if you override one of those methods.
 * 
 * @author xor (xor@freenetproject.org)
 * @deprecated Use {@link AbstractJUnit4BaseTest} instead.
 */
@Deprecated
public class AbstractJUnit3BaseTest extends TestCase {

	protected WebOfTrust mWoT;
	
	protected RandomSource mRandom;

	/**
	 * TODO: Code quality: When migrating this code to {@link AbstractJUnit4BaseTest}, use
	 * JUnit's {@link TemporaryFolder} instead. It will both ensure that the file does not exist
	 * and that it is deleted at shutdown.
	 *  
	 * @return Returns the filename of the database. This is the name of the current test function plus ".db4o".
	 */
	public String getDatabaseFilename() {
		return getName() + ".db4o";
	}

	/**
	 * You have to call super.setUp() if you override this method.
	 */
	@Override
	protected void setUp() throws Exception {
		super.setUp();
		
		File databaseFile = new File(getDatabaseFilename());
		if(databaseFile.exists())
			databaseFile.delete();
		assertFalse(databaseFile.exists());;
		databaseFile.deleteOnExit();
		
		mWoT = new WebOfTrust(getDatabaseFilename());
		
		Random random = new Random();
		long seed = random.nextLong();
		mRandom = new DummyRandomSource(seed);
		System.out.println(this + " Random seed: " + seed);
	}

	/**
	 * You have to call super.tearDown() if you override this method. 
	 */
	@Override
	protected void tearDown() throws Exception {
		super.tearDown();
		
		mWoT.terminate();
		assertTrue(mWoT.isTerminated());
		mWoT = null;
		
		WebOfTrust reopened = new WebOfTrust(getDatabaseFilename());
		assertTrue(reopened.verifyDatabaseIntegrity());
		assertTrue(reopened.verifyAndCorrectStoredScores());
		reopened.terminate();
		assertTrue(reopened.isTerminated());
		
		new File(getDatabaseFilename()).delete();
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
	protected void testClone(Class<?> clazz, Object original, Object clone) throws IllegalArgumentException, IllegalAccessException {
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
	
	/**
	 * Generates a String containing random characters of the lowercase Latin alphabet.
	 * @param The length of the returned string.
	 * @deprecated Use {@link AbstractJUnit4BaseTest#getRandomLatinString(int)} instead, which is a
     *             copypaste of this.
	 */
	@Deprecated
	protected String getRandomLatinString(int length) {
		char[] s = new char[length];
		for(int i=0; i<length; ++i)
			s[i] = (char)('a' + mRandom.nextInt(26));
		return new String(s);
	}
	
	/**
	 * Returns a normally distributed value with a bias towards positive trust values.
	 * TODO: Remove this bias once trust computation is equally fast for negative values;
	 * 
     * @deprecated Use {@link AbstractJUnit4BaseTest#getRandomTrustValue()} instead, which is a
     *             copypaste of this.
	 */
	@Deprecated
	private byte getRandomTrustValue() {
		final double trustRange = Trust.MAX_TRUST_VALUE - Trust.MIN_TRUST_VALUE + 1;
		long result;
		do {
			result = Math.round(mRandom.nextGaussian()*(trustRange/2) + (trustRange/3));
		} while(result < Trust.MIN_TRUST_VALUE || result > Trust.MAX_TRUST_VALUE);
		
		return (byte)result;
	}
	
	/**
	 * Generates a random SSK request-/insert-keypair, suitable for being used when creating identities.
	 * @return An array where slot 0 is the insert URI and slot 1 is the request URI
	 * @deprecated Use {@link AbstractJUnit4BaseTest#getRandomInsertURI()} instead. Notice that
	 *             even though in opposite to this function it only generates the insert URI, not
	 *             the request URI, it will probably be sufficient: {@link OwnIdentity} creation has
	 *             been adapted some time ago to only require the insert URI, not the full keypair.
	 *             If it turns out to be not sufficient, please copy this function to
	 *             {@link AbstractJUnit4BaseTest}.  
	 */
	@Deprecated
	protected FreenetURI[] getRandomSSKPair() {
		InsertableClientSSK ssk = InsertableClientSSK.createRandom(mRandom, "");
		return new FreenetURI[]{ ssk.getInsertURI(), ssk.getURI() };
	}
	
	/**
	 * Generates a random SSK request URI, suitable for being used when creating identities.
	 * 
	 * @deprecated Use {@link AbstractJUnit4BaseTest#getRandomRequestURI()} instead, which is a
	 *             copypaste of this.
	 */
	@Deprecated
	protected FreenetURI getRandomRequestURI() {
		return InsertableClientSSK.createRandom(mRandom, "").getURI();
	}
	
	/**
	 * Adds identities with random request URIs to the database.
	 * Their state will be as if they have never been fetched: They won't have a nickname, edition will be 0, etc.
	 * 
	 * TODO: Make sure that this function also adds random contexts & publish trust list flags.
	 * 
	 * @param count Amount of identities to add
	 * @return An {@link ArrayList} which contains all added identities.
     * @deprecated Use {@link AbstractJUnit4BaseTest#addRandomIdentities(int)} instead, which is a
     *             copypaste of this.
	 */
	@Deprecated
	protected ArrayList<Identity> addRandomIdentities(int count) {
		ArrayList<Identity> result = new ArrayList<Identity>(count+1);
		
		while(count-- > 0) {
			try {
				result.add(mWoT.addIdentity(getRandomRequestURI().toString()));
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}
		
		return result;
	}
	
	/**
     * @param count Amount of identities to add
     * @return An {@link ArrayList} which contains all added identities.
     * @deprecated Use {@link AbstractJUnit4BaseTest#addRandomOwnIdentities(int)} instead, which is
     *             a copypaste of this.
	 */
	@Deprecated
	protected ArrayList<OwnIdentity> addRandomOwnIdentities(int count) throws MalformedURLException, InvalidParameterException {
		ArrayList<OwnIdentity> result = new ArrayList<OwnIdentity>(count+1);
		
		while(count-- > 0) {
			final OwnIdentity ownIdentity = mWoT.createOwnIdentity(getRandomSSKPair()[0], getRandomLatinString(Identity.MAX_NICKNAME_LENGTH), mRandom.nextBoolean(), getRandomLatinString(Identity.MAX_CONTEXT_NAME_LENGTH));
			result.add(ownIdentity); 
		}
		
		return result;
		
	}
	
	/**
	 * ATTENTION: Its impossible to store more trust values than the amount of identities squared: There can only be a single trust value
	 * between each pair of identities. The amount of such pairs is identities². If you specify a trustCount which is higher than this 
	 * value then this function will run into an infinite loop.
	 * 
	 * TODO: Adapt this to respect {@link Identity#doesPublishTrustList()}. First you need to adapt the callers of this function to actually
	 * use identities which have set this to true - most callers generate identities with the default value which is false.
	 * 
     * @deprecated Use {@link AbstractJUnit4BaseTest#addRandomTrustValues(ArrayList, int)} instead,
     *             which is a copypaste of this.
	 */
	@Deprecated
	protected void addRandomTrustValues(final ArrayList<Identity> identities, final int trustCount) throws InvalidParameterException {
		final int identityCount = identities.size();
		
		mWoT.beginTrustListImport();
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
				mWoT.getTrust(truster, trustee);
				--i;
				continue;
			} catch(NotTrustedException e) {}
			
			
			mWoT.setTrustWithoutCommit(truster, trustee, getRandomTrustValue(), getRandomLatinString(mRandom.nextInt(Trust.MAX_TRUST_COMMENT_LENGTH+1)));
		}
		mWoT.finishTrustListImport();
		Persistent.checkedCommit(mWoT.getDatabase(), this);
	}

	/**
	 * NOTICE: {@link #listToSetWithDuplicateCheck(List, boolean)} provides important
	 * information about using the returned HashSet. */
	protected HashSet<Identity> cloneAllIdentities() {
		return listToSetWithDuplicateCheck(mWoT.getAllIdentities(), true);
	}

	/**
	 * NOTICE: {@link #listToSetWithDuplicateCheck(List, boolean)} provides important
	 * information about using the returned HashSet.
	 * 
	 * @deprecated Use {@link AbstractJUnit4BaseTest#getAllIdentities()} instead. */
	@Deprecated
	protected HashSet<Identity> getAllIdentities() {
		return listToSetWithDuplicateCheck(mWoT.getAllIdentities());
	}

	/**
	 * NOTICE: {@link #listToSetWithDuplicateCheck(List, boolean)} provides important
	 * information about using the returned HashSet. */
	protected HashSet<Trust> cloneAllTrusts() {
		return listToSetWithDuplicateCheck(mWoT.getAllTrusts(), true);
	}

	/**
	 * NOTICE: {@link #listToSetWithDuplicateCheck(List, boolean)} provides important
	 * information about using the returned HashSet.
	 * 
	 * @deprecated Use {@link AbstractJUnit4BaseTest#getAllTrusts()} instead. */
	@Deprecated
	protected HashSet<Trust> getAllTrusts() {
		return listToSetWithDuplicateCheck(mWoT.getAllTrusts());
	}

	/**
	 * NOTICE: {@link #listToSetWithDuplicateCheck(List, boolean)} provides important
	 * information about using the returned HashSet. */
	protected HashSet<Score> cloneAllScores() {
		return listToSetWithDuplicateCheck(mWoT.getAllScores(), true);
	}

	/**
	 * NOTICE: {@link #listToSetWithDuplicateCheck(List, boolean)} provides important
	 * information about using the returned HashSet.
	 * 
	 * @deprecated Use {@link AbstractJUnit4BaseTest#getAllScores()} instead. */
	@Deprecated
	protected HashSet<Score> getAllScores() {
		return listToSetWithDuplicateCheck(mWoT.getAllScores());
	}

	/**
	 * Calls {@link #listToSetWithDuplicateCheck(List, boolean)} with returnClones = false
	 * 
	 * @deprecated Use {@link AbstractJUnit4BaseTest#listToSetWithDuplicateCheck(List)} */
	@Deprecated
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
	 * {@link Persistent#equals(Object)} implementations.
	 * 
	 * @deprecated
	 *     Use {@link AbstractJUnit4BaseTest#listToSetWithDuplicateCheck(List, boolean)} */
	@Deprecated
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
	 * Does nothing. Just here because JUnit will complain if there are no tests.
	 */
	public void testSelf() {
		
	}
	
	protected void flushCaches() {
		System.gc();
		System.runFinalization();
		if(mWoT != null) {
			Persistent.checkedRollback(mWoT.getDatabase(), this, null);
			mWoT.getDatabase().purge();
		}
		System.gc();
		System.runFinalization();
	}

}

