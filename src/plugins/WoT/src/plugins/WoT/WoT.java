/**
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */

package plugins.WoT;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.Iterator;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;

import com.db4o.Db4o;
import com.db4o.config.Configuration;
import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.ext.DatabaseClosedException;
import com.db4o.ext.Db4oIOException;

import freenet.client.FetchException;
import freenet.client.HighLevelSimpleClient;
import freenet.client.InsertException;
import freenet.keys.FreenetURI;
import freenet.l10n.L10n.LANGUAGE;
import freenet.pluginmanager.FredPlugin;
import freenet.pluginmanager.FredPluginFCP;
import freenet.pluginmanager.FredPluginHTTP;
import freenet.pluginmanager.FredPluginL10n;
import freenet.pluginmanager.FredPluginThreadless;
import freenet.pluginmanager.FredPluginVersioned;
import freenet.pluginmanager.PluginHTTPException;
import freenet.pluginmanager.PluginReplySender;
import freenet.pluginmanager.PluginRespirator;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;
import freenet.support.api.HTTPRequest;

/**
 * @author Julien Cornuwel (batosai@freenetproject.org)
 */
public class WoT implements FredPlugin, FredPluginHTTP, FredPluginThreadless, FredPluginFCP, FredPluginVersioned, FredPluginL10n {
	
	private PluginRespirator pr;
	private HighLevelSimpleClient client;
	
	private ObjectContainer db;
	private WebInterface web;
	private IdentityInserter inserter;
	private IdentityFetcher fetcher;
	private String seedURI = "USK@MF2Vc6FRgeFMZJ0s2l9hOop87EYWAydUZakJzL0OfV8,fQeN-RMQZsUrDha2LCJWOMFk1-EiXZxfTnBT8NEgY00,AQACAAE/WoT/0";
	private Identity seed;
	private Config config;

	public static String SELF_URI = "/plugins/plugins.WoT.WoT";

	public void runPlugin(PluginRespirator pr) {

		Logger.debug(this, "Start");

		// Init
		this.pr = pr;
		Configuration config = Db4o.newConfiguration();
		config.objectClass(Identity.class).objectField("id").indexed(true);
		db = Db4o.openFile(config, "WoT.db4o");
		
		/*
		client = pr.getHLSimpleClient();
		web = new WebInterface(pr, db, client, SELF_URI);
		
		try {
			ObjectSet<Config> result = db.queryByExample(Config.class);
			if(result.size() == 0) {
				Logger.debug(this, "Created new config");
				config = new Config(db);
				db.store(config);
			}
			else {
				Logger.debug(this, "Loaded config");
				config = result.next();
				config.initDefault(false);
			}
		}
		catch(Exception e) {
			Logger.error(this, e.getMessage(), e);
		}
		*/
		
		// Create the seed Identity if it doesn't exist
		try {
			seed = Identity.getByURI(db, seedURI);
		} catch (UnknownIdentityException e) {
			try {
				seed = new Identity(seedURI, "Fetching seed identity...", "true", "bootstrap");
			} catch (Exception e1) {
				Logger.error(this, "Seed identity creation error", e);
				return;
			}
			db.store(seed);
			db.commit();
		} catch (Exception e) {
			Logger.error(this, "Seed identity loading error", e);
			return;
		}
		/*
		
		// Start the inserter thread
		inserter = new IdentityInserter(db, client, pr.getNode().clientCore.tempBucketFactory);
		pr.getNode().executor.execute(inserter, "WoTinserter");
		
		// Create the fetcher
		fetcher = new IdentityFetcher(db, client);
		
		// Try to fetch all known identities
		ObjectSet<Identity> identities = Identity.getAllIdentities(db);
		while (identities.hasNext()) {
			fetcher.fetch(identities.next(), true);
		}
		
		*/
	}
	
