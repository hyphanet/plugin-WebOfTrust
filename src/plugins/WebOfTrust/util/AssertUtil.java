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
     * 
     * TODO: Code quality: Java 8: Change to use lambda expressions or function pointers + varargs
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

    /**
     * If Java assertions are enabled, executes the given {@link Runnable} and asserts that it
     * throws an exception of the given type.
     * Can be used as a workaround for the fact that Java assertions cannot handle exceptions.
     * 
     * If the thrown type is wrong, the {@link Throwable} will be passed as message to the assert.
     * 
     * TODO: Code quality: Java 8: Change to use lambda expressions or function pointers + varargs
     */
    public static final void assertDidThrow(Runnable r, Class<? extends Throwable> type) {
        boolean execute = false;
        
        assert(execute = true);
        
        if(execute) {
            try {
                r.run();
                assert(false);
            } catch(Throwable t) {
                assert(type.isInstance(t)) : t;
            }
        }
    }

}
