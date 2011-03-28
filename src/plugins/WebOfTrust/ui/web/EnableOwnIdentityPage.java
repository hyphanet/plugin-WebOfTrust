/**
 * 
 */
package plugins.WebOfTrust.ui.web;

import plugins.WebOfTrust.OwnIdentity;
import plugins.WebOfTrust.exceptions.UnknownIdentityException;
import freenet.clients.http.ToadletContext;
import freenet.l10n.BaseL10n;
import freenet.support.HTMLNode;
import freenet.support.api.HTTPRequest;

/**
 * @author sima (msizenko@gamil.com)
 *
 */
public class EnableOwnIdentityPage extends WebPageImpl {
	private final OwnIdentity mIdentity;

	public EnableOwnIdentityPage(WebInterfaceToadlet toadlet, HTTPRequest myRequest, ToadletContext context, BaseL10n _baseL10n) throws UnknownIdentityException {
		super(toadlet, myRequest, context, _baseL10n);
		mIdentity = wot.getOwnIdentityByID(request.getPartAsString("id", 128));
	}

	public void make() {
		if(request.isPartSet("enable")) {
			wot.enableIdentity(mIdentity);
			
			/* TODO: Show the OwnIdentities page instead! Use the trick which Freetalk does for inlining pages */
			HTMLNode box = addContentBox(l10n().getString("EnableOwnIdentityPage.IdentityEnabled.Header"));
			box.addChild("#", l10n().getString("EnableOwnIdentityPage.IdentityEnabled.Text"));
		}

	}


}
