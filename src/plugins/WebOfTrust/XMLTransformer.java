/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map.Entry;
import java.util.TimeZone;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.DOMImplementation;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import plugins.WebOfTrust.Identity.FetchState;
import plugins.WebOfTrust.exceptions.InvalidParameterException;
import plugins.WebOfTrust.exceptions.NotInTrustTreeException;
import plugins.WebOfTrust.exceptions.NotTrustedException;
import plugins.WebOfTrust.exceptions.UnknownIdentityException;
import plugins.WebOfTrust.introduction.IntroductionPuzzle;

import com.db4o.ext.ExtObjectContainer;

import freenet.keys.FreenetURI;
import freenet.support.Base64;
import freenet.support.IllegalBase64Exception;
import freenet.support.Logger;

/**
 * This class handles all XML creation and parsing of the WoT plugin, that is import and export of identities, identity introductions 
 * and introduction puzzles. The code for handling the XML related to identity introduction is not in a separate class in the WoT.Introduction
 * package so that we do not need to create multiple instances of the XML parsers / pass the parsers to the other class. 
 * 
 * @author xor (xor@freenetproject.org)
 */
public final class XMLTransformer {

	private static final int XML_FORMAT_VERSION = 1;
	
	private static final int INTRODUCTION_XML_FORMAT_VERSION = 1; 
	
	/**
	 * Used by the IntroductionServer to limit the size of fetches to prevent DoS..
	 * The typical size of an identity introduction can be observed at {@link XMLTransformerTest}.
	 */
	public static final int MAX_INTRODUCTION_BYTE_SIZE = 1 * 1024;
	
	/**
	 * Used by the IntroductionClient to limit the size of fetches to prevent DoS. 
	 * The typical size of an introduction puzzle can be observed at {@link XMLTransformerTest}.
	 */
	public static final int MAX_INTRODUCTIONPUZZLE_BYTE_SIZE = 16 * 1024;
	
	/**
	 * Maximal size of an identity XML file.
	 * FIXME: Reduce this to about 256KiB again once bug 0005406 is fixed. Also adjust MAX_IDENTITY_XML_TRUSTEE_AMOUNT then.
	 */
	public static final int MAX_IDENTITY_XML_BYTE_SIZE = 1024 * 1024;
	
	/**
	 * The maximal amount of trustees which will be added to the XML.
	 * This value has been computed by XMLTransformerTest.testMaximalOwnIdentityXMLSize - that function is able to generate a XML file with all
	 * data fields (nicknames, comments, etc) maxed out and add identities until it exceeds the maximal XML byte size.
	 * In other words: If you ever need to re-adjust this value to fit into a new MAX_IDENTITY_XML_BYTE_SIZE, look at XMLTransformerTest.testMaximalOwnIdentityXMLSize.
	 */
	public static final int MAX_IDENTITY_XML_TRUSTEE_AMOUNT = 512;
	
	private final WebOfTrust mWoT;
	
	private final ExtObjectContainer mDB;
	
	/* TODO: Check with a profiler how much memory this takes, do not cache it if it is too much */
	/** Used for parsing the identity XML when decoding identities*/
	private final DocumentBuilder mDocumentBuilder;
	
	/* TODO: Check with a profiler how much memory this takes, do not cache it if it is too much */
	/** Created by mDocumentBuilder, used for building the identity XML DOM when encoding identities */
	private final DOMImplementation mDOM;
	
	/* TODO: Check with a profiler how much memory this takes, do not cache it if it is too much */
	/** Used for storing the XML DOM of encoded identities as physical XML text */
	private final Transformer mSerializer;
	
	private final SimpleDateFormat mDateFormat;
	
	/* These booleans are used for preventing the construction of log-strings if logging is disabled (for saving some cpu cycles) */
	
	private static volatile boolean logDEBUG = false;
	private static volatile boolean logMINOR = false;
	
