/**
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */

package plugins.WoT;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Random;
import java.util.Map.Entry;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;

import plugins.WoT.exceptions.DuplicateIdentityException;
import plugins.WoT.exceptions.DuplicateScoreException;
import plugins.WoT.exceptions.DuplicateTrustException;
import plugins.WoT.exceptions.InvalidParameterException;
import plugins.WoT.exceptions.NotTrustedException;
import plugins.WoT.exceptions.UnknownIdentityException;
import plugins.WoT.introduction.IntroductionClient;
import plugins.WoT.introduction.IntroductionPuzzle;
import plugins.WoT.introduction.IntroductionServer;
import plugins.WoT.ui.fcp.FCPInterface;
import plugins.WoT.ui.web.WebInterface;

import com.db4o.Db4o;
import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.config.Configuration;
import com.db4o.ext.DatabaseClosedException;
import com.db4o.ext.Db4oIOException;
import com.db4o.query.Query;
import com.db4o.reflect.jdk.JdkReflector;

import freenet.client.FetchException;
import freenet.client.HighLevelSimpleClient;
import freenet.client.InsertException;
import freenet.client.async.ClientContext;
import freenet.clients.http.PageMaker;
import freenet.keys.FreenetURI;
import freenet.l10n.L10n.LANGUAGE;
import freenet.node.RequestClient;
import freenet.pluginmanager.FredPlugin;
import freenet.pluginmanager.FredPluginFCP;
import freenet.pluginmanager.FredPluginHTTP;
import freenet.pluginmanager.FredPluginL10n;
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
import freenet.support.io.TempBucketFactory;

/**
 * A web of trust plugin based on Freenet.
 * 
 * @author xor (xor@freenetproject.org), Julien Cornuwel (batosai@freenetproject.org)
 */
