package plugins.WebOfTrust;

/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
import plugins.WebOfTrust.ui.fcp.FCPClientReferenceImplementation;
import plugins.WebOfTrust.ui.fcp.FCPInterface;

import com.db4o.ext.ExtObjectContainer;

import freenet.pluginmanager.PluginRespirator;

/**
 * This is a minimal implementor of the abstract base class {@link WebOfTrustInterface}.
 * It basically does nothing - it won't maintain a database, it won't store anything, it won't do any processing.
 * The purpose of this implementor is to allow you to easily copy-paste the core-classes into your own plugin WITHOUT copying the main
 * class {@link WebOfTrust}.
 * This will be necessary when copy-pasting the reference implementation of a WOT client {@link FCPClientReferenceImplementation}.
 * 
 * An implementor of the {@link WebOfTrustInterface} is needed by constructors and functions of the core classes which represent a WOT:
 * - {@link Persistent}
 * - {@link Identity} & {@link OwnIdentity}
 * - {@link Trust}
 * - {@link Score}
 *
 * @seee WebOfTrustInterface
 * @author xor (xor@freenetproject.org)
 */
public class MockWebOfTrust extends WebOfTrustInterface {

	public MockWebOfTrust() {
		// FIXME Auto-generated constructor stub
	}

	@Override
	protected PluginRespirator getPluginRespirator() {
		// FIXME Auto-generated method stub
		return null;
	}

	@Override
	protected ExtObjectContainer getDatabase() {
		// FIXME Auto-generated method stub
		return null;
	}

	@Override
	protected SubscriptionManager getSubscriptionManager() {
		// FIXME Auto-generated method stub
		return null;
	}

	@Override
	protected FCPInterface getFCPInterface() {
		// FIXME Auto-generated method stub
		return null;
	}

}
