package com.voleybolveveleybol.volleystat.ui

import android.content.Context
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.voleybolveveleybol.volleystat.AnalysisUi
import com.voleybolveveleybol.volleystat.CorrectionProblem
import com.voleybolveveleybol.volleystat.JobLoad
import com.voleybolveveleybol.volleystat.R
import com.voleybolveveleybol.volleystat.RalliesLoad
import com.voleybolveveleybol.volleystat.model.AnalysisJob
import com.voleybolveveleybol.volleystat.model.AnalysisSummary
import com.voleybolveveleybol.volleystat.model.EditTarget
import com.voleybolveveleybol.volleystat.model.LocalVideo
import com.voleybolveveleybol.volleystat.model.MatchRecord
import com.voleybolveveleybol.volleystat.model.Rally
import com.voleybolveveleybol.volleystat.model.RallyList
import com.voleybolveveleybol.volleystat.model.SetPoint
import com.voleybolveveleybol.volleystat.model.SetScore
import com.voleybolveveleybol.volleystat.model.VideoCheck
import com.voleybolveveleybol.volleystat.model.VideoSource
import com.voleybolveveleybol.volleystat.model.gapTarget
import com.voleybolveveleybol.volleystat.model.rallyTarget
import com.voleybolveveleybol.volleystat.ui.screens.RalliesScreen
import com.voleybolveveleybol.volleystat.ui.screens.RallyEditorScreen
import com.voleybolveveleybol.volleystat.ui.screens.RallyEditorWaiting
import com.voleybolveveleybol.volleystat.ui.theme.VolleyStatTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The rally list and the range editor, one state at a time. A stand-in holds the place of the
 * video player. All names, times and scores are invented.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class RallyScreensTest {
    @get:Rule val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun text(id: Int, vararg arguments: Any) = context.getString(id, *arguments)
    private fun shown(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun show(content: @Composable () -> Unit) = compose.setContent { VolleyStatTheme(dark = false) { content() } }

    private val video = LocalVideo("/data/kayit.mp4", VideoSource.CAMERA, "kayit.mp4", 1_000, "video/mp4", 60_000, 1920, 1080, null)
    private val match = MatchRecord("m1", "A Takımı", "B Takımı", 1L, video, check = VideoCheck("v1", VideoCheck.READY, null, 1920, 1080, 30.0, 60_000, "h264", emptyList()))
    private fun job(mock: Boolean = false) = AnalysisJob("j1", "v1", AnalysisSummary.NEEDS_REVIEW, null, null, null, "motion-baseline", if (mock) "MOCK" else "BASELINE", mock, null, 4, 1, 4, rallyListVersion = 7)

    private fun rally(sequence: Int, startMs: Long, endMs: Long, status: String = Rally.PROPOSED, winner: String? = null, after: SetPoint? = null, fromAnalyzer: Boolean = true) =
        Rally("r$sequence", sequence, startMs, endMs, fromAnalyzer, status, winner, after)

    /** Rally 1 to the home side, rally 2 no rally, rally 3 added by the user for the away side, rally 4 undecided. */
    private val mixed = RallyList(
        listOf(
            rally(1, 2_000, 9_000, Rally.VERIFIED, "HOME", SetPoint(1, 1, 0)),
            rally(2, 15_000, 24_000, Rally.REJECTED),
            rally(3, 30_000, 41_000, Rally.VERIFIED, "AWAY", SetPoint(1, 1, 1), fromAnalyzer = false),
            rally(4, 48_000, 56_000),
        ),
        version = 7, confirmed = false, videoDurationMs = 60_000, sets = listOf(SetScore(1, 1, 1, false)),
    )

    /** Every rally has an answer. The second set stands at 3 - 1 and is not over. */
    private val decided = RallyList(
        listOf(
            rally(1, 2_000, 9_000, Rally.VERIFIED, "HOME", SetPoint(1, 25, 21)),
            rally(2, 15_000, 24_000, Rally.REJECTED),
            rally(3, 30_000, 41_000, Rally.VERIFIED, "AWAY", SetPoint(2, 2, 1)),
            rally(4, 48_000, 56_000, Rally.VERIFIED, "HOME", SetPoint(2, 3, 1)),
        ),
        version = 7, confirmed = false, videoDurationMs = 60_000, sets = listOf(SetScore(1, 25, 21, true), SetScore(2, 3, 1, false)),
    )

    private fun state(list: RallyList?, mock: Boolean = false, load: RalliesLoad = RalliesLoad.READY, problem: CorrectionProblem? = null, overlaps: Int? = null, correcting: Boolean = false) =
        AnalysisUi("m1", JobLoad.READY, job(mock), rallies = list, ralliesLoad = load, correctionProblem = problem, overlapsRally = overlaps, correcting = correcting)

    private class ListTaps {
        val rallies = mutableListOf<String>()
        val gaps = mutableListOf<Long>()
        val reviews = mutableListOf<String>()
        var confirms = 0
        var retries = 0
    }

    private fun list(analysis: AnalysisUi): ListTaps {
        val taps = ListTaps()
        show {
            RalliesScreen(
                match, analysis, onOpenRally = { taps.rallies += it.id }, onOpenGap = { taps.gaps += it }, onConfirm = { taps.confirms++ },
                onOpenReview = { taps.reviews += it.id }, onRetry = { taps.retries++ }, onBack = {},
            )
        }
        return taps
    }

    /** The hint as the screen shows it: with the labels of the button and of the answer it sends the user to. */
    private val hint get() = text(R.string.rallies_no_hint, text(R.string.action_choose_winner), text(R.string.review_not_a_rally))

    /** The list builds only the rows on screen, so a row further down has to be scrolled to before it exists. */
    private fun reach(text: String) = compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text, substring = true))

    // ----- the rally list -----

    @Test fun theListShowsEveryRallyWithItsOutcomeAndEveryPauseBetweenThem() {
        val taps = list(state(mixed))

        // Three rallies and one interval the user called "not a rally": the head does not count that one as a rally.
        compose.onNodeWithText(text(R.string.rallies_count_rejected, 3, 1)).assertIsDisplayed()
        // The scores in the rows are counts of an unconfirmed list, and the list says what that makes them.
        compose.onNodeWithText(text(R.string.rallies_scores_lower)).assertIsDisplayed()
        listOf(
            text(R.string.rallies_gap, "2 sn"), text(R.string.rallies_set_header, 1), "A Takımı", "1 - 0",
            text(R.string.rally_rejected), "B Takımı · ${text(R.string.rally_user_made)}", "1 - 1",
            // The count stops at the first rally without a winner, and the list says where.
            text(R.string.rallies_no_set), text(R.string.rally_open), text(R.string.rallies_gap, "4 sn"),
        ).forEach { reach(it); assertTrue("\"$it\" is on the list", shown(it)) }
        assertEquals("the pauses before, between and after the rallies", 2, compose.onAllNodesWithText(text(R.string.rallies_gap, "6 sn")).fetchSemanticsNodes().size)

        reach("00:30 · 11 sn")
        compose.onNodeWithText("00:30 · 11 sn").performClick()
        reach(text(R.string.rallies_gap, "7 sn"))
        compose.onNodeWithText(text(R.string.rallies_gap, "7 sn")).performClick()
        assertEquals(listOf("r3"), taps.rallies)
        assertEquals("the pause is named by the moment it starts at", listOf(41_000L), taps.gaps)
    }

    @Test fun aRallyWithoutAWinnerHoldsBackTheScoreCheckAndTheButtonOpensItsQuestion() {
        val waiting = mixed.copy(rallies = mixed.rallies.map { if (it.sequence == 2) it.copy(status = Rally.UNRESOLVED) else it })
        val taps = list(state(waiting))

        // "Not sure" is no winner either: the unsure rally and the open one both wait.
        compose.onNodeWithText(text(R.string.rallies_not_ready, 2)).assertIsDisplayed()
        assertFalse(shown(text(R.string.action_check_score)))
        compose.onNodeWithText(text(R.string.action_go_review)).performClick()
        assertEquals("the first rally that waits, in match order", listOf("r2"), taps.reviews)
    }

    @Test fun onlyTheAnswerYesConfirmsTheList() {
        val taps = list(state(decided))
        val check = text(R.string.action_check_score)

        compose.onNodeWithText(check).performClick()
        compose.onNodeWithText(text(R.string.score_check_set, 1, 25, 21)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.score_check_set_open, 2, 3, 1)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.score_check_rule)).assertIsDisplayed()
        // A dialog is its own window, so the screenshot run can not reach it; this run keeps its picture.
        compose.onNode(isDialog()).captureRoboImage("build/outputs/roborazzi/11_ralli_listesi_skor_denetimi.png")
        compose.onNodeWithText(text(R.string.score_check_unknown)).performClick()
        assertFalse(shown(text(R.string.score_check_question)))
        assertFalse("not knowing is no reason to search the list", shown(hint))

        compose.onNodeWithText(check).performClick()
        compose.onNodeWithText(text(R.string.score_check_no)).performClick()
        compose.onNodeWithText(hint).assertIsDisplayed()
        assertEquals(0, taps.confirms)

        compose.onNodeWithText(check).performClick()
        compose.onNodeWithText(text(R.string.score_check_yes)).performClick()
        assertEquals(1, taps.confirms)
        assertFalse(shown(hint))
    }

    @Test fun aListWithoutACountedRallyHasNoScoreToCheck() {
        val rejected = RallyList(listOf(rally(1, 2_000, 9_000, Rally.REJECTED)), 7, false, 60_000)
        list(state(rejected))
        compose.onNodeWithText(text(R.string.action_check_score)).assertIsNotEnabled()
    }

    @Test fun theListOfADemoAnalysisCanNotBeConfirmed() {
        list(state(decided, mock = true))
        compose.onNodeWithText(text(R.string.rallies_demo)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.demo_title), substring = true).assertIsDisplayed()
        assertFalse(shown(text(R.string.action_check_score)))
        // The list of a demo is never confirmed, so the screen must not say that a confirmation ends the lower bounds.
        assertFalse(shown(text(R.string.rallies_scores_lower)))
    }

    @Test fun aConfirmedListSaysSoAndNamesWhatTakesTheConfirmationAway() {
        list(state(decided.copy(confirmed = true)))
        compose.onNodeWithText(text(R.string.rallies_confirmed)).assertIsDisplayed()
        assertFalse(shown(text(R.string.action_check_score)))
        assertFalse("a confirmed list has no lower bounds to warn about", shown(text(R.string.rallies_scores_lower)))
    }

    @Test fun aListWithoutAnyScoreHasNoScoreToCallALowerBound() {
        list(state(four))
        compose.onNodeWithText(text(R.string.rallies_count, 4)).assertIsDisplayed()
        assertFalse(shown(text(R.string.rallies_scores_lower)))
    }

    @Test fun aServerThatAnswersInAnOlderFormIsNamedAndNotAskedAgain() {
        list(state(null, load = RalliesLoad.OUTDATED))
        compose.onNodeWithText(text(R.string.rallies_outdated_server)).assertIsDisplayed()
        // The same question would get the same answer, so the screen offers no retry and does not keep "loading".
        assertFalse(shown(text(R.string.action_retry)))
        assertFalse(shown(text(R.string.rallies_loading)))
        assertFalse(shown(text(R.string.rallies_load_failed)))
    }

    @Test fun aListThatCouldNotBeReadSaysSoAndCanBeAskedForAgain() {
        val taps = list(state(null, load = RalliesLoad.UNREACHABLE))
        compose.onNodeWithText(text(R.string.rallies_load_failed)).assertIsDisplayed()
        assertFalse(shown(text(R.string.rallies_loading)))
        compose.onNodeWithText(text(R.string.action_retry)).performClick()
        assertEquals(1, taps.retries)
    }

    @Test fun withoutAJobTheListNamesTheReasonAndOffersARetryOnlyWhereOneCanHelp() {
        var load by mutableStateOf(JobLoad.VIDEO_MISSING)
        var retries = 0
        show { RalliesScreen(match, AnalysisUi("m1", load), onOpenRally = {}, onOpenGap = {}, onConfirm = {}, onOpenReview = {}, onRetry = { retries++ }, onBack = {}) }
        compose.onNodeWithText(text(R.string.analysis_video_missing_body)).assertIsDisplayed()
        // Asking again would get the same answer, so there is nothing to tap, and the app did reach the server.
        assertFalse(shown(text(R.string.action_retry)))
        assertFalse(shown(text(R.string.rallies_load_failed)))
        assertFalse("no list is on its way", shown(text(R.string.rallies_loading)))

        load = JobLoad.NO_JOB
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.review_no_job)).assertIsDisplayed()
        assertFalse(shown(text(R.string.action_retry)))

        load = JobLoad.UNREACHABLE
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.analysis_unreachable)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.action_retry)).performClick()
        assertEquals(1, retries)
    }

    @Test fun aListThatIsStillOnItsWaySaysSo() {
        list(state(null, load = RalliesLoad.LOADING))
        compose.onNodeWithText(text(R.string.rallies_loading)).assertIsDisplayed()
        assertFalse(shown(text(R.string.rallies_load_failed)))
    }

    @Test fun aRefusedConfirmationNamesItsCauseOnTheList() {
        list(state(decided, problem = CorrectionProblem.STALE))
        compose.onNodeWithText(text(R.string.correction_stale)).assertIsDisplayed()
    }

    @Test fun theListStaysOpenUntilTheAnswerOfTheConfirmationIsThere() {
        var correcting by mutableStateOf(true)
        val leaves = leavesBy { RalliesScreen(match, state(decided, correcting = correcting), onOpenRally = {}, onOpenGap = {}, onConfirm = {}, onOpenReview = {}, onRetry = {}, onBack = {}) }
        compose.onNodeWithText(text(R.string.rallies_confirm_sending)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.action_back)).assertIsNotEnabled()
        pressBack()
        assertEquals("a refusal of the confirmation would be shown to nobody", 0, leaves())

        // With the answer on screen the user may leave.
        correcting = false
        compose.waitForIdle()
        assertFalse(shown(text(R.string.rallies_confirm_sending)))
        compose.onNodeWithContentDescription(text(R.string.action_back)).assertIsEnabled()
        pressBack()
        assertEquals(1, leaves())
    }

    // ----- the range editor -----

    private val four = RallyList(listOf(rally(1, 2_000, 9_000), rally(2, 15_000, 24_000), rally(3, 30_000, 41_000), rally(4, 48_000, 56_000)), 7, false, 60_000)

    private class Marks {
        val moves = mutableListOf<Pair<Long, Long>>()
        val splits = mutableListOf<Long>()
        val adds = mutableListOf<Pair<Long, Long>>()
        var merges = 0
    }

    private fun editor(target: EditTarget, control: FakeControl, analysis: AnalysisUi = state(four), onChooseWinner: (() -> Unit)? = null): Marks {
        val marks = Marks()
        show {
            RallyEditorScreen(
                target, analysis, control.video, onMove = { start, end -> marks.moves += start to end }, onSplit = { marks.splits += it },
                onMerge = { marks.merges++ }, onAdd = { start, end -> marks.adds += start to end }, onBack = {}, onChooseWinner = onChooseWinner,
            )
        }
        return marks
    }

    private fun button(id: Int) = compose.onNodeWithText(text(id))

    private var backDispatcher: OnBackPressedDispatcher? = null

    /**
     * Shows a screen above a stand-in for the app's navigation, and returns how often a back press got through
     * to that navigation. A screen that keeps the user holds the press back before it gets there.
     */
    private fun leavesBy(screen: @Composable () -> Unit): () -> Int {
        var leaves = 0
        show {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            BackHandler { leaves++ }
            screen()
        }
        return { leaves }
    }

    private fun pressBack() {
        compose.runOnUiThread { backDispatcher!!.onBackPressed() }
        compose.waitForIdle()
    }

    /** Puts the picture somewhere, as playback or a drag would. */
    private fun FakeControl.standAt(positionMs: Long) {
        this.positionMs = positionMs
        compose.waitForIdle()
    }

    @Test fun theMarksOfARallyAreOfferedOnlyWhereTheyMakeARally() {
        // Rally 2 runs from 15,0 to 24,0 seconds; the pauses around it reach from 9,0 to 30,0.
        val control = FakeControl(15_000)
        val marks = editor(rallyTarget(four, "r2")!!, control)
        compose.onNodeWithText(text(R.string.editor_rally_now, 2, "00:15,0", "00:24,0")).assertIsDisplayed()
        // The user reads what a moved end costs before moving it.
        compose.onNodeWithText(text(R.string.editor_end_note)).assertIsDisplayed()

        // At its own start nothing would change, and nothing can be cut off.
        listOf(R.string.action_end_here, R.string.action_start_here, R.string.action_split_here).forEach { button(it).assertIsNotEnabled() }

        control.standAt(20_400)
        compose.onNodeWithText(text(R.string.editor_position, "00:20,4")).assertIsDisplayed()
        button(R.string.action_end_here).performClick()
        button(R.string.action_start_here).performClick()
        button(R.string.action_split_here).performClick()
        assertEquals(listOf(15_000L to 20_400L, 20_400L to 24_000L), marks.moves)
        assertEquals(listOf(20_400L), marks.splits)

        // Past its end the rally can only grow. Before its start it can only start earlier.
        control.standAt(27_000)
        button(R.string.action_start_here).assertIsNotEnabled()
        button(R.string.action_split_here).assertIsNotEnabled()
        button(R.string.action_end_here).performClick()
        control.standAt(12_000)
        button(R.string.action_end_here).assertIsNotEnabled()
        button(R.string.action_split_here).assertIsNotEnabled()
        button(R.string.action_start_here).performClick()
        assertEquals(listOf(15_000L to 27_000L, 12_000L to 24_000L), marks.moves.drop(2))

        control.standAt(24_000)
        listOf(R.string.action_end_here, R.string.action_start_here, R.string.action_split_here).forEach { button(it).assertIsNotEnabled() }
    }

    @Test fun aMarkStopsThePictureWhereItWasMade() {
        val control = FakeControl(20_000).apply { playing = true }
        editor(rallyTarget(four, "r2")!!, control)
        button(R.string.action_end_here).performClick()
        assertFalse(control.playing)
    }

    @Test fun theStepButtonsMoveThePictureAndStopAtTheEdgesOfTheWindow() {
        val control = FakeControl(20_000).apply { playing = true }
        editor(rallyTarget(four, "r2")!!, control)

        button(R.string.editor_forward_1).performClick()
        button(R.string.editor_back_02).performClick()
        assertEquals(listOf(21_000L to true, 20_800L to true), control.seeks)
        assertFalse("a step leaves the picture standing", control.playing)

        control.standAt(29_500)
        button(R.string.editor_forward_1).performClick()
        assertEquals("the next rally starts at 30,0", 30_000L, control.positionMs)
        button(R.string.editor_forward_1).assertIsNotEnabled()
        button(R.string.editor_forward_02).assertIsNotEnabled()

        control.standAt(9_100)
        button(R.string.editor_back_1).performClick()
        assertEquals("the rally before ends at 9,0", 9_000L, control.positionMs)
        button(R.string.editor_back_1).assertIsNotEnabled()
    }

    @Test fun draggingTheBarShowsKeyFramesAndLettingGoShowsTheExactFrame() {
        val control = FakeControl(15_000).apply { playing = true }
        editor(rallyTarget(four, "r2")!!, control)

        // The bar counts from the start of the window, which is at 9,0 seconds.
        compose.onNodeWithContentDescription(text(R.string.editor_seek)).performSemanticsAction(SemanticsActions.SetProgress) { it(5_000f) }
        compose.waitForIdle()

        assertEquals(listOf(14_000L to false, 14_000L to true), control.seeks)
        assertFalse(control.playing)
        compose.onNodeWithText(text(R.string.editor_position, "00:14,0")).assertIsDisplayed()
    }

    @Test fun playbackStartsOverWhenThePictureStandsAtTheEndOfTheWindow() {
        val control = FakeControl(30_000)
        editor(rallyTarget(four, "r2")!!, control)

        compose.onNodeWithContentDescription(text(R.string.editor_play)).performClick()
        assertEquals("from the start of the rally", listOf(15_000L to true), control.seeks)
        assertTrue(control.playing)
        compose.onNodeWithContentDescription(text(R.string.editor_pause)).performClick()
        assertFalse(control.playing)

        // The chip changes what the screen holds; the player takes it over on the next frame.
        compose.onNodeWithText(text(R.string.editor_fast)).performClick()
        compose.waitForIdle()
        assertTrue(control.fast)
    }

    @Test fun mergingAsksFirstAndNamesNoAnswerWhereThereIsNone() {
        val marks = editor(rallyTarget(four, "r2")!!, FakeControl(15_000))

        button(R.string.action_merge_previous).performClick()
        // Neither rally has an answer, so the dialog speaks of none.
        compose.onNodeWithText(text(R.string.merge_body, 1, 2)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.action_give_up)).performClick()
        assertEquals(0, marks.merges)

        button(R.string.action_merge_previous).performClick()
        compose.onNodeWithText(text(R.string.merge_confirm)).performClick()
        assertEquals(1, marks.merges)
        assertFalse(shown(text(R.string.merge_title)))
    }

    @Test fun theMergeDialogNamesEveryAnswerTheMergeTakesAway() {
        // Rally 1 has a winner, and the user called rally 2 "not a rally".
        val answered = four.copy(rallies = four.rallies.map {
            when (it.sequence) {
                1 -> it.copy(status = Rally.VERIFIED, winner = "HOME")
                2 -> it.copy(status = Rally.REJECTED)
                else -> it
            }
        })
        editor(rallyTarget(answered, "r2")!!, FakeControl(15_000), state(answered))
        button(R.string.action_merge_previous).performClick()
        val body = listOf(text(R.string.merge_body, 1, 2), text(R.string.merge_body_previous_answer, 1), text(R.string.merge_body_reopens, 2)).joinToString(" ")
        compose.onNodeWithText(body).assertIsDisplayed()
    }

    @Test fun playbackStopsWhereTheWindowEnds() {
        val control = FakeControl(29_000).apply { playing = true }
        editor(rallyTarget(four, "r2")!!, control)

        // The next rally starts at 30,0. The player is looked at every 50 ms, so it is a little past that when the screen sees it.
        control.standAt(30_040)
        assertFalse(control.playing)
        assertEquals(listOf(30_000L to true), control.seeks)
        compose.onNodeWithText(text(R.string.editor_position, "00:30,0")).assertIsDisplayed()
    }

    @Test fun aVideoThatPlayedToItsOwnEndStopsAndStartsOverOnPlay() {
        // The last rally: its window ends where the server says the video ends, at 60,0.
        val control = FakeControl(59_000).apply { playing = true }
        editor(rallyTarget(four, "r4")!!, control)

        // The player reaches the end of the file a little before that.
        control.positionMs = 59_996
        control.ended = true
        compose.waitForIdle()
        assertFalse(control.playing)

        compose.onNodeWithContentDescription(text(R.string.editor_play)).performClick()
        assertEquals("from the start of the rally, although the position is not at the end of the window", listOf(48_000L to true), control.seeks)
        assertTrue(control.playing)
    }

    @Test fun aMarkIsWhereThePlayerStandsAtTheTapAndNotWhereItStoodAtTheLastLook() {
        val control = FakeControl(20_000).apply { playing = true }
        val marks = editor(rallyTarget(four, "r2")!!, control)
        control.movedOnMs = 80
        button(R.string.action_end_here).performClick()
        assertEquals(listOf(15_000L to 20_080L), marks.moves)
    }

    @Test fun aVideoThatFailedCanBeAskedForAgain() {
        val control = FakeControl(20_000).apply { failed = true }
        editor(rallyTarget(four, "r2")!!, control)
        button(R.string.action_end_here).assertIsNotEnabled()

        compose.onNodeWithText(text(R.string.action_retry)).performClick()
        assertEquals(1, control.retries)
        assertFalse(shown(text(R.string.editor_video_failed)))
        // The video is on its way again. Until its picture is there, a mark would name a moment nobody saw.
        button(R.string.action_end_here).assertIsNotEnabled()
        control.ready = true
        compose.waitForIdle()
        button(R.string.action_end_here).assertIsEnabled()
    }

    @Test fun nothingIsMarkedBeforeThePictureOfThePositionIsOnScreen() {
        // The player is still fetching the frame of 20,4: after a step, a drag, or when the editor has just opened.
        val control = FakeControl(20_400).apply { ready = false }
        val marks = editor(rallyTarget(four, "r2")!!, control)
        listOf(R.string.action_end_here, R.string.action_start_here, R.string.action_split_here).forEach { button(it).assertIsNotEnabled() }
        // Moving on is allowed: the user is looking for a picture, not marking one.
        button(R.string.editor_forward_1).assertIsEnabled()
        compose.onNodeWithContentDescription(text(R.string.editor_play)).assertIsEnabled()

        control.ready = true
        compose.waitForIdle()
        button(R.string.action_end_here).performClick()
        assertEquals(listOf(15_000L to 20_400L), marks.moves)
    }

    @Test fun aPictureThatTakesLongToArriveGetsASignAndAShortWaitGetsNone() {
        val control = FakeControl(20_400).apply { ready = false }
        editor(rallyTarget(four, "r2")!!, control)
        val sign = text(R.string.editor_video_loading)
        // Every step and every drag makes the player fetch a frame. A sign for that would flash all the time.
        assertTrue(compose.onAllNodesWithContentDescription(sign).fetchSemanticsNodes().isEmpty())

        // After a moment without a picture the user is told why the marks wait.
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithContentDescription(sign).assertIsDisplayed()

        control.ready = true
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithContentDescription(sign).fetchSemanticsNodes().isEmpty())
    }

    @Test fun aRefusalMakesWayWhenTheUserMovesThePictureAgain() {
        var seen = 0
        val control = FakeControl(20_000)
        show {
            RallyEditorScreen(
                rallyTarget(four, "r2")!!, state(four, problem = CorrectionProblem.OVERLAP, overlaps = 3), control.video,
                { _, _ -> }, {}, {}, { _, _ -> }, onBack = {}, onProblemSeen = { seen++ },
            )
        }
        compose.onNodeWithText(text(R.string.correction_overlap, 3)).assertIsDisplayed()
        assertEquals("reading it is not yet moving on", 0, seen)
        // Each way of moving the picture is the start of the next try: a step, a drag of the bar, playing on.
        button(R.string.editor_forward_1).performClick()
        assertEquals(1, seen)
        compose.onNodeWithContentDescription(text(R.string.editor_seek)).performSemanticsAction(SemanticsActions.SetProgress) { it(5_000f) }
        compose.waitForIdle()
        assertEquals(2, seen)
        compose.onNodeWithContentDescription(text(R.string.editor_play)).performClick()
        assertEquals(3, seen)
    }

    @Test fun theEditorOfADemoAnalysisCarriesTheLabelOnTheIntervalItShows() {
        var mock by mutableStateOf(true)
        show { RallyEditorScreen(rallyTarget(four, "r2")!!, state(four, mock = mock), FakeControl(15_000).video, { _, _ -> }, {}, {}, { _, _ -> }, {}) }
        compose.onNodeWithText(text(R.string.status_demo)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.editor_rally_now, 2, "00:15,0", "00:24,0")).assertIsDisplayed()
        // An interval a real analyzer proposed carries no such label.
        mock = false
        compose.waitForIdle()
        assertFalse(shown(text(R.string.status_demo)))
    }

    @Test fun aPauseIsNotMarkedBeforeItsPictureIsOnScreenEither() {
        val control = FakeControl(10_000).apply { ready = false }
        editor(gapTarget(four, 9_000)!!, control)
        button(R.string.action_rally_started).assertIsNotEnabled()
        control.ready = true
        compose.waitForIdle()
        button(R.string.action_rally_started).assertIsEnabled()
    }

    @Test fun aMarkTappedAsThePictureLeavesItsRangeIsNotSent() {
        // Rally 2 ends at 24,0. The screen last saw the picture just before that, so the buttons are still
        // offered, and the picture has run on since.
        val control = FakeControl(23_970).apply { playing = true }
        val marks = editor(rallyTarget(four, "r2")!!, control)
        control.movedOnMs = 100
        button(R.string.action_split_here).performClick()
        assertEquals("a cut at 24,07 lies outside the rally", emptyList<Long>(), marks.splits)
        assertFalse("the picture stands where the tap found it", control.playing)
        // The screen now shows where the picture stands, and offers what is possible there.
        compose.onNodeWithText(text(R.string.editor_position, "00:24,0")).assertIsDisplayed()
        button(R.string.action_split_here).assertIsNotEnabled()

        control.standAt(23_970)
        control.movedOnMs = 100
        button(R.string.action_start_here).performClick()
        assertEquals("a start after the end is no rally", emptyList<Pair<Long, Long>>(), marks.moves)

        // The picture reaches the end the rally already has: nothing would change, so nothing is sent.
        control.standAt(23_950)
        control.movedOnMs = 50
        button(R.string.action_end_here).performClick()
        assertEquals(emptyList<Pair<Long, Long>>(), marks.moves)
    }

    @Test fun aStartTappedAsThePictureReachesTheEndOfThePauseKeepsTheStartThatWasMarked() {
        // The pause between rally 1 and rally 2 runs from 9,0 to 15,0.
        val control = FakeControl(10_000)
        val marks = editor(gapTarget(four, 9_000)!!, control)
        button(R.string.action_rally_started).performClick()
        compose.onNodeWithText(text(R.string.editor_marked_start, "00:10,0")).assertIsDisplayed()

        // At the very end of the pause no rally can start. The start from before stays.
        control.standAt(14_980)
        control.movedOnMs = 100
        button(R.string.action_rally_started).performClick()
        compose.onNodeWithText(text(R.string.editor_marked_start, "00:10,0")).assertIsDisplayed()

        button(R.string.action_rally_ended).performClick()
        assertEquals(listOf(10_000L to 15_000L), marks.adds)
    }

    @Test fun playbackThatReachesTheEndOfTheWindowExactlyStopsThereAndIsNotMoved() {
        val control = FakeControl(29_000).apply { playing = true }
        editor(rallyTarget(four, "r2")!!, control)
        control.standAt(30_000)
        assertFalse(control.playing)
        assertEquals("the picture already stands at the end", emptyList<Pair<Long, Boolean>>(), control.seeks)
    }

    @Test fun theEditorStaysOpenUntilTheAnswerOfACorrectionIsThere() {
        val control = FakeControl(20_000)
        val leaves = leavesBy { RallyEditorScreen(rallyTarget(four, "r2")!!, state(four, correcting = true), control.video, { _, _ -> }, {}, {}, { _, _ -> }, onBack = {}) }
        // The user sees that something is on its way, and can not walk away from how it ends: a refusal
        // would otherwise be shown to nobody.
        compose.onNodeWithText(text(R.string.correction_sending)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.action_back)).assertIsNotEnabled()
        pressBack()
        assertEquals(0, leaves())
    }

    @Test fun theEditorLetsTheUserLeaveWhenNothingIsOnItsWay() {
        var arrow = 0
        val leaves = leavesBy { RallyEditorScreen(rallyTarget(four, "r2")!!, state(four), FakeControl(20_000).video, { _, _ -> }, {}, {}, { _, _ -> }, onBack = { arrow++ }) }
        assertFalse(shown(text(R.string.correction_sending)))
        compose.onNodeWithContentDescription(text(R.string.action_back)).performClick()
        assertEquals(1, arrow)
        pressBack()
        assertEquals(1, leaves())
    }

    @Test fun aPlayerThatIsBuiltAnewTakesOverTheSpeedTheUserChose() {
        val players = mutableListOf<FakeControl>()
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            VolleyStatTheme(dark = false) {
                // Each start of the screen builds its own player, as the app does after the theme or the text size changed.
                val control = remember { FakeControl(20_000).also { players += it } }
                RallyEditorScreen(rallyTarget(four, "r2")!!, state(four), control.video, { _, _ -> }, {}, {}, { _, _ -> }, {})
            }
        }
        compose.onNodeWithText(text(R.string.editor_fast)).performClick()
        compose.waitForIdle()
        assertTrue(players.single().fast)

        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertEquals(2, players.size)
        assertTrue("the chip still says 2x, so the new player has to run at 2x", players.last().fast)
    }

    @Test fun openedFromTheListTheEditorLeadsToTheQuestionOfItsRally() {
        var opened = 0
        editor(rallyTarget(four, "r2")!!, FakeControl(15_000), onChooseWinner = { opened++ })
        button(R.string.action_choose_winner).performClick()
        assertEquals(1, opened)
    }

    @Test fun openedFromTheQuestionTheEditorOffersNoWayBackToIt() {
        editor(rallyTarget(four, "r2")!!, FakeControl(15_000))
        assertFalse(shown(text(R.string.action_choose_winner)))
    }

    @Test fun aPauseHasNoWinnerToChoose() {
        editor(gapTarget(four, 9_000)!!, FakeControl(9_000), onChooseWinner = {})
        assertFalse(shown(text(R.string.action_choose_winner)))
        // A pause has no answers either, so nothing warns about losing them.
        assertFalse(shown(text(R.string.editor_end_note)))
    }

    @Test fun theFirstRallyHasNoneBeforeItToMergeWith() {
        editor(rallyTarget(four, "r1")!!, FakeControl(2_000))
        assertFalse(shown(text(R.string.action_merge_previous)))
    }

    @Test fun nothingIsMarkedOnAPictureThatDoesNotShow() {
        val control = FakeControl(20_000).apply { failed = true }
        editor(rallyTarget(four, "r2")!!, control)

        compose.onNodeWithText(text(R.string.editor_video_failed)).assertIsDisplayed()
        listOf(R.string.action_end_here, R.string.action_start_here, R.string.action_split_here, R.string.editor_forward_1, R.string.editor_back_02)
            .forEach { button(it).assertIsNotEnabled() }
        // Merging names no moment of the video.
        button(R.string.action_merge_previous).assertIsEnabled()
    }

    @Test fun nothingIsSentWhileACorrectionIsOnItsWay() {
        editor(rallyTarget(four, "r2")!!, FakeControl(20_000), state(four, correcting = true))
        listOf(R.string.action_end_here, R.string.action_start_here, R.string.action_split_here, R.string.action_merge_previous).forEach { button(it).assertIsNotEnabled() }
    }

    @Test fun theEditorNamesWhyTheServerRefusedACorrection() {
        editor(rallyTarget(four, "r2")!!, FakeControl(20_000), state(four, problem = CorrectionProblem.OVERLAP, overlaps = 3))
        compose.onNodeWithText(text(R.string.correction_overlap, 3)).assertIsDisplayed()
    }

    @Test fun aMissedRallyIsMarkedWithItsStartAndThenItsEnd() {
        // The pause between rally 1 and rally 2 reaches from 9,0 to 15,0 seconds.
        val control = FakeControl(9_000).apply { playing = true }
        val marks = editor(gapTarget(four, 9_000)!!, control)
        compose.onNodeWithText(text(R.string.editor_gap_now, "00:09,0", "00:15,0")).assertIsDisplayed()
        button(R.string.action_rally_ended).assertIsNotEnabled()

        control.standAt(10_200)
        button(R.string.action_rally_started).performClick()
        compose.onNodeWithText(text(R.string.editor_marked_start, "00:10,2")).assertIsDisplayed()
        assertTrue("the video keeps running, so the end can be marked as the rally ends", control.playing)
        button(R.string.action_rally_ended).assertIsNotEnabled()

        control.standAt(11_000)
        button(R.string.action_rally_started).performClick()
        compose.onNodeWithText(text(R.string.editor_marked_start, "00:11,0")).assertIsDisplayed()
        assertTrue("marking the start again does not stop the video either", control.playing)
        // The marked start lives inside the screen, so the screenshot run can not set it up; this run keeps its picture.
        control.standAt(12_600)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/12_ralli_ekle_baslangic_isaretli.png")

        control.standAt(13_400)
        button(R.string.action_rally_ended).performClick()
        assertEquals(listOf(11_000L to 13_400L), marks.adds)
        assertFalse(control.playing)
        assertTrue(marks.moves.isEmpty() && marks.splits.isEmpty() && marks.merges == 0)
    }

    @Test fun aRallyCanNotStartAtTheVeryEndOfThePause() {
        editor(gapTarget(four, 9_000)!!, FakeControl(15_000))
        button(R.string.action_rally_started).assertIsNotEnabled()
    }

    @Test fun withoutAJobTheEditorNamesTheReasonToo() {
        show { RallyEditorWaiting(text(R.string.editor_title_rally), AnalysisUi("m1", JobLoad.VIDEO_MISSING), onRetry = {}, onBack = {}) }
        compose.onNodeWithText(text(R.string.analysis_video_missing_body)).assertIsDisplayed()
        assertFalse(shown(text(R.string.action_retry)))
        assertFalse(shown(text(R.string.rallies_loading)))
    }

    @Test fun theEditorWaitsForTheListAndSaysWhenItDidNotCome() {
        var retries = 0
        show { RallyEditorWaiting(text(R.string.editor_title_rally), state(null, load = RalliesLoad.UNREACHABLE), onRetry = { retries++ }, onBack = {}) }
        compose.onNodeWithText(text(R.string.rallies_load_failed)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.action_retry)).performClick()
        assertEquals(1, retries)
    }
}
