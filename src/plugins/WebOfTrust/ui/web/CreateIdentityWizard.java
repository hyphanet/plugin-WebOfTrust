/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package plugins.WebOfTrust.ui.web;

import javax.naming.SizeLimitExceededException;

import plugins.WebOfTrust.Identity;
import plugins.WebOfTrust.OwnIdentity;
import plugins.WebOfTrust.WebOfTrust;
import plugins.WebOfTrust.ui.web.WebInterface.CreateIdentityWebInterfaceToadlet;
import freenet.clients.http.InfoboxNode;
import freenet.clients.http.RedirectException;
import freenet.clients.http.SessionManager.Session;
import freenet.clients.http.ToadletContext;
import freenet.keys.FreenetURI;
import freenet.support.HTMLNode;
import freenet.support.api.HTTPRequest;

/**
 * TODO FIXME: Comment out mDisplayImages as WOT cannot display images yet but might display avatars at some point in the future.
 * 	Make sure to create a bugtracker entry for re-enabling it once we support images.
 * 
 * TODO FIXME: Provide the option to restore an existing identity. This is a *must* have because people are very likely to mix up restore/create
 * 	- which results in broken identities. We need to have both options in one place and explain the difference in detail.
 * 
 * TODO FIXME: Allow other plugins to link to his.
 * TODO FIXME: When another plugin links to this, allow it to specify a context.
 * 	- The {@link WebOfTrust#createOwnIdentity(FreenetURI, String, boolean, String)} which we use allows specifying a context.
 * 
 * TODO FIXME: L10n: Instead of "Insert" / "Request" URI, use "public"/"private". Keep "Insert"/"Request" in brackets because we use that a lot
 * 	in Freenet L10n. Explain that the private URI shouldn't be given to anyone. Also explain public-private crypto a bit. 
 *
 * @author xor (xor@freenetproject.org)
 */
public class CreateIdentityWizard extends WebPageImpl {
	
	private int mRequestedStep = 1;
	
	/* Step 1: Choose URI */
	private Boolean mGenerateRandomSSK = null; 
	private FreenetURI mIdentityURI = null;
	
	private Exception mInsertURIProblem = null;

	/* Step 2: Choose Nickname */
	private String mIdentityNickname = null;
	
	private Exception mNicknameProblem = null;

	/* Step 3: Set preferences */
	private Boolean mIdentityPublishesTrustList = null;
	
	private Boolean mDisplayImages = null;

	/*
	 * TODO: Evaluate whether we need to ask the user whether he wants to
	 * publish puzzles during identity creation. I cannot think of any privacy
	 * problems with that. Does anyone else have an idea?
	 */
	/* private Boolean mIdentityPublishesPuzzles = null; */


	/**
	 * See {@link WebPageImpl#WebPageImpl(WebInterfaceToadlet, HTTPRequest, ToadletContext, boolean)} for description of the parameters.
	 * Calls that constructor with useSession=false, i.e. does not require any identity to be logged in so it is always possible to use the wizard for creating one.
	 * 
	 * @throws RedirectException Should never be thrown since no {@link Session} is used.
	 */
	public CreateIdentityWizard(WebInterfaceToadlet toadlet, HTTPRequest myRequest, ToadletContext context) throws RedirectException {
		super(toadlet, myRequest, context, false);
	}

	public void make() {
		parseFormData();
		makeCreateIdentityBox();
	}
	
