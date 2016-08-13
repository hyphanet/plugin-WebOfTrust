/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.network.input;

import plugins.WebOfTrust.Identity;
import plugins.WebOfTrust.IdentityFetcher;
import plugins.WebOfTrust.OwnIdentity;
import plugins.WebOfTrust.Trust;
import plugins.WebOfTrust.WebOfTrust;
import plugins.WebOfTrust.util.Daemon;

/**
 * Downloads {@link Identity} objects from the P2P network.
 * 
 * ATTENTION: Implementations must NOT store a object references to {@link Identity} objects
 * inside of their db4o database. They should only store indirect references such as
 * {@link Identity#getID()}. This ensures that unrelated code which deletes {@link Identity}
 * objects from the database does not need to have take care of the IdentityDownloaders'
 * databases.
 * (This already is a requirement of the implementation of at least
 * {@link WebOfTrust#deleteWithoutCommit(Identity)}, {@link WebOfTrust#deleteOwnIdentity(String)}
 * and {@link WebOfTrust#restoreOwnIdentityWithoutCommit(freenet.keys.FreenetURI)}
 * but possibly also of other stuff. Further, it will possibly allow decoupling of table locks in a
 * future SQL port of WoT.)
 * 
 * FIXME: Review the whole of class {@link IdentityFetcher} for any important JavaDoc such as the
 * above "ATTENTION" and add it to this interface. */
public interface IdentityDownloader extends Daemon {

	/**
	 * Called by {@link WebOfTrust} as soon as {@link WebOfTrust#shouldFetchIdentity(Identity)}
	 * changes from false to true for the given {@link Identity}. This is usually the case when
	 * *any* {@link OwnIdentity} has rated it as trustworthy enough for us to download it.
	 * 
	 * FIXME: Only require {@link Identity#getID()} as parameter to enforce the "ATTENTION" at the
	 * JavaDoc of this interface. Implementations likely don't need anything but the ID anyway. */
	void storeStartFetchCommandWithoutCommit(Identity identity);

	/**
	 * Called by {@link WebOfTrust} as soon as {@link WebOfTrust#shouldFetchIdentity(Identity)}
	 * changes from true to false for the given {@link Identity}. This is usually the case when not
	 * even one {@link OwnIdentity} has rated it as trustworthy enough for us to download it.
	 * 
	 * FIXME: Only require {@link Identity#getID()} as parameter to enforce the "ATTENTION" at the
	 * JavaDoc of this interface. Implementations likely don't need anything but the ID anyway. */
	void storeAbortFetchCommandWithoutCommit(Identity identity);

	/**
	 * Called by {@link WebOfTrust} when we've downloaded the list of {@link Trust} values of a
	 * remote {@link Identity} and as a bonus payload have received an "edition hint" for another
	 * {@link Identity} it has assigned a {@link Trust} to. An edition hint is the number of the
	 * latest edition of the given {@link Identity} as claimed by a remote identity. We can try to
	 * download the hint and if it is indeed downloadable, we are lucky - but it may very well be a
	 * lie. In that case, to avoid DoS, we must discard it and try the next lower hint we received
	 * from someone else.
	 *
	 * FIXME: The edition hint is currently stored in the {@link Identity#getLatestEditionHint()}
	 * of the given {@link Identity}. Instead, this function should receive it as a parameter and
	 * the storage should be responsibility of this IdentityDownoader. This will allow it to use a
	 * suitable data structure for storage and queuing.
	 * Also, we currently discard the information of which remote Identity we've received the hint
	 * from. We should also track that information by passing it to this function as a parameter and
	 * having the IdentityDownloader store it. It can then use that information for punishment of
	 * DoS attempts.
	 * 
	 * @param identityID See {@link Identity#getID()}. */
	void storeUpdateEditionHintCommandWithoutCommit(String identityID);

	/**
	 * ATTENTION: For debugging purposes only.
	 * 
	 * Returns the effective state of whether the downloader will download an {@link Identity}
	 * = returns what was last instructed to this downloader using
	 * {@link #storeStartFetchCommandWithoutCommit(Identity)}
	 * or {@link #storeAbortFetchCommandWithoutCommit(Identity)}:
	 * True if the last command was one for starting the fetch, false if it was for stopping it.
	 * 
	 * This considers both queued commands as well as already processed commands.
	 * It will also check for contradictory commands in the command queue which would be a bug
	 * (= both start and stop command at once).
	 *
	 * You must synchronize upon this IdentityDownloader while calling this function.
	 * 
	 * @param identityID See {@link Identity#getID()}. */
	boolean getShouldFetchState(String identityID);

	/**
	 * ATTENTION: For debugging purposes only.
	 * 
	 * Specifically: {@link WebOfTrust#checkForDatabaseLeaks()} uses this for debugging. */
	void deleteAllCommands();

}
