package plugins.WebOfTrust.util;

import static org.junit.Assert.*;
import static plugins.WebOfTrust.util.MathUtil.integer;

import org.junit.Test;

/** Tests {@link Pair}. */
public final class PairTest {

	@Test public void testConstructor1() {
		Pair<Integer, Integer> p = new Pair<>(20, 10);
		assertEquals(integer(20), p.x);
		assertEquals(integer(10), p.y);
	}

	@Test public void testConstructor2() {
		Pair<Integer, Integer> p = Pair.pair(20, 10);
		assertEquals(integer(20), p.x);
		assertEquals(integer(10), p.y);
	}

	@Test public void testHashCode() {
		fail("Not yet implemented");
	}

	@Test public void testEquals() {
		fail("Not yet implemented");
	}

}
