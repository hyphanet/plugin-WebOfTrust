package plugins.WoT.ui.web;

import java.util.List;
import java.util.UUID;

import com.db4o.ObjectContainer;

import plugins.WoT.OwnIdentity;
import plugins.WoT.WoT;
import plugins.WoT.introduction.IntroductionClient;
import plugins.WoT.introduction.IntroductionPuzzle;
import plugins.WoT.introduction.IntroductionPuzzle.PuzzleType;
import freenet.pluginmanager.PluginRespirator;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.api.HTTPRequest;

public class IntroduceIdentityPage extends WebPageImpl {
	
	protected static int PUZZLE_DISPLAY_COUNT = 512; /* FIXME: set to a reasonable value before release */
	protected IntroductionClient mClient;
	protected OwnIdentity mIdentity;
	protected List<IntroductionPuzzle> mPuzzles;

	public IntroduceIdentityPage(WoT wot, HTTPRequest request, IntroductionClient myClient, OwnIdentity myIdentity) {
		super(wot, request);
		mIdentity = myIdentity; 
		mClient = myClient;
		mPuzzles = mClient.getPuzzles(PuzzleType.Captcha, mIdentity, PUZZLE_DISPLAY_COUNT);
		
		if(request.isParameterSet("solvePuzzles")) {
			ObjectContainer db = wot.getDB();
			String ids[] = request.getMultipleParam("id");
			for(String id : ids) {
				String solution = request.getParam("solution" + id);
				if(!solution.equals("")) {
					IntroductionPuzzle p = IntroductionPuzzle.getByID(db, UUID.fromString(id));
					if(p != null) {
						try {
							myClient.insertPuzzleSolution(p, solution, mIdentity);
						}
						catch(Exception e) {
							Logger.error(this, "insertPuzzleSolution() failed");
						}
					}
				}
			}
		}
	}

	public void make() {
		PluginRespirator pr = wot.getPR();
		makeInfoBox(pr);
		makePuzzleBox(pr);
	}

	private void makeInfoBox(PluginRespirator pr) {
		HTMLNode boxContent = getContentBox("Introduce identity");
		boxContent.addChild("p", "Solve about 10 puzzles to get your identity known by other identities. DO NOT continously solve puzzles."); /* FIXME: add more information */
	}
	
	private void makePuzzleBox(PluginRespirator pr) {
		HTMLNode boxContent = getContentBox(null);
		HTMLNode solveForm = pr.addFormChild(boxContent, SELF_URI, "solvePuzzles");
		solveForm.addAttribute("identity", mIdentity.getId());
		
		HTMLNode puzzleTable = solveForm.addChild("table", "border", "0");
		HTMLNode row = puzzleTable.addChild("tr");
		
		int counter = 0;

		for(IntroductionPuzzle p : mPuzzles) {
			solveForm.addAttribute("id", p.getID().toString());
			
			if(counter++ % 8 == 0)
				row = puzzleTable.addChild("tr");
			
			HTMLNode cell = row.addChild("td");
			cell.addChild("img", new String[] {"src"}, new String[] {SELF_URI + "/puzzle?id=" + p.getID()});
			cell.addChild("input", new String[] { "type", "name", "size"}, new String[] { "text", "solution" + p.getID(), "10" });
		}
	}

}
