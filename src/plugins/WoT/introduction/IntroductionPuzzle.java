/*
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */
package plugins.WoT.introduction;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
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
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.query.Query;
import com.sun.org.apache.xerces.internal.impl.dv.util.Base64;

import freenet.crypt.SHA1;
import freenet.keys.FreenetURI;
import freenet.support.Logger;

import plugins.WoT.Identity;
import plugins.WoT.IdentityFetcher;
import plugins.WoT.OwnIdentity;
import plugins.WoT.Trustlist;
import plugins.WoT.WoT;
import plugins.WoT.IdentityParser.IdentityHandler;
import plugins.WoT.exceptions.InvalidParameterException;
import plugins.WoT.exceptions.UnknownIdentityException;

public class IntroductionPuzzle {
	
	public static final String INTRODUCTION_CONTEXT = "introduction";
	public static final int MINIMAL_SOLUTION_LENGTH = 5;
	
	private static final SimpleDateFormat mDateFormat = new SimpleDateFormat("yyyy-MM-dd");
	
	/* Included in XML: */
	
	private final String mMimeType;
	
	private final long mValidUntilTime;
	
	private final byte[] mData;
	
	/* Not included in XML, decoded from URI: */
	
	private final Identity mInserter;
	
	private final Date mDateOfInsertion;
	
	private final int mIndex;
	
	/* Supplied at creation time or by user: */
	
	private final String mSolution;
	
	
	/* FIXME: wire this in */
	/**
	 * Get a list of fields which the database should create an index on.
	 */
	public static String[] getIndexedFields() {
		return new String[] {"mInserter"};
	}
	
	/**
	 * For construction from a received puzzle.
	 * @param newType
	 * @param newData
	 */
	public IntroductionPuzzle(Identity newInserter, String newMimeType, byte[] newData, long myValidUntilTime, Date myDateOfInsertion, int myIndex) {
		assert(	newInserter != null && newMimeType != null && !newMimeType.equals("") &&
				newData!=null && newData.length!=0 && myValidUntilTime > System.currentTimeMillis() && myDateOfInsertion != null &&
				myDateOfInsertion.getTime() < System.currentTimeMillis() && myIndex >= 0);
		mInserter = newInserter;
		mMimeType = newMimeType;
		mData = newData;
		mSolution = null;
		mDateOfInsertion = myDateOfInsertion;
		mValidUntilTime = myValidUntilTime;
		mIndex = myIndex;
	}
	
	/**
	 * For construction of a puzzle which is meant to be inserted.
	 * @param newType
	 * @param newData
	 */
	public IntroductionPuzzle(Identity newInserter, String newMimeType, byte[] newData, String newSolution, int myIndex) {
		assert(	newInserter != null && newMimeType != null && !newMimeType.equals("") && newData!=null && newData.length!=0 &&
				newSolution!=null && newSolution.length()>=MINIMAL_SOLUTION_LENGTH && myIndex >= 0);
		mInserter = newInserter;
		mMimeType = newMimeType;
		mData = newData;
		mSolution = newSolution;
		mDateOfInsertion = new Date(); /* FIXME: get it in UTC */
		mValidUntilTime = System.currentTimeMillis() + IntroductionServer.PUZZLE_INVALID_AFTER_DAYS * 24 * 60 * 60 * 1000; /* FIXME: get it in UTC */
		mIndex = myIndex;
	}
	
	public static ObjectSet<IntroductionPuzzle> getByInserter(ObjectContainer db, OwnIdentity i) {
		Query q = db.query();
		q.constrain(IntroductionPuzzle.class);
		q.descend("mInserter").constrain(i);
		return q.execute();
	}
	
	public static IntroductionPuzzle getBySolutionURI(ObjectContainer db, FreenetURI uri) throws ParseException {
		String[] tokens = uri.getDocName().split("|");
		String id = tokens[1];
		Date date = mDateFormat.parse(tokens[2]);
		int index = Integer.parseInt(tokens[3]);
		
		Query q = db.query();
		q.constrain(IntroductionPuzzle.class);
		q.descend("mInserter").descend("id").constrain(id);
		q.descend("mDateOfInsertion").constrain(date);
		q.descend("mIndex").constrain(index);
		ObjectSet<IntroductionPuzzle> result = q.execute();
		
		assert(result.size() == 1);
		
		return (result.hasNext() ? result.next() : null);
	}
	
