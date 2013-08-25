package plugins.WebOfTrust.ui.web;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.NoSuchElementException;

import javax.naming.SizeLimitExceededException;

import plugins.WebOfTrust.WebOfTrust;
import plugins.WebOfTrust.exceptions.UnknownIdentityException;
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
	    
	    if(!isEnabled(ctx))
			throw new RedirectException(webInterface.getToadlet(LoginWebInterfaceToadlet.class).getURI());
		
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
		
		String pass = request.getPartAsString("formPassword", 32);
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
