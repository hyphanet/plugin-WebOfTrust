/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.ui.web;

import static freenet.support.TimeUtil.formatTime;
import static java.lang.Math.max;
import static java.util.concurrent.TimeUnit.HOURS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static plugins.WebOfTrust.Configuration.DEFAULT_DEFRAG_INTERVAL;
import static plugins.WebOfTrust.Configuration.DEFAULT_VERIFY_SCORES_INTERVAL;
import static plugins.WebOfTrust.ui.web.CommonWebUtils.formatTimeDelta;
import static plugins.WebOfTrust.util.CollectionUtil.arrayList;
import static plugins.WebOfTrust.util.CollectionUtil.ignoreNulls;
import static plugins.WebOfTrust.util.plotting.XYChartUtils.differentiate;
import static plugins.WebOfTrust.util.plotting.XYChartUtils.getTimeBasedPlotPNG;
import static plugins.WebOfTrust.util.plotting.XYChartUtils.movingAverage;
import static plugins.WebOfTrust.util.plotting.XYChartUtils.multiplyY;

import java.io.IOException;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.TimeUnit;

import plugins.WebOfTrust.Configuration;
import plugins.WebOfTrust.Identity;
import plugins.WebOfTrust.IdentityFile;
import plugins.WebOfTrust.IdentityFileProcessor;
import plugins.WebOfTrust.IdentityFileQueue;
import plugins.WebOfTrust.IdentityFileQueue.IdentityFileQueueStatistics;
import plugins.WebOfTrust.SubscriptionManager;
import plugins.WebOfTrust.WebOfTrust;
import plugins.WebOfTrust.introduction.IntroductionPuzzleStore;
import plugins.WebOfTrust.network.input.EditionHint;
import plugins.WebOfTrust.network.input.IdentityDownloaderFast;
import plugins.WebOfTrust.network.input.IdentityDownloaderFast.IdentityDownloaderFastStatistics;
import plugins.WebOfTrust.network.input.IdentityDownloaderSlow;
import plugins.WebOfTrust.network.input.IdentityDownloaderSlow.IdentityDownloaderSlowStatistics;
import plugins.WebOfTrust.ui.web.WebInterface.StatisticsPNGWebInterfaceToadlet;
import plugins.WebOfTrust.util.Pair;
import plugins.WebOfTrust.util.plotting.XYChartUtils;
import plugins.WebOfTrust.util.plotting.XYChartUtils.TimeChart;
import freenet.clients.http.ToadletContext;
import freenet.l10n.BaseL10n;
import freenet.support.CurrentTimeUTC;
import freenet.support.HTMLNode;
import freenet.support.TimeUtil;
import freenet.support.api.HTTPRequest;

/**
 * The StatisticsPage of the plugin.
 * 
 * @author xor (xor@freenetproject.org)
 * @author Julien Cornuwel (batosai@freenetproject.org)
 */
public class StatisticsPage extends WebPageImpl {

	/**
	 * Creates a new StatisticsPage.
	 * 
	 * @param toadlet A reference to the {@link WebInterfaceToadlet} which created the page, used to get resources the page needs.
	 * @param myRequest The request sent by the user.
	 */
	public StatisticsPage(WebInterfaceToadlet toadlet, HTTPRequest myRequest, ToadletContext context) {
		super(toadlet, myRequest, context);
	}

	@Override
	public void make(final boolean mayWrite) {
		makeSummary();
		makePlotBox();
		makeIdentityDownloaderFastBox();
		makeIdentityDownloaderSlowBox();
		makeIdentityDownloaderSlowQueueBox();
		makeIdentityFileQueueBox();
		makeIdentityFileProcessorBox();
		makeMaintenanceBox();
	}

