/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import java.io.Serializable;
import java.util.UUID;

import plugins.WebOfTrust.SubscriptionManager.Client;
import plugins.WebOfTrust.SubscriptionManager.Notification;
import plugins.WebOfTrust.SubscriptionManager.Subscription;


/**
 * Must be implemented by any classes which can be monitored by a {@link Subscription}.<br>
 * Classes which implement it are those whose objects are what is actually being monitored
 * by the client, i.e. they are the data of interest.<br><br>
 * 
 * {@link Serializable} is implemented because once a object implementing EventSource generates an
 * event (by being changed), a serialized copy of it shall be stored in the database. The fact that
 * the serialized version is stored instead of the actual object allows storing "foreign" objects in
 * the event database table: The table won't have the fields of the EventSource-implementing
 * classes, but only generic fields which apply to all events.<br>
 * Also, the serialization stress the fact that events are and must be immutable: Events provide
 * a log of the past, and the past must not be changeable. This will even allow the optimization
 * of storing events out of the database as bare files - and bare files are what serialization
 * is about.
 */
public interface EventSource extends Serializable {
    /**
     * When a {@link Notification} about an {@link EventSource} is being deployed to a
     * {@link Client} by the {@link SubscriptionManager}, the {@link SubscriptionManager} will use
     * this function to assign a version to the {@link EventSource} before sending a copy of the
     * {@link EventSource} to the client.<br>
     * The client shall then obtain the version ID via {@link #getVersionID(UUID)}, and store it
     * in its database as part of the copy of the {@link EventSource}.<br>
     * This will allow the {@link SubscriptionManager} to instruct the {@link Client} to delete
     * all copies of an {@link EventSource} older than the most recent version ID, in a
     * "mark-and-sweep" garbage collection fashion. This is necessary as part of efficient
     * synchronization; see {@link SubscriptionManager} for an explanation of what "synchronization"
     * means in terms of events. 
     */
    public void setVersionID(final UUID versionID);
    
    /**
     * @see #setVersionID(UUID)
     */
    public void getVersionID(final UUID versionID);
}
