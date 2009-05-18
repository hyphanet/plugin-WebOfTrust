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
public class IdentityFetcher implements USKRetrieverCallback { // TODO: Use the new interface when 1210 has been released, not before so the plugin still loasd with 1209
	
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
	private final HashSet<String> mIdentities = new HashSet<String>(128); /* TODO: profile & tweak */
	private final HashSet<USKRetriever> mRequests = new HashSet<USKRetriever>(128); /* TODO: profile & tweak */
	
	
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

			if(!mIdentities.contains(identity.getID())) {
				fetch(usk);
				mIdentities.add(identity.getID());
			}
			
			mUSKManager.hintUpdate(usk, identity.getLatestEditionHint(), mClientContext);

		} catch (MalformedURLException e) {
			Logger.error(this, "Request restart failed: "+e, e);
		}
	}
	
	/**
	 * Fetches an file from Freenet, by its URI.
	 * 
	 * @param uri the {@link FreenetURI} we want to fetch
	 */
	public synchronized void fetch(USK usk) throws MalformedURLException {
		/* TODO: check whether we are downloading the uri already. probably only as debug code to see if it actually happens */
		FetchContext fetchContext = mClient.getFetchContext();
		fetchContext.maxSplitfileBlockRetries = -1; // retry forever
		fetchContext.maxNonSplitfileRetries = -1; // retry forever
		Logger.debug(this, "Trying to start fetching uri " + usk); 
		/* FIXME: Toad: Does this also eat a RequestStarter priority class? You should javadoc which priority class constants to use! */
		USKRetriever ret = mUSKManager.subscribeContent(usk, this, true, fetchContext, RequestStarter.UPDATE_PRIORITY_CLASS, mRequestClient);
		
		mRequests.add(ret);
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
		
		USKRetriever[] retrievers = mRequests.toArray(new USKRetriever[mRequests.size()]);		
		int counter = 0;		 
		for(USKRetriever r : retrievers) {
			mUSKManager.unsubscribeContent(r.getOriginalUSK(), r, true);
			r.cancel(); ++counter;
		}
		mRequests.clear();
		mIdentities.clear();
		
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
