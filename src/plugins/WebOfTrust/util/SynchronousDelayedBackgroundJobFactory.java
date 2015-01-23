package plugins.WebOfTrust.util;

import java.util.WeakHashMap;

/**
 * Background job factory implementation for {@link SynchronousDelayedBackgroundJob}s.<br>
 * You do not have to use this, you may also construct the jobs directly using their constructors.
 * <br><br>
 * 
 * ATTENTION: This internally uses a {@link WeakHashMap}. As Java HashMaps never shrink, you must
 * not allow arbitrary strangers who are connected by network to cause creation of jobs using
 * this factory. They could cause denial of service by making the HashMap grow very large.
 *
 * @author bertm
 * @see SynchronousDelayedBackgroundJob
 */
public final class SynchronousDelayedBackgroundJobFactory
        extends BackgroundJobFactoryBase
        implements DelayedBackgroundJobFactory {
    private final long defaultDelay;

    /**
     * Constructs a background job factory with given default delay.
     * @param delayMillis the default trigger aggregation delay in milliseconds
     */
    public SynchronousDelayedBackgroundJobFactory(long delayMillis) {
        defaultDelay = delayMillis;
    }

    @Override
    public SynchronousDelayedBackgroundJob newJob(Runnable job, String name) {
        return newJob(job, name, defaultDelay);
    }

    /**
     * Same as {@link #newJob(Runnable, String)} with the default job aggregation delay replaced by
     * the amount of milliseconds you specify.<br>
     * <b>Please do read its JavaDoc to know about requirements for the passed job.</b>
     * 
     * @see SynchronousDelayedBackgroundJob#SynchronousDelayedBackgroundJob(Runnable, String, long)
     *     The underlying job constructor.
     */
    @Override
    public SynchronousDelayedBackgroundJob newJob(Runnable job, String name, long delayMillis) {
        SynchronousDelayedBackgroundJob bg = new SynchronousDelayedBackgroundJob(job, name,
                delayMillis);
        registerNewJob(bg);
        return bg;
    }
}
