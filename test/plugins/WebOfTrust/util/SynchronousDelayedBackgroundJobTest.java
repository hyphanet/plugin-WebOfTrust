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
                Thread.yield();
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
                // For SynchronousDelayedBackgroundJob, triggerExecution() is synchronous - it waits
                // for the next execution to happen. Thus, when calling it from the job thread
                // (= the execution itself) deadlocks would be possible. Hence the implementation
                // ought to detect if triggerExecution() is called from the job thread and not wait
                // for the next execution then. We can easily test for such threads by just calling
                // triggerExecution() from the job thread. If there is a deadlock, it will never
                // complete.
                job.triggerExecution();
            }
        });
        long defaultDelay = 10;
        job = new SynchronousDelayedBackgroundJob(run, "self", defaultDelay);
        
        long longDelay = 100;
        long begin = System.currentTimeMillis();
        job.triggerExecution(longDelay);
        long end = System.currentTimeMillis();
        assertTrue(end - begin >= longDelay);
        float tolerance = 1.2f;
        assertTrue(end - begin < longDelay * tolerance);

        int additionalRuns = 10;
        while(runCount.get() < 1+additionalRuns) {
            Thread.sleep(1);
        }
        end = System.currentTimeMillis();
        assertTrue(end - begin >= longDelay + (additionalRuns * defaultDelay));
        assertTrue(end - begin < (longDelay + (additionalRuns * defaultDelay)) * tolerance);
        
        job.terminate();
        job.waitForTermination(Long.MAX_VALUE);
        assertTrue(job.isTerminated());
    }
}
