package plugins.WebOfTrust.util.jobs;

import freenet.pluginmanager.PluginRespirator;
import freenet.support.Executor;
import freenet.support.Logger;
import freenet.support.Ticker;

/**
 * Implementation of delayed background jobs where {@link #triggerExecution} blocks until the
 * background job has finished, as if the background job was performed synchronously. This
 * class is intended to be used in unit tests, and is likely not very useful for general purposes.
 * <br><br>
 * 
 * Can be used during startup of Freenet plugins to replace {@link TickerDelayedBackgroundJob}.
 * This can be necessary because during startup, the {@link PluginRespirator} is not be available to
 * plugins immediately. Because of that, they cannot access the node's {@link Executor} and
 * {@link Ticker}, and thus should not construct a {@link TickerDelayedBackgroundJob} to avoid
 * having to construct a duplicate Executor / Ticker.<br><br>
 * 
 * Notice: Be aware that its {{@link #triggerExecution()}} will block until the job's execution
 * completes. This can cause deadlocks if the job's thread tries to acquire locks which the thread
 * which is calling triggerExecution() is holding: The job will be run on a separate thread, so it
 * doesn't own the locks of the triggering thread.<br>
 * If that problem affects you, you can use the other possible replacement for
 * {@link TickerDelayedBackgroundJob} which is {@link MockDelayedBackgroundJob}; or change
 * your code to call {@link #triggerExecution()} after all locks are relinquished.<br><br>
 * 
 * TODO: Code quality: This rather complex class could maybe be simplified by extending
 * {@link TickerDelayedBackgroundJob}. IIRC, the only difference it has to that class is that
 * {@link #triggerExecution(long)} shall wait for the job to complete. That could be implemented
 * by wrapping the job which is passed to the parent {@link TickerDelayedBackgroundJob} in a
 * wrapper class which helps {@link #triggerExecution(long)} to wait for it to complete.
 *
 * @author bertm
 */
public final class SynchronousDelayedBackgroundJob implements DelayedBackgroundJob {
    /** Constant deadline value indicating no set {@link #nextRunDeadline deadline}, greater than
     * all deadlines. */
    private static final long NO_DEADLINE = Long.MAX_VALUE;

    /** The actual background job to run. */
    private final Runnable job;
    /** The name of the background job, used to set the Thread's name. */
    private final String name;
    /** The default trigger aggregation delay for {@link #triggerExecution()}. */
    private final long defaultDelay;

    /** Flag for termination, set after first call to {@link #terminate()}, never unset. */
    private boolean isTerminating = false;
    /** The currently running job thread, if any. */
    private Thread runningJobThread = null;
    /** The number of completed runs, assumed to never overflow. */
    private long runCount = 0;
    /** Whether the next run has already been scheduled. */
    private boolean nextRunScheduled = false;
    /** The deadline for the next run, if any (always {@link #NO_DEADLINE} if
     * {@link #nextRunScheduled} is {@code false}. */
    private long nextRunDeadline = NO_DEADLINE;

    /**
     * Constructs a delayed background job with the given default delay. Negative delays
     * are treated as zero delay.
     * When this background job is {@link BackgroundJob#terminate() terminated}, the running job
     * will be notified by means of interruption of its thread. Hence, the job implementer must take
     * care not to swallow {@link InterruptedException}, or for long computations, periodically
     * check the {@link Thread#interrupted()} flag of its {@link Thread#currentThread() thread} and
     * exit accordingly.
     * @param job the job to run
     * @param name the human-readable name of the job
     * @param delayMillis the default background job aggregation delay in milliseconds
     * @see SynchronousDelayedBackgroundJobFactory
     *     You may use the SynchronousDelayedBackgroundJobFactory instead of this constructor for
     *     the benefit of easy batch termination. You do not have to use it though.
     */
    public SynchronousDelayedBackgroundJob(Runnable job, String name, long delayMillis) {
        this.job = job;
        this.name = name;
        this.defaultDelay = delayMillis;
    }

