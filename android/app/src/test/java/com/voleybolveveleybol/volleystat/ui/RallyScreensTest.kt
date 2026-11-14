package com.voleybolveveleybol.volleystat.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
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

    /** The list builds only the rows on screen, so a row further down has to be scrolled to before it exists. */
    private fun reach(text: String) = compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text, substring = true))

    // ----- the rally list -----

    @Test fun theListShowsEveryRallyWithItsOutcomeAndEveryPauseBetweenThem() {
        val taps = list(state(mixed))

        compose.onNodeWithText(text(R.string.rallies_count, 4)).assertIsDisplayed()
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
        assertFalse("not knowing is no reason to search the list", shown(text(R.string.rallies_no_hint)))

        compose.onNodeWithText(check).performClick()
        compose.onNodeWithText(text(R.string.score_check_no)).performClick()
        compose.onNodeWithText(text(R.string.rallies_no_hint)).assertIsDisplayed()
        assertEquals(0, taps.confirms)

        compose.onNodeWithText(check).performClick()
        compose.onNodeWithText(text(R.string.score_check_yes)).performClick()
        assertEquals(1, taps.confirms)
        assertFalse(shown(text(R.string.rallies_no_hint)))
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
    }

    @Test fun aConfirmedListSaysSoAndNamesWhatTakesTheConfirmationAway() {
        list(state(decided.copy(confirmed = true)))
        compose.onNodeWithText(text(R.string.rallies_confirmed)).assertIsDisplayed()
        assertFalse(shown(text(R.string.action_check_score)))
    }

    @Test fun aListThatCouldNotBeReadSaysSoAndCanBeAskedForAgain() {
        val taps = list(state(null, load = RalliesLoad.UNREACHABLE))
        compose.onNodeWithText(text(R.string.rallies_load_failed)).assertIsDisplayed()
        assertFalse(shown(text(R.string.rallies_loading)))
        compose.onNodeWithText(text(R.string.action_retry)).performClick()
        assertEquals(1, taps.retries)
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

    // ----- the range editor -----

    private val four = RallyList(listOf(rally(1, 2_000, 9_000), rally(2, 15_000, 24_000), rally(3, 30_000, 41_000), rally(4, 48_000, 56_000)), 7, false, 60_000)

    private class Marks {
        val moves = mutableListOf<Pair<Long, Long>>()
        val splits = mutableListOf<Long>()
        val adds = mutableListOf<Pair<Long, Long>>()
        var merges = 0
    }

    private fun editor(target: EditTarget, control: FakeControl, analysis: AnalysisUi = state(four)): Marks {
        val marks = Marks()
        show {
            RallyEditorScreen(
                target, analysis, control.video, onMove = { start, end -> marks.moves += start to end }, onSplit = { marks.splits += it },
                onMerge = { marks.merges++ }, onAdd = { start, end -> marks.adds += start to end }, onBack = {},
            )
        }
        return marks
    }

    private fun button(id: Int) = compose.onNodeWithText(text(id))

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

        compose.onNodeWithText(text(R.string.editor_fast)).performClick()
        assertTrue(control.fast)
    }

    @Test fun mergingAsksFirstAndNamesTheRallyThatLosesItsAnswer() {
        val marks = editor(rallyTarget(four, "r2")!!, FakeControl(15_000))

        button(R.string.action_merge_previous).performClick()
        compose.onNodeWithText(text(R.string.merge_body, 1, 2)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.action_give_up)).performClick()
        assertEquals(0, marks.merges)

        button(R.string.action_merge_previous).performClick()
        compose.onNodeWithText(text(R.string.merge_confirm)).performClick()
        assertEquals(1, marks.merges)
        assertFalse(shown(text(R.string.merge_title)))
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

    @Test fun theEditorWaitsForTheListAndSaysWhenItDidNotCome() {
        var retries = 0
        show { RallyEditorWaiting(text(R.string.editor_title_rally), RalliesLoad.UNREACHABLE, onRetry = { retries++ }, onBack = {}) }
        compose.onNodeWithText(text(R.string.rallies_load_failed)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.action_retry)).performClick()
        assertEquals(1, retries)
    }
}
