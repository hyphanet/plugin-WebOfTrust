/* This code is part of WoT, a plugin for Freenet. It is distributed
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.Before;
import org.junit.Test;

import plugins.WebOfTrust.exceptions.InvalidParameterException;
import plugins.WebOfTrust.exceptions.UnknownIdentityException;
import freenet.support.TimeUtil;

public final class ScoreComputationBenchmark extends AbstractFullNodeTest {

	@Before
	public void checkThatAssertionsAreDisabled() {
		assert(false)
			: "WOT has very sophisticated assertions which can impact performance a lot, so please "
			+ "disable them for all classes running these benchmarks. ";
	}

	@Test
	public void benchmark() throws MalformedURLException, InvalidParameterException,
			NumberFormatException, UnknownIdentityException {
		
		final int ownIdentityCount = 3;
		final int identityCount = 1000;
		final int trustCount = 10 * 1000;
		
        assert(trustCount < identityCount * (identityCount-1));
		
		WebOfTrust wot = getWebOfTrust();
		ArrayList<OwnIdentity> ownIds = new ArrayList<OwnIdentity>(ownIdentityCount + 1);
		ArrayList<Identity> ids = new ArrayList<Identity>(identityCount + ownIdentityCount + 1);
		
		System.out.println("Creating " + ownIdentityCount + " OwnIdentitys ...");
		for(int i = 0; i < ownIdentityCount; ++i)
			ownIds.add(wot.createOwnIdentity(Integer.toString(i), true, null));
		
		ids.addAll(ownIds);
		
		System.out.println("Creating " + identityCount + " Identitys ...");
		for(int i = 0; i < identityCount; ++i)
			ids.add(wot.addIdentity(getRandomRequestURI().toString()));
		
		long startTime = System.nanoTime();
		for(int i = 0; i < trustCount; ++i) {
			System.out.println("Setting trust " + i + " ...");
			
			int truster;
			int trustee;
			
			do {
				truster = mRandom.nextInt(identityCount);
				trustee = mRandom.nextInt(identityCount);
			} while(truster == trustee);
			
			wot.setTrust(ids.get(truster), ids.get(trustee), getRandomTrustValue(), "");
		}
		long endTime = System.nanoTime();
		long ms = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
		
		System.out.println("Total time: " + TimeUtil.formatTime(ms, 3, true));
		System.out.println("Full Score recomputations: "
			+ mWebOfTrust.getNumberOfFullScoreRecomputations());
	}

}
