/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WoT.ui.web;

import java.awt.image.RenderedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;

import javax.imageio.ImageIO;

import plugins.WoT.Identity;
import plugins.WoT.WoT;
import plugins.WoT.exceptions.UnknownIdentityException;
import plugins.WoT.identicon.Identicon;
import plugins.WoT.introduction.IntroductionPuzzle;
import freenet.client.HighLevelSimpleClient;
import freenet.clients.http.PageMaker;
import freenet.clients.http.RedirectException;
import freenet.clients.http.Toadlet;
import freenet.clients.http.ToadletContainer;
import freenet.clients.http.ToadletContext;
import freenet.clients.http.ToadletContextClosedException;
import freenet.client.filter.ContentFilter;
import freenet.l10n.BaseL10n;
import freenet.node.NodeClientCore;
import freenet.pluginmanager.PluginRespirator;
import freenet.support.Logger;
import freenet.support.api.Bucket;
import freenet.support.api.HTTPRequest;
import freenet.support.io.BucketTools;
import freenet.support.io.Closer;

/**
 * The web interface of the WoT plugin.
 * 
 * @author xor (xor@freenetproject.org)
 * @author Bombe
 * @author Julien Cornuwel (batosai@freenetproject.org)
 * @author bback
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
	
	// Invisible
	private final WebInterfaceToadlet createIdentityToadlet;
	private final WebInterfaceToadlet deleteOwnIdentityToadlet;
	private final WebInterfaceToadlet editOwnIdentityToadlet;
	private final WebInterfaceToadlet introduceIdentityToadlet;
	private final WebInterfaceToadlet identityToadlet;
	private final WebInterfaceToadlet getPuzzleToadlet;
	private final WebInterfaceToadlet getIdenticonToadlet;

	private final String mURI;
	
	/**
	 * Forward access to current l10n data.
	 * 
	 * @return current BaseL10n data
	 */
	public BaseL10n l10n() {
	    return mWoT.getBaseL10n();
	}

	public class HomeWebInterfaceToadlet extends WebInterfaceToadlet {

		protected HomeWebInterfaceToadlet(HighLevelSimpleClient client, WebInterface wi, NodeClientCore core, String pageTitle) {
			super(client, wi, core, pageTitle);
		}

		@Override
		WebPage makeWebPage(HTTPRequest req, ToadletContext context) {
			return new HomePage(this, req, context, l10n());
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
			return new OwnIdentitiesPage(this, req, context, l10n());
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
			return new KnownIdentitiesPage(this, req, context, l10n());
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
			return new ConfigurationPage(this, req, context, l10n());
		}
	}

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
			return new CreateIdentityPage(this, req, context, l10n());
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
			return new DeleteOwnIdentityPage(this, req, context, l10n());
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
			return new EditOwnIdentityPage(this, req, context, l10n());
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
			return new IntroduceIdentityPage(this, req, context, l10n());
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
			return new IdentityPage(this, req, context, l10n());
		}
		
		@Override
		public Toadlet showAsToadlet() {
			return knownIdentitiesToadlet;
		}
	}
	
	
	public class GetPuzzleWebInterfaceToadlet extends WebInterfaceToadlet {

		protected GetPuzzleWebInterfaceToadlet(HighLevelSimpleClient client, WebInterface wi, NodeClientCore core, String pageTitle) {
			super(client, wi, core, pageTitle);
		}

		public void handleMethodGET(URI uri, HTTPRequest req, ToadletContext ctx) throws ToadletContextClosedException, IOException {

			// ATTENTION: The same code is used in Freetalk's WebInterface.java. Please synchronize any changes which happen there.
			
			Bucket dataBucket = null;
			Bucket output = core.tempBucketFactory.makeBucket(-1);
			InputStream filterInput = null;
			OutputStream filterOutput = null;
			try {
				final IntroductionPuzzle puzzle = mWoT.getIntroductionPuzzleStore().getByID(req.getParam("PuzzleID"));
				
				final String mimeType = puzzle.getMimeType();
				
				// TODO: Store the list of allowed mime types in a constant. Also consider that we might have introduction puzzles with "Type=Audio" in the future.
				if(!mimeType.equalsIgnoreCase("image/jpeg") &&
				  	!mimeType.equalsIgnoreCase("image/gif") && 
				  	!mimeType.equalsIgnoreCase("image/png")) {
					
					throw new Exception("Mime type '" + mimeType + "' not allowed for introduction puzzles.");
				}
				
				dataBucket = BucketTools.makeImmutableBucket(core.tempBucketFactory, puzzle.getData());
				filterInput = dataBucket.getInputStream();
				filterOutput = output.getOutputStream();
				ContentFilter.filter(filterInput, filterOutput, puzzle.getMimeType(), uri, null, null, null);
				filterInput.close();
				filterOutput.close();
				writeReply(ctx, 200, puzzle.getMimeType(), "OK", output);
			}
			catch(Exception e) {
				sendErrorPage(ctx, 404, "Introduction puzzle not available", e.getMessage());
				Logger.error(this, "GetPuzzle failed", e);
			}
			finally {
				if(filterOutput != null)
					Closer.close(filterOutput);
				Closer.close(dataBucket);
				Closer.close(filterInput);
				Closer.close(filterOutput);
			}
		}
		
		WebPage makeWebPage(HTTPRequest req, ToadletContext context) {
			// Not expected to make it here...
			return new HomePage(this, req, context, l10n());
		}
	}

	public class GetIdenticonWebInterfaceToadlet extends WebInterfaceToadlet {

		public GetIdenticonWebInterfaceToadlet(HighLevelSimpleClient highLevelSimpleClient, WebInterface webInterface, NodeClientCore nodeClientCore, String pageTitle) {
			super(highLevelSimpleClient, webInterface, nodeClientCore, pageTitle);
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		@SuppressWarnings("synthetic-access")
		public void handleMethodGET(URI uri, HTTPRequest httpRequest, ToadletContext toadletContext) throws ToadletContextClosedException, IOException {
			String identityId = httpRequest.getParam("identity");
			ByteArrayOutputStream imageOutputStream = null;
			try {
				Identity identity = mWoT.getIdentityByID(identityId);
				byte[] routingKey = identity.getRequestURI().getRoutingKey();
				RenderedImage identiconImage = new Identicon(routingKey).render(128, 128);
				imageOutputStream = new ByteArrayOutputStream();
				ImageIO.write(identiconImage, "png", imageOutputStream);
				Bucket imageBucket = BucketTools.makeImmutableBucket(core.tempBucketFactory, imageOutputStream.toByteArray());
				writeReply(toadletContext, 200, "image/png", "OK", imageBucket);
			} catch (UnknownIdentityException uie1) {
				writeReply(toadletContext, 404, "text/plain", "Not found", "Not found.");
			} finally {
				Closer.close(imageOutputStream);
			}
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		WebPage makeWebPage(HTTPRequest req, ToadletContext context) throws UnknownIdentityException {
			return null;
		}

	}

	public WebInterface(WoT myWoT, String uri) {
		mWoT = myWoT;
		mURI = uri;
		
		mPluginRespirator = mWoT.getPluginRespirator();
		ToadletContainer container = mPluginRespirator.getToadletContainer();
		mPageMaker = mPluginRespirator.getPageMaker();
		
		mPageMaker.addNavigationCategory(mURI+"/", "WebInterface.WotMenuName", "WebInterface.WotMenuName.Tooltip", mWoT, mPluginRespirator.getNode().pluginManager.isPluginLoaded("plugins.Freetalk.Freetalk") ? 2 : 1);
		
		// Visible pages
		
		homeToadlet = new HomeWebInterfaceToadlet(null, this, mWoT.getPluginRespirator().getNode().clientCore, "");
		ownIdentitiesToadlet = new OwnIdentitiesWebInterfaceToadlet(null, this, mWoT.getPluginRespirator().getNode().clientCore, "OwnIdentities");
		knownIdentitiesToadlet = new KnownIdentitiesWebInterfaceToadlet(null, this, mWoT.getPluginRespirator().getNode().clientCore, "KnownIdentities");
		configurationToadlet = new ConfigWebInterfaceToadlet(null, this, mWoT.getPluginRespirator().getNode().clientCore, "Configuration");
		
		container.register(homeToadlet, "WebInterface.WotMenuName", mURI+"/", true, "WebInterface.WotMenuItem.Home", "WebInterface.WotMenuItem.Home.Tooltip", false, null);
		container.register(ownIdentitiesToadlet, "WebInterface.WotMenuName", mURI + "/OwnIdentities", true, "WebInterface.WotMenuItem.OwnIdentities", "WebInterface.WotMenuItem.OwnIdentities.Tooltip", false, null);
		container.register(knownIdentitiesToadlet, "WebInterface.WotMenuName", mURI + "/KnownIdentities", true, "WebInterface.WotMenuItem.KnownIdentities", "WebInterface.WotMenuItem.KnownIdentities.Tooltip", false, null);
		container.register(configurationToadlet, "WebInterface.WotMenuName", mURI + "/Configuration", true, "WebInterface.WotMenuItem.Configuration", "WebInterface.WotMenuItem.Configuration.Tooltip", false, null);
		
		// Invisible pages
		
		createIdentityToadlet = new CreateIdentityWebInterfaceToadlet(null, this, mWoT.getPluginRespirator().getNode().clientCore, "CreateIdentity");
		deleteOwnIdentityToadlet = new DeleteOwnIdentityWebInterfaceToadlet(null, this, mWoT.getPluginRespirator().getNode().clientCore, "DeleteOwnIdentity");
		editOwnIdentityToadlet = new EditOwnIdentityWebInterfaceToadlet(null, this, mWoT.getPluginRespirator().getNode().clientCore, "EditOwnIdentity");
		introduceIdentityToadlet = new IntroduceIdentityWebInterfaceToadlet(null, this, mWoT.getPluginRespirator().getNode().clientCore, "IntroduceIdentity");
		identityToadlet = new IdentityWebInterfaceToadlet(null, this, mWoT.getPluginRespirator().getNode().clientCore, "ShowIdentity");
		getPuzzleToadlet = new GetPuzzleWebInterfaceToadlet(null, this, mWoT.getPluginRespirator().getNode().clientCore, "GetPuzzle");
		getIdenticonToadlet = new GetIdenticonWebInterfaceToadlet(null, this, mWoT.getPluginRespirator().getNode().clientCore, "GetIdenticon");

		container.register(createIdentityToadlet, null, mURI + "/CreateIdentity", true, false);
		container.register(deleteOwnIdentityToadlet, null, mURI + "/DeleteOwnIdentity", true, false);
		container.register(editOwnIdentityToadlet, null, mURI + "/EditOwnIdentity", true, false);
		container.register(introduceIdentityToadlet, null, mURI + "/IntroduceIdentity", true, false);
		container.register(identityToadlet, null, mURI + "/ShowIdentity", true, false);
		container.register(getPuzzleToadlet, null, mURI + "/GetPuzzle", true, false);
		container.register(getIdenticonToadlet, null, mURI + "/GetIdenticon", true, false);
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
				identityToadlet,
				getPuzzleToadlet,
				getIdenticonToadlet
		}) container.unregister(t);
		mPageMaker.removeNavigationCategory("WebInterface.WotMenuName");
	}
}
