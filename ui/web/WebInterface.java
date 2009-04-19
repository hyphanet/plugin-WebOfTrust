/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WoT.ui.web;

import plugins.WoT.WoT;
import plugins.WoT.introduction.IntroductionPuzzle;
import freenet.clients.http.PageMaker;
import freenet.l10n.L10n;
import freenet.pluginmanager.FredPluginHTTP;
import freenet.pluginmanager.PluginHTTPException;
import freenet.pluginmanager.PluginRespirator;
import freenet.support.api.HTTPRequest;

/**
 * The web interface of the WoT plugin.
 * 
 * @author xor (xor@freenetproject.org)
 * @author Bombe
 * @author Julien Cornuwel (batosai@freenetproject.org)
 */
public class WebInterface implements FredPluginHTTP {
	
	private final WoT mWoT;
	private final PluginRespirator mPluginRespirator;
	private final PageMaker mPageMaker;
	
	private final String mURI;

	public WebInterface(WoT myWoT, String uri) {
		mWoT = myWoT;
		mURI = uri;
		
		mPluginRespirator = mWoT.getPluginRespirator();
		mPageMaker = mPluginRespirator.getPageMaker();
		mPageMaker.addNavigationLink(mURI, "Home", "Home page", false, null);
		mPageMaker.addNavigationLink(mURI + "?OwnIdentities", "Own Identities", "Manage your own identities", false, null);
		mPageMaker.addNavigationLink(mURI + "?KnownIdentities", "Known Identities", "Manage others identities", false, null);
		mPageMaker.addNavigationLink(mURI + "?Configuration", "Configuration", "Configure the WoT plugin", false, null);
		mPageMaker.addNavigationLink("/plugins/", "Plugins page", "Back to Plugins page", false, null);
	}
	
	public String getURI() {
		return mURI;
	}

	public String handleHTTPGet(HTTPRequest request) throws PluginHTTPException {	
		try {
			WebPage page = null;
			
			if(request.isParameterSet("OwnIdentities")) page = new OwnIdentitiesPage(this, request);
			else if (request.isParameterSet("KnownIdentities")) page = new KnownIdentitiesPage(this, request);
			else if (request.isParameterSet("Configuration")) page = new ConfigurationPage(this, request);
			else if (request.isParameterSet("ShowIdentity")) page = new IdentityPage(this, request);
			else if (request.isParameterSet("puzzle")) {
				IntroductionPuzzle p = mWoT.getIntroductionPuzzleStore().getByID(request.getParam("id"));
				if (p != null) {
					byte[] data = p.getData();
				}
				/*
				 * FIXME: The current PluginManager implementation allows
				 * plugins only to send HTML replies. Implement general replying
				 * with any mime type and return the jpeg.
				 */
				return "";
			}
			else
				page = new HomePage(this, request);
	
			page.make();
			return page.toHTML();
		}
		catch (Exception e) {
			/* FIXME: Return a HTML page, not just e.getLocalizedMessage! */
			return e.getMessage();
		}
	}

	public String handleHTTPPost(HTTPRequest request) throws PluginHTTPException {
		WebPage page;
		
		String pass = request.getPartAsString("formPassword", 32);
		if ((pass.length() == 0) || !pass.equals(mPluginRespirator.getNode().clientCore.formPassword)) {
			return "Invalid form password.";
		}

		try {
			String pageTitle = request.getPartAsString("page",50);
			
			if(pageTitle.equals("CreateIdentity")) page = new CreateIdentityPage(this, request);
			else if(pageTitle.equals("AddIdentity")) page = new KnownIdentitiesPage(this, request);
			else if(pageTitle.equals("ViewTree")) page = new KnownIdentitiesPage(this, request);
			else if(pageTitle.equals("SetTrust"))  page = new KnownIdentitiesPage(this, request);
			else if(pageTitle.equals("EditIdentity")) page = new EditOwnIdentityPage(this, request);
			else if(pageTitle.equals("IntroduceIdentity") || pageTitle.equals("SolvePuzzles")) page = new IntroduceIdentityPage(this, request);
			else if(pageTitle.equals("RestoreIdentity")) page = new OwnIdentitiesPage(this, request);
			else if(pageTitle.equals("DeleteIdentity")) page = new DeleteOwnIdentityPage(this, request);		
			else page = new HomePage(this, request);
			
			page.make();
			return page.toHTML();
		} catch (Exception e) {
			/* FIXME: Return a HTML page, not just e.getLocalizedMessage! */
			return e.getMessage();
		}
	}
	
	public String handleHTTPPut(HTTPRequest request) throws PluginHTTPException {
		return "Go to hell";
	}
	
	public PageMaker getPageMaker() {
		return mPageMaker;
	}
	
	public WoT getWoT() {
		return mWoT;
	}
	

	private static final String l10n(String string) {
		return L10n.getString("ConfigToadlet." + string);
	}

}