	/**
	 * Parses the form data for all steps of the wizard so it persists across usage of pressing Back/Continue.
	 */
	private void parseFormData() {
		/* ======== Stage 1: Parse the passed form data ====================================================================================== */

		mRequestedStep = mRequest.isPartSet("Step") ? Integer.parseInt(mRequest.getPartAsStringFailsafe("Step", 1)) : 1;
		
		/* Parse the "Generate random SSK?" boolean specified in step 1 */
		if(mRequest.isPartSet("GenerateRandomSSK"))
			mGenerateRandomSSK = mRequest.getPartAsStringFailsafe("GenerateRandomSSK", 5).equals("true");

		/* Parse the URI specified in step 1 */
		if(mRequest.isPartSet("InsertURI")) {
			try { 
				// TODO: Once FreenetURI has a maximum size constant, use it here and elsewhere in this file.
				mIdentityURI = new FreenetURI(mRequest.getPartAsStringThrowing("InsertURI", 256));
				OwnIdentity.testAndNormalizeInsertURI(mIdentityURI);
			} catch(SizeLimitExceededException e) {
				// TODO: Once FreenetURI has a maximum size constant, use it here and elsewhere in this file.
				mInsertURIProblem = new Exception(l10n().getString("Common.SizeLimitExceededException", "limit", "256"));
				mIdentityURI = null;
			} catch(Exception e) {
				mInsertURIProblem = e;
				mIdentityURI = null;
			}
		}

		if(mGenerateRandomSSK != null && mGenerateRandomSSK && mIdentityURI == null)
			mIdentityURI = pr.getHLSimpleClient().generateKeyPair("")[0];
		
		/* Parse the nickname specified in step 2 */
		if(mRequest.isPartSet("Nickname")) {
			try {
				mIdentityNickname = mRequest.getPartAsStringThrowing("Nickname", Identity.MAX_NICKNAME_LENGTH);
				Identity.validateNickname(mIdentityNickname);
			} catch(SizeLimitExceededException e) {
				mNicknameProblem = new Exception(l10n().getString("Common.SizeLimitExceededException", "limit", Integer.toString(Identity.MAX_NICKNAME_LENGTH)));
				mIdentityNickname = null;
			} catch(Exception e) {
				mNicknameProblem = e;
				mIdentityNickname = null;
			}
		}
		
		/* Parse the preferences specified in step 3 */
		if(mRequestedStep > 3) { /* We cannot just use isPartSet("PublishTrustList") because it won't be set if the checkbox is unchecked */
			if(mRequest.isPartSet("PublishTrustList"))
				mIdentityPublishesTrustList = mRequest.getPartAsStringFailsafe("PublishTrustList", 5).equals("true");
			else
				mIdentityPublishesTrustList = false;
			
			if(mRequest.isPartSet("DisplayImages"))
				mDisplayImages = mRequest.getPartAsStringFailsafe("DisplayImages", 5).equals("true");
			else
				mDisplayImages = false;
		}
		
		/* ======== Stage 2: Check for missing data and correct mRequestedStep  =============================================================== */
		
		if(mRequestedStep > 1 && mIdentityURI == null) {
			mRequestedStep = 1;
		} else if(mRequestedStep > 2 && mIdentityNickname == null) {
			mRequestedStep = 2;
		} else if(mRequestedStep > 3 && (mIdentityPublishesTrustList == null || mDisplayImages == null)) {
			mRequestedStep = 3;
		}
	}

