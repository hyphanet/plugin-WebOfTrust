package plugins.WebOfTrust;

/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
import java.util.Comparator;
import java.util.List;

import plugins.WebOfTrust.ui.fcp.FCPClientReferenceImplementation;
import plugins.WebOfTrust.ui.fcp.FCPInterface;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.config.Configuration;
import com.db4o.ext.DatabaseClosedException;
import com.db4o.ext.DatabaseReadOnlyException;
import com.db4o.ext.Db4oDatabase;
import com.db4o.ext.Db4oIOException;
import com.db4o.ext.Db4oUUID;
import com.db4o.ext.ExtObjectContainer;
import com.db4o.ext.InvalidIDException;
import com.db4o.ext.ObjectInfo;
import com.db4o.ext.StoredClass;
import com.db4o.ext.SystemInfo;
import com.db4o.foundation.NotSupportedException;
import com.db4o.query.Predicate;
import com.db4o.query.Query;
import com.db4o.query.QueryComparator;
import com.db4o.reflect.ReflectClass;
import com.db4o.reflect.generic.GenericReflector;
import com.db4o.replication.ReplicationConflictHandler;
import com.db4o.replication.ReplicationProcess;
import com.db4o.types.Db4oCollections;

import freenet.pluginmanager.PluginRespirator;

/**
 * This is a minimal implementor of the abstract base class {@link WebOfTrustInterface}.
 * It basically does nothing - it won't maintain a database, it won't store anything, it won't do any processing.
 * The purpose of this implementor is to allow you to easily copy-paste the core-classes into your own plugin WITHOUT copying the main
 * class {@link WebOfTrust}.
 * This will be necessary when copy-pasting the reference implementation of a WOT client {@link FCPClientReferenceImplementation}.
 * 
 * An implementor of the {@link WebOfTrustInterface} is needed by constructors and functions of the core classes which represent a WOT:
 * - {@link Persistent}
 * - {@link Identity} & {@link OwnIdentity}
 * - {@link Trust}
 * - {@link Score}
 *
 * @seee WebOfTrustInterface
 * @author xor (xor@freenetproject.org)
 */
@SuppressWarnings("deprecation")
public final class MockWebOfTrust extends WebOfTrustInterface {

	private final MockExtObjectContainer mDatabase = new MockExtObjectContainer();
	
    @Override public List<Identity> getAllIdentities() {
        throw new UnsupportedOperationException("Not implemented.");
    }

    @Override public List<Trust> getAllTrusts() {
        throw new UnsupportedOperationException("Not implemented.");
    }

    @Override public List<Score> getAllScores() {
        throw new UnsupportedOperationException("Not implemented.");
    }
	
	@Override
	protected PluginRespirator getPluginRespirator() {
		throw new UnsupportedOperationException("Not implemented.");
	}

	@Override
	protected ExtObjectContainer getDatabase() {
		return mDatabase;
	}

	@Override
	protected SubscriptionManager getSubscriptionManager() {
		throw new UnsupportedOperationException("Not implemented.");
	}

	@Override
	protected FCPInterface getFCPInterface() {
		throw new UnsupportedOperationException("Not implemented.");
	}

	/**
	 * The main goal of this fake {@link ExtObjectContainer} is to allow {@link Identity}/{@link Trust}/{@link Score} objects to
	 * work. I've implemented all functions which are necessary for this.
	 * 
	 * I've made all other functions throw {@link UnsupportedOperationException} to ensure that people don't accidentally try to
	 * use this container as a real one. 
	 */
	private final class MockExtObjectContainer implements ExtObjectContainer {
		@Override
		public void store(Object arg0) throws DatabaseClosedException, DatabaseReadOnlyException {
		}
		
		@Override
		public void set(Object arg0) throws DatabaseClosedException, DatabaseReadOnlyException {
			throw new UnsupportedOperationException("Not implemented.");
		}
		