public class WoT implements FredPlugin, FredPluginHTTP, FredPluginThreadless, FredPluginFCP, FredPluginVersioned, FredPluginL10n,
	FredPluginWithClassLoader {
	
	/* Constants */
	
	public static final String SELF_URI = "/plugins/plugins.WoT.WoT";
	public static final String WOT_CONTEXT = "WoT";
	private static final String seedURI = "USK@MF2Vc6FRgeFMZJ0s2l9hOop87EYWAydUZakJzL0OfV8,fQeN-RMQZsUrDha2LCJWOMFk1-EiXZxfTnBT8NEgY00,AQACAAE/WoT/85";
	
	
	/* References from the node */
	
	private ClassLoader mClassLoader;
	private PluginRespirator pr;
	private HighLevelSimpleClient client;
	private TempBucketFactory tbf;
	private PageMaker pm;
	private Random random;
	
	
	/* References from the plugin itself */
	
	/* Database & configuration of the plugin */
	private ObjectContainer db;
	private Config config;

	/* Worker objects which actually run the plugin */
	private IdentityInserter inserter;
	private IdentityFetcher fetcher;
	private IntroductionServer introductionServer;
	private IntroductionClient introductionClient;
	private Identity seed;
	private RequestClient requestClient;
	private ClientContext clientContext;
	
	/* User interfaces */
	
	private WebInterface web;
	private FCPInterface fcp;

	public void runPlugin(PluginRespirator myPR) {
		Logger.debug(this, "Start");
		
		/* Catpcha generation needs headless mode on linux */
		System.setProperty("java.awt.headless", "true"); 

		pr = myPR;
		client = pr.getHLSimpleClient();
		tbf = pr.getNode().clientCore.tempBucketFactory;
		pm = pr.getPageMaker();
		random = pr.getNode().fastWeakRandom;
		
		db = initDB();
		config = initConfig();
		seed = getSeedIdentity();
		requestClient = new RequestClient() {

			public boolean persistent() {
				return false;
			}

			public void removeFrom(ObjectContainer container) {
				throw new UnsupportedOperationException();
			}
			
		};
		
		clientContext = pr.getNode().clientCore.clientContext;

		/* FIXME: i cannot get this to work, it does not print any objects although there are definitely IntroductionPuzzle objects in my db.
		
		HashSet<Class> WoTclasses = new HashSet<Class>(Arrays.asList(new Class[]{ Identity.class, OwnIdentity.class, Trust.class, Score.class }));
		Logger.debug(this, "Non-WoT objects in WoT database: ");
		Query q = db.query();
		for(Class c : WoTclasses)
			q.constrain(c).not();
		ObjectSet<Object> objects = q.execute();
		for(Object o : objects) {
			Logger.debug(this, o.toString());
		}
		*/
		
		/* FIXME: this should be done regularly in IntroductionClient: Puzzles can become corrupted due to the Inserter being deleted from the database */
		ObjectSet<IntroductionPuzzle> puzzles = db.queryByExample(IntroductionPuzzle.class);
		for(IntroductionPuzzle p : puzzles) {
			db.activate(p, 5); /* FIXME: check what happens if we comment this out. or to say it in another way: does querying activate the objects? */
			if(p.checkConsistency() == false) {
				db.delete(p);
				Logger.error(this, "Deleting corrupted puzzle.");
			}
		}
		
		// Start the inserter thread
		inserter = new IdentityInserter(db, client, pr.getNode().clientCore.tempBucketFactory);
		pr.getNode().executor.execute(inserter, "WoTinserter");
		
		// Create the fetcher
		fetcher = new IdentityFetcher(db, pr.getNode().clientCore.clientContext, client, requestClient);
		
		fetcher.fetch(seed, false);
		
		// Create a default OwnIdentity if none exists. Should speed up plugin usability for newbies
		if(OwnIdentity.getNbOwnIdentities(db) == 0) {
			try {
				createIdentity("Anonymous", true, "Freetalk");
			} catch (Exception e) {
				Logger.error(this, "Error creating default identity : ", e);
			}
		}
		
		/* FIXME: Debug code, remove before release */
		try {
			ObjectSet<OwnIdentity> oids = db.queryByExample(OwnIdentity.class);
			for(OwnIdentity oid : oids) {
				oid.addContext(db, IntroductionPuzzle.INTRODUCTION_CONTEXT);
				oid.removeContext("freetalk", db);
				oid.addContext(db, "Freetalk");
				oid.setProperty(db, "IntroductionPuzzleCount", Integer.toString(IntroductionServer.PUZZLE_COUNT));
			}
		}
		catch(InvalidParameterException e) {}
		
		introductionServer = new IntroductionServer(this, fetcher);
		pr.getNode().executor.execute(introductionServer, "WoT introduction server");
		
		introductionClient = new IntroductionClient(this);
		pr.getNode().executor.execute(introductionClient, "WoT introduction client");
		
		deleteDuplicateObjects();
		deleteOrphanObjects();
		
		// Try to fetch all known identities
		ObjectSet<Identity> identities = Identity.getAllIdentities(db);
		while (identities.hasNext()) {
			fetcher.fetch(identities.next(), true);
		}
		
		web = new WebInterface(this, SELF_URI);
		fcp = new FCPInterface(this);
	}
	
	/**
	 * Debug function for deleting duplicate identities etc. which might have been created due to bugs :)
	 */
	private void deleteDuplicateObjects() {
		ObjectSet<Identity> identities = Identity.getAllIdentities(db);
		HashSet<String> deleted = new HashSet<String>();
		
		for(Identity i : identities) {
			Query q = db.query();
			q.constrain(Identity.class);
			//q.constrain(OwnIdentity.class).not();
			q.descend("id").constrain(i.getId());
			q.constrain(i).identity().not();
			ObjectSet<Identity> duplicates = q.execute();
			for(Identity duplicate : duplicates) {
				if(deleted.contains(duplicate.getId()) == false) {
					Logger.error(duplicate, "Deleting duplicate identity " + duplicate.getRequestURI());
					for(Trust t : duplicate.getReceivedTrusts(db))
						db.delete(t);
					for(Trust t : duplicate.getGivenTrusts(db))
						db.delete(t);
					for(Score s : duplicate.getScores(db))
						db.delete(s);
					db.delete(duplicate);
				}
			}
			deleted.add(i.getId());
		}
		
		db.commit();
		
		for(OwnIdentity treeOwner : OwnIdentity.getAllOwnIdentities(db)) {
			HashSet<String> givenTo = new HashSet<String>();
			
			for(Trust t : treeOwner.getGivenTrusts(db)) {
				if(givenTo.contains(t.getTrustee().getId()) == false)
					givenTo.add(t.getTrustee().getId());
				else {
					Identity trustee = t.getTrustee();
					Logger.error(this, "Deleting duplicate given trust from " + treeOwner.getNickName() + " to " + trustee.getNickName());
					db.delete(t);
					
					try {
						trustee.updateScore(db, treeOwner);
					}
					catch(Exception e) { /* Maybe another duplicate prevents it from working ... */
						Logger.error(this, "Updating score of " + trustee.getNickName() + " failed.", e);
					}
				}
			}
		}
		
		db.commit();
		
		/* FIXME: Also delete duplicate trust, score, etc. */
	}
	
	/**
	 * Debug function for deleting trusts or scores of which one of the involved partners is missing.
	 */
	private void deleteOrphanObjects() {
		Query q = db.query();
		q.constrain(Trust.class);
		q.descend("truster").constrain(null).identity().or(q.descend("trustee").constrain(null).identity());
		ObjectSet<Trust> orphanTrusts = q.execute();
		for(Trust o : orphanTrusts) {
			Logger.error(o, "Deleting orphan trust, truster = " + o.getTruster() + ", trustee = " + o.getTrustee());
			db.delete(o);
		}
		
		q = db.query();
		q.constrain(Score.class);
		q.descend("treeOwner").constrain(null).identity().or(q.descend("target").constrain(null).identity());
		ObjectSet<Score> orphanScores = q.execute();
		for(Score s : orphanScores) {
			Logger.error(s, "Deleting orphan score, treeOwner = " + s.getTreeOwner() + ", target = " + s.getTarget());
			db.delete(s);
		}
		
		db.commit();
	}
	
	/**
	 * Initializes the connection to DB4O.
	 * 
	 * @return db4o's connector
	 */
	private ObjectContainer initDB() {
		
		// Set indexes on fields we query on
		Configuration cfg = Db4o.newConfiguration();
		cfg.reflectWith(new JdkReflector(mClassLoader));
		cfg.activationDepth(5);
		cfg.exceptionsOnNotStorable(true);
		
		cfg.objectClass(Identity.class).objectField("id").indexed(true);
		cfg.objectClass(OwnIdentity.class).objectField("id").indexed(true);
		cfg.objectClass(Trust.class).objectField("truster").indexed(true);
		cfg.objectClass(Trust.class).objectField("trustee").indexed(true);
		cfg.objectClass(Score.class).objectField("treeOwner").indexed(true);
		cfg.objectClass(Score.class).objectField("target").indexed(true);
		
		cfg.objectClass(IntroductionPuzzle.PuzzleType.class).persistStaticFieldValues();
		
		for(String field : IntroductionPuzzle.getIndexedFields())
			cfg.objectClass(IntroductionPuzzle.class).objectField(field).indexed(true);
		
		// This will make db4o store any complex objects which are referenced by a Config object.
		cfg.objectClass(Config.class).cascadeOnUpdate(true);
		return Db4o.openFile(cfg, "WoT.db4o");
	}
	
	/**
	 * Loads the config of the plugin, or creates it with default values if it doesn't exist.
	 * 
	 * @return config of the plugin
	 */
	private Config initConfig() {
		ObjectSet<Config> result = db.queryByExample(Config.class);
		if(result.size() == 0) {
			Logger.debug(this, "Created new config");
			config = new Config();
			db.store(config);
		}
		else {
			Logger.debug(this, "Loaded config");
			config = result.next();
			config.initDefault(false);
		}
		return config;
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
			fetcher.stop();
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
			/* FIXME: Figure out whether we can use db4o to tell whether this commit() does something. If it does, then log an error, because then
			 * probably we forgot a commit() somewhere. */
			db.commit();
			db.close();
		}
		catch(Exception e) {
			Logger.error(this, "Error during termination.", e);
		}

		Logger.debug(this, "WoT plugin terminated.");
	}

	public String handleHTTPGet(HTTPRequest request) throws PluginHTTPException {	
		return web.handleHTTPGet(request);
	}

	public String handleHTTPPost(HTTPRequest request) throws PluginHTTPException {
		return web.handleHTTPPost(request);
	}
	
	public String handleHTTPPut(HTTPRequest request) throws PluginHTTPException {
		return web.handleHTTPPut(request);
	}

	public void handle(PluginReplySender replysender, SimpleFieldSet params, Bucket data, int accesstype) {
		fcp.handle(replysender, params, data, accesstype);
	}

	public Identity addIdentity(String requestURI) throws MalformedURLException, InvalidParameterException, FetchException, DuplicateIdentityException {
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
			givenTrust.getTrustee().updateScore(db);
		}
		
		db.delete(identity);
	}
	
	public OwnIdentity createIdentity(String nickName, boolean publishTrustList, String context) throws TransformerConfigurationException, FileNotFoundException, InvalidParameterException, ParserConfigurationException, TransformerException, IOException, InsertException, Db4oIOException, DatabaseClosedException, DuplicateScoreException, NotTrustedException, DuplicateTrustException {

		FreenetURI[] keypair = client.generateKeyPair("WoT");
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
		identity.setTrust(db, seed, (byte)100, "I trust the WoT plugin");
		
		db.commit();
		
		inserter.wakeUp();
		
		Logger.debug(this, "Successfully created a new OwnIdentity (" + identity.getNickName() + ")");

		return identity;
	}

	public void restoreIdentity(String requestURI, String insertURI) throws InvalidParameterException, MalformedURLException, Db4oIOException, DatabaseClosedException, DuplicateScoreException, DuplicateIdentityException, DuplicateTrustException {
		
		OwnIdentity id;
		
		try {
			Identity old = Identity.getByURI(db, requestURI);
			
			// We already have fetched this identity as a stranger's one. We need to update the database.
			id = new OwnIdentity(new FreenetURI(insertURI), new FreenetURI(requestURI), old.getNickName(), old.doesPublishTrustList());
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
				id.setTrust(db, givenTrust.getTrustee(), givenTrust.getValue(), givenTrust.getComment());
				db.delete(givenTrust);
			}

			// Remove the old identity
			db.delete(old);
			
			Logger.debug(this, "Successfully restored an already known identity from Freenet (" + id.getNickName() + ")");
			
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
	
	public void setTrust(OwnIdentity truster, Identity trustee, byte value, String comment) throws TransformerConfigurationException, FileNotFoundException, ParserConfigurationException, TransformerException, IOException, InsertException, Db4oIOException, DatabaseClosedException, InvalidParameterException, DuplicateScoreException, NotTrustedException, DuplicateTrustException {
		truster.setTrust(db, trustee, value, comment);
		truster.updated();
		db.store(truster);
		db.commit();	
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
		
		Logger.debug(this, "Added context '" + context + "' to identity '" + id.getNickName() + "'");
	}
	
	public void removeContext(String identity, String context) throws InvalidParameterException, MalformedURLException, UnknownIdentityException, DuplicateIdentityException {
		Identity id = OwnIdentity.getById(db, identity);
		id.removeContext(context, db);
		db.store(id);
		
		Logger.debug(this, "Removed context '" + context + "' from identity '" + id.getNickName() + "'");
	}

	public void setProperty(String identity, String property, String value) throws InvalidParameterException, MalformedURLException, UnknownIdentityException, DuplicateIdentityException {
		Identity id = OwnIdentity.getById(db, identity);
		id.setProperty(db, property, value);
		db.store(id);
		
		Logger.debug(this, "Added property '" + property + "=" + value + "' to identity '" + id.getNickName() + "'");
	}
	
	public String getProperty(String identity, String property) throws InvalidParameterException, MalformedURLException, UnknownIdentityException, DuplicateIdentityException {
		return Identity.getById(db, identity).getProperty(property);
	}
	
	public void removeProperty(String identity, String property) throws InvalidParameterException, MalformedURLException, UnknownIdentityException, DuplicateIdentityException {
		Identity id = OwnIdentity.getById(db, identity);
		id.removeProperty(property, db);
		db.store(id);
		
		Logger.debug(this, "Removed property '" + property + "' from identity '" + id.getNickName() + "'");
	}

	public String getVersion() {
		return "0.4.0 r"+Version.getSvnRevision();
	}

	/* would only be needed if we connect the client plugins directly via object references which will probably not happen
	public List<Identity> getIdentitiesByScore(OwnIdentity treeOwner, int select, String context) throws InvalidParameterException
	{
		ObjectSet<Score> result = Score.getIdentitiesByScore(db, treeOwner, select);
		// TODO: decide whether the tradeoff of using too much memory for the ArrayList is worth the speedup of not having a linked
		// list which allocates lots of pieces of memory for its nodes. trimToSize() is not an option because the List usually will be
		// used only temporarily.
		ArrayList<Identity> identities = new ArrayList<Identity>(result.size()); 
		boolean getAll = context.equals("all");
		
		while(result.hasNext()) {
			Identity identity = result.next().getTarget();
			// TODO: Maybe there is a way to do this through SODA
			if(getAll || identity.hasContext(context))
				identities.add(identity);
		}
		
		return identities;
	}
	*/


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
				seed = Identity.getByURI(db, seedURI);
			} catch (UnknownIdentityException e) { // Create it.
				try {
					// Create the seed identity
					seed = new Identity(new FreenetURI(seedURI), null, true);
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
	
	public PluginRespirator getPR() {
		return pr;
	}
	
	public HighLevelSimpleClient getClient() {
		return client;
	}

	public TempBucketFactory getTBF() {
		return tbf;
	}
	
	public PageMaker getPageMaker() {
		return pm;
	}
	
	public Random getRandom() {
		return random;
	}
	
	public ObjectContainer getDB() {
		return db;
	}
	
	public Config getConfig() {
		return config;
	}

	public IntroductionClient getIntroductionClient() {
		return introductionClient;
	}

	public RequestClient getRequestClient() {
		return requestClient;
	}

	public ClientContext getClientContext() {
		return clientContext;
	}
}
