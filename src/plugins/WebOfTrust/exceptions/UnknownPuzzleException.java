/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WoT.exceptions;

public class UnknownPuzzleException extends Exception {

	private static final long serialVersionUID = 1L;

	public UnknownPuzzleException(String id) {
		super("There is no puzzle with ID " + id);
	}
}
