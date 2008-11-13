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

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;

import plugins.WoT.Identity;
import plugins.WoT.IdentityFetcher;
import plugins.WoT.OwnIdentity;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;

import freenet.client.ClientMetadata;
import freenet.client.FetchContext;
import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.HighLevelSimpleClient;
import freenet.client.InsertBlock;
import freenet.client.InsertException;
import freenet.client.async.BaseClientPutter;
import freenet.client.async.ClientCallback;
import freenet.client.async.ClientGetter;
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
public class IntroductionServer implements Runnable, ClientCallback {
	
	private static final long THREAD_PERIOD = 30 * 60 * 1000; /* FIXME: tweak before release */
	public static final byte PUZZLE_COUNT = 5; 
	public static final byte PUZZLE_INVALID_AFTER_DAYS = 3;

	private Thread mThread;
	
	/** Used to tell the introduction server thread if it should stop */
	private boolean isRunning;
	
	/** A reference to the database */
	private ObjectContainer db;

	/** A reference the HighLevelSimpleClient used to perform inserts */
	private HighLevelSimpleClient mClient;
	
	/** The TempBucketFactory used to create buckets from puzzles before insert */
	private final TempBucketFactory mTBF;
	
	private final IdentityFetcher mIdentityFetcher;

	private final IntroductionPuzzleFactory[] mPuzzleFactories = new IntroductionPuzzleFactory[] { new CaptchaFactory1() };
	
	private final ArrayList<ClientGetter> mRequests = new ArrayList<ClientGetter>(PUZZLE_COUNT * 5); /* Just assume that there are 5 identities */

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
			Thread.sleep((long) (1*60*1000 * (0.5f + Math.random()))); // Let the node start up
		}
		catch (InterruptedException e)
		{
			mThread.interrupt();
		}
		
		while(isRunning) {
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
			
			try {
				Thread.sleep((long) (THREAD_PERIOD * (0.5f + Math.random())));
			}
			catch (InterruptedException e)
			{
				mThread.interrupt();
				Logger.debug(this, "Introduction server loop interrupted.");
			}
			Logger.debug(this, "Introduction server loop finished.");
		}
		
		cancelRequests();
		Logger.debug(this, "Introduction server thread finished.");
	}
	
	public synchronized void terminate() {
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
		Iterator<ClientGetter> i = mRequests.iterator();
		int counter = 0;
		Logger.debug(this, "Trying to stop all requests"); 
		while (i.hasNext()) { i.next().cancel(); ++counter; }
		Logger.debug(this, "Stopped " + counter + " current requests");
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
			Logger.debug(this, "Trying to fetch captcha solution  " + p.getSolutionURI().toString());
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
			IntroductionPuzzle p = IntroductionPuzzle.getBySolutionURI(db, state.getURI());
			OwnIdentity puzzleOwner = (OwnIdentity)p.getInserter();
			Identity newIdentity = Identity.importIntroductionFromXML(db, mIdentityFetcher, result.asBucket().getInputStream());
			puzzleOwner.setTrust(db, newIdentity, (byte)0, null); /* FIXME: is 0 the proper trust for newly imported identities? */
			db.delete(p);
			db.commit();
		
			state.cancel(); /* FIXME: is this necessary */ 
			mRequests.remove(state);
		} catch (Exception e) { 
			Logger.error(this, "Parsing failed for "+ state.getURI(), e);
		}
	}
	
	/* Not needed functions from the ClientCallback inteface */
	
	// Only called by inserts
	public void onSuccess(BaseClientPutter state) {}
	
	// Only called by inserts
	public void onFailure(InsertException e, BaseClientPutter state) {}

	// Only called by inserts
	public void onFetchable(BaseClientPutter state) {}

	// Only called by inserts
	public void onGeneratedURI(FreenetURI uri, BaseClientPutter state) {}

	/** Called when freenet.async thinks that the request should be serialized to
	 * disk, if it is a persistent request. */
	public void onMajorProgress() {}
}
