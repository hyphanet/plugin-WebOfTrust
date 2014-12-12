/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.ui.web;

import java.net.URI;
import java.util.List;

import plugins.WebOfTrust.OwnIdentity;
import plugins.WebOfTrust.exceptions.UnknownIdentityException;
import plugins.WebOfTrust.introduction.IntroductionClient;
import plugins.WebOfTrust.introduction.IntroductionPuzzle;
import plugins.WebOfTrust.introduction.IntroductionPuzzle.PuzzleType;
import plugins.WebOfTrust.ui.web.WebInterface.GetPuzzleWebInterfaceToadlet;
import freenet.clients.http.RedirectException;
import freenet.clients.http.SessionManager.Session;
import freenet.clients.http.ToadletContext;
import freenet.support.HTMLNode;
import freenet.support.api.HTTPRequest;

public class IntroduceIdentityPage extends WebPageImpl {
	
	protected static int PUZZLE_DISPLAY_COUNT = 16;
	
	protected final IntroductionClient mClient;
	protected final OwnIdentity mLoggedInOwnIdentity;

	/**
	 * @param toadlet A reference to the {@link WebInterfaceToadlet} which created the page, used to get resources the page needs.
	 * @param myRequest The request sent by the user.
	 * @throws UnknownIdentityException 
	 * @throws RedirectException If the {@link Session} has expired. 
	 */
	public IntroduceIdentityPage(WebInterfaceToadlet toadlet, HTTPRequest myRequest, ToadletContext context) throws UnknownIdentityException, RedirectException {
		super(toadlet, myRequest, context, true);
		
		mLoggedInOwnIdentity = mWebOfTrust.getOwnIdentityByID(mLoggedInOwnIdentityID);
		mClient = mWebOfTrust.getIntroductionClient();
	}

	@Override
	public void make(final boolean mayWrite) {
		parseSolutions(mayWrite);
		makeInfoBox();
		makePuzzleBox();
	}
	
	private void parseSolutions(final boolean mayWrite) {
		if(!mayWrite || !mRequest.isPartSet("Solve"))
			return;
		
		int idx = 0;
		while(mRequest.isPartSet("id" + idx)) {
			try {
				String id = mRequest.getPartAsStringThrowing("id" + idx, IntroductionPuzzle.MAXIMAL_ID_LENGTH);
				String solution = mRequest.getPartAsStringThrowing("Solution" + id, IntroductionPuzzle.MAXIMAL_SOLUTION_LENGTH);
				if(!solution.trim().equals("")) {
					mClient.solvePuzzle(mLoggedInOwnIdentityID, id, solution);
				} else {
					// We don't break the processing loop here because users might intentionally not solve puzzles which are too difficult.
				}
				++idx;

			} catch(Exception e) {
				new ErrorPage(mToadlet, mRequest, mContext, e).addToPage(this);
			}
		}
	}

	private void makeInfoBox() {
		HTMLNode boxContent = addContentBox(l10n().getString("IntroduceIdentityPage.InfoBox.Header", "nickname", mLoggedInOwnIdentity.getNickname()));
		boxContent.addChild("p", l10n().getString("IntroduceIdentityPage.InfoBox.Text")); /* TODO: add more information */
	}
	
	private void makePuzzleBox() {
		HTMLNode boxContent = addContentBox(l10n().getString("IntroduceIdentityPage.PuzzleBox.Header"));
		
		// synchronized(mClient) { /* The client returns an ArrayList, not the ObjectContainer, so this should be safe */
		List<IntroductionPuzzle> puzzles = mClient.getPuzzles(mLoggedInOwnIdentity, PuzzleType.Captcha, PUZZLE_DISPLAY_COUNT);
		
		if(puzzles.size() > 0 ) {
			HTMLNode solveForm = pr.addFormChild(boxContent, uri.toString(), "solvePuzzles");
			
			int counter = 0;
			for(IntroductionPuzzle p : puzzles) {
				// Display as much puzzles per row as fitting in the browser-window via "inline-block" style. Nice, eh?
				HTMLNode cell = solveForm.addChild("div", new String[] { "align" , "style"}, new String[] { "center" , "display: inline-block"});
				cell.addChild("input", new String[] { "type", "name", "value", }, new String[] { "hidden", "id" + counter, p.getID() });
				cell.addChild("img", "src", getPuzzleImageURI(p.getID()).toString() );
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

	private URI getPuzzleImageURI(String puzzleID) {
		return ((GetPuzzleWebInterfaceToadlet)mWebInterface.getToadlet(GetPuzzleWebInterfaceToadlet.class))
		         .getURI(puzzleID);
	}

}