	static {
		Logger.registerClass(XMLTransformer.class);
	}

	
	/**
	 * Initializes the XML creator & parser and caches those objects in the new IdentityXML object so that they do not have to be initialized
	 * each time an identity is exported/imported.
	 */
	public XMLTransformer(WebOfTrust myWoT) {
		mWoT = myWoT;
		mDB = mWoT.getDatabase();
		
		try {
			DocumentBuilderFactory xmlFactory = DocumentBuilderFactory.newInstance();
			xmlFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
			// DOM parser uses .setAttribute() to pass to underlying Xerces
			xmlFactory.setAttribute("http://apache.org/xml/features/disallow-doctype-decl", true);
			mDocumentBuilder = xmlFactory.newDocumentBuilder(); 
			mDOM = mDocumentBuilder.getDOMImplementation();

			mSerializer = TransformerFactory.newInstance().newTransformer();
			mSerializer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
			mSerializer.setOutputProperty(OutputKeys.INDENT, "yes"); // TODO: Disable as soon as bug 0004850 is fixed.
			mSerializer.setOutputProperty(OutputKeys.STANDALONE, "no");
			
			mDateFormat = new SimpleDateFormat("yyyy-MM-dd");
			mDateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
		}
		catch(Exception e) {
			throw new RuntimeException(e);
		}
	}
	
	public void exportOwnIdentity(OwnIdentity identity, OutputStream os) throws TransformerException {
		Document xmlDoc;
		synchronized(mDocumentBuilder) { // TODO: Figure out whether the DocumentBuilder is maybe synchronized anyway 
			xmlDoc = mDOM.createDocument(null, WebOfTrust.WOT_NAME, null);
		}
		
		// 1.0 does not support all Unicode characters which the String class supports. To prevent us from having to filter all Strings, we use 1.1
		xmlDoc.setXmlVersion("1.1");
		
		Element rootElement = xmlDoc.getDocumentElement();
		
		// We include the WoT version to have an easy way of handling bogus XML which might be created by bugged versions.
		rootElement.setAttribute("Version", Long.toString(Version.getRealVersion()));
		
		/* Create the identity Element */
		
		Element identityElement = xmlDoc.createElement("Identity");
		identityElement.setAttribute("Version", Integer.toString(XML_FORMAT_VERSION)); /* Version of the XML format */
		
		synchronized(mWoT) {
			identityElement.setAttribute("Name", identity.getNickname());
			identityElement.setAttribute("PublishesTrustList", Boolean.toString(identity.doesPublishTrustList()));
			
			/* Create the context Elements */
			
			for(String context : identity.getContexts()) {
				Element contextElement = xmlDoc.createElement("Context");
				contextElement.setAttribute("Name", context);
				identityElement.appendChild(contextElement);
			}
			
			/* Create the property Elements */
			
			for(Entry<String, String> property : identity.getProperties().entrySet()) {
				Element propertyElement = xmlDoc.createElement("Property");
				propertyElement.setAttribute("Name", property.getKey());
				propertyElement.setAttribute("Value", property.getValue());
				identityElement.appendChild(propertyElement);
			}
			
			/* Create the trust list Element and its trust Elements */

			if(identity.doesPublishTrustList()) {
				Element trustListElement = xmlDoc.createElement("TrustList");

				int trustCount = 0;
				for(Trust trust : mWoT.getGivenTrustsSortedDescendingByLastSeen(identity)) {
					if(++trustCount > MAX_IDENTITY_XML_TRUSTEE_AMOUNT) {
						Logger.normal(this, "Amount of trustees exceeded " + MAX_IDENTITY_XML_TRUSTEE_AMOUNT + ", not adding any more to trust list of " + identity);
						break;
					}
					
					/* We should make very sure that we do not reveal the other own identity's */
					if(trust.getTruster() != identity) 
						throw new RuntimeException("Error in WoT: It is trying to export trust values of someone else in the trust list " +
								"of " + identity + ": Trust value from " + trust.getTruster() + "");

					Element trustElement = xmlDoc.createElement("Trust");
					trustElement.setAttribute("Identity", trust.getTrustee().getRequestURI().toString());
					trustElement.setAttribute("Value", Byte.toString(trust.getValue()));
					trustElement.setAttribute("Comment", trust.getComment());
					trustListElement.appendChild(trustElement);
				}
				identityElement.appendChild(trustListElement);
			}
		}
		
		rootElement.appendChild(identityElement);

		DOMSource domSource = new DOMSource(xmlDoc);
		StreamResult resultStream = new StreamResult(os);
		synchronized(mSerializer) { // TODO: Figure out whether the Serializer is maybe synchronized anyway
			mSerializer.transform(domSource, resultStream);
		}
	}
	
