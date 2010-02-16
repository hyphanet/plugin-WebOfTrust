/*
 * Freetalk - Identicon.java - Copyright © 2010 David Roden
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

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.awt.image.WritableRaster;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates identicons.
 *
 * @author <a href="mailto:bombe@pterodactylus.net">David ‘Bombe’ Roden</a>
 */
public class Identicon {

	/** The data defining this identicon. */
	private final byte[] data;

	/**
	 * Creates a new identicon from the given data.
	 *
	 * @param data
	 *            The data that defines this identicon
	 */
	public Identicon(byte[] data) {
		this.data = new byte[data.length];
		System.arraycopy(data, 0, this.data, 0, data.length);
	}

	/**
	 * Renders the identicon.
	 *
	 * @param width
	 *            The width to render
	 * @param height
	 *            The height to render
	 * @return The rendered image
	 */
	public RenderedImage render(int width, int height) {
		BufferedImage backgroundImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		BufferedImage foregroundImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D backgroundGraphics = (Graphics2D) backgroundImage.getGraphics();
		backgroundGraphics.addRenderingHints(new RenderingHints(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON));
		Graphics2D foregroundGraphics = (Graphics2D) foregroundImage.getGraphics();
		foregroundGraphics.addRenderingHints(new RenderingHints(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON));
		BitShiftedInputStream bitStream = new BitShiftedInputStream(new ByteArrayInputStream(data), 8);
		try {
			/* create colors. */
			Color[] backgroundColors = new Color[16];
			Color[] foregroundColors = new Color[16];
			for (int colorIndex = 0; colorIndex < 4; ++colorIndex) {
				Color backgroundColor = new Color(bitStream.read(1) * 64, bitStream.read(1) * 64, bitStream.read(1) * 64, 255);
				Color foregroundColor = new Color(bitStream.read(1) * 127 + 128, bitStream.read(1) * 127 + 128, bitStream.read(1) * 127 + 128, 255);
				for (int blockIndex = 0; blockIndex < 4; ++blockIndex) {
					backgroundColors[((colorIndex % 2) * 2 + (blockIndex % 2)) + (((colorIndex / 2) * 2 + (blockIndex / 2)) * 4)] = backgroundColor;
					foregroundColors[((colorIndex % 2) * 2 + (blockIndex % 2)) + (((colorIndex / 2) * 2 + (blockIndex / 2)) * 4)] = foregroundColor;
				}
			}
			/* create blocks. */
			for (int blockIndex = 0; blockIndex < 16; ++blockIndex) {
				int blockX = (int) ((blockIndex % 4) * width / 4.0);
				int blockY = (int) ((blockIndex / 4) * height / 4.0);
				int blockWidth = (int) (((blockIndex % 4) + 1) * width / 4.0) - blockX;
				int blockHeight = (int) (((blockIndex / 4) + 1) * height / 4.0) - blockY;

				int patternType = bitStream.read(4);
				Pattern pattern = Pattern.getPattern(patternType);
				boolean swapColors = bitStream.read(1) == 1;
				int rotation = bitStream.read(2);
				Polygon polygon = pattern.getPolygon(blockWidth, blockHeight, rotation);
				backgroundGraphics.setColor(backgroundColors[blockIndex]);
				backgroundGraphics.fillRect(blockX, blockY, blockWidth, blockHeight);
				foregroundGraphics.setColor(foregroundColors[blockIndex]);
				if (swapColors) {
					Graphics2D maskGraphics = (Graphics2D) foregroundGraphics.create(blockX, blockY, blockWidth, blockHeight);
					maskGraphics.fillRect(0, 0, blockWidth, blockHeight);
					maskGraphics.setComposite(AlphaComposite.Clear);
					maskGraphics.fill(polygon);
				} else {
					foregroundGraphics.create(blockX, blockY, blockWidth, blockHeight).fillPolygon(pattern.getPolygon(blockWidth, blockHeight, rotation));
				}
			}
			int highlightType = bitStream.read(1);
			if (highlightType == 0) {
				backgroundImage.getGraphics().drawImage(foregroundImage, 0, 0, null);
				double centerX = 0.25 + bitStream.read(1) * 0.5;
				double centerY = 0.25 + bitStream.read(1) * 0.5;
				CircularHighlight.render(backgroundImage, centerX, centerY);
			} else if (highlightType == 1) {
				GradientHighlight.render(foregroundImage);
				backgroundImage.getGraphics().drawImage(foregroundImage, 0, 0, null);
			}
			System.out.println(bitStream.available());
		} catch (IOException e) {
			e.printStackTrace();
		}
		return backgroundImage;
	}

