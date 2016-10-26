/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.network.input;

import java.util.ArrayList;

import plugins.WebOfTrust.Identity;
import plugins.WebOfTrust.IdentityFetcher;
import plugins.WebOfTrust.IdentityFetcher.IdentityFetcherCommand;
import plugins.WebOfTrust.IdentityFileQueue;
import plugins.WebOfTrust.WebOfTrust;
import plugins.WebOfTrust.util.Daemon;
import freenet.pluginmanager.PluginRespirator;
import freenet.support.Logger;


/**
 * For legacy reasons, the subsystems of WoT expect only a single instance of
 * {@link IdentityDownloader} to exist.
 * On the other hand, to improve code quality, there nowadays
 * are two different implementations running in parallel to deal with different subsets of
 * {@link Identity}s.
 * Thus, this class exists to encapsulate instances of the both of them in a single object.
 * 
 * <b>Locking:</b>
 * All implementations of {@link IdentityDownloader} MUST synchronize their database transactions
 * upon {@link WebOfTrust#getIdentityDownloaderController()}, NOT upon themselves. This is to allow 
 * the IdentityDownloaderController to be the central lock in case multiple types of
 * IdentityDownloader are running in parallel. That in turn allows the WoT core to not have to
 * synchronize upon whichever specific {@link IdentityDownloader} implementations are being used
 * currently. It can instead just synchronize upon the single IdentityDownloaderController instance.
 * 
 * @see IdentityDownloaderFast
 * @see IdentityDownloaderSlow
 */
public final class IdentityDownloaderController implements IdentityDownloader, Daemon {

	/**
	 * If true, use class {@link IdentityFetcher} instead of {@link IdentityDownloaderFast} and
	 * {@link IdentityDownloaderSlow}.
	 * 
	 * ATTENTION:
	 * 1) Use true for testing purposes only:
	 * {@link IdentityFetcher} subscribes to the USKs of ALL trustworthy {@link Identity}s.
	 * Thus, it is very reliable but also very slow and a very high load on the network.
	 * 2) {@link IdentityFetcher} will not delete its obsolete {@link IdentityFetcherCommand}
	 * objects from the database during shutdown, it only deletes them at startup. So if you use
	 * this for debugging purposes even only once, be aware that you might permanently clutter your
	 * database with stale objects. Thus you should prefer using this upon temporary databases only.
	 * For how to manually get rid of the stale objects see
	 * {@link WebOfTrust#upgradeDatabaseFormatVersion7}.
	 * 
	 * FIXME: Change to true once {@link IdentityDownloaderFast} and {@link IdentityDownloaderSlow}
	 * are actually implemented. */
	public static final boolean USE_LEGACY_REFERENCE_IMPLEMENTATION = true;


	private final IdentityDownloader[] mDownloaders;


	public IdentityDownloaderController(WebOfTrust wot, PluginRespirator pr, IdentityFileQueue q) {
		if(!USE_LEGACY_REFERENCE_IMPLEMENTATION) {
			mDownloaders = new IdentityDownloader[] {
				new IdentityDownloaderFast(),
				new IdentityDownloaderSlow(),
			};
		} else {
			mDownloaders = new IdentityDownloader[] {
				new IdentityFetcher(wot, pr, q, this)
			};
		}
	}

	@Override public void start() {
		for(IdentityDownloader d : mDownloaders)
			d.start();
	}

	@Override public void terminate() {
		for(IdentityDownloader d : mDownloaders)
			d.terminate();
	}

	@Override public void storeStartFetchCommandWithoutCommit(Identity identity) {
		for(IdentityDownloader d : mDownloaders)
			d.storeStartFetchCommandWithoutCommit(identity);
	}

	@Override public void storeAbortFetchCommandWithoutCommit(Identity identity) {
		for(IdentityDownloader d : mDownloaders)
			d.storeAbortFetchCommandWithoutCommit(identity);
	}

	@Override public void storeUpdateEditionHintCommandWithoutCommit(
			String fromIdentityID, String aboutIdentityID, long edition) {
		
		// FIXME:
		// We should really use EditionHint.constructSecure() to validate the hint instead.
		// Or just change the function signature to consume an EditionHint object in the first place
		assert(edition >= 0);
		
		for(IdentityDownloader d : mDownloaders)
			d.storeUpdateEditionHintCommandWithoutCommit(fromIdentityID, aboutIdentityID, edition);
	}

	@Override public boolean getShouldFetchState(String identityID) {
		ArrayList<Boolean> shouldFetch = new ArrayList<Boolean>();
		
		for(IdentityDownloader d : mDownloaders)
			shouldFetch.add(d.getShouldFetchState(identityID));
		
		// Normally this should be an assert() but the parent interface specifies this whole
		// function to be for debugging purposes only so we can be very careful.
		if(shouldFetch.contains(!shouldFetch.get(0))) {
			Logger.error(this, "My downloaders don't return the same getShouldFetchState("
			                   + identityID + ") each: ");
			
			for(IdentityDownloader d : mDownloaders) {
				Logger.error(this, d + " getShouldFetchState(): "
				                     + d.getShouldFetchState(identityID));
			}
			
			assert(false);
		}
		
		return shouldFetch.contains(true);
	}

	@Override public void deleteAllCommands() {
		for(IdentityDownloader d : mDownloaders)
			d.deleteAllCommands();
	}

}
