package plugins.WebOfTrust.ui.fcp;

import java.util.Collection;
import java.util.Map;

import plugins.WebOfTrust.Identity;
import plugins.WebOfTrust.Score;
import plugins.WebOfTrust.Trust;
import plugins.WebOfTrust.WebOfTrust;
import freenet.support.Executor;
import freenet.support.Logger.LogLevel;

/**
 * A FCP client which can connect to WOT itself for debugging:
 * 
 * It is able to validate the data it has received via FCP against the actual data in the WOT database.
 * This serves as an online test for the event-notifications code:
 * - If WOT is run with logging set to {@link LogLevel#DEBUG}, the reference client will be run inside of WOT and connect to it.
 * - It will store ALL {@link Identity}, {@link Trust} and {@link Score} objects received via FCP.
 * - At shutdown, it will compare the final state of what it has received against whats stored in the regular WOT database
 * - If both datasets match, the test has succeeded.
 * FIXME: Document this in developer-documentation/Debugging.txt
 * 
 * @author xor (xor@freenetproject.org)
 */
public final class DebugFCPClient extends FCPClientReferenceImplementation {
	

	public DebugFCPClient(WebOfTrust myWebOfTrust, Executor myExecutor, Map<String, Identity> identityStorage) {
		super(myWebOfTrust, identityStorage, myWebOfTrust.getPluginRespirator(), myExecutor);
		// FIXME Auto-generated constructor stub
	}

	@Override
	void handleConnectionEstablished() {
		// FIXME Auto-generated method stub

	}

	@Override
	void handleConnectionLost() {
		// FIXME Auto-generated method stub

	}

	@Override
	void handleIdentitiesSynchronization(Collection<Identity> allIdentities) {
		// FIXME Auto-generated method stub
		
	}

	@Override
	void handleTrustsSynchronization(Collection<Trust> allTrusts) {
		// FIXME Auto-generated method stub
		
	}

	@Override
	void handleScoresSynchronization(Collection<Score> allScores) {
		// FIXME Auto-generated method stub
		
	}

	@Override
	void handleIdentityChangedNotification(Identity oldIdentity,
			Identity newIdentity) {
		// FIXME Auto-generated method stub
		
	}

	@Override
	void handleTrustChangedNotification(Trust oldTrust, Trust newTrust) {
		// FIXME Auto-generated method stub
		
	}

	@Override
	void handleScoreChangedNotification(Score oldScore, Score newScore) {
		// FIXME Auto-generated method stub
		
	}

}
