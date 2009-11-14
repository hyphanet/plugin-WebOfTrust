/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WoT.introduction;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

import javax.xml.transform.TransformerException;

import plugins.WoT.Identity;
import plugins.WoT.OwnIdentity;
import plugins.WoT.WoT;
import plugins.WoT.XMLTransformer;
import plugins.WoT.exceptions.InvalidParameterException;
import plugins.WoT.exceptions.NotInTrustTreeException;
import plugins.WoT.exceptions.NotTrustedException;
import plugins.WoT.exceptions.UnknownPuzzleException;
import plugins.WoT.introduction.IntroductionPuzzle.PuzzleType;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;

import freenet.client.FetchContext;
import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.InsertBlock;
import freenet.client.InsertContext;
import freenet.client.InsertException;
import freenet.client.async.BaseClientPutter;
import freenet.client.async.ClientGetter;
import freenet.client.async.ClientPutter;
import freenet.keys.FreenetURI;
import freenet.node.RequestStarter;
import freenet.support.CurrentTimeUTC;
import freenet.support.LRUQueue;
import freenet.support.Logger;
import freenet.support.TransferThread;
import freenet.support.api.Bucket;
import freenet.support.io.Closer;
import freenet.support.io.NativeThread;


/**
 * This class allows the user to announce new identities:
 * It downloads puzzles from known identites and uploads solutions of the puzzles.
 * 
 * For a summary of how introduction works please read the Javadoc of class IntroductionPuzzleStore.
 * 
 * @author xor (xor@freenetproject.org)
 */
public final class IntroductionClient extends TransferThread  {
	
	private static final int STARTUP_DELAY = 3 * 60 * 1000;
	private static final int THREAD_PERIOD = 1 * 60 * 60 * 1000; 
	
	/**
	 * A call to getPuzzles() wakes up the puzzle downloader thread to download new puzzles. This constant specifies the minimal delay between two wake-ups.
	 */
	private static final int MINIMAL_SLEEP_TIME = 10 * 60 * 1000;
	
	/* TODO: Maybe implement backward-downloading of puzzles, currently we only download puzzles of today.
	/* public static final byte PUZZLE_DOWNLOAD_BACKWARDS_DAYS = IntroductionServer.PUZZLE_INVALID_AFTER_DAYS - 1; */
	public static final int PUZZLE_REQUEST_COUNT = 10;
	
	/** How many unsolved puzzles do we try to accumulate? */
	public static final int PUZZLE_POOL_SIZE = 40;
	
	/** How many puzzles do we download from a single identity? */
	public static final int MAX_PUZZLES_PER_IDENTITY = 2;
	
	/**
	 * The minimal score (inclusive) which an identity must have in the trust-tree of any OwnIdentity for downloading/displaying it's puzzles.
	 * Not 0 because getBestScore() returns 0 if an identity is not in the trust tree of any OwnIdentity. This is the case for the seed identities
	 * if no own identity was created yet.
	 * */
	public static final int MINIMUM_SCORE_FOR_PUZZLE_DOWNLOAD = 1;
	
	/* Objects from WoT */
	
	private WoT mWoT;
	
	/** The container object which manages storage of the puzzles in the database, also used for synchronization */
	private IntroductionPuzzleStore mPuzzleStore;
	
	/** Random number generator */
	private Random mRandom;
	
	/* Private objects */
	
	private long mLastIterationTime = 0;
	
	/**
	 * List of identities of which we have downloaded puzzles from recently. It is maintained so that puzzles are always downloaded from multiple different
	 * identities instead of the always the same ones. 
	 */
	private final LRUQueue<String> mIdentities = new LRUQueue<String>(); // A suitable default size might be PUZZLE_POOL_SIZE + 1
	
	public static final int IDENTITIES_LRU_QUEUE_SIZE_LIMIT = 128;

	/**
	 * Creates an IntroductionClient
	 */
	public IntroductionClient(WoT myWoT) {
		super(myWoT.getPluginRespirator().getNode(), myWoT.getPluginRespirator().getHLSimpleClient(), "WoT Introduction Client");
		
		mWoT = myWoT;
		mPuzzleStore = mWoT.getIntroductionPuzzleStore();
		mRandom = mWoT.getPluginRespirator().getNode().fastWeakRandom;
	}
	
