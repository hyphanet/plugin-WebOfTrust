package plugins.WebOfTrust.util;

/**
 * FIXME Javadoc
 *
 * @author bertm
 */
public interface BackgroundJob {
    /**
     * Triggers the execution of the job somewhere in the future. When the background job is
     * executed is determined by the implementation.
     */
    public void trigger();

    /**
     * Terminates this background job and prevents future execution of the job. If the job is
     * currently running, its thread will be {@link Thread#interrupt() interrupted}. Subsequent
     * invocations of {@link #trigger()} have no effect.
     * If a background job is already terminated, subsequent invocations of this method have no
     * effect.
     * @see #isTerminated()
     */
    public void terminate();

    /**
     * Whether this background job is terminated. If the background job is terminated, invocations
     * of {@link #trigger()} have no effect.
     * @return {@code true} if this background job is terminated
     * @see #terminate()
     */
    public boolean isTerminated();

    // FIXME: provide method to wait for termination?
}
