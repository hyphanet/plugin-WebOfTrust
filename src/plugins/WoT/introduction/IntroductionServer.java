/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WoT.introduction;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Random;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;

import plugins.WoT.Identity;
import plugins.WoT.IdentityFetcher;
import plugins.WoT.OwnIdentity;
import plugins.WoT.WoT;
import plugins.WoT.XMLTransformer;
import plugins.WoT.exceptions.InvalidParameterException;
import plugins.WoT.introduction.captcha.CaptchaFactory1;

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
import freenet.support.Logger;
import freenet.support.TransferThread;
import freenet.support.api.Bucket;
import freenet.support.io.Closer;
import freenet.support.io.NativeThread;

/**
 * This class provides identity announcement for new identities; It uploads puzzles in certain time intervals and checks whether they
 * were solved.
 * 
 * For a summary of how introduction works please read the Javadoc of class IntroductionPuzzleStore.
 * 
 * @author xor (xor@freenetproject.org)
 */
public final class IntroductionServer extends TransferThread {
	
	private static final int STARTUP_DELAY = 1 * 60 * 1000; /* FIXME: tweak before release */
	private static final int THREAD_PERIOD = 10 * 60 * 1000; /* FIXME: tweak before release */

	/** The name of the property we use to announce in identities how many puzzles they insert */
	public static final String PUZZLE_COUNT_PROPERTY = "IntroductionPuzzleCount";
	public static final int SEED_IDENTITY_PUZZLE_COUNT = 100;
	public static final int DEFAULT_PUZZLE_COUNT = 10; /* FIXME: tweak before release */
	public static final byte PUZZLE_INVALID_AFTER_DAYS = 3;		
	
	
	/* Objects from WoT */

	private WoT mWoT;
	
	/** The container object which manages storage of the puzzles in the database, also used for synchronization */
	private IntroductionPuzzleStore mPuzzleStore;
	
	
	/* Objects from the node */
	
	/** Random number generator */
	private final Random mRandom;
	
	
	/* Private objects */
	
	private static final IntroductionPuzzleFactory[] mPuzzleFactories = new IntroductionPuzzleFactory[] { new CaptchaFactory1() };
	
	 

	/**
	 * Creates an IntroductionServer
	 */
	public IntroductionServer(WoT myWoT, IdentityFetcher myFetcher) {
		super(myWoT.getPluginRespirator().getNode(), myWoT.getPluginRespirator().getHLSimpleClient(), "WoT Introduction Server");
		
		mWoT = myWoT;
		mPuzzleStore = mWoT.getIntroductionPuzzleStore();
		mRandom = mWoT.getPluginRespirator().getNode().fastWeakRandom;
		start();
	}
	
	public static int getIdentityPuzzleCount(Identity i) {
		try {
			return Math.max(Integer.parseInt(i.getProperty(IntroductionServer.PUZZLE_COUNT_PROPERTY)), 0);
		}
		catch(InvalidParameterException e) {
			return 0;
		}
	}
	
	@Override
	protected Collection<ClientGetter> createFetchStorage() {
		return new ArrayList<ClientGetter>(DEFAULT_PUZZLE_COUNT * 5 + 1); /* Just assume that there are 5 identities */
	}

