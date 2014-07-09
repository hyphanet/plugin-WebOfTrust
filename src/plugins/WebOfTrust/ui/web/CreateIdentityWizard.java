/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package plugins.WebOfTrust.ui.web;

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
public final class CreateIdentityWizard extends WebPageImpl {
	
	/**
	 * The wizard consists of these stages. A stage is a view of the wizard separated by Back/Continue buttons from the other stages.
	 * Depending on the choice of the user, some stages might be skipped.
	 */
	enum Step {
		ChooseURI,
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
	
	private Step mRequestedStep = Step.first();
	
	/* Step 1: Choose URI */
	private Boolean mGenerateRandomSSK = null; 
	private FreenetURI mIdentityURI = null;
	
	private Exception mInsertURIProblem = null;

	/* Step 2: Choose Nickname */
	private String mIdentityNickname = null;
	
	private Exception mNicknameProblem = null;

	/* Step 3: Set preferences */
	private Boolean mIdentityPublishesTrustList = null;
	

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
		
		HTMLNode wizardBox = addContentBox(l10n().getString("CreateIdentityWizard.CreateIdentityBox.Header"));
		HTMLNode form = pr.addFormChild(wizardBox, mToadlet.getURI().toString(), mToadlet.pageTitle);
		
		boolean finished = false;
		
