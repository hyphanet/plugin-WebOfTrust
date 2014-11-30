package plugins.WebOfTrust.util;

/**
 * Implementation of delayed background jobs where {@link #triggerExecution} blocks until the
 * background job has finished, as if the background job was performed synchronously. This
 * class is intended to be used in unit tests, and is likely not very useful for general purposes.
 *
 * @author bertm
 */
public class SynchronousDelayedBackgroundJob implements DelayedBackgroundJob {
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
     * care not to swallow {@link InterruptedException}.
     * @param job the job to run
     * @param name the human-readable name of the job
     * @param delay the default background job aggregation delay in milliseconds
     */
    public SynchronousDelayedBackgroundJob(Runnable job, String name, long delay) {
        this.job = job;
        this.name = name;
        this.defaultDelay = delay;
    }

    /**
     * Triggers the execution of the job after either aggregating triggers for at most the default
     * delay, or as soon as the currently running job finishes, whichever comes last.
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
    public synchronized void triggerExecution() {
        triggerExecution(defaultDelay);
    }

    /**
     * Triggers the execution of the job after either aggregating triggers for at most the given
     * delay, or as soon as the currently running job finishes, whichever comes last. Negative
     * delays are treated as to zero delays.
     * If this background job was terminating or has terminated prior to the invocation of this
     * method, this method has no effect and returns immediately.
     * This method waits for the first job to start after the trigger to finish before returning,
     * except when this method is called from the background job itself, in that case this method
     * does not wait for the job to finish (otherwise a deadlock would be the result).
     * If the calling thread is interrupted while waiting for the job to finish execution, this
     * method will return as soon as possible, without waiting for the job to finish, and sets the
     * thread interruption flag.
     * @param delay the maximum trigger aggregation delay in milliseconds
     */
    @Override
    public synchronized void triggerExecution(long delay) {
        if (isTerminating) {
            return;
        }
        triggerExecutionAsynchronously(delay);
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

    /**
     * Implementation of {@link #triggerExecution(long)} that does everything expected from that
     * method, except it does not check for termination (the caller is assumed to have done this)
     * and does not wait for the execution to complete.
     * @see #triggerExecution(long)
     * Caller must ensure synchronization on {@code this}.
     */
    private void triggerExecutionAsynchronously(long delay) {
        if (delay < 0) {
            delay = 0;
        }
        long newDeadline = System.currentTimeMillis() + delay;
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
                            // Ignore. We can't really return here, since there is at
                            // least one thread waiting for this thread to complete
                            // (possibly including side effects caused by its run).
                        }
                    }
                    // 2. Wait until the previous job is done, if any
                    while (runningJobThread != null) {
                        try {
                            SynchronousDelayedBackgroundJob.this.wait();
                        } catch (InterruptedException e) {
                            // Ignore. This thread is not even visible from the
                            // outside at this point, and we will be notified once the
                            // currently running job is finished. We can't really return
                            // here, since there is at least one thread waiting for this
                            // thread to complete (possibly including side effects
                            // caused by its run).
                        }
                    }
                    // 3. Reset the nextRun variables
                    nextRunDeadline = NO_DEADLINE;
                    nextRunScheduled = false;
                    // From here on, this thread is visible from the outside world,
                    // and interruption should be handled correctly.
                    runningJobThread = Thread.currentThread();
                    // We cannot return here if we are terminating, since that would violate the
                    // contract specified by {@link #triggerExecution()}. Instead, interrupt the
                    // thread so that the job may terminate early.
                    if (isTerminating) {
                        runningJobThread.interrupt();
                    }
                }
                try {
                    // 4. Run the background job
                    job.run();
                } finally {
                    // 5. Increment runCount
                    synchronized(SynchronousDelayedBackgroundJob.this) {
                        runCount++;
                        runningJobThread = null;
                    }
                    // 6. Notify all threads waiting upon completion
                    SynchronousDelayedBackgroundJob.this.notifyAll();
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
        return isTerminating && runningJobThread == null;
    }

    @Override
    public synchronized void waitForTermination(long timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout;
        while(timeout > 0 && !isTerminated()) {
            wait(timeout);
            timeout = deadline - System.currentTimeMillis();
        }
    }
}
