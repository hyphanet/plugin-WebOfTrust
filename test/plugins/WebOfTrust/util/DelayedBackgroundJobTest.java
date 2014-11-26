package plugins.WebOfTrust.util;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import freenet.support.Executor;
import freenet.support.PooledExecutor;
import freenet.support.PrioritizedTicker;

import plugins.WebOfTrust.util.DelayedBackgroundJob.JobState;

import static org.junit.Assert.*;

public class DelayedBackgroundJobTest {
    private Executor executor;
    private PrioritizedTicker ticker;

    @Before
    public void setUp() throws Exception {
        executor = new PooledExecutor();
        ticker = new PrioritizedTicker(executor, 0);
        ticker.start();
    }

    @Test
    public void testTrigger() throws Exception {
        // Value to increment by running jobs.
        final AtomicInteger value = new AtomicInteger(0);
        // Canary for unwanted background job concurrency.
        final AtomicBoolean wasConcurrent = new AtomicBoolean(false);
        // Thread sleep "randomizer".
        final AtomicInteger n = new AtomicInteger(0);
        // Sleeper for timing-sensitive tests.
        Sleeper sleeper;

        // First test for a reasonable fast test (with execution time smaller than the delay).
        Runnable test = new Runnable() {
            private AtomicBoolean isRunning = new AtomicBoolean(false);
            @Override
            public void run() {
                if (!isRunning.compareAndSet(false, true)) {
                    System.err.println("Detected job concurrency at " + System.currentTimeMillis());
                    wasConcurrent.set(true);
                }
                value.incrementAndGet();
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {}
                isRunning.set(false);
            }
        };
        final DelayedBackgroundJob job = new DelayedBackgroundJob(test, "Test", 50, executor,
                ticker);
        Runnable trigger = new Runnable() {
            @Override
            public void run() {
                long t = System.currentTimeMillis();
                // Hammer the trigger for 60 ms
                while (System.currentTimeMillis() < t + 60) {
                    for (int i = 0; i < 1000; i++) {
                        job.trigger();
                    }
                    try {
                        Thread.sleep(1, n.addAndGet(500));
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        };
        sleeper = new Sleeper();
        assertEquals(0, value.get());
        assertEquals(JobState.IDLE, job.getState());

        // The value should remain stable if we don't trigger.
        sleeper.sleepUntil(100);
        assertEquals(0, value.get());

        // Timing of schedule (with safety margin): value should not change first 25 ms, and
        // certainly be changed after 75 ms, then remain stable.
        sleeper = new Sleeper();
        assertEquals(JobState.IDLE, job.getState());
        job.trigger();
        sleeper.sleepUntil(25);
        assertEquals(0, value.get());
        sleeper.sleepUntil(75);
        assertEquals(1, value.get());
        assertEquals(JobState.IDLE, job.getState());
        sleeper.sleepUntil(175);
        assertEquals(1, value.get());
        assertEquals(JobState.IDLE, job.getState());

        // Same as before, but now with 10 threads hammering the trigger for 60 ms: we expect no
        // increase the first 25 ms, one increase after 75 ms, another increase after 125 ms, then
        // remain stable.
        sleeper = new Sleeper();
        for (int i = 0; i < 10; i++) {
            Thread t = new Thread(trigger);
            t.start();
        }
        long sleep;
        assertEquals(1, value.get());
        sleeper.sleepUntil(25);
        assertEquals(1, value.get());
        sleeper.sleepUntil(75);
        assertEquals(2, value.get());
        sleeper.sleepUntil(125);
        assertEquals(3, value.get());
        assertEquals(JobState.IDLE, job.getState());
        sleeper.sleepUntil(225);
        assertEquals(3, value.get());
        assertEquals(JobState.IDLE, job.getState());

        // Now test whether a slow background task (with execution time longer than the delay) is
        // handled correctly.
        Runnable slowTest = new Runnable() {
            private AtomicBoolean isRunning = new AtomicBoolean(false);
            @Override
            public void run() {
                if (!isRunning.compareAndSet(false, true)) {
                    System.err.println("Detected job concurrency at " + System.currentTimeMillis());
                    wasConcurrent.set(true);
                }
                value.incrementAndGet();
                try {
                    Thread.sleep(80);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                isRunning.set(false);
            }
        };
        final DelayedBackgroundJob slowJob = new DelayedBackgroundJob(slowTest, "Test", 50,
                executor, ticker);
        Thread hammer = new Thread(new Runnable() {
            @Override
            public void run() {
                long t = System.currentTimeMillis();
                // Hammer the trigger for 260 ms
                while (System.currentTimeMillis() < t + 260) {
                    for (int i = 0; i < 1000; i++) {
                        slowJob.trigger();
                    }
                    try {
                        Thread.sleep(1, n.addAndGet(500));
                    } catch (InterruptedException e) {}
                }
            }
        });
        sleeper = new Sleeper();
        assertEquals(3, value.get());
        assertEquals(JobState.IDLE, slowJob.getState());
        hammer.start();
        sleeper.sleepUntil(25);
        assertEquals(3, value.get());
        sleeper.sleepUntil(75);
        assertEquals(4, value.get());
        sleeper.sleepUntil(155);
        assertEquals(5, value.get());
        sleeper.sleepUntil(235);
        assertEquals(6, value.get());
        sleeper.sleepUntil(315);
        assertEquals(7, value.get());
        assertEquals(JobState.RUNNING, slowJob.getState());
        sleeper.sleepUntil(395);
        assertEquals(7, value.get());
        assertEquals(JobState.IDLE, slowJob.getState());
        assertFalse(wasConcurrent.get());
    }

    @Test
    public void testTerminate() throws Exception {

    }

    @Test
    public void testIsTerminated() throws Exception {

    }

    @Test
    public void testWaitForTermination() throws Exception {

    }

    private class Sleeper {
        long creation = System.currentTimeMillis();
        void sleepUntil(long msFromCreation) {
            try {
                long sleep = creation + msFromCreation - System.currentTimeMillis();
                if (sleep > 0) {
                    Thread.sleep(sleep);
                }
            } catch(InterruptedException e) {
                e.printStackTrace();
            }
        }
    }
}