	/**
	 * Workaround class for:
	 * https://bugs.freenetproject.org/view.php?id=4850
	 * http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=7031732
	 * 
	 * @author 	NowWhat@0plokJYoIwsHORk6RlUVPRA-HJ3-Cg7SjJP4S2fWEnw.freetalk
	 */
	public class OneBytePerReadInputStream extends FilterInputStream {

		public OneBytePerReadInputStream(InputStream in) {
			super(in);
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			return super.read(b, off, len<=1 ? len : 1);
		}

	}
	
	private static final class ParsedIdentityXML {
		static final class TrustListEntry {
			final FreenetURI mTrusteeURI;
			final byte mTrustValue;
			final String mTrustComment;
			
			public TrustListEntry(FreenetURI myTrusteeURI, byte myTrustValue, String myTrustComment) {
				mTrusteeURI = myTrusteeURI;
				mTrustValue = myTrustValue;
				mTrustComment = myTrustComment;
			}
		}
		
		Exception parseError = null;
		
		String identityName = null;
		Boolean identityPublishesTrustList = null;
		ArrayList<String> identityContexts = null;
		HashMap<String, String> identityProperties = null;
		ArrayList<TrustListEntry> identityTrustList = null;
		
		public ParsedIdentityXML() {
			
		}
	}
	
	/**
	 * @param xmlInputStream An InputStream which must not return more than {@link MAX_IDENTITY_XML_BYTE_SIZE} bytes.
	 */
	private ParsedIdentityXML parseIdentityXML(InputStream xmlInputStream) throws IOException {
		Logger.normal(this, "Parsing identity XML...");
		
		xmlInputStream = new OneBytePerReadInputStream(xmlInputStream); // Workaround for Java bug, see the stream class for explanation

		// May not be accurate by definition of available(). So the JavaDoc requires the callers to obey the size limit, this is a double-check.
		if(xmlInputStream.available() > MAX_IDENTITY_XML_BYTE_SIZE)
			throw new IllegalArgumentException("XML contains too many bytes: " + xmlInputStream.available());
		
		final ParsedIdentityXML result = new ParsedIdentityXML();
		
		try {			
			Document xmlDoc;
			synchronized(mDocumentBuilder) { // TODO: Figure out whether the DocumentBuilder is maybe synchronized anyway
				xmlDoc = mDocumentBuilder.parse(xmlInputStream);
			}
	
			final Element identityElement = (Element)xmlDoc.getElementsByTagName("Identity").item(0);
			
			if(Integer.parseInt(identityElement.getAttribute("Version")) > XML_FORMAT_VERSION)
				throw new Exception("Version " + identityElement.getAttribute("Version") + " > " + XML_FORMAT_VERSION);
			
			result.identityName = identityElement.getAttribute("Name");
			result.identityPublishesTrustList = Boolean.parseBoolean(identityElement.getAttribute("PublishesTrustList"));
			 
			final NodeList contextList = identityElement.getElementsByTagName("Context");
			result.identityContexts = new ArrayList<String>(contextList.getLength() + 1);
			for(int i = 0; i < contextList.getLength(); ++i) {
				Element contextElement = (Element)contextList.item(i);
				result.identityContexts.add(contextElement.getAttribute("Name"));
			}
			
			final NodeList propertyList = identityElement.getElementsByTagName("Property");
			result.identityProperties = new HashMap<String, String>(propertyList.getLength() * 2);
			for(int i = 0; i < propertyList.getLength(); ++i) {
				Element propertyElement = (Element)propertyList.item(i);
				result.identityProperties.put(propertyElement.getAttribute("Name"), propertyElement.getAttribute("Value"));
			}
			
			if(result.identityPublishesTrustList) {
				final Element trustListElement = (Element)identityElement.getElementsByTagName("TrustList").item(0);
				final NodeList trustList = trustListElement.getElementsByTagName("Trust");
				result.identityTrustList = new ArrayList<ParsedIdentityXML.TrustListEntry>(trustList.getLength() + 1);
				for(int i = 0; i < trustList.getLength(); ++i) {
					Element trustElement = (Element)trustList.item(i);
	
					result.identityTrustList.add(new ParsedIdentityXML.TrustListEntry(
								new FreenetURI(trustElement.getAttribute("Identity")),
								Byte.parseByte(trustElement.getAttribute("Value")),
								trustElement.getAttribute("Comment")
							));
				}
			}
		} catch(Exception e) {
			result.parseError = e;
		}
		
		Logger.normal(this, "Finished parsing identity XML.");
		
		return result;
	}
	
