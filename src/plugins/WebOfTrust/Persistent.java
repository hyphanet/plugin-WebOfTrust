/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package plugins.WebOfTrust;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;

import com.db4o.ObjectSet;
import com.db4o.ext.ExtObjectContainer;
import com.db4o.ext.ExtObjectSet;
import com.db4o.ext.ObjectInfo;
import com.db4o.query.Query;

import freenet.support.CurrentTimeUTC;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.io.Closer;


/**
 * ATTENTION: This class is duplicated in the Freetalk plugin. Backport any changes!
 * 
 * This is the base class for all classes which are stored in the web of trust database.<br /><br />
 * 
 * It provides common functions which are needed for storing, updating, retrieving and deleting objects.
 * 
 * @author xor (xor@freenetproject.org)
 */
public abstract class Persistent implements Serializable {
	
	/** @see Serializable */
	private static transient final long serialVersionUID = 1L;

	/**
	 * A reference to the {@link WebOfTrust} object with which this Persistent object is associated.
	 */
	protected transient WebOfTrust mWebOfTrust;
	
	/**
	 * A reference to the database in which this Persistent object resists.
	 */
	protected transient ExtObjectContainer mDB;
	
	
	/**
	 * The date when this persistent object was created. 
	 * - This is contained in class Persistent because it is something which we should store for all persistent objects:
	 * It can be very useful for debugging purposes or sanitizing old databases.
	 * Also it is needed in many cases for the UI.
	 */
	protected final Date mCreationDate = CurrentTimeUTC.get();
	
	/**
	 * The object used for locking transactions.
	 * Since we only support one open database at a moment there is only one.
	 */
	private static transient final Object mTransactionLock = new Object();
	
	/* These booleans are used for preventing the construction of log-strings if logging is disabled (for saving some cpu cycles) */
	
	protected static transient volatile boolean logDEBUG = false;
	protected static transient volatile boolean logMINOR = false;
	
	static {
		Logger.registerClass(Persistent.class);
	}
	

	/**
	 * This annotation should be added to all member variables (of Persistent classes) which the database should be configured to generate an index on.
	 * 
	 * If you want to make a field of a parent class indexed only via adding this annotation in the child class, add this annotation to the child class
	 * as a class-annotation and set the "name" parameter of the annotation to the name of the field.
	 * 
	 * If a class has indexed fields you MUST add it to the list of persistent classes in {@link WebOfTrust.openDatabase} 
	 */
	@Target( {ElementType.FIELD, ElementType.TYPE} )
	@Retention( RetentionPolicy.RUNTIME )
	public @interface IndexedField {
		String[] names() default {""};
	}

	/**
	 * This annotation should be added to all Persistent classes which the database should be configured to generate an index on.
	 * If a class is indexed you MUST add it to the list of persistent classes in {@link WebOfTrust.openDatabase} 
	 */
	public @interface IndexedClass { }
	
	public void testDatabaseIntegrity() {
		testDatabaseIntegrity(mWebOfTrust, mDB);
	}
	
	/**
	 * This function can be used for debugging, it is executed before and after store(), delete() and commit().
	 */
	public static void testDatabaseIntegrity(WebOfTrust mWebOfTrust, ExtObjectContainer db) {

	}
	
	/**
	 * This function has to be implemented by all child classes. It is executed by startup on all persistent objects to test their integrity.
	 */
	public abstract void startupDatabaseIntegrityTest() throws Exception;
	
	/**
	 * A version of {@link #startupDatabaseIntegrityTest()} which is suitable for use in assert() statements.
	 * 
	 * @return True if {@link #startupDatabaseIntegrityTest()} did not throw, false if it threw an Exception.
	 */
	public boolean startupDatabaseIntegrityTestBoolean() {
		try {
			startupDatabaseIntegrityTest();
			return true;
		} catch(Exception e) {
			Logger.error(this, "startupDatabaseIntegrityTestBoolean() failed", e);
			return false;
		}
	}
	
