/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package plugins.Freetalk.ui.web;

import plugins.Freetalk.Freetalk;
import plugins.Freetalk.WoT.WoTIdentity;
import plugins.Freetalk.WoT.WoTOwnIdentity;
import freenet.keys.FreenetURI;
import freenet.l10n.BaseL10n;
import freenet.support.HTMLNode;
import freenet.support.api.HTTPRequest;

public class CreateIdentityWizard extends WebPageImpl {
	
	/* Step 1: Choose URI */
	private Boolean mGenerateRandomSSK = null; 
	private FreenetURI[] mIdentityURI = null; /* insert URI at index 0 and request URI at index 1 */

	/* Step 2: Choose Nickname */
	private String mIdentityNickname = null;

	/* Step 3: Set preferences */
	private Boolean mIdentityPublishesTrustList = null;
	
	private Boolean mAutoSubscribe = null;
	
	private Boolean mDisplayImages = null;

	/*
	 * TODO: Evaluate whether we need to ask the user whether he wants to
	 * publish puzzles during identity creation. I cannot think of any privacy
	 * problems with that. Does anyone else have an idea?
	 */
	/* private Boolean mIdentityPublishesPuzzles = null; */

	public CreateIdentityWizard(WebInterface myWebInterface, HTTPRequest request, BaseL10n _baseL10n) {
		super(myWebInterface, null, request, _baseL10n);
	}

	public void make() {
		makeCreateIdentityBox();
	}

