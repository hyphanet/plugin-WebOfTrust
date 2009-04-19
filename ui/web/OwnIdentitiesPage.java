/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WoT.ui.web;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

import plugins.WoT.OwnIdentity;

import com.db4o.ObjectSet;

import freenet.support.HTMLNode;
import freenet.support.api.HTTPRequest;

/**
 * The page where users can manage their own identities.
 * 
 * @author xor (xor@freenetproject.org)
 * @author Julien Cornuwel (batosai@freenetproject.org)
 */
public class OwnIdentitiesPage extends WebPageImpl {

	private static final SimpleDateFormat mDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
	
	/**
	 * Creates a new OwnIdentitiesPage.
	 * 
	 * @param myWebInterface A reference to the WebInterface which created the page, used to get resources the page needs. 
	 * @param myRequest The request sent by the user.
	 */
	public OwnIdentitiesPage(WebInterface myWebInterface, HTTPRequest myRequest) {
		super(myWebInterface, myRequest);
		
	}

	public void make() {
		if(request.isPartSet("RestoreIdentity")) {
			try {
				wot.restoreIdentity(request.getPartAsString("RequestURI", 1024), request.getPartAsString("InsertURI", 1024));
				HTMLNode restoreBox = addContentBox("Restoring is in progress");
				restoreBox.addChild("p", "Please don't use that identity (set trust, edit parameters, etc.) until it has been restored " +
				"from Freenet, your changes will be overwritten by the old settings which are downloaded from Freenet.");
			}
			catch(Exception e) {
				addErrorBox("Restoring the identity failed", e.getMessage());
			}
		}
		synchronized(wot) {
			makeOwnIdentitiesList();
		}
		makeRestoreOwnIdentityForm();
	}

	private void makeOwnIdentitiesList() {

		HTMLNode boxContent = addContentBox("Summary");
		
		ObjectSet<OwnIdentity> ownIdentities = wot.getAllOwnIdentities();
		if(ownIdentities.size() == 0) {
			boxContent.addChild("p", "You have no own identity yet, you should create one.");
		}
		else {
			HTMLNode identitiesTable = boxContent.addChild("table", "border", "0");
			HTMLNode row = identitiesTable.addChild("tr");
			row.addChild("th", "Name");
			row.addChild("th", "Last change");
			row.addChild("th", "Last insert");
			row.addChild("th", "Publishes Trustlist");
			row.addChild("th", "Trusters");
			row.addChild("th", "Manage");
			
			while(ownIdentities.hasNext()) {
				OwnIdentity id = ownIdentities.next();
				row = identitiesTable.addChild("tr");
				
				row.addChild("td", new String[] {"title", "style", "align"},
						new String[] {id.getRequestURI().toString(), "cursor: help;", "center"}, id.getNickname());
				
				synchronized(mDateFormat) {
					mDateFormat.setTimeZone(TimeZone.getDefault());
					/* SimpleDateFormat.format(Date in UTC) does convert to the configured TimeZone. Interesting, eh? */
					row.addChild("td", mDateFormat.format(id.getLastChangeDate()));
				}
				
				HTMLNode cell = row.addChild("td", new String[] { "align" }, new String[] { "center" });
				if(id.getLastInsertDate() == null) {
					cell.addChild("p", "In progress.");
				}
				else if(id.getLastInsertDate().equals(new Date(0))) {
					cell.addChild("p", "Never");
				}
				else {
					synchronized(mDateFormat) {
						mDateFormat.setTimeZone(TimeZone.getDefault());
						/* SimpleDateFormat.format(Date in UTC) does convert to the configured TimeZone. Interesting, eh? */
						cell.addChild(new HTMLNode("a", "href", "/"+id.getRequestURI().toString(), mDateFormat.format(id.getLastInsertDate())));
					}
				}
				row.addChild("td", new String[] { "align" }, new String[] { "center" }, id.doesPublishTrustList() ? "Yes" : "No");
				
				HTMLNode trustersCell = row.addChild("td", new String[] { "align" }, new String[] { "center" });
				trustersCell.addChild(new HTMLNode("a", "href", uri + "?ShowIdentity&id="+id.getID(),
						Long.toString(wot.getReceivedTrusts(id).size())));
				
				HTMLNode manageCell = row.addChild("td", new String[] { "align" }, new String[] { "center" });
				
				HTMLNode editForm = pr.addFormChild(manageCell, uri, "EditIdentity");
				editForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "page", "EditIdentity" });
				editForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "id", id.getID() });
				editForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "edit", "Edit" });
								
				HTMLNode deleteForm = pr.addFormChild(manageCell, uri, "DeleteIdentity");
				deleteForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "page", "DeleteIdentity" });
				deleteForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "id", id.getID() });
				deleteForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "delete", "Delete" });
				
				HTMLNode introduceForm = pr.addFormChild(manageCell, uri, "IntroduceIdentity");
				introduceForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "page", "IntroduceIdentity" });
				introduceForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "id", id.getID() });
				introduceForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "introduce", "Introduce" });				
			}
		}
	
		HTMLNode createForm = pr.addFormChild(boxContent, uri, "CreateIdentity");
		createForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "page", "CreateIdentity" });
		createForm.addChild("span", new String[] { "title", "style" }, 
				new String[] { "No spaces or special characters.", "border-bottom: 1px dotted; cursor: help;"} , "Nickname : ");
		createForm.addChild("input", new String[] { "type", "name", "size" }, new String[] {"text", "Nickname", "30"});
		createForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "create", "Create" });
	}

	/**
	 * Makes the form used to restore an OwnIdentity from Freenet.
	 */
	private void makeRestoreOwnIdentityForm() {
		HTMLNode restoreBoxContent = addContentBox("Restore an identity from Freenet");
		restoreBoxContent.addChild("p", "Use this if you lost your database for some reason but still have your identity's keys:");
		
		HTMLNode restoreForm = pr.addFormChild(restoreBoxContent, uri, "RestoreIdentity");
		restoreForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "page", "RestoreIdentity" });
		restoreForm.addChild("input", new String[] { "type", "name", "size", "value" }, new String[] { "text", "RequestURI", "70", "Request URI" });
		restoreForm.addChild("br");
		restoreForm.addChild("input", new String[] { "type", "name", "size", "value" }, new String[] { "text", "InsertURI", "70", "InsertURI" });
		restoreForm.addChild("br");
		restoreForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "RestoreIdentity", "Restore" });
	}
}
