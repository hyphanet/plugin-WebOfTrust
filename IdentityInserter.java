/**
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */

package plugins.WoT;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Iterator;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;

import plugins.WoT.exceptions.InvalidParameterException;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.ext.DatabaseClosedException;
import com.db4o.ext.Db4oIOException;

import freenet.client.ClientMetadata;
import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.HighLevelSimpleClient;
import freenet.client.InsertBlock;
import freenet.client.InsertContext;
import freenet.client.InsertException;
import freenet.client.async.BaseClientPutter;
import freenet.client.async.ClientCallback;
import freenet.client.async.ClientGetter;
import freenet.client.async.ClientPutter;
import freenet.keys.FreenetURI;
import freenet.support.Logger;
import freenet.support.api.Bucket;
import freenet.support.io.Closer;
import freenet.support.io.TempBucketFactory;

/**
 * Inserts OwnIdentities to Freenet when they need it.
 * 
 * @author Julien Cornuwel (batosai@freenetproject.org)
 *
 */
public class IdentityInserter implements Runnable, ClientCallback {
	
	private static final int STARTUP_DELAY = 1 * 60 * 1000;
	private static final int THREAD_PERIOD = 45 * 60 * 1000;
	
	/** A reference to the database */
	ObjectContainer db;
	/** A reference the HighLevelSimpleClient used to perform inserts */
	HighLevelSimpleClient client;
	/** The TempBucketFactory used to create buckets from Identities before insert */
	final TempBucketFactory tBF;
	/** Used to tell the InserterThread if it should stop */
	private volatile boolean isRunning = false;
	private volatile boolean shutdownFinished = false;
	private Thread mThread;
	
	private final ArrayList<BaseClientPutter> mInserts = new ArrayList<BaseClientPutter>(10); /* Just assume that there are 10 identities */
	
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
		Logger.debug(this, "Identity inserter thread started.");
		
