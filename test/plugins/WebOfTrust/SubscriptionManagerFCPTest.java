/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.junit.Ignore;

import plugins.WebOfTrust.exceptions.DuplicateTrustException;
import plugins.WebOfTrust.exceptions.InvalidParameterException;
import plugins.WebOfTrust.exceptions.NotTrustedException;
import plugins.WebOfTrust.exceptions.UnknownIdentityException;
import plugins.WebOfTrust.ui.fcp.FCPClientReferenceImplementation;
import plugins.WebOfTrust.ui.fcp.FCPClientReferenceImplementation.ChangeSet;
import plugins.WebOfTrust.ui.fcp.FCPClientReferenceImplementation.IdentityParser;
import plugins.WebOfTrust.ui.fcp.FCPClientReferenceImplementation.ScoreParser;
import plugins.WebOfTrust.ui.fcp.FCPClientReferenceImplementation.TrustParser;
import plugins.WebOfTrust.ui.fcp.FCPInterface;
import freenet.node.FSParseException;
import freenet.pluginmanager.PluginNotFoundException;
import freenet.pluginmanager.PluginReplySender;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;

/**
 * @see FCPClientReferenceImplementation This class can do an online test which is similar to this unit test.
 * @author xor (xor@freenetproject.org)
 */
public class SubscriptionManagerFCPTest extends DatabaseBasedTest {

	/**
	 * From the perspective of this unit test, this does NOT send replies and therefore is called "ReplyRECEIVER" instead of ReplySENDER.
	 */
	@Ignore
	static class ReplyReceiver extends PluginReplySender {

		LinkedList<SimpleFieldSet> results = new LinkedList<SimpleFieldSet>();
		
		public ReplyReceiver() {
			super("SubscriptionManagerTest", "SubscriptionManagerTest");
		}

		/**
		 * This is called by the FCP interface to deploy the reply to the sender of the original message.
		 * So in our case this function actually means "receive()", not send.
		 */
		@Override
		public void send(SimpleFieldSet params, Bucket bucket) throws PluginNotFoundException {
			results.addLast(params);
		}
		
		/**
		 * @throws NoSuchElementException If no result is available
		 */
		public SimpleFieldSet getNextResult() {
			return results.removeFirst();
		}
		
		public boolean hasNextResult() {
			return !results.isEmpty();
		}
		
	}
	
	FCPInterface mFCPInterface;
	ReplyReceiver mReplyReceiver;
	
	/**
	 * Sends the given {@link SimpleFieldSet} to the FCP interface of {@link DatabaseBasedTest#mWoT}
	 * You can obtain the result(s) by <code>mReplySender.getNextResult();</code>
	 */
	void fcpCall(final SimpleFieldSet params) {
		mFCPInterface.handle(mReplyReceiver, params, null, 0);
	}

	/**
	 * The set of all {@link Identity}s as we have received them from the {@link SubscriptionManager.IdentitiesSubscription}.
	 * Is compared to the actual contents of the {@link WebOfTrust} database at the end of the test.
	 * 
	 * Key = {@link Identity#getID()} 
	 */
	HashMap<String, Identity> mReceivedIdentities;
	
	/**
	 * The set of all {@link Trust}s as we have received them from the {@link SubscriptionManager.TrustsSubscription}.
	 * Is compared to the actual contents of the {@link WebOfTrust} database at the end of the test.
	 * 
	 * Key = {@link Trust#getID()} 
	 */
	HashMap<String, Trust> mReceivedTrusts;
	
	/**
	 * The set of all {@link Score}s as we have received them from the {@link SubscriptionManager.ScoresSubscription}.
	 * Is compared to the actual contents of the {@link WebOfTrust} database at the end of the test.
	 * 
	 * Key = {@link Score#getID()} 
	 */
	HashMap<String, Score> mReceivedScores;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		mFCPInterface = mWoT.getFCPInterface();
		mReplyReceiver = new ReplyReceiver();
		
