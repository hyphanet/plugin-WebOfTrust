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
	 */
	public void make();
	
	/**
	 * @return the HTML code of this WebPage.
	 */
	public String toHTML();
	
}