		try {
			Thread.sleep((long) (STARTUP_DELAY * (0.5f + Math.random()))); // Let the node start up
		}
		catch (InterruptedException e)
		{
			mThread.interrupt();
		}
		try {
		while(isRunning) {
			Thread.interrupted();
			Logger.debug(this, "IdentityInserter loop running...");
			cancelInserts(); /* FIXME: check whether this does not prevent the cancelled inserts from being restarted in the loop right now */
			ObjectSet<OwnIdentity> identities = OwnIdentity.getAllOwnIdentities(db);
			while(identities.hasNext()) {
				OwnIdentity identity = identities.next();
				/* FIXME: Where is the synchronization? */
				if(identity.needsInsert()) {
					try {
						Logger.debug(this, "Starting insert of "+identity.getNickName() + " (" + identity.getInsertURI().toString() + ")");
						insert(identity);
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
				Logger.debug(this, "Identity inserter thread interrupted.");
			}
		}
		}
		
		finally {
		cancelInserts();
		synchronized (this) {
			shutdownFinished = true;
			Logger.debug(this, "Identity inserter thread finished.");
			notify();
		}
		}
	}
	
	private void cancelInserts() {
		Logger.debug(this, "Trying to stop all inserts");
		synchronized(mInserts) {
			Iterator<BaseClientPutter> i = mInserts.iterator();
			int icounter = 0;
			while (i.hasNext()) { i.next().cancel(); i.remove(); ++icounter; }
			Logger.debug(this, "Stopped " + icounter + " current inserts");
		}
	}
	
	/**
	 * Stops the IdentityInserter thread.
	 */
	public void stop() {
		Logger.debug(this, "Stopping IdentityInserter thread...");
		isRunning = false;
		mThread.interrupt();
		synchronized(this) {
			while(!shutdownFinished) {
				try {
					wait();
				}
				catch (InterruptedException e) {
					Thread.interrupted();
				}
			}
		}
		Logger.debug(this, "Stopped IdentityInserter thread.");
	}
	
	public void wakeUp() {
		if(mThread != null)
			mThread.interrupt(); /* FIXME: toad: i hope this will not break any of the code which is NOT the sleep() function??? */
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
	private synchronized void insert(OwnIdentity identity) throws TransformerConfigurationException, FileNotFoundException, ParserConfigurationException, TransformerException, IOException, Db4oIOException, DatabaseClosedException, InvalidParameterException, InsertException {
		/* FIXME: Where is the synchronization? */
		/* TODO: after the WoT has become large enough, calculate the average size of identity.xml and either modify the constant or even calculate dynamically */
		Bucket tempB = tBF.makeBucket(8 * 1024);  
		OutputStream os = null;

		try {
			if(identity.getEdition() == 0)
				identity.setEdition(1);
			
			os = tempB.getOutputStream();
			// Create XML file to insert
			identity.exportToXML(db, os);
		
			os.close(); os = null;
			tempB.setReadOnly();
		
			// Prepare the insert
			ClientMetadata cmd = new ClientMetadata("text/xml");
			InsertBlock ib = new InsertBlock(tempB,cmd,identity.getInsertURI());
			InsertContext ictx = client.getInsertContext(true);
			
			/* FIXME: are these parameters correct? */
			ClientPutter pu = client.insert(ib, false, "identity.xml", false, ictx, this);
			// pu.setPriorityClass(RequestStarter.UPDATE_PRIORITY_CLASS);	/* pluginmanager defaults to interactive priority */
			synchronized(mInserts) {
				mInserts.add(pu);
			}
			tempB = null;
			
			Logger.debug(this, "Started insert of identity '" + identity.getNickName() + "'");
		}
		catch(Exception e) {
			Logger.error(this,"Error during insert of identity '" + identity.getNickName() + "'", e);
		}
		finally {
			if(tempB != null)
				tempB.free();
			Closer.close(os);
		}
	}
	
	private void removeInsert(BaseClientPutter p) {
		synchronized(mInserts) {
			//p.cancel(); /* FIXME: is this necessary ? */
			mInserts.remove(p);
		}
		Logger.debug(this, "Removed insert for " + p.getURI());
	}
	
	// Only called by inserts
	public void onSuccess(BaseClientPutter state, ObjectContainer container)
	{
		try {
			OwnIdentity identity = OwnIdentity.getByURI(db, state.getURI());
			identity.setEdition(state.getURI().getSuggestedEdition());
			identity.updateLastInsert(); /* FIXME: check whether the identity was modified during the insert and re-insert if it was */
			 
			db.store(identity);
			db.commit();
			Logger.debug(this, "Successful insert of identity '" + identity.getNickName() + "'");
		} catch(Exception e) { Logger.error(this, "Error", e); }
		removeInsert(state);
	}
	
	// Only called by inserts
	public void onFailure(InsertException e, BaseClientPutter state, ObjectContainer container) 
	{
		try {
			OwnIdentity identity = OwnIdentity.getByURI(db, state.getURI());

			Logger.error(this, "Error during insert of identity '" + identity.getNickName() + "'", e);
		} catch(Exception ex) { Logger.error(this, "Error", e); }
		removeInsert(state);
	}
	
	/* Not needed functions from the ClientCallback interface */
	
	public void onFailure(FetchException e, ClientGetter state, ObjectContainer container) {
		// TODO Auto-generated method stub
		
	}

	public void onFetchable(BaseClientPutter state, ObjectContainer container) {
		// TODO Auto-generated method stub
		
	}

	public void onGeneratedURI(FreenetURI uri, BaseClientPutter state, ObjectContainer container) {
		// TODO Auto-generated method stub
		
	}

	public void onMajorProgress(ObjectContainer container) {
		// TODO Auto-generated method stub
		
	}

	public void onSuccess(FetchResult result, ClientGetter state, ObjectContainer container) {
		// TODO Auto-generated method stub
		
	}
}

