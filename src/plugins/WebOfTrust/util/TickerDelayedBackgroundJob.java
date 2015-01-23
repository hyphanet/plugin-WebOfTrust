package plugins.WebOfTrust.util;

import freenet.node.FastRunnable;
import freenet.node.PrioRunnable;
import freenet.support.Executor;
import freenet.support.Ticker;
import freenet.support.io.NativeThread;

/**
 * A {@link DelayedBackgroundJob} implementation that uses a {@link Ticker} for scheduling.
 *
 * @author bertm
 * @see TickerDelayedBackgroundJobFactory
 */
public final class TickerDelayedBackgroundJob implements DelayedBackgroundJob {
    /** Job wrapper for status tracking. */
    private final DelayedBackgroundRunnable realJob;
    /** Human-readable name of this job. */
    private final String name;
    /** Aggregation delay in milliseconds. */
    private final long defaultDelay;
    /** The ticker used to schedule this job. */
    private final Ticker ticker;
    /** The executor of this background job. */
    private final Executor executor;


    /**
     * TODO: Code quality: To ease understanding of the whole of TickerDelayedBackgroundJob, and to
     * help with testing, add a member function which contains assert()s for all the member
     * variables of TickerDelayedBackgroundJob. Each assert() should demonstrate the expected values
     * of the member variable in the given state.
     * <pre><code>
     * public boolean validate() {
     *   synchronized(TickerDelayedBackgroundJob.this) {
     *   switch(this) {
     *     case IDLE:
     *       assert(waitingTickerJob == null)
     *     ...
     *   }}
     *   // Return boolean so we can contain the call to this function in an assert for performance
     *   return true;
     * }
     * </code></pre>
     * Notice: You will have to remove the attribute "static" from the enum so you can access the
     * members of the TickerDelayedBackgroundJob.
     */
    static enum JobState {
        /** Waiting for a trigger, no running job thread or scheduled job. */
        IDLE,
        /** Waiting for the scheduled job to be executed, no running job thread. */
        WAITING,
        /** Running the job in the job thread. */
        RUNNING,
        /** Waiting for the running job thread to finish before we terminate. */
        TERMINATING,
        /** Terminated, no running job thread. */
        TERMINATED
    }
    /** Running state of this job. */
    private JobState state = JobState.IDLE;

    /** Tiny job to run on the ticker, invoking the execution of the {@link #realJob} on the
     * executor (only set in {@link JobState#WAITING}). */
    private Runnable waitingTickerJob = null;

    /** Job execution thread, only if the job is running (only set in {@link JobState#RUNNING}). */
    private Thread thread = null;


    /** Constant for {@link #nextExecutionTime} meaning there is no next execution requested. */
    private final static long NO_EXECUTION = Long.MAX_VALUE;
    /** Next absolute time we have a job execution scheduled, or {@link #NO_EXECUTION} if none. */
    private long nextExecutionTime = NO_EXECUTION;


    /**
     * Constructs a delayed background job with the given default delay. Negative delays
     * are treated as zero delay.
     * The {@link Ticker} given and its {@link Executor} <b>must</b> have an asynchronous
     * implementation of respectively
     * {@link Ticker#queueTimedJob(Runnable, String, long, boolean, boolean) queueTimedJob} and
     * {@link Executor#execute(Runnable, String) execute}. When this background job is
     * {@link BackgroundJob#terminate() terminated}, the running job will be notified by means of
     * interruption of its thread. Hence, the job implementer must take care not to swallow
     * {@link InterruptedException}, or for long computations, periodically check the
     * {@link Thread#interrupted()} flag of its {@link Thread#currentThread() thread} and exit
     * accordingly.
     * @param job the job to run in the background
     * @param name a human-readable name for the job
     * @param delayMillis the default background job aggregation delay in milliseconds
     * @param ticker an asynchronous ticker with asynchronous executor
     * @see TickerDelayedBackgroundJobFactory
     *     You may use the TickerDelayedBackgroundJobFactory instead of this constructor for
     *     the benefit of easy batch termination. You do not have to use it though.
     */
    public TickerDelayedBackgroundJob(Runnable job, String name, long delayMillis, Ticker ticker) {
        if (job == null || name == null || ticker == null || ticker.getExecutor() == null) {
            throw new NullPointerException();
        }
        if (delayMillis < 0) {
            delayMillis = 0;
        }
        this.realJob = new DelayedBackgroundRunnable(job);
        this.name = name;
        this.defaultDelay = delayMillis;
        this.ticker = ticker;
        this.executor = ticker.getExecutor();
    }

    @Override
    public synchronized void triggerExecution(long delayMillis) {
        tryEnqueue(delayMillis);
    }

