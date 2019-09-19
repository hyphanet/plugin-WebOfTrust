package plugins.WebOfTrust.util;

import static org.junit.Assert.*;
import static plugins.WebOfTrust.util.AssertUtil.assertDidNotThrow;
import static plugins.WebOfTrust.util.AssertUtil.assertDidThrow;
import static plugins.WebOfTrust.util.MathUtil.integer;

import java.util.concurrent.Callable;

import org.junit.BeforeClass;
import org.junit.Test;

/** Tests {@link LimitedArrayDeque}. */
public final class LimitedArrayDequeTest {

	@BeforeClass public static void beforeClass() {
		// The functions we use of AssertUtil use Java assertions, not JUnit assertions, so check if
		// they are enabled.
		// TODO: Code quality: Provide copies of these functions in a class JUnitUtil to fix this.
		assertTrue(AssertUtil.class.desiredAssertionStatus());
	}

	@Test public void testConstructor() {
		assertEquals(123, new LimitedArrayDeque<Integer>(123).sizeLimit());
		
		// Size limits less than 1 should not be accepted, test that...
		// TODO: Java 8: Use lambda expressions instead of anonymous classes.
		
		assertDidThrow(new Callable<Object>() {
		@Override public Object call() throws Exception {
			return new LimitedArrayDeque<Integer>(-1);
		}}, IllegalArgumentException.class);
		
		assertDidThrow(new Callable<Object>() {
		@Override public Object call() throws Exception {
			return new LimitedArrayDeque<Integer>(0);
		}}, IllegalArgumentException.class);
		
		assertDidNotThrow(new Runnable() {
		@Override public void run() {
			 new LimitedArrayDeque<Integer>(1);
		}});
	}

	@Test public void testAddFirst() {
		LimitedArrayDeque<Integer> q = new LimitedArrayDeque<>(2);
		
		assertEquals(0, q.size());
		q.addFirst(10);
		assertEquals(1, q.size());
		assertEquals(integer(10), q.peekFirst());
		
		q.addFirst(20);
		assertEquals(2, q.size());
		assertEquals(integer(20), q.peekFirst());
		assertEquals(integer(10), q.peekLast());
		
		q.addFirst(30);
		assertEquals(2, q.size());
		assertEquals(integer(30), q.peekFirst());
		assertEquals(integer(20), q.peekLast());

		q.addFirst(40);
		assertEquals(2, q.size());
		assertEquals(integer(40), q.peekFirst());
		assertEquals(integer(30), q.peekLast());
	}

	@Test public void testAddLast() {
		fail("Not yet implemented");
	}

	@Test public void testAddAll() {
		fail("Not yet implemented");
	}

	@Test public void testClear() {
		fail("Not yet implemented");
	}

	@Test public void testPeekFirst() {
		fail("Not yet implemented");
	}

	@Test public void testPeekLast() {
		fail("Not yet implemented");
	}

	@Test public void testSize() {
		fail("Not yet implemented");
	}

	@Test public void testSizeLimit() {
		fail("Not yet implemented");
	}

	@Test public void testClone() {
		fail("Not yet implemented");
	}

	@Test public void testIterator() {
		fail("Not yet implemented");
	}

	@Test public void testToArray() {
		fail("Not yet implemented");
	}

	@Test public void testHashCode() {
		fail("Not yet implemented");
	}

	@Test public void testEquals() {
		fail("Not yet implemented");
	}

}
