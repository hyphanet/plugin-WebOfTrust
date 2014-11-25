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

import plugins.WebOfTrust.SubscriptionManager.Notification;
import plugins.WebOfTrust.exceptions.DuplicateTrustException;
import plugins.WebOfTrust.exceptions.InvalidParameterException;
import plugins.WebOfTrust.exceptions.NotTrustedException;
import plugins.WebOfTrust.exceptions.UnknownIdentityException;
import plugins.WebOfTrust.ui.fcp.FCPClientReferenceImplementation;
import plugins.WebOfTrust.ui.fcp.FCPClientReferenceImplementation.ChangeSet;
import plugins.WebOfTrust.ui.fcp.FCPClientReferenceImplementation.FCPEventSourceContainerParser;
import plugins.WebOfTrust.ui.fcp.FCPClientReferenceImplementation.IdentityParser;
import plugins.WebOfTrust.ui.fcp.FCPClientReferenceImplementation.ScoreParser;
import plugins.WebOfTrust.ui.fcp.FCPClientReferenceImplementation.SubscriptionType;
import plugins.WebOfTrust.ui.fcp.FCPClientReferenceImplementation.TrustParser;
import freenet.node.FSParseException;
import freenet.node.fcp.FCPPluginClient;
import freenet.node.fcp.FCPPluginClient.SendDirection;
import freenet.pluginmanager.FredPluginFCPMessageHandler;
import freenet.pluginmanager.FredPluginFCPMessageHandler.FCPPluginMessage;
import freenet.support.SimpleFieldSet;

