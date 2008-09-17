/**
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */
package plugins.WoT;

/**
 * @author Julien Cornuwel (batosai@freenetproject.org)
 *
 */
public class Version {
	private static final String svnRevision = "@custom@";
	
	static String getSvnRevision() {
		return svnRevision;
	}
	
	public static void main(String[] args) {
		System.out.println("=====");
		System.out.println(svnRevision);
		System.out.println("=====");		
	}
}

