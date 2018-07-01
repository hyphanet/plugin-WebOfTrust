/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.ui.web;

import static freenet.support.TimeUtil.formatTime;
import static java.lang.Math.max;
import static java.util.concurrent.TimeUnit.MINUTES;
import static plugins.WebOfTrust.Configuration.DEFAULT_DEFRAG_INTERVAL;
import static plugins.WebOfTrust.Configuration.DEFAULT_VERIFY_SCORES_INTERVAL;
import static plugins.WebOfTrust.ui.web.CommonWebUtils.formatTimeDelta;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.TimeUnit;

import org.knowm.xchart.BitmapEncoder;
import org.knowm.xchart.BitmapEncoder.BitmapFormat;
import org.knowm.xchart.QuickChart;
import org.knowm.xchart.XYChart;

import plugins.WebOfTrust.Configuration;
import plugins.WebOfTrust.Identity;
import plugins.WebOfTrust.IdentityFile;
import plugins.WebOfTrust.IdentityFileProcessor;
import plugins.WebOfTrust.IdentityFileQueue.IdentityFileQueueStatistics;
import plugins.WebOfTrust.SubscriptionManager;
import plugins.WebOfTrust.WebOfTrust;
import plugins.WebOfTrust.introduction.IntroductionPuzzleStore;
import plugins.WebOfTrust.network.input.EditionHint;
import plugins.WebOfTrust.network.input.IdentityDownloaderFast;
import plugins.WebOfTrust.network.input.IdentityDownloaderFast.IdentityDownloaderFastStatistics;
import plugins.WebOfTrust.network.input.IdentityDownloaderSlow;
import plugins.WebOfTrust.network.input.IdentityDownloaderSlow.IdentityDownloaderSlowStatistics;
import plugins.WebOfTrust.util.LimitedArrayDeque;
import plugins.WebOfTrust.util.Pair;
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
		// FIXME: The l10n of the header sounds like this only is scheduled downloads, but it also
		// includes running downloads. Tweak the l10n to reflect that.
		// Mark running downloads in the table, e.g. by bold or italics.
		final HTMLNode box = addContentBox(l.getString(p + "Header"));
		
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
		
		HTMLNode q = box.addChild("table", "border", "0");
		q.addChild(new QueueTableHeader());
		
		synchronized(mWebOfTrust) {
		synchronized(downloader) {
			// FIXME: Code quality: Paginate instead of having a fixed limit. Also adapt the table
			// header's l10n then.
			int index = 1;
			int toDisplay = 100;
			
			SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
			
			for(EditionHint h : downloader.getQueue()) {
				HTMLNode r = q.addChild("tr");
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

	/**
	 * Renders a chart where the X-axis is the uptime of WoT, and the Y-axis is the total number of
	 * downloaded {@link IdentityFile}s.
	 * 
	 * @see IdentityFileQueueStatistics#mTotalQueuedFiles
	 * @see IdentityFileQueueStatistics#mTimesOfQueuing */
	public static byte[] getTotalDownloadCountPlotPNG(WebOfTrust wot) {
		IdentityFileQueueStatistics stats = wot.getIdentityFileQueue().getStatistics();
		LimitedArrayDeque<Pair<Long, Integer>> timesOfQueuing
			= stats.mTimesOfQueuing;
		
		// Add a dummy entry for the current time to the end of the plot so refreshing the image
		// periodically shows that it is live even when there is no progress.
		// Adding it to the obtained statistics is fine as they are a clone() of the original.
		// Also peekLast() can never return null because mTimesOfQueuing by contract always contains
		// at least one element.
		timesOfQueuing.addLast(
			new Pair<>(CurrentTimeUTC.getInMillis(), timesOfQueuing.peekLast().y));
		
		long x0 = stats.mStartupTimeMilliseconds;
		double[] x = new double[timesOfQueuing.size()];
		double[] y = new double[x.length];
		int i = 0;
		for(Pair<Long, Integer> p : timesOfQueuing) {
			// FIXME: Switch to hours for large ranges of the plot
			x[i] = ((double)(p.x - x0)) / (double)MINUTES.toMillis(1);
			y[i] = p.y;
			++i;
		}
		
		BaseL10n l = wot.getBaseL10n();
		String p = "StatisticsPage.PlotBox.TotalDownloadCountPlot.";
		XYChart c = QuickChart.getChart(l.getString(p + "Title"), l.getString(p + "XAxis.Minutes"),
			l.getString(p + "YAxis"), null, x, y);
		
		/* For debugging
		for(XYSeries s: c.getSeriesMap().values())
			s.setMarker(SeriesMarkers.CIRCLE);
		*/
		
		byte[] png;
		try {
			png = BitmapEncoder.getBitmapBytes(c, BitmapFormat.PNG);
		} catch (IOException e) {
			// No idea why this would happen so don't require callers to handle it by converting
			// to non-declared exception.
			throw new RuntimeException(e);
		}
		
		return png;
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
