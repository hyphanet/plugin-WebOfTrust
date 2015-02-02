package plugins.WebOfTrust.util;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.Math.max;
import static java.lang.Runtime.getRuntime;
import static org.junit.Assert.*;

/**
 * A real simple unit test for {@link SynchronousDelayedBackgroundJob}.<br><br>
 *
 * TODO: Code quality: This mostly tests the thread-safety of triggerExecution(). Lacking tests:<br>
 * - Thread-safety and reliability of terminate() / waitForTermination(). Tests should be added to
 *   ensure that they work when concurrently called with triggerExecution().<br>
 * - Reliability of triggerExecution(). Tests should be added to ensure that it always causes an
 *   actual execution if one has the right to be scheduled.<br>
 * Notice: The best way to improve this class likely is to re-use the most of
 * {@link TickerDelayedBackgroundJobTest}, maybe via a common base class.
 *
 * @author bertm
 */
public class SynchronousDelayedBackgroundJobTest {
    /** Concurrency canary. */
    private AtomicBoolean wasConcurrent;
    private AtomicBoolean wasInterrupted;
    /** Background job for the current test. */
    private SynchronousDelayedBackgroundJob job;

    @Before
    public void setUp() {
        wasConcurrent = new AtomicBoolean(false);
        wasInterrupted = new AtomicBoolean(false);
        job = null;
    }

    /**
     * Asserts that none of the canaries have been set.
     */
    @After
    public void checkCanaries() {
        assertFalse(wasConcurrent.get());
        assertFalse(wasInterrupted.get());
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
    
    /**
     * Test  to ensure that multiple parallel calls to triggerExceution() never cause the job
     * to run twice in parallel.
     */
    @Test
    public void concurrentTriggerExecutionTest() throws Exception {
        // This test works by creating an amount of threadCount threads of which each calls
        //     DelayedBackgroundJob.triggerExecution(random value between 0 and maxDelay);
        // for an amount of perThreadExecutionCount times.
        // Those parallel calls of triggerExecution() are done to test whether the job ensures
        // properly that the actual job thread never executes multiple times in parallel.
        // The job itself sleeps for a time of executionDuration then to ensure that parallel
        // executions have a higher probability of being detected.
        final int maxExecutionDelay = 1;
        final int executionDuration = 1;
        final int threadCount = max(getRuntime().availableProcessors() - 1, 2);
        final int perThreadExecutionCount = 1000;
        final AtomicInteger actualExecutionCount = new AtomicInteger(0);
        final Thread[] threads = new Thread[threadCount];
        
        // withConcurrentCanary() adds a wrapper Runnable which will detect parallel execution
        Runnable run = withConcurrencyCanary(new Runnable() { @Override public void run() {
            try {
                Thread.sleep(executionDuration);
            } catch (InterruptedException e) {
                wasInterrupted.set(true);
            }
            actualExecutionCount.incrementAndGet();
        }});
        
        job = new SynchronousDelayedBackgroundJob(run, "self",
            10 * 1000 * 1000 /* Set very large default delay to ensure that it would be detected if
                                it was used even though we don't plan to use it */ );
        
        for(int i=0; i < threadCount; ++i) {
            threads[i] = new Thread(new Runnable() { @Override public void run() {
                ThreadLocalRandom r = ThreadLocalRandom.current();
                for(int i=0; i < perThreadExecutionCount ; ++i) {
                    // Test both the code path of no delay (0) and a small delay (1) by randomly
                    // choosing among those both values
                    job.triggerExecution(r.nextLong(maxExecutionDelay
                        + 1 /* add 1 because nextLong() excludes the max value */));
                }
            }});
        }
        
        long begin = System.currentTimeMillis();
        
        // Start them in a separate loop, not in the loop where we construct them, to ensure that
        // they are all started at the same time, execute in parallel, and thus have maximal
        // probability of race conditions.
        for(int i=0; i < threadCount; ++i)
            threads[i].start();
        
        for(int i=0; i < threadCount; ++i)
            threads[i].join();
        
        job.terminate();
        job.waitForTermination(Long.MAX_VALUE);
        assertTrue(job.isTerminated());
        
        long end = System.currentTimeMillis();
        
        
        assertTrue(actualExecutionCount.get() < threadCount * perThreadExecutionCount);
        
        assertTrue(end - begin
            <= threadCount * perThreadExecutionCount * (maxExecutionDelay + executionDuration)
               * 1 /* 0% tolerance because we already randomize the delay */);
        
        
        // checkCanaries() is what actually checks whether any execution happened in parallel.
        // Will be done by JUnit for us because it has an @After annotation.
        /* checkCanaries(); */
    }
}