	/**
	 * Must be called once after obtaining this object from the database before using any getter or setter member functions
	 * and before calling storeWithoutCommit / deleteWithoutCommit.
	 * Transient fields are NOT stored in the database. They are references to objects such as the IdentityManager.
	 */
	public final void initializeTransient(final WebOfTrust myWebOfTrust) {
		mWebOfTrust = myWebOfTrust;
		mDB = mWebOfTrust.getDatabase();
	}
	
	/**
	 * @deprecated Only for being used when dealing with objects which are from a different object container than the passed Freetalk uses.
	 */
	@Deprecated
	public final void initializeTransient(final WebOfTrust myWebOfTrust, final ExtObjectContainer db) {
		mWebOfTrust = myWebOfTrust;
		mDB = db;
	}
	
	/**
	 * Returns the lock for creating a transaction.
	 * A proper transaction typically looks like this:
	 * synchronized(Persistent.transactionLock(db)) { try { ... do stuff ... Persistent.checkedCommit() } catch(RuntimeException e) { Persistent.checkedRollback(); } }
	 * 
	 * The db parameter is currently ignored - the same lock will be returned for all databases!
	 * We don't need multi-database support in Freetalk yet.
	 */
	public static final Object transactionLock(ExtObjectContainer db) {
		return mTransactionLock;
	}

	/**
	 * Only to be used by the extending classes, not to be called from the outside.
	 * 
	 * Used by storeWithoutCommit/deleteWithoutCommit to check whether an object is active before storing it.<br /><br />
	 * 
	 * Logs an error if the object is not active.<br /><br />
	 * 
	 * Activates the object to the specified depth.<br /><br />
	 */
	protected final void checkedActivate(final Object object, final int depth) {
		mDB.activate(object, depth);
	}
	
	/**
	 * Only to be used by the extending classes, not to be called from the outside.
	 * 
	 * Same as a call to {@link checkedActivate(this, depth)}
	 */
	protected final void checkedActivate(final int depth) {
		checkedActivate(this, depth);
	}
	
	/**
	 * Activate this object to full depth so that all members are active.
	 * 
	 * Typically you would override this to adapt it to the maximal activation depth of all your getters.
	 * Then you would use it in your override implementation of {@link #storeWithoutCommit()} or {@link #deleteWithoutCommit()}.
	 */
	protected void activateFully() {
		checkedActivate(1);
	}
	
	/**
	 * Only to be used by the extending classes, not to be called from the outside.
	 * 
	 * Used by storeWithoutCommit for actually storing the object.<br /><br />
	 * 
	 * Currently does not any additional checks, it is used to 
	 * @param object
	 */
	protected final void checkedStore(final Object object) {
		testDatabaseIntegrity();
		mDB.store(object);
		testDatabaseIntegrity();
	}
	
	/**
	 * Only to be used by the extending classes, not to be called from the outside.
	 * 
	 * Same as a call to {@link checkedStore(this)}
	 */
	protected final void checkedStore() {
		checkedStore(this);
	}
	
	/**
	 * Only to be used by the extending classes, not to be called from the outside.
	 * 
	 * Checks whether an object is stored in the database and deletes it if it is.
	 * If it was not found in the database, an error is logged.<br /><br />
	 * 
	 * This is to be used as an integrity check in deleteWithoutCommit() implementations. 
	 */
	protected final void checkedDelete(final Object object) {
		testDatabaseIntegrity();
		if(mDB.isStored(object))
			mDB.delete(object);
		else
			Logger.warning(this, "Trying to delete a inexistent object: " + object, new RuntimeException()); // Exception added to get a stack trace
		testDatabaseIntegrity();
	}
	
	/**
	 * Only to be used by the extending classes, not to be called from the outside.
	 * 
	 * Same as a call to {@link checkedDelete(Object object)}
	 */
	protected final void checkedDelete() {
		checkedDelete(this);
	}
	
	
	/**
	 * Only to be used by the extending classes, not to be called from the outside.
	 * 
	 * Checks whether the given object is stored in the database already and throws a RuntimeException if it is not.<br /><br />
	 * 
	 * This function is to be used as an integrity check in storeWithoutCommit() implementations which require that objects to which
	 * this object references have been stored already.
	 */
	protected final void throwIfNotStored(final Object object) {
		if(object == null) {
			assert(false);
			Logger.error(this, "Mandatory object is null!");
			throw new RuntimeException("Mandatory object is null!"); 
		}
		
		if(!mDB.isStored(object)) {
			assert(false);
			Logger.error(this, "Mandatory object is not stored: " + object);
			throw new RuntimeException("Mandatory object is not stored: " + object);
		}
	}
	
