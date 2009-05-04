/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WoT;


/**
 * A context is a service which an identity can provide, for example a client application such as "Freetalk".
 * 
 * The database stores exactly 1 object of type Context for every allowed Context.
 * Contexts are linked to identities by storying a {@link ContextOffer} object per "identity X offers context Y"
 * relation.
 * 
 * @author xor (xor@freenetproject.org)
 */
public final class Context {
	
	/**
	 * List of context names which WoT will allow, stored in the Config object upon creation of a new database.
	 */
	public static final String DEFAULT_ALLOWED_CONTEXT_NAMES = "Introduction, Freetalk";
	
	private final String mName;
	
	/** Get a list of fields which the database should create an index on. */
	public static String[] getIndexedFields() {
		return new String[] { "mName" };
	}
	
	public Context(String myName) {
		if(myName.length() == 0)
			throw new IllegalArgumentException("Empty context names are not permitted.");
		
		if(!myName.matches("[A-Za-z]"))
			throw new IllegalArgumentException("Context names must only contain the characters A to Z.");

		mName = myName.intern();
	}
	
	public String getName() {
		return mName;
	}
	
}
