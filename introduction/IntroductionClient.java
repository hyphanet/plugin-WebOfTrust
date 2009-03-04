/*
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */
package plugins.WoT.introduction;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;

import plugins.WoT.Identity;
import plugins.WoT.OwnIdentity;
import plugins.WoT.WoT;
import plugins.WoT.exceptions.InvalidParameterException;
import plugins.WoT.exceptions.NotInTrustTreeException;
import plugins.WoT.exceptions.NotTrustedException;
import plugins.WoT.introduction.IntroductionPuzzle.PuzzleType;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.query.Query;

import freenet.client.ClientMetadata;
import freenet.client.FetchContext;
import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.HighLevelSimpleClient;
import freenet.client.InsertBlock;
import freenet.client.InsertContext;
import freenet.client.InsertException;
import freenet.client.async.BaseClientPutter;
import freenet.client.async.ClientCallback;
import freenet.client.async.ClientGetter;
import freenet.client.async.ClientPutter;
import freenet.keys.FreenetURI;
import freenet.node.PrioRunnable;
import freenet.support.Logger;
import freenet.support.api.Bucket;
import freenet.support.io.Closer;
import freenet.support.io.NativeThread;
import freenet.support.io.TempBucketFactory;

/**
 * This class allows the user to announce new identities:
 * It downloads puzzles from known identites and uploads solutions of the puzzles.
 * 
 * @author xor
 *
 */
public final class IntroductionClient implements PrioRunnable, ClientCallback  {
	
	private static final int STARTUP_DELAY = 1 * 60 * 1000;
	private static final int THREAD_PERIOD = 30 * 60 * 1000; /* FIXME: tweak before release: */ 
	
	/* FIXME: Implement backwards-downloading. Currently, we only download puzzles from today. */
	/* public static final byte PUZZLE_DOWNLOAD_BACKWARDS_DAYS = IntroductionServer.PUZZLE_INVALID_AFTER_DAYS - 1; */
	public static final int PUZZLE_REQUEST_COUNT = 16;
	public static final int PUZZLE_POOL_SIZE = 128;
	
	/* FIXME: Display a random puzzle of an identity in the UI instead of always the first one! Otherwise we should really have each identity only
	 * insert one puzzle per day. But it might be a good idea to allow many puzzles per identity: Our seed identity could be configured to
	 * insert a very large amount of puzzles and therefore help the WoT while it is small */
	
	/* How many puzzles do we download from a single identity? */
	public static final int MAX_PUZZLES_PER_IDENTITY = 3;
	
	private static final int MINIMUM_SCORE_FOR_PUZZLE_DOWNLOAD = 30; /* FIXME: tweak before release */
	private static final int MINIMUM_SCORE_FOR_PUZZLE_DISPLAY = 30; /* FIXME: tweak before release */

	
	/* Objects from the node */

	/** A reference the HighLevelSimpleClient used to perform inserts */
	private HighLevelSimpleClient mClient;
	
	/** The TempBucketFactory used to create buckets from puzzles before insert */
	private final TempBucketFactory mTBF;
	
	
	/* Objects from WoT */
	
	private WoT mWoT;
	
	/** A reference to the database */
	private ObjectContainer db;
	
	/** Random number generator */
	private Random mRandom;
	
	
	/* Private objects */
	
	private Thread mThread;
	
	/** Used to tell the introduction server thread if it should stop */
	private volatile boolean isRunning = false;
	private volatile boolean shutdownFinished = false;
	
	/* FIXME FIXME FIXME: Use LRUQueue instead. ArrayBlockingQueue does not use a Hashset for contains()! */
	private final ArrayBlockingQueue<Identity> mIdentities = new ArrayBlockingQueue<Identity>(PUZZLE_POOL_SIZE); /* FIXME: figure out whether my assumption that this is just the right size is correct */
	
	private final HashSet<ClientGetter> mRequests = new HashSet<ClientGetter>(PUZZLE_REQUEST_COUNT * 2); /* TODO: profile & tweak */
	private final HashSet<BaseClientPutter> mInserts = new HashSet<BaseClientPutter>(PUZZLE_REQUEST_COUNT * 2);


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
	public IntroductionClient(WoT myWoT) {
		mWoT = myWoT;
		db = mWoT.getDB();
		mClient = mWoT.getClient();
		mTBF = mWoT.getTBF();
		mRandom = mWoT.getRandom();
		isRunning = true;
	}

