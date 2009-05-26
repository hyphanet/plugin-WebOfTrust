/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WoT.ui.web;

import plugins.WoT.Version;
import plugins.WoT.WoT;
import plugins.WoT.introduction.IntroductionPuzzleStore;
import freenet.support.HTMLNode;
import freenet.support.api.HTTPRequest;

/**
 * The HomePage of the plugin.
 * 
 * @author xor (xor@freenetproject.org)
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

	public void make() {
		makeSummary();
	}

	/**
	 * Creates a short summary of what the plugin knows of the WoT.
	 */
	private void makeSummary() {
		long latestMandatoryVersion = wot.getLatestReportedVersion(WoT.WOT_NAME, true);
		if(latestMandatoryVersion > Version.version) {
			addErrorBox("IMPORTANT: New mandatory version", "According to the seed identities there is a new required version of the WoT plugin available. "
															+ "You must upgrade WoT by pressing the reload button on your node's plugins page.");
		} else {
			long latestVersion = wot.getLatestReportedVersion(WoT.WOT_NAME, false);
			if(latestVersion > Version.version) {
				addErrorBox("New version", "According to the seed identities there is a new version of the WoT plugin available. "
										+ "You might want to upgrade it by pressing the reload button on your node's plugins page.");
			}
		}

		addErrorBox("WARNING", "WoT is currently in beta stage. The web of trust will be purged when we release a final version " +
				"so please create backups of your identities' request and insert URIs if you want to use the same keys when the stable " + 
				"version is released.");
		
		HTMLNode box = addContentBox("Summary");
		
		HTMLNode list = new HTMLNode("ul");
		list.addChild(new HTMLNode("li", "Own Identities: " + wot.getAllOwnIdentities().size()));
		list.addChild(new HTMLNode("li", "Known Identities: " + wot.getAllNonOwnIdentities().size()));
		list.addChild(new HTMLNode("li", "Trust relationships: " + wot.getAllTrusts().size()));
		list.addChild(new HTMLNode("li", "Score relationships: " + wot.getAllScores().size()));
		
		IntroductionPuzzleStore puzzleStore = wot.getIntroductionPuzzleStore();
		list.addChild(new HTMLNode("li", "Unsolved own captchas: " + puzzleStore.getOwnCatpchaAmount(false)));
		list.addChild(new HTMLNode("li", "Solved own captchas: " + puzzleStore.getOwnCatpchaAmount(true)));
		list.addChild(new HTMLNode("li", "Unsolved captchas of others: " + puzzleStore.getNonOwnCaptchaAmount(false)));
		list.addChild(new HTMLNode("li", "Solved captchas of others: " + puzzleStore.getNonOwnCaptchaAmount(true)));
		list.addChild(new HTMLNode("li", "Not inserted captchas solutions: " + puzzleStore.getUninsertedSolvedPuzzles().size()));
		
		box.addChild(list);
	}

}
