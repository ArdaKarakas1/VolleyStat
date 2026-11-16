package com.voleybolveveleybol.volleystat.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.voleybolveveleybol.volleystat.BuildConfig
import com.voleybolveveleybol.volleystat.MainActivity
import com.voleybolveveleybol.volleystat.R
import com.voleybolveveleybol.volleystat.data.ApiClient
import com.voleybolveveleybol.volleystat.data.LocalStore
import com.voleybolveveleybol.volleystat.data.MatchInfo
import com.voleybolveveleybol.volleystat.data.StoredState
import com.voleybolveveleybol.volleystat.data.UploadEngine
import com.voleybolveveleybol.volleystat.data.VideoBytes
import com.voleybolveveleybol.volleystat.model.AnalysisSummary
import com.voleybolveveleybol.volleystat.model.LocalVideo
import com.voleybolveveleybol.volleystat.model.MatchRecord
import com.voleybolveveleybol.volleystat.model.ServerTarget
import com.voleybolveveleybol.volleystat.model.Settings
import com.voleybolveveleybol.volleystat.model.UploadSession
import com.voleybolveveleybol.volleystat.model.VideoSource
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

private const val HOME_TEAM = "E2E Ev"
private const val AWAY_TEAM = "E2E Misafir"

/**
 * Drives the real screens, view model and API client against a running backend: a match whose
 * video the server accepted is analysed, every rally is answered on the review screen, and the
 * report screen shows exactly those answers.
 *
 * The debug build reaches the server of a phone on USB at 127.0.0.1:8000, an address the JVM
 * reaches too. So the test runs only when VOLLEYSTAT_E2E_URL is that address, the server runs
 * with the demo analyzer (python -m app --analyzer demo) and VOLLEYSTAT_E2E_VIDEO names a
 * synthetic clip. It says nothing about the camera, the picker or real playback.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class AppFlowE2eTest {
    private val url = System.getenv("VOLLEYSTAT_E2E_URL")
    private val clip = System.getenv("VOLLEYSTAT_E2E_VIDEO")?.let(::File)
    private var videoId: String? = null
    private var matchId: String? = null

    /** Uploads the clip and stores the match the way the app itself would have left it. */
    @get:Rule(order = 0) val acceptedMatch = object : ExternalResource() {
        override fun before() {
            assumeTrue("needs a backend on ${BuildConfig.API_URL_USB}", url == BuildConfig.API_URL_USB && clip != null && clip.isFile)
            // See JourneyTest: the static factory would hand the view model another test's Application.
            val factory = ViewModelProvider.AndroidViewModelFactory::class.java
            factory.declaredFields.single { it.type == factory }.apply { isAccessible = true }.set(null, null)

            var session = UploadSession()
            val check = UploadEngine(ApiClient(url!!)).run(
                session, MatchInfo(HOME_TEAM, AWAY_TEAM), "e2e.mp4", "video/mp4", VideoBytes(clip!!.length()) { clip.inputStream() },
                onSession = { session = it }, onProgress = { _, _, _ -> }, cancelled = { false },
            )
            videoId = check.videoId
            matchId = session.serverMatchId
            val files = ApplicationProvider.getApplicationContext<Context>().filesDir
            LocalStore(File(files, "volleystat.json")).save(
                StoredState(
                    matches = listOf(
                        MatchRecord(
                            "m1", HOME_TEAM, AWAY_TEAM, System.currentTimeMillis(),
                            LocalVideo(clip.path, VideoSource.GALLERY, clip.name, clip.length(), "video/mp4", check.durationMs, check.width, check.height, null),
                            session = session, check = check, checkedAtMs = System.currentTimeMillis(),
                        )
                    ),
                    settings = Settings(serverTarget = ServerTarget.USB_DEVICE, welcomeSeen = true),
                )
            )
        }

        override fun after() {
            matchId?.let { runCatching { ApiClient(url!!).deleteMatch(it) } }
        }
    }
    @get:Rule(order = 1) val app = createAndroidComposeRule<MainActivity>()

    private fun text(id: Int, vararg args: Any) = app.activity.getString(id, *args)
    private fun waitFor(text: String) = app.waitUntil(30_000) { app.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    @Test fun anAcceptedMatchIsAnalysedReviewedAndReportedThroughTheScreens() {
        app.onAllNodesWithText(text(R.string.nav_matches)).onFirst().performClick()
        app.onNodeWithText("$HOME_TEAM - $AWAY_TEAM").performClick()

        // 07: opening the match asks the server for an analysis; the demo job ends in review.
        waitFor(text(R.string.action_start_review))
        app.onNodeWithText(text(R.string.demo_title)).assertIsDisplayed()
        val job = ApiClient(url!!).latestAnalysis(videoId!!)!!
        assertEquals(AnalysisSummary.NEEDS_REVIEW, job.state)
        val rallies = job.rallies
        app.onNodeWithText(text(R.string.action_start_review)).performClick()

        // 08: one tap per rally. Every rally goes to the home team.
        for (position in 1..rallies) {
            waitFor(text(R.string.review_position, position, rallies))
            app.onNodeWithText(HOME_TEAM).performClick()
        }
        waitFor(text(R.string.review_empty_title))
        app.onNodeWithText(text(R.string.action_open_report)).performClick()

        // 09: the report follows those answers and still carries the demo label.
        waitFor(text(R.string.report_verification_done))
        app.onNodeWithText(text(R.string.demo_title)).assertIsDisplayed()
        app.onNodeWithText("$rallies-0").assertIsDisplayed()
        val report = ApiClient(url).report(requireNotNull(serverMatchId()))!!
        assertEquals(rallies, report.home.points.value)
        assertEquals(0, report.away.points.value)

        // 10: nobody chose a scorer, so no player has a point and every point is listed as unattributed.
        app.onNodeWithText(text(R.string.report_player_stats)).performClick()
        waitFor(text(R.string.players_empty))
        app.onNodeWithText(text(R.string.players_unattributed)).assertIsDisplayed()
    }

    private fun serverMatchId(): String? {
        val files = ApplicationProvider.getApplicationContext<Context>().filesDir
        return LocalStore(File(files, "volleystat.json")).load().matches.single().session.serverMatchId
    }
}
