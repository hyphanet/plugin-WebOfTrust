/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WoT.ui.web;

import plugins.WoT.OwnIdentity;
import plugins.WoT.exceptions.UnknownIdentityException;
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

	public DeleteOwnIdentityPage(WebInterfaceToadlet toadlet, HTTPRequest myRequest, ToadletContext context) throws UnknownIdentityException {
		super(toadlet, myRequest, context);
		mIdentity = wot.getOwnIdentityByID(request.getPartAsString("id", 128));
	}

	public void make() {
		if(request.isPartSet("confirm")) {
			wot.deleteIdentity(mIdentity);
			
			/* FIXME: Show the OwnIdentities page instead! Use the trick which Freetalk does for inlining pages */
			HTMLNode box = addContentBox("Success");
			box.addChild("#", "The identity was deleted.");
		}
		else
			makeConfirmation();
	}
	
	private void makeConfirmation() {
		HTMLNode box = addContentBox("Confirm identity deletion");

		box.addChild(new HTMLNode("p", "You are about to delete identity '" + mIdentity.getNickname() + "', are you sure ?"));
		box.addChild(new HTMLNode("p", "You might want to backup its keys for later use!"));

		HTMLNode confirmForm = pr.addFormChild(box, uri, "DeleteIdentity");

		confirmForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "page", "DeleteIdentity" });
		confirmForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "id", mIdentity.getID()});
		confirmForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "confirm", "I am sure, delete it." });
	}

}
