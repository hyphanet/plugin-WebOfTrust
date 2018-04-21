/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.network.input;

import java.util.ArrayList;

import plugins.WebOfTrust.Identity;
import plugins.WebOfTrust.IdentityFetcher;
import plugins.WebOfTrust.IdentityFetcher.IdentityFetcherCommand;
import plugins.WebOfTrust.IdentityFileQueue;
import plugins.WebOfTrust.OwnIdentity;
import plugins.WebOfTrust.Trust;
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


	private final IdentityDownloaderSlow mIdentityDownloaderSlow;

	private final IdentityDownloaderFast mIdentityDownloaderFast;

	private final IdentityDownloader[] mDownloaders;


	public IdentityDownloaderController(WebOfTrust wot, PluginRespirator pr, IdentityFileQueue q) {
		if(!USE_LEGACY_REFERENCE_IMPLEMENTATION) {
			mIdentityDownloaderSlow = new IdentityDownloaderSlow(wot);
			mIdentityDownloaderFast = new IdentityDownloaderFast(wot);
			mDownloaders = new IdentityDownloader[] {
				mIdentityDownloaderFast,
				mIdentityDownloaderSlow
			};
		} else {
			mIdentityDownloaderSlow = null;
			mIdentityDownloaderFast = null;
			mDownloaders = new IdentityDownloader[] {
				new IdentityFetcher(wot, pr, q, this)
			};
		}
	}

	/**
	 * If {@link #USE_LEGACY_REFERENCE_IMPLEMENTATION} is false, returns our
	 * {@link IdentityDownloaderSlow}.
	 * Otherwise returns null. */
	public IdentityDownloaderSlow getIdentityDownloaderSlow() {
		return mIdentityDownloaderSlow;
	}

	/**
	 * If {@link #USE_LEGACY_REFERENCE_IMPLEMENTATION} is false, returns our
	 * {@link IdentityDownloaderFast}.
	 * Otherwise returns null. */
	public IdentityDownloaderFast getIdentityDownloaderFast() {
		return mIdentityDownloaderFast;
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

	@Override public void storeDeleteOwnIdentityCommandWithoutCommit(OwnIdentity oldIdentity,
			Identity newIdentity) {
		
		assert(oldIdentity.getID() == newIdentity.getID());
		
		for(IdentityDownloader d : mDownloaders)
			d.storeDeleteOwnIdentityCommandWithoutCommit(oldIdentity, newIdentity);
	}

	@Override public void storeRestoreOwnIdentityCommandWithoutCommit(Identity oldIdentity,
			OwnIdentity newIdentity) {
		
		assert(oldIdentity.getID() == newIdentity.getID());
		
		for(IdentityDownloader d : mDownloaders)
			d.storeRestoreOwnIdentityCommandWithoutCommit(oldIdentity, newIdentity);
	}

	@Override public void storeTrustChangedCommandWithoutCommit(Trust oldTrust, Trust newTrust) {
		// Check sanity of passed Trusts.
		assert(oldTrust != null || newTrust != null);
		// The newTrust must be about the same truster and trustee Identitys. The Trust's ID
		// contains their IDs so we can compare it to check the Identitys. We must do that as the
		// object references of the Identitys aren't the same as oldTrust is a clone().
		assert(oldTrust != null && newTrust != null ? newTrust.getID().equals(oldTrust.getID())
			: true /* Emulate "assert(if())" using the ternary operator */);
		
		// Check whether we're being called only for such Trust changes as which the interface
		// specification of IdentityDownloader requests.
		// FIXME: Review IdentityDownloaderFast / IdentityDownloaderSlow implementations of this
		// function for whether they are safe in all those cases.
		assert(
			(newTrust == null ^ oldTrust == null) ||
			(newTrust.getValue() != oldTrust.getValue()) ||
			(	  (newTrust.getTruster() instanceof OwnIdentity)
				^ (oldTrust.getTruster() instanceof OwnIdentity))
		) : "storeTrustChangedCommandWithoutCommit() called for irrelevant Trust change!";
		
		
		for(IdentityDownloader d : mDownloaders)
			d.storeTrustChangedCommandWithoutCommit(oldTrust, newTrust);
	}

	@Override public void storeNewEditionHintCommandWithoutCommit(EditionHint hint) {
		for(IdentityDownloader d : mDownloaders)
			d.storeNewEditionHintCommandWithoutCommit(hint);
	}

	@Override public boolean getShouldFetchState(Identity identity) {
		ArrayList<Boolean> shouldFetch = new ArrayList<Boolean>();
		
		for(IdentityDownloader d : mDownloaders)
			shouldFetch.add(d.getShouldFetchState(identity));
		
		// Normally this should be an assert() but the parent interface specifies this whole
		// function to be for debugging purposes only so we can be very careful.
		if(shouldFetch.contains(!shouldFetch.get(0))) {
			Logger.error(this, "My downloaders don't return the same getShouldFetchState("
			                   + identity + ") each: ");
			
			for(IdentityDownloader d : mDownloaders) {
				Logger.error(this, d + " getShouldFetchState(): "
				                     + d.getShouldFetchState(identity));
			}
			
			assert(false);
		}
		
		return shouldFetch.contains(true);
	}

	@Override public void deleteAllCommands() {
		for(IdentityDownloader d : mDownloaders)
			d.deleteAllCommands();
	}

	@Override public void storePreDeleteOwnIdentityCommand(OwnIdentity oldIdentity) {
		assert(oldIdentity != null);
		
		for(IdentityDownloader d : mDownloaders)
			d.storePreDeleteOwnIdentityCommand(oldIdentity);
	}

	@Override public void storePostDeleteOwnIdentityCommand(Identity newIdentity) {
		assert(newIdentity != null);
		
		for(IdentityDownloader d : mDownloaders)
			d.storePostDeleteOwnIdentityCommand(newIdentity);
	}

	@Override public void storePreDeleteIdentityCommand(Identity oldIdentity) {
		assert(oldIdentity != null);
		
		for(IdentityDownloader d : mDownloaders)
			d.storePreDeleteIdentityCommand(oldIdentity);
	}

	@Override public void storePreRestoreOwnIdentityCommand(Identity oldIdentity) {
		assert(oldIdentity != null);
	
		for(IdentityDownloader d : mDownloaders)
			d.storePreRestoreOwnIdentityCommand(oldIdentity);
	}

	@Override public void storePostRestoreOwnIdentityCommand(OwnIdentity newIdentity) {
		assert(newIdentity != null);
		
		for(IdentityDownloader d : mDownloaders)
			d.storePostRestoreOwnIdentityCommand(newIdentity);
	}

}
