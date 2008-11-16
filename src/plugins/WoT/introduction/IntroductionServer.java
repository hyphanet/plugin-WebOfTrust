/*
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */
package plugins.WoT.introduction;

import java.io.IOException;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Iterator;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;

import plugins.WoT.Identity;
import plugins.WoT.IdentityFetcher;
import plugins.WoT.OwnIdentity;
import plugins.WoT.introduction.captcha.CaptchaFactory1;

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
import freenet.node.RequestStarter;
import freenet.support.Logger;
import freenet.support.api.Bucket;
import freenet.support.io.TempBucketFactory;

/**
 * This class provides identity announcement for new identities; It uploads puzzles in certain time intervals and checks whether they were solved.
 * 
 * @author xor
 */
public final class IntroductionServer implements Runnable, ClientCallback {
	
	private static final long STARTUP_DELAY = 1 * 60 * 1000;
	private static final long THREAD_PERIOD = 10 * 60 * 1000; /* FIXME: tweak before release */

	public static final byte PUZZLE_COUNT = 5; 
	public static final byte PUZZLE_INVALID_AFTER_DAYS = 3;

	private Thread mThread;
	
	/** Used to tell the introduction server thread if it should stop */
	private volatile boolean isRunning;
	
	/** A reference to the database */
	private ObjectContainer db;

	/** A reference the HighLevelSimpleClient used to perform inserts */
	private HighLevelSimpleClient mClient;
	
	/** The TempBucketFactory used to create buckets from puzzles before insert */
	private final TempBucketFactory mTBF;
	
	private final IdentityFetcher mIdentityFetcher;

	private final IntroductionPuzzleFactory[] mPuzzleFactories = new IntroductionPuzzleFactory[] { new CaptchaFactory1() };
	
	private final ArrayList<ClientGetter> mRequests = new ArrayList<ClientGetter>(PUZZLE_COUNT * 5); /* Just assume that there are 5 identities */
	
	private final ArrayList<ClientPutter> mInserts = new ArrayList<ClientPutter>(PUZZLE_COUNT * 5); /* Just assume that there are 5 identities */
	

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
	public IntroductionServer(ObjectContainer myDB, HighLevelSimpleClient myClient, TempBucketFactory myTBF, IdentityFetcher myFetcher) {
		isRunning = true;
		db = myDB;
		mClient = myClient;
		mTBF = myTBF;
		mIdentityFetcher = myFetcher;
	}

