/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import static java.lang.Math.round;
import static java.lang.Thread.sleep;
import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.MalformedURLException;
import java.net.SocketException;
import java.util.ArrayList;

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
import freenet.node.Location;
import freenet.node.Node;
import freenet.node.NodeInitException;
import freenet.node.NodeStarter;
import freenet.node.NodeStats;
import freenet.node.NodeStarter.TestNodeParameters;
import freenet.node.PeerTooOldException;
import freenet.node.simulator.RealNodeRequestInsertTest;
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
 *   ATTENTION: If your {@link #shouldTerminateAllWoTThreads()} implementation returns true, then
 *   this class' {@link #loadWoT(Node)} stops all of WoT's networking threads to ensure tests don't
 *   have to deal with concurrency. To issue network traffic you then have to manually call their
 *   functions for uploading/downloading stuff.
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

    /**
     * We generate an ideal topology, no swapping needed.
     * 
     * This constant is not intended to be used, it really shouldn't exist and be hardcoded at our
     * {@link #setUpNode()} instead. But it's needed to make the flag accessible in a different
     * place of this class due to {@link Node#start(boolean)} wanting the value again even though we
     * already told it by {@link TestNodeParameters}. */
    private static final boolean ENABLE_SWAPPING = false;

    /** Needed for calling {@link NodeStarter#globalTestInit(File, boolean, LogLevel, String,
     *  boolean, RandomSource)} only once per VM as it requires that. */
    private static boolean sGlobalTestInitDone = false;

    /** {@link NodeStarter#globalTestInit(File, boolean, LogLevel, String,
     *  boolean, RandomSource)} wants all nodes of a VM to be in the same dir, so this is it. */
    private static File sNodeFolder = null;

    private Node[] mNodes;

	/**
	 * We use one Executor for all Nodes we create so they can share the thread pool and we thus
	 * avoid having to create very many excess threads.
	 * This is inspired by fred's {@link RealNodeRequestInsertTest} as of fred build01478: it does
	 * that as well so it probably is OK to do. */
	private final PooledExecutor mExecutor = new PooledExecutor();

	/**
	 * Thread limit which is given to each Node.
	 * A single node as of build01478 will have about 64 threads when idle.
	 * All nodes share the executor {@link #mExecutor} so multiply the expected minimal thread count
	 * for one node by their amount, and divide it by the arbitrary value of 2 to compensate for
	 * the fact that each node can use the unused threads of all other nodes. */
	private final int mThreadLimit = 64 * getNodeCount() / 2;


    /**
     * Implementing child classes shall make this return the desired amount of nodes which
     * AbstractMultiNodeTest will create at startup and load the WoT plugin into.
     * 
     * If you want to do networking, i.e. use a value greater than 1, be aware that the node
     * configuration settings which this class uses have been chosen and tested with a value of 100
     * nodes. */
    public abstract int getNodeCount();

    /**
     * How many instances of the WoT plugin to load into the nodes.
     * The first node of {@link #getNodes()} will receive the first instance, the second will get
     * the second, and so on.
     * Can be used if you want to have a high {@link #getNodeCount()} for a better network topology
     * but only need a few WoT instances. */
    public abstract int getWoTCount();

    /**
     * Implementations shall return true if the instances of the WoT plugin which are loaded into
     * the nodes shall have all their subsystem threads terminated before running tests to allow
     * the tests to not have any concurrency measures.
     * This currently includes:
     * 
     * - WebOfTrust.getIntroductionClient()
     * - WebOfTrust.getIntroductionServer()
     * - WebOfTrust.getIdentityInserter()
     * - WebOfTrust.getIdentityFetcher()
     * - WebOfTrust.getSubscriptionManager() */
    public abstract boolean shouldTerminateAllWoTThreads();


    @Before public final void setUpNodes()
            throws NodeInitException, InvalidThresholdException, IOException, FSParseException,
                   PeerParseException, ReferenceSignatureVerificationException, PeerTooOldException,
                   InterruptedException {
        
        mNodes = new Node[getNodeCount()];
        
        for(int i = 0; i < mNodes.length; ++i)
        	mNodes[i] = setUpNode();
        
        if(mNodes.length > 1)
            connectNodes();
        
        assertTrue(getWoTCount() <= mNodes.length);
        
        for(int i = 0; i < getWoTCount(); ++i)
            loadWoT(mNodes[i]);
    }

    private final Node setUpNode()
            throws NodeInitException, InvalidThresholdException, IOException {
        
        if(sNodeFolder == null)
             sNodeFolder = mTempFolder.newFolder();
        
        // TODO: As of 2014-09-30, TestNodeParameters does not provide any defaults, so we have to
        // set all of its values to something reasonable. Please check back whether it supports
        // defaults in the future and use them.
        TestNodeParameters params = new TestNodeParameters();
        // TODO: Also set a random TCP port for FCP
        ArrayList<Integer> ports = getFreeUDPPorts(2);
        params.port = ports.get(0);
        params.opennetPort = ports.get(1);
        params.baseDirectory = sNodeFolder;
        params.disableProbabilisticHTLs = false;
        params.maxHTL = 5; // From RealNodeRequestInsertTest of fred build01478, for 100 nodes.
        params.dropProb = 0;
        params.random = mRandom;
        params.executor = mExecutor;
        params.threadLimit = mThreadLimit;
        params.storeSize = 16 * 1024 * 1024; // Is not preallocated so a high value doesn't hurt
        params.ramStore = true;
        params.enableSwapping = ENABLE_SWAPPING;
        params.enableARKs = false; // We only connect the nodes locally, address lookup not needed
        params.enableULPRs = true;
        params.enablePerNodeFailureTables = true;
        params.enableSwapQueueing = ENABLE_SWAPPING;
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
            NodeStarter.globalTestInit(sNodeFolder, false, LogLevel.WARNING,
                "freenet:NONE" /* Don't print noisy fred core logging to stdout */,
                true /* Disable DNS because we will only connect our nodes locally */,
                mRandom);
            
            sGlobalTestInitDone = true;
        }
        
        // Don't call Node.start() yet, we do it after creating darknet connections instead.
        // - That's how RealNodeRequestInsertTest does it.
        return NodeStarter.createTestNode(params);
    }


	/**
	 * TODO: Code quality: Move to {@link TestNodeParameters} and make it allocate node ports (and
	 * FCP ports if enabled!) automatically if the user chooses none (as indicated by choosing a
	 * port of 0, which in networking usually means to auto allocate one). */
	private final ArrayList<Integer> getFreeUDPPorts(int amount) {
		ArrayList<Integer> result = new ArrayList<>(amount);
		do {
			int candidate = getFreeUDPPort();
			// Avoid returning the same port twice.
			// TODO: Code quality: Use ArraySet once we have one.
			if(!result.contains(candidate))
				result.add(candidate);
		} while(result.size() != amount);
		return result;
	}

	private final int getFreeUDPPort() {
		while(true) {
			int port = mRandom.nextInt((65535 - 1024) + 1) + 1024;
			DatagramSocket socket = null;
			try {
				socket = new DatagramSocket(port);
				return port;
			} catch(SocketException e) {
				// Not free, try next one.
			} finally {
				if(socket != null)
					socket.close();
			}
		}
	}

    private final void loadWoT(Node node) {
        PluginInfoWrapper wotWrapper = 
            node.getPluginManager().startPluginFile(WOT_JAR_FILE, false);
        
        WebOfTrust wot = (WebOfTrust) wotWrapper.getPlugin();
        
        // Prevent unit tests from having to do thread synchronization by terminating all WOT
        // subsystems which run their own thread.
        if(shouldTerminateAllWoTThreads()) {
            wot.getIntroductionClient().terminate();
            wot.getIntroductionServer().terminate();
            wot.getIdentityInserter().terminate();
            wot.getIdentityFetcher().stop();
            wot.getSubscriptionManager().stop();
        }
    }

    public final Node getNode() {
        if(mNodes.length > 1)
            throw new UnsupportedOperationException("Running more than one Node!");
        
        return mNodes[0];
    }

    public final Node[] getNodes() {
        return mNodes;
    }

	/**
	 * Can be used to assess the health of the simulated network:
	 * - is the thread limit high enough?
	 * - are nodes signaling overload by marking a large percentage of their peers as backed off?
	 * - is the ping time of the nodes sufficiently low?
	 * 
	 * TODO: Code quality: Extract functions for computing each value and add asserts to an
	 * @After function to test whether the values are in a reasonable range. E.g. check whether
	 * thread count is 30% below the limit, backoff percentage is below 30%, and ping time is below
	 * the default soft ping time limit of fred {@link NodeStats#DEFAULT_SUB_MAX_PING_TIME}. */
	public final void printNodeStatistics() {
		System.out.println(""); // For readability when being called repeatedly.
		
		// All nodes share the same executor so the value of one node should represent all of them.
		int runningThreads = mNodes[0].nodeStats.getActiveThreadCount();
		System.out.println("AbstractMultiNodeTest: Running Node threads: " + runningThreads
			+ "; limit: " + mThreadLimit);
		
		float averageBackoffPercentage = 0;
		for(Node n : mNodes) {
			float backoffQuota = (float)n.peers.countBackedOffPeers(false)
				/ (float)n.peers.countValidPeers();
			averageBackoffPercentage += backoffQuota * 100;
		}
		averageBackoffPercentage /= mNodes.length;
		System.out.println("AbstractMultiNodeTest: Average bulk backoff percentage: "
			+ round(averageBackoffPercentage));
		
		double averagePingTime = 0;
		for(Node n : mNodes) {
			averagePingTime += n.nodeStats.getNodeAveragePingTime();
		}
		averagePingTime /= mNodes.length;
		System.out.println("AbstractMultiNodeTest: Average Node ping time: "
			+ round(averagePingTime));
	}

    /**
     * Connect every node to every other node by darknet.
     * TODO: Performance: The topology of this may suck. If it doesn't work then see what fred's
     * {@link RealNodeTest} does. */
    private final void connectNodes()
            throws FSParseException, PeerParseException, ReferenceSignatureVerificationException,
                   PeerTooOldException, InterruptedException, NodeInitException {
        
        System.out.println("AbstractMultiNodeTest: Creating darknet connections...");
        StopWatch time = new StopWatch();
        makeKleinbergNetwork(mNodes);
        System.out.println("AbstractMultiNodeTest: Darknet connections created! Time: " + time);
        
        System.out.println("AbstractMultiNodeTest: Starting nodes...");
        time = new StopWatch();
        for(Node n : mNodes)
            n.start(!ENABLE_SWAPPING);
        System.out.println("AbstractMultiNodeTest: Nodes started! Time: " + time);
        
        System.out.println("AbstractMultiNodeTest: Waiting for nodes to connect...");
        time = new StopWatch();
        boolean connected;
        do {
            connected = true;
            for(Node n : mNodes) {
                if(n.peers.countConnectedDarknetPeers() < n.peers.countValidPeers()) {
                    connected = false;
                    break;
                }
            }
            
            if(!connected)
                sleep(100);
        } while(!connected);
        System.out.println("AbstractMultiNodeTest: Nodes connected! Time: " + time);
    }

	/**
	 * TODO: Code quality: This function is an amended copy-paste of
	 * {@link RealNodeTest#makeKleinbergNetwork(Node[], boolean, int, boolean, RandomSource)} of
	 * fred tag build01478. Make it public there, backport the changes and re-use it instead.
	 * 
	 * Borrowed from mrogers simulation code (February 6, 2008)
	 * 
	 * FIXME: May not generate good networks. Presumably this is because the arrays are always
	 * scanned [0..n], some nodes tend to have *much* higher connections than the degree (the first
	 * few), starving the latter ones. */
	private final void makeKleinbergNetwork(Node[] nodes) {
		// These three values are taken from RealNodeRequestInsertTest
		boolean idealLocations = true;
		int degree = 10;
		boolean forceNeighbourConnections = true;
		
		if(idealLocations) {
			// First set the locations up so we don't spend a long time swapping just to stabilise
			// each network.
			double div = 1.0 / nodes.length;
			double loc = 0.0;
			for (int i=0; i<nodes.length; i++) {
				nodes[i].setLocation(loc);
				loc += div;
			}
		}
		if(forceNeighbourConnections) {
			for(int i=0;i<nodes.length;i++) {
				int next = (i+1) % nodes.length;
				connect(nodes[i], nodes[next]);
				
			}
		}
		for (int i=0; i<nodes.length; i++) {
			Node a = nodes[i];
			// Normalise the probabilities
			double norm = 0.0;
			for (int j=0; j<nodes.length; j++) {
				Node b = nodes[j];
				if (a.getLocation() == b.getLocation()) continue;
				norm += 1.0 / distance (a, b);
			}
			// Create degree/2 outgoing connections
			for (int k=0; k<nodes.length; k++) {
				Node b = nodes[k];
				if (a.getLocation() == b.getLocation()) continue;
				double p = 1.0 / distance (a, b) / norm;
				for (int n = 0; n < degree / 2; n++) {
					if (mRandom.nextFloat() < p) {
						connect(a, b);
						break;
					}
				}
			}
		}
	}

	/**
	 * TODO: Code quality: This function is an amended copypaste of
	 * {@link RealNodeTest#connect(Node, Node)}. Make it public there, backport the changes and
	 * re-use it instead. */
	private static final void connect(Node a, Node b) {
		try {
			a.connect(b, FRIEND_TRUST.HIGH, FRIEND_VISIBILITY.YES);
			b.connect(a, FRIEND_TRUST.HIGH, FRIEND_VISIBILITY.YES);
		} catch (FSParseException | PeerParseException | ReferenceSignatureVerificationException
				| PeerTooOldException e) {
			throw new RuntimeException(e);
		}
	}

	/** TODO: Code quality: This function was copy-pasted from {@link RealNodeTest#distance(Node,
	 *  Node)}. Make it public there and re-use it instead. */
	private static final double distance(Node a, Node b) {
		double aL=a.getLocation();
		double bL=b.getLocation();
		return Location.distance(aL, bL);
	}

    /**
     * {@link AbstractJUnit4BaseTest#testDatabaseIntegrityAfterTermination()} is based on this,
     * please apply changes there as well. */
    @After
    @Override
    public final void testDatabaseIntegrityAfterTermination() {
        for(int i = 0; i < getWoTCount(); ++i) {
            Node node = mNodes[i];
            
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
        
        for(int i = 0; i < getWoTCount(); ++i) {
            Node node = mNodes[i];
            WebOfTrust wot = getWebOfTrust(node);
            // Properly ordered combination of locks needed for wot.beginTrustListImport(),
            // wot.deleteWithoutCommit(Identity) and Persistent.checkedCommit().
            // We normally don't synchronize in unit tests but this is a base class for all WOT unit
            // tests so side effects of not locking cannot be known here, especially considering
            // that we ask child classes to implement shouldTerminateAllWoTThreads() as they please.
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
