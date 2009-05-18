/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WoT.ui.web;

import plugins.WoT.OwnIdentity;
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
	public CreateIdentityPage(WebInterface myWebInterface, HTTPRequest myRequest) {
		super(myWebInterface, myRequest);
	}

	public void make() {
		if(request.isPartSet("CreateIdentity")) {
			try {
				OwnIdentity newIdentity =
					wot.createOwnIdentity(request.getPartAsString("InsertURI",1024), request.getPartAsString("RequestURI",1024),
										request.getPartAsString("Nickname", 1024), request.getPartAsString("PublishTrustList", 5).equals("true"),
										"Freetalk"); /* FIXME: Freetalk should do that itself! */
				
				/* FIXME: inline the own identities page. first we need to modify our base class to be able to do so, see freetalk */
				
				addContentBox("Your identity was created.").addChild("#", "Please go to the own identities page and solve " +
						"introduction puzzles, otherwise nobody will see the identity!");
				
			} catch (Exception e) {
				addErrorBox("Identity creation failed", e);
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
		HTMLNode boxContent = addContentBox("Identity creation");
		FreenetURI[] keypair = wot.getPluginRespirator().getHLSimpleClient().generateKeyPair("WoT");
		
		HTMLNode createForm = pr.addFormChild(boxContent, uri, "CreateIdentity");
		createForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "page", "CreateIdentity" });
		createForm.addChild("#", "Request URI : ");
		createForm.addChild("input", new String[] { "type", "name", "size", "value" }, new String[] { "text", "RequestURI", "70", keypair[1].toString() });
		createForm.addChild("br");
		createForm.addChild("#", "Insert URI : ");
		createForm.addChild("input", new String[] { "type", "name", "size", "value" }, new String[] { "text", "InsertURI", "70", keypair[0].toString() });
		createForm.addChild("br");
		createForm.addChild("#", "Publish trust list ");
		createForm.addChild("input", new String[] { "type", "name", "value", "checked" }, new String[] { "checkbox", "PublishTrustList", "true", "checked"});
		createForm.addChild("br");
		createForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "Nickname", nickName });
		createForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "CreateIdentity", "Create it" });

	}

}
