/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import org.junit.Ignore;

/**
 * Version of {@link AbstractMultiNodeTest} which only runs a single node.
 * 
 * FIXME: Code quality: The name of this only made sense when the base class AbstractMultiNodeTest
 * did not exist yet. Thus rename to AbstractSingleNodeTest. */
@Ignore("Is ignored so it can be abstract. Contained self-tests will be run by child classes.")
public abstract class AbstractFullNodeTest extends AbstractMultiNodeTest {

}
