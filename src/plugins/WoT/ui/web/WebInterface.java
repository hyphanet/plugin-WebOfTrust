/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WoT.ui.web;

import java.io.IOException;
import java.net.URI;

import plugins.WoT.WoT;
import plugins.WoT.exceptions.UnknownIdentityException;
import freenet.client.HighLevelSimpleClient;
import freenet.clients.http.PageMaker;
import freenet.clients.http.RedirectException;
import freenet.clients.http.Toadlet;
import freenet.clients.http.ToadletContainer;
import freenet.clients.http.ToadletContext;
import freenet.clients.http.ToadletContextClosedException;
import freenet.l10n.NodeL10n;
import freenet.node.NodeClientCore;
import freenet.pluginmanager.PluginRespirator;
import freenet.support.api.HTTPRequest;

/**
 * The web interface of the WoT plugin.
 * 
 * @author xor (xor@freenetproject.org)
 * @author Bombe
 * @author Julien Cornuwel (batosai@freenetproject.org)
 */
public class WebInterface {
	
	private final WoT mWoT;
	private final PluginRespirator mPluginRespirator;
	private final PageMaker mPageMaker;
	// Visible
	private final WebInterfaceToadlet homeToadlet;
	private final WebInterfaceToadlet ownIdentitiesToadlet;
	private final WebInterfaceToadlet knownIdentitiesToadlet;
	private final WebInterfaceToadlet configurationToadlet;
	
	private final String mURI;

	public class HomeWebInterfaceToadlet extends WebInterfaceToadlet {

		protected HomeWebInterfaceToadlet(HighLevelSimpleClient client, WebInterface wi, NodeClientCore core, String pageTitle) {
			super(client, wi, core, pageTitle);
		}

		@Override
		WebPage makeWebPage(HTTPRequest req, ToadletContext context) {
			return new HomePage(this, req, context);
		}

		@Override
		public void handleMethodGET(URI uri, HTTPRequest req, ToadletContext ctx)
				throws ToadletContextClosedException, IOException,
				RedirectException {
			super.handleMethodGET(uri, req, ctx);
		}

		@Override
		public void handleMethodPOST(URI uri, HTTPRequest request,
				ToadletContext ctx) throws ToadletContextClosedException,
				IOException, RedirectException {
			super.handleMethodPOST(uri, request, ctx);
		}
		
	}
	
	public class OwnIdentitiesWebInterfaceToadlet extends WebInterfaceToadlet {

		@Override
		public void handleMethodGET(URI uri, HTTPRequest req, ToadletContext ctx)
				throws ToadletContextClosedException, IOException,
				RedirectException {
			super.handleMethodGET(uri, req, ctx);
		}

		@Override
		public void handleMethodPOST(URI uri, HTTPRequest request,
				ToadletContext ctx) throws ToadletContextClosedException,
				IOException, RedirectException {
			super.handleMethodPOST(uri, request, ctx);
		}

		protected OwnIdentitiesWebInterfaceToadlet(HighLevelSimpleClient client, WebInterface wi, NodeClientCore core, String pageTitle) {
			super(client, wi, core, pageTitle);
		}

		@Override
		WebPage makeWebPage(HTTPRequest req, ToadletContext context) {
			return new OwnIdentitiesPage(this, req, context);
		}
		
	}
	
	public class KnownIdentitiesWebInterfaceToadlet extends WebInterfaceToadlet {

		@Override
		public void handleMethodGET(URI uri, HTTPRequest req, ToadletContext ctx)
				throws ToadletContextClosedException, IOException,
				RedirectException {
			super.handleMethodGET(uri, req, ctx);
		}

		@Override
		public void handleMethodPOST(URI uri, HTTPRequest request,
				ToadletContext ctx) throws ToadletContextClosedException,
				IOException, RedirectException {
			super.handleMethodPOST(uri, request, ctx);
		}

		protected KnownIdentitiesWebInterfaceToadlet(HighLevelSimpleClient client, WebInterface wi, NodeClientCore core, String pageTitle) {
			super(client, wi, core, pageTitle);
		}

		@Override
		WebPage makeWebPage(HTTPRequest req, ToadletContext context) {
			return new KnownIdentitiesPage(this, req, context);
		}
		
	}
	
	public class ConfigWebInterfaceToadlet extends WebInterfaceToadlet {

		@Override
		public void handleMethodGET(URI uri, HTTPRequest req, ToadletContext ctx)
				throws ToadletContextClosedException, IOException,
				RedirectException {
			super.handleMethodGET(uri, req, ctx);
		}

		@Override
		public void handleMethodPOST(URI uri, HTTPRequest request,
				ToadletContext ctx) throws ToadletContextClosedException,
				IOException, RedirectException {
			super.handleMethodPOST(uri, request, ctx);
		}

