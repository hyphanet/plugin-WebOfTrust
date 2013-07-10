/**
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */

package plugins.WebOfTrust;

import java.io.InputStream;
import java.net.MalformedURLException;
import java.util.HashMap;

import plugins.WebOfTrust.Identity.FetchState;
import plugins.WebOfTrust.exceptions.UnknownIdentityException;

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
import freenet.support.CurrentTimeUTC;
import freenet.support.Logger;
import freenet.support.TrivialTicker;
import freenet.support.api.Bucket;
import freenet.support.io.Closer;
import freenet.support.io.NativeThread;

/**
 * Fetches Identities from Freenet.
 * Contains an ArrayList of all current requests.
 * 
 * TODO: There is some synchronization missing for the fetcher in some places where fetcher commands are issued.
 * It still works because those places are typically synchronized on the WoT anyway. We should fix it nevertheless.
 * Maybe we just might want to get rid of synchronization on the fetcher for storing commands... I will have to investigate.
 * 
 * @author xor (xor@freenetproject.org), Julien Cornuwel (batosai@freenetproject.org)
 */
public final class IdentityFetcher implements USKRetrieverCallback, PrioRunnable {
	
	private static final long PROCESS_COMMANDS_DELAY = 60 * 1000;
	
	private final WebOfTrust mWoT;
	
	private final ExtObjectContainer mDB;
	
	private final USKManager mUSKManager;

	/** A reference to the HighLevelSimpleClient used to talk with the node */
	private final HighLevelSimpleClient mClient;
	private final ClientContext mClientContext;
	private final RequestClient mRequestClient;

	/** All current requests */
	/* TODO: We use those HashSets for checking whether we have already have a request for the given identity if someone calls fetch().
	 * This sucks: We always request ALL identities to allow ULPRs so we must assume that those HashSets will not fit into memory
	 * if the WoT becomes large. We should instead ask the node whether we already have a request for the given SSK URI. So how to do that??? */
	private final HashMap<String, USKRetriever> mRequests = new HashMap<String, USKRetriever>(128); /* TODO: profile & tweak */
	
	private final TrivialTicker mTicker;
	
	/* Statistics */
	
	/**
	 * The value of CurrentTimeUTC.getInMillis() when this IdentityFetcher was created.
	 */
	private final long mStartupTimeMilliseconds;
	
	/**
	 * The number of identity XML files which this IdentityFetcher has fetched.
	 */
	private int mFetchedCount = 0;
	
	/**
	 * The total time in milliseconds which processing of all fetched identity XML files took.
	 */
	private long mIdentityImportNanoseconds = 0;
	
	/* These booleans are used for preventing the construction of log-strings if logging is disabled (for saving some cpu cycles) */
	
	private static transient volatile boolean logDEBUG = false;
	private static transient volatile boolean logMINOR = false;
	
	static {
		Logger.registerClass(IdentityFetcher.class);
	}
	
	
	/**
	 * Creates a new IdentityFetcher.
	 * 
	 * @param myWoT A reference to a {@link WebOfTrust}
	 */
	protected IdentityFetcher(WebOfTrust myWoT, PluginRespirator respirator) {
		mWoT = myWoT;
		
		mDB = mWoT.getDatabase();
		
		if(respirator != null) { // We are connected to a node
		mUSKManager = respirator.getNode().clientCore.uskManager;
		mClient = respirator.getHLSimpleClient();
		mClientContext = respirator.getNode().clientCore.clientContext;
		mTicker = new TrivialTicker(respirator.getNode().executor);
		} else {
			mUSKManager = null;
			mClient = null;
			mClientContext = null;
			mTicker = null;
		}
		
		mRequestClient = mWoT.getRequestClient();
		
		mStartupTimeMilliseconds = CurrentTimeUTC.getInMillis();
		
		deleteAllCommands();
	}
	
	public static class IdentityFetcherCommand extends Persistent {
		
		@IndexedField
		private final String mIdentityID;
		
		protected IdentityFetcherCommand(String myIdentityID) {
			mIdentityID = myIdentityID;
		}
		
		protected String getIdentityID() {
			checkedActivate(1); // String is a db4o primitive type so 1 is enough
			return mIdentityID;
		}

		@Override
		public void startupDatabaseIntegrityTest() throws Exception {
			checkedActivate(1); // int is a db4o primitive type so 1 is enough
			
			if(mIdentityID == null)
				throw new RuntimeException("mIdentityID==null");
			
			// TODO: Validate the ID
		}

	}
	
	protected static final class StartFetchCommand extends IdentityFetcherCommand {

		protected StartFetchCommand(Identity identity) {
			super(identity.getID());
		}
		
		protected StartFetchCommand(String identityID) {
			super(identityID);
		}
		
	}
	
	protected static final class AbortFetchCommand extends IdentityFetcherCommand {

