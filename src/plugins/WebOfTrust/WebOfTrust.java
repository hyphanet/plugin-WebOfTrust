/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import java.io.File;
import java.lang.reflect.Field;
import java.net.MalformedURLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;

import plugins.WebOfTrust.exceptions.DuplicateIdentityException;
import plugins.WebOfTrust.exceptions.DuplicateScoreException;
import plugins.WebOfTrust.exceptions.DuplicateTrustException;
import plugins.WebOfTrust.exceptions.InvalidParameterException;
import plugins.WebOfTrust.exceptions.NotInTrustTreeException;
import plugins.WebOfTrust.exceptions.NotTrustedException;
import plugins.WebOfTrust.exceptions.UnknownIdentityException;
import plugins.WebOfTrust.introduction.IntroductionClient;
import plugins.WebOfTrust.introduction.IntroductionPuzzle;
import plugins.WebOfTrust.introduction.IntroductionPuzzleStore;
import plugins.WebOfTrust.introduction.IntroductionServer;
import plugins.WebOfTrust.introduction.OwnIntroductionPuzzle;
import plugins.WebOfTrust.ui.fcp.FCPInterface;
import plugins.WebOfTrust.ui.web.WebInterface;

import com.db4o.Db4o;
import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.ext.ExtObjectContainer;
import com.db4o.query.Query;
import com.db4o.reflect.jdk.JdkReflector;

import freenet.keys.FreenetURI;
import freenet.l10n.BaseL10n;
import freenet.l10n.BaseL10n.LANGUAGE;
import freenet.l10n.PluginL10n;
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
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;

/**
 * A web of trust plugin based on Freenet.
 * 
 * @author xor (xor@freenetproject.org), Julien Cornuwel (batosai@freenetproject.org)
 */
