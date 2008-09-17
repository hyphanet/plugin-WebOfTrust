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
 * @author Julien Cornuwel (batosai@freenetproject.org)
 *
 */
public class IdentityFetcher implements ClientCallback {

	private ObjectContainer db;
	private HighLevelSimpleClient client;
	private ArrayList<ClientGetter> requests;
	
	public IdentityFetcher(ObjectContainer db, HighLevelSimpleClient client) {

		this.db = db;
		this.client = client;
		requests = new ArrayList<ClientGetter>();
	}
	
	public void fetch(Identity identity) {
		
		fetch(identity, false);
	}
	
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
	
	public void fetch(FreenetURI uri) throws FetchException {

		FetchContext fetchContext = client.getFetchContext();
		fetchContext.maxSplitfileBlockRetries = -1; // retry forever
		fetchContext.maxNonSplitfileRetries = -1; // retry forever
		requests.add(client.fetch(uri, -1, this, this, fetchContext));
		Logger.debug(this, "Start fetching identity "+uri.toString());
	}

	public void stop() {
		Iterator<ClientGetter> i = requests.iterator();
		Logger.debug(this, "Trying to stop "+requests.size()+" requests");
		while (i.hasNext()) i.next().cancel();
		Logger.debug(this, "Stopped all current requests");
	}
	
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

	public void onFailure(InsertException e, BaseClientPutter state) {
		
	}

	public void onFetchable(BaseClientPutter state) {
		
	}

	public void onGeneratedURI(FreenetURI uri, BaseClientPutter state) {
		
	}

	public void onMajorProgress() {
		
	}

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

	public void onSuccess(BaseClientPutter state) {
		
		Logger.debug(this, "Fetched key (BaseClientPutter) : " + state.getURI());		
	}
}
