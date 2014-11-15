/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;

import javax.xml.transform.TransformerException;

import org.xml.sax.SAXException;

import plugins.WebOfTrust.exceptions.InvalidParameterException;
import plugins.WebOfTrust.exceptions.UnknownIdentityException;
import plugins.WebOfTrust.introduction.IntroductionPuzzle;
import freenet.keys.FreenetURI;

/**
 * A unit test for class {@link XMLTransformer}.
 * 
 * @author xor (xor@freenetproject.org)
 */
public class XMLTransformerTest extends DatabaseBasedTest {

	private XMLTransformer mTransformer;
	
	private OwnIdentity mOwnIdentity;
	
	@Override
	protected void setUp() throws Exception {
		super.setUp();
	
		mTransformer = new XMLTransformer(mWoT);
		
		mOwnIdentity = mWoT.createOwnIdentity(
				new FreenetURI("SSK@egaZBiTrPGsiLVBJGT91MOX5jtC6pFIDFDyjt3FcsRI,GDQlSg9ncBBF8XIS-cXYb-LM9JxE3OiSydyOaZgCS4k,AQECAAE/"), // insert URI
				// "SSK@lY~N0Nk5NQpt6brGgtckFHPY11GzgkDn4VDszL6fwPg,GDQlSg9ncBBF8XIS-cXYb-LM9JxE3OiSydyOaZgCS4k,AQACAAE/" // request URI
				"test-identity", true, "Freetalk");
	}

	public void testExportOwnIdentity() {
		//fail("Not yet implemented"); // TODO
		
		// TODO: Test that we do not export the trust list if trust list export is disabled.
	}
		 
	/**
	 * XMLTransformer has a constant called MAX_IDENTITY_XML_TRUSTEE_AMOUNT. 
	 * This function tests whether this amount of identities actually fits into an XML file if all data-fields are maxed out to their limit.
	 */
	public void testMaximalOwnIdentityXMLSize() throws MalformedURLException, InvalidParameterException, TransformerException {
		final OwnIdentity ownId = mWoT.createOwnIdentity(
				new FreenetURI("USK@ZTeIa1g4T3OYCdUFfHrFSlRnt5coeFFDCIZxWSb7abs,ZP4aASnyZax8nYOvCOlUebegsmbGQIXfVzw7iyOsXEc,AQECAAE/WebOfTrust/0"), // insert URI
				// "USK@sdFxM0Z4zx4-gXhGwzXAVYvOUi6NRfdGbyJa797bNAg,ZP4aASnyZax8nYOvCOlUebegsmbGQIXfVzw7iyOsXEc,AQACAAE/WebOfTrust/0" // request URI
				getRandomLatinString(OwnIdentity.MAX_NICKNAME_LENGTH), true, getRandomLatinString(OwnIdentity.MAX_CONTEXT_NAME_LENGTH));
		
		final int initialContextCount = ownId.getContexts().size();
		
		for(int i=0; i < OwnIdentity.MAX_CONTEXT_AMOUNT-initialContextCount; ++i)
			ownId.addContext(getRandomLatinString(OwnIdentity.MAX_CONTEXT_NAME_LENGTH));
		
		final int initialPropertyCount = ownId.getProperties().size();
		
		for(int i=0; i < OwnIdentity.MAX_PROPERTY_AMOUNT-initialPropertyCount; ++i)
			ownId.setProperty(getRandomLatinString(OwnIdentity.MAX_PROPERTY_NAME_LENGTH), getRandomLatinString(OwnIdentity.MAX_PROPERTY_VALUE_LENGTH));
		
		ownId.storeAndCommit();
		
		ByteArrayOutputStream os;
		
		/* When adjusting the size limit of the XML file (XMLTransformer.MAX_IDENTITY_XML_BYTE_SIZE), you can use the commented-out lines
		 * for obtaining the maximal amount of identities which will fit. Of course you will have to remove the for() loop after them.
		 * Also, you have to comment out the part of XMLTransfomer which limits the amount of identities in the XML. */
		//int count = 0;
		//do {
		//	final Identity trustee = new Identity(mWoT,getRandomRequestURI(), 
		//									getFilledRandomString(Identity.MAX_NICKNAME_LENGTH), true); 
		//	trustee.storeAndCommit();
		//	mWoT.setTrust(ownId, trustee, (byte)100, getFilledRandomString(Trust.MAX_TRUST_COMMENT_LENGTH));
		//	
		//	++count;
		//	os = new ByteArrayOutputStream();	
		//	mTransformer.exportOwnIdentity(ownId, os);
		//} while(os.toByteArray().length < XMLTransformer.MAX_IDENTITY_XML_BYTE_SIZE);
		//System.out.println("Number of identities which fit into trust list XML: " + (count-1));
		
		/* Remove the following when using the commented out lines above */
		mWoT.beginTrustListImport();
		for(int i=0; i < XMLTransformer.MAX_IDENTITY_XML_TRUSTEE_AMOUNT; ++i) {
			final Identity trustee = new Identity(mWoT,getRandomRequestURI(), 
											getRandomLatinString(Identity.MAX_NICKNAME_LENGTH), true); 
			trustee.storeAndCommit();
			mWoT.setTrust(ownId, trustee, (byte)100, getRandomLatinString(Trust.MAX_TRUST_COMMENT_LENGTH));
		}
		mWoT.finishTrustListImport();
		
		os = new ByteArrayOutputStream(XMLTransformer.MAX_IDENTITY_XML_BYTE_SIZE);
		mTransformer.exportOwnIdentity(ownId, os);
		
		assertTrue(os.toByteArray().length <= XMLTransformer.MAX_IDENTITY_XML_BYTE_SIZE);
		/* End of "remove-this" part */
	}

