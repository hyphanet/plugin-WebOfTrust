/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WoT;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
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
import org.w3c.dom.NodeList;

import plugins.WoT.exceptions.UnknownIdentityException;

import com.db4o.ObjectContainer;

import freenet.keys.FreenetURI;
import freenet.support.Logger;

/**
 * A class for storing identities as XML text and importing them from XML text.
 * 
 * @author xor (xor@freenetproject.org)
 */
public final class IdentityXML {

	private static final int XML_FORMAT_VERSION = 1;
	
	private final WoT mWoT;
	
	private final ObjectContainer mDB;
	
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
		mDB = mWoT.getDB();
		
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
	
	public void exportOwnIdentity(OwnIdentity myIdentity, OutputStream os) throws TransformerException {
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
			for(Trust trust : myIdentity.getGivenTrusts(mDB)) {
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
	public void importIdentity(FreenetURI identityURI, InputStream xmlInputStream) throws Exception  { 
		Document xml = mDocumentBuilder.parse(xmlInputStream);
		
		Element identityElement = (Element)xml.getElementsByTagName("Identity").item(0);
		
		if(Integer.parseInt(identityElement.getAttribute("Version")) > XML_FORMAT_VERSION)
			throw new Exception("Version " + identityElement.getAttribute("Version") + " > " + XML_FORMAT_VERSION);
		
		String identityName = identityElement.getAttribute("Name");
		boolean identityPublishesTrustList = Boolean.parseBoolean(identityElement.getAttribute("PublishesTrustList"));
		
		ArrayList<String> identityContexts = new ArrayList<String>(4);
		NodeList contextList = identityElement.getElementsByTagName("Context");
		for(int i = 0; i < contextList.getLength(); ++i) {
			Element contextElement = (Element)contextList.item(i);
			identityContexts.add(contextElement.getAttribute("Name"));
		}
		
		HashMap<String, String> identityProperties = new HashMap<String, String>(8);
		NodeList propertyList = identityElement.getElementsByTagName("Property");
		for(int i = 0; i < propertyList.getLength(); ++i) {
			Element propertyElement = (Element)propertyList.item(i);
			identityProperties.put(propertyElement.getAttribute("Name"), propertyElement.getAttribute("Value"));
		}
		
		/* We tried to parse as much as we can without synchronization before we lock everything :) */
		
		synchronized(mWoT) {
			Identity identity;
			boolean isNewIdentity = false;
			
			try {
				identity = Identity.getByURI(mDB, identityURI);
				identity.setRequestURI(identityURI);
				
				try {
					identity.setNickname(identityName);
				}
				catch(Exception e) {
					Logger.error(identityURI, "setNickname() failed.", e);
				}
			}
			catch(UnknownIdentityException e) {
				identity = new Identity(identityURI, identityName, identityPublishesTrustList);
				isNewIdentity = true;
			}
			
			synchronized(identity) {
				/* FIXME: How to do this? Notice: The commit() should always be done here until we know how to do the "transactionIsRunning()",
				 * however we commit() anyway after setting contexts and properties, so it is commented out.
				 * 
				if(transactionIsRunning()) {
					mDB.commit();
					Logger.error(this, "A transaction is still pending during identity import!");
				}
				*/

				try { /* Failure of context importing should not make an identity disappear, therefore we catch exceptions. */
					identity.setContexts(mDB, identityContexts);
				}
				catch(Exception e) {
					Logger.error(identityURI, "setContexts() failed.", e);
				}

				try { /* Failure of property importing should not make an identity disappear, therefore we catch exceptions. */
					identity.setProperties(mDB, identityProperties);
				}
				catch(Exception e) {
					Logger.error(identityURI, "setProperties() failed", e);
				}
				
				/* We store the identity even if it's trust list import fails - identities should not disappear then. */
				identity.storeAndCommit(mDB);
				
				if(identityPublishesTrustList) {
					/* This try block is for rolling back in catch() if an exception is thrown during trust list import.
					 * Our policy is: We either import the whole trust list or nothing. We should not bias the trust system by allowing
					 * the import of partial trust lists. FIXME: Is it possible to ensure in catch() that there was no commit()
					 * done in one of the functions which was called in the try{} block?  */
					try {
						boolean trusteeCreationAllowed = identity.getBestScore(mDB) > 0;

						Element trustListElement = (Element)identityElement.getElementsByTagName("TrustList").item(0);
						NodeList trustList = trustListElement.getElementsByTagName("Trust");
						for(int i = 0; i < trustList.getLength(); ++i) {
							Element trustElement = (Element)trustList.item(i);

							String trusteeURI = trustElement.getAttribute("Identity");
							byte trustValue = Byte.parseByte(trustElement.getAttribute("Value"));
							String trustComment = trustElement.getAttribute("Comment");

							Identity trustee = null;
							try {
								trustee = Identity.getByURI(mDB, trusteeURI);
							}
							catch(UnknownIdentityException e) {
								if(trusteeCreationAllowed) { /* We only create trustees if the truster has a positive score */
									trustee = new Identity(trusteeURI, null, false);
									mDB.store(trustee);
									mWoT.getIdentityFetcher().fetch(trustee);
								}
							}

							if(trustee != null)
								identity.setTrust(mDB, trustee, trustValue, trustComment);
						}

						if(!isNewIdentity) { /* Delete trust objects of trustees which were removed from the trust list */
							for(Trust trust : Trust.getTrustsOlderThan(mDB, identityURI.getEdition())) {
								identity.removeTrust(mDB, trust);
							}
						}

						identity.storeAndCommit(mDB);
					}
					
					catch(Exception e) {
						mDB.rollback();
						Logger.error(identityURI, "Importing trust list failed.", e);
					}
				}
			}
		}
	}
}
