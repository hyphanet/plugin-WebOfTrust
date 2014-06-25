/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.ui.web;

import plugins.WebOfTrust.OwnIdentity;
import plugins.WebOfTrust.exceptions.UnknownIdentityException;
import plugins.WebOfTrust.introduction.IntroductionPuzzle;
import plugins.WebOfTrust.introduction.IntroductionServer;
import freenet.clients.http.RedirectException;
import freenet.clients.http.SessionManager.Session;
import freenet.clients.http.ToadletContext;
import freenet.support.HTMLNode;
import freenet.support.api.HTTPRequest;


/**
 * The page where users can edit their own identities.
 * 
 * @author xor (xor@freenetproject.org)
 * @author Julien Cornuwel (batosai@freenetproject.org)
 */
public class EditOwnIdentityPage extends WebPageImpl {
	
	private final OwnIdentity mIdentity;

	/**
	 * @throws RedirectException If the {@link Session} has expired. 
	 */
	public EditOwnIdentityPage(WebInterfaceToadlet toadlet, HTTPRequest myRequest, ToadletContext context) throws UnknownIdentityException, RedirectException {
		super(toadlet, myRequest, context, true);
		
		mIdentity = wot.getOwnIdentityByID(request.getPartAsString("id", 128));
	}
	
	public void make() {
		synchronized(wot) {
			if(request.isPartSet("Edit")) {
				final boolean newPublishTrustList = request.getPartAsStringFailsafe("PublishTrustList", 4).equals("true");
				final boolean newPublishPuzzles = request.getPartAsStringFailsafe("PublishPuzzles", 4).equals("true");
				
				try {
					wot.setPublishTrustList(mIdentity.getID(), newPublishTrustList);
					wot.setPublishIntroductionPuzzles(mIdentity.getID(), newPublishTrustList && newPublishPuzzles);
					
		            HTMLNode aBox = addContentBox(l10n().getString("EditOwnIdentityPage.SettingsSaved.Header"));
		            aBox.addChild("p", l10n().getString("EditOwnIdentityPage.SettingsSaved.Text"));
				}
				catch(Exception e) {
					addErrorBox(l10n().getString("EditOwnIdentityPage.SettingsSaveFailed"), e);
				}
			}

			HTMLNode box = addContentBox(l10n().getString("EditOwnIdentityPage.EditIdentityBox.Header", "nickname", mIdentity.getNickname()));
			
			HTMLNode createForm = pr.addFormChild(box, uri.toString(), "EditIdentity");
			createForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "id", mIdentity.getID()});
			
			createForm.addChild("p", new String[] { "style" }, new String[] { "font-size: x-small" },
					l10n().getString("EditOwnIdentityPage.EditIdentityBox.RequestUri") + ": " + mIdentity.getRequestURI().toString());
			
			createForm.addChild("p", new String[] { "style" }, new String[] { "font-size: x-small" },
					l10n().getString("EditOwnIdentityPage.EditIdentityBox.InsertUri") + ": " + mIdentity.getInsertURI().toString());
			
			// TODO Give the user the ability to edit these.
			createForm.addChild("p", l10n().getString("EditOwnIdentityPage.EditIdentityBox.Contexts") + ": " + mIdentity.getContexts());
			createForm.addChild("p", l10n().getString("EditOwnIdentityPage.EditIdentityBox.Properties") + ": " + mIdentity.getProperties());
			
			HTMLNode p = createForm.addChild("p", l10n().getString("EditOwnIdentityPage.EditIdentityBox.PublishTrustList") + ": ");
			if(mIdentity.doesPublishTrustList()) {
				p.addChild("input", new String[] { "type", "name", "value", "checked" },
						new String[] { "checkbox", "PublishTrustList", "true", "checked"});
			}
			else
				p.addChild("input", new String[] { "type", "name", "value" }, new String[] { "checkbox", "PublishTrustList", "true"});
			
			
			p = createForm.addChild("p", l10n().getString("EditOwnIdentityPage.EditIdentityBox.PublishIntroductionPuzzles") + ": ");
			if(mIdentity.hasContext(IntroductionPuzzle.INTRODUCTION_CONTEXT)) {
				p.addChild("input", new String[] { "type", "name", "value", "checked" },
						new String[] { "checkbox", "PublishPuzzles", "true", "checked"});
			}
			else
				p.addChild("input", new String[] { "type", "name", "value" }, new String[] { "checkbox", "PublishPuzzles", "true"});
			
			createForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "Edit", l10n().getString("EditOwnIdentityPage.EditIdentityBox.SaveButton") });
		}
	}
}
