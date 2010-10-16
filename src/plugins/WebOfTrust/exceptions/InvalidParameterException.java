/**
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */
package plugins.WebOfTrust.exceptions;

/**
 * Thrown the user supplied an invalid parameter.
 * 
 * @author Julien Cornuwel (batosai@freenetproject.org)
 *
 */
public class InvalidParameterException extends Exception {
	
	private static final long serialVersionUID = -1;

	public InvalidParameterException(String message) {
		super(message);
	}
}
