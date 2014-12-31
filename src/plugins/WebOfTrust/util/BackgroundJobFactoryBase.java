package plugins.WebOfTrust.util;

import java.util.ArrayList;
import java.util.WeakHashMap;

/**
 * Base class that provides all boilerplate necessary to implement the {@link BackgroundJobFactory}
 * interface.
 *
 * @author bertm
 */
public abstract class BackgroundJobFactoryBase implements BackgroundJobFactory {
    private static final Object PLACEHOLDER = new Object();

    /** Set of all live (i.e. not garbage collected) background jobs created by this instance. */
    private final WeakHashMap<BackgroundJob, Object> aliveJobSet =
            new WeakHashMap<BackgroundJob, Object>();

    @Override
    public final void terminateAll() {
        synchronized(aliveJobSet) {
            for (BackgroundJob bg : aliveJobSet.keySet()) {
                bg.terminate();
            }
        }
    }

    @Override
    public final boolean allTerminated() {
        boolean allTerminated = true;
        synchronized(aliveJobSet) {
            for (BackgroundJob bg : aliveJobSet.keySet()) {
                allTerminated &= bg.isTerminated();
            }
        }
        return allTerminated;
    }

    @Override
    public final void waitForTerminationOfAll(long timeout) throws InterruptedException {
        ArrayList<BackgroundJob> jobs;
        synchronized(aliveJobSet) {
            jobs = new ArrayList<BackgroundJob>(aliveJobSet.keySet());
        }
        long deadline = System.currentTimeMillis() + timeout;
        for (BackgroundJob job : jobs) {
            job.waitForTermination(timeout);
            timeout = deadline - System.currentTimeMillis();
            if (timeout <= 0) {
                return;
            }
        }
    }

    /**
     * Registers a background job with this factory. Implementations must ensure to invoke this
     * method for each background job they construct.
     */
    protected final void registerNewJob(BackgroundJob job) {
        synchronized(aliveJobSet) {
            aliveJobSet.put(job, PLACEHOLDER);
        }
    }
}
