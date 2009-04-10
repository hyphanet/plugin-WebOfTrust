/*
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */
package plugins.WoT.introduction;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;

import plugins.WoT.Identity;
import plugins.WoT.IdentityFetcher;
import plugins.WoT.OwnIdentity;
import plugins.WoT.WoT;
import plugins.WoT.exceptions.NotTrustedException;
import plugins.WoT.introduction.captcha.CaptchaFactory1;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;

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
import freenet.client.async.ClientContext;
import freenet.client.async.ClientGetter;
import freenet.client.async.ClientPutter;
import freenet.keys.FreenetURI;
import freenet.node.PrioRunnable;
import freenet.node.RequestClient;
import freenet.node.RequestStarter;
import freenet.support.Logger;
import freenet.support.api.Bucket;
import freenet.support.io.Closer;
import freenet.support.io.NativeThread;
import freenet.support.io.TempBucketFactory;

/**
 * This class provides identity announcement for new identities; It uploads puzzles in certain time intervals and checks whether they were solved.
 * 
 * @author xor
 */
public final class IntroductionServer implements PrioRunnable, ClientCallback {
	
	private static final int STARTUP_DELAY = 1 * 60 * 1000;
	private static final int THREAD_PERIOD = 30 * 60 * 1000; /* FIXME: tweak before release */

	public static final int PUZZLE_COUNT = 10; 
	public static final byte PUZZLE_INVALID_AFTER_DAYS = 3;
	/* public static final int PUZZLE_REINSERT_MAX_AGE = 12 * 60 * 60 * 1000; */		
	
	
	/* Objects from WoT */

	private WoT mWoT;
	
	private final IdentityFetcher mIdentityFetcher;
	
	
	/* Objects from the node */
	
	/** A reference to the database */
	private ObjectContainer db;

	/** A reference the HighLevelSimpleClient used to perform inserts */
	private HighLevelSimpleClient mClient;
	
	/** The TempBucketFactory used to create buckets from puzzles before insert */
	private final TempBucketFactory mTBF;
	
	/** Random number generator */
	private final Random mRandom;
	private RequestClient requestClient;
	private ClientContext clientContext;
	
	
	/* Private objects */
	
	private Thread mThread;
	
	/** Used to tell the introduction server thread if it should stop */
	private volatile boolean isRunning = false;
	private volatile boolean shutdownFinished = false;
	
	private final IntroductionPuzzleFactory[] mPuzzleFactories = new IntroductionPuzzleFactory[] { new CaptchaFactory1() };
	
	private final ArrayList<ClientGetter> mRequests = new ArrayList<ClientGetter>(PUZZLE_COUNT * 5); /* Just assume that there are 5 identities */
	private final ArrayList<BaseClientPutter> mInserts = new ArrayList<BaseClientPutter>(PUZZLE_COUNT * 5); /* Just assume that there are 5 identities */
	

	/**
	 * Creates an IntroductionServer
	 */
	public IntroductionServer(WoT myWoT, IdentityFetcher myFetcher) {
		mWoT = myWoT;
		mIdentityFetcher = myFetcher;
		
		db = mWoT.getDB();
		mClient = mWoT.getPluginRespirator().getHLSimpleClient();
		mTBF = mWoT.getPluginRespirator().getNode().clientCore.tempBucketFactory;
		mRandom = mWoT.getPluginRespirator().getNode().fastWeakRandom;
		requestClient = mWoT.getRequestClient();
		clientContext = mWoT.getClientContext();
		
		isRunning = true;
	}