	/**
	 * Imports a identity XML file into the given web of trust. This includes:
	 * - The identity itself and its attributes
	 * - The trust list of the identity, if it has published one in the XML.
	 * 
	 * @param xmlInputStream The input stream containing the XML.
	 */
	public void importIdentity(FreenetURI identityURI, InputStream xmlInputStream) {
		try { // Catch import problems so we can mark the edition as parsing failed
		// We first parse the XML without synchronization, then do the synchronized import into the WebOfTrust		
		final ParsedIdentityXML xmlData = parseIdentityXML(xmlInputStream);
		
		synchronized(mWoT) {
		synchronized(mWoT.getIdentityFetcher()) {
			final Identity identity = mWoT.getIdentityByURI(identityURI);
			
			Logger.normal(this, "Importing parsed XML for " + identity);
			
			long newEdition = identityURI.getEdition();
			if(identity.getEdition() > newEdition) {
				if(logDEBUG) Logger.debug(this, "Fetched an older edition: current == " + identity.getEdition() + "; fetched == " + identityURI.getEdition());
				return;
			} else if(identity.getEdition() == newEdition) {
				if(identity.getCurrentEditionFetchState() == FetchState.Fetched) {
					if(logDEBUG) Logger.debug(this, "Fetched current edition which is marked as fetched already, not importing: " + identityURI);
					return;
				} else if(identity.getCurrentEditionFetchState() == FetchState.ParsingFailed) {
					Logger.normal(this, "Re-fetched current-edition which was marked as parsing failed: " + identityURI);
				}
			}
				
			// We throw parse errors AFTER checking the edition number: If this XML was outdated anyway, we don't have to throw.
			if(xmlData.parseError != null)
				throw xmlData.parseError;
				
			
			synchronized(Persistent.transactionLock(mDB)) {
				try { // Transaction rollback block
					identity.setEdition(newEdition); // The identity constructor only takes the edition number as a hint, so we must store it explicitly.
					boolean didPublishTrustListPreviously = identity.doesPublishTrustList();
					identity.setPublishTrustList(xmlData.identityPublishesTrustList);
					
					try {
						identity.setNickname(xmlData.identityName);
					}
					catch(Exception e) {
						/* Nickname changes are not allowed, ignore them... */
						Logger.warning(this, "setNickname() failed.", e);
					}
	
					try { /* Failure of context importing should not make an identity disappear, therefore we catch exceptions. */
						identity.setContexts(xmlData.identityContexts);
					}
					catch(Exception e) {
						Logger.warning(this, "setContexts() failed.", e);
					}
	
					try { /* Failure of property importing should not make an identity disappear, therefore we catch exceptions. */
						identity.setProperties(xmlData.identityProperties);
					}
					catch(Exception e) {
						Logger.warning(this, "setProperties() failed", e);
					}
				
					
					mWoT.beginTrustListImport(); // We delete the old list if !identityPublishesTrustList and it did publish one earlier => we always call this. 
					
					if(xmlData.identityPublishesTrustList) {
						// We import the trust list of an identity if it's score is equal to 0, but we only create new identities or import edition hints
						// if the score is greater than 0. Solving a captcha therefore only allows you to create one single identity.
						boolean positiveScore = false;
						boolean hasCapacity = false;
						
						// TODO: getBestScore/getBestCapacity should always yield a positive result because we store a positive score object for an OwnIdentity
						// upon creation. The only case where it could not exist might be restoreOwnIdentity() ... check that. If it is created there as well,
						// remove the additional check here.
						if(identity instanceof OwnIdentity) {
							// Importing of OwnIdentities is always allowed
							positiveScore = true;
							hasCapacity = true;
						} else {
							try {
								positiveScore = mWoT.getBestScore(identity) > 0;
								hasCapacity = mWoT.getBestCapacity(identity) > 0;
							}
							catch(NotInTrustTreeException e) { }
						}
						
						
						HashSet<String>	identitiesWithUpdatedEditionHint = null;

						if(positiveScore) {
							identitiesWithUpdatedEditionHint = new HashSet<String>(xmlData.identityTrustList.size() * 2);
						}

						for(final ParsedIdentityXML.TrustListEntry trustListEntry : xmlData.identityTrustList) {
							final FreenetURI trusteeURI = trustListEntry.mTrusteeURI;
							final byte trustValue = trustListEntry.mTrustValue;
							final String trustComment = trustListEntry.mTrustComment;

							Identity trustee = null;
							try {
								trustee = mWoT.getIdentityByURI(trusteeURI);
								if(positiveScore) {
									if(trustee.setNewEditionHint(trusteeURI.getEdition())) {
										identitiesWithUpdatedEditionHint.add(trustee.getID());
										trustee.storeWithoutCommit();
									}
								}
							}
							catch(UnknownIdentityException e) {
								if(hasCapacity) { /* We only create trustees if the truster has capacity to rate them. */
									try {
										trustee = new Identity(mWoT, trusteeURI, null, false);
										trustee.storeWithoutCommit();
									} catch(MalformedURLException urlEx) {
										// Logging the exception does NOT log the actual malformed URL so we do it manually.
										Logger.warning(this, "Received malformed identity URL: " + trusteeURI, urlEx);
										throw urlEx;
									}
								}
							}

							if(trustee != null)
								mWoT.setTrustWithoutCommit(identity, trustee, trustValue, trustComment);
						}

						for(Trust trust : mWoT.getGivenTrustsOfDifferentEdition(identity, identityURI.getEdition())) {
							mWoT.removeTrustWithoutCommit(trust);
						}

						IdentityFetcher identityFetcher = mWoT.getIdentityFetcher();
						if(positiveScore) {
							for(String id : identitiesWithUpdatedEditionHint)
								identityFetcher.storeUpdateEditionHintCommandWithoutCommit(id);

							// We do not have to store fetch commands for new identities here, setTrustWithoutCommit does it.
						}
					} else if(!xmlData.identityPublishesTrustList && didPublishTrustListPreviously && !(identity instanceof OwnIdentity)) {
						// If it does not publish a trust list anymore, we delete all trust values it has given.
						for(Trust trust : mWoT.getGivenTrusts(identity))
							mWoT.removeTrustWithoutCommit(trust);
					}

					mWoT.finishTrustListImport();
					identity.onFetched(); // Marks the identity as parsed successfully
					identity.storeAndCommit();
				}
				catch(Exception e) { 
					mWoT.abortTrustListImport(e, Logger.LogLevel.WARNING); // Does the rollback
					throw e;
				} // try
			} // synchronized(Persistent.transactionLock(db))
				
			Logger.normal(this, "Finished XML import for " + identity);
		} // synchronized(mWoT)
		} // synchronized(mWoT.getIdentityFetcher())
		} // try
		catch(Exception e) {
			synchronized(mWoT) {
			synchronized(mWoT.getIdentityFetcher()) {
				try {
					final Identity identity = mWoT.getIdentityByURI(identityURI);
					final long newEdition = identityURI.getEdition();
					if(identity.getEdition() <= newEdition) {
						Logger.normal(this, "Marking edition as parsing failed: " + identityURI);
						try {
							identity.setEdition(newEdition);
						} catch (InvalidParameterException e1) {
							// Would only happen if newEdition < current edition.
							// We have validated the opposite.
							throw new RuntimeException(e1);
						}
						identity.onParsingFailed();
						identity.storeAndCommit();
					} else {
						Logger.normal(this, "Not marking edition as parsing failed, we have already fetched a new one (" + 
								identity.getEdition() + "):" + identityURI);
					}
					Logger.normal(this, "Parsing identity XML failed gracefully for " + identityURI, e);
				}
				catch(UnknownIdentityException uie) {
					Logger.error(this, "Fetched an unknown identity: " + identityURI);
				}	
			}
			}
		}
	}