public class WebOfTrust implements FredPlugin, FredPluginThreadless, FredPluginFCP, FredPluginVersioned, FredPluginRealVersioned,
	FredPluginL10n, FredPluginBaseL10n {
	
	/* Constants */
	
	public static final boolean FAST_DEBUG_MODE = false;
	
	/** The relative path of the plugin on Freenet's web interface */
	public static final String SELF_URI = "/WebOfTrust";

	/** Package-private method to allow unit tests to bypass some assert()s */
	
	/**
	 * The "name" of this web of trust. It is included in the document name of identity URIs. For an example, see the SEED_IDENTITIES
	 * constant below. The purpose of this costant is to allow anyone to create his own custom web of trust which is completely disconnected
	 * from the "official" web of trust of the Freenet project.
	 */
	public static final String WOT_NAME = "WebOfTrust";
	
	public static final String DATABASE_FILENAME =  WOT_NAME + ".db4o"; 
	public static final int DATABASE_FORMAT_VERSION = 1; 
	
	/**
	 * The official seed identities of the WoT plugin: If a newbie wants to download the whole offficial web of trust, he needs at least one
	 * trust list from an identity which is well-connected to the web of trust. To prevent newbies from having to add this identity manually,
	 * the Freenet development team provides a list of seed identities - each of them is one of the developers.
	 */
	private static final String[] SEED_IDENTITIES = new String[] { 
		"USK@QeTBVWTwBldfI-lrF~xf0nqFVDdQoSUghT~PvhyJ1NE,OjEywGD063La2H-IihD7iYtZm3rC0BP6UTvvwyF5Zh4,AQACAAE/WebOfTrust/90", // xor
		"USK@z9dv7wqsxIBCiFLW7VijMGXD9Gl-EXAqBAwzQ4aq26s,4Uvc~Fjw3i9toGeQuBkDARUV5mF7OTKoAhqOA9LpNdo,AQACAAE/WebOfTrust/60", // Toad
		"USK@o2~q8EMoBkCNEgzLUL97hLPdddco9ix1oAnEa~VzZtg,X~vTpL2LSyKvwQoYBx~eleI2RF6QzYJpzuenfcKDKBM,AQACAAE/WebOfTrust/0", // Bombe
		"USK@cI~w2hrvvyUa1E6PhJ9j5cCoG1xmxSooi7Nez4V2Gd4,A3ArC3rrJBHgAJV~LlwY9kgxM8kUR2pVYXbhGFtid78,AQACAAE/WebOfTrust/19", // TheSeeker
		"USK@D3MrAR-AVMqKJRjXnpKW2guW9z1mw5GZ9BB15mYVkVc,xgddjFHx2S~5U6PeFkwqO5V~1gZngFLoM-xaoMKSBI8,AQACAAE/WebOfTrust/47", // zidel
	};

	/* References from the node */
	
	/** The node's interface to connect the plugin with the node, needed for retrieval of all other interfaces */
	private PluginRespirator mPR;	
	
	private static PluginL10n l10n;
	
	/* References from the plugin itself */
	
	/* Database & configuration of the plugin */
	private ExtObjectContainer mDB;
	private Configuration mConfig;
	private IntroductionPuzzleStore mPuzzleStore;
	
	/** Used for exporting identities, identity introductions and introduction puzzles to XML and importing them from XML. */
	private XMLTransformer mXMLTransformer;
	private RequestClient mRequestClient;

	/* Worker objects which actually run the plugin */
	
	/**
	 * Clients can subscribe to certain events such as identity creation, trust changes, etc. with the {@link SubscriptionManager}
	 */
	private SubscriptionManager mSubscriptionManager;
	
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
			mDB = openDatabase(new File(getUserDataDirectory(), DATABASE_FILENAME));
			
			mConfig = getOrCreateConfig();
			if(mConfig.getDatabaseFormatVersion() > WebOfTrust.DATABASE_FORMAT_VERSION)
				throw new RuntimeException("The WoT plugin's database format is newer than the WoT plugin which is being used.");
			
			upgradeDB();
			
			mXMLTransformer = new XMLTransformer(this);
			mPuzzleStore = new IntroductionPuzzleStore(this);
			
			mRequestClient = new RequestClient() {
	
				public boolean persistent() {
					return false;
				}
	
				public void removeFrom(ObjectContainer container) {
					throw new UnsupportedOperationException();
				}

				public boolean realTimeFlag() {
					return false;
				}
				
			};
			
			mSubscriptionManager = new SubscriptionManager(this);
			
			mInserter = new IdentityInserter(this);
			mFetcher = new IdentityFetcher(this, getPluginRespirator());		
			
			verifyDatabaseIntegrity();
			
			// TODO: Don't do this as soon as we are sure that score computation works.
			Logger.normal(this, "Veriying all stored scores ...");
			synchronized(this) {
			synchronized(mDB.lock()) {
				try {
					computeAllScoresWithoutCommit();
					Persistent.checkedCommit(mDB, this);
				} catch(RuntimeException e) {
					Persistent.checkedRollbackAndThrow(mDB, this, e);
				}
			}
			}
			
			// Database is up now, integrity is checked. We can start to actually do stuff
			
			mSubscriptionManager.start();
			
			createSeedIdentities();
			
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
			
			mInserter.start();

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
	public WebOfTrust() {

	}
	
	/**
	 *  Constructor which does not generate an IdentityFetcher, IdentityInster, IntroductionPuzzleStore, user interface, etc.
	 * For use by the unit tests to be able to run WoT without a node.
	 * @param databaseFilename The filename of the database.
	 */
	public WebOfTrust(String databaseFilename) {
		mDB = openDatabase(new File(databaseFilename));
		mConfig = getOrCreateConfig();
		
		if(mConfig.getDatabaseFormatVersion() > WebOfTrust.DATABASE_FORMAT_VERSION)
			throw new RuntimeException("The WoT plugin's database format is newer than the WoT plugin which is being used.");
		
		mPuzzleStore = new IntroductionPuzzleStore(this);
		
		mSubscriptionManager = new SubscriptionManager(this);
		
		mFetcher = new IdentityFetcher(this, null);
	}
	
	private File getUserDataDirectory() {
        final File wotDirectory = new File(mPR.getNode().getUserDir(), WOT_NAME);
        
        if(!wotDirectory.exists() && !wotDirectory.mkdir())
        	throw new RuntimeException("Unable to create directory " + wotDirectory);
        
        return wotDirectory;
	}

	/**
	 * ATTENTION: This function is duplicated in the Freetalk plugin, please backport any changes.
	 * 
	 * Initializes the plugin's db4o database.
	 */
	private ExtObjectContainer openDatabase(File file) {
		Logger.debug(this, "Using db4o " + Db4o.version());
		
		com.db4o.config.Configuration cfg = Db4o.newConfiguration();
		
		// Required config options:
		cfg.reflectWith(new JdkReflector(getPluginClassLoader()));
		// TODO: Optimization: We do explicit activation everywhere. We could change this to 1 and test whether it still works.
		// We have to do very careful testing though, toad_ said that db4o bugs can occur with depth 1 and manual activation...
		cfg.activationDepth(10);
		cfg.exceptionsOnNotStorable(true);
        // The shutdown hook does auto-commit. We do NOT want auto-commit: if a transaction hasn't commit()ed, it's not safe to commit it.
        cfg.automaticShutDown(false);
        
        // Performance config options:
        cfg.callbacks(false); // We don't use callbacks yet. TODO: Investigate whether we might want to use them
        cfg.classActivationDepthConfigurable(false);
        
        // Registration of indices (also performance)
        
        @SuppressWarnings("unchecked")
		final Class<? extends Persistent>[] persistentClasses = new Class[] {
        	Identity.class,
        	OwnIdentity.class,
        	Trust.class,
        	Score.class,
        	IdentityFetcher.IdentityFetcherCommand.class,
        	IdentityFetcher.AbortFetchCommand.class,
        	IdentityFetcher.StartFetchCommand.class,
        	IdentityFetcher.UpdateEditionHintCommand.class,
        	SubscriptionManager.Subscription.class,
        	SubscriptionManager.IdentityAttributeListSubscription.class,
        	SubscriptionManager.IdentityListSubscription.class,
        	SubscriptionManager.ScoreListSubscription.class,
        	SubscriptionManager.TrustListSubscription.class,
        	SubscriptionManager.Notification.class,
        	SubscriptionManager.InitialSynchronizationNotification.class,
        	SubscriptionManager.IdentityChangedNotification.class,
        	SubscriptionManager.IdentityListChangedNotification.class,
        	SubscriptionManager.ScoreChangedNotification.class,
        	SubscriptionManager.TrustChangedNotification.class,
        	IntroductionPuzzle.class,
        	OwnIntroductionPuzzle.class
        };
        
        for(Class<? extends Persistent> clazz : persistentClasses) {
        	boolean classHasIndex = clazz.getAnnotation(Persistent.IndexedClass.class) != null;
        	
        	// TODO: We enable class indexes for all classes to make sure nothing breaks because it is the db4o default, check whether enabling
        	// them only for the classes where we need them does not cause any harm.
        	classHasIndex = true;
        	
        	Logger.debug(this, "Peristent class: " + clazz.getCanonicalName() + "; hasIndex==" + classHasIndex);
        	
        	// TODO: Make very sure that it has no negative side effects if we disable class indices for some classes
        	// Maybe benchmark in comparison to a database which has class indices enabled for ALL classes.
        	cfg.objectClass(clazz).indexed(classHasIndex);
   
        	// Check the class' fields for @IndexedField annotations
        	for(Field field : clazz.getDeclaredFields()) {
        		if(field.getAnnotation(Persistent.IndexedField.class) != null) {
        			Logger.debug(this, "Registering indexed field " + clazz.getCanonicalName() + '.' + field.getName());
        			cfg.objectClass(clazz).objectField(field.getName()).indexed(true);
        		}
        	}
        	
    		// Check whether the class itself has an @IndexedField annotation
    		final Persistent.IndexedField annotation =  clazz.getAnnotation(Persistent.IndexedField.class);
    		if(annotation != null) {
        		for(String fieldName : annotation.names()) {
        			Logger.debug(this, "Registering indexed field " + clazz.getCanonicalName() + '.' + fieldName);
        			cfg.objectClass(clazz).objectField(fieldName).indexed(true);
        		}
    		}
        }
        
        // TODO: We should check whether db4o inherits the indexed attribute to child classes, for example for this one:
        // Unforunately, db4o does not provide any way to query the indexed() property of fields, you can only set it
        // We might figure out whether inheritance works by writing a benchmark.

		return Db4o.openFile(cfg, file.getAbsolutePath()).ext();
	}
	
	private synchronized void upgradeDB() {
		int databaseVersion = mConfig.getDatabaseFormatVersion();
		
		if(databaseVersion == WebOfTrust.DATABASE_FORMAT_VERSION)
			return;
		
		// Insert upgrade code here. See Freetalk.java for a skeleton.
		
		if(databaseVersion != WebOfTrust.DATABASE_FORMAT_VERSION)
			throw new RuntimeException("Your database is too outdated to be upgraded automatically, please create a new one by deleting " 
					+ DATABASE_FILENAME + ". Contact the developers if you really need your old data.");
	}
	
	private synchronized void verifyDatabaseIntegrity() {
		deleteDuplicateObjects();
		deleteOrphanObjects();
		
		Logger.normal(this, "Testing database integrity...");
		
		final Query q = mDB.query();
		q.constrain(Persistent.class);
		
		for(final Persistent p : new Persistent.InitializingObjectSet<Persistent>(this, q)) {
			try {
				p.startupDatabaseIntegrityTest();
			} catch(Exception e) {
				Logger.error(this, "Integrity test failed for " + p, e);
			}
		}
		
		Logger.normal(this, "Database integrity test finished.");
	}
	
	/**
	 * Debug function for deleting duplicate identities etc. which might have been created due to bugs :)
	 */
	private synchronized void deleteDuplicateObjects() {
		synchronized(mDB.lock()) {
		try {
			HashSet<String> deleted = new HashSet<String>();

			Logger.debug(this, "Searching for duplicate identities ...");

			for(Identity identity : getAllIdentities()) {
				Query q = mDB.query();
				q.constrain(Identity.class);
				q.descend("mID").constrain(identity.getID());
				q.constrain(identity).identity().not();
				ObjectSet<Identity> duplicates = new Persistent.InitializingObjectSet<Identity>(this, q);
				for(Identity duplicate : duplicates) {
					if(deleted.contains(duplicate.getID()) == false) {
						Logger.error(duplicate, "Deleting duplicate identity " + duplicate.getRequestURI());
						deleteIdentity(duplicate);
					}
				}
				deleted.add(identity.getID());
			}
			Persistent.checkedCommit(mDB, this);
			
			Logger.debug(this, "Finished searching for duplicate identities.");
		}
		catch(RuntimeException e) {
			Persistent.checkedRollback(mDB, this, e);
		}
		}

		synchronized(mDB.lock()) {
		try {
		Logger.debug(this, "Searching for duplicate Trust objects ...");

		boolean duplicateTrustFound = false;
		for(OwnIdentity truster : getAllOwnIdentities()) {
			HashSet<String> givenTo = new HashSet<String>();

			for(Trust trust : getGivenTrusts(truster)) {
				if(givenTo.contains(trust.getTrustee().getID()) == false)
					givenTo.add(trust.getTrustee().getID());
				else {
					Logger.error(this, "Deleting duplicate given trust:" + trust);
					removeTrustWithoutCommit(trust);
					duplicateTrustFound = true;
				}
			}
		}
		
		if(duplicateTrustFound) {
			computeAllScoresWithoutCommit();
		}
		
		Persistent.checkedCommit(mDB, this);
		Logger.debug(this, "Finished searching for duplicate trust objects.");
		}
		catch(RuntimeException e) {
			Persistent.checkedRollback(mDB, this, e);
		}
		}
		
		/* TODO: Also delete duplicate score */
	}
	
	/**
	 * Debug function for deleting trusts or scores of which one of the involved partners is missing.
	 */
	private synchronized void deleteOrphanObjects() {
		synchronized(mDB.lock()) {
			try {
				boolean orphanTrustFound = false;
				
				Query q = mDB.query();
				q.constrain(Trust.class);
				q.descend("mTruster").constrain(null).identity().or(q.descend("mTrustee").constrain(null).identity());
				ObjectSet<Trust> orphanTrusts = new Persistent.InitializingObjectSet<Trust>(this, q);
				
				for(Trust trust : orphanTrusts) {
					if(trust.getTruster() != null && trust.getTrustee() != null) {
						// TODO: Remove this workaround for the db4o bug as soon as we are sure that it does not happen anymore.
						Logger.error(this, "Db4o bug: constrain(null).identity() did not work for " + trust);
						continue;
					}
					
					Logger.error(trust, "Deleting orphan trust, truster = " + trust.getTruster() + ", trustee = " + trust.getTrustee());
					orphanTrustFound = true;
					trust.deleteWithoutCommit();
				}
				
				if(orphanTrustFound) {
					computeAllScoresWithoutCommit();
					Persistent.checkedCommit(mDB, this);
				}
			}
			catch(Exception e) {
				Persistent.checkedRollback(mDB, this, e); 
			}
		}
		
		synchronized(mDB.lock()) {
			try {
				boolean orphanScoresFound = false;
				
				Query q = mDB.query();
				q.constrain(Score.class);
				q.descend("mTruster").constrain(null).identity().or(q.descend("mTrustee").constrain(null).identity());
				ObjectSet<Score> orphanScores = new Persistent.InitializingObjectSet<Score>(this, q);
				
				for(Score score : orphanScores) {
					if(score.getTruster() != null && score.getTrustee() != null) {
						// TODO: Remove this workaround for the db4o bug as soon as we are sure that it does not happen anymore.
						Logger.error(this, "Db4o bug: constrain(null).identity() did not work for " + score);
						continue;
					}
					
					Logger.error(score, "Deleting orphan score, truster = " + score.getTruster() + ", trustee = " + score.getTrustee());
					orphanScoresFound = true;
					score.deleteWithoutCommit();
				}
				
				if(orphanScoresFound) {
					computeAllScoresWithoutCommit();
					Persistent.checkedCommit(mDB, this);
				}
			}
			catch(Exception e) {
				Persistent.checkedRollback(mDB, this, e);
			}
		}
	}
	
	
	/**
	 * Loads an existing Config object from the database and adds any missing default values to it, creates and stores a new one if none exists.
	 * @return The config object.
	 */
	private synchronized Configuration getOrCreateConfig() {
		final Query query = mDB.query();
		query.constrain(Configuration.class);
		final ObjectSet<Configuration> result = new Persistent.InitializingObjectSet<Configuration>(this, query);

		switch(result.size()) {
			case 1: {
				final Configuration config = result.next();
				// For the HashMaps to stay alive we need to activate to full depth.
				config.checkedActivate(4);
				config.setDefaultValues(false);
				config.storeAndCommit();
				return config;
			}
			case 0: {
				final Configuration config = new Configuration(this);
				config.initializeTransient(this);
				config.storeAndCommit();
				return config;
			}
			default:
				throw new RuntimeException("Multiple config objects found: " + result.size());
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
	 * If the truster has assigned a trust value to the trustee the capacity will be computed only from that trust value:
	 * The decision of the truster should always overpower the view of remote identities.
	 * 
	 * Notice that 0 is included in infinite rank to prevent identities which have only solved introduction puzzles from having a capacity.  
	 *  
	 * @param truster The {@link OwnIdentity} in whose trust tree the capacity shall be computed
	 * @param trustee The {@link Identity} of which the capacity shall be computed. 
	 * @param rank The rank of the identity. The rank is the distance in trust steps from the OwnIdentity which views the web of trust,
	 * 				- its rank is 0, the rank of its trustees is 1 and so on. Must be -1 if the truster has no rank in the tree owners view.
	 */
	protected int computeCapacity(OwnIdentity truster, Identity trustee, int rank) {
		if(truster == trustee)
			return 100;
		 
		try {
			if(getTrust(truster, trustee).getValue() <= 0) { // Security check, if rank computation breaks this will hit.
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
		Logger.debug(this, "Doing a full computation of all Scores...");
		
		boolean returnValue = true;
		final ObjectSet<Identity> allIdentities = getAllIdentities();
		
		// Scores are a rating of an identity from the view of an OwnIdentity so we compute them per OwnIdentity.
		for(OwnIdentity treeOwner : getAllOwnIdentities()) {
			// At the end of the loop body, this table will be filled with the ranks of all identities which are visible for treeOwner.
			// An identity is visible if there is a trust chain from the owner to it.
			// The rank is the distance in trust steps from the treeOwner.			
			// So the treeOwner is rank 0, the trustees of the treeOwner are rank 1 and so on.
			final HashMap<Identity, Integer> rankValues = new HashMap<Identity, Integer>(allIdentities.size() * 2);
			
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
						rankValues.put(treeOwner, null);
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
				
				Score newScore = null;
				if(targetScore != null) {
					newScore = new Score(treeOwner, target, targetScore, targetRank, computeCapacity(treeOwner, target, targetRank));
					newScore.initializeTransient(this);
				}
				
				boolean needToCheckFetchStatus = false;
				boolean oldShouldFetch = false;
				int oldCapacity = 0;
				
				// Now we have the rank and the score of the target computed and can check whether the database-stored score object is correct.
				try {
					Score currentStoredScore = getScore(treeOwner, target);
					oldCapacity = currentStoredScore.getCapacity();
					
					if(newScore == null) {
						returnValue = false;
						if(!mFullScoreComputationNeeded)
							Logger.error(this, "Correcting wrong score: The identity has no rank and should have no score but score was " + currentStoredScore, new RuntimeException());
						
						needToCheckFetchStatus = true;
						oldShouldFetch = shouldFetchIdentity(target);
						
						currentStoredScore.deleteWithoutCommit();
						
					} else {
						if(!newScore.equals(currentStoredScore)) {
							returnValue = false;
							if(!mFullScoreComputationNeeded)
								Logger.error(this, "Correcting wrong score: Should have been " + newScore + " but was " + currentStoredScore, new RuntimeException());
							
							needToCheckFetchStatus = true;
							oldShouldFetch = shouldFetchIdentity(target);
							
							currentStoredScore.setRank(newScore.getRank());
							currentStoredScore.setCapacity(newScore.getCapacity());
							currentStoredScore.setValue(newScore.getScore());

							currentStoredScore.storeWithoutCommit();
						}
					}
				} catch(NotInTrustTreeException e) {
					oldCapacity = 0;
					
					if(newScore != null) {
						returnValue = false;
						if(!mFullScoreComputationNeeded)
							Logger.error(this, "Correcting wrong score: No score was stored for the identity but it should be " + newScore, new RuntimeException());
						
						needToCheckFetchStatus = true;
						oldShouldFetch = shouldFetchIdentity(target);
						
						newScore.storeWithoutCommit();
					}
				}
				
				if(needToCheckFetchStatus) {
					// If fetch status changed from false to true, we need to start fetching it
					// If the capacity changed from 0 to positive, we need to refetch the current edition: Identities with capacity 0 cannot
					// cause new identities to be imported from their trust list, capacity > 0 allows this.
					// If the fetch status changed from true to false, we need to stop fetching it
					if((!oldShouldFetch || (oldCapacity == 0 && newScore != null && newScore.getCapacity() > 0)) && shouldFetchIdentity(target) ) {
						if(!oldShouldFetch)
							Logger.debug(this, "Fetch status changed from false to true, refetching " + target);
						else
							Logger.debug(this, "Capacity changed from 0 to " + newScore.getCapacity() + ", refetching" + target);

						target.markForRefetch();
						target.storeWithoutCommit();

						mFetcher.storeStartFetchCommandWithoutCommit(target);
					}
					else if(oldShouldFetch && !shouldFetchIdentity(target)) {
						Logger.debug(this, "Fetch status changed from true to false, aborting fetch of " + target);

						mFetcher.storeAbortFetchCommandWithoutCommit(target);
					}
				}
			}
		}
		
		mFullScoreComputationNeeded = false;
		
		Logger.debug(this, "Full score computation finished.");
		
		return returnValue;
	}
	
	private synchronized void createSeedIdentities() {
		for(String seedURI : SEED_IDENTITIES) {
			Identity seed;
			
			try { 
				seed = getIdentityByURI(seedURI);
				if(seed instanceof OwnIdentity) {
					OwnIdentity ownSeed = (OwnIdentity)seed;
					ownSeed.addContext(IntroductionPuzzle.INTRODUCTION_CONTEXT);
					ownSeed.setProperty(IntroductionServer.PUZZLE_COUNT_PROPERTY,
							Integer.toString(IntroductionServer.SEED_IDENTITY_PUZZLE_COUNT));
					
					ownSeed.storeAndCommit();
				}
				else {
					try {
						seed.setEdition(new FreenetURI(seedURI).getEdition());
						seed.storeAndCommit();
					} catch(InvalidParameterException e) {
						/* We already have the latest edition stored */
					}
				}
			}
			catch (UnknownIdentityException uie) {
				try {
					seed = new Identity(seedURI, null, true);
					seed.initializeTransient(this);
					// We have to explicitely set the edition number because the constructor only considers the given edition as a hint.
					seed.setEdition(new FreenetURI(seedURI).getEdition());
					seed.storeAndCommit();
				} catch (Exception e) {
					Logger.error(this, "Seed identity creation error", e);
				}
			}
			catch (Exception e) {
				Persistent.checkedRollback(mDB, this, e);
			}
		}
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
			if(mSubscriptionManager != null)
				mSubscriptionManager.stop();
		} catch(Exception e) {
			Logger.error(this, "Error during termination.", e);
		}	
		
		try {
			if(mDB != null) {
				/* TODO: At 2009-06-15, it does not seem possible to ask db4o for whether a transaction is pending.
				 * If it becomes possible some day, we should check that here, and log an error if there is an uncommitted transaction. 
				 * - All transactions should be committed after obtaining the lock() on the database. */
				synchronized(mDB.lock()) {
					Persistent.checkedRollback(mDB, this, null);
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
	public synchronized Identity getIdentityByID(String id) throws UnknownIdentityException {
		final Query query = mDB.query();
		query.constrain(Identity.class);
		query.descend("mID").constrain(id);
		final ObjectSet<Identity> result = new Persistent.InitializingObjectSet<Identity>(this, query);
		
		switch(result.size()) {
			case 1: return result.next();
			case 0: throw new UnknownIdentityException(id);
			default: throw new DuplicateIdentityException(id, result.size());
		}  
	}
	
	/**
	 * Gets an OwnIdentity by its ID.
	 * 
	 * @param id The unique identifier to query an OwnIdentity
	 * @return The requested OwnIdentity
	 * @throws UnknownIdentityException if there is now OwnIdentity with that id
	 */
	public synchronized OwnIdentity getOwnIdentityByID(String id) throws UnknownIdentityException {
		final Query query = mDB.query();
		query.constrain(OwnIdentity.class);
		query.descend("mID").constrain(id);
		final ObjectSet<OwnIdentity> result = new Persistent.InitializingObjectSet<OwnIdentity>(this, query);
		
		switch(result.size()) {
			case 1: return result.next();
			case 0: throw new UnknownIdentityException(id);
			default: throw new DuplicateIdentityException(id, result.size());
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
	public ObjectSet<Identity> getAllIdentities() {
		final Query query = mDB.query();
		query.constrain(Identity.class);
		return new Persistent.InitializingObjectSet<Identity>(this, query);
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
	public ObjectSet<Identity> getAllIdentitiesFilteredAndSorted(OwnIdentity truster, String nickFilter, SortOrder sortInstruction) {
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
				q.descend("mTruster").constrain(truster).identity();
				q.descend("mValue").orderAscending();
				q = q.descend("mTrustee"); 
				break;
			case ByScoreDescending:
				// TODO: This excludes identities which have no score
				q.constrain(Score.class);
				q.descend("mTruster").constrain(truster).identity();
				q.descend("mValue").orderDescending();
				q = q.descend("mTrustee");
				break;
			case ByLocalTrustAscending:
				q.constrain(Trust.class);
				q.descend("mTruster").constrain(truster).identity();
				q.descend("mValue").orderAscending();
				q = q.descend("mTrustee");
				break;
			case ByLocalTrustDescending:
				// TODO: This excludes untrusted identities.
				q.constrain(Trust.class);
				q.descend("mTruster").constrain(truster).identity();
				q.descend("mValue").orderDescending();
				q = q.descend("mTrustee");
				break;
		}
		
		if(nickFilter != null) {
			nickFilter = nickFilter.trim();
			if(!nickFilter.equals("")) q.descend("mNickname").constrain(nickFilter).like();
		}
		
		return new Persistent.InitializingObjectSet<Identity>(this, q);
	}
	
	/**
	 * Returns all non-own identities that are in the database.
	 * 
	 * You have to synchronize on this WoT when calling the function and processing the returned list!
	 */
	public ObjectSet<Identity> getAllNonOwnIdentities() {
		final Query q = mDB.query();
		q.constrain(Identity.class);
		q.constrain(OwnIdentity.class).not();
		return new Persistent.InitializingObjectSet<Identity>(this, q);
	}
	
	/**
	 * Returns all non-own identities that are in the database, sorted descending by their date of modification, i.e. recently
	 * modified identities will be at the beginning of the list.
	 * 
	 * You have to synchronize on this WoT when calling the function and processing the returned list!
	 * 
	 * Used by the IntroductionClient for fetching puzzles from recently modified identities.
	 */
	public ObjectSet<Identity> getAllNonOwnIdentitiesSortedByModification () {
		final Query q = mDB.query();
		q.constrain(Identity.class);
		q.constrain(OwnIdentity.class).not();
		/* TODO: As soon as identities announce that they were online every day, uncomment the following line */
		/* q.descend("mLastChangedDate").constrain(new Date(CurrentTimeUTC.getInMillis() - 1 * 24 * 60 * 60 * 1000)).greater(); */
		q.descend("mLastFetchedDate").orderDescending();
		return new Persistent.InitializingObjectSet<Identity>(this, q);
	}
	
	/**
	 * Returns all own identities that are in the database
	 * You have to synchronize on this WoT when calling the function and processing the returned list!
	 * 
	 * @return An {@link ObjectSet} containing all identities present in the database.
	 */
	public ObjectSet<OwnIdentity> getAllOwnIdentities() {
		final Query q = mDB.query();
		q.constrain(OwnIdentity.class);
		return new Persistent.InitializingObjectSet<OwnIdentity>(this, q);
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
				score.deleteWithoutCommit();

			if(identity instanceof OwnIdentity) {
				Logger.debug(this, "Deleting given scores...");

				for(Score score : getGivenScores((OwnIdentity)identity))
					score.deleteWithoutCommit();
			}

			Logger.debug(this, "Deleting received trusts...");
			for(Trust trust : getReceivedTrusts(identity))
				trust.deleteWithoutCommit();

			Logger.debug(this, "Deleting given trusts...");
			for(Trust givenTrust : getGivenTrusts(identity)) {
				givenTrust.deleteWithoutCommit();
				// We call computeAllScores anyway so we do not use removeTrustWithoutCommit()
			}
			
			computeAllScoresWithoutCommit();

			Logger.debug(this, "Deleting associated introduction puzzles ...");
			mPuzzleStore.onIdentityDeletion(identity);
			
			Logger.debug(this, "Storing an abort-fetch-command...");
			
			mFetcher.storeAbortFetchCommandWithoutCommit(identity);

			Logger.debug(this, "Deleting the identity...");
			identity.deleteWithoutCommit();
		}
		catch(RuntimeException e) {
			Persistent.checkedRollbackAndThrow(mDB, this, e);
		}
	}

	/**
	 * Gets the score of this identity in a trust tree.
	 * Each {@link OwnIdentity} has its own trust tree.
	 * 
	 * @param truster The owner of the trust tree
	 * @return The {@link Score} of this Identity in the required trust tree
	 * @throws NotInTrustTreeException if this identity is not in the required trust tree 
	 */
	public synchronized Score getScore(final OwnIdentity truster, final Identity trustee) throws NotInTrustTreeException {
		final Query query = mDB.query();
		query.constrain(Score.class);
		query.descend("mTruster").constrain(truster).identity();
		query.descend("mTrustee").constrain(trustee).identity();
		final ObjectSet<Score> result = new Persistent.InitializingObjectSet<Score>(this, query);
		
		switch(result.size()) {
			case 1: return result.next();
			case 0: throw new NotInTrustTreeException(truster, trustee);
			default: throw new DuplicateScoreException(truster, trustee, result.size());
		}
	}

	/**
	 * Gets a list of all this Identity's Scores.
	 * You have to synchronize on this WoT around the call to this function and the processing of the returned list! 
	 * 
	 * @return An {@link ObjectSet} containing all {@link Score} this Identity has.
	 */
	public ObjectSet<Score> getScores(final Identity identity) {
		final Query query = mDB.query();
		query.constrain(Score.class);
		query.descend("mTrustee").constrain(identity).identity();
		return new Persistent.InitializingObjectSet<Score>(this, query);
	}
	
	/**
	 * Get a list of all scores which the passed own identity has assigned to other identities.
	 * 
	 * You have to synchronize on this WoT around the call to this function and the processing of the returned list! 
	 * @return An {@link ObjectSet} containing all {@link Score} this Identity has given.
	 */
	public ObjectSet<Score> getGivenScores(final OwnIdentity truster) {
		final Query query = mDB.query();
		query.constrain(Score.class);
		query.descend("mTruster").constrain(truster).identity();
		return new Persistent.InitializingObjectSet<Score>(this, query);
	}
	
	/**
	 * Gets the best score this Identity has in existing trust trees.
	 * 
	 * @return the best score this Identity has
	 * @throws NotInTrustTreeException If the identity has no score in any trusttree.
	 */
	public synchronized int getBestScore(final Identity identity) throws NotInTrustTreeException {
		int bestScore = Integer.MIN_VALUE;
		final ObjectSet<Score> scores = getScores(identity);
		
		if(scores.size() == 0)
			throw new NotInTrustTreeException(identity);
		
		// TODO: Cache the best score of an identity as a member variable.
		for(final Score score : scores) 
			bestScore = Math.max(score.getScore(), bestScore);
		
		return bestScore;
	}
	
	/**
	 * Gets the best capacity this identity has in any trust tree.
	 * @throws NotInTrustTreeException If the identity is not in any trust tree. Can be interpreted as capacity 0.
	 */
	public int getBestCapacity(final Identity identity) throws NotInTrustTreeException {
		int bestCapacity = 0;
		final ObjectSet<Score> scores = getScores(identity);
		
		if(scores.size() == 0)
			throw new NotInTrustTreeException(identity);
		
		// TODO: Cache the best score of an identity as a member variable.
		for(final Score score : scores) 
			bestCapacity  = Math.max(score.getCapacity(), bestCapacity);
		
		return bestCapacity;
	}
	
	/**
	 * Get all scores in the database.
	 * You have to synchronize on this WoT when calling the function and processing the returned list!
	 */
	public ObjectSet<Score> getAllScores() {
		final Query query = mDB.query();
		query.constrain(Score.class);
		return new Persistent.InitializingObjectSet<Score>(this, query);
	}
	
	/**
	 * Checks whether the given identity should be downloaded. 
	 * @return Returns true if the identity has any capacity > 0, any score >= 0 or if it is an own identity.
	 */
	public boolean shouldFetchIdentity(final Identity identity) {
		if(identity instanceof OwnIdentity)
			return true;
		
		int bestScore = Integer.MIN_VALUE;
		int bestCapacity = 0;
		final ObjectSet<Score> scores = getScores(identity);
			
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
	 * Gets non-own Identities matching a specified score criteria.
	 * TODO: Rename to getNonOwnIdentitiesByScore. Or even better: Make it return own identities as well, this will speed up the database query and clients might be ok with it.
	 * You have to synchronize on this WoT when calling the function and processing the returned list!
	 * 
	 * @param truster The owner of the trust tree, null if you want the trusted identities of all owners.
	 * @param select Score criteria, can be > zero, zero or negative. Greater than zero returns all identities with score >= 0, zero with score equal to 0
	 * 		and negative with score < 0. Zero is included in the positive range by convention because solving an introduction puzzle gives you a trust value of 0.
	 * @return an {@link ObjectSet} containing Scores of the identities that match the criteria
	 */
	public ObjectSet<Score> getIdentitiesByScore(final OwnIdentity truster, final int select) {
		final Query query = mDB.query();
		query.constrain(Score.class);
		if(truster != null)
			query.descend("mTruster").constrain(truster).identity();
		query.descend("mTrustee").constrain(OwnIdentity.class).not();
	
		/* We include 0 in the list of identities with positive score because solving captchas gives no points to score */
		
		if(select > 0)
			query.descend("mValue").constrain(0).smaller().not();
		else if(select < 0)
			query.descend("mValue").constrain(0).smaller();
		else 
			query.descend("mValue").constrain(0);

		return  new Persistent.InitializingObjectSet<Score>(this, query);
	}
	
	/**
	 * Gets {@link Trust} from a specified truster to a specified trustee.
	 * 
	 * @param truster The identity that gives trust to this Identity
	 * @param trustee The identity which receives the trust
	 * @return The trust given to the trustee by the specified truster
	 * @throws NotTrustedException if the truster doesn't trust the trustee
	 */
	public synchronized Trust getTrust(final Identity truster, final Identity trustee) throws NotTrustedException, DuplicateTrustException {
		final Query query = mDB.query();
		query.constrain(Trust.class);
		query.descend("mTruster").constrain(truster).identity();
		query.descend("mTrustee").constrain(trustee).identity();
		final ObjectSet<Trust> result = new Persistent.InitializingObjectSet<Trust>(this, query);
		
		switch(result.size()) {
			case 1: return result.next();
			case 0: throw new NotTrustedException(truster, trustee);
			default: throw new DuplicateTrustException(truster, trustee, result.size());
		}
	}

	/**
	 * Gets all trusts given by the given truster.
	 * You have to synchronize on this WoT when calling the function and processing the returned list!
	 * 
	 * @return An {@link ObjectSet} containing all {@link Trust} the passed Identity has given.
	 */
	public ObjectSet<Trust> getGivenTrusts(final Identity truster) {
		final Query query = mDB.query();
		query.constrain(Trust.class);
		query.descend("mTruster").constrain(truster).identity();
		return new Persistent.InitializingObjectSet<Trust>(this, query);
	}
	
	/**
	 * Gets given trust values of an identity matching a specified trust value criteria.
	 * You have to synchronize on this WoT when calling the function and processing the returned list!
	 * 
	 * @param truster The identity which given the trust values.
	 * @param select Trust value criteria, can be > zero, zero or negative. Greater than zero returns all trust values >= 0, zero returns trust values equal to 0.
	 * 		Negative returns trust values < 0. Zero is included in the positive range by convention because solving an introduction puzzle gives you a value of 0.
	 * @return an {@link ObjectSet} containing received trust values that match the criteria.
	 */
	public ObjectSet<Trust> getGivenTrusts(final Identity truster, final int select) {
		final Query query = mDB.query();
		query.constrain(Trust.class);
		query.descend("mTruster").constrain(truster).identity();
	
		/* We include 0 in the list of identities with positive trust because solving captchas gives 0 trust */
		
		if(select > 0)
			query.descend("mValue").constrain(0).smaller().not();
		else if(select < 0 )
			query.descend("mValue").constrain(0).smaller();
		else 
			query.descend("mValue").constrain(0);

		return new Persistent.InitializingObjectSet<Trust>(this, query);
	}
	/**
	 * Gets all trusts given by the given truster in a trust list with a different edition than the passed in one.
	 * You have to synchronize on this WoT when calling the function and processing the returned list!
	 */
	protected ObjectSet<Trust> getGivenTrustsOfDifferentEdition(final Identity truster, final long edition) {
		final Query q = mDB.query();
		q.constrain(Trust.class);
		q.descend("mTruster").constrain(truster).identity();
		q.descend("mTrusterTrustListEdition").constrain(edition).not();
		return new Persistent.InitializingObjectSet<Trust>(this, q);
	}

	/**
	 * Gets all trusts received by the given trustee.
	 * You have to synchronize on this WoT when calling the function and processing the returned list!
	 * 
	 * @return An {@link ObjectSet} containing all {@link Trust} the passed Identity has received.
	 */
	public ObjectSet<Trust> getReceivedTrusts(final Identity trustee) {
		final Query query = mDB.query();
		query.constrain(Trust.class);
		query.descend("mTrustee").constrain(trustee).identity();
		return new Persistent.InitializingObjectSet<Trust>(this, query);
	}
	
	/**
	 * Gets received trust values of an identity matching a specified trust value criteria.
	 * You have to synchronize on this WoT when calling the function and processing the returned list!
	 * 
	 * @param trustee The identity which has received the trust values.
	 * @param select Trust value criteria, can be > zero, zero or negative. Greater than zero returns all trust values >= 0, zero returns trust values equal to 0.
	 * 		Negative returns trust values < 0. Zero is included in the positive range by convention because solving an introduction puzzle gives you a value of 0.
	 * @return an {@link ObjectSet} containing received trust values that match the criteria.
	 */
	public ObjectSet<Trust> getReceivedTrusts(final Identity trustee, final int select) {		
		final Query query = mDB.query();
		query.constrain(Trust.class);
		query.descend("mTrustee").constrain(trustee).identity();
	
		/* We include 0 in the list of identities with positive trust because solving captchas gives 0 trust */
		
		if(select > 0)
			query.descend("mValue").constrain(0).smaller().not();
		else if(select < 0 )
			query.descend("mValue").constrain(0).smaller();
		else 
			query.descend("mValue").constrain(0);

		return new Persistent.InitializingObjectSet<Trust>(this, query);
	}
	
	/**
	 * Gets all trusts.
	 * You have to synchronize on this WoT when calling the function and processing the returned list!
	 * 
	 * @return An {@link ObjectSet} containing all {@link Trust} the passed Identity has received.
	 */
	public ObjectSet<Trust> getAllTrusts() {
		final Query query = mDB.query();
		query.constrain(Trust.class);
		return new Persistent.InitializingObjectSet<Trust>(this, query); 
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
		
		try { // Check if we are updating an existing trust value
			final Trust trust = getTrust(truster, trustee);
			final Trust oldTrust = trust.clone();
			trust.trusterEditionUpdated();
			trust.setComment(newComment);
			trust.storeWithoutCommit();

			if(trust.getValue() != newValue) {
				trust.setValue(newValue);
				trust.storeWithoutCommit();
				Logger.debug(this, "Updated trust value ("+ trust +"), now updating Score.");
				updateScoresWithoutCommit(oldTrust, trust);
			}
		} catch (NotTrustedException e) {
			final Trust trust = new Trust(truster, trustee, newValue, newComment);
			trust.initializeTransient(this);
			trust.storeWithoutCommit();
			Logger.debug(this, "New trust value ("+ trust +"), now updating Score.");
			updateScoresWithoutCommit(null, trust);
		} 

		truster.updated();
		truster.storeWithoutCommit();
	}
	
	/**
	 * Only for being used by WoT internally and by unit tests!
	 */
	synchronized void setTrust(OwnIdentity truster, Identity trustee, byte newValue, String newComment)
		throws InvalidParameterException {
		
		synchronized(mDB.lock()) {
			try {
				setTrustWithoutCommit(truster, trustee, newValue, newComment);
				Persistent.checkedCommit(mDB, this);
			}
			catch(RuntimeException e) {
				Persistent.checkedRollbackAndThrow(mDB, this, e);
			}
		}
	}
	
	protected synchronized void removeTrust(OwnIdentity truster, Identity trustee) {
		synchronized(mDB.lock()) {
			try  {
				removeTrustWithoutCommit(truster, trustee);
				Persistent.checkedCommit(mDB, this);
			}
			catch(RuntimeException e) {
				Persistent.checkedRollbackAndThrow(mDB, this, e);
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
				Persistent.checkedRollbackAndThrow(mDB, this, e);
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
		trust.deleteWithoutCommit();
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
			final Score score = new Score(identity, identity, Integer.MAX_VALUE, 0, 100);
			score.initializeTransient(this);
			score.storeWithoutCommit();
		}
	}

	/**
	 * Computes the trustee's Score value according to the trusts it has received and the capacity of its trusters in the specified
	 * trust tree.
	 * 
	 * @param truster The OwnIdentity that owns the trust tree
	 * @param trustee The identity for which the score shall be computed.
	 * @return The new Score of the identity. Integer.MAX_VALUE if the trustee is equal to the truster.
	 * @throws DuplicateScoreException if there already exist more than one {@link Score} objects for the trustee (should never happen)
	 */
	private synchronized int computeScoreValue(OwnIdentity truster, Identity trustee) throws DuplicateScoreException {
		if(trustee == truster)
			return Integer.MAX_VALUE;
		
		int value = 0;
		
		try {
			return getTrust(truster, trustee).getValue();
		}
		catch(NotTrustedException e) { }
		
		for(Trust trust : getReceivedTrusts(trustee)) {
			try {
				final Score trusterScore = getScore(truster, trust.getTruster());
				value += ( trust.getValue() * trusterScore.getCapacity() ) / 100;
			} catch (NotInTrustTreeException e) {}
		}
		return value;
	}
	
	/**
	 * Computes the trustees's rank in the trust tree of the truster.
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
	 * @param truster The OwnIdentity that owns the trust tree
	 * @return The new Rank if this Identity
	 * @throws DuplicateScoreException if there already exist more than one {@link Score} objects for the trustee (should never happen)
	 */
	private synchronized int computeRank(OwnIdentity truster, Identity trustee) throws DuplicateScoreException {
		if(trustee == truster)
			return 0;
		
		int rank = -1;
		
		try {
			Trust treeOwnerTrust = getTrust(truster, trustee);
			
			if(treeOwnerTrust.getValue() > 0)
				return 1;
			else
				return Integer.MAX_VALUE;
		} catch(NotTrustedException e) { }
		
		for(Trust trust : getReceivedTrusts(trustee)) {
			try {
				Score score = getScore(truster, trust.getTruster());

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
		Persistent.checkedRollback(mDB, this, e);
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
		else
			assert(computeAllScoresWithoutCommit()); // Verify whether updateScoresWithoutCommit worked.
		
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
		
		// We cannot iteratively REMOVE an inherited rank from the trustees because we don't know whether there is a circle in the trust values
		// which would make the current identity get its old rank back via the circle: computeRank searches the trusters of an identity for the best
		// rank, if we remove the rank from an identity, all its trustees will have a better rank and if one of them trusts the original identity
		// then this function would run into an infinite loop. Decreasing or incrementing an existing rank is possible with this function because
		// the rank received from the trustees will always be higher (that is exactly 1 more) than this identities rank.
		if(trustWasDeleted) { 
			mFullScoreComputationNeeded = true;
		}

		if(!mFullScoreComputationNeeded && (trustWasCreated || trustWasModified)) {
			for(OwnIdentity treeOwner : getAllOwnIdentities()) {
				try {
					// Throws to abort the update of the trustee's score: If the truster has no rank or capacity in the tree owner's view then we don't need to update the trustee's score.
					if(getScore(treeOwner, newTrust.getTruster()).getCapacity() == 0)
						continue;
				} catch(NotInTrustTreeException e) {
					continue;
				}
				
				// See explanation above "We cannot iteratively REMOVE an inherited rank..."
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

					Score currentStoredTrusteeScore;

					try {
						currentStoredTrusteeScore = getScore(treeOwner, trustee);
					} catch(NotInTrustTreeException e) {
						currentStoredTrusteeScore = new Score(treeOwner, trustee, 0, -1, 0);
						currentStoredTrusteeScore.initializeTransient(this);
					}
					
					final Score oldScore = currentStoredTrusteeScore.clone();
					boolean oldShouldFetch = shouldFetchIdentity(trustee);
					
					final int newScoreValue = computeScoreValue(treeOwner, trustee); 
					final int newRank = computeRank(treeOwner, trustee);
					final int newCapacity = computeCapacity(treeOwner, trustee, newRank);
					final Score newScore = new Score(treeOwner, trustee, newScoreValue, newRank, newCapacity);

					// Normally we couldn't detect the following two cases due to circular trust values. However, if an own identity assigns a trust value,
					// the rank and capacity are always computed based on the trust value of the own identity so we must also check this here:

					if((oldScore.getRank() >= 0 && oldScore.getRank() < Integer.MAX_VALUE) // It had an inheritable rank
							&& (newScore.getRank() == -1 || newScore.getRank() == Integer.MAX_VALUE)) { // It has no inheritable rank anymore
						mFullScoreComputationNeeded = true;
						break;
					}
					
					if(oldScore.getCapacity() > 0 && newScore.getCapacity() == 0) {
						mFullScoreComputationNeeded = true;
						break;
					}
					
					// We are OK to update it now. We must not update the values of the stored score object before determining whether we need
					// a full score computation - the full computation needs the old values of the object.
					
					currentStoredTrusteeScore.setValue(newScore.getScore());
					currentStoredTrusteeScore.setRank(newScore.getRank());
					currentStoredTrusteeScore.setCapacity(newScore.getCapacity());
					
					// Identities should not get into the queue if they have no rank, see the large if() about 20 lines below
					assert(currentStoredTrusteeScore.getRank() >= 0); 
					
					if(currentStoredTrusteeScore.getRank() >= 0)
						currentStoredTrusteeScore.storeWithoutCommit();
					
					// If fetch status changed from false to true, we need to start fetching it
					// If the capacity changed from 0 to positive, we need to refetch the current edition: Identities with capacity 0 cannot
					// cause new identities to be imported from their trust list, capacity > 0 allows this.
					// If the fetch status changed from true to false, we need to stop fetching it
					if((!oldShouldFetch || (oldScore.getCapacity()== 0 && newScore.getCapacity() > 0)) && shouldFetchIdentity(trustee)) { 
						if(!oldShouldFetch)
							Logger.debug(this, "Fetch status changed from false to true, refetching " + trustee);
						else
							Logger.debug(this, "Capacity changed from 0 to " + newScore.getCapacity() + ", refetching" + trustee);

						trustee.markForRefetch();
						trustee.storeWithoutCommit();

						mFetcher.storeStartFetchCommandWithoutCommit(trustee);
					}
					else if(oldShouldFetch && !shouldFetchIdentity(trustee)) {
						Logger.debug(this, "Fetch status changed from true to false, aborting fetch of " + trustee);

						mFetcher.storeAbortFetchCommandWithoutCommit(trustee);
					}
					
					// If the rank or capacity changed then the trustees might be affected because the could have inherited theirs
					if(oldScore.getRank() != newScore.getRank() || oldScore.getCapacity() != newScore.getCapacity()) {
						// If this identity has no capacity or no rank then it cannot affect its trustees:
						// (- If it had none and it has none now then there is none which can be inherited, this is obvious)
						// - If it had one before and it was removed, this algorithm will have aborted already because a full computation is needed
						if(newScore.getCapacity() > 0 || (newScore.getRank() >= 0 && newScore.getRank() < Integer.MAX_VALUE)) {
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
		
		if(!mTrustListImportInProgress) {
			if(mFullScoreComputationNeeded) {
				// TODO: Optimization: This uses very much CPU and memory. Write a partial computation function...
				// TODO: Optimization: While we do not have a partial computation function, we could at least optimize computeAllScores to NOT
				// keep all objects in memory etc.
				computeAllScoresWithoutCommit();
				assert(computeAllScoresWithoutCommit()); // computeAllScoresWithoutCommit is stable
			} else {
				assert(computeAllScoresWithoutCommit()); // This function worked correctly.
			}
		} else { // a trust list import is in progress
			// We not do the following here because it would cause too much CPU usage during debugging: Trust lists are large and therefore 
			// updateScoresWithoutCommit is called often during import of a single trust list
			// assert(computeAllScoresWithoutCommit());
		}
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
			identity.initializeTransient(this);
			identity.storeAndCommit();
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
		synchronized(mDB.lock()) {
			try {
				deleteWithoutCommit(identity);
				Persistent.checkedCommit(mDB, this);
			}
			catch(RuntimeException e) {
				Persistent.checkedRollbackAndThrow(mDB, this, e);
			}
		}
		}
	}
	
	public synchronized void deleteIdentity(String id) throws UnknownIdentityException {
		deleteIdentity(getIdentityByID(id));
	}
	
	public OwnIdentity createOwnIdentity(String nickName, boolean publishTrustList, String context)
		throws MalformedURLException, InvalidParameterException {
		
		FreenetURI[] keypair = getPluginRespirator().getHLSimpleClient().generateKeyPair(WOT_NAME);
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
				identity.initializeTransient(this);
				
				if(context != null)
					identity.addContext(context);
				
				if(publishTrustList) {
					identity.addContext(IntroductionPuzzle.INTRODUCTION_CONTEXT); /* TODO: make configureable */
					identity.setProperty(IntroductionServer.PUZZLE_COUNT_PROPERTY, Integer.toString(IntroductionServer.DEFAULT_PUZZLE_COUNT));
				}
				
				try {
					identity.storeWithoutCommit();
					initTrustTreeWithoutCommit(identity);
					
					beginTrustListImport();
					
					for(String seedURI : SEED_IDENTITIES) {
						try {
							setTrustWithoutCommit(identity, getIdentityByURI(seedURI), (byte)100, "Automatically assigned trust to a seed identity.");
						} catch(UnknownIdentityException e) {
							Logger.error(this, "SHOULD NOT HAPPEN: Seed identity not known: " + e);
						}
					}
					
					finishTrustListImport();
					Persistent.checkedCommit(mDB, this);
					
					if(mIntroductionClient != null)
						mIntroductionClient.nextIteration(); // This will make it fetch more introduction puzzles.
					
					Logger.debug(this, "Successfully created a new OwnIdentity (" + identity.getNickname() + ")");
					return identity;
				}
				catch(RuntimeException e) {
					abortTrustListImport(e); // Rolls back for us
					throw e; // Satisfy the compiler
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
					identity.initializeTransient(this);
					/* We re-fetch the current edition to make sure all trustees are imported */
					identity.restoreEdition(edition);
				
					identity.setContexts(old.getContexts());
					identity.setProperties(old.getProperties());
					
					identity.storeWithoutCommit();
					initTrustTreeWithoutCommit(identity);
	
					// Update all received trusts
					for(Trust oldReceivedTrust : getReceivedTrusts(old)) {
						Trust newReceivedTrust = new Trust(oldReceivedTrust.getTruster(), identity,
								oldReceivedTrust.getValue(), oldReceivedTrust.getComment());
						
						newReceivedTrust.initializeTransient(this);
						newReceivedTrust.storeWithoutCommit();
					}
		
					// Update all received scores
					for(Score oldScore : getScores(old)) {
						Score newScore = new Score(oldScore.getTruster(), identity, oldScore.getScore(),
								oldScore.getRank(), oldScore.getCapacity());
						
						newScore.initializeTransient(this);
						newScore.storeWithoutCommit();
					}
					
					beginTrustListImport();
					
					// Update all given trusts
					for(Trust givenTrust : getGivenTrusts(old)) {
						// TODO: Deleting the trust object right here would save us N score recalculations for N trust objects and probably make
						// restoreIdentity() almost twice as fast:
						// deleteWithoutCommit() calls updateScoreWithoutCommit() per trust value, setTrustWithoutCommit() also does that
						// However, the current approach of letting deleteWithoutCommit() do all the deletions is more clean. Therefore,
						// we should introduce the db.delete(givenTrust)  hereonly after having a unit test for restoreIdentity().
						setTrustWithoutCommit(identity, givenTrust.getTrustee(), givenTrust.getValue(), givenTrust.getComment());
					}
					
					finishTrustListImport();
		
					// Remove the old identity and all objects associated with it.
					deleteWithoutCommit(old);
					
					Logger.debug(this, "Successfully restored an already known identity from Freenet (" + identity.getNickname() + ")");
					
				} catch (UnknownIdentityException e) {
					identity = new OwnIdentity(new FreenetURI(insertURI), new FreenetURI(requestURI), null, false);
					identity.initializeTransient(this);
					identity.restoreEdition(edition);
					identity.updateLastInsertDate();
					
					// TODO: Instead of deciding by date whether the current edition was inserted, we should probably decide via a boolean.
					
					// Store the new identity
					identity.storeWithoutCommit();
					initTrustTreeWithoutCommit(identity);
					
					Logger.debug(this, "Successfully restored not-yet-known identity from Freenet (" + identity.getRequestURI() + ")");
				}
				
				// This is not really necessary because OwnIdenity.needsInsert() returns false if currentEditionWasFetched() is false.
				// However, we still do it because the user might have specified URIs with old edition numbers: Then the IdentityInserter would
				// start insertion the old trust lists immediately after the first one was fetched. With the last insert date being set to current
				// time, this is less likely to happen because the identity inserter has a minimal delay between last insert and next insert.
				identity.updateLastInsertDate();
				
				mFetcher.storeStartFetchCommandWithoutCommit(identity);
				Persistent.checkedCommit(mDB, this);
			}
			catch(RuntimeException e) {
				abortTrustListImport(e);
				Persistent.checkedRollbackAndThrow(mDB, this, e);
			}
		}
		}
	}

	public synchronized void setTrust(String ownTrusterID, String trusteeID, byte value, String comment)
		throws UnknownIdentityException, NumberFormatException, InvalidParameterException {
		
		final OwnIdentity truster = getOwnIdentityByID(ownTrusterID);
		Identity trustee = getIdentityByID(trusteeID);
		
		setTrust(truster, trustee, value, comment);
	}
	
	public synchronized void removeTrust(String ownTrusterID, String trusteeID) throws UnknownIdentityException {
		final OwnIdentity truster = getOwnIdentityByID(ownTrusterID);
		final Identity trustee = getIdentityByID(trusteeID);

		removeTrust(truster, trustee);
	}
	
	public synchronized void addContext(String ownIdentityID, String newContext) throws UnknownIdentityException, InvalidParameterException {
		final Identity identity = getOwnIdentityByID(ownIdentityID);
		identity.addContext(newContext);
		identity.storeAndCommit();
		
		Logger.debug(this, "Added context '" + newContext + "' to identity '" + identity.getNickname() + "'");
	}

	public synchronized void removeContext(String ownIdentityID, String context) throws UnknownIdentityException, InvalidParameterException {
		final Identity identity = getOwnIdentityByID(ownIdentityID);
		identity.removeContext(context);
		identity.storeAndCommit();
		
		Logger.debug(this, "Removed context '" + context + "' from identity '" + identity.getNickname() + "'");
	}
	
	public synchronized String getProperty(String identityID, String property) throws InvalidParameterException, UnknownIdentityException {
		return getIdentityByID(identityID).getProperty(property);
	}

	public synchronized void setProperty(String ownIdentityID, String property, String value)
		throws UnknownIdentityException, InvalidParameterException {
		
		Identity identity = getOwnIdentityByID(ownIdentityID);
		identity.setProperty(property, value);
		identity.storeAndCommit();
		
		Logger.debug(this, "Added property '" + property + "=" + value + "' to identity '" + identity.getNickname() + "'");
	}
	
	public synchronized void removeProperty(String ownIdentityID, String property) throws UnknownIdentityException, InvalidParameterException {
		final Identity identity = getOwnIdentityByID(ownIdentityID);
		identity.removeProperty(property);
		identity.storeAndCommit();
		
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
        WebOfTrust.l10n = new PluginL10n(this, newLanguage);
        Logger.debug(this, "Set LANGUAGE to: " + newLanguage.isoCode);
	}
	
	public PluginRespirator getPluginRespirator() {
		return mPR;
	}
	
	public ExtObjectContainer getDatabase() {
		return mDB;
	}
	
	public Configuration getConfig() {
		return mConfig;
	}
	
	public SubscriptionManager getSubscriptionManager() {
		return mSubscriptionManager;
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
	
	protected FCPInterface getFCPInterface() {
		return mFCPInterface;
	}

	public RequestClient getRequestClient() {
		return mRequestClient;
	}

    /**
     * This is where our L10n files are stored.
     * @return Path of our L10n files.
     */
    public String getL10nFilesBasePath() {
        return "plugins/WebOfTrust/l10n/";
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
        return "WebOfTrust_lang_${lang}.override.l10n";
    }

    /**
     * Get the ClassLoader of this plugin. This is necessary when getting
     * resources inside the plugin's Jar, for example L10n files.
     * @return ClassLoader object
     */
    public ClassLoader getPluginClassLoader() {
        return WebOfTrust.class.getClassLoader();
    }

    /**
     * Access to the current L10n data.
     *
     * @return L10n object.
     */
    public BaseL10n getBaseL10n() {
        return WebOfTrust.l10n.getBase();
    }

	
    /**
     * Tests whether two WoT are equal.
     * This is a complex operation in terms of execution time and memory usage and only intended for being used in unit tests.
     */
	public synchronized boolean equals(Object obj) {
		if(obj == this)
			return true;
		
		if(!(obj instanceof WebOfTrust))
			return false;
		
		WebOfTrust other = (WebOfTrust)obj;
		
		synchronized(other) {

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
					OwnIdentity otherTruster = other.getOwnIdentityByID(score.getTruster().getID());
					Identity otherTrustee = other.getIdentityByID(score.getTrustee().getID());
					
					if(!score.equals(other.getScore(otherTruster, otherTrustee)))
						return false;
				} catch(UnknownIdentityException e) {
					return false;
				} catch(NotInTrustTreeException e) {
					return false;
				}
			}
		}
		
		}
		
		return true;
	}
    
    
}
