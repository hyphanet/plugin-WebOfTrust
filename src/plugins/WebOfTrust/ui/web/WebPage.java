/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.ui.web;

import freenet.support.api.HTTPRequest;


/**
 * Interface specifying what a WebPage should do.
 * 
 * @author xor (xor@freenetproject.org)
 * @author Julien Cornuwel (batosai@freenetproject.org)
 */
public interface WebPage {

	/**
	 * Actually generates the page's content.
	 * 
	 * @param mayWrite If this is false, you MUST NOT do any changes to the server state, for example changing the WOT database.
	 *                 Technically, this is an anti-CSRF mechanism.
	 *                 See {@link WebInterfaceToadlet#checkAntiCSRFToken(freenet.support.api.HTTPRequest, freenet.clients.http.ToadletContext)}.
	 */
	public void make(boolean mayWrite);
	
	/**
	 * @return the HTML code of this WebPage.
	 */
	public String toHTML();
	
	/**
	 * Adds this WebPage to the given page as a HTMLNode.
	 * 
	 * This page's {@link #make(boolean)} is called with mayWrite==false for security: With this function, you can make WebPages accessible through
	 * {@link WebInterfaceToadlet}s through which they were not meant to be accessed. They might lack security checks of formdata which would normally be
	 * necessary for this WebPage. Since the {@link HTTPRequest} which possibly contains such unchecked formdata is passed through to this page, it is better
	 * to not allow it write access. 
	 */
	public void addToPage(WebPageImpl other);
	
}
