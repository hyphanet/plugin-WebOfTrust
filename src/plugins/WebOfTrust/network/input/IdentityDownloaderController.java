/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.network.input;

import plugins.WebOfTrust.Identity;
import plugins.WebOfTrust.IdentityFetcher;
import plugins.WebOfTrust.IdentityFileQueue;
import plugins.WebOfTrust.WebOfTrust;
import plugins.WebOfTrust.util.Daemon;
import freenet.pluginmanager.PluginRespirator;


/**
 * For legacy reasons, the subsystems of WoT expect only a single instance of
 * {@link IdentityDownloader} to exist.
 * On the other hand, to improve code quality, there nowadays
 * are two different implementations running in parallel to deal with different subsets of
 * {@link Identity}s.
 * Thus, this class exists to encapsulate instances of the both of them in a single object.
 * 
 * @see IdentityDownloaderFast
 * @see IdentityDownloaderSlow
 */
public class IdentityDownloaderController implements IdentityDownloader, Daemon {

	/**
	 * If true, use class {@link IdentityFetcher} instead of {@link IdentityDownloaderFast} and
	 * {@link IdentityDownloaderSlow}.
	 * 
	 * ATTENTION: Use true for testing purposes only:
	 * {@link IdentityFetcher} subscribes to the USKs of ALL trustworthy {@link Identity}s.
	 * Thus, it is very reliable but also very slow and a very high load on the network.
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
				new IdentityFetcher(wot, pr, q)
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
		// FIXME
	}

	@Override public void storeAbortFetchCommandWithoutCommit(Identity identity) {
		// FIXME
	}

	@Override public void storeUpdateEditionHintCommandWithoutCommit(String identityID) {
		// FIXME
	}

}
