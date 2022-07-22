/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.introduction;

import static java.util.concurrent.TimeUnit.HOURS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static plugins.WebOfTrust.Configuration.IS_UNIT_TEST;
import static plugins.WebOfTrust.util.MathUtil.toIntExact;

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

import plugins.WebOfTrust.Identity;
import plugins.WebOfTrust.OwnIdentity;
import plugins.WebOfTrust.Score;
import plugins.WebOfTrust.WebOfTrust;
import plugins.WebOfTrust.XMLTransformer;
import plugins.WebOfTrust.exceptions.InvalidParameterException;
import plugins.WebOfTrust.exceptions.NotInTrustTreeException;
import plugins.WebOfTrust.exceptions.NotTrustedException;
import plugins.WebOfTrust.exceptions.UnknownIdentityException;
import plugins.WebOfTrust.exceptions.UnknownPuzzleException;
import plugins.WebOfTrust.introduction.IntroductionPuzzle.PuzzleType;
import plugins.WebOfTrust.util.Daemon;
import plugins.WebOfTrust.util.IdentifierHashSet;
import plugins.WebOfTrust.util.TransferThread;

import com.db4o.ObjectSet;

import freenet.client.FetchContext;
import freenet.client.FetchException;
import freenet.client.FetchException.FetchExceptionMode;
import freenet.client.FetchResult;
import freenet.client.InsertBlock;
import freenet.client.InsertContext;
import freenet.client.InsertException;
import freenet.client.InsertException.InsertExceptionMode;
import freenet.client.async.BaseClientPutter;
import freenet.client.async.ClientContext;
import freenet.client.async.ClientGetter;
import freenet.client.async.ClientPutter;
import freenet.keys.FreenetURI;
import freenet.node.RequestClient;
import freenet.node.RequestStarter;
import freenet.support.CurrentTimeUTC;
import freenet.support.LRUQueue;
import freenet.support.Logger;
import freenet.support.api.Bucket;
import freenet.support.api.RandomAccessBucket;
import freenet.support.io.Closer;
import freenet.support.io.NativeThread;
import freenet.support.io.ResumeFailedException;


/**
 * This class allows the user to announce new identities:
 * It downloads puzzles from known identites and uploads solutions of the puzzles.
 * 
 * For a summary of how introduction works please read the Javadoc of class IntroductionPuzzleStore.
 * 
 * @author xor (xor@freenetproject.org)
 */
public final class IntroductionClient extends TransferThread implements Daemon {

	private static final int STARTUP_DELAY = toIntExact(MINUTES.toMillis(3));
	private static final int THREAD_PERIOD = toIntExact(HOURS.toMillis(1));

	/**
	 * A call to getPuzzles() wakes up the puzzle downloader thread to download new puzzles. This constant specifies the minimal delay between two wake-ups.
	 */
	private static final long MINIMAL_SLEEP_TIME
		= IS_UNIT_TEST ? SECONDS.toMillis(1)  : MINUTES.toMillis(10);

	/* TODO: Maybe implement backward-downloading of puzzles, currently we only download puzzles of today.
	/* public static final byte PUZZLE_DOWNLOAD_BACKWARDS_DAYS = IntroductionServer.PUZZLE_INVALID_AFTER_DAYS - 1; */
	
	/**
	 * The amount of concurrent puzzle requests to aim for.
	 */
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
	
	private final WebOfTrust mWoT;
	
	/** The container object which manages storage of the puzzles in the database, also used for synchronization */
	private final IntroductionPuzzleStore mPuzzleStore;
	
	/** Random number generator */
	private final Random mRandom;
	
	/* Private objects */
	
	private long mLastIterationTime = 0;
	
	/**
	 * List of identities of which we have downloaded puzzles from recently. It is maintained so that puzzles are always downloaded from multiple different
	 * identities instead of the always the same ones. 
	 */
	private final LRUQueue<String> mIdentities = new LRUQueue<String>(); // A suitable default size might be PUZZLE_POOL_SIZE + 1
	
