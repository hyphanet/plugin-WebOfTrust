/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.introduction.captcha;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Date;
import java.util.Properties;

import javax.imageio.ImageIO;

import plugins.WebOfTrust.OwnIdentity;
import plugins.WebOfTrust.introduction.IntroductionPuzzleFactory;
import plugins.WebOfTrust.introduction.IntroductionPuzzleStore;
import plugins.WebOfTrust.introduction.OwnIntroductionPuzzle;
import plugins.WebOfTrust.introduction.IntroductionPuzzle.PuzzleType;
import plugins.WebOfTrust.introduction.captcha.kaptcha.impl.DefaultKaptcha;
import plugins.WebOfTrust.introduction.captcha.kaptcha.util.Config;
import freenet.support.CurrentTimeUTC;
import freenet.support.io.Closer;

/**
 * First implementation of a captcha factory.
 * I added a "1" to the class because we should probably have many different captcha generators.
 *
 * Based on http://code.google.com/p/kaptcha/
 * 
 * @author xor
 *
 */
public class CaptchaFactory1 extends IntroductionPuzzleFactory {

	@Override
	public OwnIntroductionPuzzle generatePuzzle(IntroductionPuzzleStore store, OwnIdentity inserter) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream(10 * 1024); /* TODO: find out the maximum size of the captchas and put it here */
		try {
			DefaultKaptcha captcha = new DefaultKaptcha();
			captcha.setConfig(new Config(new Properties()));
			String text = captcha.createText();
			BufferedImage img = captcha.createImage(text);
			ImageIO.write(img, "jpg", out);
			
			Date dateOfInsertion = CurrentTimeUTC.get();
			synchronized(store) {
				OwnIntroductionPuzzle puzzle = new OwnIntroductionPuzzle(inserter, PuzzleType.Captcha, "image/jpeg", out.toByteArray(), text, 
						dateOfInsertion, store.getFreeIndex(inserter, dateOfInsertion));
				
				store.storeAndCommit(puzzle);
				return puzzle;
			}
		}
		finally {
			Closer.close(out);
		}
	}
}
