/* This code is part of WoT, a plugin for Freenet. It is distributed
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import static freenet.support.TimeUtil.formatTime;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static plugins.WebOfTrust.Configuration.IS_UNIT_TEST;
import plugins.WebOfTrust.IdentityFileQueue.IdentityFileStream;
import plugins.WebOfTrust.IdentityFileQueue.IdentityFileStreamWrapper;
import plugins.WebOfTrust.XMLTransformer.ImportIdentityStatistics;
import plugins.WebOfTrust.util.Daemon;
import plugins.WebOfTrust.util.jobs.BackgroundJob;
import plugins.WebOfTrust.util.jobs.DelayedBackgroundJob;
import plugins.WebOfTrust.util.jobs.MockDelayedBackgroundJob;
import plugins.WebOfTrust.util.jobs.TickerDelayedBackgroundJob;
import freenet.node.PrioRunnable;
import freenet.support.Logger;
import freenet.support.Ticker;
import freenet.support.io.Closer;
import freenet.support.io.NativeThread.PriorityLevel;

/**
 * The {@link IdentityFetcher} fetches the data files of all known identities and stores them
 * in the {@link IdentityFileQueue}. The job of this processor is to take the files from the queue,
 * and import them into the WOT database using the {@link XMLTransformer}.<br><br>
 * 
 * Notice: The implementation is single-threaded and processes the files sequentially one-by-one.
 * It is not parallelized since the core WOT {@link Score} computation algorithm is not.<br><br>
 * 
 * Implemented as a {@link DelayedBackgroundJob} instead of just {@link BackgroundJob}: The default
 * implementation of {@link IdentityFileQueue} supports deduplication of old versions of identity
 * files so only the latest queued edition of a file has to be processed. Thus, after a file has
 * been queued, it makes sense to first wait for a short delay as new editions might arrive to
 * replace the old one. 
 */
public final class IdentityFileProcessor implements Daemon, DelayedBackgroundJob {
	/**
	 * We wait for this delay before processing to give some time for deduplication.<br><br>
	 * 
	 * TODO: Performance: Make configurable. Tell the user that very high delays will increase
	 * performance since {@link IdentityFileQueue} will deduplicate a lot of files then - at the
	 * cost of higher latency for remote trust updates to have an effect.<br><br>
	 * 
	 * TODO: Performance: Once all of the other WOT performance issues are fixed, and thus we
	 * don't need to rely upon deduplicating identity files for performance reasons anymore,
	 * decrease this back to 1 minute for improving general latency of WOT.<br><br>
	 * 
	 * FIXME: Performance: Tweak default value. Use the statistics of {@link IdentityFileDiskQueue}
	 * to find a reasonable default. Especially test this with fresh empty databases as newbies
	 * who need to fetch all identities are most likely to benefit from deduplication.
	 * To find a reasonable default, use an infinite processing delay together with a fresh, empty
	 * database to see how much deduplication is the maximal possible value. Also see
	 * https://bugs.freenetproject.org/view.php?id=6555 */
	public static final long PROCESSING_DELAY_MILLISECONDS
		= IS_UNIT_TEST ? SECONDS.toMillis(1) : MINUTES.toMillis(1);

	/** We consume the files of this queue when it calls our {@link #triggerExecution()}. */
	private final IdentityFileQueue mQueue;

	/** Backend of the functions of this class which implement {@link DelayedBackgroundJob}. */
	private final DelayedBackgroundJob mRealDelayedBackgroundJob;
	
	/** Identity files will be passed to this {@link XMLTransformer} for the actual processing. */
	private final XMLTransformer mXMLTransformer;

	private final Statistics mStatistics = new Statistics();

	public static final class Statistics implements Cloneable {
		/** Number of files for which processing has been finished successfully. */
		public int mProcessedFiles = 0;

		/**
		 * Number of files for which processing failed.<br>
		 * This does not necessarily indicate bugs: Processing fails if remote Identitys have
		 * inserted bogus data, which they might do as they please. */
		public int mFailedFiles = 0;

		/** Total time it took to process all {@link #mProcessedFiles}. */
		public long mProcessingTimeNanoseconds = 0;

