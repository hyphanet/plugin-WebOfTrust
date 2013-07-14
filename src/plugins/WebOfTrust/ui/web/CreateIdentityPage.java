/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.ui.web;

import plugins.WebOfTrust.WebOfTrust;
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
	 */
	public CreateIdentityPage(WebInterfaceToadlet toadlet, HTTPRequest myRequest, ToadletContext context, BaseL10n _baseL10n) {
		super(toadlet, myRequest, context, _baseL10n);
	}

	public void make() {
		if(request.isPartSet("CreateIdentity")) {
			try {
				wot.createOwnIdentity(new FreenetURI(request.getPartAsString("InsertURI",1024)),
										request.getPartAsString("Nickname", 1024), request.getPartAsString("PublishTrustList", 5).equals("true"),
										null);
				
				/* TODO: inline the own identities page. first we need to modify our base class to be able to do so, see freetalk */
				
				addContentBox(l10n().getString("CreateIdentityPage.IdentityCreated.Header"))
				    .addChild("#", l10n().getString("CreateIdentityPage.IdentityCreated.Text"));
				
			} catch (Exception e) {
				addErrorBox(l10n().getString("CreateIdentityPage.IdentityCreateFailed"), e);
			}	
		}
		else
			makeCreateForm(request.getPartAsString("Nickname",1024));
	}
	
	/**
	 * Creates a form with pre-filled keypair to create an new OwnIdentity.
	 * 
	 * @param nickName the nickName supplied by the user
	 */
	private void makeCreateForm(String nickName) {
		HTMLNode boxContent = addContentBox(l10n().getString("CreateIdentityPage.CreateIdentityBox.Header"));
		FreenetURI[] keypair = wot.getPluginRespirator().getHLSimpleClient().generateKeyPair(WebOfTrust.WOT_NAME);
		
		HTMLNode createForm = pr.addFormChild(boxContent, uri, "CreateIdentity");
		createForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "page", "CreateIdentity" });
		createForm.addChild("#", l10n().getString("CreateIdentityPage.CreateIdentityBox.InsertUri") + " : ");
		createForm.addChild("input", new String[] { "type", "name", "size", "value" }, new String[] { "text", "InsertURI", "70", keypair[0].toString() });
		createForm.addChild("br");
		createForm.addChild("#", l10n().getString("CreateIdentityPage.CreateIdentityBox.PublishTrustList") + " ");
		createForm.addChild("input", new String[] { "type", "name", "value", "checked" }, new String[] { "checkbox", "PublishTrustList", "true", "checked"});
		createForm.addChild("br");
		createForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "Nickname", nickName });
		createForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "CreateIdentity", l10n().getString("CreateIdentityPage.CreateIdentityBox.CreateButton") });
	}
}
