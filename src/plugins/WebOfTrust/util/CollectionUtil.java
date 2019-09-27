package plugins.WebOfTrust.util;

import java.util.ArrayList;
import java.util.Arrays;

public final class CollectionUtil {
	/**
	 * Helper function which allows especially manually constructing an array of stuff with a type
	 * parameter, as Java disallows e.g. this: new Pair<Double, Double>[] { ... }
	 * See: https://stackoverflow.com/a/8052827
	 * 
	 * WARNING: This might allow creation of arrays which contain something else than the given
	 * type! Only use it when you are sure that you're only passing objects of the desired type!
	 * Ideally only use it when manually providing the elements as varargs, i.e. don't pass an
	 * existing array/collection!
	 * 
	 * This can be noticed by the necessity of the SafeVarargs annotation to disable the compiler
	 * warning: "Type safety: Potential heap pollution via varargs parameter array"
	 * See: https://stackoverflow.com/a/12462259
	 * To avoid this from returning an Object[] instead of T[] when the array is empty it will throw
	 * IllegalArgumentException if the array size is 0, but I'm not sure whether this covers all
	 * cases. Please ideally only pass fitting elements as varargs, do not pass existing arrays. */
	@SafeVarargs
	public static <T> T[] array(T... array) {
		if(array.length == 0) {
			throw new IllegalArgumentException(
				"Would cause ClassCastException due to array having wrong type!");
		}
		
		return Arrays.copyOf(array, array.length);
	}

	/** WARNING: See the documentation of {@link #array(Object...)} for how to use this properly!
	 *  TODO: Code quality: Use {@link Arrays#asList(Object...)} instead where possible, but do read
	 *  its JavaDoc first, its behavior is slightly different. */
	@SafeVarargs
	public static <T> ArrayList<T> arrayList(T... array) {
		if(array.length == 0) {
			throw new IllegalArgumentException(
				"Would cause ClassCastException due to ArrayList having wrong type!");
		}
		
		ArrayList<T> result = new ArrayList<>(array.length);
		
		for(T e : array)
			result.add(e);
		
		return result;
	}

	/**
	 * TODO: Java 8: Use list.removeIf(Objects::isNull);
	 * Or replace caller logic with using arrays instead as:
	 *     Arrays.stream(array).filter(Objects::nonNull).toArray(ArrayClass[]::new); */
	public static <T> ArrayList<T> ignoreNulls(ArrayList<T> list) {
		ArrayList<T> result = new ArrayList<>();
		
		for(T e : list) {
			if(e != null)
				result.add(e);
		}
		
		return result;
	}
}
