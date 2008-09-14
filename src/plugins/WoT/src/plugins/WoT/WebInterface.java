/**
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */
package plugins.WoT;

import java.net.MalformedURLException;
import java.util.Date;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.ext.DatabaseClosedException;
import com.db4o.ext.Db4oIOException;

import freenet.client.HighLevelSimpleClient;
import freenet.clients.http.PageMaker;
import freenet.keys.FreenetURI;
import freenet.pluginmanager.PluginRespirator;
import freenet.support.HTMLNode;
import freenet.support.api.HTTPRequest;

/**
 * @author Julien Cornuwel (batosai@freenetproject.org)
 *
 */
public class WebInterface {
	
	private PluginRespirator pr;
	private PageMaker pm;
	private HighLevelSimpleClient client;
	private String SELF_URI;
	private ObjectContainer db;

	public WebInterface(PluginRespirator pr, ObjectContainer db, HighLevelSimpleClient client, String uri) {
		this.pr = pr;
		this.client = client;
		this.SELF_URI = uri;
		this.db = db;
		
		pm = pr.getPageMaker();
		pm.addNavigationLink(SELF_URI, "Home", "Home page", false, null);
		pm.addNavigationLink(SELF_URI + "?ownidentities", "Own Identities", "Manage your own identities", false, null);
		pm.addNavigationLink(SELF_URI + "?knownidentities", "Known Identities", "Manage others identities", false, null);
		pm.addNavigationLink("/plugins/", "Plugins page", "Back to Plugins page", false, null);
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
				row.addChild("td",id.getLastChange().toString());
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
	
	public String makeKnownIdentitiesPage() throws Db4oIOException, DatabaseClosedException, InvalidParameterException, NotInTrustTreeException, DuplicateScoreException, DuplicateTrustException {
		return makeKnownIdentitiesPage("");
	}
	
	public String makeKnownIdentitiesPage(HTTPRequest request) throws Db4oIOException, DatabaseClosedException, InvalidParameterException, NotInTrustTreeException, DuplicateScoreException, DuplicateTrustException {
		return makeKnownIdentitiesPage(request.getPartAsString("ownerURI", 1024));
	}
	
	public String makeKnownIdentitiesPage(String owner) throws Db4oIOException, DatabaseClosedException, InvalidParameterException, NotInTrustTreeException, DuplicateScoreException, DuplicateTrustException {
		
		OwnIdentity treeOwner = null;

		HTMLNode pageNode = getPageNode();
		HTMLNode contentNode = pm.getContentNode(pageNode);
		
		// Add an identity form
		HTMLNode addBox = pm.getInfobox("Add an identity");
		HTMLNode addBoxContent = pm.getContentNode(addBox);
		
		HTMLNode createForm = pr.addFormChild(addBoxContent, SELF_URI, "addIdentity");
		createForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "page", "addIdentity" });
		createForm.addChild("span", new String[] {"title", "style"}, new String[] { "This must be a valid Freenet URI.", "border-bottom: 1px dotted; cursor: help;"} , "Identity URI : ");
		createForm.addChild("input", new String[] {"type", "name", "size"}, new String[] {"text", "identityURI", "70"});
		createForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "add", "Add this identity !" });	
		

		// Known identities list
		HTMLNode listBox = pm.getInfobox("Known Identities");
		HTMLNode listBoxContent = pm.getContentNode(listBox);
		
		int nbOwnIdentities = OwnIdentity.getNbOwnIdentities(db);
		
		if (nbOwnIdentities == 0) {
			listBoxContent.addChild("p", "You should create an identity first...");
			contentNode.addChild(listBox);
			return pageNode.generate();
		}
		else if (nbOwnIdentities == 1) {
			treeOwner = OwnIdentity.getAllOwnIdentities(db).next();
		}
		else {
			// Get the identity the user asked for, or the first one if he didn't
			if(owner.equals("")) {
				treeOwner = OwnIdentity.getAllOwnIdentities(db).next();
			}
			else {
				try {
					treeOwner = OwnIdentity.getByURI(db, owner);
				} catch (Exception e) {
					return e.getMessage();
				} 
			}
			
			// Display a form to select the tree owner 
			HTMLNode selectForm = pr.addFormChild(listBoxContent, SELF_URI, "viewTree");
			selectForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "page", "viewTree" });
			HTMLNode selectBox = selectForm.addChild("select", "name", "ownerURI");
			
			ObjectSet<OwnIdentity> ownIdentities = OwnIdentity.getAllOwnIdentities(db);
			while(ownIdentities.hasNext()) {
				OwnIdentity ownIdentity = ownIdentities.next();
				if(ownIdentity == treeOwner) {
					selectBox.addChild("option", new String [] {"value", "selected"}, new String [] {ownIdentity.getRequestURI().toString(), "selected"}, ownIdentity.getNickName());
				}
				else {
					selectBox.addChild("option", "value", ownIdentity.getRequestURI().toString(), ownIdentity.getNickName());				
				}
			}
			selectForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "select", "View this identity's Web of Trust" });
		}

		// Display the list of known identities
		HTMLNode identitiesTable = listBoxContent.addChild("table", "border", "0");
		HTMLNode row=identitiesTable.addChild("tr");
		row.addChild("th", "NickName");
		row.addChild("th", "Last update");
		row.addChild("th", "Publish TrustList ?");
		row.addChild("th", "Score (Rank)");
		row.addChild("th", "Trust/Comment");
		row.addChild("th", "Trusters");
		row.addChild("th", "Trustees");
		
		ObjectSet<Identity> identities = Identity.getAllIdentities(db);
		while(identities.hasNext()) {
			Identity id = identities.next();
			
			if(id == treeOwner) continue;

			row=identitiesTable.addChild("tr");
			
			// NickName
			row.addChild("td", new String[] {"title", "style"}, new String[] {id.getRequestURI().toString(), "cursor: help;"}, id.getNickName());
			
			// Last Change
			if (id.getLastChange().equals(new Date(0))) row.addChild("td", "Fetching...");
			else row.addChild("td", id.getLastChange().toString());
			
			// Publish TrustList
			row.addChild("td", new String[] { "align" }, new String[] { "center" } , id.doesPublishTrustList() ? "Yes" : "No");
			
			//Score
			try {
				row.addChild("td", new String[] { "align" }, new String[] { "center" } , String.valueOf(id.getScore((OwnIdentity)treeOwner, db).getScore())+" ("+id.getScore((OwnIdentity)treeOwner, db).getRank()+")");
			}
			catch (NotInTrustTreeException e) {
				// This only happen with identities added manually by the user
				// TODO Maybe we should give the opportunity to trust it at creation time
				row.addChild("td", "null");	
			}
			
			// Trust
			String trustValue = "";
			String trustComment = "";
			
			Trust trust;
			try {
				trust = treeOwner.getGivenTrust(id, db);
				trustValue = String.valueOf(trust.getValue());
				trustComment = trust.getComment();
			} catch (NotTrustedException e) {}
			
			HTMLNode cell = row.addChild("td");
			HTMLNode trustForm = pr.addFormChild(cell, SELF_URI, "setTrust");
			trustForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "page", "setTrust" });
			trustForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "truster", treeOwner.getRequestURI().toString() });
			trustForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "trustee", id.getRequestURI().toString() });
			trustForm.addChild("input", new String[] { "type", "name", "size", "value" }, new String[] { "text", "value", "2", trustValue });
			trustForm.addChild("input", new String[] { "type", "name", "size", "value" }, new String[] { "text", "comment", "20", trustComment });
			trustForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "update", "Update !" });
			
			// Trusters
			HTMLNode trustersCell = row.addChild("td", new String[] { "align" }, new String[] { "center" });
			trustersCell.addChild(new HTMLNode("a", "href", SELF_URI + "?getTrusters&id="+id.getId(), Long.toString(id.getNbReceivedTrusts(db))));
			
			//Trustees
			HTMLNode trusteesCell = row.addChild("td", new String[] { "align" }, new String[] { "center" });
			trusteesCell.addChild(new HTMLNode("a", "href", SELF_URI + "?getTrustees&id="+id.getId(), Long.toString(id.getNbGivenTrusts(db))));
		}
		contentNode.addChild(addBox);
		contentNode.addChild(listBox);
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
		
		createForm.addChild("p", "Contexts : "+id.getContextsAsString());
		
		createForm.addChild("p", "Properties : "+id.getPropsAsString());
		
		// TODO Give the user the ability to edit these parameters...
		
		contentNode.addChild(box);
		return pageNode.generate();
	}

	public String getTrustersPage(String id) throws DuplicateIdentityException, UnknownIdentityException {
		
		Identity identity = Identity.getById(db, id);
		
		HTMLNode pageNode = getPageNode();
		HTMLNode contentNode = pm.getContentNode(pageNode);
		HTMLNode box = pm.getInfobox("Identities that trust '" + identity.getNickName() + "'");
		HTMLNode boxContent = pm.getContentNode(box);
		
		// Display the list of known identities
		HTMLNode identitiesTable = boxContent.addChild("table", "border", "0");
		HTMLNode title = identitiesTable.addChild("tr");
		title.addChild("th", "NickName");
		title.addChild("th", "Last update");
		title.addChild("th", "Trust (Comment)");
		title.addChild("th", "Trusters");
		title.addChild("th", "Trustees");
		
		ObjectSet<Trust> trusters = identity.getReceivedTrusts(db);
		while(trusters.hasNext()) {
			
			Trust trust = trusters.next();
			HTMLNode row=identitiesTable.addChild("tr");
			
			// NickName
			row.addChild("td", new String[] {"title", "style"}, new String[] {trust.getTruster().getRequestURI().toString(), "cursor: help;"}, trust.getTruster().getNickName());
			
			// Last Change
			if (trust.getTruster().getLastChange().equals(new Date(0))) row.addChild("td", "Fetching...");
			else row.addChild("td", trust.getTruster().getLastChange().toString());
			
			// Trust/Comment
			row.addChild("td", trust.getValue() + " (" + trust.getComment() + ")");
			
			// Trusters
			HTMLNode trustersCell = row.addChild("td", new String[] { "align" }, new String[] { "center" });
			trustersCell.addChild(new HTMLNode("a", "href", SELF_URI + "?getTrusters&id="+trust.getTruster().getId(), Long.toString(trust.getTruster().getNbReceivedTrusts(db))));
			
			//Trustees
			HTMLNode trusteesCell = row.addChild("td", new String[] { "align" }, new String[] { "center" });
			trusteesCell.addChild(new HTMLNode("a", "href", SELF_URI + "?getTrustees&id="+trust.getTruster().getId(), Long.toString(trust.getTruster().getNbGivenTrusts(db))));

		}
		
		contentNode.addChild(box);
		return pageNode.generate();
	}

	public String getTrusteesPage(String id) throws DuplicateIdentityException, UnknownIdentityException {
		
		Identity identity = Identity.getById(db, id);
		
		HTMLNode pageNode = getPageNode();
		HTMLNode contentNode = pm.getContentNode(pageNode);
		HTMLNode box = pm.getInfobox("Identities that '" + identity.getNickName() + "' trusts");
		HTMLNode boxContent = pm.getContentNode(box);
		
		// Display the list of known identities
		HTMLNode identitiesTable = boxContent.addChild("table", "border", "0");
		HTMLNode title = identitiesTable.addChild("tr");
		title.addChild("th", "NickName");
		title.addChild("th", "Last update");
		title.addChild("th", "Trust (Comment)");
		title.addChild("th", "Trusters");
		title.addChild("th", "Trustees");
		
		ObjectSet<Trust> trustees = identity.getGivenTrusts(db);
		while(trustees.hasNext()) {
			
			Trust trust = trustees.next();
			HTMLNode row=identitiesTable.addChild("tr");
			
			// NickName
			row.addChild("td", new String[] {"title", "style"}, new String[] {trust.getTrustee().getRequestURI().toString(), "cursor: help;"}, trust.getTrustee().getNickName());
			
			// Last Change
			if (trust.getTrustee().getLastChange().equals(new Date(0))) row.addChild("td", "Fetching...");
			else row.addChild("td", trust.getTrustee().getLastChange().toString());
			
			// Trust/Comment
			row.addChild("td", trust.getValue() + " (" + trust.getComment() + ")");
			
			// Trusters
			HTMLNode trustersCell = row.addChild("td", new String[] { "align" }, new String[] { "center" });
			trustersCell.addChild(new HTMLNode("a", "href", SELF_URI + "?getTrusters&id="+trust.getTrustee().getId(), Long.toString(trust.getTrustee().getNbReceivedTrusts(db))));
			
			//Trustees
			HTMLNode trusteesCell = row.addChild("td", new String[] { "align" }, new String[] { "center" });
			trusteesCell.addChild(new HTMLNode("a", "href", SELF_URI + "?getTrustees&id="+trust.getTrustee().getId(), Long.toString(trust.getTrustee().getNbGivenTrusts(db))));

		}
		
		contentNode.addChild(box);
		return pageNode.generate();
	}
}
