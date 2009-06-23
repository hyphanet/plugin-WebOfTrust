/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WoT.test;

import java.io.ByteArrayOutputStream;
import java.net.MalformedURLException;

import javax.xml.transform.TransformerException;

import plugins.WoT.OwnIdentity;
import plugins.WoT.XMLTransformer;
import plugins.WoT.exceptions.InvalidParameterException;

/**
 * A unit test for class {@link XMLTransformer}.
 * 
 * @author xor (xor@freenetproject.org)
 */
public class XMLTransformerTest extends DatabaseBasedTest {

	private XMLTransformer mTransformer;
	
	protected void setUp() throws Exception {
		super.setUp();
	
		mTransformer = new XMLTransformer(mWoT);
	}

	public void testExportOwnIdentity() {
		//fail("Not yet implemented"); // TODO
	}

	public void testImportIdentity() {
		//fail("Not yet implemented"); // TODO
	}

	public void testExportIntroduction() throws MalformedURLException, InvalidParameterException, TransformerException {
		
		OwnIdentity identity = mWoT.createOwnIdentity(
				"SSK@egaZBiTrPGsiLVBJGT91MOX5jtC6pFIDFDyjt3FcsRI,GDQlSg9ncBBF8XIS-cXYb-LM9JxE3OiSydyOaZgCS4k,AQECAAE/",
				"SSK@lY~N0Nk5NQpt6brGgtckFHPY11GzgkDn4VDszL6fwPg,GDQlSg9ncBBF8XIS-cXYb-LM9JxE3OiSydyOaZgCS4k,AQACAAE/",
				"test-identity", true, "Freetalk");
		
		ByteArrayOutputStream os = new ByteArrayOutputStream();
		mTransformer.exportIntroduction(identity, os);
		
		String expectedXML = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>"
							+ "<WoT-testing>" 
							+ "<IdentityIntroduction Version=\"1\">"
							+ "<Identity URI=\"USK@lY~N0Nk5NQpt6brGgtckFHPY11GzgkDn4VDszL6fwPg,GDQlSg9ncBBF8XIS-cXYb-LM9JxE3OiSydyOaZgCS4k,AQACAAE/WoT/0\"/>"
							+ "</IdentityIntroduction>"
							+ "</WoT-testing>";
		
		assertEquals(expectedXML, os.toString().replaceAll("[\n\r]", ""));
	}

	public void testImportIntroduction() {
		//fail("Not yet implemented"); // TODO
	}

	public void testExportIntroductionPuzzle() {
		//fail("Not yet implemented"); // TODO
	}

	public void testImportIntroductionPuzzle() {
		//fail("Not yet implemented"); // TODO
	}

}
