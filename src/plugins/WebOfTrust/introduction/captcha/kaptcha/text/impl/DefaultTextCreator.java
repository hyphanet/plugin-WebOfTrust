package plugins.WoT.introduction.captcha.kaptcha.text.impl;

import java.util.Random;

import plugins.WoT.introduction.captcha.kaptcha.text.TextProducer;
import plugins.WoT.introduction.captcha.kaptcha.util.Configurable;

/**
 * {@link DefaultTextCreator} creates random text from an array of characters
 * with specified length.
 */
public class DefaultTextCreator extends Configurable implements TextProducer
{
	/**
	 * @return the random text
	 */
	public String getText()
	{
		int length = getConfig().getTextProducerCharLength();
		char[] chars = getConfig().getTextProducerCharString();
		int randomContext = chars.length - 1;
		Random rand = new Random();
		StringBuffer text = new StringBuffer();
		for (int i = 0; i < length; i++)
		{
			text.append(chars[rand.nextInt(randomContext) + 1]);
		}

		return text.toString();
	}
}
