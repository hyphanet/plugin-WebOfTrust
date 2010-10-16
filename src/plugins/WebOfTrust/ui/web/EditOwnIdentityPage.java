/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.ui.web;

import plugins.WebOfTrust.OwnIdentity;
import plugins.WebOfTrust.exceptions.UnknownIdentityException;
import plugins.WebOfTrust.introduction.IntroductionPuzzle;
import plugins.WebOfTrust.introduction.IntroductionServer;
import freenet.clients.http.ToadletContext;
import freenet.l10n.BaseL10n;
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

	public EditOwnIdentityPage(WebInterfaceToadlet toadlet, HTTPRequest myRequest, ToadletContext context, BaseL10n _baseL10n) throws UnknownIdentityException {
		super(toadlet, myRequest, context, _baseL10n);
		
		mIdentity = wot.getOwnIdentityByID(request.getPartAsString("id", 128));
	}
	
	public void make() {
		synchronized(wot) {
		synchronized(mIdentity) {
			if(request.isPartSet("Edit")) {
				try {
					mIdentity.setPublishTrustList(request.isPartSet("PublishTrustList") && 
						request.getPartAsString("PublishTrustList", 6).equals("true"));

					if(mIdentity.doesPublishTrustList() && request.isPartSet("PublishPuzzles") && 
							request.getPartAsString("PublishPuzzles", 6).equals("true")) {
						
						mIdentity.addContext(IntroductionPuzzle.INTRODUCTION_CONTEXT);
						mIdentity.setProperty(IntroductionServer.PUZZLE_COUNT_PROPERTY, Integer.toString(IntroductionServer.DEFAULT_PUZZLE_COUNT));
					}
					else {
						mIdentity.removeContext(IntroductionPuzzle.INTRODUCTION_CONTEXT);
						mIdentity.removeProperty(IntroductionServer.PUZZLE_COUNT_PROPERTY);
					}
					
					wot.storeAndCommit(mIdentity);
					
		            HTMLNode aBox = addContentBox(l10n().getString("EditOwnIdentityPage.SettingsSaved.Header"));
		            aBox.addChild("p", l10n().getString("EditOwnIdentityPage.SettingsSaved.Text"));
				}
				catch(Exception e) {
					addErrorBox(l10n().getString("EditOwnIdentityPage.SettingsSaveFailed"), e);
				}	
			}

			HTMLNode box = addContentBox(l10n().getString("EditOwnIdentityPage.EditIdentityBox.Header", "nickname", mIdentity.getNickname()));
			
			HTMLNode createForm = pr.addFormChild(box, uri, "EditIdentity");
			createForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "id", mIdentity.getID()});
			createForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "page", "EditIdentity"});
			
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
}
