/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.ui.web;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

import freenet.clients.http.SessionManager;
import plugins.WebOfTrust.OwnIdentity;
import plugins.WebOfTrust.WebOfTrust;
import plugins.WebOfTrust.exceptions.UnknownIdentityException;
import plugins.WebOfTrust.util.RandomName;

import com.db4o.ObjectSet;

import freenet.clients.http.ToadletContext;
import freenet.keys.FreenetURI;
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
	private final String nickname;
	
	/**
	 * Creates a new OwnIdentitiesPage.
	 * 
	 * @param toadlet A reference to the {@link WebInterfaceToadlet} which created the page, used to get resources the page needs.
	 * @param myRequest The request sent by the user.
	 */
	public OwnIdentitiesPage(WebInterfaceToadlet toadlet, HTTPRequest myRequest, ToadletContext context, BaseL10n _baseL10n) {
		super(toadlet, myRequest, context, _baseL10n);

		final WebOfTrust wot = toadlet.webInterface.getWoT();

		SessionManager.Session session = wot.getPluginRespirator().getSessionManager(WebOfTrust.WOT_NAME).useSession(context);
		OwnIdentity identity = null;
		if (session != null) {
			try {
				identity = wot.getOwnIdentityByID(session.getUserID());
			} catch (UnknownIdentityException e) {
				identity = null;
			}
		}
		nickname = identity == null ? "" : identity.getNickname();

		String baseURI = toadlet.webInterface.getURI();
		
		showIdentityURI = baseURI+"/ShowIdentity";
		createIdentityURI = baseURI+"/CreateIdentity";
		editIdentityURI = baseURI+"/EditOwnIdentity";
		deleteIdentityURI = baseURI+"/DeleteOwnIdentity";
		introduceIdentityURI = baseURI+"/IntroduceIdentity";
	}

	@Override
	public void make() {
		if(request.isPartSet("RestoreOwnIdentity")) {
			try {
				wot.restoreOwnIdentity(new FreenetURI(request.getPartAsString("InsertURI", 1024)));
				HTMLNode restoreBox = addContentBox(l10n().getString("OwnIdentitiesPage.RestoreOwnIdentityInProgress.Header"));
				restoreBox.addChild("p", l10n().getString("OwnIdentitiesPage.RestoreOwnIdentityInProgress.Text"));
			}
			catch(Exception e) {
				addErrorBox(l10n().getString("OwnIdentitiesPage.RestoreOwnIdentityFailed"), e);
			}
		}
		if (!nickname.isEmpty()) {
			makeLoggedInAs();
		}
		synchronized(wot) {
			makeOwnIdentitiesList();
		}
		makeRestoreOwnIdentityForm();
	}

	private void makeLoggedInAs() {
		HTMLNode content = addContentBox(l10n().getString("OwnIdentitiesPage.LogIn.Header"));
		content.addChild("p", nickname);
	}

	private void makeOwnIdentitiesList() {

		HTMLNode boxContent = addContentBox(l10n().getString("OwnIdentitiesPage.OwnIdentities.Header"));
		
		synchronized(wot) {
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
			row.addChild("th", l10n().getString("OwnIdentitiesPage.OwnIdentities.OwnIdentityTableHeader.Trustees"));
			row.addChild("th", l10n().getString("OwnIdentitiesPage.OwnIdentities.OwnIdentityTableHeader.Manage"));
			
			while(ownIdentities.hasNext()) {
				OwnIdentity id = ownIdentities.next();
				row = identitiesTable.addChild("tr");
				
				final boolean restoreInProgress = id.isRestoreInProgress();
				
				String nickname = id.getNickname();
				if(nickname == null && restoreInProgress) {
					nickname = l10n().getString("OwnIdentitiesPage.OwnIdentities.OwnIdentityTable.RestoreInProgress");
				}
				
				row.addChild("td", new String[] {"title", "style", "align"},
						new String[] {id.getRequestURI().toString(), "cursor: help;", "center"}, nickname);
				
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
				
				// TODO: Do a direct link to the received-trusts part of the linked page
				HTMLNode trustersCell = row.addChild("td", new String[] { "align" }, new String[] { "center" });
				String trustersString = Long.toString(wot.getReceivedTrusts(id).size());
				if(restoreInProgress)
					trustersCell.addChild("#", trustersString);
				else
					trustersCell.addChild(new HTMLNode("a", "href", showIdentityURI + "?id=" + id.getID(), trustersString));
				
				// TODO: Do a direct link to the given-trusts part of the linked page
				HTMLNode trusteesCell = row.addChild("td", new String[] { "align" }, new String[] { "center" });
				String trusteesString = Long.toString(wot.getGivenTrusts(id).size());
				if(restoreInProgress)
					trusteesCell.addChild("#", trusteesString);
				else
					trusteesCell.addChild(new HTMLNode("a", "href", showIdentityURI + "?id=" + id.getID(),
						trusteesString));
				
				HTMLNode manageCell = row.addChild("td", new String[] { "align" }, new String[] { "center" });

				HTMLNode deleteForm = pr.addFormChild(manageCell, deleteIdentityURI, "DeleteIdentity");
				deleteForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "page", "DeleteIdentity" });
				deleteForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "id", id.getID() });
				deleteForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "delete", l10n().getString("OwnIdentitiesPage.OwnIdentities.OwnIdentityTable.DeleteButton") });
				
				if(id.isRestoreInProgress()) {
					manageCell.addChild("p", l10n().getString("OwnIdentitiesPage.OwnIdentities.OwnIdentityTable.RestoreInProgress"));
				} else {	
					HTMLNode editForm = pr.addFormChild(manageCell, editIdentityURI, "EditIdentity");
					editForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "page", "EditIdentity" });
					editForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "id", id.getID() });
					editForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "edit", l10n().getString("OwnIdentitiesPage.OwnIdentities.OwnIdentityTable.EditButton") });
				
					HTMLNode introduceForm = pr.addFormChild(manageCell, introduceIdentityURI, "IntroduceIdentity");
					introduceForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "page", "IntroduceIdentity" });
					introduceForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "id", id.getID() });
					introduceForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "introduce", l10n().getString("OwnIdentitiesPage.OwnIdentities.OwnIdentityTable.IntroduceButton") });				
				}
			}
		}
		}
	
		HTMLNode createForm = pr.addFormChild(boxContent, createIdentityURI, "CreateIdentity");
		createForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "page", "CreateIdentity" });
		createForm.addChild("span", new String[] { "title", "style" }, 
				new String[] { l10n().getString("OwnIdentitiesPage.OwnIdentities.Nickname.Tooltip"), "border-bottom: 1px dotted; cursor: help;"}, 
		        l10n().getString("OwnIdentitiesPage.OwnIdentities.Nickname") + " : ");
		createForm.addChild("input", new String[] { "type", "name", "size", "value" }, new String[] {"text", "Nickname", "30", RandomName.newNickname()});
		createForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "create", l10n().getString("OwnIdentitiesPage.OwnIdentities.CreateButton") });
	}

	/**
	 * Makes the form used to restore an OwnIdentity from Freenet.
	 */
	private void makeRestoreOwnIdentityForm() {
		HTMLNode restoreBoxContent = addContentBox(l10n().getString("OwnIdentitiesPage.RestoreOwnIdentity.Header"));
		restoreBoxContent.addChild("p", l10n().getString("OwnIdentitiesPage.RestoreOwnIdentity.Text"));
		
		HTMLNode restoreForm = pr.addFormChild(restoreBoxContent, uri, "RestoreOwnIdentity");
		restoreForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "page", "RestoreOwnIdentity" });
		restoreForm.addChild("input", new String[] { "type", "name", "size", "value" }, new String[] { "text", "InsertURI", "70", l10n().getString("OwnIdentitiesPage.RestoreOwnIdentity.InsertURI") });
		restoreForm.addChild("br");
		restoreForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "RestoreOwnIdentity", l10n().getString("OwnIdentitiesPage.RestoreOwnIdentity.RestoreButton") });
	}
}
