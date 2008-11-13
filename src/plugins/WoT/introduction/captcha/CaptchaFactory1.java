/*
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */
package plugins.WoT.introduction.captcha;

import plugins.WoT.introduction.IntroductionPuzzle;
import plugins.WoT.introduction.IntroductionPuzzleFactory;

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

	public IntroductionPuzzle generatePuzzle() {
		return null;
	}

}
