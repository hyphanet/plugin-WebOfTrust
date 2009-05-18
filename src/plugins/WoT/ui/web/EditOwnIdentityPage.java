/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WoT.ui.web;

import plugins.WoT.OwnIdentity;
import plugins.WoT.exceptions.UnknownIdentityException;
import plugins.WoT.introduction.IntroductionPuzzle;
import plugins.WoT.introduction.IntroductionServer;
import freenet.support.HTMLNode;
import freenet.support.api.HTTPRequest;


/**
 * The page where users can edit their own identities.
 * 
 * @author xor (xor@freenetproject.org)
 * @author Julien Cornuwel (batosai@freenetproject.org)
 */
public class EditOwnIdentityPage extends WebPageImpl {
	
	private final OwnIdentity mIdentity;

	public EditOwnIdentityPage(WebInterface myWebInterface, HTTPRequest myRequest) throws UnknownIdentityException {
		super(myWebInterface, myRequest);
		
		mIdentity = wot.getOwnIdentityByID(request.getPartAsString("id", 128));
	}
	
	public void make() {
		synchronized(wot) {
		synchronized(mIdentity) {
			if(request.isPartSet("Edit")) {
				try {
					mIdentity.setPublishTrustList(request.isPartSet("PublishTrustList") && 
						request.getPartAsString("PublishTrustList", 6).equals("true"));

					if(request.isPartSet("PublishPuzzles") && 
							request.getPartAsString("PublishPuzzles", 6).equals("true")) {
						
						mIdentity.addContext(IntroductionPuzzle.INTRODUCTION_CONTEXT);
						mIdentity.setProperty(IntroductionServer.PUZZLE_COUNT_PROPERTY, Integer.toString(IntroductionServer.DEFAULT_PUZZLE_COUNT));
					}
					else {
						mIdentity.removeContext(IntroductionPuzzle.INTRODUCTION_CONTEXT);
						mIdentity.removeProperty(IntroductionServer.PUZZLE_COUNT_PROPERTY);
					}
					
					wot.storeAndCommit(mIdentity); 
				}
				catch(Exception e) {
					addErrorBox("Saving the changes failed", e);
				}	
			}

			HTMLNode box = addContentBox("Edit identity '" + mIdentity.getNickname() + "'");
			
			HTMLNode createForm = pr.addFormChild(box, uri, "EditIdentity");
			createForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "id", mIdentity.getID()});
			createForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "page", "EditIdentity"});
			
			createForm.addChild("p", new String[] { "style" }, new String[] { "font-size: x-small" },
					"Request URI: " + mIdentity.getRequestURI().toString());
			
			createForm.addChild("p", new String[] { "style" }, new String[] { "font-size: x-small" },
					"Insert URI (KEEP THIS SECRET!): " + mIdentity.getInsertURI().toString());
			
			// TODO Give the user the ability to edit these.
			createForm.addChild("p", "Contexts: " + mIdentity.getContexts());
			createForm.addChild("p", "Properties: " + mIdentity.getProperties());
			
			HTMLNode p = createForm.addChild("p", "Publish trust list: ");
			if(mIdentity.doesPublishTrustList()) {
				p.addChild("input", new String[] { "type", "name", "value", "checked" },
						new String[] { "checkbox", "PublishTrustList", "true", "checked"});
			}
			else
				p.addChild("input", new String[] { "type", "name", "value" }, new String[] { "checkbox", "PublishTrustList", "true"});
			
			
			p = createForm.addChild("p", "Publish introduction puzzles: ");
			if(mIdentity.hasContext(IntroductionPuzzle.INTRODUCTION_CONTEXT)) {
				p.addChild("input", new String[] { "type", "name", "value", "checked" },
						new String[] { "checkbox", "PublishPuzzles", "true", "checked"});
			}
			else
				p.addChild("input", new String[] { "type", "name", "value" }, new String[] { "checkbox", "PublishPuzzles", "true"});
			
			createForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "Edit", "Save changes" });
		}
		}
	}

}
