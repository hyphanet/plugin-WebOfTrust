/**
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */
package plugins.WoT;

/**
 * Necessary to be able to use pluginmanager's versions
 * 
 * @author Julien Cornuwel (batosai@freenetproject.org)
 *
 */
public class Version {
	/** SVN revision number. Only set if the plugin is compiled properly e.g. by emu. */
	private static final String svnRevision = "@custom@";

	/** Version number of the plugin for getRealVersion(). Increment this on making
	 * a major change, a significant bugfix etc. These numbers are used in auto-update 
	 * etc, at a minimum any build inserted into auto-update should have a unique 
	 * version.
	 * 
	 * I have set this to 4000 to allow encoding marketing versions into real versions.
	 * Long provides plenty of leeway! Toad. */
	public static long version = 4000;

	
	static String getSvnRevision() {
		return svnRevision;
	}
	
	public static void main(String[] args) {
		System.out.println("=====");
		System.out.println(svnRevision);
		System.out.println("=====");		
	}

	public static String getMarketingVersion() {
		return "0.4.0 beta1";
	}

	public static long getRealVersion() {
		return version;
	}
}

