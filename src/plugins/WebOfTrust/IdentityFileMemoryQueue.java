/* This code is part of WoT, a plugin for Freenet. It is distributed
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import java.io.ByteArrayInputStream;
import java.util.LinkedList;

import freenet.support.Logger;
import plugins.WebOfTrust.util.jobs.BackgroundJob;

/**
 * {@link IdentityFileQueue} implementation which stores the files in memory instead of on disk.<br>
 * Order of the files is preserved in a FIFO manner.<br><br>
 * 
 * This implementation aims at being used in unit tests only. Thus, in comparison to the
 * {@link IdentityFileDiskQueue} which WOT actually uses, it has the following disadvantages:<br>
 * - It doesn't deduplicate editions. See {@link IdentityFileQueue} for what that means.<br>
 * - It doesn't watch its memory usage and thus on fast Freenet nodes might cause OOM.<br>
 * - It doesn't use the {@link Logger}, you need to instead enable assert() in your JVM.<br><br>
 * 
 * TODO: Performance: Add configuration option to allow users to make their WOT use this instead
 * of the default {@link IdentityFileDiskQueue}. Be sure to resolve the above disadvantages before,
 * and to warn users that they only should use it if they have lots of memory. */
final class IdentityFileMemoryQueue implements IdentityFileQueue {

	private final LinkedList<IdentityFile> mQueue = new LinkedList<IdentityFile>();

	private final IdentityFileQueueStatistics mStatistics = new IdentityFileQueueStatistics();

	private BackgroundJob mEventHandler;


	@Override public synchronized void add(IdentityFileStream file) {
		try {
			mQueue.addLast(IdentityFile.read(file));
			++mStatistics.mQueuedFiles;
			
			if(mEventHandler != null)
				mEventHandler.triggerExecution();
			else {
				// The IdentityFetcher might fetch files during its start() already, and call this
				// function to enqueue fetched files. However, IdentityFileProcessor.start(), which
				// would register it as the event handler which is missing here, is called *after*
				// IdentityFetcher.start(). This is to prevent identity file processing from slowing
				// down WoT startup.
				// Thus having not an event handler yet is not an error, which is why the below
				// assert() is commented out.
				// TODO: Code quality: Once https://bugs.freenetproject.org/view.php?id=6674 has
				// been fixed, we could split IdentityFileProcessor.start() into register() and
				// start(): Register would be what start() was previously, i.e. register the event
				// handler so we're not missing it here anymore. start() would be what was demanded
				// by the bugtracker entry, i.e. enable IdentityFileProcessor.triggerExecution().
				// IdentityFileProcessor.register() could then be called before
				// IdentityFetcher.start(), and IdentityFileProcessor.start() afterwards. It then
				// wouldn't matter if register() is called before IdentityFetcher.start():
				// This IdentityFileMemoryQueue could not cause the processor to process the files
				// which the IdentityFetcher adds, as IdentityFileProcessor.triggerExecution() would
				// only work after start().
				// So overall, we could then enable this assert() for robustness.
				/*
				assert(false);
				*/
			}
		} catch(RuntimeException e) {
			++mStatistics.mFailedFiles;
			assert(false) : e;
			throw e;
		} catch(Error e) { // TODO: Java 7: Merge with above to catch(RuntimeException | Error e)
			++mStatistics.mFailedFiles;
			assert(false) : e;
			throw e;
		} finally {
			++mStatistics.mTotalQueuedFiles;
			assert(checkConsistency());
		}
	}

	@Override public synchronized IdentityFileStream poll() {
		try {
			IdentityFile file;
			
			while((file = mQueue.pollFirst()) != null) {
				try {
					IdentityFileStream result = new IdentityFileStream(
						file.getURI(), new ByteArrayInputStream(file.mXML));
					
					++mStatistics.mFinishedFiles;
					
					return result;
				} catch(RuntimeException e) {
					++mStatistics.mFailedFiles;
					assert(false) : e;
					continue;
				} catch(Error e) {
					// TODO: Java 7: Merge with above to catch(RuntimeException | Error e)
					++mStatistics.mFailedFiles;
					assert(false) : e;
					continue;
				} finally {
					--mStatistics.mQueuedFiles;
				}
			}
				
			return null; // Queue is empty
		} finally {
			assert(checkConsistency());
		}
	}

	@Override public synchronized void registerEventHandler(BackgroundJob handler) {
		if(mEventHandler != null) {
			throw new UnsupportedOperationException(
				"Support for more than one event handler is not implemented yet.");
		}
		
		mEventHandler = handler;
		
		if(mQueue.size() != 0)
			mEventHandler.triggerExecution();
	}

	@Override public synchronized IdentityFileQueueStatistics getStatistics() {
		assert(checkConsistency());
		return mStatistics.clone();
	}

	private synchronized boolean checkConsistency() {
		return
			   mStatistics.checkConsistency()
			&& mStatistics.mDeduplicatedFiles == 0
			&& mStatistics.mProcessingFiles == 0
			&& mStatistics.mQueuedFiles == mQueue.size();
	}
}
