/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.util;

import plugins.WebOfTrust.Identity;
import plugins.WebOfTrust.exceptions.InvalidParameterException;
import junit.framework.TestCase;

/**
 * Tests class {@link RandomName}.
 * @author xor (xor@freenetproject.org)
 */
public class RandomNameTest extends TestCase {

	/**
	 * Random name has two arrays of name tokens: {@link RandomName#firstnames} and {@link RandomName#lastnames}.
	 * The random names are constructed from those tokens plus a separator.
	 * 
	 * This function tests whether the tokens are valid nicknames on their own according to {@link Identity#validateNickname(String)}.
	 * - If the tokens are valid, a combination of them using a separator is probably also valid.
	 * 
	 * @throws InvalidParameterException If the test failed.
	 */
	public void testNameTokens() throws InvalidParameterException {
		for(String firstname : RandomName.firstnames)
			Identity.validateNickname(firstname);
		
		for(String lastname : RandomName.lastnames)
			Identity.validateNickname(lastname);
	}

	/**
	 * Test whether the actual name generation function {@link RandomName#newNickname()} generates valid nicknames.
	 * This is needed in addition to {@link #testNameTokens()} to ensure that the token separator used in the nickname is also valid.
	 * 
	 * @throws InvalidParameterException If the test failed.
	 */
    public void testNewNickname() throws InvalidParameterException {
    	Identity.validateNickname(RandomName.newNickname());
    }

}