		@Override
		public void rollback() throws Db4oIOException, DatabaseClosedException, DatabaseReadOnlyException {
			// Throw instead of doing nothing to ensure that people don't accidentally believe a MockWebOfTrust's container CAN
			// undo stuff by rolling back
			throw new UnsupportedOperationException("Not implemented.");
		}
		
		@Override
		public <T> ObjectSet<T> queryByExample(Object arg0) throws Db4oIOException, DatabaseClosedException {
			throw new UnsupportedOperationException("Not implemented.");
		}
		
		@Override
		public <TargetType> ObjectSet<TargetType> query(Predicate<TargetType> arg0, Comparator<TargetType> arg1) throws Db4oIOException,
				DatabaseClosedException {
			throw new UnsupportedOperationException("Not implemented.");
		}
		
		@Override
		public <TargetType> ObjectSet<TargetType> query(Predicate<TargetType> arg0, QueryComparator<TargetType> arg1) throws Db4oIOException,
				DatabaseClosedException {
			throw new UnsupportedOperationException("Not implemented.");
		}
		
		@Override
		public <TargetType> ObjectSet<TargetType> query(Predicate<TargetType> arg0) throws Db4oIOException, DatabaseClosedException {
			throw new UnsupportedOperationException("Not implemented.");
		}
		
		@Override
		public <TargetType> ObjectSet<TargetType> query(Class<TargetType> arg0) throws Db4oIOException, DatabaseClosedException {
			throw new UnsupportedOperationException("Not implemented.");
		}
		
		@Override
		public Query query() throws DatabaseClosedException {
			throw new UnsupportedOperationException("Not implemented.");
		}
		
		@Override
		public <T> ObjectSet<T> get(Object arg0) throws Db4oIOException, DatabaseClosedException {
			throw new UnsupportedOperationException("Not implemented.");
		}
		
		@Override
		public ExtObjectContainer ext() {
			return this;
		}
		
		@Override
		public void delete(Object arg0) throws Db4oIOException, DatabaseClosedException, DatabaseReadOnlyException {
			// Should be safe to allow deletion to succeed:
			// This class does NOT support database queries so the deleted object won't be accessible through it.
		}
		
		@Override
		public void deactivate(Object arg0, int arg1) throws DatabaseClosedException {
			throw new UnsupportedOperationException("Not implemented.");
		}
		
		@Override
		public void commit() throws Db4oIOException, DatabaseClosedException, DatabaseReadOnlyException {
			throw new UnsupportedOperationException("Not implemented.");
		}
		
		@Override
		public boolean close() throws Db4oIOException {
			throw new UnsupportedOperationException("Not implemented.");
		}
		
		@Override
		public void activate(Object arg0, int arg1) throws Db4oIOException, DatabaseClosedException {
		}
		
		@Override
		public long version() {
			throw new UnsupportedOperationException("Not implemented.");
		}
		
		@Override
		public SystemInfo systemInfo() {
			throw new UnsupportedOperationException("Not implemented.");
		}
		
		@Override
		public StoredClass[] storedClasses() {
			throw new UnsupportedOperationException("Not implemented.");
		}
		
		@Override
		public StoredClass storedClass(Object arg0) {
			throw new UnsupportedOperationException("Not implemented.");
		}
		
		@Override
		public void store(Object arg0, int arg1) {
			throw new UnsupportedOperationException("Not implemented.");
		}
		
		@Override
		public boolean setSemaphore(String arg0, int arg1) {
			throw new UnsupportedOperationException("Not implemented.");
		}
		
		@Override
		public void set(Object arg0, int arg1) {
			throw new UnsupportedOperationException("Not implemented.");
		}
		
		@Override
		public ReplicationProcess replicationBegin(ObjectContainer arg0, ReplicationConflictHandler arg1) {
			throw new UnsupportedOperationException("Not implemented.");
		}
		
