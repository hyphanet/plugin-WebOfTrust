/**
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */

package plugins.WoT;

import java.io.InputStream;
import java.net.MalformedURLException;
import java.util.Date;
import java.util.HashSet;
import java.util.Hashtable;

import freenet.client.FetchContext;
import freenet.client.FetchResult;
import freenet.client.HighLevelSimpleClient;
import freenet.client.async.ClientContext;
import freenet.client.async.ClientGetter;
import freenet.client.async.USKManager;
import freenet.client.async.USKRetriever;
import freenet.client.async.USKRetrieverCallback;
import freenet.keys.FreenetURI;
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
	/* FIXME: We use those HashSets for checking whether we have already have a request for the given identity if someone calls fetch().
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
	 * @param identity the Identity to fetch
	 * @param nextEdition If set to true, tries to fetch the next edition of the identity.
	 */
	public synchronized void fetch(Identity identity) {
		try {
			USK usk;
			
			if(!identity.getFirstFetchedDate().equals(new Date(0))) // We already have the current edition, fetch the next ...
				usk = USK.create(identity.getRequestURI().setSuggestedEdition(identity.getEdition() + 1));
			else
				usk = USK.create(identity.getRequestURI());

			fetch(identity, usk);
			
			mUSKManager.hintUpdate(usk, identity.getLatestEditionHint(), mClientContext);

		} catch (MalformedURLException e) {
			Logger.error(this, "Request restart failed: "+e, e);
		}
	}
	
	/**
	 * Fetches an identity with the given USK. If there is already a request for a newer USK, the request is cancelled and a fetch for the given older USK is
	 * started. This has to be done so that trust lists of identities can be re-fetched as soon as their score changes from negative to positive - that is 
	 * necessary because we do not import identities from trust lists for which the owner has a negative score.
	 */
	private synchronized void fetch(Identity identity, USK usk) throws MalformedURLException {
		USKRetriever ret = mRequests.get(identity.getID());
		if(ret != null) {
			// FIXME XXX: This code sucks! It's purpose is to allow re-downloading of already downloaded identities: We sometimes need to do that, for example
			// at first usage of the plugin: The seed identity's trusted identities will not be imported as long as there is no own identity which trusts the seed.
			// Therefore, when the user creates an own identity, we decrease the edition number so that the seed is re-feteched.
			// So we rather need functionality in USKManager / USKRetriever for decreasing the edition number than just cancelling the request and re-creating it!
			ret.cancel();
			mUSKManager.unsubscribeContent(ret.getOriginalUSK(), ret, true);
			mRequests.remove(identity.getID());
		}
		
		/* TODO: check whether we are downloading the uri already. probably only as debug code to see if it actually happens */
		FetchContext fetchContext = mClient.getFetchContext();
		fetchContext.maxSplitfileBlockRetries = -1; // retry forever
		fetchContext.maxNonSplitfileRetries = -1; // retry forever
		Logger.debug(this, "Trying to start fetching uri " + usk); 
		/* FIXME: Toad: Does this also eat a RequestStarter priority class? You should javadoc which priority class constants to use! */
		ret = mUSKManager.subscribeContent(usk, this, true, fetchContext, RequestStarter.UPDATE_PRIORITY_CLASS, mRequestClient);
		
		mRequests.put(identity.mID, ret);
	}
	
	@Override
	public short getPollingPriorityNormal() {
		return RequestStarter.UPDATE_PRIORITY_CLASS;  /* FIXME: Is this correct? */
	}

	@Override
	public short getPollingPriorityProgress() {
		return RequestStarter.UPDATE_PRIORITY_CLASS; /* FIXME: Is this correct? */
	}

	/**
	 * Stops all running requests.
	 */
	protected synchronized void stop() {
		Logger.debug(this, "Trying to stop all requests");
		
		USKRetriever[] retrievers = mRequests.entrySet().toArray(new USKRetriever[mRequests.size()]);		
		int counter = 0;		 
		for(USKRetriever r : retrievers) {
			r.cancel();
			mUSKManager.unsubscribeContent(r.getOriginalUSK(), r, true);
			 ++counter;
		}
		mRequests.clear();
		
		Logger.debug(this, "Stopped " + counter + " current requests");
	}

	/**
	 * Called when an identity is successfully fetched.
	 */
	@Override
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
