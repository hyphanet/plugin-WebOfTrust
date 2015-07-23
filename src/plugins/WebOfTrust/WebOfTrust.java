/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedList;
import java.util.PriorityQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import plugins.WebOfTrust.Identity.FetchState;
import plugins.WebOfTrust.Identity.IdentityID;
import plugins.WebOfTrust.Score.ScoreID;
import plugins.WebOfTrust.Trust.TrustID;
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
import plugins.WebOfTrust.ui.fcp.DebugFCPClient;
import plugins.WebOfTrust.ui.fcp.FCPClientReferenceImplementation.ChangeSet;
import plugins.WebOfTrust.ui.fcp.FCPInterface;
import plugins.WebOfTrust.ui.web.WebInterface;
import plugins.WebOfTrust.util.StopWatch;

import com.db4o.Db4o;
import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.defragment.Defragment;
import com.db4o.defragment.DefragmentConfig;
import com.db4o.ext.ExtObjectContainer;
import com.db4o.query.Query;
import com.db4o.reflect.jdk.JdkReflector;

import freenet.clients.fcp.FCPPluginConnection;
import freenet.clients.fcp.FCPPluginMessage;
import freenet.keys.FreenetURI;
import freenet.keys.USK;
import freenet.l10n.BaseL10n;
import freenet.l10n.BaseL10n.LANGUAGE;
import freenet.l10n.PluginL10n;
import freenet.node.RequestClient;
import freenet.pluginmanager.FredPlugin;
import freenet.pluginmanager.FredPluginBaseL10n;
import freenet.pluginmanager.FredPluginFCP;
import freenet.pluginmanager.FredPluginFCPMessageHandler;
import freenet.pluginmanager.FredPluginL10n;
import freenet.pluginmanager.FredPluginRealVersioned;
import freenet.pluginmanager.FredPluginThreadless;
import freenet.pluginmanager.FredPluginVersioned;
import freenet.pluginmanager.PluginReplySender;
import freenet.pluginmanager.PluginRespirator;
import freenet.support.CurrentTimeUTC;
import freenet.support.Executor;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.PooledExecutor;
import freenet.support.SimpleFieldSet;
import freenet.support.SizeUtil;
import freenet.support.api.Bucket;
import freenet.support.io.FileUtil;

/**
 * A web of trust plugin based on Freenet.
 * 
 * @author xor (xor@freenetproject.org), Julien Cornuwel (batosai@freenetproject.org)
 */
