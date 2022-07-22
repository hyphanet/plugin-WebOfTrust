/**
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */

package plugins.WebOfTrust;

import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static plugins.WebOfTrust.Configuration.IS_UNIT_TEST;

import java.io.InputStream;
import java.net.MalformedURLException;
import java.util.HashMap;

import plugins.WebOfTrust.Identity.FetchState;
import plugins.WebOfTrust.IdentityFileQueue.IdentityFileStream;
import plugins.WebOfTrust.exceptions.NotInTrustTreeException;
import plugins.WebOfTrust.exceptions.UnknownIdentityException;
import plugins.WebOfTrust.network.input.EditionHint;
import plugins.WebOfTrust.network.input.IdentityDownloader;
import plugins.WebOfTrust.network.input.IdentityDownloaderController;
import plugins.WebOfTrust.network.input.IdentityDownloaderFast;
import plugins.WebOfTrust.network.input.IdentityDownloaderSlow;
import plugins.WebOfTrust.util.Daemon;
import plugins.WebOfTrust.util.jobs.DelayedBackgroundJob;
import plugins.WebOfTrust.util.jobs.MockDelayedBackgroundJob;
import plugins.WebOfTrust.util.jobs.TickerDelayedBackgroundJob;

import com.db4o.ObjectSet;
import com.db4o.ext.ExtObjectContainer;
import com.db4o.query.Query;

import freenet.client.FetchContext;
import freenet.client.FetchResult;
import freenet.client.HighLevelSimpleClient;
import freenet.client.async.ClientContext;
import freenet.client.async.USKManager;
import freenet.client.async.USKRetriever;
import freenet.client.async.USKRetrieverCallback;
import freenet.keys.FreenetURI;
import freenet.keys.USK;
import freenet.node.PrioRunnable;
import freenet.node.RequestClient;
import freenet.node.RequestStarter;
import freenet.pluginmanager.PluginRespirator;
import freenet.support.Logger;
import freenet.support.PooledExecutor;
import freenet.support.PrioritizedTicker;
import freenet.support.Ticker;
import freenet.support.api.Bucket;
import freenet.support.io.Closer;
import freenet.support.io.NativeThread;

/**
 * Fetches Identities from Freenet.
 * Contains an ArrayList of all current requests.
 * 
 * <b>Synchronization:</b>
 * The locking order must be:
 * 	synchronized(instance of WebOfTrust) {
 *	synchronized(instance of IntroductionPuzzleStore) {
 *	synchronized(instance of IdentityDownloaderController (instead of IdentityFetcher.this!) ) {
 *	synchronized(Persistent.transactionLock(instance of ObjectContainer)) {
 *
 * ATTENTION: You must NOT synchronize upon IdentityFetcher.this! You must always synchronize
 * upon WoT's {@link IdentityDownloaderController} instead! This is because the IdentityFetcher is
 * NOT the main fetcher class of WoT upon which other subsystem synchronizes. The other subsystems
 * upon an instance of class {@link IdentityDownloaderController}. The same lock must be used
 * everywhere, so this class here must not synchronize upon itself.
 * 
 * TODO: Code quality: Rename to IdentityFileFetcher to match the naming of
 * {@link IdentityFileQueue} and {@link IdentityFileProcessor}. Notice that this needs to be done
 * together with db4o schema evolution since this class stores member classes inside db4o.
 * 
 * @author xor (xor@freenetproject.org), Julien Cornuwel (batosai@freenetproject.org)
 * @deprecated Use {@link IdentityDownloaderController} instead
 */
