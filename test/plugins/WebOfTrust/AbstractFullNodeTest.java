/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import static org.junit.Assert.*;

import java.io.File;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

import freenet.crypt.RandomSource;
import freenet.node.Node;
import freenet.node.NodeInitException;
import freenet.node.NodeStarter;
import freenet.node.NodeStarter.TestNodeParameters;
import freenet.pluginmanager.PluginInfoWrapper;
import freenet.pluginmanager.PluginRespirator;
import freenet.support.Logger.LogLevel;
import freenet.support.LoggerHook.InvalidThresholdException;
import freenet.support.PooledExecutor;

/**
 * A base class for WOT unit tests.<br>
 * As opposed to regular WOT unit tests based upon {@link DatabaseBasedTest}, this test runs the
 * unit tests inside a full Freenet node:<br>
 * WOT is loaded as a regular plugin instead of executing the tests directly without Freenet.<br>
 * <br>
 * 
 * This has the advantage of allowing more complex tests:<br>
 * - The {@link PluginRespirator} is available<br>
 * - FCP can be used.<br><br>
 * 
 * The price is that it is much more heavy to initialize and thus has a higher execution time.<br>
 * Thus, please only use it as a base class if what {@link DatabaseBasedTest} provides is not
 * sufficient.<br>
 * 
 * @author xor (xor@freenetproject.org
 */
public /* abstract (Not used so JUnit doesn't complain) */ class AbstractFullNodeTest {
    
    /** TestName construction won't work in {@link #setUp()}, so we do it here. */
    @Rule public final TestName mTestName = new TestName();
    
    protected File dataDirectory;
    
    protected Node mNode; 
    
    protected WebOfTrust mWebOfTrust;
    
    @Before public void setUp() throws NodeInitException, InvalidThresholdException {
        String testName = mTestName.getMethodName();
        
        // NodeStarter.createTestNode() will throw if we do not do this before
        RandomSource random
            = NodeStarter.globalTestInit(testName, false, LogLevel.WARNING, "", true);
        
        // TODO: The returned RNG is used by the NodeStarter before it is returned already, so
        // we cannot properly set a seed. We should set a seed, and print it to stdout, so we
        // can have reproducible test runs.
        
        // TODO: As of 2014-09-30, TestNodeParameters does not provide any defaults, so we have to
        // set all of its values to something reasonable. Please check back whether it supports
        // defaults in the future and use them.
        // The current parameters are basically set to disable anything which can be disabled
        // so the test runs as fast as possible, even if it might break stuff.
        // The exception is FCP since WOT has FCP tests.
        TestNodeParameters params = new TestNodeParameters();
        params.port = random.nextInt((65535 - 1024) + 1) + 1024;
        params.opennetPort = random.nextInt((65535 - 1024) + 1) + 1024;
        // params.baseDirectory defaults to a random UUID, which is fine for unit tests.
        // Save it so we can delete it after the test.
        dataDirectory = params.baseDirectory;
        params.disableProbabilisticHTLs = true;
        params.maxHTL = 18;
        params.dropProb = 0;
        params.random = random;
        params.executor = new PooledExecutor();
        params.threadLimit = 256;
        params.storeSize = 16 * 1024 * 1024;
        params.ramStore = true;
        params.enableSwapping = false;
        params.enableARKs = false;
        params.enableULPRs = false;
        params.enablePerNodeFailureTables = false;
        params.enableSwapQueueing = false;
        params.enablePacketCoalescing = false;
        params.outputBandwidthLimit = 0;
        params.enableFOAF = false;
        params.connectToSeednodes = false;
        params.longPingTimes = true;
        params.useSlashdotCache = false;
        params.ipAddressOverride = null;
        params.enableFCP = true;
        
        // Even though the test should delete the directory at tearDown(), we nevertheless have
        // to check whether it exists for safety: A previous test run might have been
        // force-terminated before it had a chance to run tearDown().
        assertFalse("Data directory should not persist across test restarts: " + dataDirectory,
            dataDirectory.exists());


        mNode = NodeStarter.createTestNode(params);

        String wotFilename = System.getProperty("WOT_test_jar");
        
        assertNotNull("Please specify the name of the WOT unit test JAR to the JVM via "
            + "'java -DWOT_test_jar=...'",  wotFilename);
        
        PluginInfoWrapper wotWrapper = 
            mNode.getPluginManager().startPluginFile(wotFilename, false);
        
        mWebOfTrust = (WebOfTrust) wotWrapper.getPlugin();
    }
    
    @After public void tearDown() {
        // We cannot use exit because then JUnit will complain "Forked Java VM exited abnormally.".
        /* mNode.exit("JUnit tearDown()"); */
        
        // ... So instead, we use what exit() does internally before it terminates the VM.
        mNode.park();
        
        // FIXME: Check how to delete the data directory properly, i.e. whether the node offers
        // any function for safely deleting.
        
        assertFalse("Data directory should not persist across test restarts: " + dataDirectory,
            dataDirectory.exists());
    }
    
    @Test public void testSelf() {
        
    }

}
