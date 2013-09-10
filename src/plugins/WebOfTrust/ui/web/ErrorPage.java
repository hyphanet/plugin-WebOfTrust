/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.ui.web;

import freenet.clients.http.RedirectException;
import freenet.clients.http.SessionManager.Session;
import freenet.clients.http.ToadletContext;
import freenet.support.Logger;
import freenet.support.api.HTTPRequest;

/**
 * Shown when a severe internal Exception occurs in WoT
 * 
 * @author xor (xor@freenetproject.org)
 */
public class ErrorPage extends WebPageImpl {
	
	private final Exception mError;

	/**
	 * @throws RedirectException Should never be thrown since no {@link Session} is used.
	 */
	public ErrorPage(WebInterfaceToadlet toadlet, HTTPRequest myRequest, ToadletContext context, Exception myError) throws RedirectException {
		super(toadlet, myRequest, context, false);
		mError = myError;
		Logger.error(this, "Internval error, please report this", mError);
	}

	public void make() {
		addErrorBox("Internal error, please report this", mError);
	}
}
