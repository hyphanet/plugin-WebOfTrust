package plugins.WebOfTrust.util;

/**
 * A {@link BackgroundJob} that is executed after triggers have been aggregated for some delay.
 *
 * @author bertm
 */
public interface DelayedBackgroundJob extends BackgroundJob {
    /**
     * Triggers the execution of the job after either aggregating triggers for at most some delay
     * specified by the implementation or as soon as the currently running job finishes, whichever
     * comes last. Implementations must ensure that this method is safe to invoke from any thread
     * at any moment.
     */
    @Override
    public void triggerExecution();

    /**
     * Triggers the execution of the job after either aggregating triggers for at most the given
     * delay, or as soon as the currently running job finishes, whichever comes last.
     * Negative delays are treated as to zero delays. Implementations must ensure that this method
     * is safe to invoke from any thread at any moment.
     * @param delay the maximum trigger aggregation delay in milliseconds
     */
    public void triggerExecution(long delay);
}
