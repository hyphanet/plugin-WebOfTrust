/* This code is part of WoT, a plugin for Freenet. It is distributed
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

/**
 * {@link IdentityFileQueue} implementation which writes the files to disk instead of keeping them
 * in memory.<br><br>
 * 
 * Deduplicating queue: Only the latest edition of each file is returned; see
 * {@link IdentityFileQueue} for details.<br>
 * The order of files is not preserved.<br>
 */
public class IdentityFileDiskQueue implements IdentityFileQueue {

}
