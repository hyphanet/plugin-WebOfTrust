/**
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */

package plugins.WoT;

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
import freenet.node.RequestStarter;
import freenet.support.Logger;

/**
 * Fetches Identities from Freenet.
 * Contains an ArrayList of all current requests.
 * 
 * @author Julien Cornuwel (batosai@freenetproject.org)
 */
public class IdentityFetcher implements ClientCallback {
	
	private final WoT mWoT;

        /** A reference to the database */
	private final ObjectContainer db;
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
	public IdentityFetcher(WoT myWoT) {
		mWoT = myWoT;
		
		db = mWoT.getDB();
		clientContext = mWoT.getClientContext();
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
		if(identities.contains(identity)) 
			return; /* TODO: check whether we should increase the edition in the associated ClientGetter and restart the request or rather wait
					 * for it to finish */

		try {
			if(nextEdition && !identity.getLastChangeDate().equals(new Date(0)))
				fetch(identity.getRequestURI().setSuggestedEdition(identity.getRequestURI().getSuggestedEdition() + 1));
			else
				fetch(identity.getRequestURI());
			
			synchronized(identities) {
				identities.add(identity);
			}
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
		/* FIXME: The client getter complains about "not enough metastrings" when restoring an identity using the webinterface even though uri.toString() shows identity.xml at the end of the URI in the previous line */ 
		if(uri.getAllMetaStrings().length == 0)
			uri.pushMetaString("identity.xml"); 
		ClientGetter g = client.fetch(uri, -1, requestClient, this, fetchContext);
		g.setPriorityClass(RequestStarter.UPDATE_PRIORITY_CLASS, clientContext, null); /* PluginManager defaults to interactive priority */
		synchronized(requests) {
			requests.add(g);
		}
	}

	/**
	 * Stops all running requests.
	 */
	public void stop() {
		Logger.debug(this, "Trying to stop all requests");
		synchronized(requests) {
			Iterator<ClientGetter> i = requests.iterator();
			int counter = 0;		 
			while (i.hasNext()) { i.next().cancel(); i.remove(); ++counter; }
			Logger.debug(this, "Stopped " + counter + " current requests");
		}
	}
	
	private void removeRequest(ClientGetter g) {
		synchronized(requests) {
			//g.cancel(); /* FIXME: is this necessary ? */
			requests.remove(g);
		}
		Logger.debug(this, "Removed request for " + g.getURI());
	}
	
	/**
	 * Called when the node can't fetch a file OR when there is a newer edition.
	 * If this is the later, we restart the request.
	 */
	public void onFailure(FetchException e, ClientGetter state, ObjectContainer container) {
		
		if ((e.mode == FetchException.PERMANENT_REDIRECT) || (e.mode == FetchException.TOO_MANY_PATH_COMPONENTS )) {
			// restart the request
			try {
				state.restart(e.newURI, null, clientContext);
				// Done. bye.
				return;
			} catch (FetchException e1) {
				Logger.error(this, "Request restart failed: "+e1, e1);
			}
		}
		// Errors we can't/want deal with
		Logger.error(this, "Fetch failed for "+ state.getURI(), e);
		
		removeRequest(state);
		removeIdentity(state);
	}

	/**
	 * Called when a file is successfully fetched. We then create an
	 * {@link IdentityParser} and give it the file content. 
	 */
	public void onSuccess(FetchResult result, ClientGetter state, ObjectContainer container) {
		
		Logger.debug(this, "Fetched identity "+ state.getURI().toString());
		
		try {
			mWoT.getIdentityXML().importIdentity(state.getURI(), result.asBucket().getInputStream());
		}
		catch (Exception e) {
			Logger.error(this, "Parsing failed for "+ state.getURI(), e);
		}
		
		try {
			state.restart(state.getURI().setSuggestedEdition(state.getURI().getSuggestedEdition() + 1), null, clientContext);
		}
		catch(Exception e) {
			Logger.error(this, "Error fetching next edition for " + state.getURI());
		}
	}
	
	private void removeIdentity(ClientGetter state) {
		try {
			Identity id = Identity.getByURI(db, state.getURI());
			synchronized(identities) {
				identities.remove(id);
			}
		}
		catch(UnknownIdentityException ex)
		{
			assert(false);
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

	/** Called when freenet.async thinks that the request should be serialized to
	 * disk, if it is a persistent request. */
	public void onMajorProgress(ObjectContainer container) {}
}
