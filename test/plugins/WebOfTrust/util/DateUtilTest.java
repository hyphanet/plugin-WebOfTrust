/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.util;

import static java.lang.Math.abs;
import static java.lang.String.format;
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
		
		// Step 3: Test with random Dates
		
		c = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
		
		for(int i = 0; i < 10000; ++i) {
			notRounded = new Date(abs(mRandom.nextLong()));
			rounded = DateUtil.roundToNearestDay(notRounded);
			
			assertNotSame(notRounded, rounded);
			
			c.setTime(TimeUtil.setTimeToZero(notRounded));
			Date lowerBoundary = c.getTime();
			c.add(DAY_OF_MONTH, 1);
			Date upperBoundary = c.getTime();
			
			long distanceFromLowerBoundary = notRounded.getTime() - lowerBoundary.getTime();
			long distanceFromUpperBoundary = upperBoundary.getTime() - notRounded.getTime();
			
			if(distanceFromLowerBoundary < distanceFromUpperBoundary)
				assertEquals(lowerBoundary, rounded);
			else
				assertEquals(upperBoundary, rounded);
		}
	}

	/** Tests {@link DateUtil#toStringYYYYMMDD(Date)}. */
	@Test public final void testToStringYYYYMMDD() {
		GregorianCalendar c = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
		for(int i = 0; i < 10000; ++i) { 
			Date date = new Date(abs(mRandom.nextLong()));
			c.setTime(date);
			String expected
				= format("%04d%02d%02d",
					c.get(YEAR),
					c.get(MONTH) + 1 /* 0-based! */,
					c.get(DAY_OF_MONTH));
			
			String actual = DateUtil.toStringYYYYMMDD(date);
			
			assertEquals(expected, actual);
		}
	}

	@Override protected final WebOfTrust getWebOfTrust() {
		return null;
	}

}
