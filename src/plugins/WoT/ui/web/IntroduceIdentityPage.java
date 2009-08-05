package plugins.WoT.ui.web;

import java.util.List;

import plugins.WoT.OwnIdentity;
import plugins.WoT.exceptions.UnknownIdentityException;
import plugins.WoT.exceptions.UnknownPuzzleException;
import plugins.WoT.introduction.IntroductionClient;
import plugins.WoT.introduction.IntroductionPuzzle;
import plugins.WoT.introduction.IntroductionPuzzle.PuzzleType;
import freenet.clients.http.ToadletContext;
import freenet.pluginmanager.PluginRespirator;
import freenet.support.Base64;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.api.HTTPRequest;

public class IntroduceIdentityPage extends WebPageImpl {
	
	protected static int PUZZLE_DISPLAY_COUNT = 16;
	
	protected final IntroductionClient mClient;
	protected final OwnIdentity mIdentity;

	/**
	 *
	 * 
	 * @param myWebInterface A reference to the WebInterface which created the page, used to get resources the page needs. 
	 * @param myRequest The request sent by the user.
	 * @throws UnknownIdentityException 
	 */
	public IntroduceIdentityPage(WebInterfaceToadlet toadlet, HTTPRequest myRequest, ToadletContext context) throws UnknownIdentityException {
		super(toadlet, myRequest, context);
		
		mIdentity = wot.getOwnIdentityByID(request.getPartAsString("id", 128));
		mClient = wot.getIntroductionClient();
		/* TODO: Can we risk that the identity is being deleted? I don't think we should lock the whole wot */
		
		if(request.isPartSet("Solve")) {
			int idx = 0;
			while(request.isPartSet("id" + idx)) {
				String id = request.getPartAsString("id" + idx, 128);
				String solution = request.getPartAsString("Solution" + id, 10); /* FIXME: replace "10" with the maximal solution length */
				if(!solution.equals("")) {
					IntroductionPuzzle p;
					try {
						p = wot.getIntroductionPuzzleStore().getByID(id);

						try {
							mClient.solvePuzzle(mIdentity, p, solution);
						}
						catch(Exception e) {
							/* The identity or the puzzle might have been deleted here */
							Logger.error(this, "insertPuzzleSolution() failed");
						}
					} catch (UnknownPuzzleException e1) {

					}
				}
				++idx;
			}
		}
	}

	public void make() {
		PluginRespirator pr = wot.getPluginRespirator();
		makeInfoBox(pr);
		makePuzzleBox(pr);
	}

	private void makeInfoBox(PluginRespirator pr) {
		HTMLNode boxContent = addContentBox("Introduce identity '" + mIdentity.getNickname() + "'");
		boxContent.addChild("p", "Solve about 10 puzzles to get your identity known by other identities. DO NOT continously solve puzzles."); /* TODO: add more information */
	}
	
	private void makePuzzleBox(PluginRespirator pr) {
		HTMLNode boxContent = addContentBox("Puzzles");
		
		// synchronized(mClient) { /* The client returns an ArrayList, not the ObjectContainer, so this should be safe */
		List<IntroductionPuzzle> puzzles = mClient.getPuzzles(mIdentity, PuzzleType.Captcha, PUZZLE_DISPLAY_COUNT);
		
		if(puzzles.size() > 0 ) {
			HTMLNode solveForm = pr.addFormChild(boxContent, uri, "solvePuzzles");
			solveForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "page", "SolvePuzzles" });
			solveForm.addChild("input", new String[] { "type", "name", "value", }, new String[] { "hidden", "id", mIdentity.getID() });
			
			int counter = 0;
			for(IntroductionPuzzle p : puzzles) {
				// Display as much puzzles per row as fitting in the browser-window via "inline-block" style. Nice, eh?
				HTMLNode cell = solveForm.addChild("div", new String[] { "align" , "style"}, new String[] { "center" , "display: inline-block"});
				/* FIXME: use SELF_URI + "puzzle?id=" instead 
				 * That page should then use wot.getIntroductionPuzzleStore().getByID(id); for obtaining the puzzle */
				cell.addChild("input", new String[] { "type", "name", "value", }, new String[] { "hidden", "id" + counter, p.getID() });
				cell.addChild("img", new String[] {"src"}, new String[] {"data:image/jpeg;base64," + Base64.encodeStandard(p.getData())}); 
				cell.addChild("br");
				cell.addChild("input", new String[] { "type", "name", "size"}, new String[] { "text", "Solution" + p.getID(), "10" });
				
				++counter;
			}
			
			solveForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "Solve", "Submit" });
		} else {
			boxContent.addChild("p", "No puzzles were downloaded yet, sorry. Please give the WoT plugin some time to retrieve puzzles.");
		}
		//}
	}

}