/**
 * TODO: New test: For each type of {@link Notification}, check whether it gets resent properly if
 * we reply with a {@link FCPPluginMessage#success}==false.
 * 
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

		private final LinkedList<FCPPluginMessage> mResults = new LinkedList<>();

		/**
		 * Called by fred to handle messages from WOT's FCP server.
		 */
		@Override
        public FCPPluginMessage handlePluginFCPMessage(FCPPluginClient client,
                FCPPluginMessage message) {
		    
		    mResults.addLast(message);

		    // The fred code which calls this handler expects a reply to be returned to indicate
		    // success so the sendSynchronous() calls in WOT can return.
			return message.isReplyMessage() ? null 
			    : FCPPluginMessage.constructSuccessReply(message);
		}
		
		/**
		 * @throws NoSuchElementException If no result is available
		 */
		public FCPPluginMessage getNextResult() {
			return mResults.removeFirst();
		}
		
		public void restoreNextResult(FCPPluginMessage message) {
		    mResults.addFirst(message);
		}
		
		public boolean hasNextResult() {
			return !mResults.isEmpty();
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
	    mReplyReceiver.handlePluginFCPMessage(mClient, reply);
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
	    
	    // Subscribe+Unsubscribe twice to check if unsubscribe leaves everything in a clean state
	    for(int i=0; i < 2; ++i) {
    		testSubscribeUnsubscribe("Identities");
    		testSubscribeUnsubscribe("Trusts");
    		testSubscribeUnsubscribe("Scores");
	    }
	}
	
	void testSubscribeUnsubscribe(String type)
	        throws FSParseException, IOException, InterruptedException {
	    
		final String id = testSubscribeTo(type); // We are subscribed now.
		
		testUnsubscribeFrom(type, id);
	}

    void testUnsubscribeFrom(String type, final String id)
            throws IOException, InterruptedException {
        
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

		// First message from WOT is the confirmation of the subscription
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
		
		mWebOfTrust.getSubscriptionManager().run(); // Has no Ticker so we need to run() it manually
		
	    // Second message is the "BeginSynchronizationEvent"
        final FCPPluginMessage beginSync = mReplyReceiver.getNextResult();
        // Validate the expected case of it not being a reply message so we don't have to check the
        // beginSync.success / errorCode / errorMessage as they will be null for non-reply messages.
        assertEquals(false, beginSync.isReplyMessage());
        assertEquals("BeginSynchronizationEvent", beginSync.params.get("Message"));
        assertEquals(type, beginSync.params.get("To"));
        final UUID versionID = UUID.fromString(beginSync.params.get("VersionID"));
        
        // Third and following messages in theory would be ObjectChangedEvents as containers for the
        // synchronization.
        // But as no Identitys/Trusts/Scores are stored yet, there shoudln't be any
        // ObjectChangedEvent between the BeginSynchronizationEvent and the EndSynchronizationEvent.
        // So the third message will be the "EndSynchronizationEvent" already.
        
        final FCPPluginMessage endSync = mReplyReceiver.getNextResult();
        assertEquals(false, endSync.isReplyMessage());
        assertEquals("EndSynchronizationEvent", endSync.params.get("Message"));
        assertEquals(type, endSync.params.get("To"));
        assertEquals(versionID.toString(), endSync.params.get("VersionID"));
        
        // No further messages should arrive by now.
		assertFalse(mReplyReceiver.hasNextResult());
		
		// Try to file the same subscription again - should fail because we already are subscribed
		fcpCall(sfs);
		final FCPPluginMessage duplicateSubscriptionMessage = mReplyReceiver.getNextResult();
		assertEquals(false, duplicateSubscriptionMessage.success);
		assertEquals("Error", duplicateSubscriptionMessage.params.get("Message"));
		assertEquals("Subscribe", duplicateSubscriptionMessage.params.get("OriginalMessage"));
		assertEquals("SubscriptionExistsAlready", duplicateSubscriptionMessage.errorCode);
		assert duplicateSubscriptionMessage.errorMessage == null
		    : "errorMessage does not have to be set by WOT because the Subscribe FCP "
            + "function is not something which is typically used by UI.";
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
		importObjectChangedEvents();
        assertFalse(mReplyReceiver.hasNextResult());

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
		
        // First message from WOT is the confirmation of the subscription
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
        
        mWebOfTrust.getSubscriptionManager().run(); // Has no Ticker so we need to run() it manually
        
        // Second message is the "BeginSynchronizationEvent"
        final FCPPluginMessage beginSync = mReplyReceiver.getNextResult();
        // Validate the expected case of it not being a reply message so we don't have to check the
        // beginSync.success / errorCode / errorMessage as they will be null for non-reply messages.
        assertEquals(false, beginSync.isReplyMessage());
        assertEquals("BeginSynchronizationEvent", beginSync.params.get("Message"));
        assertEquals(type, beginSync.params.get("To"));
        final UUID versionID = UUID.fromString(beginSync.params.get("VersionID"));
        
        // Following messages are the full set of all objects of the type we are interested in so
        // the client can synchronize its database.
        importSynchronization(SubscriptionType.valueOf(type), versionID);
        
        // Final message is "EndSynchronizationEvent".
        
        final FCPPluginMessage endSync = mReplyReceiver.getNextResult();
        assertEquals(false, endSync.isReplyMessage());
        assertEquals("EndSynchronizationEvent", endSync.params.get("Message"));
        assertEquals(type, endSync.params.get("To"));
        assertEquals(versionID.toString(), endSync.params.get("VersionID"));
        
        // No further messages should arrive by now.
        assertFalse(mReplyReceiver.hasNextResult());
	}
	
    @SuppressWarnings("unchecked")
    void importSynchronization(final SubscriptionType type, final UUID versionID)
	        throws MalformedURLException, FSParseException, InvalidParameterException {
	    
	    final FCPEventSourceContainerParser<? extends EventSource> parser;
	    final List<? extends EventSource> result = new LinkedList<>();
	    final List<EventSource> resultCasted = (LinkedList<EventSource>)result;
	    
	    switch(type) {
            case Identities: parser = new IdentityParser(mWebOfTrust); break;
            case Trusts:     parser = new TrustParser(mWebOfTrust, mReceivedIdentities); break;
            case Scores:     parser = new ScoreParser(mWebOfTrust, mReceivedIdentities); break;
            default: throw new UnsupportedOperationException("Unknown SubscriptionType: " + type);
	    }

	    do {
	        assertTrue(mReplyReceiver.hasNextResult());
	        
	        final FCPPluginMessage notificationMessage = mReplyReceiver.getNextResult();
            assertEquals("Notifications should not be sent as reply messages.",
                false, notificationMessage.isReplyMessage());      
            
            final SimpleFieldSet notification = notificationMessage.params;
            final String message = notification.get("Message");
            
            if(message.equals("EndSynchronizationEvent")) {
                // We're not interested in processing the EndSynchronizationEvent here, so we
                // push it back and let the caller deal with it.
                mReplyReceiver.restoreNextResult(notificationMessage);
                break;
            }
            
            assertEquals("ObjectChangedEvent", message);
            assertEquals(type.name(), notification.get("SubscriptionType"));
            
            ChangeSet<? extends EventSource> changeSet
                = parser.parseObjectChangedEvent(notification);
            
            assertEquals(null, changeSet.beforeChange);
            assertEquals(versionID, changeSet.afterChange.getVersionID());
            
            resultCasted.add(changeSet.afterChange);
	    } while(true);
	    
		switch(type) {
            case Identities: putSynchronization((List<Identity>)result, mReceivedIdentities); break;
            case Trusts:     putSynchronization((List<Trust>)result, mReceivedTrusts); break;
            case Scores:     putSynchronization((List<Score>)result, mReceivedScores); break;
            default: throw new UnsupportedOperationException("Unknown SubscriptionType: " + type);
		}
	}
	
	<T extends Persistent> void putSynchronization(final List<T> source, final HashMap<String, T> target) {
		assertEquals(0, target.size());
		for(final T p : source) {
			final T previous = target.put(p.getID(), p);
			assertEquals(null, previous);
		}
	}

	void importObjectChangedEvents()
	        throws MalformedURLException, FSParseException, InvalidParameterException {
	    
		while(mReplyReceiver.hasNextResult()) {
		    final FCPPluginMessage eventMessage = mReplyReceiver.getNextResult();
		    
		    assertEquals("Notifications should not be sent as reply messages.",
		        false, eventMessage.isReplyMessage());
		    // We don't have to check the notificationMessage.success / errorCode / errorMessage as
		    // they will be null for non-reply messages.
		    
			final SimpleFieldSet event = eventMessage.params;
			final String message = event.get("Message");
			assertEquals("ObjectChangedEvent", message);
			
		    SubscriptionType type
		        = SubscriptionType.valueOf(event.get("SubscriptionType"));
		    
		    switch(type) {
                case Identities:
                    putObjectChangedEvent(
                        new IdentityParser(mWebOfTrust)
                            .parseObjectChangedEvent(event), mReceivedIdentities);
                    break;
                case Trusts:
                    putObjectChangedEvent(
                        new TrustParser(mWebOfTrust, mReceivedIdentities)
                            .parseObjectChangedEvent(event), mReceivedTrusts);
                    break;
                case Scores:
                    putObjectChangedEvent(
                        new ScoreParser(mWebOfTrust, mReceivedIdentities)
                            .parseObjectChangedEvent(event), mReceivedScores);
                    break;
                default:
                    fail("Unknown SubscriptionType: " + type);
		    }
		}
	}
	
	<T extends EventSource> void putObjectChangedEvent(
	        final ChangeSet<T> changeSet, final HashMap<String, T> target) {
	    
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
