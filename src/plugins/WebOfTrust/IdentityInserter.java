/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import java.io.IOException;
import java.io.OutputStream;
import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Random;

import com.db4o.ObjectContainer;

import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.InsertBlock;
import freenet.client.InsertContext;
import freenet.client.InsertException;
import freenet.client.async.BaseClientPutter;
import freenet.client.async.ClientGetter;
import freenet.client.async.ClientPutter;
import freenet.keys.FreenetURI;
import freenet.node.RequestStarter;
import freenet.support.CurrentTimeUTC;
import freenet.support.Logger;
import freenet.support.TransferThread;
import freenet.support.api.Bucket;
import freenet.support.io.Closer;
import freenet.support.io.NativeThread;

/**
 * Inserts OwnIdentities to Freenet when they need it.
 * 
 * @author xor (xor@freenetproject.org)
 * @author Julien Cornuwel (batosai@freenetproject.org)
 */
public final class IdentityInserter extends TransferThread {
	
	private static final int STARTUP_DELAY = 1 * 60 * 1000;
	private static final int THREAD_PERIOD = 10 * 60 * 1000;
	
	/**
	 * The minimal time for which an identity must not have changed before we insert it.
	 */
	private static final int MIN_DELAY_BEFORE_INSERT = 15 * 60 * 1000;
	
	/**
	 * The maximal delay for which an identity insert can be delayed (relative to the last insert) due to continuous changes.
	 */
	private static final int MAX_DELAY_BEFORE_INSERT = 30 * 60 * 1000;
	
	/**
	 * The amount of time after which we insert a new edition of an identity even though it did not change.
	 */
	public static final long MAX_UNCHANGED_TINE_BEFORE_REINSERT = 1000*60*60*24*3;
	
	
	private WebOfTrust mWoT;

	/** Random number generator */
	private Random mRandom;
	
	/**
	 * Creates an IdentityInserter.
	 * 
	 * @param myWoT reference to an {@link WebOfTrust} to perform inserts
	 */
	public IdentityInserter(WebOfTrust myWoT) {
		super(myWoT.getPluginRespirator().getNode(), myWoT.getPluginRespirator().getHLSimpleClient(), "WoT Identity Inserter");
		mWoT = myWoT;
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
							Logger.debug(this, "Starting insert of " + identity.getNickname() + " (" + identity.getInsertURI() + ")");
							insert(identity);
						} else {
							long lastChangeBefore = (CurrentTimeUTC.getInMillis() - identity.getLastChangeDate().getTime()) / (60*1000);
							long lastInsertBefore = (CurrentTimeUTC.getInMillis() - identity.getLastInsertDate().getTime()) / (60*1000); 
							
							Logger.debug(this, "Delaying insert of " + identity.getNickname() + " (" + identity.getInsertURI() + "), " +
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
		Bucket tempB = mTBF.makeBucket(64 * 1024); /* TODO: Tweak */  
		OutputStream os = null;

		try {
			os = tempB.getOutputStream();
			mWoT.getXMLTransformer().exportOwnIdentity(identity, os);
			os.close(); os = null;
			tempB.setReadOnly();
		
			long edition = identity.getEdition();
			if(identity.getLastInsertDate().after(new Date(0)))
				++edition;
			
			InsertBlock ib = new InsertBlock(tempB, null, identity.getInsertURI().setSuggestedEdition(edition));
			InsertContext ictx = mClient.getInsertContext(true);
			
			ClientPutter pu = mClient.insert(ib, false, null, false, ictx, this, RequestStarter.IMMEDIATE_SPLITFILE_PRIORITY_CLASS);
			addInsert(pu);
			tempB = null;
			
			Logger.debug(this, "Started insert of identity '" + identity.getNickname() + "'");
		}
		catch(Exception e) {
			Logger.error(this, "Error during insert of identity '" + identity.getNickname() + "'", e);
		}
		finally {
			Closer.close(os);
			Closer.close(tempB);
		}
	}
	
	public void onSuccess(BaseClientPutter state, ObjectContainer container)
	{
		Logger.debug(this, "Successful insert of identity: " + state.getURI());
		
		try {
			synchronized(mWoT) {
				OwnIdentity identity = mWoT.getOwnIdentityByURI(state.getURI());
					try {
						identity.setEdition(state.getURI().getEdition());
					} catch(InvalidParameterException e) {
						// This sometimes happens. The reason is probably that the IdentityFetcher fetches it before onSuccess() is called and setEdition()
						// won't accept lower edition numbers.
						// We must catch it because insert) only increments the edition number if getLastInsertDate().after(new Date(0)) - which can
						// only be the case if we ALWAYS call updateLastInsertDate().
						// TODO: Check whether this can be prevented.
						Logger.error(this, "setEdition() failed with URI/edition " + state.getURI() + "; current stored edition: " + identity.getEdition());
						
					}
					identity.updateLastInsertDate();
					identity.storeAndCommit();
			}
		}
		catch(Exception e)
		{
			Logger.error(this, "Error", e);
		}
		finally {
			removeInsert(state);
		}
	}

	public void onFailure(InsertException e, BaseClientPutter state, ObjectContainer container) 
	{
		try {
			if(e.getMode() == InsertException.CANCELLED) {
				Logger.debug(this, "Insert cancelled: " + state.getURI());
			}
			else {
				Logger.error(this, "Error during insert of identity: " + state.getURI(), e);
				/* We do not increase the edition of the identity if there is a collision because the fetcher will fetch the new edition
				 * and the Inserter will insert it with that edition in the next run. */
			}
		}
		finally {
			removeInsert(state);
		}
	}
	
	/* Not needed functions from the ClientCallback interface */
	
	public void onFailure(FetchException e, ClientGetter state, ObjectContainer container) { }

	public void onFetchable(BaseClientPutter state, ObjectContainer container) { }
	
	public void onGeneratedURI(FreenetURI uri, BaseClientPutter state, ObjectContainer container) { }

	public void onMajorProgress(ObjectContainer container) { }

	public void onSuccess(FetchResult result, ClientGetter state, ObjectContainer container) { }

}

