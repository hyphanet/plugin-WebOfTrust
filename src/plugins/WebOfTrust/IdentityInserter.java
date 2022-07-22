/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import static java.util.concurrent.TimeUnit.DAYS;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import plugins.WebOfTrust.util.Daemon;
import plugins.WebOfTrust.util.TransferThread;

import com.db4o.ext.ExtObjectContainer;

import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.InsertBlock;
import freenet.client.InsertContext;
import freenet.client.InsertException;
import freenet.client.InsertException.InsertExceptionMode;
import freenet.client.async.BaseClientPutter;
import freenet.client.async.ClientContext;
import freenet.client.async.ClientGetter;
import freenet.client.async.ClientPutter;
import freenet.keys.FreenetURI;
import freenet.node.RequestClient;
import freenet.node.RequestStarter;
import freenet.support.CurrentTimeUTC;
import freenet.support.Logger;
import freenet.support.api.Bucket;
import freenet.support.api.RandomAccessBucket;
import freenet.support.io.Closer;
import freenet.support.io.NativeThread;
import freenet.support.io.ResumeFailedException;

/**
 * Inserts OwnIdentities to Freenet when they need it.
 * 
 * @author xor (xor@freenetproject.org)
 * @author Julien Cornuwel (batosai@freenetproject.org)
 */
public final class IdentityInserter extends TransferThread implements Daemon {
	
	private static final int STARTUP_DELAY = 1 * 60 * 1000;
	
	/**
	 * The minimal time for which an identity must not have changed before we insert it.
	 * TODO: Convert this and users to long to be able to then use {@link TimeUnit#HOURS}. */
    private static final int MIN_DELAY_BEFORE_INSERT = 1 /* hours */ * 60 * 60 * 1000;
	
	/**
	 * The maximal delay for which an identity insert can be delayed (relative to the last insert) due to continuous changes.
	 * TODO: Convert this and users to long to be able to then use {@link TimeUnit#HOURS}. */
    private static final int MAX_DELAY_BEFORE_INSERT = 3 /* hours */ * 60 * 60 * 1000;

    private static final int THREAD_PERIOD = MAX_DELAY_BEFORE_INSERT / 2;
	
	/**
	 * The amount of time after which we insert a new edition of an identity even though it did not change.
	 * TODO: Code quality: Make this and the above values configurable. */
	public static final long MAX_UNCHANGED_TIME_BEFORE_REINSERT = DAYS.toMillis(3);
	
	
	private WebOfTrust mWoT;
	
	private SubscriptionManager mSubscriptionManager;
	
	private ExtObjectContainer mDB;

	/** Random number generator */
	private Random mRandom;
	
	/* These booleans are used for preventing the construction of log-strings if logging is disabled (for saving some cpu cycles) */
	
	private static transient volatile boolean logDEBUG = false;
	private static transient volatile boolean logMINOR = false;
	
	static {
		Logger.registerClass(IdentityInserter.class);
	}
	
	
	/**
	 * Creates an IdentityInserter.
	 * 
	 * @param myWoT reference to an {@link WebOfTrust} to perform inserts
	 */
	public IdentityInserter(WebOfTrust myWoT) {
		super(myWoT.getPluginRespirator().getNode(), myWoT.getPluginRespirator().getHLSimpleClient(), "WoT Identity Inserter");
		mWoT = myWoT;
		mSubscriptionManager = mWoT.getSubscriptionManager();
		mDB = mWoT.getDatabase();
		mRandom = mWoT.getPluginRespirator().getNode().fastWeakRandom;
	}
	
	@Override
	protected Collection<ClientGetter> createFetchStorage() {
		return null;
	}

	@Override
	protected Collection<BaseClientPutter> createInsertStorage() {
		return new ArrayList<BaseClientPutter>(10); /* 10 identities should be much */
	}

	@Override
	public int getPriority() {
		return NativeThread.LOW_PRIORITY;
	}

	/** @return {@link WebOfTrust#getRequestClient()} */
    @Override public RequestClient getRequestClient() {
        return mWoT.getRequestClient();
    }

	@Override
	protected long getStartupDelay() {
		return STARTUP_DELAY/2 + mRandom.nextInt(STARTUP_DELAY);
	}
	
	@Override
	protected long getSleepTime() {
		return THREAD_PERIOD/2 + mRandom.nextInt(THREAD_PERIOD);
	}

	@Override
	protected void iterate() {
		abortInserts();
		
		synchronized(mWoT) {
			for(OwnIdentity identity : mWoT.getAllOwnIdentities()) {
				if(identity.needsInsert()) {
					try {
						long minDelayedInsertTime = identity.getLastChangeDate().getTime() + MIN_DELAY_BEFORE_INSERT;
						long maxDelayedInsertTime = identity.getLastInsertDate().getTime() + MAX_DELAY_BEFORE_INSERT; 
						
						if(CurrentTimeUTC.getInMillis() > Math.min(minDelayedInsertTime, maxDelayedInsertTime)) {
							insert(identity);
						} else {
							long lastChangeBefore = (CurrentTimeUTC.getInMillis() - identity.getLastChangeDate().getTime()) / (60*1000);
							long lastInsertBefore = (CurrentTimeUTC.getInMillis() - identity.getLastInsertDate().getTime()) / (60*1000); 
							
							if(logDEBUG) Logger.debug(this, "Delaying insert of identity '" + identity.getNickname() + "', " +
									"last change: " + lastChangeBefore + "min ago, last insert: " + lastInsertBefore + "min ago");
						}
					} catch (Exception e) {
						Logger.error(this, "Identity insert failed: " + e.getMessage(), e);
					}
				}
			}
		}
	}

