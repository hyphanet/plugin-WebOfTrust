/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.network.input;

import freenet.client.async.USKManager;
import plugins.WebOfTrust.Identity;
import plugins.WebOfTrust.OwnIdentity;
import plugins.WebOfTrust.Score;
import plugins.WebOfTrust.Trust;
import plugins.WebOfTrust.util.Daemon;

/**
 * Uses {@link USKManager} to subscribe to the USK of all "directly trusted" {@link Identity}s.
 * 
 * Directly trusted hereby means: At least one {@link OwnIdentity} has assigned a
 * {@link Trust#getValue()} of >= 0.
 * (Or more formally correct: Any {@link Score} exists with {@link Score#getRank()} == 1.)
 * 
 * This notably is only a small subset of the total set of {@link Identity}s.
 * That's necessary because USK subscriptions are expensive, they create a constant load of
 * polling on the network.
 * The lack of this class subscribing to all {@link Identity}s is compensated by
 * {@link IdentityDownloaderSlow} which deals with the rest of them in a less expensive manner. */
class IdentityDownloaderFast implements IdentityDownloader, Daemon {

	public IdentityDownloaderFast() {
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

	@Override public void storeUpdateEditionHintCommandWithoutCommit(
			String fromIdentityID, String aboutIdentityID, long edition) {
		
		// FIXME
	}

	@Override public boolean getShouldFetchState(String identityID) {
		// FIXME
		return false;
	}

	@Override public void deleteAllCommands() {
		// FIXME
	}

}
