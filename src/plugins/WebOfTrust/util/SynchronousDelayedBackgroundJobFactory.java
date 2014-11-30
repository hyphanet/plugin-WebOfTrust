package plugins.WebOfTrust.util;

/**
 * Background job factory implementation for {@link SynchronousDelayedBackgroundJob}s.
 *
 * @author bertm
 * @see SynchronousDelayedBackgroundJob
 */
public class SynchronousDelayedBackgroundJobFactory
        extends BackgroundJobFactoryBase
        implements DelayedBackgroundJobFactory {
    private final long defaultDelay;

    /**
     * Constructs a background job factory with given default delay.
     * @param delay the default trigger aggregation delay
     */
    public SynchronousDelayedBackgroundJobFactory(long delay) {
        defaultDelay = delay;
    }

    @Override
    public SynchronousDelayedBackgroundJob newJob(Runnable job, String name) {
        return newJob(job, name, defaultDelay);
    }

    /**
     * Constructs a new {@link SynchronousDelayedBackgroundJob}. When this background job is
     * {@link BackgroundJob#terminate() terminated}, the running job will be notified by
     * interruption of its thread. Hence, the job implementer must take care not to swallow
     * {@link InterruptedException}.
     * @param job the job to run in the background
     * @param name a human-readable name for the job
     * @param delay the background job aggregation delay in milliseconds
     * @see SynchronousDelayedBackgroundJob#SynchronousDelayedBackgroundJob(Runnable, String, long)
     */
    @Override
    public SynchronousDelayedBackgroundJob newJob(Runnable job, String name, long delay) {
        SynchronousDelayedBackgroundJob bg = new SynchronousDelayedBackgroundJob(job, name, delay);
        registerNewJob(bg);
        return bg;
    }
}
