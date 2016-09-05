/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.network.input;

import java.io.Serializable;

import plugins.WebOfTrust.Identity;
import plugins.WebOfTrust.Identity.IdentityID;
import plugins.WebOfTrust.Persistent;
import plugins.WebOfTrust.Trust;
import plugins.WebOfTrust.WebOfTrust;
import freenet.keys.FreenetURI;
import freenet.keys.USK;

/**
 * An EditionHint advertises the latest {@link FreenetURI#getEdition() USK edition} an
 * {@link Identity} has discovered of another Identity.
 * 
 * An {@link USK} {@link FreenetURI} is an updateable key where each update is published at an
 * incremented long integer called the "edition" of the USK.
 * Determining the latest edition N can result in O(N) network queries on the Freenet network.
 * As all M WoT {@link Identity}s are published at USKs, we want to avoid creating the high
 * O(N * M) load required to determine the editions of all their USKs.
 * To do so, Identitys publish EditionHints for other identities. They're transported as "bonus"
 * payload when an Identity uploads its {@link Trust} ratings of other identities.
 * So when we download an edition of an Identity, we automatically get an EditionHint for all the
 * other Identitys it trusts.
 * 
 * (Also, keeping an USK up to date requires a constant polling load on the network, so with M
 * identities, there would be O(M) polls on the network repeating at some time interval forever.)
 * 
 * @see IdentityDownloader#storeUpdateEditionHintCommandWithoutCommit(String, String, long) */
public final class EditionHint extends Persistent implements Comparable<EditionHint> {

	/** @see Serializable */
	private static final long serialVersionUID = 1L;


	private final String mFromIdentityID;

	private final String mAboutIdentityID;

	private final long mEdition;


	EditionHint(final String fromIdentityID, final String aboutIdentityID, long edition) {
		IdentityID.constructAndValidateFromString(fromIdentityID);
		IdentityID.constructAndValidateFromString(aboutIdentityID);
		if(fromIdentityID.equals(aboutIdentityID)) {
			throw new IllegalArgumentException(
				"Identity is trying to assign edition hint to itself, ID: " + fromIdentityID);
		}
		
		if(edition < 0)
			throw new IllegalArgumentException("Invalid edition: " + edition);
		
		mFromIdentityID = fromIdentityID;
		mAboutIdentityID = aboutIdentityID;
		mEdition = edition;
	}

	@Override public int compareTo(EditionHint o) {
		// FIXME: Implement.
		return 0;
	}

	@Override public String getID() {
		// FIXME: Implement.
		return null;
	}

	@Override public void startupDatabaseIntegrityTest() throws Exception {
		activateFully();
		
		IdentityID.constructAndValidateFromString(mFromIdentityID);
		
		IdentityID.constructAndValidateFromString(mAboutIdentityID);
		
		if(mFromIdentityID.equals(mAboutIdentityID)) {
			throw new IllegalStateException(
				"mFromIdentityID == mAboutIdentityID: " + mFromIdentityID);
		}
		
		if(mEdition < 0)
			throw new IllegalStateException("mEdition < 0: " + mEdition);
		
		// mWebOfTrust is of type WebOfTrustInterface which doesn't contain special functions of
		// the specific implementation which we need for testing our stuff - so we cast to the
		// implementation.
		// This is ok to do here: This function being a debug one for the specific implementation
		// is fine to depend on technical details of it.
		WebOfTrust wot = ((WebOfTrust)mWebOfTrust);
		
		Identity from = wot.getIdentityByID(mFromIdentityID);
		Identity about = wot.getIdentityByID(mAboutIdentityID);
		
		if(wot.getBestCapacity(from) == 0) {
			throw new IllegalStateException(
				"Identity which isn't allowed to store hints has stored one: " + this);
		}
		
		if(mEdition <= about.getEdition())
			throw new IllegalStateException("Hint is obsolete: " + this);
		
		// The legacy hinting implementation Identity.getLatestEditionHint() stores only the highest
		// edition hint we received from any identity with a Score of > 0.
		if(mEdition > about.getLatestEditionHint() && wot.getBestScore(from) > 0)
			throw new IllegalStateException("Legacy getLatestEditionHint() too low for: " + about);
	}

	/**
	 * Activates to depth 1 which is the maximal depth of all getter functions.
	 * You must adjust this when introducing new member variables! */
	@Override protected void activateFully() {
		checkedActivate(1);
	}

	@Override public String toString() {
		// FIXME: Implement.
		return super.toString();
	}

}