		mReceivedIdentities = new HashMap<String, Identity>();
		mReceivedTrusts = new HashMap<String, Trust>();
		mReceivedScores = new HashMap<String, Score>();
	}
	
	public void testSubscribeUnsubscribe() throws FSParseException {
		testSubscribeUnsubscribe("Identities");
		testSubscribeUnsubscribe("Trusts");
		testSubscribeUnsubscribe("Scores");
	}
	
	void testSubscribeUnsubscribe(String type) throws FSParseException {
		final String id = testSubscribeTo(type); // We are subscribed now.
		
		final SimpleFieldSet sfs = new SimpleFieldSet(true);
		sfs.putOverwrite("Message", "Unsubscribe");
		sfs.putOverwrite("Subscription", id);
		fcpCall(sfs);
		
		// Final reply message is the full set of all objects of the type the client was interested in so the client can validated whether
		// event-notifications has sent him everything properly.
		final SimpleFieldSet synchronization = mReplyReceiver.getNextResult();
		assertEquals(type, synchronization.get("Message"));
		assertEquals("0", synchronization.get("Amount")); // No identities/trusts/scores stored yet		

		// Second reply message is the confirmation of the unsubscription
		final SimpleFieldSet subscription = mReplyReceiver.getNextResult();
		assertEquals("Unsubscribed", subscription.get("Message"));
		assertEquals(type, subscription.get("From"));
		assertEquals(id, subscription.get("Subscription"));
		assertFalse(mReplyReceiver.hasNextResult());
	}
	
	String testSubscribeTo(String type) throws FSParseException {
		final SimpleFieldSet sfs = new SimpleFieldSet(true);
		sfs.putOverwrite("Message", "Subscribe");
		sfs.putOverwrite("To", type);
		fcpCall(sfs);
		
		// First reply message is the full set of all objects of the type we are interested in so the client can synchronize its database
		final SimpleFieldSet synchronization = mReplyReceiver.getNextResult();
		assertEquals(type, synchronization.get("Message"));
		assertEquals("0", synchronization.get("Amount")); // No identities/trusts/scores stored yet
		
		// Second reply message is the confirmation of the subscription
		final SimpleFieldSet subscription = mReplyReceiver.getNextResult();
		assertEquals("Subscribed", subscription.get("Message"));
		assertEquals(type, subscription.get("To"));
		final String id = subscription.get("Subscription");
		try {
			UUID.fromString(id);
		} catch(IllegalArgumentException e) {
			fail("Subscription ID is invalid!");
			throw e;
		}
		
		assertFalse(mReplyReceiver.hasNextResult());
		
		// Try to file the same subscription again - should fail because we already are subscribed
		fcpCall(sfs);
		final SimpleFieldSet duplicateSubscriptionMessage = mReplyReceiver.getNextResult();
		assertEquals("Error", duplicateSubscriptionMessage.get("Message"));
		assertEquals("Subscribe", duplicateSubscriptionMessage.get("OriginalMessage"));
		assertEquals("plugins.WebOfTrust.SubscriptionManager$SubscriptionExistsAlreadyException", duplicateSubscriptionMessage.get("Description"));

		assertFalse(mReplyReceiver.hasNextResult());
		
		return id;
	}

	public void testAllRandomized() throws MalformedURLException, InvalidParameterException, FSParseException, DuplicateTrustException, NotTrustedException, UnknownIdentityException {
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
		
		assertEquals(new HashSet<Identity>(mWoT.getAllIdentities()), new HashSet<Identity>(mReceivedIdentities.values()));
		assertEquals(new HashSet<Trust>(mWoT.getAllTrusts()), new HashSet<Trust>(mReceivedTrusts.values()));
		assertEquals(new HashSet<Score>(mWoT.getAllScores()), new HashSet<Score>(mReceivedScores.values()));
		
		doRandomChangesToWOT(eventCount);
		mWoT.getSubscriptionManager().run(); // It has no Ticker so we need to run() it manually
		importNotifications();

		assertEquals(new HashSet<Identity>(mWoT.getAllIdentities()), new HashSet<Identity>(mReceivedIdentities.values()));
		assertEquals(new HashSet<Trust>(mWoT.getAllTrusts()), new HashSet<Trust>(mReceivedTrusts.values()));
		assertEquals(new HashSet<Score>(mWoT.getAllScores()), new HashSet<Score>(mReceivedScores.values()));
		
	}
	
	void subscribeAndSynchronize(final String type) throws FSParseException, MalformedURLException, InvalidParameterException {
		final SimpleFieldSet sfs = new SimpleFieldSet(true);
		sfs.putOverwrite("Message", "Subscribe");
		sfs.putOverwrite("To", type);
		fcpCall(sfs);
		
		// First reply message is the full set of all objects of the type we are interested in so the client can synchronize its database
		importSynchronization(type, mReplyReceiver.getNextResult());
		
		// Second reply message is the confirmation of the subscription
		assertEquals("Subscribed", mReplyReceiver.getNextResult().get("Message"));
		assertFalse(mReplyReceiver.hasNextResult());
	}
	
	void importSynchronization(final String type, SimpleFieldSet synchronization) throws FSParseException, MalformedURLException, InvalidParameterException {
		/**
		 * It is necessary to have this class instead of the following hypothetical function:
		 * <code>void putAllWithDupecheck(final List<Persistent> source, final HashMap<String, Persistent> target)</code>
		 * 
		 * We couldn't shove a HashMap<String, Identity> into target of the hypothetical function even though Identity is a subclass of Persistent:
		 * The reason is that GenericClass<Type> is not a superclass of GenericClass<subtype of Object> in Java:
		 * "Parameterized types are not covariant."
		 */
		@Ignore
		class ReceivedSynchronizationPutter<T extends Persistent> {
			
			void putAll(final List<T> source, final HashMap<String, T> target) {
				for(final T p : source) {
					assertFalse(target.containsKey(p.getID()));
					target.put(p.getID(), p);
				}
			}
		
		}
		
		assertEquals(type, synchronization.get("Message"));
		if(type.equals("Identities")) {
			new ReceivedSynchronizationPutter<Identity>().putAll(new IdentityParser(mWoT).parseSynchronization(synchronization), mReceivedIdentities);
		} else if(type.equals("Trusts")) {
			new ReceivedSynchronizationPutter<Trust>().putAll(new TrustParser(mWoT, mReceivedIdentities).parseSynchronization(synchronization), mReceivedTrusts);
		} else if(type.equals("Scores")) {
			new ReceivedSynchronizationPutter<Score>().putAll(new ScoreParser(mWoT, mReceivedIdentities).parseSynchronization(synchronization), mReceivedScores);
		}
	}
	
	void importNotifications() throws MalformedURLException, FSParseException, InvalidParameterException {
		/**
		 * It is necessary to have this class instead of the following hypothetical function:
		 * <code>void putAll(final List<Persistent> source, final HashMap<String, Persistent> target)</code>
		 * 
		 * We couldn't shove a HashMap<String, Identity> into target of the hypothetical function even though Identity is a subclass of Persistent:
		 * The reason is that GenericClass<Type> is not a superclass of GenericClass<subtype of Object> in Java:
		 * "Parameterized types are not covariant."
		 */
		@Ignore
		class ReceivedNotificationPutter<T extends Persistent> {
			
			void putAll(final ChangeSet<T> changeSet, final HashMap<String, T> target) {
				if(changeSet.beforeChange != null) {
					final T currentBeforeChange = target.get(changeSet.beforeChange.getID());
					assertEquals(currentBeforeChange, changeSet.beforeChange);
					
					if(changeSet.afterChange != null)
						assertFalse(currentBeforeChange.equals(changeSet.afterChange));
				} else {
					assertFalse(target.containsKey(changeSet.afterChange));
				}
				
				if(changeSet.afterChange != null) {
					/* Checked in changeSet already */
					// assertEquals(changeSet.beforeChange.getID(), changeSet.afterChange.getID()); 
					target.put(changeSet.afterChange.getID(), changeSet.afterChange);
				} else
					target.remove(changeSet.beforeChange.getID());
			}
		}
				
		while(mReplyReceiver.hasNextResult()) {
			final SimpleFieldSet notification = mReplyReceiver.getNextResult();
			final String message = notification.get("Message");
			if(message.equals("IdentityChangedNotification")) {
				new ReceivedNotificationPutter<Identity>().putAll(new IdentityParser(mWoT).parseNotification(notification), mReceivedIdentities);
			} else if(message.equals("TrustChangedNotification")) {
				new ReceivedNotificationPutter<Trust>().putAll(new TrustParser(mWoT, mReceivedIdentities).parseNotification(notification), mReceivedTrusts);
			} else if(message.equals("ScoreChangedNotification")) {
				new ReceivedNotificationPutter<Score>().putAll(new ScoreParser(mWoT, mReceivedIdentities).parseNotification(notification), mReceivedScores);
			} else {
				fail("Unknown message type: " + message);
			}
		}
	}


}
