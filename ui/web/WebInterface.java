/**
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */
package plugins.WoT.ui.web;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.Date;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;

import plugins.WoT.Config;
import plugins.WoT.Identity;
import plugins.WoT.OwnIdentity;
import plugins.WoT.Score;
import plugins.WoT.Trust;
import plugins.WoT.WoT;
import plugins.WoT.exceptions.DuplicateIdentityException;
import plugins.WoT.exceptions.DuplicateScoreException;
import plugins.WoT.exceptions.DuplicateTrustException;
import plugins.WoT.exceptions.InvalidParameterException;
import plugins.WoT.exceptions.NotInTrustTreeException;
import plugins.WoT.exceptions.NotTrustedException;
import plugins.WoT.exceptions.UnknownIdentityException;
import plugins.WoT.introduction.IntroductionPuzzle;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.ext.DatabaseClosedException;
import com.db4o.ext.Db4oIOException;

import freenet.client.FetchException;
import freenet.client.HighLevelSimpleClient;
import freenet.client.InsertException;
import freenet.clients.http.PageMaker;
import freenet.keys.FreenetURI;
import freenet.l10n.L10n;
import freenet.pluginmanager.FredPluginHTTP;
import freenet.pluginmanager.PluginHTTPException;
import freenet.pluginmanager.PluginRespirator;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.api.HTTPRequest;

/**
 * @author Julien Cornuwel (batosai@freenetproject.org)
 * @author xor
 * @author Bombe
 *
 */
public class WebInterface implements FredPluginHTTP {
	
	private WoT mWoT;
	private PluginRespirator pr;
	private PageMaker pm;
	private HighLevelSimpleClient client;
	private String SELF_URI;
	private ObjectContainer db;
	private	Config config;

	public WebInterface(WoT myWoT, String uri) {
		mWoT = myWoT;
		pr = mWoT.getPR();
		db = mWoT.getDB();
		config = mWoT.getConfig();
		client = mWoT.getClient();
		SELF_URI = uri;
		
		pm = pr.getPageMaker();
		pm.addNavigationLink(SELF_URI, "Home", "Home page", false, null);
		pm.addNavigationLink(SELF_URI + "?ownidentities", "Own Identities", "Manage your own identities", false, null);
		pm.addNavigationLink(SELF_URI + "?knownidentities", "Known Identities", "Manage others identities", false, null);
		pm.addNavigationLink(SELF_URI + "?configuration", "Configuration", "Configure the WoT plugin", false, null);
		pm.addNavigationLink("/plugins/", "Plugins page", "Back to Plugins page", false, null);
	}

