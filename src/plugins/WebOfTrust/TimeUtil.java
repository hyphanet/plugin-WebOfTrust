/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.TimeZone;

/**
 * FIXME: Move to fred class TimeUtil when adding WoT to the plugins page
 */
public final class TimeUtil {
	/**
	 * @return Returns the passed date with the same year/months/day but with the time set to 00:00:00
	 */
	public static Date setTimeToZero(final Date date) {
		// We need to cut off the hour/minutes/seconds
		final GregorianCalendar calendar = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
		calendar.setTimeInMillis(date.getTime()); // We must not use setTime(date) in case the date is not UTC.
		calendar.set(calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH), 0, 0, 0);
		return calendar.getTime();
	}
}
