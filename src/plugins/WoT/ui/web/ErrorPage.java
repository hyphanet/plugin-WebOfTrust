/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WoT.ui.web;

import freenet.clients.http.ToadletContext;
import freenet.support.api.HTTPRequest;

/**
 * Shown when a severe internal Exception occurs in WoT
 * 
 * @author xor (xor@freenetproject.org)
 */
public class ErrorPage extends WebPageImpl {
	
	private final Exception mError;

	public ErrorPage(WebInterfaceToadlet toadlet, HTTPRequest myRequest, ToadletContext context, Exception myError) {
		super(toadlet, myRequest, context);
		mError = myError;
	}

	public void make() {
		addErrorBox("Internal error, please report this", mError);
	}

}
