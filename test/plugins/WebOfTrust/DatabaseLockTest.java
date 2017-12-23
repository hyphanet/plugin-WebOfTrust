/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import static org.junit.Assert.*;

import org.junit.Test;

import freenet.pluginmanager.PluginInfoWrapper;
import freenet.pluginmanager.PluginManager;

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
		// We will start the WoT instances ourselves to ensure the parent class does not try to
		// query stuff from their database while it is opened by multiple instances as that may
		// cause unexpected breakage.
		return 0;
	}

	@Override public boolean shouldTerminateAllWoTThreads() {
		return false;
	}

	@Test public void test() {
		PluginManager pm = getNodes()[0].getPluginManager();
		WebOfTrust firstWoT = (WebOfTrust)pm.startPluginFile(WOT_JAR_FILE, false).getPlugin();
		// Prevent concurrent access to the multiple instances of the database we will open,
		// could cause random breakage which disturbs the test
		firstWoT.terminateSubsystemThreads();
		PluginInfoWrapper piw = pm.getPluginInfoByFileName(WOT_JAR_FILE);
		assertNotNull(piw);
		
		assertTrue(pm.isPluginLoaded(WebOfTrust.class.getName()));
		// Allow us to load a second copy of WoT on the same database by removing the first from
		// fred's plugin table so it won't refuse loading a second copy.
		pm.removePlugin(piw);
		assertFalse(pm.isPluginLoaded(WebOfTrust.class.getName()));
		
		WebOfTrust secondWoT = (WebOfTrust)pm.startPluginFile(WOT_JAR_FILE, false).getPlugin();
		if(secondWoT != null) {
			assertFalse(firstWoT.getDatabase().isClosed());
			assertFalse(secondWoT.getDatabase().isClosed());
			assertEquals(firstWoT.getDatabase().toString(), secondWoT.getDatabase().toString());
			fail("Was able to load second WoT instance on same db file: "
					+ firstWoT.getDatabase().toString());
		} else {
			// Success
		}
	}

}