	public void terminate() {
		/*
		if(inserter == null) // Can't figure out why executor.execute() makes it null, but stop screwing the unload process.
			Logger.error(this, "Inserter's thread reference is null, can't stop it !" );
		else 
			inserter.stop(); 
		*/
		Logger.debug(this, "Cleanly closing the database");
		db.commit();
		db.close();
		
		/*
		fetcher.stop(); // Do this after cleanly closing the database, as it sometimes locks
		*/
	}

	public String handleHTTPGet(HTTPRequest request) throws PluginHTTPException {

		try {
			if(request.isParameterSet("ownidentities")) {
				return web.makeOwnIdentitiesPage();
			}
			if(request.isParameterSet("knownidentities")) {
				return web.makeKnownIdentitiesPage();
			}
			else {
				return web.makeHomePage();
			}
		} catch (Exception e) {
			e.printStackTrace();
			return e.getMessage();
		}
	}

	public String handleHTTPPost(HTTPRequest request) throws PluginHTTPException {
		
		String pass = request.getPartAsString("formPassword", 32);
		if ((pass.length() == 0) || !pass.equals(pr.getNode().clientCore.formPassword)) {
			return "Buh! Invalid form password";
		}
		
		try {
			if(request.getPartAsString("page",50).equals("createIdentity")) {
				return web.makeCreateIdentityPage(request);
			}
			else if(request.getPartAsString("page",50).equals("createIdentity2")) {
				createIdentity(request);
				return web.makeOwnIdentitiesPage();
			}
			else if(request.getPartAsString("page",50).equals("addIdentity")) {
				addIdentity(request);
				return web.makeKnownIdentitiesPage();
			}
			else if(request.getPartAsString("page",50).equals("viewTree")) {
				return web.makeKnownIdentitiesPage(request);
			}
			else if(request.getPartAsString("page",50).equals("setTrust")) {
				setTrust(request);
				return web.makeKnownIdentitiesPage(request.getPartAsString("truster", 1024));
			}
			else if(request.getPartAsString("page",50).equals("editIdentity")) {
				return web.makeEditIdentityPage(request.getPartAsString("id", 1024));
			}
			else if(request.getPartAsString("page",50).equals("restoreIdentity")) {
				// restoreIdentity(request.getPartAsString("requestURI", 1024), request.getPartAsString("insertURI", 1024));
				return web.makeOwnIdentitiesPage();
			}
			else {
				return web.makeHomePage();
			}
		} catch (Exception e) {
			e.printStackTrace();
			return e.getLocalizedMessage();
		}
	}
	
	// TODO This is OwnIdentity's job, move it there

	/*
	private void restoreIdentity(String requestURI, String insertURI) throws InvalidParameterException, MalformedURLException, Db4oIOException, DatabaseClosedException, DuplicateScoreException, DuplicateIdentityException {
		
		OwnIdentity id;
		
		try {
			Identity old = Identity.getByURI(db, requestURI);
			
			// We already have fetched this identity as a stranger's one. We need to update the database.
			id = new OwnIdentity(insertURI, requestURI, old.getLastChange(), new Date(), old.getNickName(), old.doesPublishTrustList() ? "true" : "false");
			id.setContexts(old.getContexts(), db); 
			id.setProps(old.getProps(), db); 
			
			// Update all received trusts
			ObjectSet<Trust> receivedTrusts = db.queryByExample(new Trust(null, old, 0));
			while(receivedTrusts.hasNext()) {
				Trust receivedTrust = receivedTrusts.next();
				receivedTrust.setTrustee(id);
				db.store(receivedTrust);
			}
			
			// Update all received scores
			ObjectSet<Score> scores = db.queryByExample(new Score(null, old, 0, 0, 0));
			while(scores.hasNext()) {
				Score score = scores.next();
				score.setTarget(id);
				db.store(score);
			}

			// Initialize the trust tree
			Score score = new Score(id, id, 100, 0, 100);  
			db.store(score);
			
			// Store the new identity
			db.store(id);
			
			// Update all given trusts
			ObjectSet<Trust> givenTrusts = db.queryByExample(new Trust(old, null, 0));
			while(givenTrusts.hasNext()) {
				Trust givenTrust = givenTrusts.next();
				givenTrust.setTruster(id);
				wot.setTrust(givenTrust);
				db.delete(givenTrust);
			}
			
			// Remove the old identity
			db.delete(old);
			
			Logger.debug(this, "Successfully restored an already known identity from Freenet (" + id.getNickName() + ")");
			
		} catch (UnknownIdentityException e) {
			id = new OwnIdentity(insertURI, requestURI, new Date(), new Date(0), "Restore in progress...", "false");
			
			// Initialize the trust tree
			Score score = new Score(id, id, 100, 0, 100);  
			db.store(score);
			
			// Store the new identity
			db.store(id);
			
			Logger.debug(this, "Trying to restore a not-yet-known identity from Freenet (" + id.getRequestURI() + ")");
		}
		
		db.commit();
		
		fetcher.fetch(id);
	}
	 */
	
