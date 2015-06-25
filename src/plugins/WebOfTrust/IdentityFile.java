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
import java.net.MalformedURLException;
import java.util.Arrays;

import plugins.WebOfTrust.IdentityFileQueue.IdentityFileStream;
import freenet.keys.FreenetURI;
import freenet.support.io.Closer;
import freenet.support.io.FileUtil;

/**
 * Wrapper class for storing an {@link IdentityFileStream} to disk via {@link Serializable}.
 * This is used to write and read the files of the {@link IdentityFileDiskQueue}. */
final class IdentityFile implements Serializable {
	public static transient final String FILE_EXTENSION = ".wot-identity";
	
	private static final long serialVersionUID = 4L;

	/** @see #getURI() */
	private final String mURI;

	/** @see IdentityFileStream#mXMLInputStream */
	public final byte[] mXML;
	
	/**
	 * Java serialization does not verify data integrity, so we do it ourselves with this hash.<br>
	 * This is a good idea since:<br>
	 * - At startup, we do not flush files enqueued in the {@link IdentityFileDiskQueue}. They might
	 *   have been corrupted due to a crash.<br>
	 * - {@link IdentityFileDiskQueue} does not use file locking, so the user might interfere with
	 *   the files in parallel. */
	private final int mHashCode;


	private IdentityFile(FreenetURI uri, byte[] xml) {
		mURI = uri.toString();
		mXML = xml;
		mHashCode = hashCodeRecompute();
	}

	static IdentityFile read(IdentityFileStream source) {
		FreenetURI uri;
		byte[] xml;
		
		uri = source.mURI;
		
		ByteArrayOutputStream bos = null;
		try {
			bos = new ByteArrayOutputStream(XMLTransformer.MAX_IDENTITY_XML_BYTE_SIZE + 1);
			FileUtil.copy(source.mXMLInputStream, bos, -1);
			xml = bos.toByteArray();
			assert(xml.length <= XMLTransformer.MAX_IDENTITY_XML_BYTE_SIZE);
		} catch(IOException e) {
			throw new RuntimeException(e);
		} finally {
			Closer.close(bos);
			Closer.close(source.mXMLInputStream);
		}
		
		return new IdentityFile(uri, xml);
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
			
			if(deserialized.hashCode() != deserialized.hashCodeRecompute())
				throw new IOException("Checksum mismatch: " + source);
			
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

	/** @see IdentityFileStream#mURI */
	public FreenetURI getURI() {
		try {
			return new FreenetURI(mURI);
		} catch (MalformedURLException e) {
			// We always set mURI via FreenetURI.toString(), so it should always be valid.
			throw new RuntimeException("SHOULD NEVER HAPPEN", e);
		}
	}

	@Override public int hashCode() {
		return mHashCode;
	}

	private int hashCodeRecompute() {
		// Use Arrays.hashCode(), not String.hashCode() or even FreenetURI.hashCode(), to avoid
		// caching:
		// We use the hash code to validate integrity of serialized data, so it must always be
		// recomputed.
		return Arrays.hashCode(mURI.toCharArray())
			 ^ Arrays.hashCode(mXML);
	}
}