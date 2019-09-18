package plugins.WebOfTrust.util;

import static org.junit.Assert.*;
import static plugins.WebOfTrust.util.CollectionUtil.array;
import static plugins.WebOfTrust.util.CollectionUtil.arrayList;
import static plugins.WebOfTrust.util.CollectionUtil.ignoreNulls;

import java.util.ArrayList;

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
		ArrayList<Integer> a = arrayList(10, 20, 30);
		assertEquals(3, a.size());
		assertEquals(Integer.valueOf(10), a.get(0));
		assertEquals(Integer.valueOf(20), a.get(1));
		assertEquals(Integer.valueOf(30), a.get(2));

		try {
			arrayList();
			// See the JavaDoc of array() for why this is necessary.
			fail("arrayList() must throw IllegalArgumentException for empty varargs");
		} catch(IllegalArgumentException e) {}
	}

	@Test public void testIgnoreNulls() {
		ArrayList<Integer> a = ignoreNulls(arrayList(null, 10, null, 20, null, 30, null));
		assertEquals(3, a.size());
		assertEquals(Integer.valueOf(10), a.get(0));
		assertEquals(Integer.valueOf(20), a.get(1));
		assertEquals(Integer.valueOf(30), a.get(2));
	}

}
