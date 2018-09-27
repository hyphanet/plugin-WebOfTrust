package plugins.WebOfTrust.util;

public final class MathUtil {
	/**
	 * Casts the given value to integer, but throws {@link ArithmeticException} if it is too large
	 * for an integer.
	 * 
	 * TODO: Code quality: Java 8: Replace with Math.toIntExact(). */
	public static int toIntExact(long value) {
		int result = (int)value;
		
		if(result != value)
			throw new ArithmeticException ("Too large: " + value);
		
		return result;
	}
}
