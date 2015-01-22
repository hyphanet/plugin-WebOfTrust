package plugins.WebOfTrust.util;

/**
 * A {@link BackgroundJob} that is executed after triggers have been aggregated for some delay.
 *
 * @author bertm
 */
public interface DelayedBackgroundJob extends BackgroundJob {
    /**
     * Triggers execution of the job after aggregating triggers for at least the given delay.<br>
     * If called multiple times, the job will only be executed once, and the shortest given delay
     * will be used.<br>
     * If the job is still running when this function is called or when the delay expires, another
     * execution will happen, but not before the current one finishes. Thus, it is guaranteed that
     * only one thread is running the job at once.<br>
     * Negative delays are treated as to zero delays. Implementations must ensure that this method
     * is safe to invoke from any thread at any moment.
     * @param delayMillis the minimum trigger aggregation delay in milliseconds
     */
    public void triggerExecution(long delayMillis);

    /** Same as {@link #triggerExecution(long)} with delayMillis = default set by implementation. */
    @Override
    public void triggerExecution();
}
