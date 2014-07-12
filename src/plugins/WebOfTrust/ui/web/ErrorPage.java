/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.ui.web;

import plugins.WebOfTrust.exceptions.UnknownIdentityException;
import freenet.clients.http.ToadletContext;
import freenet.support.Logger;
import freenet.support.api.HTTPRequest;

/**
 * Use this for displaying typical WOT exceptions such as {@link UnknownIdentityException}. Usually you want to use it like this:
 * <p><code>try {...} catch (UnknownIdentityException e) {
				new ErrorPage(mToadlet, mRequest, mContext, e).addToPage(this);
			}</p></code> 
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
		if(mError instanceof UnknownIdentityException) {
			final String id = ((UnknownIdentityException)mError).getIdentityID();
			addErrorBox(l10n().getString("Common.UnknownIdentityExceptionTitle"), l10n().getString("Common.UnknownIdentityExceptionDescription", "identityID", id));
		} else {
			addErrorBox("Internal error, please report this", mError);
			Logger.error(this, "Internval error, please report this", mError);
		}
	}
}
