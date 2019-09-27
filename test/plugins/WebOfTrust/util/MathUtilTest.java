package plugins.WebOfTrust.util;

import static org.junit.Assert.*;
import static plugins.WebOfTrust.util.AssertUtil.assertDidThrow;
import static plugins.WebOfTrust.util.MathUtil.equalsApprox;

import java.util.concurrent.Callable;

import org.junit.BeforeClass;
import org.junit.Test;

/** Tests {@link MathUtil}. */
public final class MathUtilTest {

	@BeforeClass public static void beforeClass() {
		// The functions we use of AssertUtil use Java assertions, not JUnit assertions, so check if
		// they are enabled.
		// TODO: Code quality: Provide copies of these functions in a class JUnitUtil to fix this.
		// A copy of this TODO exists at class RingBufferTest.
		assertTrue(AssertUtil.class.desiredAssertionStatus());
	}

	@Test public void testEqualsApprox() {
		final double a = 0.87654321d * Double.MAX_VALUE;
		final double b = a*1.10d; // Ten percent deviation
		
		// Basic equality
		
		assertTrue( equalsApprox(a, b, 89d));
		assertTrue( equalsApprox(b, a, 89d));
		assertFalse(equalsApprox(a, b, 91d));
		assertFalse(equalsApprox(b, a, 91d));
		
		// Special cases
		
		assertTrue( equalsApprox(a,   a, 99.999d));
		assertFalse(equalsApprox(a,  -a, 99.999d));
		assertTrue( equalsApprox(0d, 0d, 99.999d));
		
		// The JavaDoc of equalsApprox() promises that it throws ArithmeticException when any of
		// these Double.* values is passed to it for comparison.
		double[] specialValues = {
			1d, /* Not special, we use it to test if one special value as input is enough */
			Double.NaN,
			Double.POSITIVE_INFINITY,
			Double.NEGATIVE_INFINITY,
			Double.MAX_VALUE,
			Double.MIN_VALUE,
			// MIN_NORMAL is a lot larger than MIN_VALUE. Normal double values fulfill some special
			// purpose which I've briefly read up on and which doesn't sound like it is relevant
			// here, so equalsApprox() accepts non-normal values currently and we thus don't test
			// with values <= MIN_NORMAL here.
			/* Double.MIN_NORMAL */ };
		
		// Test each pair of a special value and non-special value 1d being passed to either of the
		// both input parameters of equalsApprox().
		// Also test special values against another to catch the potential bug of equalsApprox()
		// doing computations with the input before checking for the values being special which
		// might lead to the output not being special anymore and thereby not hitting the check for
		// if it is special.
		for(int i = 0; i < specialValues.length; ++i) {
			for(int j = 0; j < specialValues.length; ++j) {
				if(i==0 && j==0)
					continue;
				
				final double x = specialValues[i];
				final double y = specialValues[j];
				// TODO: Java 8: Use lambda expression instead of anonymous class.
				assertDidThrow(new Callable<Boolean>() { @Override public Boolean call() {
					return equalsApprox(x, y, 90);
				}}, ArithmeticException.class);
			}
		}
	}

	@Test public void testInteger() {
		assertEquals(Integer.valueOf(123), MathUtil.integer(123));
	}

}
