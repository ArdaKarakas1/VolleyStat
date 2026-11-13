package com.voleybolveveleybol.volleystat

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.voleybolveveleybol.volleystat.data.ApiClient
import com.voleybolveveleybol.volleystat.data.LocalStore
import com.voleybolveveleybol.volleystat.model.AnalysisSummary
import com.voleybolveveleybol.volleystat.model.VideoCheck
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
 * server's file check and the demo job, watch the clip, answer every rally and read the report.
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
            runCatching { storedMatch()?.check?.videoId?.let(api::deleteVideo) }
        }
    }
    @get:Rule(order = 2) val app = createAndroidComposeRule<MainActivity>()

    private fun text(id: Int, vararg args: Any) = target.getString(id, *args)
    private fun waitForText(value: String, timeoutMs: Long = 30_000) =
        app.waitUntil(timeoutMs) { app.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty() }

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
        app.waitUntil(30_000) { app.onAllNodesWithContentDescription(start).fetchSemanticsNodes().isNotEmpty() }
        app.onNodeWithContentDescription(start).performClick()
        app.waitUntil(60_000) { (6L..40L).any { second -> app.onAllNodesWithText(formatClock(second * 1000)).fetchSemanticsNodes().isNotEmpty() } }
        app.onNodeWithContentDescription(text(R.string.record_stop)).performClick()

        // The phone's own reading of the file, then the rights statement and the upload.
        waitForText(text(R.string.check_title))
        app.onNodeWithText(text(R.string.check_rights)).performScrollTo().performClick()
        app.onNodeWithText(text(R.string.action_start_upload)).performClick()

        // 07: upload, the server's file check and the demo job.
        waitForText(text(R.string.action_start_review), 180_000)
        app.onNodeWithText(text(R.string.demo_title)).assertIsDisplayed()
        val match = storedMatch()!!
        assertEquals(VideoCheck.READY, match.check!!.status)
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
