/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import org.junit.Before;

import freenet.pluginmanager.PluginRespirator;

/**
 * A base class for WOT unit tests.<br>
 * As opposed to regular WOT unit tests based upon {@link DatabaseBasedTest}, this test runs the
 * unit tests inside a full Freenet node:<br>
 * WOT is loaded as a regular plugin instead of executing the tests directly without Freenet.<br>
 * <br>
 * 
 * This has the advantage of allowing more complex tests:<br>
 * - The {@link PluginRespirator} is available<br>
 * - FCP can be used.<br><br>
 * 
 * The price is that it is much more heavy to initialize and thus has a higher execution time.<br>
 * Thus, please only use it as a base class if what {@link DatabaseBasedTest} provides is not
 * sufficient.<br>
 * 
 * @author xor (xor@freenetproject.org
 */
abstract class AbstractFullNodeTest {
    
    @Before void setUp() {
        // FIXME: Use NodeStarter.createTestNode() to create a node and load WOT.
    }

}
