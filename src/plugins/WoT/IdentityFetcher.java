/**
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */

package plugins.WoT;

import java.io.InputStream;
import java.net.MalformedURLException;
import java.util.Hashtable;
import java.util.Iterator;

import plugins.WoT.exceptions.UnknownIdentityException;

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
import freenet.node.RequestClient;
import freenet.node.RequestStarter;
import freenet.support.Logger;
import freenet.support.TrivialTicker;
import freenet.support.api.Bucket;
import freenet.support.io.Closer;

/**
 * Fetches Identities from Freenet.
 * Contains an ArrayList of all current requests.
 * 
 * @author xor (xor@freenetproject.org), Julien Cornuwel (batosai@freenetproject.org)
 */
public final class IdentityFetcher implements USKRetrieverCallback, Runnable {
	
	private static final long PROCESS_COMMANDS_DELAY = 5 * 1000;
	
	private final WoT mWoT;
	
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
	private final Hashtable<String, USKRetriever> mRequests = new Hashtable<String, USKRetriever>(128); /* TODO: profile & tweak */
	
	private final TrivialTicker mTicker;
	
	/**
	 * Creates a new IdentityFetcher.
	 * 
	 * @param myWoT A reference to a {@link WoT}
	 */
	protected IdentityFetcher(WoT myWoT) {
		mWoT = myWoT;
		
		mDB = mWoT.getDB();
		
		mUSKManager = mWoT.getPluginRespirator().getNode().clientCore.uskManager;
		mClient = mWoT.getPluginRespirator().getHLSimpleClient();
		mClientContext = mWoT.getPluginRespirator().getNode().clientCore.clientContext;
		mRequestClient = mWoT.getRequestClient();
		
		mTicker = new TrivialTicker(mWoT.getPluginRespirator().getNode().executor);
		
		deleteAllCommands();
	}
	
	public static class IdentityFetcherCommand {
		
		private final String mIdentityID;

		public static String[] getIndexedFields() {
			return new String[] { "mIdentityID" };
		}
		
		protected IdentityFetcherCommand(String myIdentityID) {
			mIdentityID = myIdentityID;
		}
		
		protected String getIdentityID() {
			return mIdentityID;
		}
		
		protected void storeWithoutCommit(ExtObjectContainer db) {
			db.store(this);
		}
		
		protected void deleteWithoutCommit(ExtObjectContainer db) {
			db.delete(this);
		}

	}
	
	private static final class StartFetchCommand extends IdentityFetcherCommand {

		protected StartFetchCommand(Identity identity) {
			super(identity.getID());
		}
		
		protected StartFetchCommand(String identityID) {
			super(identityID);
		}
		
	}
	
	private static final class AbortFetchCommand extends IdentityFetcherCommand {

		protected AbortFetchCommand(Identity identity) {
			super(identity.getID());
		}
		
	}
	
	private static final class UpdateEditionHintCommand extends IdentityFetcherCommand {

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
	
	@SuppressWarnings("unchecked")
	private IdentityFetcherCommand getCommand(Class commandType, Identity identity) throws NoSuchCommandException {
		return getCommand(commandType, identity.getID());
	}
	
	@SuppressWarnings("unchecked")
	private IdentityFetcherCommand getCommand(Class commandType, String identityID) throws NoSuchCommandException {
		Query q = mDB.query();
		q.constrain(commandType);
		q.descend("mIdentityID").constrain(identityID);
		ObjectSet<IdentityFetcherCommand> result = q.execute();
		
		switch(result.size()) {
			case 1: return result.next();
			case 0: throw new NoSuchCommandException();
			default: throw new DuplicateCommandException();
		}
	}
	
	@SuppressWarnings("unchecked")
	private ObjectSet<IdentityFetcherCommand> getCommands(Class commandType) {
		Query q = mDB.query();
		q.constrain(commandType);
		return q.execute();
	}
	
