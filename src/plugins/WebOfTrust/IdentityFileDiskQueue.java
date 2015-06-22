/* This code is part of WoT, a plugin for Freenet. It is distributed
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

import plugins.WebOfTrust.Identity.IdentityID;
import plugins.WebOfTrust.util.jobs.BackgroundJob;
import freenet.keys.FreenetURI;
import freenet.support.Logger;

/**
 * {@link IdentityFileQueue} implementation which writes the files to disk instead of keeping them
 * in memory.<br><br>
 * 
 * Deduplicating queue: Only the latest edition of each file is returned; see
 * {@link IdentityFileQueue} for details.<br>
 * The order of files is not preserved.<br>
 */
final class IdentityFileDiskQueue implements IdentityFileQueue {
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
	
	/**
	 * Amount of old files in {@link #mFinishedDir}, i.e. files from a previous session.<br>
	 * We use this to ensure that filename index prefixes of new files do not collide.<br><br>
	 * 
	 * Notice: We do intentionally track this separately instead of initializing
	 * {@link IdentityFileQueueStatistics#mFinishedFiles} with this value: The other statistics are
	 * not persisted, so they would not be coherent with this value. */
	private int mOldFinishedFileCount;

	/** @see #getStatistics() */
	private final IdentityFileQueueStatistics mStatistics = new IdentityFileQueueStatistics();
	
	/** @see #registerEventHandler(BackgroundJob) */
	private BackgroundJob mEventHandler;


	public IdentityFileDiskQueue(WebOfTrust wot) {
		mDataDir = new File(wot.getUserDataDirectory(), "IdentityFileQueue");
		mQueueDir = new File(mDataDir, "Queued");
		mProcessingDir = new File(mDataDir, "Processing");
		mFinishedDir = new File(mDataDir, "Finished");
		
		if(!mDataDir.exists() && !mDataDir.mkdir())
			throw new RuntimeException("Cannot create " + mDataDir);
		
		if(!mQueueDir.exists() && !mQueueDir.mkdir())
			throw new RuntimeException("Cannot create " + mQueueDir);
		
		if(!mProcessingDir.exists() && !mProcessingDir.mkdir())
			throw new RuntimeException("Cannot create " + mProcessingDir);
		
		if(!mFinishedDir.exists() && !mFinishedDir.mkdir())
			throw new RuntimeException("Cannot create " + mFinishedDir);

		cleanDirectories();
	}

	/** Used at startup to ensure that the data directories are in a clean state */
	private synchronized void cleanDirectories() {
		Logger.normal(this, "cleanDirectories(): Inspecting old files...");
		
		// Queue dir policy:
		// - Keep all queued files so we don't have to download them again.
		// - Count them so mStatistics.mQueuedFiles is correct.
		for(File file : mQueueDir.listFiles()) {
			if(!file.getName().endsWith(IdentityFile.FILE_EXTENSION)) {
				Logger.warning(this, "cleanDirectories(): Unexpected file type: "
			                       + file.getAbsolutePath());
				continue;
			}

			++mStatistics.mQueuedFiles;
			++mStatistics.mTotalQueuedFiles;
		}

		Logger.normal(this, "cleanDirectories(): Old queued files: " + mStatistics.mQueuedFiles);

		// Processing dir policy:
		// In theory we could move the files back to the queue. But its possible that a colliding
		// filename exists there, which would need special code to handle.
		// Since there should only be 1 file at a time in processing, and lost files will
		// automatically be downloaded again, we just delete it to avoid the hassle of writing code
		// for moving it back.
		for(File file : mProcessingDir.listFiles()) {
			if(!file.getName().endsWith(IdentityFile.FILE_EXTENSION)) {
				Logger.warning(this, "cleanDirectories(): Unexpected file type: "
			                       + file.getAbsolutePath());
				continue;
			}
			
			if(!file.delete())  {
				Logger.error(this, "cleanDirectories(): Cannot delete old file in mProcessingDir: "
			                     + file.getAbsolutePath());
			} else {
				Logger.normal(this, "cleanDirectories(): Deleted old processing file: "
			                      + file.getAbsolutePath());
			}
		}
		
		
		// Finished dir policy:
		// The finished dir is an archival dir which archives old identity files for debug purposes.
		// Thus, we want to keep all files in mFinishedDir.
		// To ensure that new files do not collide with the index prefixes of old ones, we now need
		// to find the highest filename index prefix of the old files.
		int maxFinishedIndex = 0;

		for(File file: mFinishedDir.listFiles()) {
			String name = file.getName();
			
			if(!name.endsWith(IdentityFile.FILE_EXTENSION)) {
				Logger.warning(this, "cleanDirectories(): Unexpected file type: "
			                       + file.getAbsolutePath());
				continue;
			}

			try {
				 int index = Integer.parseInt(name.substring(0, name.indexOf('_')));
				 maxFinishedIndex = Math.max(maxFinishedIndex, index);
			} catch(RuntimeException e) { // TODO: Code quality: Java 7
				                          // catch NumberFormatException | IndexOutOfBoundsException
				
				Logger.warning(this, "cleanDirectories(): Cannot parse file name: "
				                   + file.getAbsolutePath());
				continue;
			}
		}
		
		mOldFinishedFileCount = maxFinishedIndex;
		
		Logger.normal(this, "cleanDirectories(): Old finished files: " + mOldFinishedFileCount);
		
		
		// We cannot do this now since we have no event handler yet.
		// registerEventHandler() does it for us.
		/*
		if(mStatistics.mQueuedFiles != 0)
			mEventHandler.triggerExecution();
		*/
		
		Logger.normal(this, "cleanDirectories(): Finished.");
	}

