package plugins.WoT.introduction.captcha;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import plugins.WoT.OwnIdentity;
import plugins.WoT.introduction.IntroductionPuzzle;
import plugins.WoT.introduction.IntroductionPuzzleFactory;

import com.db4o.ObjectContainer;

/**
 * Suggested captcha factory based on http://recaptcha.net/
 * 
 * 
 * @author xor
 *
 */
public class ReCaptchaFactory implements IntroductionPuzzleFactory {
	
	/* FIXME: Ask the recaptcha guys to modify their java library so that it is able to just return a JPEG instead of inlining their HTML */
	// recaptcha.ReCaptchaFactory mFactory = new recaptcha.ReCaptchaFactory();
	
	public IntroductionPuzzle generatePuzzle(ObjectContainer db, OwnIdentity inserter) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream(10 * 1024); /* TODO: find out the maximum size of the captchas and put it here */
		try {
			/*
			BufferedImage img = captcha.createImage(text);
			ImageIO.write(img, "jpg", out);
			
			Date dateOfInsertion = new Date();
			return new IntroductionPuzzle(inserter, PuzzleType.Captcha, "image/jpeg", out.toByteArray(), text, dateOfInsertion, IntroductionPuzzle.getFreeIndex(db, inserter, dateOfInsertion));
			*/
			return null;
		}
		finally {
			out.close();
		}
	}
}
