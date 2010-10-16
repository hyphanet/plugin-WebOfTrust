/*
 * utils - BitShiftedOutputStream.java - Copyright © 2006-2010 David Roden
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package plugins.WoT.identicon;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * An InputStream that can read values with a bit size of other than 8.
 *
 * @author <a href="mailto:bombe@pterodactylus.net">David ‘Bombe’ Roden</a>
 */
public class BitShiftedInputStream extends FilterInputStream {

	/** The number of bits per value. */
	protected int valueSize;

	/** The current bit position in the underlying input stream. */
	protected int currentBitPosition;

	/** The current value from the underlying input stream. */
	protected int currentValue;

	/**
	 * Creates a new bit-shifted input stream wrapped around the specified input
	 * stream with the specified value size.
	 *
	 * @param in
	 *            The input stream to wrap
	 * @param valueSize
	 *            The number of bits per value
	 */
	public BitShiftedInputStream(InputStream in, int valueSize) {
		super(in);
		if ((valueSize < 1) || (valueSize > 32)) {
			throw new IllegalArgumentException("valueSize out of range 1-32");
		}
		this.valueSize = valueSize;
		currentBitPosition = 8;
	}

	/**
	 * Reads a value from the underlying input stream.
	 *
	 * @return A value from the underlying input stream
	 * @throws IOException
	 *             if an I/O error occurs
	 */
	@Override
	public int read() throws IOException {
		return read(valueSize);
	}

	/**
	 * Reads a value with the given number of bits from the underlying input
	 * stream.
	 *
	 * @param valueSize
	 *            The number of bits to read
	 * @return A value from the underlying input stream
	 * @throws IOException
	 *             if an I/O error occurs
	 */
	public int read(int valueSize) throws IOException {
		int bitsLeft = valueSize;
		int value = 0;
		while (bitsLeft > 0) {
			if (currentBitPosition > 7) {
				currentValue = super.read();
				currentBitPosition = 0;
			}
			value = Bits.encodeBits(value, valueSize - bitsLeft, 1, currentValue);
			currentValue >>>= 1;
			currentBitPosition++;
			bitsLeft--;
		}
		return value;
	}

	/**
	 * Skips the specified number of bits. This can be used to re-align the bit
	 * stream.
	 *
	 * @param numberOfBits
	 *            The number of bits to skip
	 * @throws IOException
	 *             if an I/O error occurs
	 */
	public void skipBits(int numberOfBits) throws IOException {
		int bitsLeft = numberOfBits;
		while (bitsLeft > 0) {
			if (currentBitPosition > 7) {
				currentValue = super.read();
				currentBitPosition = 0;
			}
			currentValue >>>= 1;
			currentBitPosition++;
			bitsLeft--;
		}
	}

}
