package plugins.WebOfTrust.ui.fcp;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import plugins.WebOfTrust.Identity;
import plugins.WebOfTrust.Persistent;
import plugins.WebOfTrust.Score;
import plugins.WebOfTrust.SubscriptionManager.IdentitiesSubscription;
import plugins.WebOfTrust.SubscriptionManager.ScoresSubscription;
import plugins.WebOfTrust.SubscriptionManager.TrustsSubscription;
import plugins.WebOfTrust.Trust;
import plugins.WebOfTrust.WebOfTrust;

import com.db4o.ObjectSet;

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
 * 
 * @author xor (xor@freenetproject.org)
 */
public final class DebugFCPClient extends FCPClientReferenceImplementation {

	private final WebOfTrust mWebOfTrust;
	
	/**
	 * Stores the {@link Identity} objects which we have received via FCP as part of the {@link IdentitiesSubscription}.
	 * Key = {@link Identity#getID()}.
	 */
	private HashMap<String, Identity> mReceivedIdentities = null; // Cannot be constructed here because the super constructor needs it
	
	/**
	 * Stores the {@link Trust} objects which we have received via FCP as part of the {@link TrustsSubscription}.
	 * Key = {@link Trust#getID()}.
	 */
	private final HashMap<String, Trust> mReceivedTrusts = new HashMap<String, Trust>();
	