	/**
	 * Inserts an OwnIdentity.
	 * 
	 * You have to synchronize on the WebOfTrust when calling this function.
	 * 
	 * @throws IOException 
	 */
	private void insert(OwnIdentity identity) throws IOException {
		RandomAccessBucket tempB = null;
		OutputStream os = null;

		try {
			tempB = mTBF.makeBucket(XMLTransformer.MAX_IDENTITY_XML_BYTE_SIZE + 1);
			os = tempB.getOutputStream();
			mWoT.getXMLTransformer().exportOwnIdentity(identity, os);
			os.close(); os = null;
			tempB.setReadOnly();
		
			long edition = identity.getNextEditionToInsert();
			InsertBlock ib = new InsertBlock(tempB, null, identity.getInsertURI().setSuggestedEdition(edition));
			InsertContext ictx = mClient.getInsertContext(true);
			
			ClientPutter pu = mClient.insert(
			    ib, null, false, ictx, this, RequestStarter.IMMEDIATE_SPLITFILE_PRIORITY_CLASS);
			addInsert(pu);
			tempB = null;
			
			if(logDEBUG) {
				Logger.debug(this, "Started insert of identity '" + identity.getNickname() + "' to "
					+ ib.desiredURI.deriveRequestURIFromInsertURI());
			}
		}
		catch(Exception e) {
			Logger.error(this, "Error during insert of identity '" + identity.getNickname() + "'", e);
		}
		finally {
			Closer.close(os);
			Closer.close(tempB);
		}
	}
	
	@Override
    public void onSuccess(BaseClientPutter state)
	{
		FreenetURI uri = state.getURI();
		Logger.normal(this, "Successful insert of identity: " + uri);
		
		try {
			synchronized(mWoT) {
			synchronized(mSubscriptionManager) {
			synchronized(Persistent.transactionLock(mDB)) {
				OwnIdentity identity = mWoT.getOwnIdentityByURI(uri);
				final OwnIdentity oldIdentity = identity.clone();
				try {
					identity.onInserted(uri.getEdition());
					mSubscriptionManager.storeIdentityChangedNotificationWithoutCommit(oldIdentity, identity);
					identity.storeAndCommit();
				} catch(RuntimeException e) {
					Persistent.checkedRollbackAndThrow(mDB, this, e);
				}
				
			}
			}
			}
		}
		catch(Exception e)
		{
			Logger.error(this, "Error", e);
		}
		finally {
			removeInsert(state);
			Closer.close(((ClientPutter)state).getData());
		}
	}

	@Override
    public void onFailure(InsertException e, BaseClientPutter state) 
	{
		try {
			if(e.getMode() == InsertExceptionMode.CANCELLED) {
				if(logDEBUG) Logger.debug(this, "Insert cancelled: " + state.getURI());
			}
			else {
				if(e.isFatal())
					Logger.error(this, "Error during insert of identity: " + state.getURI(), e);
				else
					Logger.warning(this, "Error during insert of identity, isFatal()==false: " + state.getURI(), e);
				/* We do not increase the edition of the identity if there is a collision because the fetcher will fetch the new edition
				 * and the Inserter will insert it with that edition in the next run. */
			}
		}
		finally {
			removeInsert(state);
			Closer.close(((ClientPutter)state).getData());
		}
	}
	
	/* Not needed functions from the ClientCallback interface */
	
	@Override
    public void onFailure(FetchException e, ClientGetter state) { }

	@Override
    public void onFetchable(BaseClientPutter state) { }
	
	@Override
    public void onGeneratedURI(FreenetURI uri, BaseClientPutter state) { }

    /**
     * Should not be called since this class does not create persistent requests.<br>
     * Will throw an exception since the interface specification requires it to do some stuff,
     * which it does not do.<br>
     * Parent interface JavaDoc follows:<br><br>
     * {@inheritDoc}
     */
    @Override public void onResume(final ClientContext context) throws ResumeFailedException {
        final ResumeFailedException error = new ResumeFailedException(
            "onResume() called even though this class does not create persistent requests");
        Logger.error(this, error.getMessage(), error /* Add exception for logging stack trace */);
        throw error;
    }

	@Override
    public void onSuccess(FetchResult result, ClientGetter state) { }

	@Override
	public void onGeneratedMetadata(Bucket metadata, BaseClientPutter state) {
		metadata.free();
		throw new UnsupportedOperationException();
	}

}

