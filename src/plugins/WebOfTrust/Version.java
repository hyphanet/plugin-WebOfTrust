/**
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */
package plugins.WebOfTrust;

import freenet.pluginmanager.FredPluginRealVersioned;
import freenet.pluginmanager.FredPluginVersioned;

/** Specifies the version numbers of WoT.  
 *  Used by class {@link WebOfTrust} to implement {@link FredPluginVersioned} and
 *  {@link FredPluginRealVersioned}. */
public final class Version {

	/** This is replaced by the Ant/Gradle build scripts during compilation.
	 *  It thus must be private and only accessible through a getter function to ensure its
	 *  pre-replacement default value does not get inlined into the code of other classes by the
	 *  compiler! */
	private static final String gitRevision = "@custom@";

	/** The {@link FredPluginRealVersioned#getRealVersion()} aka build number.
	 *  NOTICE: This is used by fred's auto-update code to distinguish different versions, so it
	 *  MUST be incremented on **every** release. */
	private static final long version = 20;

	/** The version which we tell the user for advertising purposes, e.g. by incrementing the major
	 *  version on important new features.
	 *  In opposite to {@link #version} this does not have to be changed on every release. */
	private static final String marketingVersion = "0.4.5";

	/** Published as an identity property if you own a seed identity.
	 *  TODO: Not actually implemented yet, do so or remove it. */
	private static final long mandatoryVersion = 1;

	/** Published as an identity property if you own a seed identity.
	 *  TODO: Not actually implemented yet, do so or remove it. */
	private static final long latestVersion = version;


	public static String getMarketingVersion() {
		return marketingVersion + " " + gitRevision;
	}

	public static long getRealVersion() {
		return version;
	}
}

