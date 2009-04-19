/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WoT.ui.web;

import java.text.SimpleDateFormat;
import java.util.TimeZone;

import plugins.WoT.Identity;
import plugins.WoT.OwnIdentity;
import plugins.WoT.Trust;
import plugins.WoT.exceptions.DuplicateScoreException;
import plugins.WoT.exceptions.DuplicateTrustException;
import plugins.WoT.exceptions.NotInTrustTreeException;
import plugins.WoT.exceptions.NotTrustedException;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;

import freenet.keys.FreenetURI;
import freenet.pluginmanager.PluginRespirator;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.api.HTTPRequest;


/**
 * The page where users can manage others identities.
 * 
 * @author xor (xor@freenetproject.org)
 * @author Julien Cornuwel (batosai@freenetproject.org)
 */
public class KnownIdentitiesPage extends WebPageImpl {

	private final static SimpleDateFormat mDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

	/**
	 * Creates a new KnownIdentitiesPage
	 * 
	 * @param myWebInterface A reference to the WebInterface which created the page, used to get resources the page needs. 
	 * @param myRequest The request sent by the user.
	 */
	public KnownIdentitiesPage(WebInterface myWebInterface, HTTPRequest myRequest) {
		super(myWebInterface, myRequest);
	}

	public void make() {
		if(request.isPartSet("AddIdentity")) {
			try {
				wot.addIdentity(request.getPartAsString("IdentityURI", 1024));
				HTMLNode successBox = addContentBox("Success");
				successBox.addChild("#", "The identity was added and is now being downloaded.");
			}
			catch(Exception e) {
				addErrorBox("Adding the identity failed", e.getMessage());
			}
		}
		
		if(request.isPartSet("SetTrust")) {
			String trusterID = request.getPartAsString("OwnerID", 128);
			String trusteeID = request.isPartSet("trustee") ? request.getPartAsString("Trustee", 128) : null;
			String value = request.getPartAsString("Value", 4);
			String comment = request.getPartAsString("Comment", 256); /* FIXME: store max length as a constant in class identity */
			
			try {
				if(trusteeID == null) /* For AddIdentity */
					trusteeID = Identity.getIDFromURI(new FreenetURI(request.getPartAsString("IdentityURI", 1024)));
				
				if(value.trim().equals(""))
					wot.removeTrust(trusterID, trusteeID);
				else
					wot.setTrust(trusterID, trusteeID, Byte.parseByte(value), comment);
			} catch(Exception e) {
				addErrorBox("Setting trust failed", e.getMessage());
			}
		}

		OwnIdentity treeOwner = null;
		ObjectContainer db = wot.getDB();
		PluginRespirator pr = wot.getPluginRespirator();
		int nbOwnIdentities = 1;
		String ownerID = request.getPartAsString("OwnerID", 128);
		
		if(!ownerID.equals("")) {
			try {
				treeOwner = wot.getOwnIdentityByID(ownerID);
			} catch (Exception e) {
				Logger.error(this, "Error while selecting the OwnIdentity", e);
				addErrorBox("Error while selecting the OwnIdentity", e.getMessage());
			}
		} else {
			synchronized(wot) {
				ObjectSet<OwnIdentity> allOwnIdentities = wot.getAllOwnIdentities();
				nbOwnIdentities = allOwnIdentities.size();
				if(nbOwnIdentities == 1)
					treeOwner = allOwnIdentities.next();
			}
		}
			
		makeAddIdentityForm(pr, treeOwner);

		if(treeOwner != null) {
			try {
				makeKnownIdentitiesList(treeOwner, db, pr);
			} catch (Exception e) {
				Logger.error(this, e.getMessage());
				addErrorBox("Error: " + e.getClass(), e.getMessage());
			}
		} else if(nbOwnIdentities > 1)
			makeSelectTreeOwnerForm(db, pr);
		else
			makeNoOwnIdentityWarning();
	}
	
	/**
	 * Makes a form where the user can enter the requestURI of an Identity he knows.
	 * 
	 * @param pr a reference to the {@link PluginRespirator}
	 * @param treeOwner The owner of the known identity list. Not used for adding the identity but for showing the known identity list properly after adding.
	 */
	private void makeAddIdentityForm(PluginRespirator pr, OwnIdentity treeOwner) {
		
		// TODO Add trust value and comment fields and make them mandatory
		// The user should only add an identity he trusts
		HTMLNode addBoxContent = addContentBox("Add an identity");
	
		HTMLNode createForm = pr.addFormChild(addBoxContent, uri, "AddIdentity");
		if(treeOwner != null)
			createForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "OwnerID", treeOwner.getID()});
		createForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "page", "AddIdentity" });
		createForm.addChild("span", new String[] {"title", "style"}, 
				new String[] { "This must be a valid Freenet URI.", "border-bottom: 1px dotted; cursor: help;"} , "Identity URI: ");
		
		createForm.addChild("input", new String[] {"type", "name", "size"}, new String[] {"text", "IdentityURI", "70"});
		createForm.addChild("br");
		
		if(treeOwner != null) {
			createForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "SetTrust", "true"});
			
			createForm.addChild("span", "Trust: ")
				.addChild("input", new String[] { "type", "name", "size", "value" }, new String[] { "text", "Value", "4", "" });
			
			createForm.addChild("span", "Comment:")
				.addChild("input", new String[] { "type", "name", "size", "value" }, new String[] { "text", "Comment", "20", "" });
			
			createForm.addChild("br");
		}
		
		createForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "AddIdentity", "Add" });
	}

	private void makeNoOwnIdentityWarning() {
		addErrorBox("No own identity found", "You should create an identity first.");
	}
	
	private void makeSelectTreeOwnerForm(ObjectContainer db, PluginRespirator pr) {

		HTMLNode listBoxContent = addContentBox("Select the trust tree owner");
		HTMLNode selectForm = pr.addFormChild(listBoxContent, uri, "ViewTree");
		selectForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "page", "ViewTree" });
		HTMLNode selectBox = selectForm.addChild("select", "name", "OwnerID");

		synchronized(wot) {
			for(OwnIdentity ownIdentity : wot.getAllOwnIdentities())
				selectBox.addChild("option", "value", ownIdentity.getID(), ownIdentity.getNickname());
		}

		selectForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "select", "View this identity's Web of Trust" });
	}
