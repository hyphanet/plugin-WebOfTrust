/**
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */
package plugins.WoT;

import plugins.WoT.exceptions.InvalidParameterException;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.query.Query;

/**
 * A trust relationship between two Identities
 * 
 * @author xor (xor@freenetproject.org), Julien Cornuwel (batosai@freenetproject.org)
 *
 */
public class Trust {

	/* We use a reference to the truster here rather that storing the trustList in the Identity.
	 * This allows us to load only what's needed in memory instead of everything.
	 * Maybe db4o can handle this, I don't know ATM.
	 */
	private final Identity truster;
	private final Identity trustee;
	private byte value;
	private String comment;
	
	/**
	 * The edition number of the trust list in which this trust was published the last time.
	 * This is used to speed up the import of new trust lists: When importing them, we need to delete removed trust values. We cannot just
	 * delete all trust values of the truster from the database  and then import the trust list because deleting a trust causes recalculation
	 * of the score of the trustee. So for trust values which were not really removed from the trust list we would recalculate the score twice:
	 * One time when the old trust object is deleted and one time when the new trust is imported. Not only that we might recalculate one
	 * time without any necessity, most of the time even any recalculation would not be needed because the trust value has not changed.
	 * 
	 * To prevent this, we do the following: When creating new trusts, we store the edition number of the trust list from which we obtained it.
	 * When importing a new trust list, for each trust value we query the database whether a trust value to this trustee already exists and 
	 * update it if it does - we also update the trust list edition member variable. After having imported all trust values we query the 
	 * database for trust objects from the truster which have an old trust list edition number and delete them - the old edition number
	 * means that the trust has been removed from the latest trust list.
	 */
	@SuppressWarnings("unused")
	private long mTrusterTrustListEdition;
	
	/**
	 * Creates a Trust from given parameters.
	 * 
	 * @param truster Identity that gives the trust
	 * @param trustee Identity that receives the trust
	 * @param value Numeric value of the Trust
	 * @param comment A comment to explain the numeric trust value
	 * @throws InvalidParameterException if the trust value is not between -100 and +100
	 */
	public Trust(Identity truster, Identity trustee, byte value, String comment) throws InvalidParameterException {
		this.truster = truster;
		this.trustee = trustee;
		setValue(value);
		setComment(comment);
		
		mTrusterTrustListEdition = truster.getEdition(); 
	}
	
	@SuppressWarnings("unchecked")
	protected static ObjectSet<Trust> getTrustsOlderThan(ObjectContainer db, long edition) {
		Query q = db.query();
		q.constrain(Trust.class);
		q.descend("mTrusterTrustListEdition").constrain(edition).smaller();
		return q.execute();
	}
	
	/**
	 * Counts the number of Trust objects stored in the database
	 * 
	 * @param db A reference to the database
	 * @return the number of Trust objects stored in the database
	 */
	public static int getNb(ObjectContainer db) {
		ObjectSet<Trust> trusts = db.queryByExample(Trust.class);
		return trusts.size();
	}

	@Override
	public synchronized String toString() {
		return getTruster().getNickName() + " trusts " + getTrustee().getNickName() + " (" + getValue() + " : " + getComment() + ")";
	}

	/**
	 * @return The Identity that gives this trust
	 */
	public Identity getTruster() {
		return truster;
	}

	/**
	 * @return trustee The Identity that receives this trust
	 */
	public Identity getTrustee() {
		return trustee;
	}

	/**
	 * @return value Numeric value of this trust relationship
	 */
	public synchronized byte getValue() {
		return value;
	}

	/**
	 * @param value Numeric value of this trust relationship [-100;+100] 
	 * @throws InvalidParameterException if value isn't in the range
	 */
	public synchronized void setValue(byte newValue) throws InvalidParameterException {
		if(newValue < -100 || newValue > 100) 
			throw new InvalidParameterException("Invalid trust value ("+value+").");
		
		value = newValue;
	}

	/**
	 * @return comment The comment associated to this Trust relationship
	 */
	public synchronized String getComment() {
		return comment;
	}

	/**
	 * @param comment Comment on this trust relationship
	 */
	public synchronized void setComment(String newComment) throws InvalidParameterException {
		assert(newComment != null);
		
		if(newComment != null && newComment.length() > 256)
			throw new InvalidParameterException("Comment is too long (maximum is 256 characters).");
		
		comment = newComment != null ? newComment : "";
	}
	
	public synchronized void updated(ObjectContainer db) {
		mTrusterTrustListEdition = truster.getEdition();
		db.store(this);
	}

}
