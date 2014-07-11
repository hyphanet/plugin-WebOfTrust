/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package plugins.WebOfTrust.ui.web;

import java.net.URI;
import java.net.URISyntaxException;

import javax.naming.SizeLimitExceededException;

import plugins.WebOfTrust.Identity;
import plugins.WebOfTrust.OwnIdentity;
import plugins.WebOfTrust.WebOfTrust;
import plugins.WebOfTrust.ui.web.WebInterface.CreateIdentityWebInterfaceToadlet;
import plugins.WebOfTrust.util.RandomName;
import freenet.clients.http.InfoboxNode;
import freenet.clients.http.RedirectException;
import freenet.clients.http.SessionManager.Session;
import freenet.clients.http.ToadletContext;
import freenet.keys.FreenetURI;
import freenet.support.HTMLNode;
import freenet.support.api.HTTPRequest;

/**
 * A wizard for creating an {@link OwnIdentity}. Can be used by other plugins indirectly by them linking to {@link LogInPage} - see its JavaDoc.
 * 
 * TODO FIXME: When another plugin links to this, allow it to specify a context.
 * 	- The {@link WebOfTrust#createOwnIdentity(FreenetURI, String, boolean, String)} which we use allows specifying a context.
 * 
 * TODO: Please deal with this when adding new steps: The code which handles each {@link CreateIdentityWizard.Step} should probably moved to member classes.
 *     Also, {@link #computeNextStep(Step, boolean)} and the functions which it uses could probably be split up among member classes or go to its own as  well.
 *
 * @author xor (xor@freenetproject.org)
 */
public final class CreateIdentityWizard extends WebPageImpl {
	
	/**
	 * The wizard consists of these stages. A stage is a view of the wizard separated by Back/Continue buttons from the other stages.
	 * Depending on the choice of the user, some stages might be skipped.
	 * 
	 * ATTENTION: If you reorder these, you must review the whole class {@link CreateIdentityWizard} because it uses {@link Step#ordinal()}.
	 */
	enum Step {
		ChooseURI,
		ChooseCreateOrRestore,
		ChooseNickname,
		ChoosePreferences,
		CreateIdentity;
		
		static Step first() {
			return ChooseURI;
		}
		
		static Step last() {
			return CreateIdentity;
		}
	};
	
	private Step mCurrentStep = Step.first();
	
	/* Step 1: Choose URI */
	private Boolean mGenerateRandomSSK = null; 
	private FreenetURI mIdentityURI = null;
	
	private Exception mInsertURIProblem = null;
	
	/* Step 2: Choose create or restore */
	
	private Boolean mRestoreIdentity = null;

	/* Step 3: Choose Nickname */
	private String mIdentityNickname = null;
	
	private Exception mNicknameProblem = null;

	/* Step 4: Set preferences */
	private Boolean mIdentityPublishesTrustList = null;

	
	/**
	 * Not part of the UI.
	 * Tells to which URI to redirect the client browser after creating the identity & logging it in.
	 * Can be used to allow third-party plugins to use the wizard: It allows to redirect the user back to the 3rd-party plugin after creating an identity.
	 */
	private String mRedirectTarget = DEFAULT_REDIRECT_TARGET_AFTER_LOGIN;
	
	/**
	 * Default value of {@link #mRedirectTarget}.
	 */
	private static final String DEFAULT_REDIRECT_TARGET_AFTER_LOGIN = LogInPage.DEFAULT_REDIRECT_TARGET_AFTER_LOGIN;

	
	
	final static String[] mL10nBoldSubstitutionInput = new String[] { "bold" };
	final static HTMLNode[] mL10nBoldSubstitutionOutput = new HTMLNode[] { HTMLNode.STRONG };


