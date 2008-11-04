/**
 * 
 */
package plugins.WoT.ui.web;

import plugins.WoT.WoT;
import freenet.support.api.HTTPRequest;

/**
 * @author p0s
 *
 */
public class ConfigurationPage extends WebPageImpl {

	/**
	 * @param wot a reference to the WoT, used to get resources the page needs. 
	 * @param request the request sent by the user.
	 */
	public ConfigurationPage(WoT wot, HTTPRequest request) {
		super(wot, request);
	}

	/* (non-Javadoc)
	 * @see plugins.WoT.ui.web.WebPage#make()
	 */
	public void make() {
		// TODO Generate the configuration page

	}

}