	/*
	 * TODO: In the future this function should maybe be cleaned up to be more
	 * readable: Maybe separate it into several functions
	 */
	private void makeCreateIdentityBox() {
		HTMLNode wizardBox = addContentBox(l10n().getString("CreateIdentityWizard.CreateIdentityBox.Header"));
		HTMLNode backForm = addFormChild(wizardBox, Freetalk.PLUGIN_URI + "/CreateIdentity", "CreateIdentity");
		HTMLNode createForm = addFormChild(wizardBox, Freetalk.PLUGIN_URI + "/CreateIdentity", "CreateIdentity");
		
		Exception requestURIproblem = null;
		Exception insertURIproblem = null;
		Exception nicknameProblem = null;
		
		/* ======== Stage 1: Parse the passed form data ====================================================================================== */
		
		int requestedStep = mRequest.isPartSet("Step") ? Integer.parseInt(mRequest.getPartAsStringFailsafe("Step", 1)) : 1;
		
		/* Parse the "Generate random SSK?" boolean specified in step 1 */
		if(mRequest.isPartSet("GenerateRandomSSK"))
			mGenerateRandomSSK = mRequest.getPartAsStringFailsafe("GenerateRandomSSK", 5).equals("true");

		/* Parse the URI specified in step 1 */
		if(mRequest.isPartSet("RequestURI") && mRequest.isPartSet("InsertURI")) {
			mIdentityURI = new FreenetURI[2];

			try { mIdentityURI[0] = new FreenetURI(mRequest.getPartAsStringFailsafe("InsertURI", 256)); }
			catch(Exception e) { insertURIproblem = e; }

			try { mIdentityURI[1] = new FreenetURI(mRequest.getPartAsStringFailsafe("RequestURI", 256)); }
			catch(Exception e) { requestURIproblem = e; }

			if(insertURIproblem != null || requestURIproblem != null)
				mIdentityURI = null;

			/* TODO: Check whether the URI pair is correct, i.e. if the insert URI really is one, if the request URI really is one and
			 * if the two belong together. How to do this? */
		}

		if(mGenerateRandomSSK != null && mGenerateRandomSSK && mIdentityURI == null)
			mIdentityURI = mFreetalk.getPluginRespirator().getHLSimpleClient().generateKeyPair("");
		
		/* Parse the nickname specified in step 2 */
		if(mRequest.isPartSet("Nickname")) {
			try {
				mIdentityNickname = mRequest.getPartAsStringFailsafe("Nickname", 256);
				WoTIdentity.validateNickname(mIdentityNickname);
			}
			catch(Exception e) {
				nicknameProblem = e;
				mIdentityNickname = null;
			}
		}
		
		/* Parse the preferences specified in step 3 */
		if(requestedStep > 3) { /* We cannot just use isPartSet("PublishTrustList") because it won't be set if the checkbox is unchecked */
			if(mRequest.isPartSet("PublishTrustList"))
				mIdentityPublishesTrustList = mRequest.getPartAsStringFailsafe("PublishTrustList", 5).equals("true");
			else
				mIdentityPublishesTrustList = false;
			
			if(mRequest.isPartSet("AutoSubscribe"))
				mAutoSubscribe = mRequest.getPartAsStringFailsafe("AutoSubscribe", 5).equals("true");
			else
				mAutoSubscribe = false;
			
			if(mRequest.isPartSet("DisplayImages"))
				mDisplayImages = mRequest.getPartAsStringFailsafe("DisplayImages", 5).equals("true");
			else
				mDisplayImages = false;
		}
		
		/* ======== Stage 2: Check for missing data and correct requestedStep  =============================================================== */
		
		if(requestedStep > 1 && mIdentityURI == null) {
			requestedStep = 1;
		} else if(requestedStep > 2 && mIdentityNickname == null) {
			requestedStep = 2;
		} else if(requestedStep > 3 && (mIdentityPublishesTrustList == null || mAutoSubscribe == null || mDisplayImages == null)) {
			requestedStep = 3;
		}
		
		/* ======== Stage 3: Display the wizard stage at which we are ======================================================================== */
		
		/* Step 1: URI */
		if(requestedStep == 1) {
			addHiddenFormData(createForm, requestedStep, requestedStep + 1);
			
			HTMLNode chooseURIbox = getContentBox(l10n().getString("CreateIdentityWizard.Step1.Header"));
			createForm.addChild(chooseURIbox);
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
				
				if(requestURIproblem != null) {
					enterParagraph.addChild("br");
					enterParagraph.addChild("div", "style", "color: red;", 
					        l10n().getString("CreateIdentityWizard.Step1.RequestUriError") + ": " + requestURIproblem.getLocalizedMessage());
				}
				
				if(insertURIproblem != null) {
					enterParagraph.addChild("br");
					enterParagraph.addChild("div", "style", "color: red;", 
					        l10n().getString("CreateIdentityWizard.Step1.InsertUriError") + ": " + insertURIproblem.getLocalizedMessage());
				}
			
				enterParagraph.addChild("br");
				enterParagraph.addChild("#", l10n().getString("CreateIdentityWizard.Step1.RequestUri") + ": ");
				enterParagraph.addChild("input",	new String[] { "type", "name", "size", "value" },
													new String[] { "text", "RequestURI", "70", mRequest.getPartAsString("RequestURI", 256) });

				enterParagraph.addChild("br");
				enterParagraph.addChild("#", l10n().getString("CreateIdentityWizard.Step1.InsertUri") + ": ");
				enterParagraph.addChild("input",	new String[] { "type", "name", "size", "value" },
													new String[] { "text", "InsertURI", "70", mRequest.getPartAsString("InsertURI", 256) });
			}
		}
		
		/* Step 2: Nickname */
		else if(requestedStep == 2 ) {
			addHiddenFormData(createForm, requestedStep, requestedStep + 1);
			
			HTMLNode chooseNameBox = getContentBox(l10n().getString("CreateIdentityWizard.Step2.Header"));
			createForm.addChild(chooseNameBox);
			chooseNameBox.addChild("p", l10n().getString("CreateIdentityWizard.Step2.Text"));
			HTMLNode p = chooseNameBox.addChild("p");
			
			if(nicknameProblem != null) {
				p.addChild("p", "style", "color: red;").
				addChild("#", l10n().getString("CreateIdentityWizard.Step2.NicknameError") + ": " + nicknameProblem.getLocalizedMessage());
			}
			
			p.addChild("#", l10n().getString("CreateIdentityWizard.Step2.Nickname") + ": ");
			p.addChild("input",	new String[] { "type", "name", "size", "value" },
								new String[] { "text", "Nickname", "50", mRequest.getPartAsString("Nickname", 50) });

		}
		
