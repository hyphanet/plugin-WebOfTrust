/* This code is part of WoT, a plugin for Freenet. It is distributed
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;

import plugins.WebOfTrust.Identity.IdentityID;
import freenet.keys.FreenetURI;
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
		File filename = getQueueFilename(identityFileStream.mURI);
		// Delete for deduplication
		if(filename.exists() && !filename.delete())
			throw new RuntimeException("Cannot write to " + filename);
		
		new IdentityFile(identityFileStream).write(filename);
	}

	private File getQueueFilename(FreenetURI identityFileURI) {
		// We want to deduplicate editions of files for the same identity.
		// This can be done by causing filenames to always collide for the same identity:
		// An existing file of an old edition will be overwritten then.
		// We cause the collissions by using the ID of the identity as the only variable component
		// of the filename.
		IdentityID id = IdentityID.constructAndValidateFromURI(identityFileURI);
		// FIXME: Encode the ID with base 36 to ensure maximal filesystem compatibility.
		return new File(mQueueDir, id + ".wot-identity");
	}
}
