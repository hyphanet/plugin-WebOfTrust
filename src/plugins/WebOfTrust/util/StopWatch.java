/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.util;

import java.util.concurrent.TimeUnit;

import freenet.support.TimeUtil;

/** Utility class for measuring execution time of code. */
public final class StopWatch {

	private final long mStartTime = System.nanoTime();

	/**
	 * We use Long so we can flag as "empty" using a value of null.
	 * We do not use long and "-1" instead of null because {@link System#nanoTime()} does not
	 * specify whether the return value is always positive. */
	private Long mStopTime = null;


	public void stop() {
		mStopTime = System.nanoTime();
	}

	public long getNanos() {
		if(mStopTime == null)
			stop();
		
		return mStopTime - mStartTime;
	}

	public String toString() {
		if(mStopTime == null)
			stop();
		
		return TimeUtil.formatTime(TimeUnit.NANOSECONDS.toMillis(mStopTime - mStartTime));
	}

}