		/* Step 3: Preferences */
		else if(requestedStep == 3) {
            final String[] l10nBoldSubstitutionInput = new String[] { "bold", "/bold" };
            final String[] l10nBoldSubstitutionOutput = new String[] { "<b>", "</b>" };

			addHiddenFormData(createForm, requestedStep, requestedStep + 1);
			
			HTMLNode choosePrefsBox = getContentBox(l10n().getString("CreateIdentityWizard.Step3.Header"));
			createForm.addChild(choosePrefsBox);
			
			HTMLNode tlBox = getContentBox(l10n().getString("CreateIdentityWizard.Step3.TrustList.Header"));
			choosePrefsBox.addChild(tlBox);
			
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
			
			
			HTMLNode autoSubscribeBox = getContentBox(l10n().getString("CreateIdentityWizard.Step3.AutoSubscribe.Header"));
			choosePrefsBox.addChild(autoSubscribeBox);
	
			p = autoSubscribeBox.addChild("p");
	        l10n().addL10nSubstitution(p, "CreateIdentityWizard.Step3.AutoSubscribe.Text", l10nBoldSubstitutionInput, l10nBoldSubstitutionOutput);

			
			p = autoSubscribeBox.addChild("p");
			if(mAutoSubscribe != null && mAutoSubscribe) {
				p.addChild("input",	new String[] { "type", "name", "value", "checked" },
									new String[] { "checkbox", "AutoSubscribe", "true", "checked"});
			} else {
				p.addChild("input",	new String[] { "type", "name", "value" },
									new String[] { "checkbox", "AutoSubscribe", "true" });				
			}
			p.addChild("#", l10n().getString("CreateIdentityWizard.Step3.AutoSubscribe.AutoSubscribeCheckbox"));
			
			
			HTMLNode displayImagesBox = getContentBox(l10n().getString("CreateIdentityWizard.Step3.DisplayImages.Header"));
			choosePrefsBox.addChild(displayImagesBox);
	
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
		else if(requestedStep == 4 && mRequest.getMethod().equals("POST")) {
			addHiddenFormData(createForm, requestedStep, requestedStep);
			
			try {
				WoTOwnIdentity id = (WoTOwnIdentity)mFreetalk.getIdentityManager().createOwnIdentity(mIdentityNickname,
						mIdentityPublishesTrustList, mIdentityPublishesTrustList, mAutoSubscribe, mDisplayImages, mIdentityURI[1], mIdentityURI[0]);
						
				HTMLNode summaryBox = getContentBox(l10n().getString("CreateIdentityWizard.Step4.Header"));
				wizardBox.addChild(summaryBox);
				
				summaryBox.addChild("p", l10n().getString("CreateIdentityWizard.Step4.Success"));
				LogInPage.addLoginButton(this, summaryBox, id, l10n());
				
				return;
			}
			catch(Exception e) {
				HTMLNode errorBox = getAlertBox(l10n().getString("CreateIdentityWizard.Step4.Failure"));
				createForm.addChild(errorBox);
				
				errorBox.addChild("p", e.getLocalizedMessage());
			}
		}


		if(requestedStep > 1) { // Step 4 (create the identity) will return; if it was successful so also display "Back" for it
			addHiddenFormData(backForm, requestedStep, requestedStep - 1);
			backForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "submit", l10n().getString("CreateIdentityWizard.BackButton") });
		}
		
		if(requestedStep < 4)
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
											new String[] { "hidden", "InsertURI", mIdentityURI[0].toString() });
				myForm.addChild("input",	new String[] { "type", "name", "value" },
											new String[] { "hidden", "RequestURI", mIdentityURI[1].toString() });
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
		}
	}
}
