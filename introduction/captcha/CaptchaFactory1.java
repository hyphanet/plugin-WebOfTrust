/*
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */
package plugins.WoT.introduction.captcha;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

import plugins.WoT.Identity;
import plugins.WoT.introduction.IntroductionPuzzle;
import plugins.WoT.introduction.IntroductionPuzzleFactory;
import plugins.WoT.introduction.captcha.kaptcha.impl.DefaultKaptcha;

/**
 * First implementation of a captcha factory.
 * I added a "1" to the class because we should probably have many different captcha generators.
 * I suggest we use this one for the first implementation:
 * http://simplecaptcha.sourceforge.net/
 * 
 * If anyone knows a better library than simplecaptcha please comment.
 * 
 * @author xor
 *
 */
public class CaptchaFactory1 implements IntroductionPuzzleFactory {

	public IntroductionPuzzle generatePuzzle(Identity inserter, int index) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream(10 * 1024);
		try {
			DefaultKaptcha captcha = new DefaultKaptcha();
			String text = captcha.createText();
			BufferedImage img = captcha.createImage(text);
			 /* TODO: find out the maximum size of the captchas and put it here */
			
			ImageIO.write(img, "jpg", out);
			return new IntroductionPuzzle(inserter, "image/jpeg", out.toByteArray(), text, index);
		}
		finally {
			out.close();
		}
	}
}
