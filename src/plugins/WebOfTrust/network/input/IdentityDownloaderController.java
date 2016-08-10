/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.network.input;

import plugins.WebOfTrust.Identity;
import plugins.WebOfTrust.util.Daemon;


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

	public IdentityDownloaderController() {
		// FIXME
	}

	@Override public void start() {
		// FIXME
	}

	@Override public void terminate() {
		// FIXME
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
