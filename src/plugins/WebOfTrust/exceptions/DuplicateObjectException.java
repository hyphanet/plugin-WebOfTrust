/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.exceptions;

/**
 * Thrown when there are multiple instances of an object are the result of a database query which should only return one object.
 * 
 * TODO: Ged rid of all the specific Duplicate*Exception classes and use this instead. 
 * 
 * @author xor (xor@freenetproject.org)
 */
@SuppressWarnings("serial")
public final class DuplicateObjectException extends RuntimeException {
	
	public DuplicateObjectException(String message) {
		super(message);
	}
	
}
