/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.network.input;

import static java.util.Collections.sort;
import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.Comparator;

import plugins.WebOfTrust.Identity;
import plugins.WebOfTrust.Persistent.InitializingObjectSet;
import plugins.WebOfTrust.WebOfTrust;
import plugins.WebOfTrust.exceptions.DuplicateObjectException;
import plugins.WebOfTrust.exceptions.UnknownEditionHintException;
import plugins.WebOfTrust.util.Daemon;

import com.db4o.ObjectSet;
import com.db4o.ext.ExtObjectContainer;
import com.db4o.query.Query;

import freenet.support.Logger;

/**
 * Uses USK edition hints to download {@link Identity}s from the network for which we have a
 * significant confidence that a certain edition exists.
 * For an explanation of what an edition hint is, see {@link EditionHint}.
 * 
 * The downloads happen as a direct SSK request, and thus don't cause as much network load as the
 * USK subscriptions which {@link IdentityDownloaderFast} would do.
 * 
 * This class only deals with the {@link Identity}s which {@link IdentityDownloaderFast} does not
 * download, so in combination the both of these classes download all {@link Identity}s. */
public final class IdentityDownloaderSlow implements IdentityDownloader, Daemon {
	
	private final WebOfTrust mWoT;
	
	private final ExtObjectContainer mDB;

	private static transient volatile boolean logDEBUG = false;

	private static transient volatile boolean logMINOR = false;

	static {
		Logger.registerClass(IdentityDownloaderSlow.class);
	}


	public IdentityDownloaderSlow(WebOfTrust wot) {
		requireNonNull(wot);
		
		mWoT = wot;
		mDB = mWoT.getDatabase();
	}

	@Override public void start() {
		// FIXME: Implement
		
		if(logDEBUG)
			testDatabaseIntegrity();
	}

	@Override public void terminate() {
		// FIXME
	}

	@Override public void storeStartFetchCommandWithoutCommit(Identity identity) {
		// FIXME
	}

	@Override public void storeAbortFetchCommandWithoutCommit(Identity identity) {
		// FIXME
	}

	@Override public void storeNewEditionHintCommandWithoutCommit(EditionHint hint) {
		
		// FIXME
	}

	@Override public boolean getShouldFetchState(String identityID) {
		// FIXME
		return false;
	}
	
	@Override public void deleteAllCommands() {
		// FIXME
	}

	private ObjectSet<EditionHint> getAllEditionHints() {
		Query q = mDB.query();
		q.constrain(EditionHint.class);
		return new InitializingObjectSet<>(mWoT, q);
	}

	EditionHint getEditionHintByID(String id) throws UnknownEditionHintException {
		Query query = mDB.query();
		query.constrain(EditionHint.class);
		query.descend("mID").constrain(id);
		ObjectSet<EditionHint> result = new InitializingObjectSet<>(mWoT, query);
		
		switch(result.size()) {
			case 1:
				EditionHint hint = result.next();
				assert(hint.getID().equals(id));
				return hint;
			case 0:  throw new UnknownEditionHintException(id);
			default: throw new DuplicateObjectException(id);
		}
	}

	private ObjectSet<EditionHint> getQueue() {
		Query q = mDB.query();
		q.constrain(EditionHint.class);
		q.descend("mPriority").orderDescending();
		return new InitializingObjectSet<>(mWoT, q);
	}

	private void testDatabaseIntegrity() {
		ObjectSet<EditionHint> queueSortedByDb4o = getQueue();
		ArrayList<EditionHint> queueSortedWithReferenceImpl = new ArrayList<>(getAllEditionHints());
		
		sort(queueSortedWithReferenceImpl, new Comparator<EditionHint>() {
			@Override public int compare(EditionHint h1, EditionHint h2) {
				return h1.compareTo_ReferenceImplementation(h2);
			}
		});
		
		if(!queueSortedWithReferenceImpl.equals(queueSortedByDb4o)) {
			Logger.error(this, "Sorting EditionHints by mPriority returns wrong order: ");
			
			for(EditionHint h : queueSortedByDb4o)
				Logger.error(this, h.toString());
		}
	}
}
