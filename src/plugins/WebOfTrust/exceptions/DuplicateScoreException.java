/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.exceptions;

import plugins.WebOfTrust.Identity;

/**
 * Thrown when there are more than one Score for an Identity
 * (in the same trust tree) in the database. Should never happen anymore.
 * 
 * @author xor (xor@freenetproject.org)
 * @author Julien Cornuwel (batosai@freenetproject.org)
 */
public class DuplicateScoreException extends RuntimeException {
	
	private static final long serialVersionUID = -1;

	public DuplicateScoreException(Identity treeOwner, Identity target, int amount) {
		super("Duplicate score from " + treeOwner + " to " + target + "; "+ amount + " copies exist.");
	}
}
