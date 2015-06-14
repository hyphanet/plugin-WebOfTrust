/* This code is part of WoT, a plugin for Freenet. It is distributed
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import java.io.File;

import plugins.WebOfTrust.Identity.IdentityID;
import freenet.keys.FreenetURI;

/**
 * {@link IdentityFileQueue} implementation which writes the files to disk instead of keeping them
 * in memory.<br><br>
 * 
 * Deduplicating queue: Only the latest edition of each file is returned; see
 * {@link IdentityFileQueue} for details.<br>
 * The order of files is not preserved.<br>
 */
public class IdentityFileDiskQueue implements IdentityFileQueue {
	/** Subdirectory of WOT data directory where we put our data dirs. */
	private final File mDataDir;

	/** {@link #add(IdentityFileStream)} puts files to this subdir of {@link #mDataDir}. */
	private final File mQueueDir;

	/** {@link #poll()} puts files to this subdir of {@link #mDataDir}. */
	private final File mProcessingDir;

	/**
	 * When the stream of a file returned by {@link #poll()} is closed, the closing function of
	 * the stream will move the file to this subdir of {@link #mDataDir}. */
	private final File mFinishedDir;
	

	public IdentityFileDiskQueue(WebOfTrust wot) {
		mDataDir = new File(wot.getUserDataDirectory(), "IdentityFileQueue");
		mQueueDir = new File(mDataDir, "Queued");
		mProcessingDir = new File(mDataDir, "Processing");
		mFinishedDir = new File(mDataDir, "Finished");
		mQueueDir.mkdir();
		mProcessingDir.mkdir();
		mFinishedDir.mkdir();
	}

	private File getQueueFilename(FreenetURI identityFileURI) {
		// We want to deduplicate editions of files for the same identity.
		// This can be done by causing filenames to always collide for the same identity:
		// An existing file of an old edition will be overwritten then.
		// We cause the collissions by using the ID of the identity as the only variable component
		// of the filename.
		IdentityID id = IdentityID.constructAndValidateFromURI(identityFileURI);
		return new File(mQueueDir, id + ".wot-identity");
	}
}
