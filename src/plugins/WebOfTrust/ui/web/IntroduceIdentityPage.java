/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.ui.web;

import java.util.List;

import plugins.WebOfTrust.OwnIdentity;
import plugins.WebOfTrust.exceptions.UnknownIdentityException;
import plugins.WebOfTrust.exceptions.UnknownPuzzleException;
import plugins.WebOfTrust.introduction.IntroductionClient;
import plugins.WebOfTrust.introduction.IntroductionPuzzle;
import plugins.WebOfTrust.introduction.IntroductionPuzzle.PuzzleType;
import freenet.clients.http.RedirectException;
import freenet.clients.http.SessionManager.Session;
import freenet.clients.http.ToadletContext;
import freenet.pluginmanager.PluginRespirator;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.api.HTTPRequest;

public class IntroduceIdentityPage extends WebPageImpl {
	
	protected static int PUZZLE_DISPLAY_COUNT = 16;
	
	protected final String mPuzzleURI;
	
	protected final IntroductionClient mClient;
	protected final OwnIdentity mIdentity;

	/**
	 * @param toadlet A reference to the {@link WebInterfaceToadlet} which created the page, used to get resources the page needs.
	 * @param myRequest The request sent by the user.
	 * @throws UnknownIdentityException 
	 * @throws RedirectException If the {@link Session} has expired. 
	 */
	public IntroduceIdentityPage(WebInterfaceToadlet toadlet, HTTPRequest myRequest, ToadletContext context) throws UnknownIdentityException, RedirectException {
		super(toadlet, myRequest, context, true);
		
		mPuzzleURI = toadlet.webInterface.getURI() + "/GetPuzzle";
		
		mIdentity = mWebOfTrust.getOwnIdentityByID(mLoggedInOwnIdentityID);
		mClient = mWebOfTrust.getIntroductionClient();
		
		if(mRequest.isPartSet("Solve")) {
			int idx = 0;
			while(mRequest.isPartSet("id" + idx)) {
				String id = mRequest.getPartAsString("id" + idx, 128);
				String solution = mRequest.getPartAsString("Solution" + id, IntroductionPuzzle.MAXIMAL_SOLUTION_LENGTH);
				if(!solution.trim().equals("")) {
					IntroductionPuzzle p;
					try {
						p = mWebOfTrust.getIntroductionPuzzleStore().getByID(id);

						try {
							// It is safe to use this function without synchronization as it re-queries the identity from the database.
							mClient.solvePuzzle(mIdentity, p, solution);
						}
						catch(Exception e) {
							/* The identity or the puzzle might have been deleted here */
							new ErrorPage(mToadlet, mRequest, mContext, e).addToPage(this);
						}
					} catch (UnknownPuzzleException e1) {
					}
				}
				++idx;
			}
		}
	}

	public void make() {
		PluginRespirator _pr = mWebOfTrust.getPluginRespirator();
		makeInfoBox(_pr);
		makePuzzleBox(_pr);
	}

	private void makeInfoBox(PluginRespirator _pr) {
		HTMLNode boxContent = addContentBox(l10n().getString("IntroduceIdentityPage.InfoBox.Header", "nickname", mIdentity.getNickname()));
		boxContent.addChild("p", l10n().getString("IntroduceIdentityPage.InfoBox.Text")); /* TODO: add more information */
	}
	
	private void makePuzzleBox(PluginRespirator _pr) {
		HTMLNode boxContent = addContentBox(l10n().getString("IntroduceIdentityPage.PuzzleBox.Header"));
		
		// synchronized(mClient) { /* The client returns an ArrayList, not the ObjectContainer, so this should be safe */
		List<IntroductionPuzzle> puzzles = mClient.getPuzzles(mIdentity, PuzzleType.Captcha, PUZZLE_DISPLAY_COUNT);
		
		if(puzzles.size() > 0 ) {
			HTMLNode solveForm = _pr.addFormChild(boxContent, uri.toString(), "solvePuzzles");
			
			int counter = 0;
			for(IntroductionPuzzle p : puzzles) {
				// Display as much puzzles per row as fitting in the browser-window via "inline-block" style. Nice, eh?
				HTMLNode cell = solveForm.addChild("div", new String[] { "align" , "style"}, new String[] { "center" , "display: inline-block"});
				cell.addChild("input", new String[] { "type", "name", "value", }, new String[] { "hidden", "id" + counter, p.getID() });
				cell.addChild("img", "src", mPuzzleURI + "?PuzzleID=" + p.getID() ); 
				cell.addChild("br");
				cell.addChild("input", new String[] { "type", "name", "size", "maxlength"},
						new String[] { "text", "Solution" + p.getID(), Integer.toString(IntroductionPuzzle.MINIMAL_SOLUTION_LENGTH+1),
																		Integer.toString(IntroductionPuzzle.MAXIMAL_SOLUTION_LENGTH)});
				
				++counter;
			}
			
			solveForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "Solve", l10n().getString("IntroduceIdentityPage.PuzzleBox.SubmitButton") });
		} else {
			boxContent.addChild("p", l10n().getString("IntroduceIdentityPage.PuzzleBox.NoPuzzlesDownloaded"));
		}
		//}
	}
}