	/**
	 * Checks whether this object is stored in the database already and throws a RuntimeException if it is not.<br /><br />
	 */
	public final void throwIfNotStored() {
		throwIfNotStored(this);
	}
	
	/**
	 * This is one of the only functions which outside classes should use.  Rolls back the current transaction and logs the passed exception. 
	 * The call to this function must be embedded in a transaction, that is a block of:<br />
	 * synchronized(Persistent.transactionLock(mDB)) {<br />
	 * 	try { object.storeWithoutCommit(); object.checkedCommit(this); }<br />
	 * 	catch(RuntimeException e) { Persistent.checkedRollback(mDB, this, e); }<br />
	 * }
	 * 
	 * @param db The database on which the rollback shall happen.
	 * @param loggingObject The object whose class shall appear in the Freenet log file.
	 * @param error The Exception which triggered the rollback. Will be logged to the Freenet log file.
	 * @param logLevel The {@link LogLevel} to use in the Freenet log file when the rollback is logged.
	 */
	public static final void checkedRollback(final ExtObjectContainer db, final Object loggingObject, final Throwable error, LogLevel logLevel) {
		// As of db4o 7.4 it seems necessary to call gc(); to cause rollback() to work.
		testDatabaseIntegrity(null, db);
		System.gc();
		db.rollback();
		System.gc(); 
		Logger.logStatic(loggingObject, "ROLLED BACK!", error, logLevel);
		testDatabaseIntegrity(null, db);
	}
	
	/**
	 * This is one of the only functions which outside classes should use.  Rolls back the current transaction and logs the passed exception with LogLevel {@link LogLevel.ERROR}.
	 * The call to this function must be embedded in a transaction, that is a block of:<br />
	 * synchronized(Persistent.transactionLock(mDB)) {<br />
	 * 	try { object.storeWithoutCommit(); object.checkedCommit(this); }<br />
	 * 	catch(RuntimeException e) { Persistent.checkedRollback(mDB, this, e); }<br />
	 * } 
	 * 	
	 * @param db The database on which the rollback shall happen.
	 * @param loggingObject The object whose class shall appear in the Freenet log file.
	 * @param error The Exception which triggered the rollback. Will be logged to the Freenet log file.
	 */
	public static final void checkedRollback(final ExtObjectContainer db, final Object loggingObject, final Throwable error) {
		checkedRollback(db, loggingObject, error, LogLevel.ERROR);
	}
	

	/**
	 * This is one of the only functions which outside classes should use.  Rolls back the current transaction, logs the passed exception and throws it.
	 * The call to this function must be embedded in a transaction, that is a block of:<br />
	 * synchronized(Persistent.transactionLock(mDB)) {<br />
	 * 	try { object.storeWithoutCommit(); object.checkedCommit(this); }<br />
	 * 	catch(RuntimeException e) { Persistent.checkedRollbackAndThrow(mDB, this, e); }<br />
	 * } 
	 */
	public static final void checkedRollbackAndThrow(final ExtObjectContainer db, final Object loggingObject, final RuntimeException error) {
		checkedRollback(db, loggingObject, error);
		throw error;
	}
	
	/**
	 * Only to be used by the extending classes, not to be called from the outside.
	 * To be used when writing your own {@link storeWithoutCommit}, look at this function to see how it is used.
	 */
	protected final void checkedRollbackAndThrow(final RuntimeException error) {
		checkedRollbackAndThrow(mDB, this, error);
	}
	

