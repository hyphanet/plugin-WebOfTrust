/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.util;

import static freenet.support.TimeUtil.setTimeToZero;
import static java.util.concurrent.TimeUnit.HOURS;

import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.TimeZone;

import freenet.support.TimeUtil;

/** Tools for use with {@link Date}s. */
public final class DateUtil {

	/** FIXME: Is this thread safe? */
	private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

	/**
	 * Rounds the given Date to the nearest day while setting all fields below the day such as
	 * minutes to zero.
	 * Returns a different Date object than the original one.
	 * Based on {@link TimeUtil#setTimeToZero(Date)}.
	 * 
	 * FIXME: Unit test!
	 * 
	 * TODO: Code quality: Apache Java Commons: Replace with function DateUtils.round() */
	public static final Date roundToNearestDay(Date date) {
		GregorianCalendar calendar = new GregorianCalendar(UTC);
		// We must not use setTime(date) in case the date is not UTC.
		calendar.setTimeInMillis(date.getTime());
		calendar.add(Calendar.HOUR, 12);
		calendar.set(Calendar.HOUR, 0);
		calendar.set(Calendar.MINUTE, 0);
		calendar.set(Calendar.SECOND, 0);
		calendar.set(Calendar.MILLISECOND, 0);
		Date result = calendar.getTime();
		
		assert(result != date) : "Date objects should not be re-used for code quality reasons";
		assert(result.after(setTimeToZero(date)));
		assert(result.before(setTimeToZero(new Date(date.getTime() + HOURS.toMillis(12) + 1))));
		
		return result;
	}

}
