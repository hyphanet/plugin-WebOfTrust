/* This code is part of WoT, a plugin for Freenet. It is distributed
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import java.io.InputStream;

import freenet.keys.FreenetURI;


/**
 * The {@link IdentityFetcher} fetches the data files of all known identities, which the
 * {@link IdentityFileProcessor} then imports into the database. Since the network usually delivers
 * the files faster than they can be processed, this queue is responsible for storing the files in
 * between fetching and processing.<br><br>
 * 
 * The primary key of each data file is the combination of the {@link Identity} which published it,
 * and the {@link Identity#getEdition() edition} (= version) of the file (Notice: These are
 * implicitly identified by a single {@link FreenetURI} object in the function signatures).
 * For each such primary key, one file can exist in the queue.<br><br>
 * 
 * For the case where the queue simultaneously holds multiple files of different editions for the
 * same {@link Identity}, two behaviors are allowed for the implementation:<br>
 * 1. Deduplication of editions: For increased performance, the queue only passes the latest edition
 *    of each {@link Identity}'s file to the {@link IdentityFileProcessor} and silently drops older
 *    editions. This is possible because the {@link Score} computations of WOT do not require old
 *    versions of the input data.<br>
 * 2. FIFO: The implementation passes every edition of the file it has received to the
 *    {@link IdentityFileProcessor}, even if this means that outdated editions will be processed.
 *    The order of the output of the queue is the same as the one of the input.
 *    This is suitable for the debugging purpose of deterministic repetition of sessions.<br><br>
 *    
 * Notice: Implementations do not necessarily have to be disk-based, the word "file" is only used
 * to name the data set of an {@link Identity} in an easy to understand way.
 */
public interface IdentityFileQueue {
	public static final class IdentityFileStream {
		public final FreenetURI mURI;

		public final InputStream mXMLInputStream;

		/**
		 * @param uri
		 *     The {@link FreenetURI} from which the identity file was downloaded.<br>
		 *     ATTENTION: The edition in the URI must match the specific edition of the file.
		 * @param xmlInputStream
		 *     The unmodified XML data of the file.
		 */
		public IdentityFileStream(FreenetURI uri, InputStream xmlInputStream) {
			mURI = uri;
			mXMLInputStream = xmlInputStream;
		}
	}

	public void add(IdentityFileStream file);

	/**
	 * Removes and returns element from the queue. Returns null if the queue is empty.<br><br>
	 * 
	 * ATTENTION: Concurrent processing of multiple elements from the queue is not supported.
	 * This means that the {@link InputStream} of a returned {@link IdentityFileStream} must be
	 * closed before you call {@link #poll()} the next time.*/
	public IdentityFileStream poll();
}
