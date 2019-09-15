package plugins.WebOfTrust.util;

import static org.junit.Assert.*;
import static plugins.WebOfTrust.util.CollectionUtil.array;

import org.junit.Test;

public class CollectionUtilTest {

	@Test public void testArray() {
		Integer[] a = array(10, 20, 30);
		assertEquals(3, a.length);
		assertEquals(Integer.valueOf(10), a[0]);
		assertEquals(Integer.valueOf(20), a[1]);
		assertEquals(Integer.valueOf(30), a[2]);
		
		try {
			array();
			// See the JavaDoc of array() for why this is necessary.
			fail("array() must throw IllegalArgumentException for empty varargs");
		} catch(IllegalArgumentException e) {}
	}

	@Test public void testArrayList() {
		fail("Not yet implemented");
	}

	@Test public void testIgnoreNulls() {
		fail("Not yet implemented");
	}

}
