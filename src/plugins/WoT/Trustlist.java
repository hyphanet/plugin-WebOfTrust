/**
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */
package plugins.WoT;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import plugins.WoT.exceptions.InvalidParameterException;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.ext.DatabaseClosedException;
import com.db4o.ext.Db4oIOException;

/**
 * A list of all Trusts given by an Identity
 * 
 * @author Julien Cornuwel (batosai@freenetproject.org)
 *
 */
public class Trustlist {

	public ObjectSet<Trust> list;
	
	/**
	 * Creates the trustlist of a local Identity
	 * 
	 * @param db A reference to the Database
	 * @param truster Identity that owns this trustList
	 * @throws InvalidParameterException 
	 * @throws DatabaseClosedException 
	 * @throws Db4oIOException 
	 */
	public Trustlist(ObjectContainer db, OwnIdentity truster) throws Db4oIOException, DatabaseClosedException, InvalidParameterException {
		list = truster.getGivenTrusts(db);
	}	
	
	/**
	 * Returns an XML Element containing the trustList
	 * 
	 * @param xmlDoc The XML Document
	 * @return Element containing details of the trustList
	 */
	public Element toXML(Document xmlDoc) {
		Element elem = xmlDoc.createElement("trustList");
		
		while(list.hasNext()) {
			elem.appendChild(list.next().toXML(xmlDoc));
		}
		
		return elem;
	}
}
