/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WoT;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;

import plugins.WoT.exceptions.DuplicateIdentityException;
import plugins.WoT.exceptions.DuplicateScoreException;
import plugins.WoT.exceptions.DuplicateTrustException;
import plugins.WoT.exceptions.InvalidParameterException;
import plugins.WoT.exceptions.NotInTrustTreeException;
import plugins.WoT.exceptions.NotTrustedException;
import plugins.WoT.exceptions.UnknownIdentityException;
import plugins.WoT.introduction.IntroductionClient;
import plugins.WoT.introduction.IntroductionPuzzle;
import plugins.WoT.introduction.IntroductionPuzzleStore;
import plugins.WoT.introduction.IntroductionServer;
import plugins.WoT.ui.fcp.FCPInterface;
import plugins.WoT.ui.web.WebInterface;

import com.db4o.Db4o;
import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.config.Configuration;
import com.db4o.ext.DatabaseClosedException;
import com.db4o.ext.Db4oIOException;
import com.db4o.ext.ExtObjectContainer;
import com.db4o.query.Query;
import com.db4o.reflect.jdk.JdkReflector;

import freenet.client.FetchException;
import freenet.client.InsertException;
import freenet.client.async.ClientContext;
import freenet.keys.FreenetURI;
import freenet.l10n.L10n.LANGUAGE;
import freenet.node.RequestClient;
import freenet.pluginmanager.FredPlugin;
import freenet.pluginmanager.FredPluginFCP;
import freenet.pluginmanager.FredPluginHTTP;
import freenet.pluginmanager.FredPluginL10n;
import freenet.pluginmanager.FredPluginRealVersioned;
import freenet.pluginmanager.FredPluginThreadless;
import freenet.pluginmanager.FredPluginVersioned;
import freenet.pluginmanager.FredPluginWithClassLoader;
import freenet.pluginmanager.PluginHTTPException;
import freenet.pluginmanager.PluginReplySender;
import freenet.pluginmanager.PluginRespirator;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;
import freenet.support.api.HTTPRequest;

/**
 * A web of trust plugin based on Freenet.
 * 
 * @author xor (xor@freenetproject.org), Julien Cornuwel (batosai@freenetproject.org)
 */
