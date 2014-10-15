/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import static org.junit.Assert.*;

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import plugins.WebOfTrust.exceptions.DuplicateTrustException;
import plugins.WebOfTrust.exceptions.InvalidParameterException;
import plugins.WebOfTrust.exceptions.NotTrustedException;
import plugins.WebOfTrust.exceptions.UnknownIdentityException;
import plugins.WebOfTrust.ui.fcp.FCPClientReferenceImplementation;
import plugins.WebOfTrust.ui.fcp.FCPClientReferenceImplementation.ChangeSet;
import plugins.WebOfTrust.ui.fcp.FCPClientReferenceImplementation.IdentityParser;
import plugins.WebOfTrust.ui.fcp.FCPClientReferenceImplementation.ScoreParser;
import plugins.WebOfTrust.ui.fcp.FCPClientReferenceImplementation.TrustParser;
import freenet.node.FSParseException;
import freenet.node.fcp.FCPPluginClient;
import freenet.node.fcp.FCPPluginClient.SendDirection;
import freenet.pluginmanager.FredPluginFCPMessageHandler;
import freenet.pluginmanager.FredPluginFCPMessageHandler.FCPPluginMessage;
import freenet.support.SimpleFieldSet;

/**
 * @see FCPClientReferenceImplementation This class can do an online test which is similar to this unit test.
 * @author xor (xor@freenetproject.org)
 */
public final class SubscriptionManagerFCPTest extends AbstractFullNodeTest {

	/**
	 * This test acts as a client to the WOT FCP server subscription code.
	 * Thus, the class ReplyReceiver here implements the client-side FCP message handler and stores
	 * the messages received from the WOT FCP server. 
	 */
	@Ignore
	static class ReplyReceiver implements FredPluginFCPMessageHandler.ClientSideFCPMessageHandler {

		LinkedList<FCPPluginMessage> results = new LinkedList<FCPPluginMessage>();

		/**
		 * Called by fred to handle messages from WOT's FCP server.
		 */
		@Override
        public FCPPluginMessage handlePluginFCPMessage(FCPPluginClient client,
                FCPPluginMessage message) {
		    
		    results.addLast(message);

		    // The fred code which calls this handler expects a reply to be returned to indicate
		    // success so the sendSynchronous() calls in WOT can return.
			return message.isReplyMessage() ? null 
			    : FCPPluginMessage.constructSuccessReply(message);
		}
		
		/**
		 * @throws NoSuchElementException If no result is available
		 */
		public FCPPluginMessage getNextResult() {
			return results.removeFirst();
		}
		
		public boolean hasNextResult() {
			return !results.isEmpty();
		}

	}
	
	ReplyReceiver mReplyReceiver = new ReplyReceiver();
	
	FCPPluginClient mClient;

    @Before
    public void setUpClient() throws Exception {
        mClient = mWebOfTrust.getPluginRespirator()
            .connectToOtherPlugin(FCPClientReferenceImplementation.WOT_FCP_NAME, mReplyReceiver);
    }

	/**
	 * Sends the given {@link SimpleFieldSet} to the FCP interface of {@link AbstractJUnit3BaseTest#mWoT}
	 * You can obtain the result(s) by <code>mReplySender.getNextResult();</code>
	 */
	void fcpCall(final SimpleFieldSet params) throws IOException, InterruptedException {
	    FCPPluginMessage reply = mClient.sendSynchronous(SendDirection.ToServer,
	        FCPPluginMessage.construct(params, null), TimeUnit.SECONDS.toNanos(10));
	    
	    // In opposite to send(), the reply to sendSynchronous() is NOT passed to the
	    // FredPluginFCPMessageHandler, so the mReplyReceiver won't have received it, and we have to
	    // add it manually to its list.
	    mReplyReceiver.results.addLast(reply);
	}

	/**
	 * The set of all {@link Identity}s as we have received them from the {@link SubscriptionManager.IdentitiesSubscription}.
	 * Is compared to the actual contents of the {@link WebOfTrust} database at the end of the test.
	 * 
	 * Key = {@link Identity#getID()} 
	 */
	HashMap<String, Identity> mReceivedIdentities = new HashMap<String, Identity>();
	
	/**
	 * The set of all {@link Trust}s as we have received them from the {@link SubscriptionManager.TrustsSubscription}.
	 * Is compared to the actual contents of the {@link WebOfTrust} database at the end of the test.
	 * 
	 * Key = {@link Trust#getID()} 
	 */
	HashMap<String, Trust> mReceivedTrusts = new HashMap<String, Trust>();
	
	/**
	 * The set of all {@link Score}s as we have received them from the {@link SubscriptionManager.ScoresSubscription}.
	 * Is compared to the actual contents of the {@link WebOfTrust} database at the end of the test.
	 * 
	 * Key = {@link Score#getID()} 
	 */
	HashMap<String, Score> mReceivedScores = new HashMap<String, Score>();
	