public final class WebOfTrust extends WebOfTrustInterface
    implements
        FredPlugin,
        FredPluginThreadless,
        FredPluginFCPMessageHandler.ServerSideFCPMessageHandler,
        FredPluginFCP,
        FredPluginVersioned,
        FredPluginRealVersioned,
        FredPluginL10n,
        FredPluginBaseL10n {
	
	/* Constants */

	/** The relative path of the plugin on Freenet's web interface */
	public static final String SELF_URI = "/WebOfTrust";

	/** Package-private method to allow unit tests to bypass some assert()s */
	
	public static final String DATABASE_FILENAME =  WebOfTrustInterface.WOT_NAME + ".db4o"; 
	public static final int DATABASE_FORMAT_VERSION = 6;
	
	

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
	 * 
	 * The fetched identity files will be enqueued in the {@link #mIdentityFileQueue} for processing
	 * by the {@link #mIdentityFileProcessor}. */
	private IdentityFetcher mFetcher;
	
	/**
	 * After {@link #mFetcher} has fetched an identity file, it is queued in this queue
	 * for processing by {@link #mIdentityFileProcessor}. */
	private IdentityFileQueue mIdentityFileQueue;
	
	/**
	 * Processes identity files after they were fetched by the {@link #mFetcher} and enqueued in
	 * the {@link #mIdentityFileQueue}. */
	private IdentityFileProcessor mIdentityFileProcessor;
	
	
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
	
	/**
	 * TODO: Performance / Code quality: We have incremental computation in
	 * {@link #updateScoresWithoutCommit(Trust, Trust)} now using
	 * {@link #updateScoresAfterDistrustWithoutCommit(Identity)}. It uses this variable where
	 * full recomputation was needed previously. Thus, this should be renamed to
	 * "mUpdateScoresAfterDistrustNeeded", and probably become a local variable in
	 * {@link #updateScoresWithoutCommit(Trust, Trust)}. However, before doing that, please
	 * review the other code which uses this variable for whether it uses incremental computation
	 * already. */
	private boolean mFullScoreComputationNeeded = false;
	
	private boolean mTrustListImportInProgress = false;
	
	
	/* User interfaces */
	
	private WebInterface mWebInterface;
	private FCPInterface mFCPInterface;
	
	/* Debugging */
	
	private DebugFCPClient mDebugFCPClient;
	
	/* Statistics */
	private int mFullScoreRecomputationCount = 0;
	private long mFullScoreRecomputationMilliseconds = 0;
	private int mIncrementalScoreRecomputationDueToTrustCount = 0;
	private int mIncrementalScoreRecomputationDueToDistrustCount = 0;
	private long mIncrementalScoreRecomputationDueToTrustNanos = 0;
	private long mIncrementalScoreRecomputationDueToDistrustNanos = 0;

	
	/* These booleans are used for preventing the construction of log-strings if logging is disabled (for saving some cpu cycles) */
	
	private static transient volatile boolean logDEBUG = false;
	private static transient volatile boolean logMINOR = false;
	
	static {
		Logger.registerClass(WebOfTrust.class);
	}

	@Override
	public void runPlugin(PluginRespirator myPR) {
		try {
			Logger.normal(this, "Web Of Trust plugin version " + Version.getMarketingVersion() + " starting up...");
			
			/* Catpcha generation needs headless mode on linux */
			System.setProperty("java.awt.headless", "true"); 
	
			mPR = myPR;
			
			/* TODO: This can be used for clean copies of the database to get rid of corrupted internal db4o structures. 
			/* We should provide an option on the web interface to run this once during next startup and switch to the cloned database */
			// cloneDatabase(new File(getUserDataDirectory(), DATABASE_FILENAME), new File(getUserDataDirectory(), DATABASE_FILENAME + ".clone"));
			
			mDB = openDatabase(new File(getUserDataDirectory(), DATABASE_FILENAME));
			
			mConfig = getOrCreateConfig();
			if(mConfig.getDatabaseFormatVersion() > WebOfTrust.DATABASE_FORMAT_VERSION)
				throw new RuntimeException("The WoT plugin's database format is newer than the WoT plugin which is being used.");
			
			mSubscriptionManager = new SubscriptionManager(this);
			
			mPuzzleStore = new IntroductionPuzzleStore(this);
			
			// Queried by IdentityFetcher
			mRequestClient = new RequestClient() {
	
				@Override
				public boolean persistent() {
					return false;
				}

				@Override
				public boolean realTimeFlag() {
					return false;
				}
				
			};


			mIdentityFileQueue = new IdentityFileDiskQueue(getUserDataDirectory());
			// You may use this instead for debugging purposes, or on very high memory nodes.
			// See its JavaDoc for requirements of making this a config option.
			/* mIdentityFileQueue = new IdentityFileMemoryQueue(); */
			
			mXMLTransformer = new XMLTransformer(this);

			mIdentityFileProcessor = new IdentityFileProcessor(
				mIdentityFileQueue, mPR.getNode().getTicker(), mXMLTransformer);

			mFetcher = new IdentityFetcher(this, getPluginRespirator(), mIdentityFileQueue);


			// Please ensure that no threads are using the IntroductionPuzzleStore / IdentityFetcher / SubscriptionManager while this is executing.
			upgradeDB();

			
			mInserter = new IdentityInserter(this);
					
			
			// We only do this if debug logging is enabled since the integrity verification cannot repair anything anyway,
			// if the user does not read his logs there is no need to check the integrity.
			// TODO: Do this once every few startups and notify the user in the web ui if errors are found.
			if(logDEBUG)
				verifyDatabaseIntegrity();

			// TODO: Only do this once every few startups once we are certain that score computation does not have any serious bugs.
			verifyAndCorrectStoredScores();
						
			// Database is up now, integrity is checked. We can start to actually do stuff
			
			// TODO: This can be used for doing backups. Implement auto backup, maybe once a week or month
			//backupDatabase(new File(getUserDataDirectory(), DATABASE_FILENAME + ".backup"));

			mSubscriptionManager.start();
			
			
			createSeedIdentities();
			
			// Identity files flow through the following pipe:
			//     mFetcher -> mIdentityFileQueue -> mIdentityFileProcessor
			// Thus, we start the pipe's daemons in reverse order to ensure that the receiving ones
			// are available before the ones which fill the pipe.
			mIdentityFileProcessor.start();
			/* mIdentityFileQueue.start(); */    // Not necessary, has no thread.
            mFetcher.start();

			
			mInserter.start();

			mIntroductionServer = new IntroductionServer(this, mFetcher);
			mIntroductionServer.start();
			
			mIntroductionClient = new IntroductionClient(this);
			mIntroductionClient.start();

			mWebInterface = WebInterface.constructIfEnabled(this, SELF_URI);

			mFCPInterface = new FCPInterface(this);
			mFCPInterface.start();
			
			if(Logger.shouldLog(LogLevel.DEBUG, DebugFCPClient.class)) {
				mDebugFCPClient = DebugFCPClient.construct(this);
				mDebugFCPClient.start();
			}
			
			Logger.normal(this, "Web Of Trust plugin starting up completed.");
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
		
		if(mConfig.getDatabaseFormatVersion() != WebOfTrust.DATABASE_FORMAT_VERSION)
			throw new RuntimeException("Database format version mismatch. Found: " + mConfig.getDatabaseFormatVersion() + 
					"; expected: " + WebOfTrust.DATABASE_FORMAT_VERSION);
		
		mPuzzleStore = new IntroductionPuzzleStore(this);
		
		mSubscriptionManager = new SubscriptionManager(this);
		mSubscriptionManager.start();
		

		// Use a memory queue instead of the disk queue we use during regular operation:
		// This constructor only has the name of the database file, not a user data directory.
		// Thus getUserDataDirectory() would fail, so constructing a disk queue would also fail.
		mIdentityFileQueue = new IdentityFileMemoryQueue();

		mXMLTransformer = new XMLTransformer(this);
		
		mIdentityFileProcessor
			= new IdentityFileProcessor(mIdentityFileQueue, null, mXMLTransformer);

		mFetcher = new IdentityFetcher(this, null, mIdentityFileQueue);
		
		// Identity files flow through the following pipe:
		//     mFetcher -> mIdentityFileQueue -> mIdentityFileProcessor
		// Thus, we start the pipe's daemons in reverse order to ensure that the receiving ones
		// are available before the ones which fill the pipe.
		mIdentityFileProcessor.start();
		/* mIdentityFileQueue.start(); */	// Not necessary, has no thread.
		mFetcher.start();


		mFCPInterface = new FCPInterface(this);
		
		setLanguage(LANGUAGE.getDefault()); // Even without UI, WOT will use l10n for Exceptions, so we need a language. Normally the node calls this for us.
	}
	
	File getUserDataDirectory() {
        final File wotDirectory = new File(mPR.getNode().getUserDir(), WebOfTrustInterface.WOT_NAME);
        
        if(!wotDirectory.exists() && !wotDirectory.mkdir())
        	throw new RuntimeException("Unable to create directory " + wotDirectory);
        
        return wotDirectory;
	}
	
	private com.db4o.config.Configuration getNewDatabaseConfiguration() {
		com.db4o.config.Configuration cfg = Db4o.newConfiguration();
		
		// Required config options:
		cfg.reflectWith(new JdkReflector(getPluginClassLoader()));
		cfg.activationDepth(Persistent.DEFAULT_ACTIVATION_DEPTH);
		cfg.updateDepth(1); // This must not be changed: We only activate(this, 1) before store(this).
		Logger.normal(this, "Default activation depth: " + cfg.activationDepth());
		cfg.exceptionsOnNotStorable(true);
        // The shutdown hook does auto-commit. We do NOT want auto-commit: if a transaction hasn't commit()ed, it's not safe to commit it.
        cfg.automaticShutDown(false);
        
        // Performance config options:
        cfg.callbacks(false); // We don't use callbacks yet. TODO: Investigate whether we might want to use them
        cfg.classActivationDepthConfigurable(false);
        
        // Registration of indices (also performance)
        
        // ATTENTION: Also update cloneDatabase() when adding new classes!
        @SuppressWarnings("unchecked")
		final Class<? extends Persistent>[] persistentClasses = new Class[] {
        	Configuration.class,
        	Identity.class,
        	OwnIdentity.class,
        	Trust.class,
        	Score.class,
        	IdentityFetcher.IdentityFetcherCommand.class,
        	IdentityFetcher.AbortFetchCommand.class,
        	IdentityFetcher.StartFetchCommand.class,
        	IdentityFetcher.UpdateEditionHintCommand.class,
        	SubscriptionManager.Client.class,
        	SubscriptionManager.Subscription.class,
        	SubscriptionManager.IdentitiesSubscription.class,
        	SubscriptionManager.ScoresSubscription.class,
        	SubscriptionManager.TrustsSubscription.class,
        	SubscriptionManager.Notification.class,
        	SubscriptionManager.ObjectChangedNotification.class,
        	SubscriptionManager.BeginSynchronizationNotification.class,
        	SubscriptionManager.EndSynchronizationNotification.class,
        	SubscriptionManager.IdentityChangedNotification.class,
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
        	
        	if(logDEBUG) Logger.debug(this, "Persistent class: " + clazz.getCanonicalName() + "; hasIndex==" + classHasIndex);
        	
        	// TODO: Make very sure that it has no negative side effects if we disable class indices for some classes
        	// Maybe benchmark in comparison to a database which has class indices enabled for ALL classes.
        	cfg.objectClass(clazz).indexed(classHasIndex);
   
        	// Check the class' fields for @IndexedField annotations
        	for(Field field : clazz.getDeclaredFields()) {
        		if(field.getAnnotation(Persistent.IndexedField.class) != null) {
        			if(logDEBUG) Logger.debug(this, "Registering indexed field " + clazz.getCanonicalName() + '.' + field.getName());
        			cfg.objectClass(clazz).objectField(field.getName()).indexed(true);
        		}
        	}
        	
    		// Check whether the class itself has an @IndexedField annotation
    		final Persistent.IndexedField annotation =  clazz.getAnnotation(Persistent.IndexedField.class);
    		if(annotation != null) {
        		for(String fieldName : annotation.names()) {
        			if(logDEBUG) Logger.debug(this, "Registering indexed field " + clazz.getCanonicalName() + '.' + fieldName);
        			cfg.objectClass(clazz).objectField(fieldName).indexed(true);
        		}
    		}
        }
        
        // TODO: We should check whether db4o inherits the indexed attribute to child classes, for example for this one:
        // Unforunately, db4o does not provide any way to query the indexed() property of fields, you can only set it
        // We might figure out whether inheritance works by writing a benchmark.
        
        return cfg;
	}
	
	private synchronized void restoreDatabaseBackup(File databaseFile, File backupFile) throws IOException {
		Logger.warning(this, "Trying to restore database backup: " + backupFile.getAbsolutePath());
		
		if(mDB != null)
			throw new RuntimeException("Database is opened already!");
		
		if(backupFile.exists()) {
			try {
				FileUtil.secureDelete(databaseFile);
			} catch(IOException e) {
				Logger.warning(this, "Deleting of the database failed: " + databaseFile.getAbsolutePath());
			}
			
			if(backupFile.renameTo(databaseFile)) {
				Logger.warning(this, "Backup restored!");
			} else {
				throw new IOException("Unable to rename backup file back to database file: " + databaseFile.getAbsolutePath());
			}

		} else {
			throw new IOException("Cannot restore backup, it does not exist!");
		}
	}

	private synchronized void defragmentDatabase(File databaseFile) throws IOException {
		Logger.normal(this, "Defragmenting database ...");
		
		if(mDB != null) 
			throw new RuntimeException("Database is opened already!");
		
		if(mPR == null) {
			Logger.normal(this, "No PluginRespirator found, probably running as unit test, not defragmenting.");
			return;
		}
		

		final File backupFile = new File(databaseFile.getAbsolutePath() + ".backup");
		
		if(backupFile.exists()) {
			if(!databaseFile.exists() || databaseFile.length() == 0) {
				Logger.warning(this, "Backup file exists while main database file does not or is empty, maybe the node was shot during defrag. Restoring backup...");
				restoreDatabaseBackup(databaseFile, backupFile);			
			} else {
				Logger.error(this, "Not defragmenting database: Backup AND main database exist, maybe the node was shot during defrag: " + backupFile.getAbsolutePath());
				return;
			}
		}	
		
		// Open it first, because defrag will throw if it needs to upgrade the file.
		{
			final ObjectContainer database = Db4o.openFile(getNewDatabaseConfiguration(), databaseFile.getAbsolutePath());
			
			// Db4o will throw during defragmentation if new fields were added to classes and we didn't initialize their values on existing
			// objects before defragmenting. So we just don't defragment if the database format version has changed.
			final boolean canDefragment = peekDatabaseFormatVersion(this, database.ext()) == WebOfTrust.DATABASE_FORMAT_VERSION;

			while(!database.close());
			
			if(!canDefragment) {
				Logger.normal(this, "Not defragmenting, database format version changed!");
				return;
			}
			
			if(!databaseFile.exists()) {
				Logger.error(this, "Database file does not exist after openFile: " + databaseFile.getAbsolutePath());
				return;
			}
		}

		final File tmpFile = new File(databaseFile.getAbsolutePath() + ".temp");
		FileUtil.secureDelete(tmpFile);

		/* As opposed to the default, BTreeIDMapping uses an on-disk file instead of in-memory for mapping IDs. 
		/* Reduces memory usage during defragmentation while being slower.
		/* However as of db4o 7.4.63.11890, it is bugged and prevents defragmentation from succeeding for my database, so we don't use it for now. */
		final DefragmentConfig config = new DefragmentConfig(databaseFile.getAbsolutePath(), 
																backupFile.getAbsolutePath()
															//	,new BTreeIDMapping(tmpFile.getAbsolutePath())
															);
		
		/* Delete classes which are not known to the classloader anymore - We do NOT do this because:
		/* - It is buggy and causes exceptions often as of db4o 7.4.63.11890
		/* - WOT has always had proper database upgrade code (function upgradeDB()) and does not rely on automatic schema evolution.
		/*   If we need to get rid of certain objects we should do it in the database upgrade code, */
		// config.storedClassFilter(new AvailableClassFilter());
		
		config.db4oConfig(getNewDatabaseConfiguration());
		
		try {
			Defragment.defrag(config);
		} catch (Exception e) {
			Logger.error(this, "Defragment failed", e);
			
			try {
				restoreDatabaseBackup(databaseFile, backupFile);
				return;
			} catch(IOException e2) {
				Logger.error(this, "Unable to restore backup", e2);
				throw new IOException(e);
			}
		}

		final long oldSize = backupFile.length();
		final long newSize = databaseFile.length();

		if(newSize <= 0) {
			Logger.error(this, "Defrag produced an empty file! Trying to restore old database file...");
			
			databaseFile.delete();
			try {
				restoreDatabaseBackup(databaseFile, backupFile);
			} catch(IOException e2) {
				Logger.error(this, "Unable to restore backup", e2);
				throw new IOException(e2);
			}
		} else {
			final double change = 100.0 * (((double)(oldSize - newSize)) / ((double)oldSize));
			FileUtil.secureDelete(tmpFile);
			FileUtil.secureDelete(backupFile);
			Logger.normal(this, "Defragment completed. "+SizeUtil.formatSize(oldSize)+" ("+oldSize+") -> "
					+SizeUtil.formatSize(newSize)+" ("+newSize+") ("+(int)change+"% shrink)");
		}

	}

	
	/**
	 * ATTENTION: This function is duplicated in the Freetalk plugin, please backport any changes.
	 * 
	 * Initializes the plugin's db4o database.
	 */
	private synchronized ExtObjectContainer openDatabase(File file) {
		Logger.normal(this, "Opening database using db4o " + Db4o.version());
		
		if(mDB != null) 
			throw new RuntimeException("Database is opened already!");
		
		try {
			defragmentDatabase(file);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

		return Db4o.openFile(getNewDatabaseConfiguration(), file.getAbsolutePath()).ext();
	}
	
	/**
	 * ATTENTION: Please ensure that no threads are using the IntroductionPuzzleStore / IdentityFetcher / SubscriptionManager while this is executing.
	 * It doesn't synchronize on the IntroductionPuzzleStore / IdentityFetcher / SubscriptionManager because it assumes that they are not being used yet.
	 * (I didn't upgrade this function to do the locking because it would be much work to test the changes for little benefit)
	 * 
	 * ATTENTION: After having written upgrade code, it is a good idea to set the log level to DEBUG (see developer-documentation/Debugging.txt)
	 * and check whether the startup database integrity test {@link #verifyDatabaseIntegrity()}y succeeds.
	 */
	private synchronized void upgradeDB() {
		int databaseFormatVersion = mConfig.getDatabaseFormatVersion();
		
		if(databaseFormatVersion == WebOfTrust.DATABASE_FORMAT_VERSION)
			return;
	
		Logger.normal(this, "Upgrading database format version " + databaseFormatVersion);
		
		//synchronized(this) { // Already done at function level
		synchronized(mPuzzleStore) { // For deleteWithoutCommit(Identity) / restoreOwnIdentityWithoutCommit()
		synchronized(mFetcher) { // For deleteWithoutCommit(Identity) / restoreOwnIdentityWithoutCommit()
		synchronized(mSubscriptionManager) { // For deleteWithoutCommit(Identity) / restoreOwnIdentityWithoutCommit()
		synchronized(Persistent.transactionLock(mDB)) {
			try {
                // upgradeDatabaseFormatVersion12345() must be called before the individual upgrade
                // functions. See its JavaDoc.
                // Notice: The below switch() might call it again. That won't break anything.
                if (databaseFormatVersion < 5)
                    upgradeDatabaseFormatVersion12345();

				switch(databaseFormatVersion) {
					case 1: upgradeDatabaseFormatVersion1(); mConfig.setDatabaseFormatVersion(++databaseFormatVersion);
					case 2: upgradeDatabaseFormatVersion2(); mConfig.setDatabaseFormatVersion(++databaseFormatVersion);
                    case 3:
                        // The following was done by upgradeDatabaseFormatVersion12345() already.
                        // It will not work if called a second time, so we don't call it here.
                        /* upgradeDatabaseFormatVersion3(); */
                        mConfig.setDatabaseFormatVersion(++databaseFormatVersion);
					case 4: upgradeDatabaseFormatVersion4(); mConfig.setDatabaseFormatVersion(++databaseFormatVersion);
                    case 5: upgradeDatabaseFormatVersion12345(); mConfig.setDatabaseFormatVersion(++databaseFormatVersion);
					case 6: break;
					default:
						throw new UnsupportedOperationException("Your database is newer than this WOT version! Please upgrade WOT.");
				}

				mConfig.storeAndCommit();
				Logger.normal(this, "Upgraded database to format version " + databaseFormatVersion);
			} catch(RuntimeException e) {
				Persistent.checkedRollbackAndThrow(mDB, this, e);
			}
		}
		}
		}
		}

		if(databaseFormatVersion != WebOfTrust.DATABASE_FORMAT_VERSION)
			throw new RuntimeException("Your database is too outdated to be upgraded automatically, please create a new one by deleting " 
					+ DATABASE_FILENAME + ". Contact the developers if you really need your old data.");
	}
	
	/**
	 * Upgrades database format version 1 to version 2
	 */
	@SuppressWarnings("deprecation")
	private void upgradeDatabaseFormatVersion1() {
		Logger.normal(this, "Generating Score IDs...");
		for(Score score : getAllScores()) {
			score.generateID();
			score.storeWithoutCommit();
		}
		
		Logger.normal(this, "Generating Trust IDs...");
		for(Trust trust : getAllTrusts()) {
			trust.generateID();
			trust.storeWithoutCommit();
		}
		
		Logger.normal(this, "Searching for identities with mixed up insert/request URIs...");
		for(Identity identity : getAllIdentities()) {
			try {
				USK.create(identity.getRequestURI());
			} catch (MalformedURLException e) {
				if(identity instanceof OwnIdentity) {
					Logger.error(this, "Insert URI specified as request URI for OwnIdentity, not correcting the URIs as the insert URI" +
							"might have been published by solving captchas - the identity could be compromised: " + identity);
				} else {
					Logger.error(this, "Insert URI specified as request URI for non-own Identity, deleting: " + identity);
					deleteWithoutCommit(identity);
				}								
			}
		}
	}

	/**
	 * Upgrades database format version 2 to version 3
	 * 
	 * Issue https://bugs.freenetproject.org/view.php?id=6085 caused creation of OwnIdentity objects which duplicate a non-own
	 * version of them. This was caused by restoreOwnIdentity() not detecting that there is a non-own version of the identity.
	 * So we delete the OwnIdentity and use the new restoreOwnIdentity() again.
	 * 
	 * ATTENTION: When changing this, make sure that the changes do not break {@link #upgradeDatabaseFormatVersion4()} - it uses this function internally.
	 */
	private void upgradeDatabaseFormatVersion2() {
		// The fix of restoreOwnIdentity() actually happened in Freenet itself: FreenetURI.deriveRequestURIFromInsertURI() was
		// fixed to work with certain SSKs. So we need to make sure that we are actually running on a build which has the fix.
		if(freenet.node.Version.buildNumber() < 1457)
			throw new RuntimeException("You need at least Freenet build 1457 to use this WOT version!");

		Logger.normal(this, "Searching for duplicate OwnIdentity objects...");
		for(final Identity identity : getAllNonOwnIdentities()) {
			final Query query = mDB.query();
			query.constrain(OwnIdentity.class);
			query.descend("mID").constrain(identity.getID());
			final ObjectSet<OwnIdentity> duplicates = new Persistent.InitializingObjectSet<OwnIdentity>(this, query);
			
			if(duplicates.size() == 0)
				continue;
			
			FreenetURI insertURI = null;
			try {
				// We need to prevent computeAllScoresWithoutCommit from happening in deleteWithoutCommit(Identity):
				// It might fail if duplicates exist and the user has enabled assertions.
				// beginTrustListImport() would delay it until we call finishTrustListImport() after we got rid of the
				// duplicates. But it also contains asserts which fail. So we emulate its behavior:
				assert(!mTrustListImportInProgress);
				mTrustListImportInProgress = true;
				
				for(final OwnIdentity duplicate : duplicates) {
					Logger.warning(this, "Found duplicate OwnIdentity during database upgrade, deleting it:" + duplicate);
					deleteWithoutCommit(duplicate);
					assert (insertURI == null || insertURI.equalsKeypair(duplicate.getInsertURI()));
					insertURI = duplicate.getInsertURI();
				}
				finishTrustListImport();
			} catch(RuntimeException e) {
				abortTrustListImport(e);
				throw e;
			}
		
			Logger.warning(this, "Restoring a single OwnIdentity for the deleted duplicates...");
			try {
				restoreOwnIdentityWithoutCommit(insertURI);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}		
	}
	
	/**
     * Upgrades database format version 3 to version 4.<br>
     * ATTENTION: Must not be called after upgradeDatabaseFormatVersion12345() as that function
     * changes the way we store FreenetURI. It also deals with calling this function here if
     * necessary. Thus, all you need to take care of is calling upgradeDatabaseFormatVersion12345().
     * <br><br>
	 * 
	 * This deletes leaked FreenetURI objects. Leaked means that they are not being referenced by any objects which we actually use.
	 * This was caused by a bug in class Identity, see https://bugs.freenetproject.org/view.php?id=5964
	 * 
	 * TODO FIXME: If future code adds member variables of type FreenetURI to {@link Persistent} classes, this function will null them
	 * out if the developer forgets to adapt it. See https://bugs.freenetproject.org/view.php?id=6129
	 * Therefore, support for upgrading version 3 databases should be removed after we gave users some time to upgrade all their
	 * old WOT-installations. It was implemented on 2013-11-07.
	 */
	@SuppressWarnings("unchecked")
	private void upgradeDatabaseFormatVersion3() {
		// We want to stick all non-leak FreenetURI in a set. Then we want to check for every FreenetURI in the database whether
		// it is contained in that set. If it is not, it is a leak.
		// But FreenetURI.equals() does not compare object identity, it only compares semantic identity.
		// So we cannot use HashSet, we must use IdentityHashMap since it compares object identity instead of equals().
		final IdentityHashMap<FreenetURI, Object> nonGarbageFreenetURIs = new IdentityHashMap<FreenetURI, Object>();
		
		// As of 2013-11-07, I've checked all Persistent classes for storage of FreenetURI. 
		// The only relevant ones are classes Identity and OwnIdentity. All other classes do not contain FreenetURI as stored member fields.
		
		for(final Identity identity : getAllIdentities()) {
            // Don't use identity.getRequestURI() but rather use the member variable directly:
            // getRequestURI() won't work before upgradeDatabaseFormatVersion12345() was called.
            // But that function needs to call this function here, so we cannot call it before.
            identity.checkedActivate(1);
            assert (identity.mRequestURI != null);
            identity.checkedActivate(identity.mRequestURI, 2);
            
            nonGarbageFreenetURIs.put(identity.mRequestURI, null);

            if(identity instanceof OwnIdentity) {
                final OwnIdentity ownIdentity = (OwnIdentity) identity;
                ownIdentity.checkedActivate(1);
                assert (ownIdentity.mInsertURI != null);
                ownIdentity.checkedActivate(ownIdentity.mInsertURI, 2);
                
                nonGarbageFreenetURIs.put(ownIdentity.mInsertURI, null);
            }
		}
		
		final Query query = mDB.query();
		query.constrain(FreenetURI.class);
		
		int leakCounter = 0;
		for(final FreenetURI uri : (ObjectSet<FreenetURI>)query.execute()) {
			if(!nonGarbageFreenetURIs.containsKey(uri)) {
                // A FreenetURI currently only contains db4o primitive types (String, arrays, etc.)
                // and thus we can delete it having to delete its member variables explicitly.
                mDB.delete(uri);
				++leakCounter;
			}
		}
		
		Logger.normal(this, "upgradeDatabaseFormatVersion3(): Deleted " + leakCounter + " leaked FreenetURI.");
	}
	
	/**
	 * Upgrades database format version 4 to version 5.
	 * 
	 * This deletes {@link Identity} objects which duplicate {@link OwnIdentity} objects.
	 * These could be caused to exist by a bug in {@link #createOwnIdentity(FreenetURI, String, boolean, String)}: It would not properly check whether
	 * a matching {@link Identity} object already exists when creating an {@link OwnIdentity} object from a certain {@link FreenetURI}.
	 * See <a href="https://bugs.freenetproject.org/view.php?id=6207">the relevant bugtracker entry</a>.
	 * 
	 * Internally, this just calls {@link #upgradeDatabaseFormatVersion2()}: That upgrade function served to fix the aftermath of a bug with the same symptoms,
	 * so we can just re-use it.
	 */
	private void upgradeDatabaseFormatVersion4() {
		upgradeDatabaseFormatVersion2();
	}

    /**
     * Upgrades necessary for all database format versions up to 5.<br>
     * ATTENTION: Must be called before any further database format upgrade code, see the second
     * to next section for an explanation.<br><br>
     * 
     * The {@link FreenetURI} functions for storing {@link FreenetURI} inside of db4o were removed
     * from fred recently. Thus, we must store {@link FreenetURI} as {@link String} instead.<br>
     * This function copies the {@link FreenetURI} members of stored {@link Identity} and
     * {@link OwnIdentity} objects to the new String equivalents.<br><br>
     * 
     * This function must be called before all other database upgrade code because the
     * {@link Identity} and {@link OwnIdentity} classes have been changed to only use the String
     * field of their URIs. Thus, if we did not convert the FreenetURI to String before calling
     * further database upgrade code, there would be {@link NullPointerException}s if the other
     * database upgrade code used Identity/OwnIdentity functions which try to access the String URI.
     * <br><br>
     * 
     * It is safe to call this function multiple times, as
     * {@link Identity#upgradeDatabaseFormatVersion12345WithoutCommit()} is idempotent.<br><br>
     * 
     * TODO: When removing this upgrade code path, remove the following deprecated code as well:<br>
     * - {@link Identity#mRequestURI}<br>
     * - {@link Identity#upgradeDatabaseFormatVersion12345WithoutCommit()}<br>
     * - {@link OwnIdentity#mInsertURI}<br>
     * - {@link OwnIdentity#upgradeDatabaseFormatVersion12345WithoutCommit()}
     */
    @SuppressWarnings("unchecked")
    private void upgradeDatabaseFormatVersion12345() {
        if(getConfig().getDatabaseFormatVersion() <= 3) {
            // upgradeDatabaseFormatVersion3() deletes leaked FreenetURI objects.
            // We must call it before this function converts FreenetURI to String, otherwise the
            // leak detection code will not work.
            upgradeDatabaseFormatVersion3();
        }
        
        Logger.normal(this, "Converting FreenetURI to String...");
        for(Identity identity : getAllIdentities()) {
            identity.upgradeDatabaseFormatVersion12345WithoutCommit();
        }
        
        if(logDEBUG) {
            // Since nothing should store FreenetURI inside the database anymore after the
            // format upgrade of this function, it is quite easy to test whether the function works:
            // Query the database for FreenetURI objects. We do this now...
            Logger.debug(this, "Checking database for leaked FreenetURI objects...");
            
            final Query query = mDB.query();
            query.constrain(FreenetURI.class);
            
            int leakCounter = 0;
            for(FreenetURI uri : (ObjectSet<FreenetURI>)query.execute()) {
                Logger.error(this, "Found leaked FreenetURI: " + uri);
                ++leakCounter;
                // Don't delete it: If this happens, there is a bug in this database format upgrade
                // function, and it should be fixed. Also, this code only gets executed if DEBUG
                // logging is enabled, so there wouldn't be any use in deleting it here.
            }
            
            Logger.debug(this, "Count of leaked FreenetURI: " + leakCounter);
        }
        Logger.normal(this, "Finished converting FreenetURI to String.");
    }

	/**
	 * DO NOT USE THIS FUNCTION ON A DATABASE WHICH YOU WANT TO CONTINUE TO USE!
	 * 
	 * Debug function for finding object leaks in the database.
	 * 
	 * - Deletes all identities in the database - This should delete ALL objects in the database.
	 * - Then it checks for whether any objects still exist - those are leaks.
	 */
	synchronized boolean checkForDatabaseLeaks() {
		Logger.normal(this, "checkForDatabaseLeaks(): Checking for database leaks... This will delete the whole database content!");
		
		Logger.normal(this, "checkForDatabaseLeaks(): Deleting all identities...");
		synchronized(mPuzzleStore) {
		synchronized(mFetcher) {
		synchronized(mSubscriptionManager) {
		synchronized(Persistent.transactionLock(mDB)) {
			try {
				beginTrustListImport();
				for(Identity identity : getAllIdentities()) {
					deleteWithoutCommit(identity);
				}
				finishTrustListImport();
				Persistent.checkedCommit(mDB, this);
			} catch(RuntimeException e) {
				abortTrustListImport(e);
				// abortTrustListImport() does rollback already
				// Persistent.checkedRollbackAndThrow(mDB, this, e);
				throw e;
			}
		}
		}
		}
		}
		
		Logger.normal(this, "checkForDatabaseLeaks(): Deleting all IdentityFetcher commands...");
		mFetcher.deleteAllCommands();
		
		Logger.normal(this, "checkForDatabaseLeaks(): Deleting all SubscriptionManager clients...");
		mSubscriptionManager.deleteAllClients();
		
		Logger.normal(this, "checkForDatabaseLeaks(): Deleting Configuration...");
		//synchronized(this) { // Done at function level
			try {
				getConfig().deleteWithoutCommit();
				Persistent.checkedCommit(mDB, this);
			} catch(RuntimeException e) {
				Persistent.checkedRollbackAndThrow(mDB, this, e);
			}
		//}
		
		Logger.normal(this, "checkForDatabaseLeaks(): Database should be empty now. Checking whether it really is...");
		Query query = mDB.query();
		query.constrain(Object.class);
		@SuppressWarnings("unchecked")
		ObjectSet<Object> result = query.execute();

		boolean foundLeak = false;
		
		for(Object leak : result) {
			// For each value of an enum, Db4o will store an undeletable object in the database permanently. there will only be exactly one for each
			// value, no matter how often the enum is referenced.
			// See: http://community.versant.com/documentation/reference/db4o-8.0/java/reference/Content/implementation_strategies/type_handling/static_fields_and_enums/java_enumerations.htm
			// (I've also manually tested this for db4o 7.4 which we currently use since the above link applies to 8.0 only and there is no such document for 7.4)
			if(leak.getClass().isEnum())
				Logger.normal(this, "checkForDatabaseLeaks(): Found leak candidate, it is an enum though, so its not a real leak. Class: " + leak.getClass() + "; toString(): " + leak);
			else {
				Logger.error(this, "checkForDatabaseLeaks(): Found leaked object, class: " + leak.getClass() + "; toString(): " + leak);
				foundLeak = true;
			}
		}
		
		Logger.warning(this, "checkForDatabaseLeaks(): Finished. Please delete the database now, it is destroyed.");
		
		return foundLeak;
	}
	
	private synchronized boolean verifyDatabaseIntegrity() {
		// Take locks of all objects which deal with persistent stuff because we act upon ALL persistent objects.
		synchronized(mPuzzleStore) {
		synchronized(mFetcher) {
		synchronized(mSubscriptionManager) {
			deleteDuplicateObjects();
			deleteOrphanObjects();
			
			Logger.debug(this, "Testing database integrity...");
			
			final Query q = mDB.query();
			q.constrain(Persistent.class);
			
			boolean result = true;
			
			for(final Persistent p : new Persistent.InitializingObjectSet<Persistent>(this, q)) {
				try {
					p.startupDatabaseIntegrityTest();
				} catch(Exception e) {
					result = false;
					
					try {
						Logger.error(this, "Integrity test failed for " + p, e);
					} catch(Exception e2) {
						Logger.error(this, "Integrity test failed for Persistent of class " + p.getClass(), e);
						Logger.error(this, "Exception thrown by toString() was:", e2);
					}
				}
			}
			
			Logger.debug(this, "Database integrity test finished.");
			
			return result;
		}
		}
		}
	}
	
	/**
	 * Does not do proper synchronization! Only use it in single-thread-mode during startup.
	 * 
	 * Does a backup of the database using db4o's backup mechanism.
	 * 
	 * This will NOT fix corrupted internal structures of databases - use cloneDatabase if you need to fix your database.
	 */
	private synchronized void backupDatabase(File newDatabase) {
		Logger.normal(this, "Backing up database to " + newDatabase.getAbsolutePath());
		
		if(newDatabase.exists())
			throw new RuntimeException("Target exists already: " + newDatabase.getAbsolutePath());
			
		WebOfTrust backup = null;
		
		boolean success = false;
		
		try {
			mDB.backup(newDatabase.getAbsolutePath());
			
			if(logDEBUG) {
				backup = new WebOfTrust(newDatabase.getAbsolutePath());

				// We do not throw to make the clone mechanism more robust in case it is being used for creating backups
				
				Logger.debug(this, "Checking database integrity of clone...");
				if(backup.verifyDatabaseIntegrity())
					Logger.debug(this, "Checking database integrity of clone finished.");
				else 
					Logger.error(this, "Database integrity check of clone failed!");

				Logger.debug(this, "Checking this.equals(clone)...");
				if(equals(backup))
					Logger.normal(this, "Clone is equal!");
				else
					Logger.error(this, "Clone is not equal!");
			}
			
			success = true;
		} finally {
			if(backup != null)
				backup.terminate();
			
			if(!success)
				newDatabase.delete();
		}
		
		Logger.normal(this, "Backing up database finished.");
	}
	
	/**
	 * Does not do proper synchronization! Only use it in single-thread-mode during startup.
	 * 
	 * Creates a clone of the source database by reading all objects of it into memory and then writing them out to the target database.
	 * Does NOT copy the Configuration, the IntroductionPuzzles or the IdentityFetcher command queue.
	 * 
	 * The difference to backupDatabase is that it does NOT use db4o's backup mechanism, instead it creates the whole database from scratch.
	 * This is useful because the backup mechanism of db4o does nothing but copying the raw file:
	 * It wouldn't fix databases which cannot be defragmented anymore due to internal corruption.
	 * - Databases which were cloned by this function CAN be defragmented even if the original database couldn't.
	 * 
	 * HOWEVER this function uses lots of memory as the whole database is copied into memory.
	 */
	private synchronized void cloneDatabase(File sourceDatabase, File targetDatabase) {
		Logger.normal(this, "Cloning " + sourceDatabase.getAbsolutePath() + " to " + targetDatabase.getAbsolutePath());
		
		if(targetDatabase.exists())
			throw new RuntimeException("Target exists already: " + targetDatabase.getAbsolutePath());
		
		WebOfTrust original = null;
		WebOfTrust clone = null;
		
		boolean success = false;
		
		try {
			original = new WebOfTrust(sourceDatabase.getAbsolutePath());
			
			// We need to copy all objects into memory and then close & unload the source database before writing the objects to the target one.
			// - I tried implementing this function in a way where it directly takes the objects from the source database and stores them
			// in the target database while the source is still open. This did not work: Identity objects disappeared magically, resulting
			// in Trust objects .storeWithoutCommit throwing "Mandatory object not found" on their associated identities.
			
			// FIXME: Clone the Configuration object
			
			final HashSet<Identity> allIdentities = new HashSet<Identity>(original.getAllIdentities());
			final HashSet<Trust> allTrusts = new HashSet<Trust>(original.getAllTrusts());
			final HashSet<Score> allScores = new HashSet<Score>(original.getAllScores());
			
			for(Identity identity : allIdentities) {
				identity.checkedActivate(16);
				identity.mWebOfTrust = null;
				identity.mDB = null;
			}
			
			for(Trust trust : allTrusts) {
				trust.checkedActivate(16);
				trust.mWebOfTrust = null;
				trust.mDB = null;
			}
			
			for(Score score : allScores) {
				score.checkedActivate(16);
				score.mWebOfTrust = null;
				score.mDB = null;
			}
			
			// We don't clone:
			// - Introduction puzzles because we can just download new ones
			// - IdentityFetcher commands because they aren't persistent across startups anyway
			// - Subscription and Notification objects because subscriptions are also not persistent across startups.
			
			original.terminate();
			original = null;
			System.gc();
			
			// Now we write out the in-memory copies ...
			
			clone = new WebOfTrust(targetDatabase.getAbsolutePath());
			
			for(Identity identity : allIdentities) {
				identity.initializeTransient(clone);
				identity.storeWithoutCommit();
			}
			Persistent.checkedCommit(clone.getDatabase(), clone);
			
			for(Trust trust : allTrusts) {
				trust.initializeTransient(clone);
				trust.storeWithoutCommit();
			}
			Persistent.checkedCommit(clone.getDatabase(), clone);
			
			for(Score score : allScores) {
				score.initializeTransient(clone);
				score.storeWithoutCommit();
			}
			Persistent.checkedCommit(clone.getDatabase(), clone);
			
			// And because cloning is a complex operation we do a mandatory database integrity check

			Logger.normal(this, "Checking database integrity of clone...");
			if(clone.verifyDatabaseIntegrity())
				Logger.normal(this, "Checking database integrity of clone finished.");
			else 
				throw new RuntimeException("Database integrity check of clone failed!");
			
			// ... and also test whether the Web Of Trust is equals() to the clone. This does a deep check of all identities, scores & trusts!

			original = new WebOfTrust(sourceDatabase.getAbsolutePath());
				
			Logger.normal(this, "Checking original.equals(clone)...");
			if(original.equals(clone))
				Logger.normal(this, "Clone is equal!");
			else
				throw new RuntimeException("Clone is not equal!");

			success = true;
		} finally {
			if(original != null)
				original.terminate();
			
			if(clone != null)
				clone.terminate();
			
			if(!success)
				targetDatabase.delete();
		}
		
		Logger.normal(this, "Cloning database finished.");
	}
	
	/**
	 * Recomputes the {@link Score} of all identities and checks whether the score which is stored in the database is correct.
	 * Incorrect scores are corrected & stored.
	 * 
	 * The function is synchronized and does a transaction, no outer synchronization is needed. 
	 */
	protected synchronized void verifyAndCorrectStoredScores() {
		Logger.normal(this, "Veriying all stored scores ...");
		synchronized(mFetcher) {
		synchronized(mSubscriptionManager) {
		synchronized(Persistent.transactionLock(mDB)) {
			try {
				computeAllScoresWithoutCommit();
				Persistent.checkedCommit(mDB, this);
			} catch(RuntimeException e) {
				Persistent.checkedRollbackAndThrow(mDB, this, e);
			}
		}
		}
		}
		Logger.normal(this, "Veriying all stored scores finished.");
	}
	
	/**
	 * Debug function for deleting duplicate identities etc. which might have been created due to bugs :)
	 */
	private synchronized void deleteDuplicateObjects() {
		synchronized(mPuzzleStore) { // Needed for deleteWithoutCommit(Identity)
		synchronized(mFetcher) { // Needed for deleteWithoutCommit(Identity)
		synchronized(mSubscriptionManager) { // Needed for deleteWithoutCommit(Identity)
		synchronized(Persistent.transactionLock(mDB)) {
		try {
			HashSet<String> deleted = new HashSet<String>();

			if(logDEBUG) Logger.debug(this, "Searching for duplicate identities ...");

			for(Identity identity : getAllIdentities()) {
				Query q = mDB.query();
				q.constrain(Identity.class);
				q.descend("mID").constrain(identity.getID());
				q.constrain(identity).identity().not();
				ObjectSet<Identity> duplicates = new Persistent.InitializingObjectSet<Identity>(this, q);
				for(Identity duplicate : duplicates) {
					if(deleted.contains(duplicate.getID()) == false) {
						Logger.error(duplicate, "Deleting duplicate identity " + duplicate.getRequestURI());
						deleteWithoutCommit(duplicate);
						Persistent.checkedCommit(mDB, this);
					}
				}
				deleted.add(identity.getID());
			}
			Persistent.checkedCommit(mDB, this);
			
			if(logDEBUG) Logger.debug(this, "Finished searching for duplicate identities.");
		}
		catch(RuntimeException e) {
			Persistent.checkedRollback(mDB, this, e);
		}
		} // synchronized(Persistent.transactionLock(mDB)) {
		} // synchronized(mSubscriptionManager) {
		} // synchronized(mFetcher) { 
		} // synchronized(mPuzzleStore) {

		// synchronized(this) { // For computeAllScoresWithoutCommit() / removeTrustWithoutCommit(). Done at function level already.
		synchronized(mFetcher) { // For computeAllScoresWithoutCommit() / removeTrustWithoutCommit()
		synchronized(mSubscriptionManager) { // For computeAllScoresWithoutCommit() / removeTrustWithoutCommit()
		synchronized(Persistent.transactionLock(mDB)) {
		try {
		if(logDEBUG) Logger.debug(this, "Searching for duplicate Trust objects ...");

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
		if(logDEBUG) Logger.debug(this, "Finished searching for duplicate trust objects.");
		}
		catch(RuntimeException e) {
			Persistent.checkedRollback(mDB, this, e);
		}
		} // synchronized(Persistent.transactionLock(mDB)) {
		} // synchronized(mSubscriptionManager) {
		} // synchronized(mFetcher) { 
		
		/* TODO: Also delete duplicate score */
	}
	
	/**
	 * Debug function for deleting trusts or scores of which one of the involved partners is missing.
	 */
	private synchronized void deleteOrphanObjects() {
		// synchronized(this) { // For computeAllScoresWithoutCommit(). Done at function level already.
		synchronized(mFetcher) { // For computeAllScoresWithoutCommit()
		synchronized(mSubscriptionManager) { // For computeAllScoresWithoutCommit()
		synchronized(Persistent.transactionLock(mDB)) {
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
					// No need to update subscriptions as the trust is broken anyway.
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
		}
		}

		// synchronized(this) { // For computeAllScoresWithoutCommit(). Done at function level already.
		synchronized(mFetcher) { // For computeAllScoresWithoutCommit()
		synchronized(mSubscriptionManager) { // For computeAllScoresWithoutCommit()
		synchronized(Persistent.transactionLock(mDB)) {
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
					// No need to update subscriptions as the score is broken anyway.
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
		}
	}
	
	/**
	 * Warning: This function is not synchronized, use it only in single threaded mode.
	 * @return The WOT database format version of the given database. -1 if there is no Configuration stored in it or multiple configurations exist.
	 */
	@SuppressWarnings("deprecation")
	private static int peekDatabaseFormatVersion(WebOfTrust wot, ExtObjectContainer database) {
		final Query query = database.query();
		query.constrain(Configuration.class);
		@SuppressWarnings("unchecked")
		ObjectSet<Configuration> result = (ObjectSet<Configuration>)query.execute(); 
		
		switch(result.size()) {
			case 1: {
				final Configuration config = (Configuration)result.next();
				config.initializeTransient(wot, database);
				// For the HashMaps to stay alive we need to activate to full depth.
				config.checkedActivate(4);
				return config.getDatabaseFormatVersion();
			}
			default:
				return -1;
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
	private int computeCapacity(OwnIdentity truster, Identity trustee, int rank) {
		if(truster == trustee)
			return 100;
		 
		try {
            // TODO: Performance: The comment "Security check, if rank computation breaks this will
            // hit." below sounds like we don't actually need to execute this because the callers
            // probably do it implicitly. Check if this is true and if yes, convert it to an assert.
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
	 * Synchronization:
	 * This function does neither lock the database nor commit the transaction. You have to surround it with
	 * <code>
	 * synchronized(WebOfTrust.this) {
	 * synchronized(mFetcher) {
	 * synchronized(mSubscriptionManager) {
	 * synchronized(Persistent.transactionLock(mDB)) {
	 *     try { ... computeAllScoresWithoutCommit(); Persistent.checkedCommit(mDB, this); }
	 *     catch(RuntimeException e) { Persistent.checkedRollbackAndThrow(mDB, this, e); }
	 * }}}}
	 * </code>
	 * 
	 * @return True if all stored scores were correct. False if there were any errors in stored scores.
	 */
	protected boolean computeAllScoresWithoutCommit() {
		if(logMINOR) Logger.minor(this, "Doing a full computation of all Scores...");
		
		final long beginTime = CurrentTimeUTC.getInMillis();
		
		boolean returnValue = true;
		final ObjectSet<Identity> allIdentities = getAllIdentities();
		
		// Scores are a rating of an identity from the view of an OwnIdentity so we compute them per OwnIdentity.
		for(OwnIdentity treeOwner : getAllOwnIdentities()) {
			// At the end of the loop body, this table will be filled with the ranks of all identities which are visible for treeOwner.
			// An identity is visible if there is a trust chain from the owner to it.
			// The rank is the distance in trust steps from the treeOwner.			
			// So the treeOwner is rank 0, the trustees of the treeOwner are rank 1 and so on.
			// (The initial size is specified as twice the possible maximal amount of entries to
			// ensure that the HashMap does not have to be grown.)
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
									final Trust treeOwnerTrust = getTrust(treeOwner, trustee);
									assert(treeOwnerTrust.getValue() <= 0)
										: "The treeOwner Trusts are processed before all other "
										+ "Trusts, and their rank value overwrites the ones of "
										+ "non-treeOwner Trusts. Thus, if there is a treeOwner "
										+ "Trust, it should have a value which could have caused "
										+ "the current rank of Integer.MAX_VALUE.";
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
				
				/* RankComputationTest does this as a unit test for us
				 * 
				assert(computeRankFromScratch(treeOwner, target)
					== (targetRank != null ? targetRank : -1));
				*/
				
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
					newScore = new Score(this, treeOwner, target, targetScore, targetRank, computeCapacity(treeOwner, target, targetRank));
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
						mSubscriptionManager.storeScoreChangedNotificationWithoutCommit(currentStoredScore, null);
						
					} else {
						if(!newScore.equals(currentStoredScore)) {
							returnValue = false;
							if(!mFullScoreComputationNeeded)
								Logger.error(this, "Correcting wrong score: Should have been " + newScore + " but was " + currentStoredScore, new RuntimeException());
							
							needToCheckFetchStatus = true;
							oldShouldFetch = shouldFetchIdentity(target);
							
							final Score oldScore = currentStoredScore.clone();
							
							currentStoredScore.setRank(newScore.getRank());
							currentStoredScore.setCapacity(newScore.getCapacity());
							currentStoredScore.setValue(newScore.getScore());

							currentStoredScore.storeWithoutCommit();
							mSubscriptionManager.storeScoreChangedNotificationWithoutCommit(oldScore, currentStoredScore);
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
						mSubscriptionManager.storeScoreChangedNotificationWithoutCommit(null, newScore);
					}
				}

				if(!needToCheckFetchStatus) {
					// The Score database was correct, and thus shouldFetchIdentity() cannot have
					// changed its value since no Score changed - which is why
					// needToCheckFetchStatus == false is false yet.
					// However, previously called alternate Score computation implementations could
					// have forgotten to tell IdentityFetcher the shouldFetchIdentity() value, so
					// for debugging purposes we now also check whether IdentityFetcher has the
					// correct state.
					
					final boolean realOldShouldFetch = mFetcher.getShouldFetchState(target.getID());
					final boolean newShouldFetch = shouldFetchIdentity(target);
					
					if(realOldShouldFetch != newShouldFetch) {
						needToCheckFetchStatus = true;
						returnValue = false;
						oldShouldFetch = realOldShouldFetch;
						
						// We purposely always log an error even if mFullScoreComputationNeeded is
						// false: needToCheckFetchStatus was false when we entered this branch
						// because the stored Scores were correct, so the Scores were already
						// correct before this function was called, and thus the code which
						// set mFullScoreComputationNeeded wasn't responsible for the wrong
						// shouldFetchState as it didn't create those Scores either.
						Logger.error(this, "Correcting wrong IdentityFetcher shouldFetch state: "
							+ "was: " + realOldShouldFetch + "; should be: " + newShouldFetch + "; "
							+ "identity: " + target, new Exception());
					}
					
					// ATTENTION if you want to implement an alternate Score computation algorithm:
					// What we just validated about the previous Score computation run is NOT the 
					// whole deal of verifying the IdentityFetcher state. What also would have to be
					// validated is: If the capacity of the identity was 0 before the previous run
					// and then changed to > 0 in the previous run, then the current edition of the
					// identity has to be marked as "not fetched". This is because identities with
					// capacity 0 are not allowed to introduce trustees, but identities with
					// capacity > 0 are. To get those trustees, we have to re-fetch the identity's
					// tust list.
					// We cannot check this here though: The information whether capacity changed
					// from 0 to > 0 in the previous Score computation run only available *during*
					// the previous run, not now.
					// We compensate for this by having a unit test for this situation:
					// WoTTest.testRefetchDueToCapacityChange()
					
					// TODO: Code quality: Instead of only checking the "should fetch?" state for
					// existing Identitys, also check for those which have been deleted: Obtain the
					// full list of URIs being fetched from the IdentityFetcher, and check for any
					// URIs which don't belong to an existing Identity which should be fetched.
					// However, these false positives are not security critical: When the
					// XMLTransformer imports fetched files, it will check whether an Identity
					// exists (and whether should be fetched).
				}
				
				if(needToCheckFetchStatus) {
					// If fetch status changed from false to true, we need to start fetching it
					// If the capacity changed from 0 to positive, we need to refetch the current edition: Identities with capacity 0 cannot
					// cause new identities to be imported from their trust list, capacity > 0 allows this.
					// If the fetch status changed from true to false, we need to stop fetching it
					if((!oldShouldFetch || (oldCapacity == 0 && newScore != null && newScore.getCapacity() > 0)) && shouldFetchIdentity(target) ) {
						returnValue = false;
						
						if(logMINOR) {
							if(!oldShouldFetch)
								Logger.minor(this, "Fetch status changed from false to true, refetching " + target);
							else
								Logger.minor(this, "Capacity changed from 0 to " + newScore.getCapacity() + ", refetching" + target);
						}

						final Identity oldTarget = target.clone();
						
						target.markForRefetch();
						target.storeWithoutCommit();
						
						// Clients shall determine shouldFetch from the scores of an identity on their own so there is no need to notify the client about that
						// - but we do tell the client the state of Identity.getCurrentEditionFetchState() which is changed by markForRefetch().
						// Therefore we me must store a notification nevertheless.
						if(!oldTarget.equals(target)) // markForRefetch() will not change anything if the current edition had not been fetched yet
							mSubscriptionManager.storeIdentityChangedNotificationWithoutCommit(oldTarget, target);

						mFetcher.storeStartFetchCommandWithoutCommit(target);
					}
					else if(oldShouldFetch && !shouldFetchIdentity(target)) {
						returnValue = false;
						
						if(logMINOR) Logger.minor(this, "Fetch status changed from true to false, aborting fetch of " + target);

						mFetcher.storeAbortFetchCommandWithoutCommit(target);
					}
				}
			}
		}
		
		mFullScoreComputationNeeded = false;
		
		++mFullScoreRecomputationCount;
		mFullScoreRecomputationMilliseconds += CurrentTimeUTC.getInMillis() - beginTime;
		
		if(logMINOR) {
			Logger.minor(this, "Full score computation finished. Amount: " + mFullScoreRecomputationCount + "; Avg Time:" + getAverageFullScoreRecomputationTime() + "s");
		}
		
		return returnValue;
	}
	
	private synchronized void createSeedIdentities() {
		synchronized(mSubscriptionManager) {
		for(String seedURI : WebOfTrustInterface.SEED_IDENTITIES) {
			synchronized(Persistent.transactionLock(mDB)) {
			try { 
				final Identity existingSeed = getIdentityByURI(seedURI);
				final Identity oldExistingSeed = existingSeed.clone(); // For the SubscriptionManager
				
				if(existingSeed instanceof OwnIdentity) {
					try {
						setPublishIntroductionPuzzles(existingSeed.getID(), true, IntroductionServer.SEED_IDENTITY_PUZZLE_COUNT);
					} catch(UnknownIdentityException e) {
						throw new RuntimeException(e); // Should never happen since we are synchronized on the WOT
					}
				} else {
					try {
						existingSeed.setEdition(new FreenetURI(seedURI).getEdition());
						mSubscriptionManager.storeIdentityChangedNotificationWithoutCommit(oldExistingSeed, existingSeed);
						existingSeed.storeAndCommit();
					} catch(InvalidParameterException e) {
						/* We already have the latest edition stored */
					}
				}
			}
			catch (UnknownIdentityException uie) {
				try {
					final Identity newSeed = new Identity(this, seedURI, null, true);
					// We have to explicitly set the edition number because the constructor only considers the given edition as a hint.
					newSeed.setEdition(new FreenetURI(seedURI).getEdition());
					newSeed.storeWithoutCommit();
					Logger.normal(this, "Created seed identity: " + newSeed);
					mSubscriptionManager.storeIdentityChangedNotificationWithoutCommit(null, newSeed);
					Persistent.checkedCommit(mDB, this);
				} catch (Exception e) {
					Persistent.checkedRollback(mDB, this, e);					
				}
			}
			catch (Exception e) {
				Persistent.checkedRollback(mDB, this, e);
			}
			}
		}
		}
	}

	/**
	 * ATTENTION: If you add new code which terminates threads, you must make sure that they are
	 * terminated in {@link AbstractFullNodeTest#setUpNode()} as well.
	 */
	@Override
	public void terminate() {
		Logger.normal(this, "Web Of Trust plugin terminating ...");
		
        // TODO: Code quality: The way this parallelizes shutdown of subsystems is ugly:
        // BackgroundJob, which many of the subsystems use, already has both async terminate() and
        // synchronous waitForTermination() which could be used to parallelize shutdown.
        // Nevertheless, this function creates threads for terminating those subsystems because
        // they don't expose the async shutdown functions, they only expose blocking ones.
        // So please someday change the subsystems to expose async shutdown functions and remove
        // the thread creation from this function.


        // When the counter of the following CountDownLatch is zero, all threads which terminate
        // a WOT subsystem have finished, and shutdown of them is thus complete.
        // We are not creating the latch object yet but initialize it to null because we do not
        // want to hardcode its counter value (= the amount of shutdown threads) but rather compute
        // it automatically once we created all threads. This is good because if the counter had to
        // be manually specified, developers could forget incrementing it when adding new threads.
        // Because we need to change the value of the latch from null when we create it, we need
        // to use an AtomicReference as a wrapper: Java forbids local classes from accessing
        // non-final variables of their containing function.
        final AtomicReference<CountDownLatch> latch = new AtomicReference<CountDownLatch>(null);
        
        // Because the current implementations of the submodule shutdown functions are blocking, we
        // call them in threads of this class:
        abstract class ShutdownThread implements Runnable {
            @Override public void run() {
                try {
                    realRun();
                } catch(Throwable e) {
                    // FIXME: Code quality: This used to be "catch(RuntimeException | Error e)" but
                    // was changed to catch(Throwable) because we need to be Java 6 compatible until
                    // the next build. Change it back to the Java7-style catch(). 

                    Logger.error(this, "Error during termination.", e);
                } finally {
                    latch.get().countDown();
                }
            };
            
            abstract void realRun();
        }
		
		final ArrayList<ShutdownThread> shutdownThreads = new ArrayList<ShutdownThread>(8); 
		
		shutdownThreads.add(new ShutdownThread() { @Override public void realRun() {
			if(mFCPInterface != null)
				mFCPInterface.stop();
		}});
		
		shutdownThreads.add(new ShutdownThread() { @Override public void realRun() {
			if(mWebInterface != null)
				mWebInterface.unload();
		}});
		
		shutdownThreads.add(new ShutdownThread() { @Override public void realRun() {
			if(mIntroductionClient != null)
				mIntroductionClient.terminate();
		}});
		
		shutdownThreads.add(new ShutdownThread() { @Override public void realRun() {
			if(mIntroductionServer != null)
				mIntroductionServer.terminate();
		}});
		
		shutdownThreads.add(new ShutdownThread() { @Override public void realRun() {
			if(mInserter != null)
				mInserter.terminate();
		}});
		
		shutdownThreads.add(new ShutdownThread() { @Override public void realRun() {
			if(mFetcher != null)
				mFetcher.stop();
		}});
		
		shutdownThreads.add(new ShutdownThread() { @Override public void realRun() {
			if(mIdentityFileProcessor != null) {
				// TODO: Code quality: Make all subsystems support non-blocking terminate() and
				// waitForTermination(). Then the ShutdownThread/CountDownLatch mechanism can be
				// replaced with two simple loops: One which calls terminate() on all subsystems,
				// and one which does the same with waitForTermination().
				// NOTICE: This is the same as the TODO at the beginning of the function:
				// "TODO: Code quality: The way this parallelizes shutdown of subsystems is ugly:"
				mIdentityFileProcessor.terminate();
				try {
					mIdentityFileProcessor.waitForTermination(Long.MAX_VALUE);
				} catch (InterruptedException e) {
					Logger.error(this, "ShutdownThread should not be interrupted!", e);
				}
			}
		}});

		shutdownThreads.add(new ShutdownThread() { @Override public void realRun() {
			if(mSubscriptionManager != null)
				mSubscriptionManager.stop();
		}});

        latch.set(new CountDownLatch(shutdownThreads.size()));

        Executor executor = (mPR != null /* Can be null in unit tests */)
                          ? mPR.getNode().executor
                          : new PooledExecutor();
        
        for(ShutdownThread thread : shutdownThreads)
            executor.execute(thread);

        try {
            latch.get().await();
        } catch(InterruptedException e1) {
            // We ARE a shutdown function, it doesn't make any sense to request us to shutdown.
            Logger.error(this, "Termination function requested to terminate!", e1);
        }
		
		// Must be terminated after anything is down which can modify the database
		try {
			if(mDebugFCPClient != null) {
			    // We now make sure that pending SubscriptionManager Notifications are deployed by
			    // executing SubscriptionManager.run() before shutting down the DebugFCPClient:
			    // The job of the DebugFCPClient is to compare the received notifications against
			    // the main database to check whether the received dataset is complete and correct.
			    // It needs all pending notifications for the data to match.
			    // We first log that we are calling SubscriptionManager.run() for DebugFCPClient  
			    // so that people don't think that SubscriptionManager.stop() is broken:
			    // It would itself both log that stop() has executed already, and that run() is
			    // executing after it, which would be confusing.
			    Logger.debug(mSubscriptionManager, "run(): Executing for DebugFCPClient...");
				mSubscriptionManager.run();
				
				mDebugFCPClient.stop();
			}
		} catch(Exception e) {
			Logger.error(this, "Error during termination.", e);
		}
		
		
		try {
			if(mDB != null) {
				/* TODO: At 2009-06-15, it does not seem possible to ask db4o for whether a transaction is pending.
				 * If it becomes possible some day, we should check that here, and log an error if there is an uncommitted transaction. 
				 * - All transactions should be committed after obtaining the lock() on the database. */
				synchronized(Persistent.transactionLock(mDB)) {
					System.gc();
					mDB.rollback();
					System.gc(); 
					mDB.close();
				}
			}
		}
		catch(Exception e) {
			Logger.error(this, "Error during termination.", e);
		}

		Logger.normal(this, "Web Of Trust plugin terminated.");
	}

    /**
     * Handles FCP messages.<br>
     * Actually implemented at class {@link FCPInterface} at
     * {@link FCPInterface#handlePluginFCPMessage(FCPPluginConnection, FCPPluginMessage)}.
     */
    @Override
    public FCPPluginMessage handlePluginFCPMessage(FCPPluginConnection connection,
            FCPPluginMessage message) {
        return mFCPInterface.handlePluginFCPMessage(connection, message);
    }
    
    /**
     * Backwards-compatibility handler for legacy fred plugin FCP API {@link FredPluginFCP}.<br>
     * 
     * @deprecated
     *     The old plugin FCP API {@link FredPluginFCP} is planned to be removed from fred, so this
     *     function shall be removed then as well.
     */
    @Deprecated
    @Override public void handle(PluginReplySender replysender, SimpleFieldSet params, Bucket data,
            int accesstype) {
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
		return getIdentityByID(IdentityID.constructAndValidateFromURI(uri).toString());
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
		return getOwnIdentityByID(IdentityID.constructAndValidateFromURI(uri).toString());
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
	@Override
	public ObjectSet<Identity> getAllIdentities() {
		final Query query = mDB.query();
		query.constrain(Identity.class);
		return new Persistent.InitializingObjectSet<Identity>(this, query);
	}
	
	public static enum SortOrder {
	    ByEditionAscending,
	    ByEditionDescending,
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
            case ByEditionAscending:
                q.constrain(Identity.class);
                // TODO: Performance: Add a member variable "mEdition" to Identity and use a native
                // db4o sorting query upon that. Currently, class Identity stores the edition inside
                // the String which holds its FreenetURI. The base URI is different for each
                // identity so we cannot just sort upon the URI string.
                // Also do the same for the case ByEditionDescending below.
                // NOTICE: If implement this TODO, you will have to write database upgrade code to
                // populate the new member variable in old databases. This requires quite a bit of
                // work and good testing. Thus, I would suggest you implement it together with
                // all possible further sorting functions which also need new fields. You should 
                // check the web interface's KnownIdentitiesPage for values which need sorting.
                // There is also a bugtracker entry for adding more sorting:
                // https://bugs.freenetproject.org/view.php?id=6501
                q.sortBy(new Comparator<Identity>() {
                    @Override public int compare(Identity i1, Identity i2) {
                        return Long.compare(i1.getEdition(), i2.getEdition());
                    }
                });
                break;
            case ByEditionDescending:
                q.constrain(Identity.class);
                q.sortBy(new Comparator<Identity>() {
                    @Override public int compare(Identity i1, Identity i2) {
                        return -Long.compare(i1.getEdition(), i2.getEdition());
                    }
                });
                break;
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
	 * DO NOT USE THIS FUNCTION FOR DELETING OWN IDENTITIES UPON USER REQUEST!
	 * IN FACT BE VERY CAREFUL WHEN USING IT FOR ANYTHING FOR THE FOLLOWING REASONS:
	 * - This function deletes ALL given and received trust values of the given identity. This modifies the trust list of the trusters against their will.
	 * - Especially it might be an information leak if the trust values of other OwnIdentities are deleted!
	 * - If WOT one day is designed to be used by many different users at once, the deletion of other OwnIdentity's trust values would even be corruption.
	 * 
	 * The intended purpose of this function is:
	 * - To specify which objects have to be dealt with when messing with storage of an identity.
	 * - To be able to do database object leakage tests: Many classes have a deleteWithoutCommit function and there are valid usecases for them.
	 *   However, the implementations of those functions might cause leaks by forgetting to delete certain object members.
	 *   If you call this function for ALL identities in a database, EVERYTHING should be deleted and the database SHOULD be empty.
	 *   You then can check whether the database actually IS empty to test for leakage.
	 * 
	 * You have to lock the WebOfTrust, the IntroductionPuzzleStore, the IdentityFetcher, the
	 * SubscriptionManager and the Persistent.transactionLock() before calling this function.
	 */
	void deleteWithoutCommit(Identity identity) {
		// We want to use beginTrustListImport, finishTrustListImport / abortTrustListImport.
		// If the caller already handles that for us though, we should not call those function again.
		// So we check whether the caller already started an import.
		boolean trustListImportWasInProgress = mTrustListImportInProgress;
		
		try {
			if(!trustListImportWasInProgress)
				beginTrustListImport();
			
			if(logDEBUG) Logger.debug(this, "Deleting identity " + identity + " ...");
			
			if(logDEBUG) Logger.debug(this, "Deleting received scores...");
			for(Score score : getScores(identity)) {
				score.deleteWithoutCommit();
				mSubscriptionManager.storeScoreChangedNotificationWithoutCommit(score, null);
			}

			if(identity instanceof OwnIdentity) {
				if(logDEBUG) Logger.debug(this, "Deleting given scores...");

				for(Score score : getGivenScores((OwnIdentity)identity)) {
					score.deleteWithoutCommit();
					mSubscriptionManager.storeScoreChangedNotificationWithoutCommit(score, null);
				}
			}

			if(logDEBUG) Logger.debug(this, "Deleting received trusts...");
			for(Trust trust : getReceivedTrusts(identity)) {
				trust.deleteWithoutCommit();
				mSubscriptionManager.storeTrustChangedNotificationWithoutCommit(trust, null);
			}

			if(logDEBUG) Logger.debug(this, "Deleting given trusts...");
			for(Trust givenTrust : getGivenTrusts(identity)) {
				givenTrust.deleteWithoutCommit();
				mSubscriptionManager.storeTrustChangedNotificationWithoutCommit(givenTrust, null);
				// We call computeAllScores anyway so we do not use removeTrustWithoutCommit()
			}
			
			mFullScoreComputationNeeded = true; // finishTrustListImport will call computeAllScoresWithoutCommit for us.

			if(logDEBUG) Logger.debug(this, "Deleting associated introduction puzzles ...");
			mPuzzleStore.onIdentityDeletion(identity);
			
			if(logDEBUG) Logger.debug(this, "Storing an abort-fetch-command...");
			
			if(mFetcher != null) { // Can be null if we use this function in upgradeDB()
				mFetcher.storeAbortFetchCommandWithoutCommit(identity);
				// NOTICE:
				// If the fetcher did store a db4o object reference to the identity, we would have to trigger command processing
				// now to prevent leakage of the identity object.
				// But the fetcher does NOT store a db4o object reference to the given identity. It stores its ID as String only.
				// Therefore, it is OK that the fetcher does not immediately process the commands now.
			}
		
			if(logDEBUG) Logger.debug(this, "Deleting the identity...");
			identity.deleteWithoutCommit();

			mSubscriptionManager.storeIdentityChangedNotificationWithoutCommit(identity, null);
			
			if(!trustListImportWasInProgress)
				finishTrustListImport();
		}
		catch(RuntimeException e) {
			if(!trustListImportWasInProgress)
				abortTrustListImport(e);
			
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
		query.descend("mID").constrain(new ScoreID(truster, trustee).toString());
		final ObjectSet<Score> result = new Persistent.InitializingObjectSet<Score>(this, query);
		
		switch(result.size()) {
			case 1: 
				final Score score = result.next();
				assert(score.getTruster() == truster);
				assert(score.getTrustee() == trustee);
				return score;
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
	public synchronized int getBestCapacity(final Identity identity) throws NotInTrustTreeException {
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
	@Override
	public ObjectSet<Score> getAllScores() {
		final Query query = mDB.query();
		query.constrain(Score.class);
		return new Persistent.InitializingObjectSet<Score>(this, query);
	}
	
	/**
	 * Checks whether the given identity should be downloaded. 
	 * 
	 * Synchronization: You must synchronize on this WebOfTrust when using this function.
	 * 
	 * TODO: Performance: Various callers could be optimized by storing the value of this for all
	 * identities in a database table.
	 * 
	 * @return Returns true if the identity has any capacity > 0, any score >= 0 or if it is an own identity.
	 */
    boolean shouldFetchIdentity(final Identity identity) {
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
			
			// Notice: Identitys with negative score are considered as distrusted, so one might
			// wonder why we hereby download identities even if their Score is negative just because
			// their capacity is > 0.
			// This is to ensure that the fetching algorithm allows the score computation algorithm
			// to be "stable": It should yield the same resulting scores independent of the order in
			// which identities are downloaded.
			// If an identity has a capacity of > 0, it is eligible to vote, and thus might cause
			// the negative score it has to disappear if we do still download its trust lists
			// *after* the Score is already negative (= changed order of downloading).
			// This isn't self-voting, it is rather caused by the fact that downloading its votes
			// could cause many identities to appear which have a much higher capacity than the
			// current distrusters. Those new identities will cause the current distrusters to be
			// distrusted; and thus make the currently negative score positive. In other words the
			// rank graph could be structured completely differently, where the current distrusted
			// identity has a much lower rank than the current distrusters, and thus its trustees
			// have higher voting powers than the current distrusters.
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
	public Trust getTrust(final Identity truster, final Identity trustee) throws NotTrustedException, DuplicateTrustException {
		return getTrust(new TrustID(truster, trustee).toString());
	}

    /**
     * @see #getTrust(Identity, Identity)
     * @param trusterID A valid {@link IdentityID}.
     * @param trusteeID A valid {@link IdentityID}. 
     */
    public Trust getTrust(final String trusterID, final String trusteeID)
            throws DuplicateTrustException, NotTrustedException {
        
        return getTrust(new TrustID(trusterID, trusteeID).toString());
    }

	/**
	 * Gets the {@link Trust} with the given {@link TrustID}. 
	 * 
	 * @see #getTrust(Identity, Identity)
	 */
	public synchronized Trust getTrust(final String trustID) throws NotTrustedException, DuplicateTrustException {
		final Query query = mDB.query();
		query.constrain(Trust.class);
		query.descend("mID").constrain(trustID);
		final ObjectSet<Trust> result = new Persistent.InitializingObjectSet<Trust>(this, query);
		
		switch(result.size()) {
			case 1: 
				final Trust trust = result.next();
				assert(trustID.equals(new TrustID(trust.getTruster(), trust.getTrustee()).toString()));
				return trust;
			case 0: throw new NotTrustedException(trustID);
			default: throw new DuplicateTrustException(trustID, result.size());
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
	 * Gets all trusts given by the given truster.
	 * The result is sorted descending by the time we last fetched the trusted identity. 
	 * You have to synchronize on this WoT when calling the function and processing the returned list!
	 * 
	 * @return An {@link ObjectSet} containing all {@link Trust} the passed Identity has given.
	 */
	public ObjectSet<Trust> getGivenTrustsSortedDescendingByLastSeen(final Identity truster) {
		final Query query = mDB.query();
		query.constrain(Trust.class);
		query.descend("mTruster").constrain(truster).identity();
		query.descend("mTrustee").descend("mLastFetchedDate").orderDescending();
		
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
	@Override
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
	 * synchronized(WebOfTrust.this) {
	 * synchronized(mFetcher) {
	 * synchronized(mSubscriptionManager) {
	 * synchronized(Persistent.transactionLock(mDB)) {
	 *     try { ... setTrustWithoutCommit(...); Persistent.checkedCommit(mDB, this); }
	 *     catch(RuntimeException e) { Persistent.checkedRollbackAndThrow(mDB, this, e); }
	 * }}}}
	 * 
	 * @param truster The Identity that gives the trust
	 * @param trustee The Identity that receives the trust
	 * @param newValue Numeric value of the trust
	 * @param newComment A comment to explain the given value
	 * @throws InvalidParameterException if a given parameter isn't valid, see {@link Trust} for details on accepted values.
	 */
	protected void setTrustWithoutCommit(Identity truster, Identity trustee, byte newValue, String newComment)
		throws InvalidParameterException {
		
		try { // Check if we are updating an existing trust value
			final Trust trust = getTrust(truster, trustee);
			final Trust oldTrust = trust.clone();
			trust.trusterEditionUpdated();
			trust.setComment(newComment);
			final boolean valueChanged = trust.getValue() != newValue; 
			
			if(valueChanged)
				trust.setValue(newValue);
			
			trust.storeWithoutCommit();
			
			if(!trust.equals(oldTrust))
				mSubscriptionManager.storeTrustChangedNotificationWithoutCommit(oldTrust, trust);
			
			if(valueChanged) {
				if(logDEBUG) Logger.debug(this, "Updated trust value ("+ trust +"), now updating Score.");
				updateScoresWithoutCommit(oldTrust, trust);
			}
		} catch (NotTrustedException e) {
			final Trust trust = new Trust(this, truster, trustee, newValue, newComment);
			trust.storeWithoutCommit();
			mSubscriptionManager.storeTrustChangedNotificationWithoutCommit(null, trust);
			if(logDEBUG) Logger.debug(this, "New trust value ("+ trust +"), now updating Score.");
			updateScoresWithoutCommit(null, trust);
		} 

		truster.updated();
		truster.storeWithoutCommit();
		
		// TODO: Mabye notify clients about this. IMHO it would create too much notifications on trust list import so we don't.
		// As soon as we have notification-coalescing we might do it.
		// mSubscriptionManager.storeIdentityChangedNotificationWithoutCommit(truster);
	}
	
	/**
	 * Only for being used by WoT internally and by unit tests!
	 * 
	 * You have to synchronize on this WebOfTrust while querying the parameter identities and calling this function.
	 */
	void setTrust(Identity truster, Identity trustee, byte newValue, String newComment)
		throws InvalidParameterException {
		
		synchronized(mFetcher) {
		synchronized(mSubscriptionManager) {
		synchronized(Persistent.transactionLock(mDB)) {
			try {
				setTrustWithoutCommit(truster, trustee, newValue, newComment);
				Persistent.checkedCommit(mDB, this);
			}
			catch(RuntimeException e) {
				Persistent.checkedRollbackAndThrow(mDB, this, e);
			}
		}
		}
		}
	}
	
	/**
	 * Deletes a trust object.
	 * 
	 * This function does neither lock the database nor commit the transaction. You have to surround it with
	 * synchronized(this) {
	 * synchronized(mFetcher) {
	 * synchronized(mSubscriptionManager) {
	 * synchronized(Persistent.transactionLock(mDB)) {
	 *     try { ... removeTrustWithoutCommit(...); Persistent.checkedCommit(mDB, this); }
	 *     catch(RuntimeException e) { Persistent.checkedRollbackAndThrow(mDB, this, e); }
	 * }}}}
	 * 
	 * @param truster
	 * @param trustee
	 */
	protected void removeTrustWithoutCommit(OwnIdentity truster, Identity trustee) {
		try {
			try {
				removeTrustWithoutCommit(getTrust(truster, trustee));
			} catch (NotTrustedException e) {
				Logger.error(this, "Cannot remove trust - there is none - from " + truster.getNickname() + " to " + trustee.getNickname());
			} 
		}
		catch(RuntimeException e) {
			Persistent.checkedRollbackAndThrow(mDB, this, e);
		}
	}
	
	/**
	 * 
	 * This function does neither lock the database nor commit the transaction. You have to surround it with
	 * synchronized(this) {
	 * synchronized(mFetcher) {
	 * synchronized(mSubscriptionManager) {
	 * synchronized(Persistent.transactionLock(mDB)) {
	 *     try { ... setTrustWithoutCommit(...); Persistent.checkedCommit(mDB, this); }
	 *     catch(RuntimeException e) { Persistent.checkedRollbackAndThrow(mDB, this, e); }
	 * }}}}
	 * 
	 */
	protected void removeTrustWithoutCommit(Trust trust) {
		trust.deleteWithoutCommit();
		mSubscriptionManager.storeTrustChangedNotificationWithoutCommit(trust, null);
		updateScoresWithoutCommit(trust, null);
	}

	/**
	 * Initializes this OwnIdentity's trust tree without commiting the transaction. 
	 * Meaning : It creates a Score object for this OwnIdentity in its own trust so it can give trust to other Identities. 
	 * 
	 * The score will have a rank of 0, a capacity of 100 (= 100 percent) and a score value of Integer.MAX_VALUE.
	 * 
	 * This function does neither lock the database nor commit the transaction. You have to surround it with
	 * synchronized(WebOfTrust.this) {
	 * synchronized(Persistent.transactionLock(mDB)) {
	 *     try { ... initTrustTreeWithoutCommit(...); Persistent.checkedCommit(mDB, this); }
	 *     catch(RuntimeException e) { Persistent.checkedRollbackAndThrow(mDB, this, e); }
	 * }}
	 *  
	 * @throws DuplicateScoreException if there already is more than one Score for this identity (should never happen)
	 */
	private void initTrustTreeWithoutCommit(OwnIdentity identity) throws DuplicateScoreException {
		try {
			getScore(identity, identity);
			Logger.error(this, "initTrustTreeWithoutCommit called even though there is already one for " + identity);
			return;
		} catch (NotInTrustTreeException e) {
			final Score score = new Score(this, identity, identity, Integer.MAX_VALUE, 0, 100);
			score.storeWithoutCommit();
			mSubscriptionManager.storeScoreChangedNotificationWithoutCommit(null, score);
		}
	}

	/**
	 * Computes the trustee's Score value according to the trusts it has received and the capacity of its trusters in the specified
	 * trust tree.
	 * 
	 * Synchronization:
	 * You have to synchronize on this WebOfTrust object when using this function.
	 * 
	 * @param truster The OwnIdentity that owns the trust tree
	 * @param trustee The identity for which the score shall be computed.
	 * @return The new Score of the identity. Integer.MAX_VALUE if the trustee is equal to the truster.
	 * @throws DuplicateScoreException if there already exist more than one {@link Score} objects for the trustee (should never happen)
	 */
	private int computeScoreValue(OwnIdentity truster, Identity trustee) throws DuplicateScoreException {
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
	 * Based on "uniform-cost search" algorithm (= optimized Dijkstra).<br>
	 * Modified with respect to ignoring "blocked" edges: Having received a rank of
	 * {@link Integer#MAX_VALUE} disallows an Identity to hand down a rank to its trustees. */
	int computeRankFromScratch_Forward(final OwnIdentity source, final Identity target) {
		final class Vertex implements Comparable<Vertex>{
			final Identity identity;
			final Integer rank;
			
			public Vertex(Identity identity, int rank) {
				this.identity = identity;
				this.rank = rank;
			}

			@Override public int compareTo(Vertex o) {
				return rank.compareTo(o.rank);
			}
		}
		
		PriorityQueue<Vertex> queue = new PriorityQueue<Vertex>();
		HashSet<String> seen = new HashSet<String>(); // Key = Identity.getID()
		
		final int sourceRank;
		try {
			sourceRank = getScore(source, source).getRank();
			if(source == target)
				return sourceRank;
		} catch (NotInTrustTreeException e) {
			Logger.warning(this, "initTrustTreeWithoutCommit() not called for: " + source);
			// Some unit tests require the special case of initTrustTreeWithoutCommit() not having
			// been called for an OwnIdentity yet to yield a proper result of "no rank".
			return -1;
		}
		
		try {
			// If a direct distrust exists from the OwnIdentity source to the target, then it
			// must always overwrite the rank to be MAX_VALUE, even if a path with a lower rank
			// would exist over more Trust steps. This is a demand of the specification of the
			// WOT algorithm, see computeAllScoresWithoutCommit().
			// Thus, we must now check whether a direct Trust exists before running the actual
			// search algorithm could return a lower rank than MAX_VALUE.
			if(getTrust(source, target).getValue() <= 0)
				return Integer.MAX_VALUE;
			
			// We know a direct non-distrust from source to target exists, so we can directly
			// compute the rank as sourceRank + 1.
			// Notice that this is an optimization: Not returning here and instead letting the
			// main search algorithm run now would yield the same result.
			return sourceRank + 1;
		} catch(NotTrustedException e) {}
		
		seen.add(source.getID());
		for(Trust sourceTrust : getGivenTrusts(source)) {
			Identity trustee = sourceTrust.getTrustee();
			int rank = sourceTrust.getValue() > 0 ? sourceRank + 1 : Integer.MAX_VALUE;
			queue.add(new Vertex(trustee, rank));
			
			// If the source OwnIdentity has assigned a rank to an Identity, that decision
			// is mandatory - other identities may not overwrite it.
			// Thus, in this case we must prevent the Identity from being able to receive
			// a rank from others by marking it as seen already.
			seen.add(trustee.getID());
		}
		
		while(!queue.isEmpty()) {
			Vertex vertex = queue.poll();
			
			if(vertex.identity == target)
				return vertex.rank;
			
			// Identity is not allowed to hand down a rank to trustees, no need to look at them
			if(vertex.rank == Integer.MAX_VALUE)
				continue;
			
			seen.add(vertex.identity.getID());
			
			for(Trust trust : getGivenTrusts(vertex.identity)) {
				Identity neighbourVertex = trust.getTrustee();
				
				if(seen.contains(neighbourVertex.getID()))
					continue; // Prevent infinite loop
				
				// FIXME: Performance: The UCS algorithm actually does decreaseKey() here instead of
				// add(), but Java PriorityQueue does not support decreaseKey().
				// remove() is also not an option since it is O(N).
				// The existing code will work since the entry with the too high priority will be
				// processed after the one with the lower priority since being sorted is the main
				// feature of a PQ. But it increases memory usage and runtime to have useless
				// entries in the PQ.
				
				if(trust.getValue() > 0) {
					queue.add(new Vertex(neighbourVertex, vertex.rank + 1));
				} else {
					queue.add(new Vertex(neighbourVertex, Integer.MAX_VALUE));
				}
			}
		}
		
		return -1;
	}

	/**
	 * Same as {@link #computeRankFromScratch_Forward(OwnIdentity, Identity)} except for the fact
	 * that the UCS algorithm is walking backwards from target to source.
	 * For understanding purposes, it is suggested that you read the aforementioned function first.
	 * 
	 * This fixes the worst case of the other implementation where the target is distrusted, i.e.
	 * where there is no Trust path from source to target. In that case, the other implementation
	 * would walk almost the whole WOT database if we assume that most Trusts in the database are
	 * positive.
	 * This inverse algorithm can be expected to be a lot faster in the case where there is no
	 * Trust path from source to target:
	 * WOT does not download identities which are distrusted. Thus, if we assume that evil,
	 * distrusted identities are only trusted by other evil distrusted identities, their social
	 * network will likely not be downloaded and therefore the trusts they have received will be
	 * very few. So the "dark", "evil" part of the Trust graph is likely small compared to the
	 * "good" part.
	 * Since this algorithm starts at the target identity, and only walks positive Trust edges,
	 * and not negative ones, if the target is distrusted it will only have to walk the "dark" part
	 * of the Trust graph as only the other "dark" identities trust it.
	 * As the dark part is a lot smaller, it has to search a lot less.
	 * FIXME: Review, I was pretty tired when I wrote this. */
	int computeRankFromScratch(final OwnIdentity source, final Identity target) {
		final class Vertex implements Comparable<Vertex>{
			final Identity identity;
			final Integer rank;
			
			public Vertex(Identity identity, int rank) {
				this.identity = identity;
				this.rank = rank;
			}

			@Override public int compareTo(Vertex o) {
				return rank.compareTo(o.rank);
			}
		}
		
		PriorityQueue<Vertex> queue = new PriorityQueue<Vertex>();
		HashSet<String> seen = new HashSet<String>(); // Key = Identity.getID()
		
		final int sourceRank;
		try {
			sourceRank = getScore(source, source).getRank();
			if(source == target)
				return sourceRank;
		} catch (NotInTrustTreeException e) {
			Logger.warning(this, "initTrustTreeWithoutCommit() not called for: " + source);
			// Some unit tests require the special case of initTrustTreeWithoutCommit() not having
			// been called for an OwnIdentity yet to yield a proper result of "no rank".
			return -1;
		}
		
		seen.add(target.getID());
		for(Trust targetTrust : getReceivedTrusts(target)) {
			Identity truster = targetTrust.getTruster();
			int rank = targetTrust.getValue() > 0 ? 1 : Integer.MAX_VALUE;
			
			if(truster == source) {
				// If a direct Trust exists from the OwnIdentity source to the target, then it
				// must always overwrite any other rank paths. This is a demand of the specification
				// of the WOT algorithm, see computeAllScoresWithoutCommit().
				return rank != Integer.MAX_VALUE ? rank + sourceRank : Integer.MAX_VALUE;
			}
			
			queue.add(new Vertex(truster, rank));
		}
		
		// If a vertex has received a Trust from the source, all other Trusts it has received can
		// be ignored. Thus, we cache the source Trusts: This allows us to first check for a
		// source Trust before we query all Trusts of a vertex. This should be faster since db4o
		// queries are expensive.
		// TODO: Performance: Use an array-backed map since this will be small.
		// Key = Identity.getID() of receiver of Trust
		HashMap<String, Trust> sourceTrusts = new HashMap<String, Trust>();
		for(Trust sourceTrust : getGivenTrusts(source)) {
			// TODO: Performance: Add & use Trust.getTrusteeID(), can be computed from Trust ID
			sourceTrusts.put(sourceTrust.getTrustee().getID(), sourceTrust);
		}
		
		while(!queue.isEmpty()) {
			Vertex vertex = queue.poll();
			
			if(vertex.identity == source)
				return vertex.rank != Integer.MAX_VALUE ? vertex.rank + sourceRank : Integer.MAX_VALUE;
			
			if(!seen.add(vertex.identity.getID()))
				continue; // Necessary because we do not use decreaseKey(), see below
			
			Trust trustFromSource = sourceTrusts.get(vertex.identity.getID());
			if(trustFromSource != null) {
				// The decision of an OwnIdentity overwrites all other Trust values an identity has
				// received. Thus, the rank is forced by it as well, and we must not walk other
				// edges.

					if(trustFromSource.getValue() > 0) {
						queue.add(new Vertex(source,
							vertex.rank != Integer.MAX_VALUE ? vertex.rank + 1 : Integer.MAX_VALUE));
					} else {
						// An identity with a rank of MAX_VALUE may not give its rank to its
						// trustees. So the only case where the rank of an identity can be MAX_VALUE
						// is when it is the last in the chain of Trust steps.
						// By adding the received Trusts of the search target to the queue before
						// starting processing the queue, we already processed the last links of the
						// chain. Here we can only be at last + 1, last + 2, etc.
						// So at this point, a rank of MAX_VALUE cannot be given because it would be
						// in the middle of the chain, not at the end.
						
						/* queue.add(new Vertex(source, Integer.MAX_VALUE)); */
					}
					
					continue;
			}

			
			for(Trust trust : getReceivedTrusts(vertex.identity)) {
				Identity neighbourVertex = trust.getTruster();
				
				if(seen.contains(neighbourVertex.getID()))
					continue; // Prevent infinite loop
				
				// FIXME: Performance: The UCS algorithm actually does decreaseKey() here instead of
				// add(), but Java PriorityQueue does not support decreaseKey().
				// remove() is also not an option since it is O(N).
				// The existing code will work since the entry with the too high priority will be
				// processed after the one with the lower priority since being sorted is the main
				// feature of a PQ. But it increases memory usage and runtime to have useless
				// entries in the PQ.
				
				if(trust.getValue() > 0) {
					queue.add(new Vertex(neighbourVertex,
						vertex.rank != Integer.MAX_VALUE ? vertex.rank + 1 : Integer.MAX_VALUE));
				} else {
					// Same as above
					/* queue.add(new Vertex(neighbourVertex, Integer.MAX_VALUE)); */
				}
			}
		}
		
		return -1;
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
	 * Synchronization:
	 * You have to synchronize on this WebOfTrust object when using this function.
	 * 
	 * TODO: Code quality: Move the documentation to {@link #computeRankFromScratch(OwnIdentity,
	 * Identity)} and document that this function here only works if the database content is up to
	 * date in certain areas.
	 * 
	 * @param truster The OwnIdentity that owns the trust tree
	 * @return The new Rank if this Identity
	 * @throws DuplicateScoreException if there already exist more than one {@link Score} objects for the trustee (should never happen)
	 */
	private int computeRank(OwnIdentity truster, Identity trustee) throws DuplicateScoreException {
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
	 * This speeds up setTrust/removeTrust as the score calculation is only performed when {@link #finishTrustListImport()} is called.
	 * 
	 * ATTENTION: Always take care to call one of {@link #finishTrustListImport()} / {@link #abortTrustListImport(Exception)} / {@link #abortTrustListImport(Exception, LogLevel)}
	 * for each call to this function.
	 * 
	 * Synchronization:
	 * This function does neither lock the database nor commit the transaction. You have to surround it with:
	 * <code>
	 * synchronized(WebOfTrust.this) {
	 * synchronized(mFetcher) {
	 * synchronized(mSubscriptionManager) {
	 * synchronized(Persistent.transactionLock(mDB)) {
	 *     try { beginTrustListImport(); ... finishTrustListImport(); Persistent.checkedCommit(mDB, this); }
	 *     catch(RuntimeException e) { abortTrustListImport(e); // Does checkedRollback() for you already }
	 * }}}}
	 * </code>
	 */
	protected void beginTrustListImport() {
		if(logMINOR) Logger.minor(this, "beginTrustListImport()");
		
		// Callers should not call this twice, so mTrustListImportInProgress should
		// nerver be true here.
		if(mTrustListImportInProgress) {
			// If this happens, it is probably a severe problem, so we schedule a full
			// Score recomputation hoping that it fixes eventual breakage.
			// (We cannot execute it right here because we have to rollback the transaction
			// and throw, see below.)
			mFullScoreComputationNeeded = true;
			
			// Because we are in a unclear error situation, its better to abort the
			// current trust list import and rollback the current transaction.
			// The rollback is done implicitly by abortTrustListImport().
			RuntimeException e
				= new RuntimeException("There was already a trust list import in progress!");
			abortTrustListImport(e);
			
			// We MUST throw here: We have rolled back the current transaction already,
			// and the caller might have done part of it before calling this function
			// and could continue to do part of it after calling this function.
			// So if we did not throw, the half-rolled-back transaction might be continued
			// by the caller and then be committed in its half-complete state.
			throw e;
		}
		
		mTrustListImportInProgress = true;
		assert(!mFullScoreComputationNeeded);
		assert(computeAllScoresWithoutCommit()); // The database is intact before the import
	}
	
	/**
	 * See {@link beginTrustListImport} for an explanation of the purpose of this function.
	 * Aborts the import of a trust list import and undoes all changes by it.
	 * 
	 * ATTENTION: In opposite to finishTrustListImport(), which does not commit the transaction, this rolls back the transaction.
	 * ATTENTION: Always take care to call {@link #beginTrustListImport()} for each call to this function.
	 * 
	 * Synchronization:
	 * This function does neither lock the database nor commit the transaction. You have to surround it with:
	 * <code>
	 * synchronized(WebOfTrust.this) {
	 * synchronized(mFetcher) {
	 * synchronized(mSubscriptionManager) {
	 * synchronized(Persistent.transactionLock(mDB)) {
	 *     try { beginTrustListImport(); ... finishTrustListImport(); Persistent.checkedCommit(mDB, this); }
	 *     catch(RuntimeException e) { abortTrustListImport(e, Logger.LogLevel.ERROR); // Does checkedRollback() for you already }
	 * }}}}
	 * </code>
	 * 
	 * @param e The exception which triggered the abort. Will be logged to the Freenet log file.
	 * @param logLevel The {@link LogLevel} to use when logging the abort to the Freenet log file.
	 */
	protected void abortTrustListImport(Exception e, LogLevel logLevel) {
		if(logMINOR) Logger.minor(this, "abortTrustListImport()");
		
		assert(mTrustListImportInProgress);
		mTrustListImportInProgress = false;
		mFullScoreComputationNeeded = false;
		Persistent.checkedRollback(mDB, this, e, logLevel);
		assert(computeAllScoresWithoutCommit()); // Test rollback.
	}
	
	/**
	 * See {@link beginTrustListImport} for an explanation of the purpose of this function.
	 * Aborts the import of a trust list import and undoes all changes by it.
	 * 
	 * ATTENTION: In opposite to finishTrustListImport(), which does not commit the transaction, this rolls back the transaction.
	 * ATTENTION: Always take care to call {@link #beginTrustListImport()} for each call to this function.
	 * 
	 * Synchronization:
	 * This function does neither lock the database nor commit the transaction. You have to surround it with:
	 * <code>
	 * synchronized(WebOfTrust.this) {
	 * synchronized(mFetcher) {
	 * synchronized(mSubscriptionManager) {
	 * synchronized(Persistent.transactionLock(mDB)) {
	 *     try { beginTrustListImport(); ... finishTrustListImport(); Persistent.checkedCommit(mDB, this); }
	 *     catch(RuntimeException e) { abortTrustListImport(e); // Does checkedRollback() for you already }
	 * }}}}
	 * </code>
	 * 
	 * @param e The exception which triggered the abort. Will be logged to the Freenet log file with log level {@link LogLevel.ERROR}
	 */
	protected void abortTrustListImport(Exception e) {
		abortTrustListImport(e, Logger.LogLevel.ERROR);
	}
	
	/**
	 * See {@link beginTrustListImport} for an explanation of the purpose of this function.
	 * Finishes the import of the current trust list and performs score computation. 
	 * 
	 * ATTENTION: In opposite to abortTrustListImport(), which rolls back the transaction, this does NOT commit the transaction. You have to do it!
	 * ATTENTION: Always take care to call {@link #beginTrustListImport()} for each call to this function.
	 * 
	 * Synchronization:
	 * This function does neither lock the database nor commit the transaction. You have to surround it with:
	 * <code>
	 * synchronized(WebOfTrust.this) {
	 * synchronized(mFetcher) {
	 * synchronized(mSubscriptionManager) {
	 * synchronized(Persistent.transactionLock(mDB)) {
	 *     try { beginTrustListImport(); ... finishTrustListImport(); Persistent.checkedCommit(mDB, this); }
	 *     catch(RuntimeException e) { abortTrustListImport(e); // Does checkedRollback() for you already }
	 * }}}}
	 * </code>
	 */
	protected void finishTrustListImport() {
		if(logMINOR) Logger.minor(this, "finishTrustListImport()");
		
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
	 * For understanding how score calculation works you should first read {@link #computeAllScoresWithoutCommit()}
	 * 
	 * This function does neither lock the database nor commit the transaction. You have to surround it with
	 * synchronized(this) {
	 * synchronized(mFetcher) {
	 * synchronized(mSubscriptionManager) {
	 * synchronized(Persistent.transactionLock(mDB)) {
	 *     try { ... updateScoreWithoutCommit(...); Persistent.checkedCommit(mDB, this); }
	 *     catch(RuntimeException e) { Persistent.checkedRollbackAndThrow(mDB, this, e);; }
	 * }}}}
	 */
	private void updateScoresWithoutCommit(final Trust oldTrust, final Trust newTrust) {
		if(logMINOR) Logger.minor(this, "Doing an incremental computation of all Scores...");
		
		assert(!mFullScoreComputationNeeded)
			: "updateScoresAfterDistrustWithoutCommit() which we call below will only work if "
			+ "called for each individual distrust, it is not a batch operation!";

		StopWatch time = new StopWatch();
		
		final boolean trustWasCreated = (oldTrust == null);
		final boolean trustWasDeleted = (newTrust == null);
		final boolean trustWasModified = !trustWasCreated && !trustWasDeleted;
		
		if(trustWasCreated && trustWasDeleted)
			throw new NullPointerException("No old/new trust specified.");

		// Check whether the old and new trust actually are between the same identities.
		// Notice: oldTrust() is a .clone() so the truster/trustee are also clones and we must check their IDs instead of object identity.
		
		if(trustWasModified && !oldTrust.getTruster().getID().equals(newTrust.getTruster().getID())) 
			throw new IllegalArgumentException("oldTrust has different truster, oldTrust:" + oldTrust + "; newTrust: " + newTrust);
		
		if(trustWasModified && !oldTrust.getTrustee().getID().equals(newTrust.getTrustee().getID()))
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
				
				// FIXME: Performance: Why is this inside the loop, it doesn't depend on anything
				// which changes during the loop?
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

					boolean scoreExistedBefore;
					
					try {
						currentStoredTrusteeScore = getScore(treeOwner, trustee);
						scoreExistedBefore = true;
					} catch(NotInTrustTreeException e) {
						scoreExistedBefore = false;
						currentStoredTrusteeScore = new Score(this, treeOwner, trustee, 0, -1, 0);
					}
					
					final Score oldScore = currentStoredTrusteeScore.clone();
					boolean oldShouldFetch = shouldFetchIdentity(trustee);
					
					final int newScoreValue = computeScoreValue(treeOwner, trustee); 
					final int newRank = computeRank(treeOwner, trustee);
					final int newCapacity = computeCapacity(treeOwner, trustee, newRank);
					final Score newScore = new Score(this, treeOwner, trustee, newScoreValue, newRank, newCapacity);

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
					
					if(currentStoredTrusteeScore.getRank() >= 0) {
						currentStoredTrusteeScore.storeWithoutCommit();
						if(!scoreExistedBefore || !oldScore.equals(currentStoredTrusteeScore))
							mSubscriptionManager.storeScoreChangedNotificationWithoutCommit(scoreExistedBefore ? oldScore : null, currentStoredTrusteeScore);
					}
					
					// If fetch status changed from false to true, we need to start fetching it
					// If the capacity changed from 0 to positive, we need to refetch the current edition: Identities with capacity 0 cannot
					// cause new identities to be imported from their trust list, capacity > 0 allows this.
					// If the fetch status changed from true to false, we need to stop fetching it
					// FIXME: Performance: This if() has the following inefficiency probably:
					// Its condition can be true if the following is true:
					// oldScore.getCapacity()== 0 && newScore.getCapacity() > 0
					//      && shouldFetchIdentity(trustee)
					// This CAN be the case if the identity already HAS a positive capacity in the
					// trust tree of a different tree owner. In that case, we will already have
					// imported the trustees of the identity because the other tree owner's given
					// capacity allows it, so there is no real need to refetch the trust list it.
					// BUT it has been a long time since I worked on the score computation so please
					// think about this very carefully before you change it.
					if((!oldShouldFetch || (oldScore.getCapacity()== 0 && newScore.getCapacity() > 0)) && shouldFetchIdentity(trustee)) {
						if(logMINOR) {
							if(!oldShouldFetch)
								Logger.minor(this, "Fetch status changed from false to true, refetching " + trustee);
							else
								Logger.minor(this, "Capacity changed from 0 to " + newScore.getCapacity() + ", refetching" + trustee);
						}

						final Identity oldTrustee = trustee.clone();
						
						trustee.markForRefetch();
						trustee.storeWithoutCommit();
						
						// Clients shall determine shouldFetch from the scores of an identity on their own so there is no need to notify the client about that
						// - but we do tell the client the state of Identity.getCurrentEditionFetchState() which is changed by markForRefetch().
						// Therefore we me must store a notification nevertheless.
						if(!oldTrustee.equals(trustee)) // markForRefetch() will not change anything if the current edition had not been fetched yet
							mSubscriptionManager.storeIdentityChangedNotificationWithoutCommit(oldTrustee, trustee);

						mFetcher.storeStartFetchCommandWithoutCommit(trustee);
					}
					else if(oldShouldFetch && !shouldFetchIdentity(trustee)) {
						if(logMINOR) Logger.minor(this, "Fetch status changed from true to false, aborting fetch of " + trustee);

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

		if(!mFullScoreComputationNeeded) {
			++mIncrementalScoreRecomputationDueToTrustCount;
			mIncrementalScoreRecomputationDueToTrustNanos += time.getNanos();
		} else {
			// TODO: Code quality: Do not reset time so we include the time which was necessary
			// to determine whether mFullScoreComputationNeeded = true / false.
			// The resetting was added since updateScoresAfterDistrustWithoutCommit() is new and
			// I wanted a precise measurement of how fast it is alone.
			time = new StopWatch();
		
			Identity distrusted;
			
			if(newTrust != null) {
				distrusted = newTrust.getTrustee();
			} else {
				try {
					// Must re-query the identity since oldTrust is a clone()
					distrusted = getIdentityByID(oldTrust.getTrustee().getID());
				} catch(UnknownIdentityException e) {
					throw new RuntimeException(e);
				}
			}
			
			updateScoresAfterDistrustWithoutCommit(distrusted);
			
			mFullScoreComputationNeeded = false;
	
			++mIncrementalScoreRecomputationDueToDistrustCount;
			mIncrementalScoreRecomputationDueToDistrustNanos += time.getNanos();
		}
		
		if(logMINOR) {
			if(!mFullScoreComputationNeeded)
				Logger.minor(this, "Incremental computation of all Scores finished.");
			else
				Logger.minor(this, "Incremental computation of all Scores not possible, full computation is needed.");
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

	/**
	 * FIXME: Check whether all the HashMap/HashSet used by this and the callees to avoid double 
	 * computations of stuff actually yield hits. It is possible that I wrongly assumed that double
	 * computations are possible in some of the cases where a map is used. */
	private void updateScoresAfterDistrustWithoutCommit(Identity distrusted) {
		// FIXME: Profile memory usage of this. It might get too large to fit into memory.
		// If it does, then instead store this in the database by having an "outdated?" flag on
		// Score objects.
		HashMap<String, ChangeSet<Score>> scoresWithUpdatedRank
			= updateRanksAfterDistrustWithoutCommit(distrusted); // Key = Score.getID()
		
		HashMap<String, ChangeSet<Score>> scoresWhichNeedEventNotification = scoresWithUpdatedRank;
		
		HashMap<String, ChangeSet<Score>> scoresWithUpdatedCapacity
			= updateCapacitiesAfterDistrustWithoutCommit(scoresWithUpdatedRank.values());
		
		scoresWithUpdatedRank = null;
		
		// No need to add scoresWithUpdatedCapacity to modifiedScores: They are a subset of
		// scoresWithUpdatedRank, which is already in modifiedScores.
		
		StopWatch time1 = logMINOR ? new StopWatch() : null;
		
		// TODO: Code quality: Move whole value processing code below to function
		
		HashSet<String> scoresWithUpdatedValue = new HashSet<String>(); // Key = Score.getID()
		
		// Now we update Score values.
		// A Score value in a trust tree of an OwnIdentity is the sum of all Trust values an
		// identity has received, multiplied by the capacity each trust giver has received in the
		// Score of the OwnIdentity.
		// So we must update Scores for which the product "Trust * capacity(Trust giver)" changed:
		// 1) Scores for which an included Trust value has changed = Scores which the distrusted
		//    identity has received. This is because this function is to be called when a single
		//    trust value has changed, and the distrusted identity is the receiver of that value.
		//    This is what the following loop does.
		// 2) Scores in which a Trust value is included for which the capacity of the giver of
		//    the Trust value has changed.
		//    This is what the loop after the following loop does.
		
		int scoresAffectedByTrustChange = 0;
		// Normally, we might have to check whether a new Score has to be created due to the changed
		// trust value - but updateRanksAfterDistrustWithoutCommit() did this already.
		for(Score score : getScores(distrusted)) {
			Score oldScore = score.clone();
			score.setValue(computeScoreValue(score.getTruster(), distrusted));
			score.storeWithoutCommit();
			
			String id = score.getID();
			
			scoresWithUpdatedValue.add(id);
			
			if(!score.equals(oldScore)) {
				if(!scoresWhichNeedEventNotification.containsKey(id))
					scoresWhichNeedEventNotification.put(id, new ChangeSet<Score>(oldScore, score));
			}

			++scoresAffectedByTrustChange;
		}
		
		if(logMINOR) {
			Logger.minor(this,
				"Time for updating " + scoresAffectedByTrustChange + " score values due to changed "
			  + "trust included in them: " + time1);
		}
		
		StopWatch time2 = logMINOR ? new StopWatch() : null;
		int scoresAffectedByCapacityChange = 0;
		
		// The capacity of an Identity's Score is the weight which the Trust values given by
		// the Identity have when computing Scores of other Identitys.
		// Thus, if the capacity of a Score X changed, we need to update the other Scores in which
		// a Trust value which is weighted by X's capacity is involved.
		for(ChangeSet<Score> changeSet : scoresWithUpdatedCapacity.values()) {
			if(changeSet.afterChange == null && changeSet.beforeChange.getCapacity() == 0) {
				// The Identity's capacity was deleted *and* the identity had a capacity of 0
				// before. With capacity of 0, it couldn't have influenced any other Identity's
				// Score values before and with no capacity now, it also cannot.
				// Thus, nothing has changed and we can skip it.
				continue;
			}
			
			Score scoreWithUpdatedCapacity
				= changeSet.afterChange != null ? changeSet.afterChange : changeSet.beforeChange;
			OwnIdentity treeOwner = scoreWithUpdatedCapacity.getTruster();
			Identity trustGiver = scoreWithUpdatedCapacity.getTrustee();
			
			for(Trust givenTrust : getGivenTrusts(trustGiver)) {
				Identity trustReceiver = givenTrust.getTrustee();
				ScoreID scoreID = new ScoreID(treeOwner, trustReceiver);
				
				if(!scoresWithUpdatedValue.add(scoreID.toString()))
					continue;
				
				Score score;
				try {
					// TODO: Performance: Use getScore() which consumes ScoreID
					score = getScore(treeOwner, trustReceiver);
				} catch(NotInTrustTreeException e) {
					// No need to create it: updateRanksAfterDistrustWithoutCommit() has already
					// created all scores which could be created.
					continue;
				}
				
				Score oldScore = score.clone();
				score.setValue(computeScoreValue(treeOwner, trustReceiver));
				score.storeWithoutCommit();
				 
				if(!score.equals(oldScore)) {
					String id = score.getID();
					if(!scoresWhichNeedEventNotification.containsKey(id)) {
						scoresWhichNeedEventNotification.put(id,
							new ChangeSet<Score>(oldScore, score));
					}
				}
			}
		}

		scoresWithUpdatedCapacity = null;
		scoresWithUpdatedValue = null;

		if(logMINOR) {
			Logger.minor(this,
				"Time for updating " + scoresAffectedByCapacityChange + " score values due to "
			  + "changed capacity: " + time2);
		}

		// Update SubscriptionManager and IdentityFetcher.
		// (Instead of having already created events while updating rank, capacity and value, we now
		// create the events after all three components have been updated to ensure that we only
		// create one event for each modified Score instead of three.)
		for(ChangeSet<Score> changeSet : scoresWhichNeedEventNotification.values()) {
			Score oldScore = changeSet.beforeChange;
			Score newScore = changeSet.afterChange;
			
			// Update SubscriptionManager
			
			mSubscriptionManager.storeScoreChangedNotificationWithoutCommit(oldScore, newScore);
			
			// Update IdentityFetcher
			
			boolean scoreWasCreatedOrDeleted = (oldScore == null ^ newScore == null);
			boolean capacityOrValueChangedSignum =
				!scoreWasCreatedOrDeleted &&
				(
					oldScore.getCapacity() == 0 && newScore.getCapacity() > 0 ||
					oldScore.getScore() < 0 && newScore.getScore() >= 0
				);
			
			// TODO: Performance: I am not sure whether a score having been created can cause any
			// change to shouldFetchIdentity() in this function: I feel like the Score can only be
			// a distrusting one and thus not cause an Identity to suddenly be wanted.
			// Thus, if the Score was created, you might avoid executing this branch.
			if(scoreWasCreatedOrDeleted || capacityOrValueChangedSignum) {
				Identity target = newScore != null ? newScore.getTrustee() : oldScore.getTrustee();
				
				// TODO: Performance: Use a IdentityHashMap<Identity> to only do this once for
				// every Identity, i.e. not repeat it for every OwnIdentity's Score tree.
				// As long as we don't, the IdentityFetcher will deduplicate the commands itself,
				// but database queries are expensive.
				// On the other hand, keeping all Identitys in memory might cause OOM, and the
				// amount of hits this would cause is likely small: As long as WOT doesn't have
				// a public gateway mode, the amount of OwnIdentitys can be assumed to be very small
				// as only one real user is using WOT.
				
				if(shouldFetchIdentity(target)) {
					// If the capacity changed from 0 to > 0, we have to call markForRefetch(), see
					// WoTTest.testRefetchDueToCapacityChange().
					// Currently, we also call it for any changy of shouldFetchIdentity() even if
					// there was no Score and thus no capacity before - the old Score computation
					// implementation did this, and I have no time checking whether it is needed.
					// TODO: Performance: Figure out if this is necessary.
					Identity oldTarget = target.clone();
					target.markForRefetch();
					target.storeWithoutCommit();
					
					if(!target.equals(oldTarget)) { // markForRefetch() does nothing on OwnIdentity
						mSubscriptionManager.storeIdentityChangedNotificationWithoutCommit(
							oldTarget, target);
					}
					
					mFetcher.storeStartFetchCommandWithoutCommit(target);
				} else
					mFetcher.storeAbortFetchCommandWithoutCommit(target);
			}
		}
	}

	private HashMap<String, ChangeSet<Score>>
			updateRanksAfterDistrustWithoutCommit(Identity distrusted) {
		
		StopWatch time = logMINOR ? new StopWatch() : null;
		
		LinkedList<Score> scoreQueue = new LinkedList<Score>();
		HashSet<String> scoresQueued = new HashSet<String>(); // Key = Score.getID()
		// TODO: Performance: This HashSet could be avoided by changing the code which uses it
		// to flag Score objects as just created by for example setting their rank to -1.
		// However, I am uncertain whether it is possible that Score objects with a rank of -1 are
		// created by other code as class Score does allow it explicitely, so it might be used
		// for other things already.
		HashSet<String> scoresCreated = new HashSet<String>(); // Key = Score.getID()
		// FIXME: Profile memory usage of this. It might get too large to fit into memory.
		// If it does, then instead store this in the database by having an "outdated?" flag on
		// Score objects.
		HashMap<String, ChangeSet<Score>> scoresWithOutdatedRank
			= new HashMap<String, ChangeSet<Score>>(); // Key = Score.getID()

		// Add all Scores of the distrusted identity to the queue.
		// We do this by iterating over all treeOwners instead via getScores():
		// There might *not* have been an existing Score object in every trust tree for the
		// distrusted identity if it had not received a trust value yet; and by the distrust it
		// could now be eligible for having one exist.
		// Thus, we must check whether we need to create a new Score object.
		// (We do not have to do this for trustees of the distrusted identity: A distrusted
		// identity must not be allowed to introduce other identities to prevent sybil, so
		// it cannot give them a Score)
		// FIXME: Test whether the above is actually true. Do so by attaching a special
		// marker to the created score values and checking whether they continue to survice 
		// the loop below which deletes scores.
		// FIXME: Do something smarter: Maybe we could first look at the changed trust value
		// to decide whether it could cause a Score object to be created before we do the
		// expensive database query which follows...
		for(OwnIdentity treeOwner : getAllOwnIdentities()) {
			Score outdated;
			try {
				outdated = getScore(treeOwner, distrusted);
			} catch(NotInTrustTreeException e) {
				// Use initial rank value of 0 because:
				// - it is invalid and thus the below "if(score.getRank() == newRank)" will not be
				//   confused
				// - cannot use -1 because the below computeRankFromScratch() will return that.
				outdated = new Score(this, treeOwner, distrusted, 0, 0, 0);
				outdated.storeWithoutCommit();
				scoresCreated.add(outdated.getID());
			}
			
			scoreQueue.add(outdated);
			scoresQueued.add(outdated.getID());
		}

		Score score;
		while((score = scoreQueue.poll()) != null) {
			int newRank = computeRankFromScratch(score.getTruster(), score.getTrustee());
			if(score.getRank() == newRank) {
				assert(!scoresCreated.contains(score.getID()))
					: "created scores should be initialized with an invalid rank";
				continue;
			}

			if(newRank == -1) {
				score.deleteWithoutCommit();
				// If we created the Score ourself, don't tell the caller about the delete rank:
				// There was no rank before, we had only created the Score to cause an attempt
				// of finding a rank possibly newly existing rank.
				if(!scoresCreated.contains(score.getID())) {
					ChangeSet<Score> diff = new ChangeSet<Score>(score, null);
					
					boolean wasAlreadyProcessed
						= scoresWithOutdatedRank.put(score.getID(), diff) != null;
					assert(!wasAlreadyProcessed)
						: "Each Score is only queued once so each should only be visited once";
				}
			} else {
				Score oldScore = scoresCreated.contains(score.getID()) ? null : score.clone();
				score.setRank(newRank);
				score.storeWithoutCommit();
				ChangeSet<Score> diff = new ChangeSet<Score>(oldScore, score);
				
				boolean wasAlreadyProcessed
					= scoresWithOutdatedRank.put(score.getID(), diff) != null;
				assert(!wasAlreadyProcessed)
					: "Each Score is only queued once so each should only be visited once";
			}
			
			final OwnIdentity treeOwner = score.getTruster();
			
			for(Trust edge : getGivenTrusts(score.getTrustee())) {
				Identity neighbour = edge.getTrustee();
				
				if(scoresQueued.contains(new ScoreID(treeOwner, neighbour).toString()))
					continue;
				
				Score touchedScore;
				try  {
					touchedScore = getScore(treeOwner, neighbour);
				} catch(NotInTrustTreeException e) {
					// No need to create a Score: This function is only called upon distrust.
					// Distrust can only induce Score creation for the distrusted identity, not
					// for its trustees. We already dealt with creating scores for the distrusted
					// identity in all score trees.
					continue;
				}
				
				scoreQueue.add(touchedScore);
				scoresQueued.add(touchedScore.getID());
			}
		}
		
		if(logMINOR) {
			Logger.minor(this,
				"Time for processing " + scoresQueued.size() + " scores to mark "
			  + scoresWithOutdatedRank.size() + " ranks as outdated: " + time);
		}
		
		return scoresWithOutdatedRank;
	}

	private HashMap<String, ChangeSet<Score>> updateCapacitiesAfterDistrustWithoutCommit(
			Collection<ChangeSet<Score>> scoresWithOutdatedRank) {
		
		StopWatch time = logMINOR ? new StopWatch() : null;
		
		// FIXME: Profile memory usage of this. It might get too large to fit into memory.
		// If it does, then instead store this in the database by having an "outdated?" flag on
		// Score objects.
		HashMap<String, ChangeSet<Score>> scoresWithOutdatedCapacity
			= new HashMap<String, ChangeSet<Score>>(); // Key = Score.getID()
		
		for(ChangeSet<Score> changeSet : scoresWithOutdatedRank) {
			Score score = changeSet.afterChange;
			if(score == null) {
				assert(changeSet.beforeChange != null);
				scoresWithOutdatedCapacity.put(changeSet.beforeChange.getID(), changeSet);
				continue;
			}
			
			int newCapacity
				= computeCapacity(score.getTruster(), score.getTrustee(), score.getRank());
			
			if(score.getCapacity() == newCapacity)
				continue;
			
			score.setCapacity(newCapacity);
			score.storeWithoutCommit();
			
			scoresWithOutdatedCapacity.put(score.getID(), changeSet);
		}
		
		if(logMINOR) {
			Logger.minor(this,
				"Time for processing " + scoresWithOutdatedRank.size() + " scores to mark "
		      + scoresWithOutdatedCapacity.size() + " capacities as outdated: " + time);
		}
		
		return scoresWithOutdatedCapacity;
	}

	/* Client interface functions */
	
	/**
	 * NOTICE: The added identity will not be fetched unless you also add a positive {@link Trust} value from an {@link OwnIdentity} to it.
     * (An exception would be if another identity which is being fetched starts trusting the added identity at some point in the future)
	 */
	public synchronized Identity addIdentity(String requestURI) throws MalformedURLException, InvalidParameterException {
		try {
			getIdentityByURI(requestURI);
			throw new InvalidParameterException("We already have this identity");
		}
		catch(UnknownIdentityException e) {
			final Identity identity = new Identity(this, requestURI, null, false);
			synchronized(mSubscriptionManager) {
			synchronized(Persistent.transactionLock(mDB)) {
				try {
					identity.storeWithoutCommit();
					mSubscriptionManager.storeIdentityChangedNotificationWithoutCommit(null, identity);
					Persistent.checkedCommit(mDB, this);
				} catch(RuntimeException e2) {
					Persistent.checkedRollbackAndThrow(mDB, this, e2); 
				}
			}
			}

			// The identity hasn't received a trust value. Therefore, there is no reason to fetch it and we don't notify the IdentityFetcher.
			
			Logger.normal(this, "addIdentity(): " + identity);
			return identity;
		}
	}
	
	public OwnIdentity createOwnIdentity(String nickName, boolean publishTrustList, String context)
		throws MalformedURLException, InvalidParameterException {
		
		FreenetURI[] keypair = getPluginRespirator().getHLSimpleClient().generateKeyPair(WebOfTrustInterface.WOT_NAME);
		return createOwnIdentity(keypair[0], nickName, publishTrustList, context);
	}

	/**
	 * @param context A context with which you want to use the identity. Null if you want to add it later.
	 */
	public synchronized OwnIdentity createOwnIdentity(FreenetURI insertURI, String nickName,
			boolean publishTrustList, String context) throws MalformedURLException, InvalidParameterException {
		
		synchronized(mFetcher) { // For beginTrustListImport()/setTrustWithoutCommit()
		synchronized(mSubscriptionManager) { // For beginTrustListImport()/setTrustWithoutCommit()/storeIdentityChangedNotificationWithoutCommit()
		synchronized(Persistent.transactionLock(mDB)) {
			try {
				OwnIdentity identity = getOwnIdentityByURI(insertURI);
				throw new InvalidParameterException(
				    getBaseL10n().getString("Exceptions.WebOfTrust.createOwnIdentity.IllegalParameterException.OwnIdentityExistsAlready", 
				        "nickname", identity.getShortestUniqueNickname()
				    )
				);
			} catch(UnknownIdentityException uie) {}

			try {
				Identity identity = getIdentityByURI(insertURI);
				throw new InvalidParameterException(
					getBaseL10n().getString("Exceptions.WebOfTrust.createOwnIdentity.IllegalParameterException.NonOwnIdentityExistsAlready",
				        "nickname", identity.getShortestUniqueNickname()
			        )
				);
			} catch(UnknownIdentityException uie) {}
			
			OwnIdentity identity = new OwnIdentity(this, insertURI, nickName, publishTrustList);
				
			if(context != null)
				identity.addContext(context);

			if(publishTrustList) {
				identity.addContext(IntroductionPuzzle.INTRODUCTION_CONTEXT); /* TODO: make configureable */
				identity.setProperty(IntroductionServer.PUZZLE_COUNT_PROPERTY, Integer.toString(IntroductionServer.DEFAULT_PUZZLE_COUNT));
			}

			try {
				identity.storeWithoutCommit();
				mFetcher.storeStartFetchCommandWithoutCommit(identity);
				mSubscriptionManager.storeIdentityChangedNotificationWithoutCommit(null, identity);
				initTrustTreeWithoutCommit(identity);

				beginTrustListImport();

				// Incremental score computation has proven to be very very slow when creating identities so we just schedule a full computation.
				// TODO: Performance: Only recompute the Score tree of the created identity.
				mFullScoreComputationNeeded = true;

				for(String seedURI : WebOfTrustInterface.SEED_IDENTITIES) {
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
				
	            if(mInserter != null)
	                mInserter.nextIteration();

				Logger.normal(this, "Successfully created a new OwnIdentity: " + identity);
				return identity;
			}
			catch(RuntimeException e) {
				abortTrustListImport(e); // Rolls back for us
				throw e; // Satisfy the compiler
			}
		}
		}
		}
	}
	
	/**
	 * This "deletes" an {@link OwnIdentity} by replacing it with an {@link Identity}.
	 * 
	 * The {@link OwnIdentity} is not deleted because this would be a security issue:
	 * If other {@link OwnIdentity}s have assigned a trust value to it, the trust value would be gone if there is no {@link Identity} object to be the target
	 * 
	 * @param id The {@link Identity.IdentityID} of the identity.
	 * @throws UnknownIdentityException If there is no {@link OwnIdentity} with the given ID. Also thrown if a non-own identity exists with the given ID.
	 */
	public synchronized void deleteOwnIdentity(String id) throws UnknownIdentityException {
		Logger.normal(this, "deleteOwnIdentity(): Starting... ");
		
		synchronized(mPuzzleStore) {
		synchronized(mFetcher) {
		synchronized(mSubscriptionManager) {
		synchronized(Persistent.transactionLock(mDB)) {
			final OwnIdentity oldIdentity = getOwnIdentityByID(id);
			
			try {
				Logger.normal(this, "Deleting an OwnIdentity by converting it to a non-own Identity: " + oldIdentity);

				// We don't need any score computations to happen (explanation will follow below) so we don't need the following: 
				/* beginTrustListImport(); */

				// This function messes with the score graph manually so it is a good idea to check whether it is intact before and afterwards.
				assert(computeAllScoresWithoutCommit());

				final Identity newIdentity;
				
				try {
					newIdentity = new Identity(this, oldIdentity.getRequestURI(), oldIdentity.getNickname(), oldIdentity.doesPublishTrustList());
				} catch(MalformedURLException e) { // The data was taken from the OwnIdentity so this shouldn't happen
					throw new RuntimeException(e);
				} catch (InvalidParameterException e) { // The data was taken from the OwnIdentity so this shouldn't happen
					throw new RuntimeException(e);
				}
				
				newIdentity.setContexts(oldIdentity.getContexts());
				newIdentity.setProperties(oldIdentity.getProperties());
				
				try {
					newIdentity.setEdition(oldIdentity.getEdition());
				} catch (InvalidParameterException e) { // The data was taken from old identity so this shouldn't happen
					throw new RuntimeException(e);
				}
				
				// In theory we do not need to re-fetch the current trust list edition:
				// The trust list of an own identity is always stored completely in the database, i.e. all trustees exist.
				// HOWEVER if the user had used the restoreOwnIdentity feature and then used this function, it might be the case that
				// the current edition of the old OwndIdentity was not fetched yet.
				// So we set the fetch state to FetchState.Fetched if the oldIdentity's fetch state was like that as well.
				if(oldIdentity.getCurrentEditionFetchState() == FetchState.Fetched) {
					newIdentity.onFetched(oldIdentity.getLastFetchedDate());
				}
				// An else to set the fetch state to FetchState.NotFetched is not necessary, newIdentity.setEdition() did that already.

				newIdentity.storeWithoutCommit();

				// Copy all received trusts.
				// We don't have to modify them because they are user-assigned values and the assignment
				// of the user does not change just because the type of the identity changes.
				for(Trust oldReceivedTrust : getReceivedTrusts(oldIdentity)) {
					Trust newReceivedTrust;
					try {
						newReceivedTrust = new Trust(this, oldReceivedTrust.getTruster(), newIdentity,
								oldReceivedTrust.getValue(), oldReceivedTrust.getComment());
					} catch (InvalidParameterException e) { // The data was taken from the old Trust so this shouldn't happen
						throw new RuntimeException(e);
					}

					// The following assert() cannot be added because it would always fail:
					// It would implicitly trigger oldIdentity.equals(identity) which is not the case:
					// Certain member values such as the edition might not be equal.
					/* assert(newReceivedTrust.equals(oldReceivedTrust)); */

					oldReceivedTrust.deleteWithoutCommit();
					newReceivedTrust.storeWithoutCommit();
				}

				assert(getReceivedTrusts(oldIdentity).size() == 0);

				// Copy all received scores.
				// We don't have to modify them because the rating of the identity from the perspective of a
				// different own identity should NOT be dependent upon whether it is an own identity or not.
				for(Score oldScore : getScores(oldIdentity)) {
					Score newScore = new Score(this, oldScore.getTruster(), newIdentity, oldScore.getScore(),
							oldScore.getRank(), oldScore.getCapacity());

					// The following assert() cannot be added because it would always fail:
					// It would implicitly trigger oldIdentity.equals(identity) which is not the case:
					// Certain member values such as the edition might not be equal.
					/* assert(newScore.equals(oldScore)); */

					oldScore.deleteWithoutCommit();
					newScore.storeWithoutCommit();
				}

				assert(getScores(oldIdentity).size() == 0);

				// Delete all given scores:
				// Non-own identities do not assign scores to other identities so we can just delete them.
				for(Score oldScore : getGivenScores(oldIdentity)) {
					final Identity trustee = oldScore.getTrustee();
					final boolean oldShouldFetchTrustee = shouldFetchIdentity(trustee);
					
					oldScore.deleteWithoutCommit();
					mSubscriptionManager.storeScoreChangedNotificationWithoutCommit(oldScore, null);
					
					// If the OwnIdentity which we are converting was the only source of trust to the trustee
					// of this Score value, the should-fetch state of the trustee might change to false.
					if(oldShouldFetchTrustee && shouldFetchIdentity(trustee) == false) {
						mFetcher.storeAbortFetchCommandWithoutCommit(trustee);
					}
				}
				
				assert(getGivenScores(oldIdentity).size() == 0);

				// Copy all given trusts:
				// We don't have to use the removeTrust/setTrust functions because the score graph does not need updating:
				// - To the rating of the converted identity in the score graphs of other own identities it is irrelevant
				//   whether it is an own identity or not. The rating should never depend on whether it is an own identity!
				// - Non-own identities do not have a score graph. So the score graph of the converted identity is deleted
				//   completely and therefore it does not need to be updated.
				for(Trust oldGivenTrust : getGivenTrusts(oldIdentity)) {
					Trust newGivenTrust;
					try {
						newGivenTrust = new Trust(this, newIdentity, oldGivenTrust.getTrustee(),
								oldGivenTrust.getValue(), oldGivenTrust.getComment());
					} catch (InvalidParameterException e) { // The data was taken from the old Trust so this shouldn't happen
						throw new RuntimeException(e);
					}

					// The following assert() cannot be added because it would always fail:
					// It would implicitly trigger oldIdentity.equals(identity) which is not the case:
					// Certain member values such as the edition might not be equal.
					/* assert(newGivenTrust.equals(oldGivenTrust)); */

					oldGivenTrust.deleteWithoutCommit();
					newGivenTrust.storeWithoutCommit();
				}

				mPuzzleStore.onIdentityDeletion(oldIdentity);
				mFetcher.storeAbortFetchCommandWithoutCommit(oldIdentity);
				// NOTICE:
				// If the fetcher did store a db4o object reference to the identity, we would have to trigger command processing
				// now to prevent leakage of the identity object.
				// But the fetcher does NOT store a db4o object reference to the given identity. It stores its ID as String only.
				// Therefore, it is OK that the fetcher does not immediately process the commands now.

				oldIdentity.deleteWithoutCommit();

				mFetcher.storeStartFetchCommandWithoutCommit(newIdentity);
				
				mSubscriptionManager.storeIdentityChangedNotificationWithoutCommit(oldIdentity, newIdentity);

				// This function messes with the score graph manually so it is a good idea to check whether it is intact before and afterwards.
				assert(computeAllScoresWithoutCommit());

				Persistent.checkedCommit(mDB, this);
			}
			catch(RuntimeException e) {
				Persistent.checkedRollbackAndThrow(mDB, this, e);
			}
		}
		}
		}
		}
		
		Logger.normal(this, "deleteOwnIdentity(): Finished.");
	}

	/**
	 * NOTICE: When changing this function, please also take care of {@link OwnIdentity.isRestoreInProgress()}
	 * 
	 * This function does neither lock the database nor commit the transaction. You have to surround it with
	 * <code>
	 * synchronized(WebOfTrust.this) {
	 * synchronized(mPuzzleStore) {
	 * synchronized(mFetcher) {
	 * synchronized(mSubscriptionManager) {
	 * synchronized(Persistent.transactionLock(mDB)) {
	 *     try { ... restoreOwnIdentityWithoutCommit(...); Persistent.checkedCommit(mDB, this); }
	 *     catch(RuntimeException e) { Persistent.checkedRollbackAndThrow(mDB, this, e); }
	 * }}}}
	 * </code>
	 */
	public OwnIdentity restoreOwnIdentityWithoutCommit(FreenetURI insertFreenetURI) throws MalformedURLException, InvalidParameterException {
		Logger.normal(this, "restoreOwnIdentity(): Starting... ");
		
		OwnIdentity identity;

			try {
				long edition = 0;
				
				try {
					edition = Math.max(edition, insertFreenetURI.getEdition());
				} catch(IllegalStateException e) {
					// The user supplied URI did not have an edition specified
				}
				
				try { // Try replacing an existing non-own version of the identity with an OwnIdentity
					Identity oldIdentity = getIdentityByURI(insertFreenetURI);
					
					if(oldIdentity instanceof OwnIdentity) {
						throw new InvalidParameterException(
						    getBaseL10n().getString("Exceptions.WebOfTrust.restoreOwnIdentityWithoutCommit.IllegalParameterException.OwnIdentityExistsAlready",
						        "nickname", oldIdentity.getShortestUniqueNickname()
						    )
						);
					}
					
					Logger.normal(this, "Restoring an already known identity from Freenet: " + oldIdentity);
					
					// Normally, one would expect beginTrustListImport() to happen close to the actual trust list changes later on in this function.
					// But beginTrustListImport() contains an assert(computeAllScoresWithoutCommit()) and that call to the score computation reference
					// implementation will fail if two identities with the same ID exist.
					// This would be the case later on - we cannot delete the non-own version of the OwnIdentity before we modified the trust graph
					// but we must also store the own version to be able to modify the trust graph.
					beginTrustListImport();
					
					// We already have fetched this identity as a stranger's one. We need to update the database.
					identity = new OwnIdentity(this, insertFreenetURI, oldIdentity.getNickname(), oldIdentity.doesPublishTrustList());
					
					/* We re-fetch the most recent edition to make sure all trustees are imported */
					edition = Math.max(edition, oldIdentity.getEdition());
					identity.restoreEdition(edition, oldIdentity.getLastFetchedDate());
				
					identity.setContexts(oldIdentity.getContexts());
					identity.setProperties(oldIdentity.getProperties());
					
					identity.storeWithoutCommit();
					mSubscriptionManager.storeIdentityChangedNotificationWithoutCommit(oldIdentity, identity);
					initTrustTreeWithoutCommit(identity);
	
					// Copy all received trusts.
					// We don't have to modify them because they are user-assigned values and the assignment
					// of the user does not change just because the type of the identity changes.
					for(Trust oldReceivedTrust : getReceivedTrusts(oldIdentity)) {
						Trust newReceivedTrust = new Trust(this, oldReceivedTrust.getTruster(), identity,
								oldReceivedTrust.getValue(), oldReceivedTrust.getComment());
						
						// The following assert() cannot be added because it would always fail:
						// It would implicitly trigger oldIdentity.equals(identity) which is not the case:
						// Certain member values such as the edition might not be equal.
						/* assert(newReceivedTrust.equals(oldReceivedTrust)); */
						
						oldReceivedTrust.deleteWithoutCommit();
						newReceivedTrust.storeWithoutCommit();
					}
					
					assert(getReceivedTrusts(oldIdentity).size() == 0);
		
					// Copy all received scores.
					// We don't have to modify them because the rating of the identity from the perspective of a
					// different own identity should NOT be dependent upon whether it is an own identity or not.
					for(Score oldScore : getScores(oldIdentity)) {
						Score newScore = new Score(this, oldScore.getTruster(), identity, oldScore.getScore(),
								oldScore.getRank(), oldScore.getCapacity());
						
						// The following assert() cannot be added because it would always fail:
						// It would implicitly trigger oldIdentity.equals(identity) which is not the case:
						// Certain member values such as the edition might not be equal.
						/* assert(newScore.equals(oldScore)); */
						
						oldScore.deleteWithoutCommit();
						newScore.storeWithoutCommit();
						
						// Nothing has changed about the actual score so we do not notify.
						// mSubscriptionManager.storeScoreChangedNotificationWithoutCommit(oldScore, newScore);
					}
					
					assert(getScores(oldIdentity).size() == 0);
					
					// What we do NOT have to deal with is the given scores of the old identity:
					// Given scores do NOT exist for non-own identities, so there are no old ones to update.
					// Of cause there WILL be scores because it is an own identity now.
					// They will be created automatically when updating the given trusts
					// - so thats what we will do now.
					
					// Copy all given trusts at first instead of using removeTrustWithoutCommit()
					// and immediately afterwards setTrustWithoutCommit() because those functions
					// would be confused by both the OwnIdentity and non-own Identity object being
					// in the database at the same time.
					// Thus we will first delete the non-own Identity and then re-set the trusts.
					final ObjectSet<Trust> oldGivenTrusts = getGivenTrusts(oldIdentity);
					
					// TODO: No need to copy after this is fixed:
					// https://bugs.freenetproject.org/view.php?id=6596
					final ArrayList<Trust> oldGivenTrustsCopy
						= new ArrayList<Trust>(oldGivenTrusts);
					
					for(Trust oldGivenTrust : oldGivenTrusts)
						oldGivenTrust.deleteWithoutCommit();
					
					assert(getGivenTrusts(oldIdentity).size() == 0);
					
					// We do not call finishTrustListImport() now: It might trigger execution of computeAllScoresWithoutCommit
					// which would re-create scores of the old identity. We later call it AFTER deleting the old identity.
					/* finishTrustListImport(); */
		
					mPuzzleStore.onIdentityDeletion(oldIdentity);
					mFetcher.storeAbortFetchCommandWithoutCommit(oldIdentity);
					// NOTICE:
					// If the fetcher did store a db4o object reference to the identity, we would have to trigger command processing
					// now to prevent leakage of the identity object.
					// But the fetcher does NOT store a db4o object reference to the given identity. It stores its ID as String only.
					// Therefore, it is OK that the fetcher does not immediately process the commands now.
					
					oldIdentity.deleteWithoutCommit();
					
					// Update all given trusts. This will also cause received scores to be computed,
					// which is why we had not set them yet.
					for(Trust givenTrust : oldGivenTrustsCopy)
						setTrustWithoutCommit(identity, givenTrust.getTrustee(), givenTrust.getValue(), givenTrust.getComment());
					
					mFetcher.storeStartFetchCommandWithoutCommit(identity);
					
					finishTrustListImport();
				} catch (UnknownIdentityException e) { // The identity did NOT exist as non-own identity yet so we can just create an OwnIdentity and store it.
					identity = new OwnIdentity(this, insertFreenetURI, null, false);
					
					Logger.normal(this, "Restoring not-yet-known identity from Freenet: " + identity);
					
					identity.restoreEdition(edition, null);
					
					// Store the new identity
					identity.storeWithoutCommit();
					mSubscriptionManager.storeIdentityChangedNotificationWithoutCommit(null, identity);
					
					initTrustTreeWithoutCommit(identity);
					mFetcher.storeStartFetchCommandWithoutCommit(identity);
				}

				// This function messes with the trust graph manually so it is a good idea to check whether it is intact afterwards.
				assert(computeAllScoresWithoutCommit());
				
				Logger.normal(this, "restoreOwnIdentity(): Finished.");
				return identity;
			}
			catch(RuntimeException e) {
				if(mTrustListImportInProgress) { // We don't execute beginTrustListImport() in all code paths of this function
					// Does rollback for us. The outside will do another duplicate rollback() because the JavaDoc tells it to.
					// But thats acceptable to keep the transaction code pattern the same everywhere.
					abortTrustListImport(e); 
				}
				// The callers of this function are obliged to do Persistent.checkedRollbackAndThrow() for us, so we can and must throw the exception out.
				throw e;
			}

	}
	
	/**
	 * @return An {@link OwnIdentity#clone()} of the restored identity. By cloning, the object is decoupled from the database and you can keep it in memory
	 *     to do with it whatever you like.
	 */
	public synchronized OwnIdentity restoreOwnIdentity(FreenetURI insertFreenetURI) throws MalformedURLException, InvalidParameterException {
		synchronized(mPuzzleStore) {
		synchronized(mFetcher) {
		synchronized(mSubscriptionManager) {
		synchronized(Persistent.transactionLock(mDB)) {
			try {
				final OwnIdentity identity = restoreOwnIdentityWithoutCommit(insertFreenetURI);
				Persistent.checkedCommit(mDB, this);
				return identity.clone();
			}
			catch(RuntimeException e) {
				Persistent.checkedRollbackAndThrow(mDB, this, e);
				throw e; // The compiler doesn't know that the above function throws, so it would complain about a missing return statement without this.
			}
		}
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

		synchronized(mFetcher) {
		synchronized(mSubscriptionManager) {
		synchronized(Persistent.transactionLock(mDB)) {
			try  {
				removeTrustWithoutCommit(truster, trustee);
				Persistent.checkedCommit(mDB, this);
			}
			catch(RuntimeException e) {
				Persistent.checkedRollbackAndThrow(mDB, this, e);
			}
		}
		}
		}
	}
	
	/**
	 * Enables or disables the publishing of the trust list of an {@link OwnIdentity}.
	 * The trust list contains all trust values which the OwnIdentity has assigned to other identities.
	 * 
	 * @see OwnIdentity#setPublishTrustList(boolean)
	 * @param ownIdentityID The {@link Identity.IdentityID} of the {@link OwnIdentity} you want to modify.
	 * @param publishTrustList Whether to publish the trust list. 
	 * @throws UnknownIdentityException If there is no {@link OwnIdentity} with the given {@link Identity.IdentityID}.
	 */
	public synchronized void setPublishTrustList(final String ownIdentityID, final boolean publishTrustList) throws UnknownIdentityException {
		final OwnIdentity identity = getOwnIdentityByID(ownIdentityID);
		final OwnIdentity oldIdentity = identity.clone(); // For the SubscriptionManager
		
		synchronized(mSubscriptionManager) {
		synchronized(Persistent.transactionLock(mDB)) {
			try {
				identity.setPublishTrustList(publishTrustList);
				mSubscriptionManager.storeIdentityChangedNotificationWithoutCommit(oldIdentity, identity);
				identity.storeAndCommit();
			} catch(RuntimeException e) {
				Persistent.checkedRollbackAndThrow(mDB, this, e);
			}
		}
		}
		
		Logger.normal(this, "setPublishTrustList to " + publishTrustList + " for " + identity);
	}
	
	/**
	 * Enables or disables the publishing of {@link IntroductionPuzzle}s for an {@link OwnIdentity}.
	 * 
	 * If publishIntroductionPuzzles==true adds, if false removes:
	 * - the context {@link IntroductionPuzzle.INTRODUCTION_CONTEXT}
	 * - the property {@link IntroductionServer.PUZZLE_COUNT_PROPERTY} with the value {@link IntroductionServer.DEFAULT_PUZZLE_COUNT}
	 * 
	 * @param ownIdentityID The {@link Identity.IdentityID} of the {@link OwnIdentity} you want to modify.
	 * @param publishIntroductionPuzzles Whether to publish introduction puzzles. 
	 * @throws UnknownIdentityException If there is no identity with the given ownIdentityID
	 * @throws InvalidParameterException If publishIntroudctionPuzzles is set to true and {@link OwnIdentity#doesPublishTrustList()} returns false on the selected identity: It doesn't make sense for an identity to allow introduction if it doesn't publish a trust list - the purpose of introduction is to add other identities to your trust list.
	 */
	public synchronized void setPublishIntroductionPuzzles(final String ownIdentityID, final boolean publishIntroductionPuzzles, final int count) throws UnknownIdentityException, InvalidParameterException {
		final OwnIdentity identity = getOwnIdentityByID(ownIdentityID);
		final OwnIdentity oldIdentity = identity.clone(); // For the SubscriptionManager
		
		if(publishIntroductionPuzzles && !identity.doesPublishTrustList())
			throw new InvalidParameterException("An identity must publish its trust list if it wants to publish introduction puzzles!");
		
		synchronized(mSubscriptionManager) {
		synchronized(Persistent.transactionLock(mDB)) {
			try {
				if(publishIntroductionPuzzles) {
					identity.addContext(IntroductionPuzzle.INTRODUCTION_CONTEXT);
					identity.setProperty(IntroductionServer.PUZZLE_COUNT_PROPERTY, Integer.toString(count));
				} else {
					identity.removeContext(IntroductionPuzzle.INTRODUCTION_CONTEXT);
					identity.removeProperty(IntroductionServer.PUZZLE_COUNT_PROPERTY);
				}
				
				mSubscriptionManager.storeIdentityChangedNotificationWithoutCommit(oldIdentity, identity);
				identity.storeAndCommit();
			} catch(RuntimeException e){
				Persistent.checkedRollbackAndThrow(mDB, this, e);
			}
		}
		}
		
		Logger.normal(this, "Set publishIntroductionPuzzles to " + true + " for " + identity);		
	}
	
	/**
	 * Wrapper around {@link #setPublishIntroductionPuzzles(String, boolean, int)}, passes the default puzzle amount as count to it.
	 */
	public void setPublishIntroductionPuzzles(final String ownIdentityID, final boolean publishIntroductionPuzzles) throws UnknownIdentityException, InvalidParameterException {
		setPublishIntroductionPuzzles(ownIdentityID, publishIntroductionPuzzles, IntroductionServer.DEFAULT_PUZZLE_COUNT);
	}
	
	public synchronized void addContext(String ownIdentityID, String newContext) throws UnknownIdentityException, InvalidParameterException {
		final OwnIdentity identity = getOwnIdentityByID(ownIdentityID);
		final OwnIdentity oldIdentity = identity.clone(); // For the SubscriptionManager
		
		synchronized(mSubscriptionManager) {
		synchronized(Persistent.transactionLock(mDB)) {
			try {
				identity.addContext(newContext);
				mSubscriptionManager.storeIdentityChangedNotificationWithoutCommit(oldIdentity, identity);
				identity.storeAndCommit();
			} catch(RuntimeException e){
				Persistent.checkedRollbackAndThrow(mDB, this, e);
			}
		}
		}

		
		if(logDEBUG) Logger.debug(this, "Added context '" + newContext + "' to identity '" + identity.getNickname() + "'");
	}

	public synchronized void removeContext(String ownIdentityID, String context) throws UnknownIdentityException, InvalidParameterException {
		final OwnIdentity identity = getOwnIdentityByID(ownIdentityID);
		final OwnIdentity oldIdentity = identity.clone(); // For the SubscriptionManager
		
		synchronized(mSubscriptionManager) {
		synchronized(Persistent.transactionLock(mDB)) {
			try {
				identity.removeContext(context);
				mSubscriptionManager.storeIdentityChangedNotificationWithoutCommit(oldIdentity, identity);
				identity.storeAndCommit();
			} catch(RuntimeException e){
				Persistent.checkedRollbackAndThrow(mDB, this, e);
			}
		}
		}
		
		if(logDEBUG) Logger.debug(this, "Removed context '" + context + "' from identity '" + identity.getNickname() + "'");
	}
	
	public synchronized String getProperty(String identityID, String property) throws InvalidParameterException, UnknownIdentityException {
		return getIdentityByID(identityID).getProperty(property);
	}

	public synchronized void setProperty(String ownIdentityID, String property, String value) throws UnknownIdentityException, InvalidParameterException {
		final OwnIdentity identity = getOwnIdentityByID(ownIdentityID);
		final OwnIdentity oldIdentity = identity.clone(); // For the SubscriptionManager
		
		synchronized(mSubscriptionManager) {
		synchronized(Persistent.transactionLock(mDB)) {
			try {
				identity.setProperty(property, value);
				mSubscriptionManager.storeIdentityChangedNotificationWithoutCommit(oldIdentity, identity);
				identity.storeAndCommit();
			} catch(RuntimeException e){
				Persistent.checkedRollbackAndThrow(mDB, this, e);
			}
		}
		}
		
		if(logDEBUG) Logger.debug(this, "Added property '" + property + "=" + value + "' to identity '" + identity.getNickname() + "'");
	}
	
	public synchronized void removeProperty(String ownIdentityID, String property) throws UnknownIdentityException, InvalidParameterException {
		final OwnIdentity identity = getOwnIdentityByID(ownIdentityID);
		final OwnIdentity oldIdentity = identity.clone(); // For the SubscriptionManager
		
		synchronized(mSubscriptionManager) {
		synchronized(Persistent.transactionLock(mDB)) {
			try {
				identity.removeProperty(property);
				mSubscriptionManager.storeIdentityChangedNotificationWithoutCommit(oldIdentity, identity);
				identity.storeAndCommit();
			} catch(RuntimeException e){
				Persistent.checkedRollbackAndThrow(mDB, this, e);
			}
		}
		}
		
		if(logDEBUG) Logger.debug(this, "Removed property '" + property + "' from identity '" + identity.getNickname() + "'");
	}

	@Override
	public String getVersion() {
		return Version.getMarketingVersion();
	}
	
	@Override
	public long getRealVersion() {
		return Version.getRealVersion();
	}

	@Override
	public String getString(String key) {
	    return getBaseL10n().getString(key);
	}
	
	@Override
	public void setLanguage(LANGUAGE newLanguage) {
        WebOfTrust.l10n = new PluginL10n(this, newLanguage);
        if(logDEBUG) Logger.debug(this, "Set LANGUAGE to: " + newLanguage.isoCode);
	}
	
	@Override
	public PluginRespirator getPluginRespirator() {
		return mPR;
	}
	
	@Override
	public ExtObjectContainer getDatabase() {
		return mDB;
	}
	
	public Configuration getConfig() {
		return mConfig;
	}
	
	@Override
	public SubscriptionManager getSubscriptionManager() {
		return mSubscriptionManager;
	}
	
	IdentityFetcher getIdentityFetcher() {
		return mFetcher;
	}
	
	public IdentityFileQueue getIdentityFileQueue() {
		return mIdentityFileQueue;
	}

	public IdentityFileProcessor getIdentityFileProcessor() {
		return mIdentityFileProcessor;
	}

    public IdentityInserter getIdentityInserter() {
        return mInserter;
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

    public IntroductionServer getIntroductionServer() {
        return mIntroductionServer;
    }

	@Override
	protected FCPInterface getFCPInterface() {
		return mFCPInterface;
	}

    /**
     * @return A {@link RequestClient} which shall be used by {@link IdentityFetcher} and
     *         {@link IdentityInserter} to group their Freenet data transfers into the same
     *         scheduling group.<br>
     *         Identity fetches and inserts belong together, so it makes sense to use the same
     *         RequestClient for them.
     */
	public RequestClient getRequestClient() {
		return mRequestClient;
	}

    /**
     * This is where our L10n files are stored.
     * @return Path of our L10n files.
     */
    @Override
    public String getL10nFilesBasePath() {
        return "plugins/WebOfTrust/l10n/";
    }

    /**
     * This is the mask of our L10n files : lang_en.l10n, lang_de.10n, ...
     * @return Mask of the L10n files.
     */
    @Override
    public String getL10nFilesMask() {
        return "lang_${lang}.l10n";
    }

    /**
     * Override L10n files are stored on the disk, their names should be explicit
     * we put here the plugin name, and the "override" indication. Plugin L10n
     * override is not implemented in the node yet.
     * @return Mask of the override L10n files.
     */
    @Override
    public String getL10nOverrideFilesMask() {
        return "WebOfTrust_lang_${lang}.override.l10n";
    }

    /**
     * Get the ClassLoader of this plugin. This is necessary when getting
     * resources inside the plugin's Jar, for example L10n files.
     * @return ClassLoader object
     */
    @Override
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
	 * Gets the amount of non-own identities which were never fetched yet but will be fetched.<br>
	 * This is identities for which {@link #shouldFetchIdentity(Identity)} returns true but
	 * {@link Identity#getLastFetchedDate()} is <code>new Date(0)</code>.<br><br>
	 * 
	 * Notice: This is an expensive database query and thus should only be used for manual
	 * statistical inquiries at the UI; do not use it in program logic. */
	public int getNumberOfUnfetchedIdentities() {
		Query query = mDB.query();
		query.constrain(Identity.class);
		query.constrain(OwnIdentity.class).not();
		query.descend("mLastFetchedDate").constrain(new Date(0));
		
		// TODO: Performance: Once we have a database table for the value of shouldFetchIdentity()
		// for each identity remove this loop and do everything in the database query.
		int count = 0;
		for(Identity identity : new Persistent.InitializingObjectSet<Identity>(this, query)) {
			if(shouldFetchIdentity(identity))
				++count;
		}
		return count;
	}

    public int getNumberOfFullScoreRecomputations() {
    	return mFullScoreRecomputationCount;
    }

	public synchronized double getAverageFullScoreRecomputationTime() {
		return (double) mFullScoreRecomputationMilliseconds
			/ (1000d * (mFullScoreRecomputationCount != 0 ? mFullScoreRecomputationCount : 1));
	}

	public int getNumberOfIncrementalScoreRecomputationDueToTrust() {
		return mIncrementalScoreRecomputationDueToTrustCount;
	}

	public int getNumberOfIncrementalScoreRecomputationDueToDistrust() {
		return mIncrementalScoreRecomputationDueToDistrustCount;
	}

	public synchronized double getAverageTimeForIncrementalScoreRecomputationDueToTrust() {
		return (double)mIncrementalScoreRecomputationDueToTrustNanos / 
			(1000d * 1000d * 1000d *
				(mIncrementalScoreRecomputationDueToTrustCount != 0
			  ?  mIncrementalScoreRecomputationDueToTrustCount : 1)
			);
	}

	public synchronized double getAverageTimeForIncrementalScoreRecomputationDueToDistrust() {
		return (double)mIncrementalScoreRecomputationDueToDistrustNanos / 
			(1000d * 1000d * 1000d *
				(mIncrementalScoreRecomputationDueToDistrustCount != 0
			  ?  mIncrementalScoreRecomputationDueToDistrustCount : 1)
			);
	}

    /**
     * Tests whether two WoT are equal.
     * This is a complex operation in terms of execution time and memory usage and only intended for being used in unit tests.
     */
	@Override
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
