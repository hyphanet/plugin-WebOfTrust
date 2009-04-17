/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WoT.introduction;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;

import javax.xml.transform.TransformerException;

import plugins.WoT.Identity;
import plugins.WoT.OwnIdentity;
import plugins.WoT.WoT;
import plugins.WoT.XMLTransformer;
import plugins.WoT.exceptions.InvalidParameterException;
import plugins.WoT.exceptions.NotInTrustTreeException;
import plugins.WoT.exceptions.NotTrustedException;
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
import freenet.support.Logger;
import freenet.support.TransferThread;
import freenet.support.api.Bucket;
import freenet.support.io.Closer;
import freenet.support.io.NativeThread;


/**
 * This class allows the user to announce new identities:
 * It downloads puzzles from known identites and uploads solutions of the puzzles.+
 * 
 * For a summary of how introduction works please read the Javadoc of class IntroductionPuzzleStore.
 * 
 * @author xor (xor@freenetproject.org)
 */
public final class IntroductionClient extends TransferThread  {
	
	private static final int STARTUP_DELAY = 1 * 60 * 1000;
	private static final int THREAD_PERIOD = 30 * 60 * 1000; /* FIXME: tweak before release: */ 
	
	/* FIXME: Implement backwards-downloading. Currently, we only download puzzles from today. */
	/* public static final byte PUZZLE_DOWNLOAD_BACKWARDS_DAYS = IntroductionServer.PUZZLE_INVALID_AFTER_DAYS - 1; */
	public static final int PUZZLE_REQUEST_COUNT = 16;
	
	/** How many unsolved puzzles do we try to accumulate? */
	public static final int PUZZLE_POOL_SIZE = 128;
	
	/** How many puzzles do we download from a single identity? */
	public static final int MAX_PUZZLES_PER_IDENTITY = 3;
	
	private static final int MINIMUM_SCORE_FOR_PUZZLE_DOWNLOAD = 30; /* FIXME: tweak before release */
	private static final int MINIMUM_SCORE_FOR_PUZZLE_DISPLAY = 30; /* FIXME: tweak before release */
	
	/* Objects from WoT */
	
	private WoT mWoT;
	
	/** The container object which manages storage of the puzzles in the database, also used for synchronization */
	private IntroductionPuzzleStore mPuzzleStore;
	
	/** Random number generator */
	private Random mRandom;
	
	/* Private objects */
	
	/* FIXME FIXME FIXME: Use LRUQueue instead. ArrayBlockingQueue does not use a Hashset for contains()! */
	/* FIXME: figure out whether my assumption that this is just the right size is correct */
	private final ArrayBlockingQueue<Identity> mIdentities = new ArrayBlockingQueue<Identity>(PUZZLE_POOL_SIZE + 1);

