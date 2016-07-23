/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.util;

/**
 * This serves the same purpose as Java's {@link Cloneable}, with one difference:
 * It actually defines a public clone function.
 * 
 * Java's Cloneable does not define one, due to both design decisions and historical reasons.
 * And Object's clone() is not public.
 * The lack of a public clone function prevents generic code such as:
 * void cloneThingAndDoStuff(Cloneable<T> o) {  o.clone() ... };
 * 
 * Thus this interface was added to allow high level code to obtain clones of objects of which they
 * only know that they implement the interface. */
public interface ReallyCloneable<T> extends Cloneable {
	/**
	 * Does nothing but calling {@link Object#clone()}.
	 * Has a different name because the compiler wouldn't allow us to increase the visibility of
	 * {@link Object#clone()} to public.
	 * 
	 * TODO: Code quality: Once we're on Java 8, add a default implementation ("default method")
	 * which does the job of calling Object.clone(). */
	public T cloneP();
}
