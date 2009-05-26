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
	 * @param myWebInterface A reference to the WebInterface which created the page, used to get resources the page needs. 
	 * @param myRequest The request sent by the user.
	 * @throws UnknownIdentityException 
	 */
	public IdentityPage(WebInterface myWebInterface, HTTPRequest myRequest) throws UnknownIdentityException {
		super(myWebInterface, myRequest);
		
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
		
		HTMLNode trusteeTrustsNode = addContentBox("Identities that '" + identity.getNickname() + "' trusts");
		HTMLNode trusteesTable = trusteeTrustsNode.addChild("table");
		HTMLNode trusteesTableHeader = trusteesTable.addChild("tr");
		trusteesTableHeader.addChild("th", "Nickname");
		trusteesTableHeader.addChild("th", "Identity");
		trusteesTableHeader.addChild("th", "Value");
		trusteesTableHeader.addChild("th", "Comment");

		HTMLNode trusterTrustsNode = addContentBox("Identities that trust '" + identity.getNickname() + "'");
		HTMLNode trustersTable = trusterTrustsNode.addChild("table");
		HTMLNode trustersTableHeader = trustersTable.addChild("tr");
		trustersTableHeader.addChild("th", "Nickname");
		trustersTableHeader.addChild("th", "Identity");
		trustersTableHeader.addChild("th", "Value");
		trustersTableHeader.addChild("th", "Comment");
		
		synchronized(wot) {
			for (Trust trust : wot.getGivenTrusts(identity)) {
				HTMLNode trustRow = trusteesTable.addChild("tr");
				Identity trustee = trust.getTrustee();
				trustRow.addChild("td").addChild("a", "href", "?ShowIdentity&id=" + trustee.getID(), trustee.getNickname());
				trustRow.addChild("td", trustee.getID());
				trustRow.addChild("td", "align", "right", Byte.toString(trust.getValue()));
				trustRow.addChild("td", trust.getComment());
			}

			for (Trust trust : wot.getReceivedTrusts(identity)) {
				HTMLNode trustRow = trustersTable.addChild("tr");
				Identity truster = trust.getTruster();
				trustRow.addChild("td").addChild("a", "href", "?ShowIdentity&id=" + truster.getID(), truster.getNickname());
				trustRow.addChild("td", truster.getID());
				trustRow.addChild("td", "align", "right", Byte.toString(trust.getValue()));
				trustRow.addChild("td", trust.getComment());
			}
		}
	}
	
	private void makeURIBox() {
		HTMLNode boxContent = addContentBox("Reference of identity '" + identity.getNickname() + "'");
		boxContent.addChild("p", "The following Freenet URI is a reference to this identity. If you want to tell other people about this identity, give the URI to them: ");
		boxContent.addChild("p", identity.getRequestURI().toString());
	}
	
	private void makeServicesBox() {
		HTMLNode boxContent = addContentBox("Services of identity '" + identity.getNickname() + "'");
		Iterator<String> iter = identity.getContexts().iterator();
		StringBuilder contexts = new StringBuilder(128);
		while(iter.hasNext()) {
			contexts.append(iter.next());
			if(iter.hasNext())
				contexts.append(", ");
		}
		boxContent.addChild("p", contexts.toString());
	}
	
	private String formatTimeDelta(long delta) {
		long days = delta / (1000 * 60 * 60 * 24);
		long hours = (delta % (1000 * 60 * 60 * 24)) / (1000 * 60 * 60);
		long minutes = ((delta % (1000 * 60 * 60 * 24)) % (1000 * 60 * 60)) / (1000 * 60);
		
		if(days > 0)
			return days + "d " + hours + "h " + minutes + "m ago";
		else if(hours > 0)
			return hours + "h " + minutes + "m ago";
		else
			return minutes + "m ago";
	}
	
	private void makeStatisticsBox() {
		HTMLNode box = addContentBox("Statistics about identity '" + identity.getNickname() + "'");
		
		long currentTime = CurrentTimeUTC.getInMillis();
		
		Date addedDate = identity.getAddedDate();
		String addedString;
		synchronized(mDateFormat) {
			mDateFormat.setTimeZone(TimeZone.getDefault());
			addedString = mDateFormat.format(addedDate) + " (" + formatTimeDelta(currentTime - addedDate.getTime()) + ")";
		}

		Date firstFetched = identity.getFirstFetchedDate();
		Date lastFetched = identity.getLastFetchedDate();
		String firstFetchedString;
		String lastFetchedString;
		if(!firstFetched.equals(new Date(0))) {
			synchronized(mDateFormat) {
				mDateFormat.setTimeZone(TimeZone.getDefault());
				/* SimpleDateFormat.format(Date in UTC) does convert to the configured TimeZone. Interesting, eh? */
				firstFetchedString = mDateFormat.format(firstFetched) + " (" + formatTimeDelta(currentTime - firstFetched.getTime()) + ")";
				lastFetchedString = mDateFormat.format(lastFetched) + " (" + formatTimeDelta(currentTime - lastFetched.getTime()) + ")";
			}
		}
		else {
			firstFetchedString = "never";
			lastFetchedString = "never";
		}
		
		box.addChild("p", "Added: " + addedString); 
		box.addChild("p", "First fetched: " + firstFetchedString);
		box.addChild("p", "Last fetched: " + lastFetchedString);
	}

	
}
