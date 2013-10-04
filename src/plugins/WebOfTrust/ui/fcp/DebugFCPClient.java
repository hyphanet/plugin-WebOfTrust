package plugins.WebOfTrust.ui.fcp;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import plugins.WebOfTrust.Identity;
import plugins.WebOfTrust.Persistent;
import plugins.WebOfTrust.Score;
import plugins.WebOfTrust.Trust;
import plugins.WebOfTrust.WebOfTrust;
import freenet.support.Executor;
import freenet.support.Logger;
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

	private HashMap<String, Identity> mReceivedIdentities = null; // Cannot be constructed here because the super constructor needs it
	
	private final HashMap<String, Trust> mReceivedTrusts = new HashMap<String, Trust>();
	
	private final HashMap<String, Score> mReceivedScores = new HashMap<String, Score>();
	
	/** Automatically set to true by {@link Logger} if the log level is set to {@link LogLevel#DEBUG} for this class.
	 * Used as performance optimization to prevent construction of the log strings if it is not necessary. */
	private static transient volatile boolean logDEBUG = false;
	
	/** Automatically set to true by {@link Logger} if the log level is set to {@link LogLevel#MINOR} for this class.
	 * Used as performance optimization to prevent construction of the log strings if it is not necessary. */
	private static transient volatile boolean logMINOR = false;
	
	static {
		// Necessary for automatic setting of logDEBUG and logMINOR
		Logger.registerClass(DebugFCPClient.class);
	}

	
	private DebugFCPClient(final WebOfTrust myWebOfTrust, final Executor myExecutor, Map<String, Identity> identityStorage) {
		super(myWebOfTrust, identityStorage, myWebOfTrust.getPluginRespirator(), myExecutor);
	}
	
	public static DebugFCPClient construct(final WebOfTrust myWebOfTrust) {
		final HashMap<String, Identity> identityStorage = new HashMap<String, Identity>();
		final DebugFCPClient client = new DebugFCPClient(myWebOfTrust, myWebOfTrust.getPluginRespirator().getNode().executor, identityStorage);
		client.mReceivedIdentities = identityStorage;
		return client;
	}

	@Override
	void handleConnectionEstablished() {
		if(logMINOR) Logger.minor(this, "handleConnectionEstablished()");
		// FIXME Auto-generated method stub

	}

	@Override
	void handleConnectionLost() {
		if(logMINOR) Logger.minor(this, "handleConnectionLost()");
		// FIXME Auto-generated method stub

	}

	@Override
	void handleIdentitiesSynchronization(Collection<Identity> allIdentities) {
		if(logMINOR) Logger.minor(this, "handleIdentitiesSynchronization()");
		putSynchronization(allIdentities, mReceivedIdentities);
	}

	@Override
	void handleTrustsSynchronization(Collection<Trust> allTrusts) {
		if(logMINOR) Logger.minor(this, "handleTrustsSynchronization()");
		putSynchronization(allTrusts, mReceivedTrusts);
	}

	@Override
	void handleScoresSynchronization(Collection<Score> allScores) {
		if(logMINOR) Logger.minor(this, "handleScoresSynchronization()");
		putSynchronization(allScores, mReceivedScores);
	}

	<T extends Persistent> void putSynchronization(final Collection<T> source, final HashMap<String, T> target) {
		if(target.size() > 0) {
			Logger.normal(this, "Received additional synchronization, validating existing data against it...");

			if(source.size() != target.size())
				Logger.error(this, "Size mismatch: " + source.size() + " != " + target.size());
			else {
				for(final T expected : source) {
					final T existing = target.get(expected);
					if(existing == null)
						Logger.error(this, "Not found: " + expected);
					else if(!existing.equals(expected))
						Logger.error(this, "Not equals: " + expected + " to " + existing);
				}
			}
			target.clear();
		}

		for(final T p : source) {
			target.put(p.getID(), p);
		}
	}

	@Override
	void handleIdentityChangedNotification(Identity oldIdentity,
			Identity newIdentity) {
		if(logMINOR) Logger.minor(this, "handleIdentityChangedNotification()");
		// FIXME Auto-generated method stub
		
	}

	@Override
	void handleTrustChangedNotification(Trust oldTrust, Trust newTrust) {
		if(logMINOR) Logger.minor(this, "handleTrustChangedNotification()");
		// FIXME Auto-generated method stub
		
	}

	@Override
	void handleScoreChangedNotification(Score oldScore, Score newScore) {
		if(logMINOR) Logger.minor(this, "handleScoreChangedNotification()");
		// FIXME Auto-generated method stub

	}

}
