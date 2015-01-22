package plugins.WebOfTrust.util;

import java.util.WeakHashMap;

import freenet.support.Executor;
import freenet.support.Ticker;

/**
 * Background job factory implementation for {@link TickerDelayedBackgroundJob}s.<br>
 * You do not have to use this, you may also construct the jobs directly using their constructors.
 * <br><br>
 * 
 * ATTENTION: This internally uses a {@link WeakHashMap}. As Java HashMaps never shrink, you must
 * not allow arbitrary strangers who are connected by network to cause creation of jobs using
 * this factory. They could cause denial of service by making the HashMap grow very large.
 * 
 * @author bertm
 * @see TickerDelayedBackgroundJob
 */
public class TickerDelayedBackgroundJobFactory
        extends BackgroundJobFactoryBase
        implements DelayedBackgroundJobFactory {
    /** The default delay. */
    private final long defaultDelay;
    /** The default ticker. */
    private final Ticker defaultTicker;

    /**
     * Constructs a background job factory with given default delay and {@link Ticker}.
     * The {@link Ticker} given and its {@link Executor} <b>must</b> have an asynchronous
     * implementation of respectively
     * {@link Ticker#queueTimedJob(Runnable, String, long, boolean, boolean) queueTimedJob} and
     * {@link Executor#execute(Runnable, String) execute}.
     * @param delayMillis the default trigger aggregation delay
     * @param ticker an asynchronous ticker
     */
    public TickerDelayedBackgroundJobFactory(long delayMillis, Ticker ticker) {
        this.defaultDelay = delayMillis;
        this.defaultTicker = ticker;
    }

    @Override
    public TickerDelayedBackgroundJob newJob(Runnable job, String name) {
        return newJob(job, name, defaultDelay, defaultTicker);
    }

    /**
     * Constructs a new {@link TickerDelayedBackgroundJob} using the default ticker. When
     * this background job is {@link BackgroundJob#terminate() terminated}, the running job will
     * be notified by interruption of its thread. Hence, the job implementer must take care not to
     * swallow {@link InterruptedException}, or for long computations, periodically check the
     * {@link Thread#interrupted()} flag of its {@link Thread#currentThread() thread} and exit
     * accordingly.
     * @param job the job to run in the background
     * @param name a human-readable name for the job
     * @param delayMillis the background job aggregation delay in milliseconds
     * @see TickerDelayedBackgroundJob#TickerDelayedBackgroundJob(Runnable, String, long, Ticker)
     */
    @Override
    public TickerDelayedBackgroundJob newJob(Runnable job, String name, long delayMillis) {
        return newJob(job, name, delayMillis, defaultTicker);
    }

    /**
     * Constructs a new {@link TickerDelayedBackgroundJob}, overriding the default ticker. When this
     * background job is {@link BackgroundJob#terminate() terminated}, the running job will be
     * notified by interruption of its thread. Hence, the job implementer must take care not to
     * swallow {@link InterruptedException}, or for long computations, periodically check the
     * {@link Thread#interrupted()} flag of its {@link Thread#currentThread() thread} and exit
     * accordingly.
     * @param job the job to run in the background
     * @param name a human-readable name for the job
     * @param delayMillis the background job aggregation delay in milliseconds
     * @param ticker the custom ticker
     * @see TickerDelayedBackgroundJob#TickerDelayedBackgroundJob(Runnable, String, long, Ticker)
     */
    public TickerDelayedBackgroundJob newJob(Runnable job, String name, long delayMillis,
            Ticker ticker) {
        TickerDelayedBackgroundJob bg = new TickerDelayedBackgroundJob(job, name, delayMillis,
                ticker);
        registerNewJob(bg);
        return bg;
    }
}