	/** Key = {@link IntroductionPuzzle#getID()}
	 *        (or {@link IntroductionPuzzle#getIDFromSolutionURI(FreenetURI)}) */
	private HashSet<String> mBeingInsertedPuzzleSolutions = new HashSet<String>();
	
	public static final int IDENTITIES_LRU_QUEUE_SIZE_LIMIT = 512;
	
	/* These booleans are used for preventing the construction of log-strings if logging is disabled (for saving some cpu cycles) */
	
	private static transient volatile boolean logDEBUG = false;
	private static transient volatile boolean logMINOR = false;
	
	static {
		Logger.registerClass(IntroductionClient.class);
	}


	/**
	 * Creates an IntroductionClient
	 */
	public IntroductionClient(WebOfTrust myWoT) {
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

	@Override
	public int getPriority() {
		return NativeThread.LOW_PRIORITY;
	}

	/** @return {@link IntroductionPuzzleStore#getRequestClient()} */
    @Override public RequestClient getRequestClient() {
        return mPuzzleStore.getRequestClient();
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
		// iterate() is split in two parts:
		// 1) Stuff we do at every iteration because it is important.
		// 2) Stuff which we only do if 10 minutes have passed since the last iteration because it
		//    isn't important and may be slow.
		//    Especially downloadPuzzles() can be a very heavy database query.
		
		// We must check for solutions to insert at every iteration because:
		// - When the user views puzzles on the web interface that causes getPuzzles() to trigger
		//   an iteration - which may start the 10 minute delay.
		// - When the user then solves a puzzle solvePuzzle() will want to cause the solution to be
		//   inserted by triggering iterate(). So if insertSolutions() here was being blocked by the
		//   10 minute delay that would mean that the upload of puzzle solutions is always delayed
		//   by 10 minutes and thus introducing an Identity couldn't be faster than that.
		insertSolutions();
		
		// Now follows the 10 minute delay ...
		
	    // TODO: Performance: The synchronized(this) can likely be removed since TransferThread
	    // should never execute iterate() multiple times concurrently.
		synchronized(this) {
			long time = CurrentTimeUTC.getInMillis();
			long timeSinceLastIteration = (time - mLastIterationTime);
			
			if(timeSinceLastIteration < MINIMAL_SLEEP_TIME) {
				if(logMINOR) {
					Logger.minor(this,
						"iterate(): MINIMAL_SLEEP_TIME not expired, postponing unimportant tasks!");
				}
				nextIteration(MINIMAL_SLEEP_TIME - timeSinceLastIteration);
				return;
			}
			
			mLastIterationTime = time;
		}
		
		mPuzzleStore.deleteExpiredPuzzles();
		mPuzzleStore.deleteOldestUnsolvedPuzzles(PUZZLE_POOL_SIZE);
		downloadPuzzles();
	}
	
	private boolean puzzleStoreIsTooEmpty() {
		return mPuzzleStore.getNonOwnCaptchaAmount(false) <  PUZZLE_POOL_SIZE/2;
	}
	
	/**
	 * Use this function in the UI to get a list of puzzles for the user to solve.<br><br>
	 * 
	 * You do not have to lock any parts of the database while parsing the returned list:<br>
	 * This function returns clone()s of the {@link IntroductionPuzzle} objects, not the actual
	 * ones stored in the database.<br>
	 * TODO: Performance: Don't return clones and also maybe remove the internal synchronized()
	 * after this is fixed: https://bugs.freenetproject.org/view.php?id=6247
	 * 
	 * @param ownIdentityID The value of {@link OwnIdentity#getID()} of the {@link OwnIdentity}
	 *                      which will solve the returned puzzles.<br>
	 *                      Used for selecting the puzzles which are from an {@link Identity} which:
	 *                      <br>
	 *                      - has a good {@link Score} from the perspective of the
	 *                        {@link OwnIdentity}.<br>
	 *                      - does not already trust the {@link OwnIdentity} anyway.
	 * @throws UnknownIdentityException If there is no {@link OwnIdentity} matching the given
	 *                                  ownIdentityID.
	 */
	public List<IntroductionPuzzle> getPuzzles(
	        final String ownIdentityID, final PuzzleType puzzleType, final int count)
	            throws UnknownIdentityException {
	    
		final ArrayList<IntroductionPuzzle> result = new ArrayList<IntroductionPuzzle>(count + 1);
		
		/* Deadlocks could occur without the lock on WoT because the loop calls functions which lock the WoT - if something else started to
		 * execute (while we have already locked the puzzle store) which locks the WoT and waits for the puzzle store to become available
		 * until it releases the WoT. */
		synchronized(mWoT) {
		synchronized(mPuzzleStore) {
		    final OwnIdentity user = mWoT.getOwnIdentityByID(ownIdentityID);
			final ObjectSet<IntroductionPuzzle> puzzles = mPuzzleStore.getUnsolvedPuzzles(puzzleType);
			final IdentifierHashSet<Identity> resultHasPuzzleFrom
			    = new IdentifierHashSet<Identity>(count * 2 /* It will grow at 75% load -> Make it larger */);

			for(final IntroductionPuzzle puzzle : puzzles) {
				try {
					/* TODO: Maybe also check whether the user has already solved puzzles of the identity which inserted this one */ 
					if(!resultHasPuzzleFrom.contains(puzzle.getInserter())) { 
						final int score = mWoT.getScore(user, puzzle.getInserter()).getScore();
						
						if(score >= MINIMUM_SCORE_FOR_PUZZLE_DOWNLOAD) {
							try {
								mWoT.getTrust(puzzle.getInserter(), user);
								/* We are already on this identity's trust list so there is no use in solving another puzzle from it */
							}
							catch(NotTrustedException e) {
								result.add(puzzle.clone());
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
		
        // TODO: Performance: iterate() not only deals with downloading more puzzles but
        // also with inserts, deleting expired puzzles, etc. Instead we should have an
        // event-driven loop for each and only trigger the one for downloading
        // new puzzles here.
		nextIteration();
		
		return result;
	}
	
	/**
	 * Use this function to store the solution of a puzzle.
	 * It will start the upload of the solution after 10 seconds unless you call
	 * {@link #nextIteration()} to start the upload right away.
	 * No synchronization is needed when using this function.
	 * 
	 * @throws InvalidParameterException If the puzzle was already solved.
	 * @throws RuntimeException If the identity or the puzzle was deleted already.
	 *                          TODO: Code quality: Throw {@link UnknownIdentityException} and
	 *                          {@link UnknownPuzzleException} instead.
	 */
	public void solvePuzzle(
	        final String solverOwnIdentityID, final String puzzleID, final String solution)
	            throws InvalidParameterException {
	    
		synchronized(mWoT) {
            final OwnIdentity solver;
			try {
				solver = mWoT.getOwnIdentityByID(solverOwnIdentityID);
			} catch(UnknownIdentityException e) {
				throw new RuntimeException("Your own identity was deleted already.");
			}
		synchronized(mPuzzleStore) {
            final IntroductionPuzzle puzzle;
			try {
				puzzle = mPuzzleStore.getByID(puzzleID);
			} catch (UnknownPuzzleException e) {
				throw new RuntimeException("The solved puzzle was deleted already.");
			}
		synchronized(puzzle) {
			puzzle.setSolved(solver, solution);
			mPuzzleStore.storeAndCommit(puzzle);
		}
		}
		}
		
        // We may not call insertPuzzleSolution() directly here because the parent class
        // TransferThread requires that only iterate() creates new transfers. So we schedule
        // iterate() to be executed instead.
		// We do this with a 10 second delay instead of the default delay of 0 because the web
		// interface shows multiple puzzles to the user at once and does ask them to solve multiple
		// - which will result in the web interface calling solvePuzzle() multiple times in a loop.
		// As the output "Success!" HTML won't be sent until that loop is finished we don't want to
		// cause iterate() to delay it by taking the required locks in between.
		// So we delay iterate() by 10 seconds which should be more than enough to process a handful
		// of solvePuzzle() calls.
        // TODO: Performance: iterate() not only deals with inserting puzzle solutions but
        // also with downloading puzzles, deleting expired puzzles, etc. Instead we should have an
        // event-driven loop for each and only trigger the one for inserting solutions here.
        nextIteration(SECONDS.toMillis(10));
	}

	/**
	 * Starts more fetches for puzzles, up to a total amount of {@link PUZZLE_REQUEST_COUNT} running requests.
	 * Existing requests are not aborted.
	 */
	private synchronized void downloadPuzzles() {
		final int fetchCount = fetchCount();
		
		if(fetchCount >= PUZZLE_REQUEST_COUNT) { // Check before we do the expensive database query.
			if(logMINOR) Logger.minor(this, "Got " + fetchCount + "fetches, not fetching any more.");
			return;
		}
		
		/*
		 * We do not stop fetching new puzzles once the puzzle pool is full by purpose:
		 * We want the available puzzles to be as new as possible so there is a high chance of the inserter of them still being online.
		 * This decrease the latency of the solution arriving at the inserter and therefore speeds up introduction.
		 * (Notice: If the puzzle pool contains an amount of PUZZLE_POOL_SIZE puzzles already and new fetches finish,
		 * the oldest puzzles will be deleted automatically. So the pool won't grow beyond the size limit.)
		 */
		// if(mPuzzleStore.getNonOwnCaptchaAmount(false) >= PUZZLE_POOL_SIZE) return; 
		
		Logger.normal(this, "Trying to start more fetches, current amount: " + fetchCount);
		
		final int newRequestCount = PUZZLE_REQUEST_COUNT - fetchCount;
		
        // TODO: Performance: The synchronized() upon mWoT can maybe be removed after this is fixed:
        // https://bugs.freenetproject.org/view.php?id=6247
		synchronized(mWoT) {
		ObjectSet<Identity> allIdentities = mWoT.getAllNonOwnIdentitiesSortedByModification();
		
		final ArrayList<Identity> identitiesToDownloadFrom = new ArrayList<Identity>(PUZZLE_REQUEST_COUNT + 1);
		
		/* Download puzzles from identities from which we have not downloaded for a certain period. This is ensured by
		 * keeping the last few hundred identities stored in a FIFO with fixed length, named mIdentities. */
		
		for(final Identity i : allIdentities) {
		    /* TODO: Create a "boolean providesIntroduction" in Identity to use a database query
		     * instead of this */ 
		    if(i.hasContext(IntroductionPuzzle.INTRODUCTION_CONTEXT)
		            && !mIdentities.contains(i.getID()))  {
		        try {
		            if(mWoT.getBestScore(i) >= MINIMUM_SCORE_FOR_PUZZLE_DOWNLOAD)
		                identitiesToDownloadFrom.add(i);
		        }
		        catch(NotInTrustTreeException e) { }
		    }

		    if(identitiesToDownloadFrom.size() >= newRequestCount)
		        break;
		}
		
		/* If we run out of identities to download from, flush the list of identities of which we have downloaded puzzles from */
		// TODO: Code quality: This allows running multiple downloads for the same puzzle in
		// parallel!
		// It shouldn't be a big problem due to the current MINIMAL_SLEEP_TIME of 10 minutes and
		// because when a download succeeds we will check whether we already have the puzzle and
		// not store it twice in the database if we do. But it can result in confusing logging
		// such as:
		//     Parsing failed for SSK@...:
		//     java.lang.IllegalArgumentException: Puzzle with ID ... already exists!
		if(identitiesToDownloadFrom.size() == 0) {
			mIdentities.clear(); /* We probably have less updated identities today than the size of the LRUQueue, empty it */

			// TODO: Performance: Don't re-query this from the database once the issue which caused
			// this workaround is fixed: https://bugs.freenetproject.org/view.php?id=6646
			allIdentities = mWoT.getAllNonOwnIdentitiesSortedByModification();
			
			for(final Identity i : allIdentities) {
				/* TODO: Create a "boolean providesIntroduction" in Identity to use a database query instead of this */ 
				if(i.hasContext(IntroductionPuzzle.INTRODUCTION_CONTEXT))  {
					try {
						if(mWoT.getBestScore(i) >= MINIMUM_SCORE_FOR_PUZZLE_DOWNLOAD)
							identitiesToDownloadFrom.add(i);
					}
					catch(NotInTrustTreeException e) { }
				}

				if(identitiesToDownloadFrom.size() >= newRequestCount)
					break;
			}
		}
		
		for(Identity i : identitiesToDownloadFrom) {
			try {
				downloadPuzzle(i);
			} catch (Exception e) {
				Logger.error(this, "Starting puzzle download failed for " + i, e);
			}
		}
		} // synchronized(mWoT)
		
		Logger.normal(this, "Finished starting more fetches. Amount of fetches now: " + fetchCount());
	}
	
	/**
	 * Synchronized because insertPuzzleSolution synchronizes on this IntroductionClient and that lock must be
	 * taken <b>before</b> the mPuzzleStore-lock which this function also takes.
	 */
	private synchronized void insertSolutions() {
	    // TODO: Performance: The synchronized() upon mWoT can maybe be removed after this is fixed:
	    // https://bugs.freenetproject.org/view.php?id=6247
	    // (IntroductionPuzzle objects contain references to Identity objects, and mWoT is the
	    // synchronization domain of Identity objects)
	    synchronized(mWoT) {
		synchronized(mPuzzleStore) {
			final ObjectSet<IntroductionPuzzle> puzzles = mPuzzleStore.getUninsertedSolvedPuzzles();
			
			for(final IntroductionPuzzle p : puzzles) {
				try {
					insertPuzzleSolution(p);
				}
				catch(Exception e) {
					Logger.error(this, "Inserting solution for " + p + " failed.");
				}
			}
		}}
	}
	
	/**
	 * Checks whether the given puzzle is currently being inserted.
	 * If not, starts an insert for it and marks it as currently being inserted in the HashSet of this IntroductionClient.
	 * 
     * You must synchronize upon this IntroductionClient when calling this function.
	 */
	private void insertPuzzleSolution(final IntroductionPuzzle puzzle)
	        throws IOException, TransformerException, InsertException {
	    
		if(mBeingInsertedPuzzleSolutions.contains(puzzle.getID())) 
			return;
		
		assert(!puzzle.wasInserted());
		
		RandomAccessBucket tempB = null;
		OutputStream os = null;
		
		try {
			tempB = mTBF.makeBucket(XMLTransformer.MAX_INTRODUCTION_BYTE_SIZE + 1);
			os = tempB.getOutputStream();
			mWoT.getXMLTransformer().exportIntroduction((OwnIdentity)puzzle.getSolver(), os);
			os.close(); os = null;
			tempB.setReadOnly();

			final FreenetURI solutionURI = puzzle.getSolutionURI();
			final InsertBlock ib = new InsertBlock(tempB, null, solutionURI);

			final InsertContext ictx = mClient.getInsertContext(true);
			
			final ClientPutter pu = mClient.insert(
			    ib, null, false, ictx, this, RequestStarter.IMMEDIATE_SPLITFILE_PRIORITY_CLASS);
			addInsert(pu); // Takes care of mBeingInsertedPuzleSolutions for us.
			tempB = null;
			
			Logger.normal(this, "Started to insert puzzle solution of " + puzzle.getSolver().getNickname() + " at " + solutionURI);
		}
		finally {
			Closer.close(os);
			Closer.close(tempB);
		}
	}
		
	/**
	 * Finds a random index of a puzzle from the inserter which we did not download yet and downloads it.
	 * You must synchronize upon this IntroductionClient when calling this function.
	 */
	private void downloadPuzzle(final Identity inserter) throws FetchException {
		downloadPuzzle(inserter, mRandom.nextInt(IntroductionServer.getIdentityPuzzleCount(inserter))); 
	}
	
	/**
	 * You must synchronize upon this IntroductionClient when calling this function.
	 */
	private void downloadPuzzle(final Identity inserter, int index) throws FetchException {
		final int inserterPuzzleCount = IntroductionServer.getIdentityPuzzleCount(inserter);
		assert(index < inserterPuzzleCount+1);
		
		/* We do that so that onSuccess() can just call this function with the index increased by 1 */
		index %= inserterPuzzleCount;
		
		final Date currentDate = CurrentTimeUTC.get();
		
		synchronized(mPuzzleStore) {
			if(mPuzzleStore.getOfTodayByInserter(inserter).size() >= MAX_PUZZLES_PER_IDENTITY)
				return;
			
			/* Find a free index */
			int count = 0;
			while(count < inserterPuzzleCount) {
				try {
					mPuzzleStore.getByInserterDateIndex(inserter, currentDate, index);
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
		
		
		// mIdentities contains up to IDENTITIES_LRU_QUEUE_SIZE_LIMIT identities of which we have
		// recently downloaded puzzles. This queue is used to ensure that we download puzzles from
		// different identities and not always from the same ones. 
		// The oldest identity falls out of the LRUQueue if it has reached it size limit and
		// therefore puzzle downloads from that one are allowed again.
		// It is only checked in downloadPuzzles() whether puzzle downloads are allowed because we
		// DO download multiple puzzles per identity, up to the limit of MAX_PUZZLES_PER_IDENTITY
		// - the onSuccess() starts download of the next one by calling this function here usually.

		if(mIdentities.size() >= IDENTITIES_LRU_QUEUE_SIZE_LIMIT) {
		    // We do not call pop() now already because if the given identity is already in the
		    // pipeline then downloading a puzzle from it should NOT cause a different identity to
		    // fall out - the given identity should be moved to the top and the others should stay
		    // in the pipeline. Therefore we do a contains() check... 
		    if(!mIdentities.contains(inserter.getID())) {
		        mIdentities.pop();
		    }
		}

		mIdentities.push(inserter.getID()); // put this identity at the beginning of the LRUQueue
		
		
		final FreenetURI uri = IntroductionPuzzle.generateRequestURI(inserter, currentDate, index);		
		final FetchContext fetchContext = mClient.getFetchContext();
		fetchContext.maxArchiveLevels = 0; // Because archives can become huge and WOT does not use them, we should disallow them. See JavaDoc of the variable.
		// The retry-count does not include the first attempt. We only try once because we do not know whether that identity was online to insert puzzles today.
		fetchContext.maxSplitfileBlockRetries = 0;
		fetchContext.maxNonSplitfileRetries = 0;
		// TODO: Do not auto-raise the priority without any evidence that the user wants to solve puzzles.
		// The priority-raising if the puzzle store is too empty is a workaround for the poor fetch performance of fred.
		// IMHO puzzle fetches should always be at low priority: Freetalk tells you to solve puzzles *after* you have posted your first message.
		// New users are unlikely to post a message if no messages were downloaded yet so it does not make sense if the puzzle fetches run at higher priority
		// before any messages have been fetched.
		// But puzzle fetching was severely broken when I introduced the priority-raising on too empty - it is needed....
		// So the best solution would be probably the following:
		// Use the SubscriptionManager (its in its own branch currently) for allowing clients to subscribe to puzzles and only raise the priority if a client
		// is subscribed.
		final short fetchPriority = puzzleStoreIsTooEmpty() ? RequestStarter.IMMEDIATE_SPLITFILE_PRIORITY_CLASS : RequestStarter.UPDATE_PRIORITY_CLASS;
		final ClientGetter g = mClient.fetch(uri, XMLTransformer.MAX_INTRODUCTIONPUZZLE_BYTE_SIZE,
				this, fetchContext, fetchPriority);
		addFetch(g);
		
		// Not necessary because it's not a HashSet but a fixed-length queue so the identity will get removed sometime anyway.
		//catch(RuntimeException e) {
		//	mIdentities.removeKey(identity);
		//}
		
		Logger.normal(this, "Trying to fetch puzzle from " + uri.toString());
	}

	/**
	 * Called when a puzzle is successfully fetched.
	 */
	@Override
    public void onSuccess(final FetchResult result, final ClientGetter state) {
		Logger.normal(this, "Fetched puzzle: " + state.getURI());
		
		Bucket bucket = null;
		InputStream inputStream = null;
		
		try {
			bucket = result.asBucket();
			inputStream = bucket.getInputStream();
			
			mWoT.getXMLTransformer().importIntroductionPuzzle(state.getURI(), inputStream);
			// The parent class TransferThread forbids us to create transfers in onSuccess(), so
			// this had to be commented out.
			// Downloading a second puzzle from one inserter was more of heuristics than an actual
			// need anyway: Solving multiple puzzles of one identity is not useful, you can only
			// get into its trust list once. This was being done nevertheless in case someone wanted
			// to introduce multiple identities at once, but that is a pretty rare usecase IMHO.
			/* downloadPuzzle(puzzle.getInserter()); */
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
	@Override
    public void onFailure(final FetchException e, final ClientGetter state) {
		try {
			if(e.getMode() == FetchExceptionMode.CANCELLED) {
				if(logDEBUG) Logger.debug(this, "Fetch cancelled: " + state.getURI());
			}
			else if(e.isDNF()) {
				/* This is the normal case: There is no puzzle available of today because the inserter is offline and has not inserted any.
				 *  The identity stays in the FIFO though so we do not try to fetch puzzzle from it again soon.
				 *  If we do not have enough puzzles yet, we immediately try to start a new fetch. If we have enough puzzles, we just
				 *  wait for the next time-based iteration of the puzzle fetch loop to avoid wasting CPU cycles. */ 
				
				if(logMINOR)
					Logger.minor(this, "DataNotFound for puzzle: " + state.getURI());
				
				if(puzzleStoreIsTooEmpty()) {
				    // TODO: Performance: iterate() not only deals with downloading more puzzles but
				    // also with inserts, deleting expired puzzles, etc. Instead we should have an
				    // event-driven loop for each and only trigger the one for downloading
				    // new puzzles here.
					nextIteration();
				}
			} else if (e.isFatal()) {
				Logger.error(this, "Downloading puzzle failed: " + state.getURI(), e);
			} else
				Logger.warning(this, "Downloading puzzle failed, isFatal()==false: " + state.getURI(), e);
		}
		finally {
			removeFetch(state);
		}
	}
	
	/**
	 * Does not throw any Exceptions
	 */
	private final void markPuzzleSolutionAsInserted(BaseClientPutter state) {
		try {
			synchronized(mWoT) { /* getPuzzleByRequestURI requires this */
			synchronized(mPuzzleStore) {
				final IntroductionPuzzle puzzle = mPuzzleStore.getPuzzleBySolutionURI(((ClientPutter)state).getTargetURI());
				synchronized(puzzle) {
				puzzle.setInserted();
				mPuzzleStore.storeAndCommit(puzzle);
				}
			}
			}
		}
		catch(Exception e) {
			Logger.error(this, "Unable to mark puzzle solution as inserted", e);
		}
	}

	/**
	 * Called when a puzzle solution is successfully inserted.
	 */
	@Override
    public void onSuccess(final BaseClientPutter state)
	{
		Logger.normal(this, "Successful insert of puzzle solution: " + state.getURI());
		
		try {
			markPuzzleSolutionAsInserted(state);
		} finally {
			removeInsert(state); // Takes care of mBeingInsertedPuzleSolutions for us.
			Closer.close(((ClientPutter)state).getData());
		}
	}
	
	/**
	 * Calling when inserting a puzzle solution failed.
	 */
	@Override
    public void onFailure(final InsertException e, final BaseClientPutter state)
	{
		/* No synchronization because the worst thing which can happen is that we insert it again */
		
		try {
			if(e.getMode() == InsertExceptionMode.CANCELLED)
				if(logDEBUG) Logger.debug(this, "Insert cancelled: " + state.getURI());
			else if(e.getMode() == InsertExceptionMode.COLLISION) {
				Logger.normal(this, "Insert of puzzle solution collided, puzzle was solved already, marking as inserted: " + state.getURI());
				markPuzzleSolutionAsInserted(state);
			}
			else if(e.isFatal())
				Logger.error(this, "Insert of puzzle solution failed: " + state.getURI(), e);
			else
				Logger.warning(this, "Insert of puzzle solution failed, isFatal()==false: " + state.getURI(), e);
		}
		finally {
			removeInsert(state); // Takes care of mBeingInsertedPuzleSolutions for us.
			Closer.close(((ClientPutter)state).getData());
		}
	}
	
	/* Not needed functions from the ClientCallback interface */

	/** Only called by inserts */
	@Override
    public void onFetchable(BaseClientPutter state) {}

	/** Only called by inserts */
	@Override
    public void onGeneratedURI(FreenetURI uri, BaseClientPutter state) {}
	
	
	@Override
	protected synchronized void abortInserts() {
		super.abortInserts();
		// Needs to be done afterwards, otherwise removeInsert() complains about not finding the puzzles
		mBeingInsertedPuzzleSolutions = new HashSet<String>();
	}
	
	@Override
	protected synchronized void addInsert(BaseClientPutter p) {
		try {
			final FreenetURI uri = ((ClientPutter)p).getTargetURI();
			final String id = IntroductionPuzzle.getIDFromSolutionURI(uri);
			if(!mBeingInsertedPuzzleSolutions.add(id))
				throw new RuntimeException("Already in HashSet: uri: " + uri + "; id: " + id);
		} catch(RuntimeException e) { // Also for exceptions which might happen in getIDFromSolutionURI etc.
			Logger.error(this, "Unable to add puzzle ID to the list of running inserts.", e);
		} finally {
		    super.addInsert(p);
		}
	}
	
	@Override
	protected synchronized void removeInsert(BaseClientPutter p) {
		try {
			final FreenetURI uri = ((ClientPutter)p).getTargetURI();
			final String id = IntroductionPuzzle.getIDFromSolutionURI(uri);
			mBeingInsertedPuzzleSolutions.remove(id);
			// TODO: ClientPutter currently does the onFailure callback TWICE so this would always throw. Check whether it is fixed.
			//if(!mBeingInsertedPuzzleSolutions.remove(id))
			//	throw new RuntimeException("Not in HashSet: uri: " + uri + "; id: " + id);
		} catch(RuntimeException e) { // Also for exceptions which might happen in getIDFromSolutionURI etc.
			Logger.error(this, "Unable to remove puzzle ID from list of running inserts.", e);
		} finally {
		    super.removeInsert(p);
		}
	}

	@Override
	public void onGeneratedMetadata(Bucket metadata, BaseClientPutter state) {
		metadata.free();
		throw new UnsupportedOperationException();
	}

    /**
     * Should not be called since this class does not create persistent requests.<br>
     * Will throw an exception since the interface specification requires it to do some stuff,
     * which it does not do.<br>
     * Parent interface JavaDoc follows:<br><br>
     * {@inheritDoc}
     */
    @Override public void onResume(final ClientContext context) throws ResumeFailedException {
        final ResumeFailedException error = new ResumeFailedException(
            "onResume() called even though this class does not create persistent requests");
        Logger.error(this, error.getMessage(), error /* Add exception for logging stack trace */);
        throw error;
    }

}
