/**
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */
package plugins.WoT.ui.web;

/**
 * Interface specifying what a WebPage should do.
 * 
 * @author Julien Cornuwel (batosai@freenetproject.org)
 */
public interface WebPage {

	/**
	 * Possibility to add an ErrorBox from outside the Object.
	 * Needed by the controller (WoT) if something goes wrong while executing a user request.
	 */
	public void addErrorBox(String title, String message);
	
	/**
	 * Actually generates the content of the page.
	 * Each subclass knows what it has to do.
	 */
	public void make();
	
	/**
	 * @return the HTML code of this WebPage.
	 */
	public String toHTML();
	
}