	public void exportIntroduction(OwnIdentity identity, OutputStream os) throws TransformerException {
		Document xmlDoc;
		synchronized(mDocumentBuilder) { // TODO: Figure out whether the DocumentBuilder is maybe synchronized anyway
			xmlDoc = mDOM.createDocument(null, WebOfTrust.WOT_NAME, null);
		}
		
		// 1.0 does not support all Unicode characters which the String class supports. To prevent us from having to filter all Strings, we use 1.1
		xmlDoc.setXmlVersion("1.1");
		
		Element rootElement = xmlDoc.getDocumentElement();

		// We include the WoT version to have an easy way of handling bogus XML which might be created by bugged versions.
		rootElement.setAttribute("Version", Long.toString(Version.getRealVersion()));

		Element introElement = xmlDoc.createElement("IdentityIntroduction");
		introElement.setAttribute("Version", Integer.toString(XML_FORMAT_VERSION)); /* Version of the XML format */

		Element identityElement = xmlDoc.createElement("Identity");
		// synchronized(mWoT) { // Not necessary according to JavaDoc of identity.getRequestURI()
		identityElement.setAttribute("URI", identity.getRequestURI().toString());
		//}
		introElement.appendChild(identityElement);
	
		rootElement.appendChild(introElement);

		DOMSource domSource = new DOMSource(xmlDoc);
		StreamResult resultStream = new StreamResult(os);
		synchronized(mSerializer) {  // TODO: Figure out whether the Serializer is maybe synchronized anyway
			mSerializer.transform(domSource, resultStream);
		}
	}

