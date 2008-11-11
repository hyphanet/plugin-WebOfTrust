/*
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */
package plugins.WoT.introduction;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Date;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.ext.DatabaseClosedException;
import com.db4o.ext.Db4oIOException;
import com.db4o.query.Query;

import freenet.client.ClientMetadata;
import freenet.client.HighLevelSimpleClient;
import freenet.client.InsertBlock;
import freenet.client.InsertException;
import freenet.keys.FreenetURI;
import freenet.support.Logger;
import freenet.support.api.Bucket;
import freenet.support.io.TempBucketFactory;
import plugins.WoT.OwnIdentity;
import plugins.WoT.exceptions.InvalidParameterException;

/**
 * This class provides identity announcement for new identities; It uploads puzzles in certain time intervals and checks whether they were solved.
 * 
 * @author xor
 */
public class IntroductionServer implements Runnable {
	
	private static final long THREAD_PERIOD = 30 * 60 * 1000;
	private static final short PUZZLES_COUNT = 5; 
	public static final long PUZZLE_INVALID_AFTER_DAYS = 3;

	private Thread mThread;
	
	/** Used to tell the introduction server thread if it should stop */
	private boolean isRunning;
	
	/** A reference to the database */
	private ObjectContainer db;

	/** A reference the HighLevelSimpleClient used to perform inserts */
	private HighLevelSimpleClient mClient;
	
	/** The TempBucketFactory used to create buckets from puzzles before insert */
	private final TempBucketFactory mTBF;

	private final IntroductionPuzzleFactory[] mPuzzleFactories = new IntroductionPuzzleFactory[] { new CaptchaFactory1() };

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
	public IntroductionServer(ObjectContainer myDB, HighLevelSimpleClient myClient, TempBucketFactory myTBF) {
		isRunning = true;
		db = myDB;
		mClient = myClient;
		mTBF = myTBF;
	}

	public void run() {
		mThread = Thread.currentThread();
		try {
			Thread.sleep((long) (1*60*1000 * (0.5f + Math.random()))); // Let the node start up
		}
		catch (InterruptedException e) {}
		
		while(isRunning) {
			ObjectSet<OwnIdentity> identities = OwnIdentity.getAllOwnIdentities(db);
			
			IntroductionPuzzle.deleteOldPuzzles(db);
			
			while(identities.hasNext()) {
				OwnIdentity identity = identities.next();
				if(identity.hasContext(IntroductionPuzzle.INTRODUCTION_CONTEXT)) {
					try {
						managePuzzles(identity);
						downloadSolutions(identity);
					} catch (Exception e) {
						Logger.error(this, "Puzzle insert failed: " + e.getMessage(), e);
					}
				}
			}
			db.commit();
			
			try {
				Thread.sleep((long) (THREAD_PERIOD * (0.5f + Math.random())));
			}
			catch (InterruptedException e)
			{
				mThread.interrupt();
			}
		}

	}
	
	public void terminate() {
		Logger.debug(this, "Stopping the introduction server...");
		isRunning = false;
		mThread.interrupt();
		try {
			mThread.join();
		}
		catch(InterruptedException e)
		{
			Thread.currentThread().interrupt();
		}
		Logger.debug(this, "Stopped the introduction server.");
	}
	
	private void managePuzzles(OwnIdentity identity) {
		Query q = db.query();
		q.constrain(IntroductionPuzzle.class);
		q.descend("mInserter").constrain(identity);
		ObjectSet<IntroductionPuzzle> puzzles = q.execute();
		
		Logger.debug(this, "Identity " + identity.getNickName() + " has " + puzzles.size() + " puzzles stored. Deleting expired ones ...");
		
		for(IntroductionPuzzle p : puzzles) {
			if(p.getValidUntilTime() < System.currentTimeMillis()) {
				db.delete(p);
				puzzles.remove(p);
			}
		}
		
		db.commit();
		
		int puzzlesToInsert = PUZZLES_COUNT - puzzles.size();
		Logger.debug(this, "Trying to insert " + puzzlesToInsert + " puzzles from " + identity.getNickName());
		while(puzzlesToInsert > 0) {
			try {
				insertNewPuzzle(identity);
			}
			catch(Exception e) {
				Logger.error(this, "Error while inserting puzzle", e);
			}
			--puzzlesToInsert;
		}
		Logger.debug(this, "Finished inserting puzzles from " + identity.getNickName());
	}
	
	private void insertNewPuzzle(OwnIdentity identity) throws IOException, InsertException, TransformerException, ParserConfigurationException {
		Bucket tempB = mTBF.makeBucket(10 * 1024); /* TODO: set to a reasonable value */
		OutputStream os = tempB.getOutputStream();
		
		try {
			IntroductionPuzzle p = mPuzzleFactories[(int)(Math.random() * 100) % mPuzzleFactories.length].generatePuzzle();
			p.exportToXML(os);
			os.close(); os = null;
			tempB.setReadOnly();
		
			ClientMetadata cmd = new ClientMetadata("text/xml");
			InsertBlock ib = new InsertBlock(tempB, cmd, p.getURI());

			Logger.debug(this, "Started insert puzzle from " + identity.getNickName());

			/* FIXME: use nonblocking insert */
			mClient.insert(ib, false, p.getURI().getMetaString());

			db.store(p);
			db.commit();
			Logger.debug(this, "Successful insert of puzzle from " + identity.getNickName());
		}
		finally {
			tempB.free();
			if(os != null)
				os.close();
		}
	}
	
	private void downloadSolutions(OwnIdentity identity) {
		
	}
}
