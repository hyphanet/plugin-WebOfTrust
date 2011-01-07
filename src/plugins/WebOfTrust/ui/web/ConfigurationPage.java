/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.ui.web;

import java.util.Arrays;

import plugins.WebOfTrust.Configuration;
import freenet.clients.http.ToadletContext;
import freenet.l10n.BaseL10n;
import freenet.support.HTMLNode;
import freenet.support.api.HTTPRequest;

/**
 * @author xor (xor@freenetproject.org)
 */
public class ConfigurationPage extends WebPageImpl {

	/**
	 * @param myRequest The request sent by the user.
	 * @param _baseL10n l10n handle
	 * @param toadlet A reference to the {@link WebInterfaceToadlet} which created the page, used to get resources the page needs.
	 */
	public ConfigurationPage(WebInterfaceToadlet toadlet, HTTPRequest myRequest, ToadletContext context, BaseL10n _baseL10n) {
		super(toadlet, myRequest, context, _baseL10n);
	}

	// TODO: Maybe use or steal freenet.clients.http.ConfigToadlet
	public void make() {
		HTMLNode list1 = new HTMLNode("ul");
		HTMLNode list2 = new HTMLNode("ul");
		
		Configuration config = wot.getConfig();
		synchronized(config) {
			String[] intKeys = config.getAllIntKeys();
			String[] stringKeys = config.getAllStringKeys();
			
			Arrays.sort(intKeys);
			Arrays.sort(stringKeys);

			for(String key : intKeys) list1.addChild(new HTMLNode("li", key + ": " + config.getInt(key)));
			for(String key : stringKeys) list1.addChild(new HTMLNode("li", key + ": " + config.getString(key)));
		}

		HTMLNode box = addContentBox(l10n().getString("ConfigurationPage.ConfigurationBox.Header"));
		box.addChild(list1);
		box.addChild(list2);
	}
}