	/**
	 * Creates a short summary of what the plugin knows of the WoT.
	 */
	private void makeSummary() {
		HTMLNode box = addContentBox(l10n().getString("StatisticsPage.SummaryBox.Header"));
		HTMLNode list = new HTMLNode("ul");
		
        // TODO: Performance: All the synchronized() can be removed after this is fixed:
        // https://bugs.freenetproject.org/view.php?id=6247
		synchronized(mWebOfTrust) {
		list.addChild(new HTMLNode("li", l10n().getString("StatisticsPage.SummaryBox.OwnIdentities") + ": " + mWebOfTrust.getAllOwnIdentities().size()));
		list.addChild(new HTMLNode("li", l10n().getString("StatisticsPage.SummaryBox.KnownIdentities") + ": " + mWebOfTrust.getAllNonOwnIdentities().size()));
		list.addChild(new HTMLNode("li", l10n().getString("StatisticsPage.SummaryBox.UnfetchedIdentities") + " " + mWebOfTrust.getNumberOfUnfetchedIdentities()));
		list.addChild(new HTMLNode("li", l10n().getString("StatisticsPage.SummaryBox.FetchProgress", "editionCount", Long.toString(getEditionSum()))));
		list.addChild(new HTMLNode("li", l10n().getString("StatisticsPage.SummaryBox.TrustRelationships") + ": " + mWebOfTrust.getAllTrusts().size()));
		list.addChild(new HTMLNode("li", l10n().getString("StatisticsPage.SummaryBox.ScoreRelationships") + ": " + mWebOfTrust.getAllScores().size()));
		list.addChild(new HTMLNode("li", l10n().getString("StatisticsPage.SummaryBox.FullRecomputations") + ": " + mWebOfTrust.getNumberOfFullScoreRecomputations()));
		list.addChild(new HTMLNode("li", l10n().getString("StatisticsPage.SummaryBox.FullRecomputationTime") + ": " + mWebOfTrust.getAverageFullScoreRecomputationTime()));
		list.addChild(new HTMLNode("li", l10n().getString("StatisticsPage.SummaryBox.IncrementalTrustRecomputations") + " " + mWebOfTrust.getNumberOfIncrementalScoreRecomputationDueToTrust()));
		list.addChild(new HTMLNode("li", l10n().getString("StatisticsPage.SummaryBox.IncrementalTrustRecomputationTime") + " " + mWebOfTrust.getAverageTimeForIncrementalScoreRecomputationDueToTrust()));
		list.addChild(new HTMLNode("li", l10n().getString("StatisticsPage.SummaryBox.IncrementalDistrustRecomputations") + " " + mWebOfTrust.getNumberOfIncrementalScoreRecomputationDueToDistrust()));
		list.addChild(new HTMLNode("li", l10n().getString("StatisticsPage.SummaryBox.IncrementalDistrustRecomputationTime") + " " + mWebOfTrust.getAverageTimeForIncrementalScoreRecomputationDueToDistrust()));
		list.addChild(new HTMLNode("li", l10n().getString("StatisticsPage.SummaryBox.IncrementalDistrustRecomputationsSlow") + mWebOfTrust.getNumberOfSlowIncrementalScoreRecomputationDueToDistrust()));
		list.addChild(new HTMLNode("li", l10n().getString("StatisticsPage.SummaryBox.IncrementalDistrustRecomputationTimeSlow") + mWebOfTrust.getAverageTimeForSlowIncrementalScoreRecomputationDueToDistrust()));
		IntroductionPuzzleStore puzzleStore = mWebOfTrust.getIntroductionPuzzleStore();
		synchronized(puzzleStore) {
		list.addChild(new HTMLNode("li", l10n().getString("StatisticsPage.SummaryBox.UnsolvedOwnCaptchas") + ": " + puzzleStore.getOwnCatpchaAmount(false)));
		list.addChild(new HTMLNode("li", l10n().getString("StatisticsPage.SummaryBox.SolvedOwnCaptchas") + ": " + puzzleStore.getOwnCatpchaAmount(true)));
		list.addChild(new HTMLNode("li", l10n().getString("StatisticsPage.SummaryBox.UnsolvedCaptchasOfOthers") + ": " + puzzleStore.getNonOwnCaptchaAmount(false)));
		list.addChild(new HTMLNode("li", l10n().getString("StatisticsPage.SummaryBox.SolvedCaptchasOfOthers") + ": " + puzzleStore.getNonOwnCaptchaAmount(true)));
		list.addChild(new HTMLNode("li", l10n().getString("StatisticsPage.SummaryBox.NotInsertedCaptchasSolutions") + ": " + puzzleStore.getUninsertedSolvedPuzzles().size()));
		}
		}

		SubscriptionManager sm = mWebOfTrust.getSubscriptionManager();
		synchronized(sm) {
		    list.addChild(new HTMLNode("li",
		        l10n().getString("StatisticsPage.SummaryBox.EventNotifications.Pending", "amount",
		            Integer.toString(sm.getPendingNotificationAmount()))));
		    list.addChild(new HTMLNode("li",
		        l10n().getString("StatisticsPage.SummaryBox.EventNotifications.Total", "amount",
		            Long.toString(sm.getTotalNotificationsAmountForCurrentClients()))));
		}
		
		box.addChild(list);
	}

