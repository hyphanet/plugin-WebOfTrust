/**
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */
package plugins.WoT.ui.web;

import java.util.ArrayList;
import java.util.Iterator;

import plugins.WoT.WoT;

import freenet.clients.http.InfoboxNode;
import freenet.clients.http.PageMaker;
import freenet.clients.http.PageNode;
import freenet.clients.http.ToadletContext;
import freenet.pluginmanager.PluginRespirator;
import freenet.support.HTMLNode;
import freenet.support.api.HTTPRequest;

/**
 * Basic implementation of the WebPage interface.<p>
 * It contains common features for every WebPages.
 * 
 * @author Julien Cornuwel (batosai@freenetproject.org)
 */
public abstract class WebPageImpl implements WebPage {
	

	protected final WoT wot;
	
	protected final String uri;

	protected final PluginRespirator pr;
	
	/** The node's pagemaker */
	protected final PageMaker pm;

	/** HTMLNode representing the web page */
	protected final HTMLNode pageNode;
	protected final HTMLNode contentNode;

	/** The request performed by the user */
	protected final HTTPRequest request;

	/** List of all content boxes */
	protected final ArrayList<HTMLNode> contentBoxes;
	
	/**
	 * Creates a new WebPageImpl.
	 * It is abstract because only a subclass can run the desired make() method to generate the content.
	 * 
	 * @param myWebInterface A reference to the WebInterface which created the page, used to get resources the page needs. 
	 * @param myRequest The request sent by the user.
	 */
	public WebPageImpl(WebInterfaceToadlet toadlet, HTTPRequest myRequest, ToadletContext ctx) {
		WebInterface myWebInterface = toadlet.webInterface;
		wot = myWebInterface.getWoT();
		uri = toadlet.getURI();
		
		pr = wot.getPluginRespirator();
		this.pm = myWebInterface.getPageMaker();
		PageNode page = pm.getPageNode("Web of Trust", ctx);
		this.pageNode = page.outer;
		this.contentNode = page.content;
		this.request = myRequest;
		
		this.contentBoxes = new ArrayList<HTMLNode>();
	}
	
	/**
	 * Generates the HTML code that will be sent to the browser.
	 * 
	 * @return HTML code of the page.
	 */
	public String toHTML() {
		
		// We add every ContentBoxes
		Iterator<HTMLNode> contentBox = contentBoxes.iterator();
		while(contentBox.hasNext()) contentNode.addChild(contentBox.next());

		/* FIXME: This code does seem to get executed but the test box is invisible. Why? */
		pm.getInfobox("infobox-alert", "Test", contentNode).addChild("#", "Test");
		
		// Generate the HTML output
		return pageNode.generate();
	}

	/**
	 * Adds an ErrorBox to the WebPage.
	 * 
	 * @param title The title of the desired ErrorBox

	 */
	public HTMLNode addErrorBox(String title) {
		InfoboxNode infobox = pm.getInfobox("infobox-alert", title);
		contentBoxes.add(infobox.outer);
		return infobox.content;
	}
	
	/**
	 * Adds an ErrorBox to the WebPage.
	 * 
	 * @param title The title of the desired ErrorBox
	 * @param message The error message that will be displayed
	 */
	public HTMLNode addErrorBox(String title, Exception error) {
		InfoboxNode infobox = pm.getInfobox("infobox-alert", title);
		HTMLNode errorBox = infobox.outer;
		HTMLNode errorInner = infobox.content;
		// FIXME use errorInner not errorBox to add stack trace
		
		String message = error.getLocalizedMessage();
		if(message == null || message.equals(""))
			message = error.getMessage();
		
		HTMLNode p = errorBox.addChild("p", message);
		
		p = errorBox.addChild("p", "Stack trace:");
		for(StackTraceElement element : error.getStackTrace()) {
			p.addChild("br"); p.addChild("#", element.toString());
		}
		
		contentBoxes.add(errorBox);
		return errorInner;
	}
	
	public HTMLNode addErrorBox(String title, String message) {
		InfoboxNode infobox = pm.getInfobox("infobox-alert", title);
		HTMLNode errorBox = infobox.outer;
		errorBox.addChild("p", message);
		contentBoxes.add(errorBox);
		return infobox.content;
	}
	
	/**
	 * Adds a new InfoBox to the WebPage.
	 * 
	 * @param title The title of the desired InfoBox
	 * @return the contentNode of the newly created InfoBox
	 */
	protected HTMLNode addContentBox(String title) {

		InfoboxNode infobox = pm.getInfobox(title);
		contentBoxes.add(infobox.outer);
		return infobox.content;
	}
}