public class WoT implements FredPlugin, FredPluginHTTP, FredPluginThreadless, FredPluginFCP, FredPluginVersioned, FredPluginRealVersioned, FredPluginL10n,
	FredPluginWithClassLoader {
	
	/* Constants */
	
	public static final int DATABASE_FORMAT_VERSION = 1;
	
	/** The relative path of the plugin on Freenet's web interface */
	public static final String SELF_URI = "/plugins/plugins.WoT.WoT";
	
	/**
	 * The "name" of this web of trust. It is included in the document name of identity URIs. For an example, see the SEED_IDENTITY_URI constant
	 * below. The purpose of this costant is to allow anyone to create his own custom web of trust which is completely disconnected from the
	 * "official" web of trust of the Freenet project.
	 */
	public static final String WOT_NAME = "WoT";
	
	/**
	 * The official seed identity of the WoT plugin: If a newbie wants to download the whole offficial web of trust, he needs at least one trust
	 * list from an identity which is well-connected to the web of trust. To prevent newbies from having to add this identity manually, the 
	 * Freenet development team provides a seed identity. This is an identity which will only assign neutral trust values to identities in it's
	 * trust list and provide many captchas per day to allow newbies to get on it's trust list.
	 */
	private static final String SEED_IDENTITY_URI = 
		"USK@MF2Vc6FRgeFMZJ0s2l9hOop87EYWAydUZakJzL0OfV8,fQeN-RMQZsUrDha2LCJWOMFk1-EiXZxfTnBT8NEgY00,AQACAAE/WoT/85";
	
	private Identity seed;
	
	
	/* References from the node */
	
	/** The ClassLoader which was used to load the plugin JAR, needed by db4o to work correctly */
	private ClassLoader mClassLoader;
	
	/** The node's interface to connect the plugin with the node, needed for retrieval of all other interfaces */
	private PluginRespirator pr;	
	
	
	/* References from the plugin itself */
	
	/* Database & configuration of the plugin */
	private ExtObjectContainer mDB;
	private Config mConfig;
	private IntroductionPuzzleStore mPuzzleStore;
	
	
	/** Used for exporting identities, identity introductions and introduction puzzles to XML and importing them from XML. */
	private XMLTransformer mIdentityXML;

	/* Worker objects which actually run the plugin */
	
	private IdentityInserter inserter;
	private IdentityFetcher fetcher;
	private IntroductionServer introductionServer;
	private IntroductionClient introductionClient;
	private RequestClient requestClient;
	
	/* User interfaces */
	
	private WebInterface web;
	private FCPInterface fcp;

	public void runPlugin(PluginRespirator myPR) {
		Logger.debug(this, "Start");
		
		/* Catpcha generation needs headless mode on linux */
		System.setProperty("java.awt.headless", "true"); 

		pr = myPR;
		mDB = initDB();
		deleteDuplicateObjects();
		deleteOrphanObjects();
		
		mConfig = Config.loadOrCreate(this);
		if(mConfig.getInt(Config.DATABASE_FORMAT_VERSION) > WoT.DATABASE_FORMAT_VERSION) 
			throw new RuntimeException("The WoT plugin's database format is newer than the WoT plugin which is being used.");
		
		mPuzzleStore = new IntroductionPuzzleStore(this);

		seed = getSeedIdentity();
		requestClient = new RequestClient() {

			public boolean persistent() {
				return false;
			}

			public void removeFrom(ObjectContainer container) {
				throw new UnsupportedOperationException();
			}
			
		};

		try {
			mIdentityXML = new XMLTransformer(this);
		}
		catch(Exception e) { throw new RuntimeException(e); }
		
		// Start the inserter thread
		inserter = new IdentityInserter(this);
		pr.getNode().executor.execute(inserter, "WoTinserter");
		
		// Create the fetcher
		fetcher = new IdentityFetcher(this);		
		fetcher.fetch(seed, false);
		
		introductionServer = new IntroductionServer(this, fetcher);
		introductionClient = new IntroductionClient(this);

		// Try to fetch all known identities
		synchronized(this) {
			ObjectSet<Identity> identities = getAllIdentities();
			while (identities.hasNext()) {
				fetcher.fetch(identities.next(), true);
			}
		}
		
		web = new WebInterface(this, SELF_URI);
		fcp = new FCPInterface(this);
	}

	/**
	 * Initializes the plugin's db4o database.
	 * 
	 * @return A db4o <code>ObjectContainer</code>. 
	 */
	private ExtObjectContainer initDB() {
		Configuration cfg = Db4o.newConfiguration();
		cfg.reflectWith(new JdkReflector(mClassLoader));
		cfg.activationDepth(1);
		cfg.exceptionsOnNotStorable(true);
		
		for(String field : Identity.getIndexedFields()) cfg.objectClass(Identity.class).objectField(field).indexed(true);
		for(String field : OwnIdentity.getIndexedFields()) cfg.objectClass(OwnIdentity.class).objectField(field).indexed(true);
		for(String field : Trust.getIndexedFields()) cfg.objectClass(Trust.class).objectField(field).indexed(true);
		for(String field : Score.getIndexedFields()) cfg.objectClass(Score.class).objectField(field).indexed(true);
		
		cfg.objectClass(IntroductionPuzzle.PuzzleType.class).persistStaticFieldValues(); /* Needed to be able to store enums */
		for(String field : IntroductionPuzzle.getIndexedFields()) cfg.objectClass(IntroductionPuzzle.class).objectField(field).indexed(true);
		
		return Db4o.openFile(cfg, "WebOfTrust-testing.db4o").ext();
	}
	
	/**
	 * Debug function for deleting duplicate identities etc. which might have been created due to bugs :)
	 */
	@SuppressWarnings("unchecked")
	private synchronized void deleteDuplicateObjects() {
		synchronized(mDB) {
			try {
				ObjectSet<Identity> identities = getAllIdentities();
				HashSet<String> deleted = new HashSet<String>();
				
				for(Identity identity : identities) {
					Query q = mDB.query();
					q.constrain(Identity.class);
					q.descend("mID").constrain(identity.getID());
					q.constrain(identity).identity().not();
					ObjectSet<Identity> duplicates = q.execute();
					for(Identity duplicate : duplicates) {
						if(deleted.contains(duplicate.getID()) == false) {
							Logger.error(duplicate, "Deleting duplicate identity " + duplicate.getRequestURI());
							for(Trust trust : getReceivedTrusts(duplicate))
								mDB.delete(trust);
							for(Trust trust : getGivenTrusts(duplicate))
								mDB.delete(trust);
							for(Score score : getScores(duplicate))
								mDB.delete(score);
							mDB.delete(duplicate);
						}
					}
					deleted.add(identity.getID());
					mDB.commit();
				}
			}
			catch(RuntimeException e) {
				mDB.rollback();
				Logger.error(this, "Error while deleting duplicate identities", e);
			}
			
			try {
				for(OwnIdentity treeOwner : getAllOwnIdentities()) {
					HashSet<String> givenTo = new HashSet<String>();
					
					for(Trust trust : getGivenTrusts(treeOwner)) {
						if(givenTo.contains(trust.getTrustee().getID()) == false)
							givenTo.add(trust.getTrustee().getID());
						else {
							Identity trustee = trust.getTrustee();
							Logger.error(this, "Deleting duplicate given trust from " + treeOwner.getNickname() + " to " + trustee.getNickname());
							mDB.delete(trust);
							
							try {
								updateScoreWithoutCommit(treeOwner, trustee);
							}
							catch(Exception e) { /* Maybe another duplicate prevents it from working ... */
								Logger.error(this, "Updating score of " + trustee.getNickname() + " failed.", e);
							}
						}
					}
					mDB.commit();
				}
				
				
			}
			catch(RuntimeException e) {
				mDB.rollback();
				Logger.error(this, "Error while deleting duplicate trusts", e);
			}

		}
		
		/* FIXME: Also delete duplicate trust, score, etc. */
	}
	
	/**
	 * Debug function for deleting trusts or scores of which one of the involved partners is missing.
	 */
	@SuppressWarnings("unchecked")
	private synchronized void deleteOrphanObjects() {
		synchronized(mDB) {
			try {
				Query q = mDB.query();
				q.constrain(Trust.class);
				q.descend("mTruster").constrain(null).identity().or(q.descend("mTrustee").constrain(null).identity());
				ObjectSet<Trust> orphanTrusts = q.execute();
				for(Trust trust : orphanTrusts) {
					Logger.error(trust, "Deleting orphan trust, truster = " + trust.getTruster() + ", trustee = " + trust.getTrustee());
					mDB.delete(trust);
				}
			}
			catch(Exception e) {
				Logger.error(this, "Deleting orphan trusts failed.", e);
				// mDB.rollback(); /* No need to do so here */ 
			}
			
			try {
				Query q = mDB.query();
				q.constrain(Score.class);
				q.descend("mTreeOwner").constrain(null).identity().or(q.descend("mTarget").constrain(null).identity());
				ObjectSet<Score> orphanScores = q.execute();
				for(Score score : orphanScores) {
					Logger.error(score, "Deleting orphan score, treeOwner = " + score.getTreeOwner() + ", target = " + score.getTarget());
					mDB.delete(score);
				}
				
				mDB.commit();
			}
			catch(Exception e) {
				Logger.error(this, "Deleting orphan trusts failed.", e);
				// mDB.rollback(); /* No need to do so here */ 
			}
		}
	}

	public void terminate() {
		Logger.debug(this, "WoT plugin terminating ...");
		
		/* We use single try/catch blocks so that failure of termination of one service does not prevent termination of the others */
		try {
			introductionClient.terminate();
		}
		catch(Exception e) {
			Logger.error(this, "Error during termination.", e);
		}
		
		try {
			introductionServer.terminate();
		}
		catch(Exception e) {
			Logger.error(this, "Error during termination.", e);
		}
		
		try {
			inserter.stop();
		}
		catch(Exception e) {
			Logger.error(this, "Error during termination.", e);
		}
		
		try {
			fetcher.stop();
		}
		catch(Exception e) {
			Logger.error(this, "Error during termination.", e);
		}
		
		
		try {
			/* FIXME: Is it possible to ask db4o whether a transaction is pending? If the plugin's synchronization works correctly, NONE should
			 * be pending here and we should log an error if there are any pending transactions at this point. */
			synchronized(mDB.lock()) {
				mDB.rollback();
				mDB.close();
			}
		}
		catch(Exception e) {
			Logger.error(this, "Error during termination.", e);
		}

		Logger.debug(this, "WoT plugin terminated.");
	}

	/**
	 * Inherited event handler from FredPluginHTTP, handled in <code>class WebInterface</code>.
	 */
	public String handleHTTPGet(HTTPRequest request) throws PluginHTTPException {	
		return web.handleHTTPGet(request);
	}

	/**
	 * Inherited event handler from FredPluginHTTP, handled in <code>class WebInterface</code>.
	 */
	public String handleHTTPPost(HTTPRequest request) throws PluginHTTPException {
		return web.handleHTTPPost(request);
	}
	
	/**
	 * Inherited event handler from FredPluginHTTP, handled in <code>class WebInterface</code>.
	 */
	public String handleHTTPPut(HTTPRequest request) throws PluginHTTPException {
		return web.handleHTTPPut(request);
	}

	/**
	 * Inherited event handler from FredPluginFCP, handled in <code>class FCPInterface</code>.
	 */
	public void handle(PluginReplySender replysender, SimpleFieldSet params, Bucket data, int accesstype) {
		fcp.handle(replysender, params, data, accesstype);
	}

	/**
	 * Loads an identity from the database, querying on its ID.
	 * 
	 * @param id The ID of the identity to load
	 * @return The identity matching the supplied ID.
	 * @throws DuplicateIdentityException if there are more than one identity with this id in the database
	 * @throws UnknownIdentityException if there is no identity with this id in the database
	 */
	@SuppressWarnings("unchecked")
	public synchronized Identity getIdentityByID(String id) throws UnknownIdentityException {
		Query query = mDB.query();
		query.constrain(Identity.class);
		query.descend("mID").constrain(id);
		ObjectSet<Identity> result = query.execute();
		
		if(result.size() == 0)
			throw new UnknownIdentityException(id);

		if(result.size() > 1)
			throw new DuplicateIdentityException(id);
		
		return result.next();
	}

	/**
	 * Loads an identity from the database, querying on its requestURI (as String)
	 * 
	 * @param uri The requestURI of the identity which will be converted to {@link FreenetURI} 
	 * @return The identity matching the supplied requestURI
	 * @throws UnknownIdentityException if there is no identity with this id in the database
	 * @throws DuplicateIdentityException if there are more than one identity with this id in the database
	 * @throws MalformedURLException if the requestURI isn't a valid FreenetURI
	 */
	public Identity getIdentityByURI(String uri) throws UnknownIdentityException, MalformedURLException {
		return getIdentityByURI(new FreenetURI(uri));
	}

	/**
	 * Loads an identity from the database, querying on its requestURI (a valid {@link FreenetURI})
	 * 
	 * @param uri The requestURI of the identity
	 * @return The identity matching the supplied requestURI
	 * @throws UnknownIdentityException if there is no identity with this id in the database
	 * @throws DuplicateIdentityException if there are more than one identity with this id in the database
	 */
	public Identity getIdentityByURI(FreenetURI uri) throws UnknownIdentityException {
		return getIdentityByID(Identity.getIDFromURI(uri));
	}
	
	/**
	 * Returns all identities that are in the database.
	 * You have to synchronize on this WoT when calling the function and processing the returned list!
	 * 
	 * @return An {@link ObjectSet} containing all identities present in the database 
	 */
	public synchronized ObjectSet<Identity> getAllIdentities() {
		return mDB.queryByExample(Identity.class);
	}
	
	/**
	 * Returns all own identities that are in the database
	 * You have to synchronize on this WoT when calling the function and processing the returned list!
	 * 
	 * @return An {@link ObjectSet} containing all identities present in the database.
	 */
	public synchronized ObjectSet<OwnIdentity> getAllOwnIdentities() {
		return mDB.queryByExample(OwnIdentity.class);
	}
	
	public synchronized void storeAndCommit(Identity identity) {
		synchronized(identity) {
		synchronized(mDB.lock()) {
			if(mDB.ext().isStored(identity) && !mDB.ext().isActive(identity))
				throw new RuntimeException("Trying to store an inactive Identity object!");
			
			/* FIXME: We also need to check whether the member objects are active here!!! */
			
			try {
				if(identity instanceof OwnIdentity) {
					OwnIdentity ownId = (OwnIdentity)identity;
					mDB.store(ownId.mInsertURI);
					// mDB.store(ownId.mCreationDate); /* Not stored because db4o considers it as a primitive and automatically stores it. */
					// mDB.store(ownId.mLastInsertDate); /* Not stored because db4o considers it as a primitive and automatically stores it. */
				}
				// mDB.store(mID); /* Not stored because db4o considers it as a primitive and automatically stores it. */
				mDB.store(identity.mRequestURI);
				// mDB.store(mFirstFetchedDate); /* Not stored because db4o considers it as a primitive and automatically stores it. */
				// mDB.store(mLastChangedDate); /* Not stored because db4o considers it as a primitive and automatically stores it. */
				// mDB.store(mNickname); /* Not stored because db4o considers it as a primitive and automatically stores it. */
				// mDB.store(mDoesPublishTrustList); /* Not stored because db4o considers it as a primitive and automatically stores it. */
				mDB.store(identity.mProperties);
				mDB.store(identity.mContexts);
				mDB.store(identity);
				mDB.commit();
				Logger.debug(identity, "COMMITED.");
			}
			catch(RuntimeException e) {
				mDB.rollback();
				throw e;
			}
		}
		}
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
	public synchronized Score getScore(OwnIdentity treeOwner, Identity target) throws NotInTrustTreeException, DuplicateScoreException {
		Query query = mDB.query();
		query.constrain(Score.class);
		query.descend("mTreeOwner").constrain(treeOwner).identity();
		query.descend("mTarget").constrain(target).identity();
		ObjectSet<Score> result = query.execute();
		
		if(result.size() == 0)
			throw new NotInTrustTreeException(target.getRequestURI() + " is not in that trust tree");
		
		if(result.size() > 1)
			throw new DuplicateScoreException(target.getRequestURI() +" ("+ target.getNickname() +") has " + result.size() + 
					" scores in " + treeOwner.getNickname() +"'s trust tree");
		
		return result.next();
	}
	
	/* 
	 * FIXME:
	 * I suggest before releasing we should write a getRealScore() function which recalculates the score from all Trust objects which are stored
	 * in the database. We could then assert(getScore() == getRealScore()) for verifying that the database is consistent and watch for some time
	 * whether it stays consistent, just to make sure that there are no flaws in the code.
	 */
	
	/**
	 * Gets a list of all this Identity's Scores.
	 * You have to synchronize on this WoT around the call to this function and the processing of the returned list! 
	 * 
	 * @param db A reference to the database
	 * @return An {@link ObjectSet} containing all {@link Score} this Identity has.
	 */
	@SuppressWarnings("unchecked")
	public synchronized ObjectSet<Score> getScores(Identity identity) {
		Query query = mDB.query();
		query.constrain(Score.class);
		query.descend("mTarget").constrain(identity).identity();
		return query.execute();
	}
	
	/**
	 * Gets the best score this Identity has in existing trust trees.
	 * 
	 * @param db A reference to the database
	 * @return the best score this Identity has
	 */
	public synchronized int getBestScore(Identity identity) {
		int bestScore = 0;
		ObjectSet<Score> scores = getScores(identity);
		/* TODO: Use a db4o native query for this loop. Maybe use an index, indexes should be sorted so maximum search will be O(1)... 
		 * but I guess indexes cannot be done for the (target, value) pair so we might just cache the best score ourselves...
		 * OTOH the number of own identies will be small so the maximum search will probably be fast... */
		while(scores.hasNext()) {
			Score score = scores.next();
			bestScore = Math.max(score.getScore(), bestScore);
		}
		return bestScore;
	}
	
	/**
	 * Get all scores in the database.
	 * You have to synchronize on this WoT when calling the function and processing the returned list!
	 */
	public synchronized ObjectSet<Score> getAllScores() {
		return mDB.queryByExample(Score.class);
	}
	
	/**
	 * Gets Identities matching a specified score criteria.
	 * You have to synchronize on this WoT when calling the function and processing the returned list!
	 * 
	 * @param owner requestURI of the owner of the trust tree, null if you want the trusted identities of all owners.
	 * @param select Score criteria, can be '+', '0' or '-'
	 * @return an {@link ObjectSet} containing Identities that match the criteria
	 * @throws InvalidParameterException if the criteria is not recognised
	 */
	@SuppressWarnings("unchecked")
	public synchronized ObjectSet<Score> getIdentitiesByScore(OwnIdentity treeOwner, int select) {		
		Query query = mDB.query();
		query.constrain(Score.class);
		if(treeOwner != null)
			query.descend("mTreeOwner").constrain(treeOwner).identity();
		query.descend("mTarget").constrain(OwnIdentity.class).not();
			
		if(select > 0)
			query.descend("mValue").constrain(0).greater();
		else if(select < 0 )
			query.descend("mValue").constrain(0).smaller();
		else 
			query.descend("mValue").constrain(0);

		return query.execute();
	}
	
	/**
	 * Gets {@link Trust} from a specified truster to a specified trustee.
	 * 
	 * @param truster The identity that gives trust to this Identity
	 * @param trustee The identity which receives the trust
	 * @return The trust given to the trustee by the specified truster
	 * @throws NotTrustedException if the truster doesn't trust the trustee
	 * @throws DuplicateTrustException If there are more than one Trust object between these identities in the database (should never happen)
	 */
	@SuppressWarnings("unchecked")
	public synchronized Trust getTrust(Identity truster, Identity trustee) throws NotTrustedException, DuplicateTrustException {
		Query query = mDB.query();
		query.constrain(Trust.class);
		query.descend("mTruster").constrain(truster).identity();
		query.descend("mTrustee").constrain(trustee).identity();
		ObjectSet<Trust> result = query.execute();
		
		if(result.size() == 0)
			throw new NotTrustedException(truster.getNickname() + " does not trust " + trustee.getNickname());
		
		if(result.size() > 1)
			throw new DuplicateTrustException("Trust from " + truster.getNickname() + "to " + trustee.getNickname() + " exists "
					+ result.size() + " times in the database");
		
		return result.next();
	}

	/**
	 * Gets all trusts given by the given truster.
	 * You have to synchronize on this WoT when calling the function and processing the returned list!
	 * 
	 * @return An {@link ObjectSet} containing all {@link Trust} the passed Identity has given.
	 */
	@SuppressWarnings("unchecked")
	public synchronized ObjectSet<Trust> getGivenTrusts(Identity truster) {
		Query query = mDB.query();
		query.constrain(Trust.class);
		query.descend("mTruster").constrain(truster).identity();
		return query.execute();
	}
	
	/**
	 * Gets all trusts given by the given truster in a trust list older than the given edition number.
	 * You have to synchronize on this WoT when calling the function and processing the returned list!
	 */
	@SuppressWarnings("unchecked")
	protected synchronized ObjectSet<Trust> getGivenTrustsOlderThan(Identity truster, long edition) {
		Query q = mDB.query();
		q.constrain(Trust.class);
		q.descend("mTruster").constrain(truster).identity();
		q.descend("mTrusterTrustListEdition").constrain(edition).smaller();
		return q.execute();
	}

	/**
	 * Gets all trusts received by the given trustee.
	 * You have to synchronize on this WoT when calling the function and processing the returned list!
	 * 
	 * @return An {@link ObjectSet} containing all {@link Trust} the passed Identity has received.
	 */
	@SuppressWarnings("unchecked")
	public synchronized ObjectSet<Trust> getReceivedTrusts(Identity trustee) {
		Query query = mDB.query();
		query.constrain(Trust.class);
		query.descend("mTrustee").constrain(trustee).identity();
		return query.execute();
	}
	
	/**
	 * Gives some {@link Trust} to another Identity.
	 * It creates or updates an existing Trust object and make the trustee compute its {@link Score}.
	 * 
	 * This function does neither lock the database nor commit the transaction. You have to surround it with
	 * synchronized(mDB.lock()) {
	 *     try { ... setTrustWithoutCommit(...); storeAndCommit(truster); }
	 *     catch(RuntimeException e) { mDB.rollback(); throw e; }
	 * }
	 * 
	 * @param truster The Identity that gives the trust
	 * @param trustee The Identity that receives the trust
	 * @param newValue Numeric value of the trust
	 * @param newComment A comment to explain the given value
	 * @throws InvalidParameterException if a given parameter isn't valid, {@see Trust} for details on accepted values.
	 */
	protected synchronized void setTrustWithoutCommit(Identity truster, Identity trustee, byte newValue, String newComment)
		throws InvalidParameterException {
		
		Trust trust;
				try { // Check if we are updating an existing trust value
					trust = getTrust(truster, trustee);
					trust.trusterEditionUpdated();
					trust.setComment(newComment);
					mDB.store(trust);
					
					if(trust.getValue() != newValue) {
						trust.setValue(newValue);
						mDB.store(trust);
						Logger.debug(this, "Updated trust value ("+ trust +"), now updating Score.");
						updateScoreWithoutCommit(trustee);
					}
				} catch (NotTrustedException e) {
					trust = new Trust(truster, trustee, newValue, newComment);
					mDB.store(trust);
					Logger.debug(this, "New trust value ("+ trust +"), now updating Score.");
					updateScoreWithoutCommit(trustee);
				} 
				
				truster.updated();
	}
	
	public synchronized void setTrust(OwnIdentity truster, Identity trustee, byte newValue, String newComment) throws InvalidParameterException {
		synchronized(mDB.lock()) {
			try {
				setTrustWithoutCommit(truster, trustee, newValue, newComment);
				storeAndCommit(truster);
			}
			catch(RuntimeException e) {
				mDB.rollback();
				throw e;
			}
		}
	}
	
	/**
	 * Deletes a trust object
	 * @param truster
	 * @param trustee
	 */
//	protected synchronized void removeTrust(Identity truster, Identity trustee) {
//		synchronized(mDB.lock()) {
//			try {
//				try {
//					Trust trust = getTrust(truster, trustee);
//					mDB.delete(trust);
//					updateScoreWithoutCommit(trustee);
//				} catch (NotTrustedException e) {
//					Logger.error(this, "Cannot remove trust - there is none - from " + truster.getNickName() + " to " + trustee.getNickName());
//				} 
//			}
//			catch(RuntimeException e) {
//				mDB.rollback();
//				throw e;
//			}
//		}
//	}
	
	/**
	 * 
	 * This function does neither lock the database nor commit the transaction. You have to surround it with
	 * synchronized(mDB.lock()) {
	 *     try { ... setTrustWithoutCommit(...); storeAndCommit(truster); }
	 *     catch(RuntimeException e) { mDB.rollback(); throw e; }
	 * }
	 * 
	 */
	protected synchronized void removeTrustWithoutCommit(Trust trust) {
		mDB.delete(trust);
		updateScoreWithoutCommit(trust.getTrustee());
	}
	
	/**
	 * Updates this Identity's {@link Score} in every trust tree.
	 * 
	 * This function does neither lock the database nor commit the transaction. You have to surround it with
	 * synchronized(mDB.lock()) {
	 *     try { ... setTrustWithoutCommit(...); storeAndCommit(truster); }
	 *     catch(RuntimeException e) { mDB.rollback(); throw e; }
	 * }
	 * 
	 * @throws DuplicateScoreException if there already exist more than one {@link Score} objects for the trustee (should never happen)
	 * @throws DuplicateTrustException if there already exist more than one {@link Trust} objects between these identities (should never happen)
	 */
	private synchronized void updateScoreWithoutCommit(Identity trustee) throws DuplicateScoreException, DuplicateTrustException {
			ObjectSet<OwnIdentity> treeOwners = getAllOwnIdentities();
			if(treeOwners.size() == 0)
				Logger.debug(this, "Can't update " + trustee.getNickname() + "'s score: there is no own identity yet");
			
			while(treeOwners.hasNext())
				updateScoreWithoutCommit(treeOwners.next(), trustee);
	}
	
	/**
	 * Updates this Identity's {@link Score} in one trust tree.
	 * Makes this Identity's trustees update their score if its capacity has changed.
	 * 
	 * This function does neither lock the database nor commit the transaction. You have to surround it with
	 * synchronized(mDB.lock()) {
	 *     try { ... setTrustWithoutCommit(...); storeAndCommit(truster); }
	 *     catch(RuntimeException e) { mDB.rollback(); throw e; }
	 * }
	 * 
	 * @param db A reference to the database
	 * @param treeOwner The OwnIdentity that owns the trust tree
	 * @throws DuplicateScoreException if there already exist more than one {@link Score} objects for the trustee (should never happen)
	 * @throws DuplicateTrustException if there already exist more than one {@link Trust} objects between these identities (should never happen)
	 */
	private synchronized void updateScoreWithoutCommit(OwnIdentity treeOwner, Identity target) {
		if(target == treeOwner)
			return;
		
		boolean changedCapacity = false;
		
		Logger.debug(target, "Updating " + target.getNickname() + "'s score in " + treeOwner.getNickname() + "'s trust tree...");
		
		Score score;
		int value = computeScoreValue(treeOwner, target);
		int rank = computeRank(treeOwner, target);
		
		if(rank == -1) { // -1 value means the identity is not in the trust tree
			try { // If he had a score, we delete it
				score = getScore(treeOwner, target);
				mDB.delete(score); // He had a score, we delete it
				changedCapacity = true;
				Logger.debug(target, target.getNickname() + " is not in " + treeOwner.getNickname() + "'s trust tree anymore");
			} catch (NotInTrustTreeException e) {} 
		}
		else { // The identity is in the trust tree
			
			try { // Get existing score or create one if needed
				score = getScore(treeOwner, target);
			} catch (NotInTrustTreeException e) {
				score = new Score(treeOwner, target, 0, -1, 0);
			}
			
			score.setValue(value);
			score.setRank(rank + 1);
			int oldCapacity = score.getCapacity();
			
			boolean hasNegativeTrust = false;
			// Does the treeOwner personally distrust this identity ?
			try {
				if(getTrust(treeOwner, target).getValue() < 0) {
					hasNegativeTrust = true;
					Logger.debug(target, target.getNickname() + " received negative trust from " + treeOwner.getNickname() + 
							" and therefore has no capacity in his trust tree.");
				}
			} catch (NotTrustedException e) {}
			
			if(hasNegativeTrust)
				score.setCapacity(0);
			else
				score.setCapacity((score.getRank() >= Score.capacities.length) ? 1 : Score.capacities[score.getRank()]);
			
			if(score.getCapacity() != oldCapacity)
				changedCapacity = true;
			
			mDB.store(score);
			Logger.debug(target, "New score: " + score.toString());
		}
		
		if(changedCapacity) { // We have to update trustees' score
			ObjectSet<Trust> givenTrusts = getGivenTrusts(target);
			Logger.debug(target, target.getNickname() + "'s capacity has changed in " + treeOwner.getNickname() +
					"'s trust tree, updating his (" + givenTrusts.size() + ") trustees");
			
			for(Trust givenTrust : givenTrusts)
				updateScoreWithoutCommit(treeOwner, givenTrust.getTrustee());
		}
	}
	
	/**
	 * Computes the target's Score value according to the trusts it has received and the capacity of its trusters in the specified trust tree.
	 * 
	 * @param db A reference to the database
	 * @param treeOwner The OwnIdentity that owns the trust tree
	 * @return The new Score if this Identity
	 * @throws DuplicateScoreException if there already exist more than one {@link Score} objects for the trustee (should never happen)
	 */
	private synchronized int computeScoreValue(OwnIdentity treeOwner, Identity target) throws DuplicateScoreException {
		int value = 0;
		
		ObjectSet<Trust> receivedTrusts = getReceivedTrusts(target);
		while(receivedTrusts.hasNext()) {
			Trust trust = receivedTrusts.next();
			try {
				value += trust.getValue() * (getScore(treeOwner, trust.getTruster())).getCapacity() / 100;
			} catch (NotInTrustTreeException e) {}
		}
		return value;
	}
	
	/**
	 * Computes the target's rank in the trust tree.
	 * It gets its best ranked truster's rank, plus one. Or -1 if none of its trusters are in the trust tree. 
	 *  
	 * @param db A reference to the database
	 * @param treeOwner The OwnIdentity that owns the trust tree
	 * @return The new Rank if this Identity
	 * @throws DuplicateScoreException if there already exist more than one {@link Score} objects for the trustee (should never happen)
	 */
	private synchronized int computeRank(OwnIdentity treeOwner, Identity target) throws DuplicateScoreException {
		int rank = -1;
		
		ObjectSet<Trust> receivedTrusts = getReceivedTrusts(target);
		while(receivedTrusts.hasNext()) {
			Trust trust = receivedTrusts.next();
			try {
				Score score = getScore(treeOwner, trust.getTruster());
				
				if(score.getCapacity() != 0) // If the truster has no capacity, he can't give his rank
					if(rank == -1 || score.getRank() < rank) // If the truster's rank is better than ours or if we have not  
						rank = score.getRank();
			} catch (NotInTrustTreeException e) {}
		}
		return rank;
	}
	
	
	public Identity addIdentity(String requestURI)
		throws MalformedURLException, InvalidParameterException, FetchException, DuplicateIdentityException {
		
		Identity identity = null;
		try {
			identity = Identity.getByURI(db, requestURI);
			Logger.error(this, "Tried to manually add an identity we already know, ignored.");
			throw new InvalidParameterException("We already have this identity");
		}
		catch (UnknownIdentityException e) {
			identity = new Identity(new FreenetURI(requestURI), null, false);
			db.store(identity);
			db.commit();
			Logger.debug(this, "Trying to fetch manually added identity (" + identity.getRequestURI() + ")");
			fetcher.fetch(identity);
		}
		return identity;
	}
	
	public void deleteIdentity(String id) throws DuplicateIdentityException, UnknownIdentityException, DuplicateScoreException, DuplicateTrustException {
		Identity identity = Identity.getById(db, id);
		
		// Remove all scores
		ObjectSet<Score> scores = identity.getScores(db);
		while (scores.hasNext()) db.delete(scores.next());
		
		// Remove all received trusts
		ObjectSet<Trust> receivedTrusts = identity.getReceivedTrusts(db);
		while (receivedTrusts.hasNext()) db.delete(receivedTrusts.next());
		
		// Remove all given trusts and update trustees' scores
		ObjectSet<Trust> givenTrusts = identity.getGivenTrusts(db);
		while (givenTrusts.hasNext()) {
			Trust givenTrust = givenTrusts.next();
			db.delete(givenTrust);
			givenTrust.getTrustee().updateScoreWithoutCommit(db);
		}
		
		db.delete(identity);
	}
	
	public OwnIdentity createIdentity(String nickName, boolean publishTrustList, String context) throws TransformerConfigurationException, FileNotFoundException, InvalidParameterException, ParserConfigurationException, TransformerException, IOException, InsertException, Db4oIOException, DatabaseClosedException, DuplicateScoreException, NotTrustedException, DuplicateTrustException {

		FreenetURI[] keypair = getPluginRespirator().getHLSimpleClient().generateKeyPair("WoT");
		return createIdentity(keypair[0].toString(), keypair[1].toString(), nickName, publishTrustList, context);
	}

	public OwnIdentity createIdentity(String insertURI, String requestURI, String nickName, boolean publishTrustList, String context) throws InvalidParameterException, TransformerConfigurationException, FileNotFoundException, ParserConfigurationException, TransformerException, IOException, InsertException, Db4oIOException, DatabaseClosedException, DuplicateScoreException, NotTrustedException, DuplicateTrustException {

		OwnIdentity identity = new OwnIdentity(new FreenetURI(insertURI), new FreenetURI(requestURI), nickName, publishTrustList);
		identity.addContext(db, context);
		identity.addContext(db, IntroductionPuzzle.INTRODUCTION_CONTEXT); /* FIXME: make configureable */
		identity.setProperty(db, "IntroductionPuzzleCount", Integer.toString(IntroductionServer.PUZZLE_COUNT));
		db.store(identity);
		identity.initTrustTree(db);		

		// This identity trusts the seed identity
		identity.setTrustWithoutCommit(db, seed, (byte)100, "I trust the WoT plugin");
		
		db.commit();
		
		inserter.wakeUp();
		
		Logger.debug(this, "Successfully created a new OwnIdentity (" + identity.getNickname() + ")");

		return identity;
	}

	public void restoreIdentity(String requestURI, String insertURI) throws InvalidParameterException, MalformedURLException, Db4oIOException, DatabaseClosedException, DuplicateScoreException, DuplicateIdentityException, DuplicateTrustException {
		
		OwnIdentity id;
		
		try {
			Identity old = Identity.getByURI(db, requestURI);
			
			// We already have fetched this identity as a stranger's one. We need to update the database.
			id = new OwnIdentity(new FreenetURI(insertURI), new FreenetURI(requestURI), old.getNickname(), old.doesPublishTrustList());
			id.setEdition(old.getEdition());
			
			for(String context : old.getContexts())
				id.addContext(db, context);
			
			for(Entry<String, String> prop : old.getProperties().entrySet())
				id.setProperty(db, prop.getKey(), prop.getValue());
			
			// Update all received trusts
			ObjectSet<Trust> receivedTrusts = old.getReceivedTrusts(db);
			while(receivedTrusts.hasNext()) {
				Trust receivedTrust = receivedTrusts.next();
				Trust newReceivedTrust = new Trust(receivedTrust.getTruster(), id, receivedTrust.getValue(), receivedTrust.getComment());
				db.delete(receivedTrust);
				db.store(newReceivedTrust);
			}

			// Update all received scores
			ObjectSet<Score> scores = old.getScores(db);
			while(scores.hasNext()) {
				Score score = scores.next();
				Score newScore = new Score(score.getTreeOwner(), id, score.getScore(), score.getRank(), score.getCapacity());
				db.delete(score);
				db.store(newScore);
			}

			// Store the new identity
			db.store(id);
			id.initTrustTree(db);
			
			// Update all given trusts
			ObjectSet<Trust> givenTrusts = old.getGivenTrusts(db);
			while(givenTrusts.hasNext()) {
				Trust givenTrust = givenTrusts.next();
				id.setTrustWithoutCommit(db, givenTrust.getTrustee(), givenTrust.getValue(), givenTrust.getComment());
				db.delete(givenTrust);
			}

			// Remove the old identity
			db.delete(old);
			
			Logger.debug(this, "Successfully restored an already known identity from Freenet (" + id.getNickname() + ")");
			
		} catch (UnknownIdentityException e) {
			id = new OwnIdentity(new FreenetURI(insertURI), new FreenetURI(requestURI), null, false);
			
			// Store the new identity
			db.store(id);
			id.initTrustTree(db);
			
			// Fetch the identity from freenet
			fetcher.fetch(id);
			
			Logger.debug(this, "Trying to restore a not-yet-known identity from Freenet (" + id.getRequestURI() + ")");
		}
		db.commit();
	}
	
	public void setTrust(String truster, String trustee, String value, String comment) throws InvalidParameterException, UnknownIdentityException, NumberFormatException, TransformerConfigurationException, FileNotFoundException, ParserConfigurationException, TransformerException, IOException, InsertException, Db4oIOException, DatabaseClosedException, DuplicateScoreException, DuplicateIdentityException, NotTrustedException, DuplicateTrustException  {
		OwnIdentity trusterId = OwnIdentity.getById(db, truster);
		Identity trusteeId = Identity.getById(db, trustee);
		
		if(value.trim().equals(""))
			removeTrust(trusterId, trusteeId);
		else
			setTrust(trusterId, trusteeId, Byte.parseByte(value), comment);
	}
	
	
	public void removeTrust(OwnIdentity truster, Identity trustee) throws TransformerConfigurationException, FileNotFoundException, ParserConfigurationException, TransformerException, IOException, InsertException, Db4oIOException, DatabaseClosedException, InvalidParameterException, DuplicateScoreException, NotTrustedException, DuplicateTrustException {
		truster.removeTrust(db, trustee);
		truster.updated();
		db.store(truster);
		db.commit();	
	}
	
	public void addContext(String identity, String context) throws InvalidParameterException, MalformedURLException, UnknownIdentityException, DuplicateIdentityException {
		Identity id = OwnIdentity.getById(db, identity);
		id.addContext(db, context);
		db.store(id);
		
		Logger.debug(this, "Added context '" + context + "' to identity '" + id.getNickname() + "'");
	}
	
	public void removeContext(String identity, String context) throws InvalidParameterException, MalformedURLException, UnknownIdentityException, DuplicateIdentityException {
		Identity id = OwnIdentity.getById(db, identity);
		id.removeContext(context, db);
		db.store(id);
		
		Logger.debug(this, "Removed context '" + context + "' from identity '" + id.getNickname() + "'");
	}

	public void setProperty(String identity, String property, String value) throws InvalidParameterException, MalformedURLException, UnknownIdentityException, DuplicateIdentityException {
		Identity id = OwnIdentity.getById(db, identity);
		id.setProperty(db, property, value);
		db.store(id);
		
		Logger.debug(this, "Added property '" + property + "=" + value + "' to identity '" + id.getNickname() + "'");
	}
	
	public String getProperty(String identity, String property) throws InvalidParameterException, MalformedURLException, UnknownIdentityException, DuplicateIdentityException {
		return Identity.getById(db, identity).getProperty(property);
	}
	
	public void removeProperty(String identity, String property) throws InvalidParameterException, MalformedURLException, UnknownIdentityException, DuplicateIdentityException {
		Identity id = OwnIdentity.getById(db, identity);
		id.removeProperty(property, db);
		db.store(id);
		
		Logger.debug(this, "Removed property '" + property + "' from identity '" + id.getNickname() + "'");
	}

	public String getVersion() {
		return Version.getMarketingVersion();
	}
	
	public long getRealVersion() {
		return Version.getRealVersion();
	}

	public String getString(String key) {
		return key;
	}

	public void setClassLoader(ClassLoader myClassLoader) {
		mClassLoader = myClassLoader;
	}
	
	public void setLanguage(LANGUAGE newLanguage) {
	}
	
	public Identity getSeedIdentity() {
		if(seed == null) { // The seed identity hasn't been initialized yet
			try { // Try to load it from database
				seed = Identity.getByURI(db, SEED_IDENTITY_URI);
			} catch (UnknownIdentityException e) { // Create it.
				try {
					// Create the seed identity
					seed = new Identity(new FreenetURI(SEED_IDENTITY_URI), null, true);
					seed.setEdition(seed.getRequestURI().getSuggestedEdition());
				} catch (Exception e1) { // Should never happen
					Logger.error(this, "Seed identity creation error", e1);
					return null;
				}
				db.store(seed);
				db.commit();
			} catch (Exception e) { // Should never happen
				Logger.error(this, "Seed identity loading error", e);
				return null;
			}
		}
		return seed;
	}
	
	public PluginRespirator getPluginRespirator() {
		return pr;
	}
	
	public Config getConfig() {
		return mConfig;
	}
	
	public ExtObjectContainer getDB() {
		return db;
	}
	
	public IdentityFetcher getIdentityFetcher() {
		return fetcher;
	}
	
	public XMLTransformer getIdentityXML() {
		return mIdentityXML;
	}
	
	public IntroductionPuzzleStore getIntroductionPuzzleStore() {
		return mPuzzleStore;
	}

	public IntroductionClient getIntroductionClient() {
		return introductionClient;
	}

	public RequestClient getRequestClient() {
		return requestClient;
	}

}