	private void makePlotBox() {
		HTMLNode box = addContentBox(l10n().getString("StatisticsPage.PlotBox.Header"));
		for(StatisticsPlotType type : StatisticsPlotType.values())
			box.addChild("img", "src", type.getURI(mWebInterface).toString());
	}

	/** A renderer for a {@link StatisticsPlotType}. */
	public static interface StatisticsPlotRenderer {
		/** Returns an image of the PNG format, serialized to a byte array.
		 *  It is recommended to use {@link XYChartUtils} to implement this. */
		public byte[] getPNG(WebOfTrust wot);
	}

	/**
	 * Each value of this enum defines a {@link StatisticsPlotRenderer#getPNG(WebOfTrust)} to render
	 * the associated plot.
	 * 
	 * The PNG images of all values of this enum will automatically be served by
	 * {@link StatisticsPNGWebInterfaceToadlet} at the URI as obtainable by
	 * {@link #getURI(WebInterface)}.
	 * All images are automatically added to the HTML of the StatisticsPage, using that URI, by
	 * {@link StatisticsPage#makePlotBox()}.
	 * 
	 * Thus to add a new type of statistics all you have to do is add a new value to this enum with
	 * the associated {@link StatisticsPlotRenderer} passed to its constructor. */
	public static enum StatisticsPlotType implements StatisticsPlotRenderer {
		TotalDownloadCount(new StatisticsPlotRenderer() {
			/**
			 * Renders a chart where the X-axis is the uptime of WoT, and the Y-axis is the total
			 * number of downloaded {@link IdentityFile}s.
			 * 
			 * @see IdentityFileQueueStatistics#mTimesOfQueuing */
			@Override public byte[] getPNG(WebOfTrust wot) {
				IdentityFileQueue q = wot.getIdentityFileQueue();
				
				TimeChart<Integer> chartOld;
				try {
					IdentityFileQueueStatistics s = q.getStatisticsOfLastSession();
					chartOld = new TimeChart<>(s.mTimesOfQueuing, s.mStartupTimeMilliseconds);
					chartOld.setLabel("StatisticsPage.PlotBox.LastSession");
				} catch (IOException e) {
					// No data of previous session available
					chartOld = null;
				}
				
				// Add a dummy entry for the current time to the end of the plot so refreshing the
				// image periodically shows that it is live even when there is no progress.
				// The return value of q.getStatistics() is safe to be modified here, it returns a
				// clone().
				TimeChart<Integer> chartNew = appendCurrentTimeDummy(q.getStatistics());
				chartNew.setLabel("StatisticsPage.PlotBox.CurrentSession");
				
				String l10n = "StatisticsPage.PlotBox.TotalDownloadCountPlot.";
				return getTimeBasedPlotPNG(wot.getBaseL10n(), l10n + "Title", l10n + "XAxis.Hours",
					l10n + "XAxis.Minutes",  l10n + "YAxis",
					ignoreNulls(arrayList(chartNew, chartOld)));
			}
			
			private TimeChart<Integer> appendCurrentTimeDummy(IdentityFileQueueStatistics stats) {
				long t0 = stats.mStartupTimeMilliseconds;
				TimeChart<Integer> timesOfQueuing = new TimeChart<>(stats.mTimesOfQueuing, t0);
				
				double currentTime
					= (double)(CurrentTimeUTC.getInMillis() - t0) / SECONDS.toMillis(1);
				// peekLast() will always work: IdentityFileQueueStatistics specifies it to always
				// contain at least one entry.
				timesOfQueuing.addLast(new Pair<>(currentTime, timesOfQueuing.peekLast().y));
				
				return timesOfQueuing;
			}
		}),
		DownloadsPerHour(new StatisticsPlotRenderer() {
			/**
			 * Renders a chart where the X-axis is the uptime of WoT, and the Y-axis is the number
			 * of downloaded {@link IdentityFile}s per hour.
			 * This is calculated by differentiating the total download count.
			 * 
			 * @see IdentityFileQueueStatistics#mTimesOfQueuing */
			@Override public byte[] getPNG(WebOfTrust wot) {
				IdentityFileQueue q = wot.getIdentityFileQueue();
				
				TimeChart<Double> chartOld;
				try {
					chartOld = calculateDownloadsPerHour(q.getStatisticsOfLastSession());
					chartOld.setLabel("StatisticsPage.PlotBox.LastSession");
				} catch (IOException e) {
					// No data of previous session available
					chartOld = null;
				}
				
				IdentityFileQueueStatistics stats = q.getStatistics();
				TimeChart<Double> chartNew = calculateDownloadsPerHour(stats);
				chartNew.setLabel("StatisticsPage.PlotBox.CurrentSession");
				
				// Ensure the resulting dataset contains an entry for the current time so refreshing
				// the image periodically shows that it is live even when there is no progress.
				appendCurrentTimeDummy(chartNew, stats.mStartupTimeMilliseconds);
				
				String l10n = "StatisticsPage.PlotBox.DownloadsPerHourPlot.";
				return getTimeBasedPlotPNG(wot.getBaseL10n(), l10n + "Title", l10n + "XAxis.Hours",
					l10n + "XAxis.Minutes",  l10n + "YAxis",
					ignoreNulls(arrayList(chartNew, chartOld)));
			}
			
			private TimeChart<Double> calculateDownloadsPerHour(IdentityFileQueueStatistics stats) {
				TimeChart<Integer> timesOfQueuing
					= new TimeChart<>(stats.mTimesOfQueuing, stats.mStartupTimeMilliseconds);
				
				// - Build the average before differentiating to prevent a jumpy graph due to
				//   fred delivering batches of many files at once for internal reasons.
				// - FIXME: Consider applying movingAverage() again after differentiation to make
				//   the graph even less jumpy. Though this can wait until the
				//   IdentityDownloaderSlow has received a mechanism for auto-adjusting its number
				//   of concurrent downloads to ensure the set of running downloads doesn't
				//   run very empty about every minute, which probably is a major reason for the
				//   jumpiness.
				// - Convert to hours before differentiating to aid the "dy/dx" division in
				//   preserving floating point accuracy.
				TimeChart<Double> downloadsPerHour
					= differentiate(
						multiplyY(movingAverage(timesOfQueuing, 60), HOURS.toSeconds(1))
					);
				
				// Ensure the resulting dataset is not empty.
				// differentiate() will return at most size() - 1 elements, so addFirst() won't
				// discard the tail element even if our input RingBuffer was full.
				downloadsPerHour.addFirst(new Pair<>(0d, 0d));
				
				return downloadsPerHour;
			}

			private void appendCurrentTimeDummy(TimeChart<Double> chart, long t0) {
				double currentTime
					= (double)(CurrentTimeUTC.getInMillis() - t0) / SECONDS.toMillis(1);
				chart.addLast(new Pair<>(currentTime,
					chart.size() > 0 ? chart.peekLast().y : 0d));
			}

		});

