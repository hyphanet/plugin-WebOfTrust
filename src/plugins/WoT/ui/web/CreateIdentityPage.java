/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WoT.ui.web;

import plugins.WoT.WoT;
import freenet.clients.http.ToadletContext;
import freenet.keys.FreenetURI;
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
	 * @param myWebInterface A reference to the WebInterface which created the page, used to get resources the page needs. 
	 * @param myRequest The request sent by the user.
	 */
	public CreateIdentityPage(WebInterfaceToadlet toadlet, HTTPRequest myRequest, ToadletContext context) {
		super(toadlet, myRequest, context);
	}

	public void make() {
		if(request.isPartSet("CreateIdentity")) {
			try {
				wot.createOwnIdentity(request.getPartAsString("InsertURI",1024), request.getPartAsString("RequestURI",1024),
										request.getPartAsString("Nickname", 1024), request.getPartAsString("PublishTrustList", 5).equals("true"),
										null);
				
				/* TODO: inline the own identities page. first we need to modify our base class to be able to do so, see freetalk */
				
				addContentBox(WoT.getBaseL10n().getString("CreateIdentityPage.IdentityCreated.Header"))
				    .addChild("#", WoT.getBaseL10n().getString("CreateIdentityPage.IdentityCreated.Text"));
				
			} catch (Exception e) {
				addErrorBox(WoT.getBaseL10n().getString("CreateIdentityPage.IdentityCreateFailed"), e);
			}	
		}
		else
			makeCreateForm(request.getPartAsString("Nickname",1024));
	}
	
	/**
	 * Creates a form with pre-filled keypair to create an new OwnIdentity.
	 * 
	 * @param client a reference to a HighLevelSimpleClient
	 * @param pr a reference to the PluginRespirator
	 * @param nickName the nickName supplied by the user
	 */
	private void makeCreateForm(String nickName) {
		HTMLNode boxContent = addContentBox(WoT.getBaseL10n().getString("CreateIdentityPage.CreateIdentityBox.Header"));
		FreenetURI[] keypair = wot.getPluginRespirator().getHLSimpleClient().generateKeyPair("WoT");
		
		HTMLNode createForm = pr.addFormChild(boxContent, uri, "CreateIdentity");
		createForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "page", "CreateIdentity" });
		createForm.addChild("#", WoT.getBaseL10n().getString("CreateIdentityPage.CreateIdentityBox.RequestUri") + " : ");
		createForm.addChild("input", new String[] { "type", "name", "size", "value" }, new String[] { "text", "RequestURI", "70", keypair[1].toString() });
		createForm.addChild("br");
		createForm.addChild("#", WoT.getBaseL10n().getString("CreateIdentityPage.CreateIdentityBox.InsertUri") + " : ");
		createForm.addChild("input", new String[] { "type", "name", "size", "value" }, new String[] { "text", "InsertURI", "70", keypair[0].toString() });
		createForm.addChild("br");
		createForm.addChild("#", WoT.getBaseL10n().getString("CreateIdentityPage.CreateIdentityBox.PublishTrustList") + " ");
		createForm.addChild("input", new String[] { "type", "name", "value", "checked" }, new String[] { "checkbox", "PublishTrustList", "true", "checked"});
		createForm.addChild("br");
		createForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "Nickname", nickName });
		createForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "CreateIdentity", WoT.getBaseL10n().getString("CreateIdentityPage.CreateIdentityBox.CreateButton") });
	}
}