	private void setTrust(HTTPRequest request) throws NumberFormatException, TransformerConfigurationException, FileNotFoundException, InvalidParameterException, UnknownIdentityException, ParserConfigurationException, TransformerException, IOException, InsertException, Db4oIOException, DatabaseClosedException, DuplicateScoreException, DuplicateIdentityException, NotTrustedException, DuplicateTrustException  {
		
		setTrust(	request.getPartAsString("truster", 1024),
					request.getPartAsString("trustee", 1024),
					request.getPartAsString("value", 1024),
					request.getPartAsString("comment", 1024));
	}
	
	private void setTrust(String truster, String trustee, String value, String comment) throws InvalidParameterException, UnknownIdentityException, NumberFormatException, TransformerConfigurationException, FileNotFoundException, ParserConfigurationException, TransformerException, IOException, InsertException, Db4oIOException, DatabaseClosedException, DuplicateScoreException, DuplicateIdentityException, NotTrustedException, DuplicateTrustException  {

		OwnIdentity trusterId = OwnIdentity.getByURI(db, truster);
		Identity trusteeId = Identity.getByURI(db, trustee);
		
		setTrust((OwnIdentity)trusterId, trusteeId, Integer.parseInt(value), comment);
}	
	
	private void setTrust(OwnIdentity truster, Identity trustee, int value, String comment) throws TransformerConfigurationException, FileNotFoundException, ParserConfigurationException, TransformerException, IOException, InsertException, Db4oIOException, DatabaseClosedException, InvalidParameterException, DuplicateScoreException, NotTrustedException, DuplicateTrustException {

		truster.setTrust(db, trustee, value, comment);
		truster.updated();
		db.store(truster);
		db.commit();	
	}

	private void addIdentity(HTTPRequest request) throws MalformedURLException, InvalidParameterException, FetchException, DuplicateIdentityException {
		addIdentity(request.getPartAsString("identityURI", 1024).trim());
	}
	
	private Identity addIdentity(String requestURI) throws MalformedURLException, InvalidParameterException, FetchException, DuplicateIdentityException {
		Identity identity = null;
		try {
			identity = Identity.getByURI(db, requestURI);
			Logger.error(this, "Tried to manually add an identity we already know, ignored.");
			throw new InvalidParameterException("We already have this identity");
		}
		catch (UnknownIdentityException e) {
			// TODO Only add the identity after it is successfully fetched

			identity = new Identity(requestURI, "Not found yet...", "false", "test");
			db.store(identity);
			db.commit();
			Logger.debug(this, "Trying to fetch manually added identity (" + identity.getRequestURI() + ")");
			fetcher.fetch(identity);
		}
		return identity;
	}

	private OwnIdentity createIdentity(HTTPRequest request) throws TransformerConfigurationException, FileNotFoundException, InvalidParameterException, ParserConfigurationException, TransformerException, IOException, InsertException, Db4oIOException, DatabaseClosedException, DuplicateScoreException, NotTrustedException, DuplicateTrustException {

		return createIdentity(	request.getPartAsString("insertURI",1024),
								request.getPartAsString("requestURI",1024),
								request.getPartAsString("nickName", 1024),
								request.getPartAsString("publishTrustList", 1024),
								"testing");	
	}
	
