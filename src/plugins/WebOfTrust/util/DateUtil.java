/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.util;

import static java.lang.Math.max;

import java.util.Date;

import freenet.support.CurrentTimeUTC;

public final class DateUtil {

	/** Waits until CurrentTimeUTC.get().after(future) == true */
	public static void waitUntilCurrentTimeUTCIsAfter(Date future) throws InterruptedException {
		for(	long currentTime = CurrentTimeUTC.getInMillis();
				currentTime <= future.getTime();
				currentTime = CurrentTimeUTC.getInMillis()) {
			
			long waitTime = future.getTime() - currentTime;
			if(waitTime >= 0)
				Thread.sleep(max(1, waitTime));
		}
		
		assert(CurrentTimeUTC.get().after(future));
	}

}
