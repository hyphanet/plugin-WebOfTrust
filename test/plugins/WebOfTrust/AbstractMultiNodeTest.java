/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import static java.lang.Thread.sleep;
import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;

import plugins.WebOfTrust.exceptions.UnknownIdentityException;
import plugins.WebOfTrust.util.StopWatch;
import freenet.crypt.RandomSource;
import freenet.io.comm.PeerParseException;
import freenet.io.comm.ReferenceSignatureVerificationException;
import freenet.keys.FreenetURI;
import freenet.node.DarknetPeerNode.FRIEND_TRUST;
import freenet.node.DarknetPeerNode.FRIEND_VISIBILITY;
import freenet.node.FSParseException;
import freenet.node.Node;
import freenet.node.NodeInitException;
import freenet.node.NodeStarter;
import freenet.node.NodeStarter.TestNodeParameters;
import freenet.node.PeerTooOldException;
import freenet.node.simulator.RealNodeTest;
import freenet.pluginmanager.PluginInfoWrapper;
import freenet.pluginmanager.PluginRespirator;
import freenet.support.Logger.LogLevel;
import freenet.support.LoggerHook.InvalidThresholdException;
import freenet.support.PooledExecutor;

/**
 * A base class for WoT unit tests.
 * As opposed to regular WoT unit tests based upon {@link AbstractJUnit4BaseTest}, this test runs
 * the unit tests inside one or multiple full Freenet nodes:
 * WoT is loaded as a regular plugin instead of executing the tests directly without Freenet.
 * 
 * This has the advantage of allowing more complex tests:
 * - The {@link PluginRespirator} is available.
 * - FCP can be used.
 * - Real network traffic can happen if more than one node is used.
 *   ATTENTION: This class' {@link #setUpNode()} stops all of WoT's networking threads to ensure
 *   tests don't have to deal with concurrency. To issue network traffic you have to manually call
 *   their functions for uploading/downloading stuff.
 * 
 * The price is that it is much more heavy to initialize and thus has a higher execution time.
 * Thus, please only use it as a base class if what {@link AbstractJUnit4BaseTest} provides is not
 * sufficient.
 * 
 * FIXME: This is at progress of being adapted from previously only being intended to run a single
 * node to supporting multiple nodes. See the Git history. */
