/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.net.MalformedURLException;
import java.util.ArrayList;

import org.junit.Before;
import org.junit.Test;

import plugins.WebOfTrust.exceptions.InvalidParameterException;
import plugins.WebOfTrust.exceptions.NotTrustedException;
import plugins.WebOfTrust.util.IdentifierHashSet;

import com.db4o.ObjectSet;

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
		ObjectSet<Score> scores = mWebOfTrust.getAllScores();
		
		Score duplicateScore = scores.get(0).clone();
		// The identities are also duplicates, so we need to store them to prevent
		// Persistent.throwIfNotStored() from complaining
		duplicateScore.getTruster().storeWithoutCommit();
		duplicateScore.getTrustee().storeWithoutCommit();
		duplicateScore.storeWithoutCommit();
		Trust duplicateTrust = trusts.get(0).clone();
		duplicateTrust.getTruster().storeWithoutCommit();
		duplicateTrust.getTrustee().storeWithoutCommit();
		duplicateTrust.storeWithoutCommit();
		Persistent.checkedCommit(mWebOfTrust.getDatabase(), this);
		
		IdentifierHashSet<Identity> idDuplicateCheck = new IdentifierHashSet<Identity>(10 * 2);
		boolean noDuplicates = true;
		for(Identity i : mWebOfTrust.getAllIdentities())
			noDuplicates &= idDuplicateCheck.add(i);
		assertFalse(noDuplicates);
		
		// FIXME: Check Scores / Trusts
		
		mWebOfTrust.deleteDuplicateObjects();
	
		idDuplicateCheck = new IdentifierHashSet<Identity>(10 * 2);
		for(Identity i : mWebOfTrust.getAllIdentities())
			assertTrue(idDuplicateCheck.add(i));
		
		// FIXME: Check Scores / Trusts
	}

	@Override protected WebOfTrust getWebOfTrust() {
		return mWebOfTrust;
	}

}
