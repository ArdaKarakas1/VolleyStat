package com.voleybolveveleybol.volleystat.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.voleybolveveleybol.volleystat.AnalysisUi
import com.voleybolveveleybol.volleystat.RemovalFailure
import com.voleybolveveleybol.volleystat.AppUiState
import com.voleybolveveleybol.volleystat.JobLoad
import com.voleybolveveleybol.volleystat.R
import com.voleybolveveleybol.volleystat.RecordingFile
import com.voleybolveveleybol.volleystat.ReportLoad
import com.voleybolveveleybol.volleystat.ReviewProblem
import com.voleybolveveleybol.volleystat.ServerTest
import com.voleybolveveleybol.volleystat.model.AnalysisJob
import com.voleybolveveleybol.volleystat.model.AnalysisSummary
import com.voleybolveveleybol.volleystat.model.Coverage
import com.voleybolveveleybol.volleystat.model.Draft
import com.voleybolveveleybol.volleystat.model.LocalVideo
import com.voleybolveveleybol.volleystat.model.MatchRecord
import com.voleybolveveleybol.volleystat.model.MatchRules
import com.voleybolveveleybol.volleystat.model.MatchReport
import com.voleybolveveleybol.volleystat.model.ReviewCard
import com.voleybolveveleybol.volleystat.model.ReviewDeck
import com.voleybolveveleybol.volleystat.model.SetScore
import com.voleybolveveleybol.volleystat.model.StatValue
import com.voleybolveveleybol.volleystat.model.TeamStats
import com.voleybolveveleybol.volleystat.model.VideoCheck
import com.voleybolveveleybol.volleystat.model.VideoSource
import com.voleybolveveleybol.volleystat.ui.screens.ClipPlaceholder
import com.voleybolveveleybol.volleystat.ui.screens.HomeScreen
import com.voleybolveveleybol.volleystat.ui.screens.MatchCreateScreen
import com.voleybolveveleybol.volleystat.ui.screens.PlayersScreen
import com.voleybolveveleybol.volleystat.FEEDBACK_ADDRESS
import com.voleybolveveleybol.volleystat.ui.screens.ProfileScreen
import com.voleybolveveleybol.volleystat.ui.screens.ReportScreen
import com.voleybolveveleybol.volleystat.ui.screens.ReviewScreen
import com.voleybolveveleybol.volleystat.ui.screens.StatusScreen
import com.voleybolveveleybol.volleystat.ui.screens.VideoCheckScreen
import com.voleybolveveleybol.volleystat.ui.screens.WelcomeScreen
import com.voleybolveveleybol.volleystat.ui.theme.VolleyStatTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.voleybolveveleybol.volleystat.part
import com.voleybolveveleybol.volleystat.model.PlayerLine
import com.voleybolveveleybol.volleystat.model.MatchPart
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.filter
import org.junit.Assert.assertFalse
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.autofill.ContentType
import com.voleybolveveleybol.volleystat.ui.screens.LocalAutofillCommit
import androidx.compose.ui.test.assert
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.voleybolveveleybol.volleystat.AccountChange
import com.voleybolveveleybol.volleystat.AccountUi
import com.voleybolveveleybol.volleystat.LegacyQuestion
import com.voleybolveveleybol.volleystat.data.AccountUser
import com.voleybolveveleybol.volleystat.data.AttemptScope
import com.voleybolveveleybol.volleystat.data.AuthProblem
import com.voleybolveveleybol.volleystat.data.PasswordRule
import com.voleybolveveleybol.volleystat.data.SignupClosedReason
import com.voleybolveveleybol.volleystat.model.Settings
import com.voleybolveveleybol.volleystat.ui.screens.DeleteAccountScreen
import com.voleybolveveleybol.volleystat.ui.screens.LegacyScreen
import com.voleybolveveleybol.volleystat.ui.screens.AccountAction
import com.voleybolveveleybol.volleystat.ui.screens.AccountOutcome
import com.voleybolveveleybol.volleystat.ui.screens.PasswordChangeScreen
import com.voleybolveveleybol.volleystat.ui.screens.LoginScreen
import com.voleybolveveleybol.volleystat.ui.screens.ProfileAccountActions
import com.voleybolveveleybol.volleystat.ui.screens.RegisterScreen
import com.voleybolveveleybol.volleystat.ui.screens.StartScreen

