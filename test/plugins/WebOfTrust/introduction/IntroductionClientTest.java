/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.introduction;

import static java.lang.Thread.sleep;
import static org.junit.Assert.*;
import static plugins.WebOfTrust.introduction.IntroductionPuzzle.INTRODUCTION_CONTEXT;
import static plugins.WebOfTrust.introduction.IntroductionServer.PUZZLE_COUNT_PROPERTY;

import java.net.MalformedURLException;

import org.junit.Before;
import org.junit.Test;

import plugins.WebOfTrust.AbstractMultiNodeTest;
import plugins.WebOfTrust.Identity;
import plugins.WebOfTrust.OwnIdentity;
import plugins.WebOfTrust.Trust;
import plugins.WebOfTrust.WebOfTrust;
import plugins.WebOfTrust.exceptions.InvalidParameterException;
import plugins.WebOfTrust.exceptions.UnknownIdentityException;
import plugins.WebOfTrust.exceptions.UnknownPuzzleException;
import plugins.WebOfTrust.util.StopWatch;

import com.db4o.ObjectSet;

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

	@Test public void testFullIntroductionCycle()
			throws MalformedURLException, InvalidParameterException, UnknownIdentityException,
			       InterruptedException, UnknownPuzzleException {
		
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
		
		// Synchronized to prevent the WoTs from doing stuff while we set up the test environment.
		// synchronized & clone() also as workaround for
		// https://bugs.freenetproject.org/view.php?id=6247
		// Notice: As a consequence of the clone() we will have to re-query the identities from the
		// database before we pass them to other functions which use them for database queries,
		// otherwise db4o will not know the objects' references.
		synchronized(serverWoT) {
		synchronized(clientWoT) {
			// Server Identity must have publishTrustList == true to enable puzzle publishing.
			serverIdentity = serverWoT.createOwnIdentity("serverId", true, null).clone();
			// Disable trust list and hence also puzzle upload for the client to reduce traffic.
			clientIdentity = clientWoT.createOwnIdentity("clientId", false, null).clone();
			
			// Reduce amount of inserted puzzles to speed things up and keep our code simple.
			serverWoT.setPublishIntroductionPuzzles(serverIdentity.getID(), true, 1);
			
			// Create the server identity at the client so the client knows where to download
			// puzzles from.
			clientWoT.createOwnIdentity(serverIdentity.getInsertURI(), "serverId", true, null);
			clientWoT.setPublishIntroductionPuzzles(serverIdentity.getID(), true, 1);
			// Convert it to a non-own Identity so we have to download the puzzles from the remote
			// instance instead of just creating them locally.
			clientWoT.deleteOwnIdentity(serverIdentity.getID());
			Identity serverAtClient = clientWoT.getIdentityByID(serverIdentity.getID());
			assertTrue(serverAtClient.hasContext(INTRODUCTION_CONTEXT));
			assertEquals("1", serverAtClient.getProperty(PUZZLE_COUNT_PROPERTY));
			assertEquals(0, clientStore.getNonOwnCaptchaAmount(false));
			
			// The client ID must trust the server ID so it will actually download the server ID and
			// its puzzles.
			clientWoT.setTrust(clientIdentity.getID(), serverIdentity.getID(), (byte)100, "");
		}}
		
		// Speed up generation / upload of the puzzle.
		server.nextIteration();
		
		final String puzzleID;
		final String puzzleSolution;
		System.out.println("IntroductionClientTest: Waiting for puzzle to be generated...");
		StopWatch generationTime = new StopWatch();
		do {
			synchronized(serverWoT) {
			synchronized(serverStore) {
				if(serverStore.getOwnCatpchaAmount(false) == 1) {
					OwnIdentity requeried = serverWoT.getOwnIdentityByID(serverIdentity.getID());
					IntroductionPuzzle p = serverStore.getByInserter(requeried).get(0);
					puzzleID = p.getID();
					puzzleSolution = p.getSolution();
					break;
				}
			}}
			
			sleep(100);
		} while(true);
		System.out.println("IntroductionClientTest: Puzzle generated! Time: " + generationTime);
		
		System.out.println("IntroductionClientTest: Waiting for puzzle to be up-/downloaded...");
		StopWatch uploadTime = new StopWatch();
		StopWatch downloadTime = new StopWatch();
		boolean uploaded = false;
		boolean downloaded = false;
		do {
			// Check whether the IntroductionPuzzle was uploaded and show the time it took to do so.
			// Notice: We intentionally don't wait for this in a separate loop before waiting for it
			// to be downloaded: Due to redundancy the amount of data to upload is larger than what
			// has to be downloaded, so fred's "upload finished!" callbacks can happen AFTER the
			// remote node's "download finished!" callbacks have already returned.
			if(!uploaded) {
				synchronized(serverWoT) {
				synchronized(serverStore) {
					OwnIntroductionPuzzle p = (OwnIntroductionPuzzle)serverStore.getByID(puzzleID);
					if(p.wasInserted()) {
						uploaded = true;
						System.out.println(
							"IntroductionClientTest: Puzzle uploaded! Time: " + uploadTime);
						
						// Speed up download of the puzzle
						client.nextIteration();
					}
				}}
			}
			
			synchronized(clientWoT) {
			synchronized(clientStore) {
				if(clientStore.getNonOwnCaptchaAmount(false) == 1)
					downloaded = true;
			}}
			
			if(!downloaded)
				sleep(1000);
		} while(!downloaded);
		System.out.println("IntroductionClientTest: Puzzle downloaded! Time: " + downloadTime);
		
		System.out.println("IntroductionClientTest: testFullIntroductionCycle() done! Time: " + t);
		printNodeStatistics();
	}
}