		protected ConfigWebInterfaceToadlet(HighLevelSimpleClient client, WebInterface wi, NodeClientCore core, String pageTitle) {
			super(client, wi, core, pageTitle);
		}

		@Override
		WebPage makeWebPage(HTTPRequest req, ToadletContext context) {
			return new ConfigurationPage(this, req, context);
		}
		
	}

	// Invisible
	private final WebInterfaceToadlet createIdentityToadlet;
	private final WebInterfaceToadlet deleteOwnIdentityToadlet;
	private final WebInterfaceToadlet editOwnIdentityToadlet;
	private final WebInterfaceToadlet introduceIdentityToadlet;
	private final WebInterfaceToadlet identityToadlet;

	public class CreateIdentityWebInterfaceToadlet extends WebInterfaceToadlet {

		@Override
		public void handleMethodGET(URI uri, HTTPRequest req, ToadletContext ctx)
				throws ToadletContextClosedException, IOException,
				RedirectException {
			super.handleMethodGET(uri, req, ctx);
		}

		@Override
		public void handleMethodPOST(URI uri, HTTPRequest request,
				ToadletContext ctx) throws ToadletContextClosedException,
				IOException, RedirectException {
			super.handleMethodPOST(uri, request, ctx);
		}

		protected CreateIdentityWebInterfaceToadlet(HighLevelSimpleClient client, WebInterface wi, NodeClientCore core, String pageTitle) {
			super(client, wi, core, pageTitle);
		}

		@Override
		WebPage makeWebPage(HTTPRequest req, ToadletContext context) {
			return new CreateIdentityPage(this, req, context);
		}
		
		@Override
		public Toadlet showAsToadlet() {
			return ownIdentitiesToadlet;
		}
		
	}

	public class DeleteOwnIdentityWebInterfaceToadlet extends WebInterfaceToadlet {

		@Override
		public void handleMethodGET(URI uri, HTTPRequest req, ToadletContext ctx)
				throws ToadletContextClosedException, IOException,
				RedirectException {
			super.handleMethodGET(uri, req, ctx);
		}

		@Override
		public void handleMethodPOST(URI uri, HTTPRequest request,
				ToadletContext ctx) throws ToadletContextClosedException,
				IOException, RedirectException {
			super.handleMethodPOST(uri, request, ctx);
		}

		protected DeleteOwnIdentityWebInterfaceToadlet(HighLevelSimpleClient client, WebInterface wi, NodeClientCore core, String pageTitle) {
			super(client, wi, core, pageTitle);
		}

		@Override
		WebPage makeWebPage(HTTPRequest req, ToadletContext context) throws UnknownIdentityException {
			return new DeleteOwnIdentityPage(this, req, context);
		}
		
		@Override
		public Toadlet showAsToadlet() {
			return ownIdentitiesToadlet;
		}
		
	}

	public class EditOwnIdentityWebInterfaceToadlet extends WebInterfaceToadlet {

		@Override
		public void handleMethodGET(URI uri, HTTPRequest req, ToadletContext ctx)
				throws ToadletContextClosedException, IOException,
				RedirectException {
			super.handleMethodGET(uri, req, ctx);
		}

		@Override
		public void handleMethodPOST(URI uri, HTTPRequest request,
				ToadletContext ctx) throws ToadletContextClosedException,
				IOException, RedirectException {
			super.handleMethodPOST(uri, request, ctx);
		}

		protected EditOwnIdentityWebInterfaceToadlet(HighLevelSimpleClient client, WebInterface wi, NodeClientCore core, String pageTitle) {
			super(client, wi, core, pageTitle);
		}

		@Override
		WebPage makeWebPage(HTTPRequest req, ToadletContext context) throws UnknownIdentityException {
			return new EditOwnIdentityPage(this, req, context);
		}
		
		@Override
		public Toadlet showAsToadlet() {
			return ownIdentitiesToadlet;
		}
		
	}

	public class IntroduceIdentityWebInterfaceToadlet extends WebInterfaceToadlet {

		@Override
		public void handleMethodGET(URI uri, HTTPRequest req, ToadletContext ctx)
				throws ToadletContextClosedException, IOException,
				RedirectException {
			super.handleMethodGET(uri, req, ctx);
		}

		@Override
		public void handleMethodPOST(URI uri, HTTPRequest request,
				ToadletContext ctx) throws ToadletContextClosedException,
				IOException, RedirectException {
			super.handleMethodPOST(uri, request, ctx);
		}

		protected IntroduceIdentityWebInterfaceToadlet(HighLevelSimpleClient client, WebInterface wi, NodeClientCore core, String pageTitle) {
			super(client, wi, core, pageTitle);
		}

		@Override
		WebPage makeWebPage(HTTPRequest req, ToadletContext context) throws UnknownIdentityException {
			return new IntroduceIdentityPage(this, req, context);
		}
		
		@Override
		public Toadlet showAsToadlet() {
			return ownIdentitiesToadlet;
		}
		
	}
	
