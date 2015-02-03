/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.util.jobs;

import freenet.pluginmanager.PluginRespirator;
import freenet.support.Executor;
import freenet.support.Ticker;

/**
 * A fake {@link DelayedBackgroundJob} of which all functions do nothing.<br><br>
 *
 * Can be used during startup of Freenet plugins to replace {@link TickerDelayedBackgroundJob}.
 * This can be necessary because during startup, the {@link PluginRespirator} is not be available to
 * plugins immediately. Because of that, they cannot access the node's {@link Executor} and
 * {@link Ticker}, and thus should not construct a {@link TickerDelayedBackgroundJob} to avoid
 * having to construct a duplicate Executor / Ticker.<br><br>
 * 
 * Notice: Another possible replacement for {@link TickerDelayedBackgroundJob} is
 * {@link SynchronousDelayedBackgroundJob}. Be aware that its {@link BackgroundJob#
 * triggerExecution()} will block until the job's execution completes. This can cause deadlocks if
 * the job's thread tries to acquire locks which the thread which is calling triggerExecution() is
 * holding: The job will be run on a separate thread, so it doesn't own the locks of the triggering
 * thread.<br>
 * If that problem affects you, you are probably better off using this class; or changing your code
 * to call {@link #triggerExecution()} after all locks are relinquished.
 */
public final class MockDelayedBackgroundJob implements DelayedBackgroundJob {

    @Override public void triggerExecution(long delayMillis) { }

    @Override public void triggerExecution() { }

    @Override public void terminate() { }

    @Override public boolean isTerminated() { return true; }

    @Override public void waitForTermination(long timeoutMillis) throws InterruptedException { }

}
