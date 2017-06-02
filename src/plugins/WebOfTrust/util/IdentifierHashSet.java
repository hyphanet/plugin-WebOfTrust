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
import plugins.WebOfTrust.WebOfTrust;
import freenet.support.IdentityHashSet;

/**
 * Certain classes which extend {@link Persistent}, such as {@link Identity}, {@link Trust} and
 * {@link Score}, provide an {@link Object#equals(Object)} implementation which does not only
 * compare whether the same "entity" is represented by two objects, but also whether its "version"
 * is the same:<br> FIXME: Document at {@link Persistent#equals(Object)}.
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
 * Persistent objects are added.
 * 
 * For performance please only use this in cases where it actually is possible that you try to add
 * multiple different copies of the same object to the set but only want one version to stay in it.
 * In cases where you're sure that you'll only be adding the same instance of each object instead
 * use {@link IdentityHashSet} for increased performance:
 * - It will spare us from actual disk IO: This class here has to call {@link Persistent#getID()}
 *   for computing the hashCode(), which in turn has to read the ID from the database by calling
 *   {@link Persistent#checkedActivate(int)}. IdentityHashSet will instead use
 *   {@link System#identityHashCode(Object)} which is derived from the memory location of the object
 *   and thus requires no disk IO at all.
 * - identityHashCode() only has to process the 8 byte memory location instead of this class using
 *   {@link String#hashCode()} upon e.g. the 43 byte {@link Identity#getID()} strings.
 * TODO: Performance: Review our existing users for whether they obey this.
 * TODO: Code quality: Review existing users of {@link IdentityHashSet} for cumbersome explanations
 * of why it is used and make them instead point to the above JavaDoc for an explanation. For an
 * example of a replacement comment see {@link WebOfTrust#computeAllScoresWithoutCommit()}.
 * 
 * NOTICE: In addition to the functions specified by {@link Set}, this class also provides the
 * following functions:
 * - {@link #identifierSet()}
 * - {@link #removeAllByIdentifier(Collection)}
 * 
 * FIXME: For {@link #add(Persistent)} etc., JavaDoc the main difference to the regular Set
 * behavior: Here element.equals() is NOT the defining thing which decides whether something is
 * added, removed, etc. add() will only check {@link Persistent#getID()}'s equals(). This is
 * probably OK with the Set specification as it allows implementations to refuse adding certain
 * elements. But according to the specification, we might actually have to throw if we refuse one,
 * which we don't do currently. So this would require changing the current code, and I'm not sure
 * whether it still would be suitable for WoT's purposes then :| If it is not, maybe just don't
 * explicitly implement interface Set, i.e. use the same functions, but remove the "implements"
 * declaration. */
public final class IdentifierHashSet<T extends Persistent> implements Set<T> {

	private final HashMap<String, T> map;

	public IdentifierHashSet() {
		map = new HashMap<String, T>();
	}

	public IdentifierHashSet(int initialCapacity) {
		map = new HashMap<String, T>(initialCapacity);
	}

	public IdentifierHashSet(Collection<T> c) {
		map = new HashMap<String, T>(Math.max(((int)(c.size()/0.75f)) + 1, 16));
		addAll(c);
	}

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

	/**
	 * ATTENTION: This is not compliant to {@link Set#equals(Object)}.
	 * For example, it will return false for any given Object which is not an IdentifierHashSet,
	 * even if the Object is a {@link Set}. */
	@Override public boolean equals(Object obj) {
		if(obj == null)
			return false;
		
		if(obj == this)
			return true;
		
		if(!(obj instanceof IdentifierHashSet)) {
			assert(false)
				: "IdentifierHashSet.equals() can only compare to type IdentifierHashSet";
			return false;
		}
		
		IdentifierHashSet<?> other = (IdentifierHashSet<?>)obj;
		
		return map.keySet().equals(other.map.keySet());
	}

	/**
	 * Returns the Set of the {@link Persistent#getID()} values of the contained elements.
	 * The returned Set is backed by this Set as specified by {@link HashMap#keySet()}. */
	public Set<String> identifierSet() {
		return map.keySet();
	}

	/**
	 * Removes all elements with the given {@link Persistent#getID()} values.
	 * Returns true if this resulted in any element being removed. */
	public boolean removeAllByIdentifier(Collection<String> identifiers) {
		boolean changed = false;
		for(String id : identifiers) {
			if(map.remove(id) != null)
				changed = true;
		}
		return changed;
	}
}
