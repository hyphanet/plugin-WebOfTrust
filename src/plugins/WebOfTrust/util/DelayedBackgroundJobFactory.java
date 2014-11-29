package plugins.WebOfTrust.util;

import java.util.ArrayList;
import java.util.WeakHashMap;

import freenet.support.Executor;
import freenet.support.Ticker;

/**
 * Factory for {@link DelayedBackgroundJob}s which allows all background jobs constructed using
 * an instance of this class to be terminated with a single method invocation.
 *
 * @author bertm
 * @see DelayedBackgroundJob
 */
public class DelayedBackgroundJobFactory {
    private static final Object PLACEHOLDER = new Object();

    /** Set of all live (i.e. not garbage collected) background jobs created by this instance. */
    private final WeakHashMap<BackgroundJob, Object> aliveJobSet;
    /** The default executor. */
    private final Executor executor;
    /** The default ticker. */
    private final Ticker ticker;

    /**
     * Constructs a background job factory with given default {@link Executor} and {@link Ticker}.
     * The {@link Executor} and {@link Ticker} given <b>must</b> have an asynchronous implementation
     * of respectively {@link Executor#execute(Runnable, String) execute} and
     * {@link Ticker#queueTimedJob(Runnable, String, long, boolean, boolean) queueTimedJob}.
     * @param executor an asynchronous executor
     * @param ticker an asynchronous ticker
     */
    public DelayedBackgroundJobFactory(Executor executor, Ticker ticker) {
        this.executor = executor;
        this.ticker = ticker;
        aliveJobSet = new WeakHashMap<BackgroundJob, Object>();
    }

    /**
     * Constructs a new {@link DelayedBackgroundJob} using the default executor and ticker.
     * @param job the job to run in the background
     * @param name a human-readable name for the job
     * @param delay the background job aggregation delay in milliseconds
     * @see DelayedBackgroundJob#DelayedBackgroundJob(Runnable, String, long, Executor, Ticker)
     */
    public BackgroundJob newJob(Runnable job, String name, long delay) {
        return newJob(job, name, delay, executor, ticker);
    }

    /**
     * Constructs a new {@link DelayedBackgroundJob}.
     * @param job the job to run in the background
     * @param name a human-readable name for the job
     * @param delay the background job aggregation delay in milliseconds
     * @see DelayedBackgroundJob#DelayedBackgroundJob(Runnable, String, long, Executor, Ticker)
     */
    public BackgroundJob newJob(Runnable job, String name, long delay, Executor executor,
            Ticker ticker) {
        BackgroundJob bg = new DelayedBackgroundJob(job, name, delay, executor, ticker);
        synchronized(aliveJobSet) {
            aliveJobSet.put(bg, PLACEHOLDER);
        }
        return bg;
    }

    /**
     * {@link BackgroundJob#terminate() Terminates} all live {@link BackgroundJob BackgroundJobs}
     * created by this factory.
     * @see BackgroundJob#terminate()
     */
    public void terminateAll() {
        synchronized(aliveJobSet) {
            for (BackgroundJob bg : aliveJobSet.keySet()) {
                bg.terminate();
            }
        }
    }

    /**
     * Checks whether all live {@link BackgroundJob BackgroundJobs} created by this factory are
     * terminated.
     * @return {@code true} if all jobs are terminated.
     */
    public boolean allTerminated() {
        boolean allTerminated = true;
        synchronized(aliveJobSet) {
            for (BackgroundJob bg : aliveJobSet.keySet()) {
                allTerminated &= bg.isTerminated();
            }
        }
        return allTerminated;
    }

    /**
     * Waits for all live {@link BackgroundJob BackgroundJobs} created by this factory to
     * terminate. Note that this method makes a snapshot of the currently live jobs, so jobs
     * created after the wait has started will not be waited upon. This method returns when all
     * jobs in this snapshot have terminated, if the timeout has expired, or if the wait is
     * interrupted.
     * This method does not terminate the jobs, it only waits for them to be terminated.
     * @param timeout the maximum time to wait
     * @throws InterruptedException If the wait is interrupted.
     * @see BackgroundJob#waitForTermination(long)
     * @see #terminateAll()
     */
    public void waitForTerminationOfAll(long timeout) throws InterruptedException {
        ArrayList<BackgroundJob> jobs;
        synchronized(aliveJobSet) {
            jobs = new ArrayList<BackgroundJob>(aliveJobSet.keySet());
        }
        long deadline = System.currentTimeMillis() + timeout;
        for (BackgroundJob job : jobs) {
            job.waitForTermination(timeout);
            timeout = deadline - System.currentTimeMillis();
            if (timeout <= 0) {
                return;
            }
        }
    }
}