		private final StatisticsPlotRenderer mRenderer;
	
		private StatisticsPlotType(StatisticsPlotRenderer r) {
			mRenderer = r;
		}
	
		/**
		 * TODO: Code quality: Java 8: In Java 8 the values of the enum will be able to implement
		 * this directly without the indirection of the mRenderer variable:
		 * https://stackoverflow.com/a/50472201 */
		@Override public byte[] getPNG(WebOfTrust wot) {
			return mRenderer.getPNG(wot);
		}

		/** Returns the URI of the PNG image of this StatisticsPlotType as served by the
		 *  {@link StatisticsPNGWebInterfaceToadlet}. */
		public URI getURI(WebInterface wi) {
			StatisticsPNGWebInterfaceToadlet myToadlet = (StatisticsPNGWebInterfaceToadlet)
				wi.getToadlet(StatisticsPNGWebInterfaceToadlet.class);
			
			return myToadlet.getURI(this);
		}
	}

	/**
	 * TODO: Move to class {@link WebOfTrust}
	 */
	private long getEditionSum() {
		long editionSum = 0;
		for(Identity identity : mWebOfTrust.getAllIdentities()) {
			editionSum += max(identity.getLastFetchedEdition(), 0);
		}
		return editionSum;
	}

	private void makeIdentityDownloaderFastBox() {
		IdentityDownloaderFast downloader
			= mWebOfTrust.getIdentityDownloaderController().getIdentityDownloaderFast();
		
		if(downloader == null)
			return;
		
		BaseL10n l = l10n();
		String p = "StatisticsPage.IdentityDownloaderFastBox.";
		HTMLNode box = addContentBox(l.getString(p + "Header"));
		HTMLNode ul = new HTMLNode("ul");
		IdentityDownloaderFastStatistics s = downloader.new IdentityDownloaderFastStatistics();
		
		ul.addChild(new HTMLNode("li", l.getString(p + "ScheduledForStartingDownloads")
			+ " " + s.mScheduledForStartingDownloads));
		ul.addChild(new HTMLNode("li", l.getString(p + "ScheduledForStoppingDownloads")
			+ " " + s.mScheduledForStoppingDownloads));
		ul.addChild(new HTMLNode("li", l.getString(p + "RunningDownloads")
			+ " " + s.mRunningDownloads));
		ul.addChild(new HTMLNode("li", l.getString(p + "DownloadedEditions")
			+ " " + s.mDownloadedEditions));
		ul.addChild(new HTMLNode("li", l.getString(p + "DownloadProcessingFailures")
			+ " " + s.mDownloadProcessingFailures));
		
		box.addChild(ul);
	}