    /** Same as {@link #triggerExecution(long)} with delayMillis = default set at constructor. */
    @Override
    public synchronized void triggerExecution() {
        triggerExecution(defaultDelay);
    }

    @Override
    public synchronized void terminate() {
        switch (state) {
            case TERMINATED:
                assert(waitingTickerJob == null) : "having ticker job in TERMINATED state";
                assert(thread == null) : "having job thread in TERMINATED state";
                return;
            case TERMINATING:
                assert(waitingTickerJob == null) : "having ticker job in TERMINATING state";
                assert(thread != null) : "TERMINATING state but no thread";
                return;
            case IDLE:
                toTERMINATED();
                return;
            case WAITING:
                toTERMINATED();
                return;
            case RUNNING:
                toTERMINATING();
                return;
        }
    }

    @Override
    public synchronized boolean isTerminated() {
        return state == JobState.TERMINATED;
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
        while(timeoutMillis > 0 && state != JobState.TERMINATED) {
            wait(timeoutMillis);
            timeoutMillis = deadline - System.currentTimeMillis();
        }
    }

    /**
     * Implementation of {@link #triggerExecution(long)}.
     * Caller must ensure synchronization on {@code this}.
     */
    private void tryEnqueue(long delayMillis) {
        if (state == JobState.TERMINATING || state == JobState.TERMINATED) {
            return;
        }
        if (delayMillis < 0) {
            delayMillis = 0;
        }
        long newExecutionTime = System.currentTimeMillis() + delayMillis;
        if (newExecutionTime < nextExecutionTime) {
            nextExecutionTime = newExecutionTime;
            if (state == JobState.RUNNING) {
                // Will automatically schedule this run when the running job finishes.
                return;
            }
            enqueueWaitingTickerJob(delayMillis);
        }
    }

    /**
     * Schedules the ticker job (which will submit the background job to the executor) at the
     * ticker with the given delay, or executes the ticker job immediately if the delay is zero.
     * If the current state is {@link JobState#IDLE}, the state is changed to
     * {@link JobState#WAITING} and a new ticker job is created.
     * Caller must ensure synchronization on {@code this}.
     * @param delayMillis the delay in milliseconds
     */
    private void enqueueWaitingTickerJob(long delayMillis) {
        assert(state == JobState.IDLE || state == JobState.WAITING) :
                "enqueueing ticker job in non-IDLE and non-WAITING state";
        if (state == JobState.WAITING) {
            // Best-effort attempt at removing the stale job to be replaced; this fails silently if
            // the job has already been removed because it has just started to run.
            ticker.removeQueuedJob(waitingTickerJob);
            // Replace the ticker job in case the above fails because it already started to execute.
            // The stale job will check whether waitingTickerJob == this, and because it is not,
            // refuse to start in favor of the new job.
            waitingTickerJob = createTickerJob();
        }
        if (state == JobState.IDLE) {
            toWAITING();
        }
        if (delayMillis > 0) {
            ticker.queueTimedJob(waitingTickerJob, name + " (waiting)", delayMillis, true, false);
        } else {
            waitingTickerJob.run();
        }
    }

    /**
     * Creates a tiny job that instructs the executor to run the background job immediately, to
     * be executed by the ticker.
     */
    private Runnable createTickerJob() {
        // Use FastRunnable here so hopefully the Ticker will execute this on its main thread
        // instead of spawning a new thread for this.
        return new FastRunnable() {
            @Override
            public void run() {
                synchronized(TickerDelayedBackgroundJob.this) {
                    // If this Runnable is not the waitingTickerJob, we have been rescheduled to run
                    // at a different time. Only run if this is the expected ticker job, otherwise
                    // don't run in favor of the new job which is scheduled to run at the proper
                    // time.
                    if (this == waitingTickerJob) {
                        executor.execute(realJob, name + " (running)");
                    }
                }
            }
        };
    }

    /**
     * {RUNNING} -> IDLE
     */
    private void toIDLE() {
        assert(state == JobState.RUNNING) : "going to IDLE from non-RUNNING state";
        assert(thread == Thread.currentThread()) : "going to IDLE from non-job thread";
        assert(waitingTickerJob == null) : "having ticker job while going to IDLE state";
        thread = null;
        state = JobState.IDLE;
    }

    /**
     * {IDLE} -> WAITING
     */
    private void toWAITING() {
        assert(state == JobState.IDLE) : "going to WAITING from non-IDLE state";
        assert(thread == null) : "having job thread while going to WAITING state";
        assert(waitingTickerJob == null) : "having ticker job while going to WAITING state";
        assert(nextExecutionTime != NO_EXECUTION);
        waitingTickerJob = createTickerJob();
        state = JobState.WAITING;
    }

