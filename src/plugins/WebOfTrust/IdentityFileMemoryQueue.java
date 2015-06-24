/* This code is part of WoT, a plugin for Freenet. It is distributed
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import plugins.WebOfTrust.util.jobs.BackgroundJob;

/**
 * {@link IdentityFileQueue} implementation which stores the files in memory instead of on disk.<br>
 * <br>
 * 
 * This implementation aims at being used in unit tests only. Thus, in comparison to the
 * {@link IdentityFileDiskQueue} which WOT actually uses, it has the following disadvantages:<br>
 * - It doesn't deduplicate editions. See {@link IdentityFileQueue} for what that means.<br>
 * - It doesn't watch its memory usage and thus on fast Freenet nodes might cause OOM.<br>
 * - It doesn't contain as strong self-test assert()s as {@link IdentityFileDiskQueue}.<br><br>
 * 
 * TODO: Performance: Add configuration option to allow users to make their WOT use this instead
 * of the default {@link IdentityFileDiskQueue}. Be sure to add more self-tests before, and to
 * warn users that they only should use it if they have lots of memory.
 */
final class IdentityFileMemoryQueue implements IdentityFileQueue {

}
