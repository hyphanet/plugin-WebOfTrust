/*
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */
package plugins.WoT.introduction;

import java.io.IOException;

import plugins.WoT.OwnIdentity;

import com.db4o.ObjectContainer;

/**
 * @author xor
 *
 */
public abstract class IntroductionPuzzleFactory {
	
	public abstract IntroductionPuzzle generatePuzzle(ObjectContainer db, OwnIdentity inserter) throws IOException;

}
