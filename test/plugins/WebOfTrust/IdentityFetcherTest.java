/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import static org.junit.Assert.*;

import java.net.MalformedURLException;
import java.util.Date;

import org.junit.Test;

import plugins.WebOfTrust.Identity.FetchState;
import plugins.WebOfTrust.exceptions.InvalidParameterException;
import plugins.WebOfTrust.exceptions.UnknownIdentityException;
import plugins.WebOfTrust.util.StopWatch;
import freenet.node.Node;

/** Tests class {@link IdentityFetcher} with a real small darknet of two Freenet {@link Node}s. */
public final class IdentityFetcherTest extends AbstractMultiNodeTest {

	@Override public int getNodeCount() {
		return 2;
	}

	@Override public boolean shouldTerminateAllWoTThreads() {
		return false;
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
		
		System.out.println("IdentityFetcherTest: Waiting for Identity to be inserted...");
		StopWatch time = new StopWatch();
		boolean inserted = false;
		do {
			synchronized(insertingWoT) {
				OwnIdentity i = insertingWoT.getOwnIdentityByID(insertedIdentity.getID());
				
				if(i.getLastInsertDate().after(new Date(0)))
					inserted = true;
				
				if(!inserted)
					Thread.sleep(1000);
			}
		} while(!inserted);
		System.out.println("IdentityFetcherTest: Identity inserted! Time: " + time);
		
		System.out.println("IdentityFetcherTest: Waiting for Identity to be fetched...");
		time = new StopWatch();
		boolean fetched = false;
		do {
			synchronized(fetchingWoT) {
				Identity remoteView = fetchingWoT.getIdentityByID(insertedIdentity.getID());
				
				if(remoteView.getCurrentEditionFetchState() == FetchState.Fetched)
					fetched = true;
				
				if(!fetched)
					Thread.sleep(1000);
			}
		} while(!fetched);
		System.out.println("IdentityFetcherTest: Identity fetched! Time: " + time);
		
		// Prevent further modifications while we check results...
		insertingWoT.getIdentityInserter().terminate();
		fetchingWoT.getIdentityFetcher().stop();
		
		// ... and nevertheless synchronize because there are other threads in WoT.
		synchronized(insertingWoT) {
		synchronized(fetchingWoT) {
			assertEquals(
				insertingWoT.getIdentityByID(insertedIdentity.getID()),
				fetchingWoT.getIdentityByID(insertedIdentity.getID()));
		}}
	}

}