/**
 * What single screens say in the states an independent review found wrong in version 0.4.0:
 * skipped cards, failed loads, a missing report, lower bounds, and server codes on screen.
 * All names and numbers are invented.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenStatesTest {
    @get:Rule val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun text(id: Int, vararg arguments: Any) = context.getString(id, *arguments)
    private fun shown(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun show(content: @Composable () -> Unit) = compose.setContent { VolleyStatTheme(dark = false) { content() } }

    private val video = LocalVideo("/data/kayit.mp4", VideoSource.CAMERA, "kayit.mp4", 1_000, "video/mp4", 60_000, 1920, 1080, null)
    private val match = MatchRecord("m1", "A Takımı", "B Takımı", 1L, video, check = VideoCheck("v1", VideoCheck.READY, null, 1920, 1080, 30.0, 60_000, "h264", emptyList()))

    private fun job(state: String, failure: String? = null) = AnalysisJob("j1", "v1", state, null, null, null, "motion-baseline", "BASELINE", false, failure, 3, 1, 3)
    private fun card(sequence: Int, answer: String?) = ReviewCard(
        "c$sequence", "r$sequence", sequence, ReviewCard.RALLY_WINNER, "SCORE", 1_000, 5_000, null,
        listOf("HOME", "AWAY", "NOT_A_RALLY", "UNSURE"), if (answer == null) "OPEN" else "ANSWERED", answer, 1,
    )

    private fun review(analysis: AnalysisUi, onMove: (Int) -> Unit = {}, onRetry: () -> Unit = {}) =
        show { ReviewScreen(match, analysis, emptyList(), {}, onMove, {}, {}, {}, onRetry) { ClipPlaceholder() } }

    @Test fun pastTheLastCardTheReviewNamesTheCardsThatWereSkipped() {
        val moves = mutableListOf<Int>()
        val deck = ReviewDeck(listOf(card(1, null), card(2, "HOME"), card(3, "AWAY")), 2, 3)
        review(AnalysisUi("m1", JobLoad.READY, job(AnalysisSummary.NEEDS_REVIEW), deck, cursor = 3), onMove = { moves += it })

        compose.onNodeWithText(text(R.string.review_skipped_title, 1)).assertIsDisplayed()
        assertEquals(false, shown(text(R.string.review_empty_title)))
        compose.onNodeWithText(text(R.string.review_go_open)).performClick()
        assertEquals("back to the first open card", listOf(-3), moves)
    }

    @Test fun pastTheLastCardTheReviewSaysEverythingIsDecidedOnlyWhenItIs() {
        val deck = ReviewDeck(listOf(card(1, "HOME"), card(2, "AWAY")), 2, 2)
        review(AnalysisUi("m1", JobLoad.READY, job(AnalysisSummary.COMPLETE), deck, cursor = 2))
        compose.onNodeWithText(text(R.string.review_empty_title)).assertIsDisplayed()
    }

    @Test fun cardsThatDidNotLoadAreNotCalledAnUnsavedDecisionAndCanBeAskedForAgain() {
        var retries = 0
        review(AnalysisUi("m1", JobLoad.READY, job(AnalysisSummary.NEEDS_REVIEW), reviewProblem = ReviewProblem.LOAD_FAILED), onRetry = { retries++ })
        compose.onNodeWithText(text(R.string.review_load_failed)).assertIsDisplayed()
        assertEquals(false, shown(text(R.string.review_save_failed)))
        compose.onNodeWithText(text(R.string.action_retry)).performClick()
        assertEquals(1, retries)
    }

    @Test fun aReviewWithoutAJobSaysSoInsteadOfLoadingForever() {
        review(AnalysisUi("m1", JobLoad.NO_JOB))
        compose.onNodeWithText(text(R.string.review_no_job)).assertIsDisplayed()
        assertEquals(false, shown(text(R.string.review_loading)))
    }

    private val actionCard = ReviewCard(
        "c1", "r1", 1, ReviewCard.TERMINAL_ACTION, "DETAIL", 1_000, 5_000, null,
        listOf("ATTACK", "BLOCK", "ACE", "OPPONENT_ERROR", "UNSURE"), "OPEN", null, 1,
    )
    private val definitions = listOf(R.string.define_attack, R.string.define_block, R.string.define_ace, R.string.define_error)

    @Test fun theQuestionAboutThePointTypeOpensTheDefinitionsOfTheFourTypes() {
        review(AnalysisUi("m1", JobLoad.READY, job(AnalysisSummary.NEEDS_REVIEW), ReviewDeck(listOf(actionCard), 0, 1), detail = true))
        assertEquals(false, shown(text(R.string.define_ace)))
        compose.onNodeWithContentDescription(text(R.string.definitions_open)).performClick()
        definitions.forEach { compose.onNodeWithText(text(it)).assertIsDisplayed() }
        // A dialog is its own window, so the screenshot run can not reach it; this run keeps its picture.
        compose.onNode(isDialog()).captureRoboImage("build/outputs/roborazzi/08_dogrulama_sayi_turleri.png")
        compose.onNodeWithText(text(R.string.action_ok)).performClick()
        assertEquals(false, shown(text(R.string.define_ace)))
    }

    @Test fun theQuestionAboutTheWinnerHasNoDefinitionsButton() {
        review(AnalysisUi("m1", JobLoad.READY, job(AnalysisSummary.NEEDS_REVIEW), ReviewDeck(listOf(card(1, null)), 0, 1)))
        assertEquals(0, compose.onAllNodesWithContentDescription(text(R.string.definitions_open)).fetchSemanticsNodes().size)
    }

    private fun report(scoreStatus: String) = MatchReport(
        "j1", "motion-baseline", "BASELINE", mock = false, verificationStatus = "PARTIALLY_VERIFIED",
        ralliesProposed = 40, ralliesVerified = 30, ralliesRejected = 0, ralliesUnresolved = 0, ralliesOpen = 10,
        scoreStatus = scoreStatus, matches = listOf(part(SetScore(1, 25, 21, true))),
        ralliesMayBeMissing = scoreStatus == "PARTIAL",
        home = TeamStats(StatValue(28, "PARTIAL"), StatValue(null, "UNKNOWN"), StatValue(null, "UNKNOWN"), StatValue(null, "UNKNOWN"), StatValue(null, "UNKNOWN")),
        away = TeamStats(StatValue(25, "PARTIAL"), StatValue(null, "UNKNOWN"), StatValue(null, "UNKNOWN"), StatValue(null, "UNKNOWN"), StatValue(null, "UNKNOWN")),
        players = emptyList(), unattributedHome = 28, unattributedAway = 25, coverage = listOf(Coverage("RALLY_WINNER", 40, 30)),
    )

    private fun counted(points: Int) = TeamStats(
        StatValue(points, StatValue.MEASURED), StatValue(0, StatValue.MEASURED), StatValue(0, StatValue.MEASURED), StatValue(0, StatValue.MEASURED), StatValue(0, StatValue.MEASURED))

    /** Two matches, 3-0 to home and 0-3 to away, then one set that finishes no match. Every number is invented. */
    private val twoMatches = report("MEASURED").copy(
        verificationStatus = "VERIFIED", ralliesMayBeMissing = false,
        home = counted(161), away = counted(147), players = listOf(PlayerLine(true, 7, 20, 20, 0, 0), PlayerLine(false, 9, 6, 6, 0, 0)),
        unattributedHome = 141, unattributedAway = 141,
        matches = listOf(
            part(SetScore(1, 25, 20, true), SetScore(2, 25, 18, true), SetScore(3, 25, 10, true), kind = MatchPart.MATCH, winner = "HOME")
                .copy(home = counted(75), away = counted(48), players = listOf(PlayerLine(true, 7, 20, 20, 0, 0)), unattributedHome = 55, unattributedAway = 48),
            part(SetScore(1, 18, 25, true), SetScore(2, 20, 25, true), SetScore(3, 23, 25, true), number = 2, kind = MatchPart.MATCH, winner = "AWAY")
                .copy(home = counted(61), away = counted(75), players = listOf(PlayerLine(false, 9, 6, 6, 0, 0)), unattributedHome = 61, unattributedAway = 69),
            part(SetScore(1, 25, 24, false), number = 3, kind = MatchPart.AFTER_MATCH)
                .copy(home = counted(25), away = counted(24), unattributedHome = 25, unattributedAway = 24),
        ),
    )

    /** Home wins two matches and away one, then the count stops in a fourth part. Every number is invented. */
    private val stoppedAfterThreeMatches = report("PARTIAL").copy(
        matches = listOf(
            part(SetScore(1, 25, 20, true), SetScore(2, 25, 18, true), SetScore(3, 25, 10, true), kind = MatchPart.MATCH, winner = "HOME"),
            part(SetScore(1, 18, 25, true), SetScore(2, 20, 25, true), SetScore(3, 23, 25, true), number = 2, kind = MatchPart.MATCH, winner = "AWAY"),
            part(SetScore(1, 25, 21, true), SetScore(2, 25, 19, true), SetScore(3, 25, 17, true), number = 3, kind = MatchPart.MATCH, winner = "HOME"),
            part(SetScore(1, 25, 22, true), SetScore(2, 24, 23, false), number = 4, kind = MatchPart.STOPPED),
        ),
    )

    @Test fun whileTheScoreIsALowerBoundEachPartCarriesThePlusAndTheStoppedPartKeepsItsName() {
        show { ReportScreen(match, ReportLoad.READY, stoppedAfterThreeMatches, {}, {}, {}, {}, {}) }
        // Each side keeps its own count of matches won.
        compose.onNodeWithText("2+ - 1+").assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.report_matches_at_least, 2, 1)).assertIsDisplayed()
        // The sets won of every part are a lower bound as well, and a screen reader says so.
        assertEquals(2, compose.onAllNodesWithText("3+ - 0+").fetchSemanticsNodes().size)
        compose.onNodeWithText("0+ - 3+").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("1+ - 0+").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.report_score_at_least, 1, 0)).assertIsDisplayed()
        // The part the count stopped in may still become a match: it is never named an after-match part.
        compose.onAllNodesWithText(text(R.string.part_stopped)).filter(isHeading()).onFirst().performScrollTo().assertIsDisplayed()
        assertFalse(shown(text(R.string.part_after_match)))
        compose.onNodeWithText(text(R.string.report_part_stopped_note)).performScrollTo().assertIsDisplayed()
        assertFalse(shown(text(R.string.report_part_after_match_note)))
        // The open set is spoken as open, not only marked with a sign.
        val spoken = text(R.string.report_part_sets, "25-22, " + text(R.string.report_set_open, 2) + ": 24-23")
        compose.onNodeWithContentDescription(spoken).performScrollTo().assertIsDisplayed()
    }

    @Test fun onAConfirmedListOnlyThePartTheCountStoppedInCarriesThePlus() {
        // The list is confirmed and the count stopped in the fourth part: the three matches before it are final.
        show { ReportScreen(match, ReportLoad.READY, stoppedAfterThreeMatches.copy(ralliesMayBeMissing = false, listConfirmed = true), {}, {}, {}, {}, {}) }
        assertEquals(2, compose.onAllNodesWithText("3 - 0").fetchSemanticsNodes().size)
        compose.onNodeWithText("0 - 3").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("1+ - 0+").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.report_score_at_least, 1, 0)).assertIsDisplayed()
        // The stopped part may still become a match, so the matches won are a lower bound as well.
        compose.onNodeWithText("2+ - 1+").performScrollTo().assertIsDisplayed()
    }

    @Test fun aVideoOfSeveralMatchesShowsTheMatchesWonAndEachMatchWithItsSets() {
        show { ReportScreen(match, ReportLoad.READY, twoMatches, {}, {}, {}, {}, {}) }
        compose.onNodeWithText("1 - 1").assertIsDisplayed()
        // A measured count is final: no part carries the plus.
        compose.onNodeWithText("3 - 0").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("0 - 3").performScrollTo().assertIsDisplayed()
        assertFalse(shown("+ -"))
        compose.onNodeWithText(text(R.string.report_matches_won)).assertIsDisplayed()
        // The sets of each part stand on the matches card, not in the header.
        compose.onNodeWithText(text(R.string.report_part_sets, "25-20, 25-18, 25-10")).performScrollTo().assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("25-20").fetchSemanticsNodes().size)
        // Each name stands once as the heading of its row, and once more as a choice of the statistics below.
        listOf(text(R.string.part_match, 1), text(R.string.part_match, 2), text(R.string.part_after_match)).forEach {
            compose.onAllNodesWithText(it).filter(isHeading()).onFirst().performScrollTo().assertIsDisplayed()
            assertEquals(2, compose.onAllNodesWithText(it).fetchSemanticsNodes().size)
        }
        compose.onNodeWithText(text(R.string.report_part_sets, "25-24…")).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.report_part_sets, text(R.string.report_set_open, 1) + ": 25-24")).assertIsDisplayed()
        // Sets that all ended need no words beyond their scores.
        assertEquals(0, compose.onAllNodesWithContentDescription(text(R.string.report_part_sets, "25-20, 25-18, 25-10")).fetchSemanticsNodes().size)
        compose.onNodeWithText(text(R.string.report_part_after_match_note)).performScrollTo().assertIsDisplayed()
    }

    @Test fun theTeamStatisticsFollowThePartTheUserChooses() {
        show { ReportScreen(match, ReportLoad.READY, twoMatches, {}, {}, {}, {}, {}) }
        // The whole video first: its totals, not a match's.
        compose.onNodeWithText("161").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("147").performScrollTo().assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("61").fetchSemanticsNodes().size)
        compose.onAllNodesWithText(text(R.string.part_match, 2)).filter(hasClickAction()).onFirst().performScrollTo().performClick()
        compose.onNodeWithText("61").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("75").performScrollTo().assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("161").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("147").fetchSemanticsNodes().size)
    }

    @Test fun aRecountThatDropsTheChosenPartShowsTheWholeVideoAgain() {
        var shownReport by mutableStateOf(twoMatches)
        show { ReportScreen(match, ReportLoad.READY, shownReport, {}, {}, {}, {}, {}) }
        compose.onAllNodesWithText(text(R.string.part_after_match)).filter(hasClickAction()).onFirst().performScrollTo().performClick()
        compose.onNodeWithText("24").performScrollTo().assertIsDisplayed()
        // The new count has two parts: the third choice is gone, and the choice and the numbers return to the whole video.
        shownReport = twoMatches.copy(matches = twoMatches.matches.take(2))
        compose.waitForIdle()
        compose.onNodeWithText("161").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText(text(R.string.report_scope_video)).filter(hasClickAction()).onFirst().assertIsSelected()
        assertFalse(shown(text(R.string.part_after_match)))
    }

    @Test fun eachSideOfAPartKeepsItsOwnPointsWithoutAPlayer() {
        show { PlayersScreen(match, twoMatches, emptyList(), emptyList()) {} }
        compose.onAllNodesWithText(text(R.string.part_match, 2)).filter(hasClickAction()).onFirst().performClick()
        compose.onNodeWithText(text(R.string.players_unattributed)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("61").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(match.awayTeam).performClick()
        compose.onNodeWithText("69").performScrollTo().assertIsDisplayed()
        assertFalse(shown("61"))
    }

    @Test fun aReportTheAppCanNotReadSaysTheServerIsOutdatedAndOffersNoRetry() {
        show { ReportScreen(match, ReportLoad.OUTDATED, null, {}, {}, {}, {}, {}) }
        compose.onNodeWithText(text(R.string.report_outdated_server)).assertIsDisplayed()
        assertFalse(shown(text(R.string.action_retry)))
        assertFalse(shown(text(R.string.report_unavailable_title)))
    }

    @Test fun thePlayersOfAReportTheAppCanNotReadSayTheServerIsOutdated() {
        show { PlayersScreen(match, null, emptyList(), emptyList(), load = ReportLoad.OUTDATED) {} }
        compose.onNodeWithText(text(R.string.report_outdated_server)).assertIsDisplayed()
        assertFalse(shown(text(R.string.action_retry)))
    }

    @Test fun aSinglePartKeepsTheSetsInTheHeaderAndNeedsNoChoice() {
        show { ReportScreen(match, ReportLoad.READY, report("PARTIAL"), {}, {}, {}, {}, {}) }
        assertFalse(shown(text(R.string.report_matches_won)))
        assertFalse(shown(text(R.string.report_scope_label)))
        assertFalse(shown(text(R.string.report_matches)))
    }

    @Test fun thePlayersTableFollowsThePartTheUserChooses() {
        show { PlayersScreen(match, twoMatches, emptyList(), emptyList()) {} }
        compose.onNodeWithText(text(R.string.review_jersey, 7)).assertIsDisplayed()
        compose.onAllNodesWithText(text(R.string.part_match, 2)).filter(hasClickAction()).onFirst().performClick()
        // Match 2 holds no home player: only the away player of match 2 scored a jersey point.
        assertFalse(shown(text(R.string.review_jersey, 7)))
        compose.onAllNodesWithText(text(R.string.part_match, 1)).filter(hasClickAction()).onFirst().performClick()
        compose.onNodeWithText(text(R.string.review_jersey, 7)).assertIsDisplayed()
    }

    @Test fun setsWonCarryThePlusSignWhileTheyAreALowerBound() {
        show { ReportScreen(match, ReportLoad.READY, report("PARTIAL"), {}, {}, {}, {}, {}) }
        compose.onNodeWithText("1+ - 0+").assertIsDisplayed()
    }

    @Test fun measuredSetsWonCarryNoPlusSign() {
        show { ReportScreen(match, ReportLoad.READY, report("MEASURED"), {}, {}, {}, {}, {}) }
        compose.onNodeWithText("1 - 0").assertIsDisplayed()
    }

    @Test fun everyReportCardThatShowsDemoNumbersCarriesItsOwnDemoMark() {
        var mock by mutableStateOf(true)
        show { ReportScreen(match, ReportLoad.READY, report("PARTIAL").copy(mock = mock), {}, {}, {}, {}, {}) }
        // The team numbers and the coverage counts: a picture of either card alone still says DEMO.
        assertEquals(2, compose.onAllNodesWithText(text(R.string.status_demo)).fetchSemanticsNodes().size)
        mock = false
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithText(text(R.string.status_demo)).fetchSemanticsNodes().size)
    }

    @Test fun theReportSaysWhatWouldTakeThePlusSignsAwayAndForADemoThatNothingWill() {
        var mock by mutableStateOf(false)
        show { ReportScreen(match, ReportLoad.READY, report("PARTIAL").copy(mock = mock), {}, {}, {}, {}, {}) }
        compose.onNodeWithText(text(R.string.report_may_miss)).assertIsDisplayed()
        assertEquals(false, shown(text(R.string.report_may_miss_demo)))
        // Invented intervals can not be confirmed, so a demo report must not promise that confirming helps.
        mock = true
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.report_may_miss_demo)).assertIsDisplayed()
        assertEquals(false, shown(text(R.string.report_may_miss)))
    }

    @Test fun aConfirmedListIsNamedOnTheReportAndTheWarningAboutMissingRalliesIsGone() {
        show { ReportScreen(match, ReportLoad.READY, report("MEASURED").copy(listConfirmed = true), {}, {}, {}, {}, {}) }
        compose.onNodeWithText(text(R.string.report_list_confirmed)).assertIsDisplayed()
        assertEquals(false, shown(text(R.string.report_may_miss)))
    }

    @Test fun aConfirmedListMakesThePointsExactAndLeavesThePlusOnTheCountsThatStillWaitForAnAnswer() {
        // The list is confirmed, so every point is counted. Only some points have a type yet.
        val confirmed = report("MEASURED").copy(
            listConfirmed = true,
            home = TeamStats(StatValue(28, "MEASURED"), StatValue(11, "PARTIAL"), StatValue(null, "UNKNOWN"), StatValue(null, "UNKNOWN"), StatValue(null, "UNKNOWN")),
            away = TeamStats(StatValue(25, "MEASURED"), StatValue(9, "PARTIAL"), StatValue(null, "UNKNOWN"), StatValue(null, "UNKNOWN"), StatValue(null, "UNKNOWN")),
        )
        show { ReportScreen(match, ReportLoad.READY, confirmed, {}, {}, {}, {}, {}) }
        compose.onNodeWithText("28").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("25").assertIsDisplayed()
        compose.onNodeWithText("11+").assertIsDisplayed()
        compose.onNodeWithText("9+").assertIsDisplayed()
        // The report promised exactly that before the confirmation: the set score and the row of the points.
        assertTrue(text(R.string.report_may_miss).contains(text(R.string.report_points)))
    }

    @Test fun theReportNamesTheUsersCorrectionsAsASourceOfItsIntervalsOnlyWhenThereAreSome() {
        var corrections by mutableStateOf(0)
        show { ReportScreen(match, ReportLoad.READY, report("PARTIAL").copy(corrections = corrections, ralliesUserCreated = corrections), {}, {}, {}, {}, {}) }
        compose.onNodeWithText(text(R.string.report_source, "motion-baseline", "BASELINE")).performScrollTo().assertIsDisplayed()
        corrections = 2
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.report_source_corrected, "motion-baseline", "BASELINE")).performScrollTo().assertIsDisplayed()
        assertEquals(false, shown(text(R.string.report_source, "motion-baseline", "BASELINE")))
        compose.onNodeWithText(text(R.string.report_rallies_user)).performScrollTo().assertIsDisplayed()
    }

    @Test fun theReportTellsTheSizeOfTheListApartFromWhatTheAnalyzerProposed() {
        // The analyzer proposed 41 rallies. After merges and additions the list holds 37 intervals.
        show { ReportScreen(match, ReportLoad.READY, report("PARTIAL").copy(ralliesProposed = 41, ralliesTotal = 37), {}, {}, {}, {}, {}) }
        compose.onNodeWithText(text(R.string.report_rallies_listed)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("37").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.report_rallies_total)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("41").performScrollTo().assertIsDisplayed()
    }

    @Test fun theStatusScreenCountsWhatTheServerProposedAndNotWhatTheUserMade() {
        // The list holds five rallies, and the user added one of them.
        status(AnalysisUi("m1", JobLoad.READY, job(AnalysisSummary.NEEDS_REVIEW).copy(rallies = 5, proposed = 4)))
        compose.onNodeWithText(text(R.string.stage_rallies_done, 4)).performScrollTo().assertIsDisplayed()
    }

    @Test fun noEditorOpensFromACardWhileACorrectionIsOnItsWay() {
        val deck = ReviewDeck(listOf(card(1, null)), 0, 1)
        var correcting by mutableStateOf(true)
        show {
            ReviewScreen(match, AnalysisUi("m1", JobLoad.READY, job(AnalysisSummary.NEEDS_REVIEW), deck, correcting = correcting), emptyList(), {}, {}, {}, {}, {}) { ClipPlaceholder() }
        }
        compose.onNodeWithText(text(R.string.review_fix_rally)).assertIsNotEnabled()
        correcting = false
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.review_fix_rally)).assertIsEnabled()
    }

    @Test fun theReportLeadsToTheRallyList() {
        var opened = 0
        show { ReportScreen(match, ReportLoad.READY, report("PARTIAL"), {}, {}, {}, {}, {}, onOpenRallies = { opened++ }) }
        compose.onNodeWithText(text(R.string.rallies_title)).performScrollTo().performClick()
        assertEquals(1, opened)
    }

    @Test fun everyReviewCardLeadsToTheEditorOfItsRallyAndTheEndOfTheReviewLeadsToTheList() {
        val fixed = mutableListOf<String>()
        var lists = 0
        var cursor by mutableStateOf(0)
        val deck = ReviewDeck(listOf(card(1, "HOME"), card(2, "AWAY")), 2, 2)
        show {
            ReviewScreen(
                match, AnalysisUi("m1", JobLoad.READY, job(AnalysisSummary.COMPLETE), deck, cursor = cursor), emptyList(), {}, {}, {}, {}, {},
                onFixRally = { fixed += it.rallyId }, onOpenRallies = { lists++ },
            ) { ClipPlaceholder() }
        }
        compose.onNodeWithText(text(R.string.review_fix_rally)).performClick()
        assertEquals("the rally of the card on screen", listOf("r1"), fixed)
        // Past the last card every rally has an answer, and the list is where a missed rally is added.
        cursor = 2
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.rallies_title)).performClick()
        assertEquals(1, lists)
    }

    @Test fun theReportOpensTheSameDefinitionsForTheNumbersItCounts() {
        show { ReportScreen(match, ReportLoad.READY, report("PARTIAL"), {}, {}, {}, {}, {}) }
        compose.onNodeWithText(text(R.string.definitions_title)).performScrollTo().performClick()
        definitions.forEach { compose.onNodeWithText(text(it)).assertIsDisplayed() }
    }

    @Test fun playersWithoutAReportShowLoadingAndNotAnEmptyTable() {
        show { PlayersScreen(match, null, emptyList(), emptyList(), load = ReportLoad.LOADING) {} }
        compose.onNodeWithText(text(R.string.report_loading)).assertIsDisplayed()
        assertEquals(false, shown(text(R.string.players_empty)))
    }

    @Test fun playersWhoseReportDidNotLoadOfferARetry() {
        var retries = 0
        show { PlayersScreen(match, null, emptyList(), emptyList(), load = ReportLoad.UNREACHABLE, onRetry = { retries++ }) {} }
        compose.onNodeWithText(text(R.string.report_load_failed)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.action_retry)).performClick()
        assertEquals(1, retries)
    }

    private fun status(analysis: AnalysisUi, removalFailure: RemovalFailure? = null) =
        show { StatusScreen(match, null, analysis, removalFailure, {}, {}, {}, {}, {}, {}, {}) }

    @Test fun aFailedRemovalNamesItsOwnCause() {
        val sentences = mapOf(
            RemovalFailure.UNCONFIRMED to R.string.removal_unconfirmed,
            RemovalFailure.VERIFYING to R.string.removal_verifying,
            RemovalFailure.REFUSED to R.string.removal_refused,
            // An answer that leaves open whether the server deleted the match: the account changes' own texts (ADR-0119).
            RemovalFailure.SERVER_ERROR to R.string.account_outcome_unknown,
            RemovalFailure.ERROR_ON_THE_WAY to R.string.account_outcome_unknown_on_the_way,
            RemovalFailure.UNREADABLE to R.string.account_unexpected_change_signed_in,
        )
        var shownFailure by mutableStateOf<RemovalFailure?>(null)
        show { StatusScreen(match, null, AnalysisUi("m1", JobLoad.NO_JOB), shownFailure, {}, {}, {}, {}, {}, {}, {}) }
        sentences.values.forEach { assertEquals(false, shown(text(it))) }
        sentences.forEach { (failure, sentence) ->
            shownFailure = failure
            compose.onNodeWithText(text(sentence)).performScrollTo().assertIsDisplayed()
            (sentences.values - sentence).forEach { assertEquals(false, shown(text(it))) }
        }
    }

    @Test fun aCancelledJobIsNotShownAsAnErrorAndNoServerCodeReachesTheScreen() {
        status(AnalysisUi("m1", JobLoad.READY, job(AnalysisSummary.CANCELLED)))
        compose.onNodeWithText(text(R.string.analysis_cancelled_title)).assertIsDisplayed()
        assertEquals(false, shown("CANCELLED"))
        assertEquals(false, shown(text(R.string.analysis_failed_title)))
    }

    @Test fun aFailedJobIsExplainedInWordsWithoutItsCode() {
        status(AnalysisUi("m1", JobLoad.READY, job(AnalysisSummary.FAILED, failure = "JOB_TIMEOUT")))
        compose.onNodeWithText(text(R.string.analysis_failed_timeout)).assertIsDisplayed()
        assertEquals(false, shown("JOB_TIMEOUT"))
    }

    @Test fun aVideoTheServerLostIsNamedOnTheStatusScreen() {
        status(AnalysisUi("m1", JobLoad.VIDEO_MISSING))
        compose.onNodeWithText(text(R.string.analysis_video_missing_title)).assertIsDisplayed()
    }

    @Test fun aRecordingInUseHasNoDeleteButton() {
        val used = RecordingFile("kayit-1.mp4", "/files/recordings/kayit-1.mp4", 10)
        val free = RecordingFile("kayit-2.mp4", "/files/recordings/kayit-2.mp4", 10)
        val state = AppUiState(draft = Draft(homeTeam = "A", video = video.copy(location = used.path)), recordings = listOf(used, free))
        show { ProfileScreen(state, "0.4.2", {}, {}, {}, { true }) }
        compose.onNodeWithText(text(R.string.recordings_in_use)).performScrollTo().assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithContentDescription(text(R.string.recording_delete_description, used.name)).fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodesWithContentDescription(text(R.string.recording_delete_description, free.name)).fetchSemanticsNodes().size)
    }

    @Test fun theProfileSaysWhoSeesTheVideoHowLongTheServerKeepsItAndHowItIsDeleted() {
        show { ProfileScreen(AppUiState(), "0.4.2", {}, {}, {}, { true }) }
        listOf(
            R.string.privacy_accounts, R.string.privacy_matches_open, R.string.privacy_phone_accounts, R.string.privacy_session,
            R.string.privacy_retention, R.string.privacy_delete, R.string.privacy_faces,
        ).forEach {
            compose.onNodeWithText(text(it)).performScrollTo().assertIsDisplayed()
        }
    }

    /**
     * Review finding T1. Besides the name and the password digest, the server keeps when and how the
     * account opened, when the password last changed, and a digest per session and per phone that
     * logged in, each with its times (backend db.py). The card names them all and claims no "only".
     * A deletion removes the user row, and its sessions and phones go with it (backend test_auth).
     */
    @Test fun thePrivacyCardNamesWhatTheServerKeepsForAnAccountAndThatADeletionRemovesAllOfIt() {
        show { ProfileScreen(AppUiState(), "0.4.2", {}, {}, {}, { true }) }
        listOf(
            "Sunucu hesabın için kullanıcı adını, görünen adını ve şifrenin özetini saklar. " +
                "Sunucu hesabın ne zaman ve nasıl açıldığını ve şifrenin son değiştiği zamanı da kaydeder. " +
                "Her oturum ve giriş yaptığın her telefon için sunucu bir anahtar özetini ve kullanım zamanlarını saklar. " +
                "Uygulama e-posta adresini ve telefon numaranı sormaz.",
            "Sen maçı silince sunucu o maçın videosunu, analiz sonuçlarını, verdiğin kararları, yaptığın düzeltmeleri ve maç bilgilerini siler. " +
                "Telefonundaki video dosyası kalır. Sen hesabını silince sunucu hesabın için sakladığı bütün bilgileri siler.",
        ).forEach { compose.onNodeWithText(it).performScrollTo().assertIsDisplayed() }
        assertFalse(shown("yalnızca kullanıcı adını"))
    }

    @Test fun theProfileOpensTheMailAppForFeedbackAndNamesTheAddressWhenThereIsNone() {
        var opened = 0
        var mailApp = true
        show { ProfileScreen(AppUiState(), "0.4.2", {}, {}, {}, { opened++; mailApp }) }
        compose.onNodeWithText(text(R.string.feedback_address, FEEDBACK_ADDRESS)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.action_send_feedback)).performScrollTo().performClick()
        assertEquals(1, opened)
        assertEquals(0, compose.onAllNodesWithText(text(R.string.feedback_no_mail_app)).fetchSemanticsNodes().size)
        mailApp = false
        compose.onNodeWithText(text(R.string.action_send_feedback)).performScrollTo().performClick()
        assertEquals(2, opened)
        compose.onNodeWithText(text(R.string.feedback_no_mail_app)).performScrollTo().assertIsDisplayed()
    }

    @Test fun theStartButtonOfTheWelcomeScreenCanBeReachedInLandscapeWithLargeText() {
        var started = 0
        RuntimeEnvironment.setQualifiers("+w640dp-h320dp-land")
        RuntimeEnvironment.setFontScale(1.5f)
        try {
            show { WelcomeScreen { started++ } }
            compose.onNodeWithText(text(R.string.action_start)).performScrollTo().assertIsDisplayed().performClick()
            assertEquals(1, started)
        } finally {
            RuntimeEnvironment.setFontScale(1f)
            RuntimeEnvironment.setQualifiers("+w411dp-h891dp-port")
        }
    }

    // ----- the match rule (ADR-0115) -----

    @Test fun theNewMatchScreenNamesTheFivbRuleAndContinuesWithoutATouch() {
        var continues = 0
        var opens = 0
        show { MatchCreateScreen(Draft(), "2026-11-14", {}, onContinue = { continues++ }, onBack = {}, onOpenRules = { opens++ }) }
        compose.onNodeWithText(text(R.string.rule_name_fivb)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.rule_detail, 3, 25, 5, 15)).assertIsDisplayed()
        // A standard match needs no touch on the rule.
        compose.onNodeWithText(text(R.string.action_continue)).performClick()
        assertEquals(1 to 0, continues to opens)
    }

    @Test fun aChosenRuleIsNamedOnTheNewMatchScreen() {
        var opens = 0
        show { MatchCreateScreen(Draft(rules = MatchRules(2, 21, 21)), "2026-11-14", {}, onContinue = {}, onBack = {}, onOpenRules = { opens++ }) }
        compose.onNodeWithText(text(R.string.rule_name_custom)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.rule_detail_same, 2, 21)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.rule_card_change)).performClick()
        assertEquals(1, opens)
    }

    @Test fun homeNamesTheRuleAnUnfinishedSetupHolds() {
        var opens = 0
        show { HomeScreen(AppUiState(draft = Draft(rules = MatchRules(2, 21, 15))), { opens++ }, {}, {}, {}, {}) }
        // The big "Yeni Maç" card opens this same setup, so the card of the setup says which rule goes with it.
        compose.onNodeWithText(text(R.string.home_draft_title)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.rule_name_custom)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.rule_detail, 2, 21, 3, 15)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.home_draft_title)).performClick()
        assertEquals(1, opens)
    }

    @Test fun homeNamesNoRuleForASetupWithTheStandardRule() {
        show { HomeScreen(AppUiState(draft = Draft(name = "Hazırlık Maçı")), {}, {}, {}, {}, {}) }
        compose.onNodeWithText(text(R.string.home_draft_title)).performScrollTo().assertIsDisplayed()
        assertEquals(false, shown(text(R.string.rule_card_label)))
        assertEquals(false, shown(text(R.string.rule_name_fivb)))
    }

    @Test fun theLastScreenBeforeTheUploadNamesARuleThatIsNotTheStandardOne() {
        var opens = 0
        show {
            VideoCheckScreen(video, rightsAccepted = false, serverConfigured = true, onRights = {}, onStart = {}, onChangeVideo = {}, onBack = {},
                rules = MatchRules(2, 21, 15), onOpenRules = { opens++ })
        }
        compose.onNodeWithText(text(R.string.rule_name_custom)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.rule_detail, 2, 21, 3, 15)).assertIsDisplayed()
        // The rule stands right above the box the user has to tick before the upload can start.
        val rule = compose.onNodeWithText(text(R.string.rule_detail, 2, 21, 3, 15), useUnmergedTree = true).getUnclippedBoundsInRoot()
        val box = compose.onNodeWithText(text(R.string.check_rights), useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(rule.bottom <= box.top)
        compose.onNodeWithText(text(R.string.rule_card_change)).performClick()
        assertEquals(1, opens)
    }

    @Test fun theLastScreenBeforeTheUploadShowsNoRuleForAStandardMatch() {
        show { VideoCheckScreen(video, rightsAccepted = false, serverConfigured = true, onRights = {}, onStart = {}, onChangeVideo = {}, onBack = {}) }
        compose.onNodeWithText(text(R.string.check_rights)).performScrollTo().assertIsDisplayed()
        assertEquals(false, shown(text(R.string.rule_card_label)))
    }

    @Test fun onANarrowPhoneWithLargeTextTheDateCardPutsItsActionUnderTheDate() {
        // A 320 dp phone at 1.5x text. Beside the date, the action would leave the date so little room that it breaks.
        var scale by mutableStateOf(1.5f)
        var width by mutableStateOf(320.dp)
        show {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                Box(Modifier.width(width)) { MatchCreateScreen(Draft(), "2026-08-14", {}, onContinue = {}, onBack = {}) }
            }
        }
        fun bounds(label: String) = compose.onNodeWithText(label, useUnmergedTree = true).getUnclippedBoundsInRoot()
        // The date itself, the last line of the card. The label above it ends higher than an action that
        // stands beside a date broken over three lines, so a comparison with the label passes on both layouts.
        val date = formatDay("2026-08-14")
        val change = text(R.string.match_date_pick)
        assertTrue("the action stands under the date", bounds(change).top >= bounds(date).bottom)

        // A 411 dp phone at normal text has room for both on one line.
        scale = 1f
        width = 411.dp
        compose.waitForIdle()
        assertTrue("with room, the action stands beside the date", bounds(change).left >= bounds(date).right)
    }

    @Test fun theReportNamesTheRuleTheServerCountedWith() {
        show { ReportScreen(match, ReportLoad.READY, report("PARTIAL").copy(rules = MatchRules(2, 21, 15)), {}, {}, {}, {}, {}) }
        compose.onNodeWithText(text(R.string.report_rules, text(R.string.rule_counting, 21, 3, 15))).performScrollTo().assertIsDisplayed()
        // The report says where a match ends and that play after it starts a new match.
        compose.onNodeWithText(text(R.string.rule_match_end)).performScrollTo().assertIsDisplayed()
    }

    @Test fun aReportWithoutARuleLeavesTheRuleLineOut() {
        show { ReportScreen(match, ReportLoad.READY, report("PARTIAL"), {}, {}, {}, {}, {}) }
        // "Skor kuralı:" with nothing after it would name no rule; the line is left out instead.
        assertEquals(false, shown(text(R.string.report_rules, "").trim()))
        assertEquals(false, shown(text(R.string.rule_match_end)))
    }

    // ----- accounts (slice B2) -----

    private val ali = AccountUser("user-ali", "ali", "Ali Yılmaz")
    private fun field(label: Int) = compose.onNode(hasSetTextAction() and hasText(text(label)))
    private val signedIn = AppUiState(account = AccountUi(user = ali), settings = Settings(welcomeSeen = true, accountSeen = true, legacyHandled = true))

    /** Counts the commits of the autofill service: the password-save sheet appears on a commit. */
    private class CountingAutofill {
        var commits = 0
        val commit: () -> Unit = { commits++ }
    }

    @Test fun theStartScreenOffersAnAccountOrALoginAndNoCodeEntryYet() {
        var created = 0
        var login = 0
        show { StartScreen(onCreateAccount = { created++ }, onLogin = { login++ }) }
        compose.onNodeWithText(text(R.string.start_body)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.action_create_account)).performClick()
        compose.onNodeWithText(text(R.string.action_login)).performClick()
        assertEquals(1 to 1, created to login)
        // Kod Gir comes with the invite links (slice B6).
        assertFalse(shown("Kod Gir"))
    }

    /** The third review's B5: a release build has no server address, so the start screen says what that means and opens no form. */
    @Test fun aStartScreenWithoutAServerSaysSoAndItsButtonsOpenNoForm() {
        var created = 0
        var login = 0
        show { StartScreen(onCreateAccount = { created++ }, onLogin = { login++ }, serverConfigured = false) }
        compose.onNodeWithText(text(R.string.server_none)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.start_no_server)).assertIsDisplayed()
        assertFalse("no promise of an account or a login", shown(text(R.string.start_body)))
        compose.onNode(hasClickAction() and hasText(text(R.string.action_create_account))).assertIsNotEnabled().performClick()
        compose.onNode(hasClickAction() and hasText(text(R.string.action_login))).assertIsNotEnabled().performClick()
        assertEquals(0 to 0, created to login)
    }

    @Test fun aStartScreenWithAServerSaysNothingAboutAMissingOne() {
        show { StartScreen(onCreateAccount = {}, onLogin = {}, serverConfigured = true) }
        compose.onNodeWithText(text(R.string.start_body)).assertIsDisplayed()
        assertFalse(shown(text(R.string.server_none)))
        assertFalse(shown(text(R.string.start_no_server)))
        compose.onNode(hasClickAction() and hasText(text(R.string.action_create_account))).assertIsEnabled()
        compose.onNode(hasClickAction() and hasText(text(R.string.action_login))).assertIsEnabled()
    }

    @Test fun theLoginSendsWhatWasTypedOnlyWithBothFieldsAndCommitsTheAutofill() {
        val sent = mutableListOf<Pair<String, String>>()
        val autofill = CountingAutofill()
        show { CompositionLocalProvider(LocalAutofillCommit provides autofill.commit) { LoginScreen(AccountUi(), onLogin = { u, p -> sent += u to p }, onCreateAccount = {}) } }
        compose.onNodeWithText(text(R.string.login_intro)).assertIsDisplayed()
        compose.onNode(hasClickAction() and hasText(text(R.string.action_login))).assertIsNotEnabled()
        field(R.string.field_username).performTextInput("Ali")
        compose.onNode(hasClickAction() and hasText(text(R.string.action_login))).assertIsNotEnabled()
        field(R.string.field_password).performTextInput("uzun bir şifre 42")
        compose.onNode(hasClickAction() and hasText(text(R.string.action_login))).assertIsEnabled().performClick()
        assertEquals(listOf("Ali" to "uzun bir şifre 42"), sent)
        assertEquals("the save sheet may appear while the request is on its way", 1, autofill.commits)
        // The fields carry the autofill semantics of a login.
        field(R.string.field_username).assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentType, ContentType.Username))
        field(R.string.field_password).assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentType, ContentType.Password))
    }

    @Test fun thePasswordStaysHiddenUntilTheUserAsksToSeeIt() {
        show { LoginScreen(AccountUi(), onLogin = { _, _ -> }, onCreateAccount = {}) }
        // What the field draws: dots while hidden, the typed text once the user asked to see it.
        fun drawn() = field(R.string.field_password).fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        field(R.string.field_password).performTextInput("uzun bir şifre 42")
        assertFalse("hidden, the password is not drawn", drawn().contains("uzun"))
        assertEquals(17, drawn().length)
        compose.onNodeWithContentDescription(text(R.string.password_show)).performClick()
        assertEquals("uzun bir şifre 42", drawn())
        compose.onNodeWithContentDescription(text(R.string.password_hide)).performClick()
        assertFalse(drawn().contains("uzun"))
    }

    @Test fun whileTheServerHasNotAnsweredTheLoginWaitsAndTakesNoSecondTap() {
        show { LoginScreen(AccountUi(busy = true), onLogin = { _, _ -> }, onCreateAccount = {}, initialUsername = "ali", initialPassword = "uzun bir şifre 42") }
        compose.onNodeWithText(text(R.string.account_waiting)).assertIsDisplayed()
        compose.onNode(hasClickAction() and hasText(text(R.string.action_login))).assertIsNotEnabled()
    }

    /**
     * Review finding T5. After SERVER_BUSY the app already has the server's answer and waits out its
     * Retry-After before it sends again. Every account form says so (design 2.12 `server_busy`) and
     * keeps its buttons off; the ordinary wait for an answer keeps its own text.
     */
    @Test fun whileTheAppWaitsToSendAgainAfterABusyServerEveryAccountFormSaysSo() {
        var which by mutableStateOf(0)
        var account by mutableStateOf(AccountUi(busy = true, retryingAfterBusy = true))
        show {
            when (which) {
                0 -> LoginScreen(account, onLogin = { _, _ -> }, onCreateAccount = {}, initialUsername = "ali", initialPassword = "uzun bir şifre 42")
                1 -> RegisterScreen(account, onRegister = { _, _, _, _ -> }, onHaveAccount = {}, onBack = {})
                2 -> PasswordChangeScreen(account.copy(user = ali), { _, _ -> }, onBack = {})
                3 -> DeleteAccountScreen(account.copy(user = ali), serverMatches = 0, onDelete = {}, onBack = {})
                // The row under Adı Kaydet and the logout dialog show the outcome of their call.
                else -> AccountOutcome(account.copy(user = ali), AccountAction.LOGOUT)
            }
        }
        val first = listOf(R.string.action_login, R.string.action_create_account, R.string.action_change_password, R.string.action_delete_account)
        for (screen in 0..4) {
            which = screen
            account = AccountUi(busy = true, retryingAfterBusy = true)
            compose.onNodeWithText(text(R.string.server_busy)).assertIsDisplayed()
            assertFalse("screen $screen", shown(text(R.string.account_waiting)))
            first.getOrNull(screen)?.let { compose.onNode(hasClickAction() and hasText(text(it))).assertIsNotEnabled() }
            // The next try is on its way: the form waits for its answer again.
            account = AccountUi(busy = true)
            compose.onNodeWithText(text(R.string.account_waiting)).assertIsDisplayed()
            assertFalse("screen $screen", shown(text(R.string.server_busy)))
        }
    }

    @Test fun theLoginNamesAnEndedSessionADeletedAccountAndEveryRefusal() {
        var account by mutableStateOf(AccountUi(sessionEnded = true))
        var created = 0
        show { LoginScreen(account, onLogin = { _, _ -> }, onCreateAccount = { created++ }) }
        compose.onNodeWithText(text(R.string.session_ended)).assertIsDisplayed()
        account = AccountUi(confirmed = AccountChange.DELETED)
        compose.onNodeWithText(text(R.string.account_deleted)).assertIsDisplayed()
        assertFalse(shown(text(R.string.session_ended)))
        listOf(
            AuthProblem.LoginFailed to text(R.string.login_failed),
            AuthProblem.TooManyAttempts(AttemptScope.USER, 30) to text(R.string.login_wait, "30 saniye"),
            AuthProblem.TooManyAttempts(AttemptScope.DEVICE, 120) to text(R.string.login_wait_device, "2 dakika"),
            AuthProblem.TooManyAttempts(AttemptScope.SOURCE, 900) to text(R.string.login_wait_source, "15 dakika"),
            AuthProblem.ServerOutdated to text(R.string.account_server_older),
            AuthProblem.Unreachable to text(R.string.net_no_answer),
            AuthProblem.UnexpectedAnswer to text(R.string.account_unexpected),
            AuthProblem.ServerBusy(1) to text(R.string.server_busy_gave_up, 5),
        ).forEach { (problem, words) ->
            account = AccountUi(problem = problem)
            compose.onNodeWithText(words).assertIsDisplayed()
        }
        compose.onNodeWithText(text(R.string.action_create_account)).performClick()
        assertEquals(1, created)
    }

    @Test fun aBuildWithoutAServerSaysSoAndCanNotLogIn() {
        show { LoginScreen(AccountUi(), onLogin = { _, _ -> }, onCreateAccount = {}, serverConfigured = false, initialUsername = "ali", initialPassword = "uzun bir şifre 42") }
        compose.onNodeWithText(text(R.string.server_none)).assertIsDisplayed()
        compose.onNode(hasClickAction() and hasText(text(R.string.action_login))).assertIsNotEnabled()
    }

    @Test fun theRegisterFormSuggestsTheUsernameFromTheDisplayNameUntilTheUserChangesIt() {
        show { RegisterScreen(AccountUi(), onRegister = { _, _, _, _ -> }, onHaveAccount = {}, onBack = {}) }
        compose.onNodeWithText(text(R.string.register_data)).assertIsDisplayed()
        field(R.string.field_display_name).performTextInput("Çağrı Öztürk")
        field(R.string.field_username).assertTextContains("cagri.ozturk")
        compose.onNodeWithText(text(R.string.register_username_suggested)).performScrollTo().assertIsDisplayed()
        // The user's own username stays when the display name changes.
        field(R.string.field_username).performTextReplacement("cagri")
        assertFalse(shown(text(R.string.register_username_suggested)))
        field(R.string.field_display_name).performTextReplacement("Çağrı Ö.")
        field(R.string.field_username).assertTextContains("cagri")
        field(R.string.field_display_name).assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentType, ContentType.PersonFullName))
        field(R.string.field_username).assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentType, ContentType.NewUsername))
        field(R.string.field_password).assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentType, ContentType.NewPassword))
    }

    @Test fun anInvalidUsernameIsNamedAndNotSent() {
        show { RegisterScreen(AccountUi(), onRegister = { _, _, _, _ -> }, onHaveAccount = {}, onBack = {}, initialDisplayName = "Ali", initialPassword = "uzun bir şifre 42") }
        field(R.string.field_username).performTextReplacement("7ali")
        compose.onNodeWithText(text(R.string.username_invalid)).performScrollTo().assertIsDisplayed()
        compose.onNode(hasClickAction() and hasText(text(R.string.action_create_account))).assertIsNotEnabled()
        // Capitals are fine: the app sends the name lowercased.
        field(R.string.field_username).performTextReplacement("Ali.Veli")
        assertFalse(shown(text(R.string.username_invalid)))
        compose.onNode(hasClickAction() and hasText(text(R.string.action_create_account))).assertIsEnabled()
    }

    @Test fun theRegisterFormSendsItsFieldsWithTheCodeAndCommitsTheAutofill() {
        val sent = mutableListOf<List<String>>()
        val autofill = CountingAutofill()
        show {
            CompositionLocalProvider(LocalAutofillCommit provides autofill.commit) {
                RegisterScreen(AccountUi(), onRegister = { d, u, p, c -> sent += listOf(d, u, p, c) }, onHaveAccount = {}, onBack = {})
            }
        }
        compose.onNode(hasClickAction() and hasText(text(R.string.action_create_account))).assertIsNotEnabled()
        field(R.string.field_display_name).performTextInput("İlker Işık")
        field(R.string.field_password).performTextInput("uzun bir şifre 42")
        field(R.string.field_signup_code).performScrollTo().performTextInput("abcd-efgh")
        compose.onNodeWithText(text(R.string.register_code_hint)).performScrollTo().assertIsDisplayed()
        compose.onNode(hasClickAction() and hasText(text(R.string.action_create_account))).assertIsEnabled().performClick()
        assertEquals(listOf(listOf("İlker Işık", "ilker.isik", "uzun bir şifre 42", "abcd-efgh")), sent)
        assertEquals(1, autofill.commits)
    }

    @Test fun aTakenUsernameGetsANewSuggestionOnceAndTheFormSaysWhy() {
        var account by mutableStateOf(AccountUi())
        val sent = mutableListOf<String>()
        show { RegisterScreen(account, onRegister = { _, u, _, _ -> sent += u }, onHaveAccount = {}, onBack = {}, initialDisplayName = "Çağrı Öztürk", initialPassword = "uzun bir şifre 42") }
        compose.onNode(hasClickAction() and hasText(text(R.string.action_create_account))).performClick()
        assertEquals(listOf("cagri.ozturk"), sent)
        account = AccountUi(busy = true)
        compose.waitForIdle()
        account = AccountUi(problem = AuthProblem.UsernameTaken)
        compose.onNodeWithText(text(R.string.username_taken)).assertIsDisplayed()
        val field = field(R.string.field_username).fetchSemanticsNode()
        val shownName = field.config[SemanticsProperties.EditableText].text
        assertTrue(shownName, Regex("cagri\\.ozturk[1-9][0-9]").matches(shownName))
        // The display name no longer drives the username, and the screen changes it only once.
        field(R.string.field_display_name).performTextReplacement("Çağrı Ö")
        field(R.string.field_username).assertTextContains(shownName)
    }

    @Test fun everyRefusalOfARegistrationIsShownInWords() {
        var account by mutableStateOf(AccountUi())
        show { RegisterScreen(account, onRegister = { _, _, _, _ -> }, onHaveAccount = {}, onBack = {}) }
        listOf(
            AuthProblem.SignupClosed(SignupClosedReason.NO_LINK) to text(R.string.register_gate),
            AuthProblem.SignupCodeInvalid to text(R.string.register_code_invalid),
            AuthProblem.UsernameInvalid to text(R.string.username_invalid),
            AuthProblem.DisplayNameInvalid to text(R.string.display_name_invalid),
            AuthProblem.PasswordRejected(PasswordRule.TOO_SHORT) to text(R.string.password_too_short),
            AuthProblem.PasswordRejected(PasswordRule.TOO_LONG) to text(R.string.password_too_long),
            AuthProblem.PasswordRejected(PasswordRule.CONTAINS_USERNAME) to text(R.string.password_contains_username),
            AuthProblem.PasswordRejected(PasswordRule.REPETITIVE) to text(R.string.password_repetitive),
            AuthProblem.PasswordRejected(PasswordRule.COMMON) to text(R.string.password_common),
            AuthProblem.TooManyAttempts(AttemptScope.REGISTER_SOURCE, 600) to text(R.string.register_wait_source, "10 dakika"),
            AuthProblem.Refused(422, "VALIDATION_ERROR") to text(R.string.account_refused),
        ).forEach { (problem, words) ->
            account = AccountUi(problem = problem)
            compose.onNodeWithText(words).assertIsDisplayed()
            assertFalse(shown((problem as? AuthProblem.Refused)?.code ?: "VALIDATION_ERROR"))
        }
    }

    @Test fun theOldDataQuestionListsWhatWouldMoveAndMovesIt() {
        val answers = mutableListOf<Boolean>()
        var moving by mutableStateOf(false)
        // The view model marks the answer as on its way the moment it takes it (answerLegacy).
        show {
            LegacyScreen(
                LegacyQuestion(listOf("Hazırlık Maçı", "A Takımı - B Takımı"), teams = 2, recordings = 1, draft = true),
                saveFailed = false, moving = moving, onAnswer = { answers += it; moving = true },
            )
        }
        compose.onNodeWithText(text(R.string.legacy_body)).assertIsDisplayed()
        // Review finding T8: two teams have their rosters, "kadroları".
        listOf("Hazırlık Maçı", "A Takımı - B Takımı", "2 takım ve kadroları", text(R.string.legacy_recordings, 1), text(R.string.legacy_draft), text(R.string.legacy_keep_note))
            .forEach { compose.onNodeWithText(it).performScrollTo().assertIsDisplayed() }
        compose.onNodeWithText(text(R.string.legacy_move)).performClick()
        // One answer: the buttons wait for it to land.
        compose.onNodeWithText(text(R.string.legacy_move)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.legacy_keep)).assertIsNotEnabled()
        assertEquals(listOf(true), answers)
    }

    @Test fun theOldDataCanStayWhereItIsAndAFailedMoveCanBeTriedAgain() {
        val answers = mutableListOf<Boolean>()
        var failed by mutableStateOf(false)
        var moving by mutableStateOf(false)
        show { LegacyScreen(LegacyQuestion(emptyList(), teams = 1, recordings = 0, draft = false), saveFailed = failed, moving = moving, onAnswer = { answers += it; moving = true }) }
        // Review finding T8: one team has one roster, "kadrosu".
        compose.onNodeWithText("1 takım ve kadrosu").assertIsDisplayed()
        assertFalse(shown("kadroları"))
        assertFalse(shown(text(R.string.legacy_recordings, 0)))
        assertFalse(shown(text(R.string.legacy_draft)))
        // The first failure turns saveFailed on; the second one leaves it on. Each gives the buttons back (F4).
        repeat(2) {
            compose.onNodeWithText(text(R.string.legacy_move)).performClick()
            compose.onNodeWithText(text(R.string.legacy_keep)).assertIsNotEnabled()
            moving = false
            failed = true
            compose.onNodeWithText(text(R.string.store_save_failed)).assertIsDisplayed()
            compose.onNodeWithText(text(R.string.legacy_move)).assertIsEnabled()
        }
        compose.onNodeWithText(text(R.string.legacy_keep)).assertIsEnabled().performClick()
        assertEquals(listOf(true, true, false), answers)
    }

    @Test fun theServerTestSaysThatTheServerAnsweredOnlyAfterItsOwnHealthAnswer() {
        var test by mutableStateOf(ServerTest.OK)
        show { ProfileScreen(signedIn.copy(serverTest = test), "0.8.0", {}, {}, {}, { true }) }
        compose.onNodeWithText(text(R.string.server_ok)).performScrollTo().assertIsDisplayed()
        // An answer that is not the API's health answer: a blank 2xx, another service's JSON, a gateway's page.
        test = ServerTest.UNREADABLE
        compose.onNodeWithText(text(R.string.server_unreadable)).performScrollTo().assertIsDisplayed()
        listOf(R.string.server_ok, R.string.server_ok_no_probe, R.string.server_unreachable).forEach { assertFalse(shown(text(it))) }
    }

    @Test fun theProfileShowsTheAccountAndHasNoLocalNameField() {
        show { ProfileScreen(signedIn, "0.8.0", {}, {}, {}, { true }) }
        compose.onNodeWithText(text(R.string.profile_account)).assertIsDisplayed()
        compose.onNodeWithText("ali").assertIsDisplayed()
        field(R.string.field_display_name).assertTextContains("Ali Yılmaz")
        listOf(R.string.action_change_password, R.string.action_logout, R.string.action_delete_account).forEach {
            compose.onNodeWithText(text(it)).performScrollTo().assertIsDisplayed()
        }
        assertFalse(shown("Adın"))
        assertFalse(shown(text(R.string.password_temporary)))
    }

    @Test fun aNewDisplayNameIsSentAndCalledSavedOnlyAfterTheServerKeptIt() {
        var state by mutableStateOf(signedIn)
        val names = mutableListOf<String>()
        show { ProfileScreen(state, "0.8.0", {}, {}, {}, { true }, account = ProfileAccountActions(onChangeDisplayName = { names += it })) }
        compose.onNodeWithText(text(R.string.action_save_name)).assertIsNotEnabled()
        field(R.string.field_display_name).performTextReplacement("Ali Y.")
        compose.onNodeWithText(text(R.string.action_save_name)).assertIsEnabled().performClick()
        assertEquals(listOf("Ali Y."), names)
        assertFalse(shown(text(R.string.display_name_saved)))
        state = state.copy(account = state.account.copy(problem = AuthProblem.DisplayNameInvalid))
        compose.onNodeWithText(text(R.string.display_name_invalid)).assertIsDisplayed()
        state = state.copy(account = AccountUi(user = ali.copy(displayName = "Ali Y."), confirmed = AccountChange.DISPLAY_NAME))
        compose.onNodeWithText(text(R.string.display_name_saved)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.action_save_name)).assertIsNotEnabled()
    }

    @Test fun theProfileOpensThePasswordChangeAndSaysWhenTheServerChangedIt() {
        var state by mutableStateOf(signedIn)
        var opened = 0
        show { ProfileScreen(state, "0.8.0", {}, {}, {}, { true }, account = ProfileAccountActions(onOpenPasswordChange = { opened++ })) }
        compose.onNodeWithText(text(R.string.action_change_password)).performScrollTo().performClick()
        assertEquals(1, opened)
        assertFalse(shown(text(R.string.password_changed)))
        state = state.copy(account = AccountUi(user = ali, confirmed = AccountChange.PASSWORD))
        compose.onNodeWithText(text(R.string.password_changed)).performScrollTo().assertIsDisplayed()
    }

    @Test fun thePasswordChangeAsksForBothPasswordsAndNamesWhatTheServerAnswered() {
        var account by mutableStateOf(AccountUi(user = ali))
        val sent = mutableListOf<Pair<String, String>>()
        val autofill = CountingAutofill()
        show { CompositionLocalProvider(LocalAutofillCommit provides autofill.commit) { PasswordChangeScreen(account, { old, new -> sent += old to new }, onBack = {}) } }
        compose.onNodeWithText(text(R.string.password_change_body)).assertIsDisplayed()
        val confirm = compose.onNode(hasClickAction() and hasText(text(R.string.action_change_password)))
        confirm.assertIsNotEnabled()
        field(R.string.field_current_password).performTextInput("eski bir şifre 1")
        field(R.string.field_new_password).performTextInput("yeni bir şifre 2")
        field(R.string.field_current_password).assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentType, ContentType.Password))
        field(R.string.field_new_password).assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentType, ContentType.NewPassword))
        confirm.assertIsEnabled().performClick()
        assertEquals(listOf("eski bir şifre 1" to "yeni bir şifre 2"), sent)
        // The save sheet waits for the server's word: it may refuse the new password (review finding S6).
        assertEquals(0, autofill.commits)
        account = account.copy(busy = true)
        compose.onNodeWithText(text(R.string.account_waiting)).assertIsDisplayed()
        confirm.assertIsNotEnabled()
        account = account.copy(busy = false, problem = AuthProblem.PasswordWrong)
        compose.onNodeWithText(text(R.string.password_wrong)).assertIsDisplayed()
        account = account.copy(problem = AuthProblem.TooManyAttempts(AttemptScope.ACCOUNT, 120))
        compose.onNodeWithText(text(R.string.account_wait, "2 dakika")).assertIsDisplayed()
        account = account.copy(problem = AuthProblem.PasswordRejected(PasswordRule.CONTAINS_USERNAME))
        compose.onNodeWithText(text(R.string.password_contains_username)).assertIsDisplayed()
    }

    @Test fun aLogoutTheServerDidNotConfirmOffersToLeaveOnThisPhoneOnly() {
        var state by mutableStateOf(signedIn.copy(upload = com.voleybolveveleybol.volleystat.UploadProgress("m1", running = true)))
        val logouts = mutableListOf<Boolean>()
        show { ProfileScreen(state, "0.8.0", {}, {}, {}, { true }, account = ProfileAccountActions(onLogout = { logouts += it })) }
        compose.onNodeWithText(text(R.string.action_logout)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.logout_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.logout_body)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.logout_upload)).assertIsDisplayed()
        compose.onNode(hasClickAction() and hasText(text(R.string.action_logout)) and hasAnyAncestor(isDialog())).performClick()
        assertEquals(listOf(false), logouts)
        state = state.copy(account = state.account.copy(problem = AuthProblem.Unreachable))
        compose.onNodeWithText(text(R.string.logout_not_confirmed, text(R.string.action_logout_anyway))).assertIsDisplayed()
        compose.onNode(isDialog()).captureRoboImage("build/outputs/roborazzi/profil_cikis_onaysiz.png")
        compose.onNodeWithText(text(R.string.action_logout_anyway)).performClick()
        assertEquals(listOf(false, true), logouts)
    }

    @Test fun theDeletionNeedsThePasswordAndSaysWhatTheServerKeeps() {
        var account by mutableStateOf(AccountUi(user = ali))
        val deletions = mutableListOf<String>()
        show { DeleteAccountScreen(account, serverMatches = 2, onDelete = { deletions += it }, onBack = {}) }
        compose.onNodeWithText(text(R.string.delete_account_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.delete_account_body)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.delete_account_server_matches, 2)).assertIsDisplayed()
        val confirm = compose.onNode(hasClickAction() and hasText(text(R.string.action_delete_account)))
        confirm.assertIsNotEnabled()
        field(R.string.field_your_password).performTextInput("uzun bir şifre 42")
        confirm.assertIsEnabled().performClick()
        assertEquals(listOf("uzun bir şifre 42"), deletions)
        account = account.copy(busy = true)
        compose.onNodeWithText(text(R.string.account_waiting)).assertIsDisplayed()
        confirm.assertIsNotEnabled()
        account = account.copy(busy = false, problem = AuthProblem.PasswordWrong)
        compose.onNodeWithText(text(R.string.password_wrong)).assertIsDisplayed()
    }

    @Test fun aDeletionNamesNoServerMatchesWhenTheListHoldsNone() {
        show { DeleteAccountScreen(AccountUi(user = ali), serverMatches = 0, onDelete = {}, onBack = {}) }
        compose.onNodeWithText(text(R.string.delete_account_body)).assertIsDisplayed()
        assertFalse(shown(text(R.string.delete_account_server_matches, 0)))
    }

    @Test fun theProfileOpensTheDeletion() {
        var opened = 0
        show { ProfileScreen(signedIn, "0.8.0", {}, {}, {}, { true }, account = ProfileAccountActions(onOpenDeletion = { opened++ })) }
        compose.onNodeWithText(text(R.string.action_delete_account)).performScrollTo().performClick()
        assertEquals(1, opened)
    }

    @Test fun aTemporaryPasswordIsNamedOnHomeAndOnTheProfile() {
        val state = signedIn.copy(account = AccountUi(user = ali.copy(passwordTemporary = true)))
        show { HomeScreen(state, {}, {}, {}, {}, {}) }
        compose.onNodeWithText(text(R.string.password_temporary)).assertIsDisplayed()
    }

    @Test fun theProfileNamesATemporaryPassword() {
        show { ProfileScreen(signedIn.copy(account = AccountUi(user = ali.copy(passwordTemporary = true))), "0.8.0", {}, {}, {}, { true }) }
        compose.onNodeWithText(text(R.string.password_temporary)).performScrollTo().assertIsDisplayed()
    }
}
