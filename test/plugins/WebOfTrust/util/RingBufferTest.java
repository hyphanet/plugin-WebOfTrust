package plugins.WebOfTrust.util;

import static java.util.Arrays.asList;
import static org.junit.Assert.*;
import static plugins.WebOfTrust.util.AssertUtil.assertDidNotThrow;
import static plugins.WebOfTrust.util.AssertUtil.assertDidThrow;
import static plugins.WebOfTrust.util.MathUtil.integer;

import java.util.Iterator;
import java.util.NoSuchElementException;
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
		RingBuffer<Integer> b = new RingBuffer<>(3);
		b.addAll(asList(10, -20, 30));
		assertEquals(3, b.size());
		b.clear();
		assertEquals(0, b.size());
		assertEquals(3, b.sizeLimit());
	}

	@Test public void testPeek() {
		RingBuffer<Integer> b = new RingBuffer<>(2);
		b.addFirst(10);
		b.addLast(20);
		assertEquals(2, b.size());
		assertEquals(2, b.sizeLimit());
		assertEquals(integer(10), b.peekFirst());
		assertEquals(integer(20), b.peekLast());
		// Test if elements didn't get removed instead of just peeking
		assertEquals(integer(10), b.peekFirst());
		assertEquals(integer(20), b.peekLast());
		assertEquals(2, b.size());
		assertEquals(2, b.sizeLimit());
	}

	@Test public void testSize_sizeLimit() {
		RingBuffer<Integer> b = new RingBuffer<>(4);
		assertEquals(0, b.size());
		assertEquals(4, b.sizeLimit());
		b.addFirst(10);
		assertEquals(1, b.size());
		assertEquals(4, b.sizeLimit());
		b.addLast(-20);
		assertEquals(2, b.size());
		assertEquals(4, b.sizeLimit());
		b.addAll(asList(30));
		assertEquals(3, b.size());
		assertEquals(4, b.sizeLimit());
		b.clear();
		assertEquals(0, b.size());
		assertEquals(4, b.sizeLimit());
	}

	@Test public void testClone() throws CloneNotSupportedException {
		class CloneableClass implements Cloneable {
			public CloneableClass clone() throws CloneNotSupportedException {
				return (CloneableClass)super.clone();
			}
		}
		
		RingBuffer<CloneableClass> b1 = new RingBuffer<>(2);
		CloneableClass x = new CloneableClass();
		CloneableClass y = new CloneableClass();
		assertNotSame(x, x.clone());
		b1.addFirst(x);
		b1.addLast(y);
		
		RingBuffer<CloneableClass> b2 = b1.clone();
		assertNotSame(b1, b2);
		assertSame(b1.peekFirst(), b2.peekFirst());
		assertSame(b1.peekLast(),  b2.peekLast());
		assertEquals(b1.size(),      b2.size());
		assertEquals(b1.sizeLimit(), b2.sizeLimit());
		b1.addLast(new CloneableClass());
		assertEquals(2, b2.size());
		assertNotSame(b1.peekFirst(), b2.peekFirst());
		assertNotSame(b1.peekLast(),  b2.peekLast());
		assertSame(x, b2.peekFirst());
		assertSame(y, b2.peekLast());
	}

	@Test public void testIterator() {
		// TODO: Java 8: Use lambda expression instead of anonymous classes.
		
		RingBuffer<Integer> b = new RingBuffer<>(3);
		
		final Iterator<Integer> i1 = b.iterator();
		assertFalse(i1.hasNext());
		assertDidThrow(new Callable<Integer>() { @Override public Integer call() throws Exception {
			return i1.next();
		}}, NoSuchElementException.class);
		
		b.addFirst(10);
		
		final Iterator<Integer> i2 = b.iterator();
		assertTrue(i2.hasNext());
		assertEquals(integer(10), i2.next());
		assertFalse(i2.hasNext());
		assertDidThrow(new Callable<Integer>() { @Override public Integer call() throws Exception {
			return i2.next();
		}}, NoSuchElementException.class);
		
		b.addLast(-20);
		b.addLast(30);
		
		final Iterator<Integer> i3 = b.iterator();
		assertTrue(i3.hasNext()); assertEquals(integer( 10), i3.next());
		assertTrue(i3.hasNext()); assertEquals(integer(-20), i3.next());
		assertTrue(i3.hasNext()); assertEquals(integer( 30), i3.next());
		assertFalse(i3.hasNext());
		assertDidThrow(new Callable<Integer>() { @Override public Integer call() throws Exception {
			return i3.next();
		}}, NoSuchElementException.class);
		
		b.clear();
		
		final Iterator<Integer> i4 = b.iterator();
		assertFalse(i4.hasNext());
		assertDidThrow(new Callable<Integer>() { @Override public Integer call() throws Exception {
			return i4.next();
		}}, NoSuchElementException.class);
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
