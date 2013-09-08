package plugins.WebOfTrust.ui.web;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.NoSuchElementException;

import javax.naming.SizeLimitExceededException;

import plugins.WebOfTrust.OwnIdentity;
import plugins.WebOfTrust.WebOfTrust;
import plugins.WebOfTrust.exceptions.UnknownIdentityException;
import plugins.WebOfTrust.ui.web.WebInterface.LogOutWebInterfaceToadlet;
import plugins.WebOfTrust.ui.web.WebInterface.LoginWebInterfaceToadlet;
import freenet.client.HighLevelSimpleClient;
import freenet.clients.http.LinkEnabledCallback;
import freenet.clients.http.RedirectException;
import freenet.clients.http.SessionManager;
import freenet.clients.http.SessionManager.Session;
import freenet.clients.http.Toadlet;
import freenet.clients.http.ToadletContext;
import freenet.clients.http.ToadletContextClosedException;
import freenet.node.NodeClientCore;
import freenet.support.Logger;
import freenet.support.api.HTTPRequest;

public abstract class WebInterfaceToadlet extends Toadlet implements LinkEnabledCallback {
	
	final String pageTitle;
	final SessionManager sessionManager;
	final WebInterface webInterface;
	final NodeClientCore core;

	protected WebInterfaceToadlet(HighLevelSimpleClient client, WebInterface wi, NodeClientCore core, String pageTitle) {
		super(client);
		this.pageTitle = pageTitle;
		this.sessionManager = wi.getWoT().getPluginRespirator().getSessionManager(WebOfTrust.WOT_NAME);
		this.webInterface = wi;
		this.core = core;
	}

	abstract WebPage makeWebPage(HTTPRequest req, ToadletContext context) throws UnknownIdentityException, RedirectException;
	
	/**
	 * Decides whether this Toadlet may be accessed. The GET/POST handler will only render the page if this returns true.
	 * 
	 * Checks whether a {@link SessionManager.Session} exists WITHOUT refreshing its timeout:
	 * Besides denying access if a user tries to visit a Toadlet without a session, this is typically used for deciding which 
	 * entries to display on the WOT menu on the web interface.
	 * If a user is logged in, the menu should show everything which being logged in allows the user to use.
	 * If no user is logged in, only the controls for allowing log in or creation of an account should be visible. 
	 * Those checks should not mark the session as active since the user didn't actually do anything on the WOT web interface,
	 * he was only active somewhere else on the Freenet web interface. Therefore, we don't refresh the session timeout.
	 */
	@Override
	public boolean isEnabled(ToadletContext ctx) {
		return sessionManager.sessionExists(ctx);
	}
	
	/**
	 * Check whether a {@link SessionManager.Session} exists AND refreshes its timeout. Returns the ID of the logged in user.
	 * 
	 * This is the front-end function for using session management in WebPage implementations.
	 * 
	 * ATTENTION: Throws a RedirectException to the {@link LoginWebInterfaceToadlet} if the session is expired. Therefore you must
	 * not use this function in the code which generates the {@link LoginWebInterfaceToadlet} to prevent an infinite loop. 
	 * 
	 * @throws RedirectException If the session is not valid anymore. The redirect will be to the {@link LoginWebInterfaceToadlet}.
	 * @return The ID of the logged in user. Typically the ID of the {@link OwnIdentity} which is logged in.
	 */
	public final String getLoggedInUserID(ToadletContext context) throws RedirectException {
		final Session session = sessionManager.useSession(context);

		if(session == null) {
			// To prevent an infinite redirect loop from LogInWebInterfaceToadlet to itself, we check whether we ARE that toadlet.
			// Its unlikely that this happens because this function is not be used in the code which handles requests to the 
			// LoginWebInterfaceToadlet and the JavaDoc forbids using it for that as well.
			// To guard against someone accidentally using it in the LogInWebInterfaceToadlet, we check nevertheless.
			if(this instanceof LoginWebInterfaceToadlet)
				throw new UnsupportedOperationException("You must not use this function in LogInWebInterfaceToadlet!");
			
			throw new RedirectException(webInterface.getToadlet(LoginWebInterfaceToadlet.class).getURI());
		}

		return session.getUserID();
	}
	
