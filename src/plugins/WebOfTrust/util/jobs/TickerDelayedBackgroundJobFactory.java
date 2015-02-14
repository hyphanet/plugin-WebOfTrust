/* This code is part of WoT, a plugin for Freenet. It is distributed
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.util.jobs;

import java.util.WeakHashMap;

import freenet.support.Ticker;

/**
 * Background job factory implementation for {@link TickerDelayedBackgroundJob}s.<br>
 * You do not have to use this, you may also construct the jobs directly using their constructors.
 * <br><br>
 * 
 * ATTENTION: This internally uses a {@link WeakHashMap}. As Java HashMaps never shrink, you must
 * not allow arbitrary strangers who are connected by network to cause creation of jobs using
 * this factory. They could cause denial of service by making the HashMap grow very large.
 * 
 * @author bertm
 * @see TickerDelayedBackgroundJob
 */
public final class TickerDelayedBackgroundJobFactory
        extends BackgroundJobFactoryBase
        implements DelayedBackgroundJobFactory {
    /** The default delay. */
    private final long defaultDelay;
    /** The default ticker. */
    private final Ticker defaultTicker;

    /**
     * Constructs a background job factory which will produce jobs with the given default delay
     * and default {@link Ticker}.<br>
     * <b>Please do read the JavaDoc of the underlying job constructor
     * {@link TickerDelayedBackgroundJob#TickerDelayedBackgroundJob(Runnable, String, long, Ticker)}
     * for knowing about the requirements of the parameters.</b>
     */
    public TickerDelayedBackgroundJobFactory(long delayMillis, Ticker ticker) {
        this.defaultDelay = delayMillis;
        this.defaultTicker = ticker;
    }

    @Override
    public TickerDelayedBackgroundJob newJob(Runnable job, String name) {
        return newJob(job, name, defaultDelay, defaultTicker);
    }

    /**
     * Same as {@link #newJob(Runnable, String)} with the default job aggregation delay replaced by
     * the amount of milliseconds you specify.<br>
     * <b>Please do read the JavaDoc of the underlying job constructor
     * {@link TickerDelayedBackgroundJob#TickerDelayedBackgroundJob(Runnable, String, long, Ticker)}
     * for knowing about the requirements of the parameters.</b>
     */
    @Override
    public TickerDelayedBackgroundJob newJob(Runnable job, String name, long delayMillis) {
        return newJob(job, name, delayMillis, defaultTicker);
    }

    /**
     * Same as {@link #newJob(Runnable, String)} with the default job aggregation delay replaced by
     * the amount of milliseconds you specify, and the default ticker replaced as well.<br>
     * <b>Please do read the JavaDoc of the underlying job constructor
     * {@link TickerDelayedBackgroundJob#TickerDelayedBackgroundJob(Runnable, String, long, Ticker)}
     * for knowing about the requirements of the parameters</b>
     */
    public TickerDelayedBackgroundJob newJob(Runnable job, String name, long delayMillis,
            Ticker ticker) {
        TickerDelayedBackgroundJob bg = new TickerDelayedBackgroundJob(job, name, delayMillis,
                ticker);
        registerNewJob(bg);
        return bg;
    }
}

