/**
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */
package plugins.WoT;

import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map.Entry;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.query.Query;

import freenet.keys.FreenetURI;
import freenet.support.ByteArrayWrapper;
import freenet.support.Logger;

/**
 * An identity as handled by the WoT (a USK)
 * 
 * @author Julien Cornuwel (batosai@freenetproject.org)
 *
 */
public class Identity {
	
	// Capacity is the maximum amount of points an identity can give to an other by trusting it.
	public final static int capacities[] = {
			100,// Rank 0 : Own identities
			40,	// Rank 1 : Identities directly trusted by ownIdenties
			16, // Rank 2 : Identities trusted by rank 1 identities
			6,	// So on...
			2,
			1	// Every identity above rank 5 can give 1 point
	};			// Identities with negative score have zero capacity

	@SuppressWarnings("unused")
	private ByteArrayWrapper id;
	private FreenetURI requestURI;
	
	private Date lastChange;
	private String nickName;
	private boolean publishTrustList;
	private HashMap<String, String> props;
	private ArrayList<String> contexts;

	
	public Identity (FreenetURI requestURI, String nickName, String publishTrustList, String context) throws InvalidParameterException {
		
		setRequestURI(requestURI);
		setNickName(nickName);
		setPublishTrustList(publishTrustList);
		props = new HashMap<String, String>();
		contexts = new ArrayList<String>();
		contexts.add(context);
		id = new ByteArrayWrapper(getRequestURI().getRoutingKey());
	}

	public Identity (String requestURI, String nickName, String publishTrustList, String context) throws InvalidParameterException, MalformedURLException {
		this(new FreenetURI(requestURI), nickName, publishTrustList, context);
	}
	

	
	@SuppressWarnings("unchecked")
	public static Identity getById (ObjectContainer db, ByteArrayWrapper id) throws DuplicateIdentityException, UnknownIdentityException {

		Query query = db.query();
		query.constrain(Identity.class);
		query.descend("id").constrain(id);
		ObjectSet<Identity> result = query.execute();
		
		if(result.size() == 0) throw new UnknownIdentityException(id.toString());
		if(result.size() > 1) throw new DuplicateIdentityException(id.toString());
		return result.next();
	}
	
	public static Identity getByURI (ObjectContainer db, String uri) throws UnknownIdentityException, DuplicateIdentityException, MalformedURLException {
		return getByURI(db, new FreenetURI(uri));
	}
	
	public static Identity getByURI (ObjectContainer db, FreenetURI uri) throws UnknownIdentityException, DuplicateIdentityException {
		return getById(db, new ByteArrayWrapper(uri.getRoutingKey()));
	}

	public static int getNbIdentities(ObjectContainer db) {
		return db.queryByExample(Identity.class).size() - OwnIdentity.getNbOwnIdentities(db);
	}
	
	public static ObjectSet<Identity> getAllIdentities(ObjectContainer db) {
		return db.queryByExample(Identity.class);
	}
	
	
	
	@SuppressWarnings("unchecked")
	public Score getScore(OwnIdentity treeOwner, ObjectContainer db) throws NotInTrustTreeException, DuplicateScoreException {
		Query query = db.query();
		query.constrain(Score.class);
		query.descend("treeOwner").constrain(treeOwner).and(query.descend("target").constrain(this));
		ObjectSet<Score> result = query.execute();
		
		if(result.size() == 0) throw new NotInTrustTreeException(this.getRequestURI().toString()+" is not in that trust tree");
		else if(result.size() > 1) throw new DuplicateScoreException(this.getRequestURI().toString()+" ("+ getNickName() +") has "+result.size()+" scores in "+treeOwner.getNickName()+"'s trust tree"); 
		else return result.next();
	}
	
	@SuppressWarnings("unchecked")
	public Trust getReceivedTrust(Identity truster, ObjectContainer db) throws NotTrustedException, DuplicateTrustException {
		Query query = db.query();
		query.constrain(Trust.class);
		query.descend("truster").constrain(truster).and(query.descend("trustee").constrain(this));
		ObjectSet<Trust> result = query.execute();
		
		if(result.size() == 0) throw new NotTrustedException(truster.getNickName() + " does not trust " + this.getNickName());
		else if(result.size() > 1) throw new DuplicateTrustException("Trust from " + truster.getNickName() + "to " + this.getNickName() + " exists " + result.size() + " times in the database");
		else return result.next();
	}

