/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.ui.web;

import java.util.Date;
import java.util.TreeMap;

import plugins.WebOfTrust.Identity;
import plugins.WebOfTrust.Identity.IdentityID;
import plugins.WebOfTrust.OwnIdentity;
import plugins.WebOfTrust.Score;
import plugins.WebOfTrust.Trust;
import plugins.WebOfTrust.WebOfTrust;
import plugins.WebOfTrust.exceptions.DuplicateScoreException;
import plugins.WebOfTrust.exceptions.DuplicateTrustException;
import plugins.WebOfTrust.exceptions.InvalidParameterException;
import plugins.WebOfTrust.exceptions.NotInTrustTreeException;
import plugins.WebOfTrust.exceptions.NotTrustedException;
import plugins.WebOfTrust.exceptions.UnknownIdentityException;
import plugins.WebOfTrust.ui.web.WebInterface.IdentityWebInterfaceToadlet;
import freenet.clients.http.InfoboxNode;
import freenet.clients.http.RedirectException;
import freenet.clients.http.SessionManager.Session;
import freenet.clients.http.ToadletContext;
import freenet.keys.FreenetURI;
import freenet.pluginmanager.PluginRespirator;
import freenet.support.CurrentTimeUTC;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.api.HTTPRequest;


/**
 * The page where users can manage others identities.
 * 
 * @author xor (xor@freenetproject.org)
 * @author Julien Cornuwel (batosai@freenetproject.org)
 */
public class KnownIdentitiesPage extends WebPageImpl {

	private final OwnIdentity treeOwner;
	
	private final String identitiesPageURI;
	
	private static enum SortBy {
		Nickname,
		Score,
		LocalTrust
	};
	
	/**
	 * Creates a new KnownIdentitiesPage
	 * 
	 * @param toadlet A reference to the {@link WebInterfaceToadlet} which created the page, used to get resources the page needs.
	 * @param myRequest The request sent by the user.
	 * @throws RedirectException If the {@link Session} has expired. 
	 * @throws UnknownIdentityException If the owner of the {@link Session} does not exist anymore.
	 */
	public KnownIdentitiesPage(WebInterfaceToadlet toadlet, HTTPRequest myRequest, ToadletContext context) throws RedirectException, UnknownIdentityException {
		super(toadlet, myRequest, context, true);
		identitiesPageURI = mWebInterface.getToadlet(IdentityWebInterfaceToadlet.class).getURI().toString();
		treeOwner = wot.getOwnIdentityByID(mLoggedInOwnIdentityID);
	}

	public void make() {
		final boolean addIdentity = request.isPartSet("AddIdentity");
		
		if(addIdentity) {
			try {
				wot.addIdentity(request.getPartAsStringFailsafe("IdentityURI", 1024));
				HTMLNode successBox = addContentBox(l10n().getString("KnownIdentitiesPage.AddIdentity.Success.Header"));
				successBox.addChild("#", l10n().getString("KnownIdentitiesPage.AddIdentity.Success.Text"));
			}
			catch(Exception e) {
				addErrorBox(l10n().getString("KnownIdentitiesPage.AddIdentity.Failed"), e);
			}
		}
		
		if(request.isPartSet("SetTrust")) {
			String trusterID = mLoggedInOwnIdentityID;
		
			for(String part : request.getParts()) {
				if(!part.startsWith("SetTrustOf"))
					continue;

				final String trusteeID;
				final String value;
				final String comment;

				try { 
					if(addIdentity) { // Add a single identity and set its trust value
						trusteeID = IdentityID.constructAndValidateFromURI(new FreenetURI(request.getPartAsStringFailsafe("IdentityURI", 1024))).toString();
						value = request.getPartAsStringFailsafe("Value", 4).trim();
						comment = request.getPartAsStringFailsafe("Comment", Trust.MAX_TRUST_COMMENT_LENGTH + 1);				 	
					} else { // Change multiple trust values via the known-identities-list
						trusteeID = request.getPartAsStringFailsafe(part, 128);
						value = request.getPartAsStringFailsafe("Value" + trusteeID, 4).trim();
						comment = request.getPartAsStringFailsafe("Comment" + trusteeID, Trust.MAX_TRUST_COMMENT_LENGTH + 1);
					}

					if(value.equals(""))
						wot.removeTrust(trusterID, trusteeID);
					else
						wot.setTrust(trusterID, trusteeID, Byte.parseByte(value), comment);
				} catch(NumberFormatException e) {
					addErrorBox(l10n().getString("KnownIdentitiesPage.SetTrust.Failed"), l10n().getString("Trust.InvalidValue"));
				} catch(InvalidParameterException e) {
					addErrorBox(l10n().getString("KnownIdentitiesPage.SetTrust.Failed"), e.getMessage());
				} catch(Exception e) {
					addErrorBox(l10n().getString("KnownIdentitiesPage.SetTrust.Failed"), e);
				}
			}
		}

		if(treeOwner.isRestoreInProgress()) {
			makeRestoreInProgressWarning();
			return;
		}
			
		makeAddIdentityForm(treeOwner);

			try {
				makeKnownIdentitiesList(treeOwner);
			} catch (Exception e) {
				Logger.error(this, "Error", e);
				addErrorBox("Error", e);
			}
	}
	