	@Override
	public String path() {
		return webInterface.getURI() + "/" + pageTitle;
	}
	
	/**
	 * Returns true if {@link #isEnabled(ToadletContext)} returns true. You must only proceed to handle GET/POST if this returns true.
	 * 
	 * This function will try to redirect the user to the {@link LoginWebInterfaceToadlet} if that Toadlet is enabled and if its not equal to this Toadlet.
	 * If redirecting to log in is not possible, it will attempt to redirect to {@link LogOutWebInterfaceToadlet}} with the same checks.
	 * If thats also not possible, false is returned.
	 * 
	 * @see #handleMethodGET(URI, HTTPRequest, ToadletContext) Typical user of this function
	 * @see #handleMethodPOST(URI, HTTPRequest, ToadletContext) Typical user of this function
	 */
	private final boolean checkIsEnabled(final ToadletContext ctx) throws RedirectException {
		if(isEnabled(ctx))
			return true;
		
    	// If the current Toadlet is not enabled the likely reason is that nobody is logged in.
    	// So we want to redirect the user to the LoginWebInterfaceToadlet
    	// However, we must not redirect to log in if we ARE the log in toadlet to prevent a 100% CPU redirect loop.
    	final WebInterfaceToadlet logIn = webInterface.getToadlet(LoginWebInterfaceToadlet.class);
    	if(this != logIn && logIn.isEnabled(ctx))
    		throw new RedirectException(logIn.getURI());
    	
    	// We now know that we are the log in toadlet or the log in toadlet is disabled.
    	// This is most likely the case because a user is already logged in.
    	// We check whether log out is possible and if yes, log out the user. Again, we must prevent a redirect loop.
    	final WebInterfaceToadlet logOut = webInterface.getToadlet(LogOutWebInterfaceToadlet.class);
    	if(this != logOut && logOut.isEnabled(ctx))
    		throw new RedirectException(logOut.getURI());
    	
    	// The purpose of isEnabled() mainly is to determine whether someone is logged in or not.
    	// If we reach this point of execution, its likely that something is wrong with the implementation of the toadlets.
    	assert(false); // Don't use Logger since an unauthorized request shouldn't cause disk space usage.
    	
    	return false;
	}

	public void handleMethodGET(URI uri, HTTPRequest req, ToadletContext ctx) 
			throws ToadletContextClosedException, IOException, RedirectException {
		
	    if(!ctx.checkFullAccess(this))
	        return;
	    
	    if(!checkIsEnabled(ctx))
	    	return;
		
	    handleRequest(req, ctx);
	}

	public void handleMethodPOST(URI uri, HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException, SizeLimitExceededException, NoSuchElementException {
	    if(!ctx.checkFullAccess(this))
	        return;
	    
	    if(!checkIsEnabled(ctx))
	    	return;
		
		String pass = request.getPartAsStringFailsafe("formPassword", 32);
		if ((pass.length() == 0) || !pass.equals(core.formPassword)) {
			writeHTMLReply(ctx, 403, "Forbidden", "Invalid form password.");
			return;
		}

		handleRequest(request, ctx);
	}
	
	/**
	 * Handler for POST/GET. Does not do any access control. You have to check that the user is authorized before calling this! 
	 */
	private void handleRequest(final HTTPRequest request, final ToadletContext ctx) throws RedirectException, ToadletContextClosedException, IOException {
		String ret = "";
		WebPage page = null;
		try {
			page = makeWebPage(request, ctx);
		} catch (UnknownIdentityException e) {
			Logger.warning(this, "Session is invalid, the own identity was deleted already.", e);
			sessionManager.deleteSession(ctx);
			
			try {
				page = new ErrorPage(this, request, ctx, e, webInterface.l10n());
			} catch(Exception doubleFault) {
				ret = doubleFault.toString();
			}
		}
		
		if(page != null) {
			page.make();
			ret = page.toHTML();
		}
		
		writeHTMLReply(ctx, 200, "OK", ret);
	}

	public URI getURI() {
		try {
			return new URI(webInterface.getURI() + "/" + pageTitle);
		} catch (URISyntaxException e) {
			throw new RuntimeException(e);
		}
	}
}
