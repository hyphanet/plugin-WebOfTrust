package plugins.WoT.introduction.captcha.kaptcha.util;

import java.awt.Color;
import java.awt.Font;
import java.util.Properties;

import plugins.WoT.introduction.captcha.kaptcha.BackgroundProducer;
import plugins.WoT.introduction.captcha.kaptcha.Constants;
import plugins.WoT.introduction.captcha.kaptcha.GimpyEngine;
import plugins.WoT.introduction.captcha.kaptcha.NoiseProducer;
import plugins.WoT.introduction.captcha.kaptcha.Producer;
import plugins.WoT.introduction.captcha.kaptcha.impl.DefaultBackground;
import plugins.WoT.introduction.captcha.kaptcha.impl.DefaultKaptcha;
import plugins.WoT.introduction.captcha.kaptcha.impl.DefaultNoise;
import plugins.WoT.introduction.captcha.kaptcha.impl.WaterRipple;
import plugins.WoT.introduction.captcha.kaptcha.text.TextProducer;
import plugins.WoT.introduction.captcha.kaptcha.text.WordRenderer;
import plugins.WoT.introduction.captcha.kaptcha.text.impl.DefaultTextCreator;
import plugins.WoT.introduction.captcha.kaptcha.text.impl.DefaultWordRenderer;

/**
 * {@link Config} retrieves configuration values from properties file and
 * specifies default values when no value is specified.
 */
public class Config
{
	private Properties properties;

	private ConfigHelper helper;

	/** */
	public Config(Properties properties)
	{
		this.properties = properties;
		helper = new ConfigHelper();
	}

	/** */
	public boolean isBorderDrawn()
	{
		String paramName = Constants.KAPTCHA_BORDER;
		String paramValue = properties.getProperty(paramName);
		return helper.getBoolean(paramName, paramValue, true);
	}

	/** */
	public Color getBorderColor()
	{
		String paramName = Constants.KAPTCHA_BORDER_COLOR;
		String paramValue = properties.getProperty(paramName);
		return helper.getColor(paramName, paramValue, Color.BLACK);
	}

	/** */
	public int getBorderThickness()
	{
		String paramName = Constants.KAPTCHA_BORDER_THICKNESS;
		String paramValue = properties.getProperty(paramName);
		return helper.getPositiveInt(paramName, paramValue, 1);
	}

	/** */
	public Producer getProducerImpl()
	{
		String paramName = Constants.KAPTCHA_PRODUCER_IMPL;
		String paramValue = properties.getProperty(paramName);
		Producer producer = (Producer) helper.getClassInstance(paramName, paramValue, new DefaultKaptcha(), this);
		return producer;
	}

	/** */
	public TextProducer getTextProducerImpl()
	{
		String paramName = Constants.KAPTCHA_TEXTPRODUCER_IMPL;
		String paramValue = properties.getProperty(paramName);
		TextProducer textProducer = (TextProducer) helper.getClassInstance(paramName, paramValue,
				new DefaultTextCreator(), this);
		return textProducer;
	}

	/** */
	public char[] getTextProducerCharString()
	{
		String paramName = Constants.KAPTCHA_TEXTPRODUCER_CHAR_STRING;
		String paramValue = properties.getProperty(paramName);
		return helper.getChars(paramName, paramValue, "abcde2345678gfynmnpwx".toCharArray());
	}

	/** */
	public int getTextProducerCharLength()
	{
		String paramName = Constants.KAPTCHA_TEXTPRODUCER_CHAR_LENGTH;
		String paramValue = properties.getProperty(paramName);
		return helper.getPositiveInt(paramName, paramValue, 5);
	}

	/** */
	public Font[] getTextProducerFonts(int fontSize)
	{
		String paramName = Constants.KAPTCHA_TEXTPRODUCER_FONT_NAMES;
		String paramValue = properties.getProperty(paramName);
		return helper.getFonts(paramName, paramValue, fontSize, new Font[]{
				new Font("Arial", Font.BOLD, fontSize), new Font("Courier", Font.BOLD, fontSize)
		});
	}