	private void makeIdentityDownloaderSlowBox() {
		IdentityDownloaderSlow downloader
			= mWebOfTrust.getIdentityDownloaderController().getIdentityDownloaderSlow();
		
		if(downloader == null)
			return;
		
		BaseL10n l = l10n();
		String p = "StatisticsPage.IdentityDownloaderSlowBox.";
		HTMLNode box = addContentBox(l.getString(p + "Header"));
		HTMLNode ul = new HTMLNode("ul");
		IdentityDownloaderSlowStatistics s = downloader.new IdentityDownloaderSlowStatistics();
		
		ul.addChild(new HTMLNode("li", l.getString(p + "QueuedDownloads")
			+ " " + s.mQueuedDownloads));
		ul.addChild(new HTMLNode("li", l.getString(p + "TotalQueuedDownloadsInSession")
			+ " " + s.mTotalQueuedDownloadsInSession));
		ul.addChild(new HTMLNode("li", l.getString(p + "RunningDownloads")
			+ " " + s.mRunningDownloads));
		// FIXME: MaxRunningDownloads may be misleading now because the IdentityDownloaderSlow has
		// been changed to stop downloading more files if the IdentityFileQueue is at its soft limit
		// of files pending processing. Display this info somehow here as well.
		// Also display the soft limit in the queue's stats box.
		ul.addChild(new HTMLNode("li", l.getString(p + "MaxRunningDownloads")
			+ " " + s.mMaxRunningDownloads));
		ul.addChild(new HTMLNode("li", l.getString(p + "SucceededDownloads")
			+ " " + s.mSucceededDownloads));
		ul.addChild(new HTMLNode("li", l.getString(p + "SkippedDownloads")
			+ " " + s.mSkippedDownloads));
		ul.addChild(new HTMLNode("li", l.getString(p + "FailedTemporarilyDownloads")
			+ " " + s.mFailedTemporarilyDownloads));
		ul.addChild(new HTMLNode("li", l.getString(p + "FailedPermanentlyDownloads")
			+ " " + s.mFailedPermanentlyDownloads));
		ul.addChild(new HTMLNode("li", l.getString(p + "DataNotFoundDownloads")
			+ " " + s.mDataNotFoundDownloads));
		
		box.addChild(ul);
	}