	public class IdentityWebInterfaceToadlet extends WebInterfaceToadlet {

		@Override
		public void handleMethodGET(URI uri, HTTPRequest req, ToadletContext ctx)
				throws ToadletContextClosedException, IOException,
				RedirectException {
			super.handleMethodGET(uri, req, ctx);
		}

		@Override
		public void handleMethodPOST(URI uri, HTTPRequest request,
				ToadletContext ctx) throws ToadletContextClosedException,
				IOException, RedirectException {
			super.handleMethodPOST(uri, request, ctx);
		}

		protected IdentityWebInterfaceToadlet(HighLevelSimpleClient client, WebInterface wi, NodeClientCore core, String pageTitle) {
			super(client, wi, core, pageTitle);
		}

		@Override
		WebPage makeWebPage(HTTPRequest req, ToadletContext context) throws UnknownIdentityException {
			return new IdentityPage(this, req, context);
		}
		
		@Override
		public Toadlet showAsToadlet() {
			return knownIdentitiesToadlet;
		}
		
	}
	
	public WebInterface(WoT myWoT, String uri) {
		mWoT = myWoT;
		mURI = uri;
		
		mPluginRespirator = mWoT.getPluginRespirator();
		ToadletContainer container = mPluginRespirator.getToadletContainer();
		mPageMaker = mPluginRespirator.getPageMaker();
		mPageMaker.addNavigationCategory(mURI+"/", "Web of Trust", "Web of Trust, the collaborative spam filter underlying the Freetalk chat system", mWoT);
		
		// Visible pages
		
		container.register(homeToadlet = new HomeWebInterfaceToadlet(null, this, mWoT.getPluginRespirator().getNode().clientCore, ""), "Web of Trust", mURI+"/", true, "Home", "Home page", false, null);
		container.register(ownIdentitiesToadlet = new OwnIdentitiesWebInterfaceToadlet(null, this, mWoT.getPluginRespirator().getNode().clientCore, "OwnIdentities"), "Web of Trust", mURI + "/OwnIdentities", true, "Own Identities", "Manage your own identities", false, null);
		container.register(knownIdentitiesToadlet = new KnownIdentitiesWebInterfaceToadlet(null, this, mWoT.getPluginRespirator().getNode().clientCore, "KnownIdentities"), "Web of Trust", mURI + "/KnownIdentities", true, "Known Identities", "Manage others identities", false, null);
		container.register(configurationToadlet = new ConfigWebInterfaceToadlet(null, this, mWoT.getPluginRespirator().getNode().clientCore, "Configuration"), "Web of Trust", mURI + "/Configuration", true, "Configuration", "Configure the WoT plugin", false, null);
		
		// Invisible pages
		
		container.register(createIdentityToadlet = new CreateIdentityWebInterfaceToadlet(null, this, mWoT.getPluginRespirator().getNode().clientCore, "CreateIdentity"), null, mURI + "/CreateIdentity", true, false);
		container.register(deleteOwnIdentityToadlet = new DeleteOwnIdentityWebInterfaceToadlet(null, this, mWoT.getPluginRespirator().getNode().clientCore, "DeleteOwnIdentity"), null, mURI + "/DeleteOwnIdentity", true, false);
		container.register(editOwnIdentityToadlet = new EditOwnIdentityWebInterfaceToadlet(null, this, mWoT.getPluginRespirator().getNode().clientCore, "EditOwnIdentity"), null, mURI + "/EditOwnIdentity", true, false);
		container.register(introduceIdentityToadlet = new IntroduceIdentityWebInterfaceToadlet(null, this, mWoT.getPluginRespirator().getNode().clientCore, "IntroduceIdentity"), null, mURI + "/IntroduceIdentity", true, false);
		container.register(identityToadlet = new IdentityWebInterfaceToadlet(null, this, mWoT.getPluginRespirator().getNode().clientCore, "ShowIdentity"), null, mURI + "/ShowIdentity", true, false);
	}
	
	public String getURI() {
		return mURI;
	}

	public PageMaker getPageMaker() {
		return mPageMaker;
	}
	
	public WoT getWoT() {
		return mWoT;
	}
	

	private static final String l10n(String string) {
		return NodeL10n.getBase().getString("ConfigToadlet." + string);
	}
	
	public void unload() {
		ToadletContainer container = mPluginRespirator.getToadletContainer();
		for(Toadlet t : new Toadlet[] { 
				homeToadlet,
				ownIdentitiesToadlet,
				knownIdentitiesToadlet,
				configurationToadlet,
				createIdentityToadlet,
				deleteOwnIdentityToadlet,
				editOwnIdentityToadlet,
				introduceIdentityToadlet,
				identityToadlet
		}) container.unregister(t);
		mPageMaker.removeNavigationCategory("Web of Trust");
	}

}