	private synchronized void deleteAllCommands() {
		synchronized(mDB.lock()) {
			try {
				Logger.debug(this, "Deleting all identity fetcher commands ...");
				
				int amount = 0;
				
				for(IdentityFetcherCommand command : getCommands(IdentityFetcherCommand.class)) {
					command.deleteWithoutCommit(mDB);
					++amount;
				}
				
				Logger.debug(this, "Deleted " + amount + " commands.");
				
				mDB.commit(); Logger.debug(this, "COMMITED.");
			}
			catch(RuntimeException e) {
				mDB.rollback(); mDB.purge(); Logger.error(this, "ROLLED BACK!", e);
				throw e;
			}
		}
	}
	
	public void storeStartFetchCommandWithoutCommit(Identity identity) {
		storeStartFetchCommandWithoutCommit(identity.getID());
	}
	
	public void storeStartFetchCommandWithoutCommit(String identityID) {
		Logger.debug(this, "Start fetch command received for " + identityID);
		
		try {
			getCommand(AbortFetchCommand.class, identityID).deleteWithoutCommit(mDB);
			Logger.debug(this, "Deleting abort fetch command for " + identityID);
		}
		catch(NoSuchCommandException e) { }
		
		try {
			getCommand(StartFetchCommand.class, identityID);
			Logger.debug(this, "Start fetch command already in queue!");
		}
		catch(NoSuchCommandException e) {
			new StartFetchCommand(identityID).storeWithoutCommit(mDB);
			scheduleCommandProcessing();
		}
	}
	
	public void storeAbortFetchCommandWithoutCommit(Identity identity) {
		Logger.debug(this, "Abort fetch command received for " + identity);
		
		try {
			getCommand(StartFetchCommand.class, identity).deleteWithoutCommit(mDB);
			Logger.debug(this, "Deleting start fetch command for " + identity);
		}
		catch(NoSuchCommandException e) { }
		
		try {
			getCommand(AbortFetchCommand.class, identity);
			Logger.debug(this, "Abort fetch command already in queue!");
		}
		catch(NoSuchCommandException e) {
			new AbortFetchCommand(identity).storeWithoutCommit(mDB);
			scheduleCommandProcessing();
		}
	}
	
	public void storeUpdateEditionHintCommandWithoutCommit(String identityID) {
		Logger.debug(this, "Update edition hint command received for " + identityID);
		
		try {
			getCommand(AbortFetchCommand.class, identityID);
			Logger.error(this, "Update edition hint command is useless, an abort fetch command is queued!");
		}
		catch(NoSuchCommandException e1) {
			try {
				getCommand(UpdateEditionHintCommand.class, identityID);
				Logger.debug(this, "Update edition hint command already in queue!");
			}
			catch(NoSuchCommandException e2) {
				new UpdateEditionHintCommand(identityID).storeWithoutCommit(mDB);
				scheduleCommandProcessing();
			}
		}
	}
	
	private void scheduleCommandProcessing() {
		mTicker.queueTimedJob(this, "FT IdentityFetcher", PROCESS_COMMANDS_DELAY, true, true);
	}
	
