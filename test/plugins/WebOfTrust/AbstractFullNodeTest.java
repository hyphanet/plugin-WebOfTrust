/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;

import plugins.WebOfTrust.exceptions.UnknownIdentityException;
import freenet.crypt.RandomSource;
import freenet.keys.FreenetURI;
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
 * As opposed to regular WOT unit tests based upon {@link AbstractJUnit3BaseTest}, this test runs the
 * unit tests inside a full Freenet node:<br>
 * WOT is loaded as a regular plugin instead of executing the tests directly without Freenet.<br>
 * <br>
 * 
 * This has the advantage of allowing more complex tests:<br>
 * - The {@link PluginRespirator} is available<br>
 * - FCP can be used.<br><br>
 * 
 * The price is that it is much more heavy to initialize and thus has a higher execution time.<br>
 * Thus, please only use it as a base class if what {@link AbstractJUnit3BaseTest} provides is not
 * sufficient.<br>
 * 
 * @author xor (xor@freenetproject.org
 */
@Ignore("Is ignored so it can be abstract. If you need to add self-tests, use member classes, "
    +   "they likely won't be ignored. But then also check that to make sure.")
public abstract class AbstractFullNodeTest
        extends AbstractJUnit4BaseTest {
    
    /** Needed for calling {@link NodeStarter#globalTestInit(File, boolean, LogLevel, String,
     *  boolean, RandomSource) only once per VM as it requires that. */
    private static boolean sGlobalTestInitDone = false;

    protected Node mNode; 
    
    protected WebOfTrust mWebOfTrust;
    
    
    @Before public final void setUpNode()
            throws NodeInitException, InvalidThresholdException, IOException {
        
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

        if(!sGlobalTestInitDone) {
            // NodeStarter.createTestNode() will throw if we do not do this before
            NodeStarter.globalTestInit(nodeFolder, false, LogLevel.WARNING, "", true, mRandom);
            sGlobalTestInitDone = true;
        }

        mNode = NodeStarter.createTestNode(params);
        mNode.start(!params.enableSwapping);

        String wotFilename = System.getProperty("WOT_test_jar");
        
        assertNotNull("Please specify the name of the WOT unit test JAR to the JVM via "
            + "'java -DWOT_test_jar=...'",  wotFilename);
        
        PluginInfoWrapper wotWrapper = 
            mNode.getPluginManager().startPluginFile(wotFilename, false);
        
        mWebOfTrust = (WebOfTrust) wotWrapper.getPlugin();
        
        // Prevent unit tests from having to do thread synchronization by terminating all WOT
        // subsystems which run their own thread.
        mWebOfTrust.getIntroductionClient().terminate();
        mWebOfTrust.getIntroductionServer().terminate();
        mWebOfTrust.getIdentityInserter().terminate();
        mWebOfTrust.getIdentityFetcher().stop();
        mWebOfTrust.getSubscriptionManager().stop();
    }
    
    @After public final void tearDownNode() {
        // We cannot use exit because then JUnit will complain "Forked Java VM exited abnormally.".
        /* mNode.exit("JUnit tearDown()"); */
        
        // ... So instead, we use what exit() does internally before it terminates the VM.
        mNode.park();
    }

    @Override
    protected final WebOfTrust getWebOfTrust() {
        return mWebOfTrust;
    }

    /**
     * {@link AbstractJUnit4BaseTest} loads WOT as a real plugin just as if it was running in
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
        
        // Properly ordered combination of locks needed for mWebOftrust.beginTrustListImport(),
        // mWebOfTrust.deleteWithoutCommit(Identity) and Persistent.checkedCommit().
        // We normally don't synchronize in unit tests but this is a base class for all WOT unit
        // tests so side effects of not locking cannot be known here.
        // Calling this now already so our assert..() are guaranteed to be coherent as well.
        // Also, taking all those locks at once for proper anti-deadlock order.
        synchronized(mWebOfTrust) {
        synchronized(mWebOfTrust.getIntroductionPuzzleStore()) {
        synchronized(mWebOfTrust.getIdentityFetcher()) {
        synchronized(mWebOfTrust.getSubscriptionManager()) {
        synchronized(Persistent.transactionLock(mWebOfTrust.getDatabase()))  {

        assertEquals(WebOfTrust.SEED_IDENTITIES.length, mWebOfTrust.getAllIdentities().size());
        
        // The function for deleting identities deleteWithoutCommit() is mostly a debug function
        // and thus shouldn't be used upon complex databases. See its JavaDoc.
        assertEquals(
              "This function might have side effects upon databases which contain more than"
            + " just the seed identities, so please do not use it upon such databases.",
            0, mWebOfTrust.getAllTrusts().size() + mWebOfTrust.getAllScores().size());
        
        mWebOfTrust.beginTrustListImport();
        for(String seedURI : WebOfTrust.SEED_IDENTITIES) {
            mWebOfTrust.deleteWithoutCommit(mWebOfTrust.getIdentityByURI(new FreenetURI(seedURI)));
        }
        mWebOfTrust.finishTrustListImport();
        Persistent.checkedCommit(mWebOfTrust.getDatabase(), mWebOfTrust);
        
        assertEquals(0, mWebOfTrust.getAllIdentities().size());

        }}}}}
    }
}
