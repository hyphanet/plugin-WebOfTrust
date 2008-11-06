/**
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */
package plugins.WoT;

import java.net.MalformedURLException;

import plugins.WoT.exceptions.DuplicateIdentityException;
import plugins.WoT.exceptions.InvalidParameterException;
import plugins.WoT.exceptions.UnknownIdentityException;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.query.Constraint;
import com.db4o.query.Query;

/**
 * The Score of an Identity in an OwnIdentity's trust tree.
 * 
 * @author Julien Cornuwel (batosai@freenetproject.org)
 */
public class Score {

	private final OwnIdentity treeOwner; 	// OwnIdentity that owns the trust tree
	private final Identity target;			// Identity that has this Score
	
	// The actual score of the Identity. 
	// Used to decide if the OwnIdentity sees the Identity or not
	private int score;
	
	// How far the Identity is from the tree's root. 
	// Tells how much point it can add to its trustees score.
	private int rank;
	
	// How much point the target Identity can add to its trustees score.
	// Depends on its rank AND the trust given by the tree owner.
	// If the tree owner sets a negative trust on the target identity,
	// it gets zero capacity, even if it has a positive score.
	private int capacity;
	
	/**
	 * Creates a Score from given parameters.
	 * 
	 * @param treeOwner The owner of the trust tree
	 * @param target The Identity that has the score
	 * @param score The actual score of the Identity. 
	 * @param rank How far the Identity is from the tree's root. 
	 * @param capacity How much point the target Identity can add to its trustees score.
	 */
	public Score (OwnIdentity treeOwner, Identity target, int score, int rank, int capacity) {
		this.treeOwner = treeOwner;
		this.target = target;
		this.score = score;
		this.rank = rank;
		this.capacity = capacity;
	}

	/**
	 * Counts the number of Score objects in the database.
	 * 
	 * @param db A reference to the database
	 * @return the number of Score objects in the database
	 */
	public static int getNb(ObjectContainer db) {
		ObjectSet<Score> scores = db.queryByExample(Score.class);
		return scores.size();
	}
	
	/**
	 * Gets Identities matching a specified score criteria.
	 * 
	 * @param db A reference to the database
	 * @param owner requestURI of the owner of the trust tree
	 * @param select Score criteria, can be '+', '0' or '-'
	 * @return an {@link ObjectSet} containing Identities that match the criteria
	 * @throws InvalidParameterException if the criteria is not recognised
	 */
	@SuppressWarnings("unchecked")
	public static ObjectSet<Score> getIdentitiesByScore (ObjectContainer db, OwnIdentity treeOwner, int select) throws InvalidParameterException {
		if(treeOwner == null)
			throw new IllegalArgumentException();
		
		Query query = db.query();
		query.constrain(Score.class);
		query.descend("treeOwner").constrain(treeOwner);
	
		// TODO: we should decide whether identities with score 0 should be returned if select>0
		
		if(select > 0)
			query.descend("score").constrain(new Integer(0)).greater();
		else if(select < 0 )
			query.descend("score").constrain(new Integer(0)).smaller();
		else 
			query.descend("score").constrain(new Integer(0));

		return query.execute();
	}
	
	public String toString() {
		return getTarget().getNickName() + " has " + getScore() + " points in " + getTreeOwner().getNickName() + "'s trust tree (rank : " + getRank() + ", capacity : " + getCapacity() + ")";
	}

	/**
	 * @return in which OwnIdentity's trust tree this score is
	 */
	public OwnIdentity getTreeOwner() {
		return treeOwner;
	}

	/**
	 * @return Identity that has this Score
	 */
	public Identity getTarget() {
		return target;
	}

	/**
	 * @return the numeric value of this Score
	 */
	public int getScore() {
		return score;
	}

	/**
	 * Sets the numeric value of this Score
	 */
	public void setScore(int score) {
		this.score = score;
	}

	/**
	 * @return How far the target Identity is from the trust tree's root
	 */
	public int getRank() {
		return rank;
	}

	/**
	 * Sets how far the target Identity is from the trust tree's root.
	 */
	public void setRank(int rank) {
		this.rank = rank;
	}

	/**
	 * @return how much points the target Identity can add to its trustees score
	 */
	public int getCapacity() {
		return capacity;
	}

	/**
	 * Sets how much points the target Identity can add to its trustees score.
	 */
	public void setCapacity(int capacity) {
		this.capacity = capacity;
	}
}
