/**
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */
package plugins.WoT;

import java.net.MalformedURLException;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.query.Query;

/**
 * The Web of Trust
 * 
 * @author Julien Cornuwel (batosai@freenetproject.org)
 * 
 */
public class WoT {
	

	
	ObjectContainer db;

	/**
	 * Implements a variation of the Advogato Trust Metric
	 * The only difference is that we allow negative trust to avoid giving 
	 * capacity to people we dislike...
	 */
	public WoT(ObjectContainer db) {
		this.db = db;
	}

	public int getNbOwnIdentities() {
		return db.queryByExample(OwnIdentity.class).size();
	}

	public int getNbIdentities() {
		return db.queryByExample(Identity.class).size() - getNbOwnIdentities();
	}
	
	public ObjectSet<OwnIdentity> getOwnIdentities() {
		return db.queryByExample(OwnIdentity.class);
	}
	
	public ObjectSet<Identity> getAllIdentities() {
		return db.queryByExample(Identity.class);
	}
		
	@SuppressWarnings("unchecked")
	public ObjectSet<Score> getIdentitiesByScore (String owner, String select) throws InvalidParameterException, MalformedURLException, UnknownIdentityException, DuplicateIdentityException {
		
		OwnIdentity treeOwner = OwnIdentity.getByURI(db, owner);
		
		Query query = db.query();
		query.constrain(Score.class);
		query.descend("treeOwner").constrain(treeOwner);
		
		if(select.equals("+")) {
			query.descend("score").constrain(new Integer(0)).greater();
		}
		else if(select.equals("0")) {
			query.descend("score").constrain(new Integer(0));
		}
		else if(select.equals("-")) {
			query.descend("score").constrain(new Integer(0)).smaller();
		}
		else throw new InvalidParameterException("Unhandled select value ("+select+")");

		return query.execute();
	}
}