	@Override public synchronized void add(IdentityFileStream identityFileStream) {
		// We increment the counter before errors could occur so erroneously dropped files are
		// included: This ensures that the user might notice dropped files from the statistics in
		// the UI.
		++mStatistics.mTotalQueuedFiles;
		
		File filename = getQueueFilename(identityFileStream.mURI);
		// Delete for deduplication
		if(filename.exists()) {
			assert(IdentityFile.read(filename).getURI().getEdition()
				<= identityFileStream.mURI.getEdition())
				: "The IdentityFetcher must not fetch old editions after more recent ones!";
			
			if(!filename.delete())
				throw new RuntimeException("Cannot write to " + filename);
			
			--mStatistics.mQueuedFiles;
			++mStatistics.mDeduplicatedFiles;
		}
		
		// FIXME: Measure how long this takes. The IdentityFetcher contains code which could be
		// recycled for that.
		IdentityFile.read(identityFileStream).write(filename);
		
		++mStatistics.mQueuedFiles;
		
		assert(mStatistics.mQueuedFiles <= mStatistics.mTotalQueuedFiles);
		
		assert(mStatistics.mDeduplicatedFiles ==
			   mStatistics.mTotalQueuedFiles - mStatistics.mQueuedFiles
			   - mStatistics.mProcessingFiles - mStatistics.mFinishedFiles);
		
		if(mEventHandler != null)
			mEventHandler.triggerExecution();
		else
			Logger.error(this, "IdentityFile queued but no event handler is monitoring the queue!");
	}

	private File getQueueFilename(FreenetURI identityFileURI) {
		// We want to deduplicate editions of files for the same identity.
		// This can be done by causing filenames to always collide for the same identity:
		// An existing file of an old edition will be overwritten then.
		// We cause the collissions by using the ID of the identity as the only variable component
		// of the filename.
		return new File(mQueueDir,
			            getEncodedIdentityID(identityFileURI) + IdentityFile.FILE_EXTENSION);
	}

	private String getEncodedIdentityID(FreenetURI identityURI) {
		// Encode the ID with base 36 to ensure maximal filesystem compatibility.
		// ([a-z] and [A-Z] cannot both be used since Windows filenames are case-insensitive.)
		return IdentityID.constructAndValidateFromURI(identityURI).toStringBase36();
	}

	@Override public synchronized IdentityFileStream poll() {
		File[] queue = mQueueDir.listFiles();
		assert(queue.length == mStatistics.mQueuedFiles);
		
		// In theory, we should not have to loop over the result of listFiles(), we could always
		// return the first slot in its resulting array: poll() is not required to return any
		// specific selection of files.
		// However, to be robust against things such as the user sticking arbitrary files in the
		// directory, we loop over the files in the queue dir nevertheless:
		// If processing a file fails, we try the others until we succeed. 
		for(File queuedFile : queue) {
			try {
				IdentityFile fileData = IdentityFile.read(queuedFile);
				
				// Before we can return the file data, we must move the on-disk file from mQueueDir
				// to mProcessingDir to prevent it from getting poll()ed again.
				File dequeuedFile = new File(mProcessingDir, queuedFile.getName());
				assert(!dequeuedFile.exists());
				if(!queuedFile.renameTo(dequeuedFile)) {
					throw new RuntimeException("Cannot move file, source: " + queuedFile
			                                 + "; dest: " + dequeuedFile);
				}
				
				// The InputStreamWithCleanup wrapper will remove the file from mProcessingDir once
				// the stream is close()d.
				IdentityFileStream result = new IdentityFileStream(fileData.getURI(),
					new InputStreamWithCleanup(dequeuedFile, fileData,
						new ByteArrayInputStream(fileData.mXML)));
				
				++mStatistics.mProcessingFiles;
				assert(mStatistics.mProcessingFiles == 1);
				
				--mStatistics.mQueuedFiles;
				assert(mStatistics.mQueuedFiles >= 0);
				
				return result;
			} catch(RuntimeException e) {
				Logger.error(this, "Error in poll() for queued file: " + queuedFile, e);
				// FIXME: Delete the file if debug logging is not enabled.
				// Try whether we can process the next file
				continue;
			}
		}

		return null; // Queue is empty
	}

