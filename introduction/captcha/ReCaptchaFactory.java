package plugins.WoT.introduction.captcha;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import plugins.WoT.OwnIdentity;
import plugins.WoT.introduction.IntroductionPuzzle;
import plugins.WoT.introduction.IntroductionPuzzleFactory;

import com.db4o.ObjectContainer;

import freenet.support.io.Closer;

/**
 * Suggested captcha factory based on http://recaptcha.net/
 * We would only need to find a decent way of anonymizing the requests using a public anonymization gateway or whatever of course. Maybe
 * someone has an idea? Public tor proxies?
 * 
 * We could also ask the recaptcha people to run their own seed identity for us which provides insane amounts of captchas per day.
 * 
 * First thing we could do: Just implement the ReCaptchaFactory without anonymization and make our seed identity only insert recaptchas.
 * We would have to change the KSK at which to insert captcha solutions to include the hash of the solution an not the solution itself because
 * ReCaptcha cannot give away the solution as they only check one of the two words in the captcha. This could be done by extending the 
 * class IntroductionPuzzle.
 * 
 * Description:
 *  reCAPTCHA improves the process of digitizing books by sending words that cannot be read by computers to the Web in the form of CAPTCHAs
 *  for humans to decipher. More specifically, each word that cannot be read correctly by OCR is placed on an image and used as a CAPTCHA. 
 *  This is possible because most OCR programs alert you when a word cannot be read correctly.
 *  
 *  But if a computer can't read such a CAPTCHA, how does the system know the correct answer to the puzzle? Here's how: Each new word that
 *  cannot be read correctly by OCR is given to a user in conjunction with another word for which the answer is already known. The user is
 *  then asked to read both words. If they solve the one for which the answer is known, the system assumes their answer is correct for the
 *  new one. The system then gives the new image to a number of other people to determine, with higher confidence, whether the original answer
 *  was correct. 
 * 
 * @author xor
 *
 */
public class ReCaptchaFactory extends IntroductionPuzzleFactory {
	
	/* FIXME: Ask the recaptcha guys to modify their java library so that it is able to just return a JPEG instead of inlining their HTML */
	// recaptcha.ReCaptchaFactory mFactory = new recaptcha.ReCaptchaFactory();
	
	@Override
	public IntroductionPuzzle generatePuzzle(ObjectContainer db, OwnIdentity inserter) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream(10 * 1024); /* TODO: find out the maximum size of the captchas and put it here */
		try {
			/*
			BufferedImage img = captcha.createImage(text);
			ImageIO.write(img, "jpg", out);
			
			Date dateOfInsertion = getUTCDate();
			return new IntroductionPuzzle(inserter, PuzzleType.Captcha, "image/jpeg", out.toByteArray(), text, dateOfInsertion, IntroductionPuzzle.getFreeIndex(db, inserter, dateOfInsertion));
			*/
			return null;
		}
		finally {
			Closer.close(out);
		}
	}
}