	public void run() {
		Logger.debug(this, "Introduction server thread started.");
		
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
			Logger.debug(this, "Introduction server loop running...");
			
			IntroductionPuzzle.deleteExpiredPuzzles(db);

			ObjectSet<OwnIdentity> identities = OwnIdentity.getAllOwnIdentities(db);
			while(identities.hasNext()) {
				OwnIdentity identity = identities.next();
				if(identity.hasContext(IntroductionPuzzle.INTRODUCTION_CONTEXT)) {
					try {
						Logger.debug(this, "Managing puzzles of " + identity.getNickName());
						downloadSolutions(identity);
						insertOldPuzzles(identity);
						insertNewPuzzles(identity);
						Logger.debug(this, "Managing puzzles finished.");
					} catch (Exception e) {
						Logger.error(this, "Puzzle management failed for " + identity.getNickName(), e);
					}
				}
			}
			db.commit();
			Logger.debug(this, "Introduction server loop finished.");
			
			try {
				Thread.sleep(THREAD_PERIOD/2 + mRandom.nextInt(THREAD_PERIOD));
			}
			catch (InterruptedException e)
			{
				mThread.interrupt();
				Logger.debug(this, "Introduction server loop interrupted.");
			}
		}
		}
		
		finally {
		cancelRequests();
		synchronized (this) {
			shutdownFinished = true;
			Logger.debug(this, "Introduction server thread finished.");
			notify();
		}
		}
	}
	
	public int getPriority() {
		return NativeThread.LOW_PRIORITY;
	}
	
	public void terminate() {
		Logger.debug(this, "Stopping the introduction server...");
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
		Logger.debug(this, "Stopped the introduction server.");
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
			//g.cancel(); /* FIXME: is this necessary ? */
			mRequests.remove(g);
		}
		Logger.debug(this, "Removed request for " + g.getURI());
	}
	
	private void removeInsert(BaseClientPutter p) {
		synchronized(mInserts) {
			//p.cancel(); /* FIXME: is this necessary ? */
			mInserts.remove(p);
		}
		Logger.debug(this, "Removed insert for " + p.getURI());
	}
		
	private synchronized void downloadSolutions(OwnIdentity identity) throws FetchException {
		ObjectSet<IntroductionPuzzle> puzzles = IntroductionPuzzle.getByInserter(db, identity);
		
		Logger.debug(this, "Identity " + identity.getNickName() + " has " + puzzles.size() + " puzzles stored. Trying to fetch solutions ...");

		/* TODO: We restart all requests in every iteration. Decide whether this makes sense or not, if not add code to re-use requests for
		 * puzzles which still exist.
		 * I think it makes sense to restart them because there are not many puzzles and therefore not many requests. */
		synchronized(mRequests) {
			for(int idx=0; idx < mRequests.size(); ++idx) {
				mRequests.get(idx).cancel();
				mRequests.remove(idx);
			}
		}
		
		for(IntroductionPuzzle p : puzzles) {
			FetchContext fetchContext = mClient.getFetchContext();
			fetchContext.maxSplitfileBlockRetries = -1; // retry forever
			fetchContext.maxNonSplitfileRetries = -1; // retry forever
			ClientGetter g = mClient.fetch(p.getSolutionURI(), -1, requestClient, this, fetchContext);
			g.setPriorityClass(RequestStarter.UPDATE_PRIORITY_CLASS, clientContext, null); /* FIXME: decide which one to use */
			synchronized(mRequests) {
				mRequests.add(g);
			}
			Logger.debug(this, "Trying to fetch captcha solution for " + p.getRequestURI() + " at " + p.getSolutionURI().toString());
		}
		
		db.commit();
	}
	
	private synchronized void insertNewPuzzles(OwnIdentity identity) {
		int puzzlesToInsert = PUZZLE_COUNT - IntroductionPuzzle.getRecentByInserter(db, identity).size();
		Logger.debug(this, "Trying to insert " + puzzlesToInsert + " new puzzles from " + identity.getNickName());
		
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
	
	private synchronized void insertOldPuzzles(OwnIdentity identity) throws IOException, InsertException, TransformerException, ParserConfigurationException {
		List<IntroductionPuzzle> puzzles = IntroductionPuzzle.getRecentByInserter(db, identity); /* FIXME: Pass PUZZLE_REINSERT_MAX_AGE */
		Logger.debug(this, "Trying to insert " + puzzles.size() + " old puzzles from " + identity.getNickName());
		for(IntroductionPuzzle p : puzzles) {
			insertPuzzle(identity, p);
		}
		Logger.debug(this, "Finished inserting puzzles from " + identity.getNickName());
	}
	
	private synchronized void insertNewPuzzle(OwnIdentity identity) throws IOException, InsertException, TransformerException, ParserConfigurationException {
		IntroductionPuzzle p = mPuzzleFactories[mRandom.nextInt(mPuzzleFactories.length)].generatePuzzle(db, identity);
		insertPuzzle(identity, p);
	}
	
	private synchronized void insertPuzzle(OwnIdentity identity, IntroductionPuzzle p) throws IOException, InsertException, TransformerException, ParserConfigurationException {
		Bucket tempB = mTBF.makeBucket(10 * 1024); /* TODO: set to a reasonable value */
		OutputStream os = null;
		
		try {
			os = tempB.getOutputStream();
			p.exportToXML(os);
			os.close(); os = null;
			tempB.setReadOnly();

			ClientMetadata cmd = new ClientMetadata("text/xml");
			InsertBlock ib = new InsertBlock(tempB, cmd, p.getInsertURI());
			InsertContext ictx = mClient.getInsertContext(true);

			/* FIXME: are these parameters correct? */
			ClientPutter pu = mClient.insert(ib, false, null, false, ictx, this);
			// pu.setPriorityClass(RequestStarter.UPDATE_PRIORITY_CLASS); /* pluginmanager defaults to interactive priority */
			synchronized(mInserts) {
				mInserts.add(pu);
			}
			tempB = null;

			p.store(db);
			Logger.debug(this, "Started insert of puzzle from " + identity.getNickName());
		}
		finally {
			if(tempB != null)
				tempB.free();
			Closer.close(os);
		}
	}
	
	/**
	 * Called when the node can't fetch a file OR when there is a newer edition.
	 * In our case, called when there is no solution to a puzzle in the network.
	 */
	public void onFailure(FetchException e, ClientGetter state, ObjectContainer container) {
		Logger.normal(this, "Downloading puzzle solution " + state.getURI() + " failed: ", e);
		removeRequest(state);
	}

	/**
	 * Called when a puzzle solution is successfully fetched. We then add the identity which solved the puzzle.
	 */
	public void onSuccess(FetchResult result, ClientGetter state, ObjectContainer container) {
		Logger.debug(this, "Fetched puzzle solution: " + state.getURI());

		try {
			db.commit();
			IntroductionPuzzle p = IntroductionPuzzle.getBySolutionURI(db, state.getURI());
			synchronized(p) {
				p.setSolved();
				p.store(db);
				OwnIdentity puzzleOwner = (OwnIdentity)p.getInserter();
				Identity newIdentity = Identity.importIntroductionFromXML(db, mIdentityFetcher, result.asBucket().getInputStream());
				Logger.debug(this, "Imported identity introduction for identity " + newIdentity.getRequestURI());
				try {
					puzzleOwner.getGivenTrust(newIdentity, db);
					Logger.debug(this, "Not setting introduction trust, the identity is already trusted.");
				}
				catch(NotTrustedException e) {
					puzzleOwner.setTrust(db, newIdentity, (byte)0, "Trust received by solving a captcha."); /* FIXME: We need null trust. Giving trust by solving captchas is a REALLY bad idea */
				}
			}
		
			removeRequest(state);
		} catch (Exception e) { 
			Logger.error(this, "Parsing failed for "+ state.getURI(), e);
		}
	}
	
	/** 
	 * Called when a puzzle was successfully inserted.
	 */
	public void onSuccess(BaseClientPutter state, ObjectContainer container)
	{
		try {
			IntroductionPuzzle p = IntroductionPuzzle.getByRequestURI(db, state.getURI());
			Logger.debug(this, "Successful insert of puzzle from " + p.getInserter().getNickName() + ": " + p.getRequestURI());
		} catch(Exception e) { Logger.error(this, "Error", e); }
		
		removeInsert(state);
	}
	
	/**
	 * Called when the insertion of a puzzle failed.
	 */
	public void onFailure(InsertException e, BaseClientPutter state, ObjectContainer container) 
	{
		try {
			IntroductionPuzzle p = IntroductionPuzzle.getByRequestURI(db, state.getURI());
			Logger.debug(this, "Insert of puzzle failed from " + p.getInserter().getNickName() + ": " + p.getRequestURI(), e);
		} catch(Exception ex) { Logger.error(this, "Error", e); }
		
		removeInsert(state);
	}

	/* Not needed functions from the ClientCallback interface */
	
	/** Only called by inserts */
	public void onFetchable(BaseClientPutter state, ObjectContainer container) {}

	/** Only called by inserts */
	public void onGeneratedURI(FreenetURI uri, BaseClientPutter state, ObjectContainer container) {}

	/** Called when freenet.async thinks that the request should be serialized to disk, if it is a persistent request. */
	public void onMajorProgress(ObjectContainer container) {}
}
