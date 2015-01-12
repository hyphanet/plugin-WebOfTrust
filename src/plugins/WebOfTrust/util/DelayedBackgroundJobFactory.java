package plugins.WebOfTrust.util;

/**
 * Background job factory that allows for creation of {@link DelayedBackgroundJob}s.
 *
 * @author bertm
 */
public interface DelayedBackgroundJobFactory extends BackgroundJobFactory {
    /**
     * Constructs a new {@link DelayedBackgroundJob}. When this background job is
     * {@link DelayedBackgroundJob#terminate() terminated}, the running job will be notified by
     * interruption of its thread. Hence, the job implementer must take care not to swallow
     * {@link InterruptedException}.
     * @param job the job to run in the background
     * @param name a human-readable name for the job
     * @param delayMillis the background job aggregation delay in milliseconds
     */
    public DelayedBackgroundJob newJob(Runnable job, String name, long delayMillis);
}