	/** */
	public int getTextProducerFontSize()
	{
		String paramName = Constants.KAPTCHA_TEXTPRODUCER_FONT_SIZE;
		String paramValue = properties.getProperty(paramName);
		return helper.getPositiveInt(paramName, paramValue, 40);
	}

	/** */
	public Color getTextProducerFontColor()
	{
		String paramName = Constants.KAPTCHA_TEXTPRODUCER_FONT_COLOR;
		String paramValue = properties.getProperty(paramName);
		return helper.getColor(paramName, paramValue, Color.BLACK);
	}

	public NoiseProducer getNoiseImpl()
	{
		String paramName = Constants.KAPTCHA_NOISE_IMPL;
		String paramValue = properties.getProperty(paramName);
		NoiseProducer noiseProducer = (NoiseProducer) helper.getClassInstance(paramName, paramValue,
				new DefaultNoise(), this);
		return noiseProducer;
	}

	/** */
	public Color getNoiseColor()
	{
		String paramName = Constants.KAPTCHA_NOISE_COLOR;
		String paramValue = properties.getProperty(paramName);
		return helper.getColor(paramName, paramValue, Color.BLACK);
	}

	/** */
	public GimpyEngine getObscurificatorImpl()
	{
		String paramName = Constants.KAPTCHA_OBSCURIFICATOR_IMPL;
		String paramValue = properties.getProperty(paramName);
		GimpyEngine gimpyEngine = (GimpyEngine) helper.getClassInstance(paramName, paramValue, new WaterRipple(), this);
		return gimpyEngine;
	}

	/** */
	public WordRenderer getWordRendererImpl()
	{
		String paramName = Constants.KAPTCHA_WORDRENDERER_IMPL;
		String paramValue = properties.getProperty(paramName);
		WordRenderer wordRenderer = (WordRenderer) helper.getClassInstance(paramName, paramValue,
				new DefaultWordRenderer(), this);
		return wordRenderer;
	}

	/** */
	public BackgroundProducer getBackgroundImpl()
	{
		String paramName = Constants.KAPTCHA_BACKGROUND_IMPL;
		String paramValue = properties.getProperty(paramName);
		BackgroundProducer backgroundProducer = (BackgroundProducer) helper.getClassInstance(paramName, paramValue,
				new DefaultBackground(), this);
		return backgroundProducer;
	}

	/** */
	public Color getBackgroundColorFrom()
	{
		String paramName = Constants.KAPTCHA_BACKGROUND_CLR_FROM;
		String paramValue = properties.getProperty(paramName);
		return helper.getColor(paramName, paramValue, Color.LIGHT_GRAY);
	}

	/** */
	public Color getBackgroundColorTo()
	{
		String paramName = Constants.KAPTCHA_BACKGROUND_CLR_TO;
		String paramValue = properties.getProperty(paramName);
		return helper.getColor(paramName, paramValue, Color.WHITE);
	}

	/** */
	public int getWidth()
	{
		String paramName = Constants.KAPTCHA_IMAGE_WIDTH;
		String paramValue = properties.getProperty(paramName);
		return helper.getPositiveInt(paramName, paramValue, 200);
	}

	/** */
	public int getHeight()
	{
		String paramName = Constants.KAPTCHA_IMAGE_HEIGHT;
		String paramValue = properties.getProperty(paramName);
		return helper.getPositiveInt(paramName, paramValue, 50);
	}

	/**
	 * Allows one to override the key name which is stored in the users
	 * HttpSession. Defaults to Constants.KAPTCHA_SESSION_KEY.
	 */
	public String getSessionKey()
	{
		return properties.getProperty(Constants.KAPTCHA_SESSION_CONFIG_KEY, Constants.KAPTCHA_SESSION_KEY);
	}

	/** */
	public Properties getProperties()
	{
		return properties;
	}
}
