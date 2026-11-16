package com.voleybolveveleybol.volleystat.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import com.voleybolveveleybol.volleystat.model.AnalysisJob
import com.voleybolveveleybol.volleystat.model.AnalysisSummary
import com.voleybolveveleybol.volleystat.model.Coverage
import com.voleybolveveleybol.volleystat.model.Draft
import com.voleybolveveleybol.volleystat.model.LocalVideo
import com.voleybolveveleybol.volleystat.model.MatchRecord
import com.voleybolveveleybol.volleystat.model.MatchReport
import com.voleybolveveleybol.volleystat.model.ReviewCard
import com.voleybolveveleybol.volleystat.model.ReviewDeck
import com.voleybolveveleybol.volleystat.model.SetScore
import com.voleybolveveleybol.volleystat.model.StatValue
import com.voleybolveveleybol.volleystat.model.TeamStats
import com.voleybolveveleybol.volleystat.model.VideoCheck
import com.voleybolveveleybol.volleystat.model.VideoSource
import com.voleybolveveleybol.volleystat.ui.screens.ClipPlaceholder
import com.voleybolveveleybol.volleystat.ui.screens.PlayersScreen
import com.voleybolveveleybol.volleystat.ui.screens.ProfileScreen
import com.voleybolveveleybol.volleystat.ui.screens.ReportScreen
import com.voleybolveveleybol.volleystat.ui.screens.ReviewScreen
import com.voleybolveveleybol.volleystat.ui.screens.StatusScreen
import com.voleybolveveleybol.volleystat.ui.screens.WelcomeScreen
import com.voleybolveveleybol.volleystat.ui.theme.VolleyStatTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

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
        scoreStatus = scoreStatus, setsWonHome = 1, setsWonAway = 0, sets = listOf(SetScore(1, 25, 21, true)),
        ralliesMayBeMissing = scoreStatus == "PARTIAL",
        home = TeamStats(StatValue(28, "PARTIAL"), StatValue(null, "UNKNOWN"), StatValue(null, "UNKNOWN"), StatValue(null, "UNKNOWN"), StatValue(null, "UNKNOWN")),
        away = TeamStats(StatValue(25, "PARTIAL"), StatValue(null, "UNKNOWN"), StatValue(null, "UNKNOWN"), StatValue(null, "UNKNOWN"), StatValue(null, "UNKNOWN")),
        players = emptyList(), unattributedHome = 28, unattributedAway = 25, coverage = listOf(Coverage("RALLY_WINNER", 40, 30)),
    )

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
        show { ProfileScreen(state, "0.4.2", {}, {}, {}, {}) }
        compose.onNodeWithText(text(R.string.recordings_in_use)).performScrollTo().assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithContentDescription(text(R.string.recording_delete_description, used.name)).fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodesWithContentDescription(text(R.string.recording_delete_description, free.name)).fetchSemanticsNodes().size)
    }

    @Test fun theProfileSaysWhoSeesTheVideoHowLongTheServerKeepsItAndHowItIsDeleted() {
        show { ProfileScreen(AppUiState(), "0.4.2", {}, {}, {}, {}) }
        listOf(R.string.privacy_accounts, R.string.privacy_retention, R.string.privacy_delete, R.string.privacy_faces).forEach {
            compose.onNodeWithText(text(it)).performScrollTo().assertIsDisplayed()
        }
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
}