	/**
	 * See {@link WebPageImpl#WebPageImpl(WebInterfaceToadlet, HTTPRequest, ToadletContext, boolean)} for description of the parameters.
	 * Calls that constructor with useSession=false, i.e. does not require any identity to be logged in so it is always possible to use the wizard for creating one.
	 * 
	 * @param myRequest Checked for param "redirect-target", a node-relative target that the user is redirected to after logging in. This can include a path,
	 *                  query, and fragment, but any scheme, host, or port will be ignored. If this parameter is empty or not specified it redirects to
	 *                  {@link #DEFAULT_REDIRECT_TARGET_AFTER_LOGIN}.
	 *                  Typically specified by {@link LogInPage} to allow third party plugins to use it.
	 * @throws RedirectException Should never be thrown since no {@link Session} is used.
	 */
	public CreateIdentityWizard(WebInterfaceToadlet toadlet, HTTPRequest myRequest, ToadletContext context) throws RedirectException {
		super(toadlet, myRequest, context, false);
	}

	public void make() {
		parseFormData();
		
		HTMLNode wizardBox = addContentBox(l10n().getString("CreateIdentityWizard.CreateIdentityBox.Header"));
		HTMLNode form = pr.addFormChild(wizardBox, mToadlet.getURI().toString(), mToadlet.pageTitle);
		
		boolean finished = false;
		
		switch(mCurrentStep) {
			case ChooseURI: makeChooseURIStep(form); break;
			case ChooseCreateOrRestore: makeChooseCreateOrRestoreStep(form); break;
			case ChooseNickname: makeChooseNicknameStep(form); break;
			case ChoosePreferences: makeChoosePreferencesStep(form); break;
			case CreateIdentity: finished = makeCreateIdentityStep(wizardBox, form); break;
			default: throw new IllegalStateException();
		}
		
		if(!finished) {
			addHiddenFormData(form);
			makeBackAndContinueButtons(form);
		}
	}
	
