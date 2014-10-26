/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import plugins.WebOfTrust.SubscriptionManager.Subscription;


/**
 * Must be implemented by any classes which can be monitored by a {@link Subscription}.<br>
 * Classes which implement it are those whose objects are what is actually being monitored
 * by the client, i.e. they are the data of interest.>
 */
public interface EventSource {

}
