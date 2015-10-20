/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.util;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import plugins.WebOfTrust.Identity;
import plugins.WebOfTrust.Persistent;
import plugins.WebOfTrust.Score;
import plugins.WebOfTrust.Trust;

/**
 * Certain classes which extend {@link Persistent}, such as {@link Identity}, {@link Trust} and
 * {@link Score}, provide an {@link Object#equals(Object)} implementation which does not only
 * compare whether the same "entity" is represented by two objects, but also whether its "version"
 * is the same:<br>
 * For example, there can usually only be one {@link Trust} object between each pair of
 * {@link Identity} objects, and thus a pair of two {@link Identity} objects uniquely identifies
 * a Trust "entity". Nevertheless, {@link Trust#equals(Object)} will also return false when
 * comparing Trust objects between the same Identitys if they hold a different
 * {@link Trust#getValue()}.<br><br>
 * 
 * To allow compensation for this behavior, this {@link HashSet} implementation will consider
 * the given {@link Persistent} object's identity only from the value of their
 * {@link Persistent#getID()}.<br>
 * I.e. it will behave like a {@link HashSet} with type {@link String}, to which the IDs of the
 * Persistent objects are added. */
final class IdentifierHashSet<T extends Persistent> implements Set<T> {

	private final HashMap<String, T> map = new HashMap<String, T>();

	/**
	 * {@inheritDoc}
	 * @throws NullPointerException If parameter e is null. */
	@Override public boolean add(T e) {
		String id = e.getID();
		if(map.containsKey(id))
			return false;
		
		boolean notContained = map.put(id, e) == null;
		assert(notContained);
		return true;
	}

	@Override public boolean addAll(Collection<? extends T> c) {
		boolean changed = false;
		for(T item : c) {
			if(add(item))
				changed = true;
		}
		return changed;
	}

	@Override public void clear() {
		map.clear();
	}

	@Override public boolean contains(Object o) {
		return map.containsKey(((Persistent)o).getID());
	}

	@Override public boolean containsAll(Collection<?> c) {
		for(Object item : c) {
			if(!contains(item))
				return false;
		}
		return true;
	}

	@Override public boolean isEmpty() {
		return map.isEmpty();
	}

	@Override public Iterator<T> iterator() {
		return map.values().iterator();
	}

	@Override public boolean remove(Object o) {
		return map.remove(((Persistent)o).getID()) != null;
	}

	@Override public boolean removeAll(Collection<?> c) {
		boolean changed = false;
		for(Object o : c) {
			if(remove(o))
				changed = true;
		}
		return changed;
	}

	@Override public boolean retainAll(Collection<?> c) {
		throw new UnsupportedOperationException("Not implemented yet.");
	}

	@Override public int size() {
		return map.size();
	}

	@Override public Object[] toArray() {
		throw new UnsupportedOperationException("Not implemented yet.");

	}

	@Override public <T> T[] toArray(T[] a) {
		throw new UnsupportedOperationException("Not implemented yet.");

	}

	@Override public int hashCode() {
		return map.keySet().hashCode();
	}

	@Override public boolean equals(Object obj) {
		if(!(obj instanceof IdentifierHashSet)) {
			throw new IllegalArgumentException(
				"IdentifierHashSet.equals() can only compare to objects of type IdentifierHashSet");
		}
		
		IdentifierHashSet<?> other = (IdentifierHashSet<?>)obj;
		
		return map.keySet().equals(other.map.keySet());
	}

}
