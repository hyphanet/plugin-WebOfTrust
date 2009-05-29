package plugins.WoT.ui.web;

import java.io.IOException;
import java.net.URI;

import plugins.WoT.exceptions.UnknownIdentityException;

import freenet.client.HighLevelSimpleClient;
import freenet.clients.http.RedirectException;
import freenet.clients.http.Toadlet;
import freenet.clients.http.ToadletContext;
import freenet.clients.http.ToadletContextClosedException;
import freenet.node.NodeClientCore;
import freenet.support.api.HTTPRequest;

public abstract class WebInterfaceToadlet extends Toadlet {
	
	final String pageTitle;
	final WebInterface webInterface;
	final NodeClientCore core;

	protected WebInterfaceToadlet(HighLevelSimpleClient client, WebInterface wi, NodeClientCore core, String pageTitle) {
		super(client);
		this.pageTitle = pageTitle;
		this.webInterface = wi;
		this.core = core;
	}

	abstract WebPage makeWebPage(HTTPRequest req, ToadletContext context) throws UnknownIdentityException;
	
	@Override
	public String path() {
		return webInterface.getURI() + "/" + pageTitle;
	}

	@Override
	public String supportedMethods() {
		return "GET, POST";
	}
	
	@Override
	public void handleGet(URI uri, HTTPRequest req, ToadletContext ctx) 
			throws ToadletContextClosedException, IOException, RedirectException {
		String ret;
		try {
			WebPage page = makeWebPage(req, ctx);
			page.make();
			ret = page.toHTML();
		} catch (UnknownIdentityException e) {
			try {
				WebPage page = new ErrorPage(this, req, ctx, e);
				page.make();
				ret = page.toHTML();
			}
			catch(Exception doubleFault) {
				ret = doubleFault.toString();
			}

		}
		writeHTMLReply(ctx, 200, "OK", ret);
	}
	
	@Override
	public void handlePost(URI uri, HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
		
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
				WebPage page = new ErrorPage(this, request, ctx, e);
				page.make();
				ret = page.toHTML();
			}
			catch(Exception doubleFault) {
				ret = doubleFault.toString();
			}

		}
		writeHTMLReply(ctx, 200, "OK", ret);
	}

	public String getURI() {
		return webInterface.getURI() + "/" + pageTitle;
	}

}