	/**
	 * Makes a form where the user can enter the requestURI of an Identity he knows.
	 * 
	 * @param pr a reference to the {@link PluginRespirator}
	 * @param treeOwner The owner of the known identity list. Not used for adding the identity but for showing the known identity list properly after adding.
	 */
	private void makeAddIdentityForm(OwnIdentity treeOwner) {
		
		// TODO Add trust value and comment fields and make them mandatory
		// The user should only add an identity he trusts
		HTMLNode addBoxContent = addContentBox(l10n().getString("KnownIdentitiesPage.AddIdentity.Header"));
	
		HTMLNode createForm = pr.addFormChild(addBoxContent, uri.toString(), "AddIdentity");

		createForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "page", "AddIdentity" });
		createForm.addChild("span", new String[] {"title", "style"}, 
				new String[] { 
		            l10n().getString("KnownIdentitiesPage.AddIdentity.IdentityURI.Tooltip"), 
		            "border-bottom: 1px dotted; cursor: help;"} , 
		            l10n().getString("KnownIdentitiesPage.AddIdentity.IdentityURI") + ": ");
		
		createForm.addChild("input", new String[] {"type", "name", "size"}, new String[] {"text", "IdentityURI", "70"});
		createForm.addChild("br");
		
		if(treeOwner != null) {
			createForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "SetTrust", "true"});
			createForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "SetTrustOf", "void"});
			
			createForm.addChild("span", l10n().getString("KnownIdentitiesPage.AddIdentity.Trust") + ": ")
				.addChild("input", new String[] { "type", "name", "size", "value" }, new String[] { "text", "Value", "4", "" });
			
			createForm.addChild("span", " " + l10n().getString("KnownIdentitiesPage.AddIdentity.Comment") + ": ")
				.addChild("input", new String[] { "type", "name", "size", "value" }, new String[] { "text", "Comment", "20", "" });
			
			createForm.addChild("br");
		}
		
		createForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "AddIdentity", l10n().getString("KnownIdentitiesPage.AddIdentity.AddButton") });
	}
	
	private void makeRestoreInProgressWarning() {
		addErrorBox(l10n().getString("KnownIdentitiesPage.RestoreInProgressWarning.Header"), l10n().getString("KnownIdentitiesPage.RestoreInProgressWarning.Text"));
	}
	
	private void makeSelectTreeOwnerForm() {

		HTMLNode listBoxContent = addContentBox(l10n().getString("KnownIdentitiesPage.SelectTreeOwner.Header"));
		HTMLNode selectForm = pr.addFormChild(listBoxContent, uri.toString(), "ViewTree");
		selectForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "page", "ViewTree" });
		HTMLNode selectBox = selectForm.addChild("select", "name", "OwnerID");

		synchronized(wot) {
			for(OwnIdentity ownIdentity : wot.getAllOwnIdentities())
				selectBox.addChild("option", "value", ownIdentity.getID(), ownIdentity.getNickname());
		}

		selectForm.addChild(
		        "input", 
		        new String[] { "type", "name", "value" }, 
		        new String[] { "submit", "select", l10n().getString("KnownIdentitiesPage.SelectTreeOwner.ViewOwnersTreeButton") });
	}
	
	/**
	 * Get a xHTML color (#abcdef) corresponding to a trust level.
	 * Red : -100 or below
	 * Green : 100 or above
	 * Yellow : 0
	 * And all the intermediate colors between.
	 *
	 * @param trustLevel Level of trust
	 * @return Color (format #abcdef)
	 */
	public static String getTrustColor(int trustLevel) {
		final int r;
		final int g;

		if(trustLevel < 0) {
			r = 0xff;
			g = Math.max(0xff + (int)((trustLevel)*2.55f), 0x00);
		} else {
			g = 0xff;
			r = Math.max(0xff - (int)((trustLevel)*2.55f), 0x00);
		}

		String sR = Integer.toHexString(r);
		if(sR.length() == 1) {
			sR = "0" + sR;
		}
		String sG = Integer.toHexString(g);
		if(sG.length() == 1) {
			sG = "0" + sG;
		}

		return "#" + sR + sG + "00";
	}
	
	/**
	 * Makes the list of Identities known by the tree owner.
	 * 
	 * @param db a reference to the database 
	 * @param _pr a reference to the {@link PluginRespirator}
	 * @param treeOwner owner of the trust tree we want to display 
	 */
	private void makeKnownIdentitiesList(OwnIdentity treeOwner) throws DuplicateScoreException, DuplicateTrustException {

		String nickFilter = request.getPartAsStringFailsafe("nickfilter", 100).trim();
		String sortBy = request.isPartSet("sortby") ? request.getPartAsStringFailsafe("sortby", 100).trim() : "Nickname";
		String sortType = request.isPartSet("sorttype") ? request.getPartAsStringFailsafe("sorttype", 100).trim() : "Ascending";
		
		
		HTMLNode knownIdentitiesBox = addContentBox(l10n().getString("KnownIdentitiesPage.KnownIdentities.Header"));
		knownIdentitiesBox = pr.addFormChild(knownIdentitiesBox, uri.toString(), "Filters").addChild("p");

		
		
		InfoboxNode filtersBoxNode = getContentBox(l10n().getString("KnownIdentitiesPage.FiltersAndSorting.Header"));
		
		{ // Filters box
		knownIdentitiesBox.addChild(filtersBoxNode.outer);
		HTMLNode filtersBox = filtersBoxNode.content;
		filtersBox.addChild("#", l10n().getString("KnownIdentitiesPage.FiltersAndSorting.ShowOnlyNicksContaining") + " : ");
		filtersBox.addChild("input", new String[] {"type", "size", "name", "value"}, new String[]{"text", "15", "nickfilter", nickFilter});
		
		filtersBox.addChild("#", " " + l10n().getString("KnownIdentitiesPage.FiltersAndSorting.SortIdentitiesBy") + " : ");
		HTMLNode option = filtersBox.addChild("select", new String[]{"name", "id"}, new String[]{"sortby", "sortby"});
		TreeMap<String, String> options = new TreeMap<String, String>();
		options.put(SortBy.Nickname.toString(), l10n().getString("KnownIdentitiesPage.FiltersAndSorting.SortIdentitiesBy.Nickname"));
		options.put(SortBy.Score.toString(), l10n().getString("KnownIdentitiesPage.FiltersAndSorting.SortIdentitiesBy.Score"));
		options.put(SortBy.LocalTrust.toString(), l10n().getString("KnownIdentitiesPage.FiltersAndSorting.SortIdentitiesBy.LocalTrust"));
		for(String e : options.keySet()) {
			HTMLNode newOption = option.addChild("option", "value", e, options.get(e));
			if(e.equals(sortBy)) {
				newOption.addAttribute("selected", "selected");
			}
		}

		option =  filtersBox.addChild("select", new String[]{"name", "id"}, new String[]{"sorttype", "sorttype"});
		options = new TreeMap<String, String>();
		options.put("Ascending", l10n().getString("KnownIdentitiesPage.FiltersAndSorting.SortIdentitiesBy.Ascending"));
		options.put("Descending", l10n().getString("KnownIdentitiesPage.FiltersAndSorting.SortIdentitiesBy.Descending"));
		for(String e : options.keySet()) {
			HTMLNode newOption = option.addChild("option", "value", e, options.get(e));
			if(e.equals(sortType)) {
				newOption.addAttribute("selected", "selected");
			}
		}

		filtersBox.addChild("input", new String[]{"type", "value"}, new String[]{"submit", l10n().getString("KnownIdentitiesPage.FiltersAndSorting.SortIdentitiesBy.SubmitButton")});
		}

		// Display the list of known identities
		HTMLNode identitiesTable = knownIdentitiesBox.addChild("table", "border", "0");
		identitiesTable.addChild(getKnownIdentitiesListTableHeader());
		
		WebOfTrust.SortOrder sortInstruction = WebOfTrust.SortOrder.valueOf("By" + sortBy + sortType);
		
		long currentTime = CurrentTimeUTC.getInMillis();
		
		long editionSum = 0;

		synchronized(wot) {
		for(Identity id : wot.getAllIdentitiesFilteredAndSorted(treeOwner, nickFilter, sortInstruction)) {
			if(id == treeOwner) continue;

			HTMLNode row=identitiesTable.addChild("tr");
			
			// NickName
			HTMLNode nameLink = row.addChild("td", new String[] {"title", "style"}, new String[] {id.getRequestURI().toString(), "cursor: help;"})
				.addChild("a", "href", identitiesPageURI+"?id=" + id.getID());
			
			String nickName = id.getNickname();
			
			if(nickName != null) {
				nameLink.addChild("#", nickName + "@" + id.getID().substring(0, 5) + "...");
			}
			else
				nameLink.addChild("span", "class", "alert-error").addChild("#", l10n().getString("KnownIdentitiesPage.KnownIdentities.Table.NicknameNotDownloadedYet"));
			
			// Added date
			row.addChild("td", CommonWebUtils.formatTimeDelta(currentTime - id.getAddedDate().getTime(), l10n()));
			
			// Last fetched date
			Date lastFetched = id.getLastFetchedDate();
			if(!lastFetched.equals(new Date(0)))
				row.addChild("td", CommonWebUtils.formatTimeDelta(currentTime - lastFetched.getTime(), l10n()));
			else
				row.addChild("td", l10n().getString("Common.Never"));
			
			// Publish TrustList
			row.addChild("td", new String[] { "align" }, new String[] { "center" } , id.doesPublishTrustList() ? l10n().getString("Common.Yes") : l10n().getString("Common.No"));
			
			//Score
			try {
				final Score score = wot.getScore((OwnIdentity)treeOwner, id);
				final int scoreValue = score.getScore();
				final int rank = score.getRank();
				
				row.addChild("td", new String[] { "align", "style" }, new String[] { "center", "background-color:" + KnownIdentitiesPage.getTrustColor(scoreValue) + ";" } ,
						Integer.toString(scoreValue) +" ("+
						(rank != Integer.MAX_VALUE ?  rank : l10n().getString("KnownIdentitiesPage.KnownIdentities.Table.InfiniteRank"))
						+")");
			}
			catch (NotInTrustTreeException e) {
				// This only happen with identities added manually by the user
				row.addChild("td", l10n().getString("KnownIdentitiesPage.KnownIdentities.Table.NoScore"));	
			}
			
			// Own Trust
			row.addChild(getReceivedTrustCell(treeOwner, id));
			
			// Checkbox
			row.addChild(getSetTrustCell(id));
			
			// Nb Trusters
			HTMLNode trustersCell = row.addChild("td", new String[] { "align" }, new String[] { "center" });
			trustersCell.addChild(new HTMLNode("a", "href", identitiesPageURI + "?id="+id.getID(),
					Long.toString(wot.getReceivedTrusts(id).size())));
			
			// Nb Trustees
			HTMLNode trusteesCell = row.addChild("td", new String[] { "align" }, new String[] { "center" });
			trusteesCell.addChild(new HTMLNode("a", "href", identitiesPageURI + "?id="+id.getID(),
					Long.toString(wot.getGivenTrusts(id).size())));
			
			// TODO: Show in advanced mode only once someone finally fixes the "Switch to advanced mode" link on FProxy to work on ALL pages.
			
			final long edition = id.getEdition();
			editionSum += edition;
			row.addChild("td", "align", "center", Long.toString(edition));
			
			row.addChild("td", "align", "center", Long.toString(id.getLatestEditionHint()));
		}
		}
		
		identitiesTable.addChild(getKnownIdentitiesListTableHeader());
		
		knownIdentitiesBox.addChild("#", l10n().getString("KnownIdentitiesPage.KnownIdentities.FetchProgress", "editionCount", Long.toString(editionSum)));
	}
	
	private HTMLNode getKnownIdentitiesListTableHeader() {
		HTMLNode row = new HTMLNode("tr");
		row.addChild("th", l10n().getString("KnownIdentitiesPage.KnownIdentities.TableHeader.Nickname"));
		row.addChild("th", l10n().getString("KnownIdentitiesPage.KnownIdentities.TableHeader.Added"));
		row.addChild("th", l10n().getString("KnownIdentitiesPage.KnownIdentities.TableHeader.Fetched"));
		row.addChild("th", l10n().getString("KnownIdentitiesPage.KnownIdentities.TableHeader.PublishesTrustlist"));
		row.addChild("th", l10n().getString("KnownIdentitiesPage.KnownIdentities.TableHeader.ScoreAndRank"));
		row.addChild("th", l10n().getString("KnownIdentitiesPage.KnownIdentities.TableHeader.TrustAndComment"));
		row.addChild("th").addChild(new HTMLNode("input", new String[] { "type", "name", "value" }, new String[] { "submit", "SetTrust", l10n().getString("KnownIdentitiesPage.KnownIdentities.Table.UpdateTrustButton") }));
		row.addChild("th", l10n().getString("KnownIdentitiesPage.KnownIdentities.TableHeader.Trusters"));
		row.addChild("th", l10n().getString("KnownIdentitiesPage.KnownIdentities.TableHeader.Trustees"));
		row.addChild("th", l10n().getString("KnownIdentitiesPage.KnownIdentities.TableHeader.Edition"));
		row.addChild("th", l10n().getString("KnownIdentitiesPage.KnownIdentities.TableHeader.EditionHint"));
		
		return row;
	}
	
	private HTMLNode getReceivedTrustCell (OwnIdentity truster, Identity trustee) throws DuplicateTrustException {

		String trustValue = "";
		String trustComment = "";
		Trust trust;
		
		try {
			trust = wot.getTrust(truster, trustee);
			trustValue = String.valueOf(trust.getValue());
			trustComment = trust.getComment();
		}
		catch (NotTrustedException e) {
		} 
			
		HTMLNode cell = new HTMLNode("td");
		if(trustValue.length()>0) {
			cell.addAttribute("style", "background-color:" + KnownIdentitiesPage.getTrustColor(Integer.parseInt(trustValue)) + ";");
		}

		
		// Trust value input field
		cell.addChild("input", new String[] { "type", "name", "size", "maxlength", "value" }, 
				new String[] { "text", "Value" + trustee.getID(), "4", "4", trustValue });
		
		// Trust comment input field
		cell.addChild("input", new String[] { "type", "name", "size", "maxlength", "value" }, 
				new String[] { "text", "Comment" + trustee.getID(), "50", Integer.toString(Trust.MAX_TRUST_COMMENT_LENGTH), trustComment });

		return cell;
	}
	
	private HTMLNode getSetTrustCell(Identity trustee) {
		HTMLNode cell = new HTMLNode("td");
		// There can be multiple checkboxes with the same name in HTML, however Fred does not support it...
		cell.addChild(new HTMLNode("input", new String[] { "type", "name", "value" }, new String[] { "checkbox", "SetTrustOf" + trustee.getID(), trustee.getID()}));
		return cell;
	}
}
