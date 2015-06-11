/* This code is part of WoT, a plugin for Freenet. It is distributed
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

/**
 * The {@link IdentityFetcher} fetches the data files of all known identities and stores them
 * in the {@link IdentityFileQueue}. The job of this processor is to take the files from the queue,
 * and import them into the WOT database using the {@link XMLTransformer}.<br><br>
 * 
 * Notice: The implementation is single-threaded and processes the files sequentially one-by-one.
 * It is not parallelized since the core WOT {@link Score} computation algorithm is not.
 */
public class IdentityFileProcessor {

}
