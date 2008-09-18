/**
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */
package plugins.WoT;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;

/**
 * A trust relationship between two identities
 * 
 * @author Julien Cornuwel (batosai@freenetproject.org)
 *
 */
public class Trust {

	/* We use a reference to the truster here rather that storing the trustList in the Identity.
	 * This allows us to load only what's needed in memory instead of everything.
	 * Maybe db4o can handle this, I don't know ATM.
	 */
	private Identity truster;
	private Identity trustee;
	private int value;
	private String comment;
	
		
	public Trust(Identity truster, Identity trustee, int value, String comment) throws InvalidParameterException {
		this.truster = truster;
		this.trustee = trustee;
		setValue(value);
		setComment(comment);
	}
	
	public static int getNb(ObjectContainer db) {
		ObjectSet<Trust> trusts = db.queryByExample(Trust.class);
		return trusts.size();
	}
	
	public Element toXML(Document xmlDoc) {
		Element elem = xmlDoc.createElement("trust");
		elem.setAttribute("uri", trustee.getRequestURI().toString());
		elem.setAttribute("value", String.valueOf(value));
		elem.setAttribute("comment", comment);
		
		return elem;
	}
	
	public String toString() {
		return getTruster().getNickName() + " trusts " + getTrustee().getNickName() + " (" + getValue() + " : " + getComment() + ")";
	}

	/**
	 * @return truster
	 */
	public Identity getTruster() {
		return truster;
	}

	/**
	 * @param truster Identity that gives the trust
	 */
	public void setTruster(Identity truster) {
		this.truster = truster;
	}

	/**
	 * @return trustee
	 */
	public Identity getTrustee() {
		return trustee;
	}

	/**
	 * @param trustee Identity that receives the trust
	 */
	public void setTrustee(Identity trustee) {
		this.trustee = trustee;
	}

	/**
	 * @return value
	 */
	public int getValue() {
		return value;
	}

	/**
	 * @param value Numeric value of the trust [-100;+100] 
	 * @throws InvalidParameterException if value isn't in the range
	 */
	public void setValue(int value) throws InvalidParameterException {
		if(value < -100 || value > 100) 
			throw new InvalidParameterException("Invalid trust value ("+value+")");
		
		this.value = value;
	}

	/**
	 * 
	 * @return Comment
	 */
	public String getComment() {
		return comment;
	}

	/**
	 * 
	 * @param comment Comment on that trust relationship
	 */
	public void setComment(String comment) {
		this.comment = comment;
	}
}
