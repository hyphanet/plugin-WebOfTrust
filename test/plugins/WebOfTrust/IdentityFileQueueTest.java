/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;

import javax.xml.transform.TransformerException;

import org.junit.Before;
import org.junit.Test;

import plugins.WebOfTrust.IdentityFileQueue.IdentityFileStream;
import plugins.WebOfTrust.exceptions.DuplicateTrustException;
import plugins.WebOfTrust.exceptions.InvalidParameterException;
import plugins.WebOfTrust.exceptions.NotTrustedException;
import plugins.WebOfTrust.exceptions.UnknownIdentityException;
import freenet.support.PooledExecutor;
import freenet.support.PrioritizedTicker;

/**
 * Test for both implementations of {@link IdentityFileQueue}: {@link IdentityFileDiskQueue} and
 * {@link IdentityFileMemoryQueue}.<br><br>
 * 
 * They are being tested against each other by feeding the same set of identity files to them, and
 * then checking whether the resulting WOT database is equal.<br><br>
 * 
 * FIXME: General ideas for tests:<br>
 * - Test whether deduplication does not over-deduplicate stuff which it shouldn't deduplicate. Also
 *   test whether it does deduplicate stuff which it should.
 */
public class IdentityFileQueueTest extends AbstractJUnit4BaseTest {
	
	/** A random WebOfTrust: Random {@link OwnIdentity}s, {@link Trust}s, {@link Score}s */
	private WebOfTrust mWebOfTrust;
	
	/**
	 * Multiple snapshots of the random {@link WebOfTrust} {@link #mWebOfTrust} at different stages
	 * of generation of the random content. */
	private ArrayList<IdentityFileStream> mIdentityFiles1;
	
	/**
	 * Copy of {@link #mIdentityFiles1} with different {@link InputStream} objects since streams
	 * cannot be recycled after {@link InputStream#close()}. */
	private ArrayList<IdentityFileStream> mIdentityFiles2;


	/**
	 * Generates random {@link OwnIdentity}s and {@link Trust}s in {@link #mWebOfTrust}.<br>
	 * Populates {@link #mIdentityFiles1} and {@link #mIdentityFiles2} with {@link IdentityFile}
	 * dumps of several stages of the generation of {@link #mWebOfTrust}, including the final
	 * stage.<br>
	 * Those dumps will be used as input for the {@link IdentityFileQueue} implementations
	 * to validate that they operate correctly. */
	@Before public void setUp() throws InvalidParameterException, DuplicateTrustException,
			NotTrustedException, UnknownIdentityException, TransformerException, IOException {
		
		mWebOfTrust = constructEmptyWebOfTrust();

		// Our goal is to populate mIdentityFiles1/2 with this many files for each OwnIdentity.
		final int identityFileCount = 5;
		
		// Now we generate a random Identity/Trust/Score graph.
		// We need a full copy of the trust graph as IdentityFiles. Exporting IdentityFiles is only
		// possible for OwnIdentity, not for regular Identity, so we only generate OwnIdentitys.
		final int ownIdentityCount = 5;
		// A complete graph would be (identity count)² Trust values.
		// An Identity cannot trust itself, and addRandomTrustValues() runs into an infinite loop
		// if the graph is full, so we need to lower this significantly.
		final int newTrustsPerFile
			= (ownIdentityCount*ownIdentityCount) / (identityFileCount*2);
		// Would be used for doRandomChangesToWOT(), but we cannot do that in the current
		// implementation, see below.
		/* final int eventCount = 10; */

		final ArrayList<OwnIdentity> ownIdentitiesUncasted
			= addRandomOwnIdentities(ownIdentityCount);
		@SuppressWarnings("unchecked")
		final ArrayList<Identity> ownIdentitiesCasted
			= (ArrayList<Identity>) (ArrayList<? extends Identity>)ownIdentitiesUncasted;
		
		// Now produce the actual IdentityFile dumps
		mIdentityFiles1 = new ArrayList<IdentityFileStream>(identityFileCount*ownIdentityCount + 1);
		mIdentityFiles2 = new ArrayList<IdentityFileStream>(identityFileCount*ownIdentityCount + 1);
		
		for(int i=0; i < identityFileCount; ++i) {
			addRandomTrustValues(ownIdentitiesCasted, newTrustsPerFile);
			
			// TODO: Code quality: We cannot do this: It could use deleteOwnIdentity() to convert
			// an OwnIdentity to a regular Identity. This would cause us to be unable to use
			// the XMLTransformer to export the IdentityFile.
			// A fix might be a version of this function which allows disabling specific event
			// types such as deleteOwnIdentity().
			/* doRandomChangesToWOT(eventCount); */
			
			for(OwnIdentity identity : ownIdentitiesUncasted) {
				ByteArrayOutputStream bos
					= new ByteArrayOutputStream(XMLTransformer.MAX_IDENTITY_XML_BYTE_SIZE + 1);
				
				mWebOfTrust.getXMLTransformer().exportOwnIdentity(
					// Re-query since we only have a clone() but db4o needs the original
					mWebOfTrust.getOwnIdentityByID(identity.getID()), bos);
				
				ByteArrayInputStream bis1
					= new ByteArrayInputStream(bos.toByteArray());
				ByteArrayInputStream bis2
					= new ByteArrayInputStream(bos.toByteArray());
				bos.close();
				
				mIdentityFiles1.add(new IdentityFileStream(identity.getRequestURI(), bis1));
				mIdentityFiles2.add(new IdentityFileStream(identity.getRequestURI(), bis2));
			}
		}
	}

}
