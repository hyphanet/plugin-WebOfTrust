package plugins.WebOfTrust.util;

import java.io.Serializable;
import java.util.Objects;

/** General purpose immutable 2-tuple. */
public final class Pair<X, Y> implements Serializable {
	private static final long serialVersionUID = 1L;

	public final X x;
	public final Y y;

	public Pair(X x, Y y) {
		this.x = x;
		this.y = y;
	}

	public static <X, Y> Pair<X, Y> pair(X x, Y y) {
		return new Pair<>(x, y);
	}

	@Override public int hashCode() {
		return Objects.hash(x, y);
	}

	@Override public boolean equals(Object obj) {
		if(obj == null)
			return false;
		
		if(!(obj instanceof Pair<?, ?>))
			return false;
		
		Pair<? ,?> p = (Pair<?, ?>)obj;
		// Use Objects.equals() to avoid having to do null-checks here.
		return Objects.equals(x, p.x) && Objects.equals(y, p.y);
	}
}
