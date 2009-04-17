/*
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */
package plugins.WoT.introduction;

import java.io.IOException;

import plugins.WoT.OwnIdentity;

/**
 * A base class for puzzle factories. Puzzle factories are frontends for different (3rd person) puzzle creation libraries which are included
 * in Freetalk. If you include a new captcha library for example you are supposed to write a PuzzleFactory for it and add it to the
 * factory list in class IntroductionServer.
 * 
 * @author xor
 */
public abstract class IntroductionPuzzleFactory {
	
	/**
	 * Create a new puzzle for CurrenTimeUTC.get() with an index set to a free index of the given inserter - the free index 
	 * shall be queried from the given IntroductionPuzzleStore - store it in the puzzle store and return it.
	 * 
	 * @param store The IntroductionPuzzleStore where the puzzle shall be stored.
	 * @param inserter The inserter of the puzzle.
	 * @return The new puzzle.
	 * @throws IOException
	 */
	public abstract IntroductionPuzzle generatePuzzle(IntroductionPuzzleStore store, OwnIdentity inserter) throws IOException;

}
