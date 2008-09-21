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

import plugins.WoT.exceptions.DuplicateIdentityException;
import plugins.WoT.exceptions.DuplicateScoreException;
import plugins.WoT.exceptions.DuplicateTrustException;
import plugins.WoT.exceptions.InvalidParameterException;
import plugins.WoT.exceptions.NotInTrustTreeException;
import plugins.WoT.exceptions.NotTrustedException;
import plugins.WoT.exceptions.UnknownIdentityException;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.query.Query;

import freenet.keys.FreenetURI;
import freenet.support.Logger;

/**
 * An identity as handled by the WoT (a USK)
 * 
 * @author Julien Cornuwel (batosai@freenetproject.org)
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

	private String id;

	private FreenetURI requestURI;
	
	private Date lastChange;
	private String nickName;
	private boolean publishTrustList;
	private HashMap<String, String> props;
	private ArrayList<String> contexts;

	/**
	 * Creates an Identity
	 * 
	 * @param requestURI A {@link FreenetURI} to fetch tis Identity 
	 * @param nickName The nickname of this identity
	 * @param publishTrustList Whether this identity publishes its trustList or not
	 * @throws InvalidParameterException if a parameter is invalid
	 */
	public Identity (FreenetURI requestURI, String nickName, String publishTrustList) throws InvalidParameterException {
		
		setRequestURI(requestURI);
		setNickName(nickName);
		setPublishTrustList(publishTrustList);
		props = new HashMap<String, String>();
		contexts = new ArrayList<String>();
		id = getIdFromURI(getRequestURI());
		
		Logger.debug(this, "New identity : " + getNickName());
	}

	/**
	 * Creates an Identity
	 * 
	 * @param requestURI A String that will be converted to {@link FreenetURI} before creating the identity
	 * @param nickName The nickname of this identity
	 * @param publishTrustList Whether this identity publishes its trustList or not
	 * @throws InvalidParameterException if a parameter is invalid
	 * @throws MalformedURLException if the supplied requestURI isn't a valid FreenetURI
	 */
	public Identity (String requestURI, String nickName, String publishTrustList) throws InvalidParameterException, MalformedURLException {
		this(new FreenetURI(requestURI), nickName, publishTrustList);
	}
	

	/**
	 * Loads an identity from the database, querying on its id
	 * 
	 * @param db A reference to the database
	 * @param id The id of the identity to load
	 * @return The identity matching the supplied id
	 * @throws DuplicateIdentityException if there are more than one identity with this id in the database
	 * @throws UnknownIdentityException if there is no identity with this id in the database
	 */
	@SuppressWarnings("unchecked")
	public static Identity getById (ObjectContainer db, String id) throws DuplicateIdentityException, UnknownIdentityException {

		Query query = db.query();
		query.constrain(Identity.class);
		query.descend("id").constrain(id);
		ObjectSet<Identity> result = query.execute();
		
		if(result.size() == 0) throw new UnknownIdentityException(id);
		if(result.size() > 1) throw new DuplicateIdentityException(id);
		return result.next();
	}

	/**
	 * Loads an identity from the database, querying on its requestURI (as String)
	 * 
	 * @param db A reference to the database
	 * @param uri The requestURI of the identity which will be converted to {@link FreenetURI} 
	 * @return The identity matching the supplied requestURI
	 * @throws UnknownIdentityException if there is no identity with this id in the database
	 * @throws DuplicateIdentityException if there are more than one identity with this id in the database
	 * @throws MalformedURLException if the requestURI isn't a valid FreenetURI
	 */
	public static Identity getByURI (ObjectContainer db, String uri) throws UnknownIdentityException, DuplicateIdentityException, MalformedURLException {
		return getByURI(db, new FreenetURI(uri));
	}

	/**
	 * Loads an identity from the database, querying on its requestURI (a valid {@link FreenetURI})
	 * 
	 * @param db A reference to the database
	 * @param uri The requestURI of the identity
	 * @return The identity matching the supplied requestURI
	 * @throws UnknownIdentityException if there is no identity with this id in the database
	 * @throws DuplicateIdentityException if there are more than one identity with this id in the database
	 */
	public static Identity getByURI (ObjectContainer db, FreenetURI uri) throws UnknownIdentityException, DuplicateIdentityException {
		return getById(db, getIdFromURI(uri));
	}

	/**
	 * Counts the number of identities (not OwnIdentities) in the database
	 * 
	 * @param db A reference to the database
	 * @return The number of identities in the database as an integer
	 */
	public static int getNbIdentities(ObjectContainer db) {
		return db.queryByExample(Identity.class).size() - OwnIdentity.getNbOwnIdentities(db);
	}
	
	/**
	 * Returns all identities that are in the database
	 * 
	 * @param db A reference to the database
	 * @return an {@link ObjectSet} containing all identities present in the database 
	 */
	public static ObjectSet<Identity> getAllIdentities(ObjectContainer db) {
		return db.queryByExample(Identity.class);
	}
	
	/**
	 * Generates a unique id from a {@link FreenetURI}. 
	 * It is simply a String representing it's routing key.
	 * We use this to identify identities and perform requests on the database. 
	 * 
	 * @param uri The requestURI of the Identity
	 * @return A string to uniquely identify an Identity
	 */
	public static String getIdFromURI (FreenetURI uri) {
		int begin = uri.toString().indexOf(',') + 1;
		int end = uri.toString().indexOf(',', begin);
		return uri.toString().substring(begin, end);
	}

	
	/**
	 * Gets the score of this identity in a trust tree.
	 * Each {@link OwnIdentity} has its own trust tree.
	 * 
	 * @param treeOwner The owner of the trust tree
	 * @param db A reference to the database
	 * @return The {@link Score} of this Identity in the required trust tree
	 * @throws NotInTrustTreeException if this identity is not in the required trust tree 
	 * @throws DuplicateScoreException if thid identity has more than one Score objects for that trust tree in the database (should never happen)
	 */
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

	/**
	 * Gets all this Identity's Scores. 
	 * 
	 * @param db A reference to the database
	 * @return An {@link ObjectSet} containing all Scores this Identity has.
	 */
	@SuppressWarnings("unchecked")
	public ObjectSet<Score> getScores(ObjectContainer db) {
		Query query = db.query();
		query.constrain(Score.class);
		query.descend("target").constrain(this);
		return query.execute();
	}
		
	/**
	 * Gets {@link Trust} this Identity received from a specified truster
	 * @param truster The identity that gives trust to this Identity
	 * @param db  A reference to the database
	 * @return The trust given to this identity by the specified truster
	 * @throws NotTrustedException if the truster doesn't trust this identity
	 * @throws DuplicateTrustException if there are more than one Trust object between these identities in the database (should never happen)
	 */
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
	
	public long getNbReceivedTrusts(ObjectContainer db) {
		return getReceivedTrusts(db).size();
	}
	
	@SuppressWarnings("unchecked")
	public ObjectSet<Trust> getGivenTrusts(ObjectContainer db) {
		Query query = db.query();
		query.constrain(Trust.class);
		query.descend("truster").constrain(this);
		return query.execute();
	}
	
	public long getNbGivenTrusts(ObjectContainer db) {
		return getGivenTrusts(db).size();
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
				Logger.debug(this, "Updated trust value ("+ trust +"), now updating Score.");
				trustee.updateScore(db);
			}
		} catch (NotTrustedException e) {
			trust = new Trust(this, trustee, value, comment);
			db.store(trust);
			Logger.debug(this, "New trust value ("+ trust +"), now updating Score.");
			trustee.updateScore(db);
		} 
	}
	
	public void updateScore (ObjectContainer db) throws DuplicateScoreException, DuplicateTrustException {
		ObjectSet<OwnIdentity> treeOwners = OwnIdentity.getAllOwnIdentities(db);
		if(treeOwners.size() == 0) Logger.error(this, "Can't update "+ getNickName()+"'s score : there is no own identity yet");
		while(treeOwners.hasNext())
			updateScore (db, treeOwners.next());
	}
	
	public void updateScore (ObjectContainer db, OwnIdentity treeOwner) throws DuplicateScoreException, DuplicateTrustException {
		
		if(this == treeOwner) return;
		
		boolean hasNegativeTrust = false;
		boolean changedCapacity = false;
		Score score;
		
		Logger.debug(this, "Updating " + getNickName() + "'s score in " + treeOwner.getNickName() + "'s trust tree");
		
		int value = computeScoreValue(db, treeOwner);
		int rank = computeRank(db, treeOwner);
		
		if(rank == -1) { // -1 value means the identity is not in the trust tree
			try { // If he had a score, we delete it
				score = getScore(treeOwner, db);
				db.delete(score); // He had a score, we delete it
				changedCapacity = true;
				Logger.debug(this, getNickName() + " is not in " + treeOwner.getNickName() + "'s trust tree anymore");
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
				if(getReceivedTrust(treeOwner, db).getValue() < 0) {
					hasNegativeTrust = true;
					Logger.debug(this, getNickName() + " received negative trust from " + treeOwner.getNickName() + " and therefore has no capacity in his trust tree.");
				}
			} catch (NotTrustedException e) {}
			
			if(hasNegativeTrust) score.setCapacity(0);
			else score.setCapacity((score.getRank() >= capacities.length) ? 1 : capacities[score.getRank()]);
			
			if(score.getCapacity() != oldCapacity) changedCapacity = true;
			db.store(score);
			Logger.debug(this, score.toString());
		}
		
		if(changedCapacity) { // We have to update trustees' score
			ObjectSet<Trust> trustees = getGivenTrusts(db);
			Logger.debug(this, getNickName() + "'s capacity has changed in " + treeOwner.getNickName() + "'s trust tree, updating his ("+trustees.size()+") trustees");
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
	
	public String getId() {
		return id;
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
