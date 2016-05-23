/* This code is part of WoT, a plugin for Freenet. It is distributed
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.zip.CRC32;

import plugins.WebOfTrust.IdentityFileQueue.IdentityFileStream;
import freenet.clients.fcp.FCPConnectionInputHandler;
import freenet.keys.FreenetURI;
import freenet.node.FSParseException;
import freenet.support.SimpleFieldSet;
import freenet.support.io.Closer;
import freenet.support.io.FileUtil;
import freenet.support.io.LineReadingInputStream;

/**
 * Serializer and parser class for storing an {@link IdentityFileStream} to disk and reading
 * IdentityFile objects back into memory.
 * This is used to write and read the files of the {@link IdentityFileDiskQueue}.
 * The higher level purpose is to store XML files we've downloaded from the network until we have
 * time to process them with the {@link IdentityFileProcessor}. Storage is necessary because the
 * downloading is usually faster than processing, and thus we must prevent exhausting the memory.
 * 
 * FILE FORMAT EXAMPLE:
 * 
 * # IdentityFile
 * Version=6
 * CRC32=cdef9876
 * SourceURI=USK@...
 * DataLength=1400
 * Data
 * <?xml version="1.1" encoding="UTF-8" standalone="no"?>
 * <WebOfTrust Version="...">
 * <Identity Name="..." PublishesTrustList="..." Version="...">
 * ...
 * </Identity>
 * </WebOfTrust>
 * 
 * REASONS FOR CHOICE OF FILE FORMAT:
 * 
 * The human readable file format is a combination of {@link SimpleFieldSet} and XML:
 * - Files start with a SimpleFieldSet with metadata about the file. SimpleFieldSet is used for
 *   human readability and simple parsing.
 * - Right after the SimpleFieldSet follows the raw XML as fetched from the public WoT network.
 * 
 * This is the same way as FCP handles binary attachments to FCP messages:
 * It first ships a SimpleFieldSet, and the binary attachment follows after the end marker of
 * the SimpleFieldSet. See class {@link FCPConnectionInputHandler}.
 *
 * The XML is treated like a binary attachment of the SFS because we cannot add the metadata to the
 * XML itself instead of keeping it in the SFS:
 * This would require us to parse the XML first. But parsing of data from the network shall happen
 * when we actually process the IdentityFiles, not when we store them. Thus we instead store the XML
 * as a "binary" attachment of a different file format.
 * Postponing the XML parsing is necessary because we want to punish publishers of corrupt XML. To
 * be able to punish them, we need access to the main WoT database. But we do not have access to the 
 * main database at the stage of writing IdentityFiles, so we cannot mark XML files as "parsing
 * failed" there.
 * And last but not least, we don't want access to the main database to ensure that
 * {@link IdentityFileDiskQueue} does not have to wait for any database table locks. 
 * It's a bit complex decision, but overall it keeps the {@link IdentityFileDiskQueue} separate from
 * the main WoT database and thus allows it to be very fast.
 * Remember: Speed is critical because Freenet can deliver files very quickly which can cause us to
 * run out of memory if we don't dump them to disk soon enough. */
final class IdentityFile {
	public static transient final String FILE_EXTENSION = ".wot-identity";
	
	public static transient final int FILE_FORMAT_VERSION = 6;

	/** @see #getURI() */
	private final FreenetURI mURI;

	/** @see IdentityFileStream#mXMLInputStream */
	public final byte[] mXML;


	private IdentityFile(FreenetURI uri, byte[] xml) {
		mURI = uri;
		mXML = xml;
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
		SimpleFieldSet sfs = new SimpleFieldSet(true);
		// Metadata
		sfs.setHeader("IdentityFile");
		sfs.put("Version", FILE_FORMAT_VERSION);
		// Data
		sfs.putOverwrite("SourceURI", mURI.toString());
		sfs.putOverwrite("CRC32", Long.toHexString(crc32()));
		sfs.put("DataLength", mXML.length); // Same format as FCP messages with Data attachment
		// XML follows after SimpleFieldSet dump
		sfs.setEndMarker("Data"); // Same format as FCP messages with Data attachment
		
		FileOutputStream fos = null;
		
		try {
			fos = new FileOutputStream(file);
			sfs.writeTo(fos);
			fos.write(mXML);
		} catch(IOException e) {
			throw new RuntimeException(e);
		} finally {
			Closer.close(fos);
		}
	}

	public static IdentityFile read(File source) {
		FileInputStream fis = null;
		LineReadingInputStream lris = null;
		ByteArrayOutputStream xmlBos = null;
		
		try {
			fis = new FileInputStream(source);
			lris = new LineReadingInputStream(fis);
			
			SimpleFieldSet sfs
				= new SimpleFieldSet(lris, Integer.MAX_VALUE, 4096, true, false, true);
			
			String[] headers = sfs.getHeader();
			if(headers == null || !headers[0].equals("IdentityFile"))
				throw new IOException("Unexpected file type: IdentityFile header not found!");
			
			if(sfs.getInt("Version") != FILE_FORMAT_VERSION) {
				throw new IOException("Unknown file format version: " + sfs.getInt("Version"));
			
			FreenetURI uri = new FreenetURI(sfs.getString("SourceURI"));
			
			int xmlLength = sfs.getInt("DataLength");
			assert(xmlLength > 0 && xmlLength <= XMLTransformer.MAX_IDENTITY_XML_BYTE_SIZE);
			assert(xmlLength == lris.available());
			xmlBos = new ByteArrayOutputStream(xmlLength);
			FileUtil.copy(lris, xmlBos, xmlLength);

			final IdentityFile deserialized = new IdentityFile(uri, xmlBos.toByteArray());
			
			long expectedCRC = Long.parseLong(sfs.getString("CRC32"), 16);
			if(deserialized.crc32() != expectedCRC)
				throw new IOException("CRC mismatch!");
			
			return deserialized;
		} catch(IOException e) {
			throw new RuntimeException(e);
		} catch(FSParseException e) {
			throw new RuntimeException(e);
		} finally {
			Closer.close(xmlBos);
			Closer.close(lris);
			Closer.close(fis);
		}
	}

	/** @see IdentityFileStream#mURI */
	public FreenetURI getURI() {
		return mURI;
	}

	public long crc32() {
		CRC32 crc = new CRC32();
		crc.update(mURI.toString().getBytes(XMLTransformer.XML_CHARSET));
		crc.update(mXML);
		return crc.getValue();
	}

	/** Same as {@link #crc32()}. Use that one instead for always getting non-negative values. */
	@Override public int hashCode() {
		return (int)crc32();
	}
}