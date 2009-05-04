/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WoT;


/**
 * An object of type ContextOffer is stored for each identity which provides a given {@link Context}.
 * 
 * @author xor (xor@freenetproject.org)
 */
public final class ContextOffer {
	
	private final Context mContext;
	
	private final Identity mProvider;
	
	/** Get a list of fields which the database should create an index on. */
	public static String[] getIndexedFields() {
		return new String[] { "mContext", "mProvider" };
	}
	
	public ContextOffer(Context myContext, Identity myProvider) {
		if(myContext == null)
			throw new NullPointerException();
		
		if(myProvider == null)
			throw new NullPointerException();
		
		mContext = myContext;
		mProvider = myProvider;
	}
	
	public Context getContext() {
		return mContext;
	}
	
	public Identity getProvider() {
		return mProvider;
	}
	
}
