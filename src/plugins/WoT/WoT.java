/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WoT;

import java.net.MalformedURLException;
import java.util.Date;
import java.util.HashSet;
import java.util.Hashtable;
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
import freenet.l10n.BaseL10n.LANGUAGE;
import freenet.node.RequestClient;
import freenet.pluginmanager.FredPlugin;
import freenet.pluginmanager.FredPluginFCP;
import freenet.pluginmanager.FredPluginL10n;
import freenet.pluginmanager.FredPluginRealVersioned;
import freenet.pluginmanager.FredPluginThreadless;
import freenet.pluginmanager.FredPluginVersioned;
import freenet.pluginmanager.FredPluginWithClassLoader;
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
public class WoT implements FredPlugin, FredPluginThreadless, FredPluginFCP, FredPluginVersioned, FredPluginRealVersioned,
	FredPluginL10n, FredPluginWithClassLoader {
	
	/* Constants */
	
	public static final String DATABASE_FILENAME =  "WebOfTrust-testing.db4o";
	public static final int DATABASE_FORMAT_VERSION = -99;
	
	/** The relative path of the plugin on Freenet's web interface */
	public static final String SELF_URI = "/WoT";
	
	/**
	 * The "name" of this web of trust. It is included in the document name of identity URIs. For an example, see the SEED_IDENTITIES
	 * constant below. The purpose of this costant is to allow anyone to create his own custom web of trust which is completely disconnected
	 * from the "official" web of trust of the Freenet project.
	 */
	public static final String WOT_NAME = "WoT-testing";
	
	/**
	 * The official seed identities of the WoT plugin: If a newbie wants to download the whole offficial web of trust, he needs at least one
	 * trust list from an identity which is well-connected to the web of trust. To prevent newbies from having to add this identity manually,
	 * the Freenet development team provides a list of seed identities - each of them is one of the developers.
	 */
	private static final String[] SEED_IDENTITIES = new String[] { 
		"USK@fWK9InP~vG6HnTDm3wiJgvh6ULJQaU5XYTkXXNuKTTk,GnZgrilXSYjD~xrD6l4~5x~Nspz3aFe2eYXWRvaNRHU,AQACAAE/WoT/120", // xor
		"USK@Ng~ixtLAfKBd4oaW6Ln7Fy~Z9Wm8HSoqIKvy4zzt3Sc,Cytpvs9neFQM0Ju4Yb2BCEC7VEZfeX8VAOpQgvOAY80,AQACAAE/WoT/0" // toad
		/* FIXME: Add the developers. But first we need to debug :) */
	};
	
	private static final String SEED_IDENTITY_MANDATORY_VERSION_PROPERTY = "MandatoryVersion";
	private static final String SEED_IDENTITY_LATEST_VERSION_PROPERTY = "LatestVersion";
	
	

	/* References from the node */
	
	/** The ClassLoader which was used to load the plugin JAR, needed by db4o to work correctly */
	private ClassLoader mClassLoader;
	
	/** The node's interface to connect the plugin with the node, needed for retrieval of all other interfaces */
	private PluginRespirator mPR;	
	
	
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
	
	
	/* User interfaces */
	
	private WebInterface mWebInterface;
	private FCPInterface mFCPInterface;

	public void runPlugin(PluginRespirator myPR) {
		try {
			Logger.debug(this, "Start");
			
			/* Catpcha generation needs headless mode on linux */
			System.setProperty("java.awt.headless", "true"); 
	
			mPR = myPR;
			mDB = initDB(DATABASE_FILENAME); /* FIXME: Change before release */
			
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
			
			mIntroductionServer = new IntroductionServer(this, mFetcher);
			mIntroductionServer.start();
			
			mIntroductionClient = new IntroductionClient(this);
			mIntroductionClient.start();

			mWebInterface = new WebInterface(this, SELF_URI);
			mFCPInterface = new FCPInterface(this);
			
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
		cfg.reflectWith(new JdkReflector(mClassLoader));
		cfg.activationDepth(5); /* TODO: Change to 1 and add explicit activation everywhere */
		cfg.exceptionsOnNotStorable(true);
		
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
		int oldVersion = mConfig.getInt(Config.DATABASE_FORMAT_VERSION);
		
		if(oldVersion == WoT.DATABASE_FORMAT_VERSION)
			return;
		
		try {
		if(oldVersion == -100) {
			Logger.normal(this, "Found old database (-100), adding last fetched date to all identities ...");
			for(Identity identity : getAllIdentities()) {
				identity.mLastFetchedDate = new Date(0);
				storeWithoutCommit(identity);
			}
			
			mConfig.set(Config.DATABASE_FORMAT_VERSION, WoT.DATABASE_FORMAT_VERSION);
			mConfig.storeAndCommit();
		}
		else
			throw new RuntimeException("Your database is too outdated to be upgraded automatically, please create a new one by deleting " 
				+ DATABASE_FILENAME + ". Contact the developers if you really need your old data.");
		}
		catch(RuntimeException e) {
			mDB.rollback(); Logger.debug(this, "ROLLED BACK!");
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
								mDB.rollback(); Logger.error(this, "ROLLED BACK!", e);
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
				mDB.rollback(); Logger.debug(this, "ROLLED BACK!"); 
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
				mDB.rollback(); Logger.debug(this, "ROLLED BACK!"); 
			}
		}
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
					storeAndCommit(seed);
				} catch (Exception e) {
					Logger.error(this, "Seed identity creation error", e);
				}
			}
			catch (Exception e) {
				Logger.error(this, "Seed identity loading error", e);
				mDB.rollback(); Logger.debug(this, "ROLLED BACK!");
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
					mDB.rollback(); Logger.debug(this, "ROLLED BACK!");
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
	 * @param db A reference to the database
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
				mDB.rollback(); Logger.debug(this, "ROLLED BACK!");
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
		if(mDB.ext().isStored(identity) && !mDB.ext().isActive(identity))
			throw new RuntimeException("Trying to store an inactive Identity object!");

		/* FIXME: We also need to check whether the member objects are active here!!! */

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
			mDB.rollback(); Logger.debug(this, "ROLLED BACK!");
			throw e;
		}
	}
	
	private void deleteWithoutCommit(Identity identity) {
		if(mDB.ext().isStored(identity) && !mDB.ext().isActive(identity))
			throw new RuntimeException("Trying to delete an inactive Identity object!");
		
		if(mFetcher != null)
			mFetcher.storeAbortFetchCommandWithoutCommit(identity);
		
		/* FIXME: We also need to check whether the member objects are active here!!! */
		
		try {
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
			mDB.rollback(); Logger.debug(this, "ROLLED BACK!");
			throw e;
		}
	}

	/**
	 * Gets the score of this identity in a trust tree.
	 * Each {@link OwnIdentity} has its own trust tree.
	 * 
	 * @param treeOwner The owner of the trust tree
	 * @param db A reference to the database
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
	 * Gets the best score this Identity has in existing trust trees, 0 if it is not in the trust tree.
	 * 
	 * @return the best score this Identity has
	 */
	public synchronized int getBestScore(Identity identity) throws NotInTrustTreeException {
		int bestScore = 0;
		ObjectSet<Score> scores = getScores(identity);
		
		if(scores.size() == 0)
			throw new NotInTrustTreeException(identity);
		
		// TODO: Cache the best score of an identity as a member variable.
		for(Score score : scores) 
			bestScore = Math.max(score.getScore(), bestScore);
		
		return bestScore;
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
	 * @return Returns true if the identity has any score >= 0 or if it is an own identity.
	 */
	public boolean shouldFetchIdentity(Identity identity) {
		if(identity instanceof OwnIdentity)
			return true;
		
		try {
			return getBestScore(identity) >= 0;
		}
		catch(NotInTrustTreeException e) {
			return false;
		}
	}
	
	/**
	 * Gets Identities matching a specified score criteria.
	 * You have to synchronize on this WoT when calling the function and processing the returned list!
	 * 
	 * @param owner requestURI of the owner of the trust tree, null if you want the trusted identities of all owners.
	 * @param select Score criteria, can be '+', '0' or '-'
	 * @return an {@link ObjectSet} containing Identities that match the criteria
	 * @throws InvalidParameterException if the criteria is not recognised
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
	 *     catch(RuntimeException e) { mDB.rollback(); throw e; }
	 * }
	 * 
	 * @param truster The Identity that gives the trust
	 * @param trustee The Identity that receives the trust
	 * @param newValue Numeric value of the trust
	 * @param newComment A comment to explain the given value
	 * @throws InvalidParameterException if a given parameter isn't valid, {@see Trust} for details on accepted values.
	 */
	protected synchronized void setTrustWithoutCommit(Identity truster, Identity trustee, byte newValue, String newComment)
		throws InvalidParameterException {
		
		Trust trust;
		try { // Check if we are updating an existing trust value
			trust = getTrust(truster, trustee);
			trust.trusterEditionUpdated();
			trust.setComment(newComment);
			mDB.store(trust);

			if(trust.getValue() != newValue) {
				trust.setValue(newValue);
				mDB.store(trust);
				Logger.debug(this, "Updated trust value ("+ trust +"), now updating Score.");
				updateScoreWithoutCommit(trustee);
			}
		} catch (NotTrustedException e) {
			trust = new Trust(truster, trustee, newValue, newComment);
			mDB.store(trust);
			Logger.debug(this, "New trust value ("+ trust +"), now updating Score.");
			updateScoreWithoutCommit(trustee);
		} 

		truster.updated();
		storeWithoutCommit(truster);
	}
	
	/**
	 * Only for being used by WoT internally and by unit tests! 
	 * 
	 * @param truster Must be an own identity unless the function is being used in an unit test!
	 */
	synchronized void setTrust(Identity truster, Identity trustee, byte newValue, String newComment)
		throws InvalidParameterException {
		
		assert(truster instanceof OwnIdentity); /* Unit tests may ignore this. */
		
		synchronized(mDB.lock()) {
			try {
				setTrustWithoutCommit(truster, trustee, newValue, newComment);
				mDB.commit(); Logger.debug(this, "COMMITED.");
			}
			catch(RuntimeException e) {
				mDB.rollback(); Logger.debug(this, "ROLLED BACK!");
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
	 *     catch(RuntimeException e) { mDB.rollback(); throw e; }
	 * }
	 * 
	 * @param truster
	 * @param trustee
	 */
	protected synchronized void removeTrustWithoutCommit(OwnIdentity truster, Identity trustee) {
			try {
				try {
					Trust trust = getTrust(truster, trustee);
					mDB.delete(trust);
					updateScoreWithoutCommit(trustee);
				} catch (NotTrustedException e) {
					Logger.error(this, "Cannot remove trust - there is none - from " + truster.getNickname() + " to "
						+ trustee.getNickname());
				} 
			}
			catch(RuntimeException e) {
				mDB.rollback(); Logger.debug(this, "ROLLED BACK!");
				throw e;
			}
	}
	
	/**
	 * 
	 * This function does neither lock the database nor commit the transaction. You have to surround it with
	 * synchronized(mDB.lock()) {
	 *     try { ... setTrustWithoutCommit(...); mDB.commit(); }
	 *     catch(RuntimeException e) { mDB.rollback(); throw e; }
	 * }
	 * 
	 */
	protected synchronized void removeTrustWithoutCommit(Trust trust) {
		mDB.delete(trust);
		updateScoreWithoutCommit(trust.getTrustee());
	}
	
	/**
	 * Initializes this OwnIdentity's trust tree without commiting the transaction. 
	 * Meaning : It creates a Score object for this OwnIdentity in its own trust tree, 
	 * so it gets a rank and a capacity and can give trust to other Identities.
	 * 
	 * This function does neither lock the database nor commit the transaction. You have to surround it with
	 * synchronized(mDB.lock()) {
	 *     try { ... initTrustTreeWithoutCommit(...); mDB.commit(); }
	 *     catch(RuntimeException e) { mDB.rollback(); throw e; }
	 * }
	 *  
	 * @param db A reference to the database 
	 * @throws DuplicateScoreException if there already is more than one Score for this identity (should never happen)
	 */
	private synchronized void initTrustTreeWithoutCommit(OwnIdentity identity) throws DuplicateScoreException {
		try {
			getScore(identity, identity);
			Logger.error(this, "initTrusTree called even though there is already one for " + identity);
			return;
		} catch (NotInTrustTreeException e) {
			mDB.store(new Score(identity, identity, 100, 0, 100));
		}
	}
	
	/**
	 * Updates this Identity's {@link Score} in every trust tree.
	 * 
	 * This function does neither lock the database nor commit the transaction. You have to surround it with
	 * synchronized(mDB.lock()) {
	 *     try { ... updateScoreWithoutCommit(...); storeAndCommit(trustee); }
	 *     catch(RuntimeException e) { mDB.rollback(); throw e; }
	 * }
	 * 
	 */
	private synchronized void updateScoreWithoutCommit(Identity trustee) {
		ObjectSet<OwnIdentity> treeOwners = getAllOwnIdentities();
		if(treeOwners.size() == 0)
			Logger.debug(this, "Can't update " + trustee.getNickname() + "'s score: there is no own identity yet");

		while(treeOwners.hasNext())
			updateScoreWithoutCommit(treeOwners.next(), trustee);
	}
	
	/**
	 * Updates this Identity's {@link Score} in one trust tree.
	 * Makes this Identity's trustees update their score if its capacity has changed.
	 * 
	 * This function does neither lock the database nor commit the transaction. You have to surround it with
	 * synchronized(mDB.lock()) {
	 *     try { ... updateScoreWithoutCommit(...); mDB.commit(); }
	 *     catch(RuntimeException e) { mDB.rollback(); throw e; }
	 * }
	 * 
	 * @param db A reference to the database
	 * @param treeOwner The OwnIdentity that owns the trust tree
	 */
	private synchronized void updateScoreWithoutCommit(OwnIdentity treeOwner, Identity target) {
		if(target == treeOwner)
			return;
		
		boolean changedCapacity = false;
		
		Logger.debug(target, "Updating " + target.getNickname() + "'s score in " + treeOwner.getNickname() + "'s trust tree...");
		
		Score score;
		int value = computeScoreValue(treeOwner, target);
		int rank = computeRank(treeOwner, target);
		
		
		
		if(rank == -1) { // -1 value means the identity is not in the trust tree
			try { // If he had a score, we delete it
				score = getScore(treeOwner, target);
				mDB.delete(score); // He had a score, we delete it
				changedCapacity = true;
				Logger.debug(target, target.getNickname() + " is not in " + treeOwner.getNickname() + "'s trust tree anymore");
			} catch (NotInTrustTreeException e) { } 
		}
		else { // The identity is in the trust tree
			
			/* We must detect if an identity had a null or negative score which has changed to positive:
			 * If we download the trust list of someone who has no or negative score we do not create the identities in his trust list.
			 * If the identity now gets a positive score we must re-download his current trust list. */
			boolean scoreSignChanged;
		
			try { // Get existing score or create one if needed
				score = getScore(treeOwner, target);
				scoreSignChanged = Integer.signum(score.getScore()) != Integer.signum(value);
			} catch (NotInTrustTreeException e) {
				score = new Score(treeOwner, target, 0, -1, 0);
				scoreSignChanged = true;
			}
			
			boolean oldShouldFetch = true;
			
			if(scoreSignChanged) // No need to figure out whether the identity should have been fetched in the past if the score sign did not change
				oldShouldFetch = shouldFetchIdentity(target);
			
			score.setValue(value);
			score.setRank(rank + 1);
			
			int oldCapacity = score.getCapacity();
			
			boolean hasNegativeTrust = false;
			// Does the treeOwner personally distrust this identity ?
			try {
				if(getTrust(treeOwner, target).getValue() < 0) {
					hasNegativeTrust = true;
					Logger.debug(target, target.getNickname() + " received negative trust from " + treeOwner.getNickname() + 
							" and therefore has no capacity in his trust tree.");
				}
			} catch (NotTrustedException e) {}
			
			if(hasNegativeTrust)
				score.setCapacity(0);
			else
				score.setCapacity((score.getRank() >= Score.capacities.length) ? 1 : Score.capacities[score.getRank()]);
			
			if(score.getCapacity() != oldCapacity)
				changedCapacity = true;
			
			mDB.store(score);
			
			
			if(scoreSignChanged) {
				if(!oldShouldFetch && shouldFetchIdentity(target)) { 
					Logger.debug(this, "Best score changed from negative/null to positive, refetching " + target);
					
					target.markForRefetch();
					storeWithoutCommit(target);
					
					if(mFetcher != null) {
						mFetcher.storeStartFetchCommandWithoutCommit(target);
					}
				}
				
				if(oldShouldFetch && !shouldFetchIdentity(target)) {
					Logger.debug(this, "Best score changed from positive/null to negative, aborting fetch of " + target);
					
					if(mFetcher != null)
						mFetcher.storeAbortFetchCommandWithoutCommit(target);
				}
			}
			
			
			Logger.debug(target, "New score: " + score.toString());
		}
		
		if(changedCapacity) { // We have to update trustees' score
			ObjectSet<Trust> givenTrusts = getGivenTrusts(target);
			Logger.debug(target, target.getNickname() + "'s capacity has changed in " + treeOwner.getNickname() +
					"'s trust tree, updating his (" + givenTrusts.size() + ") trustees");
			
			for(Trust givenTrust : givenTrusts)
				updateScoreWithoutCommit(treeOwner, givenTrust.getTrustee());
		}
		
	}
	
	/**
	 * Computes the target's Score value according to the trusts it has received and the capacity of its trusters in the specified
	 * trust tree.
	 * 
	 * @param db A reference to the database
	 * @param treeOwner The OwnIdentity that owns the trust tree
	 * @return The new Score if this Identity
	 * @throws DuplicateScoreException if there already exist more than one {@link Score} objects for the trustee (should never happen)
	 */
	private synchronized int computeScoreValue(OwnIdentity treeOwner, Identity target) throws DuplicateScoreException {
		int value = 0;
		
		ObjectSet<Trust> receivedTrusts = getReceivedTrusts(target);
		while(receivedTrusts.hasNext()) {
			Trust trust = receivedTrusts.next();
			try {
				value += trust.getValue() * (getScore(treeOwner, trust.getTruster())).getCapacity() / 100;
			} catch (NotInTrustTreeException e) {}
		}
		return value;
	}
	
	/**
	 * Computes the target's rank in the trust tree.
	 * It gets its best ranked truster's rank, plus one. Or -1 if none of its trusters are in the trust tree. 
	 *  
	 * @param db A reference to the database
	 * @param treeOwner The OwnIdentity that owns the trust tree
	 * @return The new Rank if this Identity
	 * @throws DuplicateScoreException if there already exist more than one {@link Score} objects for the trustee (should never happen)
	 */
	private synchronized int computeRank(OwnIdentity treeOwner, Identity target) throws DuplicateScoreException {
		int rank = -1;
		
		ObjectSet<Trust> receivedTrusts = getReceivedTrusts(target);
		while(receivedTrusts.hasNext()) {
			Trust trust = receivedTrusts.next();
			try {
				Score score = getScore(treeOwner, trust.getTruster());
				
				if(score.getCapacity() != 0) // If the truster has no capacity, he can't give his rank
					if(rank == -1 || score.getRank() < rank) // If the truster's rank is better than ours or if we have not  
						rank = score.getRank();
			} catch (NotInTrustTreeException e) {}
		}
		return rank;
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
			//	mDB.rollback(); Logger.error(this, "ROLLED BACK: addIdentity() failed", e);
			//	throw error;
			//}
		}
		
		return identity;
	}
	
	public synchronized void deleteIdentity(Identity identity) {
		synchronized(mPuzzleStore) {
		synchronized(mDB.lock()) {
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
					updateScoreWithoutCommit(givenTrust.getTrustee());
				}
				
				Logger.debug(this, "Deleting associated introduction puzzles ...");
				mPuzzleStore.onIdentityDeletion(identity);
				
				Logger.debug(this, "Deleting the identity...");
				deleteWithoutCommit(identity);
				mDB.commit(); Logger.debug(this, "COMMITED.");
			}
			catch(RuntimeException e) {
				mDB.rollback(); Logger.debug(this, "ROLLED BACK!");
				throw e;
			}
		}
		}
	}
	
	public synchronized void deleteIdentity(String id) throws UnknownIdentityException {
		synchronized(mDB.lock()) {
			Identity identity = getIdentityByID(id);
			deleteIdentity(identity);
		}
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
				identity.addContext(IntroductionPuzzle.INTRODUCTION_CONTEXT); /* FIXME: make configureable */
				identity.setProperty(IntroductionServer.PUZZLE_COUNT_PROPERTY, Integer.toString(IntroductionServer.DEFAULT_PUZZLE_COUNT));
				
				try {
					storeWithoutCommit(identity);
					initTrustTreeWithoutCommit(identity);
					
					for(String seedURI : SEED_IDENTITIES) {
						try {
							setTrustWithoutCommit(identity, getIdentityByURI(seedURI), (byte)100, "I trust the Freenet developers.");
						} catch(UnknownIdentityException e) {
							Logger.error(this, "SHOULD NOT HAPPEN: Seed identity not known.", e);
						}
					}
					
					mDB.commit(); Logger.debug(this, "COMMITED.");
					
					if(mIntroductionClient != null)
						mIntroductionClient.nextIteration(); // This will make it fetch more introduction puzzles.
					
					Logger.debug(this, "Successfully created a new OwnIdentity (" + identity.getNickname() + ")");
					return identity;
				}
				catch(RuntimeException e) {
					mDB.rollback(); Logger.debug(this, "ROLLED BACK!");
					throw e;
				}
			}
		}
	}

	public synchronized void restoreIdentity(String requestURI, String insertURI) throws MalformedURLException, InvalidParameterException {
		OwnIdentity identity;
		synchronized(mDB.lock()) {
			try {
				try {
					Identity old = getIdentityByURI(requestURI);
					
					if(old instanceof OwnIdentity)
						throw new InvalidParameterException("There is already an own identity with the given URI pair.");
					
					// We already have fetched this identity as a stranger's one. We need to update the database.
					identity = new OwnIdentity(insertURI, requestURI, old.getNickname(), old.doesPublishTrustList());
					/* We re-fetch the current edition to make sure all trustees are imported */
					identity.setEdition(old.getEdition() - 1);
				
					identity.setContexts(old.getContexts());
					identity.setProperties(old.getProperties());
	
					// Update all received trusts
					for(Trust oldReceivedTrust : getReceivedTrusts(old)) {
						Trust newReceivedTrust = new Trust(oldReceivedTrust.getTruster(), identity,
								oldReceivedTrust.getValue(), oldReceivedTrust.getComment());
						
						mDB.delete(oldReceivedTrust); /* FIXME: Is this allowed by db4o in a for-each loop? */
						mDB.store(newReceivedTrust);
					}
		
					// Update all received scores
					for(Score oldScore : getScores(old)) {
						Score newScore = new Score(oldScore.getTreeOwner(), identity, oldScore.getScore(),
								oldScore.getRank(), oldScore.getCapacity());
						
						mDB.delete(oldScore);
						mDB.store(newScore);
					}
		
					storeWithoutCommit(identity);
					initTrustTreeWithoutCommit(identity);
					
					// Update all given trusts
					for(Trust givenTrust : getGivenTrusts(old)) {
						setTrustWithoutCommit(identity, givenTrust.getTrustee(), givenTrust.getValue(), givenTrust.getComment());
						/* FIXME: The old code would just delete the old trust value here instead of doing the following... 
						 * Is the following line correct? */
						removeTrustWithoutCommit(givenTrust);
					}
		
					// Remove the old identity
					deleteWithoutCommit(old);

					
					Logger.debug(this, "Successfully restored an already known identity from Freenet (" + identity.getNickname() + ")");
					
				} catch (UnknownIdentityException e) {
					identity = new OwnIdentity(new FreenetURI(insertURI), new FreenetURI(requestURI), null, false);
					
					// Store the new identity
					storeWithoutCommit(identity);
					initTrustTreeWithoutCommit(identity);
					
					Logger.debug(this, "Successfully restored not-yet-known identity from Freenet (" + identity.getRequestURI() + ")");
				}
				
				mFetcher.storeStartFetchCommandWithoutCommit(identity);
				mDB.commit(); Logger.debug(this, "COMMITED.");
			}
			catch(RuntimeException e) {
				mDB.rollback(); Logger.debug(this, "ROLLED BACK!");
				throw e;
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
				mDB.rollback(); Logger.debug(this, "ROLLED BACK!");
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
		return key;
	}

	public void setClassLoader(ClassLoader myClassLoader) {
		mClassLoader = myClassLoader;
	}
	
	public void setLanguage(LANGUAGE newLanguage) {
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

}