	@Override
	protected Collection<ClientGetter> createFetchStorage() {
		return new HashSet<ClientGetter>(PUZZLE_REQUEST_COUNT * 2); /* TODO: profile & tweak */
	}

	@Override
	protected Collection<BaseClientPutter> createInsertStorage() {
		return new HashSet<BaseClientPutter>(PUZZLE_REQUEST_COUNT * 2); /* TODO: profile & tweak */
	}

	public int getPriority() {
		return NativeThread.LOW_PRIORITY;
	}

	@Override
	protected long getStartupDelay() {
		return STARTUP_DELAY/2 + mRandom.nextInt(STARTUP_DELAY);
	}

	@Override
	protected long getSleepTime() {
		return THREAD_PERIOD/2 + mRandom.nextInt(THREAD_PERIOD);
	}

	/**
	 * Called by the superclass TransferThread after getStartupDelay() milliseconds and then after each getSleepTime() milliseconds.
	 * Deletes old puzzles, fetches new ones and inserts solutions of the user. 
	 * 
	 * Further, getPuzzles() also causes the execution of iterate() by calling nextIteration().
	 */
	@Override
	protected void iterate() {
		
		synchronized(this) {
			long time = CurrentTimeUTC.getInMillis();
			
			if((time - mLastIterationTime) <= MINIMAL_SLEEP_TIME)
				return;
			
			mLastIterationTime = time;
		}
		
		mPuzzleStore.deleteExpiredPuzzles();
		mPuzzleStore.deleteOldestUnsolvedPuzzles(PUZZLE_POOL_SIZE);
		downloadPuzzles();
		insertSolutions();
	}
	
	/**
	 * Use this function in the UI to get a list of puzzles for the user to solve.
	 * 
	 * The locking policy when using this function is that we do not lock anything while parsing the returned list - it's not a problem if a single 
	 * puzzle gets deleted while the user is solving it.
	 */
	public List<IntroductionPuzzle> getPuzzles(OwnIdentity user, PuzzleType puzzleType, int count) {
		ArrayList<IntroductionPuzzle> result = new ArrayList<IntroductionPuzzle>(count + 1);
		HashSet<Identity> resultHasPuzzleFrom = new HashSet<Identity>(count * 2); /* Have some room so we do not hit the load factor */
		
		/* Deadlocks could occur without the lock on WoT because the loop calls functions which lock the WoT - if something else started to
		 * execute (while we have already locked the puzzle store) which locks the WoT and waits for the puzzle store to become available
		 * until it releases the WoT. */
		synchronized(mWoT) {
		synchronized(mPuzzleStore) {
			List<IntroductionPuzzle> puzzles = mPuzzleStore.getUnsolvedPuzzles(puzzleType);
			 
			for(IntroductionPuzzle puzzle : puzzles) {
				try {
					/* TODO: Maybe also check whether the user has already solved puzzles of the identity which inserted this one */ 
					if(!resultHasPuzzleFrom.contains(puzzle.getInserter())) { 
						int score = mWoT.getScore(user, puzzle.getInserter()).getScore();
						
						if(score >= MINIMUM_SCORE_FOR_PUZZLE_DOWNLOAD) {
							try {
								mWoT.getTrust(puzzle.getInserter(), user);
								/* We are already on this identity's trust list so there is no use in solving another puzzle from it */
							}
							catch(NotTrustedException e) {
								result.add(puzzle);
								resultHasPuzzleFrom.add(puzzle.getInserter());
								if(result.size() == count)
									break;
							}
						}
					}
				}
				catch(NotInTrustTreeException e) {
					// We do not ask the users to solve puzzles from identities who he does not trust.
				}
			}
		}
		}
		
		nextIteration();
		
		return result;
	}
	
