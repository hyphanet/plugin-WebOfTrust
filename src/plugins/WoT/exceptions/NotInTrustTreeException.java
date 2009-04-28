/**
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */
package plugins.WoT.exceptions;

/**
 * Thrown when querying the Score of an Identity that is 
 * not in the trust tree.
 * 
 * @author Julien Cornuwel (batosai@freenetproject.org)
 *
 */
public class NotInTrustTreeException extends Exception {
	
	private static final long serialVersionUID = -1;

	public NotInTrustTreeException(String message) {
		super(message);
	}

}
