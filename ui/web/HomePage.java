/**
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */
package plugins.WoT.ui.web;

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
	 * @param myWebInterface A reference to the WebInterface which created the page, used to get resources the page needs. 
	 * @param myRequest The request sent by the user.
	 */
	public HomePage(WebInterface myWebInterface, HTTPRequest myRequest) {
		super(myWebInterface, myRequest);
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
		HTMLNode box = getContentBox("Summary");
		
		HTMLNode list = new HTMLNode("ul");
		list.addChild(new HTMLNode("li", "Own Identities: " + wot.getAllOwnIdentities().size()));
		list.addChild(new HTMLNode("li", "Known Identities: " + wot.getAllNonOwnIdentities().size()));
		list.addChild(new HTMLNode("li", "Trust relationships: " + wot.getAllTrusts().size()));
		list.addChild(new HTMLNode("li", "Own unsolved captchas: " + wot.getIntroductionPuzzleStore().getOwnCatpchaAmount(false)));
		list.addChild(new HTMLNode("li", "Own solved captchas: " + wot.getIntroductionPuzzleStore().getOwnCatpchaAmount(true)));
		list.addChild(new HTMLNode("li", "Other's unsolved captchas: " + wot.getIntroductionPuzzleStore().getNonOwnCaptchaAmount(false)));
		list.addChild(new HTMLNode("li", "Other's solved captchas: " + wot.getIntroductionPuzzleStore().getNonOwnCaptchaAmount(true)));
		
		box.addChild(list);
	}

}
