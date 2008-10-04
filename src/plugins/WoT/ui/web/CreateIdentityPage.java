/**
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */

package plugins.WoT.ui.web;

import freenet.client.HighLevelSimpleClient;
import freenet.keys.FreenetURI;
import freenet.pluginmanager.PluginRespirator;
import freenet.support.HTMLNode;
import freenet.support.api.HTTPRequest;
import plugins.WoT.WoT;

/**
 * The page the user can create an OwnIdentity.
 * 
 * @author Julien Cornuwel (batosai@freenetproject.org)
 */
public class CreateIdentityPage extends WebPageImpl {
	
	/**
	 * Creates a new OwnIdentitiesPage.
	 * 
	 * @param wot a reference to the WoT, used to get resources the page needs. 
	 * @param request the request sent by the user.
	 */
	public CreateIdentityPage(WoT wot, HTTPRequest request) {
		super(wot, request);
	}
	
	/* (non-Javadoc)
	 * @see plugins.WoT.ui.web.WebPage#make()
	 */
	public void make() {
		HighLevelSimpleClient client = wot.getClient();
		PluginRespirator pr = wot.getPR();
		
		makeCreateForm(client, pr, request.getPartAsString("nickName",1024));
	}
	
	/**
	 * Creates a form with pre-filled keypair to create an new OwnIdentity.
	 * 
	 * @param client a reference to a HighLevelSimpleClient
	 * @param pr a reference to the PluginRespirator
	 * @param nickName the nickName supplied by the user
	 */
	private void makeCreateForm(HighLevelSimpleClient client, PluginRespirator pr, String nickName) {
		HTMLNode boxContent = getContentBox("Identity creation");
		FreenetURI[] keypair = client.generateKeyPair("WoT");
		
		HTMLNode createForm = pr.addFormChild(boxContent, SELF_URI, "createIdentity2");
		createForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "page", "createIdentity2" });
		createForm.addChild("#", "Request URI : ");
		createForm.addChild("input", new String[] { "type", "name", "size", "value" }, new String[] { "text", "requestURI", "70", keypair[1].toString() });
		createForm.addChild("br");
		createForm.addChild("#", "Insert URI : ");
		createForm.addChild("input", new String[] { "type", "name", "size", "value" }, new String[] { "text", "insertURI", "70", keypair[0].toString() });
		createForm.addChild("br");
		createForm.addChild("#", "Publish trust list ");
		createForm.addChild("input", new String[] { "type", "name", "value", "checked" }, new String[] { "checkbox", "publishTrustList", "true", "checked"});
		createForm.addChild("br");
		createForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "nickName", nickName });
		createForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "create", "Create a new identity !" });

	}

}
