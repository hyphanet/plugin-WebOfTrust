/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.ui.web;

import java.util.Date;
import java.util.Iterator;
import java.util.TreeMap;

import plugins.WebOfTrust.Identity;
import plugins.WebOfTrust.Identity.IdentityID;
import plugins.WebOfTrust.OwnIdentity;
import plugins.WebOfTrust.Score;
import plugins.WebOfTrust.Trust;
import plugins.WebOfTrust.WebOfTrust;
import plugins.WebOfTrust.exceptions.DuplicateTrustException;
import plugins.WebOfTrust.exceptions.InvalidParameterException;
import plugins.WebOfTrust.exceptions.NotInTrustTreeException;
import plugins.WebOfTrust.exceptions.NotTrustedException;
import plugins.WebOfTrust.exceptions.UnknownIdentityException;

import com.db4o.ObjectSet;

import freenet.clients.http.InfoboxNode;
import freenet.clients.http.RedirectException;
import freenet.clients.http.SessionManager.Session;
import freenet.clients.http.ToadletContext;
import freenet.keys.FreenetURI;
import freenet.support.CurrentTimeUTC;
import freenet.support.HTMLNode;
import freenet.support.api.HTTPRequest;


/**
 * The page where users can manage others identities.
 * 
 * @author xor (xor@freenetproject.org)
 * @author Julien Cornuwel (batosai@freenetproject.org)
 */
public class KnownIdentitiesPage extends WebPageImpl {
	
	public static final int IDENTITIES_PER_PAGE = 15;

	private final OwnIdentity mLoggedInOwnIdentity;
	
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
		