	public String getMimeType() {
		return mMimeType;
	}
	
	/**
	 * Get the URI at which to insert this puzzle.
	 * SSK@asdfasdf.../WoT/introduction/yyyy-MM-dd|#.xml 
	 */
	public FreenetURI getURI() throws MalformedURLException {
		assert(mSolution != null); /* This function should only be needed by the introduction server, not by clients. */
		
		/* FIXME: I did not really understand the javadoc of FreenetURI. Please verify that the following code actually creates an URI
		 * which looks like the one I specified in the javadoc above this function. Thanks. */
		String dayOfInsertion = mDateFormat.format(mDateOfInsertion);
		FreenetURI baseURI = ((OwnIdentity)mInserter).getInsertURI().setKeyType("SSK");
		baseURI = baseURI.setDocName(WoT.WOT_CONTEXT + "/" + INTRODUCTION_CONTEXT);
		return baseURI.setMetaString(new String[] {dayOfInsertion + "|" + mIndex + ".xml"} );
	}
	
	public static FreenetURI generateRequestURI(Identity inserter, Date dateOfInsertion, int index) {
		assert(dateOfInsertion.before(new Date()));
		assert(index >= 0);
		
		/* FIXME: I did not really understand the javadoc of FreenetURI. Please verify that the following code actually creates an URI
		 * which looks like the one I specified in the javadoc above this function. Thanks. */
		String dayOfInsertion = mDateFormat.format(dateOfInsertion);
		FreenetURI baseURI = inserter.getRequestURI().setKeyType("SSK");
		baseURI = baseURI.setDocName(WoT.WOT_CONTEXT + "/" + INTRODUCTION_CONTEXT);
		return baseURI.setMetaString(new String[] {dayOfInsertion + "|" + index + ".xml"} );
	}
	
	
	/**
	 * Get the URI at which to look for a solution of this puzzle (if someone solved it)
	 */
	public FreenetURI getSolutionURI() {
		return getSolutionURI(mSolution);
	}
	
	/**
	 * Get the URI at which to insert the solution of this puzzle.
	 */
	public FreenetURI getSolutionURI(String guessOfSolution) {
		String dayOfInsertion = mDateFormat.format(mDateOfInsertion);
		return new FreenetURI("KSK", 	INTRODUCTION_CONTEXT + "|" +
								mInserter.getId() + "|" +
								dayOfInsertion + "|" +
								mIndex + "|" +
								guessOfSolution); /* FIXME: hash the solution!! */
	}
	
	public byte[] getPuzzle() {
		return mData;
	}
	
	/**
	 * Get the solution of the puzzle. Null if the puzzle was received and not locally generated.
	 */
	public String getSolution() {
		assert(mSolution != null); /* Whoever uses this function should not need to call it when there is no solution available */
		return mSolution;
	}
	
	public Identity getInserter() {
		return mInserter;
	}
	
	public Date getDateOfInsertion() {
		return mDateOfInsertion;
	}
	
	public long getValidUntilTime() {
		return mValidUntilTime;
	}
	
	public int getIndex() {
		return mIndex;
	}
	
	public void store(ObjectContainer db) {
		db.store(this);
		db.commit();
	}
	
	public static void deleteExpiredPuzzles(ObjectContainer db) {
		Query q = db.query();
		q.constrain(IntroductionPuzzle.class);
		q.descend("mValidUntilTime").constrain(System.currentTimeMillis()).smaller();
		ObjectSet<IntroductionPuzzle> result = q.execute();
		
		Logger.debug(IntroductionPuzzle.class, "Deleting " + result.size() + " expired puzzles.");
		for(IntroductionPuzzle p : result)
			db.delete(p);
		
		db.commit();
	}
	
	public static void deleteOldestPuzzles(ObjectContainer db, int puzzlePoolSize) {
		Query q = db.query();
		q.constrain(IntroductionPuzzle.class);
		q.descend("mSolution").constrain(null).identity(); /* FIXME: toad said constrain(null) is maybe broken. If this is true: Alternative would be: q.descend("mIdentity").constrain(OwnIdentity.class).not(); */
		q.descend("mValidUntilTime").orderAscending();
		ObjectSet<IntroductionPuzzle> result = q.execute();
		
		int deleteCount = result.size() - puzzlePoolSize;
		
		Logger.debug(IntroductionPuzzle.class, "Deleting " + deleteCount + " old puzzles.");
		while(deleteCount > 0) {
			db.delete(result.next());
			deleteCount--;
		}
		
		db.commit();
	}
	