@Ignore("Is ignored so it can be abstract. Self-tests are at class AbstractMultiNodeTestSelfTest.")
public abstract class AbstractMultiNodeTest
        extends AbstractJUnit4BaseTest {

    /** Path of the WoT plugin JAR which will be loaded into the test's nodes. */
    public static final String WOT_JAR_FILE = System.getProperty("WOT_test_jar");

    static {
        assertNotNull("Please specify the path of the WOT unit test JAR to the JVM via "
            + "'java -DWOT_test_jar=...'",  WOT_JAR_FILE);
    }

    /** Needed for calling {@link NodeStarter#globalTestInit(File, boolean, LogLevel, String,
     *  boolean, RandomSource) only once per VM as it requires that. */
    private static boolean sGlobalTestInitDone = false;

    private Node[] mNodes;


    /**
     * Implementing child classes shall make this return the desired amount of nodes which
     * AbstractMultiNodeTest will create at startup and load the WoT plugin into. */
    public abstract int getNodeCount();

    @Before public final void setUpNodes()
            throws NodeInitException, InvalidThresholdException, IOException, FSParseException,
                   PeerParseException, ReferenceSignatureVerificationException, PeerTooOldException,
                   InterruptedException {
        
        mNodes = new Node[getNodeCount()];
        
        for(int i = 0; i < mNodes.length; ++i)
        	mNodes[i] = setUpNode();
        
        if(mNodes.length > 1)
            connectNodes();
    }

    private final Node setUpNode()
            throws NodeInitException, InvalidThresholdException, IOException {
        
        File nodeFolder = mTempFolder.newFolder();

        // TODO: As of 2014-09-30, TestNodeParameters does not provide any defaults, so we have to
        // set all of its values to something reasonable. Please check back whether it supports
        // defaults in the future and use them.
        TestNodeParameters params = new TestNodeParameters();
        params.port = mRandom.nextInt((65535 - 1024) + 1) + 1024;
        params.opennetPort = mRandom.nextInt((65535 - 1024) + 1) + 1024;
        params.baseDirectory = nodeFolder;
        params.disableProbabilisticHTLs = false;
        params.maxHTL = 18;
        params.dropProb = 0;
        params.random = mRandom;
        params.executor = new PooledExecutor();
        params.threadLimit = 256;
        params.storeSize = 16 * 1024 * 1024;
        params.ramStore = true;
        params.enableSwapping = true;
        params.enableARKs = false; // We only connect the nodes locally, address lookup not needed
        params.enableULPRs = true;
        params.enablePerNodeFailureTables = true;
        params.enableSwapQueueing = true;
        params.enablePacketCoalescing = false; // Decrease latency for faster tests
        params.outputBandwidthLimit = 0; // = (Almost) unlimited, see NodeStarter.createTestNode()
        params.enableFOAF = true;
        params.connectToSeednodes = false; // We will only create a small darknet of our local nodes
        params.longPingTimes = true;
        params.useSlashdotCache = false; // Cannot be configured to be RAM-only so disable it.
        params.ipAddressOverride = null;
        params.enableFCP = true; // WoT has FCP
        params.enablePlugins = true;

        if(!sGlobalTestInitDone) {
            // NodeStarter.createTestNode() will throw if we do not do this before
            NodeStarter.globalTestInit(nodeFolder, false, LogLevel.WARNING,
                "freenet:NONE" /* Don't print noisy fred core logging to stdout */,
                true /* Disable DNS because we will only connect our nodes locally */,
                mRandom);
            
            sGlobalTestInitDone = true;
        }

        Node node = NodeStarter.createTestNode(params);
        node.start(!params.enableSwapping);
        
        PluginInfoWrapper wotWrapper = 
            node.getPluginManager().startPluginFile(WOT_JAR_FILE, false);
        
        WebOfTrust wot = (WebOfTrust) wotWrapper.getPlugin();
        
        // Prevent unit tests from having to do thread synchronization by terminating all WOT
        // subsystems which run their own thread.
        wot.getIntroductionClient().terminate();
        wot.getIntroductionServer().terminate();
        wot.getIdentityInserter().terminate();
        wot.getIdentityFetcher().stop();
        wot.getSubscriptionManager().stop();
        
        return node;
    }

    public final Node getNode() {
        if(mNodes.length > 1)
            throw new UnsupportedOperationException("Running more than one Node!");
        
        return mNodes[0];
    }

    /**
     * Connect every node to every other node by darknet.
     * TODO: Performance: The topology of this may suck. If it doesn't work then see what fred's
     * {@link RealNodeTest} does. */
    private final void connectNodes()
            throws FSParseException, PeerParseException, ReferenceSignatureVerificationException,
                   PeerTooOldException, InterruptedException {
        
        StopWatch setupTime = new StopWatch();
        
        for(int i = 0; i < mNodes.length; ++i) {
            for(int j = 0; j < mNodes.length; ++j) {
                if(j == i)
                    continue;
                
                // The choice of loop boundaries means that for a pair of nodes (a,b) we will both
                // call a.connect(b) AND b.connect(a).
                // This is intentional: As of fred build01478 its class RealNodeTest, which was used
                // as an inspiration for this code, does just that.
                mNodes[i].connect(mNodes[j], FRIEND_TRUST.HIGH, FRIEND_VISIBILITY.YES);
            }
        }
        
        System.out.println("AbstractMultiNodeTest: Waiting for nodes to connect...");
        boolean connected;
        do {
            connected = true;
            for(Node n : mNodes) {
                if(n.peers.countConnectedDarknetPeers() != (mNodes.length - 1)) {
                    connected = false;
                    break;
                }
            }
            
            if(!connected)
                sleep(100);
        } while(!connected);
        
        System.out.println("AbstractMultiNodeTest: Nodes connected! Time: " + setupTime);
    }

    /**
     * {@link AbstractJUnit4BaseTest#testDatabaseIntegrityAfterTermination()} is based on this,
     * please apply changes there as well. */
    @After
    @Override
    public final void testDatabaseIntegrityAfterTermination() {
        for(Node node : mNodes) {
            // We cannot use Node.exit() because it would terminate the whole JVM.
            // TODO: Code quality: Once fred supports shutting down a Node without killing the JVM,
            // use that instead of only unloading WoT.
            // https://bugs.freenetproject.org/view.php?id=6683
            /* node.exit("JUnit tearDown()"); */
            
            WebOfTrust wot = getWebOfTrust(node);
            File database = wot.getDatabaseFile();
            node.getPluginManager().killPlugin(wot, Long.MAX_VALUE);
            
            // The following commented-out assert would yield a false failure:
            // - setUpNode() already called terminate() upon various subsystems of WoT.
            // - When killPlugin() calls WebOfTrust.terminate(), that function will try to
            //   terminate() those subsystems again. This will fail because they are terminated
            //   already.
            // - WebOfTrust.terminate() will mark termination as failed due to subsystem termination
            //   failure. Thus, isTerminated() will return false.
            // The compensation for having this assert commented out is the function testTerminate()
            // at AbstractMultiNodeTestSelfTest.
            // TODO: Code quality: It would nevertheless be a good idea to find a way to enable this
            // assert since testTerminate() does not cause load upon the subsystems of WoT. This
            // function here however is an @After test, so it will be run after the child test
            // classes' tests, which can cause sophisticated load. An alternate solution would be to
            // find a way to make testTerminate() cause the subsystem threads to all run, in
            // parallel of terminate(). 
            /* assertTrue(wot.isTerminated()); */
            
            wot = null;
            
            WebOfTrust reopened = new WebOfTrust(database.toString());
            assertTrue(reopened.verifyDatabaseIntegrity());
            assertTrue(reopened.verifyAndCorrectStoredScores());
            reopened.terminate();
            assertTrue(reopened.isTerminated());
        }
    }

    @Override
    protected final WebOfTrust getWebOfTrust() {
        if(mNodes.length > 1)
            throw new UnsupportedOperationException("Running more than one WebOfTrust!");
        
        return getWebOfTrust(mNodes[0]);
    }

    protected static final WebOfTrust getWebOfTrust(Node node) {
        PluginInfoWrapper pluginInfo =
            node.getPluginManager().getPluginInfoByClassName(WebOfTrust.class.getName());
        assertNotNull("Plugin shouldn't be unloaded yet!", pluginInfo);
        return (WebOfTrust)pluginInfo.getPlugin();
    }

    /**
     * {@link AbstractMultiNodeTest} loads WOT as a real plugin just as if it was running in
     * a regular node. This will cause WOT to create the seed identities.<br>
     * If you need to do a test upon a really empty database, use this function to delete them.
     * 
     * @throws UnknownIdentityException
     *             If the seeds did not exist. This is usually an error, don't catch it, let it hit
     *             JUnit.
     * @throws MalformedURLException
     *             Upon internal failure. Don't catch this, let it hit JUnit.
     */
    protected final void deleteSeedIdentities()
            throws UnknownIdentityException, MalformedURLException {
        
        for(Node node : mNodes) {
            WebOfTrust wot = getWebOfTrust(node);
            // Properly ordered combination of locks needed for wot.beginTrustListImport(),
            // wot.deleteWithoutCommit(Identity) and Persistent.checkedCommit().
            // We normally don't synchronize in unit tests but this is a base class for all WOT unit
            // tests so side effects of not locking cannot be known here.
            // Calling this now already so our assert..() are guaranteed to be coherent as well.
            // Also, taking all those locks at once for proper anti-deadlock order.
            synchronized(wot) {
            synchronized(wot.getIntroductionPuzzleStore()) {
            synchronized(wot.getIdentityFetcher()) {
            synchronized(wot.getSubscriptionManager()) {
            synchronized(Persistent.transactionLock(wot.getDatabase()))  {
            
            assertEquals(WebOfTrust.SEED_IDENTITIES.length, wot.getAllIdentities().size());
            
            // The function for deleting identities deleteWithoutCommit() is mostly a debug function
            // and thus shouldn't be used upon complex databases. See its JavaDoc.
            assertEquals(
                  "This function might have side effects upon databases which contain more than"
                + " just the seed identities, so please do not use it upon such databases.",
                0, wot.getAllTrusts().size() + wot.getAllScores().size());
            
            wot.beginTrustListImport();
            for(String seedURI : WebOfTrust.SEED_IDENTITIES) {
                wot.deleteWithoutCommit(wot.getIdentityByURI(new FreenetURI(seedURI)));
            }
            wot.finishTrustListImport();
            Persistent.checkedCommit(wot.getDatabase(), wot);
            
            assertEquals(0, wot.getAllIdentities().size());
            
            }}}}}
        }
    }
}
