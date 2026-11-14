package com.voleybolveveleybol.volleystat

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.voleybolveveleybol.volleystat.data.ApiClient
import com.voleybolveveleybol.volleystat.data.LocalStore
import com.voleybolveveleybol.volleystat.model.AnalysisSummary
import com.voleybolveveleybol.volleystat.model.VideoCheck
import com.voleybolveveleybol.volleystat.model.rallyTarget
import com.voleybolveveleybol.volleystat.ui.formatClock
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import java.io.File

private const val HOME_TEAM = "Cihaz Ev"
private const val AWAY_TEAM = "Cihaz Misafir"

/**
 * The whole journey on a device, against a backend on the development computer that runs the
 * demo analyzer: create a match, record a few seconds with the camera, upload, wait for the
 * server's file check and the demo job, watch the clip, move the end of a rally in the range
 * editor, answer every rally and read the report.
 *
 * Skipped when the device has no camera or the backend does not answer. The demo analyzer
 * invents its rally intervals, so this proves the plumbing and nothing about analysis quality.
 */
@RunWith(AndroidJUnit4::class)
class DeviceFlowE2eTest {
    private val target get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val api = ApiClient(BuildConfig.API_URL_EMULATOR)
    private fun storedMatch() = LocalStore(File(target.filesDir, "volleystat.json")).load().matches.singleOrNull()

