/**
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */

package plugins.WoT;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Date;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;

import plugins.WoT.exceptions.InvalidParameterException;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.ext.DatabaseClosedException;
import com.db4o.ext.Db4oIOException;

import freenet.client.ClientMetadata;
import freenet.client.HighLevelSimpleClient;
import freenet.client.InsertBlock;
import freenet.client.InsertException;
import freenet.keys.FreenetURI;
import freenet.support.Logger;
import freenet.support.api.Bucket;
import freenet.support.io.TempBucketFactory;

/**
 * Inserts OwnIdentities to Freenet when they need it.
 * 
 * @author Julien Cornuwel (batosai@freenetproject.org)
 *
 */
public class IdentityInserter implements Runnable {
	
	private static final int THREAD_PERIOD = 30 * 60 * 1000;
	
	/** A reference to the database */
	ObjectContainer db;
	/** A reference the HighLevelSimpleClient used to perform inserts */
	HighLevelSimpleClient client;
	/** The TempBucketFactory used to create buckets from Identities before insert */
	final TempBucketFactory tBF;
	/** Used to tell the InserterThread if it should stop */
	private boolean isRunning;
	private Thread mThread;
	
	/**
	 * Creates an IdentityInserter.
	 * 
	 * @param db A reference to the database
	 * @param client A reference to an {@link HighLevelSimpleClient} to perform inserts
	 * @param tbf Needed to create buckets from Identities before insert
	 */
	public IdentityInserter(ObjectContainer db, HighLevelSimpleClient client, TempBucketFactory tbf) {
		this.db = db;
		this.client = client;
		isRunning = true;
		tBF = tbf;
	}
	
	/**
	 * Starts the IdentityInserter thread. About every 30 minutes (+/- 50%),
	 * it exports to XML and inserts OwnIdentities that need it.
	 */
	public void run() {
		mThread = Thread.currentThread();
		
		try {
			Thread.sleep((long) (3*60*1000 * (0.5f + Math.random()))); // Let the node start up
		}
		catch (InterruptedException e)
		{
			mThread.interrupt();
		}
		while(isRunning) {
			Logger.debug(this, "IdentityInserter loop running...");
			ObjectSet<OwnIdentity> identities = OwnIdentity.getAllOwnIdentities(db);
			while(identities.hasNext()) {
				OwnIdentity identity = identities.next();
				/* FIXME: Where is the synchronization? */
				if(identity.needsInsert()) {
					try {
						Logger.debug(this, "Starting insert of "+identity.getNickName() + " (" + identity.getInsertURI().toString() + ")");
						insert(identity);
						// We set the date now, so if the identity is modified during the insert, we'll insert it again next time
						identity.setLastInsert(new Date()); 
						db.store(identity);
					} catch (Exception e) {
						Logger.error(this, "Identity insert failed: "+e.getMessage(), e);
					}
				}
			}
			db.commit();
			Logger.debug(this, "IdentityInserter loop finished...");
			try {
				Thread.sleep((long) (THREAD_PERIOD * (0.5f + Math.random())));
			}
			catch (InterruptedException e)
			{
				mThread.interrupt();
			}
		}
	}
	
	/**
	 * Stops the IdentityInserter thread.
	 */
	public void stop() {
		Logger.debug(this, "Stopping IdentityInserter thread...");
		isRunning = false;
		mThread.interrupt();
		try {
			mThread.join();
		}
		catch(InterruptedException e)
		{
			Thread.currentThread().interrupt();
		}
		Logger.debug(this, "Stopped IdentityInserter thread.");
	}

	/**
	 * Inserts an OwnIdentity.
	 * 
	 * @param identity the OwnIdentity to insert
	 * @throws TransformerConfigurationException
	 * @throws FileNotFoundException
	 * @throws ParserConfigurationException
	 * @throws TransformerException
	 * @throws IOException
	 * @throws Db4oIOException
	 * @throws DatabaseClosedException
	 * @throws InvalidParameterException
	 * @throws InsertException
	 */
	public void insert(OwnIdentity identity) throws TransformerConfigurationException, FileNotFoundException, ParserConfigurationException, TransformerException, IOException, Db4oIOException, DatabaseClosedException, InvalidParameterException, InsertException {
		/* FIXME: Where is the synchronization? */
		/* TODO: after the WoT has become large enough, calculate the average size of identity.xml and either modify the constant or even calculate dynamically */
		Bucket tempB = tBF.makeBucket(8 * 1024);  
		OutputStream os = tempB.getOutputStream();
		FreenetURI iURI;
		try {
			// Create XML file to insert
			identity.exportToXML(db, os);
		
			os.close();
			tempB.setReadOnly();
		
			// Prepare the insert
			ClientMetadata cmd = new ClientMetadata("text/xml");
			InsertBlock ib = new InsertBlock(tempB,cmd,identity.getInsertURI());

			// Logging
			Logger.debug(this, "Started insert of identity '" + identity.getNickName() + "'");

			/* FIXME: use nonblocking insert */
			// Blocking Insert
			iURI = client.insert(ib, false, "identity.xml");
		} finally {
			tempB.free();		
		}
		
		identity.setEdition(iURI.getSuggestedEdition());
		identity.setLastInsert(new Date());
		
		db.store(identity);
		db.commit();
		
		// Logging
		Logger.debug(this, "Successful insert of identity '" + identity.getNickName() + "'");
	}
}
