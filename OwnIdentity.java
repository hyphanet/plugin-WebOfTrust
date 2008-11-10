/**
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */
package plugins.WoT;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.util.Date;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.DOMImplementation;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import plugins.WoT.exceptions.DuplicateIdentityException;
import plugins.WoT.exceptions.DuplicateScoreException;
import plugins.WoT.exceptions.InvalidParameterException;
import plugins.WoT.exceptions.NotInTrustTreeException;
import plugins.WoT.exceptions.UnknownIdentityException;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.ext.DatabaseClosedException;
import com.db4o.ext.Db4oIOException;
import com.db4o.query.Query;

import freenet.keys.FreenetURI;

/**
 * A local Identity (it belongs to the user)
 * 
 * @author Julien Cornuwel (batosai@freenetproject.org)
 *
 */
public class OwnIdentity extends Identity {
	
	private FreenetURI insertURI;
	private Date lastInsert;
	
	/**
	 * Creates a new OwnIdentity with the given parameters.
	 * 
	 * @param insertURI A {@link FreenetURI} used to insert this OwnIdentity in Freenet
	 * @param requestURI A {@link FreenetURI} used to fetch this OwnIdentity in Freenet
	 * @param nickName The nickName of this OwnIdentity
	 * @param publishTrustList Whether this OwnIdentity publishes its trustList or not 
	 * @throws InvalidParameterException If a given parameter is invalid
	 */
	public OwnIdentity (FreenetURI insertURI, FreenetURI requestURI, String nickName, boolean publishTrustList) throws InvalidParameterException {	
		super(requestURI, nickName, publishTrustList);
		setInsertURI(insertURI);
		setLastInsert(new Date(0));
	}
	
	/**
	 * Creates a new OwnIdentity with the given parameters.
	 * insertURI and requestURI are converted from String to {@link FreenetURI}
	 * 
	 * @param insertURI A String representing the key needed to insert this OwnIdentity in Freenet
	 * @param requestURIA String representing the key needed to fetch this OwnIdentity from Freenet
	 * @param nickName The nickName of this OwnIdentity
	 * @param publishTrustList Whether this OwnIdentity publishes its trustList or not 
	 * @throws InvalidParameterException If a given parameter is invalid
	 * @throws MalformedURLException If either requestURI or insertURI is not a valid FreenetURI
	 */
	public OwnIdentity (String insertURI, String requestURI, String nickName, boolean publishTrustList) throws InvalidParameterException, MalformedURLException {
		this(new FreenetURI(insertURI), new FreenetURI(requestURI), nickName, publishTrustList);
	}
	
	/**
	 * Initializes this OwnIdentity's trust tree.
	 * Meaning : It creates a Score object for this OwnIdentity in its own trust tree, 
	 * so it gets a rank and a capacity and can give trust to other Identities.
	 *  
	 * @param db A reference to the database 
	 * @throws DuplicateScoreException if there already is more than one Score for this identity (should never happen)
	 */
	public void initTrustTree (ObjectContainer db) throws DuplicateScoreException {
		try {
			getScore(this,db);
			return;
		} catch (NotInTrustTreeException e) {
			db.store(new Score(this, this, 100, 0, 100));
		}
	}
	
	/**
	 * Gets an OwnIdentity by its id
	 * 
	 * @param db A reference to the database
	 * @param id The unique identifier to query an OwnIdentity
	 * @return The requested OwnIdentity
	 * @throws UnknownIdentityException if there is now OwnIdentity with that id
	 * @throws DuplicateIdentityException If there is more than one identity with that id (should never happen)
	 */
	@SuppressWarnings("unchecked")
	public static OwnIdentity getById (ObjectContainer db, String id) throws UnknownIdentityException {
		Query query = db.query();
		query.constrain(OwnIdentity.class);
		query.descend("id").constrain(id);
		ObjectSet<OwnIdentity> result = query.execute();
		
		assert(result.size() <= 1);
		if(result.size() == 0) throw new UnknownIdentityException(id.toString());
		return result.next();
	}
	
	/**
	 * Gets an OwnIdentity by its requestURI (as String).
	 * The given String is converted to {@link FreenetURI} in order to extract a unique id.
	 * 
	 * @param db A reference to the database
	 * @param uri The requestURI (as String) of the desired OwnIdentity
	 * @return The requested OwnIdentity
	 * @throws UnknownIdentityException if the OwnIdentity isn't in the database
	 * @throws DuplicateIdentityException if the OwnIdentity is present more that once in the database (should never happen)
	 * @throws MalformedURLException if the supplied requestURI is not a valid FreenetURI
	 */
	public static OwnIdentity getByURI (ObjectContainer db, String uri) throws UnknownIdentityException, MalformedURLException {
		return getByURI(db, new FreenetURI(uri));
	}

	/**
	 * Gets an OwnIdentity by its requestURI (a {@link FreenetURI}).
	 * The OwnIdentity's unique identifier is extracted from the supplied requestURI.
	 * 
	 * @param db A reference to the database
	 * @param uri The requestURI of the desired OwnIdentity
	 * @return The requested OwnIdentity
	 * @throws UnknownIdentityException if the OwnIdentity isn't in the database
	 * @throws DuplicateIdentityException if the OwnIdentity is present more that once in the database (should never happen)
	 */
	public static OwnIdentity getByURI (ObjectContainer db, FreenetURI uri) throws UnknownIdentityException {
		return getById(db, getIdFromURI(uri));
	}
	