		/** Gets the average time it took for processing a file, in seconds. This includes only the
		 *  most significant computations:
		 *  - The time to parse the XML.
		 *  - The time to import the XML to the database and do the resulting Score recomputations.
		 *  
		 *  In other words, it excludes things which cannot be optimized much such as:
		 *  - Time for loading the file from disk.
		 *  - Time spent waiting to acquire the necessary locks.
		 * 
		 * ATTENTION: Not synchronized - only use this if you are sure that the Statistics object is
		 * not being modified anymore. This is the case if you obtained it using
		 * {@link IdentityFileProcessor#getStatistics()}. */
		public double getAverageXMLImportTime() {
			if (mProcessedFiles == 0) // prevent division by 0
				return 0;
			
			return ((double) mProcessingTimeNanoseconds / (double) SECONDS.toNanos(1))
				/ (double) mProcessedFiles;
		}

		@Override public Statistics clone() {
			try {
				return (Statistics)super.clone();
			} catch (CloneNotSupportedException e) {
				throw new RuntimeException(e);
			}
		}	
	}

	private static transient volatile boolean logMINOR = false;
	static {
		Logger.registerClass(IdentityFileProcessor.class);
	}


	IdentityFileProcessor(IdentityFileQueue queue, Ticker ticker, XMLTransformer xmlTransformer) {
		if(ticker != null) {
			mRealDelayedBackgroundJob = new TickerDelayedBackgroundJob(
				new Processor(), "WOT IdentityFileProcessor", PROCESSING_DELAY_MILLISECONDS,
				ticker);
		} else {
			// Don't log this as error since it is used for unit tests
			Logger.warning(this, "No Ticker provided, processing will never execute!",
				new RuntimeException("For stack trace"));

			mRealDelayedBackgroundJob = MockDelayedBackgroundJob.DEFAULT;
		}
		
		mQueue = queue;
		// Not called in constructor since then the queue might call our functions concurrently
		// before we are even finished with construction. Called in start() instead.
		/* mQueue.registerEventHandler(this); */
		
		mXMLTransformer = xmlTransformer;
	}

	/** Must be called during startup of WOT */
	@Override public void start() {
		mQueue.registerEventHandler(this);
		// In theory, the queue might contain files already, so we should triggerExecution() to
		// process them. But registerEventHandler() does that automatically for us if there are
		// files in the queue.
		/* triggerExecution(); */
	}

	/**
	 * Must be called by the {@link IdentityFileQueue} every time a new file is enqueued.<br>
	 * Processing will happen after the delay of {@link #PROCESSING_DELAY_MILLISECONDS} to give
	 * time for deduplication.<br><br>
	 * 
	 * {@link IdentityFileQueue} implementations which do not deduplicate should instead use
	 * {@link #triggerExecution(long)} to force a delay of 0. */
	@Override public void triggerExecution() {
		if(logMINOR) {
			Logger.minor(this, "triggerExecution(): Scheduling processing with delay of: "
				+ formatTime(PROCESSING_DELAY_MILLISECONDS));
		}
		mRealDelayedBackgroundJob.triggerExecution();
	}

	/**
	 * {@link IdentityFileQueue} implementations which do not deduplicate may use this function
	 * instead of {@link #triggerExecution()} to force a delay of 0:<br>
	 * The only reason for a non-zero delay is to give time for deduplication. */
	@Override public void triggerExecution(long delayMillis) {
		if(logMINOR) {
			Logger.minor(this, "triggerExecution(): Scheduling processing with delay of: "
				+ formatTime(delayMillis));
		}
		mRealDelayedBackgroundJob.triggerExecution(delayMillis);
	}

