/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.xml.transform.TransformerException;

import org.junit.Before;
import org.junit.Ignore;
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
 * TODO: Ideas for tests:<br>
 * - Test whether deduplication does actually deduplicate stuff.
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
	 * to validate that they operate correctly.<br><br>
	 * 
	 * NOTICE: This does not have a {@link Before} annotation but rather is only called explicitly
	 * by the test which needs it so the tests of the parent class do not cause it to run since it
	 * takes a long time to execute. */
	private void setUp() throws InvalidParameterException, DuplicateTrustException,
			NotTrustedException, UnknownIdentityException, TransformerException, IOException {
		
		mWebOfTrust = constructEmptyWebOfTrust();

		// Now we generate a random Identity/Trust/Score graph.
		// We need a full copy of the trust graph as IdentityFiles. Exporting IdentityFiles is only
		// possible for OwnIdentity, not for regular Identity, so we only generate OwnIdentitys.
		final int ownIdentityCount = 10;
		
		final int newTrustsPerFile = 3;
		
		// Our goal is to populate mIdentityFiles1/mIdentityFiles2 with identityFileCount
		// IdentityFileStreams for each OwnIdentity.
		// To make the file contents differ, we will call addRandomTrustValues(newTrustsPerFile)
		// at each iteration of generating the files, so for an amount of identityFileCount times.
		// But addRandomTrustValues() would run into an infinite loop if we added more trusts than
		// can possibly exist given the number of identities. So the value of identityFileCount must
		// be limited by the number of trust values which are allowed to exist:
		// The upper boundary of possible trust values is (ownIdentityCount*(ownIdentityCount-1))
		// because each identity can trust each other identity except itself.
		final int identityFileCount = (ownIdentityCount*(ownIdentityCount-1)) / newTrustsPerFile;
		assert(identityFileCount >= 30);
		
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

				// Re-query since we only have a clone() but db4o needs the original
				identity = mWebOfTrust.getOwnIdentityByID(identity.getID());
				identity.setPublishTrustList(true);
				// Before creating the file, we increment the edition of the identity to ensure that
				// the edition deduplication code of IdentityFileDiskQueue doesn't wrongly
				// deduplicate the newer files with older ones.
				identity.updated(); // Necessary for getNextEditionToInsert() to not throw
				identity.onInserted(identity.getNextEditionToInsert());
				identity.storeAndCommit();
				
				// testByComparingResultsOfTwoImplementations() will use WebOfTrust.equals() to
				// compare the original WebOfTrust against what was transported by identity files.
				// The equals() also checks the value of trust.getTrusterEdition(), so we need
				// to update it.
				for(Trust trust : mWebOfTrust.getGivenTrusts(identity)) {
					trust.trusterEditionUpdated();
					trust.storeWithoutCommit();
				}
				Persistent.checkedCommit(mWebOfTrust.getDatabase(), this);
				
				mWebOfTrust.getXMLTransformer().exportOwnIdentity(identity, bos);
				
				ByteArrayInputStream bis1
					= new ByteArrayInputStream(bos.toByteArray());
				ByteArrayInputStream bis2
					= new ByteArrayInputStream(bos.toByteArray());
				bos.close();
				
				mIdentityFiles1.add(new IdentityFileStream(identity.getRequestURI(), bis1));
				mIdentityFiles2.add(new IdentityFileStream(identity.getRequestURI(), bis2));
			}
		}
		
		// Paranoid self-tests because an old version of the above code failed to generate Trusts
		assertTrue(mWebOfTrust.getAllOwnIdentities().size() > 0);
		assertTrue(mWebOfTrust.getAllTrusts().size() > 0);
		assertTrue(mWebOfTrust.getAllScores().size() > 0);
		assertEquals(ownIdentityCount, mWebOfTrust.getAllOwnIdentities().size());
		assertEquals(newTrustsPerFile * identityFileCount, mWebOfTrust.getAllTrusts().size());
		assertEquals(ownIdentityCount * ownIdentityCount, mWebOfTrust.getAllScores().size());
	}

	@Test public void testByComparingResultsOfTwoImplementations()
			throws IOException, InterruptedException, InvalidParameterException,
			DuplicateTrustException, NotTrustedException, UnknownIdentityException,
			TransformerException {
		
		setUp();

		WebOfTrust wot1 = constructEmptyWebOfTrust();
		WebOfTrust wot2 = constructEmptyWebOfTrust();

		// The final goal of this test is to achieve assertEquals(mWebOfTrust, wot1) and
		// assertEquals(mWebOfTrust, wot2) just by passing the contents of mWebOfTrust to wot1
		// and wot2 as IdentityFiles through the different IdentityFileQueue implementations
		// (or in other words:  by simulating network traffic from mWebOfTrust to wot1/wot2).
		// Thus let's do a self-test to ensure that the equals case is NOT already met before,
		// i.e. to validate that setUp() generated a valid *non*-empty mWebOfTrust.
		assertNotEquals(mWebOfTrust, wot1);
		assertNotEquals(mWebOfTrust, wot2);

		// Copy the OwnIdentitys from the source WOT to our test WOTs to ensure that trust lists
		// are being imported.
		for(OwnIdentity ownId : mWebOfTrust.getAllOwnIdentities()) {
			wot1.restoreOwnIdentity(ownId.getInsertURI());
			wot2.restoreOwnIdentity(ownId.getInsertURI());
		}
		
		// Similar to the above self-test: Check whether setUp() correctly added more than just
		// the OwnIdentitys because we had to manually copy them over and they thus cannot serve
		// as test data.
		assertNotEquals(mWebOfTrust, wot1);
		assertNotEquals(mWebOfTrust, wot2);
		
		// Actual test follows ...
		
		IdentityFileQueue queue1 = new IdentityFileMemoryQueue();
		IdentityFileQueue queue2 = new IdentityFileDiskQueue(mTempFolder.newFolder());
		
		// TODO: Code quality: Move the Ticker creation to a function. Also search the other unit
		// tests for similar code to deduplicate then.
		IdentityFileProcessor proc1 = new IdentityFileProcessor(queue1,
			new PrioritizedTicker(new PooledExecutor(), 0), wot1.getXMLTransformer());
		IdentityFileProcessor proc2 = new IdentityFileProcessor(queue2,
			new PrioritizedTicker(new PooledExecutor(), 0), wot2.getXMLTransformer());
		
		@Ignore final class ConcurrentEnqueuer {
			public void enqueue(final List<IdentityFileStream> files,
					final IdentityFileQueue queue, final IdentityFileProcessor proc)
					throws InterruptedException {
				
				Collections.shuffle(files, mRandom);
				
				ArrayList<Thread> threads = new ArrayList<Thread>(files.size() + 1);
				
				for(final IdentityFileStream file : files) {
					threads.add(new Thread(new Runnable() { @Override public void run() {
						// Trigger processor execution before the queue contains stuff to ensure
						// concurrency issues between the processor thread and add() also get
						// noticed.
						proc.triggerExecution(0);
						queue.add(file);
						proc.triggerExecution(0);
					}}));
				}
				
				proc.start();
				// Start threads in separate loop for maximum concurrency
				for(Thread thread : threads) thread.start();
				for(Thread thread : threads) thread.join();
			}
		};

		new ConcurrentEnqueuer().enqueue(mIdentityFiles1, queue1, proc1);	
		new ConcurrentEnqueuer().enqueue(mIdentityFiles2, queue2, proc2);
		
		do {
			Thread.sleep(100);
		} while(
				queue1.getStatistics().mQueuedFiles != 0
			 || queue2.getStatistics().mQueuedFiles != 0
			 || proc1.getStatistics().mProcessedFiles != mIdentityFiles1.size()
			 // Deduplication can cause us to process less files than mIdentityFiles2.size()
			 || proc2.getStatistics().mProcessedFiles != queue2.getStatistics().mFinishedFiles
		 );
		
		proc1.terminate();
		proc2.terminate();
		proc1.waitForTermination(Long.MAX_VALUE);
		proc2.waitForTermination(Long.MAX_VALUE);
		
		assertEquals(mWebOfTrust, wot1);
		assertEquals(mWebOfTrust, wot2);
	}

    @Override protected WebOfTrust getWebOfTrust() {
    	return mWebOfTrust;
    }

}
