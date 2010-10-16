/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WoT.exceptions;

/**
 * Thrown when there are more than one Context with the same name in the database. Should never happen.
 * 
 * @author xor (xor@freenetproject.org)
 */
public class DuplicateContextException extends RuntimeException {
	
	private static final long serialVersionUID = -1;

	public DuplicateContextException(String name, int amount) {
		super("Duplicate context: " + name + "; " + amount + " copies exist.");
	}
}
