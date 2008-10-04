/**
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */
package plugins.WoT.ui.web;

import java.util.Date;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;

import plugins.WoT.Identity;
import plugins.WoT.OwnIdentity;
import plugins.WoT.Trust;
import plugins.WoT.WoT;
import plugins.WoT.exceptions.DuplicateScoreException;
import plugins.WoT.exceptions.DuplicateTrustException;
import plugins.WoT.exceptions.NotInTrustTreeException;
import plugins.WoT.exceptions.NotTrustedException;
import freenet.pluginmanager.PluginRespirator;
import freenet.support.HTMLNode;
import freenet.support.api.HTTPRequest;

/**
 * The page where users can manage others identities.
 * 
 * @author Julien Cornuwel (batosai@freenetproject.org)
 */
public class KnownIdentitiesPage extends WebPageImpl {

	/**
	 * Creates a new OwnIdentitiesPage.
	 * 
	 * @param wot a reference to the WoT, used to get resources the page needs. 
	 * @param request the request sent by the user.
	 */
	public KnownIdentitiesPage(WoT wot, HTTPRequest request) {
		super(wot, request);
	}

	/* (non-Javadoc)
	 * @see plugins.WoT.ui.web.WebPage#make()
	 */
	public void make() {
		ObjectContainer db = wot.getDB();
		PluginRespirator pr = wot.getPR();
		makeAddIdentityForm(pr);
		makeKnownIdentitiesList(request.getPartAsString("ownerURI", 1024), db, pr);
	}
	
	/**
	 * Makes a form where the user can enter the requestURI of an Identity he knows.
	 * 
	 * @param pr a reference to the {@link PluginRespirator}
	 */
	private void makeAddIdentityForm(PluginRespirator pr) {
		HTMLNode addBoxContent = getContentBox("Add an identity");
	
		HTMLNode createForm = pr.addFormChild(addBoxContent, SELF_URI, "addIdentity");
		createForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "page", "addIdentity" });
		createForm.addChild("span", new String[] {"title", "style"}, new String[] { "This must be a valid Freenet URI.", "border-bottom: 1px dotted; cursor: help;"} , "Identity URI : ");
		createForm.addChild("input", new String[] {"type", "name", "size"}, new String[] {"text", "identityURI", "70"});
		createForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "add", "Add this identity !" });	
	}

	/**
	 * Makes the list of Identities known by the tree owner.
	 * 
	 * @param db a reference to the database 
	 * @param pr a reference to the {@link PluginRespirator}
	 * @param treeOwner owner of the trust tree we want to display 
	 */
	private void makeKnownIdentitiesList(String owner, ObjectContainer db, PluginRespirator pr) {
		int nbOwnIdentities = OwnIdentity.getNbOwnIdentities(db);
		OwnIdentity treeOwner;

		HTMLNode listBoxContent = getContentBox("Known Identities");
		
		if (nbOwnIdentities == 0) {
			listBoxContent.addChild("p", "You should create an identity first...");
			return;
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
					addErrorBox("Error", e.getMessage());
					return;
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
			} catch (DuplicateScoreException e) {
				addErrorBox("Error", e.getMessage());
				return;
			}
			
			// Trust
			String trustValue = "";
			String trustComment = "";
			
			Trust trust;
			try {
				trust = treeOwner.getGivenTrust(id, db);
				trustValue = String.valueOf(trust.getValue());
				trustComment = trust.getComment();
			} 
			catch (DuplicateTrustException e) {
				addErrorBox("Error", e.getMessage());
				return;
			}
			catch (NotTrustedException e) {} 
			
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

		
	}

}