/**
	 * Makes the list of Identities known by the tree owner.
	 * 
	 * @param db a reference to the database 
	 * @param pr a reference to the {@link PluginRespirator}
	 * @param treeOwner owner of the trust tree we want to display 
	 */
	private void makeKnownIdentitiesList(OwnIdentity treeOwner, ObjectContainer db, PluginRespirator pr) throws DuplicateScoreException, DuplicateTrustException {

		HTMLNode listBoxContent = addContentBox("Known Identities");

		// Display the list of known identities
		HTMLNode identitiesTable = listBoxContent.addChild("table", "border", "0");
		HTMLNode row=identitiesTable.addChild("tr");
		row.addChild("th", "Nickname");
		row.addChild("th", "Updated");
		row.addChild("th", "Trustlist");
		row.addChild("th", "Score (Rank)");
		row.addChild("th", "Trust/Comment");
		row.addChild("th", "Trusters");
		row.addChild("th", "Trustees");
		
		synchronized(wot) {
		for(Identity id : wot.getAllIdentities()) {
			if(id == treeOwner) continue;

			row=identitiesTable.addChild("tr");
			
			// NickName
			row.addChild("td", new String[] {"title", "style"}, new String[] {id.getRequestURI().toString(), "cursor: help;"}).addChild("a", "href", "?ShowIdentity&id=" + id.getID(), id.getNickname());
			
			synchronized(mDateFormat) {
				mDateFormat.setTimeZone(TimeZone.getDefault());
				/* SimpleDateFormat.format(Date in UTC) does convert to the configured TimeZone. Interesting, eh? */
				row.addChild("td", mDateFormat.format(id.getLastChangeDate()));
			}
			
			// Publish TrustList
			row.addChild("td", new String[] { "align" }, new String[] { "center" } , id.doesPublishTrustList() ? "Yes" : "No");
			
			//Score
			try {
				row.addChild("td", new String[] { "align" }, new String[] { "center" } , String.valueOf(wot.getScore((OwnIdentity)treeOwner, id).getScore())+" ("+wot.getScore((OwnIdentity)treeOwner, id).getRank()+")");
			}
			catch (NotInTrustTreeException e) {
				// This only happen with identities added manually by the user
				// TODO Maybe we should give the opportunity to trust it at creation time
				row.addChild("td", "null");	
			}
			
			// Own Trust
			row.addChild(getReceivedTrustForm(treeOwner, id));
			
			// Nb Trusters
			HTMLNode trustersCell = row.addChild("td", new String[] { "align" }, new String[] { "center" });
			trustersCell.addChild(new HTMLNode("a", "href", uri + "?ShowIdentity&id="+id.getID(), Long.toString(wot.getReceivedTrusts(id).size())));
			
			// Nb Trustees
			HTMLNode trusteesCell = row.addChild("td", new String[] { "align" }, new String[] { "center" });
			trusteesCell.addChild(new HTMLNode("a", "href", uri + "?ShowIdentity&id="+id.getID(), Long.toString(wot.getGivenTrusts(id).size())));
		}
		}
	}
	
	private HTMLNode getReceivedTrustForm (OwnIdentity truster, Identity trustee) throws DuplicateTrustException {

		String trustValue = "";
		String trustComment = "";
		Trust trust;
		
		try {
			trust = wot.getTrust(truster, trustee);
			trustValue = String.valueOf(trust.getValue());
			trustComment = trust.getComment();
		}
		catch (NotTrustedException e) {
			Logger.debug(this, truster.getNickname() + " does not trust " + trustee.getNickname());
		} 
			
		HTMLNode cell = new HTMLNode("td");
		HTMLNode trustForm = pr.addFormChild(cell, uri, "SetTrust");
		trustForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "page", "SetTrust" });
		trustForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "OwnerID", truster.getID() });
		trustForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "Trustee", trustee.getID() });
		trustForm.addChild("input", new String[] { "type", "name", "size", "value" }, new String[] { "text", "Value", "2", trustValue });
		trustForm.addChild("input", new String[] { "type", "name", "size", "value" }, new String[] { "text", "Comment", "50", trustComment });
		trustForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "SetTrust", "Update" });

		return cell;
	}
}