	/** The actual processing thread, run by {@link IdentityFileProcessor#triggerExecution()}. */
	private final class Processor implements Runnable, PrioRunnable {
		public void run() {
			Logger.normal(this, "run()...");
			
			// We query the IdentityFileQueue for *multiple* files until it is empty since if
			// it does multiple calls to triggerExecution(), that will only cause one execution of
			// run().
			while(true) {
				IdentityFileStreamWrapper streamWrapper = null;
				IdentityFileStream stream = null;
				
				try {
					streamWrapper = mQueue.poll();
					if(streamWrapper == null)
						break;
					
					// The real stream is wrapped in an IdentityFileStreamWrapper to ensure the
					// XML parser close()ing the real stream won't close the wrapper and thereby
					// delete the file from the IdentityFileQueue while it is still being processed,
					// which prevents the IdentityDownloader from downloading it again meanwhile.
					// See JavaDoc of IdentityFileStreamWrapper for details.
					stream = streamWrapper.getIdentityFileStream();
					assert(stream != null);
					
					Logger.normal(this, "run(): Processing: " + stream.mURI);
					
					ImportIdentityStatistics stats
						= mXMLTransformer.importIdentity(stream.mURI, stream.mXMLInputStream);
					
					long processingTime =
					    (stats.mXMLParsingTime != null ? stats.mXMLParsingTime.getNanos() : 0)
					  + (stats.mImportTime     != null ?     stats.mImportTime.getNanos() : 0);
					
					synchronized(IdentityFileProcessor.this) {
						++mStatistics.mProcessedFiles;
						// FIXME: Add two members to mStatistics to expose the two separate
						// measurements which are added up to "time" currently. Show them in the UI.
						mStatistics.mProcessingTimeNanoseconds +=  processingTime;
					}
				} catch(RuntimeException | Error e) {
					if(stream != null && stream.mURI != null) {
						Logger.error(this,
						    "Parsing identity XML failed severely - edition probably could NOT be "
						  + "marked for not being fetched again: " + stream.mURI, e);
					} else
						Logger.error(this, "Error in poll()", e);
					
					synchronized(IdentityFileProcessor.this) {
						++mStatistics.mFailedFiles;
					}
					
					if(e instanceof OutOfMemoryError) {
						// Importing Identitys and all the Score computation resulting from it
						// probably is one of the most memory-heavy parts of WoT.
						// So if it causes OOM we better wait for some time to give memory pressure
						// a chance to relax before trying again.
						// TODO: Bugs: IIRC there might be some places where triggerExecution() is
						// called with a delay of 0, which then shortens the delay we set here to 0,
						// though probably these are merely unit tests.
						// Review the calls to triggerExecution() and think about that.
						// Also adapt the documentation of triggerExecution() to mention the
						// potential issue of a delay of 0.
						triggerExecution(60 * 1000);
						Logger.error(this, "Delaying processing due to OutOfMemoryError!", e);
						break;
					}
				} finally {
					Closer.close(streamWrapper); // Also closes the wrapped stream.
				}
				
				if(Thread.interrupted()) {
					// terminate() interrupts our thread, so we obey that.
					Logger.normal(this, "run(): Shutdown requested, exiting...");
					break;
				}
				
				// Processing an identity file can take a long time, and thus we give other stuff
				// a chance to execute in between processing each.
				Thread.yield();
			}
			
			Logger.normal(this, "run() finished.");
		}

		@Override public int getPriority() {
			// LOW_PRIORITY since we are background processing, and not triggered by UI actions.
			// Not MIN_PRIORITY since we are not garbage cleanup, and serve the important job
			// of delivering updated trust lists to Score computation.
			return PriorityLevel.LOW_PRIORITY.value;
		}
	}


	/** Must be called before the WOT plugin is terminated. */
	@Override public void terminate() {
		mRealDelayedBackgroundJob.terminate();
	}

	/** Not needed by WOT. */
	@Override public boolean isTerminated() {
		return mRealDelayedBackgroundJob.isTerminated();
	}

	/**
	 * Must be called after {@link #terminate()} was called, and before the WOT plugin is
	 * terminated.<br>
	 * @param timeoutMillis Is ignored, {@link Long#MAX_VALUE} will always be used. */
	@Override public void waitForTermination(long timeoutMillis) throws InterruptedException {
		// Processor.run() supports thread interruption by terminate(), so we force the timeout to
		// be infinite so we always wait for clean exit of run() after it was terminate()d.
		mRealDelayedBackgroundJob.waitForTermination(Long.MAX_VALUE);
	}

	/**
	 * Gets a {@link Statistics} object suitable for displaying statistics in the UI.<br>
	 * Its data is coherent, i.e. queried in an atomic fashion.<br>
	 * The object is a clone, you may interfere with the contents of the member variables. */
	public synchronized Statistics getStatistics() {
		return mStatistics.clone();
	}
}
