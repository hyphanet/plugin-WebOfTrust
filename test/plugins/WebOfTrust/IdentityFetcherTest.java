/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import static java.lang.Thread.sleep;
import static org.junit.Assert.*;

import java.net.MalformedURLException;
import java.util.Date;

import org.junit.Before;
import org.junit.Test;

import plugins.WebOfTrust.Identity.FetchState;
import plugins.WebOfTrust.exceptions.InvalidParameterException;
import plugins.WebOfTrust.exceptions.UnknownIdentityException;
import plugins.WebOfTrust.util.StopWatch;
import freenet.node.Node;

/**
 * Tests class {@link IdentityFetcher} and {@link IdentityInserter} with a real small darknet of
 * ten Freenet {@link Node}s:
 * - Creates an {@link OwnIdentity} on node #1 and has the IdentityInserter insert it into Freenet.
 * - Creates another OwnIdentity on node #2 which adds the remote Identity by its URI and sets a
 *   positive trust to it.
 * - Waits until the remote Identity is successfully fetched and imported by the IdentityFetcher
 *   and validates its attributes are equal to the original. */
public final class IdentityFetcherTest extends AbstractMultiNodeTest {

	@Override public int getNodeCount() {
		// As recommended by the specification of this function at AbstractMultiNodeTest.
		return 100;
	}

	@Override public int getWoTCount() {
		return 2;
	}

	@Override public boolean shouldTerminateAllWoTThreads() {
		return false;
	}

	@Override public String getDetailedLogLevel() {
		// Enable DEBUG logging for the inserter/fetcher so you can watch progress on stdout while
		// the test is running.
		return "freenet:NONE,"
		     + "plugins.WebOfTrust.IdentityInserter:DEBUG,"
		     + "plugins.WebOfTrust.IdentityFetcher:DEBUG";
	}

	@Before public void setUp() throws MalformedURLException, UnknownIdentityException {
		deleteSeedIdentities();
	}

	@Test public void testInsertAndFetch()
			throws MalformedURLException, InvalidParameterException, NumberFormatException,
			UnknownIdentityException, InterruptedException {
		
		Node[] nodes = getNodes();
		WebOfTrust insertingWoT = getWebOfTrust(nodes[0]);
		WebOfTrust fetchingWoT  = getWebOfTrust(nodes[1]);
		
		OwnIdentity insertedIdentity;
		OwnIdentity trustingIdentity;
		
		// synchronized & clone() as workaround for https://bugs.freenetproject.org/view.php?id=6247
		synchronized(insertingWoT) {
		synchronized(fetchingWoT) {
			insertedIdentity = insertingWoT.createOwnIdentity("i1", true, null).clone();
			trustingIdentity = fetchingWoT.createOwnIdentity("i2", true, null).clone();
			
			fetchingWoT.addIdentity(insertedIdentity.getRequestURI().toString());
		}}
		
		// synchronized for concurrency, inserts & fetch could complete as soon as setTrust() is
		// done which would break the assertNotEquals().
		synchronized(insertingWoT) {
		synchronized(fetchingWoT) {
			// The Identity has to receive a Trust, otherwise it won't be eligible for download.
			fetchingWoT.setTrust(trustingIdentity.getID(), insertedIdentity.getID(), (byte)100, "");
			
			// This will be equals after the identity was inserted & fetched.
			assertNotEquals(
				insertingWoT.getIdentityByID(insertedIdentity.getID()),
				fetchingWoT.getIdentityByID(insertedIdentity.getID()));
		}}
		
		// Automatically scheduled for execution on a Thread by createOwnIdentity().
		/* insertingWoT.getIdentityInserter().iterate(); */
		
		// Automatically scheduled for execution by setTrust()'s callees.
		/* fetchingWoT.getIdentityFetcher().run(); */
		
		System.out.println("IdentityFetcherTest: Waiting for Identity to be inserted/fetched...");
		StopWatch insertTime = new StopWatch();
		StopWatch fetchTime = new StopWatch();
		boolean inserted = false;
		boolean fetched = false;
		do {
			printNodeStatistics();
			
			// Check whether Identity was inserted and print the time it took to insert it.
			// Notice: We intentionally don't wait for this in a separate loop before waiting for it
			// to be fetched: Due to redundancy of inserts fred's "insert finished!" callbacks can
			// happen AFTER the remote node's "fetch finished!" callbacks have already returned.
			if(!inserted) {
				synchronized(insertingWoT) {
					OwnIdentity i = insertingWoT.getOwnIdentityByID(insertedIdentity.getID());
					
					if(i.getLastInsertDate().after(new Date(0))) {
						inserted = true;
						System.out.println(
							"IdentityFetcherTest: Identity inserted! Time: " + insertTime);
					}
				}
			}
			
			synchronized(fetchingWoT) {
				Identity remoteView = fetchingWoT.getIdentityByID(insertedIdentity.getID());
				
				if(remoteView.getCurrentEditionFetchState() == FetchState.Fetched)
					fetched = true;
			} // Must not keep the lock while sleeping to allow WoT's threads to acquire it!
			
			if(!fetched)
				sleep(1000);
		} while(!fetched);
		System.out.println("IdentityFetcherTest: Identity fetched! Time: " + fetchTime);
		
		// Prevent further modifications while we check results...
		// FIXME: Code quality: Extract a function for this from AbstractMultiNodeTest.loadWoT(),
		// perhaps even put it into class WebOfTrust.
		insertingWoT.getIdentityInserter().terminate();
		fetchingWoT.getIdentityFetcher().stop();
		
		// ... and nevertheless synchronize because there are other threads in WoT.
		synchronized(insertingWoT) {
		synchronized(fetchingWoT) {
			// For Identity.equals() to succeed the source Identity we compare it to must not
			// be an OwnIdentity. deleteOwnIdentity() will replace the OwnIdentity with a
			// non-own one.
			insertingWoT.deleteOwnIdentity(insertedIdentity.getID());
			assertEquals(
				insertingWoT.getIdentityByID(insertedIdentity.getID()),
				fetchingWoT.getIdentityByID(insertedIdentity.getID()));
		}}
	}

}
