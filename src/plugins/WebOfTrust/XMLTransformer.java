/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import static java.lang.Math.max;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.nio.charset.Charset;
import java.security.SecureRandom;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map.Entry;
import java.util.Random;
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
import plugins.WebOfTrust.network.input.EditionHint;
import plugins.WebOfTrust.network.input.IdentityDownloaderController;

import com.db4o.ext.ExtObjectContainer;

import freenet.keys.FreenetURI;
import freenet.support.Base64;
import freenet.support.CurrentTimeUTC;
import freenet.support.IllegalBase64Exception;
import freenet.support.Logger;

/**
 * This class handles all XML creation and parsing of the WoT plugin, that is import and export of identities, identity introductions 
 * and introduction puzzles. The code for handling the XML related to identity introduction is not in a separate class in the WoT.Introduction
 * package so that we do not need to create multiple instances of the XML parsers / pass the parsers to the other class. 
 * 
 * TODO: Code quality: Rename to IdentityFileXMLTransformer to match naming of
 * {@link IdentityFileQueue} and {@link IdentityFileProcessor}.
 * 
 * @author xor (xor@freenetproject.org)
 */
public final class XMLTransformer {

	private static final int XML_FORMAT_VERSION = 1;
	
	private static final String XML_CHARSET_NAME = "UTF-8";
	
	public static final Charset XML_CHARSET = Charset.forName(XML_CHARSET_NAME);
	
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
	
	/**
	 * Equal to {@link WebOfTrust#getSubscriptionManager()} of {@link #mWoT}.
	 */
	private final SubscriptionManager mSubscriptionManager; 
	
	private final ExtObjectContainer mDB;
	
	/* TODO: Check with a profiler how much memory this takes, do not cache it if it is too much */
	/** Used for parsing the identity XML when decoding identities*/
	private final DocumentBuilder mDocumentBuilder;
	
	/* TODO: Check with a profiler how much memory this takes, do not cache it if it is too much */
	/** Created by mDocumentBuilder, used for building the identity XML DOM when encoding identities */
	private final DOMImplementation mDOM;
	
	/** Used for ensuring that the order of the output XML does not reveal private data of the user */
	private final Random mFastWeakRandom;
	
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
		mSubscriptionManager = mWoT.getSubscriptionManager();
		mDB = mWoT.getDatabase();
		
		// If we are not running inside a node, use a SecureRandom, not Random: Assume that the node's choice of "fastWeakRandom"
		// would have been better than the standard java Random - otherwise it wouldn't have that field and use standard Random instead.
		mFastWeakRandom = mWoT.getPluginRespirator() != null ? mWoT.getPluginRespirator().getNode().fastWeakRandom : new SecureRandom();
		
