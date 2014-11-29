package plugins.WebOfTrust.util;

/**
 * Factory for {@link BackgroundJob}s which allows all background jobs constructed using an instance
 * of this class to be terminated with a single method invocation. This interface does not
 * restrict how factories should provide access to new instances, it only specifies
 * common operations on the instances constructed by the factory.
 *
 * @author bertm
 * @see BackgroundJobFactoryBase
 */
public interface BackgroundJobFactory {
    /**
     * {@link BackgroundJob#terminate() Terminates} all live
     * {@link BackgroundJob background jobs} created by this factory, but does not wait for
     * completion.
     * @see BackgroundJob#terminate()
     * @see #waitForTerminationOfAll(long)
     */
    public void terminateAll();

    /**
     * Checks whether all live {@link BackgroundJob background jobs} created by this factory are
     * terminated.
     * @return {@code true} if all jobs are terminated.
     */
    public boolean allTerminated();

    /**
     * Waits for all live {@link BackgroundJob background jobs} created by this factory to
     * terminate. Note that this method makes a snapshot of the currently live jobs, so jobs
     * created after the wait has started will not be waited upon. This method returns when all
     * jobs in this snapshot have terminated, if the timeout has expired, or if the wait is
     * interrupted.
     * This method does not terminate the jobs, it only waits for them to be terminated.
     * @param timeout the maximum time to wait
     * @throws InterruptedException If the wait is interrupted.
     * @see BackgroundJob#waitForTermination(long)
     * @see #terminateAll()
     */
    public void waitForTerminationOfAll(long timeout) throws InterruptedException;
}