	/**
	 * Renders the actual wizard page.
	 * 
	 * TODO: In the future this function should maybe be cleaned up to be more
	 * readable: Maybe separate it into several functions
	 */
	private void makeCreateIdentityBox() {
		HTMLNode wizardBox = addContentBox(l10n().getString("CreateIdentityWizard.CreateIdentityBox.Header"));
		HTMLNode backForm = pr.addFormChild(wizardBox, mToadlet.getURI().toString(), mToadlet.pageTitle);
		HTMLNode createForm = pr.addFormChild(wizardBox, mToadlet.getURI().toString(), mToadlet.pageTitle);
		
		/* Step 1: URI */
		if(mRequestedStep == 1) {
			addHiddenFormData(createForm, mRequestedStep, mRequestedStep + 1);
			
			InfoboxNode chooseURIInfoboxNode = getContentBox(l10n().getString("CreateIdentityWizard.Step1.Header"));
			createForm.addChild(chooseURIInfoboxNode.outer);
			
			HTMLNode chooseURIbox = chooseURIInfoboxNode.content;
			chooseURIbox.addChild("p", l10n().getString("CreateIdentityWizard.Step1.Text"));
			
			HTMLNode randomRadio = chooseURIbox.addChild("p");
			HTMLNode notRandomRadio = chooseURIbox.addChild("p");
			
		
			if(mGenerateRandomSSK == null || mGenerateRandomSSK == true) {
				randomRadio.addChild("input", 	new String[] { "type", "name", "value" , "checked"},
												new String[] { "radio", "GenerateRandomSSK" , "true", "checked"});
								
				notRandomRadio.addChild("input",	new String[] { "type", "name", "value" },
													new String[] { "radio", "GenerateRandomSSK" , "false"});
			} else {

				randomRadio.addChild("input", 	new String[] { "type", "name", "value"},
												new String[] { "radio", "GenerateRandomSSK" , "true",});
				
				notRandomRadio.addChild("input",	new String[] { "type", "name", "value", "checked"},
													new String[] { "radio", "GenerateRandomSSK" , "false", "checked"});
			}
			
			randomRadio.addChild("#", l10n().getString("CreateIdentityWizard.Step1.GenerateNewKeyPairRadio"));
			notRandomRadio.addChild("#", l10n().getString("CreateIdentityWizard.Step1.UseExistingKeyPairRadio"));

			if(mGenerateRandomSSK != null && mGenerateRandomSSK == false) {
				HTMLNode enterParagraph = notRandomRadio.addChild("p", l10n().getString("CreateIdentityWizard.Step1.EnterKeyPair") + ":");
				
				if(mInsertURIProblem != null) {
					enterParagraph.addChild("br");
					enterParagraph.addChild("div", "style", "color: red;", 
					        l10n().getString("CreateIdentityWizard.Step1.InsertUriError") + ": " + mInsertURIProblem.getLocalizedMessage());
				}

				enterParagraph.addChild("br");
				enterParagraph.addChild("#", l10n().getString("CreateIdentityWizard.Step1.InsertUri") + ": ");
				enterParagraph.addChild("input",	new String[] { "type", "name", "size", "value" },
													new String[] { "text", "InsertURI", "70", mRequest.getPartAsStringFailsafe("InsertURI", 256) });
			}
		}
		
		/* Step 2: Nickname */
		else if(mRequestedStep == 2 ) {
			addHiddenFormData(createForm, mRequestedStep, mRequestedStep + 1);
			
			InfoboxNode chooseNameInfoboxNode = getContentBox(l10n().getString("CreateIdentityWizard.Step2.Header"));
			createForm.addChild(chooseNameInfoboxNode.outer);
			
			HTMLNode chooseNameBox = chooseNameInfoboxNode.content;
			chooseNameBox.addChild("p", l10n().getString("CreateIdentityWizard.Step2.Text"));
			HTMLNode p = chooseNameBox.addChild("p");
			
			if(mNicknameProblem != null) {
				p.addChild("p", "style", "color: red;").
				addChild("#", l10n().getString("CreateIdentityWizard.Step2.NicknameError") + ": " + mNicknameProblem.getLocalizedMessage());
			}
			
			p.addChild("#", l10n().getString("CreateIdentityWizard.Step2.Nickname") + ": ");
			p.addChild("input",	new String[] { "type", "name", "size", "value" },
								new String[] { "text", "Nickname", "50", mRequest.getPartAsStringFailsafe("Nickname", Identity.MAX_NICKNAME_LENGTH) });

		}
		
		/* Step 3: Preferences */
		else if(mRequestedStep == 3) {
            final String[] l10nBoldSubstitutionInput = new String[] { "bold", "/bold" };
            final String[] l10nBoldSubstitutionOutput = new String[] { "<b>", "</b>" };

			addHiddenFormData(createForm, mRequestedStep, mRequestedStep + 1);
			
			InfoboxNode choosePrefsInfoboxNode = getContentBox(l10n().getString("CreateIdentityWizard.Step3.Header"));
			createForm.addChild(choosePrefsInfoboxNode.outer);
			
			HTMLNode choosePrefsBox = choosePrefsInfoboxNode.content;
			
			InfoboxNode tlInfoboxNode = getContentBox(l10n().getString("CreateIdentityWizard.Step3.TrustList.Header"));
			choosePrefsBox.addChild(tlInfoboxNode.outer);
			
			HTMLNode tlBox = tlInfoboxNode.content;
			
			HTMLNode p;

			p = tlBox.addChild("p");
	        l10n().addL10nSubstitution(p, "CreateIdentityWizard.Step3.TrustList.Text1", l10nBoldSubstitutionInput, l10nBoldSubstitutionOutput);
	        p = tlBox.addChild("p");
	        l10n().addL10nSubstitution(p, "CreateIdentityWizard.Step3.TrustList.Text2", l10nBoldSubstitutionInput, l10nBoldSubstitutionOutput);
	        p = tlBox.addChild("p");
	        l10n().addL10nSubstitution(p, "CreateIdentityWizard.Step3.TrustList.Text3", l10nBoldSubstitutionInput, l10nBoldSubstitutionOutput);
	        p = tlBox.addChild("p");
	        l10n().addL10nSubstitution(p, "CreateIdentityWizard.Step3.TrustList.Text4", l10nBoldSubstitutionInput, l10nBoldSubstitutionOutput);
			
			p = tlBox.addChild("p");
			if(mIdentityPublishesTrustList == null || mIdentityPublishesTrustList == true) {
				p.addChild("input",	new String[] { "type", "name", "value", "checked" },
									new String[] { "checkbox", "PublishTrustList", "true", "checked"});
			} else {
				p.addChild("input",	new String[] { "type", "name", "value" },
									new String[] { "checkbox", "PublishTrustList", "true" });
			}
			p.addChild("#", l10n().getString("CreateIdentityWizard.Step3.TrustList.PublishTrustListCheckbox"));
			
			
			InfoboxNode displayImagesInfoboxNode = getContentBox(l10n().getString("CreateIdentityWizard.Step3.DisplayImages.Header"));
			choosePrefsBox.addChild(displayImagesInfoboxNode.outer);
			
			HTMLNode displayImagesBox = displayImagesInfoboxNode.content;
	
			p = displayImagesBox.addChild("p");
	        l10n().addL10nSubstitution(p, "CreateIdentityWizard.Step3.DisplayImages.Text", l10nBoldSubstitutionInput, l10nBoldSubstitutionOutput);

			
			p = displayImagesBox.addChild("p");
			if(mDisplayImages != null && mDisplayImages) {
				p.addChild("input",	new String[] { "type", "name", "value", "checked" },
									new String[] { "checkbox", "DisplayImages", "true", "checked"});
			} else {
				p.addChild("input",	new String[] { "type", "name", "value" },
									new String[] { "checkbox", "DisplayImages", "true" });				
			}
			p.addChild("#", l10n().getString("CreateIdentityWizard.Step3.DisplayImages.Checkbox"));
		}
		
		/* Step 4: Create the identity */
		else if(mRequestedStep == 4 && mRequest.getMethod().equals("POST")) {
			addHiddenFormData(createForm, mRequestedStep, mRequestedStep);
			
			try {
				OwnIdentity id = mWebOfTrust.createOwnIdentity(mIdentityURI, mIdentityNickname, mIdentityPublishesTrustList, null);
						
				// FIXME TODO: Add storage for mDisplayImages
				
				InfoboxNode summaryInfoboxNode = getContentBox(l10n().getString("CreateIdentityWizard.Step4.Header"));
				wizardBox.addChild(summaryInfoboxNode.outer);
				
				HTMLNode summaryBox = summaryInfoboxNode.content;
				summaryBox.addChild("p", l10n().getString("CreateIdentityWizard.Step4.Success"));
				LogInPage.addLoginButton(this, summaryBox, id);
				
				return;
			}
			catch(Exception e) {
				InfoboxNode errorInfoboxNode = getAlertBox(l10n().getString("CreateIdentityWizard.Step4.Failure"));
				createForm.addChild(errorInfoboxNode.outer);
				
				HTMLNode errorBox = errorInfoboxNode.content;
				errorBox.addChild("p", e.getLocalizedMessage());
			}
		}


		if(mRequestedStep > 1) { // Step 4 (create the identity) will return; if it was successful so also display "Back" for it
			addHiddenFormData(backForm, mRequestedStep, mRequestedStep - 1);
			backForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "submit", l10n().getString("CreateIdentityWizard.BackButton") });
		}
		
		if(mRequestedStep < 4)
			createForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "submit", l10n().getString("CreateIdentityWizard.ContinueButton") });
		else // There was an error creating the identity
			createForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "submit", l10n().getString("CreateIdentityWizard.RetryButton") });
	}

	/**
	 * Stores the form data which the user has already specified as hidden
	 * elements.
	 * 
	 * @param myForm
	 *            The HTMLNode of the parent form.
	 */
	private void addHiddenFormData(HTMLNode myForm, int currentStep, int nextStep) {
		myForm.addChild("input", new String[] { "type", "name", "value" },
								 new String[] { "hidden", "Step", Integer.toString(nextStep)});
		
		boolean backStep = nextStep < currentStep;
		
		if(backStep || currentStep != 1) { // Do not overwrite the visible fields with hidden fields.
			if(mGenerateRandomSSK != null) {
				myForm.addChild("input",	new String[] { "type", "name", "value" },
											new String[] { "hidden", "GenerateRandomSSK", mGenerateRandomSSK.toString() });
			}
			
			if(mIdentityURI != null) {
				myForm.addChild("input",	new String[] { "type", "name", "value" },
											new String[] { "hidden", "InsertURI", mIdentityURI.toString() });
			}
		}

		if(backStep || currentStep != 2) { // Do not overwrite the visible fields with hidden fields
			if(mIdentityNickname != null) {
				myForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "Nickname", mIdentityNickname });
			}
		}

		if(backStep || currentStep != 3) { // Do not overwrite the visible fields with hidden fields
			if(mIdentityPublishesTrustList != null) {
				myForm.addChild("input",	new String[] { "type", "name", "value" },
											new String[] { "hidden", "PublishTrustList", mIdentityPublishesTrustList.toString() });
			}
			
			// FIXME TODO: Why don't we add mAutoSubscribe / mDisplayImages?
		}
	}

	/**
	 * FIXME TODO: Rename L10n strings
	 */
	public static void addLinkToCreateIdentityWizard(WebPageImpl page) {
		final String createIdentityURI = page.mWebInterface.getToadlet(CreateIdentityWebInterfaceToadlet.class).getURI().toString();
		
		HTMLNode createIdentityBox = page.addContentBox(page.l10n().getString("CreateIdentityPage.LinkToCreateIdentityPageBox.Header"));
		page.l10n().addL10nSubstitution(
		        createIdentityBox,
		        "CreateIdentityPage.LinkToCreateIdentityPageBox.Text",
		        new String[] { "link", "/link" },
		        new HTMLNode[] { new HTMLNode("a", "href", createIdentityURI) });
	}
}