	/**
	 * Use this function to store the solution of a puzzle and upload it.
	 * @throws InvalidParameterException If the puzzle was already solved.
	 */
	public void solvePuzzle(OwnIdentity solver, IntroductionPuzzle puzzle, String solution) throws InvalidParameterException {
		synchronized(mPuzzleStore) {
		synchronized(puzzle) {
			puzzle.setSolved(solver, solution);
			mPuzzleStore.storeAndCommit(puzzle);
		}
		}
		try {
			insertPuzzleSolution(puzzle);
		}
		catch(Exception e) {
			Logger.error(this, "insertPuzzleSolution() failed.", e);
		}
	}

	private synchronized void downloadPuzzles() {
		/* Normally we would lock the whole WoT here because we iterate over a list returned by it. But because it is not a severe
		 * problem if we download a puzzle of an identity which has been deleted or so we do not do that. */
		ObjectSet<Identity> allIdentities = mWoT.getAllNonOwnIdentitiesSortedByModification();
		ArrayList<Identity> identitiesToDownloadFrom = new ArrayList<Identity>(PUZZLE_POOL_SIZE);
		
		/* Download puzzles from identities from which we have not downloaded for a certain period. This is ensured by
		 * keeping the last few hundred identities stored in a FIFO with fixed length, named mIdentities. */
		
		/* Normally we would have to lock the WoT here first so that no deadlock happens if something else locks the mIdentities and
		 * waits for the WoT until it unlocks them. BUT nothing else in this class locks mIdentities and then the WoT */
		synchronized(mIdentities) {
			for(Identity i : allIdentities) {
				/* TODO: Create a "boolean providesIntroduction" in Identity to use a database query instead of this */ 
				if(i.hasContext(IntroductionPuzzle.INTRODUCTION_CONTEXT) && !mIdentities.contains(i.getID()))  {
					try {
						if(mWoT.getBestScore(i) >= MINIMUM_SCORE_FOR_PUZZLE_DOWNLOAD)
							identitiesToDownloadFrom.add(i);
					}
					catch(NotInTrustTreeException e) { }
				}
	
				if(identitiesToDownloadFrom.size() == PUZZLE_REQUEST_COUNT)
					break;
			}
		}
		
		/* If we run out of identities to download from, flush the list of identities of which we have downloaded puzzles from */
		if(identitiesToDownloadFrom.size() == 0) {
			mIdentities.clear(); /* We probably have less updated identities today than the size of the LRUQueue, empty it */

			for(Identity i : allIdentities) {
				/* TODO: Create a "boolean providesIntroduction" in Identity to use a database query instead of this */ 
				if(i.hasContext(IntroductionPuzzle.INTRODUCTION_CONTEXT))  {
					try {
						if(mWoT.getBestScore(i) >= MINIMUM_SCORE_FOR_PUZZLE_DOWNLOAD)
							identitiesToDownloadFrom.add(i);
					}
					catch(NotInTrustTreeException e) { }
				}

				if(identitiesToDownloadFrom.size() == PUZZLE_REQUEST_COUNT)
					break;
			}
		}

		
		/* I suppose its a good idea to restart downloading the puzzles from the latest updated identities every time the thread iterates
		 * This prevents denial of service because people will usually get very new puzzles. */
		abortFetches();
		
		for(Identity i : identitiesToDownloadFrom) {
			try {
				downloadPuzzle(i);
			} catch (Exception e) {
				Logger.error(this, "Starting puzzle download failed.", e);
			}
		}
		
	}
	
	private void insertSolutions() {
		abortInserts();
		synchronized(mPuzzleStore) {
			ObjectSet<IntroductionPuzzle> puzzles = mPuzzleStore.getUninsertedSolvedPuzzles();
			
			for(IntroductionPuzzle p : puzzles) {
				try {
					insertPuzzleSolution(p);
				}
				catch(Exception e) {
					Logger.error(this, "Inserting solution for " + p + " failed.");
				}
			}
		}
	}
	
