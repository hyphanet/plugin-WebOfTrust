/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package plugins.WebOfTrust.ui.web;

import com.db4o.ObjectSet;
import freenet.clients.http.ToadletContext;
import freenet.l10n.BaseL10n;
import freenet.support.HTMLNode;
import freenet.support.api.HTTPRequest;
import plugins.WebOfTrust.OwnIdentity;
import plugins.WebOfTrust.WebOfTrust;

public final class LogInPage extends WebPageImpl {

	private final String path;
	private final String target;

	/**
	 * @param request
	 *                   Checked for "redirect-target", a node-relative target that the user is redirected to after
	 *                   logging in. This can include a path, query, and fragment,
	 *                   but any scheme, host, or port will be ignored. If this parameter is empty or not specified it
	 *                   redirects to "/WebOfTrust".
	 * @see WebOfTrust#SELF_URI
	 */
	public LogInPage(WebInterfaceToadlet toadlet, HTTPRequest request, ToadletContext context, BaseL10n _baseL10n) {
		super(toadlet, request, context, _baseL10n);
		path = toadlet.path();

		final String candidate = request.getParam("redirect-target");
		target = candidate.isEmpty() ? WebOfTrust.SELF_URI : candidate;
	}

	@Override
	public void make() {
		makeWelcomeBox();
		
		synchronized (wot) {
			final ObjectSet<OwnIdentity> ownIdentities = wot.getAllOwnIdentities();
		
			if (ownIdentities.hasNext()) {
				makeLoginBox(ownIdentities);
				makeCreateIdentityBox();
			}
		}
	}

	private final void makeWelcomeBox() {
		HTMLNode welcomeBox = addContentBox(l10n().getString("LoginPage.Welcome.Header"));
		welcomeBox.addChild("p", l10n().getString("LoginPage.Welcome.Text1"));
		welcomeBox.addChild("p", l10n().getString("LoginPage.Welcome.Text2"));
		welcomeBox.addChild("p", l10n().getString("LoginPage.Welcome.Text3"));
	}

	private final void makeLoginBox(ObjectSet<OwnIdentity> ownIdentities) {
		HTMLNode loginBox = addContentBox(l10n().getString("LoginPage.LogIn.Header"));

		HTMLNode selectForm = pr.addFormChild(loginBox, path, "LogIn");
		HTMLNode selectBox = selectForm.addChild("select", "name", "OwnIdentityID");
		for(OwnIdentity ownIdentity : ownIdentities) {
			// TODO: Freetalk has .getShortestUniqueName(), which should be moved to WoT and is preferable to full
			// nickname and ID.
			selectBox.addChild("option", "value", ownIdentity.getID(),
			    ownIdentity.getNickname() + "@" + ownIdentity.getID());
		}
		// HTMLNode escapes the target value.
		selectForm.addChild("input",
				new String[] { "type", "name", "value" },
				new String[] { "hidden", "redirect-target", target });
		selectForm.addChild("input",
				new String[] { "type", "value" },
				new String[] { "submit", l10n().getString("LoginPage.LogIn.Button") });
		selectForm.addChild("p", l10n().getString("LoginPage.CookiesRequired.Text"));
	}

	private void makeCreateIdentityBox() {
		HTMLNode createIdentityBox = addContentBox(l10n().getString("LoginPage.CreateOwnIdentity.Header"));
		HTMLNode aChild = createIdentityBox.addChild("p");
		l10n().addL10nSubstitution(
		        aChild,
		        "LoginPage.CreateOwnIdentity.Text",
		        new String[] { "link", "/link" },
		        new HTMLNode[] { new HTMLNode("a", "href", WebOfTrust.SELF_URI+"/CreateIdentity") });
	}
}
