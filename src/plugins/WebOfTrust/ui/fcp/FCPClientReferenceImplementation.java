/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.ui.fcp;

import plugins.WebOfTrust.Identity;
import plugins.WebOfTrust.Score;
import plugins.WebOfTrust.SubscriptionManager;
import plugins.WebOfTrust.Trust;
import freenet.support.Logger.LogLevel;

/**
 * This is a reference implementation of how a FCP client application should interact with WOT via event-notifications.
 * Feel free to copy-paste it.
 * 
 * The foundation of event-notifications is class {@link SubscriptionManager}, you should read the JavaDoc of it to understand them.
 * 
 * Notice that it has one additional debugging functionality which a client application obviously shouldn't have:
 * It is able to validate the data it has received via FCP against the actual data in the WOT database.
 * This serves as an online test for the event-notifications code:
 * - If WOT is run with logging set to {@link LogLevel#DEBUG}, the reference client will be run inside of WOT and connect to it.
 * - It will store ALL {@link Identity}, {@link Trust} and {@link Score} objects received via FCP.
 * - At shutdown, it will compare the final state of what it has received against whats stored in the regular WOT database
 * - If both datasets match, the test has succeeded.
 * FIXME: Document this in developer-documentation/Debugging.txt
 * 
 * NOTICE: This class was based upon class SubscriptionManagerFCPTest, which you can find in the unit test. Its not possible to link it in the
 * JavaDoc because the unit tests are not within the classpath. 
 * 
 * @see FCPInterface The "server" to which a FCP client connects.
 * @see SubscriptionManager The foundation of event-notifications and therefore the backend of all FCP traffic which this class does.
 * @author xor (xor@freenetproject.org)
 */
public class FCPClientReferenceImplementation {

}
