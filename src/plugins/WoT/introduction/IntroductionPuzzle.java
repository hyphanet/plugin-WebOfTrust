/*
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */
package plugins.WoT.introduction;

import plugins.WoT.Identity;

public class IntroductionPuzzle {
	
	enum PuzzleType {
		Image,
		Audio
	};
	
	private final PuzzleType mType;
	
	private final byte[] mData;
	
	private final String mSolution;
	
	private final Identity mInserter;
	
	/**
	 * For construction from a received puzzle.
	 * @param newType
	 * @param newData
	 */
	public IntroductionPuzzle(Identity newInserter, PuzzleType newType, byte[] newData) {
		mInserter = newInserter;
		mType = newType;
		mData = newData;
		mSolution = null; 
	}
	
	/**
	 * For construction of a puzzle which is meant to be inserted.
	 * @param newType
	 * @param newData
	 */
	public IntroductionPuzzle(Identity newInserter, PuzzleType newType, byte[] newData, String newSolution) {
		mInserter = newInserter;
		mType = newType;
		mData = newData;
		mSolution = newSolution; 
	}
	
	public PuzzleType getPuzzleType() {
		return mType;
	}
	
	public byte[] getPuzzle() {
		return mData;
	}
	
	/**
	 * Get the solution of the puzzle. Null if the puzzle was received and not locally generated.
	 */
	public String getSolution() {
		assert(mSolution != null); /* Whoever uses this function should not need to call it when there is no solution available */
		return mSolution;
	}
	
	public Identity getInserter() {
		return mInserter;
	}
}
