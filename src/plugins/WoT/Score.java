/**
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */
package plugins.WoT;

import java.net.MalformedURLException;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.query.Query;

/**
 * @author Julien Cornuwel (batosai@freenetproject.org)
 *
 */
public class Score {

	private OwnIdentity treeOwner;
	private Identity target;
	private int score;
	private int rank;
	private int capacity;
	

	public Score (OwnIdentity treeOwner, Identity target, int score, int rank, int capacity) {
		this.treeOwner = treeOwner;
		this.target = target;
		this.score = score;
		this.rank = rank;
		this.capacity = capacity;
	}
	
	public static int getNb(ObjectContainer db) {
		ObjectSet<Score> scores = db.queryByExample(Score.class);
		return scores.size();
	}
	
	@SuppressWarnings("unchecked")
	public static ObjectSet<Score> getIdentitiesByScore (String owner, String select, ObjectContainer db) throws InvalidParameterException, MalformedURLException, UnknownIdentityException, DuplicateIdentityException {
		
		OwnIdentity treeOwner = OwnIdentity.getByURI(db, owner);
		
		Query query = db.query();
		query.constrain(Score.class);
		query.descend("treeOwner").constrain(treeOwner);
		
		if(select.equals("+")) {
			query.descend("score").constrain(new Integer(0)).greater();
		}
		else if(select.equals("0")) {
			query.descend("score").constrain(new Integer(0));
		}
		else if(select.equals("-")) {
			query.descend("score").constrain(new Integer(0)).smaller();
		}
		else throw new InvalidParameterException("Unhandled select value ("+select+")");

		return query.execute();
	}
	
	public String toString() {
		return getTarget().getNickName() + " has " + getScore() + " points in " + getTreeOwner().getNickName() + "'s trust tree (rank : " + getRank() + ", capacity : " + getCapacity() + ")";
	}

	public OwnIdentity getTreeOwner() {
		return treeOwner;
	}

	public void setTreeOwner(OwnIdentity treeOwner) {
		this.treeOwner = treeOwner;
	}

	public Identity getTarget() {
		return target;
	}

	public void setTarget(Identity target) {
		this.target = target;
	}

	public int getScore() {
		return score;
	}

	public void setScore(int score) {
		this.score = score;
	}

	public int getRank() {
		return rank;
	}

	public void setRank(int rank) {
		this.rank = rank;
	}

	public int getCapacity() {
		return capacity;
	}

	public void setCapacity(int capacity) {
		this.capacity = capacity;
	}
}
