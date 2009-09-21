/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WoT.exceptions;

import plugins.WoT.Identity;

/**
 * Thrown when querying the {@link Score} of a target {@link Identity} in the trust tree of a tree owner {@link Identity} shows that there is no {@link Score} for
 * the target in the tree owner's trust tree.  
 * 
 * @author Julien Cornuwel (batosai@freenetproject.org)
 *
 */
public class NotInTrustTreeException extends Exception {
	
	private static final long serialVersionUID = -1;
	
	public NotInTrustTreeException(Identity target) {
		super("There is no trust tree which contains " + target);
	}

	public NotInTrustTreeException(Identity treeOwner, Identity target) {
		super(target + " is not in the trust treee of " + treeOwner);
	}

}