	/**
	 * When we return {@link IdentityFileStream} objects from {@link IdentityFileDiskQueue#poll()},
	 * we wrap their {@link InputStream} in this wrapper. Its purpose is to hook {@link #close()} to
	 * implement cleanup of our disk directories. */
	private final class InputStreamWithCleanup extends FilterInputStream {
		/**
		 * The backend file in {@link IdentityFileDiskQueue#mProcessingDir}.<br>
		 * On {@link #close()} we delete it; or archive it for debugging purposes.
		 * FIXME: Implement deletion. Currently only archival is implemented. */
		private final File mSourceFile;

		/**
		 * The URI where the File {@link #mSourceFile} was downloaded from.<br>
		 * If the file is to be archived for debugging purposes, the URI will be used for producing
		 * a new filename for archival. */
		private final FreenetURI mSourceURI;

		/** Used to prevent {@link #close()} from executing twice */
		private boolean mClosedAlready = false;


		public InputStreamWithCleanup(File fileName, IdentityFile fileData,
				InputStream fileStream) {
			super(fileStream);
			mSourceFile = fileName;
			mSourceURI = fileData.getURI();
		}

		@Override
		public void close() throws IOException {
			try {
				super.close();
			} finally {
				synchronized(IdentityFileDiskQueue.this) {
					// Prevent wrong value of mProcessingFiles by multiple calls to close(), which
					// paranoid code might do.
					if(mClosedAlready)
						return;

					assert(mStatistics.mProcessingFiles == 1);
					
					File moveTo = getAndReserveFinishedFilename(mSourceURI);

					assert(mSourceFile.exists());
					assert(!moveTo.exists());

					if(!mSourceFile.renameTo(moveTo)) {
						Logger.error(this, "Cannot move file, source: " + mSourceFile
							             + "; dest: " + moveTo);
						
						// We must delete as fallback: Otherwise, subsequent processed files of the
						// same Identity would collide with the filenames in the mProcessingDir.
						if(!mSourceFile.delete())
							Logger.error(this, "Cannot delete file: " + mSourceFile);
						else
							--mStatistics.mProcessingFiles;
					} else
						--mStatistics.mProcessingFiles;
					
					assert(mStatistics.mProcessingFiles == 0);

					mClosedAlready = true;
				}
			}
		}
	}

	/**
	 * Returns a filename suitable for use in directory {@link #mFinishedDir}.<br>
	 * Subsequent calls will never return the same filename again.<br><br>
	 * 
	 * ATTENTION: Must be called while being synchronized(this).<br><br>
	 * 
	 * Format:<br>
	 *     "I_identityID-HASH_edition-E.wot-identity"<br>
	 * where:<br>
	 *     I = zero-padded integer counting up from 0, to tell the precise order in which queued
	 *         files were processed. The padding is for nice sorting in the file manager.<br>
	 *     HASH = the ID of the {@link Identity}.<br>
	 *     E = the {@link Identity#getEdition() edition} of the identity file, as a zero-padded long
	 *         integer.<br><br>
	 * 
	 * Notice: The filenames contain more information than WOT needs for general purposes of future
	 * external scripts. */
	private File getAndReserveFinishedFilename(FreenetURI sourceURI) {
		File result = new File(mFinishedDir,
			String.format("%09d_identityID-%s_edition-%018d" + IdentityFile.FILE_EXTENSION,
				++mStatistics.mFinishedFiles + mOldFinishedFileCount,
				getEncodedIdentityID(sourceURI),
				sourceURI.getEdition()));
		
		assert(mStatistics.mFinishedFiles <= mStatistics.mTotalQueuedFiles);
		
		return result;
	}

	@Override public synchronized void registerEventHandler(BackgroundJob handler) {
		if(mEventHandler != null) {
			throw new UnsupportedOperationException(
				"Support for more than one event handler is not implemented yet.");
		}
		
		mEventHandler = handler;
		
		// We preserve queued files across restarts, so as soon after startup as we know who
		// the event handler is, we must wake up the event handler to process the waiting files.
		if(mStatistics.mQueuedFiles != 0)
			mEventHandler.triggerExecution();
	}

	@Override public synchronized IdentityFileQueueStatistics getStatistics() {
		return mStatistics.clone();
	}
}
