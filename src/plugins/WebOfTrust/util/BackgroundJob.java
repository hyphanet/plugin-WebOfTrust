package plugins.WebOfTrust.util;

/**
 * A background job that can be triggered to run, for example for performing maintenance jobs.
 *
 * Implementations of this interface must ensure that at most one single instance of the background
 * job is executed at any particular moment, i.e. a single background job must always appear to
 * be run sequentially. Unless terminated, all triggers must eventually followed by an execution
 * of the background job.
 *
 * @author bertm
 */
public interface BackgroundJob {
    /**
     * Triggers the execution of the job somewhere in the future. When the background job is
     * executed is determined by the implementation. Implementations must ensure that this method
     * is safe to invoke from any thread at any moment.
     */
    public void triggerExecution();

    /**
     * Terminates this background job and prevents future execution of the job. If the job is
     * currently running, its thread will be {@link Thread#interrupt() interrupted}. Subsequent
     * invocations of {@link #triggerExecution()} have no effect.
     * If a background job is already terminated, subsequent invocations of this method have no
     * effect.
     * This method does not wait for the job to terminate, it only triggers its termination and
     * returns immediately. Use this method in conjunction with {@link #waitForTermination(long)}
     * when blocking behaviour is required.
     * @see #isTerminated()
     * @see #waitForTermination(long)
     */
    public void terminate();

    /**
     * Whether this background job is terminated. If the background job is terminated, invocations
     * of {@link #triggerExecution()} have no effect. Implementations must ensure that a terminated
     * background job cannot be restarted, i.e. once this method returns {@code true}, it will
     * always return {@code true}.
     * @return {@code true} if this background job is terminated
     * @see #terminate()
     */
    public boolean isTerminated();

    /**
     * Blocks until {@link #isTerminated()} is {@code true}, the timeout provided has expired, or
     * the thread is interrupted.
     * This method does not terminate the background job, it only waits for it to terminate. Use
     * this method in conjunction with {@link #terminate()} when terminating behaviour is required.
     * @param timeout the maximum time to wait in milliseconds
     * @throws InterruptedException When the calling thread is interrupted.
     * @see #terminate()
     */
    public void waitForTermination(long timeout) throws InterruptedException;
}
