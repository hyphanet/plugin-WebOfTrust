/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import org.junit.Before;
import org.junit.Ignore;

import freenet.node.Node;

/** Version of {@link AbstractMultiNodeTest} which only runs a single node. */
@Ignore("Is ignored so it can be abstract. Contained self-tests will be run by child classes.")
public abstract class AbstractSingleNodeTest extends AbstractMultiNodeTest {

	protected Node mNode;

	protected WebOfTrust mWebOfTrust;


	@Override public int getNodeCount() {
		return 1;
	}

	@Before public void setUp() {
		mNode = getNode();
		mWebOfTrust = getWebOfTrust();
	}

}
