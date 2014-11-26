package plugins.WebOfTrust.util;

import java.util.WeakHashMap;

import freenet.support.Executor;
import freenet.support.Ticker;

/**
 * FIXME Javadoc
 *
 * @author bertm
 */
public class DelayedBackgroundJobFactory {
    private static final Object PLACEHOLDER = new Object();

    private final WeakHashMap<BackgroundJob, Object> aliveJobSet;
    private final Executor executor;
    private final Ticker ticker;

    /**
     * FIXME Javadoc: what is expected from executor and ticker?
     */
    public DelayedBackgroundJobFactory(Executor executor, Ticker ticker) {
        this.executor = executor;
        this.ticker = ticker;
        aliveJobSet = new WeakHashMap<BackgroundJob, Object>();
    }

    /**
     * FIXME Javadoc
     */
    public BackgroundJob newJob(Runnable job, String name, long minInterval) {
        BackgroundJob bg = new DelayedBackgroundJob(job, name, minInterval, executor, ticker);
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
            aliveJobSet.clear();
        }
    }
}
