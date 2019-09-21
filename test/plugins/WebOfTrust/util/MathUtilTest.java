package plugins.WebOfTrust.util;

import static org.junit.Assert.*;
import static plugins.WebOfTrust.util.MathUtil.equalsApprox;

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
		
		assertTrue(equalsApprox(a,   a, 99.999d));
		assertTrue(equalsApprox(0d, 0d, 99.999d));
	}

	@Test public void testInteger() {
		fail("Not yet implemented");
	}

}
