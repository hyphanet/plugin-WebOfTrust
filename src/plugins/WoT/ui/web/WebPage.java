/**
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */
package plugins.WoT.ui.web;

import plugins.WoT.Config;

import com.db4o.ObjectContainer;

import freenet.client.HighLevelSimpleClient;
import freenet.clients.http.PageMaker;
import freenet.pluginmanager.PluginRespirator;
import freenet.support.HTMLNode;
import freenet.support.api.HTTPRequest;

/**
 * @author Julien Cornuwel (batosai@freenetproject.org)
 */
public class WebPage {
	
	private PluginRespirator pr;
	private PageMaker pm;
	private HighLevelSimpleClient client;
	private HTTPRequest request;
	private ObjectContainer db;
	private	Config config;
	public static String SELF_URI = "/plugins/plugins.WoT.WoT";
	
	private HTMLNode pageNode;
	
	/**
	 * Generates a WebPage of the plugin's interface.
	 * 
	 * @param pr The {@link PluginRespirator} supplied by Freenet
	 * @param db An {@link ObjectContainer} where plugin's datas are stored
	 * @param cfg A {@link Config} object containing the plugin's configuration
	 * @param client A {@link HighLevelSimpleClient} to perform requests to the node, if needed
	 * @param uri
	 */
	public WebPage(PluginRespirator pr, ObjectContainer db, Config cfg, HighLevelSimpleClient client, HTTPRequest request) {
		
		this.pr = pr;
		this.db = db;
		this.config = cfg;
		this.client = client;
		this.request = request;
		
		pm = pr.getPageMaker();
		pageNode = pm.getPageNode("Web of Trust", null);
		makeMenu();
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
	 * Created the menu of the WebPage
	 */
	public void makeMenu() {
		
		pm.addNavigationLink(SELF_URI, "Home", "Home page", false, null);
		pm.addNavigationLink(SELF_URI + "?ownidentities", "Own Identities", "Manage your own identities", false, null);
		pm.addNavigationLink(SELF_URI + "?knownidentities", "Known Identities", "Manage others identities", false, null);
		pm.addNavigationLink(SELF_URI + "?configuration", "Configuration", "Configure the WoT plugin", false, null);
		pm.addNavigationLink("/plugins/", "Plugins page", "Back to Plugins page", false, null);
	}

}
