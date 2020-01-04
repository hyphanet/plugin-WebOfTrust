package plugins.WebOfTrust;

/** Contains constants which can be changed to enable various debugging features. */
public final class DebugConfiguration {

	// FIXME: Implement usage of the below
	/** Enable assert()s which run {@link WebOfTrust#computeAllScoresWithoutCommit()} before and
	 *  after incremental {@link Score} computation to test if incremental computation yields
	 *  correct results.
	 *  Running a full score recomputation is very slow so these asserts are disabled by default.
	 *  
	 *  NOTICE: You also need to pass "-enableassertions" to the JVM for asserts to be executed! */
	public static final boolean DEBUG_INCREMENTAL_SCORE_COMPUTATION = false;

}
