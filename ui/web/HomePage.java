/**
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */
package plugins.WoT.ui.web;

import plugins.WoT.Identity;
import plugins.WoT.OwnIdentity;
import plugins.WoT.Score;
import plugins.WoT.Trust;
import plugins.WoT.WoT;

import com.db4o.ObjectContainer;

import freenet.support.HTMLNode;
import freenet.support.api.HTTPRequest;

/**
 * @author Julien Cornuwel (batosai@freenetproject.org)
 */

public class HomePage extends WebPageImpl {
	
	public HomePage(WoT wot, HTTPRequest request) {
		super(wot, request);
	}
	
	public void make() {
		makeSummary();
	}
	
	private void makeSummary() {
		ObjectContainer db = wot.getDB();
		HTMLNode box = getContentBox("Summary");
		
		HTMLNode list = new HTMLNode("ul");
		list.addChild(new HTMLNode("li", "Own Identities : " + OwnIdentity.getNbOwnIdentities(db)));
		list.addChild(new HTMLNode("li", "Known Identities : " + Identity.getNbIdentities(db)));
		list.addChild(new HTMLNode("li", "Trust relationships : " + Trust.getNb(db)));
		list.addChild(new HTMLNode("li", "Scores : " + Score.getNb(db)));
		
		box.addChild(list);
	}

}
