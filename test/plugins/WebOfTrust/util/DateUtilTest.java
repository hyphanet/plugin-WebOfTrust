/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.util;

import static java.lang.Math.abs;
import static java.util.Calendar.DAY_OF_MONTH;
import static java.util.Calendar.HOUR_OF_DAY;
import static java.util.Calendar.MILLISECOND;
import static java.util.Calendar.MINUTE;
import static java.util.Calendar.MONTH;
import static java.util.Calendar.SECOND;
import static java.util.Calendar.YEAR;
import static org.junit.Assert.*;

import java.util.Date;
import java.util.GregorianCalendar;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import plugins.WebOfTrust.AbstractJUnit4BaseTest;
import plugins.WebOfTrust.WebOfTrust;
import freenet.support.TimeUtil;

/** Tests {@link DateUtil}.  */
public final class DateUtilTest extends AbstractJUnit4BaseTest {

	/** Tests {@link DateUtil#roundToNearestDay(Date)} */
	@Test public final void testRoundToNearestDay() {
		// Step 1: Test whether rounding happens when it should.
		
		GregorianCalendar c = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
		// Use 2014 because it neither is a leap year nor has a leap second
		c.set(2014, 12 - 1 /* 0-based! */, 31, 23, 59, 59);
		c.set(MILLISECOND, 999);
		Date notRounded = c.getTime();
		Date notRoundedBackup = (Date) notRounded.clone();
		
		Date rounded = DateUtil.roundToNearestDay(notRounded);
		
		// Date is mutable so we must check for re-use / bogus modifications of the original
		assertNotSame(notRounded, rounded);
		assertEquals(notRounded, notRoundedBackup);
		
		c.setTime(rounded);
		assertEquals(2015,  c.get(YEAR));
		assertEquals(1 - 1, c.get(MONTH));
		assertEquals(1,     c.get(DAY_OF_MONTH));
		assertEquals(0,     c.get(HOUR_OF_DAY));
		assertEquals(0,     c.get(MINUTE));
		assertEquals(0,     c.get(SECOND));
		assertEquals(0,     c.get(MILLISECOND));
		
		// Step 2: Test whether rounding does not happen when it should not.
		
		c = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
		c.set(2014, 12  - 1 /* 0-based! */, 31, 11, 59, 59);
		c.set(MILLISECOND, 999);
		notRounded = c.getTime();
		notRoundedBackup = (Date) notRounded.clone();
				
		rounded = DateUtil.roundToNearestDay(notRounded);
		
		assertNotSame(notRounded, rounded);
		assertEquals(notRounded, notRoundedBackup);
		
		c.setTime(rounded);
		assertEquals(2014,   c.get(YEAR));
		assertEquals(12 - 1, c.get(MONTH));
		assertEquals(31,     c.get(DAY_OF_MONTH));
		assertEquals(0,      c.get(HOUR_OF_DAY));
		assertEquals(0,      c.get(MINUTE));
		assertEquals(0,      c.get(SECOND));
		assertEquals(0,      c.get(MILLISECOND));
		
		// Step 3: Test with random Date.
		
		Date date = new Date(abs(mRandom.nextLong()));
		Date result = DateUtil.roundToNearestDay(date);
		
		assertNotSame(date, result);
		assertTrue(result.after(TimeUtil.setTimeToZero(date)));
		assertTrue(result.before(
			TimeUtil.setTimeToZero(new Date(date.getTime() + TimeUnit.HOURS.toMillis(12) + 1))));
	}

	@Override protected final WebOfTrust getWebOfTrust() {
		return null;
	}

}
