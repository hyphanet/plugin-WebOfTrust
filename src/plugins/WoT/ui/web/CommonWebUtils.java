/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WoT.ui.web;

import plugins.WoT.WoT;


/**
 * Common methods used by more than one class of the web interface. 
 * 
 * @author bback
 */
public class CommonWebUtils {
    
    /**
     * Format a time delta string.
     *  
     * @param delta the time delta
     * @return String containing the localized formatted delta value 
     */
    public static String formatTimeDelta(long delta) {
        long days = delta / (1000L * 60L * 60L * 24L);
        long hours = (delta % (1000L * 60L * 60L * 24L)) / (1000L * 60L * 60L);
        long minutes = ((delta % (1000L * 60L * 60L * 24L)) % (1000L * 60L * 60L)) / (1000L * 60L);
        
        if(days > 3 || (days > 0 && hours == 0))
            return WoT.getBaseL10n().getString("CommonWebUtils.daysAgo", "days", Long.toString(days));
        else if(days > 0)
            return WoT.getBaseL10n().getString("CommonWebUtils.daysHoursAgo", new String[] {"days", "hours"}, new String[] {Long.toString(days), Long.toString(hours)});
        else if(hours > 0)
            return WoT.getBaseL10n().getString("CommonWebUtils.hoursAgo", "hours", Long.toString(hours));
        else
            return WoT.getBaseL10n().getString("CommonWebUtils.minutesAgo", "minutes", Long.toString(minutes));
    }
}