	@Before public void setUpWOT() throws UnknownIdentityException, MalformedURLException {
	    // Delete the seed identities since the test assumes the WOT database to be empty.
	    deleteSeedIdentities();
	}

	@Test public void testSubscribeUnsubscribe()
	        throws FSParseException, IOException, InterruptedException {
	    
		testSubscribeUnsubscribe("Identities");
		testSubscribeUnsubscribe("Trusts");
		testSubscribeUnsubscribe("Scores");
	}
	
	void testSubscribeUnsubscribe(String type)
	        throws FSParseException, IOException, InterruptedException {
	    
		final String id = testSubscribeTo(type); // We are subscribed now.
		
		final SimpleFieldSet sfs = new SimpleFieldSet(true);
		sfs.putOverwrite("Message", "Unsubscribe");
		sfs.putOverwrite("SubscriptionID", id);
		fcpCall(sfs);
		
		// Second reply message is the confirmation of the unsubscription
		final FCPPluginMessage result = mReplyReceiver.getNextResult();
		assertEquals(true, result.success);
		assertEquals("Unsubscribed", result.params.get("Message"));
		assertEquals(type, result.params.get("From"));
		assertEquals(id, result.params.get("SubscriptionID"));
		assertFalse(mReplyReceiver.hasNextResult());
	}
	
	String testSubscribeTo(String type)
	        throws FSParseException, IOException, InterruptedException {
	    
		final SimpleFieldSet sfs = new SimpleFieldSet(true);
		sfs.putOverwrite("Message", "Subscribe");
		sfs.putOverwrite("To", type);
		fcpCall(sfs);
		
		// First reply message is the full set of all objects of the type we are interested in so the client can synchronize its database
		final FCPPluginMessage synchronization = mReplyReceiver.getNextResult();
		// The first "reply" message is not an actual reply message. See the JavaDoc of
		// FCPInterface.handleSubscribe()).
		// So we don't have to check the synchronization.success / errorCode / errorMessage as they
		// will be null for non-reply messages.
		assertEquals(false, synchronization.isReplyMessage());
		assertEquals(type, synchronization.params.get("Message"));
		assertEquals("No identities/trusts/scores stored yet",
		    "0", synchronization.params.get(type + ".Amount"));

		// Second reply message is the confirmation of the subscription
		final FCPPluginMessage subscription = mReplyReceiver.getNextResult();
		assertEquals(true, subscription.success);
		assertEquals("Subscribed", subscription.params.get("Message"));
		assertEquals(type, subscription.params.get("To"));
		final String id = subscription.params.get("SubscriptionID");
		try {
			UUID.fromString(id);
		} catch(IllegalArgumentException e) {
			fail("SubscriptionID is invalid!");
			throw e;
		}
		
		assertFalse(mReplyReceiver.hasNextResult());
		
		// Try to file the same subscription again - should fail because we already are subscribed
		fcpCall(sfs);
		final FCPPluginMessage duplicateSubscriptionMessage = mReplyReceiver.getNextResult();
		assertEquals(false, duplicateSubscriptionMessage.success);
		assertEquals("Error", duplicateSubscriptionMessage.params.get("Message"));
		assertEquals("Subscribe", duplicateSubscriptionMessage.params.get("OriginalMessage"));
		assertEquals("plugins.WebOfTrust.SubscriptionManager$SubscriptionExistsAlreadyException",
		    duplicateSubscriptionMessage.params.get("Description"));

		assertFalse(mReplyReceiver.hasNextResult());
		
		return id;
	}

	@Test public void testAllRandomized()
	        throws InvalidParameterException, FSParseException, DuplicateTrustException,
	        NotTrustedException, UnknownIdentityException, IOException, InterruptedException {
	    
		final int initialOwnIdentityCount = 1;
		final int initialIdentityCount = 100;
		final int initialTrustCount = (initialIdentityCount*initialIdentityCount) / 10; // A complete graph would be identityCount² trust values.
		final int eventCount = 100;
		
		// Random trust graph setup...
		final ArrayList<Identity> identities = addRandomIdentities(initialIdentityCount);
		
		// At least one own identity needs to exist to ensure that scores are computed.
		identities.addAll(addRandomOwnIdentities(initialOwnIdentityCount));
		
		addRandomTrustValues(identities, initialTrustCount);

		/* Initial test data is set up */
		subscribeAndSynchronize("Identities");
		subscribeAndSynchronize("Trusts");
		subscribeAndSynchronize("Scores");
		
		assertEquals(new HashSet<Identity>(mWebOfTrust.getAllIdentities()),
		             new HashSet<Identity>(mReceivedIdentities.values()));
		
		assertEquals(new HashSet<Trust>(mWebOfTrust.getAllTrusts()),
		             new HashSet<Trust>(mReceivedTrusts.values()));

		assertEquals(new HashSet<Score>(mWebOfTrust.getAllScores()),
		             new HashSet<Score>(mReceivedScores.values()));
		
		doRandomChangesToWOT(eventCount);
		mWebOfTrust.getSubscriptionManager().run(); // Has no Ticker so we need to run() it manually
		importNotifications();

		assertEquals(new HashSet<Identity>(mWebOfTrust.getAllIdentities()),
		             new HashSet<Identity>(mReceivedIdentities.values()));
		
		assertEquals(new HashSet<Trust>(mWebOfTrust.getAllTrusts()),
		             new HashSet<Trust>(mReceivedTrusts.values()));
		
		assertEquals(new HashSet<Score>(mWebOfTrust.getAllScores()),
		             new HashSet<Score>(mReceivedScores.values()));
		
	}
	
