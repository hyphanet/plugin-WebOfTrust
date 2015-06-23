/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.ui.web;

import plugins.WebOfTrust.Identity;
import plugins.WebOfTrust.IdentityFetcher;
import plugins.WebOfTrust.IdentityFileQueue.IdentityFileQueueStatistics;
import plugins.WebOfTrust.SubscriptionManager;
import plugins.WebOfTrust.WebOfTrust;
import plugins.WebOfTrust.introduction.IntroductionPuzzleStore;
import freenet.clients.http.ToadletContext;
import freenet.support.HTMLNode;
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
		makeIdentityFileQueueBox();
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
		list.addChild(new HTMLNode("li", l10n().getString("StatisticsPage.SummaryBox.FetchProgress", "editionCount", Long.toString(getEditionSum()))));
		list.addChild(new HTMLNode("li", l10n().getString("StatisticsPage.SummaryBox.TrustRelationships") + ": " + mWebOfTrust.getAllTrusts().size()));
		list.addChild(new HTMLNode("li", l10n().getString("StatisticsPage.SummaryBox.ScoreRelationships") + ": " + mWebOfTrust.getAllScores().size()));
		list.addChild(new HTMLNode("li", l10n().getString("StatisticsPage.SummaryBox.FullRecomputations") + ": " + mWebOfTrust.getNumberOfFullScoreRecomputations()));
		list.addChild(new HTMLNode("li", l10n().getString("StatisticsPage.SummaryBox.FullRecomputationTime") + ": " + mWebOfTrust.getAverageFullScoreRecomputationTime()));
		list.addChild(new HTMLNode("li", l10n().getString("StatisticsPage.SummaryBox.IncrementalRecomputations") + ": " + mWebOfTrust.getNumberOfIncrementalScoreRecomputations()));
		list.addChild(new HTMLNode("li", l10n().getString("StatisticsPage.SummaryBox.IncrementalRecomputationTime") + ": " + mWebOfTrust.getAverageIncrementalScoreRecomputationTime()));
		IntroductionPuzzleStore puzzleStore = mWebOfTrust.getIntroductionPuzzleStore();
		synchronized(puzzleStore) {
		list.addChild(new HTMLNode("li", l10n().getString("StatisticsPage.SummaryBox.UnsolvedOwnCaptchas") + ": " + puzzleStore.getOwnCatpchaAmount(false)));
		list.addChild(new HTMLNode("li", l10n().getString("StatisticsPage.SummaryBox.SolvedOwnCaptchas") + ": " + puzzleStore.getOwnCatpchaAmount(true)));
		list.addChild(new HTMLNode("li", l10n().getString("StatisticsPage.SummaryBox.UnsolvedCaptchasOfOthers") + ": " + puzzleStore.getNonOwnCaptchaAmount(false)));
		list.addChild(new HTMLNode("li", l10n().getString("StatisticsPage.SummaryBox.SolvedCaptchasOfOthers") + ": " + puzzleStore.getNonOwnCaptchaAmount(true)));
		list.addChild(new HTMLNode("li", l10n().getString("StatisticsPage.SummaryBox.NotInsertedCaptchasSolutions") + ": " + puzzleStore.getUninsertedSolvedPuzzles().size()));
		}
		}
		
		IdentityFetcher fetcher = mWebOfTrust.getIdentityFetcher();
		synchronized(fetcher) {
			list.addChild(new HTMLNode("li", l10n().getString("StatisticsPage.SummaryBox.AverageIdentityImportTime") + ": " + fetcher.getAverageXMLImportTime()));
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
	
	public void makeIdentityFileQueueBox() {
		String l10nPrefix = "StatisticsPage.IdentityFileQueueBox.";
		HTMLNode box = addContentBox(l10n().getString(l10nPrefix + "Header"));
		HTMLNode list = new HTMLNode("ul");
		IdentityFileQueueStatistics stats = mWebOfTrust.getIdentityFileQueue().getStatistics();

		list.addChild(new HTMLNode("li", l10n().getString(l10nPrefix + "AverageQueuedFilesPerHour")
			+ stats.getAverageQueuedFilesPerHour()));
		list.addChild(new HTMLNode("li", l10n().getString(l10nPrefix + "TotalQueuedFiles")
			+ stats.mTotalQueuedFiles));
		list.addChild(new HTMLNode("li", l10n().getString(l10nPrefix + "QueuedFiles")
			+ stats.mQueuedFiles));
		list.addChild(new HTMLNode("li", l10n().getString(l10nPrefix + "ProcessingFiles")
			+ stats.mProcessingFiles));
		list.addChild(new HTMLNode("li", l10n().getString(l10nPrefix + "FinishedFiles")
			+ stats.mFinishedFiles));
		list.addChild(new HTMLNode("li", l10n().getString(l10nPrefix + "DeduplicatedFiles")
			+ stats.mDeduplicatedFiles));
		
		box.addChild(list);
	}

	/**
	 * TODO: Move to class {@link WebOfTrust}
	 */
	private long getEditionSum() {
		long editionSum = 0;
		for(Identity identity : mWebOfTrust.getAllIdentities()) {
			editionSum += identity.getEdition();
		}
		return editionSum;
	}
}