	public void testImportIdentity() throws Exception {
		//fail("Not yet implemented"); // TODO
	}

	public void testExportIntroduction() throws MalformedURLException, InvalidParameterException, TransformerException {
		ByteArrayOutputStream os = new ByteArrayOutputStream();
		mTransformer.exportIntroduction(mOwnIdentity, os);
		
		String expectedXML = "<?xml version=\"1.1\" encoding=\"UTF-8\" standalone=\"no\"?>"
							+ "<" + WebOfTrustInterface.WOT_NAME + " Version=\"" + Version.getRealVersion() + "\">" 
							+ "<IdentityIntroduction Version=\"1\">"
							+ "<Identity URI=\"USK@lY~N0Nk5NQpt6brGgtckFHPY11GzgkDn4VDszL6fwPg,GDQlSg9ncBBF8XIS-cXYb-LM9JxE3OiSydyOaZgCS4k,AQACAAE/" + WebOfTrustInterface.WOT_NAME + "/0\"/>"
							+ "</IdentityIntroduction>"
							+ "</" + WebOfTrustInterface.WOT_NAME + ">";
		
		assertEquals(expectedXML, os.toString().replaceAll("[\n\r]", ""));
	}

	public void testImportIntroduction() throws SAXException, IOException, InvalidParameterException {
		String introductionXML = "<?xml version=\"1.1\" encoding=\"UTF-8\" standalone=\"no\"?>"
			+ "<" + WebOfTrustInterface.WOT_NAME + " Version=\"" + Version.getRealVersion() + "\">" 
			+ "<IdentityIntroduction Version=\"1\">"
			+ "<Identity URI=\"USK@HH~V2XmCbZp~738qtE67jUg1M5L5flVvQfc2bYpE1o4,c8H39jkp08cao-EJVTV~rISHlcMnlTlpNFICzL4gmZ4,AQACAAE/" + WebOfTrustInterface.WOT_NAME + "/0\"/>"
			+ "</IdentityIntroduction>"
			+ "</" + WebOfTrustInterface.WOT_NAME + ">";
		
		FreenetURI identityURI = new FreenetURI("USK@HH~V2XmCbZp~738qtE67jUg1M5L5flVvQfc2bYpE1o4,c8H39jkp08cao-EJVTV~rISHlcMnlTlpNFICzL4gmZ4,AQACAAE/" + WebOfTrustInterface.WOT_NAME + "/0");
		
		ByteArrayInputStream is = new ByteArrayInputStream(introductionXML.getBytes("UTF-8"));
		
		try {
			mOwnIdentity.removeContext(IntroductionPuzzle.INTRODUCTION_CONTEXT); mOwnIdentity.storeAndCommit();
			
			mTransformer.importIntroduction(mOwnIdentity, is);
			fail("XMLTransformer.importIntroduction() should not import an introduction if the puzzle publisher does not allow introduction anymore.");
		} catch (InvalidParameterException e) {
			try {
				mWoT.getIdentityByURI(identityURI);
				fail("XMLTransformer.importIntroduction() failed but the new identity exists.");
			}
			catch(UnknownIdentityException ex) {
				
			}
		}
		
		is = new ByteArrayInputStream(introductionXML.getBytes("UTF-8"));
		
		mOwnIdentity.addContext(IntroductionPuzzle.INTRODUCTION_CONTEXT); mOwnIdentity.storeAndCommit();
		
		Identity importedIdentity = mTransformer.importIntroduction(mOwnIdentity, is);
		
		assertEquals(importedIdentity.getRequestURI(), identityURI);
		
		assertEquals(importedIdentity.getNickname(), null);
		
		assertEquals(importedIdentity.doesPublishTrustList(), false);
		
		assertEquals(importedIdentity.getContexts().size(), 0);
		
		assertEquals(importedIdentity.getProperties().size(), 0);
	}

	public void testExportIntroductionPuzzle() {
		//fail("Not yet implemented"); // TODO
	}

	public void testImportIntroductionPuzzle() {
		//fail("Not yet implemented"); // TODO
	}

}
