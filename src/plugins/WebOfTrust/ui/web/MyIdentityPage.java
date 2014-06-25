/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.ui.web;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

import plugins.WebOfTrust.OwnIdentity;
import plugins.WebOfTrust.WebOfTrust;
import plugins.WebOfTrust.WebOfTrustInterface;
import plugins.WebOfTrust.exceptions.UnknownIdentityException;

import com.db4o.ObjectSet;

import freenet.clients.http.RedirectException;
import freenet.clients.http.SessionManager;
import freenet.clients.http.SessionManager.Session;
import freenet.clients.http.ToadletContext;
import freenet.keys.FreenetURI;
import freenet.support.HTMLNode;
import freenet.support.api.HTTPRequest;

/**
 * This page allows the user to display information about the currently logged in {@link OwnIdentity}.
 * It is shown right after the user logs in.
 * 
 * @author xor (xor@freenetproject.org)
 * @author Julien Cornuwel (batosai@freenetproject.org)
 */
public class MyIdentityPage extends WebPageImpl {

	private static final SimpleDateFormat mDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
	
	private final String showIdentityURI;
	private final String createIdentityURI;
	private final String editIdentityURI;
	private final String deleteIdentityURI;
	private final String introduceIdentityURI;
	
	private final OwnIdentity mIdentity;
	
	/**
	 * Creates a new MyIdentityPage.
	 * 
	 * @param toadlet A reference to the {@link WebInterfaceToadlet} which created the page, used to get resources the page needs.
	 * @param myRequest The request sent by the user.
	 * @throws RedirectException If the {@link Session} has expired.
	 * @throws UnknownIdentityException If the owner of the {@link Session} does not exist anymore.
	 */
	public MyIdentityPage(WebInterfaceToadlet toadlet, HTTPRequest myRequest, ToadletContext context) throws RedirectException, UnknownIdentityException {
		super(toadlet, myRequest, context, true);

		mIdentity = wot.getOwnIdentityByID(mLoggedInOwnIdentityID);

		String baseURI = toadlet.webInterface.getURI();
		
		// FIXME: toadlet.webInterface.getToadlet(ToadletType.class).getURI()
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
				HTMLNode restoreBox = addContentBox(l10n().getString("MyIdentityPage.RestoreOwnIdentityInProgress.Header"));
				restoreBox.addChild("p", l10n().getString("MyIdentityPage.RestoreOwnIdentityInProgress.Text"));
			}
			catch(Exception e) {
				addErrorBox(l10n().getString("MyIdentityPage.RestoreOwnIdentityFailed"), e);
			}
		}
		
		makeLoggedInAs();
		
		makeRestoreOwnIdentityForm();
	}

	/**
	 * This function was originally able to display a table of all {@link OwnIdentity}s in the WOT database. This did make sense back when the web-interface
	 * did not allow the user to log in and instead always displayed everything. 
	 * Since the web-interface was changed to log-in style, the code has been refactored to only display a single {@link OwnIdentity} - the currently logged in one.
	 * This has been done by removing the loops which iterates over the identities. 
	 * Conclusion: TODO: Change the layout of the function to be less loop-alike and take more advantage of the fact that there only is one identity.
	 */
	private void makeLoggedInAs() {
		HTMLNode boxContent = addContentBox(l10n().getString("MyIdentityPage.LogIn.Header"));
		


			HTMLNode identitiesTable = boxContent.addChild("table", "border", "0");
			HTMLNode row = identitiesTable.addChild("tr");
			row.addChild("th", l10n().getString("MyIdentityPage.OwnIdentities.OwnIdentityTableHeader.Name"));
			row.addChild("th", l10n().getString("MyIdentityPage.OwnIdentities.OwnIdentityTableHeader.LastChange"));
			row.addChild("th", l10n().getString("MyIdentityPage.OwnIdentities.OwnIdentityTableHeader.LastInsert"));
			row.addChild("th", l10n().getString("MyIdentityPage.OwnIdentities.OwnIdentityTableHeader.PublishesTrustlist"));
			row.addChild("th", l10n().getString("MyIdentityPage.OwnIdentities.OwnIdentityTableHeader.Trusters"));
			row.addChild("th", l10n().getString("MyIdentityPage.OwnIdentities.OwnIdentityTableHeader.Trustees"));
			row.addChild("th", l10n().getString("MyIdentityPage.OwnIdentities.OwnIdentityTableHeader.Manage"));
			
				OwnIdentity id = mIdentity;
				row = identitiesTable.addChild("tr");
				
				final boolean restoreInProgress = id.isRestoreInProgress();
				
				String nickname = id.getNickname();
				if(nickname == null && restoreInProgress) {
					nickname = l10n().getString("MyIdentityPage.OwnIdentities.OwnIdentityTable.RestoreInProgress");
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
				deleteForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "delete", l10n().getString("MyIdentityPage.OwnIdentities.OwnIdentityTable.DeleteButton") });
				
				if(id.isRestoreInProgress()) {
					manageCell.addChild("p", l10n().getString("MyIdentityPage.OwnIdentities.OwnIdentityTable.RestoreInProgress"));
				} else {	
					HTMLNode editForm = pr.addFormChild(manageCell, editIdentityURI, "EditIdentity");
					editForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "page", "EditIdentity" });
					editForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "id", id.getID() });
					editForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "edit", l10n().getString("MyIdentityPage.OwnIdentities.OwnIdentityTable.EditButton") });
				
					HTMLNode introduceForm = pr.addFormChild(manageCell, introduceIdentityURI, "IntroduceIdentity");
					introduceForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "page", "IntroduceIdentity" });
					introduceForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "id", id.getID() });
					introduceForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "introduce", l10n().getString("MyIdentityPage.OwnIdentities.OwnIdentityTable.IntroduceButton") });				
				}


		CreateIdentityPage.addLinkToCreateIdentityPage(this);
	}

	/**
	 * Makes the form used to restore an OwnIdentity from Freenet.
	 */
	private void makeRestoreOwnIdentityForm() {
		HTMLNode restoreBoxContent = addContentBox(l10n().getString("MyIdentityPage.RestoreOwnIdentity.Header"));
		restoreBoxContent.addChild("p", l10n().getString("MyIdentityPage.RestoreOwnIdentity.Text"));
		
		HTMLNode restoreForm = pr.addFormChild(restoreBoxContent, uri.toString(), "RestoreOwnIdentity");
		restoreForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "page", "RestoreOwnIdentity" });
		restoreForm.addChild("input", new String[] { "type", "name", "size", "value" }, new String[] { "text", "InsertURI", "70", l10n().getString("MyIdentityPage.RestoreOwnIdentity.InsertURI") });
		restoreForm.addChild("br");
		restoreForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "RestoreOwnIdentity", l10n().getString("MyIdentityPage.RestoreOwnIdentity.RestoreButton") });
	}
}
