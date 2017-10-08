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
		
		// Create the server Identity which will upload a puzzle.
		// Don't create the client Identity yet: That would result in the client trying to download
		// puzzles immediately, which would fail because the puzzles of the server aren't uploaded
		// yet, and the IntroductionClient puzzle download loop has a MINIMAL_SLEEP_TIME of 10
		// minutes, so retrying once they are uploaded would take >= 10 minutes. 
		//
		// Synchronized to prevent the WoTs from doing stuff while we set up the test environment.
		// synchronized & clone() also as workaround for
		// https://bugs.freenetproject.org/view.php?id=6247
		//
		// Notice: As a consequence of the clone() we will have to re-query the identities from the
		// database before we pass them to other functions which use them for database queries,
		// otherwise db4o will not know the objects' references.
		synchronized(serverWoT) {
			// Server Identity must have publishTrustList == true to enable puzzle publishing.
			serverIdentity = serverWoT.createOwnIdentity("serverId", true, null).clone();
			
			// Reduce amount of inserted puzzles to speed things up and keep our code simple.
			serverWoT.setPublishIntroductionPuzzles(serverIdentity.getID(), true, 1);
		}
		
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
		
		System.out.println("IntroductionClientTest: Waiting for puzzle to be uploaded...");
		StopWatch uploadTime = new StopWatch();
		do {
			// Check whether the IntroductionPuzzle was uploaded and show the time it took to do so.
			synchronized(serverWoT) {
			synchronized(serverStore) {
				OwnIntroductionPuzzle p = (OwnIntroductionPuzzle)serverStore.getByID(puzzleID);
				if(p.wasInserted())
					break;
			}}
			
			sleep(1000);
		} while(true);
		System.out.println("IntroductionClientTest: Puzzle uploaded! Time: " + uploadTime);
		
		// Create the client Identity which will download the puzzle.
		synchronized(clientWoT) {
		synchronized(clientStore) {
			// Disable trust list and hence also puzzle upload for the client to reduce traffic.
			clientIdentity = clientWoT.createOwnIdentity("clientId", false, null).clone();
			
			// Create the server identity at the client so the client knows where to download
			// puzzles from.
			clientWoT.createOwnIdentity(serverIdentity.getInsertURI(), "serverId", true, null);
			clientWoT.setPublishIntroductionPuzzles(serverIdentity.getID(), true, 1);
			// Convert it to a non-own Identity so we have to download the puzzles from the remote
			// instance instead of just creating them locally.
			clientWoT.deleteOwnIdentity(serverIdentity.getID());
			Identity serverIdentityAtClient = clientWoT.getIdentityByID(serverIdentity.getID());
			assertTrue(serverIdentityAtClient.hasContext(INTRODUCTION_CONTEXT));
			assertEquals("1", serverIdentityAtClient.getProperty(PUZZLE_COUNT_PROPERTY));
			assertEquals(0, clientStore.getNonOwnCaptchaAmount(false));
			
			// The client ID must trust the server ID so it will actually download the server ID and
			// its puzzles.
			clientWoT.setTrust(clientIdentity.getID(), serverIdentity.getID(), (byte)100, "");
		}}
		
		// Not necessary: createOwnIdentity() does it.
		/*
			// Speed up generation / upload of the puzzle.
			client.nextIteration();
		*/
		
		System.out.println("IntroductionClientTest: Waiting for puzzle to downloaded...");
		StopWatch downloadTime = new StopWatch();
		do {
			synchronized(clientWoT) {
			synchronized(clientStore) {
				if(clientStore.getNonOwnCaptchaAmount(false) == 1)
					break;
			}}
			
			sleep(1000);
		} while(true);
		System.out.println("IntroductionClientTest: Puzzle downloaded! Time: " + downloadTime);
		
		// Self-test: Our central test is whether we can get this to not throw anymore by solving
		// the puzzle and thus transferring knowledge of the Identity's existence across the
		// network. For now it shouldn't be known yet as we haven't solved the puzzle.
		synchronized(serverWoT) {
			try {
				serverWoT.getIdentityByID(clientIdentity.getID());
				fail();
			}
			catch(UnknownIdentityException e) { }
		}
		
		synchronized(clientWoT) {
		synchronized(clientStore) {
			System.out.println("IntroductionClientTest: Solving puzzle.");
			client.solvePuzzle(clientIdentity.getID(), puzzleID, puzzleSolution);
			assertEquals(0, clientStore.getNonOwnCaptchaAmount(false));
			assertEquals(1, clientStore.getNonOwnCaptchaAmount(true));
			assertEquals(1, clientStore.getUninsertedSolvedPuzzles().size());
		}}
		
		System.out.println("IntroductionClientTest: testFullIntroductionCycle() done! Time: " + t);
		printNodeStatistics();
	}
}
