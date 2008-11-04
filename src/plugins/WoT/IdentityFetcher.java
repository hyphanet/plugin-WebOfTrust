/**
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */

package plugins.WoT;

import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;

import com.db4o.ObjectContainer;

import freenet.client.FetchContext;
import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.HighLevelSimpleClient;
import freenet.client.InsertException;
import freenet.client.async.BaseClientPutter;
import freenet.client.async.ClientCallback;
import freenet.client.async.ClientGetter;
import freenet.keys.FreenetURI;
import freenet.support.Logger;

/**
 * Fetches Identities from Freenet.
 * Contains an ArrayList of all current requests.
 * 
 * @author Julien Cornuwel (batosai@freenetproject.org)
 */
public class IdentityFetcher implements ClientCallback {

        /** A reference to the database */
	private ObjectContainer db;
        /** A reference to the HighLevelSimpleClient used to talk with the node */
	private HighLevelSimpleClient client;
        /** A list of all current requests */
	private ArrayList<ClientGetter> requests;
	
	/**
	 * Creates a new IdentityFetcher.
	 * 
	 * @param db A reference to the database
	 * @param client A reference to a {@link HighLevelSimpleClient}
	 */
	public IdentityFetcher(ObjectContainer db, HighLevelSimpleClient client) {

		this.db = db;
		this.client = client;
		requests = new ArrayList<ClientGetter>();
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
	public void fetch(Identity identity, boolean nextEdition) {
		
		try {
			if(nextEdition && !identity.getLastChange().equals(new Date(0)))
				fetch(identity.getRequestURI().setSuggestedEdition(identity.getRequestURI().getSuggestedEdition() + 1));
			else
				fetch(identity.getRequestURI());
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
	public void fetch(FreenetURI uri) throws FetchException {

		FetchContext fetchContext = client.getFetchContext();
		fetchContext.maxSplitfileBlockRetries = -1; // retry forever
		fetchContext.maxNonSplitfileRetries = -1; // retry forever
		requests.add(client.fetch(uri, -1, this, this, fetchContext));
		Logger.debug(this, "Start fetching identity "+uri.toString());
	}

	/**
	 * Stops all running requests.
	 */
	public void stop() {
		Iterator<ClientGetter> i = requests.iterator();
		Logger.debug(this, "Trying to stop "+requests.size()+" requests");
		while (i.hasNext()) i.next().cancel();
		Logger.debug(this, "Stopped all current requests");
	}
	
	/**
	 * Called when the node can't fetch a file OR when there is a newer edition.
	 * If this is the later, we restart the request.
	 */
	public void onFailure(FetchException e, ClientGetter state) {
		
		if ((e.mode == FetchException.PERMANENT_REDIRECT) || (e.mode == FetchException.TOO_MANY_PATH_COMPONENTS )) {
			// restart the request
			try {
				state.restart(e.newURI);
				// Done. bye.
				return;
			} catch (FetchException e1) {
				Logger.error(this, "Request restart failed: "+e1, e1);
			}
		}
		// Errors we can't/want deal with
		Logger.error(this, "Fetch failed for "+ state.getURI(), e);
		requests.remove(state); 
	}

	// Only called by inserts
	public void onFailure(InsertException e, BaseClientPutter state) {}

	// Only called by inserts
	public void onFetchable(BaseClientPutter state) {}

	// Only called by inserts
	public void onGeneratedURI(FreenetURI uri, BaseClientPutter state) {}

	/** Called when freenet.async thinks that the request should be serialized to
	 * disk, if it is a persistent request. */
	public void onMajorProgress() {}

	/**
	 * Called when a file is successfully fetched. We then create an
	 * {@link IdentityParser} and give it the file content. 
	 */
	public void onSuccess(FetchResult result, ClientGetter state) {
		
		Logger.debug(this, "Fetched key (ClientGetter) : " + state.getURI());

		try {
			Logger.debug(this, "Sucessfully fetched identity "+ state.getURI().toString());
			new IdentityParser(db, client, this).parse(result.asBucket().getInputStream(), state.getURI());
			db.commit();		
			state.restart(state.getURI().setSuggestedEdition(state.getURI().getSuggestedEdition() + 1));
		} catch (Exception e) {
			Logger.error(this, "Parsing failed for "+ state.getURI(), e);
		}
	}

	// Only called by inserts
	public void onSuccess(BaseClientPutter state) {}
}
