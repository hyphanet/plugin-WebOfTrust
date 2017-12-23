/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * Ant's JUnit test runner fails to terminate upon the failure in test(), probably because the
 * Node's shutdown hooks hang.
 * Changing {@link #getNodeCount()} to more than 1 makes the issue go away!?!? */
public final class AbstractMultiNodeTestShutdownSelfTest extends AbstractMultiNodeTest {

	@Override public int getNodeCount() {
		// Changing this to 2 makes the issue go away!?!?
		return 1;
	}

	@Override public int getWoTCount() {
		return 0;
	}

	@Override public boolean shouldTerminateAllWoTThreads() {
		return false;
	}

	@Test public void test() {
		fail("Issue is fixed if Ant JUnit test runner succeeds to terminate after this failure.");
	}

}
