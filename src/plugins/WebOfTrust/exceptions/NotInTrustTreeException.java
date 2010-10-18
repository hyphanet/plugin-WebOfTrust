/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.exceptions;

import plugins.WebOfTrust.Identity;
import plugins.WebOfTrust.Score;

/**
 * Thrown when querying the {@link Score} of a trusted {@link Identity} in the trust tree of a truster
 * {@link Identity} shows that there is no {@link Score} for the target in the tree owner's trust tree.  
 * 
 * @author xor (xor@freenetproject.org)
 * @author Julien Cornuwel (batosai@freenetproject.org)
 */
public class NotInTrustTreeException extends Exception {
	
	private static final long serialVersionUID = -1;
	
	public NotInTrustTreeException(Identity trustee) {
		super("There is no trust tree which contains " + trustee);
	}

	public NotInTrustTreeException(Identity truster, Identity trustee) {
		super(trustee + " is not in the trust treee of " + truster);
	}

}