	/**
	 * Not synchronized because its caller is synchronized already.
	 */
	private void insertPuzzleSolution(IntroductionPuzzle puzzle) throws IOException, TransformerException, InsertException {
		assert(!puzzle.wasInserted());
		
		Bucket tempB = mTBF.makeBucket(1024); /* TODO: Set to a reasonable value */
		OutputStream os = null;
		
		try {
			os = tempB.getOutputStream();
			mWoT.getXMLTransformer().exportIntroduction(puzzle.getSolver(), os);
			os.close(); os = null;
			tempB.setReadOnly();

			FreenetURI solutionURI = puzzle.getSolutionURI();
			InsertBlock ib = new InsertBlock(tempB, null, solutionURI);

			InsertContext ictx = mClient.getInsertContext(true);
			
			ClientPutter pu = mClient.insert(ib, false, null, false, ictx, this);
			pu.setPriorityClass(RequestStarter.UPDATE_PRIORITY_CLASS, mClientContext, null);
			addInsert(pu);
			tempB = null;
			
			Logger.debug(this, "Started to insert puzzle solution of " + puzzle.getSolver().getNickname() + " at " + solutionURI);
		}
		finally {
			Closer.close(os);
			Closer.close(tempB);
		}
	}
		
	/**
	 * Finds a random index of a puzzle from the inserter which we did not download yet and downloads it.
	 */
	private synchronized void downloadPuzzle(Identity inserter) throws FetchException {
		downloadPuzzle(inserter, mRandom.nextInt(IntroductionServer.getIdentityPuzzleCount(inserter))); 
	}
	
	/**
	 * Not synchronized because its caller is synchronized already.
	 */
	private void downloadPuzzle(Identity inserter, int index) throws FetchException {
		int inserterPuzzleCount = IntroductionServer.getIdentityPuzzleCount(inserter);
		assert(index < inserterPuzzleCount+1);
		
		/* We do that so that onSuccess() can just call this function with the index increased by 1 */
		index %= inserterPuzzleCount;
		
		Date date = CurrentTimeUTC.get();
		FreenetURI uri;
		
		synchronized(mPuzzleStore) {
			if(mPuzzleStore.getOfTodayByInserter(inserter).size() >= MAX_PUZZLES_PER_IDENTITY)
				return;
			
			/* Find a free index */
			int count = 0;
			while(count < inserterPuzzleCount) {
				try {
					mPuzzleStore.getByInserterDateIndex(inserter, date, index);
					index = (index+1) % inserterPuzzleCount;
					++count;
				} catch(UnknownPuzzleException e) {
					break; // We found a free slot!
				}
			}
			
			/* TODO: Maybe also use the above loop which finds a free index for counting the recent puzzles accurately. */ 
			if(count >= MAX_PUZZLES_PER_IDENTITY) {	/* We have all puzzles of this identity */
				Logger.error(this, "SHOULD NOT HAPPEN: getOfTodayByInserter() returned less puzzles than getByInerterDayIndex().");
				return;
			}
		}
		
		/* Attention: Do not lock the WoT here before locking mIdentities because there is another synchronized(mIdentities) in this class
		 * which locks the WoT inside the mIdentities-lock */
		synchronized(mIdentities) {
			// mIdentities contains up to IDENTITIES_LRU_QUEUE_SIZE_LIMIT identities of which we have recently downloaded puzzles. This queue is used to ensure
			// that we download puzzles from different identities and not always from the same ones. 
			// The oldest identity falls out of the LRUQueue if it has reached it size limit and therefore puzzle downloads from that one are allowed again.
			// It is only checked in downloadPuzzles() whether puzzle downloads are allowed because we DO download multiple puzzles per identity, up to the limit
			// of MAX_PUZZLES_PER_IDENTITY - the onSuccess() starts download of the next one by calling this function here usually.
				
			if(mIdentities.size() >= IDENTITIES_LRU_QUEUE_SIZE_LIMIT) {
				// We do not call pop() now already because if the given identity is already in the pipeline then downloading a puzzle from it should NOT cause
				// a different identity to fall out - the given identity should be moved to the top and the others should stay in the pipeline. Therefore we
				// do a contains() check... 
				if(!mIdentities.contains(inserter.getID())) {
					mIdentities.pop();
				}
			}
			
			mIdentities.push(inserter.getID()); // put this identity at the beginning of the LRUQueue
		}
		
		uri = IntroductionPuzzle.generateRequestURI(inserter, date, index);		
		FetchContext fetchContext = mClient.getFetchContext();
		fetchContext.maxSplitfileBlockRetries = 2; /* 3 and above or -1 = cooldown queue. -1 is infinite */
		fetchContext.maxNonSplitfileRetries = 2;
		ClientGetter g = mClient.fetch(uri, XMLTransformer.MAX_INTRODUCTIONPUZZLE_BYTE_SIZE, mWoT.getRequestClient(), this, fetchContext);
		g.setPriorityClass(RequestStarter.UPDATE_PRIORITY_CLASS, mClientContext, null);
		addFetch(g);
		
		// Not necessary because it's not a HashSet but a fixed-length queue so the identity will get removed sometime anyway.
		//catch(RuntimeException e) {
		//	mIdentities.removeKey(identity);
		//}
		
		Logger.debug(this, "Trying to fetch puzzle from " + uri.toString());
	}

