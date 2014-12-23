/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.introduction;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Random;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;

import plugins.WebOfTrust.Identity;
import plugins.WebOfTrust.IdentityFetcher;
import plugins.WebOfTrust.OwnIdentity;
import plugins.WebOfTrust.WebOfTrust;
import plugins.WebOfTrust.XMLTransformer;
import plugins.WebOfTrust.exceptions.InvalidParameterException;
import plugins.WebOfTrust.introduction.captcha.CaptchaFactory1;

import com.db4o.ObjectContainer;
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
import freenet.client.async.ClientGetter;
import freenet.client.async.ClientPutter;
import freenet.keys.FreenetURI;
import freenet.node.RequestClient;
import freenet.node.RequestStarter;
import freenet.support.Logger;
import freenet.support.TransferThread;
import freenet.support.api.Bucket;
import freenet.support.api.RandomAccessBucket;
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
	
	private static final int STARTUP_DELAY = 5 * 60 * 1000;
	private static final int THREAD_PERIOD = 60 * 60 * 1000;

	/** The name of the property we use to announce in identities how many puzzles they insert */
	public static final String PUZZLE_COUNT_PROPERTY = "IntroductionPuzzleCount";
	public static final int SEED_IDENTITY_PUZZLE_COUNT = 100;
	public static final int DEFAULT_PUZZLE_COUNT = 10;
	public static final byte PUZZLE_INVALID_AFTER_DAYS = 3;		
	
	
	/* Objects from WoT */

	private final WebOfTrust mWoT;
	
	/** The container object which manages storage of the puzzles in the database, also used for synchronization */
	private final IntroductionPuzzleStore mPuzzleStore;
	
	
	/* Objects from the node */
	
	/** Random number generator */
	private final Random mRandom;
	
	
	/* Private objects */
	
	/**
	 * The {@link IntroductionPuzzleFactory} objects which shall be used to generate puzzles.
	 */
	private static final IntroductionPuzzleFactory[] mPuzzleFactories = new IntroductionPuzzleFactory[] {
		new CaptchaFactory1()
		// ATTENTION: When adding new ones please also add them to IntroductionPuzzleStoreTest
	};
	
	/* These booleans are used for preventing the construction of log-strings if logging is disabled (for saving some cpu cycles) */
	
	private static transient volatile boolean logDEBUG = false;
	private static transient volatile boolean logMINOR = false;
	
	static {
		Logger.registerClass(IntroductionServer.class);
	}
	

	/**
	 * Creates an IntroductionServer
	 */
	public IntroductionServer(final WebOfTrust myWoT, final IdentityFetcher myFetcher) {
		super(myWoT.getPluginRespirator().getNode(), myWoT.getPluginRespirator().getHLSimpleClient(), "WoT Introduction Server");
		
		mWoT = myWoT;
		mPuzzleStore = mWoT.getIntroductionPuzzleStore();
		mRandom = mWoT.getPluginRespirator().getNode().fastWeakRandom;
	}
	
	public static int getIdentityPuzzleCount(final Identity i) {
		try {
			return Math.max(Integer.parseInt(i.getProperty(IntroductionServer.PUZZLE_COUNT_PROPERTY)), 0);
		}
		catch(InvalidParameterException e) { // Property does not exist
			// TODO: This is a workaround for bug 4552. Remove it and fix the underlying issue.
			// The workaround is valid: getIdentityPuzzleCount is usually called by the IntroductionClient when it tries to download puzzles from an identity
			// which has the introduction context. Having the introduction context means that this identity publishes puzzles so the absence of the puzzle
			// count problem is a bug - probably a db4o one.
			Logger.error(IntroductionServer.class, "getIdentitityPuzzleCount called even though identity has no puzzle count property, please check the XML: " + i.getRequestURI());
			return IntroductionServer.DEFAULT_PUZZLE_COUNT;
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

	@Override
	public int getPriority() {
		return NativeThread.LOW_PRIORITY;
	}

	/** {@inheritDoc} */
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
	 * Deletes old puzzles, downloads solutions of existing ones and inserts new ones.
	 */
	@Override
	protected void iterate() {
		mPuzzleStore.deleteExpiredPuzzles();

		/* TODO: We restart all requests in every iteration. Decide whether this makes sense or not, if not add code to re-use requests for
		 * puzzles which still exist.
		 * I think it makes sense to restart them because there are not many puzzles and therefore not many requests. */
		abortFetches();
		abortInserts();
		
		synchronized(mWoT) {
			/* TODO: We might want to not lock all the time during captcha creation... figure out how long this takes ... */
			
			for(final OwnIdentity identity : mWoT.getAllOwnIdentities()) {
				if(identity.hasContext(IntroductionPuzzle.INTRODUCTION_CONTEXT)) {
					try {
						Logger.normal(this, "Managing puzzles of " + identity.getNickname());
						downloadSolutions(identity);
						generateNewPuzzles(identity);
						insertPuzzles(identity);
						Logger.normal(this, "Managing puzzles finished.");
					} catch (Exception e) {
						Logger.error(this, "Puzzle management failed for " + identity.getNickname(), e);
					}
				}
			}
		}
	}

	/* Primary worker functions */
		

	private void downloadSolutions(final OwnIdentity inserter) throws FetchException {		
		synchronized(mPuzzleStore) {
			final ObjectSet<OwnIntroductionPuzzle> puzzles = mPuzzleStore.getUnsolvedByInserter(inserter);
			Logger.normal(this, "Identity " + inserter.getNickname() + " has " + puzzles.size() + " unsolved puzzles stored. " + 
					"Trying to fetch solutions ...");
			
			for(final OwnIntroductionPuzzle p : puzzles) {
				try {
				final FetchContext fetchContext = mClient.getFetchContext();
				fetchContext.maxArchiveLevels = 0; // Because archives can become huge and WOT does not use them, we should disallow them. See JavaDoc of the variable.
				// -1 means retry forever. Does make sense here: After 2 retries the fetches go into the cooldown queue, ULPRs are used. So if someone inserts
				// the puzzle solution during that, we might get to know it.
				fetchContext.maxSplitfileBlockRetries = -1;
				fetchContext.maxNonSplitfileRetries = -1;
				final ClientGetter g = mClient.fetch(
                    p.getSolutionURI(), XMLTransformer.MAX_INTRODUCTION_BYTE_SIZE,
						this, fetchContext, RequestStarter.UPDATE_PRIORITY_CLASS); 
				addFetch(g);
				if(logDEBUG) Logger.debug(this, "Trying to fetch captcha solution for " + p.getRequestURI() + " at " + p.getSolutionURI().toString());
				}
				catch(RuntimeException e) {
					Logger.error(this, "Error while trying to fetch captcha solution at " + p.getSolutionURI());
				}
			}
			
			Logger.normal(this, "Finishing with starting fetches for the puzzle solutions of " + inserter.getNickname());
		}
	}
	
	private void generateNewPuzzles(final OwnIdentity identity) throws IOException {
		synchronized(mPuzzleStore) {
		int puzzlesToGenerate = getIdentityPuzzleCount(identity) - mPuzzleStore.getOfTodayByInserter(identity).size();
		Logger.normal(this, "Trying to generate " + puzzlesToGenerate + " new puzzles from " + identity.getNickname());
		
		while(puzzlesToGenerate > 0) {
			try {
			final OwnIntroductionPuzzle p = mPuzzleFactories[mRandom.nextInt(mPuzzleFactories.length)].generatePuzzle(mPuzzleStore, identity);
			if(logDEBUG) Logger.debug(this, "Generated puzzle of " + p.getDateOfInsertion() + "; valid until " + p.getValidUntilDate());
			} catch(Exception e) {
				Logger.error(this, "Puzzle generation failed.", e);
			}
			--puzzlesToGenerate;
		}
		}
		
		Logger.normal(this, "Finished generating puzzles from " + identity.getNickname());
	}
	
	private void insertPuzzles(final OwnIdentity identity) throws IOException, InsertException {
		synchronized(mPuzzleStore) {
			// TODO: This query will also return puzzles which are currently being inserted! Mark them as being inserted somehow
			// Either have a hashtable here or wire it in to class IntroductionPuzzle.
			
			final ObjectSet<OwnIntroductionPuzzle> puzzles = mPuzzleStore.getUninsertedOwnPuzzlesByInserter(identity); 
			Logger.normal(this, "Trying to insert " + puzzles.size() + " puzzles from " + identity.getNickname());
			for(final OwnIntroductionPuzzle p : puzzles) {
				try {
					insertPuzzle(p);
				}
				catch(Exception e) {
					Logger.error(this, "Puzzle insert failed.", e);
				}
			}
			Logger.normal(this, "Finished inserting puzzles from " + identity.getNickname());
		}
	}
	
	/* "Slave" functions" */
	
	/**
	 * Not synchronized because it's caller is synchronized already.
	 */
	private void insertPuzzle(final OwnIntroductionPuzzle puzzle)
		throws IOException, InsertException, TransformerException, ParserConfigurationException  {
		
		assert(!puzzle.wasInserted());
		
		RandomAccessBucket tempB = mTBF.makeBucket(XMLTransformer.MAX_INTRODUCTIONPUZZLE_BYTE_SIZE + 1);
		OutputStream os = null;
		
		try {
			os = tempB.getOutputStream();
			mWoT.getXMLTransformer().exportIntroductionPuzzle(puzzle, os); /* Provides synchronization on the puzzle */
			Closer.close(os); os = null;
			tempB.setReadOnly();

			final InsertBlock ib = new InsertBlock(tempB, null, puzzle.getInsertURI());
			final InsertContext ictx = mClient.getInsertContext(true);
            // FIXME: Code quality: Check if this is the default, if yes, remove it.
			ictx.getCHKOnly = false;
			
			final ClientPutter pu = mClient.insert(
			    ib, null, false, ictx, this, RequestStarter.IMMEDIATE_SPLITFILE_PRIORITY_CLASS);
			addInsert(pu);
			tempB = null;

			if(logDEBUG) Logger.debug(this, "Started insert of puzzle:" + puzzle);
		}
		finally {
			Closer.close(os);
			Closer.close(tempB);
		}
	}
	
	/** 
	 * Called when a puzzle was successfully inserted.
	 */
	@Override
    public void onSuccess(final BaseClientPutter state)
	{
		if(logDEBUG) Logger.debug(this, "Successful insert of puzzle: " + state.getURI());
		
		try {
			synchronized(mWoT) {
			synchronized(mPuzzleStore) {
				final OwnIntroductionPuzzle puzzle = mPuzzleStore.getOwnPuzzleByRequestURI(state.getURI()); /* Be careful: This locks the WoT! */
				puzzle.setInserted();
				mPuzzleStore.storeAndCommit(puzzle);
			}
			}
		}
		catch(Exception e) {
			Logger.error(this, "Marking puzzle as inserted failed", e);
		}
		finally {
			removeInsert(state);
			Closer.close(((ClientPutter)state).getData());
		}
	}
	
	/**
	 * Called when the insertion of a puzzle failed.
	 */
	@Override
    public void onFailure(final InsertException e, final BaseClientPutter state) 
	{
		try {
			if(e.getMode() == InsertExceptionMode.CANCELLED) {
				if(logDEBUG) Logger.debug(this, "Insert cancelled: " + state.getURI());
			} else if(e.getMode() == InsertExceptionMode.COLLISION) {
				// TODO: Investigate why this happens.
				Logger.warning(this, "Insert of puzzle collided, marking as inserted: " + state.getURI(), e);
				
				// We mark it as inserted to prevent continuous insert attempts
				try {
					synchronized(mWoT) {
					synchronized(mPuzzleStore) {
						final OwnIntroductionPuzzle puzzle = mPuzzleStore.getOwnPuzzleByRequestURI(state.getURI()); /* Be careful: This locks the WoT! */
						puzzle.setInserted();
						mPuzzleStore.storeAndCommit(puzzle);
					}
					}
				}
				catch(Exception error) {
					Logger.error(this, "Marking puzzle as inserted failed", error);
				}
			}
			else if(e.isFatal())
				Logger.error(this, "Insert of puzzle failed: " + state.getURI(), e);
			else
				Logger.warning(this, "Insert of puzzle failed, isFatal()==false: " + state.getURI(), e);
		}
		finally {
			removeInsert(state);
			Closer.close(((ClientPutter)state).getData());
		}
	}

	/**
	 * Called when a puzzle solution is successfully fetched. We then add the identity which solved the puzzle.
	 */
	@Override
    public void onSuccess(final FetchResult result, final ClientGetter state) {
		Logger.normal(this, "Fetched puzzle solution: " + state.getURI());
		
		Bucket bucket = null;
		InputStream inputStream = null;

		try {
			bucket = result.asBucket();
			inputStream = bucket.getInputStream();
			
			/* importIntroduction() locks the WoT so we need to do that here first to keep the locking order the same everywhere to
			 * prevent deadlocks. */
			synchronized(mWoT) {
			synchronized(mPuzzleStore) {
				final  OwnIntroductionPuzzle p = mPuzzleStore.getOwnPuzzleBySolutionURI(state.getURI());
				synchronized(p) {
					final OwnIdentity puzzleOwner = (OwnIdentity)p.getInserter();
					try {
						// Make double sure that nothing goes wrong, especially considering the fact that multiple own identities can share one database
						// and there might be bugs in the future which result in one seeing the puzzles of the other.
						if(p.wasInserted() == false)
							throw new Exception("Puzzle was not inserted yet!");
						
						if(p.wasSolved())
							throw new Exception("Puzzle was solved already!");
							
						final Identity newIdentity = mWoT.getXMLTransformer().importIntroduction(puzzleOwner, inputStream);
						Logger.normal(this, "Imported identity introduction for identity " + newIdentity.getRequestURI() +
										" to the OwnIdentity " + puzzleOwner);
						
						p.setSolved(newIdentity);
					} catch(Exception e) { 
						Logger.error(this, "Importing introduciton failed, marking puzzle as solved: " + state.getURI(), e);
						p.setSolved(); // Mark it as solved even if parsing failed to prevent DoS.
					}
					
					mPuzzleStore.storeAndCommit(p);
				}
			}
			}
		}
		catch (Exception e) { 
			Logger.error(this, "Importing introduction failed for " + state.getURI(), e);
		}
		finally {
			Closer.close(inputStream);
			Closer.close(bucket);
			removeFetch(state);
		}
	}
	
	/**
	 * Called when the node can't fetch a file OR when there is a newer edition.
	 */
	@Override
    public void onFailure(final FetchException e, final ClientGetter state) {
		try {
			if(e.getMode() == FetchExceptionMode.CANCELLED) {
				if(logDEBUG) Logger.debug(this, "Fetch cancelled: " + state.getURI());
			}
			else {
				// We use retries = -1 so this should never happen unless a client inserts bogus data to the puzzle slot.
				Logger.error(this, "SHOULD NOT HAPPEN: Downloading puzzle solution " + state.getURI() + " failed: ", e);
			}
		}
		finally {
			removeFetch(state);
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
	public void onGeneratedMetadata(Bucket metadata, BaseClientPutter state) {
		metadata.free();
		throw new UnsupportedOperationException();
	}

}
