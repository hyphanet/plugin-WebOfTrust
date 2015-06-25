/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

/**
 * Test for both implementations of {@link IdentityFileQueue}: {@link IdentityFileDiskQueue} and
 * {@link IdentityFileMemoryQueue}.<br><br>
 * 
 * They are being tested against each other by feeding the same set of identity files to them, and
 * then checking whether the resulting WOT database is equal.<br><br>
 * 
 * FIXME: General ideas for tests:<br>
 * - Test whether deduplication does not over-deduplicate stuff which it shouldn't deduplicate. Also
 *   test whether it does deduplicate stuff which it should.
 */
class IdentityFileQueueTest extends AbstractJUnit4BaseTest {

}