	@SuppressWarnings("unchecked")
	public Trust getGivenTrust(Identity trustee, ObjectContainer db) throws NotTrustedException, DuplicateTrustException {
		Query query = db.query();
		query.constrain(Trust.class);
		query.descend("truster").constrain(this).and(query.descend("trustee").constrain(trustee));
		ObjectSet<Trust> result = query.execute();
		
		if(result.size() == 0) throw new NotTrustedException(trustee.getNickName() + " does not trust " + this.getNickName());
		else if(result.size() > 1) throw new DuplicateTrustException("Trust from " + this.getNickName() + "to " + trustee.getNickName() + " exists " + result.size() + " times in the database");
		else return result.next();
	}
	
	@SuppressWarnings("unchecked")
	public ObjectSet<Trust> getReceivedTrusts(ObjectContainer db) {
		Query query = db.query();
		query.constrain(Trust.class);
		query.descend("trustee").constrain(this);
		return query.execute();
	}
	
	@SuppressWarnings("unchecked")
	public ObjectSet<Trust> getGivenTrusts(ObjectContainer db) {
		Query query = db.query();
		query.constrain(Trust.class);
		query.descend("truster").constrain(this);
		return query.execute();
	}

	public void setTrust(ObjectContainer db, Identity trustee, int value, String comment) throws DuplicateTrustException, InvalidParameterException, DuplicateScoreException {
		// Check if we are updating an existing trust value
		Trust trust;
		try {
			trust = getGivenTrust(trustee, db);
			
			if(!trust.getComment().equals(comment)) {
				trust.setComment(comment);
				db.store(trust);
			}
			
			if(trust.getValue() != value) {
				trust.setValue(value);
				db.store(trust);
				Logger.debug(this, "Updated trust value, now updating Score.");
				trustee.updateScore(db);
			}
		} catch (NotTrustedException e) {
			trust = new Trust(this, trustee, value, comment);
			db.store(trust);
			Logger.debug(this, "New trust value, now updating Score.");
			trustee.updateScore(db);
		} 
	}
	
	public void updateScore (ObjectContainer db) throws DuplicateScoreException, DuplicateTrustException {
		ObjectSet<OwnIdentity> treeOwners = OwnIdentity.getAllOwnIdentities(db);
		while(treeOwners.hasNext())
			updateScore (db, treeOwners.next());
	}
	
	public void updateScore (ObjectContainer db, OwnIdentity treeOwner) throws DuplicateScoreException, DuplicateTrustException {
		
		if(this == treeOwner) return;
		
		boolean hasNegativeTrust = false;
		boolean changedCapacity = false;
		Score score;
		
		int value = computeScoreValue(db, treeOwner);
		int rank = computeRank(db, treeOwner);
		
		if(rank == -1) { // -1 value means the identity is not in the trust tree
			try { // If he had a score, we delete it
				score = getScore(treeOwner, db);
				db.delete(score); // He had a score, we delete it
				changedCapacity = true;
			} catch (NotInTrustTreeException e) {} 
		}
		else { // The identity is in the trust tree
			
			try { // Get existing score or create one if needed
				score = getScore(treeOwner, db);
			} catch (NotInTrustTreeException e) {
				score = new Score(treeOwner, this, 0, -1, 0);
			}
			
			score.setScore(value);
			score.setRank(rank + 1);
			int oldCapacity = score.getCapacity();
			
			// Does the treeOwner personally distrust this identity ?
			try {
				if(getReceivedTrust(treeOwner, db).getValue() < 0) hasNegativeTrust = true;
			} catch (NotTrustedException e) {}
			
			if(hasNegativeTrust) score.setCapacity(0);
			else score.setCapacity(capacities[score.getRank()]);
			
			if(score.getCapacity() != oldCapacity) changedCapacity = true;
			db.store(score);
		}
		
		if(changedCapacity) { // We have to update trustees' score
			ObjectSet<Trust> trustees = getGivenTrusts(db);
			while(trustees.hasNext()) trustees.next().getTrustee().updateScore(db, treeOwner);
		}
	}
	