	/**
	 * Only to be used by the extending classes, not to be called from the outside.
	 * 
	 * When your extending class needs a different activation depth for store than 1, you have to override storeWithoutCommit() and make it call this function.
	 * If you need to store other objects than this object (that is member objects) then you might want to copy the body of this function so that 
	 * checkedActivate() is not called twice.
	 * 
	 * @param activationDepth The desired activation depth.
	 */
	protected void storeWithoutCommit(final int activationDepth) {
		try {		
			// 1 is the maximal depth of all getter functions. You have to adjust this when introducing new member variables.
			checkedActivate(activationDepth);
			checkedStore();
		}
		catch(final RuntimeException e) {
			checkedRollbackAndThrow(e);
		}
	}
	
	/**
	 * This is one of the only functions which outside classes should use. It is used for storing the object.
	 * The call to this function must be embedded in a transaction, that is a block of:<br />
	 * synchronized(Persistent.transactionLock(mDB)) {<br />
	 * 	try { object.storeWithoutCommit(); object.checkedCommit(this); }<br />
	 * 	catch(RuntimeException e) { Persistent.checkedRollbackAndThrow(mDB, this, e); }<br />
	 * } 
	 */
	protected void storeWithoutCommit() {
		storeWithoutCommit(1);
	}
	
	/**
	 * Only to be used by the extending classes, not to be called from the outside.
	 * 
	 * When your extending class needs a different activation depth for store than 1, you have to override storeWithoutCommit() and make it call this function.
	 * If you need to store other objects than this object (that is member objects) then you might want to copy the body of this function so that 
	 * checkedActivate() is not called twice.
	 * 
	 * @param activationDepth The desired activation depth.
	 */
	protected void deleteWithoutCommit(final int activationDepth) {
		try {
			// 1 is the maximal depth of all getter functions. You have to adjust this when introducing new member variables.
			checkedActivate(activationDepth);
			checkedDelete(this);
		}
		catch(final RuntimeException e) {
			checkedRollbackAndThrow(e);
		}
	}
	
	/**
	 * This is one of the only functions which outside classes should use. It is used for deleting the object.
	 * The call to this function must be embedded in a transaction, that is a block of:<br />
	 * synchronized(Persistent.transactionLock(mDB)) {<br />
	 * 	try { object.storeWithoutCommit(); object.checkedCommit(this); }<br />
	 * 	catch(RuntimeException e) { Persistent.checkedRollbackAndThrow(mDB, this, e); }<br />
	 * } 
	 */
	protected void deleteWithoutCommit() {
		deleteWithoutCommit(1);
	}
	

	/**
	 * This is one of the only functions which outside classes should use. It is used for committing the transaction. 
	 * The call to this function must be embedded in a transaction, that is a block of:<br />
	 * synchronized(Persistent.transactionLock(mDB)) {<br />
	 * 	try { object.storeWithoutCommit(); Persistent.checkedCommit(mDB, this); }<br />
	 * 	catch(RuntimeException e) { Persistent.checkedRollbackAndThrow(mDB, this, e); }<br />
	 * } 
	 */
	public static final void checkedCommit(final ExtObjectContainer db, final Object loggingObject) {
		testDatabaseIntegrity(null, db);
		db.commit();
		if(logDEBUG) Logger.debug(loggingObject, "COMMITED.");
		testDatabaseIntegrity(null, db);
	}
	
	/**
	 * This is one of the only functions which outside classes should use. It is used for committing the transaction.
	 * The call to this function must be embedded in a transaction, that is a block of:<br />
	 * synchronized(Persistent.transactionLock(mDB)) {<br />
	 * 	try { object.storeWithoutCommit(); object.checkedCommit(this); }<br />
	 * 	catch(RuntimeException e) { Persistent.checkedRollbackAndThrow(mDB, this, e); }<br />
	 * } 
	 * 
	 * Notice that this function is not final to allow implementing classes to override it for making it visible in their package.
	 */
	protected void checkedCommit(final Object loggingObject) {
		checkedCommit(mDB, loggingObject);
	}
	
	/**
	 * Get the date when this persistent object was created.
	 * This date is stored in the database so it is constant for a given persistent object.
	 */
	public final Date getCreationDate() {
		checkedActivate(1); // Date is a db4o primitive type so 1 is enough
		return mCreationDate;
	}
	
