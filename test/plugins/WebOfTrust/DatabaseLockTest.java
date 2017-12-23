/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import org.junit.Test;

/**
 * Tests whether WoT refuses to run upon a database which is already opened by another instance, and
 * by currently always failing demonstrates that it currently does NOT due to a potential db4o bug.
 * 
 * At first glance once could imagine that locking isn't needed in practice because the node
 * prevents loading multiple instances of the same plugin, but it may be relevant indeed:
 * If {@link WebOfTrust#terminate()} has bugs which prevent the database from being closed then
 * restarting WoT (e.g. due to fred updating it) may cause the database to be opened twice.
 * 
 * In fact {@link AbstractMultiNodeTestSelfTest#testTerminate()} already ran into this issue:
 * As of commit 90b4538987898363e73775aeeedde1d2a6f8585c it doesn't detect that its version of
 * {@link WebOfTrust#terminate()} doesn't close the database like it should due to
 * {@link WebOfTrust#terminateSubsystemThreads()} having the bug that it sets
 * {@link WebOfTrust#mIsTerminated} which prevents subsequent terminate() from closing the database
 * (due to the assert(!mIsTerminated) at the beginning ending the function by throwing).
 * testTerminate() would detect this if subsequent startup of WoT failed due to the database
 * being locked, but it doesn't fail. */
public final class DatabaseLockTest extends AbstractMultiNodeTest {

	@Override public int getNodeCount() {
		return 1;
	}

	@Override public int getWoTCount() {
		return 1;
	}

	@Override public boolean shouldTerminateAllWoTThreads() {
		return false;
	}

	@Test public void test() {
		// FIXME: Implement
	}

}
