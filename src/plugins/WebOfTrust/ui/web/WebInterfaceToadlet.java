package plugins.WebOfTrust.ui.web;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.NoSuchElementException;

import javax.naming.SizeLimitExceededException;

import plugins.WebOfTrust.WebOfTrust;
import plugins.WebOfTrust.exceptions.UnknownIdentityException;
import plugins.WebOfTrust.ui.web.WebInterface.LogOutWebInterfaceToadlet;
import plugins.WebOfTrust.ui.web.WebInterface.LoginWebInterfaceToadlet;
import freenet.client.HighLevelSimpleClient;
import freenet.clients.http.LinkEnabledCallback;
import freenet.clients.http.RedirectException;
import freenet.clients.http.SessionManager;
import freenet.clients.http.Toadlet;
import freenet.clients.http.ToadletContext;
import freenet.clients.http.ToadletContextClosedException;
import freenet.node.NodeClientCore;
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
	
	@Override
	public boolean isEnabled(ToadletContext ctx) {
		return sessionManager.sessionExists(ctx);
	}
	
	@Override
	public String path() {
		return webInterface.getURI() + "/" + pageTitle;
	}

	public void handleMethodGET(URI uri, HTTPRequest req, ToadletContext ctx) 
			throws ToadletContextClosedException, IOException, RedirectException {
		
	    if(!ctx.checkFullAccess(this))
	        return;
	    
	    if(!isEnabled(ctx)) {
	    	// If the current Toadlet is not enabled the likely reason is that nobody is logged in.
	    	// So we want to redirect the user to the LoginWebInterfaceToadlet
	    	// However, we must not redirect to log in if we ARE the log in toaddlet to prevent a 100% CPU redirect loop.
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
	    	
	    	return;
	    }
		
		String ret;
		try {
			WebPage page = makeWebPage(req, ctx);
			page.make();
			ret = page.toHTML();
		} catch (UnknownIdentityException e) {
			try {
				WebPage page = new ErrorPage(this, req, ctx, e, webInterface.l10n());
				page.make();
				ret = page.toHTML();
			}
			catch(Exception doubleFault) {
				ret = doubleFault.toString();
			}

		}
		writeHTMLReply(ctx, 200, "OK", ret);
	}

	public void handleMethodPOST(URI uri, HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException, SizeLimitExceededException, NoSuchElementException {
	    if(!ctx.checkFullAccess(this))
	        return;
	    
	    if(!isEnabled(ctx))
	    	throw new RedirectException(webInterface.getToadlet(LoginWebInterfaceToadlet.class).getURI());
		
		String pass = request.getPartAsStringFailsafe("formPassword", 32);
		if ((pass.length() == 0) || !pass.equals(core.formPassword)) {
			writeHTMLReply(ctx, 403, "Forbidden", "Invalid form password.");
			return;
		}

		String ret;
		try {
			
			WebPage page = makeWebPage(request, ctx);
			page.make();
			ret = page.toHTML();
		} catch (UnknownIdentityException e) {
			try {
				WebPage page = new ErrorPage(this, request, ctx, e, webInterface.l10n());
				page.make();
				ret = page.toHTML();
			}
			catch(Exception doubleFault) {
				ret = doubleFault.toString();
			}

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
