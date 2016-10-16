/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.network.input;

import static plugins.WebOfTrust.util.AssertUtil.assertDidNotThrow;
import static plugins.WebOfTrust.util.DateUtil.roundToNearestDay;

import java.io.Serializable;
import java.util.Date;

import plugins.WebOfTrust.Identity;
import plugins.WebOfTrust.Identity.IdentityID;
import plugins.WebOfTrust.OwnIdentity;
import plugins.WebOfTrust.Persistent;
import plugins.WebOfTrust.Score;
import plugins.WebOfTrust.Trust;
import plugins.WebOfTrust.Trust.TrustID;
import plugins.WebOfTrust.WebOfTrust;
import freenet.keys.FreenetURI;
import freenet.keys.USK;
import freenet.support.CurrentTimeUTC;

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


	private final String mSourceIdentityID;

	private final String mTargetIdentityID;
	
	/** @see #getID() */
	@IndexedField
	private final String mID;

	/**
	 * The estimate Date of when the {@link Identity} which gave us the edition hint discovered
	 * the edition. This shall be the first thing we sort upon when trying to decide which hints to
	 * attempt to download first. For hints with equal dates, sorting shall fall back to
	 * {@link #mSourceCapacity}.
	 * 
	 * The closer to current time, the higher the probability that the hint is still correct, so
	 * sorting upon this first should ensure we attempt to download the best hints first. 
	 * 
	 * Since the precision of Dates being milliseconds is rather high, and thus the probability of
	 * two Date being equal is very low, this is rounded to the nearest day to:
	 * - ensure that the fallback sorting actually has a chance to happen. 
	 * - by the above also avoid malicious identities from inserting very many hints to always win
	 *   in the sorting.
	 * 
	 * @see #compareTo(EditionHint) */
	private final Date mDate;

	/**
	 * {@link WebOfTrust#getBestCapacity(Identity)} of the {@link Identity} which gave us the hint.
	 * For hints with equal {@link #mDate}, this shall be the fallback sorting key to determine
	 * which ones to download first: Hints with higher capacity must be downloaded first.
	 * 
	 * The Identitys which have received a direct {@link Trust} of an {@link OwnIdentity} are
	 * constantly being polled for new editions by the {@link IdentityDownloaderFast}, so their
	 * hints are most up to date and should be downloaded first. Sorting by capacity ensures this as
	 * they will have the highest capacity: Capacity is a direct function of {@link Score#getRank()}
	 * (see {@link WebOfTrust#capacities}) - it higher for lower {@link Score#getRank()}, so
	 * the higher it is, the closer an identity is to the users {@link OwnIdentity}s in the
	 * {@link Trust} graph. For identities directly being trusted by an OwnIdentity, the capacity
	 * will be the highest.
	 * Also as sorting by capacity means sorting by rank, we sort the download order like the
	 * shells of an onion where each shell is a rank step, or in other words: We represent the
	 * topology of the social graph through which the edition hints flow. This hopefully might
	 * ensure fast propagation of hints across the social graph.
	 * 
	 * Also notice:
	 * Normally, we would naively say the next sorting key after {@link #mDate} should be
	 * {@link #mSourceScore} as the Identitys with a high Score are the most trustworthy overall,
	 * but there is a pitfall: If the user assigns a {@link Trust} with an {@link OwnIdentity} to an
	 * Identity, its Score will be always have the same value as the  Trust, so it will always be in
	 * range [-100, 100].
	 * Remote identities can get much higher Scores than that because their Score is the sum of
	 * their Trusts weighted by the trusters' capacities. So with those Scores above 100 they
	 * could always win against OwnIdentiys. But the hints of trustees of OwnIdentitys are much more 
	 * valuable due to the USK subscriptions of {@link IdentityDownloaderFast} - which is why
	 * we sort by capacity first as aforementioned.
	 * 
	 * Furthermore, sorting by capacity is critical to ensure the most "stable" behavior of WoT
	 * - where "stable" means that the results of {@link Score} computation should not depend on
	 * order of download:
	 * The higher the capacity of an Identity, the more voting power it has in {@link Score}
	 * computation. As identities with higher capacity can give higher capacity = voting power to
	 * their trustees, by preferring to download hints with higher capacity, we prefer to download
	 * the identities with the highest potential voting power first.
	 * 
	 * The next fallback sorting key after this one is {@link #mSourceScore}.
	 * It is also ensured that fallback will actually happen:
	 * There are only 7 distinct capacities, see {@link WebOfTrust#capacities}.
	 * 
	 * @see #compareTo(EditionHint) */
	private final byte mSourceCapacity;

	/**
	 * Rounded {@link WebOfTrust#getBestScore(Identity)} of the {@link Identity} which gave us the
	 * hint. For hints with equal {@link #mSourceCapacity}, this shall be the fallback sorting key
	 * to determine which ones to download first: Hints with higher Score must be downloaded first.
	 * 
	 * As hints are accepted from any {@link Identity} with a {@link Score#getCapacity()} > 0,
	 * hints will also be accepted from Identitys with a negative Score. As a negative Score
	 * generally means "this identity is rated as a spammer by the community", we must be very
	 * careful with the hints we received from them. So by having some level of sorting based on the
	 * Score, we ensure that non-spammers are preferred.
	 * 
	 * As there are many distinct Score values, the Score is rounded to one of the two values
	 * {-1, 1} to merely represent whether the Score was < 0 or >= 0, i.e. whether the source is
	 * a distrusted or trusted Identity. Reducing the amount of distinct values is needed to ensure
	 * we have a chance to actually fallback to {@link #mEdition} as sorting key:
	 * We need to make sure that we try downloading higher editions first as a low edition is
	 * worthless if a higher one exists.
	 * 
	 * @see #compareTo(EditionHint) */
	private final byte mSourceScore;

	/**
	 * The actual edition hint itself.
	 * This is the final fallback sorting key after we sorted hints by {@link #mDate},
	 * {@link #mSourceCapacity} and {@link #mSourceScore} and all of them were equal.
	 * 
	 * Notice: We only sort by edition if {@link #mTargetIdentityID} is equal for the two
	 * EditionHint objects at comparison. Editions of different identities are completely unrelated,
	 * it wouldn't make any sense to compare them. */
	private final long mEdition;


	/** Factory with parameter validation */
	static EditionHint constructSecure(
			String sourceIdentityID, String targetIdentityID, Date date, int sourceCapacity,
			int sourceScore, long edition) {
		
		IdentityID.constructAndValidateFromString(sourceIdentityID);
		IdentityID.constructAndValidateFromString(targetIdentityID);
		if(sourceIdentityID.equals(targetIdentityID)) {
			throw new IllegalArgumentException(
				"Identity is trying to assign edition hint to itself, ID: " + sourceIdentityID);
		}
		
		if(date.after(CurrentTimeUTC.get()))
			throw new IllegalArgumentException("Invalid date: " + date);
		
		// Hints should only be accepted if the capacity is > 0. Also, the capacity should be in
		// the valid range of WebOfTrust.capacities.
		// TODO: Code quality: Use WebOfTrust.VALID_CAPCITIES once branch
		// issue-0006895-full-tests-for-Score is merged
		if(sourceCapacity < 1 || sourceCapacity > 100)
			throw new IllegalArgumentException("Invalid capacity: " + sourceCapacity);
		
		// Cannot validate the score: All values of int are acceptable Score values.

		if(edition < 0)
			throw new IllegalArgumentException("Invalid edition: " + edition);
		
		return new EditionHint(
			sourceIdentityID, targetIdentityID, date, sourceCapacity, sourceScore, edition);
	}

	/** Factory WITHOUT parameter validation */
	static EditionHint construcInsecure(
			final String sourceIdentityID, final String targetIdentityID, final Date date,
			final int sourceCapacity, final int sourceScore, final long edition) {
		
		assertDidNotThrow(new Runnable() { @Override public void run() {
			constructSecure(
				sourceIdentityID, targetIdentityID, date, sourceCapacity, sourceScore, edition);
		}});
		
		return new EditionHint(
			sourceIdentityID, targetIdentityID, date, sourceCapacity, sourceScore, edition);
	}

	private EditionHint(
			String sourceIdentityID, String targetIdentityID, Date date, int sourceCapacity,
			int sourceScore, long edition) {
		
		mSourceIdentityID = sourceIdentityID;
		mTargetIdentityID = targetIdentityID;
		mDate = roundToNearestDay(date);
		mSourceCapacity = (byte)sourceCapacity;
		mSourceScore = sourceScore >= 0 ? (byte)1 : (byte)-1;
		mEdition = edition;
		mID = new TrustID(mSourceIdentityID, mTargetIdentityID).toString();
	}

	String getSourceIdentityID() {
		// String is a db4o primitive type so 1 is enough even though it is a reference type
		checkedActivate(1);
		return mSourceIdentityID;
	}

	String getTargetIdentityID() {
		// String is a db4o primitive type so 1 is enough even though it is a reference type
		checkedActivate(1);
		return mTargetIdentityID;
	}

	long getEdition() {
		checkedActivate(1);
		return mEdition;
	}

	/** Returns the sort ordering which the {@link IdentityDownloader} should use. */
	@Override public int compareTo(EditionHint o) {
		this.activateFully();
		o.activateFully();
		
		int dateCompared = mDate.compareTo(o.mDate);
		if(dateCompared != 0)
			return dateCompared;
		
		int capacityCompared = Byte.compare(mSourceCapacity, o.mSourceCapacity);
		if(capacityCompared != 0)
			return capacityCompared;
		
		int scoreCompared = Byte.compare(mSourceScore, o.mSourceScore);
		if(scoreCompared != 0)
			return scoreCompared;

		if(mTargetIdentityID.equals(o.mTargetIdentityID))
			return Long.compare(mEdition, o.mEdition);
		else
			return 0; // No sense in comparing edition of different target identities
	}

	/** Equals: <code>new TrustID(getSourceIdentityID(), getTargetIdentityID()).toString()</code> */
	@Override public String getID() {
		// String is a db4o primitive type so 1 is enough even though it is a reference type
		checkedActivate(1);
		return mID;
	}

	@Override public void startupDatabaseIntegrityTest() throws Exception {
		activateFully();
		
		// Will throw if any of the three passed member variables is invalid.
		// mID is generated from them, so we keep the returned object to validate it.
		EditionHint this2 = constructSecure(
			mSourceIdentityID, mTargetIdentityID, mDate, mSourceCapacity, mSourceScore, mEdition);
		
		if(!mID.equals(this2.mID))
			throw new IllegalStateException("mID invalid: " + this);
		
		// mWebOfTrust is of type WebOfTrustInterface which doesn't contain special functions of
		// the specific implementation which we need for testing our stuff - so we cast to the
		// implementation.
		// This is ok to do here: This function being a debug one for the specific implementation
		// is fine to depend on technical details of it.
		WebOfTrust wot = ((WebOfTrust)mWebOfTrust);
		
		Identity source = wot.getIdentityByID(mSourceIdentityID);
		Identity target = wot.getIdentityByID(mTargetIdentityID);
		
		if(wot.getBestCapacity(source) == 0) {
			throw new IllegalStateException(
				"Identity which isn't allowed to store hints has stored one: " + this);
		}
		
		if(mEdition <= target.getEdition())
			throw new IllegalStateException("Hint is obsolete: " + this);
		
		// The legacy hinting implementation Identity.getLatestEditionHint() stores only the highest
		// edition hint we received from any identity with a Score of > 0.
		if(mEdition > target.getLatestEditionHint() && wot.getBestScore(source) > 0)
			throw new IllegalStateException("Legacy getLatestEditionHint() too low for: " + target);
		
		// FIXME: Once IdentityDownloader has a query function for obtaining an EditionHint by mID,
		// check whether there is only one edition hint for the given ID.
	}

	/**
	 * Activates to depth 1 which is the maximal depth of all getter functions.
	 * You must adjust this when introducing new member variables! */
	@Override protected void activateFully() {
		checkedActivate(1);
	}

	@Override public String toString() {
		activateFully();
		return "[EditionHint: " + super.toString()
		     + "; mID: " + mID
		     + "; mSourceIdentityID: " + mSourceIdentityID
		     + "; mTargetIdentityID: " + mTargetIdentityID
		     + "; mEdition: " + mEdition
		     + "]";
	}

}