    /**
     * {@inheritDoc}<br><br>
     * 
     * If this background job was terminating or has terminated prior to the invocation of this
     * method, this method has no effect and returns immediately.
     * This method waits for the first job to start after the trigger to finish before returning,
     * except when this method is called from the background job itself, in that case this method
     * does not wait for the job to finish (otherwise a deadlock would be the result).
     * If the calling thread is interrupted while waiting for the job to finish execution, this
     * method will return as soon as possible, without waiting for the job to finish, and sets the
     * thread interruption flag.
     */
    @Override
    public synchronized void triggerExecution(long delayMillis) {
        if (isTerminating) {
            return;
        }
        triggerExecutionAsynchronously(delayMillis);
        // When called from the job thread, don't wait for the job to finish: this would cause a
        // deadlock.
        if (runningJobThread == Thread.currentThread()) {
            return;
        }
        // Wait for the relevant execution to complete.
        long waitForRunCount;
        if (runningJobThread == null) {
            // Wait for next run to finish
            waitForRunCount = runCount + 1;
        } else {
            // Wait for next run after current run to finish
            waitForRunCount = runCount + 2;
        }
        while (runCount < waitForRunCount) {
            try {
                wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Same as {@link #triggerExecution(long)} with delayMillis = default set at constructor. */
    @Override
    public synchronized void triggerExecution() {
        triggerExecution(defaultDelay);
    }

    /**
     * Implementation of {@link #triggerExecution(long)} that does everything expected from that
     * method, except it does not check for termination (the caller is assumed to have done this)
     * and does not wait for the execution to complete.
     * Caller must ensure synchronization on {@code this}.
     * @see #triggerExecution(long)
     */
    private void triggerExecutionAsynchronously(long delayMillis) {
        if (delayMillis < 0) {
            delayMillis = 0;
        }
        long newDeadline = System.currentTimeMillis() + delayMillis;
        if (newDeadline < nextRunDeadline) {
            nextRunDeadline = newDeadline;
            if (!nextRunScheduled) {
                // Create a new waiting job thread using the given delay.
                nextRunScheduled = true;
                startNewJobThread();
            } else {
                // Notify waiting job thread that it should start earlier.
                notifyAll();
            }
        }
    }

    /**
     * Starts a new job daemon thread that:
     * 1. Waits for the deadline to expire
     * 2. Waits until the previous job is done, if any
     * 3. Resets {@link #nextRunDeadline} / {@link #nextRunScheduled}
     * 4. Runs the background job
     * 5. Increments {@link #runCount}
     * 6. Notifies all threads waiting upon its completion
     * Caller must ensure synchronization on {@code this}.
     */
    private void startNewJobThread() {
        Thread jobThread = new Thread(new Runnable() {
            @Override
            public void run() {
                synchronized(SynchronousDelayedBackgroundJob.this) {
                    long delay;
                    // 1. Wait for the deadline to expire
                    while ((delay = nextRunDeadline - System.currentTimeMillis()) > 0) {
                        try {
                            SynchronousDelayedBackgroundJob.this.wait(delay);
                        } catch (InterruptedException e) {
                            // Should not happen: We have NOT set runningJobThread to the current
                            // thread yet, so the containing SynchronousDelayedBackgroundJob had no
                            // access to this thread, and thus no possibility to call interrupt()
                            // on it. The interrupt() must have been misuse from the outside.
                            // We ignore the interrupt instead of returning since the contract of
                            // triggerExecution() promises to wait for the execution to actually
                            // happen, so we must proceed to make it happen.
                            // (Notice: The same applies to a similar catch() below)
                            Logger.error(this, "Received unexpected InterruptedException. "
                                             + "Instead use terminate()!", e);
                        }
                    }
                    // 2. Wait until the previous job is done, if any
                    while (runningJobThread != null) {
                        try {
                            SynchronousDelayedBackgroundJob.this.wait();
                        } catch (InterruptedException e) {
                            // Should not happen as explained above.
                            Logger.error(this, "Received unexpected InterruptedException. "
                                             + "Instead use terminate()!", e);
                        }
                    }
                    // 3. Reset the nextRun variables
                    nextRunDeadline = NO_DEADLINE;
                    nextRunScheduled = false;
                    // From here on, this thread is visible from the outside world,
                    // and interruption should be handled correctly.
                    runningJobThread = Thread.currentThread();
                    // We cannot return here if we are terminating, since that would violate the
                    // contract specified by {@link #triggerExecution()}: It promises to wait for
                    // the execution to actually happen, so we must proceed to make it happen.
                    // Instead, interrupt the thread so that the job may terminate early.
                    if (isTerminating) {
                        runningJobThread.interrupt();
                    }
                }
                try {
                    // 4. Run the background job
                    job.run();
                } finally {
                    synchronized(SynchronousDelayedBackgroundJob.this) {
                        // 5. Increment runCount
                        runCount++;
                        runningJobThread = null;
                        // 6. Notify all threads waiting upon completion
                        SynchronousDelayedBackgroundJob.this.notifyAll();
                    }
                }
            }
        });
        jobThread.setName(name + " (running)");
        jobThread.setDaemon(true);
        jobThread.start();
    }

    @Override
    public synchronized void terminate() {
        if (isTerminating) {
            return;
        }
        isTerminating = true;
        if (runningJobThread != null) {
            runningJobThread.interrupt();
        }
    }

    @Override
    public synchronized boolean isTerminated() {
        return isTerminating && runningJobThread == null && nextRunDeadline == NO_DEADLINE;
    }

    /**
     * {@inheritDoc}<br><br>
     * 
     * NOTICE: The current implementation does not {@link Thread#join()} its worker thread but
     * merely waits for it to have no more code to execute. I am not sure whether this is an issue,
     * since the thread will exit soon if it has no more code to execute, but I speculate that it
     * might cause issues when unloading Freenet plugins which contain this class: When terminating
     * plugins, we force the classloader to unload the plugin JAR, and thus unload all its classes.
     * Hence it is possible that the JVM will throw an exception when trying to load the next
     * code to execute on the thread, because this class, which contains the code, isn't even loaded
     * anymore.<br>
     * This could be fixed by moving this class to Freenet itself so it won't be unloaded by
     * unloading plugins.
     */
    @Override
    public synchronized void waitForTermination(long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while(timeoutMillis > 0 && !isTerminated()) {
            wait(timeoutMillis);
            timeoutMillis = deadline - System.currentTimeMillis();
        }
    }
}
