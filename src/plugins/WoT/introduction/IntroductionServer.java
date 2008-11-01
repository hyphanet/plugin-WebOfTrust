/*
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */
package plugins.WoT.introduction;

import java.util.Date;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;

import freenet.client.HighLevelSimpleClient;
import freenet.support.Logger;
import freenet.support.io.TempBucketFactory;
import plugins.WoT.OwnIdentity;

/**
 * This class provides identity announcement for new identities; It uploads puzzles in certain time intervals and checks whether they were solved.
 * 
 * @author xor
 */
public class IntroductionServer implements Runnable {
	
	private static long THREAD_PERIOD = 30 * 60 * 1000;
	private static short PUZZLES_PER_DAY = 1; 
	private static long PUZZLE_INVALID_AFTER_DAYS = 3;

	/** A reference to the database */
	ObjectContainer db;

	/** A reference the HighLevelSimpleClient used to perform inserts */
	HighLevelSimpleClient client;
	
	/** The TempBucketFactory used to create buckets from puzzles before insert */
	final TempBucketFactory tBF;
	
	/** Used to tell the introduction server thread if it should stop */
	boolean isRunning;

	/**
	 * Creates an IntroductionServer
	 * 
	 * @param db
	 *            A reference to the database
	 * @param client
	 *            A reference to an {@link HighLevelSimpleClient} to perform
	 *            inserts
	 * @param tbf
	 *            Needed to create buckets from Identities before insert
	 */
	public IntroductionServer(ObjectContainer db, HighLevelSimpleClient client, TempBucketFactory tbf) {
		this.db = db;
		this.client = client;
		isRunning = true;
		tBF = tbf;
	}

	public void run() {
		try {
			Thread.sleep((long) (3*60*1000 * (0.5f + Math.random()))); // Let the node start up
		}
		catch (InterruptedException e) {}
		
		while(isRunning) {
			ObjectSet<OwnIdentity> identities = OwnIdentity.getAllOwnIdentities(db);
			
			while(identities.hasNext()) {
				OwnIdentity identity = identities.next();
				synchronized(identity) {
					if(identity.hasContext("introduction")) {
						try {
							insertPuzzles(identity);
							// identity.setLastInsert(new Date()); 
							// db.store(identity);
						} catch (Exception e) {
							Logger.error(this, "Puzzle insert failed: " + e.getMessage(), e);
						}
					}
				}
			}
			db.commit();
			
			try {
				Thread.sleep((long) (THREAD_PERIOD * (0.5f + Math.random())));
			}
			catch (InterruptedException e){}
		}

	}
	
	private void insertPuzzles(OwnIdentity identity) {
		
	}

}
