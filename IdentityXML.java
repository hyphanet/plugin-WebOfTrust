/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WoT;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map.Entry;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.TransformerFactoryConfigurationError;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.DOMImplementation;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import freenet.keys.FreenetURI;

/**
 * A class for storing identities as XML text and importing them from XML text.
 * 
 * @author xor (xor@freenetproject.org)
 */
public final class IdentityXML {

	private static final int XML_FORMAT_VERSION = 1;
	
	private final WoT mWoT;
	
	/* TODO: Check with a profiler how much memory this takes, do not cache it if it is too much */
	/** Used for parsing the identity XML when decoding identities*/
	private final DocumentBuilder mDocumentBuilder;
	
	/* TODO: Check with a profiler how much memory this takes, do not cache it if it is too much */
	/** Created by mDocumentBuilder, used for building the identity XML DOM when encoding identities */
	private final DOMImplementation mDOM;
	
	/* TODO: Check with a profiler how much memory this takes, do not cache it if it is too much */
	/** Used for storing the XML DOM of encoded identities as physical XML text */
	private final Transformer mSerializer;
	
	/**
	 * 
	 */
	public IdentityXML(WoT myWoT) throws ParserConfigurationException, TransformerConfigurationException, TransformerFactoryConfigurationError {
		mWoT = myWoT;
		
		DocumentBuilderFactory xmlFactory = DocumentBuilderFactory.newInstance();
		xmlFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
		// DOM parser uses .setAttribute() to pass to underlying Xerces
		xmlFactory.setAttribute("http://apache.org/xml/features/disallow-doctype-decl", true);
		mDocumentBuilder = xmlFactory.newDocumentBuilder(); 
		mDOM = mDocumentBuilder.getDOMImplementation();
		
		mSerializer = TransformerFactory.newInstance().newTransformer();
		mSerializer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
		mSerializer.setOutputProperty(OutputKeys.INDENT, "yes"); /* FIXME: Set to no before release. */
		mSerializer.setOutputProperty(OutputKeys.STANDALONE, "no");
	}
	
	public void encode(OwnIdentity myIdentity, OutputStream os) throws TransformerException {
		Document xmlDoc = mDOM.createDocument(null, WoT.WOT_NAME, null);
		Element rootElement = xmlDoc.getDocumentElement();
		
		/* Create the identity tag */
		
		Element identityTag = xmlDoc.createElement("Identity");
		identityTag.setAttribute("Version", Integer.toString(XML_FORMAT_VERSION)); /* Version of the XML format */
		
		synchronized(myIdentity) {
			identityTag.setAttribute("Name", myIdentity.getNickName());
			identityTag.setAttribute("PublishesTrustList", Boolean.toString(myIdentity.doesPublishTrustList()));
			
			/* Create the context tags */
			
			for(String context : myIdentity.getContexts()) {
				Element contextTag = xmlDoc.createElement("Context");
				contextTag.setAttribute("Name", context);
				identityTag.appendChild(contextTag);
			}
			
			/* Create the property tags */
			
			for(Entry<String, String> property : myIdentity.getProperties().entrySet()) {
				Element propertyTag = xmlDoc.createElement("Property");
				propertyTag.setAttribute("Name", property.getKey());
				propertyTag.setAttribute("Value", property.getValue());
				identityTag.appendChild(propertyTag);
			}
			
			/* Create the trust list tag and its trust tags */
			
			Element trustListTag = xmlDoc.createElement("TrustList");
			for(Trust trust : myIdentity.getGivenTrusts(mWoT.getDB())) {
				Element trustTag = xmlDoc.createElement("Trust");
				trustTag.setAttribute("Identity", trust.getTrustee().getRequestURI().toString());
				trustTag.setAttribute("Value", Byte.toString(trust.getValue()));
				trustTag.setAttribute("Comment", trust.getComment());
				trustListTag.appendChild(trustTag);
			}
			identityTag.appendChild(trustListTag);
		}
		
		rootElement.appendChild(identityTag);

		DOMSource domSource = new DOMSource(xmlDoc);
		StreamResult resultStream = new StreamResult(os);
		mSerializer.transform(domSource, resultStream);
	}
	
	/**
	 * Imports a identity XML file into the given web of trust. This includes:
	 * - The identity itself and its attributes
	 * - The trust list of the identity, if it has published one in the XML.
	 * 
	 * If the identity does not exist yet, it is created. If it does, the existing one is updated.
	 * 
	 * @param myWoT The web of trust where to store the identity and trust list at.
	 * @param xmlInputStream The input stream containing the XML.
	 * @throws Exception 
	 * @throws Exception
	 */
	public void decode(FreenetURI identityURI, InputStream xmlInputStream) throws Exception  { 
		Document xml = mDocumentBuilder.parse(xmlInputStream);
		
		Element identityElement = (Element)xml.getElementsByTagName("Identity").item(0);
		
		if(Integer.parseInt(identityElement.getAttribute("Version")) > XML_FORMAT_VERSION)
			throw new Exception("Version " + identityElement.getAttribute("Version") + " > " + XML_FORMAT_VERSION);
	}
}