		@Override
		public void releaseSemaphore(String arg0) {
			throw new UnsupportedOperationException("Not implemented.");
		}
		
		@Override
		public void refresh(Object arg0, int arg1) {
			throw new UnsupportedOperationException("Not implemented.");
		}
		
		@Override
		public GenericReflector reflector() {
			throw new UnsupportedOperationException("Not implemented.");
		}
		
		@Override
		public void purge(Object arg0) {
			throw new UnsupportedOperationException("Not implemented.");
		}
		
		@Override
		public void purge() {
			throw new UnsupportedOperationException("Not implemented.");
		}
		
		@Override
		public <T> T peekPersisted(T arg0, int arg1, boolean arg2) {
			throw new UnsupportedOperationException("Not implemented.");
		}
		
		@Override
		public void migrateFrom(ObjectContainer arg0) {
			throw new UnsupportedOperationException("Not implemented.");
		}
		
		@Override
		public Object lock() {
			throw new UnsupportedOperationException("Not implemented.");
		}
		
		@Override
		public ReflectClass[] knownClasses() {
			throw new UnsupportedOperationException("Not implemented.");
		}
		
		@Override
		public boolean isStored(Object arg0) throws DatabaseClosedException {
			// This function is typically used to detect whether we have to delete() something from the database so it would be safe to
			// return false. But SubscriptionManager also uses it to detect whether we have to send stuff to the Client: If the Client
			// is stored, it shall send things. So it is better to return true. Notice I don't plan to ever shove a MockWebOfTrust into the
			// SubscriptionManager but I would like to assume that people might do it by accident in the future.
			return true; 
		}
		
		@Override
		public boolean isClosed() {
			throw new UnsupportedOperationException("Not implemented.");
		}
		
		@Override
		public boolean isCached(long arg0) {
			throw new UnsupportedOperationException("Not implemented.");
		}
		
		@Override
		public boolean isActive(Object arg0) {
			throw new UnsupportedOperationException("Not implemented.");
		}
		
		@Override
		public Db4oDatabase identity() {
			throw new UnsupportedOperationException("Not implemented.");
		}
		
		@Override
		public ObjectInfo getObjectInfo(Object arg0) {
			// We must implement this since it is used by Persistent.toString(). Null is an acceptable return value for toString().
			return null;
		}
		
		@Override
		public long getID(Object arg0) {
			throw new UnsupportedOperationException("Not implemented.");
		}
		
		@Override
		public <T> T getByUUID(Db4oUUID arg0) throws DatabaseClosedException, Db4oIOException {
			throw new UnsupportedOperationException("Not implemented.");
		}
		
		@Override
		public <T> T getByID(long arg0) throws DatabaseClosedException, InvalidIDException {
			throw new UnsupportedOperationException("Not implemented.");
		}
		
		@Override
		public Object descend(Object arg0, String[] arg1) {
			throw new UnsupportedOperationException("Not implemented.");
		}
		
		@Override
		public void deactivate(Object arg0) {
			// Used by Identity.activateProperties()
			// It is safe to do nothing: Objects which are not stored in a real ObjectContainer are always active
		}
		
		@Override
		public Configuration configure() {
			throw new UnsupportedOperationException("Not implemented.");
		}
		
		@Override
		public Db4oCollections collections() {
			throw new UnsupportedOperationException("Not implemented.");
		}
		
		@Override
		public void bind(Object arg0, long arg1) throws InvalidIDException, DatabaseClosedException {
			throw new UnsupportedOperationException("Not implemented.");
		}
		
		@Override
		public void backup(String arg0) throws Db4oIOException, DatabaseClosedException, NotSupportedException {
			throw new UnsupportedOperationException("Not implemented.");
		}
		
		@Override
		public void activate(Object arg0) throws Db4oIOException, DatabaseClosedException {
			throw new UnsupportedOperationException("Not implemented.");
		}
	}
}
