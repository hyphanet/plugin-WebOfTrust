/**
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */

package plugins.WoT;

import java.io.InputStream;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;

import plugins.WoT.exceptions.UnknownIdentityException;

import com.db4o.ObjectContainer;

import freenet.client.FetchContext;
import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.HighLevelSimpleClient;
import freenet.client.InsertException;
import freenet.client.async.BaseClientPutter;
import freenet.client.async.ClientCallback;
import freenet.client.async.ClientContext;
import freenet.client.async.ClientGetter;
import freenet.keys.FreenetURI;
import freenet.node.RequestClient;
import freenet.support.Logger;
import freenet.support.api.Bucket;
import freenet.support.io.Closer;

/**
 * Fetches Identities from Freenet.
 * Contains an ArrayList of all current requests.
 * 
 * @author xor (xor@freenetproject.org), Julien Cornuwel (batosai@freenetproject.org)
 */
public class IdentityFetcher implements ClientCallback { // TODO: Use the new interface when 1210 has been released, not before so the plugin still loasd with 1209
	
	private final WoT mWoT;

	/** A reference to the HighLevelSimpleClient used to talk with the node */
	private final HighLevelSimpleClient client;
	
	private final RequestClient requestClient;
	private final ClientContext clientContext;

	/** All current requests */
	/* FIXME: We use those HashSets for checking whether we have already have a request for the given identity if someone calls fetch().
	 * This sucks: We always request ALL identities to allow ULPRs so we must assume that those HashSets will not fit into memory
	 * if the WoT becomes large. We should instead ask the node whether we already have a request for the given SSK URI. So how to do that??? */
	private final HashSet<Identity> identities = new HashSet<Identity>(128); /* TODO: profile & tweak */
	private final HashSet<ClientGetter> requests = new HashSet<ClientGetter>(128); /* TODO: profile & tweak */
	
	
	/**
	 * Creates a new IdentityFetcher.
	 * 
	 * @param db A reference to the database
	 * @param client A reference to a {@link HighLevelSimpleClient}
	 */
	protected IdentityFetcher(WoT myWoT) {
		mWoT = myWoT;
		
		clientContext = mWoT.getPluginRespirator().getNode().clientCore.clientContext;
		client = mWoT.getPluginRespirator().getHLSimpleClient();
		requestClient = mWoT.getRequestClient();
	}
	
	/**
	 * Fetches an Identity from Freenet. 
	 * Sets nextEdition to false by default if not specified.
	 * 
	 * @param identity the Identity to fetch
	 */
	public void fetch(Identity identity) {
		fetch(identity, false);
	}
	
	/**
	 * Fetches an Identity from Freenet. 
	 * Sets nextEdition to false by default if not specified.
	 * 
	 * @param identity the Identity to fetch
	 * @param nextEdition whether we want to check current edition or the next one
	 */
	public synchronized void fetch(Identity identity, boolean nextEdition) {
		/* TODO: check whether we should increase the edition in the associated ClientGetter and restart the request or rather wait
		 * for it to finish */
		if(identities.contains(identity)) 
			return; 

		try {
			if(nextEdition && !identity.getFirstFetchedDate().equals(new Date(0)))
				fetch(identity.getRequestURI().setSuggestedEdition(identity.getRequestURI().getSuggestedEdition() + 1));
			else
				fetch(identity.getRequestURI());
			
			identities.add(identity);
		} catch (FetchException e) {
			Logger.error(this, "Request restart failed: "+e, e);
		}
	}
	
