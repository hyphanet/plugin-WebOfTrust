/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import static org.junit.Assert.*;

import java.net.MalformedURLException;

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

	@Test public void testInsertAndFetch()
			throws MalformedURLException, InvalidParameterException, NumberFormatException,
			UnknownIdentityException, InterruptedException {
		
		Node[] nodes = getNodes();
		WebOfTrust insertingWoT = getWebOfTrust(nodes[0]);
		WebOfTrust fetchingWoT  = getWebOfTrust(nodes[1]);
		
		// clone() as workaround for https://bugs.freenetproject.org/view.php?id=6247
		OwnIdentity insertedIdentity = insertingWoT.createOwnIdentity("i1", true, null).clone();
		OwnIdentity trustingIdentity = fetchingWoT.createOwnIdentity("i2", true, null).clone();
		
		fetchingWoT.addIdentity(insertedIdentity.getRequestURI().toString());
		fetchingWoT.setTrust(trustingIdentity.getID(), insertedIdentity.getID(), (byte)100, "");
		
		// This will be equals after the identity was inserted & fetched.
		assertNotEquals(
			insertingWoT.getIdentityByID(insertedIdentity.getID()),
			fetchingWoT.getIdentityByID(insertedIdentity.getID()));
		
		StopWatch time = new StopWatch();
		
		insertingWoT.getIdentityInserter().iterate();
		fetchingWoT.getIdentityFetcher().run();
		
		// FIXME: Code quality: Also add a wait loop for the insert so if this doesn't complete it
		// is easier to tell whether inserts or fetches do not work.
		System.out.println(
			"IdentityFetcherTest: Waiting for Identity to be inserted and fetched...");
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
		
		System.out.println("IdentityFetcherTest: Identity inserted and fetched! Time: " + time);
		
		insertingWoT.getIdentityInserter().terminate();
		fetchingWoT.getIdentityFetcher().stop();
		
		assertEquals(
			insertingWoT.getIdentityByID(insertedIdentity.getID()),
			fetchingWoT.getIdentityByID(insertedIdentity.getID()));
	}

}
