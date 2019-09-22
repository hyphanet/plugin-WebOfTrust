package plugins.WebOfTrust.util;

import static org.junit.Assert.*;
import static plugins.WebOfTrust.util.AssertUtil.assertDidThrow;
import static plugins.WebOfTrust.util.MathUtil.equalsApprox;

import java.util.concurrent.Callable;

import org.junit.Test;

/** Tests {@link MathUtil}. */
public final class MathUtilTest {

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
		
		// TODO: Java 8: Use lambda expressions instead of anonymous classes.
		
		assertDidThrow(new Callable<Boolean>() { @Override public Boolean call() throws Exception {
			return equalsApprox(1d, Double.NaN, 90);
		}}, ArithmeticException.class);
		assertDidThrow(new Callable<Boolean>() { @Override public Boolean call() throws Exception {
			return equalsApprox(Double.NaN, 1d, 90);
		}}, ArithmeticException.class);
		
		assertDidThrow(new Callable<Boolean>() { @Override public Boolean call() throws Exception {
			return equalsApprox(1d, Double.POSITIVE_INFINITY, 90);
		}}, ArithmeticException.class);
		assertDidThrow(new Callable<Boolean>() { @Override public Boolean call() throws Exception {
			return equalsApprox(Double.POSITIVE_INFINITY, 1d, 90);
		}}, ArithmeticException.class);
	}

	@Test public void testInteger() {
		fail("Not yet implemented");
	}

}