	// FIXME: Create the same for IdentityDownloaderFast's running downloads once the FIXMEs in this
	// are finished
	private void makeIdentityDownloaderSlowQueueBox() {
		final IdentityDownloaderSlow downloader
			= mWebOfTrust.getIdentityDownloaderController().getIdentityDownloaderSlow();
		
		if(downloader == null)
			return;
		
		final BaseL10n l = l10n();
		final String p = "StatisticsPage.IdentityDownloaderSlowQueueBox.";
		final HTMLNode box = addContentBox(l.getString(p + "Header"));
		box.addChild("#", l.getString(p + "Text"));
		
		class QueueTableHeader extends HTMLNode {
			QueueTableHeader() {
				super("tr");
				String p2 = p + "Queue.";
				
				addChild("th", l.getString(p2 + "Index"));
				// We now add columns for all fields of EditionHint which define the sort order of
				// the EditionHints in the download queue. We add them in the same order as they
				// define the sort order at EditionHint.compareTo().
				addChild("th", l.getString(p2 + "Date"));
				// FIXME: Add "Queued for" column which shows the number of hours for which the hint
				// has been queued for downloading. This can be computed from the
				// Persistent.getCreationDate() of the EditionHint object as hints are only stored
				// in the database as long as they are eligible for download (proof is e.g.
				// IdentityDownloaderSlow.getQueue()).
				addChild("th", l.getString(p2 + "SourceCapacity"));
				addChild("th", l.getString(p2 + "SourceScore"));
				addChild("th", l.getString(p2 + "TargetIdentity"));
				addChild("th", l.getString(p2 + "Edition"));
				// Fields of EditionHint which don't affect the sort order.
				addChild("th", l.getString(p2 + "SourceIdentity"));
				// Notice: The mID and mPriority fields are intentionally not displayed as they
				// are included in the other fields: mID is just sourceIdentityID@targetIdentityID,
				// and mPriority defines the sort order of the queue, which we show by displaying
				// the EditionHints in the table in the same order.
				// (Those duplicate files serve the purpose of allowing efficient database queries.)
			}
		}
		
		HTMLNode q = box.addChild("p").addChild("table", "border", "0");
		// Necessary so our following <tr> CSS has precedence over fred's top-level CSS for <td>.
		// (Inlining this into the style attributes of the <tr> tags for some reason does not work.)
		q.addChild("style").addChild("%", "td { font-weight: inherit }");
		q.addChild(new QueueTableHeader());
		
		synchronized(mWebOfTrust) {
		synchronized(mWebOfTrust.getIdentityDownloaderController()) {
			// FIXME: Code quality: Paginate instead of having a fixed limit. Also adapt the table
			// header's l10n then.
			int index = 1;
			int toDisplay = 100;
			
			SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
			
			for(EditionHint h : downloader.getQueue()) {
				HTMLNode r = q.addChild("tr");
				if(downloader.isDownloadInProgress(h))
					r.addAttribute("style", "font-weight: bold");
				r.addChild("td", Integer.toString(index));
				r.addChild("td", dateFormat.format(h.getDate()));
				r.addChild("td", Byte.toString(h.getSourceCapacity()));
				r.addChild("td", Byte.toString(h.getSourceScore()));
				// FIXME: Show pubkey if nick is not available yet because the identity was not
				// downloaded yet, which will often happen as this is the download queue.
				// (Not necessary for the source identity below as we can only have received this
				// EditionHint from it by having downloaded it, which provides the nickname.)
				r.addChild("td").addChild(IdentityPage.getLinkWithNickname(mWebInterface,
					h.getTargetIdentity()));
				r.addChild("td", Long.toString(h.getEdition()));
				r.addChild("td").addChild(IdentityPage.getLinkWithNickname(mWebInterface,
					h.getSourceIdentity()));
				
				if(++index > toDisplay)
					break;
			}
		}
		}
		
		q.addChild(new QueueTableHeader());
	}

