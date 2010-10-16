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

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.TimeZone;

import plugins.WoT.Identity;
import plugins.WoT.Trust;
import plugins.WoT.exceptions.UnknownIdentityException;
import freenet.clients.http.ToadletContext;
import freenet.l10n.BaseL10n;
import freenet.support.CurrentTimeUTC;
import freenet.support.HTMLNode;
import freenet.support.api.HTTPRequest;

/**
 * @author xor (xor@freenetproject.org)
 * @author bombe (bombe@freenetproject.org)
 * @version $Id$
 */
public class IdentityPage extends WebPageImpl {
	
	private final static SimpleDateFormat mDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

	/** The identity to show trust relationships of. */
	private final Identity identity;


	/**
	 * Creates a new trust-relationship web page.
	 * 
	 * @param toadlet A reference to the {@link WebInterfaceToadlet} which created the page, used to get resources the page needs.
	 * @param myRequest The request sent by the user.
	 * @throws UnknownIdentityException 
	 */
	public IdentityPage(WebInterfaceToadlet toadlet, HTTPRequest myRequest, ToadletContext context, BaseL10n _baseL10n) throws UnknownIdentityException {
		super(toadlet, myRequest, context, _baseL10n);
		
		identity = wot.getIdentityByID(request.getParam("id")); 
	}

	/**
	 * {@inheritDoc}
	 * 
	 * @see WebPage#make()
	 */
	public void make() {
		synchronized(identity) {
			/* Does not matter much if this information is not synchronous to the trust tables so we put it outside the lock on the WoT
			 * to reduce the time the whole WoT is locked. */
			makeURIBox();
			makeServicesBox();
			makeStatisticsBox();
		}

		HTMLNode trusteeTrustsNode = addContentBox(l10n().getString("IdentityPage.TrusteeTrustsBox.Header", "nickname", identity.getNickname()));
		HTMLNode trusteesTable = trusteeTrustsNode.addChild("table");
		HTMLNode trusteesTableHeader = trusteesTable.addChild("tr");
		trusteesTableHeader.addChild("th", l10n().getString("IdentityPage.TableHeader.Nickname"));
		trusteesTableHeader.addChild("th", l10n().getString("IdentityPage.TableHeader.Identity"));
		trusteesTableHeader.addChild("th", l10n().getString("IdentityPage.TableHeader.Value"));
		trusteesTableHeader.addChild("th", l10n().getString("IdentityPage.TableHeader.Comment"));

		HTMLNode trusterTrustsNode = addContentBox(l10n().getString("IdentityPage.TrusterTrustsBox.Header", "nickname", identity.getNickname()));
		HTMLNode trustersTable = trusterTrustsNode.addChild("table");
		HTMLNode trustersTableHeader = trustersTable.addChild("tr");
		trustersTableHeader.addChild("th", l10n().getString("IdentityPage.TableHeader.Nickname"));
		trustersTableHeader.addChild("th", l10n().getString("IdentityPage.TableHeader.Identity"));
		trustersTableHeader.addChild("th", l10n().getString("IdentityPage.TableHeader.Value"));
		trustersTableHeader.addChild("th", l10n().getString("IdentityPage.TableHeader.Comment"));
		
		synchronized(wot) {
			for (Trust trust : wot.getGivenTrusts(identity)) {
				HTMLNode trustRow = trusteesTable.addChild("tr");
				Identity trustee = trust.getTrustee();
				trustRow.addChild("td").addChild("a", "href", "?ShowIdentity&id=" + trustee.getID(), trustee.getNickname());
				trustRow.addChild("td", trustee.getID());
				trustRow.addChild("td", new String[]{"align", "style"}, new String[]{"right", "background-color:" + KnownIdentitiesPage.getTrustColor(trust.getValue()) + ";"}, Byte.toString(trust.getValue()));
				trustRow.addChild("td", trust.getComment());
			}

			for (Trust trust : wot.getReceivedTrusts(identity)) {
				HTMLNode trustRow = trustersTable.addChild("tr");
				Identity truster = trust.getTruster();
				trustRow.addChild("td").addChild("a", "href", "?ShowIdentity&id=" + truster.getID(), truster.getNickname());
				trustRow.addChild("td", truster.getID());
				trustRow.addChild("td", new String[]{"align", "style"}, new String[]{"right", "background-color:" + KnownIdentitiesPage.getTrustColor(trust.getValue()) + ";"}, Byte.toString(trust.getValue()));
				trustRow.addChild("td", trust.getComment());
			}
		}
	}
	
	private void makeURIBox() {
        HTMLNode boxContent = addContentBox(l10n().getString("IdentityPage.IdentityUriBox.Header", "nickname", identity.getNickname()));
		boxContent.addChild("p", l10n().getString("IdentityPage.IdentityUriBox.Text"));
		boxContent.addChild("p", identity.getRequestURI().toString());
	}
	
	private void makeServicesBox() {
		HTMLNode boxContent = addContentBox(l10n().getString("IdentityPage.ServicesBox.Header", "nickname", identity.getNickname()));
		Iterator<String> iter = identity.getContexts().iterator();
		StringBuilder contexts = new StringBuilder(128);
		while(iter.hasNext()) {
			contexts.append(iter.next());
			if(iter.hasNext())
				contexts.append(", ");
		}
		boxContent.addChild("p", contexts.toString());
	}
	
	private void makeStatisticsBox() {
		HTMLNode box = addContentBox(l10n().getString("IdentityPage.StatisticsBox.Header", "nickname", identity.getNickname()));
		
		long currentTime = CurrentTimeUTC.getInMillis();
		
		Date addedDate = identity.getAddedDate();
		String addedString;
		synchronized(mDateFormat) {
			mDateFormat.setTimeZone(TimeZone.getDefault());
			addedString = mDateFormat.format(addedDate) + " (" + CommonWebUtils.formatTimeDelta(currentTime - addedDate.getTime(), l10n()) + ")";
		}

		Date firstFetched = identity.getFirstFetchedDate();
		Date lastFetched = identity.getLastFetchedDate();
		String firstFetchedString;
		String lastFetchedString;
		if(!firstFetched.equals(new Date(0))) {
			synchronized(mDateFormat) {
				mDateFormat.setTimeZone(TimeZone.getDefault());
				/* SimpleDateFormat.format(Date in UTC) does convert to the configured TimeZone. Interesting, eh? */
				firstFetchedString = mDateFormat.format(firstFetched) + " (" + CommonWebUtils.formatTimeDelta(currentTime - firstFetched.getTime(), l10n()) + ")";
				lastFetchedString = mDateFormat.format(lastFetched) + " (" + CommonWebUtils.formatTimeDelta(currentTime - lastFetched.getTime(), l10n()) + ")";
			}
		}
		else {
			firstFetchedString = lastFetchedString = l10n().getString("Common.Never");
		}
		
		box.addChild("p", l10n().getString("IdentityPage.StatisticsBox.Added") + ": " + addedString); 
		box.addChild("p", l10n().getString("IdentityPage.StatisticsBox.FirstFetched") + ": " + firstFetchedString);
		box.addChild("p", l10n().getString("IdentityPage.StatisticsBox.LastFetched") + ": " + lastFetchedString);
	}
}
