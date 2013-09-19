/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map.Entry;
import java.util.NoSuchElementException;

import freenet.crypt.RandomSource;

/**
 * HashSet with the ability to return a random item.
 * All operations are armortised O(1).
 * 
 * @author xor (xor@freenetproject.org)
 * @param E The type of the elements.
 */
public final class RandomGrabHashSet<E> {

	private final RandomSource mRandomSource; 
	
	private final HashMap<E, Integer> mIndex = new HashMap<E, Integer>();
	
	private final ArrayList<E> mArray = new ArrayList<E>();
	
	
	public RandomGrabHashSet(final RandomSource randomSource) {
		mRandomSource = randomSource;
		assert(indexIsValid());
	}
	
	private boolean indexIsValid() {
		if(mIndex.size() != mArray.size())
			return false;
		
		for(Entry<E, Integer> entry : mIndex.entrySet()) {
			if(!mArray.get(entry.getValue()).equals(entry.getKey()))
				return false;
		}
		
		return true;
	}
	
	public boolean add(final E item) {
		if(mIndex.containsKey(item))
			return false;
		
		mArray.add(item);
		mIndex.put(item, mArray.size()-1);
		
		assert(indexIsValid());
		
		return true;
	}
	
	public void remove(final E toRemove) {
		final Integer indexOfRemovedItem = mIndex.remove(toRemove) ;
		if(indexOfRemovedItem == null)
			throw new NoSuchElementException();
		
		// We cannot use ArrayList.remove() because it would shift all following elements.
		// Instead of that, we replace the now-empty slot with the last element
		final int indexOfLastItem = mArray.size()-1;
		final E lastItem = mArray.remove(indexOfLastItem);
		if(indexOfRemovedItem != indexOfLastItem) {
			mArray.set(indexOfRemovedItem, lastItem);
			mIndex.put(lastItem, indexOfRemovedItem);
		}
		
		assert(indexIsValid());
	}
	
	public E getRandom() {
		return mArray.get(mRandomSource.nextInt(mArray.size()));
	}

}
