/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import plugins.WebOfTrust.ui.fcp.FCPClientReferenceImplementation;
import plugins.WebOfTrust.ui.fcp.FCPInterface;

import com.db4o.ext.ExtObjectContainer;

import freenet.pluginmanager.PluginRespirator;

/**
 * This is a minimal interface needed by constructors and functions of the core classes which represent a WOT:
 * - {@link Persistent}
 * - {@link Identity} & {@link OwnIdentity}
 * - {@link Trust}
 * - {@link Score}
 * 
 * The purpose of this interface is to allow you to easily copy-paste the core-classes into your own plugin WITHOUT copying the main
 * class {@link WebOfTrust}.
 * This will be necessary when copy-pasting the reference implementation of a WOT client {@link FCPClientReferenceImplementation}.
 * 
 * @author xor (xor@freenetproject.org)
 */
public abstract class WebOfTrustInterface {

	/**
	 * The "name" of this web of trust. It is included in the document name of identity URIs. For an example, see the SEED_IDENTITIES
	 * constant below. The purpose of this constant is to allow anyone to create his own custom web of trust which is completely disconnected
	 * from the "official" web of trust of the Freenet project. It is also used as the session cookie namespace.
	 */
	public static final String WOT_NAME = "WebOfTrust";
	

	abstract protected PluginRespirator getPluginRespirator();
	
	abstract protected ExtObjectContainer getDatabase();

	abstract protected SubscriptionManager getSubscriptionManager();

	abstract protected FCPInterface getFCPInterface();

}
