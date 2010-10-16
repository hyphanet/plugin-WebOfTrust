/*
 * utils - Bits.java - Copyright © 2006-2009 David Roden
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

/**
 * Utility class for bit manipulations.
 *
 * @author <a href="mailto:bombe@pterodactylus.net">David ‘Bombe’ Roden</a>
 */
public class Bits {

	/**
	 * Decodes <code>numberOfBits</code> bits from the specified start index.
	 * The resulting value has the range <code>0</code> to
	 * <code>2 ^ numberOfBits - 1</code>.
	 *
	 * @param value
	 *            The value to decode bits from
	 * @param bitIndex
	 *            The index of the first bit to decode
	 * @param numberOfBits
	 *            The number of bits to decode
	 * @return The decoded value
	 */
	public static int decodeBits(int value, int bitIndex, int numberOfBits) {
		return (value >> bitIndex) & ((1 << numberOfBits) - 1);
	}

	/**
	 * Changes <code>numberOfBits</code> bits starting from index
	 * <code>bitIndex</code> in <code>octet</code> to the
	 * <code>numberOfBits</code> lowest bits from <code>newValue</code>.
	 *
	 * @param oldValue
	 *            The value to change
	 * @param bitIndex
	 *            The index of the lowest bit to change
	 * @param numberOfBits
	 *            The number of bits to change
	 * @param newValue
	 *            The new value of the changed bits
	 * @return <code>octet</code> with the specified bits changed
	 */
	public static int encodeBits(int oldValue, int bitIndex, int numberOfBits, int newValue) {
		return (oldValue & ~(((1 << numberOfBits) - 1) << bitIndex)) | ((newValue & ((1 << numberOfBits) - 1)) << bitIndex);
	}

	/**
	 * Rotates the bits in the given value to the left.
	 *
	 * @param value
	 *            The value to rotate
	 * @param distance
	 *            The distance of the rotation, in bits
	 * @return The rotated value
	 */
	public static int rotateLeft(int value, int distance) {
		return (value << (distance & 0x1f)) | (value >>> ((32 - distance) & 0x1f));
	}

	/**
	 * Rotates the bits in the given value to the right.
	 *
	 * @param value
	 *            The value to rotate
	 * @param distance
	 *            The distance of the rotation, in bits
	 * @return The rotated value
	 */
	public static int rotateRight(int value, int distance) {
		return (value >>> (distance & 0x1f)) | (value << ((32 - distance) & 0x1f));
	}

}
