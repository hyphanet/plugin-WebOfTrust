/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.introduction.captcha;

import static java.lang.Math.max;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Date;
import java.util.HashSet;
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

	/** Determined by generating 10000 captchas using {@link #main(String[])}. */
	public static final int ESTIMATED_MAX_BYTE_SIZE = 4096;

	private static class Captcha {
		byte[] jpeg;
		String text;
		
		Captcha() throws IOException {
			ByteArrayOutputStream out = new ByteArrayOutputStream(ESTIMATED_MAX_BYTE_SIZE);
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
		Date dateOfInsertion = CurrentTimeUTC.get();
		synchronized(store) {
			OwnIntroductionPuzzle puzzle = new OwnIntroductionPuzzle(store.getWebOfTrust(), inserter, PuzzleType.Captcha, "image/jpeg", c.jpeg, c.text, 
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
		HashSet<String> alreadyCreated = new HashSet<>(amount * 2);
		int maxSize = 0;
		
		while(--amount >= 0) {
			Captcha c = new Captcha();
			if(!alreadyCreated.add(c.text)) {
				++amount;
				continue;
			}
			
			maxSize = max(maxSize, c.jpeg.length);
			
			Path out = outputDir.resolve(c.text + ".jpg");
			Files.write(out, c.jpeg, StandardOpenOption.CREATE_NEW /* Throws if existing */);
		}
		
		System.out.println("Largest byte size: " + maxSize);
	}
}
