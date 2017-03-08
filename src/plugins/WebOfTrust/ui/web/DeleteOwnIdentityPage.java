/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.ui.web;

import plugins.WebOfTrust.OwnIdentity;
import plugins.WebOfTrust.exceptions.UnknownIdentityException;
import plugins.WebOfTrust.ui.web.WebInterface.LoginWebInterfaceToadlet;
import freenet.clients.http.RedirectException;
import freenet.clients.http.SessionManager.Session;
import freenet.clients.http.ToadletContext;
import freenet.support.HTMLNode;
import freenet.support.api.HTTPRequest;


/**
 * Deletes the currently logged in {@link OwnIdentity}.
 * 
 * @author xor (xor@freenetproject.org)
 * @author Julien Cornuwel (batosai@freenetproject.org)
 */
public class DeleteOwnIdentityPage extends WebPageImpl {
	/**
	 * @throws RedirectException If the {@link Session} has expired. 
	 */
	public DeleteOwnIdentityPage(WebInterfaceToadlet toadlet, HTTPRequest myRequest, ToadletContext context) throws UnknownIdentityException, RedirectException {
		super(toadlet, myRequest, context, true);
	}

	@Override
	public void make(final boolean mayWrite) {
		if(mayWrite && mRequest.isPartSet("confirm")) {
			try {
				mWebOfTrust.deleteOwnIdentity(mLoggedInOwnIdentity.getID());
				mToadlet.logOut(mContext);
				
				HTMLNode box = addContentBox(l10n().getString("DeleteOwnIdentityPage.IdentityDeleted.Header"));
				box.addChild("p", l10n().getString("DeleteOwnIdentityPage.IdentityDeleted.Text1"));

				l10n().addL10nSubstitution(box.addChild("p"),
					"DeleteOwnIdentityPage.IdentityDeleted.Text2", 
					new String[] { "bold" }, new HTMLNode[] { HTMLNode.STRONG });
				
				// Cast because the casted version does not throw RedirectException.
				((LoginWebInterfaceToadlet)mWebInterface.getToadlet(LoginWebInterfaceToadlet.class))
					.makeWebPage(mRequest, mContext).addToPage(this);
			} catch (UnknownIdentityException e) {
				new ErrorPage(mToadlet, mRequest, mContext, e).addToPage(this);
			}
		}
		else
			makeConfirmation();
	}
	
	private void makeConfirmation() {
		HTMLNode box = addContentBox(l10n().getString("DeleteOwnIdentityPage.DeleteIdentityBox.Header"));

		box.addChild(new HTMLNode("p", l10n().getString("DeleteOwnIdentityPage.DeleteIdentityBox.Text1", "nickname", mLoggedInOwnIdentity.getShortestUniqueNickname())));
		box.addChild(new HTMLNode("p", l10n().getString("DeleteOwnIdentityPage.DeleteIdentityBox.Text2")));

		HTMLNode confirmForm = pr.addFormChild(box, uri.toString(), "DeleteIdentity");

		confirmForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "confirm", l10n().getString("DeleteOwnIdentityPage.DeleteIdentityBox.ConfirmButton") });
	}
}
