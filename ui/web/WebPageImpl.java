/**
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */
package plugins.WoT.ui.web;

import java.util.ArrayList;
import java.util.Iterator;

import plugins.WoT.WoT;

import freenet.clients.http.PageMaker;
import freenet.support.HTMLNode;
import freenet.support.api.HTTPRequest;

/**
 * @author Julien Cornuwel (batosai@freenetproject.org)
 */
public abstract class WebPageImpl implements WebPage {
	
	protected static String SELF_URI = "/plugins/plugins.WoT.WoT";
	protected PageMaker pm;
	protected HTMLNode pageNode;
	protected WoT wot;
	protected HTTPRequest request;
	
	protected HTMLNode errorBox;
	protected ArrayList<HTMLNode> contentBoxes;
	
	/**
	 * Creates a new WebPageImpl.
	 * It is abstract because only a subclass can run the desired make() method to generate the content.
	 * 
	 * @param wot a reference to the WoT, used to get references to database, client, whatever is needed.
	 * @param request the request from the user.
	 */
	public WebPageImpl(WoT wot, HTTPRequest request) {
		
		this.wot = wot;
		this.pm = wot.getPageMaker();
		this.pageNode = pm.getPageNode("Web of Trust", null);
		this.request = request;
		
		this.errorBox = null;
		this.contentBoxes = new ArrayList<HTMLNode>();
		
		makeMenu();
	}
	
	/**
	 * Generates the HTML code that will be sent to the browser.
	 * 
	 * @return HTML code of the page.
	 */
	public String toHTML() {
		
		//FIXME Must have missed something stupid, the generated page is empty.
		//Wil have a look at it later.
		
		// We add the ErrorBox if it exists
		if(errorBox != null) {
			pageNode.addChild(errorBox);
			System.out.println("There is an errorBox");
		}
		
		// We add every ContentBoxes
		Iterator<HTMLNode> contentBox = contentBoxes.iterator();
		while(contentBox.hasNext()) pageNode.addChild(contentBox.next());
		
		System.out.println("There are " + contentBoxes.size() + " contentBoxes");
		
		HTMLNode test = pm.getInfobox("infobox-alert", "Test");
		test.addChild("#", "Test");
		pageNode.addChild(test);
		
		// Generate the HTML output
		return pageNode.generate();
	}

	/**
	 * Adds an ErrorBox to the WebPage.
	 * 
	 * @param title The title of the desired ErrorBox
	 * @param message The error message that will be displayed
	 */
	public void addErrorBox(String title, String message) {
		
		errorBox = pm.getInfobox("infobox-alert", "Error");
		errorBox.addChild("#", message);
	}
	
	/**
	 * Adds a new InfoBox to the WebPage.
	 * 
	 * @param title The title of the desired InfoBox
	 * @param content The content of the InfoBox
	 */
	protected void addContentBox(String title, HTMLNode content) {
		
		HTMLNode box = pm.getInfobox(title);
		box.addChild(content);
		contentBoxes.add(box);
	}
	
	/**
	 * Creates the menu of the WebPage
	 */
	private void makeMenu() {
		
		// FIXME It seems that the PluginRespirator gives the same PageMaker at each request.
		// That means we keep adding links each time a page is generated :(
		pm.addNavigationLink(SELF_URI, "Home", "Home page", false, null);
		pm.addNavigationLink(SELF_URI + "?ownidentities", "Own Identities", "Manage your own identities", false, null);
		pm.addNavigationLink(SELF_URI + "?knownidentities", "Known Identities", "Manage others identities", false, null);
		pm.addNavigationLink(SELF_URI + "?configuration", "Configuration", "Configure the WoT plugin", false, null);
		pm.addNavigationLink("/plugins/", "Plugins page", "Back to Plugins page", false, null);
	}

}
