/*
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */
package plugins.WoT.ui.web;

import java.util.Set;

import plugins.WoT.Identity;
import plugins.WoT.Trust;
import plugins.WoT.WoT;
import freenet.support.HTMLNode;
import freenet.support.api.HTTPRequest;

/**
 * Web page that shows all identities that give trust to a given identity.
 * 
 * @author David ‘Bombe’ Roden <bombe@freenetproject.org>
 */
public class TrustersPage extends WebPageImpl {

	/** The identity to show the trusters of. */
	private final Identity identity;

	/** Set of trusts that have the given identity as trustee. */
	private final Set<Trust> trusts;

	/**
	 * Creates a new truster web page.
	 * 
	 * @param wot
	 *            a reference to the WoT, used to get resources the page needs.
	 * @param request
	 *            the request sent by the user.
	 * @param identity
	 *            The identity to show the trusters of
	 * @param trusters
	 *            The trusts that have the given identity as trustee
	 */
	public TrustersPage(WoT wot, HTTPRequest request, Identity identity, Set<Trust> trusters) {
		super(wot, request);
		this.identity = identity;
		this.trusts = trusters;
	}

	/**
	 * {@inheritDoc}
	 * 
	 * @see plugins.WoT.ui.web.WebPage#make()
	 */
	public void make() {
		HTMLNode contentNode = getContentBox("Identities that trust “" + identity.getNickName() + "”");

		HTMLNode trustersTable = contentNode.addChild("table");
		HTMLNode trustersTableHeader = trustersTable.addChild("tr");
		trustersTableHeader.addChild("th", "NickName");
		trustersTableHeader.addChild("th", "Identity");
		trustersTableHeader.addChild("th", "Trust Value");
		trustersTableHeader.addChild("th", "Comment");

		for (Trust trust : trusts) {
			HTMLNode trustRow = trustersTable.addChild("tr");
			trustRow.addChild("td", trust.getTruster().getNickName());
			trustRow.addChild("td", trust.getTruster().getId());
			trustRow.addChild("td", "align", "right", String.valueOf(trust.getValue()));
			trustRow.addChild("td", trust.getComment());
		}
	}

}