	private OwnIdentity createIdentity(String nickName, String publishTrustList, String context) throws TransformerConfigurationException, FileNotFoundException, InvalidParameterException, ParserConfigurationException, TransformerException, IOException, InsertException, Db4oIOException, DatabaseClosedException, DuplicateScoreException, NotTrustedException, DuplicateTrustException {

		FreenetURI[] keypair = client.generateKeyPair("WoT");
		return createIdentity(keypair[0].toString(), keypair[1].toString(), nickName, publishTrustList, context);
	}

	private OwnIdentity createIdentity(String insertURI, String requestURI, String nickName, String publishTrustList, String context) throws InvalidParameterException, TransformerConfigurationException, FileNotFoundException, ParserConfigurationException, TransformerException, IOException, InsertException, Db4oIOException, DatabaseClosedException, DuplicateScoreException, NotTrustedException, DuplicateTrustException {

		// TODO Add context in the creation form
		OwnIdentity identity = new OwnIdentity(insertURI, requestURI, nickName, publishTrustList, "testing");
		db.store(identity);
		identity.initTrustTree(db);		
		
		// This identity trusts the seed identity
		identity.setTrust(db, seed, 100, "I trust the WoT plugin");
		
		Logger.debug(this, "Successfully created a new OwnIdentity (" + identity.getNickName() + ")");

		db.commit();	

		return identity;
	}
	
	private void addContext(String identity, String context) throws InvalidParameterException, MalformedURLException, UnknownIdentityException, DuplicateIdentityException {
		Identity id = OwnIdentity.getByURI(db, identity);
		id.addContext(context, db);
		db.store(id);
		
		Logger.debug(this, "Added context '" + context + "' to identity '" + id.getNickName() + "'");
	}
	
	private void removeContext(String identity, String context) throws InvalidParameterException, MalformedURLException, UnknownIdentityException, DuplicateIdentityException {
		Identity id = OwnIdentity.getByURI(db, identity);
		id.removeContext(context, db);
		db.store(id);
		
		Logger.debug(this, "Removed context '" + context + "' from identity '" + id.getNickName() + "'");
	}

	private void setProperty(String identity, String property, String value) throws InvalidParameterException, MalformedURLException, UnknownIdentityException, DuplicateIdentityException {
		Identity id = OwnIdentity.getByURI(db, identity);
		id.setProp(property, value, db);
		db.store(id);
		
		Logger.debug(this, "Added property '" + property + "=" + value + "' to identity '" + id.getNickName() + "'");
	}
	
	private String getProperty(String identity, String property) throws InvalidParameterException, MalformedURLException, UnknownIdentityException, DuplicateIdentityException {
		return Identity.getByURI(db, identity).getProp(property);
	}
	
	private void removeProperty(String identity, String property) throws InvalidParameterException, MalformedURLException, UnknownIdentityException, DuplicateIdentityException {
		Identity id = OwnIdentity.getByURI(db, identity);
		id.removeProp(property, db);
		db.store(id);
		
		Logger.debug(this, "Removed property '" + property + "' from identity '" + id.getNickName() + "'");
	}

	public String handleHTTPPut(HTTPRequest request) throws PluginHTTPException {
		return null;
	}

	public String getVersion() {
		return "0.3.0 r"+Version.getSvnRevision();
	}

