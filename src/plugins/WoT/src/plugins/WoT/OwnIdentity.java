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
	
	public OwnIdentity (FreenetURI insertURI, FreenetURI requestURI, String nickName, String publishTrustList) throws InvalidParameterException {	
		super(requestURI, nickName, publishTrustList);
		setInsertURI(insertURI);
		setLastInsert(new Date(0));
	}

	public OwnIdentity (String insertURI, String requestURI, String nickName, String publishTrustList) throws InvalidParameterException, MalformedURLException {
		this(new FreenetURI(insertURI), new FreenetURI(requestURI), nickName, publishTrustList);
	}
	
	public void initTrustTree (ObjectContainer db) throws DuplicateScoreException {
		try {
			getScore(this,db);
			return;
		} catch (NotInTrustTreeException e) {
			db.store(new Score(this, this, 100, 0, 100));
		}
	}
	
	@SuppressWarnings("unchecked")
	public static OwnIdentity getById (ObjectContainer db, String id) throws UnknownIdentityException, DuplicateIdentityException {
		Query query = db.query();
		query.constrain(OwnIdentity.class);
		query.descend("id").constrain(id);
		ObjectSet<OwnIdentity> result = query.execute();
		
		if(result.size() == 0) throw new UnknownIdentityException(id.toString());
		if(result.size() > 1) throw new DuplicateIdentityException(id.toString());
		return result.next();
	}
	
	public static OwnIdentity getByURI (ObjectContainer db, String uri) throws UnknownIdentityException, DuplicateIdentityException, MalformedURLException {
		return getByURI(db, new FreenetURI(uri));
	}

	public static OwnIdentity getByURI (ObjectContainer db, FreenetURI uri) throws UnknownIdentityException, DuplicateIdentityException {
		return getById(db, getIdFromURI(uri));
	}
	
	public static int getNbOwnIdentities(ObjectContainer db) {
		return db.queryByExample(OwnIdentity.class).size();
	}
	
	public static ObjectSet<OwnIdentity> getAllOwnIdentities (ObjectContainer db) {
		return db.queryByExample(OwnIdentity.class);
	}

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
	
	public boolean needsInsert() {
		return (getLastChange().after(getLastInsert()) || (new Date().getTime() - getLastInsert().getTime()) > 1000*60*60*24*7); 
	}

	public FreenetURI getInsertURI() {
		return insertURI;
	}
	
	public void setInsertURI(FreenetURI key) throws InvalidParameterException {
		if(key.getKeyType().equals("SSK")) key = key.setKeyType("USK");
		if(!key.getKeyType().equals("USK")) throw new InvalidParameterException("Key type not supported");

		this.insertURI = key;
	}
	
	public void setEdition(long edition) throws InvalidParameterException {
		setInsertURI(getInsertURI().setSuggestedEdition(edition));
		setRequestURI(getRequestURI().setSuggestedEdition(edition));
	}

	public Date getLastInsert() {
		return lastInsert;
	}

	public void setLastInsert(Date lastInsert) {
		this.lastInsert = lastInsert;
	}
}
