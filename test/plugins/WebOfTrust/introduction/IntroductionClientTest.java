/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.introduction;

import java.net.MalformedURLException;

import org.junit.Before;
import org.junit.Test;

import plugins.WebOfTrust.AbstractMultiNodeTest;
import plugins.WebOfTrust.Identity;
import plugins.WebOfTrust.OwnIdentity;
import plugins.WebOfTrust.Trust;
import plugins.WebOfTrust.WebOfTrust;
import plugins.WebOfTrust.exceptions.UnknownIdentityException;
import plugins.WebOfTrust.util.StopWatch;
import freenet.node.Node;

/**
 * Tests classes {@link IntroductionClient} and {@link IntroductionServer} with a real small darknet
 * of ten Freenet {@link Node}s and two {@link WebOfTrust} plugin instances:
 * - Makes an {@link OwnIdentity} on WoT #1 insert an {@link OwnIntroductionPuzzle} with the
 *   {@link IntroductionServer}.
 * - Makes a non-own {@link Identity} on WoT #2 solve the puzzle and insert the solution with the
 *   {@link IntroductionClient}.
 * - Waits until the IntroductionServer on WoT #1 successfully downloads and imports the remote
 *   Identity's introduction and thereby creates a {@link Trust} by the local OwnIdentity assigned
 *   to the remote Identity. */
public final class IntroductionClientTest extends AbstractMultiNodeTest {

	@Override public int getNodeCount() {
		return 10;
	}

	@Override public int getWoTCount() {
		return 2;
	}

	@Override public boolean shouldTerminateAllWoTThreads() {
		return false;
	}

	@Override public String getDetailedLogLevel() {
		// Enable DEBUG logging so you can watch progress on stdout while the test is running.
		// Enable logging for the unrelated IdentityFileProcessor as well as it may introduce a
		// processing delay currently which you can observe at its logging (see
		// https://bugs.freenetproject.org/view.php?id=6958).
		return "freenet:NONE,"
		     + "plugins.WebOfTrust.IdentityInserter:DEBUG,"
		     + "plugins.WebOfTrust.IdentityFetcher:DEBUG,"
		     + "plugins.WebOfTrust.IdentityFileProcessor:MINOR,"
		     + "plugins.WebOfTrust.introduction.IntroductionServer:DEBUG,"
		     + "plugins.WebOfTrust.introduction.IntroductionClient:DEBUG";
		
		// FIXME: Add plugins.WebOfTrust.network.input.IdentityDownloader:DEBUG / WHATEVER once
		// the branch issue-0003816-IdentityFetcher-rewrite is complete.
	}

	@Before public void setUp() throws MalformedURLException, UnknownIdentityException {
		deleteSeedIdentities();
	}

	@Test public void testFullIntroductionCycle() {
		System.out.println("IntroductionClientTest: testFullIntroductionCycle()...");
		StopWatch t = new StopWatch();
		
		Node[] nodes = getNodes();
		WebOfTrust serverWoT = getWebOfTrust(nodes[0]);
		WebOfTrust clientWoT  = getWebOfTrust(nodes[1]);
		IntroductionServer server = serverWoT.getIntroductionServer();
		IntroductionClient client = clientWoT.getIntroductionClient();
		IntroductionPuzzleStore serverStore = serverWoT.getIntroductionPuzzleStore();
		IntroductionPuzzleStore clientStore = clientWoT.getIntroductionPuzzleStore();
		OwnIdentity serverIdentity;
		OwnIdentity clientIdentity;
		
		System.out.println("IntroductionClientTest: testFullIntroductionCycle() done! Time: " + t);
		printNodeStatistics();
	}
}
