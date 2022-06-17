package plugins.WebOfTrust.util;

import java.util.Locale;

public final class StringUtil {

	/** Returns true if the given string is fully lowercase in terms of {@link Locale#getDefault()},
	 *  which is system-dependent!
	 * 
	 *  TODO: Code quality: As of 2022-06-17 there is no Java function for this. Once there is one
	 *  please use it instead. */
	static public boolean isAllLowercase(String string) {
		return string.equals(string.toLowerCase());
	}

}
