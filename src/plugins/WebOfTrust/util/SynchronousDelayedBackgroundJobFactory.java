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
     * Constructs a new {@link SynchronousDelayedBackgroundJob}. When this background job is
     * {@link BackgroundJob#terminate() terminated}, the running job will be notified by
     * interruption of its thread. Hence, the job implementer must take care not to swallow
     * {@link InterruptedException}, or for long computations, periodically check the
     * {@link Thread#interrupted()} flag of its {@link Thread#currentThread() thread} and exit
     * accordingly.
     * @param job the job to run in the background
     * @param name a human-readable name for the job
     * @param delayMillis the background job aggregation delay in milliseconds
     * @see SynchronousDelayedBackgroundJob#SynchronousDelayedBackgroundJob(Runnable, String, long)
     */
    @Override
    public SynchronousDelayedBackgroundJob newJob(Runnable job, String name, long delayMillis) {
        SynchronousDelayedBackgroundJob bg = new SynchronousDelayedBackgroundJob(job, name,
                delayMillis);
        registerNewJob(bg);
        return bg;
    }
}
