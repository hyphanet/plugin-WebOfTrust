/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.net.MalformedURLException;
import java.util.ArrayList;

import org.junit.Before;
import org.junit.Test;

import plugins.WebOfTrust.exceptions.InvalidParameterException;
import plugins.WebOfTrust.exceptions.NotTrustedException;
import plugins.WebOfTrust.util.IdentifierHashSet;

/**
 * Tests class {@link WebOfTrust}.
 * NOTICE: {@link WoTTest} also tests that class. It is pending to be merged with this one. */
public class WebOfTrustTest extends AbstractJUnit4BaseTest {

	/** A random WebOfTrust: Random {@link OwnIdentity}s, {@link Trust}s, {@link Score}s */
	private WebOfTrust mWebOfTrust;
	
	@Before public void setUp() {
		mWebOfTrust = constructEmptyWebOfTrust();
	}

	@Test public void testDeleteDuplicateObjects()
			throws InvalidParameterException, MalformedURLException, NotTrustedException {
		
		ArrayList<Identity> identities = addRandomIdentities(2, 10);
		ArrayList<Trust> trusts = addRandomTrustValues(identities, 5);
		// Don't keep an ObjectSet, but rather an ArrayList to avoid random changes of the ObjectSet
		// due to db4o bugs which do happen if we use the ObjectSet long after doing all our
		// modifications of the database. This would cause the last line of the test to fail:
		//     assertEquals(new IdentifierHashSet<Score>(scores), scoreDuplicateCheck);
		// TODO: Investigate whether a newer db4o version fixes this. To do so, change this variable
		// to store the original ObjectSet, and repeat the test many times with both the old and
		// new db4o version, like this:
		// ant clean ; i=0 ;
		// while ant -Dtest.skip=false -Dtest.class=plugins.WebOfTrust.WebOfTrustTest junit ; do
		//     echo $((++i)) ;
		// done
		// Bugtracker entry: https://bugs.freenetproject.org/view.php?id=6819
		ArrayList<Score> scores = new ArrayList<Score>(mWebOfTrust.getAllScores());
		
		// We now produce duplicates.
		// We do so by copying an Identity and *all* its related Trusts and Scores. We don't just
		// copy a single Trust/Score to allow the following test:
		// After deleting the duplicates, we want to compare whether the database matches what it
		// was before creating them.
		// This wouldn't work if the duplicate-deletion code deleted the original Identity and the
		// duplicate didn't have all its Trusts/Scores.
		Identity identity = identities.get(0);
		Identity duplicate = identity.clone();
		duplicate.storeWithoutCommit();
		boolean identityHadNoTrusts = true;
		for(Trust t : mWebOfTrust.getGivenTrusts(identity)) {
			new Trust(mWebOfTrust, duplicate, t.getTrustee(), t.getValue(), t.getComment())
				.storeWithoutCommit();
			identityHadNoTrusts = false;
		}
		for(Trust t : mWebOfTrust.getReceivedTrusts(identity)) {
			new Trust(mWebOfTrust, t.getTruster(), duplicate, t.getValue(), t.getComment())
				.storeWithoutCommit();
			identityHadNoTrusts = false;
		}
		if(identity instanceof OwnIdentity) {
			for(Score s : mWebOfTrust.getGivenScores((OwnIdentity)identity)) {
				new Score(mWebOfTrust, (OwnIdentity)duplicate, s.getTrustee(), s.getScore(),
					s.getRank(), s.getCapacity()).storeWithoutCommit();
			}
		}
		for(Score s : mWebOfTrust.getScores(identity)) {
			new Score(mWebOfTrust, s.getTruster(), duplicate, s.getScore(), s.getRank(),
				s.getCapacity()).storeWithoutCommit();
		}
		Persistent.checkedCommit(mWebOfTrust.getDatabase(), this);
		
		IdentifierHashSet<Identity> idDuplicateCheck = new IdentifierHashSet<Identity>(10 * 2);
		boolean noDuplicates = true;
		for(Identity i : mWebOfTrust.getAllIdentities())
			noDuplicates &= idDuplicateCheck.add(i);
		assertFalse(noDuplicates);
		
		IdentifierHashSet<Trust> trustDuplicateCheck = new IdentifierHashSet<Trust>(5 * 2);
		noDuplicates = true;
		for(Trust t : mWebOfTrust.getAllTrusts())
			noDuplicates &= trustDuplicateCheck.add(t);
		// Normally we would just assertTrue(noDuplicates == false), but since we generate the Trust
		// graph randomly, it is possible that the Identity we duplicated had no Trusts, and
		// therefore we failed to generate duplicate Trusts in this test run.
		assertTrue(noDuplicates == false || identityHadNoTrusts);
		
		IdentifierHashSet<Score> scoreDuplicateCheck = new IdentifierHashSet<Score>(20 * 2);
		noDuplicates = true;
		for(Score s : mWebOfTrust.getAllScores())
			noDuplicates &= scoreDuplicateCheck.add(s);
		assertFalse(noDuplicates);
		
		mWebOfTrust.deleteDuplicateObjects();
	
		idDuplicateCheck = new IdentifierHashSet<Identity>(10 * 2);
		for(Identity i : mWebOfTrust.getAllIdentities())
			assertTrue(idDuplicateCheck.add(i));
		assertEquals(new IdentifierHashSet<Identity>(identities), idDuplicateCheck);
		
		trustDuplicateCheck = new IdentifierHashSet<Trust>(5 * 2);
		for(Trust t : mWebOfTrust.getAllTrusts())
			assertTrue(trustDuplicateCheck.add(t));
		assertEquals(new IdentifierHashSet<Trust>(trusts), trustDuplicateCheck);
		
		scoreDuplicateCheck = new IdentifierHashSet<Score>(5 * 2);
		for(Score s : mWebOfTrust.getAllScores())
			assertTrue(scoreDuplicateCheck.add(s));
		assertEquals(new IdentifierHashSet<Score>(scores), scoreDuplicateCheck);
	}

	/**
	 * Currently empty because {@link ScoreTest#testStoreWithoutCommit()} covers most of what
	 * this test should do.
	 * TODO: Code quality: Test things which the above doesn't cover. This might for example be:
	 * - whether getScore() does throw upon duplicate Score objects.
	 * - whether getScore() works if unrelated Score objects exist.
	 * For more ideas see the output of Cobertura (can be run with "ant -Dtest.coverage=true") */
	@Test public void testGetScoreOwnIdentityIdentity() {
		
	}

	@Override protected WebOfTrust getWebOfTrust() {
		return mWebOfTrust;
	}

}