	/**
	 * Parses the form data for all steps of the wizard so it persists across usage of pressing Back/Continue.
	 */
	private void parseFormData() {
		// Remote plugins specify the redirect-target via GET-data, the wizard itself uses POST-data.
		// Therefore, we first check for GET via getParam(), then for POST via getPartAsStringThrowing().
		mRedirectTarget = mRequest.getParam("redirect-target", null);
		if(mRedirectTarget == null) {
			try {
				mRedirectTarget = mRequest.getPartAsStringThrowing("redirect-target", 256);
			} catch(Exception e) {
				mRedirectTarget = DEFAULT_REDIRECT_TARGET_AFTER_LOGIN;
			}
		}
		
		/* Parse data from makeChooseURIStep(): Radio button which selects whether to generate a random URI or specify one  */
		if(mRequest.isPartSet("GenerateRandomSSK"))
			mGenerateRandomSSK = mRequest.getPartAsStringFailsafe("GenerateRandomSSK", 5).equals("true");

		/* Parse data from makeChooseURIStep(): Textbox to specify an URI */
		if(mGenerateRandomSSK != null && mGenerateRandomSSK == true) { // Radio button choice was to generate a random URI, so the text box was not present
			// Set a dummy URI so the code which checks whether the user specified an URI is satisfied
			// We don't generate the random URI ourself because that shouldn't be done in the UI.
			// Instead we later on let WebOfTrust.createOwnIdentity() do it.
			mIdentityURI = FreenetURI.EMPTY_CHK_URI;
		} else if(mRequest.isPartSet("InsertURI")) {
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
	
		/* Parse data from makeChooseCreateOrRestoreStep: Radio button to select whether to restore an existing identity or create a new one*/
		if(mRequest.isPartSet("RestoreIdentity"))
			mRestoreIdentity = mRequest.getPartAsStringFailsafe("RestoreIdentity", 5).equals("true");
		
		/* Parse data from makeChooseNicknameStep(): Textbox to choose a nickname*/
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
		
		/* Parse data from makeChoosePreferencesStep(): Checkbox to select whether to publish the trust list */ 
		if(mRequest.isPartSet("PublishTrustList")) {
			mIdentityPublishesTrustList = mRequest.getPartAsStringFailsafe("PublishTrustList", 5).equals("true");
		}
		
		// We now have the data from all visible UI elements parsed. This is now validated for completeness and the next step is computed depending on
		// which the step the user was at, whether he pressed Continue or Back and whether data is missing.
		mCurrentStep = parsePreviousAndComputeCurrentStep();
	}

	private Step parsePreviousAndComputeCurrentStep() {
		// The "PreviousStep" POST part specifies the integer value of the ordinal() of the Step which the user completed when submitting the POST request
		// If it is not set, we are at the first step.
		if(!mRequest.isPartSet("PreviousStep"))
			return Step.first();
		
		int previous = Integer.parseInt(mRequest.getPartAsStringFailsafe("PreviousStep", 1));
		// Prevent malicious out-of-bounds ordinals.
		previous = Math.max(Step.first().ordinal(), previous);
		previous = Math.min(Step.last().ordinal(), previous);
		
		final Step previousStep = Step.values()[previous];
		
		// If the user pressed "Continue", the NextStepButton part will be specified. If he pressed back, it is not specified.
		final boolean forward = mRequest.isPartSet("NextStepButton");
		
		// First we simulate walking through every step up to and excluding previous one:
		// For each of them, check whether the user specified all data using validateStepData()
		// If this returns false, we found the earliest incomplete Step and the user must repeat it.
		for(Step walk = Step.first(); walk != previousStep; ) {
			if(!validateStepData(walk))
				return walk;
			
			Step nextStep = computeNextStep(walk, true);
			if(nextStep == walk) // Prevent infinite loop
				return nextStep;
			else
				walk = nextStep;
		}
		
		// Now we are sure that the data of all Steps excluding previousStep is complete.
		
		if(forward) {
			// To go forward, the data of the previous step must have been valid.	
			if(validateStepData(previousStep))
				return computeNextStep(previousStep, true);
			else
				return previousStep;
		} else {
			// To go backward, we do not have to validate the data of the previousStep as the user might not have entered anything yet
			return computeNextStep(previousStep, false);
		}
			
	}

	/**
	 * Validates whether we successfully parsed the form data which is entered in the UI of the given {@link Step}.
	 * Does NOT check the steps before that.
	 */
	private boolean validateStepData(Step step) {
		switch(step) {
			case ChooseURI: return mGenerateRandomSSK != null && mIdentityURI != null;
			case ChooseCreateOrRestore: return mRestoreIdentity != null;
			case ChooseNickname: return mIdentityNickname != null;
			case ChoosePreferences: return mIdentityPublishesTrustList != null;
			case CreateIdentity: return true;
			default: throw new UnsupportedOperationException();
		}
	}

	/**
	 * Computes the next step using the form data which has already been parsed to the member variables.
	 * 
	 * If forward is true, computes the Step following the given one. In this case, you MUST first check whether {@link #validateStepData(Step)} for all Steps
	 * before and including the given one has returned true.
	 * 
	 * If forward is false, computes the Step preceding the given one. In this case, you MUST first check whether {@link #validateStepData(Step)} for all Steps
	 * before and excluding the given one has returned true.
	 */
	private Step computeNextStep(Step step, boolean forward) {
		// The following helps when understanding this function:
		// - In the first step Step.ChooseURI, the user is asked whether to generate a fresh, random URI for the identity or whether he wants to enter
		//   an existing URI of his own. This allows the user to use the URI of his Freesite for example so people can validate that the owner of
		//   the Freesite and the WOT identity are the same.
		//   BUT it is also possible that the user had already created an identity from that URI in the past. In that case, we don't want to create a new one.
		//   Instead, we want to download the data of the identity from the network.
		// - Because it is difficult to implement code which detects whether there already is an identity existent at that given URI, there is the second
		//   step Step.ChooseCreateOrRestore in which we ask the user explicitly whether to create a fresh identity or restore an existing one.
		// So the conclusions for what this function computes as the next step are:
		// - Step.ChooseCreateOrRestore shall only be the next step if the user specified an URI in the first step
		// - Step.ChooseNickname shall not be the next step if the user chose to restore an identity in Step.ChooseCreateOrRestore.
		//   This is because we can download the nickname from the network when restoring an identity
		// - Step.ChoosePreferences also shall not be the next step when restoring an identity because preferences can also be downloaded from the network.
		if(forward) {
			switch(step) {
				case ChooseURI: 
					if(mGenerateRandomSSK)
						return Step.ChooseNickname;
					else
						return Step.ChooseCreateOrRestore;
				case ChooseCreateOrRestore:
					if(!mRestoreIdentity)
						return Step.ChooseNickname;
					else
						return Step.CreateIdentity;
				case ChooseNickname: 
					return Step.ChoosePreferences;
				case ChoosePreferences:
					return Step.CreateIdentity;
				case CreateIdentity:
					return Step.CreateIdentity; // Allow repeating it if identity creation has failed.
				default:
					throw new UnsupportedOperationException();
			}
		} else { // Backward
			switch(step) {
				case ChooseURI: 
					throw new UnsupportedOperationException();
				case ChooseCreateOrRestore:
					return Step.ChooseURI;
				case ChooseNickname:
					if(!mGenerateRandomSSK)
						return Step.ChooseCreateOrRestore;
					else
						return Step.ChooseURI;
				case ChoosePreferences:
					return Step.ChooseNickname;
				case CreateIdentity:
					if(!mGenerateRandomSSK && mRestoreIdentity)  // Check mGenerateRandomSSK first because mRestoreIdentity will be null if it is true
						return Step.ChooseCreateOrRestore;
					else
						return Step.ChoosePreferences;
				default:
					throw new UnsupportedOperationException();
			}
		}
	}

	private void makeChooseURIStep(HTMLNode form) {
		InfoboxNode chooseURIInfoboxNode = getContentBox(l10n().getString("CreateIdentityWizard.Step.ChooseURI.Header"));
		form.addChild(chooseURIInfoboxNode.outer);

		HTMLNode chooseURIbox = chooseURIInfoboxNode.content;
		chooseURIbox.addChild("p", l10n().getString("CreateIdentityWizard.Step.ChooseURI.Text"));

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

		randomRadio.addChild("#", l10n().getString("CreateIdentityWizard.Step.ChooseURI.GenerateNewKeyPairRadio"));
		notRandomRadio.addChild("#", l10n().getString("CreateIdentityWizard.Step.ChooseURI.UseExistingKeyPairRadio"));

		if(mGenerateRandomSSK != null && mGenerateRandomSSK == false) {
			HTMLNode enterParagraph = notRandomRadio.addChild("p", l10n().getString("CreateIdentityWizard.Step.ChooseURI.EnterKeyPair") + ":");

			if(mInsertURIProblem != null) {
				enterParagraph.addChild("br");
				enterParagraph.addChild("div", "style", "color: red;", 
						l10n().getString("CreateIdentityWizard.Step.ChooseURI.InsertUriError") + ": " + mInsertURIProblem.getLocalizedMessage());
			}

			enterParagraph.addChild("br");
			enterParagraph.addChild("#", l10n().getString("CreateIdentityWizard.Step.ChooseURI.InsertUri") + ": ");
			enterParagraph.addChild("input",	new String[] { "type", "name", "size", "value" },
					new String[] { "text", "InsertURI", "70", mRequest.getPartAsStringFailsafe("InsertURI", 256) });
		}
	}
	
	private void makeChooseCreateOrRestoreStep(HTMLNode form) {
		InfoboxNode chooseCreateOrRestoreInfoboxNode = getContentBox(l10n().getString("CreateIdentityWizard.Step.ChooseCreateOrRestore.Header"));
		form.addChild(chooseCreateOrRestoreInfoboxNode.outer);

		HTMLNode chooseCreateOrRestoreBox = chooseCreateOrRestoreInfoboxNode.content;
		chooseCreateOrRestoreBox.addChild("p", l10n().getString("CreateIdentityWizard.Step.ChooseCreateOrRestore.Text.1"));

		HTMLNode restoreRadio = chooseCreateOrRestoreBox.addChild("p");
		HTMLNode createRadio = chooseCreateOrRestoreBox.addChild("p");


		if(mRestoreIdentity == null || mRestoreIdentity == true) {
			restoreRadio.addChild("input", 	new String[] { "type", "name", "value" , "checked"},
					new String[] { "radio", "RestoreIdentity" , "true", "checked"});

			createRadio.addChild("input",	new String[] { "type", "name", "value" },
					new String[] { "radio", "RestoreIdentity" , "false"});
		} else {

			restoreRadio.addChild("input", 	new String[] { "type", "name", "value"},
					new String[] { "radio", "RestoreIdentity" , "true",});

			createRadio.addChild("input",	new String[] { "type", "name", "value", "checked"},
					new String[] { "radio", "RestoreIdentity" , "false", "checked"});
		}


		restoreRadio.addChild("#", l10n().getString("CreateIdentityWizard.Step.ChooseCreateOrRestore.RestoreRadio"));
		l10n().addL10nSubstitution(createRadio.addChild("#"), "CreateIdentityWizard.Step.ChooseCreateOrRestore.CreateRadio", 
				mL10nBoldSubstitutionInput, mL10nBoldSubstitutionOutput);
		
		l10n().addL10nSubstitution(chooseCreateOrRestoreBox.addChild("p"), "CreateIdentityWizard.Step.ChooseCreateOrRestore.Text.2", 
				mL10nBoldSubstitutionInput, mL10nBoldSubstitutionOutput);
	}
	
	private void makeChooseNicknameStep(HTMLNode form) {
		InfoboxNode chooseNameInfoboxNode = getContentBox(l10n().getString("CreateIdentityWizard.Step.ChooseNickname.Header"));
		form.addChild(chooseNameInfoboxNode.outer);

		HTMLNode chooseNameBox = chooseNameInfoboxNode.content;
		chooseNameBox.addChild("p", l10n().getString("CreateIdentityWizard.Step.ChooseNickname.Text.1"));
		chooseNameBox.addChild("p", l10n().getString("CreateIdentityWizard.Step.ChooseNickname.Text.2"));
		HTMLNode p = chooseNameBox.addChild("p");

		if(mNicknameProblem != null) {
			p.addChild("p", "style", "color: red;").
			addChild("#", l10n().getString("CreateIdentityWizard.Step.ChooseNickname.NicknameError") + ": " + mNicknameProblem.getLocalizedMessage());
		}

		String nickname = mRequest.getPartAsStringFailsafe("Nickname", Identity.MAX_NICKNAME_LENGTH);
		
		if(nickname.length() == 0)
			nickname = RandomName.newNickname();
		
		p.addChild("#", l10n().getString("CreateIdentityWizard.Step.ChooseNickname.Nickname") + ": ");
		p.addChild("input",	new String[] { "type", "name", "size", "value" },
				new String[] { "text", "Nickname", "50", nickname });
	}

	private void makeChoosePreferencesStep(HTMLNode form) {
		InfoboxNode choosePrefsInfoboxNode = getContentBox(l10n().getString("CreateIdentityWizard.Step.ChoosePreferences.Header"));
		form.addChild(choosePrefsInfoboxNode.outer);

		HTMLNode choosePrefsBox = choosePrefsInfoboxNode.content;

		InfoboxNode tlInfoboxNode = getContentBox(l10n().getString("CreateIdentityWizard.Step.ChoosePreferences.TrustList.Header"));
		choosePrefsBox.addChild(tlInfoboxNode.outer);

		HTMLNode tlBox = tlInfoboxNode.content;

		HTMLNode p;

		p = tlBox.addChild("p");
		l10n().addL10nSubstitution(p, "CreateIdentityWizard.Step.ChoosePreferences.TrustList.Text1", mL10nBoldSubstitutionInput, mL10nBoldSubstitutionOutput);
		p = tlBox.addChild("p");
		l10n().addL10nSubstitution(p, "CreateIdentityWizard.Step.ChoosePreferences.TrustList.Text2", mL10nBoldSubstitutionInput, mL10nBoldSubstitutionOutput);
		p = tlBox.addChild("p");
		l10n().addL10nSubstitution(p, "CreateIdentityWizard.Step.ChoosePreferences.TrustList.Text3", mL10nBoldSubstitutionInput, mL10nBoldSubstitutionOutput);
		p = tlBox.addChild("p");
		l10n().addL10nSubstitution(p, "CreateIdentityWizard.Step.ChoosePreferences.TrustList.Text4", mL10nBoldSubstitutionInput, mL10nBoldSubstitutionOutput);

		p = tlBox.addChild("p");
		
		// The browser won't submit anything under the name a checkbox if it is not checked.
		// We need "PublishTrustList"=false to be specified though if the checkbox is not checked: The formdata parsing code needs to know that we were at this
		// step already so it doesn't resort to the default value.
		// To get the browser to submit the "false" in case of the checkbox not being checked, we add a hidden input right before the checkbox.
		// If the checkbox is checked, it will overwrite the hidden field. If it is not checked, the hidden field is submitted.
		p.addChild("input",	new String[] { "type", "name", "value" }, new String[] { "hidden", "PublishTrustList", "false" });
		
		if(mIdentityPublishesTrustList == null || mIdentityPublishesTrustList == true) {
			p.addChild("input",	new String[] { "type", "name", "value", "checked" },
					new String[] { "checkbox", "PublishTrustList", "true", "checked"});
		} else {
			p.addChild("input",	new String[] { "type", "name", "value" },
					new String[] { "checkbox", "PublishTrustList", "true" });
		}
		p.addChild("#", l10n().getString("CreateIdentityWizard.Step.ChoosePreferences.TrustList.PublishTrustListCheckbox"));
	}
	
	/**
	 * @return True if the identity was created successfully, false upon error.
	 */
	private boolean makeCreateIdentityStep(HTMLNode wizardBox, HTMLNode form) {
		// It was unclear why we check for POST when I ported this code from Freetalk.
		// After some investigation, it seems like the reason is to ensure that higher-level code has validated the formPassword
		// - it only does so for POST, not for GET.
		// See: https://bugs.freenetproject.org/view.php?id=6210
		// TODO: It might make sense to get rid of the formPasssword mechanism and replace it with session cookies as suggested in the bugtracker entry above.
		if(mRequest.getMethod().equals("POST")) {
			try {
				final OwnIdentity id;
				
				if(mGenerateRandomSSK) 
					id = mWebOfTrust.createOwnIdentity(mIdentityNickname, mIdentityPublishesTrustList, null);
				else if(!mRestoreIdentity)
					id = mWebOfTrust.createOwnIdentity(mIdentityURI, mIdentityNickname, mIdentityPublishesTrustList, null);
				else
					id = mWebOfTrust.restoreOwnIdentity(mIdentityURI);
				
				mToadlet.logOut(mContext); // Log out the current identity in case the user created a second one.
				
				InfoboxNode summaryInfoboxNode = getContentBox(l10n().getString("CreateIdentityWizard.Step.CreateIdentity.Header"));
				 // Add it to wizardBox instead of form because we want to add a "Log in" button to it which is a different form.
				wizardBox.addChild(summaryInfoboxNode.outer);
				
				HTMLNode summaryBox = summaryInfoboxNode.content;
				
				l10n().addL10nSubstitution(summaryBox.addChild("p"), "CreateIdentityWizard.Step.CreateIdentity.Success", 
					mL10nBoldSubstitutionInput, mL10nBoldSubstitutionOutput);
				
				LogInPage.addLoginButton(this, summaryBox, id, mRedirectTarget);
				
				return true;
			}
			catch(Exception e) {
				InfoboxNode errorInfoboxNode = getAlertBox(l10n().getString("CreateIdentityWizard.Step.CreateIdentity.Failure"));
				form.addChild(errorInfoboxNode.outer);
				
				HTMLNode errorBox = errorInfoboxNode.content;
				errorBox.addChild("p", e.getLocalizedMessage());
			}
		}
		
		return false;
	}

	private void makeBackAndContinueButtons(HTMLNode form) {
		if(mCurrentStep.ordinal() > Step.first().ordinal()) {
			form.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "PreviousStepButton", l10n().getString("CreateIdentityWizard.BackButton") });
		}
		