	@Override
	protected Collection<BaseClientPutter> createInsertStorage() {
		return new ArrayList<BaseClientPutter>(DEFAULT_PUZZLE_COUNT * 5 + 1); /* Just assume that there are 5 identities */
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
	 * Deletes old puzzles, downloads solutions of existing ones and inserts new ones.
	 */
	@Override
	protected void iterate() {
		mPuzzleStore.deleteExpiredPuzzles();

		synchronized(mWoT) {
			/* TODO: We might want to not lock all the time during captcha creation... figure out how long this takes ... */
			
			for(OwnIdentity identity : mWoT.getAllOwnIdentities()) {
				if(identity.hasContext(IntroductionPuzzle.INTRODUCTION_CONTEXT)) {
					try {
						Logger.debug(this, "Managing puzzles of " + identity.getNickname());
						downloadSolutions(identity);
						generateNewPuzzles(identity);
						insertPuzzles(identity);
						Logger.debug(this, "Managing puzzles finished.");
					} catch (Exception e) {
						Logger.error(this, "Puzzle management failed for " + identity.getNickname(), e);
					}
				}
			}
		}
	}

	/* Primary worker functions */
		

	/**
	 * Not synchronized because its caller is synchronized.
	 */
	private void downloadSolutions(OwnIdentity inserter) throws FetchException {
		/* TODO: We restart all requests in every iteration. Decide whether this makes sense or not, if not add code to re-use requests for
		 * puzzles which still exist.
		 * I think it makes sense to restart them because there are not many puzzles and therefore not many requests. */
		abortFetches();
		
		synchronized(mPuzzleStore) {
			ObjectSet<OwnIntroductionPuzzle> puzzles = mPuzzleStore.getUnsolvedByInserter(inserter);
			Logger.debug(this, "Identity " + inserter.getNickname() + " has " + puzzles.size() + " unsolved puzzles stored. " + 
					"Trying to fetch solutions ...");
			
			for(OwnIntroductionPuzzle p : puzzles) {
				FetchContext fetchContext = mClient.getFetchContext();
				/* FIXME: Toad: Are these parameters correct? */
				fetchContext.maxSplitfileBlockRetries = -1; // retry forever
				fetchContext.maxNonSplitfileRetries = -1; // retry forever
				ClientGetter g = mClient.fetch(p.getSolutionURI(), XMLTransformer.MAX_INTRODUCTION_BYTE_SIZE, mWoT.getRequestClient(),
						this, fetchContext);
				// FIXME: Set to a reasonable value before release, PluginManager default is interactive priority
				// g.setPriorityClass(RequestStarter.UPDATE_PRIORITY_CLASS, mWoT.getPluginRespirator().getNode().clientCore.clientContext, null); 
				addFetch(g);
				Logger.debug(this, "Trying to fetch captcha solution for " + p.getRequestURI() + " at " + p.getSolutionURI().toString());
			}
		}
	}
	
	/**
	 * Not synchronized because its caller is synchronized.
	 */
	private void generateNewPuzzles(OwnIdentity identity) throws IOException {
		int puzzlesToGenerate = getIdentityPuzzleCount(identity) - mPuzzleStore.getOfTodayByInserter(identity).size();
		Logger.debug(this, "Trying to generate " + puzzlesToGenerate + " new puzzles from " + identity.getNickname());
		
		while(puzzlesToGenerate > 0) {
			mPuzzleFactories[mRandom.nextInt(mPuzzleFactories.length)].generatePuzzle(mPuzzleStore, identity);
			--puzzlesToGenerate;
		}
		
		Logger.debug(this, "Finished generating puzzles from " + identity.getNickname());
	}
	
	/**
	 * Not synchronized because its caller is synchronized.
	 */
	private void insertPuzzles(OwnIdentity identity) throws IOException, InsertException {
		synchronized(mPuzzleStore) {
			ObjectSet<OwnIntroductionPuzzle> puzzles = mPuzzleStore.getUninsertedOwnPuzzlesByInserter(identity); 
			Logger.debug(this, "Trying to insert " + puzzles.size() + " puzzles from " + identity.getNickname());
			for(OwnIntroductionPuzzle p : puzzles) {
				try {
					insertPuzzle(p);
				}
				catch(Exception e) {
					Logger.error(this, "Puzzle insert failed.", e);
				}
			}
			Logger.debug(this, "Finished inserting puzzles from " + identity.getNickname());
		}
	}
	
	/* "Slave" functions" */
	
	/**
	 * Not synchronized because it's caller is synchronized already.
	 */
	private void insertPuzzle(OwnIntroductionPuzzle puzzle)
		throws IOException, InsertException, TransformerException, ParserConfigurationException  {
		
		assert(!puzzle.wasInserted());
		
		Bucket tempB = mTBF.makeBucket(XMLTransformer.MAX_INTRODUCTIONPUZZLE_BYTE_SIZE);
		OutputStream os = null;
		
		try {
			os = tempB.getOutputStream();
			mWoT.getXMLTransformer().exportIntroductionPuzzle(puzzle, os); /* Provides synchronization on the puzzle */
			os.close(); os = null;
			tempB.setReadOnly();

			/* FIXME: Toad: Are these parameters correct? */
			InsertBlock ib = new InsertBlock(tempB, null, puzzle.getInsertURI());
			InsertContext ictx = mClient.getInsertContext(true);

			ClientPutter pu = mClient.insert(ib, false, null, false, ictx, this);
			// FIXME: Set to a reasonable value before release, PluginManager default is interactive priority
			// pu.setPriorityClass(RequestStarter.UPDATE_PRIORITY_CLASS, mWoT.getPluginRespirator().getNode().clientCore.clientContext ,null);
			addInsert(pu);
			tempB = null;

			Logger.debug(this, "Started insert of puzzle from " + puzzle.getInserter().getNickname());
		}
		finally {
			if(tempB != null)
				tempB.free();
			Closer.close(os);
		}
	}
	
	/** 
	 * Called when a puzzle was successfully inserted.
	 * 
	 * Synchronized because it locks the puzzle store and the WoT which is also done by the iterate() function, deadlocks could 
	 * happen if we did not synchronize this one.
	 */
	public synchronized void onSuccess(BaseClientPutter state, ObjectContainer container)
	{
		Logger.debug(this, "Successful insert of puzzle: " + state.getURI());
		
		try {
			synchronized(mPuzzleStore) {
				OwnIntroductionPuzzle puzzle = mPuzzleStore.getOwnPuzzleByRequestURI(state.getURI()); /* Be careful: This locks the WoT! */
				puzzle.setInserted();
				mPuzzleStore.storeAndCommit(puzzle);
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
	 * Called when the insertion of a puzzle failed.
	 * 
	 */
	public synchronized void onFailure(InsertException e, BaseClientPutter state, ObjectContainer container) 
	{
		if(e.getMode() == InsertException.CANCELLED) {
			Logger.debug(this, "Insert cancelled: " + state.getURI());
			return;
		}

		try {
			Logger.error(this, "Insert of puzzle failed: " + state.getURI(), e);
		}
		finally {
			removeInsert(state);
		}
	}

	/**
	 * Called when a puzzle solution is successfully fetched. We then add the identity which solved the puzzle.
	 * 
	 * Synchronized because it locks the puzzle store and the WoT which is also done by the iterate() function, deadlocks could 
	 * happen if we did not synchronize this one.
	 */
	public synchronized void onSuccess(FetchResult result, ClientGetter state, ObjectContainer container) {
		Logger.debug(this, "Fetched puzzle solution: " + state.getURI());

		try {
			synchronized(mPuzzleStore) {
				OwnIntroductionPuzzle p = mPuzzleStore.getOwnPuzzleBySolutionURI(state.getURI());
				synchronized(p) {
					OwnIdentity puzzleOwner = (OwnIdentity)p.getInserter();
					Identity newIdentity = mWoT.getXMLTransformer().importIntroduction(puzzleOwner, result.asBucket().getInputStream());
					Logger.debug(this, "Imported identity introduction for identity " + newIdentity.getRequestURI() +
							" to the OwnIdentity " + puzzleOwner);
					p.setSolved();
					mPuzzleStore.storeAndCommit(p);
				}
			}
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
	 * In our case, called when there is no solution to a puzzle in the network.
	 * 
	 * Not synchronized because it does not lock anything and generally does nothing.
	 */
	public void onFailure(FetchException e, ClientGetter state, ObjectContainer container) {
		if(e.getMode() == FetchException.CANCELLED) {
			Logger.debug(this, "Fetch cancelled: " + state.getURI());
			return;
		}
		
		try {
			Logger.debug(this, "Downloading puzzle solution " + state.getURI() + " failed: ", e);
		}
		finally {
			removeFetch(state);
		}
	}

	/* Not needed functions from the ClientCallback interface */
	
	/** Only called by inserts */
	public void onFetchable(BaseClientPutter state, ObjectContainer container) {}

	/** Only called by inserts */
	public void onGeneratedURI(FreenetURI uri, BaseClientPutter state, ObjectContainer container) {}

	/** Called when freenet.async thinks that the request should be serialized to disk, if it is a persistent request. */
	public void onMajorProgress(ObjectContainer container) {}

}
