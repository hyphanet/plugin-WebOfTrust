/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.ui.web;

import freenet.clients.http.ToadletContext;
import freenet.l10n.BaseL10n;
import freenet.support.Logger;
import freenet.support.api.HTTPRequest;

/**
 * Shown when a severe internal Exception occurs in WoT
 * 
 * @author xor (xor@freenetproject.org)
 */
public class ErrorPage extends WebPageImpl {
	
	private final Exception mError;

	public ErrorPage(WebInterfaceToadlet toadlet, HTTPRequest myRequest, ToadletContext context, Exception myError, BaseL10n _baseL10n) {
		super(toadlet, myRequest, context, _baseL10n);
		mError = myError;
		Logger.error(this, "Internval error, please report this", mError);
	}

	public void make() {
		addErrorBox("Internal error, please report this", mError);
	}
}
