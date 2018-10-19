package plugins.WebOfTrust.util;

/** General purpose immutable 2-tuple. */
public final class Pair<X, Y> {
	public final X x;
	public final Y y;

	public Pair(X x, Y y) {
		this.x = x;
		this.y = y;
	}

	public static <X, Y> Pair<X, Y> pair(X x, Y y) {
		return new Pair<>(x, y);
	}
}