		if(mCurrentStep.ordinal() < Step.last().ordinal())
			form.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "NextStepButton", l10n().getString("CreateIdentityWizard.ContinueButton") });
		else // There was an error creating the identity
			form.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "NextStepButton", l10n().getString("CreateIdentityWizard.RetryButton") });
	}

	/**
	 * Stores the form data which the user has already specified as hidden
	 * elements.
	 * 
	 * @param myForm
	 *            The HTMLNode of the parent form.
	 */
	private void addHiddenFormData(HTMLNode myForm) {
		myForm.addChild("input", new String[] { "type", "name", "value" },
								 new String[] { "hidden", "PreviousStep", Integer.toString(mCurrentStep.ordinal())});
		
		myForm.addChild("input", new String[] { "type", "name", "value" },
		                         new String[] { "hidden", "redirect-target", mRedirectTarget});
		
		if(mCurrentStep != Step.ChooseURI) { // Do not overwrite the visible fields with hidden fields. 
			if(mGenerateRandomSSK != null) {
				myForm.addChild("input",	new String[] { "type", "name", "value" },
											new String[] { "hidden", "GenerateRandomSSK", mGenerateRandomSSK.toString() });
			}
			
			if(mIdentityURI != null) {
				myForm.addChild("input",	new String[] { "type", "name", "value" },
											new String[] { "hidden", "InsertURI", mIdentityURI.toString() });
			}
		}
		
		if(mCurrentStep != Step.ChooseCreateOrRestore) { // Do not overwrite the visible fields with hidden fields.
			if(mRestoreIdentity != null) {
				myForm.addChild("input", new String[] { "type", "name", "value" },
				                         new String[] { "hidden", "RestoreIdentity", mRestoreIdentity.toString() });
			}
		}

		if(mCurrentStep != Step.ChooseNickname) { // Do not overwrite the visible fields with hidden fields
			if(mIdentityNickname != null) {
				myForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "Nickname", mIdentityNickname });
			}
		}

		if(mCurrentStep != Step.ChoosePreferences) { // Do not overwrite the visible fields with hidden fields
			if(mIdentityPublishesTrustList != null) {
				myForm.addChild("input",	new String[] { "type", "name", "value" },
											new String[] { "hidden", "PublishTrustList", mIdentityPublishesTrustList.toString() });
			}
		}
	}

	/**
	 * @param redirectTarget See {@link #CreateIdentityWizard(WebInterfaceToadlet, HTTPRequest, ToadletContext)} and {@link #mRedirectTarget}.
	 */
	private static URI getURI(WebInterface webInterface, String redirectTarget) {
		final URI baseURI = webInterface.getToadlet(CreateIdentityWebInterfaceToadlet.class).getURI();

		try {
			// The parameter which is baseURI.getPath() may not be null, otherwise the last directory is stripped.
			return baseURI.resolve(new URI(null, null, baseURI.getPath(), "redirect-target=" + redirectTarget, null));
		} catch (URISyntaxException e) {
			throw new RuntimeException(e);
		}
	}
	
	/**
	 * @param redirectTarget See {@link #CreateIdentityWizard(WebInterfaceToadlet, HTTPRequest, ToadletContext)} and {@link #mRedirectTarget}.
	 */
	public static void addLinkToCreateIdentityWizard(WebPageImpl page, String redirectTarget) {
		final String createIdentityURI = getURI(page.mWebInterface, redirectTarget).toString();
		
		HTMLNode createIdentityBox = page.addContentBox(page.l10n().getString("CreateIdentityWizard.LinkToCreateIdentityWizardBox.Header"));
		page.l10n().addL10nSubstitution(
		        createIdentityBox,
		        "CreateIdentityWizard.LinkToCreateIdentityWizardBox.Text",
		        new String[] { "link", "/link" },
		        new HTMLNode[] { new HTMLNode("a", "href", createIdentityURI) });
	}
	
	/**
	 * Calls {@link #addLinkToCreateIdentityWizard(WebPageImpl, String)} with redirectTarget={@link #DEFAULT_REDIRECT_TARGET_AFTER_LOGIN}.
	 */
	public static void addLinkToCreateIdentityWizard(WebPageImpl page) {
		addLinkToCreateIdentityWizard(page, DEFAULT_REDIRECT_TARGET_AFTER_LOGIN);
	}
}
