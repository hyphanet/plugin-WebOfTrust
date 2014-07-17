/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.ui.web;

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
	 */
	public void addToPage(WebPageImpl other);
	
}
