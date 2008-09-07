/**
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */

package plugins.WoT;

import java.io.IOException;
import java.io.InputStream;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import com.db4o.ObjectContainer;

import freenet.client.HighLevelSimpleClient;
import freenet.keys.FreenetURI;
import freenet.support.Logger;

/**
 * @author Julien Cornuwel (batosai@freenetproject.org)
 *
 */
public class IdentityParser {
	
	ObjectContainer db;
	HighLevelSimpleClient client;
	IdentityFetcher fetcher;
	SAXParser saxParser;
	Identity identity;
	
	public IdentityParser(ObjectContainer db, HighLevelSimpleClient client, IdentityFetcher fetcher) throws ParserConfigurationException, SAXException {

		this.db = db;
		this.client = client;
		this.fetcher = fetcher;
		saxParser = SAXParserFactory.newInstance().newSAXParser();
	}
	
	public void parse (InputStream is, FreenetURI uri) throws InvalidParameterException, SAXException, IOException, UnknownIdentityException, DuplicateIdentityException {

		identity = Identity.getByURI(db,uri);
		if(!(identity instanceof OwnIdentity)) identity.updated();
		identity.setEdition(uri.getSuggestedEdition());
		
		saxParser.parse(is, new IdentityHandler() );
		db.store(identity);
		
		Logger.debug(this, "Successfuly parsed identity '" + identity.getNickName() + "'");
	}
	
	public class IdentityHandler extends DefaultHandler {
		
		public IdentityHandler() {
			
		}
		
		public void startElement(String nameSpaceURI, String localName, String rawName, Attributes attrs) throws SAXException {
			
			String elt_name;
			if (rawName == null) elt_name = localName;
			else elt_name = rawName;

			try {
				if (elt_name.equals("nickName")) {
					identity.setNickName(attrs.getValue("value"));
				}
				if (elt_name.equals("publishTrustList")) {
					identity.setPublishTrustList(attrs.getValue("value"));
				}
				else if (elt_name.equals("prop")) {
					identity.setProp(attrs.getValue("key"), attrs.getValue("value"), db);
				}
				else if(elt_name.equals("context")) {
					identity.addContext(attrs.getValue("value"), db);
				}
				else if (elt_name.equals("trust")) {
	
					Identity trustee;
					try{
						trustee = Identity.getByURI(db, attrs.getValue("uri"));
						int value = Integer.parseInt(attrs.getValue("value"));
						String comment = attrs.getValue("comment");
		
						identity.setTrust(db, trustee, value, comment);
					}
					catch (UnknownIdentityException e) {
						
						// TODO Don't create Identity object before we succesfully fetched it !
						
						trustee = new Identity(attrs.getValue("uri"), "Not found yet...", "false", "test");
						db.store(trustee);
						fetcher.fetch(trustee); 
					}
											
				}	
			} catch (Exception e1) {
				Logger.error(this, "Parsing error",e1);
				e1.printStackTrace();
			}
		}
	}
}
