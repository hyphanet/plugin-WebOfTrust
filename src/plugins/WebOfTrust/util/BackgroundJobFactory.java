package plugins.WebOfTrust.util;

/**
 * Factory for {@link BackgroundJob}s which allows all background jobs constructed using an instance
 * of this class to be terminated with a single method invocation.
 *
 * @author bertm
 * @see BackgroundJobFactoryBase
 */
public interface BackgroundJobFactory {
    /**
     * Constructs a new background job using the default parameters. When this background job is
     * {@link BackgroundJob#terminate() terminated}, the running job will be notified by
     * interruption of its thread. Hence, the job implementer must take care not to swallow
     * {@link InterruptedException}, or for long computations, periodically check the
     * {@link Thread#interrupted()} flag of its {@link Thread#currentThread() thread} and exit
     * accordingly.
     * @param job the job to run
     * @param name the human-readable name of the job
     */
    public BackgroundJob newJob(Runnable job, String name);

    /**
     * {@link BackgroundJob#terminate() Terminates} all live
     * {@link BackgroundJob background jobs} created by this factory, but does not wait for
     * completion.
     * @see BackgroundJob#terminate()
     * @see #waitForTerminationOfAll(long)
     * @deprecated FIXME: The implementations of this function will currently only terminate the
     * currently running jobs but NOT prevent creation of new ones after it has been called. This
     * renders the function quite useless for shutdown, which would probably be its primary purpose.
     * WOT currently will not use the factories but create and terminate jobs using their own
     * functions, so this is not important. But for future use it might be worth to fix this
     * function to make sure that it is an atomic operation which prevents creation of further jobs.
     * See {@link BackgroundJobFactoryBase#registerNewJob(BackgroundJob)} for ideas on how to fix
     * this.
     */
    @Deprecated
    public void terminateAll();

    /**
     * Checks whether all live {@link BackgroundJob background jobs} created by this factory are
     * terminated.<br>
     * This method returns immediately, use {@link #waitForTerminationOfAll(long)} when blocking
     * behavior is required.
     * @return {@code true} if all jobs are terminated.
     * @Deprecated See {@link #terminateAll()}.
     */
    @Deprecated
    public boolean allTerminated();

    /**
     * Waits for all live {@link BackgroundJob background jobs} created by this factory to
     * terminate. Note that this method makes a snapshot of the currently live jobs, so jobs
     * created after the wait has started will not be waited upon. This method returns when all
     * jobs in this snapshot have terminated, if the timeout has expired, or if the wait is
     * interrupted.
     * This method does not terminate the jobs, it only waits for them to be terminated.
     * @param timeoutMillis the maximum time to wait
     * @throws InterruptedException If the wait is interrupted.
     * @see BackgroundJob#waitForTermination(long)
     * @see #terminateAll()
     * @deprecated See {@link #terminateAll()}
     */
    @Deprecated
    public void waitForTerminationOfAll(long timeoutMillis) throws InterruptedException;
}
