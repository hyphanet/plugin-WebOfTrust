/* This code is part of WoT, a plugin for Freenet. It is distributed
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

import plugins.WebOfTrust.Identity.IdentityID;
import plugins.WebOfTrust.util.Pair;
import plugins.WebOfTrust.util.jobs.BackgroundJob;
import freenet.keys.FreenetURI;
import freenet.support.CurrentTimeUTC;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;

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
	 * If {@link #logDEBUG} is true, when the stream of a file returned by {@link #poll()} is
	 * closed, the closing function of the stream will move the file to this subdir of
	 * {@link #mDataDir}.<br>
	 * If {@link #logDEBUG} is false, the finished files will be deleted upon stream closure. */
	private final File mFinishedDir;

	/** @see IdentityFetcher#DEBUG__NETWORK_DUMP_MODE */
	private final boolean mDeduplicationEnabled;

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

	/** @see #getStatisticsOfLastSession() */
	private static final String STATISTICS_OF_LAST_SESSION_FILENAME = "Statistics.ser";

	/** @see #registerEventHandler(BackgroundJob) */
	private BackgroundJob mEventHandler;


	/**
	 * Automatically set to true by {@link Logger} if the log level is set to
	 * {@link LogLevel#DEBUG} for this class. Used as performance optimization to prevent
	 * construction of the log strings if it is not necessary. */
	private static transient volatile boolean logDEBUG = false;
	
	/**
	 * Automatically set to true by {@link Logger} if the log level is set to
	 * {@link LogLevel#MINOR} for this class. Used as performance optimization to prevent
	 * construction of the log strings if it is not necessary. */
	private static transient volatile boolean logMINOR = false;

	static {
		// Necessary for automatic setting of logDEBUG and logMINOR
		Logger.registerClass(IdentityFileDiskQueue.class);
	}


	public IdentityFileDiskQueue(File parentDirectory) {
		mDataDir = new File(parentDirectory, "IdentityFileQueue");
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
		
		if(!IdentityFetcher.DEBUG__NETWORK_DUMP_MODE) {
			mDeduplicationEnabled = true;
		} else {
			Logger.warning(this,
				"IdentityFetcher.DEBUG__NETWORK_DUMP_MODE == true: Disabling deduplication!");
			
			mDeduplicationEnabled = false;
		}
	}

	/** Used at startup to ensure that the data directories are in a clean state */
	private synchronized void cleanDirectories() {
		Logger.normal(this, "cleanDirectories(): Inspecting old files...");
		
		// Queue dir policy:
		// - Keep all queued files so we don't have to download them again.
		// - Count them so mStatistics.mQueuedFiles is correct.
		for(File file : mQueueDir.listFiles()) {
			if(!file.getName().endsWith(IdentityFile.FILE_EXTENSION)) {
				Logger.warning(this, "cleanDirectories(): Unexpected file type: " + file);
				continue;
			}

			++mStatistics.mQueuedFiles;
			++mStatistics.mTotalQueuedFiles;
			++mStatistics.mLeftoverFilesOfLastSession;
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
				Logger.warning(this, "cleanDirectories(): Unexpected file type: " + file);
				continue;
			}
			
			if(!file.delete())  {
				Logger.error(this, "cleanDirectories(): Cannot delete old file in mProcessingDir: "
			                     + file);
			} else {
				Logger.normal(this, "cleanDirectories(): Deleted old processing file: " + file);
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
				Logger.warning(this, "cleanDirectories(): Unexpected file type: " + file);
				continue;
			}

			try {
				 int index = Integer.parseInt(name.substring(0, name.indexOf('_')));
				 maxFinishedIndex = Math.max(maxFinishedIndex, index);
			} catch(RuntimeException e) { // TODO: Code quality: Java 7
				                          // catch NumberFormatException | IndexOutOfBoundsException
				
				Logger.warning(this, "cleanDirectories(): Cannot parse file name: " + file);
				continue;
			}
		}
		
		mOldFinishedFileCount = maxFinishedIndex;
		
		Logger.normal(this, "cleanDirectories(): Old finished files: " + mOldFinishedFileCount);
		
		assert(mStatistics.checkConsistency());
		assert(checkDiskConsistency());
		
		// We cannot do this now since we have no event handler yet.
		// registerEventHandler() does it for us.
		/*
		if(mStatistics.mQueuedFiles != 0)
			mEventHandler.triggerExecution();
		*/
		
		Logger.normal(this, "cleanDirectories(): Finished.");
	}

	/** FIXME: This doesn't seem to need to be synchronized based on what it does! I.e. computing
	 *  a filename from the URI and checking if that file exists should be independent upon any
	 *  state of this object.
	 *  Removing the synchronization would be a good idea because:
	 * - IdentityDownloaderSlow calls it while having taken other locks so that is prone to
	 *   deadlocking.
	 * - IdentityDownloaderSlow calls it frequently and thus it should be fast.
	 *
	 *  FIXME: This won't work if {@link #mDeduplicationEnabled} is false! Just get rid of the
	 *  possibility to disable it, the {@link IdentityFetcher#DEBUG__NETWORK_DUMP_MODE} didn't
	 *  work as intended anyway and IdentityFetcher itself is a legacy class now. */
	@Override public synchronized boolean containsAnyEditionOf(FreenetURI identityFileURI) {
		// TODO: Performance: Avoid computing the filename from the URI twice by replacing
		// get*Filename() with a single call to compute the filename without the dir, and then
		// constructing two new File(dir, filename) for the two dirs.
		// FIXME: Investigate if it would be possible to not additionally check the processing dir
		// without causing IdentityDownloaderSlow to download the same file again while it is
		// being processed. If we do remove the additional check then the FIXME related to files
		// which are being processed at IdentityFileMemoryQueue.contains*() can be removed.
		return getQueueFilename(identityFileURI).exists()
		    || getProcessingDirFilename(identityFileURI).exists();
	}

	// FIXME: This sometimes gets called a long time after WoT has been terminated, which indicates
	// the new IdentityDownloaderFast/Slow don't terminate properly.
	// - Caught by then failing in assert(checkDiskConsistency()) in this function.
	@Override public synchronized void add(IdentityFileStream identityFileStream) {
		try {
			// We increment the counter before errors could occur so erroneously dropped files are
			// included: This ensures that the user might notice dropped files from the statistics
			// in the UI.
			++mStatistics.mTotalQueuedFiles;
			mStatistics.mTimesOfQueuing.addLast(
				new Pair<>(CurrentTimeUTC.getInMillis(),
					mStatistics.mTotalQueuedFiles - mStatistics.mLeftoverFilesOfLastSession));
			
			File filename = getQueueFilename(identityFileStream.mURI);
			// Delete for deduplication
			if(filename.exists()) {
				IdentityFile existingQueuedData = IdentityFile.read(filename);
				assert(IdentityID.constructAndValidateFromURI(existingQueuedData.getURI())
					   .equals(IdentityID.constructAndValidateFromURI(identityFileStream.mURI)))
					: "Filenames should only collide for the same Identity, see getQueueFilename()";
				
				long existingQueuedEdition = existingQueuedData.getURI().getEdition();
				long givenEdition = identityFileStream.mURI.getEdition();
				
				// Make sure that we do not delete a queued new edition in favor of an old one
				// passed to us. This can happen because:
				// A) the IdentityFetcher.onFound() USK subscription callback is called in threads
				//    and thus no proper order of arrival of files is guaranteed.
				// B) we keep queued files across restarts, but the IdentityFetcher fetches are
				//    restarted from the edition specified in the main database. As queued files
				//    have not been imported in the main database yet, the edition there may be
				//    older than what is queued.
				// Notice: This is intentionally a ">" check instead of ">=":
				// If we re-fetch the same edition, we better drop the file on disk to protect
				// against broken files which are stuck in the queue due to corruption/bugs.
				if(existingQueuedEdition > givenEdition) {
					if(logMINOR) {
						Logger.minor(this, "Fetched edition which is older than queued file, "
										 + "dropping: " + givenEdition);
					}
					
					++mStatistics.mDeduplicatedFiles;
					assert(mStatistics.checkConsistency());
					assert(checkDiskConsistency());
					return;
				} else {
					// Queued file *is* old, deduplicate it
					if(filename.delete()) {
						if(logMINOR) {
							Logger.minor(this, "Deduplicating edition " + existingQueuedEdition
							                 + " with edition " + givenEdition
							                 + " for: " + identityFileStream.mURI);
						}
						
						--mStatistics.mQueuedFiles;
						++mStatistics.mDeduplicatedFiles;
						// Must first add the new file to the queue before this is valid again
						// because mTotalQueuedFiles has been incremented already.
						/* assert(mStatistics.checkConsistency()); */
					} else
						throw new RuntimeException("Cannot write to " + filename);				
				}
			}
			
			// FIXME: Measure how long this takes. The IdentityFileProcessor contains code which
			// could be recycled for that.
			IdentityFile.read(identityFileStream).write(filename);
			
			++mStatistics.mQueuedFiles;
			assert(mStatistics.checkConsistency());
			assert(checkDiskConsistency());
			
			if(mEventHandler != null)
				mEventHandler.triggerExecution();
			else {
				// The IdentityFetcher might fetch files during its start() already, and call this
				// function to enqueue fetched files. However, IdentityFileProcessor.start(), which
				// would register it as the event handler which is missing here, is called *after*
				// IdentityFetcher.start(). This is to prevent identity file processing from slowing
				// down WoT startup.
				// Thus having not an event handler yet is not an error, which is why the below
				// error logging code is commented out.
				// TODO: Code quality: Once https://bugs.freenetproject.org/view.php?id=6674 has
				// been fixed, we could split IdentityFileProcessor.start() into register() and
				// start(): Register would be what start() was previously, i.e. register the event
				// handler so we're not missing it here anymore. start() would be what was demanded
				// by the bugtracker entry, i.e. enable IdentityFileProcessor.triggerExecution().
				// IdentityFileProcessor.register() could then be called before
				// IdentityFetcher.start(), and IdentityFileProcessor.start() afterwards. It then
				// wouldn't matter if register() is called before IdentityFetcher.start():
				// This IdentityFileDiskQueue could not cause the processor to process the files
				// which the IdentityFetcher adds, as IdentityFileProcessor.triggerExecution() would
				// only work after start().
				// So overall, we could then log this error for robustness.
				/*
				Logger.error(this, "IdentityFile queued but no event handler is monitoring the "
					 			 + "queue!");
				*/
			}
		} catch(RuntimeException e) {
			++mStatistics.mFailedFiles;
			assert(mStatistics.checkConsistency());
			throw e;
		} catch(Error e) { // TODO: Java 7: Merge with above to catch(RuntimeException | Error e)
			++mStatistics.mFailedFiles;
			assert(mStatistics.checkConsistency());
			throw e;
		}
	}

	private File getQueueFilename(FreenetURI identityFileURI) {
		if(mDeduplicationEnabled) {
			// We want to deduplicate editions of files for the same identity.
			// This can be done by causing filenames to always collide for the same identity:
			// An existing file of an old edition will be overwritten then.
			// We cause the collissions by using the ID of the identity as the only variable
			// component of the filename.
			return new File(mQueueDir,
			            getEncodedIdentityID(identityFileURI) + IdentityFile.FILE_EXTENSION);
		} else {
			// Return non-colliding filenames by including edition
			return new File(mQueueDir,
				String.format("identityID-%s_edition-%018d" + IdentityFile.FILE_EXTENSION,
								getEncodedIdentityID(identityFileURI),
								identityFileURI.getEdition()));	
		}
	}

	private File getProcessingDirFilename(FreenetURI identityFileURI) {
		return new File(mProcessingDir, getQueueFilename(identityFileURI).getName());
	}

	private static String getEncodedIdentityID(FreenetURI identityURI) {
		// Encode the ID with Base32 to ensure maximal filesystem compatibility.
		// ([a-z] and [A-Z] cannot both be used since Windows filenames are case-insensitive.)
		// TODO: Performance: Do we really need to use the fully validating version here?
		return IdentityID.constructAndValidateFromURI(identityURI).toStringBase32();
	}

	@Override public synchronized IdentityFileStreamWrapper poll() {
		File[] queue = mQueueDir.listFiles();
		assert(queue.length == mStatistics.mQueuedFiles);
		
		if(logDEBUG) {
			Logger.debug(this, "poll(): DEBUG logging enabled, sorting files alphabetically...");
			Arrays.sort(queue);
		}

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
				// to mProcessingDir due to potential download of newer editions with the same
				// filename which may happen after we've returned but before our caller has closed
				// the stream we return:
				// Moving it ensures IdentityFileStreamWrapperImpl cannot wrongly delete such
				// concurrently downloaded newer editions upon close() just because their filename
				// matches.
				// It also prevents the file from being returned by the next call to poll() again in
				// case processing fails fatally - that guarantees a single bogus file cannot
				// permanently block processing by always being returned by poll().
				File dequeuedFile = new File(mProcessingDir, queuedFile.getName());
				assert(!dequeuedFile.exists());
				if(!queuedFile.renameTo(dequeuedFile)) {
					throw new RuntimeException("Cannot move file, source: " + queuedFile
			                                 + "; dest: " + dequeuedFile);
				}
				
				// The IdentityFileStreamWrapperImpl wrapper will remove the file from
				// mProcessingDir once the stream is close()d.
				// TODO: Code quality: Close inner streams upon construction failure of outer ones.
				// Not critical to fix: The streams do not lock any resources.
				IdentityFileStreamWrapper result =  new IdentityFileStreamWrapperImpl(
					dequeuedFile, fileData, new IdentityFileStream(fileData.getURI(),
						new ByteArrayInputStream(fileData.mXML)));
				
				++mStatistics.mProcessingFiles;
				assert(mStatistics.mProcessingFiles == 1);
				
				--mStatistics.mQueuedFiles;
				assert(mStatistics.checkConsistency());
				assert(checkDiskConsistency());
				
				if(logDEBUG) Logger.debug(this, "poll(): Yielded " + queuedFile.getName());
				return result;
			} catch(RuntimeException e) {
				Logger.error(this, "Error in poll() for queued file: " + queuedFile, e);
				
				++mStatistics.mFailedFiles;
				assert(mStatistics.checkConsistency());
				
				if(!logDEBUG) {
					Logger.error(this, "logDEBUG is false, deleting erroneous file: " + queuedFile);
					
					if(queuedFile.delete()) {
						--mStatistics.mQueuedFiles;
						assert(mStatistics.checkConsistency());
						assert(checkDiskConsistency());
					} else
						Logger.error(this, "Cannot delete file: " + queuedFile);
				}
				
				// Try whether we can process the next file
				continue;
			}
		}

		if(logDEBUG) Logger.debug(this, "poll(): Yielded no file" );
		return null; // Queue is empty
	}

	@Override public synchronized int getSize() {
		assert(mStatistics.mQueuedFiles == mQueueDir.listFiles().length);
		return mStatistics.mQueuedFiles;
	}

	private final class IdentityFileStreamWrapperImpl implements IdentityFileStreamWrapper {
		/**
		 * The backend file in {@link IdentityFileDiskQueue#mProcessingDir}.<br>
		 * On {@link #close()} we delete it; or archive it for debugging purposes if
		 * {@link IdentityFileDiskQueue#logDEBUG} is true */
		private final File mSourceFile;

		/**
		 * The URI where the File {@link #mSourceFile} was downloaded from.<br>
		 * If the file is to be archived for debugging purposes, the URI will be used for producing
		 * a new filename for archival. */
		private final FreenetURI mSourceURI;

		private final IdentityFileStream mStream;

		/** Used to prevent {@link #close()} from executing twice */
		private boolean mClosedAlready = false;


		public IdentityFileStreamWrapperImpl(File fileName, IdentityFile fileData,
				IdentityFileStream fileStream) {
			mSourceFile = fileName;
			mSourceURI = fileData.getURI();
			mStream = fileStream;
		}

		@Override public IdentityFileStream getIdentityFileStream() {
			return mStream;
		}

		@Override
		public void close() throws IOException {
			try {
				mStream.mXMLInputStream.close();
			} finally {
				synchronized(IdentityFileDiskQueue.this) {
					// Prevent wrong value of mProcessingFiles by multiple calls to close(), which
					// paranoid code might do.
					if(mClosedAlready)
						return;

					assert(mStatistics.mProcessingFiles == 1);

					if(!logDEBUG)
						deleteFile();
					else
						archiveFile();
					
					assert(mStatistics.mProcessingFiles == 0);

					mClosedAlready = true;
				}
			}
		}
		
		/** Must be called while synchronized(IdentityFileDiskQueue.this) */
		private void deleteFile() {
			if(logMINOR)
				Logger.minor(this, "deleteFile() started for " + mSourceFile);
			
			++mStatistics.mFinishedFiles;
			
			if(mSourceFile.delete())
				--mStatistics.mProcessingFiles;
			else
				Logger.error(this, "Cannot delete file: " + mSourceFile);
			
			assert(mStatistics.checkConsistency());
			assert(checkDiskConsistency());
			
			if(logMINOR)
				Logger.minor(this, "deleteFile() finished for " + mSourceFile);
		}

		/** Must be called while synchronized(IdentityFileDiskQueue.this) */
		private void archiveFile() {
			if(logMINOR)
				Logger.minor(this, "archiveFile() started for " + mSourceFile);
			
			File moveTo = getAndReserveFinishedFilename(mSourceURI);

			assert(mSourceFile.exists());
			assert(!moveTo.exists());

			if(!mSourceFile.renameTo(moveTo)) {
				Logger.error(this, "Cannot move file, source: " + mSourceFile
								 + "; dest: " + moveTo);

				// We must delete as fallback: Otherwise, subsequent processed files of the
				// same Identity would collide with the filenames in the mProcessingDir.
				// (Do not use deleteFile() since it would update mStatistics which we did already.)
				if(mSourceFile.delete())
					--mStatistics.mProcessingFiles;
				else
					Logger.error(this, "Cannot delete file: " + mSourceFile);
			} else
				--mStatistics.mProcessingFiles;
			
			assert(mStatistics.checkConsistency());
			assert(checkDiskConsistency());
			
			if(logMINOR)
				Logger.minor(this, "archiveFile() finished for " + mSourceFile);
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
	 *     E = the {@link Identity#getRawEdition() edition} of the identity file, as a zero-padded long
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
		
		// Cannot do this yet: mProcessingFiles etc. are not updated yet.
		/* assert(mStatistics.checkConsistency()); */
		
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
		IdentityFileQueueStatistics result = mStatistics.clone();
		assert(result.checkConsistency());
		assert(checkDiskConsistency());
		return result;
	}

	@Override public synchronized IdentityFileQueueStatistics getStatisticsOfLastSession()
			throws IOException {
		return IdentityFileQueueStatistics.read(
			new File(mDataDir, STATISTICS_OF_LAST_SESSION_FILENAME));
	}

	private synchronized void saveStatistics() throws IOException {
		File output = new File(mDataDir, STATISTICS_OF_LAST_SESSION_FILENAME);
		output.delete();
		mStatistics.write(output);
	}

	@Override public synchronized void stop() {
		try {
			saveStatistics();
		} catch(IOException e) {
			Logger.error(this, "saveStatistics() failed!", e);
		}
	}

	/**
	 * Returns true if the numbers in {@link #mStatistics} match the amount of files in the on-disk
	 * directories. */
	private synchronized boolean checkDiskConsistency() {
		int queued = mQueueDir.listFiles().length;
		int processing = mProcessingDir.listFiles().length;
		int finished = mFinishedDir.listFiles().length;
		
		return (
				(queued == mStatistics.mQueuedFiles)
			 && (processing == mStatistics.mProcessingFiles)
			 && (finished ==
					(logDEBUG == false ?
						mOldFinishedFileCount
					  : mStatistics.mFinishedFiles + mOldFinishedFileCount))
			);
	}
}
