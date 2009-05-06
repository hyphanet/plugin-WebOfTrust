/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WoT.exceptions;

/**
 * Thrown when there are more than one Identities with the same id
 * in the database. Should never happen anymore.
 * 
 * @author xor (xor@freenetproject.org)
 * @author Julien Cornuwel (batosai@freenetproject.org)
 */
public class DuplicateIdentityException extends RuntimeException {
	
	private static final long serialVersionUID = -1;

	public DuplicateIdentityException(String id, int amount) {
		super("Duplicate identity: " + id + "; " + amount + " copies exist.");
	}
}
