/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.util;

/**
 * Utilities for amending the features of  the Java assert statement.
 */
public final class AssertUtil {

    /**
     * If Java assertions are enabled, execute the given {@link Runnable}, and assert(false) if it
     * throws any {@link Throwable}.<br>
     * Can be used for asserting upon stuff which does not return false upon error but throws:
     * A regular Java assert could only consume boolean-returning functions.<br><br>
     * 
     * The {@link Throwable} will be passed as message to the assert, it will not be thrown.<br>
     */
    public static final void assertDidNotThrow(final Runnable r) {
        boolean execute = false;
        
        assert(execute = true);
        
        if(execute) {
            try {
                r.run();
            } catch(Throwable t) {
                assert(false) : t;
            }
        }
    }

}