	/**
	 * Creates an IntroductionClient
	 */
	public IntroductionClient(WoT myWoT) {
		super(myWoT.getPluginRespirator().getNode(), myWoT.getPluginRespirator().getHLSimpleClient(), "WoT Introduction Client");
		
		mWoT = myWoT;
		mPuzzleStore = mWoT.getIntroductionPuzzleStore();
		mRandom = mWoT.getPluginRespirator().getNode().fastWeakRandom;
		start();
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
	 */
	@Override
	protected void iterate() {
		mPuzzleStore.deleteExpiredPuzzles();
		downloadPuzzles();
		insertSolutions();
	}
	
	/**
	 * Use this function in the UI to get a list of puzzles for the user to solve.
	 * You have to synchronize on the IntroductionPuzzleStore around the usage of this function and the list it returns!
	 */
	public List<IntroductionPuzzle> getPuzzles(OwnIdentity user, PuzzleType puzzleType, int count) {
		synchronized(mPuzzleStore) {
			List<IntroductionPuzzle> puzzles = mPuzzleStore.getUnsolvedPuzzles(puzzleType);
			ArrayList<IntroductionPuzzle> result = new ArrayList<IntroductionPuzzle>(count + 1);
			HashSet<Identity> resultHasPuzzleFrom = new HashSet<Identity>(count * 2); /* Have some room so we do not hit the load factor */ 
			
			for(IntroductionPuzzle puzzle : puzzles) {
				try {
					/* TODO: Maybe also check whether the user has already solved puzzles of the identity which inserted this one */ 
					if(!resultHasPuzzleFrom.contains(puzzle.getInserter())) { 
						int score = mWoT.getScore(user, puzzle.getInserter()).getScore();
						
						if(score > MINIMUM_SCORE_FOR_PUZZLE_DISPLAY) {
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
					Logger.error(this, "WTF?", e);
				}
			}
			
			return result;
		}
	}
	
	/**
	 * Use this function to store the solution of a puzzle and upload it.
	 * @throws InvalidParameterException If the puzzle was already solved.
	 */
	public synchronized void solvePuzzle(OwnIdentity solver, IntroductionPuzzle puzzle, String solution) throws InvalidParameterException {
		synchronized(puzzle) {
			puzzle.setSolved(solver, solution);
			mPuzzleStore.storeAndCommit(puzzle);
		}
		try {
			insertPuzzleSolution(puzzle);
		}
		catch(Exception e) {
			Logger.error(this, "insertPuzzleSolution() failed.", e);
		}
	}

	private synchronized void downloadPuzzles() {
		ObjectSet<Identity> allIdentities = mWoT.getAllNonOwnIdentitiesSortedByModification(); 
		ArrayList<Identity> identitiesToDownloadFrom = new ArrayList<Identity>(PUZZLE_POOL_SIZE);
		
		int counter = 0;
		/* Download puzzles from identities from which we have not downloaded for a certain period. This is ensured by
		 * keeping the last few hunderd identities stored in a FIFO with fixed length, named mIdentities. */
		synchronized(mIdentities) {
			for(Identity i : allIdentities) {
				/* TODO: Create a "boolean providesIntroduction" in Identity to use a database query instead of this */ 
				if(i.hasContext(IntroductionPuzzle.INTRODUCTION_CONTEXT) && !mIdentities.contains(i)
						&& mWoT.getBestScore(i) > MINIMUM_SCORE_FOR_PUZZLE_DOWNLOAD)  {
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
				if(i.hasContext(IntroductionPuzzle.INTRODUCTION_CONTEXT) && mWoT.getBestScore(i) > MINIMUM_SCORE_FOR_PUZZLE_DOWNLOAD)  {
					identitiesToDownloadFrom.add(i);
					++counter;
				}

				if(counter == PUZZLE_REQUEST_COUNT)
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
	
	private synchronized void insertSolutions() {
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
			
			/* FIXME: Toad: Are these parameters correct? */
			ClientPutter pu = mClient.insert(ib, false, null, false, ictx, this);
			pu.setPriorityClass(RequestStarter.UPDATE_PRIORITY_CLASS, mWoT.getPluginRespirator().getNode().clientCore.clientContext, null);
			addInsert(pu);
			tempB = null;
			
			Logger.debug(this, "Started to insert puzzle solution of " + puzzle.getSolver().getNickname() + " at " + solutionURI);
		}
		finally {
			if(tempB != null)
				tempB.free();
			Closer.close(os);
		}
	}
		
	/**
	 * Finds a random index of a puzzle from the inserter which we did not download yet and downloads it.
	 * 
	 * Not synchronized because its caller is synchronized already.
	 */
	private void downloadPuzzle(Identity inserter) throws FetchException {
		downloadPuzzle(inserter, mRandom.nextInt(getIdentityPuzzleCount(inserter))); 
	}
	
	/**
	 * Not synchronized because its caller is synchronized already.
	 */
	private void downloadPuzzle(Identity inserter, int index) throws FetchException {
		int inserterPuzzleCount = getIdentityPuzzleCount(inserter);
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
			while(mPuzzleStore.getByInserterDateIndex(inserter, date, index) != null && count < inserterPuzzleCount) {
				index = (index+1) % inserterPuzzleCount;
				++count;
			}
			
			/* TODO: Maybe also use the above loop which finds a free index for counting the recent puzzles accurately. */ 
			if(count >= MAX_PUZZLES_PER_IDENTITY) {	/* We have all puzzles of this identity */
				Logger.error(this, "SHOULD NOT HAPPEN: getOfTodayByInserter() returned less puzzles than getByInerterDayIndex().");
				return;
			}
		}
		
		uri = IntroductionPuzzle.generateRequestURI(inserter, date, index);
		
		FetchContext fetchContext = mClient.getFetchContext();
		/* FIXME: Toad: Are these parameters correct? */
		fetchContext.maxSplitfileBlockRetries = -1; // retry forever
		fetchContext.maxNonSplitfileRetries = -1; // retry forever
		ClientGetter g = mClient.fetch(uri, XMLTransformer.MAX_INTRODUCTIONPUZZLE_BYTE_SIZE, mWoT.getRequestClient(), this, fetchContext);
		//g.setPriorityClass(RequestStarter.UPDATE_PRIORITY_CLASS); /* pluginmanager defaults to interactive priority */
		addFetch(g);
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
	
	/**
	 * Get the amount of puzzles a given identity inserts.
	 */
	public int getIdentityPuzzleCount(Identity i) {
		try {
			return Math.max(Integer.parseInt(i.getProperty("IntroductionPuzzleCount")), 0);
		}
		catch(InvalidParameterException e) {
			return 0;
		}
	}

	/**
	 * Called when a puzzle is successfully fetched.
	 * 
	 * Not synchronized because the worst thing which can happen is that we donwload it again.
	 */
	public void onSuccess(FetchResult result, ClientGetter state, ObjectContainer container) {
		Logger.debug(this, "Fetched puzzle: " + state.getURI());

		try {
			IntroductionPuzzle puzzle = mWoT.getXMLTransformer().importIntroductionPuzzle(state.getURI(), result.asBucket().getInputStream());
			/* FIXME: Add logic to call this only once for every few puzzles fetched not for every single one! */
			mPuzzleStore.deleteOldestUnsolvedPuzzles(PUZZLE_POOL_SIZE);
			
			downloadPuzzle(puzzle.getInserter(), puzzle.getIndex() + 1); /* TODO: Also download a random index here maybe */
		}
		catch (Exception e) { 
			Logger.error(this, "Parsing failed for "+ state.getURI(), e);
		}
		finally {
			removeFetch(state);
		}
	}
	
	/**
	 * Called when the node can't fetch a file OR when there is a newer edition.
	 * In our case, called when there is no puzzle available.
	 * 
	 * Not sychronized because it does nothing and removeFetch() is synchronized already.
	 */
	public void onFailure(FetchException e, ClientGetter state, ObjectContainer container) {
		try {
			Logger.debug(this, "Downloading puzzle " + state.getURI() + " failed.", e);
		}
		finally {
			removeFetch(state);
		}
	}

	/**
	 * Called when a puzzle solution is successfully inserted.
	 * 
	 * Synchronized so that we do not insert it again accidentally, so we can keep the assert(!puzzle.wasInserted()) in insertPuzzle().
	 */
	public synchronized void onSuccess(BaseClientPutter state, ObjectContainer container)
	{
		try {
			synchronized(mPuzzleStore) {
				IntroductionPuzzle puzzle = mPuzzleStore.getPuzzleByURI(state.getURI());
				puzzle.setInserted();
				mPuzzleStore.storeAndCommit(puzzle);
				Logger.debug(this, "Successful insert of puzzle solution at " + state.getURI());
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
	 * 
	 * Not synchronized because the worst thing which can happen is that we donwload it again.
	 */
	public void onFailure(InsertException e, BaseClientPutter state, ObjectContainer container)
	{
		try {
			Logger.minor(this, "Insert of puzzle solution failed for " + state.getURI(), e);
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
