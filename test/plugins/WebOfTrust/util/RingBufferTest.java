package plugins.WebOfTrust.util;

import static java.util.Arrays.asList;
import static org.junit.Assert.*;
import static plugins.WebOfTrust.util.AssertUtil.assertDidNotThrow;
import static plugins.WebOfTrust.util.AssertUtil.assertDidThrow;
import static plugins.WebOfTrust.util.MathUtil.integer;

import java.util.Iterator;
import java.util.concurrent.Callable;

import org.junit.BeforeClass;
import org.junit.Test;

/** Tests {@link RingBuffer}. */
public final class RingBufferTest {

	@BeforeClass public static void beforeClass() {
		// The functions we use of AssertUtil use Java assertions, not JUnit assertions, so check if
		// they are enabled.
		// TODO: Code quality: Provide copies of these functions in a class JUnitUtil to fix this.
		assertTrue(AssertUtil.class.desiredAssertionStatus());
	}

	@Test public void testConstructor() {
		assertEquals(123, new RingBuffer<Integer>(123).sizeLimit());
		
		// Size limits less than 1 should not be accepted, test that...
		// TODO: Java 8: Use lambda expressions instead of anonymous classes.
		
		assertDidThrow(new Callable<Object>() {
		@Override public Object call() throws Exception {
			return new RingBuffer<Integer>(-1);
		}}, IllegalArgumentException.class);
		
		assertDidThrow(new Callable<Object>() {
		@Override public Object call() throws Exception {
			return new RingBuffer<Integer>(0);
		}}, IllegalArgumentException.class);
		
		assertDidNotThrow(new Runnable() {
		@Override public void run() {
			 new RingBuffer<Integer>(1);
		}});
	}

	@Test public void testAddFirst() {
		RingBuffer<Integer> b = new RingBuffer<>(2);
		
		assertEquals(0, b.size());
		b.addFirst(10);
		assertEquals(1, b.size());
		assertEquals(integer(10), b.peekFirst());
		
		b.addFirst(20);
		assertEquals(2, b.size());
		assertEquals(integer(20), b.peekFirst());
		assertEquals(integer(10), b.peekLast());
		
		b.addFirst(30);
		assertEquals(2, b.size());
		assertEquals(integer(30), b.peekFirst());
		assertEquals(integer(20), b.peekLast());

		b.addFirst(-40);
		assertEquals(2, b.size());
		assertEquals(integer(-40), b.peekFirst());
		assertEquals(integer(30), b.peekLast());
	}

	@Test public void testAddLast() {
		RingBuffer<Integer> b = new RingBuffer<>(2);
		
		assertEquals(0, b.size());
		b.addLast(10);
		assertEquals(1, b.size());
		assertEquals(integer(10), b.peekLast());
		
		b.addLast(20);
		assertEquals(2, b.size());
		assertEquals(integer(10), b.peekFirst());
		assertEquals(integer(20), b.peekLast());
		
		b.addLast(30);
		assertEquals(2, b.size());
		assertEquals(integer(20), b.peekFirst());
		assertEquals(integer(30), b.peekLast());

		b.addLast(-40);
		assertEquals(2, b.size());
		assertEquals(integer(30), b.peekFirst());
		assertEquals(integer(-40), b.peekLast());
	}

	@Test public void testAddAll() {
		RingBuffer<Integer> b = new RingBuffer<>(3);

		b.addAll(asList(10, -20, 30, -40, 50));
		assertEquals(3, b.size());
		Iterator<Integer> i = b.iterator();
		assertEquals(integer( 30), i.next());
		assertEquals(integer(-40), i.next());
		assertEquals(integer( 50), i.next());
		assertFalse(i.hasNext());
		
		b.addAll(asList(-20, 10));
		assertEquals(3, b.size());
		i = b.iterator();
		assertEquals(integer( 50), i.next());
		assertEquals(integer(-20), i.next());
		assertEquals(integer( 10), i.next());
		assertFalse(i.hasNext());
	}

	@Test public void testClear() {
		RingBuffer<Integer> q = new RingBuffer<>(3);
		q.addAll(asList(10, -20, 30));
		assertEquals(3, q.size());
		q.clear();
		assertEquals(0, q.size());
		assertEquals(3, q.sizeLimit());
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
