/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.ui.web;

import plugins.WebOfTrust.IdentityFetcher;
import plugins.WebOfTrust.introduction.IntroductionPuzzleStore;
import freenet.clients.http.ToadletContext;
import freenet.l10n.BaseL10n;
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
	public StatisticsPage(WebInterfaceToadlet toadlet, HTTPRequest myRequest, ToadletContext context, BaseL10n _baseL10n) {
		super(toadlet, myRequest, context, _baseL10n);
	}

	public void make() {
		makeSummary();
	}

	/**
	 * Creates a short summary of what the plugin knows of the WoT.
	 */
	private void makeSummary() {
		HTMLNode box = addContentBox(l10n().getString("StatisticsPage.SummaryBox.Header"));
		HTMLNode list = new HTMLNode("ul");
		
		synchronized(wot) {
		list.addChild(new HTMLNode("li", l10n().getString("StatisticsPage.SummaryBox.OwnIdentities") + ": " + wot.getAllOwnIdentities().size()));
		list.addChild(new HTMLNode("li", l10n().getString("StatisticsPage.SummaryBox.KnownIdentities") + ": " + wot.getAllNonOwnIdentities().size()));
		list.addChild(new HTMLNode("li", l10n().getString("StatisticsPage.SummaryBox.TrustRelationships") + ": " + wot.getAllTrusts().size()));
		list.addChild(new HTMLNode("li", l10n().getString("StatisticsPage.SummaryBox.ScoreRelationships") + ": " + wot.getAllScores().size()));
		list.addChild(new HTMLNode("li", l10n().getString("StatisticsPage.SummaryBox.FullRecomputations") + ": " + wot.getNumberOfFullScoreRecomputations()));
		list.addChild(new HTMLNode("li", l10n().getString("StatisticsPage.SummaryBox.FullRecomputationTime") + ": " + wot.getAverageFullScoreRecomputationTime()));
		list.addChild(new HTMLNode("li", l10n().getString("StatisticsPage.SummaryBox.IncrementalRecomputations") + ": " + wot.getNumberOfIncrementalScoreRecomputations()));
		list.addChild(new HTMLNode("li", l10n().getString("StatisticsPage.SummaryBox.IncrementalRecomputationTime") + ": " + wot.getAverageIncrementalScoreRecomputationTime()));
		IntroductionPuzzleStore puzzleStore = wot.getIntroductionPuzzleStore();
		synchronized(puzzleStore) {
		list.addChild(new HTMLNode("li", l10n().getString("StatisticsPage.SummaryBox.UnsolvedOwnCaptchas") + ": " + puzzleStore.getOwnCatpchaAmount(false)));
		list.addChild(new HTMLNode("li", l10n().getString("StatisticsPage.SummaryBox.SolvedOwnCaptchas") + ": " + puzzleStore.getOwnCatpchaAmount(true)));
		list.addChild(new HTMLNode("li", l10n().getString("StatisticsPage.SummaryBox.UnsolvedCaptchasOfOthers") + ": " + puzzleStore.getNonOwnCaptchaAmount(false)));
		list.addChild(new HTMLNode("li", l10n().getString("StatisticsPage.SummaryBox.SolvedCaptchasOfOthers") + ": " + puzzleStore.getNonOwnCaptchaAmount(true)));
		list.addChild(new HTMLNode("li", l10n().getString("StatisticsPage.SummaryBox.NotInsertedCaptchasSolutions") + ": " + puzzleStore.getUninsertedSolvedPuzzles().size()));
		}
		}
		
		IdentityFetcher fetcher = wot.getIdentityFetcher();
		synchronized(fetcher) {
			list.addChild(new HTMLNode("li", l10n().getString("StatisticsPage.SummaryBox.FetchedIdentities") + ": " + fetcher.getFetchedCount()));
			list.addChild(new HTMLNode("li", l10n().getString("StatisticsPage.SummaryBox.AverageFetchedIdentitiesPerHour") + ": " + fetcher.getAverageFetchCountPerHour()));
			list.addChild(new HTMLNode("li", l10n().getString("StatisticsPage.SummaryBox.AverageIdentityImportTime") + ": " + fetcher.getAverageXMLImportTime()));
		}
		
		box.addChild(list);
	}
}
