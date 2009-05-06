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
	
	/** The offered context. */
	private final Context mContext;
	
	/** The identity which offers the context. */
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
	
	/** Get the offered context */
	public Context getContext() {
		return mContext;
	}
	
	/** Get the identity which offers this context. */
	public Identity getProvider() {
		return mProvider;
	}
	
}
