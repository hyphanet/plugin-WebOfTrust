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
import java.util.Arrays;

import plugins.WebOfTrust.IdentityFileQueue.IdentityFileStream;
import freenet.keys.FreenetURI;
import freenet.support.io.Closer;
import freenet.support.io.FileUtil;

/**
 * Wrapper class for storing an {@link IdentityFileStream} to disk via {@link Serializable}.
 * This is used to write and read the files of the {@link IdentityFileDiskQueue}.
 * 
 * FIXME: Add checksum and validate it during deserialization. This is indicated because:
 * 1) I have done a test run where I modified the XML on a serialized file - the result was that
 *    the deserializer does not notice it, the modified XML was imported as is.
 * 2) At startup, we do not delete pre-existing files. They might have been damaged due to
 *    system crashes, force termination, etc. */
final class IdentityFile implements Serializable {
	public static transient final String FILE_EXTENSION = ".wot-identity";
	
	private static final long serialVersionUID = 2L;

	/** @see IdentityFileStream#mURI */
	public final FreenetURI mURI;

	/** @see IdentityFileStream#mXMLInputStream */
	public final byte[] mXML;
	
	/**
	 * Java serialization does not verify data integrity, so we do it ourselves with this hash.<br>
	 * This is a good idea since at startup, we do not flush files enqueued in the
	 * {@link IdentityFileDiskQueue} - they might have been corrupted due to a crash. */
	private final int mHashCode;


	private IdentityFile(FreenetURI uri, byte[] xml) {
		mURI = uri;
		mXML = xml;
		mHashCode = hashCodeCompute();
	}

	static IdentityFile read(IdentityFileStream source) {
		FreenetURI uri;
		byte[] xml;
		
		uri = source.mURI.clone();
		
		ByteArrayOutputStream bos = null;
		try {
			bos = new ByteArrayOutputStream(XMLTransformer.MAX_IDENTITY_XML_BYTE_SIZE + 1);
			FileUtil.copy(source.mXMLInputStream, bos, -1);
			xml = bos.toByteArray();
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
			
			if(deserialized.hashCode() != deserialized.hashCodeCompute())
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

	@Override public int hashCode() {
		return mHashCode;
	}

	public int hashCodeCompute() {
		return mURI.hashCode() ^ Arrays.hashCode(mXML);
	}
}