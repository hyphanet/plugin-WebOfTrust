/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.util;

import static java.lang.System.err;

import java.util.HashSet;

import junit.framework.TestCase;
import plugins.WebOfTrust.Identity;
import plugins.WebOfTrust.exceptions.InvalidParameterException;

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
	 */
	public void testNameTokens() {
		HashSet<String> seenFirstNames = new HashSet<>(RandomName.firstnames.length * 2);
		HashSet<String> seenLastNames  = new HashSet<>(RandomName.lastnames.length  * 2);
		boolean duplicatesFound = false;
		
		for(String firstname : RandomName.firstnames) {
			try {
				Identity.validateNickname(firstname);
			} catch(InvalidParameterException e) {
				fail("Invalid first name: '" + firstname + "', reason: " + e);
			}
			
			if(!seenFirstNames.add(firstname)) {
				err.println("Duplicate first name: " + firstname);
				duplicatesFound = true;
			}
		}
		
		for(String lastname : RandomName.lastnames) {
			try {
				Identity.validateNickname(lastname);
			} catch(InvalidParameterException e) {
				fail("Invalid last name: '" + lastname + "', reason: " + e);
			}
			
			if(!seenLastNames.add(lastname)) {
				err.println("Duplicate last name: " + lastname);
				duplicatesFound = true;
			}
		}
		
		assertFalse("Duplicate check failed, see stderr for duplicates.", duplicatesFound);
	}

	/**
	 * Test whether the actual name generation function {@link RandomName#newNickname()} generates valid nicknames.
	 * This is needed in addition to {@link #testNameTokens()} to ensure that the token separator used in the nickname is also valid.
	 */
	public void testNewNickname() {
		String name = RandomName.newNickname();
		try {
			Identity.validateNickname(name);
		} catch(InvalidParameterException e) {
			fail("Invalid name: '" + name + "', reason: " + e);
		}
	}

}