	/**
	 * Creates an identity from an identity introduction, stores it in the database and returns the new identity.
	 * If the identity already exists, the existing identity is returned.
	 * 
	 * You have to synchronize on the WebOfTrust object when using this function!
	 * TODO: Remove this requirement and re-query the parameter OwnIdentity puzzleOwner from the database after we are synchronized. 
	 * 
	 * @param xmlInputStream An InputStream which must not return more than {@link MAX_INTRODUCTION_BYTE_SIZE} bytes.
	 * @throws InvalidParameterException If the XML format is unknown or if the puzzle owner does not allow introduction anymore.
	 * @throws IOException 
	 * @throws SAXException 
	 */
	public Identity importIntroduction(OwnIdentity puzzleOwner, InputStream xmlInputStream)
		throws InvalidParameterException, SAXException, IOException {
		xmlInputStream = new OneBytePerReadInputStream(xmlInputStream); // Workaround for Java bug, see the stream class for explanation
		
		// May not be accurate by definition of available(). So the JavaDoc requires the callers to obey the size limit, this is a double-check.
		if(xmlInputStream.available() > MAX_INTRODUCTION_BYTE_SIZE)
			throw new IllegalArgumentException("XML contains too many bytes: " + xmlInputStream.available());
		
		FreenetURI identityURI;
		Identity newIdentity;
		
		Document xmlDoc;
		synchronized(mDocumentBuilder) { // TODO: Figure out whether the DocumentBuilder is maybe synchronized anyway
			xmlDoc = mDocumentBuilder.parse(xmlInputStream);
		}
		
		Element introductionElement = (Element)xmlDoc.getElementsByTagName("IdentityIntroduction").item(0);

		if(Integer.parseInt(introductionElement.getAttribute("Version")) > XML_FORMAT_VERSION)
			throw new InvalidParameterException("Version " + introductionElement.getAttribute("Version") + " > " + XML_FORMAT_VERSION);

		Element identityElement = (Element)introductionElement.getElementsByTagName("Identity").item(0);

		identityURI = new FreenetURI(identityElement.getAttribute("URI"));
		
		final IdentityFetcher identityFetcher = mWoT.getIdentityFetcher();
		
		synchronized(mWoT) {
		synchronized(identityFetcher) {
			if(!puzzleOwner.hasContext(IntroductionPuzzle.INTRODUCTION_CONTEXT))
				throw new InvalidParameterException("Trying to import an identity identroduction for an own identity which does not allow introduction.");
			
			synchronized(Persistent.transactionLock(mDB)) {
				try {
					try {
						newIdentity = mWoT.getIdentityByURI(identityURI);
						if(logMINOR) Logger.minor(this, "Imported introduction for an already existing identity: " + newIdentity);
					}
					catch (UnknownIdentityException e) {
						newIdentity = new Identity(mWoT, identityURI, null, false);
						// We do NOT call setEdition(): An attacker might solve puzzles pretending to be someone else and publish bogus edition numbers for
						// that identity by that. The identity constructor only takes the edition number as edition hint, this is the proper behavior.
						// TODO: As soon as we have code for signing XML with an identity SSK we could sign the introduction XML and therefore prevent that
						// attack.
						//newIdentity.setEdition(identityURI.getEdition());
						newIdentity.storeWithoutCommit();
						if(logMINOR) Logger.minor(this, "Imported introduction for an unknown identity: " + newIdentity);
					}

					try {
						mWoT.getTrust(puzzleOwner, newIdentity); /* Double check ... */
						if(logMINOR) Logger.minor(this, "The identity is already trusted.");
					}
					catch(NotTrustedException ex) {
						// 0 trust will not allow the import of other new identities for the new identity because the trust list import code will only create
						// new identities if the score of an identity is > 0, not if it is equal to 0.
						mWoT.setTrustWithoutCommit(puzzleOwner, newIdentity, (byte)0, "Trust received by solving a captcha.");	
					}
					
					// setTrustWithoutCommit() does this for us.
					// identityFetcher.storeStartFetchCommandWithoutCommit(newIdentity.getID());

					newIdentity.checkedCommit(this);
				}
				catch(RuntimeException error) {
					Persistent.checkedRollbackAndThrow(mDB, this, error);
					throw error; // Satisfy the compiler
				}
			}
		}
		}

		return newIdentity;
	}

