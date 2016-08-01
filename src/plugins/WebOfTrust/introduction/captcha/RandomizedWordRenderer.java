/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.introduction.captcha;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.Random;

import plugins.WebOfTrust.introduction.captcha.kaptcha.text.WordRenderer;
import plugins.WebOfTrust.introduction.captcha.kaptcha.util.Configurable;

/**
 * Word renderer that randomizes character rotation, vertical position and horizontal separation.
 *
 * @author bertm
 */
public class RandomizedWordRenderer extends Configurable implements WordRenderer
{
    // Amount of pixels to keep clear of borders in text rendering.
    private static final double BORDER_CLEARANCE = 5;
    
    // Maximal angle for character rotation in radians.
    private static final float MAX_ANGLE = 0.7f;

    @Override
    public BufferedImage renderWord(String word, int width, int height) {
        final int fontSize = getConfig().getTextProducerFontSize();
        final Font[] fonts = getConfig().getTextProducerFonts(fontSize);
        
        final BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g2D = image.createGraphics();
        
        final Color textColor = getConfig().getTextProducerFontColor();
        g2D.setColor(textColor);

        g2D.setRenderingHints(Collections.EMPTY_MAP);
        g2D.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2D.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        final FontRenderContext frc = g2D.getFontRenderContext();
        final Random random = new Random();

        final GlyphVector[] glyphs = new GlyphVector[word.length()];
        final double[] charWidths = new double[word.length()];
        final double[] charHeights = new double[word.length()];
        
        final double[] offsets = new double[word.length()];
        double offsetSum = 0;
        double remainingWidth = width - 2 * BORDER_CLEARANCE;
        
        for (int i = 0; i < word.length(); i++) {
            // Generate glyph
            final Font chosenFont = fonts[random.nextInt(fonts.length)];
            final GlyphVector gv = chosenFont.createGlyphVector(frc,
                new char[] { word.charAt(i) });
            
            // Apply random glyph rotation
            final float theta = random.nextFloat() * MAX_ANGLE * 2.0f - MAX_ANGLE;
            final AffineTransform tr = new AffineTransform();
            tr.rotate(theta);
            gv.setGlyphTransform(0, tr);
            
            final Rectangle2D rotatedBounds = gv.getGlyphVisualBounds(0).getBounds2D();
            
            // Left-align, top-align glyph at (0,0)
            gv.setGlyphPosition(0,
                new Point2D.Double(-rotatedBounds.getMinX(), -rotatedBounds.getMinY()));
            
            // Update horizontal separation randomization
            final double offset = random.nextDouble();
            offsetSum += offset;
            remainingWidth -= rotatedBounds.getWidth();
            
            glyphs[i] = gv;
            charWidths[i] = rotatedBounds.getWidth();
            charHeights[i] = rotatedBounds.getHeight();
            offsets[i] = offset;
        }
        
        // Add right border random separation
        offsetSum += random.nextDouble();
        
        // Draw all glyphs at random vertical position, horizontal position relative to separation
        double posX = BORDER_CLEARANCE;
        for (int i = 0; i < glyphs.length; i++) {
            final GlyphVector gv = glyphs[i];
            final double charWidth = charWidths[i];
            final double charHeight = charHeights[i];
            final double remainingHeight = height - charHeight - 2 * BORDER_CLEARANCE;
            final double posY = random.nextDouble() * remainingHeight + BORDER_CLEARANCE;
            
            posX += offsets[i] / offsetSum * remainingWidth;
            g2D.drawGlyphVector(gv, (float)posX, (float)posY);
            posX += charWidth;
        }

        return image;
    }
}