		try {
			DocumentBuilderFactory xmlFactory = DocumentBuilderFactory.newInstance();
			xmlFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
			// DOM parser uses .setAttribute() to pass to underlying Xerces
			xmlFactory.setAttribute("http://apache.org/xml/features/disallow-doctype-decl", true);
			mDocumentBuilder = xmlFactory.newDocumentBuilder(); 
			mDOM = mDocumentBuilder.getDOMImplementation();

			mSerializer = TransformerFactory.newInstance().newTransformer();
			mSerializer.setOutputProperty(OutputKeys.ENCODING, XML_CHARSET_NAME);
			mSerializer.setOutputProperty(OutputKeys.INDENT, "yes"); // TODO: Disable as soon as bug 0004850 is fixed.
			mSerializer.setOutputProperty(OutputKeys.STANDALONE, "no");
			
			// TODO: Code quality: Move to DateUtil, see its function toStringYYYYMMDD().
			// Also search codebase for other occurrences of SimpleDateFormat and move them as well.
			mDateFormat = new SimpleDateFormat("yyyy-MM-dd");
			mDateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
		}
		catch(Exception e) {
			throw new RuntimeException(e);
		}
	}

    /**
     * @param softXMLByteSizeLimit
     *            <b>ATTENTION:</b> This limit cannot be accurately followed due to the way
     *            {@link InputStream#available()} works. Consider this as a soft, fallback security
     *            mechanism and please take further precautions to prevent too larger input. 
     */
    Document parseDocument(InputStream xmlInputStream, final int softXMLByteSizeLimit)
            throws IOException, SAXException {
        
        xmlInputStream = new OneBytePerReadInputStream(xmlInputStream); // Workaround for Java bug, see the stream class for explanation
         
        // May not be accurate by definition of available(). So the JavaDoc requires the callers to obey the size limit, this is a double-check.
        if(xmlInputStream.available() > softXMLByteSizeLimit)
            throw new IllegalArgumentException("XML contains too many bytes: " + xmlInputStream.available());
        
        synchronized(mDocumentBuilder) { // TODO: Figure out whether the DocumentBuilder is maybe synchronized anyway
            return mDocumentBuilder.parse(xmlInputStream);
        }
    }

	public void exportOwnIdentity(OwnIdentity identity, OutputStream os) throws TransformerException {
		Document xmlDoc;
		synchronized(mDocumentBuilder) { // TODO: Figure out whether the DocumentBuilder is maybe synchronized anyway 
			xmlDoc = mDOM.createDocument(null, WebOfTrustInterface.WOT_NAME, null);
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
				
				final ArrayList<Trust> trusts = new ArrayList<Trust>(MAX_IDENTITY_XML_TRUSTEE_AMOUNT + 1);
				// We can only include a limited amount of trust values because the allowed size of a trust list must be finite to prevent DoS.
				// So we chose the included trust values by sorting the trust list by last seen date of the trustee and cutting off
				// the list after the size limit. This gives active identities who still publish a trust list a better chance than the ones
				// who aren't in use anymore.
				int trustCount = 0;
				for(Trust trustCandidate : mWoT.getGivenTrustsSortedDescendingByLastSeen(identity)) {
					if(++trustCount > MAX_IDENTITY_XML_TRUSTEE_AMOUNT) {
						Logger.normal(this, "Amount of trustees exceeded " + MAX_IDENTITY_XML_TRUSTEE_AMOUNT + ", not adding any more to trust list of " + identity);
						break;
					}
					trusts.add(trustCandidate);
				}

				// We cannot add the trusts like we queried them from the database: We have sorted the database query by last-seen date
				// and that date reveals some information about the state of the WOT. This is a potential privacy leak.
				// So we randomize the appearance of the trust values in the XML. We are OK to use a weak RNG:
				// - The original sort order which we try to hide is not used for any computations, it is merely of statistical significance.
				//   So RNG exploits cannot wreak any havoc by maliciously positioning stuff where it shouldn't be
				// - The order in which the node fetches identities should already be softly randomized.
				//   Randomizing it even more with a weak RNG will make it very random.
				Collections.shuffle(trusts, mFastWeakRandom);
				
				for(Trust trust : trusts) {
					/* We should make very sure that we do not reveal the other own identity's */
					if(trust.getTruster() != identity) 
						throw new RuntimeException("Error in WoT: It is trying to export trust values of someone else in the trust list " +
								"of " + identity + ": Trust value from " + trust.getTruster() + "");

					Element trustElement = xmlDoc.createElement("Trust");
					// FIXME: Take into account the getTrustee().getCurrentEditionFetchState() to
					// avoid publishing edition hints for editions which don't exist yet.
					// Encapsulate this by adding a function "getLastFetchedURI()" to class Identity
					// Also think about how much of the currently existing edition hints on the
					// network before these changes are wrong, i.e. of editions which don't exist
					// yet. If this applies to most of them we might need some compatibility code.
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
	}
	
	/**
	 * @param xmlInputStream An InputStream which must not return more than {@link MAX_IDENTITY_XML_BYTE_SIZE} bytes.
	 */
	private ParsedIdentityXML parseIdentityXML(InputStream xmlInputStream) throws IOException {
		Logger.normal(this, "Parsing identity XML...");
		
		final ParsedIdentityXML result = new ParsedIdentityXML();
		
		try {			
			Document xmlDoc = parseDocument(xmlInputStream, MAX_IDENTITY_XML_BYTE_SIZE);
			
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
				
				if(trustList.getLength() > MAX_IDENTITY_XML_TRUSTEE_AMOUNT)
					throw new Exception("Too many trust values: " + trustList.getLength());
				
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
		synchronized(mSubscriptionManager) {
			final Identity identity = mWoT.getIdentityByURI(identityURI);
			final Identity oldIdentity = identity.clone(); // For the SubscriptionManager
			
			Logger.normal(this, "Importing parsed XML for " + identity);

			// When shouldFetchIdentity() changes from true to false due to an identity becoming
			// distrusted, this change will not cause the IdentityFetcher to abort the fetch
			// immediately: It queues the command to abort the fetch, and processes commands after
			// some seconds.
			// Also, fetched identity files are enqueued for processing in an IdentityFileQueue, and
			// might wait there for several minutes.
			// Thus, it is possible that this function is called for an Identity which is not
			// actually wanted anymore. So we must check whether the identity is really still
			// wanted.
            if(!mWoT.shouldFetchIdentity(identity)) {
                Logger.normal(this,
                    "importIdentity() called for unwanted identity, probably because the "
                  + "IdentityFetcher has not processed the AbortFetchCommand yet or the "
                  + "file was in the IdentityFileQueue for some time, not importing: "
                  + identity);
                return;
            }
			
			final long newEdition = identityURI.getEdition();
			if(newEdition <= identity.getLastFetchedEdition()) {
				if(identity.getCurrentEditionFetchState() == FetchState.ParsingFailed) {
					Logger.warning(this,
						  "Fetched obsolete edition, bug? Allowing import nevertheless because "
						+ "current one is marked as ParsingFailed: This may not be a bug if you "
						+ "intentionally cause re-fetching for cleanup after parser bugfixes! "
						+ "edition: " + newEdition + "; " + identity);
					
					// Don't return, proceed to import it, maybe parsing works now.
				} else {
					Logger.error(this,
						"Fetched obsolete edition, bug? edition: " + newEdition + "; " + identity);
					return;
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
						
						// We need the actual values for constructing EditionHint objects.
						Integer bestScore = null;
						Integer bestCapacity = null;
						
						try {
							bestScore = mWoT.getBestScore(identity);
							// Capacity is stored inside Score objects so if the call before threw
							// then the following would also throw.
							// So we don't need a separate try/catch for the following.
							bestCapacity = mWoT.getBestCapacity(identity);
						}
						catch(NotInTrustTreeException e) { }
						
						// TODO: getBestScore/getBestCapacity should always yield a positive result because we store a positive score object for an OwnIdentity
						// upon creation. The only case where it could not exist might be restoreOwnIdentity() ... check that. If it is created there as well,
						// remove the additional check here.
						if(identity instanceof OwnIdentity) {
							// Importing of OwnIdentities is always allowed
							positiveScore = true;
							hasCapacity = true;
							
							// If these never fail, then the above TODO can be considered as
							// resolved. Then we can remove the whole special case of this if()
							// handling OwnIdentities separately: The else{} can remain and will
							// always do what this special branch used to do.
							assert(bestScore != null && bestScore > 0) : bestScore;
							assert(bestCapacity != null && bestCapacity > 0) : bestCapacity;
						} else {
							if(bestScore != null) {
								assert(bestCapacity != null);
								positiveScore = bestScore > 0;
								hasCapacity = bestCapacity > 0;
							} else
								assert(bestCapacity == null);
							
							// FIXME: This is a copy of the old version of the code above code
							// amended with assert()s. It serves as a paranoia check to figure out
							// whether I refactored the above code correctly.
							// Please remove if the assertions never fail.
							try {
								assert(positiveScore == mWoT.getBestScore(identity) > 0);
								assert(hasCapacity == mWoT.getBestCapacity(identity) > 0);
							}
							catch(NotInTrustTreeException e) { }
						}
						
						// Key = Identity ID of the Identity which the hint is about.
						// Value = The actual hint
						HashMap<String, Long> editionHints
							= new HashMap<>(xmlData.identityTrustList.size() * 2);

						for(final ParsedIdentityXML.TrustListEntry trustListEntry : xmlData.identityTrustList) {
							final FreenetURI trusteeURI = trustListEntry.mTrusteeURI;
							final byte trustValue = trustListEntry.mTrustValue;
							final String trustComment = trustListEntry.mTrustComment;

							Identity trustee = null;
							try {
								trustee = mWoT.getIdentityByURI(trusteeURI);
								
								// Here would be the place where we decide whether to accept
								// the edition hints of the identity or discard them in case it is
								// not trustworthy enough. But we don't, we accept them all.
								// This is to guarnatee "stability" of Score computation.
								// Explanation follows:
								// At first thought, we would decide that we must only accept the
								// edition hints if positiveScore == true: The central goal of WoT
								// is to stop downloading spam, and spam is anything which an
								// identity with positiveScore == false links. So the edition hints
								// would be spam as well then.
								// But: There are imaginable situations where an identity has a
								// negative Score just because we downloaded a single malicious
								// distruster of it before a much larger network of legitimate
								// trusters of it. It further is possible that the only way for the
								// wrongly distrusted identity's trusters to be downloaded is if we
								// use the edition hints it provides for its trusters. So for the
								// wrongly distrusted identity to be able to become trusted, we must
								// accept its hints. ("Look, those people trust me, download them!")
								// In other words: Score computation is supposed to be *stable*.
								// Stable means that the results of it should be independent of the
								// order in which Trust values are obtained as input.
								// To get a stable Score computation in terms of trust value import,
								// this class imports identities not only if positiveScore == true,
								// but also if positiveCapacity == true.
								// So we apply the same principle upon edition hints: We must not
								// only accept hints if positiveScore == true, but also if
								// hasCapacity == true.
								// (The deciding thing is the "positiveCapacity == true" check, you
								// can validate that this helps with pen and paper. Or see the
								// "testStability" functions in class WoTTest.)
								// In our case, one of positiveScore and hasCapacity will be always
								// true, so we don't have to check them at all:
								// We already only run his function if
								//     mWebOfTrust.shouldFetchIdentity() == true
								// which only happens if:
								assert(hasCapacity || positiveScore);
								
								if(trusteeURI.getEdition() >= 0) {
									Long previous = editionHints.put(
										trustee.getID(), trusteeURI.getEdition());
									
									assert(previous == null);
								} else {
									// FIXME: Either punish the publisher of the XML for providing
									// bogus data OR have this be a valid contion:
									// Amend the code which generates XML to use editions of -1
									// to signal that an identity wasn't downloaded at all yet.
								}								
							}
							catch(UnknownIdentityException e) {
								if(hasCapacity) { /* We only create trustees if the truster has capacity to rate them. */
									try {
										trustee = new Identity(mWoT, trusteeURI, null, false);
										trustee.storeWithoutCommit();
										mSubscriptionManager.storeIdentityChangedNotificationWithoutCommit(null, trustee);
										
										// We must always store a hint for new identities even
										// if their truster has indicated he doesn't know their
										// edition by setting it to -1:
										// IdentityDownladerFast will not create an USK subscription
										// for identities with rank > 1, so the only way we can
										// ever download them for the first time is through a hint
										// to IdentityDownloaderSlow.
										// So even if we got no valid hint, we store one of 0.
										Long hint = max(0,  trusteeURI.getEdition());
										Long previous = editionHints.put(trustee.getID(), hint);
										assert(previous == null);
										
										Logger.normal(this, "New identity received via trust list: " + identity);
									} catch(MalformedURLException urlEx) {
										// Logging the exception does NOT log the actual malformed URL so we do it manually.
										Logger.warning(this, "Received malformed identity URL: " + trusteeURI, urlEx);
										throw urlEx;
									}
								}
							}

							if(trustee != null)
								mWoT.setTrustWithoutCommit(identity, trustee, trustValue, trustComment); // Also takes care of SubscriptionManager
						}

						for(Trust trust : mWoT.getGivenTrustsOfDifferentEdition(identity, identityURI.getEdition())) {
							mWoT.removeTrustWithoutCommit(trust); // Also takes care of SubscriptionManager
						}

						// Feed EditionHints to the IdentityDownloaderController
						IdentityDownloaderController idc = mWoT.getIdentityDownloaderController();
						assert(bestCapacity != null && bestCapacity > 0) : bestCapacity;
						assert(bestScore != null) : bestScore;
						for(Entry<String, Long> e : editionHints.entrySet()) {
							EditionHint h = EditionHint.constructSecure(
								mWoT,
								identity.getID(),
								e.getKey(),
								CurrentTimeUTC.get(), /* FIXME: Propagate in the XML */
								bestCapacity,
								bestScore,
								e.getValue());
							idc.storeNewEditionHintCommandWithoutCommit(h);
						}

						//if(positiveScore) {
							// We do not have to store fetch commands for new identities here, setTrustWithoutCommit does it.
						//}
					} else if(!xmlData.identityPublishesTrustList && didPublishTrustListPreviously && !(identity instanceof OwnIdentity)) {
						// If it does not publish a trust list anymore, we delete all trust values it has given.
						for(Trust trust : mWoT.getGivenTrusts(identity))
							mWoT.removeTrustWithoutCommit(trust); // Also takes care of SubscriptionManager
					}

					mWoT.finishTrustListImport();
					identity.onFetched(); // Marks the identity as parsed successfully
					mSubscriptionManager.storeIdentityChangedNotificationWithoutCommit(oldIdentity, identity);
					identity.storeAndCommit();
				}
				catch(Exception e) { 
					mWoT.abortTrustListImport(e, Logger.LogLevel.WARNING); // Does the rollback
					throw e;
				} // try
			} // synchronized(Persistent.transactionLock(db))
				
			Logger.normal(this, "Finished XML import for " + identity);
		} // synchronized(mSubscriptionManager)
		} // synchronized(mWoT.getIdentityFetcher())
		} // synchronized(mWoT)
		} // try
		catch(Exception e) {
			synchronized(mWoT) {
			// synchronized(mSubscriptionManager) { // We don't use the SubscriptionManager, see below
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
						// We don't notify the SubscriptionManager here since there is not really any new information about the identity because parsing failed.
						identity.storeAndCommit();
					} else {
						Logger.normal(this, "Not marking edition as parsing failed, we have already fetched a new one (" + 
								identity.getEdition() + "):" + identityURI);
					}
					Logger.warning(this, "Parsing identity XML failed gracefully for " + identityURI, e);
				}
				catch(UnknownIdentityException uie) {
					Logger.error(this, "Parsing identity XML failed and marking the edition as ParsingFailed also did not work - UnknownIdentityException for: "
							+ identityURI, e);
				}	
			}
			}
		}
	}

	public void exportIntroduction(OwnIdentity identity, OutputStream os) throws TransformerException {
		Document xmlDoc;
		synchronized(mDocumentBuilder) { // TODO: Figure out whether the DocumentBuilder is maybe synchronized anyway
			xmlDoc = mDOM.createDocument(null, WebOfTrustInterface.WOT_NAME, null);
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
	    
		FreenetURI identityURI;
		Identity newIdentity;
		
		Document xmlDoc = parseDocument(xmlInputStream, MAX_INTRODUCTION_BYTE_SIZE);
		
		Element introductionElement = (Element)xmlDoc.getElementsByTagName("IdentityIntroduction").item(0);

		if(Integer.parseInt(introductionElement.getAttribute("Version")) > XML_FORMAT_VERSION)
			throw new InvalidParameterException("Version " + introductionElement.getAttribute("Version") + " > " + XML_FORMAT_VERSION);

		Element identityElement = (Element)introductionElement.getElementsByTagName("Identity").item(0);

		identityURI = new FreenetURI(identityElement.getAttribute("URI"));
		
		synchronized(mWoT) {
		synchronized(mWoT.getIdentityFetcher()) {
		synchronized(mSubscriptionManager) {
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
						mWoT.getSubscriptionManager().storeIdentityChangedNotificationWithoutCommit(null, newIdentity);
						if(logMINOR) Logger.minor(this, "Imported introduction for an unknown identity: " + newIdentity);
					}

					try {
						mWoT.getTrust(puzzleOwner, newIdentity); /* Double check ... */
						if(logMINOR) Logger.minor(this, "The identity is already trusted.");
					}
					catch(NotTrustedException ex) {
						// 0 trust will not allow the import of other new identities for the new identity because the trust list import code will only create
						// new identities if the score of an identity is > 0, not if it is equal to 0.
						mWoT.setTrustWithoutCommit(puzzleOwner, newIdentity, (byte)0, "Trust received by solving a captcha.");// Also takes care of SubscriptionManager
					}
					
					// setTrustWithoutCommit() does this for us, no need to do it ourself:
					//   mWoT.getIdentityFetcher()
					//       .storeStartFetchCommandWithoutCommit(newIdentity.getID());

					newIdentity.checkedCommit(this);
				}
				catch(RuntimeException error) {
					Persistent.checkedRollbackAndThrow(mDB, this, error);
					// Satisfy the compiler - without this the return at the end of the function would complain about the uninitialized newIdentity variable
					throw error;
				}
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
			xmlDoc = mDOM.createDocument(null, WebOfTrustInterface.WOT_NAME, null);
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
	public void importIntroductionPuzzle(FreenetURI puzzleURI, InputStream xmlInputStream)
		throws SAXException, IOException, InvalidParameterException, UnknownIdentityException, IllegalBase64Exception, ParseException {
	    
		String puzzleID;
		IntroductionPuzzle.PuzzleType puzzleType;
		String puzzleMimeType;
		Date puzzleValidUntilDate;
		byte[] puzzleData;
		
		Document xmlDoc = parseDocument(xmlInputStream, MAX_INTRODUCTIONPUZZLE_BYTE_SIZE);
		
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

		synchronized(mWoT) {
		synchronized(mWoT.getIntroductionPuzzleStore()) {
			Identity puzzleInserter = mWoT.getIdentityByURI(puzzleURI);
			IntroductionPuzzle puzzle
			    = new IntroductionPuzzle(mWoT, puzzleInserter, puzzleID, puzzleType, puzzleMimeType,
			        puzzleData,  IntroductionPuzzle.getDateFromRequestURI(puzzleURI),
			        puzzleValidUntilDate, IntroductionPuzzle.getIndexFromRequestURI(puzzleURI));
		
			mWoT.getIntroductionPuzzleStore().storeAndCommit(puzzle);
		}}
	}

}
