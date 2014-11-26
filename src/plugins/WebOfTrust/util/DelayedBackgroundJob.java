package plugins.WebOfTrust.util;

import java.util.concurrent.atomic.AtomicLong;

import freenet.node.FastRunnable;
import freenet.node.PrioRunnable;
import freenet.support.Executor;
import freenet.support.Ticker;
import freenet.support.io.NativeThread;

/**
* FIXME Javadoc
*
* @author bertm
*/
public class DelayedBackgroundJob implements BackgroundJob {
    private static enum JobState {
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

    private final static long NO_TRIGGER = 0;

    private final Executor executor;
    private final Ticker ticker;
    private final DelayedBackgroundRunnable realJob;
    private final String name;
    private final long interval;

    private final AtomicLong oldestTrigger = new AtomicLong(NO_TRIGGER);
    private JobState state = JobState.IDLE;
    private Thread thread = null;

    /**
     * FIXME Javadoc: what is expected from executor and ticker?
     */
    DelayedBackgroundJob(Runnable job, String name, long minInterval, Executor executor, Ticker
            ticker) {
        this.executor = executor;
        this.ticker = ticker;
        this.name = name;
        this.interval = minInterval;
        this.realJob = new DelayedBackgroundRunnable(job);
    }

    /**
     * FIXME Javadoc: when does this schedule?
     */
    @Override
    public void trigger() {
        if (oldestTrigger.compareAndSet(NO_TRIGGER, System.currentTimeMillis())) {
            tryEnqueue();
        }
    }

    /**
     * If this job is {@code IDLE}, enqueue the next run in {@link #interval} milliseconds after
     * the oldest trigger since the last job run, going to a {@code WAITING} state until it is run.
     */
    private synchronized void tryEnqueue() {
        if (state != JobState.IDLE) {
            return;
        }
        long wait = oldestTrigger.get() + interval - System.currentTimeMillis();
        if (wait < 0) {
            wait = 0;
        }
        ticker.queueTimedJob(new FastRunnable() {
            @Override
            public void run() {
                executor.execute(realJob, name + " (running)");
            }
        }, name + " (waiting)", wait, true, false);
        toWAITING();
    }

    @Override
    public synchronized void terminate() {
        switch (state) {
            case TERMINATED:
                assert(thread == null) : "having job thread in TERMINATED state";
                return;
            case TERMINATING:
                assert(thread != null) : "TERMINATING state but no thread";
                return;
            case IDLE:
                toTERMINATED();
                return;
            case WAITING:
                toTERMINATED();
                break;
            case RUNNING:
                toTERMINATING();
                break;
        }
    }

    @Override
    public boolean isTerminated() {
        return state == JobState.TERMINATED;
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
        state = JobState.WAITING;
    }

    /**
     * {WAITING} -> RUNNING
     */
    private void toRUNNING() {
        assert(state == JobState.WAITING) : "going to RUNNING state from non-WAITING state";
        assert(thread == null) : "already having job thread while going to RUNNING state";
        thread = Thread.currentThread();
        state = JobState.RUNNING;
    }

    /**
     * {RUNNING} -> TERMINATING
     */
    private void toTERMINATING() {
        assert(state == JobState.RUNNING);
        assert(thread != null) : "going to TERMINATING state without job thread";
        thread.interrupt();
    }

    /**
     * {IDLE, TERMINATING, WAITING} -> TERMINATED
     */
    private void toTERMINATED() {
        if (state == JobState.TERMINATING) {
            assert(thread == Thread.currentThread()) : "TERMINATING from non-job thread";
            thread = null;
        } else {
            assert (state == JobState.IDLE || state == JobState.WAITING)
                    : "going to TERMINATED state from non-IDLE and non-WAITING state";
            assert (thread == null) : "having job thread while going to TERMINATED state";
        }
        state = JobState.TERMINATED;
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
        oldestTrigger.set(NO_TRIGGER);
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
        if (oldestTrigger.get() != NO_TRIGGER) {
            tryEnqueue();
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
}
