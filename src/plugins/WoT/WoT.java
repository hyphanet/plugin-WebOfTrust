/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WoT;

import java.net.MalformedURLException;
import java.util.Date;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.LinkedList;
import java.util.Random;
import java.util.Map.Entry;

import plugins.WoT.exceptions.DuplicateIdentityException;
import plugins.WoT.exceptions.DuplicateScoreException;
import plugins.WoT.exceptions.DuplicateTrustException;
import plugins.WoT.exceptions.InvalidParameterException;
import plugins.WoT.exceptions.NotInTrustTreeException;
import plugins.WoT.exceptions.NotTrustedException;
import plugins.WoT.exceptions.UnknownIdentityException;
import plugins.WoT.introduction.IntroductionClient;
import plugins.WoT.introduction.IntroductionPuzzle;
import plugins.WoT.introduction.IntroductionPuzzleStore;
import plugins.WoT.introduction.IntroductionServer;
import plugins.WoT.ui.fcp.FCPInterface;
import plugins.WoT.ui.web.WebInterface;

import com.db4o.Db4o;
import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.config.Configuration;
import com.db4o.ext.ExtObjectContainer;
import com.db4o.query.Query;
import com.db4o.reflect.jdk.JdkReflector;

import freenet.keys.FreenetURI;
import freenet.l10n.BaseL10n;
import freenet.l10n.PluginL10n;
import freenet.l10n.BaseL10n.LANGUAGE;
import freenet.node.RequestClient;
import freenet.pluginmanager.FredPlugin;
import freenet.pluginmanager.FredPluginBaseL10n;
import freenet.pluginmanager.FredPluginFCP;
import freenet.pluginmanager.FredPluginL10n;
import freenet.pluginmanager.FredPluginRealVersioned;
import freenet.pluginmanager.FredPluginThreadless;
import freenet.pluginmanager.FredPluginVersioned;
import freenet.pluginmanager.PluginReplySender;
import freenet.pluginmanager.PluginRespirator;
import freenet.support.CurrentTimeUTC;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;

/**
 * A web of trust plugin based on Freenet.
 * 
 * @author xor (xor@freenetproject.org), Julien Cornuwel (batosai@freenetproject.org)
 */
