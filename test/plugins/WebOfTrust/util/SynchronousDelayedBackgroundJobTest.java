package plugins.WebOfTrust.util;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * A real simple unit test for {@link SynchronousDelayedBackgroundJob}.
 *
 * @author bertm
 */
public class SynchronousDelayedBackgroundJobTest {
    /** Concurrency canary. */
    private AtomicBoolean wasConcurrent;
    /** Runnable for the current test. */
    private Runnable run;
    /** Background job for the current test. */
    private SynchronousDelayedBackgroundJob job;

    @Before
    public void setUp() {
        wasConcurrent = new AtomicBoolean(false);
        run = null;
        job = null;
    }

    /**
     * Asserts that none of the canaries have been set.
     */
    @After
    public void checkCanaries() {
        assertFalse(wasConcurrent.get());
    }

    /**
     * Surrounds the given runnable with the concurrency canary boilerplate.
     */
    private Runnable withConcurrencyCanary(final Runnable r) {
        return new Runnable() {
            private final AtomicBoolean isRunning = new AtomicBoolean(false);
            @Override
            public void run() {
                if (isRunning.getAndSet(true)) {
                    wasConcurrent.set(true);
                }
                r.run();
                isRunning.set(false);
            }
        };
    }

    /**
     * Tests a simple self-scheduling job for lack of deadlock, reasonable timing and termination.
     */
    @Test
    public void selfSchedulingTest() throws Exception {
        final AtomicInteger runCount = new AtomicInteger(0);
        Runnable run = withConcurrencyCanary(new Runnable() {
            @Override
            public void run() {
                runCount.incrementAndGet();
                job.triggerExecution(0);
            }
        });
        job = new SynchronousDelayedBackgroundJob(run, "self", 10);
        long begin = System.currentTimeMillis();
        job.triggerExecution(100);
        long end = System.currentTimeMillis();
        assertTrue(end - begin >= 100);
        assertTrue(end - begin < 120);
        while(runCount.get() <= 10) {
            Thread.sleep(1);
        }
        end = System.currentTimeMillis();
        assertTrue(end - begin >= 200);
        assertTrue(end - begin < 250);
        job.terminate();
        job.waitForTermination(20);
        assertTrue(job.isTerminated());
    }
}
