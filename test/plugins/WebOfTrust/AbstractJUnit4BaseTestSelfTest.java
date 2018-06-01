/* This code is part of WoT, a plugin for Freenet. It is distributed
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.net.MalformedURLException;

import org.junit.Before;
import org.junit.Test;

import plugins.WebOfTrust.exceptions.InvalidParameterException;

/**
 * Tests class {@link AbstractJUnit4BaseTest}.
 * That class contains the foundation for many other unit tests, so it is a case where it makes
 * sense to have unit tests for unit test code.
 * 
 * TODO: Code quality: It might make sense to move some of the function of
 * {@link AbstractJUnit4BaseTest} to class {@link WebOfTrust} - namely the functions for generating
 * random {@link Identity} / {@link Trust} / {@link Score} objects. That would mean that many of
 * the test functions in this class here could become tests for regular code instead of being
 * tests for other unit test code. */
public class AbstractJUnit4BaseTestSelfTest extends AbstractJUnit4BaseTest {

    private WebOfTrust mWebOfTrust;

    @Before public void setUp() {
        mWebOfTrust = constructEmptyWebOfTrust();
    }

    /** @see #setupUncaughtExceptionHandler() */
    @Test public void testSetupUncaughtExceptionHandler() throws InterruptedException {
        Thread t = new Thread(new Runnable() {@Override public void run() {
            throw new RuntimeException();
        }});
        t.start();
        t.join();
        assertNotEquals(null, uncaughtException.get());
        // Set back to null so testUncaughtExceptions() does not fail
        uncaughtException.set(null);
    }

    /** Tests {@link #addRandomIdentities(int, int)}. */
    @Test public void testAddRandomIdentitiesIntInt()
            throws MalformedURLException, InvalidParameterException {
        
        assertEquals(0, getWebOfTrust().getAllIdentities().size());
        assertEquals(2 + 3, addRandomIdentities(2, 3).size());
        assertEquals(2, getWebOfTrust().getAllOwnIdentities().size());
        assertEquals(3, getWebOfTrust().getAllNonOwnIdentities().size());
    }

    @Override protected WebOfTrust getWebOfTrust() {
        return mWebOfTrust;
    }


	/**
	 * Tests what we assume about {@link Class#isPrimitive()} assume at {@link #testClone(Class,
	 * Object, Object)} because its documentation is a bit ambiguous:
	 * isPrimitive() should only return true for types such as int, not for their wrapper classes
	 * such as Integer. */
	@Test
	public void testIsPrimitive() {
		// Instead of accessing e.g. boolean.class directly wrap the primitives in classes to
		// ensure we use the same codepath through class.getDeclaredFields() as testClone() does.
		@SuppressWarnings("unused") class Primitives {
			boolean a; byte b; char c;      short d; int f;     long g; float h; double i; };
		@SuppressWarnings("unused") class NonPrimitives {
			Boolean a; Byte b; Character c; Short d; Integer f; Long g; Float h; Double i; };
		
		for(Field f : Primitives.class.getDeclaredFields()) {
			// Local classes cannot be static so ignore the pointer to the parent object.
			if(f.getType() == AbstractJUnit4BaseTestSelfTest.class)
				continue;
			
			assertTrue(f.getType().isPrimitive());
		}
		for(Field f : NonPrimitives.class.getDeclaredFields()) {
			if(f.getType() == AbstractJUnit4BaseTestSelfTest.class)
				continue;
			
			assertFalse(f.getType().isPrimitive());
		}
	}

}
