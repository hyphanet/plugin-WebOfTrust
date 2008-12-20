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
 * Basic implementation of the WebPage interface.<p>
 * It contains common features for every WebPages.
 * 
 * @author Julien Cornuwel (batosai@freenetproject.org)
 */
public abstract class WebPageImpl implements WebPage {
	
        /** The URI the plugin can be accessed from. */
	protected static String SELF_URI = "/plugins/plugins.WoT.WoT";
        /** The node's pagemaker */
	protected PageMaker pm;
        /** HTMLNode representing the web page */
	protected HTMLNode pageNode;
        /** A reference to the WoT */
	protected WoT wot;
        /** The request performed by the user */
	protected HTTPRequest request;
	/** The error box displayed at the top of the page */
	protected HTMLNode errorBox;
        /** List of all content boxes */
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
	}
	
	/**
	 * Generates the HTML code that will be sent to the browser.
	 * 
	 * @return HTML code of the page.
	 */
	public String toHTML() {
		
		HTMLNode contentNode = pm.getContentNode(pageNode);
		
		// We add the ErrorBox if it exists
		if(errorBox != null) contentNode.addChild(errorBox);
		
		// We add every ContentBoxes
		Iterator<HTMLNode> contentBox = contentBoxes.iterator();
		while(contentBox.hasNext()) contentNode.addChild(contentBox.next());

		/* FIXME: This code does seem to get executed but the test box is invisible. Why? */
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
	 * @return the contentNode of the newly created InfoBox
	 */
	protected HTMLNode getContentBox(String title) {
		
		HTMLNode box = pm.getInfobox(title);
		contentBoxes.add(box);
		return pm.getContentNode(box);
	}
}
