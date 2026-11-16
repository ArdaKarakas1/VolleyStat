package com.voleybolveveleybol.volleystat

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.voleybolveveleybol.volleystat.data.LocalStore
import com.voleybolveveleybol.volleystat.data.StoredState
import com.voleybolveveleybol.volleystat.model.Draft
import com.voleybolveveleybol.volleystat.model.LocalVideo
import com.voleybolveveleybol.volleystat.model.MatchRecord
import com.voleybolveveleybol.volleystat.model.UploadSession
import com.voleybolveveleybol.volleystat.model.VideoCheck
import com.voleybolveveleybol.volleystat.model.VideoSource
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The view model against a scripted stand-in server: what it does with slow, lost and refused
 * answers. Every case here was a defect an independent review found in version 0.4.0.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppViewModelTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val server = MockWebServer()
    /** "METHOD path" of every request, in the order the server saw them. */
    private val requests = CopyOnWriteArrayList<String>()
    private val script = ConcurrentHashMap<String, (RecordedRequest) -> MockResponse>()

    @Before fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val key = "${request.method} ${request.requestUrl!!.encodedPath.removePrefix("/v1")}"
                requests += key
                return script[key]?.invoke(request) ?: refuse(404, "NOT_FOUND")
            }
        }
        server.start()
    }

    @After fun stop() = server.shutdown()

    private fun on(request: String, answer: (RecordedRequest) -> MockResponse) { script[request] = answer }
    private fun ok(json: JSONObject) = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(json.toString())
    private fun refuse(status: Int, code: String) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json")
        .setBody(JSONObject().put("error", JSONObject().put("code", code).put("message", code).put("details", JSONObject())).toString())

    private fun job(id: String, video: String, state: String, open: Int = 2) = JSONObject().put("id", id).put("videoId", video).put("state", state)
        .put("analyzer", JSONObject().put("id", "demo").put("kind", "MOCK")).put("mock", true)
        .put("counts", JSONObject().put("rallies", 2).put("reviewOpen", open).put("reviewTotal", 2))

    private fun card(id: String, sequence: Int, answer: String? = null) = JSONObject().put("id", id).put("rallyId", "rally-$id").put("rallySequence", sequence)
        .put("questionType", "RALLY_WINNER").put("scope", "SCORE").put("clipStartMs", 1000).put("clipEndMs", 5000)
        .put("options", JSONArray(listOf("HOME", "AWAY", "NOT_A_RALLY", "UNSURE")))
        .put("status", if (answer == null) "OPEN" else "ANSWERED").put("answer", answer ?: JSONObject.NULL).put("version", if (answer == null) 1 else 2)

    private fun deck(vararg cards: JSONObject) = JSONObject().put("cards", JSONArray(cards.toList()))
        .put("position", JSONObject().put("answered", cards.count { it.getString("status") != "OPEN" }).put("total", cards.size))

    private fun video(path: String) = LocalVideo(path, VideoSource.CAMERA, File(path).name, 10, "video/mp4", null, null, null, null)

    /** A match whose file the server accepted as video-<id>. */
    private fun accepted(id: String) = MatchRecord(
        id, "Ev $id", "Misafir $id", 1L, video("/none/$id.mp4"),
        session = UploadSession("sha", "server-$id", "upload-$id", 10),
        check = VideoCheck("video-$id", VideoCheck.READY, null, null, null, null, null, null, emptyList()),
    )

    private fun viewModel(stored: StoredState): AppViewModel {
        LocalStore(File(context.filesDir, "volleystat.json")).save(stored)
        return AppViewModel(context).also { it.baseUrlOverride = server.url("/v1").toString() }
    }

    private fun await(what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "timed out waiting for: $what" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    /** Lets answers that are already on their way arrive. */
    private fun settle() = repeat(40) {
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(15)
    }

    @Test fun cardsThatArriveLateForAnotherMatchNeverReachTheOpenMatch() {
        val releaseA = CountDownLatch(1)
        on("GET /videos/video-A/analyses/latest") { ok(job("jA", "video-A", "NEEDS_REVIEW")) }
        on("GET /videos/video-B/analyses/latest") { ok(job("jB", "video-B", "NEEDS_REVIEW")) }
        on("GET /analyses/jA/review-cards") { releaseA.await(8, TimeUnit.SECONDS); ok(deck(card("A1", 1), card("A2", 2))) }
        on("GET /analyses/jB/review-cards") { ok(deck(card("B1", 1))) }
        val vm = viewModel(StoredState(matches = listOf(accepted("A"), accepted("B"))))

        vm.watchAnalysis("A", startIfMissing = false)
        await("the job of A") { vm.analysis.value.job?.id == "jA" }
        vm.loadReview()
        await("the request for the cards of A") { "GET /analyses/jA/review-cards" in requests }

        vm.watchAnalysis("B", startIfMissing = false)
        await("the job of B") { vm.analysis.value.job?.id == "jB" }
        vm.loadReview()
        await("the cards of B") { vm.analysis.value.deck != null }

        releaseA.countDown()
        settle()
        assertEquals("B", vm.analysis.value.matchId)
        assertEquals(listOf("B1"), vm.analysis.value.deck!!.cards.map { it.id })
    }

    @Test fun aStoredDecisionWhoseCardReloadFailedIsNotReportedAsUnsavedAndTheSameTapReusesItsKey() {
        val keys = CopyOnWriteArrayList<String>()
        var cardLoads = 0
        on("GET /videos/video-A/analyses/latest") { ok(job("jA", "video-A", "NEEDS_REVIEW")) }
        on("GET /analyses/jA/review-cards") {
            when (++cardLoads) {
                1 -> ok(deck(card("A1", 1), card("A2", 2)))
                2 -> refuse(500, "INTERNAL")
                else -> ok(deck(card("A1", 1, answer = "HOME"), card("A2", 2)))
            }
        }
        on("POST /review-cards/A1/decisions") { request ->
            keys += JSONObject(request.body.readUtf8()).getString("idempotencyKey")
            ok(JSONObject().put("job", job("jA", "video-A", "NEEDS_REVIEW", open = 1)))
        }
        val vm = viewModel(StoredState(matches = listOf(accepted("A"))))
        vm.watchAnalysis("A", startIfMissing = false)
        await("the job") { vm.analysis.value.job != null }
        vm.loadReview()
        await("the cards") { vm.analysis.value.deck != null }

        vm.answerReview("HOME")
        await("the first answer") { !vm.analysis.value.saving }
        // The server stored the decision. Only the reload of the cards failed.
        assertEquals(ReviewProblem.LOAD_FAILED, vm.analysis.value.reviewProblem)
        assertEquals(1, vm.analysis.value.job!!.reviewOpen)
        assertEquals(0, vm.analysis.value.cursor)

        vm.answerReview("HOME")
        await("the second answer") { !vm.analysis.value.saving }
        assertEquals(2, keys.size)
        assertEquals("the retry of the same decision must carry the same key", keys[0], keys[1])
        assertNull(vm.analysis.value.reviewProblem)
        assertEquals(1, vm.analysis.value.cursor)
    }

    @Test fun retryAfterALostConnectionAsksForTheJobAgainAndStartsNoNewAnalysis() {
        var reachable = false
        on("GET /videos/video-A/analyses/latest") { if (reachable) ok(job("jA", "video-A", "NEEDS_REVIEW")) else refuse(500, "INTERNAL") }
        on("POST /videos/video-A/analyses") { ok(job("jNew", "video-A", "QUEUED")) }
        val vm = viewModel(StoredState(matches = listOf(accepted("A"))))
        vm.watchAnalysis("A")
        await("the failed first read") { vm.analysis.value.jobLoad == JobLoad.UNREACHABLE }

        reachable = true
        vm.restartAnalysis("A")
        await("the job") { vm.analysis.value.jobLoad == JobLoad.READY }
        // A new job here would have replaced the one the user is reviewing.
        assertEquals("jA", vm.analysis.value.job!!.id)
        assertFalse(requests.any { it.startsWith("POST /videos/video-A/analyses") })
    }

    @Test fun restartOfAFailedJobSaysSoWhenTheServerHasNoAnalyzer() {
        on("GET /videos/video-A/analyses/latest") { ok(job("jA", "video-A", "FAILED").put("failure", JSONObject().put("code", "ANALYZER_ERROR"))) }
        on("POST /videos/video-A/analyses") { refuse(503, "MODEL_NOT_CONFIGURED") }
        val vm = viewModel(StoredState(matches = listOf(accepted("A"))))
        vm.watchAnalysis("A")
        await("the failed job") { vm.analysis.value.job?.state == "FAILED" }

        vm.restartAnalysis("A")
        await("the answer to the restart") { vm.analysis.value.jobLoad == JobLoad.NO_ANALYZER }
        assertNull(vm.analysis.value.job)
        assertEquals(1, requests.count { it == "POST /videos/video-A/analyses" })
    }

    @Test fun aVideoTheServerNoLongerHoldsEndsTheWatchWithoutANewRequest() {
        on("GET /videos/video-A/analyses/latest") { refuse(404, "VIDEO_NOT_FOUND") }
        val vm = viewModel(StoredState(matches = listOf(accepted("A"))))
        vm.watchAnalysis("A")
        await("the server's answer") { vm.analysis.value.jobLoad == JobLoad.VIDEO_MISSING }
        settle()
        assertEquals(listOf("GET /videos/video-A/analyses/latest"), requests.toList())
    }

    @Test fun aVideoWithoutAJobIsNotMistakenForAMissingVideo() {
        on("GET /videos/video-A/analyses/latest") { refuse(404, "ANALYSIS_NOT_FOUND") }
        val vm = viewModel(StoredState(matches = listOf(accepted("A"))))
        vm.watchAnalysis("A", startIfMissing = false)
        await("the server's answer") { vm.analysis.value.jobLoad == JobLoad.NO_JOB }
    }

    @Test fun aRecordingTheDraftOrAnUnfinishedUploadReadsFromIsNotDeleted() {
        val folder = File(context.filesDir, "recordings").apply { mkdirs() }
        val ofDraft = File(folder, "kayit-1.mp4").apply { writeBytes(ByteArray(10)) }
        val ofUpload = File(folder, "kayit-2.mp4").apply { writeBytes(ByteArray(10)) }
        val free = File(folder, "kayit-3.mp4").apply { writeBytes(ByteArray(10)) }
        val uploading = MatchRecord("U", "Ev", "Misafir", 1L, video(ofUpload.path), session = UploadSession("sha", "server-U", "upload-U", 4))
        val vm = viewModel(StoredState(matches = listOf(uploading), draft = Draft(homeTeam = "Ev", awayTeam = "Misafir", video = video(ofDraft.path))))

        listOf(ofDraft, ofUpload, free).forEach { vm.deleteRecording(it.path) }
        assertTrue(ofDraft.exists())
        assertTrue(ofUpload.exists())
        assertFalse(free.exists())
        assertEquals(setOf("kayit-1.mp4", "kayit-2.mp4"), vm.state.value.recordings.map { it.name }.toSet())

        vm.discardDraft()
        vm.deleteRecording(ofDraft.path)
        assertFalse(ofDraft.exists())
    }
}