	/**
	 * Called when a puzzle is successfully fetched.
	 */
	public void onSuccess(FetchResult result, ClientGetter state, ObjectContainer container) {
		Logger.debug(this, "Fetched puzzle: " + state.getURI());
		
		Bucket bucket = null;
		InputStream inputStream = null;
		
		try {
			bucket = result.asBucket();
			inputStream = bucket.getInputStream();
			
			IntroductionPuzzle puzzle = mWoT.getXMLTransformer().importIntroductionPuzzle(state.getURI(), inputStream);
			downloadPuzzle(puzzle.getInserter());
		}
		catch (Exception e) { 
			Logger.error(this, "Parsing failed for "+ state.getURI(), e);
		}
		finally {
			Closer.close(inputStream);
			Closer.close(bucket);
			removeFetch(state);
		}
	}
	
	/**
	 * Called when the node can't fetch a file OR when there is a newer edition.
	 * In our case, called when there is no puzzle available.
	 */
	public void onFailure(FetchException e, ClientGetter state, ObjectContainer container) {
		try {
			if(e.getMode() == FetchException.CANCELLED) {
				Logger.debug(this, "Fetch cancelled: " + state.getURI());
			}
			else if(e.getMode() == FetchException.DATA_NOT_FOUND) {
				/* This is the normal case: There is no puzzle available of today because the inserter is offline and has not inserted any.
				 * We do nothing here. The identity stays in the FIFO though so we do not try to fetch puzzzle from it again soon. */
			}
			else {
				Logger.debug(this, "Downloading puzzle failed: " + state.getURI(), e);
			}
		}
		finally {
			removeFetch(state);
		}
	}

	/**
	 * Called when a puzzle solution is successfully inserted.
	 */
	public void onSuccess(BaseClientPutter state, ObjectContainer container)
	{
		Logger.debug(this, "Successful insert of puzzle solution: " + state.getURI());
		
		try {
			synchronized(mWoT) { /* getPuzzleByRequestURI requires this */
			synchronized(mPuzzleStore) {
				IntroductionPuzzle puzzle = mPuzzleStore.getPuzzleBySolutionURI(state.getURI());
				puzzle.setInserted();
				mPuzzleStore.storeAndCommit(puzzle);
			}
			}
		}
		catch(Exception e) {
			Logger.error(this, "Error", e);
		}
		finally {
			removeInsert(state);
		}
	}
	
	/**
	 * Calling when inserting a puzzle solution failed.
	 */
	public void onFailure(InsertException e, BaseClientPutter state, ObjectContainer container)
	{
		/* No synchronization because the worst thing which can happen is that we insert it again */
		
		try {
			if(e.getMode() == InsertException.CANCELLED)
				Logger.debug(this, "Insert cancelled: " + state.getURI());
			else
				Logger.minor(this, "Insert of puzzle solution failed: " + state.getURI(), e);
		}
		finally {
			removeInsert(state);
		}
	}
	
	/* Not needed functions from the ClientCallback interface */

	/** Only called by inserts */
	public void onFetchable(BaseClientPutter state, ObjectContainer container) {}

	/** Only called by inserts */
	public void onGeneratedURI(FreenetURI uri, BaseClientPutter state, ObjectContainer container) {}

	/** Called when freenet.async thinks that the request should be serialized to
	 * disk, if it is a persistent request. */
	public void onMajorProgress(ObjectContainer container) {}


}
