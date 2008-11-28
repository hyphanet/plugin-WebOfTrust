/**
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */
package plugins.WoT.ui.web;

import java.util.Date;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;

import plugins.WoT.OwnIdentity;
import plugins.WoT.WoT;
import freenet.pluginmanager.PluginRespirator;
import freenet.support.HTMLNode;
import freenet.support.api.HTTPRequest;

/**
 * The page where users can manage their own identities.
 * 
 * @author Julien Cornuwel (batosai@freenetproject.org)
 */
public class OwnIdentitiesPage extends WebPageImpl {
	
	/**
	 * Creates a new OwnIdentitiesPage.
	 * 
	 * @param wot a reference to the WoT, used to get resources the page needs. 
	 * @param request the request sent by the user.
	 */
	public OwnIdentitiesPage(WoT wot, HTTPRequest request) {
		super(wot, request);
	}
	
	/* (non-Javadoc)
	 * @see plugins.WoT.ui.web.WebPage#make()
	 */
	public void make() {
		ObjectContainer db = wot.getDB();
		PluginRespirator pr = wot.getPR();
		makeOwnIdentitiesList(db, pr);
		makeRestoreOwnIdentityForm(pr);
	}
	
	/**
	 * Makes the list of known identities.
	 * 
	 * @param db a reference to the database.
	 * @param pr a reference to the {@link PluginRespirator}
	 */
	private void makeOwnIdentitiesList(ObjectContainer db, PluginRespirator pr) {

		HTMLNode boxContent = getContentBox("Summary");
		
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
			row.addChild("th", "Publishes Trustlist");
			row.addChild("th", "Trusters");
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
				
				HTMLNode trustersCell = row.addChild("td", new String[] { "align" }, new String[] { "center" });
				trustersCell.addChild(new HTMLNode("a", "href", SELF_URI + "?showIdentity&id="+id.getId(), Long.toString(id.getNbReceivedTrusts(db))));
				
				HTMLNode manageCell = row.addChild("td");
				
				HTMLNode editForm = pr.addFormChild(manageCell, SELF_URI, "editIdentity");
				editForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "page", "editIdentity" });
				editForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "id", id.getRequestURI().toString() });
				editForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "edit", "Details" });
								
				HTMLNode deleteForm = pr.addFormChild(manageCell, SELF_URI, "deleteIdentity");
				deleteForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "page", "deleteIdentity" });
				deleteForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "id", id.getId() });
				deleteForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "delete", "Delete" });
				
				HTMLNode introduceForm = pr.addFormChild(manageCell, SELF_URI, "introduceIdentity");
				introduceForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "page", "introduceIdentity" });
				introduceForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "identity", id.getId() });
				introduceForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "introduce", "Introduce" });				
			}
		}
	
		HTMLNode createForm = pr.addFormChild(boxContent, SELF_URI, "createIdentity");
		createForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "page", "createIdentity" });
		createForm.addChild("span", new String[] {"title", "style"}, new String[] { "No spaces or special characters.", "border-bottom: 1px dotted; cursor: help;"} , "NickName : ");
		createForm.addChild("input", new String[] {"type", "name", "size"}, new String[] {"text", "nickName", "30"});
		createForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "create", "Create a new identity !" });
	}

	/**
	 * Makes the form used to restore an OwnIdentity from Freenet.
	 * 
	 * @param pr a reference to the {@link PluginRespirator}
	 */
	private void makeRestoreOwnIdentityForm(PluginRespirator pr) {
		HTMLNode restoreBoxContent = getContentBox("Restore an identity from Freenet");
		restoreBoxContent.addChild("p", "Use this if you lost your database for some reason (crash, reinstall...) but still have your identity's keys :");
		
		HTMLNode restoreForm = pr.addFormChild(restoreBoxContent, SELF_URI, "restoreIdentity");
		restoreForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "page", "restoreIdentity" });
		restoreForm.addChild("input", new String[] { "type", "name", "size", "value" }, new String[] { "text", "requestURI", "70", "Request URI" });
		restoreForm.addChild("br");
		restoreForm.addChild("input", new String[] { "type", "name", "size", "value" }, new String[] { "text", "insertURI", "70", "InsertURI" });
		restoreForm.addChild("br");
		restoreForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "restore", "Restore this identity !" });
	
		restoreBoxContent.addChild("p", "Please don't use that identity (set trust, edit parameters...) until it has been restored from Freenet, or you might loose all its content.");
	}
}