	/**
	 * Defines a block pattern. Essentielly a block pattern can consist of
	 * anything that can be converted into a {@link Polygon}.
	 *
	 * @author <a href="mailto:bombe@pterodactylus.net">David ‘Bombe’ Roden</a>
	 */
	private static class Pattern {

		/** All currently defined patterns. */
		private static final List<Pattern> patterns = new ArrayList<Pattern>();

		static {
			patterns.add(new Pattern(new Point2D.Double(0, 0), new Point2D.Double(0.5, 0), new Point2D.Double(0.5, 0.5), new Point2D.Double(0, 0.5)));
			patterns.add(new Pattern(new Point(0, 0), new Point(1, 0), new Point2D.Double(0.5, 1)));
			patterns.add(new Pattern(new Point(0, 0), new Point(1, 0), new Point2D.Double(0.75, 1), new Point2D.Double(0.5, 0), new Point2D.Double(0.25, 1)));
			patterns.add(new Pattern(new Point(0, 0), new Point2D.Double(0.5, 0.5), new Point(1, 0), new Point2D.Double(0.5, 1)));
			patterns.add(new Pattern(new Point(0, 0), new Point(1, 0), new Point2D.Double(1, 0.5), new Point2D.Double(0, 0.5)));
			patterns.add(new Pattern(new Point2D.Double(0, 0.25), new Point2D.Double(0.5, 0.5), new Point2D.Double(0, 0.75)));
			patterns.add(new Pattern(new Point(0, 0), new Point2D.Double(0.5, 0), new Point(0, 1)));
			patterns.add(new Pattern(new Point2D.Double(0, 0), new Point2D.Double(0.5, 0), new Point(1, 1), new Point2D.Double(0, 0.5)));
			patterns.add(new Pattern(new Point(0, 0), new Point2D.Double(0.25, 0), new Point2D.Double(0.5, 1), new Point2D.Double(0.25, 1)));
			patterns.add(new Pattern(new Point2D.Double(0.5, 0), new Point2D.Double(1, 0.5), new Point2D.Double(0.5, 0.5), new Point2D.Double(0.5, 1), new Point2D.Double(0, 0.5)));
			patterns.add(new Pattern(new Point(0, 0), new Point(1, 1), new Point2D.Double(0.5, 1), new Point2D.Double(0, 0.5)));
			patterns.add(new Pattern(new Point2D.Double(0, 0.25), new Point2D.Double(0.5, 0), new Point2D.Double(1, 0.25), new Point2D.Double(1, 0.75), new Point2D.Double(0, 0.75)));
			patterns.add(new Pattern(new Point(0, 0), new Point2D.Double(0.5, 0), new Point2D.Double(0, 0.5)));
			patterns.add(new Pattern(new Point2D.Double(0, 0.25), new Point2D.Double(0.25, 0), new Point2D.Double(0.75, 0), new Point2D.Double(1, 0.25), new Point2D.Double(1, 0.5), new Point2D.Double(0, 0.5)));
			patterns.add(new Pattern(new Point(0, 0), new Point(1, 0), new Point2D.Double(1, 0.5), new Point2D.Double(0.75, 0.5), new Point2D.Double(0.75, 1), new Point2D.Double(0.25, 1), new Point2D.Double(0.25, 0.5), new Point2D.Double(0, 0.5)));
			patterns.add(new Pattern(new Point(0, 0), new Point2D.Double(0.5, 0), new Point2D.Double(0.5, 0.25), new Point2D.Double(0, 0.75)));
		}

		/** The points making up this pattern. */
		private final Point2D[] points;

		/**
		 * Defines a new pattern.
		 *
		 * @param points
		 *            The points of the pattern
		 */
		public Pattern(Point2D... points) {
			this.points = points;
		}

		/**
		 * Creates a polygon from this pattern, scaled to fit into a box with
		 * the given width and height.
		 *
		 * @param width
		 *            The width of the box
		 * @param height
		 *            The height of the box
		 * @param rotated
		 *            How often the pattern should be rotated clock-wise
		 * @return The created polygon
		 */
		public Polygon getPolygon(int width, int height, int rotated) {
			Polygon polygon = new Polygon();
			for (Point2D point : points) {
				double x = point.getX();
				double y = point.getY();
				if (rotated == 1) {
					x = point.getY();
					y = 1 - point.getX();
				} else if (rotated == 2) {
					x = 1 - point.getX();
					y = 1 - point.getY();
				} else if (rotated == 3) {
					x = 1 - point.getY();
					y = point.getX();
				}
				polygon.addPoint((int) (x * width), (int) (y * height));
			}
			return polygon;
		}

