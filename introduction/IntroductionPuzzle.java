/*
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */
package plugins.WoT.introduction;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.UUID;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
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

import plugins.WoT.Identity;
import plugins.WoT.OwnIdentity;
import plugins.WoT.WoT;
import plugins.WoT.exceptions.UnknownIdentityException;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.query.Query;

import freenet.keys.FreenetURI;
import freenet.support.Base64;
import freenet.support.Logger;

public final class IntroductionPuzzle {
	
	public static enum PuzzleType { Captcha };
	
	public static final String INTRODUCTION_CONTEXT = "introduction";
	public static final int MINIMAL_SOLUTION_LENGTH = 5;
	
	private static final SimpleDateFormat mDateFormat = new SimpleDateFormat("yyyy-MM-dd");
	
	/* Included in XML: */
	
	private final UUID mID;
	
	private final PuzzleType mType;
	
	private final String mMimeType;
	
	private final long mValidUntilTime;
	
	private final byte[] mData;
	
	/* Not included in XML, decoded from URI: */
	
	private final Identity mInserter;
	
	private final Date mDateOfInsertion;
	
	private final int mIndex;
	
	/* Supplied at creation time or by user: */

	/**
	 * We store the solver of the puzzle so that we can insert the solution even if the node is shutdown directly after solving puzzles.
	 */
	private OwnIdentity mSolver = null;
	
	private String mSolution = null;


	/**
	 * Set to true after it was used for introducing a new identity. We keep used puzzles in the database until they expire for the purpose of
	 * being able to figure out free index values of new puzzles. Storing a few KiB for some days will not hurt.
	 */
	private boolean iWasSolved = false;
	
	
	/* FIXME: wire this in */
	/**
	 * Get a list of fields which the database should create an index on.
	 */
	public static String[] getIndexedFields() {
		return new String[] {"mID", "mInserter"};
	}
	
	/**
	 * For construction from a received puzzle.
	 * @param newType
	 * @param newData
	 */
	public IntroductionPuzzle(Identity newInserter, UUID newID, PuzzleType newType, String newMimeType, byte[] newData, long myValidUntilTime, Date myDateOfInsertion, int myIndex) {
		assert(	newInserter != null && newID != null && newType != null && newMimeType != null && !newMimeType.equals("") &&
				newData!=null && newData.length!=0 && myValidUntilTime > System.currentTimeMillis() && myDateOfInsertion != null &&
				myDateOfInsertion.getTime() < System.currentTimeMillis() && myIndex >= 0);
		mID = newID;
		mInserter = newInserter;
		mType = newType;
		mMimeType = newMimeType;
		mData = newData;
		mSolution = null;
		mDateOfInsertion = new Date(myDateOfInsertion.getYear(), myDateOfInsertion.getMonth(), myDateOfInsertion.getDate());
		mValidUntilTime = myValidUntilTime;
		mIndex = myIndex;
		checkConsistency();
	}
	
	/**
	 * For construction of a puzzle which is meant to be inserted.
	 * @param newType
	 * @param newData
	 */
	public IntroductionPuzzle(Identity newInserter, PuzzleType newType, String newMimeType, byte[] newData, String newSolution, Date newDateOfInsertion, int myIndex) {
		this(newInserter, UUID.randomUUID(), newType, newMimeType, newData, newDateOfInsertion.getTime() + IntroductionServer.PUZZLE_INVALID_AFTER_DAYS * 24 * 60 * 60 * 1000, newDateOfInsertion, myIndex);
		assert(newSolution!=null && newSolution.length()>=MINIMAL_SOLUTION_LENGTH);
		mSolution = newSolution;
		checkConsistency();
	}
	
	public static IntroductionPuzzle getByID(ObjectContainer db, UUID id) {
		Query q = db.query();
		q.constrain(IntroductionPuzzle.class);
		q.descend("mID").constrain(id);
		ObjectSet<IntroductionPuzzle> result = q.execute();
		
		assert(result.size() <= 1);
		
		return (result.hasNext() ? result.next() : null);
	}
	
	/**
	 * Used by the IntroductionServer for downloading solutions.
	 * @param db
	 * @param i
	 * @return
	 */
	public static ObjectSet<IntroductionPuzzle> getByInserter(ObjectContainer db, OwnIdentity i) {
		Query q = db.query();
		q.constrain(IntroductionPuzzle.class);
		q.descend("mInserter").constrain(i);
		q.descend("iWasSolved").constrain(new Boolean(false));
		return q.execute();
	}

	public static IntroductionPuzzle getByRequestURI(ObjectContainer db, FreenetURI uri) throws ParseException {
		String filename = uri.getDocName().replaceAll(".xml", "");
		String[] tokens = filename.split("[|]");
		Date date = mDateFormat.parse(tokens[2]);
		int index = Integer.parseInt(tokens[3]);
		
		Query q = db.query();
		q.constrain(IntroductionPuzzle.class);
		q.descend("mInserter").descend("id").constrain(Identity.getIdFromURI(uri));
		q.descend("mDateOfInsertion").constrain(date);
		q.descend("mIndex").constrain(index);
		ObjectSet<IntroductionPuzzle> result = q.execute();
		
		assert(result.size() == 1);
		
		return (result.hasNext() ? result.next() : null);
	}
	
