/**
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */

package plugins.WoT;

import java.io.InputStream;
import java.net.MalformedURLException;
import java.util.Hashtable;

import freenet.client.FetchContext;
import freenet.client.FetchResult;
import freenet.client.HighLevelSimpleClient;
import freenet.client.async.ClientContext;
import freenet.client.async.USKManager;
import freenet.client.async.USKRetriever;
import freenet.client.async.USKRetrieverCallback;
import freenet.keys.USK;
import freenet.node.RequestClient;
import freenet.node.RequestStarter;
import freenet.support.Logger;
import freenet.support.api.Bucket;
import freenet.support.io.Closer;

/**
 * Fetches Identities from Freenet.
 * Contains an ArrayList of all current requests.
 * 
 * @author xor (xor@freenetproject.org), Julien Cornuwel (batosai@freenetproject.org)
 */
public class IdentityFetcher implements USKRetrieverCallback {
	
	private final WoT mWoT;
	
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
	
	
	/**
	 * Creates a new IdentityFetcher.
	 * 
	 * @param db A reference to the database
	 * @param mClient A reference to a {@link HighLevelSimpleClient}
	 */
	protected IdentityFetcher(WoT myWoT) {
		mWoT = myWoT;
		
		mUSKManager = mWoT.getPluginRespirator().getNode().clientCore.uskManager;
		mClient = mWoT.getPluginRespirator().getHLSimpleClient();
		mClientContext = mWoT.getPluginRespirator().getNode().clientCore.clientContext;
		mRequestClient = mWoT.getRequestClient();
	}

	/**
	 * Fetches an identity from Freenet, using the current edition number and edition hint stored in the identity.
	 * If the identity is already being fetched, passes the current edition hint stored in the identity to the USKManager.
	 * 
	 * If there is already a request for a newer USK, the request is cancelled and a fetch for the given older USK is started.
	 * This has to be done so that trust lists of identities can be re-fetched as soon as their score changes from negative to positive - that is necessary
	 * because we do not import identities from trust lists for which the owner has a negative score.
	 * 
	 * @param identity the Identity to fetch
	 */
	public synchronized void fetch(Identity identity) {
		try {
			USK usk;
			
			synchronized(identity) {
				USKRetriever retriever = mRequests.get(identity.getID());

				if(identity.currentEditionWasFetched())
					usk = USK.create(identity.getRequestURI().setSuggestedEdition(identity.getEdition() + 1));
				else {
					usk = USK.create(identity.getRequestURI());

					if(retriever != null) {
						// The identity has a new "mandatory" edition number stored which we must fetch, so we restart the request because the edition number might
						// be lower than the last one which the USKRetriever has fetched.
						Logger.minor(this, "The current edition of the given identity is marked as not fetched, re-creating the USKRetriever for " + usk);
						retriever.cancel(null, mClientContext);
						mUSKManager.unsubscribeContent(retriever.getOriginalUSK(), retriever, true);
						mRequests.remove(identity.getID());
						retriever = null;
					}
				}

				if(retriever == null)
					mRequests.put(identity.getID(), fetch(usk));

				mUSKManager.hintUpdate(usk, identity.getLatestEditionHint(), mClientContext);
			}

		} catch (MalformedURLException e) {
			Logger.error(this, "Request restart failed: "+e, e);
		}
	}
	
	/**
	 * Has to be called when the edition hint of the given identity was updated. Tells the USKManager about the new hint.
	 */
	public synchronized void editionHintUpdated(Identity identity) {
		// TODO: Reconsider this
		fetch(identity);
	}
	
	/**
	 * Fetches the given USK and returns the new USKRetriever. Does not check whether there is already a fetch for that USK.
	 */
	private synchronized USKRetriever fetch(USK usk) throws MalformedURLException {
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
		
		USKRetriever[] retrievers = mRequests.entrySet().toArray(new USKRetriever[mRequests.size()]);		
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
		Logger.debug(this, "Fetched identity: " + origUSK);
		
		Bucket bucket = null;
		InputStream inputStream = null;
		
		try {
			bucket = result.asBucket();
			inputStream = bucket.getInputStream();
			mWoT.getXMLTransformer().importIdentity(origUSK.getURI(), inputStream);
		}
		catch (Exception e) {
			Logger.error(this, "Parsing failed for "+ origUSK, e);
		}
		finally {
			Closer.close(inputStream);
			Closer.close(bucket);
		}
	}

}
