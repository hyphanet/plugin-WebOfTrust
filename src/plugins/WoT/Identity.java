/**
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */
package plugins.WoT;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.TimeZone;
import java.util.Map.Entry;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

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
import freenet.support.Base64;
import freenet.support.Logger;

/**
 * An identity as handled by the WoT (a USK). 
 * <p>
 * It has a nickName and as many custom properties as needed (set by the user).
 * 
 * @author Julien Cornuwel (batosai@freenetproject.org)
 * @param id A unique identifier used to query an Identity from the database
 * @param requestURI The requestURI used to fetch this identity from Freenet
 * @param nickName The nickname of an Identity
 * @param publishTrustList Whether an identity publishes its trust list or not
 * @param props A HashMap containing all custom properties o an Identity
 * @param contexts An ArrayList containing contexts (eg. client apps) an Identity is used for
 */
public class Identity {
	/** Capacity is the maximum amount of points an identity can give to an other by trusting it. 
	 * 
	 * Values choice :
	 * Advogato Trust metric recommends that values decrease by rounded 2.5 times.
	 * This makes sense, making the need of 3 N+1 ranked people to overpower
	 * the trust given by a N ranked identity.
	 * 
	 * Number of ranks choice :
	 * When someone creates a fresh identity, he gets the seed identity at
	 * rank 1 and freenet developpers at rank 2. That means that
	 * he will see people that were :
	 * - given 7 trust by freenet devs (rank 2)
	 * - given 17 trust by rank 3
	 * - given 50 trust by rank 4
	 * - given 100 trust by rank 5 and above.
	 * This makes the range small enough to avoid a newbie
	 * to even see spam, and large enough to make him see a reasonnable part
	 * of the community right out-of-the-box.
	 * Of course, as soon as he will start to give trust, he will put more
	 * people at rank 1 and enlarge his WoT.
	 */
	public final static int capacities[] = {
			100,// Rank 0 : Own identities
			40,	// Rank 1 : Identities directly trusted by ownIdenties
			16, // Rank 2 : Identities trusted by rank 1 identities
			6,	// So on...
			2,
			1	// Every identity above rank 5 can give 1 point
	};			// Identities with negative score have zero capacity

	/**
	 * A unique identifier used to query this Identity from the database.
	 * In fact, it is simply a String representing its routing key.
	 */
	private final String id;
	
	/** The requestURI used to fetch this identity from Freenet */
	private FreenetURI requestURI;
	
	/** Date of this identity's last modification (last time we fetched it from Freenet) */
	private Date lastChange;
	
	/** The nickname of this Identity */
	private String nickName;
	
	/** Whether this Identity publishes its trust list or not */
	private boolean publishTrustList;
	
	/** A list of this Identity's custom properties */
	private HashMap<String, String> props;
	
	/** A list of contexts (eg. client apps) this Identity is used for */
	private ArrayList<String> contexts;
	
