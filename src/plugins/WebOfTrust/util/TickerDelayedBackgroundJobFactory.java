package plugins.WebOfTrust.util;

import freenet.support.Executor;
import freenet.support.Ticker;

/**
 * Background job factory implementation for {@link TickerDelayedBackgroundJob}s.
 *
 * @author bertm
 * @see TickerDelayedBackgroundJob
 */
public class TickerDelayedBackgroundJobFactory
        extends BackgroundJobFactoryBase
        implements DelayedBackgroundJobFactory {
    /** The default ticker. */
    private final Ticker ticker;

    /**
     * Constructs a background job factory with given default {@link Ticker}.
     * The {@link Ticker} given and its {@link Executor} <b>must</b> have an asynchronous
     * implementation of respectively
     * {@link Ticker#queueTimedJob(Runnable, String, long, boolean, boolean) queueTimedJob} and
     * {@link Executor#execute(Runnable, String) execute}.
     * @param ticker an asynchronous ticker
     */
    public TickerDelayedBackgroundJobFactory(Ticker ticker) {
        this.ticker = ticker;
    }

    /**
     * Constructs a new {@link TickerDelayedBackgroundJob} using the default ticker. When
     * this background job is {@link BackgroundJob#terminate() terminated}, the running job will
     * be notified by interruption of its thread. Hence, the job implementer must take care not
     * to swallow {@link InterruptedException}.
     * @param job the job to run in the background
     * @param name a human-readable name for the job
     * @param delay the background job aggregation delay in milliseconds
     * @see TickerDelayedBackgroundJob#TickerDelayedBackgroundJob(Runnable, String, long, Ticker)
     */
    @Override
    public TickerDelayedBackgroundJob newJob(Runnable job, String name, long delay) {
        return newJob(job, name, delay, ticker);
    }

    /**
     * Constructs a new {@link TickerDelayedBackgroundJob}, overriding the default ticker. When this
     * background job is {@link BackgroundJob#terminate() terminated}, the running job will be
     * notified by interruption of its thread. Hence, the job implementer must take care not to
     * swallow {@link InterruptedException}.
     * @param job the job to run in the background
     * @param name a human-readable name for the job
     * @param delay the background job aggregation delay in milliseconds
     * @param ticker the custom ticker
     * @see TickerDelayedBackgroundJob#TickerDelayedBackgroundJob(Runnable, String, long, Ticker)
     */
    public TickerDelayedBackgroundJob newJob(Runnable job, String name, long delay, Ticker ticker) {
        TickerDelayedBackgroundJob bg = new TickerDelayedBackgroundJob(job, name, delay, ticker);
        registerNewJob(bg);
        return bg;
    }
}