	public void run() {
		Logger.debug(this, "Introduction client thread started.");
		
		mThread = Thread.currentThread();
		try {
			Thread.sleep(STARTUP_DELAY/2 + mRandom.nextInt(STARTUP_DELAY)); // Let the node start up
		}
		catch (InterruptedException e)
		{
			mThread.interrupt();
		}
		
		try {
		while(isRunning) {
			Thread.interrupted();
			Logger.debug(this, "Introduction client loop running...");
			
			synchronized(this) {
				IntroductionPuzzle.deleteExpiredPuzzles(db);
			}
			downloadPuzzles();
			insertSolutions();
			
			Logger.debug(this, "Introduction client loop finished.");
			
			try {
				Thread.sleep(THREAD_PERIOD/2 + mRandom.nextInt(THREAD_PERIOD));
			}
			catch (InterruptedException e)
			{
				mThread.interrupt();
				Logger.debug(this, "Introduction client loop interrupted.");
			}
		}
		}
		
		finally {
		cancelRequests();
		synchronized (this) {
			shutdownFinished = true;
			Logger.debug(this, "Introduction client thread finished.");
			notify();
		}
		}
	}

	public int getPriority() {
		return NativeThread.LOW_PRIORITY;
	}
	
	public void terminate() {
		Logger.debug(this, "Stopping the introduction client...");
		isRunning = false;
		mThread.interrupt();
		synchronized(this) {
			while(!shutdownFinished) {
				try {
					wait();
				}
				catch (InterruptedException e) {
					Thread.interrupted();
				}
			}
		}
		Logger.debug(this, "Stopped the introduction client.");
	}
	
	/**
	 * Use this function to get a list of puzzles for the user to solve.
	 * @param puzzleType
	 * @param id
	 * @param count
	 * @return
	 */
	public synchronized List<IntroductionPuzzle> getPuzzles(PuzzleType puzzleType, OwnIdentity id, int count) {
		Query q = db.query();
		q.constrain(IntroductionPuzzle.class);
		q.descend("mType").constrain(puzzleType);
		q.descend("mSolution").constrain(null).identity(); /* FIXME: toad said constrain(null) is maybe broken. If this is true: Alternative would be: q.descend("mIdentity").constrain(OwnIdentity.class).not(); */
		ObjectSet<IntroductionPuzzle> puzzles = q.execute();
		ArrayList<IntroductionPuzzle> result = new ArrayList<IntroductionPuzzle>(count);
		HashSet<Identity> resultHasPuzzleFrom = new HashSet<Identity>(count);
		
		for(IntroductionPuzzle p : puzzles) {
			try {
				int score = p.getInserter().getScore(id, db).getScore();
				/* TODO: Also check whether the database contains any solution of puzzles from the requester for the inserter of this puzzle */ 
				if(score > MINIMUM_SCORE_FOR_PUZZLE_DISPLAY && !resultHasPuzzleFrom.contains(p.getInserter())) { 
					try {
						p.getInserter().getGivenTrust(id, db);
					}
					catch(NotTrustedException e) {
						result.add(p);
						resultHasPuzzleFrom.add(p.getInserter());
						if(result.size() == count)
							break;
					}
				}
			}
			catch(NotInTrustTreeException e) {
			}
		}
		
		return result;
	}
	
	/**
	 * Use this function to store the solution of a puzzle and upload it.
	 * @param p
	 * @param solution
	 * @param solver
	 * @throws InvalidParameterException If the puzzle was already solved.
	 */
	public synchronized void solvePuzzle(IntroductionPuzzle p, String solution, OwnIdentity solver) throws InvalidParameterException {
		synchronized(p) {
			p.setSolved(solver, solution);
			p.store(db);
		}
		try {
			insertPuzzleSolution(p);
		}
		catch(Exception e) {
			Logger.error(this, "insertPuzzleSolution() failed.", e);
		}
	}
	
	/* Private functions */
	
	private void cancelRequests() {
		Logger.debug(this, "Trying to stop all requests & inserts");
		
		synchronized(mRequests) {
			Iterator<ClientGetter> r = mRequests.iterator();
			int rcounter = 0;
			while (r.hasNext()) { r.next().cancel(); r.remove(); ++rcounter; }
			Logger.debug(this, "Stopped " + rcounter + " current requests");
		}

		synchronized(mInserts) {
			Iterator<BaseClientPutter> i = mInserts.iterator();
			int icounter = 0;
			while (i.hasNext()) { i.next().cancel(); i.remove(); ++icounter; }
			Logger.debug(this, "Stopped " + icounter + " current inserts");
		}
	}
	
	private void removeRequest(ClientGetter g) {
		synchronized(mRequests) {
			//g.cancel(); /* FIXME: is this necessary? */
			mRequests.remove(g);
		}
		Logger.debug(this, "Removed request for " + g.getURI());
	}
	
