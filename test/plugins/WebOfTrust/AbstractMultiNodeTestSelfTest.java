/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import freenet.node.Node;
import freenet.pluginmanager.FredPlugin;
import freenet.pluginmanager.PluginManager;

/**
 * Self-tests of the abstract test base class {@link AbstractMultiNodeTest}.
 * They are not included in that class because:
 * - otherwise they would be executed multiple times - once for each child class of it.
 * - they assume only a single node is used, so it's easier to base them on AbstractMultiNodeTest's
 *   child class {@link AbstractSingleNodeTest}. */
public final class AbstractMultiNodeTestSelfTest extends AbstractSingleNodeTest {

	/**
	 * Tests whether unloading the WoT plugin using {@link PluginManager#killPlugin(FredPlugin,
	 * long)} works:
	 * - Checks whether the PluginManager reports 0 running plugins afterwards.
	 * - Checks whether {@link WebOfTrust#isTerminated()} reports successful shutdown. */
	@Test
	public final void testTerminate() {
		Node node = getNode();
		WebOfTrust wot = getWebOfTrust();
		
		PluginManager pm = node.getPluginManager();
		
		// Before doing the actual test of killPlugin(...); assertTrue(mWebOfTrust.isTerminated()),
		// we must restart WoT. Instead this would happen:
		// - setUpNode() already called terminate() upon various subsystems of WoT.
		// - When killPlugin() calls WebOfTrust.terminate(), that function will try to terminate()
		//   those subsystems again. This will fail because they are terminated already.
		// - WebOfTrust.terminate() will mark termination as failed due to subsystem termination
		//   failure. Thus, isTerminated() will return false.
		pm.killPlugin(wot, Long.MAX_VALUE);
		// Pointer of AbstractSingleNodeTest. AbstractMultiNodeTest doesn't keep one.
		this.mWebOfTrust = null;
		wot = null;
		assertEquals(0, pm.getPlugins().size());
		wot
			= (WebOfTrust)pm.startPluginFile(WOT_JAR_FILE, false).getPlugin();
		assertEquals(1, pm.getPlugins().size());
		
		// The actual test
		pm.killPlugin(wot, Long.MAX_VALUE);
		assertEquals(0, pm.getPlugins().size());
		assertTrue(wot.isTerminated());
		
		// The @After test AbstractMultiNodeTest.testDatabaseIntegrityAfterTermination() expects the
		// WoT plugin to still be loaded after this test is finished so we need to load it again.
		pm.startPluginFile(WOT_JAR_FILE, false);
	}

}