	void subscribeAndSynchronize(final String type)
	        throws FSParseException, InvalidParameterException, IOException, InterruptedException {
	    
		final SimpleFieldSet sfs = new SimpleFieldSet(true);
		sfs.putOverwrite("Message", "Subscribe");
		sfs.putOverwrite("To", type);
		fcpCall(sfs);
		
		// First reply message is the full set of all objects of the type we are interested in so the client can synchronize its database
		importSynchronization(type, mReplyReceiver.getNextResult().params);
		
		// Second reply message is the confirmation of the subscription
		assertEquals("Subscribed", mReplyReceiver.getNextResult().params.get("Message"));
		assertFalse(mReplyReceiver.hasNextResult());
	}
	
	void importSynchronization(final String type, SimpleFieldSet synchronization)
	        throws FSParseException, MalformedURLException, InvalidParameterException {
	    
		assertEquals(type, synchronization.get("Message"));
		if(type.equals("Identities")) {
			putSynchronization(
			    new IdentityParser(mWebOfTrust).parseSynchronization(synchronization),
			    mReceivedIdentities);
		} else if(type.equals("Trusts")) {
			putSynchronization(
			    new TrustParser(mWebOfTrust, mReceivedIdentities)
			        .parseSynchronization(synchronization),
			    mReceivedTrusts);
		} else if(type.equals("Scores")) {
			putSynchronization(
			    new ScoreParser(mWebOfTrust, mReceivedIdentities)
			        .parseSynchronization(synchronization),
			    mReceivedScores);
		}
	}
	
	<T extends Persistent> void putSynchronization(final List<T> source, final HashMap<String, T> target) {
		assertEquals(0, target.size());
		for(final T p : source) {
			assertFalse(target.containsKey(p.getID()));
			target.put(p.getID(), p);
		}
	}

	void importNotifications() throws MalformedURLException, FSParseException, InvalidParameterException {

	
		while(mReplyReceiver.hasNextResult()) {
		    final FCPPluginMessage notificationMessage = mReplyReceiver.getNextResult();
		    
		    assertEquals("Notifications should not be sent as reply messages.",
		        false, notificationMessage.isReplyMessage());		    
		    // We don't have to check the notificationMessage.success / errorCode / errorMessage as
		    // they will be null for non-reply messages.
		    
			final SimpleFieldSet notification = notificationMessage.params;
			final String message = notification.get("Message");
			if(message.equals("IdentityChangedNotification")) {
				putNotification(
				    new IdentityParser(mWebOfTrust).parseNotification(notification),
				    mReceivedIdentities);
			} else if(message.equals("TrustChangedNotification")) {
				putNotification(
				    new TrustParser(mWebOfTrust, mReceivedIdentities)
				        .parseNotification(notification),
				    mReceivedTrusts);
			} else if(message.equals("ScoreChangedNotification")) {
				putNotification(
				    new ScoreParser(mWebOfTrust, mReceivedIdentities)
				        .parseNotification(notification),
				    mReceivedScores);
			} else {
				fail("Unknown message type: " + message);
			}
		}
	}
	
	<T extends Persistent> void putNotification(final ChangeSet<T> changeSet, final HashMap<String, T> target) {
		if(changeSet.beforeChange != null) {
			final T currentBeforeChange = target.get(changeSet.beforeChange.getID());
			assertEquals(currentBeforeChange, changeSet.beforeChange);
			
			if(changeSet.afterChange != null)
				assertFalse(currentBeforeChange.equals(changeSet.afterChange));
		} else {
			assertFalse(target.containsKey(changeSet.afterChange.getID()));
		}
		
		if(changeSet.afterChange != null) {
			/* Checked in changeSet already */
			// assertEquals(changeSet.beforeChange.getID(), changeSet.afterChange.getID()); 
			target.put(changeSet.afterChange.getID(), changeSet.afterChange);
		} else
			target.remove(changeSet.beforeChange.getID());
	}

}
