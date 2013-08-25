/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.ui.web;

import java.awt.image.RenderedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

import javax.imageio.ImageIO;
import javax.naming.SizeLimitExceededException;

import freenet.clients.http.RedirectException;
import plugins.WebOfTrust.Identity;
import plugins.WebOfTrust.Identity.IdentityID;
import plugins.WebOfTrust.OwnIdentity;
import plugins.WebOfTrust.WebOfTrust;
import plugins.WebOfTrust.exceptions.UnknownIdentityException;
import plugins.WebOfTrust.identicon.Identicon;
import plugins.WebOfTrust.introduction.IntroductionPuzzle;
import freenet.client.HighLevelSimpleClient;
import freenet.client.filter.ContentFilter;
import freenet.clients.http.PageMaker;
import freenet.clients.http.Toadlet;
import freenet.clients.http.ToadletContainer;
import freenet.clients.http.ToadletContext;
import freenet.clients.http.ToadletContextClosedException;
import freenet.l10n.BaseL10n;
import freenet.node.NodeClientCore;
import freenet.pluginmanager.PluginRespirator;
import freenet.support.IllegalBase64Exception;
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
	
	private final WebOfTrust mWoT;
	private final PluginRespirator mPluginRespirator;
	private final PageMaker mPageMaker;

	// Used by pages not listed in the menu. TODO: For what?
	private final WebInterfaceToadlet ownIdentitiesToadlet;
	private final WebInterfaceToadlet knownIdentitiesToadlet;

	private final HashMap<Class<? extends WebInterfaceToadlet>, WebInterfaceToadlet> toadlets;

	private final String mURI;

	private static final String MENU_NAME = "WebInterface.WotMenuName";

	/**
	 * Forward access to current l10n data.
	 * 
	 * @return current BaseL10n data
	 */
	public BaseL10n l10n() {
	    return mWoT.getBaseL10n();
	}

	public class StatisticsWebInterfaceToadlet extends WebInterfaceToadlet {

		protected StatisticsWebInterfaceToadlet(HighLevelSimpleClient client, WebInterface wi, NodeClientCore core, String pageTitle) {
			super(client, wi, core, pageTitle);
		}

		@Override
		WebPage makeWebPage(HTTPRequest req, ToadletContext context) {
			return new StatisticsPage(this, req, context, l10n());
		}

	}
	
	public class OwnIdentitiesWebInterfaceToadlet extends WebInterfaceToadlet {

		protected OwnIdentitiesWebInterfaceToadlet(HighLevelSimpleClient client, WebInterface wi, NodeClientCore core, String pageTitle) {
			super(client, wi, core, pageTitle);
		}

		@Override
		WebPage makeWebPage(HTTPRequest req, ToadletContext context) {
			return new OwnIdentitiesPage(this, req, context, l10n());
		}
	}
	
	public class KnownIdentitiesWebInterfaceToadlet extends WebInterfaceToadlet {

		protected KnownIdentitiesWebInterfaceToadlet(HighLevelSimpleClient client, WebInterface wi, NodeClientCore core, String pageTitle) {
			super(client, wi, core, pageTitle);
		}

		@Override
		WebPage makeWebPage(HTTPRequest req, ToadletContext context) {
			return new KnownIdentitiesPage(this, req, context, l10n());
		}
	}

	public class LoginWebInterfaceToadlet extends WebInterfaceToadlet {

		protected LoginWebInterfaceToadlet(HighLevelSimpleClient client, WebInterface wi, NodeClientCore core, String pageTitle) {
			super(client, wi, core, pageTitle);
		}

		@Override
		WebPage makeWebPage(HTTPRequest req, ToadletContext context) throws UnknownIdentityException {
			return new LogInPage(this, req, context, l10n());
		}

		/** Log an user in from a POST and redirect to the BoardsPage */
		@Override
		public void handleMethodPOST(URI uri, HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
			if(!ctx.checkFullAccess(this))
				return;

			String pass = request.getPartAsStringFailsafe("formPassword", 32);
			if ((pass.length() == 0) || !pass.equals(core.formPassword)) {
				writeHTMLReply(ctx, 403, "Forbidden", "Invalid form password.");
				return;
			}

			final String ID;
			try {
				ID = request.getPartAsStringThrowing("OwnIdentityID", IdentityID.MAX_IDENTITY_ID_LENGTH);
				assert ID.length() == IdentityID.MAX_IDENTITY_ID_LENGTH;
			} catch (SizeLimitExceededException e) {
				Logger.error(this.getClass(),
				    "The identity ID was too long. (Expected " + IdentityID.MAX_IDENTITY_ID_LENGTH +") Was the page modified?", e);
				sendErrorPage(ctx, 500, "Unexpectedly long identity ID", "");
				return;
			}

			try {
				final OwnIdentity ownIdentity = mWoT.getOwnIdentityByID(ID);
				mPluginRespirator.getSessionManager(WebOfTrust.WOT_NAME).createSession(ownIdentity.getID(), ctx);
			} catch(UnknownIdentityException e) {
				Logger.error(this.getClass(), "Attempted to log in to unknown identity. Was it deleted?", e);
				writeTemporaryRedirect(ctx, "Unknown identity", path());
			}

			try {
				/**
				 * "redirect-target" is a node-relative target after successful login. It is encoded but the URI
				 * constructor should decode.
				 * @see LogInPage
				 *
				 * The limit of 64 characters is arbitrary.
				 */
				URI raw = new URI(request.getPartAsStringFailsafe("redirect-target", 64));
				// Use only the path, query, and fragment. Stay on the node's scheme, host, and port.
				URI target = new URI(null, null, raw.getPath(), raw.getQuery(), raw.getFragment());
				writeTemporaryRedirect(ctx, "Login successful", target.toString());
			} catch (URISyntaxException e) {
				writeInternalError(e, ctx);
			}
		}
	}

	public class CreateIdentityWebInterfaceToadlet extends WebInterfaceToadlet {

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
			
		    if(!ctx.checkFullAccess(this))
		        return;
		    
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
				Closer.close(dataBucket);
				Closer.close(filterInput);
				Closer.close(filterOutput);
				// Closer.close(output); // We do not have to do that, writeReply() does it for us
			}
		}
		
		WebPage makeWebPage(HTTPRequest req, ToadletContext context) {
			// Not expected to make it here...
			return new StatisticsPage(this, req, context, l10n());
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
		    if(!toadletContext.checkFullAccess(this))
		        return;
			
			String identityId = httpRequest.getParam("identity");
			int width = 128;
			int height = 128;
			try {
				width = Integer.parseInt(httpRequest.getParam("width"));
				height = Integer.parseInt(httpRequest.getParam("height"));
			} catch (NumberFormatException nfe1) {
				/* could not parse, ignore. defaults are fine. */
			}
			if (width < 1) {
				width = 128;
			}
			if (height < 1) {
				height = 128;
			}
			ByteArrayOutputStream imageOutputStream = null;
			try {
				RenderedImage identiconImage = new Identicon(IdentityID.constructAndValidateFromString(identityId).getRoutingKey()).render(width, height);
				imageOutputStream = new ByteArrayOutputStream();
				ImageIO.write(identiconImage, "png", imageOutputStream);
				Bucket imageBucket = BucketTools.makeImmutableBucket(core.tempBucketFactory, imageOutputStream.toByteArray());
				writeReply(toadletContext, 200, "image/png", "OK", imageBucket);
			} catch (IllegalBase64Exception e) {
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

	public WebInterface(WebOfTrust myWoT, String uri) {
		mWoT = myWoT;
		mURI = uri;
		
		mPluginRespirator = mWoT.getPluginRespirator();
		ToadletContainer container = mPluginRespirator.getToadletContainer();
		mPageMaker = mPluginRespirator.getPageMaker();

		mPageMaker.addNavigationCategory(mURI+"/", MENU_NAME, MENU_NAME + ".Tooltip", mWoT, mPluginRespirator.getNode().pluginManager.isPluginLoaded("plugins.Freetalk.Freetalk") ? 2 : 1);

		final NodeClientCore core = mWoT.getPluginRespirator().getNode().clientCore;

		/*
		 * These are WebInterfaceToadlets instead of Toadlets because the package-scope pageTitle is used to look up
		 * the menu localization keys.
		 *
		 * Pages listed in the menu:
		 */

		WebInterfaceToadlet home = new StatisticsWebInterfaceToadlet(null, this, core, "Statistics");
		ownIdentitiesToadlet = new OwnIdentitiesWebInterfaceToadlet(null, this, core, "OwnIdentities");
		knownIdentitiesToadlet = new KnownIdentitiesWebInterfaceToadlet(null, this, core, "KnownIdentities");

		ArrayList<WebInterfaceToadlet> listed = new ArrayList<WebInterfaceToadlet>(Arrays.asList(
			new LoginWebInterfaceToadlet(null, this, core, "LogIn"),
			home,
			ownIdentitiesToadlet,
			knownIdentitiesToadlet
		));

		// Register homepage at the root. This catches any otherwise unmatched request because it is registered first.
		container.register(home, null, mURI + "/", true, true);
		
		toadlets = new HashMap<Class<? extends WebInterfaceToadlet>, WebInterfaceToadlet>();

		for (WebInterfaceToadlet toadlet : listed) {
			registerMenu(container, toadlet);
			toadlets.put(toadlet.getClass(), toadlet);
		}

		// Pages not listed in the menu:

		ArrayList<WebInterfaceToadlet> unlisted = new ArrayList<WebInterfaceToadlet>(Arrays.asList(
			new CreateIdentityWebInterfaceToadlet(null, this, core, "CreateIdentity"),
			new DeleteOwnIdentityWebInterfaceToadlet(null, this, core, "DeleteOwnIdentity"),
			new EditOwnIdentityWebInterfaceToadlet(null, this, core, "EditOwnIdentity"),
			new IntroduceIdentityWebInterfaceToadlet(null, this, core, "IntroduceIdentity"),
			new IdentityWebInterfaceToadlet(null, this, core, "ShowIdentity"),
			new GetPuzzleWebInterfaceToadlet(null, this, core, "GetPuzzle"),
			new GetIdenticonWebInterfaceToadlet(null, this, core, "GetIdenticon")
		));

		for (WebInterfaceToadlet toadlet : unlisted) {
			registerHidden(container, toadlet);
			toadlets.put(toadlet.getClass(), toadlet);
		}

	}

	/**
	 * Register the given Toadlet as fullAccessOnly at its .path() and list it in the menu.<br/>
	 * The menu item is "WebInterface.WotMenuItem." followed by the pageTitle.<br/>
	 * The tooltip is the same followed by ".Tooltip".<br/>
	 * @param container to register with.
	 * @param toadlet to register.
	 */
	private void registerMenu(ToadletContainer container, WebInterfaceToadlet toadlet) {
		container.register(toadlet, MENU_NAME, toadlet.path(), true,
		    "WebInterface.WotMenuItem." + toadlet.pageTitle,
		    "WebInterface.WotMenuItem." + toadlet.pageTitle + ".Tooltip", true, null);
	}

	/**
	 * Register the given Toadlet as fullAccessOnly at its .path() without listing it in the menu.
	 * @param container to register with.
	 * @param toadlet to register.
	 */
	private void registerHidden(ToadletContainer container, Toadlet toadlet) {
		container.register(toadlet, null, toadlet.path(), true, true);
	}
	
	public WebInterfaceToadlet getToadlet(Class<? extends Toadlet> clazz) {
		return toadlets.get(clazz);
	}

	public String getURI() {
		return mURI;
	}

	public PageMaker getPageMaker() {
		return mPageMaker;
	}
	
	public WebOfTrust getWoT() {
		return mWoT;
	}
	
	public void unload() {
		ToadletContainer container = mPluginRespirator.getToadletContainer();
		for(Toadlet t : toadlets.values()) {
			container.unregister(t);
		}
		mPageMaker.removeNavigationCategory(MENU_NAME);
	}
}