	public void exportToXML(OutputStream os) throws TransformerException, ParserConfigurationException {
		// Create the output file
		StreamResult resultStream = new StreamResult(os);

		// Create the XML document
		DocumentBuilderFactory xmlFactory = DocumentBuilderFactory.newInstance();
		DocumentBuilder xmlBuilder = xmlFactory.newDocumentBuilder();
		DOMImplementation impl = xmlBuilder.getDOMImplementation();
		Document xmlDoc = impl.createDocument(null, "WoT", null);
		Element rootElement = xmlDoc.getDocumentElement();

		// Create the content
		Element puzzleTag = xmlDoc.createElement("IntroductionPuzzle");

		Element mimeTypeTag = xmlDoc.createElement("MimeType");
		mimeTypeTag.setAttribute("value", mMimeType);
		puzzleTag.appendChild(mimeTypeTag);
		
		Element validUntilTag = xmlDoc.createElement("ValidUntilTime");
		validUntilTag.setAttribute("value", Long.toString(mValidUntilTime));
		puzzleTag.appendChild(validUntilTag);
		
		Element dataTag = xmlDoc.createElement("Data");
		dataTag.setAttribute("value", Base64.encode(mData));
		puzzleTag.appendChild(dataTag);
		
		rootElement.appendChild(puzzleTag);

		DOMSource domSource = new DOMSource(xmlDoc);
		TransformerFactory transformFactory = TransformerFactory.newInstance();
		Transformer serializer = transformFactory.newTransformer();
		
		serializer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
		serializer.setOutputProperty(OutputKeys.INDENT,"yes");
		serializer.transform(domSource, resultStream);
	}
	
	public static IntroductionPuzzle importFromXML(ObjectContainer db, InputStream is, FreenetURI puzzleURI ) throws SAXException, IOException, ParserConfigurationException, UnknownIdentityException, ParseException {
		PuzzleHandler puzzleHandler = new PuzzleHandler(db, puzzleURI);
		SAXParserFactory.newInstance().newSAXParser().parse(is, puzzleHandler);
		
		db.store(puzzleHandler.getPuzzle());
		db.commit();
		
		return puzzleHandler.getPuzzle();
	}
	
	public static class PuzzleHandler extends DefaultHandler {
		private final Identity newInserter;
		private String newMimeType;
		private byte[] newData;
		private long newValidUntilTime;
		private Date newDateOfInsertion;
		private int newIndex;


		public PuzzleHandler(ObjectContainer db, FreenetURI puzzleURI) throws UnknownIdentityException, ParseException {
			super();
			newInserter = Identity.getByURI(db, puzzleURI);
			String filename = puzzleURI.getDocName().replaceAll(".xml", "");
			String tokens[] = filename.split("|");
			newDateOfInsertion = mDateFormat.parse(tokens[0]);
			newIndex = Integer.parseInt(tokens[1]);
		}

		/**
		 * Called by SAXParser for each XML element.
		 */
		public void startElement(String nameSpaceURI, String localName, String rawName, Attributes attrs) throws SAXException {
			String elt_name = rawName == null ? localName : rawName;

			try {
				if (elt_name.equals("MimeType")) {
					newMimeType = attrs.getValue("value");
				}
				else if (elt_name.equals("ValidUntilTime")) {
					newValidUntilTime = Long.parseLong(attrs.getValue("value"));
				}
				else if(elt_name.equals("Data")) {
					newData = Base64.decode(attrs.getValue("value"));
				}					
				else
					Logger.error(this, "Unknown element in puzzle: " + elt_name);
				
			} catch (Exception e1) {
				Logger.error(this, "Parsing error",e1);
			}
		}

		public IntroductionPuzzle getPuzzle() {
			return new IntroductionPuzzle(newInserter, newMimeType, newData, newValidUntilTime, newDateOfInsertion, newIndex);
		}
	}
}
