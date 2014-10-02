/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;

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
@Ignore("Is ignored so it can be abstract. If you need to add self-tests, use member classes, "
    +   "they likely won't be ignored. But then also check that to make sure.")
public abstract class AbstractFullNodeTest
        extends AbstractJUnit4BaseTest {

    protected Node mNode; 
    
    protected WebOfTrust mWebOfTrust;
    
    @Before public void setUp() throws NodeInitException, InvalidThresholdException, IOException {
        File nodeFolder = mTempFolder.newFolder();

        // TODO: As of 2014-09-30, TestNodeParameters does not provide any defaults, so we have to
        // set all of its values to something reasonable. Please check back whether it supports
        // defaults in the future and use them.
        // The current parameters are basically set to disable anything which can be disabled
        // so the test runs as fast as possible, even if it might break stuff.
        // The exception is FCP since WOT has FCP tests.
        TestNodeParameters params = new TestNodeParameters();
        params.port = mRandom.nextInt((65535 - 1024) + 1) + 1024;
        params.opennetPort = mRandom.nextInt((65535 - 1024) + 1) + 1024;
        params.baseDirectory = nodeFolder;
        params.disableProbabilisticHTLs = true;
        params.maxHTL = 18;
        params.dropProb = 0;
        params.random = mRandom;
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

        
        // NodeStarter.createTestNode() will throw if we do not do this before
        mRandom
            = NodeStarter.globalTestInit(nodeFolder, false, LogLevel.WARNING, "", true, mRandom);

        mNode = NodeStarter.createTestNode(params);
        mNode.start(!params.enableSwapping);

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
    }

    @Override
    protected WebOfTrust getWebOfTrust() {
        return mWebOfTrust;
    }

}