	public void handle(PluginReplySender replysender, SimpleFieldSet params, Bucket data, int accesstype) {
		
		try {
			if(params.get("Message").equals("CreateIdentity")) {
				replysender.send(handleCreateIdentity(params), data);
			}
			else if(params.get("Message").equals("SetTrust")) {
				replysender.send(handleSetTrust(params), data);
			}
			else if(params.get("Message").equals("AddIdentity")) {
				replysender.send(handleAddIdentity(params), data);
			}
			else if(params.get("Message").equals("GetIdentity")) {
				replysender.send(handleGetIdentity(params), data);
			}
			else if(params.get("Message").equals("GetIdentitiesByScore")) {
				replysender.send(handleGetIdentitiesByScore(params), data);
			}			
			else if(params.get("Message").equals("GetTrusters")) {
				replysender.send(handleGetTrusters(params), data);
			}	
			else if(params.get("Message").equals("GetTrustees")) {
				replysender.send(handleGetTrustees(params), data);
			}
			else if(params.get("Message").equals("AddContext")) {
				replysender.send(handleAddContext(params), data);
			}
			else if(params.get("Message").equals("RemoveContext")) {
				replysender.send(handleRemoveContext(params), data);
			}
			else if(params.get("Message").equals("SetProperty")) {
				replysender.send(handleSetProperty(params), data);
			}
			else if(params.get("Message").equals("GetProperty")) {
				replysender.send(handleGetProperty(params), data);
			}
			else if(params.get("Message").equals("RemoveProperty")) {
				replysender.send(handleRemoveProperty(params), data);
			}
			else {
				throw new Exception("Unknown message (" + params.get("Message") + ")");
			}
		}
		catch (Exception e) {
			replysender.send(errorMessageFCP(e), data);
		}
	}

	private SimpleFieldSet handleCreateIdentity(SimpleFieldSet params) throws TransformerConfigurationException, FileNotFoundException, InvalidParameterException, ParserConfigurationException, TransformerException, IOException, InsertException, Db4oIOException, DatabaseClosedException, DuplicateScoreException, NotTrustedException, DuplicateTrustException  {
		
		SimpleFieldSet sfs = new SimpleFieldSet(false);
		OwnIdentity identity;
		
		if(params.get("NickName")==null || params.get("PublishTrustList")==null || params.get("Context")==null) throw new InvalidParameterException("Missing mandatory parameter");
		
		if(params.get("RequestURI")==null || params.get("InsertURI")==null) {
			identity = createIdentity(params.get("NickName"), params.get("PublishTrustList"), params.get("Context"));
		}
		else {
			identity = createIdentity(	params.get("InsertURI"),
										params.get("RequestURI"),
										params.get("NickName"), 
										params.get("PublishTrustList"),
										params.get("Context"));
		}
		sfs.putAppend("Message", "IdentityCreated");
		sfs.putAppend("InsertURI", identity.getInsertURI().toString());
		sfs.putAppend("RequestURI", identity.getRequestURI().toString());	
		return sfs;
	}

	private SimpleFieldSet handleSetTrust(SimpleFieldSet params) throws NumberFormatException, TransformerConfigurationException, FileNotFoundException, InvalidParameterException, ParserConfigurationException, TransformerException, IOException, InsertException, UnknownIdentityException, Db4oIOException, DatabaseClosedException, DuplicateScoreException, DuplicateIdentityException, NotTrustedException, DuplicateTrustException  {
		
		SimpleFieldSet sfs = new SimpleFieldSet(false);

		if(params.get("Truster") == null || params.get("Trustee") == null || params.get("Value") == null || params.get("Comment") == null) throw new InvalidParameterException("Missing mandatory parameter");
		
		setTrust(params.get("Truster"), params.get("Trustee"), params.get("Value"), params.get("Comment"));
		
		sfs.putAppend("Message", "TrustSet");
		return sfs;
	}
	
	private SimpleFieldSet handleAddIdentity(SimpleFieldSet params) throws InvalidParameterException, MalformedURLException, FetchException, DuplicateIdentityException {
		
		SimpleFieldSet sfs = new SimpleFieldSet(false);

		if(params.get("RequestURI") == null) throw new InvalidParameterException("Missing mandatory parameter");
		
		Identity identity = addIdentity(params.get("RequestURI").trim());
		
		sfs.putAppend("Message", "IdentityAdded");
		sfs.putAppend("RequestURI", identity.getRequestURI().toString());
		return sfs;
	}
	
