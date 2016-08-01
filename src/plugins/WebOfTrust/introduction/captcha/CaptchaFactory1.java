/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.introduction.captcha;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Date;
import java.util.Properties;

import javax.imageio.ImageIO;

import plugins.WebOfTrust.OwnIdentity;
import plugins.WebOfTrust.introduction.IntroductionPuzzle.PuzzleType;
import plugins.WebOfTrust.introduction.IntroductionPuzzleFactory;
import plugins.WebOfTrust.introduction.IntroductionPuzzleStore;
import plugins.WebOfTrust.introduction.OwnIntroductionPuzzle;
import plugins.WebOfTrust.introduction.captcha.kaptcha.Constants;
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
	
	private static class Captcha {
		byte[] jpeg;
		String text;
		
		Captcha() throws IOException {
			ByteArrayOutputStream out = new ByteArrayOutputStream(10 * 1024); /* TODO: find out the maximum size of the captchas and put it here */
			try {
				DefaultKaptcha captcha = new DefaultKaptcha();
				Properties prop = new Properties();
				prop.setProperty(Constants.KAPTCHA_OBSCURIFICATOR_IMPL, RandomizedDistortion.class.getName());
				prop.setProperty(Constants.KAPTCHA_WORDRENDERER_IMPL, RandomizedWordRenderer.class.getName());
				captcha.setConfig(new Config(prop));
				text = captcha.createText();
				BufferedImage img = captcha.createImage(text);
				ImageIO.write(img, "jpg", out);
				jpeg = out.toByteArray();
			} finally {
				Closer.close(out);
			}
		}
	}

	@Override
	public OwnIntroductionPuzzle generatePuzzle(IntroductionPuzzleStore store, OwnIdentity inserter) throws IOException {
		Captcha c = new Captcha();

			String text = c.text;
			Date dateOfInsertion = CurrentTimeUTC.get();
			synchronized(store) {
				OwnIntroductionPuzzle puzzle = new OwnIntroductionPuzzle(store.getWebOfTrust(), inserter, PuzzleType.Captcha, "image/jpeg", c.jpeg, text, 
						dateOfInsertion, store.getFreeIndex(inserter, dateOfInsertion));
				
				store.storeAndCommit(puzzle);
				return puzzle;
			}
	}

	/**
	 * Run with:
	 * java -classpath ../fred/dist/freenet.jar:dist/WebOfTrust.jar
	 *     plugins.WebOfTrust.introduction.captcha.CaptchaFactory1 NUMBER_OF_CAPTCHAS OUTPUT_DIR
	 */
	public static void main(String[] args) throws IOException {
		if(args.length != 2)
			throw new IllegalArgumentException("Need arguments: NUMBER_OF_CAPTCHAS OUTPUT_DIR");
		
		int amount = Integer.parseInt(args[0]);
		Path outputDir = Paths.get(args[1]);
		
		while(--amount >= 0) {
			Captcha c = new Captcha();
			Path out = outputDir.resolve(c.text + ".jpg");
			Files.write(out, c.jpeg, StandardOpenOption.CREATE_NEW /* Throws if existing */);
		}
	}
}