	public void run() {
		Logger.debug(this, "Introduction server thread started.");
		
		mThread = Thread.currentThread();
		try {
			Thread.sleep((long) (STARTUP_DELAY * (0.5f + Math.random()))); // Let the node start up
		}
		catch (InterruptedException e)
		{
			mThread.interrupt();
		}
		
		while(isRunning) {
			Thread.interrupted();
			Logger.debug(this, "Introduction server loop running...");
			ObjectSet<OwnIdentity> identities = OwnIdentity.getAllOwnIdentities(db);
			
			IntroductionPuzzle.deleteExpiredPuzzles(db);
			
			while(identities.hasNext()) {
				OwnIdentity identity = identities.next();
				if(identity.hasContext(IntroductionPuzzle.INTRODUCTION_CONTEXT)) {
					try {
						Logger.debug(this, "Managing puzzles of " + identity.getNickName());
						downloadSolutions(identity);
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
				Thread.sleep((long) (THREAD_PERIOD * (0.5f + Math.random())));
			}
			catch (InterruptedException e)
			{
				mThread.interrupt();
				Logger.debug(this, "Introduction server loop interrupted.");
			}
		}
		
		cancelRequests();
		Logger.debug(this, "Introduction server thread finished.");
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
	
	private synchronized void cancelRequests() {
		Iterator<ClientGetter> r = mRequests.iterator();
		Iterator<ClientPutter> i = mInserts.iterator();
		int rcounter = 0;
		int icounter = 0;
		Logger.debug(this, "Trying to stop all requests & inserts"); 
		while (r.hasNext()) { r.next().cancel(); ++rcounter; }
		while (i.hasNext()) { i.next().cancel(); ++icounter; }
		Logger.debug(this, "Stopped " + rcounter + " current requests");
		Logger.debug(this, "Stopped " + icounter + " current inserts");
	}
		
	private synchronized void downloadSolutions(OwnIdentity identity) throws FetchException {
		ObjectSet<IntroductionPuzzle> puzzles = IntroductionPuzzle.getByInserter(db, identity);
		
		Logger.debug(this, "Identity " + identity.getNickName() + " has " + puzzles.size() + " puzzles stored. Trying to fetch solutions ...");

		/* TODO: We restart all requests in every iteration. Decide whether this makes sense or not, if not add code to re-use requests for
		 * puzzles which still exist.
		 * I think it makes sense to restart them because there are not many puzzles and therefore not many requests. */
		for(int idx=0; idx < mRequests.size(); ++idx) {
			mRequests.get(idx).cancel();
			mRequests.remove(idx);
		}
		
		for(IntroductionPuzzle p : puzzles) {
			FetchContext fetchContext = mClient.getFetchContext();
			fetchContext.maxSplitfileBlockRetries = -1; // retry forever
			fetchContext.maxNonSplitfileRetries = -1; // retry forever
			ClientGetter g = mClient.fetch(p.getSolutionURI(), -1, this, this, fetchContext);
			g.setPriorityClass(RequestStarter.UPDATE_PRIORITY_CLASS); /* FIXME: decide which one to use */
			mRequests.add(g);
			Logger.debug(this, "Trying to fetch captcha solution for " + p.getRequestURI() + " at " + p.getSolutionURI().toString());
		}
		
		db.commit();
	}
	
	private synchronized void insertNewPuzzles(OwnIdentity identity) {
		int puzzlesToInsert = PUZZLE_COUNT - IntroductionPuzzle.getByInserter(db, identity).size();
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
	
	private synchronized void insertNewPuzzle(OwnIdentity identity) throws IOException, InsertException, TransformerException, ParserConfigurationException {
		Bucket tempB = mTBF.makeBucket(10 * 1024); /* TODO: set to a reasonable value */
		OutputStream os = tempB.getOutputStream();
		
		try {
			boolean retryWithNewIndex = false;
			IntroductionPuzzle p = null;
			do {
				try {
					p = mPuzzleFactories[(int)(Math.random() * 100) % mPuzzleFactories.length].generatePuzzle(db, identity);
					p.exportToXML(os);
					os.close(); os = null;
					tempB.setReadOnly();
				
					ClientMetadata cmd = new ClientMetadata("text/xml");
					InsertBlock ib = new InsertBlock(tempB, cmd, p.getInsertURI());
					InsertContext ictx = mClient.getInsertContext(true);
					
					/* FIXME: are these parameters correct? */
					ClientPutter pu = mClient.insert(ib, false, null, false, ictx, this);
					pu.setPriorityClass(RequestStarter.UPDATE_PRIORITY_CLASS);
					mInserts.add(pu);
					tempB = null;
		
					db.store(p);
					db.commit();
					Logger.debug(this, "Started insert of puzzle from " + identity.getNickName());
				}
				catch(InsertException e) {
					if(e.errorCodes.getFirstCode() == InsertException.COLLISION)
						retryWithNewIndex = true;
					else
						throw e;
					
					Logger.error(this, "Puzzle with index " + p.getIndex() + " already inserted and not found in database! Retrying with next index ...");
				}
			}
			while(retryWithNewIndex);
		}
		finally {
			if(tempB != null)
				tempB.free();
			if(os != null)
				os.close();
		}
	}
	
	/**
	 * Called when the node can't fetch a file OR when there is a newer edition.
	 * In our case, called when there is no solution to a puzzle in the network.
	 */
	public synchronized void onFailure(FetchException e, ClientGetter state) {
		Logger.normal(this, "Downloading puzzle solution " + state.getURI() + " failed: ", e);

		mRequests.remove(state);
	}

	/**
	 * Called when a file is successfully fetched. We then add the identity which
	 * solved the puzzle.
	 */
	public synchronized void onSuccess(FetchResult result, ClientGetter state) {
		Logger.debug(this, "Fetched puzzle solution: " + state.getURI());

		try {
			db.commit();
			IntroductionPuzzle p = IntroductionPuzzle.getByURI(db, state.getURI());
			OwnIdentity puzzleOwner = (OwnIdentity)p.getInserter();
			Identity newIdentity = Identity.importIntroductionFromXML(db, mIdentityFetcher, result.asBucket().getInputStream());
			puzzleOwner.setTrust(db, newIdentity, (byte)50, "Trust received by solving a captcha"); /* FIXME: We need null trust. Giving trust by solving captchas is a REALLY bad idea */
			p.setSolved();
			db.store(p);
			db.commit();
		
			state.cancel(); /* FIXME: is this necessary */ 
			mRequests.remove(state);
		} catch (Exception e) { 
			Logger.error(this, "Parsing failed for "+ state.getURI(), e);
		}
	}
	
	// Only called by inserts
	public synchronized void onSuccess(BaseClientPutter state)
	{
		try {
			IntroductionPuzzle p = IntroductionPuzzle.getByURI(db, state.getURI());
			Logger.debug(this, "Successful insert of puzzle from " + p.getInserter().getNickName() + ": " + p.getRequestURI());
		} catch(Exception e) { Logger.error(this, "Error", e); }
		state.cancel(); /* FIXME: is this necessary */
		mInserts.remove(state);
	}
	
	// Only called by inserts
	public synchronized void onFailure(InsertException e, BaseClientPutter state) 
	{
		try {
			IntroductionPuzzle p = IntroductionPuzzle.getByURI(db, state.getURI());
			Logger.debug(this, "Insert of puzzle failed from " + p.getInserter().getNickName() + ": " + p.getRequestURI(), e);
		} catch(Exception ex) { Logger.error(this, "Error", e); }
		state.cancel(); /* FIXME: is this necessary */
		mInserts.remove(state);
	}

	/* Not needed functions from the ClientCallback inteface */
	
	// Only called by inserts
	public void onFetchable(BaseClientPutter state) {}

	// Only called by inserts
	public void onGeneratedURI(FreenetURI uri, BaseClientPutter state) {}

	/** Called when freenet.async thinks that the request should be serialized to
	 * disk, if it is a persistent request. */
	public void onMajorProgress() {}
}
