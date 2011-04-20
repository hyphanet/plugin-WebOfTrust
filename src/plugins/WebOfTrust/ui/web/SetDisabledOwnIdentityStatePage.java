/**
 * 
 */
package plugins.WebOfTrust.ui.web;

import plugins.WebOfTrust.OwnIdentity;
import freenet.clients.http.ToadletContext;
import freenet.l10n.BaseL10n;
import freenet.support.HTMLNode;
import freenet.support.api.HTTPRequest;

/**
 * @author sima (msizenko@gmail.com)
 */
public class SetDisabledOwnIdentityStatePage extends WebPageImpl {

	public SetDisabledOwnIdentityStatePage(WebInterfaceToadlet toadlet, HTTPRequest myRequest, ToadletContext context, BaseL10n _baseL10n) {
		super(toadlet, myRequest, context, _baseL10n);
	}

	public void make() {
		if(request.isPartSet("disable")) {	
			try{
				String identityID = request.getPartAsStringThrowing("id", 128);
				wot.setDisabledState(identityID, true);
				
				/* TODO: Show the OwnIdentities page instead! Use the trick which Freetalk does for inlining pages */
				HTMLNode box = addContentBox(l10n().getString("SetDisabledOwnIdentityStatePage.IdentityDisabled.Header"));
				box.addChild("#", l10n().getString("SetDisabledOwnIdentityStatePage.IdentityDisabled.Text"));
			} catch (Exception e) {
				addErrorBox(l10n().getString("SetDisabledOwnIdentityStatePage.DisableFailed"), e);
			}
		}else if(request.isPartSet("enable")) {
			try {
				String identityID = request.getPartAsStringThrowing("id", 128);
				wot.setDisabledState(identityID, false);
			
				/* TODO: Show the OwnIdentities page instead! Use the trick which Freetalk does for inlining pages */
				HTMLNode box = addContentBox(l10n().getString("SetDisabledOwnIdentityStatePage.IdentityEnabled.Header"));
				box.addChild("#", l10n().getString("SetDisabledOwnIdentityStatePage.IdentityEnabled.Text"));
			} catch (Exception e) {
				addErrorBox(l10n().getString("SetDisabledOwnIdentityStatePage.DisableFailed"), e);
			}
		}
	}
	
}