	public void run() {
		synchronized(mWoT) { // Lock needed because we do getIdentityByID() in fetch()
		synchronized(this) {
		synchronized(mDB.lock()) {
			try  {
				Logger.debug(this, "Processing identity fetcher commands ...");
				
				for(IdentityFetcherCommand command : getCommands(AbortFetchCommand.class)) {
					try {
						abortFetch(command.getIdentityID());
						command.deleteWithoutCommit(mDB);
					} catch(Exception e) {
						Logger.error(this, "Aborting fetch failed", e);
					}
				}
				
				for(IdentityFetcherCommand command : getCommands(StartFetchCommand.class)) {
					try {
						fetch(command.getIdentityID());
						command.deleteWithoutCommit(mDB);
					} catch (Exception e) {
						Logger.error(this, "Fetching identity failed", e);
					}
					
				}
				
				for(IdentityFetcherCommand command : getCommands(UpdateEditionHintCommand.class)) {
					try {
						editionHintUpdated(command.getIdentityID());
						command.deleteWithoutCommit(mDB);
					} catch (Exception e) { 
						Logger.error(this, "Updating edition hint failed", e);
					}
					
				}
				
				Logger.debug(this, "Processing finished.");
				
				mDB.commit(); Logger.debug(this, "COMMITED.");
			} catch(RuntimeException e) {
				mDB.rollback(); mDB.purge(); Logger.error(this, "ROLLED BACK!", e);
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
		synchronized(identity) {
			USKRetriever retriever = mRequests.get(identity.getID());

			USK usk;

			if(identity.currentEditionWasFetched())
				usk = USK.create(identity.getRequestURI().setSuggestedEdition(identity.getEdition() + 1));
			else {
				usk = USK.create(identity.getRequestURI());

				if(retriever != null) {
					// The identity has a new "mandatory" edition number stored which we must fetch, so we restart the request because the edition number might
					// be lower than the last one which the USKRetriever has fetched.
					Logger.minor(this, "The current edition of the given identity is marked as not fetched, re-creating the USKRetriever for " + usk);
					abortFetch(identity.getID());
					retriever = null;
				}
			}

			if(retriever == null)
				mRequests.put(identity.getID(), fetch(usk));

			mUSKManager.hintUpdate(usk, identity.getLatestEditionHint(), mClientContext);

		}
	}
	
	/**
	 * Has to be called when the edition hint of the given identity was updated. Tells the USKManager about the new hint.
	 * @throws Exception 
	 */
	private synchronized void editionHintUpdated(String identityID) throws Exception {
		try {
			Identity identity = mWoT.getIdentityByID(identityID);

			synchronized(identity) {
				if(mRequests.get(identity.getID()) == null)
					throw new UnknownIdentityException("updateEdtitionHint() called for an identity which is not being fetched: " + identityID);

				USK usk;

				if(identity.currentEditionWasFetched())
					usk = USK.create(identity.getRequestURI().setSuggestedEdition(identity.getEdition() + 1));
				else
					usk = USK.create(identity.getRequestURI());
				
				long editionHint = identity.getLatestEditionHint();
				
				Logger.debug(this, "Updating edition hint to " + editionHint + " for " + identityID);

				mUSKManager.hintUpdate(usk, identity.getLatestEditionHint(), mClientContext);

			}
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
			
		Logger.debug(this, "Aborting fetch for identity " + identityID);
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
		Logger.debug(this, "Trying to start fetching uri " + usk); 
		return mUSKManager.subscribeContent(usk, this, true, fetchContext, RequestStarter.UPDATE_PRIORITY_CLASS, mRequestClient);
	}
	
	public short getPollingPriorityNormal() {
		return RequestStarter.UPDATE_PRIORITY_CLASS;
	}

	public short getPollingPriorityProgress() {
		return RequestStarter.UPDATE_PRIORITY_CLASS;
	}

	/**
	 * Stops all running requests.
	 */
	protected synchronized void stop() {
		Logger.debug(this, "Trying to stop all requests");
		
		USKRetriever[] retrievers = mRequests.values().toArray(new USKRetriever[mRequests.size()]);		
		int counter = 0;		 
		for(USKRetriever r : retrievers) {
			r.cancel(null, mClientContext);
			mUSKManager.unsubscribeContent(r.getOriginalUSK(), r, true);
			 ++counter;
		}
		mRequests.clear();
		
		Logger.debug(this, "Stopped " + counter + " current requests");
	}

	/**
	 * Called when an identity is successfully fetched.
	 */
	public void onFound(USK origUSK, long edition, FetchResult result) {
		FreenetURI realURI = origUSK.getURI().setSuggestedEdition(edition);
		
		Logger.debug(this, "Fetched identity: " + realURI);
		
		Bucket bucket = null;
		InputStream inputStream = null;
		
		try {
			bucket = result.asBucket();
			inputStream = bucket.getInputStream();
			mWoT.getXMLTransformer().importIdentity(realURI, inputStream);
		}
		catch (Exception e) {
			Logger.error(this, "Parsing failed for " + realURI, e);
		}
		finally {
			Closer.close(inputStream);
			Closer.close(bucket);
		}
	}

}
