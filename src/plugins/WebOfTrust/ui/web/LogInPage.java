/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package plugins.WebOfTrust.ui.web;

import plugins.WebOfTrust.OwnIdentity;
import plugins.WebOfTrust.WebOfTrust;
import plugins.WebOfTrust.ui.web.WebInterface.LogOutWebInterfaceToadlet;
import plugins.WebOfTrust.ui.web.WebInterface.LoginWebInterfaceToadlet;

import com.db4o.ObjectSet;

import freenet.clients.http.RedirectException;
import freenet.clients.http.SessionManager;
import freenet.clients.http.SessionManager.Session;
import freenet.clients.http.ToadletContext;
import freenet.support.HTMLNode;
import freenet.support.api.HTTPRequest;

/**
 * Can be used by third-party plugins by specifying a HTTPRequest param <code>redirect-target</code> in the URI such as:
 * <p><code>http://127.0.0.1:8888/WebOfTrust/LogIn?redirect-target=/my-plugin</code></p>
 * This would redirect the user to <code>http://127.0.0.1:8888/my-plugin</code> after log in.
 * 
 * <p>To use the log-in session cookie to obtain the logged in {@link OwnIdentity}, you would then use {@link SessionManager}.</p>
 * <p>For examples of its usage, see {@link LoginWebInterfaceToadlet}, {@link LogOutWebInterfaceToadlet} and  {@link MyIdentityPage}.</p>
 * 
 * TODO: Improve this documentation. To do so, ask operhiem1 how his plugin uses the session management.
 */
public final class LogInPage extends WebPageImpl {

	/**
	 * To which URI to redirect the client browser after log in. Can be used to allow third-party plugins to use the session management of WOT.
	 */
	private final String target;
	
	/**
	 * Default value of {@link #target}.
	 */
	public static final String DEFAULT_REDIRECT_TARGET_AFTER_LOGIN = WebOfTrust.SELF_URI;

	/**
	 * @param request Checked for param "redirect-target", a node-relative target that the user is redirected to after logging in. This can include a path,
	 *                query, and fragment, but any scheme, host, or port will be ignored. If this parameter is empty or not specified it redirects to
	 *                {@link #DEFAULT_REDIRECT_TARGET_AFTER_LOGIN}. 
	 *                This allows third party plugins to use the session-management of WOT.
	 * @throws RedirectException Should never be thrown since no {@link Session} is used.
	 */
	public LogInPage(WebInterfaceToadlet toadlet, HTTPRequest request, ToadletContext context) throws RedirectException {
		super(toadlet, request, context, false);
		target = request.getParam("redirect-target", DEFAULT_REDIRECT_TARGET_AFTER_LOGIN);
	}

	@Override
	public void make() {
		makeWelcomeBox();
		
		synchronized (mWebOfTrust) {
			final ObjectSet<OwnIdentity> ownIdentities = mWebOfTrust.getAllOwnIdentities();
		
			if (ownIdentities.hasNext()) {
				makeLoginBox(ownIdentities);
				makeCreateIdentityBox();
			} else {
				makeCreateIdentityBox(); // TODO: We should show the CreateIdentityWizard here once it has been ported from Freetalk
			}
		}
	}

	private final void makeWelcomeBox() {
	    final String[] l10nBoldSubstitutionInput = new String[] { "bold", "/bold" };
	    final String[] l10nBoldSubstitutionOutput = new String[] { "<b>", "</b>" };
	    HTMLNode paragraph;
		HTMLNode welcomeBox = addContentBox(l10n().getString("LoginPage.Welcome.Header"));
		paragraph = welcomeBox.addChild("p");
		l10n().addL10nSubstitution(paragraph, "LoginPage.Welcome.Text1", l10nBoldSubstitutionInput, l10nBoldSubstitutionOutput);
		paragraph = welcomeBox.addChild("p");
		l10n().addL10nSubstitution(paragraph, "LoginPage.Welcome.Text2", l10nBoldSubstitutionInput, l10nBoldSubstitutionOutput);
		paragraph = welcomeBox.addChild("p");
		l10n().addL10nSubstitution(paragraph, "LoginPage.Welcome.Text3", l10nBoldSubstitutionInput, l10nBoldSubstitutionOutput);
	}

	private final void makeLoginBox(ObjectSet<OwnIdentity> ownIdentities) {
		HTMLNode loginBox = addContentBox(l10n().getString("LoginPage.LogIn.Header"));

		HTMLNode selectForm = pr.addFormChild(loginBox, mToadlet.getURI().toString(), mToadlet.pageTitle);
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
	
	/**
	 * @param redirectTarget See {@link LogInPage} and {@link #target}.
	 */
	protected static final void addLoginButton(final WebPageImpl page, final HTMLNode contentNode, final OwnIdentity identity, final String redirectTarget) {
		final WebInterfaceToadlet logIn = page.mWebInterface.getToadlet(LoginWebInterfaceToadlet.class);
		final HTMLNode logInForm = page.pr.addFormChild(contentNode, logIn.getURI().toString() , logIn.pageTitle);
		logInForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "OwnIdentityID", identity.getID() });
		logInForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "redirect-target", redirectTarget });
		logInForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "submit", page.l10n().getString("LoginPage.LogIn.Button") });
		logInForm.addChild("p", page.l10n().getString("LoginPage.CookiesRequired.Text"));
	}

	private void makeCreateIdentityBox() {
		CreateIdentityWizard.addLinkToCreateIdentityWizard(this, target);
	}
}