    @get:Rule(order = 0) val camera: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)
    @get:Rule(order = 1) val firstLaunchWithServer = object : ExternalResource() {
        override fun before() {
            assumeTrue("this device has no camera", target.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY))
            assumeTrue("needs the backend on ${BuildConfig.API_URL_EMULATOR}", runCatching { api.health() }.isSuccess)
            // The journey starts at the welcome screen, as after a fresh install.
            target.filesDir.deleteRecursively()
        }

        override fun after() {
            runCatching { storedMatch()?.session?.serverMatchId?.let(api::deleteMatch) }
        }
    }
    @get:Rule(order = 2) val app = createAndroidComposeRule<MainActivity>()

    private fun text(id: Int, vararg args: Any) = target.getString(id, *args)

    private fun textsOf(node: SemanticsNode): List<String> =
        node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text } + node.children.flatMap(::textsOf)

    /** What the app stored and what the screen shows, on one line: the only evidence a CI log keeps. */
    private fun evidence(): String {
        val stored = runCatching { storedMatch() }.getOrNull()
        val state = if (stored == null) "no stored match" else "check=${stored.check} session=${stored.session} analysis=${stored.analysis} video=${stored.video.sizeBytes} bytes ${stored.video.mimeType}"
        val screen = runCatching { app.onAllNodes(isRoot()).fetchSemanticsNodes().flatMap(::textsOf).take(40).joinToString(" | ") }.getOrElse { "unreadable: $it" }
        return "Stored: $state. On screen: $screen"
    }

    private fun waitFor(step: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
        try {
            app.waitUntil(timeoutMs, condition)
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError("$step did not happen within ${timeoutMs / 1000} s. ${evidence()}")
        }
    }

    private fun waitForText(value: String, timeoutMs: Long = 30_000) =
        waitFor("Text '$value'", timeoutMs) { app.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty() }

    /** The position the range editor shows, read back from its "Konum" line. Null when no editor is on screen. */
    private fun shownPositionMs(): Long? {
        val label = text(R.string.editor_position, "")
        val shown = app.onAllNodes(isRoot()).fetchSemanticsNodes().flatMap(::textsOf).firstOrNull { it.startsWith(label) } ?: return null
        val (clock, tenths) = shown.removePrefix(label).split(",")
        return clock.split(":").fold(0L) { seconds, part -> seconds * 60 + part.toLong() } * 1000 + tenths.toLong() * 100
    }

    private fun addAndPickTeam(name: String) {
        app.onAllNodesWithText(text(R.string.match_team_pick)).onFirst().performClick()
        app.onNodeWithText(text(R.string.teams_new_label)).performTextInput(name)
        app.onNodeWithText(text(R.string.team_sheet_add)).performClick()
        waitForText(name)
    }

    @Test fun aMatchIsRecordedUploadedAnalysedReviewedAndReported() {
        // 01 to 05
        app.onNodeWithText(text(R.string.action_start)).performClick()
        app.onNodeWithText(text(R.string.home_new_match_body)).performClick()
        app.onNodeWithText(text(R.string.action_continue)).performClick()
        addAndPickTeam(HOME_TEAM)
        addAndPickTeam(AWAY_TEAM)
        app.onNodeWithText(text(R.string.action_continue)).performClick()
        app.onNodeWithText(text(R.string.action_record)).performClick()

        // 06: record until the recorder's own clock shows six seconds.
        val start = text(R.string.record_start)
        waitFor("The record button") { app.onAllNodesWithContentDescription(start).fetchSemanticsNodes().isNotEmpty() }
        app.onNodeWithContentDescription(start).performClick()
        waitFor("Six recorded seconds on the recorder's clock", 60_000) {
            (6L..40L).any { second -> app.onAllNodesWithText(formatClock(second * 1000)).fetchSemanticsNodes().isNotEmpty() }
        }
        app.onNodeWithContentDescription(text(R.string.record_stop)).performClick()

        // The phone's own reading of the file, then the rights statement and the upload.
        waitForText(text(R.string.check_title))
        app.onNodeWithText(text(R.string.check_rights)).performScrollTo().performClick()
        app.onNodeWithText(text(R.string.action_start_upload)).assertIsEnabled().performClick()

        // 07: the upload, then the server's verdict on the file the camera wrote, then the demo job.
        waitFor("The match record after starting the upload") { storedMatch() != null }
        waitFor("The server's file check", 180_000) { storedMatch()?.check != null }
        val match = storedMatch()!!
        assertEquals("The server did not accept the recording. ${evidence()}", VideoCheck.READY, match.check!!.status)
        waitForText(text(R.string.action_start_review), 60_000)
        app.onNodeWithText(text(R.string.demo_title)).assertIsDisplayed()
        val job = api.latestAnalysis(match.check!!.videoId)!!
        assertEquals(AnalysisSummary.NEEDS_REVIEW, job.state)
        app.onNodeWithText(text(R.string.action_start_review)).performClick()

        // 08: the clip of the first rally must play from the server before any answer is given.
        waitForText(text(R.string.review_position, 1, job.rallies))
        try {
            app.waitUntil(8_000) { app.onAllNodesWithText(text(R.string.review_clip_failed)).fetchSemanticsNodes().isNotEmpty() }
            fail("the player could not play the clip from the server")
        } catch (stillPlaying: ComposeTimeoutException) {
            // No error within eight seconds of looping playback.
        }

        // The range editor, on the same player as the user gets. The video runs on its own from the start
        // of the rally, the bar moves the picture, and the mark is the moment the screen shows.
        val list = api.rallies(job.id)
        val rally = list.rallies.first()
        val window = rallyTarget(list, rally.id)!!
        app.onNodeWithText(text(R.string.review_fix_rally)).performClick()
        waitForText(text(R.string.editor_title_rally))
        waitFor("Playback in the range editor", 30_000) { shownPositionMs()?.let { it > rally.startMs } == true }
        assertEquals("The player could not play the video from the server. ${evidence()}", 0, app.onAllNodesWithText(text(R.string.editor_video_failed)).fetchSemanticsNodes().size)
        val mark = (rally.startMs + rally.endMs) / 2 + 300
        app.onNodeWithContentDescription(text(R.string.editor_seek)).performSemanticsAction(SemanticsActions.SetProgress) { it((mark - window.windowStartMs).toFloat()) }
        waitFor("The picture to stand at the mark") { shownPositionMs() == mark / 100 * 100 }
        app.onNodeWithText(text(R.string.action_end_here)).performClick()
        // One applied correction closes the editor. The rally asks for its winner on the new clip.
        waitForText(text(R.string.review_position, 1, job.rallies))
        assertEquals("The end of the rally is not the moment the editor showed. ${evidence()}", mark, api.rallies(job.id).rallies.first().endMs)

        for (position in 1..job.rallies) {
            waitForText(text(R.string.review_position, position, job.rallies))
            app.onNodeWithText(HOME_TEAM).performClick()
        }
        waitForText(text(R.string.review_empty_title))
        app.onNodeWithText(text(R.string.action_open_report)).performClick()

        // 09: every rally went to the home team.
        waitForText(text(R.string.report_verification_done))
        app.onNodeWithText(text(R.string.demo_title)).assertIsDisplayed()
        app.onNodeWithText("${job.rallies}-0").assertIsDisplayed()
        val report = api.report(match.session.serverMatchId!!)!!
        assertEquals(job.rallies, report.home.points.value)
        assertEquals(0, report.away.points.value)
    }
}