	/** UTC calendar */
	protected static final Calendar mCalendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));

	/**
	 * Creates an Identity
	 * 
	 * @param requestURI A {@link FreenetURI} to fetch this Identity 
	 * @param nickName The nickname of this identity
	 * @param publishTrustList Whether this identity publishes its trustList or not
	 * @throws InvalidParameterException if a supplied parameter is invalid
	 */
	public Identity (FreenetURI newRequestURI, String newNickName, boolean publishTrustList) throws InvalidParameterException {
		setRequestURI(newRequestURI);
		id = getIdFromURI(getRequestURI());
		setNickName(newNickName);
		setPublishTrustList(publishTrustList);
		props = new HashMap<String, String>();
		contexts = new ArrayList<String>();
		
		Logger.debug(this, "New identity : " + getNickName());
	}	

	/**
	 * Creates an Identity
	 * 
	 * @param requestURI A String that will be converted to {@link FreenetURI} before creating the identity
	 * @param nickName The nickname of this identity
	 * @param publishTrustList Whether this identity publishes its trustList or not
	 * @throws InvalidParameterException if a supplied parameter is invalid
	 * @throws MalformedURLException if the supplied requestURI isn't a valid FreenetURI
	 */
	public Identity (String requestURI, String nickName, boolean publishTrustList) throws InvalidParameterException, MalformedURLException {
		this(new FreenetURI(requestURI), nickName, publishTrustList);
	}
	
	/**
	 * Create an identity from an identity introduction.
	 * Please commit() after calling the function!
	 * @param db
	 * @param fetcher
	 * @param is
	 * @return
	 * @throws ParserConfigurationException
	 * @throws SAXException
	 * @throws IOException
	 * @throws InvalidParameterException
	 */
	public static Identity importIntroductionFromXML(ObjectContainer db, IdentityFetcher fetcher, InputStream is) throws ParserConfigurationException, SAXException, IOException, InvalidParameterException {
		IntroductionHandler introHandler = new IntroductionHandler();
		SAXParserFactory.newInstance().newSAXParser().parse(is, introHandler);
		
		Identity id;
		FreenetURI requestURI = introHandler.getRequestURI();
		
		synchronized(Identity.class) {	/* Prevent creation of duplicate identities. FIXME: Find a better object to lock on. */
			try {
				id = Identity.getByURI(db, requestURI);
			}
			catch (UnknownIdentityException e) {
				id = new Identity(requestURI, null, false);
				db.store(id);
				fetcher.fetch(id);
			}
		}

		return id;
	}
	
	private static class IntroductionHandler extends DefaultHandler {
		private FreenetURI requestURI;

		public IntroductionHandler() {
			super();
		}

		/**
		 * Called by SAXParser for each XML element.
		 */
		public void startElement(String nameSpaceURI, String localName, String rawName, Attributes attrs) throws SAXException {
			String elt_name = rawName == null ? localName : rawName;

			try {
				if (elt_name.equals("Identity")) {
					requestURI = new FreenetURI(attrs.getValue("value"));
				}				
				else
					Logger.error(this, "Unknown element in identity introduction: " + elt_name);
				
			} catch (Exception e1) {
				Logger.error(this, "Parsing error",e1);
			}
		}

		public FreenetURI getRequestURI() {
			return requestURI;
		}
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
	public static Identity getById (ObjectContainer db, String id) throws UnknownIdentityException {

		Query query = db.query();
		query.constrain(Identity.class);
		query.descend("id").constrain(id);
		ObjectSet<Identity> result = query.execute();
		
		if(result.size() == 0) throw new UnknownIdentityException(id);
		else if(result.size() > 1) throw new DuplicateIdentityException(id);
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
	public static Identity getByURI (ObjectContainer db, String uri) throws UnknownIdentityException, MalformedURLException {
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
	public static Identity getByURI (ObjectContainer db, FreenetURI uri) throws UnknownIdentityException {
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
		/* WARNING: When changing this, also update Freetalk.WoT.WoTIdentity.getUIDFromURI()! */
		return Base64.encode(uri.getRoutingKey());
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
	

	/* 
	 * FIXME:
	 * I suggest before releasing we should write a getRealScore() function which recalculates the score from all Trust objects which are stored
	 * in the database. We could then assert(getScore() == getRealScore()) for verifying that the database is consistent and watch for some time
	 * whether it stays consistent, just to make sure that there are no flaws in the code.
	 */
	

	/**
	 * Gets all this Identity's Scores. 
	 * 
	 * @param db A reference to the database
	 * @return An {@link ObjectSet} containing all {@link Score} this Identity has.
	 */
	@SuppressWarnings("unchecked")
	public ObjectSet<Score> getScores(ObjectContainer db) {
		Query query = db.query();
		query.constrain(Score.class);
		query.descend("target").constrain(this);
		return query.execute();
	}

	/**
	 * Gets the best score this Identity has in existing trust trees.
	 * 
	 * @param db A reference to the database
	 * @return the best score this Identity has
	 */
	public int getBestScore(ObjectContainer db) {
		int bestScore = 0;
		ObjectSet<Score> scores = getScores(db);
		while(scores.hasNext()) {
			Score score = scores.next();
			if(score.getScore() > bestScore) bestScore = score.getScore();
		}
		return bestScore;
	}
		
	/**
	 * Gets {@link Trust} this Identity receives from a specified truster
	 * 
	 * @param truster The identity that gives trust to this Identity
	 * @param db A reference to the database
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

	/**
	 * Gets {@link Trust} this Identity gives to a specified trustee
	 * 
	 * @param trustee The identity that receives trust from this Identity
	 * @param db A reference to the database
	 * @return The trust given by this identity to the specified trustee
	 * @throws NotTrustedException if this identity doesn't trust the trustee
	 * @throws DuplicateTrustException if there are more than one Trust object between these identities in the database (should never happen)
	 */
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
	
	/**
	 * Gets all trusts received by this Identity
	 * 
	 * @param db A reference to the database
	 * @return An {@link ObjectSet} containing all {@link Trust} this Identity has received.
	 */
	@SuppressWarnings("unchecked")
	public ObjectSet<Trust> getReceivedTrusts(ObjectContainer db) {
		Query query = db.query();
		query.constrain(Trust.class);
		query.descend("trustee").constrain(this);
		return query.execute();
	}
	
	/**
	 * Gets the number of Trusts received by this Identity
	 * 
	 * @param db A reference to the database
	 * @return The number of Trusts received by this Identity
	 */
	public long getNbReceivedTrusts(ObjectContainer db) {
		return getReceivedTrusts(db).size();
	}
	
	/**
	 * Gets all trusts given by this Identity
	 * 
	 * @param db A reference to the database
	 * @return An {@link ObjectSet} containing all {@link Trust} this Identity has given.
	 */
	@SuppressWarnings("unchecked")
	public ObjectSet<Trust> getGivenTrusts(ObjectContainer db) {
		Query query = db.query();
		query.constrain(Trust.class);
		query.descend("truster").constrain(this);
		return query.execute();
	}
	
	/**
	 * Gets the number of Trusts given by this Identity
	 * 
	 * @param db A reference to the database
	 * @return The number of Trusts given by this Identity
	 */
	public long getNbGivenTrusts(ObjectContainer db) {
		return getGivenTrusts(db).size();
	}

	/**
	 * Gives some {@link Trust} to another Identity.
	 * It creates or updates an existing Trust object and make the trustee compute its {@link Score}.
	 * 
	 * @param db A reference to the database
	 * @param trustee The Identity that receives the trust
	 * @param value Numeric value of the trust
	 * @param comment A comment to explain the given value
	 * @throws DuplicateTrustException if there already exist more than one {@link Trust} objects between these identities (should never happen)
	 * @throws InvalidParameterException if a given parameter isn't valid, {@see Trust} for details on accepted values.
	 * @throws DuplicateScoreException if there already exist more than one {@link Score} objects for the trustee (should never happen)
	 */
	public void setTrust(ObjectContainer db, Identity trustee, byte value, String comment) throws DuplicateTrustException, InvalidParameterException, DuplicateScoreException {
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
	
	public void removeTrust(ObjectContainer db, Identity trustee) {
		// Check if we are updating an existing trust value
		Trust trust;
		try {
			trust = getGivenTrust(trustee, db);
			
			db.delete(trust);
			db.commit();
			trustee.updateScore(db);
		} catch (NotTrustedException e) {
			Logger.debug(this, "Cannot remove trust - there is none - from " + this.getNickName() + " to " + trustee.getNickName());
		} 
	}

	/**
	 * Updates this Identity's {@link Score} in every trust tree.
	 * 
	 * @param db A reference to the database
	 * @throws DuplicateScoreException if there already exist more than one {@link Score} objects for the trustee (should never happen)
	 * @throws DuplicateTrustException if there already exist more than one {@link Trust} objects between these identities (should never happen)
	 */
	public synchronized void updateScore (ObjectContainer db) throws DuplicateScoreException, DuplicateTrustException {
		ObjectSet<OwnIdentity> treeOwners = OwnIdentity.getAllOwnIdentities(db);
		if(treeOwners.size() == 0) Logger.debug(this, "Can't update "+ getNickName()+"'s score : there is no own identity yet");
		while(treeOwners.hasNext())
			updateScore (db, treeOwners.next());
	}
	
	/**
	 * Updates this Identity's {@link Score} in one trust tree.
	 * Makes this Identity's trustees update their score if its capacity has changed.
	 * 
	 * @param db A reference to the database
	 * @param treeOwner The OwnIdentity that owns the trust tree
	 * @throws DuplicateScoreException if there already exist more than one {@link Score} objects for the trustee (should never happen)
	 * @throws DuplicateTrustException if there already exist more than one {@link Trust} objects between these identities (should never happen)
	 */
	public synchronized void updateScore (ObjectContainer db, OwnIdentity treeOwner) throws DuplicateScoreException, DuplicateTrustException {
		
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
	
	/**
	 * Computes this Identity's Score value according to the trusts it has received and the capacity of its trusters in the specified trust tree.
	 * 
	 * @param db A reference to the database
	 * @param treeOwner The OwnIdentity that owns the trust tree
	 * @return The new Score if this Identity
	 * @throws DuplicateScoreException if there already exist more than one {@link Score} objects for the trustee (should never happen)
	 */
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
	
	/**
	 * Computes this Identity's rank in the trust tree.
	 * It gets its best ranked truster's rank, plus one. Or -1 if none of its trusters are in the trust tree. 
	 *  
	 * @param db A reference to the database
	 * @param treeOwner The OwnIdentity that owns the trust tree
	 * @return The new Rank if this Identity
	 * @throws DuplicateScoreException if there already exist more than one {@link Score} objects for the trustee (should never happen)
	 */
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
	
	/**
	 * Sets the requestURI of this Identity.
	 * The given {@link FreenetURI} is converted to USK and the docName is forced to WoT.
	 * 
	 * @param requestURI The FreenetURI used to fetch this identity 
	 * @throws InvalidParameterException if the given FreenetURI is neither a SSK nor a USK
	 */
	protected synchronized void setRequestURI(FreenetURI newRequestURI) throws InvalidParameterException {
		if(requestURI != null && !newRequestURI.equalsKeypair(requestURI))
			throw new InvalidParameterException("Cannot change the request URI of an existing identity");
		
		if(newRequestURI.getKeyType().equals("SSK")) newRequestURI = newRequestURI.setKeyType("USK");
		if(!newRequestURI.getKeyType().equals("USK")) throw new InvalidParameterException("Key type not supported");
		requestURI = newRequestURI.setKeyType("USK").setDocName("WoT");
		updated();
	}
	
	/**
	 * Sets the edition of the last fetched version of this identity.
	 * That number is published in trustLists to limit the number of editions a newbie has to fetch before he actually gets ans Identity.
	 * 
	 * @param edition A long representing the last fetched version of this identity.
	 * @throws InvalidParameterException
	 */
	public synchronized void setEdition(long edition) throws InvalidParameterException {
		setRequestURI(getRequestURI().setSuggestedEdition(edition));
	}
	
	public synchronized long getEdition() {
		return getRequestURI().getSuggestedEdition();
	}

	/**
	 * Sets the nickName of this Identity. 
	 * 
	 * @param nickName A String containing this Identity's NickName. Setting it to null means that it was not retrieved yet.
	 * @throws InvalidParameterException if the nickName's length is bigger than 50, or if it empty
	 */
	public synchronized void setNickName(String newNickname) throws InvalidParameterException {
		if(newNickname != null)
			newNickname = newNickname.trim();
		
		if(newNickname != null) {
			if(newNickname.length() == 0) throw new InvalidParameterException("Blank nickname");
			if(newNickname.length() > 50) throw new InvalidParameterException("Nickname is too long (50 chars max)");
		}
		
		if(!isNicknameValid(newNickname)) {
			throw new InvalidParameterException("Nickname contains illegal characters.");
		}
		
		if(nickName != null && !nickName.equals(newNickname))
			throw new InvalidParameterException("Changing the nickname of an identity is not allowed.");
	
		nickName = newNickname;
		updated();
	}
	
	public synchronized boolean isNicknameValid(String newNickname) {
		/* FIXME: Add more checks! */
		if(newNickname.matches("[<> \r\n]"))
			return false;
		
		return true;
	}

	/**
	 * Sets if this Identity publishes its trustList or not
	 * 
	 * @param publishTrustList 
	 */
	public synchronized void setPublishTrustList(boolean publishTrustList) {
		this.publishTrustList = publishTrustList;
		updated();
	}
	
	/**
	 * Sets a custom property on this Identity. Custom properties keys have to be unique.
	 * This can be used by client apps that need to put additionnal informations on their Identities (crypto keys, avatar, whatever...)
	 * 
	 * @param key Name of the custom property
	 * @param value Value of the property
	 * @param db A reference to the database 
	 * @throws InvalidParameterException if the key or the value is empty
	 */
	public synchronized void setProp(String key, String value, ObjectContainer db) throws InvalidParameterException {
		if(key.trim().length() == 0 || value.trim().length() == 0) throw new InvalidParameterException("Blank key or value in this property");
		String oldValue = props.get(key.trim());
		if(oldValue == null || oldValue.equals(value.trim()) == false) {
			props.put(key.trim(), value.trim());
			db.store(props);
			updated();
		}
	}
	
	/**
	 * Removes a custom property from this Identity
	 * 
	 * @param key Name of the custom property
	 * @param db A reference to the database 
	 * @throws InvalidParameterException if this Identity doesn't have the given property
	 */
	public synchronized void removeProp(String key, ObjectContainer db) throws InvalidParameterException {
		if(!props.containsKey(key)) throw new InvalidParameterException("Property '"+key+"' isn't set on this identity");
		props.remove(key.trim());
		db.store(props);
		updated();
	}
	
	/**
	 * Gets all custom properties from this Identity
	 * 
	 * @return An Iterator referencing all this Identity's custom properties
	 */
	public synchronized Iterator<Entry<String, String>> getProps() {
		Iterator<Entry<String, String>> i = props.entrySet().iterator();
		return i;
	}
	
	/**
	 * Adds a context to this identity.
	 * Contexts are used by clients to identify what identities are relevant for their use.
	 * Example : A filesharing app sets a 'filesharing' context on its identities,
	 * so it only has to fetch files lists from relevant Identities.
	 * 
	 * @param context Name of the context
	 * @param db A reference to the database
	 * @throws InvalidParameterException if the context name is empty
	 */
	public synchronized void addContext(String context, ObjectContainer db) throws InvalidParameterException {
		String newContext = context.trim();
		if(newContext.length() == 0) throw new InvalidParameterException("Blank context");
		if(!contexts.contains(newContext)) {
			contexts.add(newContext);
			db.store(contexts);
			updated();
		}
	}
	
	/**
	 * Removes a context from this Identity.
	 * If this Identity is no longer used by a client app, 
	 * the user can tell it and others won't try to fetch it anymore.
	 * 
	 * @param context Name of the context
	 * @param db A reference to the database
	 * @throws InvalidParameterException if the client tries to remove the last context of this Identity (an identity with no context is useless)
	 */
	public synchronized void removeContext(String context, ObjectContainer db) throws InvalidParameterException {
		if(contexts.size() == 1) throw new InvalidParameterException("Only one context left");
		context = context.trim();
		if(contexts.contains(context)) {
			contexts.remove(context);
			db.store(contexts);
			updated();
		}
	}

	/**
	 * Gets all this Identity's contexts
	 * 
	 * @return An Iterator referencing all this identity's contexts
	 */
	public synchronized Iterator<String> getContexts() {
		return contexts.iterator();
	}
		
	/**
	 * Tell that this Identity has been updated.
	 */
	public synchronized void updated() {
		
		lastChange = mCalendar.getTime();
	}
	
	/**
	 * Gets this Identity's id
	 * @return A unique identifier for this Identity
	 */
	public String getId() {
		return id;
	}

	/**
	 * @return The requestURI ({@link FreenetURI}) to fetch this Identity 
	 */
	public synchronized FreenetURI getRequestURI() {
		return requestURI.setMetaString(new String[] {"identity.xml"} );
	}

	/**
	 * @return The date of this Identity's last modification
	 */
	public synchronized Date getLastChange() {
		return lastChange;
	}
	
	/* TODO: I think this is not the job of class Identity, remove this.

	 * @return A string representing the date of this Identity's last 
	 * modification. Or "Fetching..." if it has not been fetched yet.

	public synchronized String getReadableLastChange() {
		if (lastChange.equals(new Date(0))) return "Fetching...";
		else return lastChange.toString();
	}
	*/

	/**
	 * @return this Identity's nickName
	 */
	public synchronized String getNickName() {
		return nickName;
	}

	/**
	 * @return Whether this Identity publishes its trustList or not
	 */
	public boolean doesPublishTrustList() {
		return publishTrustList;
	}

	/**
	 * @return A String listing all this Identity's contexts
	 */
	public synchronized String getContextsAsString() {
		return contexts.toString();
	}
	
	/**
	 * Gets the value of one of this Identity's contexts
	 * 
	 * @param key The name of the requested custom property
	 * @return The value of the requested custom property
	 * @throws InvalidParameterException if this Identity doesn't have the required property
	 */
	public synchronized String getProp(String key) throws InvalidParameterException {
		if(!props.containsKey(key)) throw new InvalidParameterException("Property '"+key+"' isn't set on this identity");
		return props.get(key);
	}
	
	/**
	 * @return A String listing all this Identities custom properties
	 */
	public synchronized String getPropsAsString() {
		return props.toString();
	}
	
	/**
	 * @param context The context we want to know if this Identity has it or not
	 * @return Whether this Identity has that context or not
	 */
	public synchronized boolean hasContext(String context) {
		return contexts.contains(context.trim());
	}
}