	/**
	 * Stores the {@link Score} objects which we have received via FCP as part of the {@link ScoresSubscription}.
	 * Key = {@link Score#getID()}.
	 */
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
		mWebOfTrust = myWebOfTrust;
	}
	
	public static DebugFCPClient construct(final WebOfTrust myWebOfTrust) {
		final HashMap<String, Identity> identityStorage = new HashMap<String, Identity>();
		final DebugFCPClient client = new DebugFCPClient(myWebOfTrust, myWebOfTrust.getPluginRespirator().getNode().executor, identityStorage);
		client.mReceivedIdentities = identityStorage;
		return client;
	}
	
	@Override
	public void start() {
		super.start();
		subscribe(SubscriptionType.Identities);
		subscribe(SubscriptionType.Trusts);
		subscribe(SubscriptionType.Scores);
	}
	
	@Override
	public void stop() { 
		super.stop();
		
		Logger.normal(this, "terminate(): Amending edition hints...");
		// Event-notifications does not propagate edition hints because that would cause a lot of traffic so we need to set them manually
		final ObjectSet<Identity> allIdentities = mWebOfTrust.getAllIdentities();
		for(final Identity identity : allIdentities) {
			final Identity received = mReceivedIdentities.get(identity.getID());
			if(received == null)
				continue;
			
			received.forceSetNewEditionHint(identity.getLatestEditionHint());
		}
		
		Logger.normal(this, "terminate(): Validating received data against WOT database...");
		validateAgainstDatabase(allIdentities, mReceivedIdentities);
		validateAgainstDatabase(mWebOfTrust.getAllTrusts(), mReceivedTrusts);
		validateAgainstDatabase(mWebOfTrust.getAllScores(), mReceivedScores);
		Logger.normal(this, "terminate() finished.");
	}
	
	private <T extends Persistent> void validateAgainstDatabase(final ObjectSet<T> expectedSet, final HashMap<String, T> actualSet) {
		if(actualSet.size() != expectedSet.size())
			Logger.error(this, "Size mismatch for " + actualSet + ": actual size " + actualSet.size() + " != expected size " + expectedSet.size());
		
		for(final T expected : expectedSet) {
			final T actual = actualSet.get(expected.getID());
			if(actual == null || !actual.equals(expected))
				Logger.error(this, "Mismatch: actual " + actual + " not equals() to expected " + expected);
		}
	}

	@Override
	void handleConnectionEstablished() {
		if(logMINOR) Logger.minor(this, "handleConnectionEstablished()");
	}

	@Override
	void handleConnectionLost() {
		if(logMINOR) Logger.minor(this, "handleConnectionLost()");
		
		mReceivedIdentities.clear();
		mReceivedTrusts.clear();
		mReceivedScores.clear();
	}

	@Override
	void handleIdentitiesSynchronization(Collection<Identity> allIdentities) {
		if(logMINOR) Logger.minor(this, "handleIdentitiesSynchronization()...");
		putSynchronization(allIdentities, mReceivedIdentities);
		if(logMINOR) Logger.minor(this, "handleIdentitiesSynchronization() finished.");
	}

	@Override
	void handleTrustsSynchronization(Collection<Trust> allTrusts) {
		if(logMINOR) Logger.minor(this, "handleTrustsSynchronization()...");
		putSynchronization(allTrusts, mReceivedTrusts);
		if(logMINOR) Logger.minor(this, "handleTrustsSynchronization() finished.");
	}

	@Override
	void handleScoresSynchronization(Collection<Score> allScores) {
		if(logMINOR) Logger.minor(this, "handleScoresSynchronization()...");
		putSynchronization(allScores, mReceivedScores);
		if(logMINOR) Logger.minor(this, "handleScoresSynchronization() finished.");
	}

	<T extends Persistent> void putSynchronization(final Collection<T> source, final HashMap<String, T> target) {
		if(target.size() > 0) {
			Logger.normal(this, "Received additional synchronization, validating existing data against it...");

			if(source.size() != target.size())
				Logger.error(this, "Size mismatch: received size " + source.size() + " != existing size " + target.size());
			else {
				for(final T expected : source) {
					final T existing = target.get(expected);
					if(existing == null)
						Logger.error(this, "Not found: expected " + expected);
					else if(!existing.equals(expected))
						Logger.error(this, "Not equals: expected " + expected + " to existing " + existing);
				}
			}
			target.clear();
		}

		for(final T p : source) {
			target.put(p.getID(), p);
		}
	}

	@Override
	void handleIdentityChangedNotification(Identity oldIdentity, Identity newIdentity) {
		if(logMINOR) Logger.minor(this, "handleIdentityChangedNotification(): old=" + oldIdentity + "; new=" + newIdentity);
		putNotification(new ChangeSet<Identity>(oldIdentity, newIdentity), mReceivedIdentities);
	}

	@Override
	void handleTrustChangedNotification(Trust oldTrust, Trust newTrust) {
		if(logMINOR) Logger.minor(this, "handleTrustChangedNotification(): old=" + oldTrust + "; new=" + newTrust);
		putNotification(new ChangeSet<Trust>(oldTrust, newTrust), mReceivedTrusts);
	}

	@Override
	void handleScoreChangedNotification(Score oldScore, Score newScore) {
		if(logMINOR) Logger.minor(this, "handleScoreChangedNotification(): old=" + oldScore + "; new=" + newScore);
		putNotification(new ChangeSet<Score>(oldScore, newScore), mReceivedScores);
	}
	
	<T extends Persistent> void putNotification(final ChangeSet<T> changeSet, final HashMap<String, T> target) {
		// Check validity of existing data
		if(changeSet.beforeChange != null) {
			final T currentBeforeChange = target.get(changeSet.beforeChange.getID());
			if(!currentBeforeChange.equals(changeSet.beforeChange))
				Logger.error(this, "Existing data is not equals() to changeSet.beforeChange: currentBeforeChange=" + currentBeforeChange + "; changeSet=" + changeSet);
			
			if(changeSet.afterChange != null && currentBeforeChange.equals(changeSet.afterChange)) {
				if(!changeSet.beforeChange.equals(changeSet.afterChange))
					Logger.warning(this, "Received useless notification, we already have this data: " + changeSet);
				else
					Logger.warning(this, "Received notification which changed nothing: " + changeSet);
			}
		} else {
			if(target.containsKey(changeSet.afterChange.getID()))
				Logger.error(this, "ChangeSet claims to create the object but we already have it: existing="  
					+ target.get(changeSet.afterChange.getID()) + "; changeSet=" + changeSet);
		}
		
		if(changeSet.afterChange != null) {
			/* Checked in changeSet already */
			// assert(changeSet.beforeChange.getID(), changeSet.afterChange.getID()); 
			target.put(changeSet.afterChange.getID(), changeSet.afterChange);
		} else
			target.remove(changeSet.beforeChange.getID());
	}

}
