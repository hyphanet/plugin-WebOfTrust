/* This code is part of WoT, a plugin for Freenet. It is distributed
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;

import plugins.WebOfTrust.Identity.IdentityID;
import plugins.WebOfTrust.util.jobs.BackgroundJob;
import freenet.keys.FreenetURI;
import freenet.support.Logger;
import freenet.support.io.Closer;
import freenet.support.io.FileUtil;

/**
 * {@link IdentityFileQueue} implementation which writes the files to disk instead of keeping them
 * in memory.<br><br>
 * 
 * Deduplicating queue: Only the latest edition of each file is returned; see
 * {@link IdentityFileQueue} for details.<br>
 * The order of files is not preserved.<br>
 */
public final class IdentityFileDiskQueue implements IdentityFileQueue {
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
	 * FIXME: Initialize if the queue is non-empty during startup.
	 * 
	 * @see #getStatistics() */
	private final IdentityFileQueueStatistics mStatistics = new IdentityFileQueueStatistics();
	
	/**
	 * FIXME: Trigger execution of the handler if the queue is non-empty during startup.
	 * 
	 * @see #registerEventHandler(BackgroundJob) */
	private BackgroundJob mEventHandler;


	public IdentityFileDiskQueue(WebOfTrust wot) {
		mDataDir = new File(wot.getUserDataDirectory(), "IdentityFileQueue");
		mQueueDir = new File(mDataDir, "Queued");
		mProcessingDir = new File(mDataDir, "Processing");
		mFinishedDir = new File(mDataDir, "Finished");
		
		if(!mQueueDir.exists() && !mQueueDir.mkdir())
			throw new RuntimeException("Cannot create " + mQueueDir);
		
		if(!mProcessingDir.exists() && !mProcessingDir.mkdir())
			throw new RuntimeException("Cannot create " + mProcessingDir);
		
		if(!mFinishedDir.exists() && !mFinishedDir.mkdir())
			throw new RuntimeException("Cannot create " + mFinishedDir);
	}

	/**
	 * Wrapper class for storing an {@link IdentityFileStream} to disk via {@link Serializable}.
	 * This is used to write and read the actual files of the queue. */
	private static final class IdentityFile implements Serializable {
		private static final long serialVersionUID = 1L;

		/** @see IdentityFileStream#mURI */
		public final FreenetURI mURI;

		/** @see IdentityFileStream#mXMLInputStream */
		public final byte[] mXML;

		public IdentityFile(IdentityFileStream source) {
			mURI = source.mURI.clone();
			
			ByteArrayOutputStream bos = null;
			try {
				bos = new ByteArrayOutputStream(XMLTransformer.MAX_IDENTITY_XML_BYTE_SIZE + 1);
				FileUtil.copy(source.mXMLInputStream, bos, -1);
				mXML = bos.toByteArray();
			} catch(IOException e) {
				throw new RuntimeException(e);
			} finally {
				Closer.close(bos);
				Closer.close(source.mXMLInputStream);
			}
		}

		public void write(File file) {
			FileOutputStream fos = null;
			ObjectOutputStream ous = null;
			
			try {
				fos = new FileOutputStream(file);
				ous = new ObjectOutputStream(fos);
				ous.writeObject(this);
			} catch(IOException e) {
				throw new RuntimeException(e);
			} finally {
				Closer.close(ous);
				Closer.close(fos);
			}
		}

		public static IdentityFile read(File source) {
			FileInputStream fis = null;
			ObjectInputStream ois = null;
			
			try {
				fis = new FileInputStream(source);
				ois = new ObjectInputStream(fis);
				final IdentityFile deserialized = (IdentityFile)ois.readObject();
				assert(deserialized != null) : "Not an IdentityFile: " + source;
				return deserialized;
			} catch(IOException e) {
				throw new RuntimeException(e);
			} catch (ClassNotFoundException e) {
				throw new RuntimeException(e);
			} finally {
				Closer.close(ois);
				Closer.close(fis);
			}
		}
	}

	@Override public synchronized void add(IdentityFileStream identityFileStream) {
		// We increment the counter before errors could occur so erroneously dropped files are
		// included: This ensures that the user might notice dropped files from the statistics in
		// the UI.
		++mStatistics.mTotalQueuedFiles;
		
		File filename = getQueueFilename(identityFileStream.mURI);
		// Delete for deduplication
		if(filename.exists()) {
			if(!filename.delete())
				throw new RuntimeException("Cannot write to " + filename);
			
			++mStatistics.mDeduplicatedFiles;
		}
		
		new IdentityFile(identityFileStream).write(filename);
		
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
		return new File(mQueueDir, getEncodedIdentityID(identityFileURI) + ".wot-identity");
	}

	private String getEncodedIdentityID(FreenetURI identityURI) {
		// FIXME: Encode the ID with base 36 to ensure maximal filesystem compatibility.
		return IdentityID.constructAndValidateFromURI(identityURI).toString();
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
				IdentityFileStream result = new IdentityFileStream(fileData.mURI,
					new InputStreamWithCleanup(dequeuedFile, fileData,
						new ByteArrayInputStream(fileData.mXML)));
				
				++mStatistics.mProcessingFiles;
				assert(mStatistics.mProcessingFiles == 1);
				
				--mStatistics.mQueuedFiles;
				assert(mStatistics.mQueuedFiles >= 0);
				
				return result;
			} catch(RuntimeException e) {
				Logger.error(this, "Error in poll() for queued file: " + queuedFile, e);
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


		public InputStreamWithCleanup(File fileName, IdentityFile fileData,
				InputStream fileStream) {
			super(fileStream);
			mSourceFile = fileName;
			mSourceURI = fileData.mURI;
		}

		@Override
		public void close() throws IOException {
			try {
				super.close();
			} finally {
				synchronized(IdentityFileDiskQueue.this) {
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
			String.format("%0d_identityID-%s_edition-%0d.wot-identity",
				++mStatistics.mFinishedFiles,
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
	}

	@Override public synchronized IdentityFileQueueStatistics getStatistics() {
		return mStatistics.clone();
	}
}
