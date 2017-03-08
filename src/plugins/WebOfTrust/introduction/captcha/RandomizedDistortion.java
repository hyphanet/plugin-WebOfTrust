/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.introduction.captcha;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Random;

import plugins.WebOfTrust.introduction.captcha.kaptcha.GimpyEngine;
import plugins.WebOfTrust.introduction.captcha.kaptcha.NoiseProducer;
import plugins.WebOfTrust.introduction.captcha.kaptcha.util.Configurable;
import plugins.WebOfTrust.introduction.captcha.kaptcha.jhlabs.image.RippleFilter;
import plugins.WebOfTrust.introduction.captcha.kaptcha.jhlabs.image.TransformFilter;
import plugins.WebOfTrust.introduction.captcha.kaptcha.jhlabs.image.WaterFilter;
import plugins.WebOfTrust.introduction.captcha.kaptcha.jhlabs.image.TwirlFilter;

/**
 * Image filter that applies twirl, ripple and water filter with randomized coefficients, in
 * addition to noise curves.
 *
 * @author bertm
 */
public class RandomizedDistortion extends Configurable implements GimpyEngine {

    @Override
    public BufferedImage getDistortedImage(BufferedImage baseImage) {
        final NoiseProducer noiseProducer = getConfig().getNoiseImpl();
        final BufferedImage distortedImage = new BufferedImage(baseImage.getWidth(),
            baseImage.getHeight(), BufferedImage.TYPE_INT_ARGB);

        final Random r = new Random();
        final Graphics2D graphics = (Graphics2D)distortedImage.getGraphics();

        final TwirlFilter twirlFilter = new TwirlFilter();
        twirlFilter.setAngle(r.nextFloat() * 0.3f - 0.15f);
        twirlFilter.setEdgeAction(TransformFilter.NEAREST_NEIGHBOUR);

        final RippleFilter rippleFilter = new RippleFilter();
        rippleFilter.setWaveType(RippleFilter.SINE);
        rippleFilter.setXAmplitude(2.0f + r.nextFloat());
        rippleFilter.setYAmplitude(1.0f + r.nextFloat());
        rippleFilter.setXWavelength(15 + r.nextInt(10));
        rippleFilter.setYWavelength(5 + r.nextInt(5));
        rippleFilter.setEdgeAction(TransformFilter.NEAREST_NEIGHBOUR);

        final WaterFilter waterFilter = new WaterFilter();
        waterFilter.setAmplitude(1.5f);
        waterFilter.setPhase(10);
        waterFilter.setWavelength(2);
        waterFilter.setCentreX(0.25f + r.nextFloat() * 0.5f);
        waterFilter.setCentreY(0.25f + r.nextFloat() * 0.5f);
        waterFilter.setRadius(40.0f + r.nextFloat() * 20.0f);

        BufferedImage effectImage = twirlFilter.filter(baseImage, null);
        effectImage = waterFilter.filter(effectImage, null);
        effectImage = rippleFilter.filter(effectImage, null);

        graphics.drawImage(effectImage, 0, 0, null, null);
        graphics.dispose();

        noiseProducer.makeNoise(distortedImage, .1f, .1f, .25f, .25f);
        noiseProducer.makeNoise(distortedImage, .1f, .25f, .5f, .9f);

        return distortedImage;
    }
}
