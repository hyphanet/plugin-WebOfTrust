/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.util;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;

import java.util.Collection;

import plugins.WebOfTrust.util.jobs.DelayedBackgroundJob;
import plugins.WebOfTrust.util.jobs.TickerDelayedBackgroundJob;
import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.HighLevelSimpleClient;
import freenet.client.InsertException;
import freenet.client.async.BaseClientPutter;
import freenet.client.async.ClientContext;
import freenet.client.async.ClientGetCallback;
import freenet.client.async.ClientGetter;
import freenet.client.async.ClientPutCallback;
import freenet.keys.FreenetURI;
import freenet.node.Node;
import freenet.node.PrioRunnable;
import freenet.support.Logger;
import freenet.support.io.TempBucketFactory;

/**
 * A thread which periodically wakes up and iterates to start fetches and/or inserts.
 * 
 * When calling <code>start()</code>, the thread will iterate the first time after <code>getStartupDelay()</code> milliseconds.
 * After each iteration, it will sleep for <code>getSleepTime()</code> milliseconds.
 * <br><br>
 * 
 * ATTENTION: You must only ever create transfers in {@link #iterate()}. Otherwise
 * {@link #terminate()} could not be guaranteed to terminate all existing transfers.
 * 
 * @author xor
 */
public abstract class TransferThread implements
		Daemon, PrioRunnable, ClientGetCallback, ClientPutCallback {
	
	protected final Node mNode;
	protected final HighLevelSimpleClient mClient;
	protected final ClientContext mClientContext;
	protected final TempBucketFactory mTBF;
	
    /**
     * Used to schedule periodic execution of {@link #run()}, and thus {@link #iterate()}.<br>
     * Its {@link DelayedBackgroundJob#isTerminated()} state also implicitly serves as our own
     * execution state. See {@link #start()} and {@link #terminate()}. */
    private final DelayedBackgroundJob mJob;
	
	private final Collection<ClientGetter> mFetches = createFetchStorage();
	private final Collection<BaseClientPutter> mInserts = createInsertStorage();
	
	public TransferThread(Node myNode, HighLevelSimpleClient myClient, String myName) {
		mNode = myNode;
		mClient = myClient;
		mClientContext = mNode.clientCore.clientContext;
		mTBF = mNode.clientCore.tempBucketFactory;
		
        mJob = new TickerDelayedBackgroundJob(this, myName,
            0 /* We don't use the default delay, we always specify one */, mNode.getTicker());
	}
	
	/**
	 * Tells this TransferThread to start it's execution. You have to call this after constructing an object of an implementing class - it must not
	 * be called in the constructors of implementing classes.
	 */
	@Override public void start() {
		Logger.debug(this, "Starting...");

        // We don't need to reliably protect against this, triggerExecution() will do nothing if we
        // terminate()d the job already.
        assert (!mJob.isTerminated());

        mJob.triggerExecution(getStartupDelay());
        
        Logger.debug(this, "Started.");
	}
	
	/** Specify the priority of this thread. Priorities to return can be found in class NativeThread. */
	@Override
	public abstract int getPriority();

	@Override
	public void run() {
        assert (!mJob.isTerminated());

		long sleepTime = SECONDS.toMillis(1);
		try {
			Logger.debug(this, "Loop running...");
			iterate();
			sleepTime = getSleepTime();
		}
		catch(Exception e) {
			Logger.error(this, "Error in iterate() or getSleepTime() probably", e);
		}
		finally {
            // FIXME: The delay we log is not correct: If iterate() used nextIteration() to
            // reschedule itself to a time shorter than sleepTime, triggerExecution(sleepTime) which
            // we call here will not have any effect since it uses the shortest delay of all calls,
            // and our sleepTime is longer.
            // To fix this, we probably should add a getCurrentDelay() to BackgroundJob and use
            // that to log the actual delay.. This problem affects class IntroductionClient already,
            // so there is some use in fixing this.
			Logger.debug(this, "Loop finished. Sleeping for " + MINUTES.convert(sleepTime, MILLISECONDS) + " minutes.");
            mJob.triggerExecution(sleepTime);
		}
	}
	
	/**
	 * Same as {@link #nextIteration()} with a delay of zero.
	 */
	public void nextIteration() {
	    nextIteration(0);
	}
	
    /**
     * Schedules {@link #iterate()} to be executed after the given delay.
     */
	public void nextIteration(long delayMillis) {
        // We do not do this because some unit tests intentionally terminate() us before they run.
        /*  assert (!mJob.isTerminated()) : "Should not be called after terminate()!"; */
        
        mJob.triggerExecution(delayMillis);
	}
	
	protected void abortAllTransfers() {
		Logger.debug(this, "Trying to stop all fetches & inserts...");
		
		abortFetches();
		abortInserts();
	}
	
	protected void abortFetches() {
		Logger.debug(this, "Trying to stop all fetches...");
		if(mFetches != null) synchronized(mFetches) {
			ClientGetter[] fetches = mFetches.toArray(new ClientGetter[mFetches.size()]);
			int fcounter = 0;
			for(ClientGetter fetch : fetches) {
				/* This calls onFailure which removes the fetch from mFetches on the same thread, therefore we need to copy to an array */
				fetch.cancel(mNode.clientCore.clientContext);
				++fcounter;
			}
			
			Logger.debug(this, "Stopped " + fcounter + " current fetches.");
		}
	}
	
	protected void abortInserts() {
		Logger.debug(this, "Trying to stop all inserts...");
		if(mInserts != null) synchronized(mInserts) {
			BaseClientPutter[] inserts = mInserts.toArray(new BaseClientPutter[mInserts.size()]);
			int icounter = 0;
			for(BaseClientPutter insert : inserts) {
				/* This calls onFailure which removes the fetch from mFetches on the same thread, therefore we need to copy to an array */
				insert.cancel(mNode.clientCore.clientContext);
				++icounter;
			}
			Logger.debug(this, "Stopped " + icounter + " current inserts.");
		}
	}
	
	protected void addFetch(ClientGetter g) {
		synchronized(mFetches) {
            assert (!mJob.isTerminated());
			mFetches.add(g);
		}
	}
	
	protected void removeFetch(ClientGetter g) {
		synchronized(mFetches) {
			mFetches.remove(g);
		}
		Logger.debug(this, "Removed request for " + g.getURI());
	}
	
	protected void addInsert(BaseClientPutter p) {
		synchronized(mInserts) {
            assert (!mJob.isTerminated());
			mInserts.add(p);
		}
	}
	
	protected void removeInsert(BaseClientPutter p) {
		synchronized(mInserts) {
			mInserts.remove(p);
		}
		Logger.debug(this, "Removed insert for " + p.getURI());
	}
	
	protected int fetchCount() {
		synchronized(mFetches) {
			return mFetches.size();
		}
	}
	
	protected int insertCount() {
		synchronized(mInserts) {
			return mInserts.size();
		}
	}
	
	@Override public void terminate() {
		Logger.debug(this, "Terminating...");

        // We don't need a reliable guard against calling this twice, what we do is non-destructive
        // and thread-safe.
        assert (!mJob.isTerminated());

        mJob.terminate();
        try {
            // We must wait without timeout since we need to cancel our requests at the core of
            // Freenet (see below) and the job thread might create requests until it is terminated.
            mJob.waitForTermination(Long.MAX_VALUE);
        } catch (InterruptedException e) {
            // We are a shutdown function, there is no sense in sending a shutdown signal to us.
            Logger.error(this, "terminate() should not be interrupt()ed.", e);
        }

        // Having terminate()d mJob prevents run() and thus iterate() from executing, and iterate()
        // is the only thing which can create transfers. Thus we are safe to clean up the existing
        // transfers now.
		try {
			abortAllTransfers();
		}
		catch(RuntimeException e) {
			Logger.error(this, "Aborting all transfers failed", e);
		}
		Logger.debug(this, "Terminated.");
	}
	
	
	protected abstract Collection<ClientGetter> createFetchStorage();
	
	protected abstract Collection<BaseClientPutter> createInsertStorage();
	
	protected abstract long getStartupDelay();

	protected abstract long getSleepTime();
	
	/**
	 * Called by the TransferThread after getStartupDelay() milliseconds for the first time and then after each getSleepTime() milliseconds.
	 * <br>May new create transfers. You <b>must</b> register them with
	 * {@link #addFetch(ClientGetter)} or {@link #addInsert(BaseClientPutter)}.
	 */
	protected abstract void iterate();

	
	/* Fetches */
	
	/**
	 * You have to do "finally { removeFetch() }" when using this function.<br>
	 * You <b>must not</b> create new transfers in this function, only do that in
	 * {@link #iterate()}. */
	@Override
	public abstract void onSuccess(FetchResult result, ClientGetter state);

	/**
	 * You have to do "finally { removeFetch() }" when using this function.<br>
     * You <b>must not</b> create new transfers in this function, only do that in
     * {@link #iterate()}. */
	@Override
	public abstract void onFailure(FetchException e, ClientGetter state);

	/* Inserts */
	
	/**
	 * You have to do "finally { removeInsert() }" when using this function.<br>
     * You <b>must not</b> create new transfers in this function, only do that in
     * {@link #iterate()}. */
	@Override
	public abstract void onSuccess(BaseClientPutter state);

	/**
	 * You have to do "finally { removeInsert() }" when using this function.<br>
     * You <b>must not</b> create new transfers in this function, only do that in
     * {@link #iterate()}. */
	@Override
	public abstract void onFailure(InsertException e, BaseClientPutter state);

	/**
     * You <b>must not</b> create new transfers in this function, only do that in
     * {@link #iterate()}. */
	@Override
	public abstract void onFetchable(BaseClientPutter state);

    /**
     * You <b>must not</b> create new transfers in this function, only do that in
     * {@link #iterate()}. */
	@Override
	public abstract void onGeneratedURI(FreenetURI uri, BaseClientPutter state);

}