		/**
		 * Returns the number of defined patterns.
		 *
		 * @return The number of defined patterns
		 */
		@SuppressWarnings("unused")
		public static int getPatternCount() {
			return patterns.size();
		}

		/**
		 * Returns the pattern with the given index.
		 *
		 * @param index
		 *            The index of the pattern
		 * @return The pattern at the given index
		 */
		public static Pattern getPattern(int index) {
			return patterns.get(index % patterns.size());
		}

	}

	/**
	 * Helper class that renders a circular highlight onto a
	 * {@link BufferedImage}.
	 *
	 * @author <a href="mailto:bombe@pterodactylus.net">David ‘Bombe’ Roden</a>
	 */
	private static class CircularHighlight {

		/**
		 * Renders the highlight onto the given image.
		 *
		 * @param image
		 *            The image to render the highlight onto
		 * @param centerX
		 *            The X center of the highlight (from {@code 0.0} to {@code
		 *            1.0})
		 * @param centerY
		 *            The Y center of the highlight (from {@code 0.0} to {@code
		 *            1.0})
		 */
		public static void render(BufferedImage image, double centerX, double centerY) {
			WritableRaster imageRaster = image.getRaster();
			int width = image.getWidth();
			int height = image.getHeight();
			double amplification = 2;
			int glowX = (int) (width * centerX);
			int glowY = (int) (height * centerY);
			for (int x = 0; x < width; ++x) {
				for (int y = 0; y < height; ++y) {
					int[] pixels = new int[4];
					imageRaster.getPixel(x, y, pixels);
					double dX = Math.abs(x - glowX) / (double) width;
					double dY = Math.abs(y - glowY) / (double) height;
					double d = Math.min(1, Math.max(0, Math.sqrt(dX * dX + dY * dY) / 1.1));
					pixels[0] = Math.min(255, (int) (pixels[0] * (1 - d) * amplification));
					pixels[1] = Math.min(255, (int) (pixels[1] * (1 - d) * amplification));
					pixels[2] = Math.min(255, (int) (pixels[2] * (1 - d) * amplification));
					imageRaster.setPixel(x, y, pixels);
				}
			}
		}

	}

	/**
	 * Helper class that renders a straigh gradient highlight onto a
	 * {@link BufferedImage}.
	 *
	 * @author <a href="mailto:bombe@pterodactylus.net">David ‘Bombe’ Roden</a>
	 */
	private static class GradientHighlight {

		/**
		 * Renders the gradient highlight onto the given image
		 *
		 * @param image
		 *            The image to render the highlight onto
		 */
		public static void render(BufferedImage image) {
			WritableRaster imageRaster = image.getRaster();
			int width = image.getWidth();
			int height = image.getHeight();
			double lowerBorder = 0.45;
			double upperBorder = 0.45;
			double amplification = 2;
			for (int x = 0; x < width; ++x) {
				for (int y = 0; y < height; ++y) {
					int[] pixels = new int[4];
					imageRaster.getPixel(x, y, pixels);
					double progress = (x + y) / (double) (width + height);
					if (progress < lowerBorder) {
						pixels[0] = Math.min(255, (int) (pixels[0] * (1 + (lowerBorder - progress) * amplification)));
						pixels[1] = Math.min(255, (int) (pixels[1] * (1 + (lowerBorder - progress) * amplification)));
						pixels[2] = Math.min(255, (int) (pixels[2] * (1 + (lowerBorder - progress) * amplification)));
						imageRaster.setPixel(x, y, pixels);
					} else if (progress > upperBorder) {
						pixels[0] = Math.min(255, (int) (pixels[0] * (1 + (progress - upperBorder) * amplification)));
						pixels[1] = Math.min(255, (int) (pixels[1] * (1 + (progress - upperBorder) * amplification)));
						pixels[2] = Math.min(255, (int) (pixels[2] * (1 + (progress - upperBorder) * amplification)));
						imageRaster.setPixel(x, y, pixels);
					}
				}
			}
		}

	}

}