	public void exportIntroductionPuzzle(IntroductionPuzzle puzzle, OutputStream os)
		throws TransformerException, ParserConfigurationException {
		
		Document xmlDoc;
		synchronized(mDocumentBuilder) { // TODO: Figure out whether the DocumentBuilder is maybe synchronized anyway
			xmlDoc = mDOM.createDocument(null, WebOfTrust.WOT_NAME, null);
		}
		
		// 1.0 does not support all Unicode characters which the String class supports. To prevent us from having to filter all Strings, we use 1.1
		xmlDoc.setXmlVersion("1.1");
		
		Element rootElement = xmlDoc.getDocumentElement();

		// We include the WoT version to have an easy way of handling bogus XML which might be created by bugged versions.
		rootElement.setAttribute("Version", Long.toString(Version.getRealVersion()));

		Element puzzleElement = xmlDoc.createElement("IntroductionPuzzle");
		puzzleElement.setAttribute("Version", Integer.toString(INTRODUCTION_XML_FORMAT_VERSION)); /* Version of the XML format */
		
		// This lock is actually not necessary because all values which are taken from the puzzle are final. We leave it here just to make sure that it does
		// not get lost if it becomes necessary someday.
		synchronized(puzzle) { 
			puzzleElement.setAttribute("ID", puzzle.getID());
			puzzleElement.setAttribute("Type", puzzle.getType().toString());
			puzzleElement.setAttribute("MimeType", puzzle.getMimeType());
			synchronized(mDateFormat) {
			puzzleElement.setAttribute("ValidUntil", mDateFormat.format(puzzle.getValidUntilDate()));
			}
			
			Element dataElement = xmlDoc.createElement("Data");
			dataElement.setAttribute("Value", Base64.encodeStandard(puzzle.getData()));
			puzzleElement.appendChild(dataElement);	
		}
		
		rootElement.appendChild(puzzleElement);

		DOMSource domSource = new DOMSource(xmlDoc);
		StreamResult resultStream = new StreamResult(os);
		synchronized(mSerializer) {
			mSerializer.transform(domSource, resultStream);
		}
	}