	private void removeInsert(BaseClientPutter p) {
		synchronized(mInserts) {
			//p.cancel(); /* FIXME: is this necessary? */
			mInserts.remove(p);
			
		}
		Logger.debug(this, "Removed insert for " + p.getURI());
	}
	
	private synchronized void downloadPuzzles() {
		Query q = db.query();
		q.constrain(Identity.class);
		q.constrain(OwnIdentity.class).not();
		/* FIXME: As soon as identities announce that they were online every day, uncomment the following line */
		/* q.descend("lastChange").constrain(new Date(CurrentTimeUTC.getInMillis() - 1 * 24 * 60 * 60 * 1000)).greater(); */
		q.descend("lastChange").orderDescending(); /* This should choose identities in a sufficiently random order */
		ObjectSet<Identity> allIdentities = q.execute();
		ArrayList<Identity> identitiesToDownloadFrom = new ArrayList<Identity>(PUZZLE_POOL_SIZE);
		
		int counter = 0;
		/* Download puzzles from identities from which we have not downloaded for a certain period. This is ensured by
		 * keeping the last few hunderd identities stored in a FIFO with fixed length, named mIdentities. */
		synchronized(mIdentities) {
			for(Identity i : allIdentities) {
				/* TODO: Create a "boolean providesIntroduction" in Identity to use a database query instead of this */ 
				if(i.hasContext(IntroductionPuzzle.INTRODUCTION_CONTEXT) && !mIdentities.contains(i)
						&& i.getBestScore(db) > MINIMUM_SCORE_FOR_PUZZLE_DOWNLOAD)  {
					identitiesToDownloadFrom.add(i);
					++counter;
				}
	
				if(counter == PUZZLE_REQUEST_COUNT)
					break;
			}
		}
		
		/* If we run out of identities to download from, be less restrictive */
		if(counter == 0) {
			identitiesToDownloadFrom.clear();  /* We probably have less updated identities today than the size of the LRUQueue, empty it */

			for(Identity i : allIdentities) {
				/* TODO: Create a "boolean providesIntroduction" in Identity to use a database query instead of this */ 
				if(i.hasContext(IntroductionPuzzle.INTRODUCTION_CONTEXT) && i.getBestScore(db) > MINIMUM_SCORE_FOR_PUZZLE_DOWNLOAD)  {
					identitiesToDownloadFrom.add(i);
					++counter;
				}

				if(counter == PUZZLE_REQUEST_COUNT)
					break;
			}
		}

		
		/* I suppose its a good idea to restart downloading the puzzles from the latest updated identities every time the thread iterates
		 * This prevents denial of service because people will usually get very new puzzles. */
		cancelRequests();
		
		for(Identity i : identitiesToDownloadFrom) {
			try {
				downloadPuzzle(i);
			} catch (Exception e) {
				Logger.error(this, "Starting puzzle download failed.", e);
			}
		}
		
	}
	
	private synchronized void insertSolutions() {
		ObjectSet<IntroductionPuzzle> puzzles = IntroductionPuzzle.getSolvedPuzzles(db);
		
		for(IntroductionPuzzle p : puzzles) {
			try {
				/* FIXME: This function is called every time the IntroductionClient thread iterates. Do not always re-insert the solution */
				insertPuzzleSolution(p);
			}
			catch(Exception e) {
				Logger.error(this, "Inserting solution for " + p + " failed.");
			}
		}
	}
	
	private synchronized void insertPuzzleSolution(IntroductionPuzzle p) throws IOException, ParserConfigurationException, TransformerException, InsertException, InvalidParameterException {
		Bucket tempB = mTBF.makeBucket(10 * 1024); /* TODO: set to a reasonable value */
		OutputStream os = null;
		
		try {
			os = tempB.getOutputStream();
			p.getSolver().exportIntroductionToXML(os);
			os.close(); os = null;
			tempB.setReadOnly();

			ClientMetadata cmd = new ClientMetadata("text/xml");
			FreenetURI solutionURI = p.getSolutionURI(p.getSolution());
			InsertBlock ib = new InsertBlock(tempB, cmd, solutionURI);

			InsertContext ictx = mClient.getInsertContext(true);
			
			/* FIXME: are these parameters correct? */
			ClientPutter pu = mClient.insert(ib, false, null, false, ictx, this);
			//pu.setPriorityClass(RequestStarter.UPDATE_PRIORITY_CLASS); /* pluginmanager defaults to interactive priority */
			synchronized(mInserts) {
				mInserts.add(pu);
			}
			tempB = null;
			
			Logger.debug(this, "Started to insert puzzle solution of " + p.getSolver().getNickName() + " at " + solutionURI);
		}
		finally {
			if(tempB != null)
				tempB.free();
			Closer.close(os);
		}
	}
		