    /**
     * {WAITING} -> RUNNING
     */
    private void toRUNNING() {
        assert(state == JobState.WAITING) : "going to RUNNING state from non-WAITING state";
        assert(thread == null) : "already having job thread while going to RUNNING state";
        assert(waitingTickerJob != null) : "going to RUNNING state without ticker job";
        assert(nextExecutionTime <= System.currentTimeMillis());
        waitingTickerJob = null;
        nextExecutionTime = NO_EXECUTION;
        thread = Thread.currentThread();
        state = JobState.RUNNING;
    }

    /**
     * {RUNNING} -> TERMINATING
     */
    private void toTERMINATING() {
        assert(state == JobState.RUNNING) : "going to TERMINATING state from non-RUNNING state";
        assert(thread != null) : "going to TERMINATING state without job thread";
        assert(waitingTickerJob == null) : "having ticker job while going to TERMINATING state";
        thread.interrupt();
        state = JobState.TERMINATING;
    }

    /**
     * {IDLE, TERMINATING, WAITING} -> TERMINATED
     */
    private void toTERMINATED() {
        if (state == JobState.TERMINATING) {
            assert(thread == Thread.currentThread()) : "going to TERMINATED from non-job thread";
            assert(waitingTickerJob == null) : "going to TERMINATED with waiting ticker job";
            thread = null;
        } else if (state == JobState.WAITING) {
            assert(thread == null) : "having job thread while going to TERMINATED state";
            assert(waitingTickerJob != null) : "in WAITING state but no ticker job";
            // Best-effort attempt at removing the scheduled job from the ticker; this fails
            // silently if the job has already been removed because it has just started to run.
            ticker.removeQueuedJob(waitingTickerJob);
            // Remove the ticker job in case the above fails because it already started to execute.
            // The job will check whether waitingTickerJob == this, and because it is not,
            // refuse to start.
            waitingTickerJob = null;
        } else {
            assert(state == JobState.IDLE) : "going to TERMINATED state from illegal state";
            assert(thread == null) : "having job thread while going to TERMINATED state";
            assert(waitingTickerJob == null) : "going to TERMINATED with waiting ticker job";
        }
        state = JobState.TERMINATED;
        // Notify all threads waiting in waitForTermination()
        notifyAll();
    }

    /**
     * Indicates whether this job may start and sets the state to {@link JobState#RUNNING}
     * accordingly. If this job is in {@link JobState#TERMINATED}, it may not start, if it is
     * in {@link JobState#WAITING}, it may start.
     * @return {@code true} if the job may start
     */
    private synchronized boolean onJobStarted() {
        if (state == JobState.TERMINATED) {
            return false;
        }
        toRUNNING();
        return true;
    }

    /**
     * Finishes the job by either enqueuing itself again in {@link JobState#WAITING} (if there has
     * been a trigger since the start of the last job), waiting for a trigger in
     * {@link JobState#IDLE} or going to {@link JobState#TERMINATED} if we were previously in
     * {@link JobState#TERMINATING}.
     */
    private synchronized void onJobFinished() {
        if (state == JobState.TERMINATED) {
            return;
        }
        if (state == JobState.TERMINATING) {
            toTERMINATED();
            return;
        }
        toIDLE();
        if (nextExecutionTime != NO_EXECUTION) {
            long delay = nextExecutionTime - System.currentTimeMillis();
            enqueueWaitingTickerJob(delay);
        }
    }

    /**
     * A wrapper for jobs. After the job finishes, either goes to a {@link JobState#IDLE} or
     * enqueues its own next run {@link JobState#WAITING} (implementation in
     * {@link #onJobFinished()}).<br><br>
     * 
     * TODO: Code quality: The amount of different Runnables which the container class
     * TickerDelayedBackgroundJob contains is quite large already. Maybe merge the Runnable created
     * at {@link TickerDelayedBackgroundJob#createTickerJob()} into this Runnable here. This would
     * change a lot about how this class works, and take multiple hours to review, so I would
     * suggest you only do this upon other major required changes though.
     */
    private class DelayedBackgroundRunnable implements PrioRunnable {
        private final Runnable job;

        DelayedBackgroundRunnable(Runnable job) {
            this.job = job;
        }

        @Override
        public void run() {
            try {
                if (onJobStarted()) {
                    job.run();
                }
            } finally {
                onJobFinished();
            }
        }

        @Override
        public int getPriority() {
            if (job instanceof PrioRunnable) {
                return ((PrioRunnable)job).getPriority();
            }
            return NativeThread.NORM_PRIORITY;
        }
    }

    /**
     * For testing purposes. Returns the internal job state.
     */
    synchronized JobState getState() {
        return state;
    }
}
