/**
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */
package plugins.WebOfTrust;

/**
 * Necessary to be able to use pluginmanager's versions
 * 
 * @author Julien Cornuwel (batosai@freenetproject.org)
 *
 */
public class Version {


	/** This is replaced by the Ant/Gradle build scripts during compilation.
	 *  It thus must be private and only accessible through a getter function to ensure its
	 *  pre-replacement default value does not get inlined into the code of other classes by the
	 *  compiler! */
	private static final String gitRevision = "@custom@";

	/** Version number of the plugin for getRealVersion(). Increment this on making
	 * a major change, a significant bugfix etc. These numbers are used in auto-update 
	 * etc, at a minimum any build inserted into auto-update should have a unique 
	 * version.
	 */
	private static final long version = 20;

	private static final String marketingVersion = "0.4.5";

	/** Published as an identity property if you own a seed identity. */
	private static final long mandatoryVersion = 1;

	/** Published as an identity property if you own a seed identity. */
	private static final long latestVersion = version;


	public static String getMarketingVersion() {
		return marketingVersion + " " + gitRevision;
	}

	public static long getRealVersion() {
		return version;
	}
}