		protected AbortFetchCommand(Identity identity) {
			super(identity.getID());
		}
		
	}
	
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
	
	private synchronized void deleteAllCommands() {
		synchronized(Persistent.transactionLock(mDB)) {
			try {
				if(logDEBUG) Logger.debug(this, "Deleting all identity fetcher commands ...");
				
				int amount = 0;
				
				for(IdentityFetcherCommand command : getCommands(IdentityFetcherCommand.class)) {
					command.deleteWithoutCommit();
					++amount;
				}
				
				if(logDEBUG) Logger.debug(this, "Deleted " + amount + " commands.");
				
				Persistent.checkedCommit(mDB, this);
			}
			catch(RuntimeException e) {
				Persistent.checkedRollbackAndThrow(mDB, this, e);
			}
		}
	}
	
	public void storeStartFetchCommandWithoutCommit(Identity identity) {
		storeStartFetchCommandWithoutCommit(identity.getID());
	}
	
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
	
	public void storeAbortFetchCommandWithoutCommit(Identity identity) {
		if(logDEBUG) Logger.debug(this, "Abort fetch command received for " + identity);
		
		try {
			getCommand(StartFetchCommand.class, identity).deleteWithoutCommit();
			if(logDEBUG) Logger.debug(this, "Deleting start fetch command for " + identity);
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
	
	public void storeUpdateEditionHintCommandWithoutCommit(String identityID) {
		if(logDEBUG) Logger.debug(this, "Update edition hint command received for " + identityID);
		
		try {
			getCommand(AbortFetchCommand.class, identityID);
			Logger.error(this, "Update edition hint command is useless, an abort fetch command is queued!");
		}
		catch(NoSuchCommandException e1) {
			try {
				getCommand(UpdateEditionHintCommand.class, identityID);
				if(logDEBUG) Logger.debug(this, "Update edition hint command already in queue!");
			}
			catch(NoSuchCommandException e2) {
				final UpdateEditionHintCommand cmd = new UpdateEditionHintCommand(identityID);
				cmd.initializeTransient(mWoT);
				cmd.storeWithoutCommit();
				scheduleCommandProcessing();
			}
		}
	}
	
	private void scheduleCommandProcessing() {
		if(mTicker != null)
			mTicker.queueTimedJob(this, "WoT IdentityFetcher", PROCESS_COMMANDS_DELAY, false, true);
		else
			Logger.warning(this, "Cannot schedule command processing: Ticker is null.");
	}
	
	public int getPriority() {
		return NativeThread.LOW_PRIORITY;
	}
	
	public void run() {
		synchronized(mWoT) { // Lock needed because we do getIdentityByID() in fetch()
		synchronized(this) {
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
				}
				
				for(IdentityFetcherCommand command : getCommands(StartFetchCommand.class)) {
					try {
						fetch(command.getIdentityID());
						command.deleteWithoutCommit();
					} catch (Exception e) {
						Logger.error(this, "Fetching identity failed", e);
					}
					
				}
				
				for(IdentityFetcherCommand command : getCommands(UpdateEditionHintCommand.class)) {
					try {
						editionHintUpdated(command.getIdentityID());
						command.deleteWithoutCommit();
					} catch (Exception e) { 
						Logger.error(this, "Updating edition hint failed", e);
					}
					
				}
				
				if(logDEBUG) Logger.debug(this, "Processing finished.");
				
				Persistent.checkedCommit(mDB, this);
			} catch(RuntimeException e) {
				Persistent.checkedRollback(mDB, this, e);
			}
		}
		}
		}
	}
	
	protected void fetch(String identityID) throws Exception {
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
	protected synchronized void fetch(Identity identity) throws Exception {
			USKRetriever retriever = mRequests.get(identity.getID());

			USK usk;

			if(identity.getCurrentEditionFetchState() != FetchState.NotFetched) // Do not refetch if parsing failed!
				usk = USK.create(identity.getRequestURI().setSuggestedEdition(identity.getEdition() + 1));
			else {
				usk = USK.create(identity.getRequestURI());

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

			mUSKManager.hintUpdate(usk, identity.getLatestEditionHint(), mClientContext);
	}
	
	/**
	 * Has to be called when the edition hint of the given identity was updated. Tells the USKManager about the new hint.
	 * 
	 * You have to synchronize on the WebOfTrust and then on this IdentityFetcher before calling this function!
	 * 
	 * @throws Exception 
	 */
	private void editionHintUpdated(String identityID) throws Exception {
		try {
			Identity identity = mWoT.getIdentityByID(identityID);
				if(mRequests.get(identity.getID()) == null)
					throw new UnknownIdentityException("updateEdtitionHint() called for an identity which is not being fetched: " + identityID);

				USK usk;

				if(identity.getCurrentEditionFetchState() != FetchState.NotFetched) // Do not refetch if parsing failed!
					usk = USK.create(identity.getRequestURI().setSuggestedEdition(identity.getEdition() + 1));
				else
					usk = USK.create(identity.getRequestURI());
				
				long editionHint = identity.getLatestEditionHint();
				
				if(logDEBUG) Logger.debug(this, "Updating edition hint to " + editionHint + " for " + identityID);

				mUSKManager.hintUpdate(usk, identity.getLatestEditionHint(), mClientContext);
		} catch (UnknownIdentityException e) {
			Logger.normal(this, "Updating edition hint failed, the identity was deleted already.", e);
		}
	}
	
	private synchronized void abortFetch(String identityID) {
		USKRetriever retriever = mRequests.remove(identityID);

		if(retriever == null) {
			Logger.error(this, "Aborting fetch failed (no fetch found) for identity " + identityID);
			return;
		}
			
		if(logDEBUG) Logger.debug(this, "Aborting fetch for identity " + identityID);
		retriever.cancel(null, mClientContext);
		mUSKManager.unsubscribeContent(retriever.getOriginalUSK(), retriever, true);
	}
	
	/**
	 * Fetches the given USK and returns the new USKRetriever. Does not check whether there is already a fetch for that USK.
	 */
	private USKRetriever fetch(USK usk) throws MalformedURLException {
		FetchContext fetchContext = mClient.getFetchContext();
		fetchContext.maxSplitfileBlockRetries = -1; // retry forever
		fetchContext.maxNonSplitfileRetries = -1; // retry forever
		fetchContext.maxOutputLength = XMLTransformer.MAX_IDENTITY_XML_BYTE_SIZE;
		if(logDEBUG) Logger.debug(this, "Trying to start fetching uri " + usk); 
		return mUSKManager.subscribeContent(usk, this, true, fetchContext, RequestStarter.UPDATE_PRIORITY_CLASS, mRequestClient);
	}
	
	public short getPollingPriorityNormal() {
		return RequestStarter.UPDATE_PRIORITY_CLASS;
	}

	public short getPollingPriorityProgress() {
		return RequestStarter.IMMEDIATE_SPLITFILE_PRIORITY_CLASS;
	}

	/**
	 * Stops all running requests.
	 */
	protected synchronized void stop() {
		if(logDEBUG) Logger.debug(this, "Trying to stop all requests");
		
		if(mTicker != null)
			mTicker.shutdown();
		
		USKRetriever[] retrievers = mRequests.values().toArray(new USKRetriever[mRequests.size()]);		
		int counter = 0;		 
		for(USKRetriever r : retrievers) {
			r.cancel(null, mClientContext);
			mUSKManager.unsubscribeContent(r.getOriginalUSK(), r, true);
			 ++counter;
		}
		mRequests.clear();
		
		if(logDEBUG) Logger.debug(this, "Stopped " + counter + " current requests");
	}

	/**
	 * Called when an identity is successfully fetched.
	 */
	public void onFound(USK origUSK, long edition, FetchResult result) {
		FreenetURI realURI = origUSK.getURI().setSuggestedEdition(edition);
		
		if(logDEBUG) Logger.debug(this, "Fetched identity: " + realURI);

		Bucket bucket = null;
		InputStream inputStream = null;
		
		try {
			bucket = result.asBucket();
			inputStream = bucket.getInputStream();
			
			final long startTime = System.nanoTime();
			mWoT.getXMLTransformer().importIdentity(realURI, inputStream);
			final long endTime = System.nanoTime();
			
			synchronized(this) {
				++mFetchedCount;
				mIdentityImportNanoseconds +=  endTime - startTime;
			}
		}
		catch (Throwable e) {
			Logger.error(this, "Parsing failed for " + realURI, e);
		}
		finally {
			Closer.close(inputStream);
			Closer.close(bucket);
		}
	}
	
	/**
	 * @return The number of identity XML files which this fetcher has fetched and processed successfully.
	 */
	public int getFetchedCount() {
		return mFetchedCount;
	}
	
	/**
	 * Notice that this function is synchronized because it processes multiple member variables.
	 * 
	 * @return The average time it took for parsing an identity XML file in seconds.
	 */
	public synchronized double getAverageXMLImportTime() {
		if(mFetchedCount == 0) // prevent division by 0
			return 0;
		
		return ((double)mIdentityImportNanoseconds/(1000*1000*1000)) / (double)mFetchedCount;
	}
	
	/**
	 * Notice that this function is synchronized because it processes multiple member variables.
	 * 
	 * @return The average number of identity XML files which are fetched per hour.
	 */
	public synchronized float getAverageFetchCountPerHour() {
		float uptimeSeconds = (float)(CurrentTimeUTC.getInMillis() - mStartupTimeMilliseconds)/1000;
		float uptimeHours = uptimeSeconds / (60*60);
		
		if(uptimeHours == 0) // prevent division by 0
			return 0;
		
		return (float)mFetchedCount / uptimeHours;		
	}

}