	public String handleHTTPGet(HTTPRequest request) throws PluginHTTPException {	
		WebPage page = null;
		
		if(request.isParameterSet("ownidentities")) page = new OwnIdentitiesPage(mWoT, request);
		else if(request.isParameterSet("knownidentities")) page = new KnownIdentitiesPage(mWoT, request);
		else if(request.isParameterSet("configuration")) page = new ConfigurationPage(mWoT, request);
		// TODO Handle these two in KnownIdentitiesPage
		else if (request.isParameterSet("showIdentity")) {
			try {
				Identity identity = Identity.getById(db, request.getParam("id"));
				ObjectSet<Trust> trusteesTrusts = identity.getGivenTrusts(db);
				ObjectSet<Trust> trustersTrusts = identity.getReceivedTrusts(db);
				page = new IdentityPage(mWoT, request, identity, trustersTrusts, trusteesTrusts);
			} catch (UnknownIdentityException uie1) {
				Logger.error(this, "Could not load identity " + request.getParam("id"), uie1);
			}
		}
		else if(request.isParameterSet("puzzle")) { 
			IntroductionPuzzle p = IntroductionPuzzle.getByID(db, request.getParam("id"));
			if(p != null) {
				byte[] data = p.getData();
			}
			/* FIXME: The current PluginManager implementation allows plugins only to send HTML replies.
			 * Implement general replying with any mime type and return the jpeg. */
			return "";
		}
		
		if (page == null) {
			page = new HomePage(mWoT, request);
		}
		
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
			if(pageTitle.equals("createIdentity")) page = new CreateIdentityPage(mWoT, request);
			else if(pageTitle.equals("createIdentity2")) {
				createIdentity(request);
				page = new OwnIdentitiesPage(mWoT, request);
			}
			else if(pageTitle.equals("addIdentity")) {
				addIdentity(request);
				page = new KnownIdentitiesPage(mWoT, request);
			}
			else if(pageTitle.equals("viewTree")) {
				page = new KnownIdentitiesPage(mWoT, request);
			}
			else if(pageTitle.equals("setTrust")) {
				setTrust(request);
				page = new KnownIdentitiesPage(mWoT, request);
			}
			else if(pageTitle.equals("editIdentity")) {
				return makeEditIdentityPage(request.getPartAsString("id", 1024));
			}
			else if(pageTitle.equals("introduceIdentity") || pageTitle.equals("solvePuzzles")) {
				page = new IntroduceIdentityPage(mWoT, request, mWoT.getIntroductionClient(), OwnIdentity.getById(db, request.getPartAsString("identity", 128)));
			}
			else if(pageTitle.equals("restoreIdentity")) {
				mWoT.restoreIdentity(request.getPartAsString("requestURI", 1024), request.getPartAsString("insertURI", 1024));
				page = new OwnIdentitiesPage(mWoT, request);
			}
			else if(pageTitle.equals("deleteIdentity")) {
				return makeDeleteIdentityPage(request.getPartAsString("id", 1024));
			}			
			else if(pageTitle.equals("deleteIdentity2")) {
				mWoT.deleteIdentity(request.getPartAsString("id", 1024));
				page = new OwnIdentitiesPage(mWoT, request);
			}			
			else {
				page = new HomePage(mWoT, request);
			}
			
			page.make();
			return page.toHTML();
		} catch (Exception e) {
			/* FIXME: Return a HTML page, not just e.getLocalizedMessage! */
			e.printStackTrace();
			return e.getLocalizedMessage();
		}
	}
	
	public String handleHTTPPut(HTTPRequest request) throws PluginHTTPException {
		return "Go to hell";
	}
	
	private HTMLNode getPageNode() {
		return pm.getPageNode("Web of Trust", null);
	}
	
	public String makeHomePage() {
		
		HTMLNode list = new HTMLNode("ul");
		
		list.addChild(new HTMLNode("li", "Own Identities : " + OwnIdentity.getNbOwnIdentities(db)));
		list.addChild(new HTMLNode("li", "Known Identities : " + Identity.getNbIdentities(db)));
		list.addChild(new HTMLNode("li", "Trust relationships : " + Trust.getNb(db)));
		list.addChild(new HTMLNode("li", "Scores : " + Score.getNb(db)));
		
		HTMLNode pageNode = getPageNode();
		HTMLNode contentNode = pm.getContentNode(pageNode);
		HTMLNode box = pm.getInfobox("Summary");
		
		HTMLNode boxContent = pm.getContentNode(box);
		boxContent.addChild(list);
		
		contentNode.addChild(box);
		return pageNode.generate();
	}
	
	public String makeOwnIdentitiesPage() {

		HTMLNode pageNode = getPageNode();
		HTMLNode contentNode = pm.getContentNode(pageNode);
		HTMLNode box = pm.getInfobox("Own Identities");
		HTMLNode boxContent = pm.getContentNode(box);

		
		ObjectSet<OwnIdentity> ownIdentities = OwnIdentity.getAllOwnIdentities(db);
		if(ownIdentities.size() == 0) {
			boxContent.addChild("p", "You have no own identity yet, you should create one...");
		}
		else {
			
			HTMLNode identitiesTable = boxContent.addChild("table", "border", "0");
			HTMLNode row=identitiesTable.addChild("tr");
			row.addChild("th", "Name");
			row.addChild("th", "Last change");
			row.addChild("th", "Last insert");
			row.addChild("th", "Publish TrustList ?");
			row.addChild("th", "Manage");
			
			while(ownIdentities.hasNext()) {
				OwnIdentity id = ownIdentities.next();
				row=identitiesTable.addChild("tr");
				row.addChild("td", new String[] {"title", "style"}, new String[] {id.getRequestURI().toString(), "cursor: help;"}, id.getNickName());
				row.addChild("td",id.getLastChangeDate().toString());
				HTMLNode cell = row.addChild("td");
				if(id.getLastInsert() == null) {
					cell.addChild("p", "Insert in progress...");
				}
				else if(id.getLastInsert().equals(new Date(0))) {
					cell.addChild("p", "Never");
				}
				else {
					cell.addChild(new HTMLNode("a", "href", "/"+id.getRequestURI().toString(), id.getLastInsert().toString()));
				}
				row.addChild("td", id.doesPublishTrustList() ? "Yes" : "No");
				
				HTMLNode manageCell = row.addChild("td");
				
				HTMLNode editForm = pr.addFormChild(manageCell, SELF_URI, "editIdentity");
				editForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "page", "editIdentity" });
				editForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "id", id.getRequestURI().toString() });
				editForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "edit", "Details" });
								
				HTMLNode deleteForm = pr.addFormChild(manageCell, SELF_URI, "deleteIdentity");
				deleteForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "page", "deleteIdentity" });
				deleteForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "id", id.getId() });
				deleteForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "delete", "Delete" });
			}
		}

		HTMLNode createForm = pr.addFormChild(boxContent, SELF_URI, "createIdentity");
		createForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "page", "createIdentity" });
		createForm.addChild("span", new String[] {"title", "style"}, new String[] { "No spaces or special characters.", "border-bottom: 1px dotted; cursor: help;"} , "NickName : ");
		createForm.addChild("input", new String[] {"type", "name", "size"}, new String[] {"text", "nickName", "30"});
		createForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "create", "Create a new identity !" });

		contentNode.addChild(box);
		

		// Form to restore an existing OwnIdentity from Freenet
		HTMLNode restoreBox = pm.getInfobox("Restore an identity from Freenet");
		HTMLNode restoreBoxContent = pm.getContentNode(restoreBox);
		
		restoreBoxContent.addChild("p", "Use this if you lost your database for some reason (crash, reinstall...) but still have your identity's keys :");
		
		HTMLNode restoreForm = pr.addFormChild(restoreBoxContent, SELF_URI, "restoreIdentity");
		restoreForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "page", "restoreIdentity" });
		restoreForm.addChild("input", new String[] { "type", "name", "size", "value" }, new String[] { "text", "requestURI", "70", "Request URI" });
		restoreForm.addChild("br");
		restoreForm.addChild("input", new String[] { "type", "name", "size", "value" }, new String[] { "text", "insertURI", "70", "InsertURI" });
		restoreForm.addChild("br");
		restoreForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "restore", "Restore this identity !" });

		restoreBoxContent.addChild("p", "Please don't use that identity (set trust, edit parameters...) until it has been restored from Freenet, or you might loose all its content.");

		contentNode.addChild(restoreBox);
		
		return pageNode.generate();
	}
	
	public String makeCreateIdentityPage(HTTPRequest request) {
		
		String nickName = request.getPartAsString("nickName",1024);
		HTMLNode pageNode = getPageNode();
		HTMLNode contentNode = pm.getContentNode(pageNode);
		HTMLNode box = pm.getInfobox("Identity creation");
		HTMLNode boxContent = pm.getContentNode(box);
		
		FreenetURI[] keypair = client.generateKeyPair("WoT");
		
		HTMLNode createForm = pr.addFormChild(boxContent, SELF_URI, "createIdentity2");
		createForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "page", "createIdentity2" });
		createForm.addChild("#", "Request URI : ");
		createForm.addChild("input", new String[] { "type", "name", "size", "value" }, new String[] { "text", "requestURI", "70", keypair[1].toString() });
		createForm.addChild("br");
		createForm.addChild("#", "Insert URI : ");
		createForm.addChild("input", new String[] { "type", "name", "size", "value" }, new String[] { "text", "insertURI", "70", keypair[0].toString() });
		createForm.addChild("br");
		createForm.addChild("#", "Publish trust list ");
		createForm.addChild("input", new String[] { "type", "name", "value", "checked" }, new String[] { "checkbox", "publishTrustList", "true", "checked"});
		createForm.addChild("br");
		createForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "nickName", nickName });
		createForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "create", "Create a new identity !" });
		
		contentNode.addChild(box);
		return pageNode.generate();
	}

	public String makeEditIdentityPage(String requestURI) throws MalformedURLException, InvalidParameterException, UnknownIdentityException, DuplicateIdentityException {
		
		OwnIdentity id = OwnIdentity.getByURI(db, requestURI);
		
		HTMLNode pageNode = getPageNode();
		HTMLNode contentNode = pm.getContentNode(pageNode);
		HTMLNode box = pm.getInfobox("Identity edition");
		HTMLNode boxContent = pm.getContentNode(box);
		
		HTMLNode createForm = pr.addFormChild(boxContent, SELF_URI, "editIdentity2");
		createForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "page", "editIdentity2"});
		
		createForm.addChild("p", "NickName : " + id.getNickName());
		
		createForm.addChild("p", new String[] { "style" }, new String[] { "font-size: x-small" }, "Request URI : "+id.getRequestURI().toString());
		createForm.addChild("p", new String[] { "style" }, new String[] { "font-size: x-small" }, "Insert URI : "+id.getInsertURI().toString());
		
		createForm.addChild("#", "Publish trust list ");
		if(id.doesPublishTrustList())
			createForm.addChild("input", new String[] { "type", "name", "value", "checked" }, new String[] { "checkbox", "publishTrustList", "true", "checked"});
		else
			createForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "checkbox", "publishTrustList", "true"});
		createForm.addChild("br");
		
		createForm.addChild("p", "Contexts : " + id.getContexts());
		
		createForm.addChild("p", "Properties : " + id.getProperties());
		
		// TODO Give the user the ability to edit these parameters...
		
		contentNode.addChild(box);
		return pageNode.generate();
	}

	public String makeDeleteIdentityPage(String id) throws DuplicateIdentityException, UnknownIdentityException {
		Identity identity = Identity.getById(db, id);
		
		HTMLNode pageNode = getPageNode();
		HTMLNode contentNode = pm.getContentNode(pageNode);
		HTMLNode box = pm.getInfobox("Confirm identity deletion");
		HTMLNode boxContent = pm.getContentNode(box);
		
		boxContent.addChild(new HTMLNode("p", "You are about to delete identity '" + identity.getNickName() + "', are you sure ?"));
		
		if(identity instanceof OwnIdentity)
			boxContent.addChild(new HTMLNode("p", "You might want to backup its keys for later use..."));
		
		HTMLNode confirmForm = pr.addFormChild(boxContent, SELF_URI, "deleteIdentity2");
		
		confirmForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "page", "deleteIdentity2" });
		confirmForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "id", identity.getId() });
		confirmForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "confirm", "Confirm !" });
		
		contentNode.addChild(box);
		return pageNode.generate();
	}
	
	// FIXME: Steal everything from freenet.clients.http.ConfigToadlet
	public String makeConfigurationPage() {
		HTMLNode list = new HTMLNode("ul");

		for(String key : config.getAllKeys()) {
			list.addChild(new HTMLNode("li", key + ": " + config.get(key))); 
		}
	
		HTMLNode pageNode = getPageNode();
		HTMLNode contentNode = pm.getContentNode(pageNode);
		HTMLNode box = pm.getInfobox("Configuration");
		HTMLNode boxContent = pm.getContentNode(box);
		boxContent.addChild(list);
		contentNode.addChild(box);

		return pageNode.generate();
	}

	/* TODO: Move to KnownIdentitiesPage! */
	private void setTrust(HTTPRequest request) throws NumberFormatException, TransformerConfigurationException, FileNotFoundException, InvalidParameterException, UnknownIdentityException, ParserConfigurationException, TransformerException, IOException, InsertException, Db4oIOException, DatabaseClosedException, DuplicateScoreException, DuplicateIdentityException, NotTrustedException, DuplicateTrustException  {
		
		mWoT.setTrust(	request.getPartAsString("ownerID", 128),
					request.getPartAsString("trustee", 128),
					request.getPartAsString("value", 4),
					request.getPartAsString("comment", 256));
	}
	
	private void addIdentity(HTTPRequest request) throws MalformedURLException, InvalidParameterException, FetchException, DuplicateIdentityException {
		mWoT.addIdentity(request.getPartAsString("identityURI", 1024).trim());
	}
	
	private OwnIdentity createIdentity(HTTPRequest request) throws TransformerConfigurationException, FileNotFoundException, InvalidParameterException, ParserConfigurationException, TransformerException, IOException, InsertException, Db4oIOException, DatabaseClosedException, DuplicateScoreException, NotTrustedException, DuplicateTrustException {

		return mWoT.createIdentity(	request.getPartAsString("insertURI",1024),
								request.getPartAsString("requestURI",1024),
								request.getPartAsString("nickName", 1024),
								request.getPartAsString("publishTrustList", 5).equals("true"),
								"Freetalk"); /* FIXME: Make Freetalk do that itself */	
	}
	
	
	private static final String l10n(String string) {
		return L10n.getString("ConfigToadlet." + string);
	}
}