	private SimpleFieldSet handleGetIdentity(SimpleFieldSet params) throws InvalidParameterException, MalformedURLException, FetchException, UnknownIdentityException, DuplicateScoreException, DuplicateIdentityException, DuplicateTrustException {
		
		SimpleFieldSet sfs = new SimpleFieldSet(false);

		if(params.get("TreeOwner") == null || params.get("Identity") == null) throw new InvalidParameterException("Missing mandatory parameter");
		
		sfs.putAppend("Message", "Identity");
		
		OwnIdentity treeOwner = OwnIdentity.getByURI(db, params.get("TreeOwner"));
		Identity identity = Identity.getByURI(db, params.get("Identity"));
		
		try {
			Trust trust = identity.getReceivedTrust(treeOwner, db);
			sfs.putAppend("Trust", String.valueOf(trust.getValue()));
		} catch (NotTrustedException e1) {
			sfs.putAppend("Trust", "null");
		}  
		
		Score score;
		try {
			score = identity.getScore(treeOwner, db);
			sfs.putAppend("Score", String.valueOf(score.getScore()));
			sfs.putAppend("Rank", String.valueOf(score.getRank()));
		} catch (NotInTrustTreeException e) {
			sfs.putAppend("Score", "null");
			sfs.putAppend("Rank", "null");
		}
		
		Iterator<String> contexts = identity.getContexts();
		for(int i = 1 ; contexts.hasNext() ; i++) sfs.putAppend("Context"+i, contexts.next());
		
		return sfs;
	}
	
	private SimpleFieldSet handleGetIdentitiesByScore(SimpleFieldSet params) throws InvalidParameterException, MalformedURLException, UnknownIdentityException, DuplicateIdentityException {
		
		SimpleFieldSet sfs = new SimpleFieldSet(false);

		if(params.get("TreeOwner") == null || params.get("Select") == null || params.get("Context") == null) throw new InvalidParameterException("Missing mandatory parameter");

		sfs.putAppend("Message", "Identities");

		ObjectSet<Score> result = Score.getIdentitiesByScore(params.get("TreeOwner"), params.get("Select").trim(), db);
		for(int i = 1 ; result.hasNext() ; i++) {
			Score score = result.next();
			// Maybe there is a way to do this through SODA
			if(score.getTarget().hasContext(params.get("Context")) || params.get("Context").equals("all"))
				sfs.putAppend("Identity"+i, score.getTarget().getRequestURI().toString());
		}
		return sfs;
	}
	
	private SimpleFieldSet handleGetTrusters(SimpleFieldSet params) throws InvalidParameterException, MalformedURLException, UnknownIdentityException, Db4oIOException, DatabaseClosedException, DuplicateIdentityException {
		
		SimpleFieldSet sfs = new SimpleFieldSet(false);

		if(params.get("Identity") == null || params.get("Context") == null) throw new InvalidParameterException("Missing mandatory parameter");

		sfs.putAppend("Message", "Identities");
		
		ObjectSet<Trust> result = Identity.getByURI(db, params.get("Identity")).getReceivedTrusts(db);
	
		for(int i = 1 ; result.hasNext() ; i++) {
			Trust trust = result.next();
			// Maybe there is a way to do this through SODA
			if(trust.getTruster().hasContext(params.get("Context")) || params.get("Context").equals("all")) {
				sfs.putAppend("Identity"+i, trust.getTruster().getRequestURI().toString());
				sfs.putAppend("Value"+i, String.valueOf(trust.getValue()));
				sfs.putAppend("Comment"+i, trust.getComment());
			}
		}
		return sfs;
	}
	