	/**
	 * Finds a random index of a puzzle from the inserter which we did not download yet and downloads it.
	 */
	private void downloadPuzzle(Identity inserter) throws FetchException {
		downloadPuzzle(inserter, mRandom.nextInt(getIdentityPuzzleUploadCount(inserter))); 
	}
	
	private void downloadPuzzle(Identity inserter, int index) throws FetchException {
		int inserterPuzzleCount = getIdentityPuzzleUploadCount(inserter);
		assert(index < inserterPuzzleCount+1);
		
		/* We do that so that onSuccess() can just call this function with the index increased by 1 */
		index %= inserterPuzzleCount;
		
		Date date = new Date();
		FreenetURI uri;
		
		/* Find a free index */
		int count = 0;
		while(IntroductionPuzzle.getByInserterDateIndex(db, inserter, date, index) != null && count < MAX_PUZZLES_PER_IDENTITY) {
			index = (index+1) % inserterPuzzleCount;
			++count;
		}
		
		/* TODO: Maybe also use the above loop which finds a free index for counting the recent puzzles accurately. */ 
		if(count >= MAX_PUZZLES_PER_IDENTITY || IntroductionPuzzle.getRecentByInserter(db, inserter).size() >= MAX_PUZZLES_PER_IDENTITY)	/* We have all puzzles of this identity */
			return;
		
		uri = IntroductionPuzzle.generateRequestURI(inserter, date, index);
		
		FetchContext fetchContext = mClient.getFetchContext();
		fetchContext.maxSplitfileBlockRetries = -1; // retry forever
		fetchContext.maxNonSplitfileRetries = -1; // retry forever
		ClientGetter g = mClient.fetch(uri, -1, this, this, fetchContext);
		//g.setPriorityClass(RequestStarter.UPDATE_PRIORITY_CLASS); /* pluginmanager defaults to interactive priority */
		synchronized(mRequests) {
			mRequests.add(g);
		}
		synchronized(mIdentities) {
			if(!mIdentities.contains(inserter)) {
				mIdentities.poll();	/* the oldest identity falls out of the FIFO and therefore puzzle downloads from that one are allowed again */
				try {
					mIdentities.put(inserter); /* put this identity at the beginning of the FIFO */
				} catch(InterruptedException e) {}
			}
		}
		Logger.debug(this, "Trying to fetch puzzle from " + uri.toString());
	}
	
	public int getIdentityPuzzleUploadCount(Identity i) {
		try {
			return Math.max(Integer.parseInt(i.getProp("IntroductionPuzzleCount")), 0);
		}
		catch(Exception e) {
			return IntroductionServer.PUZZLE_COUNT;
		}
	}

	/**
	 * Called when a puzzle is successfully fetched.
	 */
	public void onSuccess(FetchResult result, ClientGetter state) {
		Logger.debug(this, "Fetched puzzle: " + state.getURI());

		try {
			IntroductionPuzzle p = IntroductionPuzzle.importFromXML(db, result.asBucket().getInputStream(), state.getURI());
			if(IntroductionPuzzle.getByID(db, p.getID()) == null) {
				p.store(db);
				IntroductionPuzzle.deleteOldestPuzzles(db, PUZZLE_POOL_SIZE);
			}
			removeRequest(state);

			downloadPuzzle(p.getInserter(), p.getIndex() + 1); /* TODO: Also download a random index here maybe */
		} catch (Exception e) { 
			Logger.error(this, "Parsing failed for "+ state.getURI(), e);
		}
	}
	
	/**
	 * Called when the node can't fetch a file OR when there is a newer edition.
	 * In our case, called when there is no puzzle available.
	 */
	public void onFailure(FetchException e, ClientGetter state) {
		Logger.normal(this, "Downloading puzzle " + state.getURI() + " failed.", e);
		removeRequest(state);
	}

	/**
	 * Called when a puzzle solution is successfully inserted.
	 */
	public void onSuccess(BaseClientPutter state)
	{
		Logger.debug(this, "Successful insert of puzzle solution at " + state.getURI());
		removeInsert(state);
	}
	
	/**
	 * Calling when inserting a puzzle solution failed.
	 */
	public void onFailure(InsertException e, BaseClientPutter state)
	{
		Logger.debug(this, "Insert of puzzle solution failed for " + state.getURI(), e);
		removeInsert(state);
	}
	
	/* Not needed functions from the ClientCallback interface */

	/** Only called by inserts */
	public void onFetchable(BaseClientPutter state) {}

	/** Only called by inserts */
	public void onGeneratedURI(FreenetURI uri, BaseClientPutter state) {}

	/** Called when freenet.async thinks that the request should be serialized to
	 * disk, if it is a persistent request. */
	public void onMajorProgress() {}
}