        // TODO: Performance: The synchronized() and the clone() here and the getOwnIdentityByID()
		// in makeKnownIdentitiesList() can be removed after this is fixed:
        // https://bugs.freenetproject.org/view.php?id=6247
		synchronized(mWebOfTrust) {
		    mLoggedInOwnIdentity = mWebOfTrust.getOwnIdentityByID(mLoggedInOwnIdentityID).clone();
        }
	}

	@Override
	public void make(final boolean mayWrite) {
		if(mLoggedInOwnIdentity.isRestoreInProgress()) {
			makeRestoreInProgressWarning();
			return;
		}
		
		final boolean addIdentity = mRequest.isPartSet("AddIdentity");
		
		if(mayWrite && addIdentity) {
			try {
				mWebOfTrust.addIdentity(mRequest.getPartAsStringFailsafe("IdentityURI", 1024));
				HTMLNode successBox = addContentBox(l10n().getString("KnownIdentitiesPage.AddIdentity.Success.Header"));
				successBox.addChild("#", l10n().getString("KnownIdentitiesPage.AddIdentity.Success.Text"));
			}
			catch(Exception e) {
				addErrorBox(l10n().getString("KnownIdentitiesPage.AddIdentity.Failed"), e);
			}
		}
		
		if(mayWrite && mRequest.isPartSet("SetTrust")) {
			for(String part : mRequest.getParts()) {
				if(!part.startsWith("SetTrustOf"))
					continue;

				final String trusteeID;
				final String value;
				final String comment;

				try { 
					if(addIdentity) { // Add a single identity and set its trust value
						trusteeID = IdentityID.constructAndValidateFromURI(new FreenetURI(mRequest.getPartAsStringFailsafe("IdentityURI", 1024))).toString();
						value = mRequest.getPartAsStringFailsafe("Value", 4).trim();
						comment = mRequest.getPartAsStringFailsafe("Comment", Trust.MAX_TRUST_COMMENT_LENGTH + 1);				 	
					} else { // Change multiple trust values via the known-identities-list
						trusteeID = mRequest.getPartAsStringFailsafe(part, 128);
						value = mRequest.getPartAsStringFailsafe("Value" + trusteeID, 4).trim();
						comment = mRequest.getPartAsStringFailsafe("Comment" + trusteeID, Trust.MAX_TRUST_COMMENT_LENGTH + 1);
					}

					if(value.equals(""))
						mWebOfTrust.removeTrust(mLoggedInOwnIdentityID, trusteeID);
					else
						mWebOfTrust.setTrust(mLoggedInOwnIdentityID, trusteeID, Byte.parseByte(value), comment);
					
					if(addIdentity && (value.equals("") || Byte.parseByte(value) < 0)) {
						addErrorBox(l10n().getString("KnownIdentitiesPage.AddIdentity.NoTrustWarning.Header"), 
								l10n().getString("KnownIdentitiesPage.AddIdentity.NoTrustWarning.Text"));
					}
				} catch(NumberFormatException e) {
					addErrorBox(l10n().getString("KnownIdentitiesPage.SetTrust.Failed"), l10n().getString("Trust.InvalidValue"));
				} catch(InvalidParameterException e) {
					addErrorBox(l10n().getString("KnownIdentitiesPage.SetTrust.Failed"), e.getMessage());
				} catch(Exception e) {
					addErrorBox(l10n().getString("KnownIdentitiesPage.SetTrust.Failed"), e);
				}
			}
		}

		
		makeKnownIdentitiesList();
		makeAddIdentityForm(); // Put this after makeKnownIdentitiesList() so clicking through pages of the known identities list doesn't involve scrolling.
	}
	
	/**
	 * Makes a form where the user can enter the requestURI of an Identity he knows.
	 */
	private void makeAddIdentityForm() {
		
		// TODO Add trust value and comment fields and make them mandatory
		// The user should only add an identity he trusts
		HTMLNode addBoxContent = addContentBox(l10n().getString("KnownIdentitiesPage.AddIdentity.Header"));
	
		HTMLNode createForm = pr.addFormChild(addBoxContent, uri.toString(), "AddIdentity");

		createForm.addChild("span", new String[] {"title", "style"}, 
				new String[] { 
		            l10n().getString("KnownIdentitiesPage.AddIdentity.IdentityURI.Tooltip"), 
		            "border-bottom: 1px dotted; cursor: help;"} , 
		            l10n().getString("KnownIdentitiesPage.AddIdentity.IdentityURI") + ": ");
		
		createForm.addChild("input", new String[] {"type", "name", "size"}, new String[] {"text", "IdentityURI", "70"});
		createForm.addChild("br");
		
		
		createForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "SetTrust", "true"});
		createForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "SetTrustOf", "void"});

		createForm.addChild("span", l10n().getString("KnownIdentitiesPage.AddIdentity.Trust") + ": ")
		.addChild("input", new String[] { "type", "name", "size", "value" }, new String[] { "text", "Value", "4", "" });

		createForm.addChild("span", " " + l10n().getString("KnownIdentitiesPage.AddIdentity.Comment") + ": ")
		.addChild("input", new String[] { "type", "name", "size", "value" }, new String[] { "text", "Comment", "20", "" });

		createForm.addChild("br");
		
		
		createForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "AddIdentity", l10n().getString("KnownIdentitiesPage.AddIdentity.AddButton") });
	}
	
	private void makeRestoreInProgressWarning() {
		addErrorBox(l10n().getString("KnownIdentitiesPage.RestoreInProgressWarning.Header"), l10n().getString("KnownIdentitiesPage.RestoreInProgressWarning.Text"));
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
	 */
	private void makeKnownIdentitiesList() {

		String nickFilter = mRequest.getPartAsStringFailsafe("nickfilter", 100).trim();
		String sortBy = mRequest.isPartSet("sortby") ? mRequest.getPartAsStringFailsafe("sortby", 100).trim() : "Nickname";
		String sortType = mRequest.isPartSet("sorttype") ? mRequest.getPartAsStringFailsafe("sorttype", 100).trim() : "Ascending";
		
		int page = mRequest.isPartSet("page") ? Integer.parseInt(mRequest.getPartAsStringFailsafe("page", Integer.toString(Integer.MAX_VALUE).length())) : 0;
		page = page - 1; // What we get passed is the user-friendly page number counting from 1, not 0.
		page = Math.max(0, page); // In case no page part was set, it would be -1
		
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
		
		
		synchronized(mWebOfTrust) {
		
		final int indexOfFirstIdentity = page * IDENTITIES_PER_PAGE;
		
		// Re-query it instead of using mLoggedInOwnIdentity because mLoggedInOwnIdentity is a
		// clone() and thus will not work with database queries on the WebOfTrust.
		// See the constructor for why we clone() it.
		final OwnIdentity ownId;
		try {
		    ownId = mWebOfTrust.getOwnIdentityByID(mLoggedInOwnIdentityID);
		} catch(UnknownIdentityException e) {
		    new ErrorPage(mToadlet, mRequest, mContext, e).addToPage(this);
		    return;
		}
		
		final ObjectSet<Identity> allIdentities
		    = mWebOfTrust.getAllIdentitiesFilteredAndSorted(ownId, nickFilter, sortInstruction);
		final Iterator<Identity> identities = allIdentities.listIterator(indexOfFirstIdentity);
		
		for(int displayed = 0; displayed < IDENTITIES_PER_PAGE && identities.hasNext(); ++displayed) {
			final Identity id = identities.next();
			
			if(id == ownId) continue;

			HTMLNode row=identitiesTable.addChild("tr");
			
			// NickName
			HTMLNode nameLink = row.addChild("td", new String[] {"title", "style"}, new String[] {id.getRequestURI().toString(), "cursor: help;"})
				.addChild("a", "href", IdentityPage.getURI(mWebInterface, id.getID()).toString());
			
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
				final Score score = mWebOfTrust.getScore(ownId, id);
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
			row.addChild(getReceivedTrustCell(ownId, id));
			
			// Checkbox
			row.addChild(getSetTrustCell(id));
			
			// Nb Trusters
			// TODO: Do a direct link to the received-trusts part of the linked page
			HTMLNode trustersCell = row.addChild("td", new String[] { "align" }, new String[] { "center" });
			trustersCell.addChild(new HTMLNode("a", "href", IdentityPage.getURI(mWebInterface, id.getID()).toString(),
					Long.toString(mWebOfTrust.getReceivedTrusts(id).size())));
			
			// Nb Trustees
			// TODO: Do a direct link to the given-trusts part of the linked page
			HTMLNode trusteesCell = row.addChild("td", new String[] { "align" }, new String[] { "center" });
			trusteesCell.addChild(new HTMLNode("a", "href", IdentityPage.getURI(mWebInterface, id.getID()).toString(),
					Long.toString(mWebOfTrust.getGivenTrusts(id).size())));
			
			// TODO: Show in advanced mode only once someone finally fixes the "Switch to advanced mode" link on FProxy to work on ALL pages.
			
			row.addChild("td", "align", "center", Long.toString(id.getEdition()));
			
			row.addChild("td", "align", "center", Long.toString(id.getLatestEditionHint()));
		
		identitiesTable.addChild(getKnownIdentitiesListTableHeader());
		knownIdentitiesBox.addChild(getKnownIdentitiesListPageLinks(page, allIdentities.size()));
	    }
        }
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
	
	/**
	 * Gets a list of links to the pages of the known identities list. Will look like this:
	 * 1 to 5... currentPage-5 to currentPage+5 ... lastPage-5 to lastPage
	 * 
	 * @param currentPage The currently displayed page, counting from 0.
	 * @param identityCount Total amount of identities in the result set. This is used to compute the page count. TODO: Optimization: When using lazy database
	 *                      query evaluation, the amount of identities will not be computed until we have processed the whole database query. So the 
	 *                      computation of this parameter is expensive then and we should get rid of it. The link to the last page should be "Last" instead of
	 *                      a numeric label then. Bugtracker entry: https://bugs.freenetproject.org/view.php?id=6245
	 */
	private HTMLNode getKnownIdentitiesListPageLinks(final int currentPage, final int identityCount) {
		final int pageCount = identityCount / IDENTITIES_PER_PAGE + ((identityCount % IDENTITIES_PER_PAGE > 0) ? 1 : 0);
		final int lastPage = pageCount-1;
		
		HTMLNode div = new HTMLNode("div");
		
		if(pageCount == 1)
			return div;
		
		int lastDisplayedLink = -1;
		
		// Display links to first 5 pages excluding the current page.
		for(int i = 0; i < currentPage; ) {
			div.addChild(getSingleKnownIdentitiesListPageLink(currentPage, i));
			lastDisplayedLink = i;
			
			++i; 
			
			if(i >= 5) { // Display 5 links at most
				if(i < currentPage-5) // The next loop displays links starting from currentPage-5. If our next link wouldn't fall into that range, add dots.
					div.addChild("#", "...");
				break;
			}
		}
		
		// Display 5 links before and after current page, excluding last page.
		for(int i = Math.max(currentPage-5, lastDisplayedLink+1); i < lastPage; ) {
			div.addChild(getSingleKnownIdentitiesListPageLink(currentPage, i));
			lastDisplayedLink = i;
			
			++i;
			
			if(i > currentPage+5) {
				if(i != lastPage) // If the next link would not have been the lastPage, add "..." for the missing pages in between
					div.addChild("#", "...");
				break;
			}
		}
		
		// Display last page
		if(lastDisplayedLink != lastPage)
			div.addChild(getSingleKnownIdentitiesListPageLink(currentPage, lastPage));

		return div;
	}
	
	/**
	 * Get a single entry in the link list to the pages of the known identities list.
	 * 
	 * TODO: This currently returns a button, not an actual link. We need a button instead of a link because it must submit the "Filters" form. Use CSS
	 *       or Javascript to make it look like a link to follow the style convention of having the page list being links, not buttons.
	 * 
	 * @param currentPage The currently displayed page, counting from 0. Used to decide whether the link should really be a link or just a bold number,
	 *                    which indicates which page the user is on
	 * @param desiredPage The page to which the link shall point.
	 */
	private HTMLNode getSingleKnownIdentitiesListPageLink(final int currentPage, int desiredPage) {
		final String desiredPageString = Integer.toString(desiredPage + 1);
		
		if(currentPage != desiredPage)
			return new HTMLNode("input", new String[]{ "type", "name", "value" },
			                             new String[]{ "submit", "page", desiredPageString });
		else
			return new HTMLNode("b", desiredPageString);
	}
	
	private HTMLNode getReceivedTrustCell (OwnIdentity truster, Identity trustee) throws DuplicateTrustException {

		String trustValue = "";
		String trustComment = "";
		Trust trust;
		
		try {
			trust = mWebOfTrust.getTrust(truster, trustee);
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