	/**
	 * Returns the java object ID and the database object ID of this Persistent object.
	 * Notice: The database object ID can change between restarts if we defragment the database.
	 */
	@Override
	public String toString() {
		final String databaseID;
		
		if(mDB == null)
			databaseID = "mDB==null!";
		else {
			final ObjectInfo oi = mDB.getObjectInfo(this);
			if(oi == null)
				databaseID = "object not stored";
			else
				databaseID = Long.toString(oi.getInternalID());
		}
		
		return super.toString() + " (databaseID: " + databaseID + ")";
	}
	
	/**
	 * An implementation of ObjectSet which encapsulates a given ObjectSet of objects which extend Persistent and calls initializeTransient() for each returned object
	 * automatically.
	 */
	public static final class InitializingObjectSet<Type extends Persistent> implements ObjectSet<Type> {
		
		private final WebOfTrust mWebOfTrust;
		private final ObjectSet<Type> mObjectSet;
		
		@SuppressWarnings("unchecked") 	// "ObjectSet<Type> myObjectSet" won't compile against db4o-7.12 so we use the Suppress trick
		public InitializingObjectSet(final WebOfTrust myWebOfTrust, @SuppressWarnings("rawtypes") final ObjectSet myObjectSet) {
			mWebOfTrust = myWebOfTrust;
			mObjectSet = (ObjectSet<Type>)myObjectSet;
		}
		
		public InitializingObjectSet(final WebOfTrust myWebOfTrust, final Query myQuery) {
			this(myWebOfTrust, myQuery.execute());
		}
	
		public ExtObjectSet ext() {
			throw new UnsupportedOperationException();
		}

		public boolean hasNext() {
			return mObjectSet.hasNext();
		}

		public Type next() {
			final Type next = mObjectSet.next();
			next.initializeTransient(mWebOfTrust);
			return next;
		}

		public void reset() {
			mObjectSet.reset();
		}

		public int size() {
			return mObjectSet.size();
		}

		public boolean add(final Type e) {
			throw new UnsupportedOperationException();
		}

		public void add(final int index, final Type element) {
			throw new UnsupportedOperationException();
		}

		public boolean addAll(final Collection<? extends Type> c) {
			throw new UnsupportedOperationException();
		}

		public boolean addAll(final int index, final Collection<? extends Type> c) {
			throw new UnsupportedOperationException();
		}

		public void clear() {
			throw new UnsupportedOperationException();
		}

		public boolean contains(final Object o) {
			return mObjectSet.contains(o);
		}

		public boolean containsAll(final Collection<?> c) {
			return mObjectSet.containsAll(c);
		}

		public Type get(final int index) {
			Type object = mObjectSet.get(index);
			object.initializeTransient(mWebOfTrust);
			return object;
		}

		public int indexOf(final Object o) {
			return mObjectSet.indexOf(o);
		}

		public boolean isEmpty() {
			return mObjectSet.isEmpty();
		}

		public final Iterator<Type> iterator() {
			return new Iterator<Type>() {
				final Iterator<Type> mIterator = mObjectSet.iterator(); 
				
				public boolean hasNext() {
					return mIterator.hasNext();
				}

				public Type next() {
					final Type next = mIterator.next();
					next.initializeTransient(mWebOfTrust);
					return next;
				}

				public void remove() {
					throw new UnsupportedOperationException();
				}
				
			};
		}

		public int lastIndexOf(final Object o) {
			return mObjectSet.lastIndexOf(o);
		}

		private final class InitializingListIterator<ListType extends Persistent> implements ListIterator<ListType> {
			private final ListIterator<ListType> mIterator;
			
			public InitializingListIterator(final ListIterator<ListType> myIterator) {
				 mIterator = myIterator;
			}

			public void add(final ListType e) {
				throw new UnsupportedOperationException();
			}

			public boolean hasNext() {
				return mIterator.hasNext();
			}

			public boolean hasPrevious() {
				return mIterator.hasPrevious();
			}

			public ListType next() {
				final ListType next = mIterator.next();
				next.initializeTransient(mWebOfTrust);
				return next;
			}