	private void makeIdentityFileQueueBox() {
		String l10nPrefix = "StatisticsPage.IdentityFileQueueBox.";
		HTMLNode box = addContentBox(l10n().getString(l10nPrefix + "Header"));
		HTMLNode list = new HTMLNode("ul");
		IdentityFileQueueStatistics stats = mWebOfTrust.getIdentityFileQueue().getStatistics();

		list.addChild(new HTMLNode("li", l10n().getString(l10nPrefix + "AverageQueuedFilesPerHour")
			+ " " + stats.getAverageQueuedFilesPerHour()));
		list.addChild(new HTMLNode("li", l10n().getString(l10nPrefix + "LeftoverFilesOfLastSession")
			+ " " + stats.mLeftoverFilesOfLastSession));
		list.addChild(new HTMLNode("li", l10n().getString(l10nPrefix + "TotalQueuedFiles")
			+ " " + stats.mTotalQueuedFiles));
		list.addChild(new HTMLNode("li", l10n().getString(l10nPrefix + "QueuedFiles")
			+ " " + stats.mQueuedFiles));
		list.addChild(new HTMLNode("li", l10n().getString(l10nPrefix + "ProcessingFiles")
			+ " " + stats.mProcessingFiles));
		list.addChild(new HTMLNode("li", l10n().getString(l10nPrefix + "FinishedFiles")
			+ " " + stats.mFinishedFiles));
		list.addChild(new HTMLNode("li", l10n().getString(l10nPrefix + "DeduplicatedFiles")
			+ " " + stats.mDeduplicatedFiles));
		list.addChild(new HTMLNode("li", l10n().getString(l10nPrefix + "FailedFiles")
			+ " " + stats.mFailedFiles));
		
		box.addChild(list);
	}

	private void makeIdentityFileProcessorBox() {
		String l10nPrefix = "StatisticsPage.IdentityFileProcessorBox.";
		HTMLNode box = addContentBox(l10n().getString(l10nPrefix + "Header"));
		HTMLNode list = new HTMLNode("ul");
		IdentityFileProcessor.Statistics stats
			= mWebOfTrust.getIdentityFileProcessor().getStatistics();

		list.addChild(new HTMLNode("li", l10n().getString(l10nPrefix + "ProcessedFiles") + " "
			+ stats.mProcessedFiles));

		list.addChild(new HTMLNode("li", l10n().getString(l10nPrefix + "FailedFiles") + " "
			+ stats.mFailedFiles));

		list.addChild(new HTMLNode("li", l10n().getString(l10nPrefix + "TotalProcessingTime") + " "
			+ TimeUtil.formatTime(TimeUnit.NANOSECONDS.toMillis(stats.mProcessingTimeNanoseconds))));
		
		list.addChild(new HTMLNode("li", l10n().getString(l10nPrefix + "AverageProcessingTimeSecs")
			+ " " + stats.getAverageXMLImportTime()));
		
		box.addChild(list);
	}

	private void makeMaintenanceBox() {
		String l10nPrefix = "StatisticsPage.MaintenanceBox.";
		HTMLNode box = addContentBox(l10n().getString(l10nPrefix + "Header"));
		HTMLNode list = new HTMLNode("ul");
		
		Date now;
		Date lastDefragDate;
		Date lastVerificationDate;
		
		// TODO: Performance: The synchronized() can be removed after this is fixed:
		// https://bugs.freenetproject.org/view.php?id=6247
		synchronized(mWebOfTrust) {
			Configuration config = mWebOfTrust.getConfig();
			now = CurrentTimeUTC.get();
			lastDefragDate = config.getLastDefragDate();
			lastVerificationDate = config.getLastVerificationOfScoresDate();
		}
		
		String defrag =  l10n().getString(l10nPrefix + "LastDefrag",
			new String[] { "lastTime",
			               "interval" },
			new String[] { formatTimeDelta(now.getTime() - lastDefragDate.getTime(), l10n()),
			               formatTime(DEFAULT_DEFRAG_INTERVAL) });
		
		String verification =  l10n().getString(l10nPrefix + "LastScoreVerification",
			new String[] { "lastTime",
			               "interval" },
			new String[] { formatTimeDelta(now.getTime() - lastVerificationDate.getTime(), l10n()),
			               formatTime(DEFAULT_VERIFY_SCORES_INTERVAL) });
		
		list.addChild(new HTMLNode("li", defrag));
		list.addChild(new HTMLNode("li", verification));
		
		box.addChild(list);
	}

}
