package com.voleybolveveleybol.volleystat.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.voleybolveveleybol.volleystat.AppViewModel
import com.voleybolveveleybol.volleystat.R
import com.voleybolveveleybol.volleystat.VolleyStatApp
import com.voleybolveveleybol.volleystat.data.LocalStore
import com.voleybolveveleybol.volleystat.data.StoredState
import com.voleybolveveleybol.volleystat.model.AnalysisSummary
import com.voleybolveveleybol.volleystat.model.LocalVideo
import com.voleybolveveleybol.volleystat.model.MatchRecord
import com.voleybolveveleybol.volleystat.model.Settings
import com.voleybolveveleybol.volleystat.model.UploadSession
import com.voleybolveveleybol.volleystat.model.VideoCheck
import com.voleybolveveleybol.volleystat.model.VideoSource
import com.voleybolveveleybol.volleystat.ui.theme.VolleyStatTheme
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

private const val HOME_TEAM = "Liste Ev"
private const val AWAY_TEAM = "Liste Misafir"

/**
 * The way from the report to the rally list, to the question of a rally and to the range editor,
 * against a stand-in server with invented answers. The real server refuses to confirm the list of
 * its demo analyzer, so the confirmed list and what it does to the report are tried here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class RallyListFlowTest {
    private val server = MockWebServer()
    private val requests = CopyOnWriteArrayList<String>()
    private val bodies = CopyOnWriteArrayList<JSONObject>()
    private val control = FakeControl()
    private val storeFile get() = File(ApplicationProvider.getApplicationContext<Context>().filesDir, "volleystat.json")

    // What the stand-in server holds. Three rallies; the tests decide who has a winner.
    @Volatile private var version = 7
    @Volatile private var confirmed = false
    @Volatile private var winners = listOf<String?>("HOME", "AWAY", "HOME")
    @Volatile private var unsure = setOf<Int>()
    @Volatile private var thirdEndMs = 56_000L

    private fun ok(json: JSONObject) = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(json.toString())
    private val open get() = winners.indices.count { winners[it] == null && it !in unsure }

    private fun job() = JSONObject().put("id", "j1").put("videoId", "video-1").put("state", if (open == 0) "COMPLETE" else "NEEDS_REVIEW")
        .put("analyzer", JSONObject().put("id", "motion-baseline").put("kind", "BASELINE")).put("mock", false)
        .put("counts", JSONObject().put("rallies", 3).put("reviewOpen", open).put("reviewTotal", 3))
        .put("rallyListVersion", version).put("listConfirmed", confirmed)

    private val spans get() = listOf(2_000L to 9_000L, 15_000L to 24_000L, 48_000L to thirdEndMs)

    /** The running score, counted the way the server counts it: up to the first rally without a winner. */
    private fun scores(): List<Pair<Int, Int>?> {
        var home = 0
        var away = 0
        var counting = true
        return winners.map { winner ->
            if (winner == null) counting = false
            if (!counting) return@map null
            if (winner == "HOME") home++ else away++
            home to away
        }
    }

    private fun rallies() = JSONObject().put("rallies", JSONArray(winners.indices.map { index ->
        val score = scores()[index]
        JSONObject().put("id", "r${index + 1}").put("sequence", index + 1).put("startMs", spans[index].first).put("endMs", spans[index].second)
            .put("origin", "ANALYZER").put("status", if (winners[index] != null) "VERIFIED" else if (index in unsure) "UNRESOLVED" else "PROPOSED")
            .put("proposedWinner", "UNKNOWN").put("winner", winners[index] ?: JSONObject.NULL)
            .put("scoreAfter", score?.let { JSONObject().put("set", 1).put("home", it.first).put("away", it.second) } ?: JSONObject.NULL)
    })).put("rallyListVersion", version).put("listConfirmed", confirmed).put("videoDurationMs", 60_000)
        .put("sets", JSONArray(listOfNotNull(scores().lastOrNull { it != null }?.let { JSONObject().put("number", 1).put("home", it.first).put("away", it.second).put("complete", false) })))

    private fun cards() = JSONObject().put("cards", JSONArray(winners.indices.map { index ->
        val answer = winners[index] ?: if (index in unsure) "UNSURE" else null
        JSONObject().put("id", "c${index + 1}").put("rallyId", "r${index + 1}").put("rallySequence", index + 1)
            .put("questionType", "RALLY_WINNER").put("scope", "SCORE").put("clipStartMs", spans[index].first).put("clipEndMs", spans[index].second)
            .put("options", JSONArray(listOf("HOME", "AWAY", "NOT_A_RALLY", "UNSURE")))
            .put("status", if (answer == null) "OPEN" else "ANSWERED").put("answer", answer ?: JSONObject.NULL).put("version", version)
    })).put("position", JSONObject().put("answered", 3 - open).put("total", 3))

    private fun value(number: Int) = JSONObject().put("value", number).put("status", if (confirmed) "MEASURED" else "PARTIAL")
    private val nothing get() = JSONObject().put("value", JSONObject.NULL).put("status", "UNKNOWN")
    private fun team(points: Int) = JSONObject().put("points", value(points)).put("attackPoints", nothing).put("blockPoints", nothing)
        .put("aces", nothing).put("opponentErrors", nothing)

    private fun report(): JSONObject {
        val home = winners.count { it == "HOME" }
        val away = winners.count { it == "AWAY" }
        return JSONObject().put("jobId", "j1").put("analyzer", JSONObject().put("id", "motion-baseline").put("kind", "BASELINE")).put("mock", false)
            .put("verificationStatus", if (open == 0 && unsure.isEmpty()) "VERIFIED" else "PARTIALLY_VERIFIED").put("ralliesMayBeMissing", !confirmed)
            .put("rallyList", JSONObject().put("confirmed", confirmed).put("confirmedAt", JSONObject.NULL).put("corrections", 0))
            .put("rallies", JSONObject().put("proposed", 3).put("total", 3).put("userCreated", 0).put("verified", home + away).put("rejected", 0)
                .put("unresolved", unsure.size).put("open", open))
            .put("score", JSONObject().put("status", if (confirmed) "MEASURED" else "PARTIAL").put("setsWon", JSONObject().put("home", 0).put("away", 0))
                .put("sets", JSONArray(listOf(JSONObject().put("number", 1).put("home", home).put("away", away).put("complete", false)))).put("stoppedAtRally", JSONObject.NULL))
            .put("teams", JSONObject().put("home", team(home)).put("away", team(away)))
            .put("players", JSONArray()).put("unattributed", JSONObject().put("home", home).put("away", away))
            .put("coverage", JSONArray(listOf(JSONObject().put("metricId", "RALLY_WINNER").put("eligibleN", 3).put("observedN", home + away).put("verifiedN", home + away).put("status", "PARTIAL"))))
    }

    @get:Rule(order = 0) val seeded = object : ExternalResource() {
        override fun before() {
            // See JourneyTest: the static factory would hand the view model another test's Application.
            val factory = ViewModelProvider.AndroidViewModelFactory::class.java
            factory.declaredFields.single { it.type == factory }.apply { isAccessible = true }.set(null, null)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath.removePrefix("/v1")
                    requests += "${request.method} $path"
                    val body = if (request.method == "POST") JSONObject(request.body.readUtf8()).also { bodies += it } else JSONObject()
                    return when ("${request.method} $path") {
                        "GET /matches/server-1/report" -> ok(report())
                        "GET /videos/video-1/analyses/latest" -> ok(job())
                        "GET /analyses/j1/rallies" -> ok(rallies())
                        "GET /analyses/j1/review-cards" -> ok(cards())
                        "POST /analyses/j1/rally-list/confirm" -> {
                            confirmed = true
                            ok(JSONObject().put("job", job()).put("reportRevision", 9))
                        }
                        "POST /review-cards/c2/decisions" -> {
                            // A winner decision moves the list on, as on the real server.
                            winners = winners.toMutableList().also { it[1] = body.getString("selection") }
                            unsure = emptySet()
                            version++
                            ok(JSONObject().put("job", job()).put("reportRevision", 9))
                        }
                        "POST /rallies/r3/move" -> {
                            thirdEndMs = body.getLong("endMs")
                            version++
                            ok(JSONObject().put("correctionId", "k1").put("affected", JSONArray(listOf(rallies().getJSONArray("rallies").getJSONObject(2))))
                                .put("removed", JSONArray()).put("job", job()).put("reportRevision", 10))
                        }
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
                            analysis = AnalysisSummary("j1", AnalysisSummary.NEEDS_REVIEW, mock = false, reviewOpen = 1, reviewTotal = 3),
                        )
                    ),
                    settings = Settings(welcomeSeen = true),
                )
            )
        }

        override fun after() = server.shutdown()
    }
    @get:Rule(order = 1) val app = createAndroidComposeRule<ComponentActivity>()

    private fun text(id: Int, vararg arguments: Any) = app.activity.getString(id, *arguments)
    private fun waitFor(text: String) = app.waitUntil(20_000) { app.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun tap(id: Int) = app.onNodeWithText(text(id)).performClick()
    private fun back() {
        app.runOnUiThread { app.activity.onBackPressedDispatcher.onBackPressed() }
        app.waitForIdle()
    }

    /** Starts the app on its match list and opens the rally list of the one match. */
    private fun openTheRallyList() {
        ViewModelProvider(app.activity)[AppViewModel::class.java].baseUrlOverride = server.url("/v1").toString()
        app.setContent {
            VolleyStatTheme { VolleyStatApp(editorVideo = { _, startMs, _ -> remember { control.positionMs = startMs; control.video } }) }
        }
        app.onAllNodesWithText("$HOME_TEAM - $AWAY_TEAM", substring = true).onFirst().performClick()
        waitFor(text(R.string.rallies_title))
        app.onNodeWithText(text(R.string.rallies_title)).performScrollTo().performClick()
        waitFor(text(R.string.rallies_count, 3))
    }

    @Test fun confirmingTheListTakesThePlusSignsOffTheReport() {
        ViewModelProvider(app.activity)[AppViewModel::class.java].baseUrlOverride = server.url("/v1").toString()
        app.setContent { VolleyStatTheme { VolleyStatApp() } }
        app.onAllNodesWithText("$HOME_TEAM - $AWAY_TEAM", substring = true).onFirst().performClick()

        // Every card is answered, and still every number is a lower bound: the list may miss a rally.
        waitFor(text(R.string.report_may_miss))
        app.onNodeWithText("0+ - 0+").assertIsDisplayed()
        app.onNodeWithText(text(R.string.rallies_title)).performScrollTo().performClick()

        waitFor(text(R.string.rallies_count, 3))
        tap(R.string.action_check_score)
        app.onNodeWithText(text(R.string.score_check_set_open, 1, 2, 1)).assertIsDisplayed()
        tap(R.string.score_check_yes)
        waitFor(text(R.string.rallies_confirmed))
        assertEquals("the confirmation names the list the user was looking at", 7, bodies.single().getInt("expectedListVersion"))
        assertEquals(1, requests.count { it == "POST /analyses/j1/rally-list/confirm" })

        back()
        waitFor(text(R.string.report_list_confirmed))
        app.onNodeWithText("0 - 0").assertIsDisplayed()
        assertTrue(app.onAllNodesWithText(text(R.string.report_may_miss)).fetchSemanticsNodes().isEmpty())
    }

    @Test fun aRallyThatWaitsIsOpenedFromTheListAndTheEditorWorksOnTheListAsItIsNow() {
        // Rally 2 was answered "not sure", rally 3 has no answer yet.
        winners = listOf("HOME", null, null)
        unsure = setOf(1)
        openTheRallyList()

        app.onNodeWithText(text(R.string.rallies_not_ready, 2)).assertIsDisplayed()
        assertTrue(app.onAllNodesWithText(text(R.string.action_check_score)).fetchSemanticsNodes().isEmpty())
        tap(R.string.action_go_review)
        // The first rally that waits is the unsure one. Its card is answered, and it comes up all the same.
        waitFor(text(R.string.review_position, 2, 3))
        app.onNodeWithText(HOME_TEAM).performClick()
        waitFor(text(R.string.review_position, 3, 3))
        assertEquals(8, version)

        // The list in memory is the one from before that answer. The editor reads the new one before it lets the user mark.
        val readsBefore = requests.count { it == "GET /analyses/j1/rallies" }
        tap(R.string.review_fix_rally)
        waitFor(text(R.string.editor_title_rally))
        waitFor(text(R.string.action_end_here))
        assertEquals(readsBefore + 1, requests.count { it == "GET /analyses/j1/rallies" })
        assertEquals("the editor opens at the start of the rally", 48_000L, control.positionMs)
        app.runOnUiThread { control.positionMs = 52_000 }
        app.waitForIdle()
        tap(R.string.action_end_here)

        // The correction names the list of now, so the server has no reason to refuse it.
        waitFor(text(R.string.review_position, 3, 3))
        val move = bodies.last()
        assertEquals(listOf(8, 48_000, 52_000), listOf(move.getInt("expectedListVersion"), move.getInt("startMs"), move.getInt("endMs")))
        assertEquals(1, requests.count { it == "POST /rallies/r3/move" })

        // Back leads to the list the user came from, and the list shows what the server holds now.
        back()
        waitFor(text(R.string.rallies_count, 3))
        app.onNodeWithText(text(R.string.rallies_not_ready, 1)).assertIsDisplayed()
        // The list builds only the rows on screen, so the last rally has to be scrolled to.
        app.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("00:48 · 4 sn"))
        app.onNodeWithText("00:48 · 4 sn").assertIsDisplayed()
    }
}
