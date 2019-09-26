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
		Object x = new Object();
		Object y = new Object();
		assertEquals(new Pair<>(x, y).hashCode(), new Pair<>(x, y).hashCode());
		
		assertNotEquals(new Pair<>(10, 20).hashCode(), new Pair<>(10,  0).hashCode());
		assertNotEquals(new Pair<>(10, 20).hashCode(), new Pair<>(0 , 20).hashCode());
	}

	@Test public void testEquals() {
		fail("Not yet implemented");
	}

}
