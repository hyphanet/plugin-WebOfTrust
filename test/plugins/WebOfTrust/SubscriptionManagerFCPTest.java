/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.junit.Ignore;

import plugins.WebOfTrust.ui.fcp.FCPInterface;
import freenet.node.FSParseException;
import freenet.pluginmanager.PluginNotFoundException;
import freenet.pluginmanager.PluginReplySender;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;

public class SubscriptionManagerFCPTest extends DatabaseBasedTest {

	@Ignore
	static class MockReplySender extends PluginReplySender {

		LinkedList<SimpleFieldSet> results = new LinkedList<SimpleFieldSet>();
		
		public MockReplySender() {
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
		
	}
	
	FCPInterface mFCPInterface;
	MockReplySender mReplySender;
	
	@Override
	protected void setUp() throws Exception {
		super.setUp();
		mFCPInterface = mWoT.getFCPInterface();
		mReplySender = new MockReplySender();
	}
	
	/**
	 * Sends the given {@link SimpleFieldSet} to the FCP interface of {@link DatabaseBasedTest#mWoT}
	 * You can obtain the result(s) by <code>mReplySender.getNextResult();</code>
	 */
	void fcpCall(final SimpleFieldSet params) {
		mFCPInterface.handle(mReplySender, params, null, 0);
	}
	
	
	
	void testSubscribeTo(String type) throws FSParseException {
		final SimpleFieldSet sfs = new SimpleFieldSet(true);
		sfs.putOverwrite("Message", "Subscribe");
		sfs.putOverwrite("To", type);
		fcpCall(sfs);
		
		// First reply message is the full set of all objects of the type we are interested in so the client can synchronize its database
		final SimpleFieldSet synchronization = mReplySender.getNextResult();
		assertEquals(type, synchronization.get("Message"));
		assertEquals("0", synchronization.get("Amount")); // No identities/trusts/scores stored yet
		
		// Second reply message is the confirmation of the subscription
		final SimpleFieldSet subscription = mReplySender.getNextResult();
		assertEquals("Subscribed", subscription.get("Message"));
		try {
			UUID.fromString(subscription.get("Subscription"));
		} catch(IllegalArgumentException e) {
			fail("Subscription ID is invalid!");
			throw e;
		}
		
		assertTrue(mReplySender.results.isEmpty());
		
		// Try to file the same subscription again - should fail because we already are subscribed
		fcpCall(sfs);
		final SimpleFieldSet duplicateSubscriptionMessage = mReplySender.getNextResult();
		assertEquals("Error", duplicateSubscriptionMessage.get("Message"));
		assertEquals("Subscribe", duplicateSubscriptionMessage.get("OriginalMessage"));
		assertEquals("plugins.WebOfTrust.SubscriptionManager$SubscriptionExistsAlreadyException", duplicateSubscriptionMessage.get("Description"));

		assertTrue(mReplySender.results.isEmpty());
	}
	
	public void testSubscribe() throws FSParseException {
		testSubscribeTo("Identities");
		testSubscribeTo("Trusts");
		testSubscribeTo("Scores");
	}

}