	 /**
	  * Used by the IntroductionServer when a solution was downloaded to retrieve the IntroductionPuzzle object.
	  * @param db
	  * @param uri
	  * @return
	  * @throws ParseException
	  */
	public static IntroductionPuzzle getBySolutionURI(ObjectContainer db, FreenetURI uri) throws ParseException {
		UUID id = UUID.fromString(uri.getDocName().split("[|]")[3]);
		
		Query q = db.query();
		q.constrain(IntroductionPuzzle.class);
		q.descend("mID").constrain(id);
		ObjectSet<IntroductionPuzzle> result = q.execute();
		
		assert(result.size() <= 1);
		
		return (result.hasNext() ? result.next() : null);
	}
	
	/**
	 * Used by the IntroductionServer for inserting new puzzles.
	 * @param db
	 * @param id
	 * @param date
	 * @return
	 */
	public static int getFreeIndex(ObjectContainer db, OwnIdentity id, Date date) {
		Query q = db.query();
		q.constrain(IntroductionPuzzle.class);
		q.descend("mInserter").descend("id").constrain(id.getId());
		q.descend("mDateOfInsertion").constrain(new Date(date.getYear(), date.getMonth(), date.getDate()));
		q.descend("mIndex").orderDescending();
		ObjectSet<IntroductionPuzzle> result = q.execute();
		
		return result.size() > 0 ? result.next().getIndex()+1 : 0;
	}

	/**
	 * Used by the IntroductionClient for inserting solutions of solved puzzles.
	 * @param db
	 * @return
	 */
	public static ObjectSet<IntroductionPuzzle> getSolvedPuzzles(ObjectContainer db) {
		Query q = db.query();
		q.constrain(IntroductionPuzzle.class);
		q.descend("mSolver").constrain(null).identity().not();
		return q.execute();
	}
	
	public UUID getID() {
		return mID;
	}
	
	public PuzzleType getType() {
		return mType;
	}

	public String getMimeType() {
		return mMimeType;
	}

	/**
	 * Get the URI at which to insert this puzzle.
	 * SSK@asdfasdf...|WoT|introduction|yyyy-MM-dd|#.xml 
	 */
	public FreenetURI getInsertURI() {
		assert(mSolution != null); /* This function should only be needed by the introduction server, not by clients. */
		
		/* FIXME: I did not really understand the javadoc of FreenetURI. Please verify that the following code actually creates an URI
		 * which looks like the one I specified in the javadoc above this function. Thanks. */
		String dayOfInsertion = mDateFormat.format(mDateOfInsertion);
		FreenetURI baseURI = ((OwnIdentity)mInserter).getInsertURI().setKeyType("SSK");
		baseURI = baseURI.setDocName(WoT.WOT_CONTEXT + "|" + INTRODUCTION_CONTEXT + "|" + dayOfInsertion + "|" + mIndex + ".xml");
		return baseURI.setMetaString(null);
	}

	public FreenetURI getRequestURI() {
		return generateRequestURI(mInserter, mDateOfInsertion, mIndex);
	}

	public static FreenetURI generateRequestURI(Identity inserter, Date dateOfInsertion, int index) {
		assert(dateOfInsertion.before(new Date()));
		assert(index >= 0);
		
		/* FIXME: I did not really understand the javadoc of FreenetURI. Please verify that the following code actually creates an URI
		 * which looks like the one I specified in the javadoc above this function. Thanks. */
		String dayOfInsertion = mDateFormat.format(dateOfInsertion);
		FreenetURI baseURI = inserter.getRequestURI().setKeyType("SSK");
		baseURI = baseURI.setDocName(WoT.WOT_CONTEXT + "|" + INTRODUCTION_CONTEXT + "|" + dayOfInsertion + "|" + index + ".xml");
		return baseURI.setMetaString(null);
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
		return new FreenetURI("KSK",	WoT.WOT_CONTEXT + "|" +
										INTRODUCTION_CONTEXT + "|" +
										mInserter.getId() + "|" +
										mID + "|" +
										guessOfSolution); /* FIXME: hash the solution!! */
	}
	
	public byte[] getData() {
		return mData;
	}
	
	public String getDataBase64() {
		return Base64.encodeStandard(mData);
	}
	
	/**
	 * Get the solution of the puzzle. Null if the puzzle was received and not locally generated.
	 */
	public String getSolution() {
		assert(mSolution != null); /* Whoever uses this function should not need to call it when there is no solution available */
		return mSolution;
	}
	
	
	/**
	 * Used by the IntroductionServer to mark a puzzle as solved.
	 */
	public void setSolved() {
		iWasSolved = true;
	}
	
