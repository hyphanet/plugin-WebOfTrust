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
 * The HomePage of the plugin.
 * 
 * @author Julien Cornuwel (batosai@freenetproject.org)
 */

public class HomePage extends WebPageImpl {
	
	/**
	 * Creates a new HomePage.
	 * 
	 * @param wot a reference to the WoT, used to get resources the page needs. 
	 * @param request the request sent by the user.
	 */
	public HomePage(WoT wot, HTTPRequest request) {
		super(wot, request);
	}
	
	/* (non-Javadoc)
	 * @see plugins.WoT.ui.web.WebPage#make()
	 */
	public void make() {
		makeSummary();
	}
	
	/**
	 * Creates a short summary of what the plugin knows of the WoT.
	 */
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