	private SimpleFieldSet handleGetTrustees(SimpleFieldSet params) throws InvalidParameterException, MalformedURLException, UnknownIdentityException, Db4oIOException, DatabaseClosedException, DuplicateIdentityException {
		
		SimpleFieldSet sfs = new SimpleFieldSet(false);

		if(params.get("Identity") == null || params.get("Context") == null) throw new InvalidParameterException("Missing mandatory parameter");

		sfs.putAppend("Message", "Identities");

		ObjectSet<Trust> result = Identity.getByURI(db, params.get("Identity")).getGivenTrusts(db);
		
		for(int i = 1 ; result.hasNext() ; i++) {
			Trust trust = result.next();
			// Maybe there is a way to do this through SODA
			if(trust.getTrustee().hasContext(params.get("Context")) || params.get("Context").equals("all")) {
				sfs.putAppend("Identity"+i, trust.getTrustee().getRequestURI().toString());
				sfs.putAppend("Value"+i, String.valueOf(trust.getValue()));
				sfs.putAppend("Comment"+i, trust.getComment());
			}
		}
		return sfs;
	}
	
	private SimpleFieldSet handleAddContext(SimpleFieldSet params) throws InvalidParameterException, MalformedURLException, UnknownIdentityException, DuplicateIdentityException {
		
		SimpleFieldSet sfs = new SimpleFieldSet(false);

		if(params.get("Identity") == null || params.get("Context") == null) throw new InvalidParameterException("Missing mandatory parameter");

		addContext(params.get("Identity"), params.get("Context"));
		
		sfs.putAppend("Message", "ContextAdded");
		return sfs;
	}
	
	private SimpleFieldSet handleRemoveContext(SimpleFieldSet params) throws InvalidParameterException, MalformedURLException, UnknownIdentityException, DuplicateIdentityException {
		
		SimpleFieldSet sfs = new SimpleFieldSet(false);

		if(params.get("Identity") == null || params.get("Context") == null) throw new InvalidParameterException("Missing mandatory parameter");

		removeContext(params.get("Identity"), params.get("Context"));
		
		sfs.putAppend("Message", "ContextRemoved");
		return sfs;
	}
	
	private SimpleFieldSet handleSetProperty(SimpleFieldSet params) throws InvalidParameterException, MalformedURLException, UnknownIdentityException, DuplicateIdentityException {
		
		SimpleFieldSet sfs = new SimpleFieldSet(false);

		if(params.get("Identity") == null || params.get("Property") == null || params.get("Value") == null) throw new InvalidParameterException("Missing mandatory parameter");

		setProperty(params.get("Identity"), params.get("Property"), params.get("Value"));
		
		sfs.putAppend("Message", "PropertyAdded");
		return sfs;
	}

	private SimpleFieldSet handleGetProperty(SimpleFieldSet params) throws InvalidParameterException, MalformedURLException, UnknownIdentityException, DuplicateIdentityException {
		
		SimpleFieldSet sfs = new SimpleFieldSet(false);

		if(params.get("Identity") == null || params.get("Property") == null) throw new InvalidParameterException("Missing mandatory parameter");

		sfs.putAppend("Message", "PropertyValue");
		sfs.putAppend("Property", getProperty(params.get("Identity"), params.get("Property")));
		
		return sfs;
	}

	private SimpleFieldSet handleRemoveProperty(SimpleFieldSet params) throws InvalidParameterException, MalformedURLException, UnknownIdentityException, DuplicateIdentityException {
		
		SimpleFieldSet sfs = new SimpleFieldSet(false);

		if(params.get("Identity") == null || params.get("Property") == null) throw new InvalidParameterException("Missing mandatory parameter");

		removeProperty(params.get("Identity"), params.get("Property"));
		
		sfs.putAppend("Message", "PropertyRemoved");
		return sfs;
	}
	
	private SimpleFieldSet errorMessageFCP (Exception e) {
		
		SimpleFieldSet sfs = new SimpleFieldSet(false);
		sfs.putAppend("Message", "Error");
		sfs.putAppend("Description", (e.getLocalizedMessage() == null) ? "null" : e.getLocalizedMessage());
		e.printStackTrace();
		return sfs;
	}

	public String getString(String key) {
		// TODO Auto-generated method stub
		return key;
	}

	public void setLanguage(LANGUAGE newLanguage) {
		// TODO Auto-generated method stub
	}
}
