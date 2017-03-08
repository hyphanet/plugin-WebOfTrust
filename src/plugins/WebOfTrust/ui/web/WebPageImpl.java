/**
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */
package plugins.WebOfTrust.ui.web;

import java.net.URI;

import plugins.WebOfTrust.OwnIdentity;
import plugins.WebOfTrust.WebOfTrust;
import plugins.WebOfTrust.exceptions.UnknownIdentityException;
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
 * @author xor (xor@freenetproject.org)
 * @author Julien Cornuwel (batosai@freenetproject.org)
 */
public abstract class WebPageImpl implements WebPage {
	

	protected final WebInterface mWebInterface;
	
	protected final WebOfTrust mWebOfTrust;
	
	protected final WebInterfaceToadlet mToadlet;
	
	protected final ToadletContext mContext;
	
	protected final OwnIdentity mLoggedInOwnIdentity;
	
	protected final URI uri;

	protected final PluginRespirator pr;
	
	/** The node's pagemaker */
	protected final PageMaker pm;

	/** HTMLNode representing the web page */
	protected HTMLNode pageNode;
	protected HTMLNode contentNode;

	/** The request performed by the user */
	protected final HTTPRequest mRequest;

	protected final BaseL10n baseL10n;
	
	/**
	 * Creates a new WebPageImpl.
	 * It is abstract because only a subclass can run the desired make() method to generate the content.
	 * @param toadlet A reference to the {@link WebInterfaceToadlet} which created the page, used to get resources the page needs.
	 * @param myRequest The request sent by the user.
	 * @param ctx Similar to myRequest, this is also request-specific data. Don't ask me why we have two types to store it.
	 * @param useSession If true, the timeout of the current {@link Session} is refreshed and
	 *                   {@link #mLoggedInOwnIdentity} is initialized to a clone() of the logged in
	 *                   {@link OwnIdentity} (it will be null otherwise).<br>
	 *                   Instead of setting this to false, use the constructor {@link #WebPageImpl(WebInterfaceToadlet, HTTPRequest, ToadletContext)}. It has
	 *                   the advantage of not possibly throwing a {@link RedirectException}.
	 * @throws RedirectException If useSession was true and the {@link Session} was expired already. Then the user is redirected to the {@link LoginWebInterfaceToadlet}.
	 * @throws UnknownIdentityException
	 *     If useSession was true and the {@link OwnIdentity} specified by the
	 *     {@link Session#getUserID()} has been deleted already.
	 */
	public WebPageImpl(WebInterfaceToadlet toadlet, HTTPRequest myRequest, ToadletContext ctx,
	        boolean useSession) throws RedirectException, UnknownIdentityException {
	    
		this(toadlet, myRequest, ctx,
		    useSession ? getLoggedInOwnIdentityFromHTTPSession(toadlet, ctx) : null);
	}
	
	private static OwnIdentity getLoggedInOwnIdentityFromHTTPSession(WebInterfaceToadlet toadlet,
	        ToadletContext ctx) throws RedirectException, UnknownIdentityException {
	    
	    String id = toadlet.getLoggedInUserID(ctx);
	    WebOfTrust wot = toadlet.webInterface.getWoT();
	    
        // TODO: Performance: The synchronized() and clone() can be removed after this is fixed:
        // https://bugs.freenetproject.org/view.php?id=6247
        // Once the clone() is removed, please also adapt EditOwnIdentityPage.make(),
        // KnownIdentitiesPage.makeKnownIdentitiesList() and MyIdentityPage() to not re-query the
        // identity from the database anymore. See the TODOs there for details.
        synchronized(wot) {
            return wot.getOwnIdentityByID(id).clone();
        }
	}
	
	/**
	 * Same as {@link #WebPageImpl(WebInterfaceToadlet, HTTPRequest, ToadletContext, boolean)} with useSession == false.
	 */
	public WebPageImpl(WebInterfaceToadlet toadlet, HTTPRequest myRequest, ToadletContext ctx) {
		this(toadlet, myRequest, ctx, null);
	}

	/**
	 * @see #WebPageImpl(WebInterfaceToadlet, HTTPRequest, ToadletContext, boolean) Frontend to this.
	 * @see #WebPageImpl(WebInterfaceToadlet, HTTPRequest, ToadletContext) Frontend to this.
	 */
	private WebPageImpl(WebInterfaceToadlet toadlet, HTTPRequest myRequest, ToadletContext ctx,
	        OwnIdentity loggedInOwnIdentity) {
	    
		mToadlet = toadlet;
		mRequest = myRequest;
		mContext = ctx;
        mLoggedInOwnIdentity = loggedInOwnIdentity;

		mWebInterface = mToadlet.webInterface;
		mWebOfTrust = mWebInterface.getWoT();
		uri = mToadlet.getURI();
		baseL10n = mWebInterface.l10n();
		pr = mWebOfTrust.getPluginRespirator();
		pm = mWebInterface.getPageMaker();


        String pageTitle = mLoggedInOwnIdentity != null ?
              baseL10n.getString("WebInterface.PageTitle.LoggedIn",
                                 "nickname", mLoggedInOwnIdentity.getShortestUniqueNickname())
            : baseL10n.getString("WebInterface.PageTitle.NotLoggedIn");
        
        PageNode page = pm.getPageNode(pageTitle, ctx);
		this.pageNode = page.outer;
		this.contentNode = page.content;
	}
	
	/**
	 * Generates the HTML code that will be sent to the browser.
	 * 
	 * @return HTML code of the page.
	 */
	@Override
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
	 * @deprecated Use {@link ErrorPage} instead. TODO: Move this function to ErrorPage and make it private there. The remaining users of this function
	 *             should then do new ErrorPage(...).addToPage(this). However to do this we first need some changes to the WOT core to make the functions
	 *             there throw more specific exceptions so the ErrorPage can display them as non-internal errors and as such without a stack trace.
	 *             For example WebOfTrust.setTrust() currently will throw InvalidParameterException for invalid trust values even though it is declared to
	 *             throw NumberFormatException.
	 */
	@Deprecated
	public HTMLNode addErrorBox(String title, Exception error) {
		HTMLNode errorInner = addErrorBox(title);
		
		String message = error.getLocalizedMessage();
		if(message == null || message.equals(""))
			message = error.getMessage();
		
		HTMLNode p = errorInner.addChild("p", message);
		
		p = errorInner.addChild("p", "Exception " + error.getClass() + ", stack trace:");
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
	@Override
	public final void addToPage(WebPageImpl other) {
		pageNode = other.pageNode;
		contentNode = other.contentNode;
		make(false);
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

	/**
	 * Get a new {@link #InfoboxNode} with a style which indicates an error, but do not add it to the page.
	 * Can be used for putting infoboxes inside infoboxes.
	 * 
	 * You must add your content to the {@link InfoboxNode#content}.
	 * You then add the box to your page by adding {@link InfoboxNode#outer} to a HTMLNode.
	 * 
	 * @param title The title of the desired Infobox
	 */
	protected final InfoboxNode getAlertBox(String title) {
		return pm.getInfobox("infobox-alert", title);
	}
	
	
	protected BaseL10n l10n() {
	    return baseL10n;
	}
	
}
