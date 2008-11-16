/**
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */

package plugins.WoT;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.UUID;
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
import plugins.WoT.introduction.IntroductionServer;
import plugins.WoT.ui.web.HomePage;
import plugins.WoT.ui.web.IntroduceIdentityPage;
import plugins.WoT.ui.web.KnownIdentitiesPage;
import plugins.WoT.ui.web.OwnIdentitiesPage;
import plugins.WoT.ui.web.WebPage;
import plugins.WoT.ui.web.ConfigurationPage;
import plugins.WoT.ui.web.TrustersPage;
import plugins.WoT.ui.web.TrusteesPage;
import plugins.WoT.ui.web.CreateIdentityPage;
import plugins.WoT.ui.web.WebPageImpl;

import com.db4o.Db4o;
import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.config.Configuration;
import com.db4o.ext.DatabaseClosedException;
import com.db4o.ext.Db4oIOException;
import com.db4o.query.Query;

import freenet.client.FetchException;
import freenet.client.HighLevelSimpleClient;
import freenet.client.InsertException;
import freenet.clients.http.PageMaker;
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
	
	/* References from the node */
	
	private PluginRespirator pr;
	private HighLevelSimpleClient client;
	private PageMaker pm;
	
	/* References from the plugin itself */
	
	private ObjectContainer db;
	private WebInterface web;
	private IdentityInserter inserter;
	private IdentityFetcher fetcher;
	private IntroductionServer introductionServer;
	private IntroductionClient introductionClient;
	
	private String seedURI = "USK@MF2Vc6FRgeFMZJ0s2l9hOop87EYWAydUZakJzL0OfV8,fQeN-RMQZsUrDha2LCJWOMFk1-EiXZxfTnBT8NEgY00,AQACAAE/WoT/1";
	private Identity seed = null;
	private Config config;

	public static final String SELF_URI = "/plugins/plugins.WoT.WoT";
	
	public static final String WOT_CONTEXT = "WoT";

	public void runPlugin(PluginRespirator pr) {

		Logger.debug(this, "Start");
		
		/* Catpcha generation needs headless mode on linux */
		System.setProperty("java.awt.headless", "true"); 

		this.db = initDB();
		this.pr = pr;
		client = pr.getHLSimpleClient();
		config = initConfig();
		seed = getSeedIdentity();

		pm = pr.getPageMaker();

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
		
		// Should disappear soon.
		web = new WebInterface(pr, db, config, client, SELF_URI);

		// Create a default OwnIdentity if none exists. Should speed up plugin usability for newbies
		if(OwnIdentity.getNbOwnIdentities(db) == 0) {
			try {
				createIdentity("Anonymous", true, "freetalk");
			} catch (Exception e) {
				Logger.error(this, "Error creating default identity : ", e);
			}
		}
		
		// Start the inserter thread
		inserter = new IdentityInserter(db, client, pr.getNode().clientCore.tempBucketFactory);
		pr.getNode().executor.execute(inserter, "WoTinserter");
		
		// Create the fetcher
		fetcher = new IdentityFetcher(db, client);
		
		fetcher.fetch(seed, false);
		
		try {
		ObjectSet<OwnIdentity> oids = db.queryByExample(OwnIdentity.class);
		for(OwnIdentity oid : oids)
			oid.addContext(IntroductionPuzzle.INTRODUCTION_CONTEXT, db);
		}
		catch(InvalidParameterException e) {}
		
		introductionServer = new IntroductionServer(db, client, pr.getNode().clientCore.tempBucketFactory, fetcher);
		pr.getNode().executor.execute(introductionServer, "WoT introduction server");
		
		introductionClient = new IntroductionClient(db, client, pr.getNode().clientCore.tempBucketFactory);
		pr.getNode().executor.execute(introductionClient, "WoT introduction client");
		
		// Try to fetch all known identities
		ObjectSet<Identity> identities = Identity.getAllIdentities(db);
		while (identities.hasNext()) {
			fetcher.fetch(identities.next(), true);
		}
	}
	
	public void terminate() {
		Logger.debug(this, "WoT plugin terminating ...");
		if(inserter != null) inserter.stop();
		if(introductionServer != null) introductionServer.terminate();
		if(introductionClient != null) introductionClient.terminate();
		if(fetcher != null) fetcher.stop();
		if(db != null) {
			db.commit();
			db.close();
		}
		Logger.debug(this, "WoT plugin terminated.");
	}

	public String handleHTTPGet(HTTPRequest request) throws PluginHTTPException {	
		WebPage page;
		
		if(request.isParameterSet("ownidentities")) page = new OwnIdentitiesPage(this, request);
		else if(request.isParameterSet("knownidentities")) page = new KnownIdentitiesPage(this, request);
		else if(request.isParameterSet("configuration")) page = new ConfigurationPage(this, request);
		// TODO Handle these two in KnownIdentitiesPage
		else if(request.isParameterSet("getTrusters")) page = new TrustersPage(this, request); 
		else if(request.isParameterSet("getTrustees")) page = new TrusteesPage(this, request); 
		else if(request.isParameterSet("puzzle")) { 
			IntroductionPuzzle p = IntroductionPuzzle.getByID(db, UUID.fromString(request.getParam("id")));
			if(p != null) {
				byte[] data = p.getData();
			}
			/* FIXME: The current PluginManager implementation allows plugins only to send HTML replies.
			 * Implement general replying with any mime type and return the jpeg. */
			return "";
		}
		
		else page = new HomePage(this, request);
		
		page.make();	
		return page.toHTML();
	}

	public String handleHTTPPost(HTTPRequest request) throws PluginHTTPException {
		WebPage page;
		
		String pass = request.getPartAsString("formPassword", 32);
		if ((pass.length() == 0) || !pass.equals(pr.getNode().clientCore.formPassword)) {
			return "Buh! Invalid form password";
		}
		
		// TODO: finish refactoring to "page = new ..."

		try {
			String pageTitle = request.getPartAsString("page",50);
			if(pageTitle.equals("createIdentity")) page = new CreateIdentityPage(this, request);
			else if(pageTitle.equals("createIdentity2")) {
				createIdentity(request);
				page = new OwnIdentitiesPage(this, request);
			}
			else if(pageTitle.equals("addIdentity")) {
				addIdentity(request);
				page = new KnownIdentitiesPage(this, request);
			}
			else if(pageTitle.equals("viewTree")) {
				page = new KnownIdentitiesPage(this, request);
			}
			else if(pageTitle.equals("setTrust")) {
				setTrust(request);
				return web.makeKnownIdentitiesPage(request.getPartAsString("truster", 1024));
			}
			else if(pageTitle.equals("editIdentity")) {
				return web.makeEditIdentityPage(request.getPartAsString("id", 1024));
			}
			else if(pageTitle.equals("introduceIdentity")) {
				page = new IntroduceIdentityPage(this, request, introductionClient, OwnIdentity.getById(db, request.getPartAsString("identity", 128)));
			}
			else if(pageTitle.equals("restoreIdentity")) {
				restoreIdentity(request.getPartAsString("requestURI", 1024), request.getPartAsString("insertURI", 1024));
				page = new OwnIdentitiesPage(this, request);
			}
			else if(pageTitle.equals("deleteIdentity")) {
				return web.makeDeleteIdentityPage(request.getPartAsString("id", 1024));
			}			
			else if(pageTitle.equals("deleteIdentity2")) {
				deleteIdentity(request.getPartAsString("id", 1024));
				page = new OwnIdentitiesPage(this, request);
			}			
			else {
				page = new HomePage(this, request);
			}
			
			page.make();
			return page.toHTML();
		} catch (Exception e) {
			e.printStackTrace();
			return e.getLocalizedMessage();
		}
	}
	
	public String handleHTTPPut(HTTPRequest request) throws PluginHTTPException {
		return "Go to hell";
	}
	
	private void deleteIdentity(String id) throws DuplicateIdentityException, UnknownIdentityException, DuplicateScoreException, DuplicateTrustException {
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

	private void restoreIdentity(String requestURI, String insertURI) throws InvalidParameterException, MalformedURLException, Db4oIOException, DatabaseClosedException, DuplicateScoreException, DuplicateIdentityException, DuplicateTrustException {
		
		OwnIdentity id;
		
		try {
			Identity old = Identity.getByURI(db, requestURI);
			
			// We already have fetched this identity as a stranger's one. We need to update the database.
			id = new OwnIdentity(new FreenetURI(insertURI), new FreenetURI(requestURI), old.getNickName(), old.doesPublishTrustList());
			id.setEdition(old.getEdition());
			
			Iterator<String> i1 = old.getContexts();
			while (i1.hasNext()) id.addContext(i1.next(), db);
			
			Iterator<Entry<String, String>> i2 = old.getProps();
			while (i2.hasNext()) {
				Entry<String, String> prop = i2.next();
				id.setProp(prop.getKey(), prop.getValue(), db);
			}
			
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
	
	private void setTrust(HTTPRequest request) throws NumberFormatException, TransformerConfigurationException, FileNotFoundException, InvalidParameterException, UnknownIdentityException, ParserConfigurationException, TransformerException, IOException, InsertException, Db4oIOException, DatabaseClosedException, DuplicateScoreException, DuplicateIdentityException, NotTrustedException, DuplicateTrustException  {
		
		setTrust(	request.getPartAsString("truster", 1024),
					request.getPartAsString("trustee", 1024),
					request.getPartAsString("value", 4),
					request.getPartAsString("comment", 256));
	}
	
	private void setTrust(String truster, String trustee, String value, String comment) throws InvalidParameterException, UnknownIdentityException, NumberFormatException, TransformerConfigurationException, FileNotFoundException, ParserConfigurationException, TransformerException, IOException, InsertException, Db4oIOException, DatabaseClosedException, DuplicateScoreException, DuplicateIdentityException, NotTrustedException, DuplicateTrustException  {

		OwnIdentity trusterId = OwnIdentity.getByURI(db, truster);
		Identity trusteeId = Identity.getByURI(db, trustee);
		
		setTrust((OwnIdentity)trusterId, trusteeId, Byte.parseByte(value), comment);
	}
	
	private void setTrust(OwnIdentity truster, Identity trustee, byte value, String comment) throws TransformerConfigurationException, FileNotFoundException, ParserConfigurationException, TransformerException, IOException, InsertException, Db4oIOException, DatabaseClosedException, InvalidParameterException, DuplicateScoreException, NotTrustedException, DuplicateTrustException {
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
			identity = new Identity(new FreenetURI(requestURI), null, false);
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
								request.getPartAsString("publishTrustList", 5).equals("true"),
								"freetalk");	
	}
	
	private OwnIdentity createIdentity(String nickName, boolean publishTrustList, String context) throws TransformerConfigurationException, FileNotFoundException, InvalidParameterException, ParserConfigurationException, TransformerException, IOException, InsertException, Db4oIOException, DatabaseClosedException, DuplicateScoreException, NotTrustedException, DuplicateTrustException {

		FreenetURI[] keypair = client.generateKeyPair("WoT");
		return createIdentity(keypair[0].toString(), keypair[1].toString(), nickName, publishTrustList, context);
	}

	private OwnIdentity createIdentity(String insertURI, String requestURI, String nickName, boolean publishTrustList, String context) throws InvalidParameterException, TransformerConfigurationException, FileNotFoundException, ParserConfigurationException, TransformerException, IOException, InsertException, Db4oIOException, DatabaseClosedException, DuplicateScoreException, NotTrustedException, DuplicateTrustException {

		OwnIdentity identity = new OwnIdentity(new FreenetURI(insertURI), new FreenetURI(requestURI), nickName, publishTrustList);
		identity.addContext(context, db);
		identity.addContext(IntroductionPuzzle.INTRODUCTION_CONTEXT, db); /* fixme: make configureable */
		db.store(identity);
		identity.initTrustTree(db);		

		// This identity trusts the seed identity
		identity.setTrust(db, seed, (byte)100, "I trust the WoT plugin");
		
		db.commit();
		
		inserter.wakeUp();
		
		Logger.debug(this, "Successfully created a new OwnIdentity (" + identity.getNickName() + ")");

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

	public String getVersion() {
		return "0.3.1 r"+Version.getSvnRevision();
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
			else if(params.get("Message").equals("GetOwnIdentities")) {
				replysender.send(handleGetOwnIdentities(params), data);
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
			Logger.error(this, e.toString());
			replysender.send(errorMessageFCP(e), data);
		}
	}

	private SimpleFieldSet handleCreateIdentity(SimpleFieldSet params) throws TransformerConfigurationException, FileNotFoundException, InvalidParameterException, ParserConfigurationException, TransformerException, IOException, InsertException, Db4oIOException, DatabaseClosedException, DuplicateScoreException, NotTrustedException, DuplicateTrustException  {
		
		SimpleFieldSet sfs = new SimpleFieldSet(true);
		OwnIdentity identity;
		
		if(params.get("NickName")==null || params.get("PublishTrustList")==null || params.get("Context")==null) throw new InvalidParameterException("Missing mandatory parameter");
		
		if(params.get("RequestURI")==null || params.get("InsertURI")==null) {
			identity = createIdentity(params.get("NickName"), params.get("PublishTrustList").equals("true"), params.get("Context"));
		}
		else {
			identity = createIdentity(	params.get("InsertURI"),
										params.get("RequestURI"),
										params.get("NickName"), 
										params.get("PublishTrustList").equals("true"),
										params.get("Context"));
		}
		sfs.putAppend("Message", "IdentityCreated");
		sfs.putAppend("InsertURI", identity.getInsertURI().toString());
		sfs.putAppend("RequestURI", identity.getRequestURI().toString());	
		return sfs;
	}

	private SimpleFieldSet handleSetTrust(SimpleFieldSet params) throws NumberFormatException, TransformerConfigurationException, FileNotFoundException, InvalidParameterException, ParserConfigurationException, TransformerException, IOException, InsertException, UnknownIdentityException, Db4oIOException, DatabaseClosedException, DuplicateScoreException, DuplicateIdentityException, NotTrustedException, DuplicateTrustException  {
		
		SimpleFieldSet sfs = new SimpleFieldSet(true);

		if(params.get("Truster") == null || params.get("Trustee") == null || params.get("Value") == null || params.get("Comment") == null) throw new InvalidParameterException("Missing mandatory parameter");
		
		setTrust(params.get("Truster"), params.get("Trustee"), params.get("Value"), params.get("Comment"));
		
		sfs.putAppend("Message", "TrustSet");
		return sfs;
	}
	
	private SimpleFieldSet handleAddIdentity(SimpleFieldSet params) throws InvalidParameterException, MalformedURLException, FetchException, DuplicateIdentityException {
		
		SimpleFieldSet sfs = new SimpleFieldSet(true);

		if(params.get("RequestURI") == null) throw new InvalidParameterException("Missing mandatory parameter");
		
		Identity identity = addIdentity(params.get("RequestURI").trim());
		
		sfs.putAppend("Message", "IdentityAdded");
		sfs.putAppend("RequestURI", identity.getRequestURI().toString());
		return sfs;
	}
	
	private SimpleFieldSet handleGetIdentity(SimpleFieldSet params) throws InvalidParameterException, MalformedURLException, FetchException, UnknownIdentityException, DuplicateScoreException, DuplicateIdentityException, DuplicateTrustException {
		
		SimpleFieldSet sfs = new SimpleFieldSet(true);

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

	private SimpleFieldSet handleGetOwnIdentities(SimpleFieldSet params) throws InvalidParameterException, MalformedURLException, UnknownIdentityException, DuplicateIdentityException {
		
		SimpleFieldSet sfs = new SimpleFieldSet(true);

		sfs.putAppend("Message", "OwnIdentities");
		
		ObjectSet<OwnIdentity> result = OwnIdentity.getAllOwnIdentities(db);
	
		for(int idx = 1 ; result.hasNext() ; idx++) {
			OwnIdentity oid = result.next();
			/* FIXME: Isn't append slower than replace? Figure this out */
			sfs.putAppend("Identity"+idx, oid.getId());
			sfs.putAppend("RequestURI"+idx, oid.getRequestURI().toString());
			sfs.putAppend("InsertURI"+idx, oid.getInsertURI().toString());
			sfs.putAppend("Nickname"+idx, oid.getNickName());
			/* FIXME: Allow the client to select what data he wants */
		}
		return sfs;
	}
	
	private SimpleFieldSet handleGetIdentitiesByScore(SimpleFieldSet params) throws InvalidParameterException, MalformedURLException, UnknownIdentityException, DuplicateIdentityException {
		
		SimpleFieldSet sfs = new SimpleFieldSet(true);

		if(params.get("Select") == null || params.get("Context") == null) throw new InvalidParameterException("Missing mandatory parameter");

		sfs.putAppend("Message", "Identities");
		
		OwnIdentity treeOwner = params.get("TreeOwner")!=null ? OwnIdentity.getByURI(db, params.get("TreeOwner")) : null;
		
		String selectString = params.get("Select").trim();
		int select = 0; // TODO: decide about the default value
		
		if(selectString.equals("+")) select = 1;
		else if(selectString.equals("-")) select = -1;
		else if(selectString.equals("0")) select = 0;
		else throw new InvalidParameterException("Unhandled select value ("+select+")");
		
		ObjectSet<Score> result = Score.getIdentitiesByScore(db, treeOwner, select);
		String context = params.get("Context");
		boolean getAll = context.equals("all");

		for(int idx = 1 ; result.hasNext() ; idx++) {
			Score score = result.next();
			// TODO: Maybe there is a way to do this through SODA
			if(getAll || score.getTarget().hasContext(context)) {
				Identity id = score.getTarget();
				/* FIXME: Isn't append slower than replace? Figure this out */
				sfs.putAppend("Identity"+idx, id.getId());
				sfs.putAppend("RequestURI"+idx, id.getRequestURI().toString());
				sfs.putAppend("Nickname"+idx, id.getNickName());
				
				/* FIXME: Allow the client to select what data he wants */
			}
		}
		return sfs;
	}
	
	private SimpleFieldSet handleGetTrusters(SimpleFieldSet params) throws InvalidParameterException, MalformedURLException, UnknownIdentityException, Db4oIOException, DatabaseClosedException, DuplicateIdentityException {
		
		SimpleFieldSet sfs = new SimpleFieldSet(true);

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
		
		SimpleFieldSet sfs = new SimpleFieldSet(true);

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
		
		SimpleFieldSet sfs = new SimpleFieldSet(true);

		if(params.get("Identity") == null || params.get("Context") == null) throw new InvalidParameterException("Missing mandatory parameter");

		addContext(params.get("Identity"), params.get("Context"));
		
		sfs.putAppend("Message", "ContextAdded");
		return sfs;
	}
	
	private SimpleFieldSet handleRemoveContext(SimpleFieldSet params) throws InvalidParameterException, MalformedURLException, UnknownIdentityException, DuplicateIdentityException {
		
		SimpleFieldSet sfs = new SimpleFieldSet(true);

		if(params.get("Identity") == null || params.get("Context") == null) throw new InvalidParameterException("Missing mandatory parameter");

		removeContext(params.get("Identity"), params.get("Context"));
		
		sfs.putAppend("Message", "ContextRemoved");
		return sfs;
	}
	
	private SimpleFieldSet handleSetProperty(SimpleFieldSet params) throws InvalidParameterException, MalformedURLException, UnknownIdentityException, DuplicateIdentityException {
		
		SimpleFieldSet sfs = new SimpleFieldSet(true);

		if(params.get("Identity") == null || params.get("Property") == null || params.get("Value") == null) throw new InvalidParameterException("Missing mandatory parameter");

		setProperty(params.get("Identity"), params.get("Property"), params.get("Value"));
		
		sfs.putAppend("Message", "PropertyAdded");
		return sfs;
	}

	private SimpleFieldSet handleGetProperty(SimpleFieldSet params) throws InvalidParameterException, MalformedURLException, UnknownIdentityException, DuplicateIdentityException {
		
		SimpleFieldSet sfs = new SimpleFieldSet(true);

		if(params.get("Identity") == null || params.get("Property") == null) throw new InvalidParameterException("Missing mandatory parameter");

		sfs.putAppend("Message", "PropertyValue");
		sfs.putAppend("Property", getProperty(params.get("Identity"), params.get("Property")));
		
		return sfs;
	}

	private SimpleFieldSet handleRemoveProperty(SimpleFieldSet params) throws InvalidParameterException, MalformedURLException, UnknownIdentityException, DuplicateIdentityException {
		
		SimpleFieldSet sfs = new SimpleFieldSet(true);

		if(params.get("Identity") == null || params.get("Property") == null) throw new InvalidParameterException("Missing mandatory parameter");

		removeProperty(params.get("Identity"), params.get("Property"));
		
		sfs.putAppend("Message", "PropertyRemoved");
		return sfs;
	}
	
	private SimpleFieldSet errorMessageFCP (Exception e) {
		
		SimpleFieldSet sfs = new SimpleFieldSet(true);
		sfs.putAppend("Message", "Error");
		sfs.putAppend("Description", (e.getLocalizedMessage() == null) ? "null" : e.getLocalizedMessage());
		e.printStackTrace();
		return sfs;
	}

	public String getString(String key) {
		return key;
	}

	public void setLanguage(LANGUAGE newLanguage) {
	}
	
	/**
	 * Initializes the connection to DB4O.
	 * 
	 * @return db4o's connector
	 */
	private ObjectContainer initDB() {
		
		// Set indexes on fields we query on
		Configuration cfg = Db4o.newConfiguration();
		cfg.objectClass(Identity.class).objectField("id").indexed(true);
		cfg.objectClass(OwnIdentity.class).objectField("id").indexed(true);
		cfg.objectClass(Trust.class).objectField("truster").indexed(true);
		cfg.objectClass(Trust.class).objectField("trustee").indexed(true);
		cfg.objectClass(Score.class).objectField("treeOwner").indexed(true);
		cfg.objectClass(Score.class).objectField("target").indexed(true);
		for(String field : IntroductionPuzzle.getIndexedFields())
			cfg.objectClass(IntroductionPuzzle.class).objectField(field).indexed(true);
		cfg.objectClass(IntroductionPuzzle.class).cascadeOnUpdate(true); /* FIXME: verify if this does not break anything */
		
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
	
	public PageMaker getPageMaker() {
		return pm;
	}
	
	public ObjectContainer getDB() {
		return db;
	}
	
	public PluginRespirator getPR() {
		return pr;
	}
	
	public HighLevelSimpleClient getClient() {
		return client;
	}
}
