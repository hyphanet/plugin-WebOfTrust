/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.network.input;

import plugins.WebOfTrust.Identity;
import plugins.WebOfTrust.OwnIdentity;
import plugins.WebOfTrust.Trust;
import plugins.WebOfTrust.WebOfTrust;
import plugins.WebOfTrust.util.Daemon;

/** Downloads {@link Identity} objects from the P2P network. */
public interface IdentityDownloader extends Daemon {

	/**
	 * Called by {@link WebOfTrust} as soon as {@link WebOfTrust#shouldFetchIdentity(Identity)}
	 * changes from false to true for the given {@link Identity}. This is usually the case when
	 * *any* {@link OwnIdentity} has rated it as trustworthy enough for us to download it.
	 * 
	 * FIXME: Only require {@link Identity#getID()} as parameter. Implementations likely don't need
	 * anything except the ID. */
	void storeStartFetchCommandWithoutCommit(Identity identity);

	/**
	 * Called by {@link WebOfTrust} as soon as {@link WebOfTrust#shouldFetchIdentity(Identity)}
	 * changes from true to false for the given {@link Identity}. This is usually the case when not
	 * even one {@link OwnIdentity} has rated it as trustworthy enough for us to download it.
	 * 
	 * FIXME: Only require {@link Identity#getID()} as parameter. Implementations likely don't need
	 * anything except the ID. */
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
	 * Specifically: {@link WebOfTrust#checkForDatabaseLeaks()} uses this for debugging. */
	void deleteAllCommands();

}
