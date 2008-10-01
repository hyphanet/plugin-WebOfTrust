/**
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */
package plugins.WoT.ui.web;

import plugins.WoT.WoT;

import freenet.clients.http.PageMaker;
import freenet.support.HTMLNode;
import freenet.support.api.HTTPRequest;

/**
 * @author Julien Cornuwel (batosai@freenetproject.org)
 */
public abstract class WebPageImpl {
	
	protected static String SELF_URI = "/plugins/plugins.WoT.WoT";
	protected PageMaker pm;
	protected HTMLNode pageNode;
	protected WoT wot;
	
	/**
	 * Creates a new WebPage.
	 * 
	 * @param wot a reference to the WoT, used to get references to database, client, whatever is needed.
	 * @param request the request from the user.
	 */
	public WebPageImpl(WoT wot, HTTPRequest request) {
		this.wot = wot;
		pm = wot.getPageMaker();
		pageNode = pm.getPageNode("Web of Trust", null);
		makeMenu();
	}
	
	/**
	 * Abstract method actual WebPages (subclasses) will have to implement.
	 * That is where they do their job.
	 */
	public abstract void make();
	
	/**
	 * Generates the HTML code that will be sent to the browser.
	 * 
	 * @return HTML code of the page.
	 */
	public String toHTML() {
		return pageNode.generate();
	}
	
	/**
	 * Creates a new infoBox in the WebPage and returns its {@link HTMLnode}.
	 * 
	 * @param title The title of the desired InfoBox
	 * @return InfoBox' contentNode
	 */
	public HTMLNode getInfoBox(String title) {
		
		HTMLNode box = pm.getInfobox(title);
		
		HTMLNode contentNode = pm.getContentNode(pageNode);
		contentNode.addChild(box);

		return pm.getContentNode(box);
		
	}
	
	/**
	 * Returns a String containing the HTML code of the WebPage
	 * 
	 * @return HTML code of this page
	 */
	public String generateHTML() {
		
		return pageNode.generate();
	}
	
	/**
	 * Creates the menu of the WebPage
	 */
	public void makeMenu() {
		pm.addNavigationLink(SELF_URI, "Home", "Home page", false, null);
		pm.addNavigationLink(SELF_URI + "?ownidentities", "Own Identities", "Manage your own identities", false, null);
		pm.addNavigationLink(SELF_URI + "?knownidentities", "Known Identities", "Manage others identities", false, null);
		pm.addNavigationLink(SELF_URI + "?configuration", "Configuration", "Configure the WoT plugin", false, null);
		pm.addNavigationLink("/plugins/", "Plugins page", "Back to Plugins page", false, null);
	}

}