public class WoT implements FredPlugin, FredPluginThreadless, FredPluginFCP, FredPluginVersioned, FredPluginRealVersioned,
	FredPluginL10n, FredPluginBaseL10n {
	
	/* Constants */
	
	public static final boolean FAST_DEBUG_MODE = false;
	
	public static final String DATABASE_FILENAME =  "WebOfTrust-testing.db4o";  /* FIXME: Change when we leave the beta stage */
	public static final int DATABASE_FORMAT_VERSION = -92;  /* FIXME: Change when we leave the beta stage */
	
	/** The relative path of the plugin on Freenet's web interface */
	public static final String SELF_URI = "/WoT";

	/** Package-private method to allow unit tests to bypass some assert()s */
	
	/**
	 * The "name" of this web of trust. It is included in the document name of identity URIs. For an example, see the SEED_IDENTITIES
	 * constant below. The purpose of this costant is to allow anyone to create his own custom web of trust which is completely disconnected
	 * from the "official" web of trust of the Freenet project.
	 */
	public static final String WOT_NAME = "WoT-testing"; // FIXME: Change to "WebOfTrust" when we leave the beta stage
	
	/**
	 * The official seed identities of the WoT plugin: If a newbie wants to download the whole offficial web of trust, he needs at least one
	 * trust list from an identity which is well-connected to the web of trust. To prevent newbies from having to add this identity manually,
	 * the Freenet development team provides a list of seed identities - each of them is one of the developers.
	 */
	private static final String[] SEED_IDENTITIES = new String[] { 
		"USK@fWK9InP~vG6HnTDm3wiJgvh6ULJQaU5XYTkXXNuKTTk,GnZgrilXSYjD~xrD6l4~5x~Nspz3aFe2eYXWRvaNRHU,AQACAAE/WoT/261", // xor
		"USK@Ng~ixtLAfKBd4oaW6Ln7Fy~Z9Wm8HSoqIKvy4zzt3Sc,Cytpvs9neFQM0Ju4Yb2BCEC7VEZfeX8VAOpQgvOAY80,AQACAAE/WoT/65", // toad
		"USK@ZO6itT2Fi844HLt6N0v7xOKFm96M4Jdp2XHwkBcpeWw,D24PpfV3BGfZUB8Nnl8h5rr2jUJpKBLIwiMqGjVnQ1w,AQACAAE/WoT/1" // Artefact2 aka. Lysergesaurediethylamid
		// TODO: Add more developers
	};
	
	private static final String SEED_IDENTITY_MANDATORY_VERSION_PROPERTY = "MandatoryVersion";
	private static final String SEED_IDENTITY_LATEST_VERSION_PROPERTY = "LatestVersion";
	

	/* References from the node */
	
	/** The node's interface to connect the plugin with the node, needed for retrieval of all other interfaces */
	private PluginRespirator mPR;	
	
	private static PluginL10n l10n;
	
	/* References from the plugin itself */
	
	/* Database & configuration of the plugin */
	private ExtObjectContainer mDB;
	private Config mConfig;
	private IntroductionPuzzleStore mPuzzleStore;
	
	/** Used for exporting identities, identity introductions and introduction puzzles to XML and importing them from XML. */
	private XMLTransformer mXMLTransformer;
	private RequestClient mRequestClient;

	/* Worker objects which actually run the plugin */
	
	/**
	 * Periodically wakes up and inserts any OwnIdentity which needs to be inserted.
	 */
	private IdentityInserter mInserter;
	
	/**
	 * Fetches identities when it is told to do so by the plugin:
	 * - At startup, all known identities are fetched
	 * - When a new identity is received from a trust list it is fetched
	 * - When a new identity is received by the IntrouductionServer it is fetched
	 * - When an identity is manually added it is also fetched.
	 * - ...
	 */
	private IdentityFetcher mFetcher;
	
	/**
	 * Uploads captchas belonging to our own identities which others can solve to get on the trust list of them. Checks whether someone
	 * uploaded solutions for them periodically and adds the new identities if a solution is received. 
	 */
	private IntroductionServer mIntroductionServer;
	
	/**
	 * Downloads captchas which the user can solve to announce his identities on other people's trust lists, provides the interface for
	 * the UI to obtain the captchas and enter solutions. Uploads the solutions if the UI enters them.
	 */
	private IntroductionClient mIntroductionClient;
	
	/* Actual data of the WoT */
	
	private boolean mFullScoreComputationNeeded = false;
	
	private boolean mTrustListImportInProgress = false;
	
	
	/* User interfaces */
	
	private WebInterface mWebInterface;
	private FCPInterface mFCPInterface;

	public void runPlugin(PluginRespirator myPR) {
		try {
			Logger.debug(this, "Start");
			
			/* Catpcha generation needs headless mode on linux */
			System.setProperty("java.awt.headless", "true"); 
	
			mPR = myPR;
			mDB = initDB(DATABASE_FILENAME);
			
			mConfig = Config.loadOrCreate(this);
			if(mConfig.getInt(Config.DATABASE_FORMAT_VERSION) > WoT.DATABASE_FORMAT_VERSION)
				throw new RuntimeException("The WoT plugin's database format is newer than the WoT plugin which is being used.");
			
			upgradeDB();
			deleteDuplicateObjects();
			deleteOrphanObjects();
			
			mXMLTransformer = new XMLTransformer(this);
			mPuzzleStore = new IntroductionPuzzleStore(this);
			
			mRequestClient = new RequestClient() {
	
				public boolean persistent() {
					return false;
				}
	
				public void removeFrom(ObjectContainer container) {
					throw new UnsupportedOperationException();
				}
				
			};
	
			createSeedIdentities();
			
			mInserter = new IdentityInserter(this);
			mInserter.start();
			
			mFetcher = new IdentityFetcher(this);		
			
			// TODO: Don't do this as soon as we are sure that score computation works.
			Logger.normal(this, "Veriying all stored scores ...");
			synchronized(this) {
			synchronized(mDB.lock()) {
				computeAllScoresWithoutCommit();
				mDB.commit();
			}
			}
			
			Logger.debug(this, "Starting fetches of all identities...");
			synchronized(this) {
			synchronized(mFetcher) {
				for(Identity identity : getAllIdentities()) {
					if(shouldFetchIdentity(identity)) {
						try {
							mFetcher.fetch(identity.getID());
						}
						catch(Exception e) {
							Logger.error(this, "Fetching identity failed!", e);
						}
					}
				}
			}
			}
			
			mIntroductionServer = new IntroductionServer(this, mFetcher);
			mIntroductionServer.start();
			
			mIntroductionClient = new IntroductionClient(this);
			mIntroductionClient.start();

			mWebInterface = new WebInterface(this, SELF_URI);
			mFCPInterface = new FCPInterface(this);
			
			Logger.debug(this, "WoT startup completed.");
		}
		catch(RuntimeException e){
			Logger.error(this, "Error during startup", e);
			/* We call it so the database is properly closed */
			terminate();
			
			throw e;
		}
	}
	
	/**
	 * Constructor for being used by the node and unit tests. Does not do anything.
	 */
	public WoT() {

	}
	
	/**
	 *  Constructor which does not generate an IdentityFetcher, IdentityInster, IntroductionPuzzleStore, user interface, etc.
	 * For use by the unit tests to be able to run WoT without a node.
	 * @param databaseFilename The filename of the database.
	 */
	public WoT(String databaseFilename) {
		mDB = initDB(databaseFilename);
		mConfig = Config.loadOrCreate(this);
		
		if(mConfig.getInt(Config.DATABASE_FORMAT_VERSION) > WoT.DATABASE_FORMAT_VERSION)
			throw new RuntimeException("The WoT plugin's database format is newer than the WoT plugin which is being used.");
	}

	/**
	 * Initializes the plugin's db4o database.
	 * 
	 * @return A db4o <code>ObjectContainer</code>. 
	 */
	private ExtObjectContainer initDB(String filename) {
		Configuration cfg = Db4o.newConfiguration();
		cfg.reflectWith(new JdkReflector(getPluginClassLoader()));
		cfg.activationDepth(5); /* TODO: Change to 1 and add explicit activation everywhere */
		cfg.exceptionsOnNotStorable(true);

        // TURN OFF SHUTDOWN HOOK.
        // The shutdown hook does auto-commit. We do NOT want auto-commit: if a
        // transaction hasn't commit()ed, it's not safe to commit it.
        cfg.automaticShutDown(false);
		
		for(String field : Identity.getIndexedFields()) cfg.objectClass(Identity.class).objectField(field).indexed(true);
		for(String field : OwnIdentity.getIndexedFields()) cfg.objectClass(OwnIdentity.class).objectField(field).indexed(true);
		for(String field : Trust.getIndexedFields()) cfg.objectClass(Trust.class).objectField(field).indexed(true);
		for(String field : Score.getIndexedFields()) cfg.objectClass(Score.class).objectField(field).indexed(true);
		
		for(String field : IdentityFetcher.IdentityFetcherCommand.getIndexedFields())
			cfg.objectClass(IdentityFetcher.IdentityFetcherCommand.class).objectField(field).indexed(true);
		
		cfg.objectClass(IntroductionPuzzle.PuzzleType.class).persistStaticFieldValues(); /* Needed to be able to store enums */
		for(String field : IntroductionPuzzle.getIndexedFields()) cfg.objectClass(IntroductionPuzzle.class).objectField(field).indexed(true);
		
		return Db4o.openFile(cfg, filename).ext();
	}
	
	private synchronized void upgradeDB() {
		int databaseVersion = mConfig.getInt(Config.DATABASE_FORMAT_VERSION);
		
		if(databaseVersion == WoT.DATABASE_FORMAT_VERSION)
			return;
		
		try {
		if(databaseVersion == -100) {
			Logger.normal(this, "Found old database (-100), adding last fetched date to all identities ...");
			for(Identity identity : getAllIdentities()) {
				identity.mLastFetchedDate = new Date(0);
				storeWithoutCommit(identity);
			}
			
			mConfig.set(Config.DATABASE_FORMAT_VERSION, ++databaseVersion);
			mConfig.storeAndCommit();
		}
		
		if(databaseVersion == -99) {
			Logger.normal(this, "Found old database (-99), adding last changed date to all trust values ...");
			
			final long now = CurrentTimeUTC.getInMillis();
			final int randomizationRange = 10 * 24 * 60 * 60 * 1000;
			final Random random = mPR.getNode().fastWeakRandom;
			
			for(Trust trust : getAllTrusts()) {
				trust.setDateOfLastChange(new Date(now + random.nextInt(randomizationRange)));
				mDB.store(trust);
			}
			
			mConfig.set(Config.DATABASE_FORMAT_VERSION, ++databaseVersion);
			mConfig.storeAndCommit();
		} 
		
		if(databaseVersion == -98) {
			Logger.normal(this, "Found old database (-98), recalculating all scores & marking all identities for re-fetch ...");
			
			computeAllScoresWithoutCommit();
			
			for(Identity identity : getAllIdentities()) {
				if(!(identity instanceof OwnIdentity))
					identity.markForRefetch(); // Re-fetch the identity so that the "publishes trustlist" flag is imported, the old WoT forgot that...
			}
			
			mConfig.set(Config.DATABASE_FORMAT_VERSION, ++databaseVersion);
			mConfig.storeAndCommit();
		}
		
		if(databaseVersion == -97) {
			Logger.normal(this, "Found old database (-97), checking for self-referential trust values ...");
			
			for(Identity id : getAllIdentities()) {
				try {
					Trust trust = getTrust(id, id);
					Logger.debug(this, "Deleting a self-referencing trust value for " + id);
					removeTrustWithoutCommit(trust);
					id.updated();
				}
				catch(NotTrustedException e) { }
			}
		
			mConfig.set(Config.DATABASE_FORMAT_VERSION, ++databaseVersion);
			mConfig.storeAndCommit();
		}
		
		if(databaseVersion == -96) {
			Logger.normal(this, "Found old database (-96), adding dates ...");
			
			for(Trust trust : getAllTrusts()) {
				trust.setDateOfCreation(trust.getDateOfLastChange());
				mDB.store(trust);
			}
			
			Date now = CurrentTimeUTC.get();
			
			for(Score score : getAllScores()) {
				score.initializeDates(now);
				mDB.store(score);
			}
			
			mConfig.set(Config.DATABASE_FORMAT_VERSION, ++databaseVersion);
			mConfig.storeAndCommit();
		}
		
		
		if(databaseVersion == -95 || databaseVersion == -94 || databaseVersion == -93) {
			Logger.normal(this, "Found old database (" + databaseVersion + "), re-calculating all scores ...");
			
			mFullScoreComputationNeeded = true;
			computeAllScoresWithoutCommit();
			
			mConfig.set(Config.DATABASE_FORMAT_VERSION, databaseVersion = -92);
			mConfig.storeAndCommit();
		}
		
		
		
		if(databaseVersion != WoT.DATABASE_FORMAT_VERSION)
			throw new RuntimeException("Your database is too outdated to be upgraded automatically, please create a new one by deleting " 
				+ DATABASE_FILENAME + ". Contact the developers if you really need your old data.");
		}
		catch(RuntimeException e) {
			System.gc(); mDB.rollback(); Logger.debug(this, "ROLLED BACK!");
			throw e;
		}
	}
	
	/**
	 * Debug function for deleting duplicate identities etc. which might have been created due to bugs :)
	 */
	@SuppressWarnings("unchecked")
	private synchronized void deleteDuplicateObjects() {

		try {
			ObjectSet<Identity> identities = getAllIdentities();
			HashSet<String> deleted = new HashSet<String>();

			Logger.debug(this, "Searching for duplicate identities ...");

			for(Identity identity : identities) {
				Query q = mDB.query();
				q.constrain(Identity.class);
				q.descend("mID").constrain(identity.getID());
				q.constrain(identity).identity().not();
				ObjectSet<Identity> duplicates = q.execute();
				for(Identity duplicate : duplicates) {
					if(deleted.contains(duplicate.getID()) == false) {
						Logger.error(duplicate, "Deleting duplicate identity " + duplicate.getRequestURI());
						deleteIdentity(duplicate);
					}
				}
				deleted.add(identity.getID());
			}

			Logger.debug(this, "Finished searching for duplicate identities.");
		}
		catch(RuntimeException e) {
			Logger.error(this, "Error while deleting duplicate identities", e);
		}

		Logger.debug(this, "Searching for duplicate Trust objects ...");

		for(OwnIdentity treeOwner : getAllOwnIdentities()) {
			HashSet<String> givenTo = new HashSet<String>();

			for(Trust trust : getGivenTrusts(treeOwner)) {
				if(givenTo.contains(trust.getTrustee().getID()) == false)
					givenTo.add(trust.getTrustee().getID());
				else {
					synchronized(mDB.lock()) {
						try {
							Logger.error(this, "Deleting duplicate given trust:" + trust);
							removeTrustWithoutCommit(trust);
							mDB.commit(); Logger.debug(this, "COMMITED.");
						}
						catch(RuntimeException e) {
							System.gc(); mDB.rollback(); Logger.error(this, "ROLLED BACK!", e);
						}
					}

				}
			}

		}

		Logger.debug(this, "Finished searching for duplicate trust objects.");
		
		/* TODO: Also delete duplicate score */
	}
	
	/**
	 * Debug function for deleting trusts or scores of which one of the involved partners is missing.
	 */
	@SuppressWarnings("unchecked")
	private synchronized void deleteOrphanObjects() {
		synchronized(mDB) {
			try {
				Query q = mDB.query();
				q.constrain(Trust.class);
				q.descend("mTruster").constrain(null).identity().or(q.descend("mTrustee").constrain(null).identity());
				ObjectSet<Trust> orphanTrusts = q.execute();
				for(Trust trust : orphanTrusts) {
					Logger.error(trust, "Deleting orphan trust, truster = " + trust.getTruster() + ", trustee = " + trust.getTrustee());
					mDB.delete(trust);
				}
			}
			catch(Exception e) {
				Logger.error(this, "Deleting orphan trusts failed.", e);
				System.gc(); mDB.rollback(); Logger.debug(this, "ROLLED BACK!"); 
			}
			
			try {
				Query q = mDB.query();
				q.constrain(Score.class);
				q.descend("mTreeOwner").constrain(null).identity().or(q.descend("mTarget").constrain(null).identity());
				ObjectSet<Score> orphanScores = q.execute();
				for(Score score : orphanScores) {
					Logger.error(score, "Deleting orphan score, treeOwner = " + score.getTreeOwner() + ", target = " + score.getTarget());
					mDB.delete(score);
				}
				
				mDB.commit(); Logger.debug(this, "COMMITED.");
			}
			catch(Exception e) {
				Logger.error(this, "Deleting orphan trusts failed.", e);
				System.gc(); mDB.rollback(); Logger.debug(this, "ROLLED BACK!"); 
			}
		}
	}
	
	/** Capacity is the maximum amount of points an identity can give to an other by trusting it. 
	 * 
	 * Values choice :
	 * Advogato Trust metric recommends that values decrease by rounded 2.5 times.
	 * This makes sense, making the need of 3 N+1 ranked people to overpower
	 * the trust given by a N ranked identity.
	 * 
	 * Number of ranks choice :
	 * When someone creates a fresh identity, he gets the seed identity at
	 * rank 1 and freenet developpers at rank 2. That means that
	 * he will see people that were :
	 * - given 7 trust by freenet devs (rank 2)
	 * - given 17 trust by rank 3
	 * - given 50 trust by rank 4
	 * - given 100 trust by rank 5 and above.
	 * This makes the range small enough to avoid a newbie
	 * to even see spam, and large enough to make him see a reasonnable part
	 * of the community right out-of-the-box.
	 * Of course, as soon as he will start to give trust, he will put more
	 * people at rank 1 and enlarge his WoT.
	 */
	protected static final int capacities[] = {
			100,// Rank 0 : Own identities
			40,	// Rank 1 : Identities directly trusted by ownIdenties
			16, // Rank 2 : Identities trusted by rank 1 identities
			6,	// So on...
			2,
			1	// Every identity above rank 5 can give 1 point
	};			// Identities with negative score have zero capacity
	
	/**
	 * Computes the capacity of a truster. The capacity is a weight function in percent which is used to decide how much
	 * trust points an identity can add to the score of identities which it has assigned trust values to.
	 * The higher the rank of an identity, the less is it's capacity.
	 *
	 * If the rank of the identity is Integer.MAX_VALUE (infinite, this means it has only received negative or 0 trust values from identities with rank >= 0 and less
	 * than infinite) or -1 (this means that it has only received trust values from identities with infinite rank) then its capacity is 0.
	 * 
	 * If the tree owner has assigned a trust value to the target the capacity will be computed only from that trust value:
	 * The decision of the tree owner should always overpower the view of remote identities.
	 * 
	 * Notice that 0 is included in infinite rank to prevent identities which have only solved introduction puzzles from having a capacity.  
	 *  
	 * @param treeOwner The {@link OwnIdentity} in whose trust tree the capacity shall be computed
	 * @param target The {@link Identity} of which the capacity shall be computed. 
	 * @param rank The rank of the identity. The rank is the distance in trust steps from the OwnIdentity which views the web of trust,
	 * 				- its rank is 0, the rank of its trustees is 1 and so on. Must be -1 if the truster has no rank in the tree owners view.
	 */
	protected int computeCapacity(OwnIdentity treeOwner, Identity target, int rank) {
		if(treeOwner == target)
			return 100;
		 
		try {
			if(getTrust(treeOwner, target).getValue() <= 0) { // Security check, if rank computation breaks this will hit.
				assert(rank == Integer.MAX_VALUE);
				return 0;
			}
		} catch(NotTrustedException e) { }
		
		if(rank == -1 || rank == Integer.MAX_VALUE)
			return 0;
		 
		return (rank < capacities.length) ? capacities[rank] : 1;
	}
	
	/**
	 * Reference-implementation of score computation. This means:<br />
	 * - It is not used by the real WoT code because its slow<br />
	 * - It is used by unit tests (and WoT) to check whether the real implementation works<br />
	 * - It is the function which you should read if you want to understand how WoT works.<br />
	 * 
	 * Computes all rank and score values and checks whether the database is correct. If wrong values are found, they are correct.<br />
	 * 
	 * There was a bug in the score computation for a long time which resulted in wrong computation when trust values very removed under certain conditions.<br />
	 * 
	 * Further, rank values are shortest paths and the path-finding algorithm is not executed from the source
	 * to the target upon score computation: It uses the rank of the neighbor nodes to find a shortest path.
	 * Therefore, the algorithm is very vulnerable to bugs since one wrong value will stay in the database
	 * and affect many others. So it is useful to have this function.
	 * 
	 * @return True if all stored scores were correct. False if there were any errors in stored scores.
	 */
	protected synchronized boolean computeAllScoresWithoutCommit() {
		boolean returnValue = true;
		final ObjectSet<Identity> allIdentities = getAllIdentities();
		
		// Scores are a rating of an identity from the view of an OwnIdentity so we compute them per OwnIdentity.
		for(OwnIdentity treeOwner : getAllOwnIdentities()) {
			// At the end of the loop body, this table will be filled with the ranks of all identities which are visible for treeOwner.
			// An identity is visible if there is a trust chain from the owner to it.
			// The rank is the distance in trust steps from the treeOwner.			
			// So the treeOwner is rank 0, the trustees of the treeOwner are rank 1 and so on.
			final Hashtable<Identity, Integer> rankValues = new Hashtable<Identity, Integer>(allIdentities.size() * 2);
			
			// Compute the rank values
			{
				// For each identity which is added to rankValues, all its trustees are added to unprocessedTrusters.
				// The inner loop then pulls out one unprocessed identity and computes the rank of its trustees:
				// All trustees which have received positive (> 0) trust will get his rank + 1
				// Trustees with negative trust or 0 trust will get a rank of Integer.MAX_VALUE.
				// Trusters with rank Integer.MAX_VALUE cannot inherit their rank to their trustees so the trustees will get no rank at all.
				// Identities with no rank are considered to be not in the trust tree of the own identity and their score will be null / none.
				//
				// Further, if the treeOwner has assigned a trust value to an identity, the rank decision is done by only considering this trust value:
				// The decision of the own identity shall not be overpowered by the view of the remote identities.
				//
				// The purpose of differentiation between Integer.MAX_VALUE and -1 is:
				// Score objects of identities with rank Integer.MAX_VALUE are kept in the database because WoT will usually "hear" about those identities by seeing
				// them in the trust lists of trusted identities (with 0 or negative trust values). So it must store the trust values to those identities and
				// have a way of telling the user "this identity is not trusted" by keeping a score object of them.
				// Score objects of identities with rank -1 are deleted because they are the trustees of distrusted identities and we will not get to the point where
				// we hear about those identities because the only way of hearing about them is importing a trust list of a identity with Integer.MAX_VALUE rank
				// - and we never import their trust lists. 
				// We include trust values of 0 in the set of rank Integer.MAX_VALUE (instead of only NEGATIVE trust) so that identities which only have solved
				// introduction puzzles cannot inherit their rank to their trustees.
				final LinkedList<Identity> unprocessedTrusters = new LinkedList<Identity>();
				
				// The own identity is the root of the trust tree, it should assign itself a rank of 0 , a capacity of 100 and a symbolic score of Integer.MAX_VALUE
				
				try {
					Score selfScore = getScore(treeOwner, treeOwner);
					
					if(selfScore.getRank() >= 0) { // It can only give it's rank if it has a valid one
						rankValues.put(treeOwner, selfScore.getRank());
						unprocessedTrusters.addLast(treeOwner);
					} else {
						// rankValues.put(treeOwner, null); // No need to store null in a Hashtable
					}
				} catch(NotInTrustTreeException e) {
					// This only happens in unit tests.
				}
				 
				while(!unprocessedTrusters.isEmpty()) {
					final Identity truster = unprocessedTrusters.removeFirst();
	
					final Integer trusterRank = rankValues.get(truster);
					
					// The truster cannot give his rank to his trustees because he has none (or infinite), they receive no rank at all.
					if(trusterRank == null || trusterRank == Integer.MAX_VALUE) {
						// (Normally this does not happen because we do not enqueue the identities if they have no rank but we check for security)
						continue;
					}
					
					final int trusteeRank = trusterRank + 1;
					
					for(Trust trust : getGivenTrusts(truster)) {
						final Identity trustee = trust.getTrustee();
						final Integer oldTrusteeRank = rankValues.get(trustee);
						
						
						if(oldTrusteeRank == null) { // The trustee was not processed yet
							if(trust.getValue() > 0) {
								rankValues.put(trustee, trusteeRank);
								unprocessedTrusters.addLast(trustee);
							}
							else
								rankValues.put(trustee, Integer.MAX_VALUE);
						} else {
							// Breadth first search will process all rank one identities are processed before any rank two identities, etc.
							assert(oldTrusteeRank == Integer.MAX_VALUE || trusteeRank >= oldTrusteeRank);
							
							if(oldTrusteeRank == Integer.MAX_VALUE) {
								// If we found a rank less than infinite we can overwrite the old rank with this one, but only if the infinite rank was not
								// given by the tree owner.
								try {
									getTrust(treeOwner, trustee);
								} catch(NotTrustedException e) {
									if(trust.getValue() > 0) {
										rankValues.put(trustee, trusteeRank);
										unprocessedTrusters.addLast(trustee);
									}
								}
							}
						}
					}
				}
			}
			
			// Rank values of all visible identities are computed now.
			// Next step is to compute the scores of all identities
			
			for(Identity target : allIdentities) {
				// The score of an identity is the sum of all weighted trust values it has received.
				// Each trust value is weighted with the capacity of the truster - the capacity decays with increasing rank.
				Integer targetScore;
				final Integer targetRank = rankValues.get(target);
				
				if(targetRank == null) {
					targetScore = null;
				} else {
					// The treeOwner trusts himself.
					if(targetRank == 0) {
						targetScore = Integer.MAX_VALUE;
					}
					else {
						// If the treeOwner has assigned a trust value to the target, it always overrides the "remote" score.
						try {
							targetScore = (int)getTrust(treeOwner, target).getValue();
						} catch(NotTrustedException e) {
							targetScore = 0;
							for(Trust receivedTrust : getReceivedTrusts(target)) {
								final Identity truster = receivedTrust.getTruster();
								final Integer trusterRank = rankValues.get(truster);
								
								// The capacity is a weight function for trust values which are given from an identity:
								// The higher the rank, the less the capacity.
								// If the rank is Integer.MAX_VALUE (infinite) or -1 (no rank at all) the capacity will be 0.
								final int capacity = computeCapacity(treeOwner, truster, trusterRank != null ? trusterRank : -1);
								
								targetScore += (receivedTrust.getValue() * capacity) / 100;
							}
						}
					}
				}
				
				Score expectedScore = targetScore != null ? new Score(treeOwner, target, targetScore, targetRank, computeCapacity(treeOwner, target, targetRank)) : null;
				
				boolean needToCheckFetchStatus = false;
				boolean oldShouldFetch = false;
				
				// Now we have the rank and the score of the target computed and can check whether the database-stored score object is correct.
				try {
					Score storedScore = getScore(treeOwner, target);
					if(expectedScore == null) {
						returnValue = false;
						if(!mFullScoreComputationNeeded)
							Logger.error(this, "Correcting wrong score: The identity has no rank and should have no score but score was " + storedScore);
						
						needToCheckFetchStatus = true;
						oldShouldFetch = shouldFetchIdentity(target);
						
						mDB.delete(storedScore);
						
					} else {
						if(!expectedScore.equals(storedScore)) {
							returnValue = false;
							if(!mFullScoreComputationNeeded)
								Logger.error(this, "Correcting wrong score: Should have been " + expectedScore + " but was " + storedScore);
							
							needToCheckFetchStatus = true;
							oldShouldFetch = shouldFetchIdentity(target);
							
							storedScore.setRank(expectedScore.getRank());
							storedScore.setCapacity(computeCapacity(expectedScore.getTreeOwner(), expectedScore.getTarget(), expectedScore.getRank()));
							storedScore.setValue(expectedScore.getScore());

							mDB.store(storedScore);
						}
					}
				} catch(NotInTrustTreeException e) {
					if(expectedScore != null) {
						returnValue = false;
						if(!mFullScoreComputationNeeded)
							Logger.error(this, "Correcting wrong score: No score was stored for the identity but it should be " + expectedScore);
						
						needToCheckFetchStatus = true;
						oldShouldFetch = shouldFetchIdentity(target);
						
						mDB.store(expectedScore);
					}
				}
				
				if(needToCheckFetchStatus) {
					// If the sign of the identities score changed, then we need to start fetching it or abort fetching it.
					if(!oldShouldFetch && shouldFetchIdentity(target)) { 
						Logger.debug(this, "Best capacity changed from 0 to positive, refetching " + target);

						target.markForRefetch();
						storeWithoutCommit(target);

						if(mFetcher != null) {
							mFetcher.storeStartFetchCommandWithoutCommit(target);
						}
					}
					else if(oldShouldFetch && !shouldFetchIdentity(target)) {
						Logger.debug(this, "Best capacity changed from positive to 0, aborting fetch of " + target);

						if(mFetcher != null) {
							mFetcher.storeAbortFetchCommandWithoutCommit(target);
						}
					}
				}
			}
		}
		
		mFullScoreComputationNeeded = false;
		
		return returnValue;
	}
	
	private synchronized void createSeedIdentities() {
		for(String seedURI : SEED_IDENTITIES) {
			Identity seed;
			
			try { 
				seed = getIdentityByURI(seedURI);
				if(seed instanceof OwnIdentity) {
					OwnIdentity ownSeed = (OwnIdentity)seed;
					// TODO: Does the cast make that necessary? I'm adding it to make sure that we do not lose information when storing
					mDB.activate(ownSeed, 5);
					ownSeed.addContext(IntroductionPuzzle.INTRODUCTION_CONTEXT);
					ownSeed.setProperty(IntroductionServer.PUZZLE_COUNT_PROPERTY,
							Integer.toString(IntroductionServer.SEED_IDENTITY_PUZZLE_COUNT));
					
					ownSeed.setProperty(WOT_NAME + "." + SEED_IDENTITY_MANDATORY_VERSION_PROPERTY, Long.toString(Version.mandatoryVersion));
					ownSeed.setProperty(WOT_NAME + "." + SEED_IDENTITY_LATEST_VERSION_PROPERTY, Long.toString(Version.latestVersion));
					
					storeAndCommit(ownSeed);
				}
				else {
					try {
						seed.setEdition(new FreenetURI(seedURI).getEdition());
						storeAndCommit(seed);
					} catch(InvalidParameterException e) {
						/* We already have the latest edition stored */
					}
				}
			}
			catch (UnknownIdentityException uie) {
				try {
					seed = new Identity(seedURI, null, true);
					// We have to explicitely set the edition number because the constructor only considers the given edition as a hint.
					seed.setEdition(new FreenetURI(seedURI).getEdition());
					storeAndCommit(seed);
				} catch (Exception e) {
					Logger.error(this, "Seed identity creation error", e);
				}
			}
			catch (Exception e) {
				Logger.error(this, "Seed identity loading error", e);
				System.gc(); mDB.rollback(); Logger.debug(this, "ROLLED BACK!");
			}
		}
	}
	
	/**
	 * @param clientApp Can be "WoT" or "Freetalk".
	 * @param onlyGetMandatory Set to true if not the latest version shall be returned but the latest mandatory version.
	 * @return The recommended version of the given client-app which is reported by the majority (> 50%) of the seed identities.
	 */
	public synchronized long getLatestReportedVersion(String clientApp, boolean onlyGetMandatory) {
		Hashtable<Long, Integer> votesForVersions = new Hashtable<Long, Integer>(SEED_IDENTITIES.length);
		
		for(String seedURI : SEED_IDENTITIES) {
			Identity seed;
			
			try { 
				seed = getIdentityByURI(seedURI);
				if(!(seed instanceof OwnIdentity)) {
					
					try {
						 long newVersion = Long.parseLong(seed.getProperty(clientApp + "." + (onlyGetMandatory ? SEED_IDENTITY_MANDATORY_VERSION_PROPERTY :
							 												SEED_IDENTITY_LATEST_VERSION_PROPERTY)));
						 
						 Integer oldVoteCount = votesForVersions.get(newVersion);
						 votesForVersions.put(newVersion, oldVoteCount == null ? 1 : oldVoteCount + 1);
					}
					catch(InvalidParameterException e) {
						/* Seed does not specify a version */
					}
				}
			}
			catch (Exception e) {
				Logger.debug(this, "SHOULD NOT HAPPEN!", e);
			}
		}
		
		for(Entry<Long, Integer> entry: votesForVersions.entrySet()) {
			if(entry.getValue() > (SEED_IDENTITIES.length/2))
				return entry.getKey();
		}
		
		return onlyGetMandatory ? Version.mandatoryVersion : Version.latestVersion;
	}
	

	public void terminate() {
		Logger.debug(this, "WoT plugin terminating ...");
		
		/* We use single try/catch blocks so that failure of termination of one service does not prevent termination of the others */
		try {
			if(mWebInterface != null)
				this.mWebInterface.unload();
		}
		catch(Exception e) {
			Logger.error(this, "Error during termination.", e);
		}
		
		try {
			if(mIntroductionClient != null)
				mIntroductionClient.terminate();
		}
		catch(Exception e) {
			Logger.error(this, "Error during termination.", e);
		}
		
		try {
			if(mIntroductionServer != null)
				mIntroductionServer.terminate();
		}
		catch(Exception e) {
			Logger.error(this, "Error during termination.", e);
		}
		
		try {
			if(mInserter != null)
				mInserter.terminate();
		}
		catch(Exception e) {
			Logger.error(this, "Error during termination.", e);
		}
		
		try {
			if(mFetcher != null)
				mFetcher.stop();
		}
		catch(Exception e) {
			Logger.error(this, "Error during termination.", e);
		}
		
		
		try {
			if(mDB != null) {
				/* TODO: At 2009-06-15, it does not seem possible to ask db4o for whether a transaction is pending.
				 * If it becomes possible some day, we should check that here, and log an error if there is an uncommitted transaction. 
				 * - All transactions should be committed after obtaining the lock() on the database. */
				synchronized(mDB.lock()) {
					System.gc(); mDB.rollback(); Logger.debug(this, "ROLLED BACK!");
					mDB.close();
				}
			}
		}
		catch(Exception e) {
			Logger.error(this, "Error during termination.", e);
		}

		Logger.debug(this, "WoT plugin terminated.");
	}

	/**
	 * Inherited event handler from FredPluginFCP, handled in <code>class FCPInterface</code>.
	 */
	public void handle(PluginReplySender replysender, SimpleFieldSet params, Bucket data, int accesstype) {
		mFCPInterface.handle(replysender, params, data, accesstype);
	}

	/**
	 * Loads an own or normal identity from the database, querying on its ID.
	 * 
	 * @param id The ID of the identity to load
	 * @return The identity matching the supplied ID.
	 * @throws DuplicateIdentityException if there are more than one identity with this id in the database
	 * @throws UnknownIdentityException if there is no identity with this id in the database
	 */
	@SuppressWarnings("unchecked")
	public synchronized Identity getIdentityByID(String id) throws UnknownIdentityException {
		Query query = mDB.query();
		query.constrain(Identity.class);
		query.descend("mID").constrain(id);
		ObjectSet<Identity> result = query.execute();
		
		switch(result.size()) {
			case 1:
				return result.next();
			case 0:
				throw new UnknownIdentityException(id);
			default:
				throw new DuplicateIdentityException(id, result.size());
		}  
	}
	
	/**
	 * Gets an OwnIdentity by its ID.
	 * 
	 * @param id The unique identifier to query an OwnIdentity
	 * @return The requested OwnIdentity
	 * @throws UnknownIdentityException if there is now OwnIdentity with that id
	 */
	@SuppressWarnings("unchecked")
	public synchronized OwnIdentity getOwnIdentityByID(String id) throws UnknownIdentityException {
		Query query = mDB.query();
		query.constrain(OwnIdentity.class);
		query.descend("mID").constrain(id);
		ObjectSet<OwnIdentity> result = query.execute();
		
		switch(result.size()) {
			case 1:
				return result.next();
			case 0:
				throw new UnknownIdentityException(id);
			default:
				throw new DuplicateIdentityException(id, result.size());
		}  
	}

	/**
	 * Loads an identity from the database, querying on its requestURI (a valid {@link FreenetURI})
	 * 
	 * @param uri The requestURI of the identity
	 * @return The identity matching the supplied requestURI
	 * @throws UnknownIdentityException if there is no identity with this id in the database
	 */
	public Identity getIdentityByURI(FreenetURI uri) throws UnknownIdentityException {
		return getIdentityByID(Identity.getIDFromURI(uri));
	}

	/**
	 * Loads an identity from the database, querying on its requestURI (as String)
	 * 
	 * @param uri The requestURI of the identity which will be converted to {@link FreenetURI} 
	 * @return The identity matching the supplied requestURI
	 * @throws UnknownIdentityException if there is no identity with this id in the database
	 * @throws MalformedURLException if the requestURI isn't a valid FreenetURI
	 */
	public Identity getIdentityByURI(String uri) throws UnknownIdentityException, MalformedURLException {
		return getIdentityByURI(new FreenetURI(uri));
	}

	/**
	 * Gets an OwnIdentity by its requestURI (a {@link FreenetURI}).
	 * The OwnIdentity's unique identifier is extracted from the supplied requestURI.
	 * 
	 * @param uri The requestURI of the desired OwnIdentity
	 * @return The requested OwnIdentity
	 * @throws UnknownIdentityException if the OwnIdentity isn't in the database
	 */
	public OwnIdentity getOwnIdentityByURI(FreenetURI uri) throws UnknownIdentityException {
		return getOwnIdentityByID(OwnIdentity.getIDFromURI(uri));
	}

	/**
	 * Gets an OwnIdentity by its requestURI (as String).
	 * The given String is converted to {@link FreenetURI} in order to extract a unique id.
	 * 
	 * @param uri The requestURI (as String) of the desired OwnIdentity
	 * @return The requested OwnIdentity
	 * @throws UnknownIdentityException if the OwnIdentity isn't in the database
	 * @throws MalformedURLException if the supplied requestURI is not a valid FreenetURI
	 */
	public OwnIdentity getOwnIdentityByURI(String uri) throws UnknownIdentityException, MalformedURLException {
		return getOwnIdentityByURI(new FreenetURI(uri));
	}
	
	/**
	 * Returns all identities that are in the database
	 * You have to synchronize on this WoT when calling the function and processing the returned list!
	 * 
	 * @return An {@link ObjectSet} containing all identities present in the database 
	 */
	public synchronized ObjectSet<Identity> getAllIdentities() {
		return mDB.queryByExample(Identity.class);
	}
	
	public static enum SortOrder {
		ByNicknameAscending,
		ByNicknameDescending,
		ByScoreAscending,
		ByScoreDescending,
		ByLocalTrustAscending,
		ByLocalTrustDescending
	}
	
	/**
	 * Get a filtered and sorted list of identities.
	 * You have to synchronize on this WoT when calling the function and processing the returned list.
	 */
	@SuppressWarnings("unchecked")
	public synchronized ObjectSet<Identity> getAllIdentitiesFilteredAndSorted(OwnIdentity treeOwner, String nickFilter, SortOrder sortInstruction) {
		Query q = mDB.query();
		
		switch(sortInstruction) {
			case ByNicknameAscending:
				q.constrain(Identity.class);
				q.descend("mNickname").orderAscending();
				break;
			case ByNicknameDescending:
				q.constrain(Identity.class);
				q.descend("mNickname").orderDescending();
				break;
			case ByScoreAscending:
				q.constrain(Score.class);
				q.descend("mTreeOwner").constrain(treeOwner);
				q.descend("mValue").orderAscending();
				q = q.descend("mTarget"); 
				break;
			case ByScoreDescending:
				// FIXME: This excludes identities which have no score
				q.constrain(Score.class);
				q.descend("mTreeOwner").constrain(treeOwner);
				q.descend("mValue").orderDescending();
				q = q.descend("mTarget");
				break;
			case ByLocalTrustAscending:
				q.constrain(Trust.class);
				q.descend("mTruster").constrain(treeOwner);
				q.descend("mValue").orderAscending();
				q = q.descend("mTrustee");
				break;
			case ByLocalTrustDescending:
				// FIXME: This excludes untrusted identities.
				q.constrain(Trust.class);
				q.descend("mTruster").constrain(treeOwner);
				q.descend("mValue").orderDescending();
				q = q.descend("mTrustee");
				break;
		}
		
		if(nickFilter != null) {
			nickFilter = nickFilter.trim();
			if(!nickFilter.equals("")) q.descend("mNickname").constrain(nickFilter).like();
		}
		
		return q.execute();
	}
	
	/**
	 * Returns all non-own identities that are in the database.
	 * 
	 * You have to synchronize on this WoT when calling the function and processing the returned list!
	 */
	@SuppressWarnings("unchecked")
	public synchronized ObjectSet<Identity> getAllNonOwnIdentities() {
		Query q = mDB.query();
		q.constrain(Identity.class);
		q.constrain(OwnIdentity.class).not();
		return q.execute();
	}
	
	/**
	 * Returns all non-own identities that are in the database, sorted descending by their date of modification, i.e. recently
	 * modified identities will be at the beginning of the list.
	 * 
	 * You have to synchronize on this WoT when calling the function and processing the returned list!
	 * 
	 * Used by the IntroductionClient for fetching puzzles from recently modified identities.
	 */
	@SuppressWarnings("unchecked")
	public synchronized ObjectSet<Identity> getAllNonOwnIdentitiesSortedByModification () {
		Query q = mDB.query();
		q.constrain(Identity.class);
		q.constrain(OwnIdentity.class).not();
		/* TODO: As soon as identities announce that they were online every day, uncomment the following line */
		/* q.descend("mLastChangedDate").constrain(new Date(CurrentTimeUTC.getInMillis() - 1 * 24 * 60 * 60 * 1000)).greater(); */
		q.descend("mLastFetchedDate").orderDescending();
		
		return q.execute();
	}
	
	/**
	 * Returns all own identities that are in the database
	 * You have to synchronize on this WoT when calling the function and processing the returned list!
	 * 
	 * @return An {@link ObjectSet} containing all identities present in the database.
	 */
	public synchronized ObjectSet<OwnIdentity> getAllOwnIdentities() {
		return mDB.queryByExample(OwnIdentity.class);
	}

	/**
	 * Locks the WoT, locks the identity, locks the database and stores the identity.
	 */
	public synchronized void storeAndCommit(Identity identity) {
		synchronized(identity) {
		synchronized(mDB.lock()) {
			try {
				storeWithoutCommit(identity);
				mDB.commit(); Logger.debug(identity, "COMMITED.");
			}
			catch(RuntimeException e) {
				System.gc(); mDB.rollback(); Logger.debug(this, "ROLLED BACK!");
				throw e;
			}
		}
		}
	}
	
	/**
	 * Locks the identity and stores it in the database without committing.
	 * You must synchronize on the WoT, on the identity and then on the database when using this function!
	 * @param identity The identity to store.
	 */
	protected void storeWithoutCommit(Identity identity) {
		
		DBUtil.checkedActivate(mDB, identity, 4);

		try {
			if(identity instanceof OwnIdentity) {
				OwnIdentity ownId = (OwnIdentity)identity;
				mDB.store(ownId.mInsertURI);
				// mDB.store(ownId.mCreationDate); /* Not stored because db4o considers it as a primitive and automatically stores it. */
				// mDB.store(ownId.mLastInsertDate); /* Not stored because db4o considers it as a primitive and automatically stores it. */
			}
			// mDB.store(mID); /* Not stored because db4o considers it as a primitive and automatically stores it. */
			mDB.store(identity.mRequestURI);
			// mDB.store(mFirstFetchedDate); /* Not stored because db4o considers it as a primitive and automatically stores it. */
			// mDB.store(mLastFetchedDate); /* Not stored because db4o considers it as a primitive and automatically stores it. */
			// mDB.store(mLastChangedDate); /* Not stored because db4o considers it as a primitive and automatically stores it. */
			// mDB.store(mNickname); /* Not stored because db4o considers it as a primitive and automatically stores it. */
			// mDB.store(mDoesPublishTrustList); /* Not stored because db4o considers it as a primitive and automatically stores it. */
			mDB.store(identity.mProperties);
			mDB.store(identity.mContexts);
			mDB.store(identity);
		}
		catch(RuntimeException e) {
			System.gc(); mDB.rollback(); Logger.debug(this, "ROLLED BACK!");
			throw e;
		}
	}
	
	/**
	 * You have to lock the WoT and the IntroductionPuzzleStore before calling this function.
	 * @param identity
	 */
	private void deleteWithoutCommit(Identity identity) {
		try {
			Logger.debug(this, "Deleting identity " + identity + " ...");
			
			Logger.debug(this, "Deleting received scores...");
			for(Score score : getScores(identity))
				mDB.delete(score);

			if(identity instanceof OwnIdentity) {
				Logger.debug(this, "Deleting given scores...");

				for(Score score : getGivenScores((OwnIdentity)identity))
					mDB.delete(score);
			}

			Logger.debug(this, "Deleting received trusts...");
			for(Trust trust : getReceivedTrusts(identity))
				mDB.delete(trust);

			Logger.debug(this, "Deleting given trusts...");
			for(Trust givenTrust : getGivenTrusts(identity)) {
				mDB.delete(givenTrust);
				// We call computeAllScores anyway so we do not use removeTrustWithoutCommit()
			}
			
			computeAllScoresWithoutCommit();

			Logger.debug(this, "Deleting associated introduction puzzles ...");
			mPuzzleStore.onIdentityDeletion(identity);

			Logger.debug(this, "Deleting the identity...");

			DBUtil.checkedActivate(mDB, identity, 4);

			if(mFetcher != null)
				mFetcher.storeAbortFetchCommandWithoutCommit(identity);

			if(identity instanceof OwnIdentity) {
				OwnIdentity ownId = (OwnIdentity)identity;
				ownId.mInsertURI.removeFrom(mDB);
				// mDB.delete(ownId.mCreationDate); /* Not stored because db4o considers it as a primitive and automatically stores it. */
				// mDB.delete(ownId.mLastInsertDate); /* Not stored because db4o considers it as a primitive and automatically stores it. */
			}
			// mDB.delete(mID); /* Not stored because db4o considers it as a primitive and automatically stores it. */
			identity.mRequestURI.removeFrom(mDB);
			// mDB.delete(mFirstFetchedDate); /* Not stored because db4o considers it as a primitive and automatically stores it. */
			// mDB.delete(mLastFetchedDate); /* Not stored because db4o considers it as a primitive and automatically stores it. */
			// mDB.delete(mLastChangedDate); /* Not stored because db4o considers it as a primitive and automatically stores it. */
			// mDB.delete(mNickname); /* Not stored because db4o considers it as a primitive and automatically stores it. */
			// mDB.delete(mDoesPublishTrustList); /* Not stored because db4o considers it as a primitive and automatically stores it. */
			mDB.delete(identity.mProperties);
			mDB.delete(identity.mContexts);
			mDB.delete(identity);
		}
		catch(RuntimeException e) {
			System.gc(); mDB.rollback(); Logger.debug(this, "ROLLED BACK!");
			throw e;
		}
	}

	/**
	 * Gets the score of this identity in a trust tree.
	 * Each {@link OwnIdentity} has its own trust tree.
	 * 
	 * @param treeOwner The owner of the trust tree
	 * @return The {@link Score} of this Identity in the required trust tree
	 * @throws NotInTrustTreeException if this identity is not in the required trust tree 
	 */
	@SuppressWarnings("unchecked")
	public synchronized Score getScore(OwnIdentity treeOwner, Identity target) throws NotInTrustTreeException {
		Query query = mDB.query();
		query.constrain(Score.class);
		query.descend("mTreeOwner").constrain(treeOwner).identity();
		query.descend("mTarget").constrain(target).identity();
		ObjectSet<Score> result = query.execute();
		
		switch(result.size()) {
			case 1:
				return result.next();
			case 0:
				throw new NotInTrustTreeException(treeOwner, target);
			default:
				throw new DuplicateScoreException(treeOwner, target, result.size());
		}
	}
	
	/* 
	 * FIXME:
	 * I suggest before releasing we should write a getRealScore() function which recalculates the score from all Trust objects which are
	 * stored in the database. We could then assert(getScore() == getRealScore()) for verifying that the database is consistent and watch
	 * for some time whether it stays consistent, just to make sure that there are no flaws in the code.
	 */
	
	/**
	 * Gets a list of all this Identity's Scores.
	 * You have to synchronize on this WoT around the call to this function and the processing of the returned list! 
	 * 
	 * @return An {@link ObjectSet} containing all {@link Score} this Identity has.
	 */
	@SuppressWarnings("unchecked")
	public ObjectSet<Score> getScores(Identity identity) {
		Query query = mDB.query();
		query.constrain(Score.class);
		query.descend("mTarget").constrain(identity).identity();
		return query.execute();
	}
	
	/**
	 * Get a list of all scores which the passed own identity has assigned to other identities.
	 * 
	 * You have to synchronize on this WoT around the call to this function and the processing of the returned list! 
	 * @return An {@link ObjectSet} containing all {@link Score} this Identity has given.
	 */
	@SuppressWarnings("unchecked")
	public ObjectSet<Score> getGivenScores(OwnIdentity treeOwner) {
		Query query = mDB.query();
		query.constrain(Score.class);
		query.descend("mTreeOwner").constrain(treeOwner).identity();
		return query.execute();
	}
	
	/**
	 * Gets the best score this Identity has in existing trust trees.
	 * 
	 * @return the best score this Identity has
	 * @throws NotInTrustTreeException If the identity has no score in any trusttree.
	 */
	public synchronized int getBestScore(Identity identity) throws NotInTrustTreeException {
		int bestScore = Integer.MIN_VALUE;
		ObjectSet<Score> scores = getScores(identity);
		
		if(scores.size() == 0)
			throw new NotInTrustTreeException(identity);
		
		// TODO: Cache the best score of an identity as a member variable.
		for(Score score : scores) 
			bestScore = Math.max(score.getScore(), bestScore);
		
		return bestScore;
	}
	
	/**
	 * Gets the best capacity this identity has in any trust tree.
	 * @throws NotInTrustTreeException If the identity is not in any trust tree. Can be interpreted as capacity 0.
	 */
	public int getBestCapacity(Identity identity) throws NotInTrustTreeException {
		int bestCapacity = 0;
		ObjectSet<Score> scores = getScores(identity);
		
		if(scores.size() == 0)
			throw new NotInTrustTreeException(identity);
		
		// TODO: Cache the best score of an identity as a member variable.
		for(Score score : scores) 
			bestCapacity  = Math.max(score.getCapacity(), bestCapacity);
		
		return bestCapacity;
	}
	
	/**
	 * Get all scores in the database.
	 * You have to synchronize on this WoT when calling the function and processing the returned list!
	 */
	public synchronized ObjectSet<Score> getAllScores() {
		return mDB.queryByExample(Score.class);
	}
	
	/**
	 * Checks whether the given identity should be downloaded. 
	 * @return Returns true if the identity has any capacity > 0, any score >= 0 or if it is an own identity.
	 */
	public boolean shouldFetchIdentity(Identity identity) {
		if(identity instanceof OwnIdentity)
			return true;
		
		int bestScore = Integer.MIN_VALUE;
		int bestCapacity = 0;
		ObjectSet<Score> scores = getScores(identity);
			
		if(scores.size() == 0)
			return false;
			
		// TODO: Cache the best score of an identity as a member variable.
		for(Score score : scores) { 
			bestCapacity  = Math.max(score.getCapacity(), bestCapacity);
			bestScore  = Math.max(score.getScore(), bestScore);
			
			if(bestCapacity > 0 || bestScore >= 0)
				return true;
		}
			
		return false;
	}
	
	/**
	 * Gets Identities matching a specified score criteria.
	 * You have to synchronize on this WoT when calling the function and processing the returned list!
	 * 
	 * @param treeOwner The owner of the trust tree, null if you want the trusted identities of all owners.
	 * @param select Score criteria, can be > zero, zero or negative. Greater than zero returns all identities with score >= 0, zero with score equal to 0
	 * 		and negative with score < 0. Zero is included in the positive range by convention because solving an introduction puzzle gives you a trust value of 0.
	 * @return an {@link ObjectSet} containing Scores of the identities that match the criteria
	 */
	@SuppressWarnings("unchecked")
	public synchronized ObjectSet<Score> getIdentitiesByScore(OwnIdentity treeOwner, int select) {		
		Query query = mDB.query();
		query.constrain(Score.class);
		if(treeOwner != null)
			query.descend("mTreeOwner").constrain(treeOwner).identity();
		query.descend("mTarget").constrain(OwnIdentity.class).not();
	
		/* We include 0 in the list of identities with positive score because solving captchas gives no points to score */
		
		if(select > 0)
			query.descend("mValue").constrain(0).smaller().not();
		else if(select < 0 )
			query.descend("mValue").constrain(0).smaller();
		else 
			query.descend("mValue").constrain(0);

		return query.execute();
	}
	
	/**
	 * Gets {@link Trust} from a specified truster to a specified trustee.
	 * 
	 * @param truster The identity that gives trust to this Identity
	 * @param trustee The identity which receives the trust
	 * @return The trust given to the trustee by the specified truster
	 * @throws NotTrustedException if the truster doesn't trust the trustee
	 */
	@SuppressWarnings("unchecked")
	public synchronized Trust getTrust(Identity truster, Identity trustee) throws NotTrustedException, DuplicateTrustException {
		Query query = mDB.query();
		query.constrain(Trust.class);
		query.descend("mTruster").constrain(truster).identity();
		query.descend("mTrustee").constrain(trustee).identity();
		ObjectSet<Trust> result = query.execute();
		
		switch(result.size()) {
			case 1:
				return result.next();
			case 0:
				throw new NotTrustedException(truster, trustee);
			default:
				throw new DuplicateTrustException(truster, trustee, result.size());
		}
	}

	/**
	 * Gets all trusts given by the given truster.
	 * You have to synchronize on this WoT when calling the function and processing the returned list!
	 * 
	 * @return An {@link ObjectSet} containing all {@link Trust} the passed Identity has given.
	 */
	@SuppressWarnings("unchecked")
	public synchronized ObjectSet<Trust> getGivenTrusts(Identity truster) {
		Query query = mDB.query();
		query.constrain(Trust.class);
		query.descend("mTruster").constrain(truster).identity();
		return query.execute();
	}
	
	/**
	 * Gets given trust values of an identity matching a specified trust value criteria.
	 * You have to synchronize on this WoT when calling the function and processing the returned list!
	 * 
	 * @param truster The identity which given the trust values.
	 * @param select Trust value criteria, can be > zero, zero or negative. Greater than zero returns all trust values >= 0, zero returns trust values equal to 0.
	 * 		Negative returns trust values < 0. Zero is included in the positive range by convention because solving an introduction puzzle gives you a value of 0.
	 * @return an {@link ObjectSet} containing received trust values that match the criteria.
	 * @throws NullPointerException If truster is null.
	 */
	@SuppressWarnings("unchecked")
	public synchronized ObjectSet<Trust> getGivenTrusts(Identity truster, int select) {		
		if(truster == null)
			throw new NullPointerException("No truster specified");
		
		Query query = mDB.query();
		query.constrain(Trust.class);
		query.descend("mTruster").constrain(truster).identity();
	
		/* We include 0 in the list of identities with positive trust because solving captchas gives 0 trust */
		
		if(select > 0)
			query.descend("mValue").constrain(0).smaller().not();
		else if(select < 0 )
			query.descend("mValue").constrain(0).smaller();
		else 
			query.descend("mValue").constrain(0);

		return query.execute();
	}
	/**
	 * Gets all trusts given by the given truster in a trust list older than the given edition number.
	 * You have to synchronize on this WoT when calling the function and processing the returned list!
	 */
	@SuppressWarnings("unchecked")
	protected synchronized ObjectSet<Trust> getGivenTrustsOlderThan(Identity truster, long edition) {
		Query q = mDB.query();
		q.constrain(Trust.class);
		q.descend("mTruster").constrain(truster).identity();
		q.descend("mTrusterTrustListEdition").constrain(edition).smaller();
		return q.execute();
	}

	/**
	 * Gets all trusts received by the given trustee.
	 * You have to synchronize on this WoT when calling the function and processing the returned list!
	 * 
	 * @return An {@link ObjectSet} containing all {@link Trust} the passed Identity has received.
	 */
	@SuppressWarnings("unchecked")
	public synchronized ObjectSet<Trust> getReceivedTrusts(Identity trustee) {
		Query query = mDB.query();
		query.constrain(Trust.class);
		query.descend("mTrustee").constrain(trustee).identity();
		return query.execute();
	}
	
	/**
	 * Gets received trust values of an identity matching a specified trust value criteria.
	 * You have to synchronize on this WoT when calling the function and processing the returned list!
	 * 
	 * @param trustee The identity which has received the trust values.
	 * @param select Trust value criteria, can be > zero, zero or negative. Greater than zero returns all trust values >= 0, zero returns trust values equal to 0.
	 * 		Negative returns trust values < 0. Zero is included in the positive range by convention because solving an introduction puzzle gives you a value of 0.
	 * @return an {@link ObjectSet} containing received trust values that match the criteria.
	 * @throws NullPointerException If trustee is null.
	 */
	@SuppressWarnings("unchecked")
	public synchronized ObjectSet<Trust> getReceivedTrusts(Identity trustee, int select) {		
		if(trustee == null)
			throw new NullPointerException("No trustee specified");
		
		Query query = mDB.query();
		query.constrain(Trust.class);
		query.descend("mTrustee").constrain(trustee).identity();
	
		/* We include 0 in the list of identities with positive trust because solving captchas gives 0 trust */
		
		if(select > 0)
			query.descend("mValue").constrain(0).smaller().not();
		else if(select < 0 )
			query.descend("mValue").constrain(0).smaller();
		else 
			query.descend("mValue").constrain(0);

		return query.execute();
	}
	
	/**
	 * Gets all trusts.
	 * You have to synchronize on this WoT when calling the function and processing the returned list!
	 * 
	 * @return An {@link ObjectSet} containing all {@link Trust} the passed Identity has received.
	 */
	@SuppressWarnings("unchecked")
	public synchronized ObjectSet<Trust> getAllTrusts() {
		Query query = mDB.query();
		query.constrain(Trust.class);
		return query.execute();
	}
	
	/**
	 * Gives some {@link Trust} to another Identity.
	 * It creates or updates an existing Trust object and make the trustee compute its {@link Score}.
	 * 
	 * This function does neither lock the database nor commit the transaction. You have to surround it with
	 * synchronized(mDB.lock()) {
	 *     try { ... setTrustWithoutCommit(...); mDB.commit(); }
	 *     catch(RuntimeException e) { System.gc(); mDB.rollback(); throw e; }
	 * }
	 * 
	 * @param truster The Identity that gives the trust
	 * @param trustee The Identity that receives the trust
	 * @param newValue Numeric value of the trust
	 * @param newComment A comment to explain the given value
	 * @throws InvalidParameterException if a given parameter isn't valid, see {@link Trust} for details on accepted values.
	 */
	protected synchronized void setTrustWithoutCommit(Identity truster, Identity trustee, byte newValue, String newComment)
		throws InvalidParameterException {
		
		Trust trust;
		try { // Check if we are updating an existing trust value
			trust = getTrust(truster, trustee);
			Trust oldTrust = trust.clone();
			trust.trusterEditionUpdated();
			trust.setComment(newComment);
			mDB.store(trust);

			if(trust.getValue() != newValue) {
				trust.setValue(newValue);
				mDB.store(trust);
				Logger.debug(this, "Updated trust value ("+ trust +"), now updating Score.");
				updateScoresWithoutCommit(oldTrust, trust);
			}
		} catch (NotTrustedException e) {
			trust = new Trust(truster, trustee, newValue, newComment);
			mDB.store(trust);
			Logger.debug(this, "New trust value ("+ trust +"), now updating Score.");
			updateScoresWithoutCommit(null, trust);
		} 

		truster.updated();
		storeWithoutCommit(truster);
	}
	
	/**
	 * Only for being used by WoT internally and by unit tests!
	 */
	synchronized void setTrust(OwnIdentity truster, Identity trustee, byte newValue, String newComment)
		throws InvalidParameterException {
		
		synchronized(mDB.lock()) {
			try {
				setTrustWithoutCommit(truster, trustee, newValue, newComment);
				mDB.commit(); Logger.debug(this, "COMMITED.");
			}
			catch(RuntimeException e) {
				System.gc(); mDB.rollback(); Logger.debug(this, "ROLLED BACK!");
				throw e;
			}
		}
	}
	
	/**
	 * Deletes a trust object.
	 * 
	 * This function does neither lock the database nor commit the transaction. You have to surround it with
	 * synchronized(mDB.lock()) {
	 *     try { ... removeTrustWithoutCommit(...); mDB.commit(); }
	 *     catch(RuntimeException e) { System.gc(); mDB.rollback(); throw e; }
	 * }
	 * 
	 * @param truster
	 * @param trustee
	 */
	protected synchronized void removeTrustWithoutCommit(OwnIdentity truster, Identity trustee) {
			try {
				try {
					removeTrustWithoutCommit(getTrust(truster, trustee));
				} catch (NotTrustedException e) {
					Logger.error(this, "Cannot remove trust - there is none - from " + truster.getNickname() + " to "
						+ trustee.getNickname());
				} 
			}
			catch(RuntimeException e) {
				System.gc(); mDB.rollback(); Logger.debug(this, "ROLLED BACK!");
				throw e;
			}
	}
	
	/**
	 * 
	 * This function does neither lock the database nor commit the transaction. You have to surround it with
	 * synchronized(mDB.lock()) {
	 *     try { ... setTrustWithoutCommit(...); mDB.commit(); }
	 *     catch(RuntimeException e) { System.gc(); mDB.rollback(); throw e; }
	 * }
	 * 
	 */
	protected synchronized void removeTrustWithoutCommit(Trust trust) {
		mDB.delete(trust);
		updateScoresWithoutCommit(trust, null);
	}
	
	/**
	 * Initializes this OwnIdentity's trust tree without commiting the transaction. 
	 * Meaning : It creates a Score object for this OwnIdentity in its own trust so it can give trust to other Identities. 
	 * 
	 * The score will have a rank of 0, a capacity of 100 (= 100 percent) and a score value of Integer.MAX_VALUE.
	 * 
	 * This function does neither lock the database nor commit the transaction. You have to surround it with
	 * synchronized(mDB.lock()) {
	 *     try { ... initTrustTreeWithoutCommit(...); mDB.commit(); }
	 *     catch(RuntimeException e) { System.gc(); mDB.rollback(); throw e; }
	 * }
	 *  
	 * @throws DuplicateScoreException if there already is more than one Score for this identity (should never happen)
	 */
	private synchronized void initTrustTreeWithoutCommit(OwnIdentity identity) throws DuplicateScoreException {
		try {
			getScore(identity, identity);
			Logger.error(this, "initTrusTree called even though there is already one for " + identity);
			return;
		} catch (NotInTrustTreeException e) {
			mDB.store(new Score(identity, identity, Integer.MAX_VALUE, 0, 100));
		}
	}
	
	/**
	 * Updates this Identity's {@link Score} in every trust tree.
	 * 
	 * This function does neither lock the database nor commit the transaction. You have to surround it with
	 * synchronized(mDB.lock()) {
	 *     try { ... updateScoreWithoutCommit(...); storeAndCommit(trustee); }
	 *     catch(RuntimeException e) { System.gc(); mDB.rollback(); throw e; }
	 * }
	 * 
	 */
//	private synchronized void updateScoreWithoutCommit(Identity trustee) {
//		ObjectSet<OwnIdentity> treeOwners = getAllOwnIdentities();
//		if(treeOwners.size() == 0)
//			Logger.debug(this, "Can't update " + trustee.getNickname() + "'s score: there is no own identity yet");
//
//		while(treeOwners.hasNext())
//			updateScoreWithoutCommit(treeOwners.next(), trustee);
//	}
	
	/**
	 * Computes the target's Score value according to the trusts it has received and the capacity of its trusters in the specified
	 * trust tree.
	 * 
	 * @param treeOwner The OwnIdentity that owns the trust tree
	 * @return The new Score if this Identity
	 * @throws DuplicateScoreException if there already exist more than one {@link Score} objects for the trustee (should never happen)
	 */
	private synchronized int computeScoreValue(OwnIdentity treeOwner, Identity target) throws DuplicateScoreException {
		if(target == treeOwner)
			return Integer.MAX_VALUE;
		
		int value = 0;
		
		try {
			return getTrust(treeOwner, target).getValue();
		}
		catch(NotTrustedException e) { }
		
		for(Trust trust : getReceivedTrusts(target)) {
			try {
				final Score trusterScore = getScore(treeOwner, trust.getTruster());
				value += ( trust.getValue() * trusterScore.getCapacity() ) / 100;
			} catch (NotInTrustTreeException e) {}
		}
		return value;
	}
	
	/**
	 * Computes the target's rank in the trust tree.
	 * It gets its best ranked non-zero-capacity truster's rank, plus one.
	 * If it has only received negative trust values from identities which have a non-zero-capacity it gets a rank of Integer.MAX_VALUE (infinite).
	 * If it has only received trust values from identities with rank of Integer.MAX_VALUE it gets a rank of -1.
	 * 
	 * If the tree owner has assigned a trust value to the identity, the rank computation is only done from that value because the score decisions of the
	 * tree owner are always absolute (if you distrust someone, the remote identities should not be allowed to overpower your decision).
	 * 
	 * The purpose of differentiation between Integer.MAX_VALUE and -1 is:
	 * Score objects of identities with rank Integer.MAX_VALUE are kept in the database because WoT will usually "hear" about those identities by seeing them
	 * in the trust lists of trusted identities (with negative trust values). So it must store the trust values to those identities and have a way of telling the
	 * user "this identity is not trusted" by keeping a score object of them.
	 * Score objects of identities with rank -1 are deleted because they are the trustees of distrusted identities and we will not get to the point where we
	 * hear about those identities because the only way of hearing about them is downloading a trust list of a identity with Integer.MAX_VALUE rank - and
	 * we never download their trust lists. 
	 * 
	 * Notice that 0 is included in infinite rank to prevent identities which have only solved introduction puzzles from having a capacity.
	 * 
	 * @param treeOwner The OwnIdentity that owns the trust tree
	 * @return The new Rank if this Identity
	 * @throws DuplicateScoreException if there already exist more than one {@link Score} objects for the trustee (should never happen)
	 */
	private synchronized int computeRank(OwnIdentity treeOwner, Identity target) throws DuplicateScoreException {
		if(target == treeOwner)
			return 0;
		
		int rank = -1;
		
		try {
			Trust treeOwnerTrust = getTrust(treeOwner, target);
			
			if(treeOwnerTrust.getValue() > 0)
				return 1;
			else
				return Integer.MAX_VALUE;
		} catch(NotTrustedException e) { }
		
		for(Trust trust : getReceivedTrusts(target)) {
			try {
				Score score = getScore(treeOwner, trust.getTruster());

				if(score.getCapacity() != 0) { // If the truster has no capacity, he can't give his rank
					// A truster only gives his rank to a trustee if he has assigned a strictly positive trust value
					if(trust.getValue() > 0 ) {
						// We give the rank to the trustee if it is better than its current rank or he has no rank yet. 
						if(rank == -1 || score.getRank() < rank)  
							rank = score.getRank();						
					} else {
						// If the trustee has no rank yet we give him an infinite rank. because he is distrusted by the truster.
						if(rank == -1)
							rank = Integer.MAX_VALUE;
					}
				}
			} catch (NotInTrustTreeException e) {}
		}
		
		if(rank == -1)
			return -1;
		else if(rank == Integer.MAX_VALUE)
			return Integer.MAX_VALUE;
		else
			return rank+1;
	}
	
	/**
	 * Begins the import of a trust list. This sets a flag on this WoT which signals that the import of a trust list is in progress.
	 * This speeds up setTrust/removeTrust as the score calculation is only performed when endTrustListImport is called.
	 * 
	 * You MUST synchronize on this WoT around beginTrustListImport, abortTrustListImport and finishTrustListImport!
	 * You MUST create a database transaction by synchronizing on db.lock().
	 */
	protected void beginTrustListImport() {
		if(mTrustListImportInProgress) {
			abortTrustListImport(new RuntimeException("There was already a trust list import in progress!"));
			mFullScoreComputationNeeded = true;
			computeAllScoresWithoutCommit();
			assert(mFullScoreComputationNeeded == false);
		}
		
		mTrustListImportInProgress = true;
		assert(!mFullScoreComputationNeeded);
		assert(computeAllScoresWithoutCommit()); // The database is intact before the import
	}
	
	/**
	 * See {@link beginTrustListImport} for an explanation of the purpose of this function.
	 * 
	 * Aborts the import of a trust list and rolls back the current transaction.
	 */
	protected void abortTrustListImport(Exception e) {
		assert(mTrustListImportInProgress);
		mTrustListImportInProgress = false;
		mFullScoreComputationNeeded = false;
		System.gc(); mDB.rollback(); Logger.error(this, "ROLLED BACK!", e);
		assert(computeAllScoresWithoutCommit()); // Test rollback.
	}
	
	/**
	 * See {@link beginTrustListImport} for an explanation of the purpose of this function.
	 * 
	 * Finishes the import of the current trust list and clears the "trust list 
	 * 
	 * Does NOT commit the transaction, you must do this.
	 */
	protected void finishTrustListImport() {
		if(!mTrustListImportInProgress) {
			Logger.error(this, "There was no trust list import in progress!");
			return;
		}
		
		if(mFullScoreComputationNeeded) {
			computeAllScoresWithoutCommit();
			assert(!mFullScoreComputationNeeded); // It properly clears the flag
			assert(computeAllScoresWithoutCommit()); // computeAllScoresWithoutCommit() is stable
		}
		
		mTrustListImportInProgress = false;
	}
	
	/**
	 * Updates all trust trees which are affected by the given modified score.
	 * For understanding how score calculation works you should first read {@link computeAllScores
	 * 
	 * This function does neither lock the database nor commit the transaction. You have to surround it with
	 * synchronized(mDB.lock()) {
	 *     try { ... updateScoreWithoutCommit(...); mDB.commit(); }
	 *     catch(RuntimeException e) { System.gc(); mDB.rollback(); throw e; }
	 * }
	 * 
	 * @param treeOwner The OwnIdentity that owns the trust tree
	 */
	private synchronized void updateScoresWithoutCommit(final Trust oldTrust, final Trust newTrust) {
		final boolean trustWasCreated = (oldTrust == null);
		final boolean trustWasDeleted = (newTrust == null);
		final boolean trustWasModified = !trustWasCreated && !trustWasDeleted;
		
		if(trustWasCreated && trustWasDeleted)
			throw new NullPointerException("No old/new trust specified.");
		
		if(trustWasModified && oldTrust.getTruster() != newTrust.getTruster())
			throw new IllegalArgumentException("oldTrust has different truster, oldTrust:" + oldTrust + "; newTrust: " + newTrust);
		
		if(trustWasModified && oldTrust.getTrustee() != newTrust.getTrustee())
			throw new IllegalArgumentException("oldTrust has different trustee, oldTrust:" + oldTrust + "; newTrust: " + newTrust);

		if(!mFullScoreComputationNeeded && (trustWasCreated || trustWasModified)) {
			for(OwnIdentity treeOwner : getAllOwnIdentities()) {
				try {
					// Throws to abort the update of the trustee's score: If the truster has no rank or capacity in the tree owner's view then we don't need to update the trustee's score.
					if(getScore(treeOwner, newTrust.getTruster()).getCapacity() == 0)
						continue;
				} catch(NotInTrustTreeException e) {
					continue;
				}
				
				// We cannot iteratively REMOVE an inherited rank from the trustees because we don't know whether there is a circle in the trust values
				// which would make the current identity get its old rank back via the circle: computeRank searches the trusters of an identity for the best
				// rank, if we remove the rank from an identity, all its trustees will have a better rank and if one of them trusts the original identity
				// then this function would run into an infinite loop. Decreasing or incrementing an existing rank is possible with this function because
				// the rank received from the trustees will always be higher (that is exactly 1 more) than this identities rank.
				if(trustWasModified && oldTrust.getValue() > 0 && newTrust.getValue() <= 0) {
					mFullScoreComputationNeeded = true;
					break;
				}
				
				final LinkedList<Trust> unprocessedEdges = new LinkedList<Trust>();
				unprocessedEdges.add(newTrust);

				while(!unprocessedEdges.isEmpty()) {
					final Trust trust = unprocessedEdges.removeFirst();
					final Identity trustee = trust.getTrustee();
					
					if(trustee == treeOwner)
						continue;

					Score trusteeScore;

					try {
						trusteeScore = getScore(treeOwner, trustee);
					} catch(NotInTrustTreeException e) {
						trusteeScore = new Score(treeOwner, trustee, 0, -1, 0);
					}

					final Score oldScore = trusteeScore.clone();
					boolean oldShouldFetch = shouldFetchIdentity(trustee);
					
					trusteeScore.setValue(computeScoreValue(treeOwner, trustee));
					trusteeScore.setRank(computeRank(treeOwner, trustee));
					trusteeScore.setCapacity(computeCapacity(treeOwner, trustee, trusteeScore.getRank()));

					// Normally we couldn't detect the following two cases due to circular trust values. However, if an own identity assigns a trust value,
					// the rank and capacity are always computed based on the trust value of the own identity so we must also check this here:

					if((oldScore.getRank() >= 0 && oldScore.getRank() < Integer.MAX_VALUE) // It had an inheritable rank
							&& (trusteeScore.getRank() == -1 || trusteeScore.getRank() == Integer.MAX_VALUE)) { // It has no inheritable rank anymore
						mFullScoreComputationNeeded = true;
						break;
					}
					
					if(oldScore.getCapacity() > 0 && trusteeScore.getCapacity() == 0) {
						mFullScoreComputationNeeded = true;
						break;
					}
					
					// Identities should not get into the queue if they have no rank, see the large if() about 20 lines below
					assert(trusteeScore.getRank() >= 0); 
					
					if(trusteeScore.getRank() >= 0)
						mDB.store(trusteeScore);
					
					if(!oldShouldFetch && shouldFetchIdentity(trustee)) { 
						Logger.debug(this, "Fetch status changed from false to true, refetching " + trustee);

						trustee.markForRefetch();
						storeWithoutCommit(trustee);

						if(mFetcher != null)
							mFetcher.storeStartFetchCommandWithoutCommit(trustee);
					}
					else if(oldShouldFetch && !shouldFetchIdentity(trustee)) {
						Logger.debug(this, "Fetch status changed from true to false, aborting fetch of " + trustee);

						if(mFetcher != null)
							mFetcher.storeAbortFetchCommandWithoutCommit(trustee);
					}
					
					// If the rank or capacity changed then the trustees might be affected because the could have inherited theirs
					if(oldScore.getRank() != trusteeScore.getRank() || oldScore.getCapacity() != trusteeScore.getCapacity()) {
						// If this identity has no capacity or no rank then it cannot affect its trustees:
						// (- If it had none and it has none now then there is none which can be inherited, this is obvious)
						// - If it had one before and it was removed, this algorithm will have aborted already because a full computation is needed
						if(trusteeScore.getCapacity() > 0 || (trusteeScore.getRank() >= 0 && trusteeScore.getRank() < Integer.MAX_VALUE)) {
							// We need to update the trustees of trustee
							for(Trust givenTrust : getGivenTrusts(trustee)) {
								unprocessedEdges.add(givenTrust);
							}
						}
					}
				}
				
				if(mFullScoreComputationNeeded)
					break;
			}
		}
		
		if(trustWasDeleted) { 
			mFullScoreComputationNeeded = true;
		}
		
		// If we're in debug mode we always run computeAllScores to find errors in this function.
		assert(mFullScoreComputationNeeded || (!mFullScoreComputationNeeded && computeAllScoresWithoutCommit()));
		
		if(mFullScoreComputationNeeded && !mTrustListImportInProgress) {
			// FIXME before 0.4.0 final: Write a computeAllScores() which does not keep all objects in memory.
			// TODO: This uses very much CPU and memory. Find a better solution!
			computeAllScoresWithoutCommit();
			assert(computeAllScoresWithoutCommit()); // It is stable
		}
		
		
//		
//		if(target == treeOwner)
//			return;
//		
//		boolean changedCapacity = false;
//		
//		Logger.debug(target, "Updating " + target.getNickname() + "'s score in " + treeOwner.getNickname() + "'s trust tree...");
//		
//		Score score;
//		int value = computeScoreValue(treeOwner, target);
//		int rank = computeRank(treeOwner, target);
//		
//		
//		
//		if(rank == -1) { // -1 value means the identity is not in the trust tree
//			try { // If he had a score, we delete it
//				score = getScore(treeOwner, target);
//				mDB.delete(score); // He had a score, we delete it
//				changedCapacity = true;
//				Logger.debug(target, target.getNickname() + " is not in " + treeOwner.getNickname() + "'s trust tree anymore");
//			} catch (NotInTrustTreeException e) { } 
//		}
//		else { // The identity is in the trust tree
//			
//			/* We must detect if an identity had a null or negative score which has changed to positive:
//			 * If we download the trust list of someone who has no or negative score we do not create the identities in his trust list.
//			 * If the identity now gets a positive score we must re-download his current trust list. */
//			boolean scoreSignChanged;
//		
//			try { // Get existing score or create one if needed
//				score = getScore(treeOwner, target);
//				scoreSignChanged = Integer.signum(score.getScore()) != Integer.signum(value);
//			} catch (NotInTrustTreeException e) {
//				score = new Score(treeOwner, target, 0, -1, 0);
//				scoreSignChanged = true;
//			}
//			
//			boolean oldShouldFetch = true;
//			
//			if(scoreSignChanged) // No need to figure out whether the identity should have been fetched in the past if the score sign did not change
//				oldShouldFetch = shouldFetchIdentity(target);
//			
//			score.setValue(value);
//			score.setRank(rank);
//			
//			int oldCapacity = score.getCapacity();
//			
//			boolean hasNegativeTrust = false;
//			// Does the treeOwner personally distrust this identity ?
//			try {
//				if(getTrust(treeOwner, target).getValue() < 0) {
//					hasNegativeTrust = true;
//					Logger.debug(target, target.getNickname() + " received negative trust from " + treeOwner.getNickname() + 
//							" and therefore has no capacity in his trust tree.");
//				}
//			} catch (NotTrustedException e) {}
//			
//			if(hasNegativeTrust)
//				score.setCapacity(0);
//			else
//				score.setCapacity(computeCapacity(score.getRank()));
//			
//			if(score.getCapacity() != oldCapacity)
//				changedCapacity = true;
//			
//			mDB.store(score);
//			
//			
//			if(scoreSignChanged) {
//				if(!oldShouldFetch && shouldFetchIdentity(target)) { 
//					Logger.debug(this, "Best score changed from negative/null to positive, refetching " + target);
//					
//					target.markForRefetch();
//					storeWithoutCommit(target);
//					
//					if(mFetcher != null) {
//						mFetcher.storeStartFetchCommandWithoutCommit(target);
//					}
//				}
//				
//				if(oldShouldFetch && !shouldFetchIdentity(target)) {
//					Logger.debug(this, "Best score changed from positive/null to negative, aborting fetch of " + target);
//					
//					if(mFetcher != null)
//						mFetcher.storeAbortFetchCommandWithoutCommit(target);
//				}
//			}
//			
//			
//			Logger.debug(target, "New score: " + score.toString());
//		}
//		
//		if(changedCapacity) { // We have to update trustees' score
//			ObjectSet<Trust> givenTrusts = getGivenTrusts(target);
//			Logger.debug(target, target.getNickname() + "'s capacity has changed in " + treeOwner.getNickname() +
//					"'s trust tree, updating his (" + givenTrusts.size() + ") trustees");
//			
//			for(Trust givenTrust : givenTrusts)
//				updateScoreWithoutCommit(treeOwner, givenTrust.getTrustee());
//		}
		
	}

	
	/* Client interface functions */
	
	public synchronized Identity addIdentity(String requestURI) throws MalformedURLException, InvalidParameterException {
		Identity identity;
		
		try {
			identity = getIdentityByURI(requestURI);
			Logger.debug(this, "Tried to manually add an identity we already know, ignored.");
			throw new InvalidParameterException("We already have this identity");
		}
		catch(UnknownIdentityException e) {
			// TODO: The identity won't be fetched because it has not received a trust value yet.
			// IMHO we should not support adding identities without giving them a trust value.
			
			//try {
			identity = new Identity(requestURI, null, false);
			storeAndCommit(identity);
			//storeWithoutCommit(identity);
			Logger.debug(this, "Created identity " + identity);
			
			//if(!shouldFetchIdentity(identity)) {
			//	assert(false);
			//	Logger.error(this, "shouldFetchIdentity() returned false for manually added identity!");
			//}
			
			//mFetcher.storeStartFetchCommandWithoutCommit(identity);
			//mDB.commit(); Logger.debug(this, "COMMITED.");
			//}
			//catch(RuntimeException error) {
			//	System.gc(); mDB.rollback(); Logger.error(this, "ROLLED BACK: addIdentity() failed", e);
			//	throw error;
			//}
		}
		
		return identity;
	}
	
	public synchronized void deleteIdentity(Identity identity) {
		synchronized(mPuzzleStore) {
		synchronized(identity) {
		synchronized(mDB.lock()) {
			try {
				deleteWithoutCommit(identity);
				mDB.commit(); Logger.debug(this, "COMMITED.");
			}
			catch(RuntimeException e) {
				System.gc(); mDB.rollback(); Logger.debug(this, "ROLLED BACK!");
				throw e;
			}
		}
		}
		}
	}
	
	public synchronized void deleteIdentity(String id) throws UnknownIdentityException {
		Identity identity = getIdentityByID(id);
		deleteIdentity(identity);
	}
	
	public OwnIdentity createOwnIdentity(String nickName, boolean publishTrustList, String context)
		throws MalformedURLException, InvalidParameterException {
		
		FreenetURI[] keypair = getPluginRespirator().getHLSimpleClient().generateKeyPair("WoT");
		return createOwnIdentity(keypair[0].toString(), keypair[1].toString(), nickName, publishTrustList, context);
	}

	/**
	 * @param context A context with which you want to use the identity. Null if you want to add it later.
	 */
	public synchronized OwnIdentity createOwnIdentity(String insertURI, String requestURI, String nickName,
			boolean publishTrustList, String context) throws MalformedURLException, InvalidParameterException {
		
		synchronized(mDB.lock()) {
			OwnIdentity identity;
			
			try {
				identity = getOwnIdentityByURI(requestURI);
				Logger.debug(this, "Tried to create an own identity with an already existing request URI.");
				throw new InvalidParameterException("The URI you specified is already used by the own identity " +
						identity.getNickname() + ".");
			}
			catch(UnknownIdentityException uie) {
				identity = new OwnIdentity(new FreenetURI(insertURI), new FreenetURI(requestURI), nickName, publishTrustList);
				if(context != null)
					identity.addContext(context);
				
				if(publishTrustList) {
					identity.addContext(IntroductionPuzzle.INTRODUCTION_CONTEXT); /* FIXME: make configureable */
					identity.setProperty(IntroductionServer.PUZZLE_COUNT_PROPERTY, Integer.toString(IntroductionServer.DEFAULT_PUZZLE_COUNT));
				}
				
				try {
					storeWithoutCommit(identity);
					initTrustTreeWithoutCommit(identity);
					
					for(String seedURI : SEED_IDENTITIES) {
						try {
							setTrustWithoutCommit(identity, getIdentityByURI(seedURI), (byte)100, "I trust the Freenet developers.");
						} catch(UnknownIdentityException e) {
							Logger.error(this, "SHOULD NOT HAPPEN: Seed identity not known: " + e);
						}
					}
					
					mDB.commit(); Logger.debug(this, "COMMITED.");
					
					if(mIntroductionClient != null)
						mIntroductionClient.nextIteration(); // This will make it fetch more introduction puzzles.
					
					Logger.debug(this, "Successfully created a new OwnIdentity (" + identity.getNickname() + ")");
					return identity;
				}
				catch(RuntimeException e) {
					System.gc(); mDB.rollback(); Logger.debug(this, "ROLLED BACK!");
					throw e;
				}
			}
		}
	}

	public synchronized void restoreIdentity(String requestURI, String insertURI) throws MalformedURLException, InvalidParameterException {
		OwnIdentity identity;
		synchronized(mPuzzleStore) {
		synchronized(mDB.lock()) {
			try {
				FreenetURI requestFreenetURI = new FreenetURI(requestURI);
				FreenetURI insertFreenetURI = new FreenetURI(insertURI);
				
				if(requestFreenetURI.isSSKForUSK()) requestFreenetURI = requestFreenetURI.uskForSSK();
				if(insertFreenetURI.isSSKForUSK()) insertFreenetURI = insertFreenetURI.uskForSSK();
				
				long edition = Math.max(requestFreenetURI.getEdition(), insertFreenetURI.getEdition());
				
				try {
					Identity old = getIdentityByURI(requestURI);
					
					if(old instanceof OwnIdentity)
						throw new InvalidParameterException("There is already an own identity with the given URI pair.");
					
					edition = Math.max(old.getEdition(), edition);
					
					// We already have fetched this identity as a stranger's one. We need to update the database.
					identity = new OwnIdentity(insertFreenetURI, requestFreenetURI, old.getNickname(), old.doesPublishTrustList());
					/* We re-fetch the current edition to make sure all trustees are imported */
					identity.restoreEdition(edition);
				
					identity.setContexts(old.getContexts());
					identity.setProperties(old.getProperties());
	
					// Update all received trusts
					for(Trust oldReceivedTrust : getReceivedTrusts(old)) {
						Trust newReceivedTrust = new Trust(oldReceivedTrust.getTruster(), identity,
								oldReceivedTrust.getValue(), oldReceivedTrust.getComment());
						
						mDB.store(newReceivedTrust);
					}
		
					// Update all received scores
					for(Score oldScore : getScores(old)) {
						Score newScore = new Score(oldScore.getTreeOwner(), identity, oldScore.getScore(),
								oldScore.getRank(), oldScore.getCapacity());
						
						mDB.store(newScore);
					}
		
					storeWithoutCommit(identity);
					initTrustTreeWithoutCommit(identity);
					
					// Update all given trusts
					for(Trust givenTrust : getGivenTrusts(old)) {
						// TODO: Deleting the trust object right here would save us N score recalculations for N trust objects and probably make
						// restoreIdentity() almost twice as fast:
						// deleteWithoutCommit() calls updateScoreWithoutCommit() per trust value, setTrustWithoutCommit() also does that
						// However, the current approach of letting deleteWithoutCommit() do all the deletions is more clean. Therefore,
						// we should introduce the db.delete(givenTrust)  hereonly after having a unit test for restoreIdentity().
						setTrustWithoutCommit(identity, givenTrust.getTrustee(), givenTrust.getValue(), givenTrust.getComment());
					}
		
					// Remove the old identity and all objects associated with it.
					deleteWithoutCommit(old);
					
					Logger.debug(this, "Successfully restored an already known identity from Freenet (" + identity.getNickname() + ")");
					
				} catch (UnknownIdentityException e) {
					identity = new OwnIdentity(new FreenetURI(insertURI), new FreenetURI(requestURI), null, false);
					identity.restoreEdition(edition);
					identity.updateLastInsertDate();
					
					// TODO: Instead of deciding by date whether the current edition was inserted, we should probably decide via a boolean.
					
					// Store the new identity
					storeWithoutCommit(identity);
					initTrustTreeWithoutCommit(identity);
					
					Logger.debug(this, "Successfully restored not-yet-known identity from Freenet (" + identity.getRequestURI() + ")");
				}
				
				// This is not really necessary because OwnIdenity.needsInsert() returns false if currentEditionWasFetched() is false.
				// However, we still do it because the user might have specified URIs with old edition numbers: Then the IdentityInserter would
				// start insertion the old trust lists immediately after the first one was fetched. With the last insert date being set to current
				// time, this is less likely to happen because the identity inserter has a minimal delay between last insert and next insert.
				identity.updateLastInsertDate();
				
				mFetcher.storeStartFetchCommandWithoutCommit(identity);
				mDB.commit(); Logger.debug(this, "COMMITED.");
			}
			catch(RuntimeException e) {
				System.gc(); mDB.rollback(); Logger.debug(this, "ROLLED BACK!");
				throw e;
			}
		}
		}
	}

	public synchronized void setTrust(String ownTrusterID, String trusteeID, byte value, String comment)
		throws UnknownIdentityException, NumberFormatException, InvalidParameterException {
		
		OwnIdentity truster = getOwnIdentityByID(ownTrusterID);
		Identity trustee = getIdentityByID(trusteeID);
		
		setTrust(truster, trustee, value, comment);
	}
	
	public synchronized void removeTrust(String ownTrusterID, String trusteeID) throws UnknownIdentityException {
		OwnIdentity truster = getOwnIdentityByID(ownTrusterID);
		Identity trustee = getIdentityByID(trusteeID);

		synchronized(mDB.lock()) {
			try  {
				removeTrustWithoutCommit(truster, trustee);
				mDB.commit(); Logger.debug(this, "COMMITED.");
			}
			catch(RuntimeException e)
			{
				System.gc(); mDB.rollback(); Logger.debug(this, "ROLLED BACK!");
				throw e;
			}
		}
	}
	
	public synchronized void addContext(String ownIdentityID, String newContext) throws UnknownIdentityException, InvalidParameterException {
		Identity identity = getOwnIdentityByID(ownIdentityID);
		identity.addContext(newContext);
		storeAndCommit(identity);
		
		Logger.debug(this, "Added context '" + newContext + "' to identity '" + identity.getNickname() + "'");
	}

	public synchronized void removeContext(String ownIdentityID, String context) throws UnknownIdentityException, InvalidParameterException {
		Identity identity = getOwnIdentityByID(ownIdentityID);
		identity.removeContext(context);
		storeAndCommit(identity);
		
		Logger.debug(this, "Removed context '" + context + "' from identity '" + identity.getNickname() + "'");
	}
	
	public synchronized String getProperty(String identityID, String property) throws InvalidParameterException, UnknownIdentityException {
		return getIdentityByID(identityID).getProperty(property);
	}

	public synchronized void setProperty(String ownIdentityID, String property, String value)
		throws UnknownIdentityException, InvalidParameterException {
		
		Identity identity = getOwnIdentityByID(ownIdentityID);
		identity.setProperty(property, value);
		storeAndCommit(identity);
		
		Logger.debug(this, "Added property '" + property + "=" + value + "' to identity '" + identity.getNickname() + "'");
	}
	
	public void removeProperty(String ownIdentityID, String property) throws UnknownIdentityException, InvalidParameterException {
		Identity identity = getOwnIdentityByID(ownIdentityID);
		identity.removeProperty(property);
		storeAndCommit(identity);
		
		Logger.debug(this, "Removed property '" + property + "' from identity '" + identity.getNickname() + "'");
	}

	public String getVersion() {
		return Version.getMarketingVersion();
	}
	
	public long getRealVersion() {
		return Version.getRealVersion();
	}

	public String getString(String key) {
	    return getBaseL10n().getString(key);
	}
	
	public void setLanguage(LANGUAGE newLanguage) {
        WoT.l10n = new PluginL10n(this, newLanguage);
        Logger.debug(this, "Set LANGUAGE to: " + newLanguage.isoCode);
	}
	
	public PluginRespirator getPluginRespirator() {
		return mPR;
	}
	
	public ExtObjectContainer getDB() {
		return mDB;
	}
	
	public Config getConfig() {
		return mConfig;
	}
	
	public IdentityFetcher getIdentityFetcher() {
		return mFetcher;
	}

	public XMLTransformer getXMLTransformer() {
		return mXMLTransformer;
	}
	
	public IntroductionPuzzleStore getIntroductionPuzzleStore() {
		return mPuzzleStore;
	}

	public IntroductionClient getIntroductionClient() {
		return mIntroductionClient;
	}

	public RequestClient getRequestClient() {
		return mRequestClient;
	}

    /**
     * This is where our L10n files are stored.
     * @return Path of our L10n files.
     */
    public String getL10nFilesBasePath() {
        return "plugins/WoT/l10n/";
    }

    /**
     * This is the mask of our L10n files : lang_en.l10n, lang_de.10n, ...
     * @return Mask of the L10n files.
     */
    public String getL10nFilesMask() {
        return "lang_${lang}.l10n";
    }

    /**
     * Override L10n files are stored on the disk, their names should be explicit
     * we put here the plugin name, and the "override" indication. Plugin L10n
     * override is not implemented in the node yet.
     * @return Mask of the override L10n files.
     */
    public String getL10nOverrideFilesMask() {
        return "WoT_lang_${lang}.override.l10n";
    }

    /**
     * Get the ClassLoader of this plugin. This is necessary when getting
     * resources inside the plugin's Jar, for example L10n files.
     * @return ClassLoader object
     */
    public ClassLoader getPluginClassLoader() {
        return WoT.class.getClassLoader();
    }

    /**
     * Access to the current L10n data.
     *
     * @return L10n object.
     */
    public BaseL10n getBaseL10n() {
        return WoT.l10n.getBase();
    }

	
    /**
     * Tests whether two WoT are equal.
     * This is a complex operation in terms of execution time and memory usage and only intended for being used in unit tests.
     */
	public boolean equals(Object obj) {
		if(obj == this)
			return true;
		
		if(!(obj instanceof WoT))
			return false;
		
		WoT other = (WoT)obj;
		
		{ // Compare own identities
			final ObjectSet<OwnIdentity> allIdentities = getAllOwnIdentities();
			
			if(allIdentities.size() != other.getAllOwnIdentities().size())
				return false;
			
			for(OwnIdentity identity : allIdentities) {
				try {
					if(!identity.equals(other.getOwnIdentityByID(identity.getID())))
						return false;
				} catch(UnknownIdentityException e) {
					return false;
				}
			}
		}

		{ // Compare identities
			final ObjectSet<Identity> allIdentities = getAllIdentities();
			
			if(allIdentities.size() != other.getAllIdentities().size())
				return false;
			
			for(Identity identity : allIdentities) {
				try {
					if(!identity.equals(other.getIdentityByID(identity.getID())))
						return false;
				} catch(UnknownIdentityException e) {
					return false;
				}
			}
		}
		
		
		{ // Compare trusts
			final ObjectSet<Trust> allTrusts = getAllTrusts();
			
			if(allTrusts.size() != other.getAllTrusts().size())
				return false;
			
			for(Trust trust : allTrusts) {
				try {
					Identity otherTruster = other.getIdentityByID(trust.getTruster().getID());
					Identity otherTrustee = other.getIdentityByID(trust.getTrustee().getID());
					
					if(!trust.equals(other.getTrust(otherTruster, otherTrustee)))
						return false;
				} catch(UnknownIdentityException e) {
					return false;
				} catch(NotTrustedException e) {
					return false;
				}
			}
		}
		
		{ // Compare scores
			final ObjectSet<Score> allScores = getAllScores();
			
			if(allScores.size() != other.getAllScores().size())
				return false;
			
			for(Score score : allScores) {
				try {
					OwnIdentity otherTreeOwner = other.getOwnIdentityByID(score.getTreeOwner().getID());
					Identity otherTarget = other.getIdentityByID(score.getTarget().getID());
					
					if(!score.equals(other.getScore(otherTreeOwner, otherTarget)))
						return false;
				} catch(UnknownIdentityException e) {
					return false;
				} catch(NotInTrustTreeException e) {
					return false;
				}
			}
		}
		
		return true;
	}
    
    
}
