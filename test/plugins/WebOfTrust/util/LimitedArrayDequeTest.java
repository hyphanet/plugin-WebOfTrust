package plugins.WebOfTrust.util;

import static org.junit.Assert.*;
import static plugins.WebOfTrust.util.AssertUtil.assertDidNotThrow;
import static plugins.WebOfTrust.util.AssertUtil.assertDidThrow;

import java.util.concurrent.Callable;

import org.junit.Test;

/** Tests {@link LimitedArrayDeque}. */
public final class LimitedArrayDequeTest {

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
		fail("Not yet implemented");
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