	/**
	 * @param xmlInputStream An InputStream which must not return more than {@link MAX_INTRODUCTIONPUZZLE_BYTE_SIZE} bytes.
	 */
	public IntroductionPuzzle importIntroductionPuzzle(FreenetURI puzzleURI, InputStream xmlInputStream)
		throws SAXException, IOException, InvalidParameterException, UnknownIdentityException, IllegalBase64Exception, ParseException {
		
		xmlInputStream = new OneBytePerReadInputStream(xmlInputStream); // Workaround for Java bug, see the stream class for explanation
		
		// May not be accurate by definition of available(). So the JavaDoc requires the callers to obey the size limit, this is a double-check.
		if(xmlInputStream.available() > MAX_INTRODUCTIONPUZZLE_BYTE_SIZE)
			throw new IllegalArgumentException("XML contains too many bytes: " + xmlInputStream.available());
		
		String puzzleID;
		IntroductionPuzzle.PuzzleType puzzleType;
		String puzzleMimeType;
		Date puzzleValidUntilDate;
		byte[] puzzleData;
		
		
		Document xmlDoc;
		synchronized(mDocumentBuilder) { // TODO: Figure out whether the DocumentBuilder is maybe synchronized anyway
			xmlDoc = mDocumentBuilder.parse(xmlInputStream);
		}
		Element puzzleElement = (Element)xmlDoc.getElementsByTagName("IntroductionPuzzle").item(0);

		if(Integer.parseInt(puzzleElement.getAttribute("Version")) > INTRODUCTION_XML_FORMAT_VERSION)
			throw new InvalidParameterException("Version " + puzzleElement.getAttribute("Version") + " > " + INTRODUCTION_XML_FORMAT_VERSION);	

		puzzleID = puzzleElement.getAttribute("ID");
		puzzleType = IntroductionPuzzle.PuzzleType.valueOf(puzzleElement.getAttribute("Type"));
		puzzleMimeType = puzzleElement.getAttribute("MimeType");
		synchronized(mDateFormat) {
		puzzleValidUntilDate = mDateFormat.parse(puzzleElement.getAttribute("ValidUntil"));
		}

		Element dataElement = (Element)puzzleElement.getElementsByTagName("Data").item(0);
		puzzleData = Base64.decodeStandard(dataElement.getAttribute("Value"));

		
		IntroductionPuzzle puzzle;
		
		synchronized(mWoT) {
			Identity puzzleInserter = mWoT.getIdentityByURI(puzzleURI);
			puzzle = new IntroductionPuzzle(mWoT, puzzleInserter, puzzleID, puzzleType, puzzleMimeType, puzzleData, 
					IntroductionPuzzle.getDateFromRequestURI(puzzleURI), puzzleValidUntilDate, IntroductionPuzzle.getIndexFromRequestURI(puzzleURI));
		
			mWoT.getIntroductionPuzzleStore().storeAndCommit(puzzle);
		}
		
		return puzzle;
	}

}
