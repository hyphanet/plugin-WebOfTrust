/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Random;

import junit.framework.TestCase;

import org.junit.Ignore;

import plugins.WebOfTrust.Trust.TrustID;
import plugins.WebOfTrust.exceptions.DuplicateTrustException;
import plugins.WebOfTrust.exceptions.InvalidParameterException;
import plugins.WebOfTrust.exceptions.NotTrustedException;
import plugins.WebOfTrust.exceptions.UnknownIdentityException;
import plugins.WebOfTrust.util.RandomGrabHashSet;

import com.db4o.ObjectSet;

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
 */
public class DatabaseBasedTest extends TestCase {

	protected WebOfTrust mWoT;
	
	protected RandomSource mRandom;

	/**
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
			assertEquals(field.toGenericString(), field.get(original), field.get(clone));
			
			if(!field.getType().isEnum() // Enum objects exist only once
				&& field.getType() != String.class // Strings are interned and therefore might also exist only once
				&& !Modifier.isTransient(field.getModifiers())) // Persistent.mWebOfTurst/mDB are transient field which have the same value everywhere
			{
				assertNotSame(field.toGenericString(), field.get(original), field.get(clone));
			}
		}
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
	 * Generates a random SSK request-/insert-keypair, suitable for being used when creating identities.
	 * @return An array where slot 0 is the insert URI and slot 1 is the request URI
	 */
	protected FreenetURI[] getRandomSSKPair() {
		InsertableClientSSK ssk = InsertableClientSSK.createRandom(mRandom, "");
		return new FreenetURI[]{ ssk.getInsertURI(), ssk.getURI() };
	}
	
	/**
	 * Generates a random SSK request URI, suitable for being used when creating identities.
	 */
	protected FreenetURI getRandomRequestURI() {
		return InsertableClientSSK.createRandom(mRandom, "").getURI();
	}
	