	/**
	 * Counts the number of OwnIdentities in the database
	 * @param db A reference to the database
	 * @return the number of OwnIdentities in the database
	 */
	public static int getNbOwnIdentities(ObjectContainer db) {
		return db.queryByExample(OwnIdentity.class).size();
	}
	
	/**
	 * Gets all OwnIdentities present in the database
	 * 
	 * @param db A reference to the database
	 * @return An {@link ObjectSet} containing all OwnIdentities that exist in the database
	 */
	public static ObjectSet<OwnIdentity> getAllOwnIdentities (ObjectContainer db) {
		return db.queryByExample(OwnIdentity.class);
	}

	/**
	 * Exports this identity to XML format, in a supplied OutputStream
	 * 
	 * @param db A reference to the database
	 * @param os The {@link OutputStream} where to put the XML code
	 * @throws ParserConfigurationException
	 * @throws TransformerConfigurationException
	 * @throws TransformerException
	 * @throws FileNotFoundException
	 * @throws IOException
	 * @throws Db4oIOException
	 * @throws DatabaseClosedException
	 * @throws InvalidParameterException
	 */
	public void exportToXML(ObjectContainer db, OutputStream os) throws ParserConfigurationException, TransformerConfigurationException, TransformerException, FileNotFoundException, IOException, Db4oIOException, DatabaseClosedException, InvalidParameterException {

		// Create the output file
		StreamResult resultStream = new StreamResult(os);

		// Create the XML document
		DocumentBuilderFactory xmlFactory = DocumentBuilderFactory.newInstance();
		DocumentBuilder xmlBuilder = xmlFactory.newDocumentBuilder();
		DOMImplementation impl = xmlBuilder.getDOMImplementation();
		Document xmlDoc = impl.createDocument(null, "WoT", null);
		Element rootElement = xmlDoc.getDocumentElement();

		// Create the content
		Element identity = xmlDoc.createElement("identity");

		// NickName
		Element nickNameTag = xmlDoc.createElement("nickName");
		nickNameTag.setAttribute("value", getNickName());
		identity.appendChild(nickNameTag);

		// PublishTrustList
		Element publishTrustListTag = xmlDoc.createElement("publishTrustList");
		publishTrustListTag.setAttribute("value", doesPublishTrustList() ? "true" : "false");
		identity.appendChild(publishTrustListTag);

		// Properties
		
		Iterator<Entry<String, String>> props = getProps();
		while(props.hasNext()){
			Map.Entry<String,String> prop = props.next();
			Element propTag = xmlDoc.createElement("prop");
			propTag.setAttribute("key", prop.getKey());
			propTag.setAttribute("value", prop.getValue());
			identity.appendChild(propTag);
		}

		// Contexts
		Iterator<String> contexts = getContexts();
		while(contexts.hasNext()) {
			String context = (String)contexts.next();
			Element contextTag = xmlDoc.createElement("context");
			contextTag.setAttribute("value", context);
			identity.appendChild(contextTag);			
		}

		rootElement.appendChild(identity);
		
		if(doesPublishTrustList()) {
			Trustlist trustList = new Trustlist(db, this);
			rootElement.appendChild(trustList.toXML(xmlDoc));
		}
		
		DOMSource domSource = new DOMSource(xmlDoc);
		TransformerFactory transformFactory = TransformerFactory.newInstance();
		Transformer serializer = transformFactory.newTransformer();
		
		serializer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
		serializer.setOutputProperty(OutputKeys.INDENT,"yes");
		serializer.transform(domSource, resultStream);
	}
	
	/**
	 * Whether this OwnIdentity needs to be inserted or not.
	 * We insert OwnIdentities when they have been modified AND at least once a week.
	 * @return Whether this OwnIdentity needs to be inserted or not
	 */
	public boolean needsInsert() {
		return (getLastChange().after(getLastInsert()) || (new Date().getTime() - getLastInsert().getTime()) > 1000*60*60*24*7); 
	}

	/**
	 * @return This OwnIdentity's insertURI
	 */
	public FreenetURI getInsertURI() {
		return insertURI;
	}
	
	/**
	 * Sets this OwnIdentity's insertURI. 
	 * The key must be a USK or a SSK, and is stored as a USK anyway.
	 *  
	 * @param key this OwnIdentity's insertURI
	 * @throws InvalidParameterException if the supplied key is neither a USK nor a SSK
	 */
	public void setInsertURI(FreenetURI key) throws InvalidParameterException {
		if(key.getKeyType().equals("SSK")) key = key.setKeyType("USK");
		if(!key.getKeyType().equals("USK")) throw new InvalidParameterException("Key type not supported");

		this.insertURI = key;
	}
	
	public void setEdition(long edition) throws InvalidParameterException {
		setInsertURI(getInsertURI().setSuggestedEdition(edition));
		setRequestURI(getRequestURI().setSuggestedEdition(edition));
	}

	/**
	 * @return Date of last insertion of this OwnIdentity in Freenet
	 */
	public Date getLastInsert() {
		return lastInsert;
	}

	/**
	 * Sets the last insertion date of this OwnIdentity in Freenet
	 * 
	 * @param lastInsert last insertion date of this OwnIdentity in Freenet
	 */
	public void setLastInsert(Date lastInsert) {
		this.lastInsert = lastInsert;
	}
}
