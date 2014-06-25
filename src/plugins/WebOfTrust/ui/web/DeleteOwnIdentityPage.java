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
 * The web interface of the WoT plugin.
 * 
 * @author xor (xor@freenetproject.org)
 * @author Julien Cornuwel (batosai@freenetproject.org)
 */
public class DeleteOwnIdentityPage extends WebPageImpl {
	
	private final OwnIdentity mIdentity;

	/**
	 * @throws RedirectException If the {@link Session} has expired. 
	 */
	public DeleteOwnIdentityPage(WebInterfaceToadlet toadlet, HTTPRequest myRequest, ToadletContext context) throws UnknownIdentityException, RedirectException {
		super(toadlet, myRequest, context, true);
		
		mIdentity = wot.getOwnIdentityByID(request.getPartAsString("id", 128));
	}

	public void make() {
		if(request.isPartSet("confirm")) {
			try {
				wot.deleteOwnIdentity(mIdentity.getID());
				mToadlet.logOut(mContext);
				
				/* TODO: Show the MyIdentityPage instead! Use the trick which Freetalk does for inlining pages */
				HTMLNode box = addContentBox(l10n().getString("DeleteOwnIdentityPage.IdentityDeleted.Header"));
				box.addChild("#", l10n().getString("DeleteOwnIdentityPage.IdentityDeleted.Text"));
				
				try {
					mWebInterface.getToadlet(LoginWebInterfaceToadlet.class).makeWebPage(request, mContext).addToPage(this);
				} catch (RedirectException e) {
					throw new RuntimeException(e); // Shouldn't happen according to JavaDoc of constructor
				}
				
			} catch (UnknownIdentityException e) {
				addErrorBox(l10n().getString("Common.UnknownIdentityExceptionTitle"), l10n().getString("Common.UnknownIdentityExceptionDescription"));
			}
		}
		else
			makeConfirmation();
	}
	
	private void makeConfirmation() {
		HTMLNode box = addContentBox(l10n().getString("DeleteOwnIdentityPage.DeleteIdentityBox.Header"));

		box.addChild(new HTMLNode("p", l10n().getString("DeleteOwnIdentityPage.DeleteIdentityBox.Text1", "nickname", mIdentity.getNickname())));
		box.addChild(new HTMLNode("p", l10n().getString("DeleteOwnIdentityPage.DeleteIdentityBox.Text2")));

		HTMLNode confirmForm = pr.addFormChild(box, uri.toString(), "DeleteIdentity");

		confirmForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "page", "DeleteIdentity" });
		confirmForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "id", mIdentity.getID()});
		confirmForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "confirm", l10n().getString("DeleteOwnIdentityPage.DeleteIdentityBox.ConfirmButton") });
	}
}
