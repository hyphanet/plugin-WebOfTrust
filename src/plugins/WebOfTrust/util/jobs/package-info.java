/**
 * Implementations of {@link plugins.WebOfTrust.util.jobs.BackgroundJob BackgroundJob}. The ideas
 * behind their purpose are the following:<br>
 * 1) Often the change of a single object renders a non-time-critical batch computation in another
 *    thread necessary. For example the creation of a local event might require a server for network
 *    client connections to query the database for all clients which are connected by network and
 *    want to be noticed about that type of event. BackgroundJob allows running such batch-job
 *    threads in a convenient way.<br>
 * 2) The query which is triggered a single event might be inefficient if we do it with only one
 *    event as input: Running a database query can have quite a bit of overhead. Also, events might
 *    usually happen in large batches, so it doesn't make sense to start a query for every single
 *    event, we should rather wait for some events to accumulate before we process them.
 *    The {@link plugins.WebOfTrust.util.jobs.DelayedBackgroundJob DelayedBackgroundJob} in this
 *    package allows you to cause batch jobs to wait some time before they start processing.
 * 
 * @author bertm
 * @author xor (xor@freenetproject.org)
 */
package plugins.WebOfTrust.util.jobs;