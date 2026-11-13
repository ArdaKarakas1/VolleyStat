package com.voleybolveveleybol.volleystat.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.voleybolveveleybol.volleystat.AppViewModel
import com.voleybolveveleybol.volleystat.MainActivity
import com.voleybolveveleybol.volleystat.R
import com.voleybolveveleybol.volleystat.data.LocalStore
import com.voleybolveveleybol.volleystat.data.StoredState
import com.voleybolveveleybol.volleystat.model.AnalysisSummary
import com.voleybolveveleybol.volleystat.model.LocalVideo
import com.voleybolveveleybol.volleystat.model.MatchRecord
import com.voleybolveveleybol.volleystat.model.Settings
import com.voleybolveveleybol.volleystat.model.UploadSession
import com.voleybolveveleybol.volleystat.model.VideoCheck
import com.voleybolveveleybol.volleystat.model.VideoSource
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

private const val HOME_TEAM = "Dönüş Ev"
private const val AWAY_TEAM = "Dönüş Misafir"

/**
 * Report, review and back to the report, against a stand-in server with invented answers.
 * The way back must lead to the one report there is: version 0.4.0 put a second copy of the
 * report on the stack, and the back button then showed the same report again.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ReportReturnTest {
    private val server = MockWebServer()
    private val requests = CopyOnWriteArrayList<String>()
    private val storeFile get() = File(ApplicationProvider.getApplicationContext<Context>().filesDir, "volleystat.json")

    private fun ok(json: JSONObject) = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(json.toString())
    private fun value(number: Int?) = JSONObject().put("value", number ?: JSONObject.NULL).put("status", if (number == null) "UNKNOWN" else "PARTIAL")
    private fun team(points: Int) = JSONObject().put("points", value(points)).put("attackPoints", value(null)).put("blockPoints", value(null))
        .put("aces", value(null)).put("opponentErrors", value(null))
    private val job = JSONObject().put("id", "j1").put("videoId", "video-1").put("state", "NEEDS_REVIEW")
        .put("analyzer", JSONObject().put("id", "demo").put("kind", "MOCK")).put("mock", true)
        .put("counts", JSONObject().put("rallies", 2).put("reviewOpen", 1).put("reviewTotal", 2))
    private fun card(sequence: Int, answer: String?) = JSONObject().put("id", "c$sequence").put("rallyId", "r$sequence").put("rallySequence", sequence)
        .put("questionType", "RALLY_WINNER").put("scope", "SCORE").put("clipStartMs", 1000).put("clipEndMs", 5000)
        .put("options", JSONArray(listOf("HOME", "AWAY", "NOT_A_RALLY", "UNSURE")))
        .put("status", if (answer == null) "OPEN" else "ANSWERED").put("answer", answer ?: JSONObject.NULL).put("version", 1)
    private val report = JSONObject().put("jobId", "j1").put("analyzer", JSONObject().put("id", "demo").put("kind", "MOCK")).put("mock", true)
        .put("verificationStatus", "PARTIALLY_VERIFIED").put("ralliesMayBeMissing", true)
        .put("rallies", JSONObject().put("proposed", 2).put("verified", 1).put("rejected", 0).put("unresolved", 0).put("open", 1))
        .put("score", JSONObject().put("status", "PARTIAL").put("setsWon", JSONObject().put("home", 0).put("away", 0))
            .put("sets", JSONArray(listOf(JSONObject().put("number", 1).put("home", 1).put("away", 0).put("complete", false)))).put("stoppedAtRally", 2))
        .put("teams", JSONObject().put("home", team(1)).put("away", team(0)))
        .put("players", JSONArray()).put("unattributed", JSONObject().put("home", 1).put("away", 0))
        .put("coverage", JSONArray(listOf(JSONObject().put("metricId", "RALLY_WINNER").put("eligibleN", 2).put("observedN", 1).put("verifiedN", 1).put("status", "PARTIAL"))))

    @get:Rule(order = 0) val seeded = object : ExternalResource() {
        override fun before() {
            // See JourneyTest: the static factory would hand the view model another test's Application.
            val factory = ViewModelProvider.AndroidViewModelFactory::class.java
            factory.declaredFields.single { it.type == factory }.apply { isAccessible = true }.set(null, null)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath.removePrefix("/v1")
                    requests += "${request.method} $path"
                    return when (path) {
                        "/matches/server-1/report" -> ok(report)
                        "/videos/video-1/analyses/latest" -> ok(job)
                        "/analyses/j1/review-cards" -> ok(JSONObject().put("cards", JSONArray(listOf(card(1, "HOME"), card(2, null))))
                            .put("position", JSONObject().put("answered", 1).put("total", 2)))
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            server.start()
            LocalStore(storeFile).save(
                StoredState(
                    matches = listOf(
                        MatchRecord(
                            "m1", HOME_TEAM, AWAY_TEAM, 1L, LocalVideo("/none/m1.mp4", VideoSource.CAMERA, "m1.mp4", 10, "video/mp4", null, null, null, null),
                            session = UploadSession("sha", "server-1", "upload-1", 10),
                            check = VideoCheck("video-1", VideoCheck.READY, null, null, null, null, null, null, emptyList()),
                            analysis = AnalysisSummary("j1", AnalysisSummary.NEEDS_REVIEW, mock = true, reviewOpen = 1, reviewTotal = 2),
                        )
                    ),
                    settings = Settings(welcomeSeen = true),
                )
            )
        }

        override fun after() = server.shutdown()
    }
    @get:Rule(order = 1) val app = createAndroidComposeRule<MainActivity>()

    private fun text(id: Int, vararg arguments: Any) = app.activity.getString(id, *arguments)
    private fun waitFor(text: String) = app.waitUntil(20_000) { app.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    @Test fun reviewOpenedFromTheReportReturnsToThatReportAndBackLeadsHome() {
        ViewModelProvider(app.activity)[AppViewModel::class.java].baseUrlOverride = server.url("/v1").toString()

        app.onAllNodesWithText("$HOME_TEAM - $AWAY_TEAM", substring = true).onFirst().performClick()
        waitFor(text(R.string.report_review_video))
        // The demo label and the lower bound are on the report from its first frame.
        app.onNodeWithText("0+ - 0+").assertIsDisplayed()
        app.onNodeWithText(text(R.string.report_review_video)).performScrollTo().performClick()

        // The open card comes up. Skipping it must not end in "everything is decided".
        waitFor(text(R.string.review_skip))
        app.onNodeWithText(text(R.string.review_skip)).performClick()
        app.onNodeWithText(text(R.string.review_skipped_title, 1)).assertIsDisplayed()
        app.onNodeWithText(text(R.string.action_open_report)).performClick()

        waitFor(text(R.string.report_review_video))
        assertEquals("the report is read again when the user comes back to it", 2, requests.count { it == "GET /matches/server-1/report" })
        app.runOnUiThread { app.activity.onBackPressedDispatcher.onBackPressed() }
        app.waitForIdle()
        app.onNodeWithText(text(R.string.home_new_match_body)).assertIsDisplayed()
    }
}