	/**
	 * Adds identities with random request URIs to the database.
	 * Their state will be as if they have never been fetched: They won't have a nickname, edition will be 0, etc.
	 * 
	 * @param count Amount of identities to add
	 * @return An {@link ArrayList} which contains all added identities.
	 */
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
	 */
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
			
			
			mWoT.setTrustWithoutCommit(truster, trustee, getRandomTrustValue(), "");
		}
		mWoT.finishTrustListImport();
		Persistent.checkedCommit(mWoT.getDatabase(), this);
	}
	
	protected void doRandomChangesToWOT(int eventCount) throws DuplicateTrustException, NotTrustedException, InvalidParameterException, UnknownIdentityException, MalformedURLException {
		@Ignore
		class Randomizer {
			final RandomGrabHashSet<String> allOwnIdentities = new RandomGrabHashSet<String>(mRandom);
			final RandomGrabHashSet<String> allIdentities = new RandomGrabHashSet<String>(mRandom);
			final RandomGrabHashSet<String> allTrusts = new RandomGrabHashSet<String>(mRandom);
			
			Randomizer() { 
				for(Identity identity : mWoT.getAllIdentities())
					allIdentities.add(identity.getID());
				
				for(OwnIdentity ownIdentity : mWoT.getAllOwnIdentities())
					allOwnIdentities.add(ownIdentity.getID());
				
				for(Trust trust : mWoT.getAllTrusts())
					allTrusts.add(trust.getID());
			}
		}
		final Randomizer randomizer = new Randomizer();
		
		for(int i=0; i < eventCount; ++i) {
			switch(mRandom.nextInt(12 + 1)) {
				case 0:
					{
						final OwnIdentity identity = mWoT.createOwnIdentity(
									getRandomSSKPair()[0], 
									getRandomLatinString(Identity.MAX_NICKNAME_LENGTH), 
									mRandom.nextBoolean(),
									getRandomLatinString(Identity.MAX_CONTEXT_NAME_LENGTH)
								);
						randomizer.allIdentities.add(identity.getID());
						randomizer.allOwnIdentities.add(identity.getID());
					}
					break;
				case 1:
					{
						final String original = randomizer.allOwnIdentities.getRandom();
						mWoT.deleteOwnIdentity(original);
						randomizer.allIdentities.remove(original);
						randomizer.allOwnIdentities.remove(original);
						// Dummy non-own identity which deleteOwnIdenity() has replaced it with.
						final Identity surrogate = mWoT.getIdentityByID(original);
						assertFalse(surrogate.getClass().equals(OwnIdentity.class));
						randomizer.allIdentities.add(surrogate.getID());
					}
					break;
				case 2:
					{
						final FreenetURI[] keypair = getRandomSSKPair();
						mWoT.restoreOwnIdentity(keypair[0]);
						final String id = mWoT.getOwnIdentityByURI(keypair[1]).getID();
						randomizer.allIdentities.add(id);
						randomizer.allOwnIdentities.add(id);
					}
					break;
				case 3:
					{
						final FreenetURI[] keypair = getRandomSSKPair();
						mWoT.addIdentity(keypair[1].toString());
						mWoT.restoreOwnIdentity(keypair[0]);
						final String id = mWoT.getOwnIdentityByURI(keypair[1]).getID();
						randomizer.allIdentities.add(id);
						randomizer.allOwnIdentities.add(id);
					}
					break;
				case 4:
					randomizer.allIdentities.add(mWoT.addIdentity(getRandomRequestURI().toString()).getID());
					break;
				case 5:
					{
						final String ownIdentityID = randomizer.allOwnIdentities.getRandom();
						final String context = getRandomLatinString(Identity.MAX_CONTEXT_NAME_LENGTH);
						mWoT.addContext(ownIdentityID, context);
						if(mRandom.nextBoolean())
							mWoT.removeContext(ownIdentityID, context);
					}
					break;
				case 6:
					{
						final String ownIdentityID = randomizer.allOwnIdentities.getRandom();
						final String propertyName = getRandomLatinString(Identity.MAX_PROPERTY_NAME_LENGTH);
						final String propertyValue = getRandomLatinString(Identity.MAX_PROPERTY_VALUE_LENGTH);
						mWoT.setProperty(ownIdentityID, propertyName, propertyValue);
						if(mRandom.nextBoolean())
							mWoT.removeContext(ownIdentityID, propertyName);
					}
					break;
				case 7:
				case 8:
				case 9:
				case 10:
				case 11:
					{
						Identity truster;
						Identity trustee;
						do {
							truster = mWoT.getIdentityByID(randomizer.allIdentities.getRandom());
							trustee = mWoT.getIdentityByID(randomizer.allIdentities.getRandom());
						} while(truster == trustee);
						
						mWoT.beginTrustListImport();
						mWoT.setTrustWithoutCommit(truster, trustee, getRandomTrustValue(), getRandomLatinString(Trust.MAX_TRUST_COMMENT_LENGTH));
						mWoT.finishTrustListImport();
						Persistent.checkedCommit(mWoT.getDatabase(), this);
						
						final String trustID = new TrustID(truster, trustee).toString();
						if(!randomizer.allTrusts.contains(trustID)) // We selected the truster/trustee randomly so a value may have existed
							randomizer.allTrusts.add(trustID); 
					}
					break;
				case 12:
					{
						mWoT.beginTrustListImport();
						final Trust trust = mWoT.getTrust(randomizer.allTrusts.getRandom());
						mWoT.removeTrustWithoutCommit(trust);
						mWoT.finishTrustListImport();
						Persistent.checkedCommit(mWoT.getDatabase(), this);
						
						randomizer.allTrusts.remove(trust.getID());
					}
					break;
				default:
					throw new RuntimeException("Please adapt mRandom.nextInt() above!");
			}
		}
	}
	
	protected HashSet<Identity> cloneAllIdentities() {
		final ObjectSet<Identity> identities = mWoT.getAllIdentities();
		final HashSet<Identity> clones = new HashSet<Identity>(identities.size() * 2);
		
		for(Identity identity : identities) {
			// We assertTrue upon the return value of HashSet.add() because it will return false if the identity was already in the HashSet:
			// Each identity should only exist once!
			assertTrue(clones.add(identity.clone()));
		}

		return clones;
	}
	
	protected HashSet<Trust> cloneAllTrusts() {
		final ObjectSet<Trust> trusts = mWoT.getAllTrusts();
		final HashSet<Trust> clones = new HashSet<Trust>(trusts.size() * 2);
		
		for(Trust trust : trusts) {
			// We assertTrue upon the return value of HashSet.add() because it will return false if the Trust was already in the HashSet:
			// Each Trust should only exist once!
			assertTrue(clones.add(trust.clone()));
		}

		return clones;
	}
	
	protected HashSet<Score> cloneAllScores() {
		final ObjectSet<Score> scores = mWoT.getAllScores();
		final HashSet<Score> clones = new HashSet<Score>(scores.size() * 2);
		
		for(Score score : scores) {
			// We assertTrue upon the return value of HashSet.add() because it will return false if the Score was already in the HashSet:
			// Each Score should only exist once!
			assertTrue(clones.add(score.clone()));
		}
		
		return clones;
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