	/**
	 * Used by the IntroductionClient to mark a puzzle as solved
	 * @param solver
	 * @param solution
	 */
	public void setSolved(OwnIdentity solver, String solution) {
		iWasSolved = true;
		mSolver = solver;
		mSolution = solution;
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
		db.store(mID);
		db.store(mType);
		db.store(mDateOfInsertion);
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
	
	/**
	 * Used by the introduction client to delete old puzzles and replace them with new ones.
	 * @param db
	 * @param puzzlePoolSize
	 */
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
		
		Element idTag = xmlDoc.createElement("ID");
		idTag.setAttribute("value", mID.toString());
		puzzleTag.appendChild(idTag);

		Element typeTag = xmlDoc.createElement("Type");
		typeTag.setAttribute("value", mType.toString());
		puzzleTag.appendChild(typeTag);
		
		Element mimeTypeTag = xmlDoc.createElement("MimeType");
		mimeTypeTag.setAttribute("value", mMimeType);
		puzzleTag.appendChild(mimeTypeTag);
		
		Element validUntilTag = xmlDoc.createElement("ValidUntilTime");
		validUntilTag.setAttribute("value", Long.toString(mValidUntilTime));
		puzzleTag.appendChild(validUntilTag);
		
		Element dataTag = xmlDoc.createElement("Data");
		dataTag.setAttribute("value", Base64.encodeStandard(mData));
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
		
		puzzleHandler.getPuzzle().store(db);
		
		return puzzleHandler.getPuzzle();
	}
	
	public static class PuzzleHandler extends DefaultHandler {
		private final Identity newInserter;
		private UUID newID;
		private PuzzleType newType;
		private String newMimeType;
		private byte[] newData;
		private long newValidUntilTime;
		private Date newDateOfInsertion;
		private int newIndex;


		public PuzzleHandler(ObjectContainer db, FreenetURI puzzleURI) throws UnknownIdentityException, ParseException {
			super();
			newInserter = Identity.getByURI(db, puzzleURI);
			String filename = puzzleURI.getDocName().replaceAll(".xml", "");
			String tokens[] = filename.split("[|]");
			newDateOfInsertion = mDateFormat.parse(tokens[2]);
			newIndex = Integer.parseInt(tokens[3]);
		}

		/**
		 * Called by SAXParser for each XML element.
		 */
		public void startElement(String nameSpaceURI, String localName, String rawName, Attributes attrs) throws SAXException {
			String elt_name = rawName == null ? localName : rawName;

			try {
				if (elt_name.equals("ID")) {
					newID = UUID.fromString(attrs.getValue("value"));
				}
				else if (elt_name.equals("Type")) {
					newType = PuzzleType.valueOf(attrs.getValue("value"));
				}
				else if (elt_name.equals("MimeType")) {
					newMimeType = attrs.getValue("value");
				}
				else if (elt_name.equals("ValidUntilTime")) {
					newValidUntilTime = Long.parseLong(attrs.getValue("value"));
				}
				else if(elt_name.equals("Data")) {
					newData = Base64.decodeStandard(attrs.getValue("value"));
				}					
				else
					Logger.error(this, "Unknown element in puzzle: " + elt_name);
				
			} catch (Exception e1) {
				Logger.error(this, "Parsing error",e1);
			}
		}

		public IntroductionPuzzle getPuzzle() {
			return new IntroductionPuzzle(newInserter, newID, newType, newMimeType, newData, newValidUntilTime, newDateOfInsertion, newIndex);
		}
	}
	
	public boolean checkConsistency() {
		boolean result = true;
		if(mID == null) { Logger.error(this, "mID == null!"); result = false; }
		if(mType == null) { Logger.error(this, "mType == null!"); result = false; }
		if(mMimeType == null || !mMimeType.equals("image/jpeg")) { Logger.error(this, "mMimeType == " + mMimeType); result = false; }
		if(new Date(mValidUntilTime).before(new Date(2008-1900, 10, 10))) { Logger.error(this, "mValidUntilTime ==" + new Date(mValidUntilTime)); result = false; }
		if(mData == null || mData.length<100) { Logger.error(this, "mData == " + mData); result = false; }
		if(mInserter == null) { Logger.error(this, "mInserter == null"); result = false; }
		if(mDateOfInsertion == null || mDateOfInsertion.before(new Date(2008-1900, 10, 10))) { Logger.error(this, "mDateOfInsertion ==" + mDateOfInsertion); result = false; }
		if(mIndex < 0) { Logger.error(this, "mIndex == " + mIndex); result = false; }
		if(iWasSolved == true && (mSolver == null || mSolution == null)) { Logger.error(this, "iWasSolved but mSolver == " + mSolver + ", " + "mSolution == " + mSolution); result = false; }
		
		return result;
	}
}
