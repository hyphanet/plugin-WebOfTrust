/*
 * freenet - IdentityPage.java
 * Copyright © 2008 David Roden
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 */

package plugins.WoT.ui.web;

import java.util.List;

import plugins.WoT.Identity;
import plugins.WoT.Trust;
import plugins.WoT.WoT;
import freenet.support.HTMLNode;
import freenet.support.api.HTTPRequest;

/**
 * @author David &lsquo;Bombe&rsquo; Roden &lt;bombe@freenetproject.org&gt;
 * @version $Id$
 */
public class IdentityPage extends WebPageImpl {

	/** The identity to show trust relationships of. */
	private final Identity identity;

	/** All trusts that trust the identity. */
	private final List<Trust> trustersTrusts;

	/** All trusts that the identity trusts. */
	private final List<Trust> trusteesTrusts;

	/**
	 * Creates a new trust-relationship web page.
	 * 
	 * @param webOfTrust
	 *            The web of trust
	 * @param httpRequest
	 *            The HTTP request
	 * @param identity
	 *            The identity
	 * @param trustersTrusts
	 *            The trusts having the given identity as truster
	 * @param trusteesTrusts
	 *            The trusts having the given identity as trustee
	 */
	public IdentityPage(WoT webOfTrust, HTTPRequest httpRequest, Identity identity, List<Trust> trustersTrusts, List<Trust> trusteesTrusts) {
		super(webOfTrust, httpRequest);
		this.identity = identity;
		this.trustersTrusts = trustersTrusts;
		this.trusteesTrusts = trusteesTrusts;
	}

	/**
	 * {@inheritDoc}
	 * 
	 * @see WebPage#make()
	 */
	public void make() {
		HTMLNode trusteeTrustsNode = getContentBox("Identities that “" + identity.getNickName() + "” trusts");

		HTMLNode trustersTable = trusteeTrustsNode.addChild("table");
		HTMLNode trustersTableHeader = trustersTable.addChild("tr");
		trustersTableHeader.addChild("th", "NickName");
		trustersTableHeader.addChild("th", "Identity");
		trustersTableHeader.addChild("th", "Trust Value");
		trustersTableHeader.addChild("th", "Comment");

		for (Trust trust : trusteesTrusts) {
			HTMLNode trustRow = trustersTable.addChild("tr");
			Identity trustee = trust.getTrustee();
			trustRow.addChild("td").addChild("a", "href", "?showIdentity&id=" + trustee.getId(), trustee.getNickName());
			trustRow.addChild("td", trustee.getId());
			trustRow.addChild("td", "align", "right", String.valueOf(trust.getValue()));
			trustRow.addChild("td", trust.getComment());
		}

		HTMLNode trusterTrustsNode = getContentBox("Identities that trust “" + identity.getNickName() + "”");

		HTMLNode trusteesTable = trusterTrustsNode.addChild("table");
		HTMLNode trusteesTableHeader = trusteesTable.addChild("tr");
		trusteesTableHeader.addChild("th", "NickName");
		trusteesTableHeader.addChild("th", "Identity");
		trusteesTableHeader.addChild("th", "Trust Value");
		trusteesTableHeader.addChild("th", "Comment");

		for (Trust trust : trustersTrusts) {
			HTMLNode trustRow = trusteesTable.addChild("tr");
			Identity truster = trust.getTruster();
			trustRow.addChild("td").addChild("a", "href", "?showIdentity&id=" + truster.getId(), truster.getNickName());
			trustRow.addChild("td", truster.getId());
			trustRow.addChild("td", "align", "right", String.valueOf(trust.getValue()));
			trustRow.addChild("td", trust.getComment());
		}
	}

}
