package com.voleybolveveleybol.volleystat.ui

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.voleybolveveleybol.volleystat.AnalysisUi
import com.voleybolveveleybol.volleystat.CorrectionProblem
import com.voleybolveveleybol.volleystat.JobLoad
import com.voleybolveveleybol.volleystat.R
import com.voleybolveveleybol.volleystat.RalliesLoad
import com.voleybolveveleybol.volleystat.model.AnalysisJob
import com.voleybolveveleybol.volleystat.model.AnalysisSummary
import com.voleybolveveleybol.volleystat.model.LocalVideo
import com.voleybolveveleybol.volleystat.model.MatchRecord
import com.voleybolveveleybol.volleystat.model.MatchRules
import com.voleybolveveleybol.volleystat.model.Rally
import com.voleybolveveleybol.volleystat.model.RallyList
import com.voleybolveveleybol.volleystat.model.SetPoint
import com.voleybolveveleybol.volleystat.model.SetScore
import com.voleybolveveleybol.volleystat.model.VideoCheck
import com.voleybolveveleybol.volleystat.model.VideoSource
import com.voleybolveveleybol.volleystat.ui.screens.RalliesScreen
import com.voleybolveveleybol.volleystat.ui.theme.VolleyStatTheme
import kotlin.coroutines.coroutineContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.voleybolveveleybol.volleystat.part

/**
 * The rally list on a phone whose animations are removed. Compose scrolls on another path there:
 * it jumps the whole distance in one frame and looks once more. The other list tests run with
 * animations on, so this class runs the scroll of the confirmation line with the scale at zero.
 * The list and the match are made up.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h568dp-xhdpi")
class RallyListWithoutAnimationsTest {
    @get:Rule val compose = createComposeRule(effectContext = object : MotionDurationScale { override val scaleFactor = 0f })

    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun text(id: Int, vararg arguments: Any) = context.getString(id, *arguments)
    private fun place(label: String) = compose.onNodeWithText(label).getUnclippedBoundsInRoot()

    private val video = LocalVideo("/data/kayit.mp4", VideoSource.CAMERA, "kayit.mp4", 1_000, "video/mp4", 60_000, 1920, 1080, null)
    private val match = MatchRecord("m1", "A Takımı", "B Takımı", 1L, video, check = VideoCheck("v1", VideoCheck.READY, null, 1920, 1080, 30.0, 60_000, "h264", emptyList()))
    private val job = AnalysisJob("j1", "v1", AnalysisSummary.NEEDS_REVIEW, null, null, null, "motion-baseline", "BASELINE", false, null, 4, 1, 4, rallyListVersion = 7)
    private fun rally(sequence: Int, startMs: Long, endMs: Long, status: String, winner: String? = null, after: SetPoint? = null) =
        Rally("r$sequence", sequence, startMs, endMs, true, status, winner, after)

    /** Every rally has an answer, and the rule has a long name: on this phone the bottom edge cuts the button. */
    private val list = RallyList(
        listOf(
            rally(1, 2_000, 9_000, Rally.VERIFIED, "HOME", SetPoint(1, 21, 18)),
            rally(2, 15_000, 24_000, Rally.REJECTED),
            rally(3, 30_000, 41_000, Rally.VERIFIED, "AWAY", SetPoint(2, 2, 1)),
            rally(4, 48_000, 56_000, Rally.VERIFIED, "HOME", SetPoint(2, 3, 1)),
        ),
        version = 7, confirmed = false, videoDurationMs = 60_000, matches = listOf(part(SetScore(1, 21, 18, true), SetScore(2, 3, 1, false))),
        rules = MatchRules(2, 21, 15),
    )

    @Test fun withAnimationsRemovedTheListMovesAsFarAsTheLineNeedsAndNoFurther() {
        var correcting by mutableStateOf(false)
        var problem by mutableStateOf<CorrectionProblem?>(null)
        var scale: Float? = null
        compose.setContent {
            VolleyStatTheme(dark = false) {
                LaunchedEffect(Unit) { scale = coroutineContext[MotionDurationScale]?.scaleFactor }
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                    RalliesScreen(
                        match, AnalysisUi("m1", JobLoad.READY, job, rallies = list, ralliesLoad = RalliesLoad.READY, correctionProblem = problem, correcting = correcting),
                        onOpenRally = {}, onOpenGap = {}, onConfirm = {}, onOpenReview = {}, onRetry = {}, onBack = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        assertEquals("the screen runs with animations removed", 0f, scale)
        val screen = compose.onRoot().getUnclippedBoundsInRoot()
        val listTop = compose.onNode(hasScrollToNodeAction()).getUnclippedBoundsInRoot().top
        val button = text(R.string.action_check_score)
        val ruleCard = hasClickAction() and hasText(text(R.string.rule_card_change), substring = true)
        assertTrue("the test starts where the bottom edge cuts the button", place(button).bottom > screen.bottom)

        fun settled(line: String) {
            assertTrue("the whole line is on the screen", place(line).top >= listTop)
            assertEquals("the button ends at the bottom edge", screen.bottom.value, place(button).bottom.value, 1f)
            assertTrue("the rule card above the line stays whole", compose.onNode(ruleCard).getUnclippedBoundsInRoot().top >= listTop)
        }
        correcting = true
        compose.waitForIdle()
        settled(text(R.string.rallies_confirm_sending))

        correcting = false
        problem = CorrectionProblem.STALE
        compose.waitForIdle()
        settled(text(R.string.rallies_confirm_stale))
    }
}