	public int computeScoreValue(ObjectContainer db, OwnIdentity treeOwner) throws DuplicateScoreException {
		int value = 0;
		
		ObjectSet<Trust> receivedTrusts = getReceivedTrusts(db);
		while(receivedTrusts.hasNext()) {
			Trust trust = receivedTrusts.next();
			try {
				value += trust.getValue() * trust.getTruster().getScore(treeOwner, db).getCapacity() / 100;
			} catch (NotInTrustTreeException e) {}
		}
		return value;
	}
	
	public int computeRank(ObjectContainer db, OwnIdentity treeOwner) throws DuplicateScoreException {
		int rank = -1;
		
		ObjectSet<Trust> receivedTrusts = getReceivedTrusts(db);
		while(receivedTrusts.hasNext()) {
			Trust trust = receivedTrusts.next();
			try {
				Score score = trust.getTruster().getScore(treeOwner, db);
				if(score.getCapacity() != 0) // If the truster has no capacity, he can't give his rank
					if(rank == -1 || score.getRank() < rank) // If the truster's rank is better than ours or if we have not  
						rank = score.getRank();
			} catch (NotInTrustTreeException e) {}
		}
		return rank;
	}
	
	public void setRequestURI(FreenetURI requestURI) throws InvalidParameterException {
		if(requestURI.getKeyType().equals("SSK")) requestURI = requestURI.setKeyType("USK");
		if(!requestURI.getKeyType().equals("USK")) throw new InvalidParameterException("Key type not supported");
		this.requestURI = requestURI.setKeyType("USK").setDocName("WoT");
		updated();
	}
	
	public void setEdition(long edition) throws InvalidParameterException {
		setRequestURI(getRequestURI().setSuggestedEdition(edition));
	}

	public void setNickName(String nickName) throws InvalidParameterException {
		String nick = nickName.trim();
		if(nick.length() == 0) throw new InvalidParameterException("Blank nickName");
		if(nick.length() > 50) throw new InvalidParameterException("NickName is too long (50 chars max)");
		this.nickName = nick;
		updated();
	}

	public void setPublishTrustList(boolean publishTrustList) {
		this.publishTrustList = publishTrustList;
		updated();
	}

	public void setPublishTrustList(String publishTrustList) {
		setPublishTrustList(publishTrustList.equals("true"));
	}
	
	public void setProp(String key, String value, ObjectContainer db) throws InvalidParameterException {
		if(key.trim().length() == 0 || value.trim().length() == 0) throw new InvalidParameterException("Blank key or value in this property");
		props.put(key.trim(), value.trim());
		db.store(props);
		updated();
	}
	
	public void removeProp(String key, ObjectContainer db) throws InvalidParameterException {
		if(!props.containsKey(key)) throw new InvalidParameterException("Property '"+key+"' isn't set on this identity");
		props.remove(key.trim());
		db.store(props);
		updated();
	}
	
	public Iterator<Entry<String, String>> getProps() {
		Iterator<Entry<String, String>> i = props.entrySet().iterator();
		return i;
	}
	
	public void addContext(String context, ObjectContainer db) throws InvalidParameterException {
		String newContext = context.trim();
		if(newContext.length() == 0) throw new InvalidParameterException("Blank context");
		if(!contexts.contains(newContext)) contexts.add(newContext);
		db.store(contexts);
		updated();
	}
	
	public void removeContext(String context, ObjectContainer db) throws InvalidParameterException {
		if(contexts.size() == 1) throw new InvalidParameterException("Only one context left");
		contexts.remove(context);
		db.store(contexts);
		updated();
	}
	
	public Iterator<String> getContexts() {
		return contexts.iterator();
	}
		
	public void updated() {
		lastChange = new Date();
	}

	public FreenetURI getRequestURI() {
		return requestURI.setMetaString(new String[] {"identity.xml"} );
	}

	public Date getLastChange() {
		return lastChange;
	}

	public String getNickName() {
		return nickName;
	}

	public boolean doesPublishTrustList() {
		return publishTrustList;
	}

	public String getContextsAsString() {
		return contexts.toString();
	}
	
	public String getProp(String key) throws InvalidParameterException {
		if(!props.containsKey(key)) throw new InvalidParameterException("Property '"+key+"' isn't set on this identity");
		return props.get(key);
	}
	
	public String getPropsAsString() {
		return props.toString();
	}
	
	public boolean hasContext(String context) {
		return contexts.contains(context.trim());
	}
	

}
