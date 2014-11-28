package plugins.WebOfTrust.util;

import freenet.node.FastRunnable;
import freenet.node.PrioRunnable;
import freenet.support.Executor;
import freenet.support.Ticker;
import freenet.support.io.NativeThread;

/**
 * A {@link BackgroundJob} that is executed after triggers have been aggregated for a given delay.
 *
 * @author bertm
 * @see BackgroundJob
 * @see DelayedBackgroundJobFactory
 */
public class DelayedBackgroundJob implements BackgroundJob {
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

    /** Constant for {@link #nextExecutionTime} meaning there is no next execution requested. */
    private final static long NO_EXECUTION = Long.MAX_VALUE;

    /** The executor of this background job. */
    private final Executor executor;
    /** The ticker used to schedule this job. */
    private final Ticker ticker;
    /** Job wrapper for status tracking. */
    private final DelayedBackgroundRunnable realJob;
     /** Human-readable name of this job. */
    private final String name;
    /** Aggregation delay in milliseconds. */
    private final long defaultDelay;

    /** Tiny job to run on the ticker, invoking the execution of the {@link #realJob} on the
     * executor (only set in {@code WAITING} state). */
    private Runnable waitingTickerJob = null;
    /** Running state of this job. */
    private JobState state = JobState.IDLE;
    /** Job execution thread, only if the job is running (only set in {@code RUNNING} state). */
    private Thread thread = null;
    /** Next absolute time we have a job execution scheduled, or {@link #NO_EXECUTION} if none. */
    private long nextExecutionTime = NO_EXECUTION;

    /**
     * Constructs a delayed background job.
     * The {@link Executor} and {@link Ticker} given <b>must</b> have an asynchronous implementation
     * of respectively {@link Executor#execute(Runnable, String) execute} and
     * {@link Ticker#queueTimedJob(Runnable, String, long, boolean, boolean) queueTimedJob}.
     * @param job the job to run in the background
     * @param name a human-readable name for the job
     * @param delay the default background job aggregation delay in milliseconds
     * @param executor an asynchronous executor
     * @param ticker an asynchronous ticker
     */
    DelayedBackgroundJob(Runnable job, String name, long delay, Executor executor, Ticker ticker) {
        this.executor = executor;
        this.ticker = ticker;
        this.name = name;
        this.defaultDelay = delay;
        this.realJob = new DelayedBackgroundRunnable(job);
    }

    /**
     * Creates a tiny job that instructs the executor to run the background job immediately, to
     * be executed by the ticker.
     */
    private Runnable createTickerJob() {
        return new FastRunnable() {
            @Override
            public void run() {
                synchronized(DelayedBackgroundJob.this) {
                    // If this runnable is not the waitingTickerJob, it has been rescheduled. Only
                    // run if this is the expected ticker job, otherwise the expected job will run
                    // real soon.
                    if (this == waitingTickerJob) {
                        executor.execute(realJob, name + " (running)");
                    }
                }
            }
        };
    }

    /**
     * Triggers scheduling of the job with the default delay if no job is scheduled. If a job
     * is already scheduled later than the default delay, it is rescheduled at the default delay.
     *
     * The first trigger received after the start of the last job execution leads to scheduling of
     * another execution of the job, either after the default delay or when the currently executing
     * job is finished, whichever comes last. A newly constructed delayed background job can be
     * assumed to have started its last job infinitely in the past.
     * @see #trigger(long)
     */
    @Override
    public synchronized void trigger() {
        tryEnqueue(defaultDelay);
    }

    /**
     * Triggers scheduling of the job with the given delay if no job is scheduled. If a job
     * is already scheduled later than the given delay, it is rescheduled at the given delay.
     * Negative delays are considered equal to zero delay.
     *
     * The first trigger received after the start of the last job execution leads to scheduling of
     * another execution of the job, either after the default delay or when the currently executing
     * job is finished, whichever comes last. A newly constructed delayed background job can be
     * assumed to have started its last job infinitely in the past.
     * @param delay the trigger aggregation delay in ms
     * @see #trigger()
     */
    public synchronized void trigger(long delay) {
        tryEnqueue(delay);
    }

    /**
     * Implementation of {@link #trigger(long)}.
     * Caller must ensure synchronization on {@code this}.
     */
    private void tryEnqueue(long delay) {
        if (state == JobState.TERMINATING || state == JobState.TERMINATED) {
            return;
        }
        if (delay < 0) {
            delay = 0;
        }
        long newExecutionTime = System.currentTimeMillis() + delay;
        if (newExecutionTime < nextExecutionTime) {
            nextExecutionTime = newExecutionTime;
            if (state == JobState.RUNNING) {
                // Will automatically schedule this run when the running job finishes.
                return;
            }
            if (state == JobState.WAITING) {
                // Best-effort attempt at removing the stale job to be replaced.
                ticker.removeQueuedJob(waitingTickerJob);
                // Replace the ticker job in case the above fails, so the stale job will not run.
                waitingTickerJob = createTickerJob();
            }
            enqueueWaitingTickerJob(delay);
        }
    }

    /**
     * Schedules the ticker job (which will submit the background job to the executor) at the
     * ticker with the given delay, or executes the ticker job immediately if the delay is zero.
     * If the current state is {@code IDLE}, we the state is changed to {@code WAITING} and a new
     * ticker job is created.
     * @param delay the delay in ms
     */
    private void enqueueWaitingTickerJob(long delay) {
        assert(state == JobState.IDLE || state == JobState.WAITING) :
                "enqueueing ticker job in non-IDLE and non-WAITING state";
        // Use a unique job for each (re)scheduling to avoid running twice.
        if (state == JobState.IDLE) {
            toWAITING();
        }
        if (delay > 0) {
            ticker.queueTimedJob(waitingTickerJob, name + " (waiting)", delay, true, false);
        } else {
            waitingTickerJob.run();
        }
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

    @Override
    public synchronized void waitForTermination(long timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout;
        while(timeout > 0 && state != JobState.TERMINATED) {
            wait(timeout);
            timeout = deadline - System.currentTimeMillis();
        }
    }

    /**
     * {RUNNING} -> IDLE
     */
    private void toIDLE() {
        assert(state == JobState.RUNNING) : "going to IDLE from non-RUNNING state";
        assert(thread == Thread.currentThread()) : "going to IDLE from non-job thread";
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
            // Remove the scheduled job from the ticker on a best-effort basis.
            ticker.removeQueuedJob(waitingTickerJob);
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
     * Indicates whether this job may start and sets the state to {@code RUNNING}
     * accordingly. If this job is {@code TERMINATED}, it may not start, if it is {@code
     * WAITING}, it may start.
     * @return {@code true} if the job may start
     */
    private synchronized boolean jobStarted() {
        if (state == JobState.TERMINATED) {
            return false;
        }
        toRUNNING();
        return true;
    }

    /**
     * Finishes the job by either enqueueing itself again in {@code WAITING} (if there has been a
     * trigger since the start of the last job), waiting for a trigger in {@code IDLE} or going
     * to the {@code TERMINATED} state if we were previously {@code TERMINATING}.
     */
    private synchronized void jobFinished() {
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
     * A wrapper for jobs. After the job finishes, either goes to an {@code IDLE} state or enqueues
     * its own next run {@code WAITING}.
     */
    private class DelayedBackgroundRunnable implements PrioRunnable {
        private final Runnable job;

        DelayedBackgroundRunnable(Runnable job) {
            this.job = job;
        }

        @Override
        public void run() {
            try {
                if (jobStarted()) {
                    job.run();
                }
            } finally {
                jobFinished();
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
