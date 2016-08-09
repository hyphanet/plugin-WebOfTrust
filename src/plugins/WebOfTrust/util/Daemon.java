/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.util;

/**
 * Similar to a {@link Thread} but more graceful and abstract:
 * - It doesn't necessarily have to run a thread in background. It could as well be event-driven
 *   from the outside. The defining thing of a Daemon is that it is something which must be
 *   cleanly started and stopped during startup and shutdown of woT to ensure things such
 *   as database consistency.
 * - Starting and stopping shall be guaranteed to be synchronous and reliable. */
public interface Daemon {

	/**
	 * Starts this daemon *and* waits until it is fully started.
	 * Should only throw upon bugs. */
	public void start();

	/**
	 * Stops this daemon *and* waits until it is stopped cleanly.
	 * Should only throw upon bugs.
	 * 
	 * TODO: Java8: This interface was created to retro-fit certain pre-existing classes to
	 * implement it. Thus "terminate()" was chosen as a name for this function because many of the
	 * classes use that - instead of "stop() which would have been shorter. Deprecate this function
	 * in favor of a new "stop()" function. Then add a default implementation (= Java 8 feature) to
	 * this function which calls stop(). */
	public void terminate();

}
