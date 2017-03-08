/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import java.net.MalformedURLException;
import java.util.ArrayList;

import plugins.WebOfTrust.exceptions.InvalidParameterException;
import plugins.WebOfTrust.exceptions.NotTrustedException;
import freenet.crypt.DummyRandomSource;

/**
 * Copy-paste of relevant parts of
 * {@link WoTTest#test_RestoreOwnIdentity_DeleteOwnIdentity_Chained()} to demonstrate a potential
 * bug in {@link WebOfTrust#computeAllScoresWithoutCommit()}:
 * It will fail with an {@link AssertionError} because it has computed a rank of "none" where
 * {@link WebOfTrust#computeRankFromScratch()} has computed a rank of 13.
 * Amending computeRankFromScratch() to dump a trace of the Trust steps it has taken to compute
 * that rank shows that all trusts are positive and thus the rank of 13 is valid.
 * 
 * Bugtracker entry: https://bugs.freenetproject.org/view.php?id=6599 */
public class Issue0006599 extends AbstractJUnit3BaseTest {
	
	private final String requestUriO = "USK@sdFxM0Z4zx4-gXhGwzXAVYvOUi6NRfdGbyJa797bNAg,ZP4aASnyZax8nYOvCOlUebegsmbGQIXfVzw7iyOsXEc,AQACAAE/WebOfTrust/0";

	@Override
	public void setUp() throws Exception {
		super.setUp();
		
		long seed = -1625659661486645598L;
		mRandom = new DummyRandomSource(seed);
		System.out.println(this + " Random seed: " + seed);
	}

	
	public void testComputeAllScoresWithoutCommitBug() throws MalformedURLException, InvalidParameterException {
		final int identityCount = 100;
		final int trustCount = 283;
		
		final ArrayList<Identity> identities = addRandomIdentities(identityCount);
		
		final Identity identityToConvert = mWoT.addIdentity(requestUriO);
		identities.add(identityToConvert);
		
		// At least one own identity needs to exist to ensure that scores are computed.
		final OwnIdentity ownIdentity = mWoT.createOwnIdentity(getRandomSSKPair()[0], "Test", true, "Test");
		identities.add(ownIdentity); 
		
		addRandomTrustValues(identities, trustCount);
	}

	protected void addRandomTrustValues(final ArrayList<Identity> identities, final int trustCount) throws InvalidParameterException {
		final int identityCount = identities.size();
		
		mWoT.beginTrustListImport();
		for(int i=0; i < trustCount; ++i) {
			Identity truster = identities.get(mRandom.nextInt(identityCount));
			Identity trustee = identities.get(mRandom.nextInt(identityCount));
			
			if(truster == trustee) { // You cannot assign trust to yourself
				--i;
				continue;
			}
			
			try {
				// Only one trust value can exist between a given pair of identities:
				// We are bound to generate an amount of trustCount values,
				// so we have to check whether this pair of identities already has a trust.
				mWoT.getTrust(truster, trustee);
				--i;
				continue;
			} catch(NotTrustedException e) {}
			
			
			mWoT.setTrustWithoutCommit(truster, trustee, getRandomTrustValue(), getRandomLatinString(mRandom.nextInt(Trust.MAX_TRUST_COMMENT_LENGTH+1)));
		}
		mWoT.finishTrustListImport();

		Persistent.checkedCommit(mWoT.getDatabase(), this);
	}

	@Deprecated
	private byte getRandomTrustValue() {
		final double trustRange = Trust.MAX_TRUST_VALUE - Trust.MIN_TRUST_VALUE + 1;
		long result;
		do {
			result = Math.round(mRandom.nextGaussian()*(trustRange/2) + (trustRange/3));
		} while(result < Trust.MIN_TRUST_VALUE || result > Trust.MAX_TRUST_VALUE);
		
		return (byte)result;
	}
	
}
