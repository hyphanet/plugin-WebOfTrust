/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WoT.ui.web;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

import plugins.WoT.OwnIdentity;

import com.db4o.ObjectSet;

import freenet.clients.http.ToadletContext;
import freenet.l10n.BaseL10n;
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
	
	private final String showIdentityURI;
	private final String createIdentityURI;
	private final String editIdentityURI;
	private final String deleteIdentityURI;
	private final String introduceIdentityURI;
	
	/**
	 * Creates a new OwnIdentitiesPage.
	 * 
	 * @param toadlet A reference to the {@link WebInterfaceToadlet} which created the page, used to get resources the page needs.
	 * @param myRequest The request sent by the user.
	 */
	public OwnIdentitiesPage(WebInterfaceToadlet toadlet, HTTPRequest myRequest, ToadletContext context, BaseL10n _baseL10n) {
		super(toadlet, myRequest, context, _baseL10n);
		
		String baseURI = toadlet.webInterface.getURI();
		
		showIdentityURI = baseURI+"/ShowIdentity";
		createIdentityURI = baseURI+"/CreateIdentity";
		editIdentityURI = baseURI+"/EditOwnIdentity";
		deleteIdentityURI = baseURI+"/DeleteOwnIdentity";
		introduceIdentityURI = baseURI+"/IntroduceIdentity";
	}

	public void make() {
		if(request.isPartSet("RestoreIdentity")) {
			try {
				wot.restoreIdentity(request.getPartAsString("RequestURI", 1024), request.getPartAsString("InsertURI", 1024));
				HTMLNode restoreBox = addContentBox(l10n().getString("OwnIdentitiesPage.RestoreIdentityInProgress.Header"));
				restoreBox.addChild("p", l10n().getString("OwnIdentitiesPage.RestoreIdentityInProgress.Text"));
			}
			catch(Exception e) {
				addErrorBox(l10n().getString("OwnIdentitiesPage.RestoreIdentityFailed"), e);
			}
		}
		synchronized(wot) {
			makeOwnIdentitiesList();
		}
		makeRestoreOwnIdentityForm();
	}

	private void makeOwnIdentitiesList() {

		HTMLNode boxContent = addContentBox(l10n().getString("OwnIdentitiesPage.OwnIdentities.Header"));
		
		ObjectSet<OwnIdentity> ownIdentities = wot.getAllOwnIdentities();
		if(ownIdentities.size() == 0) {
			boxContent.addChild("p", l10n().getString("OwnIdentitiesPage.OwnIdentities.NoOwnIdentity"));
		}
		else {
			HTMLNode identitiesTable = boxContent.addChild("table", "border", "0");
			HTMLNode row = identitiesTable.addChild("tr");
			row.addChild("th", l10n().getString("OwnIdentitiesPage.OwnIdentities.OwnIdentityTableHeader.Name"));
			row.addChild("th", l10n().getString("OwnIdentitiesPage.OwnIdentities.OwnIdentityTableHeader.LastChange"));
			row.addChild("th", l10n().getString("OwnIdentitiesPage.OwnIdentities.OwnIdentityTableHeader.LastInsert"));
			row.addChild("th", l10n().getString("OwnIdentitiesPage.OwnIdentities.OwnIdentityTableHeader.PublishesTrustlist"));
			row.addChild("th", l10n().getString("OwnIdentitiesPage.OwnIdentities.OwnIdentityTableHeader.Trusters"));
			row.addChild("th", l10n().getString("OwnIdentitiesPage.OwnIdentities.OwnIdentityTableHeader.Manage"));
			
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
				if(id.getLastInsertDate().equals(new Date(0))) {
					cell.addChild("p", l10n().getString("Common.Never"));
				}
				else {
					synchronized(mDateFormat) {
						mDateFormat.setTimeZone(TimeZone.getDefault());
						/* SimpleDateFormat.format(Date in UTC) does convert to the configured TimeZone. Interesting, eh? */
						cell.addChild(new HTMLNode("a", "href", "/"+id.getRequestURI().toString(), mDateFormat.format(id.getLastInsertDate())));
					}
				}
				row.addChild("td", new String[] { "align" }, new String[] { "center" }, 
				        id.doesPublishTrustList() 
				                ? l10n().getString("Common.Yes") 
				                : l10n().getString("Common.No"));
				
				HTMLNode trustersCell = row.addChild("td", new String[] { "align" }, new String[] { "center" });
				trustersCell.addChild(new HTMLNode("a", "href", showIdentityURI + "?id=" + id.getID(),
						Long.toString(wot.getReceivedTrusts(id).size())));
				
				HTMLNode manageCell = row.addChild("td", new String[] { "align" }, new String[] { "center" });
				
				HTMLNode editForm = pr.addFormChild(manageCell, editIdentityURI, "EditIdentity");
				editForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "page", "EditIdentity" });
				editForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "id", id.getID() });
				editForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "edit", l10n().getString("OwnIdentitiesPage.OwnIdentities.OwnIdentityTable.EditButton") });
								
				HTMLNode deleteForm = pr.addFormChild(manageCell, deleteIdentityURI, "DeleteIdentity");
				deleteForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "page", "DeleteIdentity" });
				deleteForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "id", id.getID() });
				deleteForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "delete", l10n().getString("OwnIdentitiesPage.OwnIdentities.OwnIdentityTable.DeleteButton") });
				
				HTMLNode introduceForm = pr.addFormChild(manageCell, introduceIdentityURI, "IntroduceIdentity");
				introduceForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "page", "IntroduceIdentity" });
				introduceForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "id", id.getID() });
				introduceForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "introduce", l10n().getString("OwnIdentitiesPage.OwnIdentities.OwnIdentityTable.IntroduceButton") });				
			}
		}
	
		HTMLNode createForm = pr.addFormChild(boxContent, createIdentityURI, "CreateIdentity");
		createForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "page", "CreateIdentity" });
		createForm.addChild("span", new String[] { "title", "style" }, 
				new String[] { l10n().getString("OwnIdentitiesPage.OwnIdentities.Nickname.Tooltip"), "border-bottom: 1px dotted; cursor: help;"}, 
		        l10n().getString("OwnIdentitiesPage.OwnIdentities.Nickname") + " : ");
		createForm.addChild("input", new String[] { "type", "name", "size" }, new String[] {"text", "Nickname", "30"});
		createForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "create", l10n().getString("OwnIdentitiesPage.OwnIdentities.CreateButton") });
	}

	/**
	 * Makes the form used to restore an OwnIdentity from Freenet.
	 */
	private void makeRestoreOwnIdentityForm() {
		HTMLNode restoreBoxContent = addContentBox(l10n().getString("OwnIdentitiesPage.RestoreIdentity.Header"));
		restoreBoxContent.addChild("p", l10n().getString("OwnIdentitiesPage.RestoreIdentity.Text"));
		
		HTMLNode restoreForm = pr.addFormChild(restoreBoxContent, uri, "RestoreIdentity");
		restoreForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "page", "RestoreIdentity" });
		restoreForm.addChild("input", new String[] { "type", "name", "size", "value" }, new String[] { "text", "RequestURI", "70", l10n().getString("OwnIdentitiesPage.RestoreIdentity.RequestURI") });
		restoreForm.addChild("br");
		restoreForm.addChild("input", new String[] { "type", "name", "size", "value" }, new String[] { "text", "InsertURI", "70", l10n().getString("OwnIdentitiesPage.RestoreIdentity.InsertURI") });
		restoreForm.addChild("br");
		restoreForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "RestoreIdentity", l10n().getString("OwnIdentitiesPage.RestoreIdentity.RestoreButton") });
	}
}
