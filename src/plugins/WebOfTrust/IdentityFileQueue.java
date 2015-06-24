/* This code is part of WoT, a plugin for Freenet. It is distributed
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import java.io.FilterInputStream;
import java.io.InputStream;

import plugins.WebOfTrust.util.jobs.BackgroundJob;
import freenet.keys.FreenetURI;
import freenet.support.CurrentTimeUTC;


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
	/**
	 * TODO: Code quality: This should be a {@link FilterInputStream} to allow replacing
	 * 
	 *     if(identityFileStream != null)
	 *         Closer.close(identityFileStream.mXMLInputStream);
	 * 
	 * with
	 * 
	 *     Closer.close(identityFileStream);
	 *     
	 * in class IdentityFileQueue (variables are named differently there, search for Closer). */
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

	/**
	 * Registers a {@link BackgroundJob} whose {@link BackgroundJob#triggerExecution()} shall be
	 * called by the queue once an element is available for the job to {@link #poll()}.<br> */
	public void registerEventHandler(BackgroundJob handler);

	/**
	 * @return
	 *     An {@link IdentityFileQueueStatistics} object suitable for displaying statistics
	 *     in the UI.<br>
	 *     Its data is coherent, i.e. queried in an atomic fashion.<br>
	 *     The object is a clone, you may interfere with the contents of the member variables. */
	public IdentityFileQueueStatistics getStatistics();


	public static final class IdentityFileQueueStatistics implements Cloneable {
		/**
		 * Count of files which were passed to {@link #add(IdentityFileStream)}.<br>
		 * This <b>includes</b> files which:<br>
		 * - are not queued anymore (see {@link #mFinishedFiles}).<br>
		 * - are still queued (see {@link #mQueuedFiles}).<br>
		 * - were deleted due to deduplication (see {@link #mDeduplicatedFiles}).<br>
		 * - failed en-/dequeuing due to errors (see {@link #mFailedFiles}).<br><br>
		 * 
		 * The lost files are included to ensure that errors can be noticed by the user from
		 * statistics in the UI. */
		public int mTotalQueuedFiles = 0;
		
		/**
		 * Count of files which have been passed to {@link #add(IdentityFileStream)} but have not
		 * been dequeued by {@link #poll()} yet. */
		public int mQueuedFiles = 0;
		
		/**
		 * Count of files which are currently in processing.<br>
		 * A file is considered to be in processing when it has been dequeued using
		 * {@link IdentityFileQueue#poll()}, but the {@link InputStream} of the
		 * {@link IdentityFileStream} has not been closed yet.<br>
		 * This number should only ever be 0 or 1 as required by {@link IdentityFileQueue#poll()}.
		 * (Concurrent processing is not supported because the filenames could collide). */
		public int mProcessingFiles = 0;
		
		/**
		 * Count of files for which processing is finished.<br>
		 * A file is considered to be finished when it has been dequeued using
		 * {@link IdentityFileQueue#poll()} and the {@link InputStream} of the
		 * {@link IdentityFileStream} has been closed.<br>
		 * This number can be less than the files passed to {@link #add(IdentityFileStream)}:
		 * Files can be dropped due to deduplication (or errors). */
		public int mFinishedFiles = 0;
	
		/**
		 * See {@link IdentityFileQueue}.<br>
		 * Equal to <code>{@link #mTotalQueuedFiles} - {@link #mQueuedFiles}
		 * - {@link #mProcessingFiles} - {@link #mFinishedFiles}</code>.
		 */
		public int mDeduplicatedFiles = 0;

		/** Number of files which the queue has dropped due to internal errors. These are bugs. */
		public int mFailedFiles = 0;


		/** Value of {@link CurrentTimeUTC#getInMillis()} when this object was created. */
		public final long mStartupTimeMilliseconds = CurrentTimeUTC.getInMillis();


		@Override public IdentityFileQueueStatistics clone() {
			try {
				return (IdentityFileQueueStatistics)super.clone();
			} catch (CloneNotSupportedException e) {
				throw new RuntimeException(e);
			}
		}

		/**
		 * The average increase of {@link #mTotalQueuedFiles} per hour.<br>
		 * If no bugs are in {@link IdentityFetcher}, this is equal to the number of fetched files
		 * per hour. */
		public float getAverageQueuedFilesPerHour() {
			float uptimeSeconds
				= (float)(CurrentTimeUTC.getInMillis() - mStartupTimeMilliseconds)/1000;
			float uptimeHours = uptimeSeconds / (60*60);
			
			if(uptimeHours == 0) // prevent division by 0
				return 0;
			
			return (float)mTotalQueuedFiles / uptimeHours;		
		}

		/** FIXME: Also check whether the numbers match the directory contents on disk. */
		boolean checkConsistency() {
			return (
					(mTotalQueuedFiles >= 0)
					
				 && (mQueuedFiles >= 0)
				 
				 && (mProcessingFiles >= 0)
				 
				 && (mFinishedFiles >= 0)
				 
				 && (mDeduplicatedFiles >= 0)
				 
				 && (mFailedFiles == 0)
				
				 && (mQueuedFiles <= mTotalQueuedFiles)
				 
				 && (mProcessingFiles <= mTotalQueuedFiles)
				 
				 && (mFinishedFiles <= mTotalQueuedFiles)
				 
				 && (mDeduplicatedFiles <= mTotalQueuedFiles)
				 
				 && (mProcessingFiles <= 1)
					
				 && (mDeduplicatedFiles ==
						mTotalQueuedFiles - mQueuedFiles - mProcessingFiles - mFinishedFiles)
			 );
		}
	}
}
