/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.ui.web;

import plugins.WebOfTrust.WebOfTrust;
import plugins.WebOfTrust.ui.web.WebInterface.CreateIdentityWebInterfaceToadlet;
import plugins.WebOfTrust.ui.web.WebInterface.LoginWebInterfaceToadlet;
import plugins.WebOfTrust.util.RandomName;
import freenet.clients.http.RedirectException;
import freenet.clients.http.SessionManager.Session;
import freenet.clients.http.ToadletContext;
import freenet.keys.FreenetURI;
import freenet.l10n.BaseL10n;
import freenet.support.HTMLNode;
import freenet.support.api.HTTPRequest;

/**
 * The page the user can create an OwnIdentity.
 * 
 * @author xor (xor@freenetproject.org)
 * @author Julien Cornuwel (batosai@freenetproject.org)
 */
public class CreateIdentityPage extends WebPageImpl {

	/**
	 * Creates a new OwnIdentitiesPage.
	 * 
	 * @param toadlet A reference to the {@link WebInterfaceToadlet} which created the page, used to get resources the page needs.
	 * @param myRequest The request sent by the user.
	 * @throws RedirectException Should never be thrown since no {@link Session} is used.
	 */
	public CreateIdentityPage(WebInterfaceToadlet toadlet, HTTPRequest myRequest, ToadletContext context, BaseL10n _baseL10n) throws RedirectException {
		super(toadlet, myRequest, context, _baseL10n, false);
	}

	public void make() {
		if(request.isPartSet("CreateIdentity")) {
			try {
				wot.createOwnIdentity(new FreenetURI(request.getPartAsString("InsertURI",1024)),
										request.getPartAsString("Nickname", 1024), request.getPartAsString("PublishTrustList", 5).equals("true"),
										null);
				mToadlet.logOut(mContext);
				
				/* TODO: inline the own identities page. first we need to modify our base class to be able to do so, see freetalk */
				
				addContentBox(l10n().getString("CreateIdentityPage.IdentityCreated.Header"))
				    .addChild("#", l10n().getString("CreateIdentityPage.IdentityCreated.Text"));
				
				try {
					new LogInPage(mWebInterface.getToadlet(LoginWebInterfaceToadlet.class), request, mContext, l10n()).addToPage(this);
				} catch (RedirectException e) {
					throw new RuntimeException(e); // Shouldn't happen according to JavaDoc of constructor
				}
				
			} catch (Exception e) {
				addErrorBox(l10n().getString("CreateIdentityPage.IdentityCreateFailed"), e);
			}	
		}
		else
			makeCreateForm();
	}
	
	/**
	 * Creates a form with pre-filled keypair to create an new OwnIdentity.
	 * 
	 * @param nickName the nickName supplied by the user
	 */
	private void makeCreateForm() {
		HTMLNode boxContent = addContentBox(l10n().getString("CreateIdentityPage.CreateIdentityBox.Header"));
		FreenetURI[] keypair = wot.getPluginRespirator().getHLSimpleClient().generateKeyPair(WebOfTrust.WOT_NAME);
		
		HTMLNode createForm = pr.addFormChild(boxContent, uri.toString(), "CreateIdentity");
		createForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "page", "CreateIdentity" });
		createForm.addChild("span", new String[] { "title", "style" }, 
				new String[] { l10n().getString("CreateIdentityPage.CreateIdentityBox.Nickname.Tooltip"), "border-bottom: 1px dotted; cursor: help;"}, 
		        l10n().getString("CreateIdentityPage.CreateIdentityBox.Nickname") + " : ");
		createForm.addChild("input", new String[] { "type", "name", "size", "value" }, new String[] {"text", "Nickname", "30", RandomName.newNickname()});
		createForm.addChild("br");
		createForm.addChild("#", l10n().getString("CreateIdentityPage.CreateIdentityBox.InsertUri") + " : ");
		createForm.addChild("input", new String[] { "type", "name", "size", "value" }, new String[] { "text", "InsertURI", "70", keypair[0].toString() });
		createForm.addChild("br");
		createForm.addChild("#", l10n().getString("CreateIdentityPage.CreateIdentityBox.PublishTrustList") + " ");
		createForm.addChild("input", new String[] { "type", "name", "value", "checked" }, new String[] { "checkbox", "PublishTrustList", "true", "checked"});
		createForm.addChild("br");
		createForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "CreateIdentity", l10n().getString("CreateIdentityPage.CreateIdentityBox.CreateButton") });
	}
	
	public static void addLinkToCreateIdentityPage(WebPageImpl page) {
		final String createIdentityURI = page.mWebInterface.getToadlet(CreateIdentityWebInterfaceToadlet.class).getURI().toString();
		
		HTMLNode createIdentityBox = page.addContentBox(page.l10n().getString("CreateIdentityPage.LinkToCreateIdentityPageBox.Header"));
		page.l10n().addL10nSubstitution(
		        createIdentityBox,
		        "CreateIdentityPage.LinkToCreateIdentityPageBox.Text",
		        new String[] { "link", "/link" },
		        new HTMLNode[] { new HTMLNode("a", "href", createIdentityURI) });
	}
}