			public int nextIndex() {
				return mIterator.nextIndex();
			}

			public ListType previous() {
				final ListType previous = mIterator.previous();
				previous.initializeTransient(mWebOfTrust);
				return previous;
			}

			public int previousIndex() {
				return mIterator.previousIndex();
			}

			public void remove() {
				throw new UnsupportedOperationException();
			}

			public void set(final ListType e) {
				throw new UnsupportedOperationException();
			}
		}
		
		public ListIterator<Type> listIterator() {
			return new InitializingListIterator<Type>(mObjectSet.listIterator());
		}
		
		public ListIterator<Type> listIterator(final int index) {
			return new InitializingListIterator<Type>(mObjectSet.listIterator(index));
		}

		public boolean remove(final Object o) {
			throw new UnsupportedOperationException();
		}

		public Type remove(final int index) {
			throw new UnsupportedOperationException();
		}

		public boolean removeAll(final Collection<?> c) {
			throw new UnsupportedOperationException();
		}

		public boolean retainAll(final Collection<?> c) {
			throw new UnsupportedOperationException();
		}

		public Type set(final int index, final Type element) {
			throw new UnsupportedOperationException();
		}

		public List<Type> subList(final int fromIndex, final int toIndex) {
			throw new UnsupportedOperationException();
		}

		public Object[] toArray() {
			throw new UnsupportedOperationException("ObjectSet provides array functionality already.");
		}

		public <T> T[] toArray(final T[] a) {
			throw new UnsupportedOperationException("ObjectSet provides array functionality already.");
		}

		public void remove() {
			throw new UnsupportedOperationException();
		}

	}
	
	
	/* Non-db4o related code */

	/**
	 * Uses standard Java serialization to convert this Object to a byte array. NOT used by db4o.
	 * 
	 * The purpose for this is to allow in-db4o storage of cloned {@link Identity}/{@link Trust}/{@link Score}/etc. objects:
	 * Normally there should only be one object with a given ID in the database, if we clone a Persistent object it will have the same ID.
	 * If we store objects as a byte[] instead of using native db4o object storage, we can store those duplcates.
	 * 
	 * Typically used by {@link SubscriptionManager} for being able to store clones.
	 * 
	 * ATTENTION: Your Persistent class must provide an implementation of the following function:
	 * <code>private void writeObject(ObjectOutputStream stream) throws IOException;</code>
	 * This function is not specified by an interface, it can be read up about in the <a href="http://docs.oracle.com/javase/7/docs/platform/serialization/spec/output.html#861">serialization documentation</a>.
	 * It must properly activate the object, all of its members and all of their members:
	 * serialize() will store all members and their members. If they are not activated, this will fail.
	 * After that, it must call {@link ObjectOutputStream#defaultWriteObject()}.
	 * 
	 * @see Persistent#deserialize(WebOfTrust, byte[]) The inverse function.
	 */
	final byte[] serialize() {
		ByteArrayOutputStream bos = null;
		ObjectOutputStream ous = null;
		
		try {
			bos = new ByteArrayOutputStream();
			ous = new ObjectOutputStream(bos);
			ous.writeObject(this);	
			ous.flush();
			return bos.toByteArray();
		} catch(IOException e) {
			throw new RuntimeException(e);
		} finally {
			Closer.close(ous);
			Closer.close(bos);
		}
	}
	
	/** Inverse function of {@link #serialize()}. */
	static final Persistent deserialize(final WebOfTrust wot, final byte[] data) {
		ByteArrayInputStream bis = null;
		ObjectInputStream ois = null;
		
		try {
			bis = new ByteArrayInputStream(data);
			ois = new ObjectInputStream(bis);
			final Persistent deserialized = (Persistent)ois.readObject();
			deserialized.initializeTransient(wot);
			assert(deserialized.startupDatabaseIntegrityTestBoolean());
			return deserialized;
		} catch(IOException e) {
			throw new RuntimeException(e);
		} catch (ClassNotFoundException e) {
			throw new RuntimeException(e);
		} finally {
			Closer.close(ois);
			Closer.close(bis);
		}
	}
	
}