		switch(mRequestedStep) {
			case ChooseURI: makeChooseURIStep(form); break;
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
		/* ======== Stage 1: Parse the passed form data ====================================================================================== */

		if(mRequest.isPartSet("PreviousStep")) {
			int previousStep = Integer.parseInt(mRequest.getPartAsStringFailsafe("PreviousStep", 1));
			int requestedStep = mRequest.isPartSet("NextStepButton") ? previousStep+1 : previousStep-1;
			
			requestedStep = Math.max(Step.first().ordinal(), requestedStep);
			requestedStep = Math.min(Step.last().ordinal(), requestedStep);
			
			mRequestedStep = Step.values()[requestedStep];
				
		} else
			mRequestedStep = Step.first();
		
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
		if(mRequest.isPartSet("PublishTrustList")) {
			mIdentityPublishesTrustList = mRequest.getPartAsStringFailsafe("PublishTrustList", 5).equals("true");
		}
		
		/* ======== Stage 2: Check for missing data and correct mRequestedStep  =============================================================== */
		
		if(mRequestedStep.ordinal() > Step.ChooseURI.ordinal() && mIdentityURI == null) {
			mRequestedStep = Step.ChooseURI;
		} else if(mRequestedStep.ordinal() > Step.ChooseNickname.ordinal() && mIdentityNickname == null) {
			mRequestedStep = Step.ChooseNickname;
		} else if(mRequestedStep.ordinal() > Step.ChoosePreferences.ordinal() && mIdentityPublishesTrustList == null) {
			mRequestedStep = Step.ChoosePreferences;
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
		final String[] l10nBoldSubstitutionInput = new String[] { "bold" };
		final HTMLNode[] l10nBoldSubstitutionOutput = new HTMLNode[] { HTMLNode.STRONG };

		InfoboxNode choosePrefsInfoboxNode = getContentBox(l10n().getString("CreateIdentityWizard.Step.ChoosePreferences.Header"));
		form.addChild(choosePrefsInfoboxNode.outer);

		HTMLNode choosePrefsBox = choosePrefsInfoboxNode.content;

		InfoboxNode tlInfoboxNode = getContentBox(l10n().getString("CreateIdentityWizard.Step.ChoosePreferences.TrustList.Header"));
		choosePrefsBox.addChild(tlInfoboxNode.outer);

		HTMLNode tlBox = tlInfoboxNode.content;

		HTMLNode p;

		p = tlBox.addChild("p");
		l10n().addL10nSubstitution(p, "CreateIdentityWizard.Step.ChoosePreferences.TrustList.Text1", l10nBoldSubstitutionInput, l10nBoldSubstitutionOutput);
		p = tlBox.addChild("p");
		l10n().addL10nSubstitution(p, "CreateIdentityWizard.Step.ChoosePreferences.TrustList.Text2", l10nBoldSubstitutionInput, l10nBoldSubstitutionOutput);
		p = tlBox.addChild("p");
		l10n().addL10nSubstitution(p, "CreateIdentityWizard.Step.ChoosePreferences.TrustList.Text3", l10nBoldSubstitutionInput, l10nBoldSubstitutionOutput);
		p = tlBox.addChild("p");
		l10n().addL10nSubstitution(p, "CreateIdentityWizard.Step.ChoosePreferences.TrustList.Text4", l10nBoldSubstitutionInput, l10nBoldSubstitutionOutput);

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
		// It was unclear why whe check for POST when I ported this code from Freetalk.
		// After some investigation, it seems like the reason is to ensure that higher-level code has validated the formPassword
		// - it only does so for POST, not for GET.
		// See: https://bugs.freenetproject.org/view.php?id=6210
		// TODO: It might make sense to get rid of the formPasssword mechanism and replace it with session cookies as suggested in the bugtracker entry above.
		if(mRequest.getMethod().equals("POST")) {
			try {
				OwnIdentity id = mWebOfTrust.createOwnIdentity(mIdentityURI, mIdentityNickname, mIdentityPublishesTrustList, null);
				
				mToadlet.logOut(mContext); // Log out the current identity in case the user created a second one.
				
				InfoboxNode summaryInfoboxNode = getContentBox(l10n().getString("CreateIdentityWizard.Step.CreateIdentity.Header"));
				 // Add it to wizardBox instead of form because we want to add a "Log in" button to it which is a different form.
				wizardBox.addChild(summaryInfoboxNode.outer);
				
				HTMLNode summaryBox = summaryInfoboxNode.content;
				
				l10n().addL10nSubstitution(summaryBox.addChild("p"), "CreateIdentityWizard.Step.CreateIdentity.Success", 
					new String[] { "bold" }, new HTMLNode[] { HTMLNode.STRONG });
				
				LogInPage.addLoginButton(this, summaryBox, id);
				
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
		if(mRequestedStep.ordinal() > Step.first().ordinal()) {
			form.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "PreviousStepButton", l10n().getString("CreateIdentityWizard.BackButton") });
		}
		
		if(mRequestedStep.ordinal() < Step.last().ordinal())
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
								 new String[] { "hidden", "PreviousStep", Integer.toString(mRequestedStep.ordinal())});
		
		if(mRequestedStep != Step.ChooseURI) { // Do not overwrite the visible fields with hidden fields. 
			if(mGenerateRandomSSK != null) {
				myForm.addChild("input",	new String[] { "type", "name", "value" },
											new String[] { "hidden", "GenerateRandomSSK", mGenerateRandomSSK.toString() });
			}
			
			if(mIdentityURI != null) {
				myForm.addChild("input",	new String[] { "type", "name", "value" },
											new String[] { "hidden", "InsertURI", mIdentityURI.toString() });
			}
		}

		if(mRequestedStep != Step.ChooseNickname) { // Do not overwrite the visible fields with hidden fields
			if(mIdentityNickname != null) {
				myForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "Nickname", mIdentityNickname });
			}
		}

		if(mRequestedStep != Step.ChoosePreferences) { // Do not overwrite the visible fields with hidden fields
			if(mIdentityPublishesTrustList != null) {
				myForm.addChild("input",	new String[] { "type", "name", "value" },
											new String[] { "hidden", "PublishTrustList", mIdentityPublishesTrustList.toString() });
			}
		}
	}

	public static void addLinkToCreateIdentityWizard(WebPageImpl page) {
		final String createIdentityURI = page.mWebInterface.getToadlet(CreateIdentityWebInterfaceToadlet.class).getURI().toString();
		
		HTMLNode createIdentityBox = page.addContentBox(page.l10n().getString("CreateIdentityWizard.LinkToCreateIdentityWizardBox.Header"));
		page.l10n().addL10nSubstitution(
		        createIdentityBox,
		        "CreateIdentityWizard.LinkToCreateIdentityWizardBox.Text",
		        new String[] { "link", "/link" },
		        new HTMLNode[] { new HTMLNode("a", "href", createIdentityURI) });
	}
}
