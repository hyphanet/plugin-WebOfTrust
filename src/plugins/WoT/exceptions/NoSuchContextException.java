/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WoT.exceptions;

/**
 * Thrown when a query for a context is executed which does not exist. 
 *
 *	@author xor (xor@freenetproject.org)
 */
public class NoSuchContextException extends Exception {
	
	private static final long serialVersionUID = -1;
	
	public NoSuchContextException(String contextName) {
		super("Context does not exist: " + contextName);
	}
	
}
