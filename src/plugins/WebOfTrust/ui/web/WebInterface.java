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
import java.util.NoSuchElementException;

import javax.imageio.ImageIO;
import javax.naming.SizeLimitExceededException;

import plugins.WebOfTrust.Identity.IdentityID;
import plugins.WebOfTrust.OwnIdentity;
import plugins.WebOfTrust.WebOfTrust;
import plugins.WebOfTrust.exceptions.UnknownIdentityException;
import plugins.WebOfTrust.identicon.Identicon;
import plugins.WebOfTrust.introduction.IntroductionPuzzle;
import plugins.WebOfTrust.introduction.IntroductionPuzzleStore;
import freenet.client.HighLevelSimpleClient;
import freenet.client.filter.ContentFilter;
import freenet.clients.http.PageMaker;
import freenet.clients.http.RedirectException;
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

	/**
	 * Used by the {@link Toadlet#showAsToadlet()} implementations of:
	 * - {@link CreateOwnIdentityWebInterfaceToadlet}
	 * - {@link DeleteOwnIdentityWebInterfaceToadlet}
	 * - {@link EditOwnIdentityWebInterfaceToadlet}
	 * - {@link IntroduceIdentityWebInterfaceToadlet}
	 */
	private final WebInterfaceToadlet myIdentityToadlet;
	
	/**
	 * Used by the {@link Toadlet#showAsToadlet()} implementation of {@link IdentityWebInterfaceToadlet}.
	 */
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
			return new StatisticsPage(this, req, context);
		}

		@Override
		public boolean isEnabled(ToadletContext ctx) {
			return true;
		}
	}
	
	public class MyIdentityWebInterfaceToadlet extends WebInterfaceToadlet {

		protected MyIdentityWebInterfaceToadlet(HighLevelSimpleClient client, WebInterface wi, NodeClientCore core, String pageTitle) {
			super(client, wi, core, pageTitle);
		}

		@Override
		WebPage makeWebPage(HTTPRequest req, ToadletContext context) throws RedirectException, UnknownIdentityException {
			return new MyIdentityPage(this, req, context);
		}
	}
	
	public class KnownIdentitiesWebInterfaceToadlet extends WebInterfaceToadlet {

		protected KnownIdentitiesWebInterfaceToadlet(HighLevelSimpleClient client, WebInterface wi, NodeClientCore core, String pageTitle) {
			super(client, wi, core, pageTitle);
		}

		@Override
		WebPage makeWebPage(HTTPRequest req, ToadletContext context) throws RedirectException, UnknownIdentityException {
			return new KnownIdentitiesPage(this, req, context);
		}
	}

	public class LoginWebInterfaceToadlet extends WebInterfaceToadlet {

		protected LoginWebInterfaceToadlet(HighLevelSimpleClient client, WebInterface wi, NodeClientCore core, String pageTitle) {
			super(client, wi, core, pageTitle);
		}

		@Override
		WebPage makeWebPage(HTTPRequest req, ToadletContext context) {
			return new LogInPage(this, req, context);
		}

		/** Log an user in from a POST and redirect to the BoardsPage */
		@Override
		public void handleMethodPOST(URI uri, HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException, SizeLimitExceededException, NoSuchElementException {
			if(!ctx.checkFullAccess(this))
				return;

			if(!checkAntiCSRFToken(request, ctx))
				return;

			final String ID = request.getPartAsStringThrowing("OwnIdentityID", IdentityID.LENGTH);
			assert ID.length() == IdentityID.LENGTH;

			try {
		        // TODO: Performance: The synchronized() can be removed after this is fixed:
		        // https://bugs.freenetproject.org/view.php?id=6247
			    synchronized(mWoT) {
			        final OwnIdentity ownIdentity = mWoT.getOwnIdentityByID(ID);
			        sessionManager.createSession(ownIdentity.getID(), ctx);
			    }
			} catch(UnknownIdentityException e) {
				Logger.error(this.getClass(), "Attempted to log in to unknown identity. Was it deleted?", e);
				writeTemporaryRedirect(ctx, "Unknown identity", path());
				return;
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
		
		@Override
		public boolean isEnabled(ToadletContext ctx) {
			return !sessionManager.sessionExists(ctx);
		}
	}
	
	class LogOutWebInterfaceToadlet extends WebInterfaceToadlet {

		protected LogOutWebInterfaceToadlet(HighLevelSimpleClient client, WebInterface wi, NodeClientCore core, String pageTitle) {
			super(client, wi, core, pageTitle);
		}

		@Override
		WebPage makeWebPage(HTTPRequest req, ToadletContext context) throws RedirectException {
			// TODO: Secure log out against malicious links (by using POST with form password instead of GET)
			//       At the moment it is just a link and unsecured i.e. no form password check etc.
			//       Bugtracker entry for this: https://bugs.freenetproject.org/view.php?id=6238
			logOut(context);
			throw new RedirectException(getToadlet(LoginWebInterfaceToadlet.class).getURI());
		}
		
	}

	public class CreateOwnIdentityWebInterfaceToadlet extends WebInterfaceToadlet {

		protected CreateOwnIdentityWebInterfaceToadlet(HighLevelSimpleClient client, WebInterface wi, NodeClientCore core, String pageTitle) {
			super(client, wi, core, pageTitle);
		}

		@Override
		WebPage makeWebPage(HTTPRequest req, ToadletContext context) {
			return new CreateOwnIdentityWizardPage(this, req, context);
		}
		
		@Override
		public Toadlet showAsToadlet() {
			// TODO: The myIdentityToadlet won't be visible if no OwnIdentity is logged in. Is it a good idea to return it then? Probably not.
			// Then we should instead use the LogInWebInterfaceToadlet as a menu entry (= return value) because when nobody is logged in, that is
			// the way to access this CreateOwnIdentityWebInterfaceToadlet.
			// However, it is not possible to check whether someone is logged in in this function because it does not get a ToadletContext.
			return myIdentityToadlet;
		}
		
		@Override
		public boolean isEnabled(ToadletContext ctx) {
			return true;
		}
	}

	public class DeleteOwnIdentityWebInterfaceToadlet extends WebInterfaceToadlet {

		protected DeleteOwnIdentityWebInterfaceToadlet(HighLevelSimpleClient client, WebInterface wi, NodeClientCore core, String pageTitle) {
			super(client, wi, core, pageTitle);
		}

		@Override
		WebPage makeWebPage(HTTPRequest req, ToadletContext context) throws UnknownIdentityException, RedirectException {
			return new DeleteOwnIdentityPage(this, req, context);
		}
		
		@Override
		public Toadlet showAsToadlet() {
			return myIdentityToadlet;
		}
	}

	public class EditOwnIdentityWebInterfaceToadlet extends WebInterfaceToadlet {

		protected EditOwnIdentityWebInterfaceToadlet(HighLevelSimpleClient client, WebInterface wi, NodeClientCore core, String pageTitle) {
			super(client, wi, core, pageTitle);
		}

		@Override
		WebPage makeWebPage(HTTPRequest req, ToadletContext context) throws UnknownIdentityException, RedirectException {
			return new EditOwnIdentityPage(this, req, context);
		}
		
		@Override
		public Toadlet showAsToadlet() {
			return myIdentityToadlet;
		}
	}

	public class IntroduceIdentityWebInterfaceToadlet extends WebInterfaceToadlet {

		protected IntroduceIdentityWebInterfaceToadlet(HighLevelSimpleClient client, WebInterface wi, NodeClientCore core, String pageTitle) {
			super(client, wi, core, pageTitle);
		}

		@Override
		WebPage makeWebPage(HTTPRequest req, ToadletContext context) throws UnknownIdentityException, RedirectException {
			return new IntroduceIdentityPage(this, req, context);
		}
		
		@Override
		public Toadlet showAsToadlet() {
			return myIdentityToadlet;
		}
	}
	
	public class IdentityWebInterfaceToadlet extends WebInterfaceToadlet {

		protected IdentityWebInterfaceToadlet(HighLevelSimpleClient client, WebInterface wi, NodeClientCore core, String pageTitle) {
			super(client, wi, core, pageTitle);
		}

		@Override
		WebPage makeWebPage(HTTPRequest req, ToadletContext context) throws UnknownIdentityException, RedirectException {
			return new IdentityPage(this, req, context);
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

		public URI getURI(String puzzleID) {
			final URI baseURI = getURI();
			
			try {
				// The parameter which is baseURI.getPath() may not be null, otherwise the last directory is stripped.
				return baseURI.resolve(new URI(null, null, baseURI.getPath(), "PuzzleID=" + puzzleID, null));
			} catch (URISyntaxException e) {
				throw new RuntimeException(e);
			}
		}

		@Override
		public void handleMethodGET(URI uri, HTTPRequest req, ToadletContext ctx) throws ToadletContextClosedException, IOException {

			// ATTENTION: The same code is used in Freetalk's WebInterface.java. Please synchronize any changes which happen there.
			
		    if(!ctx.checkFullAccess(this))
		        return;
		    
			Bucket dataBucket = null;
			Bucket output = core.tempBucketFactory.makeBucket(-1);
			InputStream filterInput = null;
			OutputStream filterOutput = null;
			try {
			    final IntroductionPuzzleStore puzzleStore = mWoT.getIntroductionPuzzleStore();
				final IntroductionPuzzle puzzle;
		        // TODO: Performance: The synchronized() and clone() can be removed after this is
				// fixed: https://bugs.freenetproject.org/view.php?id=6247
				synchronized(puzzleStore) {
				    puzzle = puzzleStore.getByID(req.getParam("PuzzleID")).clone();
				}
				
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
		
		@Override
		WebPage makeWebPage(HTTPRequest req, ToadletContext context) {
			return new ErrorPage(this, req, context, new RuntimeException("This Toadlet does not offer HTML."));
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
			return new ErrorPage(this, req, context, new RuntimeException("This Toadlet does not offer HTML."));
		}

	}

	/**
	 * @return Null if the Freenet web interface is disabled, a valid WOT WebInterface otherwise.
	 */
	public static WebInterface constructIfEnabled(WebOfTrust myWoT, String uri) {
	    if(myWoT.getPluginRespirator().getNode().config
	        .get("fproxy").getBoolean("enabled") == false) {
	        
	        return null;
	    }
	    
	    return new WebInterface(myWoT, uri);
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

		myIdentityToadlet = new MyIdentityWebInterfaceToadlet(null, this, core, "MyIdentity");
		knownIdentitiesToadlet = new KnownIdentitiesWebInterfaceToadlet(null, this, core, "KnownIdentities");

		ArrayList<WebInterfaceToadlet> listed = new ArrayList<WebInterfaceToadlet>(Arrays.asList(
			new LoginWebInterfaceToadlet(null, this, core, "LogIn"),
			myIdentityToadlet,
			knownIdentitiesToadlet,
			new StatisticsWebInterfaceToadlet(null, this, core, "Statistics"),
			new LogOutWebInterfaceToadlet(null, this, core, "LogOut")
		));

		// Register homepage at the root. This catches any otherwise unmatched request because it is registered first.
		container.register(myIdentityToadlet, null, mURI + "/", true, true);
		
		toadlets = new HashMap<Class<? extends WebInterfaceToadlet>, WebInterfaceToadlet>();

		for (WebInterfaceToadlet toadlet : listed) {
			registerMenu(container, toadlet);
			toadlets.put(toadlet.getClass(), toadlet);
		}

		// Pages not listed in the menu:

		ArrayList<WebInterfaceToadlet> unlisted = new ArrayList<WebInterfaceToadlet>(Arrays.asList(
			new CreateOwnIdentityWebInterfaceToadlet(null, this, core, "CreateOwnIdentity"),
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
		    "WebInterface.WotMenuItem." + toadlet.pageTitle + ".Tooltip", true, toadlet);
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
