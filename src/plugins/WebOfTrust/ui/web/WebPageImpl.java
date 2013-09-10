/**
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */
package plugins.WebOfTrust.ui.web;

import java.net.URI;

import plugins.WebOfTrust.WebOfTrust;
import plugins.WebOfTrust.ui.web.WebInterface.LoginWebInterfaceToadlet;
import freenet.clients.http.InfoboxNode;
import freenet.clients.http.PageMaker;
import freenet.clients.http.PageNode;
import freenet.clients.http.RedirectException;
import freenet.clients.http.SessionManager.Session;
import freenet.clients.http.ToadletContext;
import freenet.l10n.BaseL10n;
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
	

	protected final WebInterface mWebInterface;
	
	protected final WebOfTrust wot;
	
	protected final WebInterfaceToadlet mToadlet;
	
	protected final ToadletContext mContext;
	
	protected final String mLoggedInOwnIdentityID;
	
	protected final URI uri;

	protected final PluginRespirator pr;
	
	/** The node's pagemaker */
	protected final PageMaker pm;

	/** HTMLNode representing the web page */
	protected HTMLNode pageNode;
	protected HTMLNode contentNode;

	/** The request performed by the user */
	protected final HTTPRequest request;

	protected final BaseL10n baseL10n;
	
	/**
	 * Creates a new WebPageImpl.
	 * It is abstract because only a subclass can run the desired make() method to generate the content.
	 * @param myRequest The request sent by the user.
	 * @param _baseL10n TODO
	 * @param toadlet A reference to the {@link WebInterfaceToadlet} which created the page, used to get resources the page needs. 
	 * @param useSession If true, the timeout of the current {@link Session} is refreshed and {@link #mLoggedInOwnIdentityID} is initialized to the ID of the logged in identity.
	 * @throws RedirectException If useSession was true and the {@link Session} was expired already. Then the user is redirected to the {@link LoginWebInterfaceToadlet}.
	 */
	public WebPageImpl(WebInterfaceToadlet toadlet, HTTPRequest myRequest, ToadletContext ctx, BaseL10n _baseL10n, boolean useSession) throws RedirectException {
		mToadlet = toadlet;
		mWebInterface = mToadlet.webInterface;
		mContext = ctx;
		wot = mWebInterface.getWoT();
		uri = mToadlet.getURI();
		baseL10n = _baseL10n;
		
		pr = wot.getPluginRespirator();
		this.pm = mWebInterface.getPageMaker();
		PageNode page = pm.getPageNode("Web of Trust", ctx);
		this.pageNode = page.outer;
		this.contentNode = page.content;
		this.request = myRequest;
		
		mLoggedInOwnIdentityID = useSession ? mToadlet.getLoggedInUserID(ctx) : null;
	}
	
	/**
	 * Generates the HTML code that will be sent to the browser.
	 * 
	 * @return HTML code of the page.
	 */
	public String toHTML() {
		// Generate the HTML output
		return pageNode.generate();
	}

	/**
	 * Adds an ErrorBox to the WebPage.
	 * 
	 * @param title The title of the desired ErrorBox
	 * @return The content node of the ErrorBox
	 */
	public HTMLNode addErrorBox(String title) {
		return pm.getInfobox("infobox-alert", title, contentNode);
	}
	
	/**
	 * Adds an ErrorBox to the WebPage.
	 * 
	 * @param title The title of the desired ErrorBox
	 * @param error The error message that will be displayed
	 * @return The content node of the ErrorBox
	 */
	public HTMLNode addErrorBox(String title, Exception error) {
		HTMLNode errorInner = addErrorBox(title);
		
		String message = error.getLocalizedMessage();
		if(message == null || message.equals(""))
			message = error.getMessage();
		
		HTMLNode p = errorInner.addChild("p", message);
		
		p = errorInner.addChild("p", "Stack trace:");
		for(StackTraceElement element : error.getStackTrace()) {
			p.addChild("br"); p.addChild("#", element.toString());
		}
		
		return errorInner;
	}
	
	public HTMLNode addErrorBox(String title, String message) {
		HTMLNode infobox = addErrorBox(title);
		infobox.addChild("div", message);
		return infobox;
	}
	
	/**
	 * Adds a new InfoBox to the WebPage.
	 * 
	 * @param title The title of the desired InfoBox
	 * @return the contentNode of the newly created InfoBox
	 */
	protected HTMLNode addContentBox(String title) {
		InfoboxNode infobox = pm.getInfobox(title);
		contentNode.addChild(infobox.outer);
		return infobox.content;
	}

	/**
	 * {@inheritDoc}
	 */
	public final void addToPage(WebPageImpl other) {
		pageNode = other.pageNode;
		contentNode = other.contentNode;
		make();
	}
	
	/**
	 * Get a new {@link #InfoboxNode} but do not add it to the page. Can be used for putting Infoboxes inside Infoboxes.
	 * You must add your content to the {@link InfoboxNode#content}.
	 * You then add the box to your page by adding {@link InfoboxNode#outer} to a HTMLNode.
	 * 
	 * @param title The title of the desired Infobox
	 */
	protected final InfoboxNode getContentBox(String title) {
		InfoboxNode infobox = pm.getInfobox(title);
		return infobox;
	}
	
	
	protected BaseL10n l10n() {
	    return baseL10n;
	}
	
}