@Deprecated
public final class IdentityFetcher implements
		IdentityDownloader, Daemon, USKRetrieverCallback, PrioRunnable {
	
    /**
     * Will be used as delay for the {@link DelayedBackgroundJob} which schedules processing of
     * {@link IdentityFetcherCommand}s. */
	private static final long PROCESS_COMMANDS_DELAY =
		IS_UNIT_TEST ? SECONDS.toMillis(1) : MINUTES.toMillis(1);

	/**
	 * If true, the fetcher will not only fetch the latest editions of Identitys, but also old
	 * ones.
	 * Also, {@link IdentityFileDiskQueue} will disable its deduplication of multiple fetched
	 * {@link IdentityFile}s with the same edition. 
	 * Together with {@link IdentityFileDiskQueue}s ability of archiving all fetched files
	 * to disk, this can be used for debugging purposes. For example for testing changes to the
	 * {@link Score} computation algorithm, it is a good idea to import many {@link Trust} lists to
	 * have many changes to the Trust graph.
	 * 
	 * ATTENTION: If this is enabled, the seed identities will be created with an edition of 0.
	 * This will persist restarts, and thus also continue to persist even if you disable this flag
	 * again. Thus please only use this flag with throwaway databases. */
	public static final boolean DEBUG__NETWORK_DUMP_MODE = false;

	private final WebOfTrust mWoT;
	
	private final IdentityDownloaderController mLock;

	private final ExtObjectContainer mDB;
	
	private final USKManager mUSKManager;

	/** A reference to the HighLevelSimpleClient used to talk with the node */
	private final HighLevelSimpleClient mClient;
	private final ClientContext mClientContext;

	/** @see WebOfTrust#getRequestClient() */
	private final RequestClient mRequestClient;

	/** All current requests */
	/* TODO: We use those HashSets for checking whether we have already have a request for the given identity if someone calls fetch().
	 * This sucks: We always request ALL identities to allow ULPRs so we must assume that those HashSets will not fit into memory
	 * if the WoT becomes large. We should instead ask the node whether we already have a request for the given SSK URI. So how to do that??? */
	private final HashMap<String, USKRetriever> mRequests = new HashMap<String, USKRetriever>(128); /* TODO: profile & tweak */
	
    /**
     * The IdentityFetcher schedules execution of its command processing thread {@link #run()} on
     * this {@link DelayedBackgroundJob}.
     * The execution typically is scheduled after a delay of {@link #PROCESS_COMMANDS_DELAY}.
     * 
     * The value distinguishes the run state of this IdentityFetcher as follows:
     * - Until {@link #start()} was called, defaults to {@link MockDelayedBackgroundJob#DEFAULT}
     *   with {@link DelayedBackgroundJob#isTerminated()} == true.
     * - Once {@link #start()} has been called, becomes a
     *   {@link TickerDelayedBackgroundJob} with {@link DelayedBackgroundJob#isTerminated()}
     *   == false.
     * - Once {@link #stop()} has been called, stays a {@link TickerDelayedBackgroundJob} but has
     *   {@link DelayedBackgroundJob#isTerminated()} == true for ever.
     * 
     * There can be exactly one start() - stop() lifecycle, an IdentityFetcher cannot be recycled.
     * 
     * Concurrent write access to this variable by start() is guarded by {@link #mLock}.
     * Volatile since stop() needs to read it without synchronization.
     * 
     * {@link IdentityDownloaderFast#mDownloadSchedulerThread},
     * {@link IdentityDownloaderSlow#mDownloadSchedulerThread} and {@link SubscriptionManager#mJob}
     * are related to this, please apply changes there as well. */
    private volatile DelayedBackgroundJob mJob = MockDelayedBackgroundJob.DEFAULT;

    /** Fetched identity files are stored for processing at this {@link IdentityFileQueue}. */
    private final IdentityFileQueue mQueue;


	/* These booleans are used for preventing the construction of log-strings if logging is disabled (for saving some cpu cycles) */
	
	private static transient volatile boolean logDEBUG = false;
	private static transient volatile boolean logMINOR = false;
	
	static {
		Logger.registerClass(IdentityFetcher.class);
	}
	
	
	/**
	 * Creates a new IdentityFetcher. You must call {@link #start()} before expecting any commands to have an effect.
	 * 
	 * @param myWoT A reference to a {@link WebOfTrust}
	 */
	public IdentityFetcher(WebOfTrust myWoT, PluginRespirator respirator,
			IdentityFileQueue queue, IdentityDownloaderController controller) {
		mWoT = myWoT;
		mLock = controller;
		mQueue = queue;
		
		mDB = mWoT.getDatabase();
		
		if(respirator != null) { // We are connected to a node
			mUSKManager = respirator.getNode().clientCore.uskManager;
			mClient = respirator.getHLSimpleClient();
			mClientContext = respirator.getNode().clientCore.clientContext;
		} else {
			mUSKManager = null;
			mClient = null;
			mClientContext = null;
		}
		
		mRequestClient = mWoT.getRequestClient();
		
		if(DEBUG__NETWORK_DUMP_MODE) {
			Logger.warning(
				this, "IdentityFetcher.DEBUG__NETWORK_DUMP_MODE == true: Will fetch old editions "
					+ " of identities!");
		}
	}
	
	/**
	 * FIXME: This should have been abstract as we only ever create instances of the child classes.
	 * Not having to create a table for this in the database may be beneficial to d4bo.
	 * It's likely not a good idea to change this now that there are existing databases with this
	 * architecture already and class IdentityFetcher is legacy anyway. But what we should do is
	 * investigate whether this design pattern was also used in other classes and lacks abstract
	 * there as well.
	 * One example could be the member classes of class {@link SubscriptionManager}. Their instances
	 * are currently deleted from the database upon every restart so even if db4o has a problem
	 * with changing non-abstract classes to abstract ones then it maybe won't matter for those as
	 * the existing instances are deleted upon upgrade.
	 * In any case please test changes carefully for db4o issues! */
	@SuppressWarnings("serial")
	public static class IdentityFetcherCommand extends Persistent {
		
		@IndexedField
		private final String mIdentityID;
		
		protected IdentityFetcherCommand(String myIdentityID) {
			mIdentityID = myIdentityID;
		}
		
		protected final String getIdentityID() {
			checkedActivate(1); // String is a db4o primitive type so 1 is enough
			return mIdentityID;
		}

		/**
		 * @see #getIdentityID
		 */
		@Override
		public final String getID() {
			return getIdentityID();
		}
		
		@Override
		public void startupDatabaseIntegrityTest() throws Exception {
			checkedActivate(1); // int is a db4o primitive type so 1 is enough
			
			if(mIdentityID == null)
				throw new RuntimeException("mIdentityID==null");
			
			// TODO: Validate the ID
		}

	}
	
	@SuppressWarnings("serial")
	protected static final class StartFetchCommand extends IdentityFetcherCommand {

		protected StartFetchCommand(Identity identity) {
			super(identity.getID());
		}
		
		protected StartFetchCommand(String identityID) {
			super(identityID);
		}
		
	}
	
	@SuppressWarnings("serial")
	protected static final class AbortFetchCommand extends IdentityFetcherCommand {

		protected AbortFetchCommand(Identity identity) {
			super(identity.getID());
		}
		
	}
	
	@SuppressWarnings("serial")
	protected static final class UpdateEditionHintCommand extends IdentityFetcherCommand {

		protected UpdateEditionHintCommand(Identity identity) {
			super(identity.getID());
		}
		
		protected UpdateEditionHintCommand(String identityID) {
			super(identityID);
		}
		
	}
	
	private static final class NoSuchCommandException extends Exception {
		private static final long serialVersionUID = 1L;
	}
	
	private static final class DuplicateCommandException extends RuntimeException {
		private static final long serialVersionUID = 1L;
	}
	
	private IdentityFetcherCommand getCommand(Class<? extends IdentityFetcherCommand> commandType, Identity identity)
		throws NoSuchCommandException {
		
		return getCommand(commandType, identity.getID());
	}
	
	private IdentityFetcherCommand getCommand(final Class<? extends IdentityFetcherCommand> commandType, final String identityID)
		throws NoSuchCommandException {
		
		final Query q = mDB.query();
		q.constrain(commandType);
		q.descend("mIdentityID").constrain(identityID);
		final ObjectSet<IdentityFetcherCommand> result = new Persistent.InitializingObjectSet<IdentityFetcher.IdentityFetcherCommand>(mWoT, q);
		
		switch(result.size()) {
			case 1: return result.next();
			case 0: throw new NoSuchCommandException();
			default: throw new DuplicateCommandException();
		}
	}
	
	private ObjectSet<IdentityFetcherCommand> getCommands(final Class<? extends IdentityFetcherCommand> commandType) {
		final Query q = mDB.query();
		q.constrain(commandType);
		return new Persistent.InitializingObjectSet<IdentityFetcher.IdentityFetcherCommand>(mWoT, q);
	}

	@Override public final boolean getShouldFetchState(final Identity identity) {
		boolean abortFetchScheduled = false;
		try {
			getCommand(AbortFetchCommand.class, identity.getID());
			abortFetchScheduled = true;
		} catch(NoSuchCommandException e) {}
		
		boolean startFetchScheduled = false;
		try {
			getCommand(StartFetchCommand.class, identity.getID());
			startFetchScheduled = true;
		} catch(NoSuchCommandException e) {}
		
		if(abortFetchScheduled && startFetchScheduled) {
			assert(false);
			throw new IllegalStateException("Contradictory commands stored");
		}
		
		if(abortFetchScheduled) {
			// This assert() would currently fail since storeAbortFetchCommandWithoutCommit()
			// will currently store a command even if
			//     mRequests.containsKey(identity.getID()) == false.
			// See the TODO there.
			
			/* assert(mRequests.containsKey(identity.getID())) : "Command is useless"; */
			return false;
		}
		
		if(startFetchScheduled) {
			// Similar to the above: Current implementation of storeStartFetchCommandWithoutCommit()
			// would cause this to fail
			
			/* assert(!mRequests.containsKey(identity.getID())) : "Command is useless"; */
			return true;
		}
		
		return mRequests.containsKey(identity.getID());
	}

	@Override public void deleteAllCommands() {
		synchronized(mLock) {
		synchronized(Persistent.transactionLock(mDB)) {
			try {
				deleteAllCommandsWithoutCommit();
				Persistent.checkedCommit(mDB, this);
			}
			catch(RuntimeException e) {
				Persistent.checkedRollbackAndThrow(mDB, this, e);
			}
		}
		}
	}

	void deleteAllCommandsWithoutCommit() {
		if(logDEBUG) Logger.debug(this, "Deleting all identity fetcher commands ...");
		
		int amount = 0;
		
		for(IdentityFetcherCommand command : getCommands(IdentityFetcherCommand.class)) {
			command.deleteWithoutCommit();
			++amount;
		}
		
		if(logDEBUG) Logger.debug(this, "Deleted " + amount + " commands.");
	}

    /**
     * Synchronization:<br>
     * This function does neither lock the database nor commit the transaction. You have to surround
     * it with:<br><code>
     * synchronized(instance of WebOfTrust) {
     * synchronized(instance of IdentityDownloaderController) {
     * synchronized(Persistent.transactionLock(mDB)) {
     *     try { ... storeStartFetchCommandWithoutCommit(id); Persistent.checkedCommit(mDB, this); }
     *     catch(RuntimeException e) { Persistent.checkedRollbackAndThrow(mDB, this, e); }
     * }}}
     * </code>
     */
	public void storeStartFetchCommandWithoutCommit(Identity identity) {
		storeStartFetchCommandWithoutCommit(identity.getID());
	}

    /**
     * Synchronization:<br>
     * This function does neither lock the database nor commit the transaction. You have to surround
     * it with:<br><code>
     * synchronized(instance of IdentityDownloaderController) {
     * synchronized(Persistent.transactionLock(mDB)) {
     *     try { ... storeStartFetchCommandWithoutCommit(id); Persistent.checkedCommit(mDB, this); }
     *     catch(RuntimeException e) { Persistent.checkedRollbackAndThrow(mDB, this, e); }
     * }}
     * </code>
     */
	public void storeStartFetchCommandWithoutCommit(String identityID) {
		if(logDEBUG) Logger.debug(this, "Start fetch command received for " + identityID);
		
		try {
			getCommand(AbortFetchCommand.class, identityID).deleteWithoutCommit();
			if(logDEBUG) Logger.debug(this, "Deleting abort fetch command for " + identityID);
		}
		catch(NoSuchCommandException e) { }
		
		try {
			getCommand(StartFetchCommand.class, identityID);
			if(logDEBUG) Logger.debug(this, "Start fetch command already in queue!");
		}
		catch(NoSuchCommandException e) {
			final StartFetchCommand cmd = new StartFetchCommand(identityID);
			cmd.initializeTransient(mWoT);
			cmd.storeWithoutCommit();
			scheduleCommandProcessing();
		}
	}

    /**
     * Synchronization:<br>
     * This function does neither lock the database nor commit the transaction. You have to surround
     * it with:<br><code>
     * synchronized(instance of WebOfTrust) {
     * synchronized(instance of IdentityDownloaderController) {
     * synchronized(Persistent.transactionLock(mDB)) {
     *     try { ... storeAbortFetchCommandWithoutCommit(id); Persistent.checkedCommit(mDB, this); }
     *     catch(RuntimeException e) { Persistent.checkedRollbackAndThrow(mDB, this, e); }
     * }}}
     * </code>
     * 
     * <br><br>TODO: Performance: The backend database query doesn't require the identity, it merely
     * needs its ID. Thus, make this function only consume the ID & adapt the callers.
     */
	public void storeAbortFetchCommandWithoutCommit(Identity identity) {
		if(logDEBUG) Logger.debug(this, "Abort fetch command received for " + identity);
		
		try {
			getCommand(StartFetchCommand.class, identity).deleteWithoutCommit();
			if(logDEBUG) Logger.debug(this, "Deleting start fetch command for " + identity);
			
			// TODO: Performance: The following assert failed randomly, especially when using
			// WebOfTrust.deleteOwnIdentity() via the web interface. I currently cannot reproduce
			// it and need to get the release done. As a workaround, we proceed to do store an
			// AbortFetchCommand here instead of doing nothing and returning. This will make sure
			// that a possibly currently running fetch for the identity always gets terminated as
			// desired; at the cost of maybe storing useless AbortFetchCommands because there
			// actually is no fetch running. The code which processes the commands will not break
			// in that case, it will log an error.
			// A possible explanation for the failure is the following sequence of events:
			// 1. The identity has a fetch running
			// 2. storeAbortFetchCommandWithoutCommit() is called and stores an AbortFetchCommand
			// 3. storeStartFetchCommandWithoutCommit() is called which deletes the
			//    AbortFetchCommand AND stores a StartFetchCommand. As a result, a StartFetchCommand
			//    is pending even though we are already fetching the identity. Thus the assert here
			//    fails.
			// Notice: This is also documented at https://bugs.freenetproject.org/view.php?id=6468
			// Notice: When fixing this to return here, also enable the commented out assert()
			// in getShouldFetchState(). Also deal with the other assert() in that function and
			// the cause of it being commented out in storeStartFetchCommandWithoutCommit().
			// EDIT 2017-07-02:
			// Another valid reason (and the most valid) for a StartFetchCommand being enqueued
			// even though we're fetching an Identity already could be the purpose of
			// Identity.markForRefetch() - if markForRetch() was done the IdentityFetcher is
			// signaled by calling its storeStartFetchCommandWithoutCommit().
			// Thus this whole assert is potentially invalid and should be removed, probably along
			// with the above bugtracker entry.
			// Overall we should probably invent a more explicit mechanism of signalling
			// markForRefetch() to the fetcher, e.g. having a separate callback
			// "storeMarkForRefetchCommand...()".
			/*
			assert(mRequests.get(identity.getID()) == null)
			    : "We have not yet processed the StartFetchCommand for the identity, so there "
			    + "should not be a request for it. ID: " + identity.getID();
			
			// There shouldn't be a request to abort so we don't store an AbortFetchCommand.
			return;
			*/
		}
		catch(NoSuchCommandException e) { }
		
		try {
			getCommand(AbortFetchCommand.class, identity);
			if(logDEBUG) Logger.debug(this, "Abort fetch command already in queue!");
		}
		catch(NoSuchCommandException e) {
			final AbortFetchCommand cmd = new AbortFetchCommand(identity);
			cmd.initializeTransient(mWoT);
			cmd.storeWithoutCommit();
			scheduleCommandProcessing();
		}
	}

	@Override public void storePreDeleteOwnIdentityCommand(OwnIdentity oldIdentity) {
		for(Score s : mWoT.getGivenScores(oldIdentity)) {
			if(mWoT.shouldMaybeFetchIdentity(s)) {
				// The trustee could possibly have been eligible for download solely due to having
				// received a positive Trust chain from the OwnIdentity.
				// As it isn't an OwnIdentity anymore the Trust chain isn't a justification for
				// downloading it anymore. So we need to check whether another Trust chain
				// originating from a different OwnIdentity justifies to keep fetching it, and if
				// not abort the fetch.
				// The proper way to do so is to check whether a Score from another OwnIdentity
				// exists which asks us to fetch the Identity: The purpose of Scores is to reflect
				// the "is this Identity trustworthy enough to fetch?" decision of an
				// OwnIdentity. If a single OwnIdentity asks us to fetch the remote Identity by that
				// then we need to keep fetching it.
				
				Identity trustee = s.getTrustee();
				boolean keepDownloadingTrustee = false;
				
				for(Score otherScore : mWoT.getScores(trustee)) {
					if(otherScore == s)
						continue;
					
					if(mWoT.shouldMaybeFetchIdentity(otherScore)) {
						keepDownloadingTrustee = true;
						break;
					}
				}
				
				if(!keepDownloadingTrustee)
					storeAbortFetchCommandWithoutCommit(trustee);
			}
		}
		
		// Given that the oldIdentity was an OwnIdentity it was probably eligible for fetching
		// for the purpose of WebOfTrust.restoreOwnIdentity(). Thus we new to abort a potentially
		// pre-existing fetch.
		storeAbortFetchCommandWithoutCommit(oldIdentity);
	}

	@Override public void storePostDeleteOwnIdentityCommand(Identity newIdentity) {
		// The replacement Identity may still be eligible for download if another OwnIdentity trusts
		// it.
		if(mWoT.shouldFetchIdentity(newIdentity))
			storeStartFetchCommandWithoutCommit(newIdentity);
	}

	@Override public void storePreDeleteIdentityCommand(Identity oldIdentity) {
		if(oldIdentity instanceof OwnIdentity)
			storePreDeleteOwnIdentityCommand((OwnIdentity)oldIdentity);
		else
			storeAbortFetchCommandWithoutCommit(oldIdentity);
	}

	@Override public void storePreRestoreOwnIdentityCommand(Identity oldIdentity) {
		// With regards to our duty of deleting object references to the oldIdentity from the
		// database the commented out code is not necessary as AbortFetchCommand doesn't contain
		// any, it only stores the ID String.
		// We can also keep pre-existing downloads running as we want to keep fetching the Identity
		// anyway because once it became an OwnIdentity it is supposed to be fetched for restoring.
		// There is also no need to delete a potentially pre-existing AbortFetchCommand here:
		// storePostRestoreOwnIdentityCommand() will use storeStartFetchCommandWithoutCommit(), and
		// that function will delete the pre-existing command.
		
		/* storeAbortFetchCommandWithoutCommit(oldIdentity); */
	}

	@Override public void storePostRestoreOwnIdentityCommand(OwnIdentity newIdentity) {
		// Will also delete pre-existing AbortFetchCommands, which is necessary because
		// storePreRestoreOwnIdentityCommand() doesn't deal with that.
		//
		// If the download is already running the command will cause the download to be restarted.
		// This fulfills our duty of dealing with a user-supplied edition hint as returned by
		// newIdentity.getNextEditionToFetch().
		storeStartFetchCommandWithoutCommit(newIdentity);
	}

	/** This callback is not used by this class. */
	@Override public void storeTrustChangedCommandWithoutCommit(Trust oldTrust, Trust newTrust) {
	}

    /**
     * Synchronization:<br>
     * This function does neither lock the database nor commit the transaction. You have to surround
     * it with:<br><code>
     * synchronized(instance of WebofTrust) {
     * synchronized(instance of IdentityDownloaderController) {
     * synchronized(Persistent.transactionLock(mDB)) {
     *     try { ... storeNewEditionHintWithoutCommit(id); ... 
     *               Persistent.checkedCommit(mDB, this); }
     *     catch(RuntimeException e) { Persistent.checkedRollbackAndThrow(mDB, this, e); }
     * }}}
     * </code>
     */
	public void storeNewEditionHintCommandWithoutCommit(EditionHint hint) {
		Identity fromIdentity = hint.getSourceIdentity();
		Identity toIdentity = hint.getTargetIdentity();
		
		// XMLTransformer will nowadays pass edition hints if the giver of the hint has a positive
		// Score OR positive capacity (or more precisely: always if the giver is eligible for
		// download, which is the case with Score >= 0 or capacity > 0. Notice that the MIN_CAPACITY
		// variable it checks w.r.t. the hints is set to 0 instead of 1 if the legacy
		// IdentityFetcher is enabled, thus allowing identities which merely have a positive Score).
		// Before this class here was deprecated in favor of the new IdentityDownloader
		// implementations, XMLTransformer used to only pass edition hints if the giver had a
		// positive Score - NOT if it merely had a positive capacity.
		// To ensure this old implementation here keeps its behavior as of before the adaption of 
		// XMLTransformer to the IdentityDownloader rewrites, it will now check whether the giver of
		// the hint has a positive Score and return if it does not.
		// Also, XMLTransformer nowadays does not check the edition of the hints anymore, it passes
		// not just the ones which are higher than the highest previously known hint, but all of
		// them. So we also check that to discard non-higher ones.
		// In other words: The checks in the following scope have been part of XMLTransformer
		// previously and were moved here as part of its modifications for IdentityDownloader.
		{
			boolean fromPositiveScore = false;
			
			if(fromIdentity instanceof OwnIdentity) {
				// Importing of OwnIdentities is always allowed
				fromPositiveScore = true;
			} else {
				try {
					fromPositiveScore = mWoT.getBestScore(fromIdentity) > 0;
				}
				catch(NotInTrustTreeException e) { }
			}
			
			// FIXME: Do a test run to ensure this doesn't always wrongly return, it should still
			// accept many hints.
			
			if(!fromPositiveScore)
				return;
			
			// This class only deals with the highest known hint, so if the one we just got isn't
			// higher than the previous one we return.
			// This is attackable by publishing wrongly too high hints - but it's what the class has
			// always been like, and remember:
			// The goal here is to preserve the original legacy functionality.
			if(!toIdentity.setNewEditionHint(hint.getEdition()))
				return;
			
			toIdentity.storeWithoutCommit();
			
			// We don't notify clients about this: The edition hint is not very useful to them.
			// mSubscriptionManager.storeIdentityChangedNotificationWithoutCommit(trustee, trustee);
		}
		
		
		if(logDEBUG) {
			Logger.debug(
				this, "Update edition hint command received for hint: " + hint);
		}
		
		try {
			getCommand(AbortFetchCommand.class, toIdentity.getID());
			Logger.error(this, "Update edition hint command is useless, an abort fetch command is queued!");
		}
		catch(NoSuchCommandException e1) {
			try {
				getCommand(UpdateEditionHintCommand.class, toIdentity.getID());
				if(logDEBUG) Logger.debug(this, "Update edition hint command already in queue!");
			}
			catch(NoSuchCommandException e2) {
				final UpdateEditionHintCommand cmd
					= new UpdateEditionHintCommand(toIdentity.getID());
				cmd.initializeTransient(mWoT);
				cmd.storeWithoutCommit();
				scheduleCommandProcessing();
			}
		}
	}
	
	private void scheduleCommandProcessing() {
	    // We do not do the following commented out assert() because:
	    // 1) WebOfTrust.upgradeDB() can rightfully cause this function to be called before start()
	    //    (in case it calls functions which create commands):
	    //    upgradeDB()'s job is to update outdated databases to a new database format. No
	    //    subsystem of WOT which accesses the database should be start()ed before the database
	    //    has been converted to the current format, including the IdentityFetcher.
	    // 2) It doesn't matter if we don't process commands which were created before start():
	    //    start() will delete all old commands anyway and compute the set of identities to be
	    //    fetched from scratch.
	    //
	    /* assert(mJob != MockDelayedBackgroundJob.DEFAULT)
            : "Should not be called before start() as mJob won't execute then!"; */
        
        // We do not do this because some unit tests intentionally stop() us before they run. 
        /*  assert (!mJob.isTerminated()) : "Should not be called after stop()"; */
        
        mJob.triggerExecution();
	}

	@Override public void scheduleImmediateCommandProcessing() {
		mJob.triggerExecution(0);
	}

	@Override
	public int getPriority() {
		return NativeThread.LOW_PRIORITY;
	}

	/** ATTENTION: For internal use only! TODO: Code quality: Wrap in a private class to hide it. */
	@Override
	public void run() {
	    final Thread thread = Thread.currentThread();

		synchronized(mWoT) { // Lock needed because we do getIdentityByID() in fetch()
		synchronized(mLock) {
		synchronized(Persistent.transactionLock(mDB)) {
			try  {
				if(logDEBUG) Logger.debug(this, "Processing identity fetcher commands ...");
				
				for(IdentityFetcherCommand command : getCommands(AbortFetchCommand.class)) {
					try {
						abortFetch(command.getIdentityID());
						command.deleteWithoutCommit();
					} catch(Exception e) {
						Logger.error(this, "Aborting fetch failed", e);
					}
					
					if(thread.isInterrupted())
					    break;
				}
				
				for(IdentityFetcherCommand command : getCommands(StartFetchCommand.class)) {
					try {
						fetch(command.getIdentityID());
						command.deleteWithoutCommit();
					} catch (Exception e) {
						Logger.error(this, "Fetching identity failed", e);
					}
					
                    if(thread.isInterrupted())
                        break;
				}
				
				for(IdentityFetcherCommand command : getCommands(UpdateEditionHintCommand.class)) {
					try {
						editionHintUpdated(command.getIdentityID());
						command.deleteWithoutCommit();
					} catch (Exception e) { 
						Logger.error(this, "Updating edition hint failed", e);
					}
					
                    if(thread.isInterrupted())
                        break;
				}
				
				// isInterrupted() does not clear the interruption flag, so we do the logging here
				// instead of duplicating it at each of the above "if(thread.isInterrupted())"
				if(thread.isInterrupted())
				    Logger.normal(this, "Shutdown requested, aborting command processing...");
				
				if(logDEBUG) Logger.debug(this, "Processing finished.");
				
				Persistent.checkedCommit(mDB, this);
			} catch(RuntimeException e) {
				Persistent.checkedRollback(mDB, this, e);
			}
		}
		}
		}
	}
	
	private void fetch(String identityID) throws Exception {
		try {
			synchronized(mWoT) {
				Identity identity = mWoT.getIdentityByID(identityID);
				fetch(identity);
			}
		} catch (UnknownIdentityException e) {
			Logger.normal(this, "Fetching identity failed, it was deleted already.", e);
		}
	}

	/**
	 * DO ONLY USE THIS METHOD AT STARTUP OF WOT. Use {@link #storeStartFetchCommandWithoutCommit(String)} everywhere else.
	 * 
	 * Fetches an identity from Freenet, using the current edition number and edition hint stored in the identity.
	 * If the identity is already being fetched, passes the current edition hint stored in the identity to the USKManager.
	 * 
	 * If there is already a request for a newer USK, the request is cancelled and a fetch for the given older USK is started.
	 * This has to be done so that trust lists of identities can be re-fetched as soon as their score changes from negative to positive - that is necessary
	 * because we do not import identities from trust lists for which the owner has a negative score.
	 * 
	 * @param identity the Identity to fetch
	 */
	private void fetch(Identity identity) throws Exception {
		synchronized(mLock) {
			USKRetriever retriever = mRequests.get(identity.getID());

			USK usk = USK.create(identity.getRequestURI().setSuggestedEdition(
				identity.getNextEditionToFetch()));
			
			if(identity.getCurrentEditionFetchState() == FetchState.NotFetched) {
				if(retriever != null) {
					// The identity has a new "mandatory" edition number stored which we must fetch, so we restart the request because the edition number might
					// be lower than the last one which the USKRetriever has fetched.
					if(logMINOR) Logger.minor(this, "The current edition of the given identity is marked as not fetched, re-creating the USKRetriever for " + usk);
					abortFetch(identity.getID());
					retriever = null;
				}
			}

			if(retriever == null)
				mRequests.put(identity.getID(), fetch(usk));

			if(!DEBUG__NETWORK_DUMP_MODE)
				mUSKManager.hintUpdate(usk, identity.getLatestEditionHint(), mClientContext);
		}
	}
	
	/**
	 * Has to be called when the edition hint of the given identity was updated. Tells the USKManager about the new hint.
	 * 
	 * You have to synchronize on the WebOfTrust and then on mLock before calling this function!
	 * 
	 * @throws Exception 
	 */
	private void editionHintUpdated(String identityID) throws Exception {
		try {
			Identity identity = mWoT.getIdentityByID(identityID);
			if(mRequests.get(identity.getID()) == null)
				throw new UnknownIdentityException("updateEdtitionHint() called for an identity which is not being fetched: " + identityID);

			USK usk = USK.create(identity.getRequestURI().setSuggestedEdition(
				identity.getNextEditionToFetch()));

			long editionHint = identity.getLatestEditionHint();

			if(logDEBUG) Logger.debug(this, "Updating edition hint to " + editionHint + " for " + identityID);

			if(!DEBUG__NETWORK_DUMP_MODE)
				mUSKManager.hintUpdate(usk, identity.getLatestEditionHint(), mClientContext);
		} catch (UnknownIdentityException e) {
			Logger.normal(this, "Updating edition hint failed, the identity was deleted already.", e);
		}
	}
	
	private void abortFetch(String identityID) {
		synchronized(mLock) {
			USKRetriever retriever = mRequests.remove(identityID);
	
			if(retriever == null) {
				Logger.error(
					this, "Aborting fetch failed (no fetch found) for identity " + identityID);
				return;
			}
				
			if(logDEBUG) Logger.debug(this, "Aborting fetch for identity " + identityID);
			retriever.cancel(mClientContext);
			mUSKManager.unsubscribeContent(retriever.getOriginalUSK(), retriever, true);
		}
	}
	
	/**
	 * Fetches the given USK and returns the new USKRetriever. Does not check whether there is already a fetch for that USK.
	 */
	private USKRetriever fetch(USK usk) throws MalformedURLException {
		if(mUSKManager == null) {
			Logger.warning(this, "mUSKManager==null, not fetching anything! Only valid in tests!");
			return null;
		}
		
		boolean fetchLatestOnly = !DEBUG__NETWORK_DUMP_MODE;
		
		FetchContext fetchContext = mClient.getFetchContext();
		fetchContext.maxArchiveLevels = 0; // Because archives can become huge and WOT does not use them, we should disallow them. See JavaDoc of the variable.
		fetchContext.maxSplitfileBlockRetries = -1; // retry forever
		fetchContext.maxNonSplitfileRetries = -1; // retry forever
		fetchContext.maxOutputLength = XMLTransformer.MAX_IDENTITY_XML_BYTE_SIZE;
		fetchContext.ignoreUSKDatehints = !fetchLatestOnly;
		if(logDEBUG) Logger.debug(this, "Trying to start fetching uri " + usk); 
		
		if(fetchLatestOnly)
			return mUSKManager.subscribeContent(usk, this, true, fetchContext, RequestStarter.UPDATE_PRIORITY_CLASS, mRequestClient);
		else {
			// There is no version of subscribeContent() which supports disabling using a
			// USKSparseProxyCallback, so we manually do what suscribeContent() does except for
			// using a sparse proxy.
			// FIXME: Code quality: File a fred pull request which adds such a subscribeContent()
			USKRetriever ret = new USKRetriever(
				fetchContext, RequestStarter.UPDATE_PRIORITY_CLASS, mRequestClient, this, usk);
			mUSKManager.subscribe(usk, ret, true, fetchContext.ignoreUSKDatehints, mRequestClient);
			return ret;
		}
	}
	
	@Override
	public short getPollingPriorityNormal() {
		return RequestStarter.UPDATE_PRIORITY_CLASS;
	}

	@Override
	public short getPollingPriorityProgress() {
		return RequestStarter.IMMEDIATE_SPLITFILE_PRIORITY_CLASS;
	}

	
	/**
	 * Deletes all existing commands using {@link #deleteAllCommands()}. Enables usage of {@link #scheduleCommandProcessing()}.
	 * 
	 * ATTENTION: Does NOT enable {@link #scheduleCommandProcessing()} in unit tests - you must
	 * manually trigger command processing by calling {@link #run()} there.
	 * 
	 * {@link SubscriptionManager#start()} and {@link IdentityDownloaderSlow#start()} are related to
	 * this, please apply changes there as well. */
	@Override public void start() {
        Logger.normal(this, "start()...");
        
        synchronized (mWoT) {
        synchronized (mLock) {

         // This is thread-safe guard against concurrent multiple calls to start() / stop() since
         // stop() does not modify the job and start() is synchronized(mLock). 
         if(mJob != MockDelayedBackgroundJob.DEFAULT)
             throw new IllegalStateException("start() was already called!");

         // This must be called while synchronized on mLock, and the lock must be
         // held until mJob is set:
         // Holding the lock prevents IdentityFetcherCommands from being created before
         // scheduleCommandProcessing() is made functioning by setting mJob.
         // It is critically necessary for scheduleCommandProcessing() to be working before
         // any commands can be created: Commands will only be processed if
         // scheduleCommandProcessing() is functioning at the moment a command is created.
		deleteAllCommands();

        Logger.normal(this, "Starting fetches of all identities...");
        for(Identity identity : mWoT.getAllIdentities()) {
            if(mWoT.shouldFetchIdentity(identity)) {
                try {
                    fetch(identity);
                }
                catch(Exception e) {
                    Logger.error(this, "Fetching identity failed!", e);
                }
            }
        }


        PluginRespirator respirator = mWoT.getPluginRespirator();
        Ticker ticker;
        Runnable jobRunnable;
        
        if(respirator != null) { // We are connected to a node
            ticker = respirator.getNode().getTicker();
            jobRunnable = this;
        } else { // We are inside of a unit test
            Logger.warning(this, "No PluginRespirator available, will never run job. "
                               + "This should only happen in unit tests!");
            
            // Generate our own Ticker so we can set mJob to be a real TickerDelayedBackgroundJob.
            // This is better than leaving it be a MockDelayedBackgroundJob because it allows us to
            // clearly distinguish the run state (start() not called, start() called, stop() called)
            // by checking whether mJob is at the default or not, and if not checking the run state
            // of it.
            ticker = new PrioritizedTicker(new PooledExecutor(), 0);
            jobRunnable = new Runnable() { @Override public void run() {
                    // Do nothing because:
                    // - We shouldn't do work on custom executors, we should only ever use the main
                    //   one of the node.
                    // - Unit tests execute instantly after loading the WoT plugin, so delayed jobs
                    //   should not happen since their timing cannot be guaranteed to match the unit
                    //   tests execution state.
                  };
            };
        }
        
        // Set the volatile mJob after everything which stop() must cleanup is initialized to ensure
        // that stop() can use the variable (without synchronization) to check whether cleanup will
        // cover everything.
        mJob = new TickerDelayedBackgroundJob(
            jobRunnable, "WoT IdentityFetcher", PROCESS_COMMANDS_DELAY, ticker);
        
        } // synchronized(mLock)
        } // synchronized(mWoT)

        Logger.normal(this, "start() finished.");
	}
	
	/**
	 * Stops all running requests.
	 * {@link SubscriptionManager#stop()} and {@link IdentityDownloaderSlow#stop()} are related to
	 * this, please apply changes there as well. */
	protected void stop() {
        Logger.normal(this, "stop()...");

		if(logDEBUG) Logger.debug(this, "Trying to stop all requests");
		
		// The following code intentionally does NOT write to the mJob variable so it does not have
		// to use synchronized(mLock). We do not want to synchronize because:
		// 1) run() is synchronized(mLock), so we would not get the lock until run() is finished.
		//    But we want to call mJob.terminate() immediately while run() is still executing to
		//    make it call Thread.interrupt() upon run() to speed up its termination. So we
		//    shouldn't require acquisition of the lock before mJob.terminate().
		// 2) Keeping mJob as is makes sure that start() is not possible anymore so this object can
		//    only have a single lifecycle. Recycling needs to be impossible: If we allowed
		//    restarting, the cleanup of the USKRetrievers at the end of this function could damage
		//    the new lifecycle because its synchronization block does not include mJob.terminate()
		//    and thus it would not be possible to guarantee that we kill the USKRetrievers of the
		//    same cycle 
		
		
		// Since mJob can only transition from not "not started yet", as implied by the "==" here,
		// to "started" as implied by "!=", but never backwards, is volatile, and is set by start()
		// *after* everything is initialized, this is safe against concurrent start() / stop().
		if(mJob == MockDelayedBackgroundJob.DEFAULT)
			throw new IllegalStateException("start() not called/finished yet!");
		
		// We cannot guard against concurrent stop() here since we don't synchronize, we can only
		// probabilistically detect it by assert(). Concurrent stop() is not a problem though since
		// restarting jobs is not possible: We cannot run into a situation where we accidentally
		// stop the wrong lifecycle. It can only happen that we do cleanup the cleanup which a
		// different thread would have done, but they won't care since all actions below will
		// succeed silently if done multiple times.
		assert !mJob.isTerminated() : "stop() called already";
		
		mJob.terminate();
        try {
            // We must wait without timeout since we need to cancel our requests at the core of
            // Freenet (see below synchronized(mLock)) and the job thread might create requests
            // until it is terminated.
            mJob.waitForTermination(Long.MAX_VALUE);
        } catch (InterruptedException e) {
            // We are a shutdown function, there is no sense in sending a shutdown signal to us.
            Logger.error(this, "stop() should not be interrupt()ed.", e);
        }
        
        // We are safe now to terminate all existing Freenet requests since no new ones can be
        // created anymore:
        // - only start() and run() do so.
        // - start() is not possible anymore
        // - run() can only be executed by mJob, and it will not do so after waitForTermination().
        // 
        // Nevertheless, all access to mRequests needs to be guarded by synchronized(mLock):
        // - It is also accessed by our Freenet callback handlers which might be called by fred at
        //   arbitrary points in time.
        // - stop() can be called multiple times in parallel.
		synchronized(mLock) {
		USKRetriever[] retrievers = mRequests.values().toArray(new USKRetriever[mRequests.size()]);		
		int counter = 0;		 
		for(USKRetriever r : retrievers) {
			if(r == null) {
				// fetch(USK) returns null in tests.
				continue;
			}
			r.cancel(mClientContext);
			mUSKManager.unsubscribeContent(r.getOriginalUSK(), r, true);
			 ++counter;
		}
		mRequests.clear();
		
		if(logDEBUG) Logger.debug(this, "Stopped " + counter + " current requests");
		}
		
        Logger.normal(this, "stop() finished.");
	}

	@Override public void terminate() {
		// terminate() is merely a wrapper around stop() in preparation of the TODO at
		// Daemon.terminate() which requests renaming it to stop().
		stop();
	}

	/**
	 * Called when an identity is successfully fetched.
	 */
	@Override
	public void onFound(USK origUSK, long edition, FetchResult result) {
		final FreenetURI realURI = origUSK.getURI().setSuggestedEdition(edition);
		
		if(logDEBUG) Logger.debug(this, "Fetched identity: " + realURI);
		
		Bucket bucket = null;
		InputStream inputStream = null;
		
		try {
			bucket = result.asBucket();
			inputStream = bucket.getInputStream();
			
			// Thanks to IdentityFileQueue we do NOT have to take mLock while consuming the
			// InputStream:
			// The queue exists to allow delaying import of the IdentityFileStream into the db4o
			// database by serializing it to disk and having the separate thread of
			// IdentityFileProcessor do the importing.
			// This was implemented because download of IdentityFiles is usually faster than
			// their processing and happens in parallel, so possibly many threads would call this 
			// function here concurrently - but processing requires mLock so hundreds of threads
			// would stall here if we did the processing in this function already.
			//
			// IdentityFileStream currently does not need to be close()d so we don't store it
			// FIXME: It is very unsafe to assume the stream implementation won't be changed to
			// need closure, just close it here anyway.
			mQueue.add(new IdentityFileStream(realURI, inputStream));
		}
		catch(Exception e) {
			Logger.error(this, "Queueing identity XML failed: " + realURI, e);
		}
		finally {
			Closer.close(inputStream);
			Closer.close(bucket);
		}
	}

	@Override public void onNewEditionImported(Identity identity) {
		// This callback is intentionally unused because it was added after the class was already
		// deprecated.
	}

}
