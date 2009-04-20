/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WoT.ui.web;

import freenet.support.HTMLNode;
import freenet.support.api.HTTPRequest;

/**
 * Shown when a severe internal Exception occurs in WoT
 * 
 * @author xor (xor@freenetproject.org)
 */
public class ErrorPage extends WebPageImpl {
	
	private final Exception mError;

	public ErrorPage(WebInterface myWebInterface, HTTPRequest myRequest, Exception myError) {
		super(myWebInterface, myRequest);
		mError = myError;
	}

	public void make() {
		HTMLNode errorBox = addErrorBox("Internal error, please report this");
		
		String message = mError.getLocalizedMessage();
		if(message == null || message.equals(""))
			message = mError.getMessage();
		
		HTMLNode p = errorBox.addChild("p", message);
		
		p = errorBox.addChild("p", "Stack trace:");
		for(StackTraceElement element : mError.getStackTrace()) {
			p.addChild("br"); p.addChild("#", element.toString());
		}

	}

}
