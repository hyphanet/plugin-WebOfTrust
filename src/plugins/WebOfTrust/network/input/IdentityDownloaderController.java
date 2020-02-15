/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.network.input;

import plugins.WebOfTrust.Identity;
import plugins.WebOfTrust.IdentityFetcher;
import plugins.WebOfTrust.IdentityFetcher.IdentityFetcherCommand;
import plugins.WebOfTrust.IdentityFileQueue;
import plugins.WebOfTrust.OwnIdentity;
import plugins.WebOfTrust.Score;
import plugins.WebOfTrust.Trust;
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
 * <b>Locking:</b>
 * All implementations of {@link IdentityDownloader} MUST synchronize their database transactions
 * upon {@link WebOfTrust#getIdentityDownloaderController()}, NOT upon themselves. This is to allow 
 * the IdentityDownloaderController to be the central lock in case multiple types of
 * IdentityDownloader are running in parallel. That in turn allows the WoT core to not have to
 * synchronize upon whichever specific {@link IdentityDownloader} implementations are being used
 * currently. It can instead just synchronize upon the single IdentityDownloaderController instance.
 * 
 * TODO: Code quality: The specifications of many of IdentiyDownloader's callbacks require that they
 * only be called when the {@link Trust} and {@link Score} database is up to date.
 * Currently importing changed Trusts and updating the Scores from them happens in a single database
 * transaction, and it is likely that this will be split into two separate transactions in the
 * future to reduce the time important locks are blocked. This will imply the creation of a
 * "boolean scoreDatabaseIsOutdated" flag in the database to ensure Scores get updated after Trusts
 * have been changed - and such a flag will be precisely what we could use to test if the callbacks
 * are indeed only called if the Score database is OK.  
 * So once such a flag exists add assert()s in this class here to check it. It is the ideal place
 * to check the flag because the callbacks to all IdentityDownloader implementations are passed
 * through this class so we only need the assert()s once here, not everywhere where the callbacks
 * are called.  
 * The bugtracker entry for introducing the flag is:
 * https://bugs.freenetproject.org/view.php?id=6848
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
	 * {@link WebOfTrust#upgradeDatabaseFormatVersion7}. */
	public static final boolean USE_LEGACY_REFERENCE_IMPLEMENTATION = false;

	private final WebOfTrust mWoT;

	private final IdentityDownloaderSlow mIdentityDownloaderSlow;

	private final IdentityDownloaderFast mIdentityDownloaderFast;

	private final IdentityDownloader[] mDownloaders;


	public IdentityDownloaderController(WebOfTrust wot, PluginRespirator pr, IdentityFileQueue q) {
		mWoT = wot;
		if(!USE_LEGACY_REFERENCE_IMPLEMENTATION) {
			mIdentityDownloaderSlow = new IdentityDownloaderSlow(this);
			mIdentityDownloaderFast = new IdentityDownloaderFast(this);
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

	public WebOfTrust getWebOfTrust() {
		return mWoT;
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
			(newTrust.getValue() != oldTrust.getValue())
		) : "storeTrustChangedCommandWithoutCommit() called for irrelevant Trust change!";
		
		
		for(IdentityDownloader d : mDownloaders)
			d.storeTrustChangedCommandWithoutCommit(oldTrust, newTrust);
	}

	@Override public void storeNewEditionHintCommandWithoutCommit(EditionHint hint) {
		for(IdentityDownloader d : mDownloaders)
			d.storeNewEditionHintCommandWithoutCommit(hint);
	}

	@Override public boolean getShouldFetchState(Identity identity) {
		// FIXME: This being used for the function's intended usage purpose of detecting whether an
		// Identity is not being downloaded even though it should be is insufficient:
		// It won't detect the case where e.g. the IdentityDownloaderSlow is downloading an
		// Identity but in fact it should be downloaded by the IdentityDownloaderFast.
		// In fact it won't ever detect if an Identity is not being downloaded at all even though it
		// should because IdentityDownloaderSlow will resort to returning true if it has no
		// EditionHints stored for the given Identity and WoT says it is wanted for download, so the
		// whole function here will always return true if an Identity is wanted for download. 
		// The fix of only returning true if all of the downloaders returned true won't work because
		// not every downloader downloads every identity.
		// Hence the proper fix would be to have each IdentityDownloader figure out how to self-test
		// on their own instead of having the caller of this callback interpret a return value.
		// This could be done by either having a periodic self-test run by their thread like
		// IdentityDownloaderFast does, or by introducing a "testSelf()" callback which could be
		// called instead of this function at particularly interesting points of Score computation.
		// Also see the similar FIXME inside of IdentityDownloaderSlow's implementation of this
		// function.
		
		for(IdentityDownloader d : mDownloaders) {
			if(d.getShouldFetchState(identity) == true)
				return true;
		}
		
		return false;
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
		assert(!(newIdentity instanceof OwnIdentity));
		
		for(IdentityDownloader d : mDownloaders)
			d.storePostDeleteOwnIdentityCommand(newIdentity);
	}

	@Override public void storePreDeleteIdentityCommand(Identity oldIdentity) {
		assert(oldIdentity != null);
		// It *CAN* be an OwnIdentity!
		/* assert(!(newIdentity instanceof OwnIdentity)); */
		
		for(IdentityDownloader d : mDownloaders)
			d.storePreDeleteIdentityCommand(oldIdentity);
	}

	@Override public void storePreRestoreOwnIdentityCommand(Identity oldIdentity) {
		assert(oldIdentity != null);
		assert(!(oldIdentity instanceof OwnIdentity));
		
		for(IdentityDownloader d : mDownloaders)
			d.storePreRestoreOwnIdentityCommand(oldIdentity);
	}

	@Override public void storePostRestoreOwnIdentityCommand(OwnIdentity newIdentity) {
		assert(newIdentity != null);
		// Check whether download of the OwnIdentity is enabled:
		// Formally an OwnIdentity is only eligible for download if a self-assigned Score of it
		// was created using WebOfTrust.initTrustTreeWithoutCommit().
		// In the future it may be possible to temporarily disable the download by deleting the
		// self-assigned Score or setting it to a negative value.
		// The specification of this callback doesn't allow that currently though, it specifies that
		// the download of the OwnIdentity should always be started. If this is allowed some day
		// please change the specification before removing this assert.
		assert(mWoT.shouldFetchIdentity(newIdentity))
			: "Asked to start download of restored OwnIdentity but downloading it is disallowed?";
		
		for(IdentityDownloader d : mDownloaders)
			d.storePostRestoreOwnIdentityCommand(newIdentity);
	}

	@Override public void scheduleImmediateCommandProcessing() {
		for(IdentityDownloader d : mDownloaders)
			d.scheduleImmediateCommandProcessing();
	}
}
