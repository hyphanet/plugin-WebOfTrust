/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import java.util.List;

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
 * @see MockWebOfTrust A minimal implementor of this interface. Copy-paste this as well.
 * @author xor (xor@freenetproject.org)
 */
public abstract class WebOfTrustInterface {

	/**
	 * The "name" of this web of trust. It is included in the document name of identity URIs. For an example, see the {@link #SEED_IDENTITIES}
	 * constant below. The purpose of this constant is to allow anyone to create his own custom web of trust which is completely disconnected
	 * from the "official" web of trust of the Freenet project. It is also used as the session cookie namespace.
	 */
	public static final String WOT_NAME = "WebOfTrust";
	
	/**
	 * The official seed identities of the WoT plugin: If a newbie wants to download the whole offficial web of trust, he needs at least one
	 * trust list from an identity which is well-connected to the web of trust. To prevent newbies from having to add this identity manually,
	 * the Freenet development team provides a list of seed identities - each of them is one of the developers.
	 */
	static final String[] SEED_IDENTITIES = new String[] { 
		"USK@QeTBVWTwBldfI-lrF~xf0nqFVDdQoSUghT~PvhyJ1NE,OjEywGD063La2H-IihD7iYtZm3rC0BP6UTvvwyF5Zh4,AQACAAE/WebOfTrust/1502", // xor
		"USK@z9dv7wqsxIBCiFLW7VijMGXD9Gl-EXAqBAwzQ4aq26s,4Uvc~Fjw3i9toGeQuBkDARUV5mF7OTKoAhqOA9LpNdo,AQACAAE/WebOfTrust/5156", // Toad
		"USK@o2~q8EMoBkCNEgzLUL97hLPdddco9ix1oAnEa~VzZtg,X~vTpL2LSyKvwQoYBx~eleI2RF6QzYJpzuenfcKDKBM,AQACAAE/WebOfTrust/15723", // Bombe
		"USK@D3MrAR-AVMqKJRjXnpKW2guW9z1mw5GZ9BB15mYVkVc,xgddjFHx2S~5U6PeFkwqO5V~1gZngFLoM-xaoMKSBI8,AQACAAE/WebOfTrust/8137", // zidel
		"USK@nmTkFmn0Akz1-G9iIN2w6lsQEAfWkpQw3ckOxtwMh2Q,Du9GQxGDj0Nax4zN9-ANTPetx-GxOoWaRf6Gq6Nbh1o,AQACAAE/WebOfTrust/7437", // operhiem1
	};
	
	abstract public List<Identity> getAllIdentities();
	
	abstract public List<Trust> getAllTrusts();
	
	abstract public List<Score> getAllScores();
	

	abstract protected PluginRespirator getPluginRespirator();
	
	abstract protected ExtObjectContainer getDatabase();

	abstract protected SubscriptionManager getSubscriptionManager();

	abstract protected FCPInterface getFCPInterface();

}