	/**
	 * Fetches a file from Freenet, by its URI.
	 * 
	 * @param uri the {@link FreenetURI} we want to fetch
	 * @throws FetchException if the node encounters a problem
	 */
	public synchronized void fetch(FreenetURI uri) throws FetchException {
		/* TODO: check whether we are downloading the uri already. probably only as debug code to see if it actually happens */
		FetchContext fetchContext = client.getFetchContext();
		fetchContext.maxSplitfileBlockRetries = -1; // retry forever
		fetchContext.maxNonSplitfileRetries = -1; // retry forever
		Logger.debug(this, "Trying to start fetching uri " + uri.toString()); 
		ClientGetter g = client.fetch(uri, -1, requestClient, this, fetchContext);
		// FIXME: Set to a reasonable value before release, PluginManager default is interactive priority
		//g.setPriorityClass(RequestStarter.UPDATE_PRIORITY_CLASS, clientContext, null);
		requests.add(g);
	}

	/**
	 * Stops all running requests.
	 */
	protected synchronized void stop() {
		Logger.debug(this, "Trying to stop all requests");
		Iterator<ClientGetter> i = requests.iterator();
		int counter = 0;		 
		while (i.hasNext()) { i.next().cancel(); i.remove(); ++counter; }
		Logger.debug(this, "Stopped " + counter + " current requests");
	}
	
	private synchronized void removeRequest(ClientGetter g) {
		requests.remove(g);
		Logger.debug(this, "Removed request for " + g.getURI());
	}
	
	private synchronized void removeIdentity(ClientGetter state) {
		try {
			identities.remove(mWoT.getIdentityByURI(state.getURI()));
		}
		catch(UnknownIdentityException ex)
		{
			assert(false);
		}
	}
	
	/**
	 * Called when the node can't fetch a file OR when there is a newer edition.
	 * If this is the later, we restart the request.
	 */
	public synchronized void onFailure(FetchException e, ClientGetter state, ObjectContainer container) {		
		if(e.getMode() == FetchException.CANCELLED) {
			Logger.debug(this, "Fetch cancelled: " + state.getURI());
			return;
		}
		
		if ((e.mode == FetchException.PERMANENT_REDIRECT) || (e.mode == FetchException.TOO_MANY_PATH_COMPONENTS )) {
			try {
				state.restart(e.newURI, null, clientContext);
			} catch (FetchException e1) {
				Logger.error(this, "Request restart failed: "+e1, e1);
			}
		} else {
			// Errors we can't/want deal with
			Logger.error(this, "Fetch failed for "+ state.getURI(), e);
			
			removeRequest(state);
			removeIdentity(state);
		}
	}

	/**
	 * Called when a file is successfully fetched. We then create an
	 * {@link IdentityParser} and give it the file content. 
	 */
	public synchronized void onSuccess(FetchResult result, ClientGetter state, ObjectContainer container) {
		Logger.debug(this, "Fetched identity: " + state.getURI());
		
		Bucket bucket = null;
		InputStream inputStream = null;
		
		try {
			bucket = result.asBucket();
			inputStream = bucket.getInputStream();
			mWoT.getXMLTransformer().importIdentity(state.getURI(), inputStream);
		}
		catch (Exception e) {
			Logger.error(this, "Parsing failed for "+ state.getURI(), e);
		}
		finally {
			Closer.close(inputStream);
			// TODO: Wire in when build 1210 is released: Closer.close(bucket);
			if(bucket != null)
				bucket.free();
		}
		
		try {
			state.restart(state.getURI().setSuggestedEdition(state.getURI().getEdition() + 1), null, clientContext);
		}
		catch(Exception e) {
			Logger.error(this, "Error fetching next edition for " + state.getURI());
		}
	}


	// Only called by inserts
	public void onSuccess(BaseClientPutter state, ObjectContainer container) {}
	
	// Only called by inserts
	public void onFailure(InsertException e, BaseClientPutter state, ObjectContainer container) {}

	// Only called by inserts
	public void onFetchable(BaseClientPutter state, ObjectContainer container) {}

	// Only called by inserts
	public void onGeneratedURI(FreenetURI uri, BaseClientPutter state, ObjectContainer container) {}

	/* Called when freenet.async thinks that the request should be serialized to disk, if it is a persistent request. */
	public void onMajorProgress(ObjectContainer container) {}
}
