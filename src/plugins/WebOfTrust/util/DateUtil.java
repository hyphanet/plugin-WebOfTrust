/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.util;

import java.text.SimpleDateFormat;
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
	 * NOT thread-safe according to JavaDoc of SimpleDateFormat, you must synchronize on
	 * DateUtil.class when using this!
	 * TODO: Code quality: Apache Java Commons: Use FastDateFormat, StackExchange says it is
	 * thread safe */
	private static final SimpleDateFormat dateFormatShort = new SimpleDateFormat("yyyyMMdd");

	static {
		synchronized(DateUtil.class) {
			dateFormatShort.setTimeZone(UTC);
		}
	}

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
		calendar.add(Calendar.HOUR_OF_DAY, 12);
		calendar.set(Calendar.HOUR_OF_DAY, 0);
		calendar.set(Calendar.MINUTE, 0);
		calendar.set(Calendar.SECOND, 0);
		calendar.set(Calendar.MILLISECOND, 0);
		Date result = calendar.getTime();
		
		return result;
	}
	
	/** Does NOT round! Use {@link #roundToNearestDay(Date)} first if you want that. */
	public static final String toStringYYYYMMDD(Date utcDate) {
		synchronized(DateUtil.class) {
			return dateFormatShort.format(utcDate);
		}
	}

}
