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
import org.junit.Assert.fail
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
 * answers. The analysis cases were defects an independent review found in version 0.4.0; the
 * removal cases keep the app from forgetting a match the server still holds.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppViewModelTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val server = MockWebServer()
    /** "METHOD path" of every request, in the order the server saw them. */
    private val requests = CopyOnWriteArrayList<String>()
    private val script = ConcurrentHashMap<String, (RecordedRequest) -> MockResponse>()
    /** Answers for addresses that carry an id the phone chose itself. */
    private val patterns = CopyOnWriteArrayList<Pair<Regex, (RecordedRequest) -> MockResponse>>()

    @Before fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val key = "${request.method} ${request.requestUrl!!.encodedPath.removePrefix("/v1")}"
                requests += key
                val answer = script[key] ?: patterns.firstOrNull { it.first.matches(key) }?.second
                return answer?.invoke(request) ?: refuse(404, "NOT_FOUND")
            }
        }
        server.start()
    }

    @After fun stop() = server.shutdown()

    private fun on(request: String, answer: (RecordedRequest) -> MockResponse) { script[request] = answer }
    private fun onMatching(request: String, answer: (RecordedRequest) -> MockResponse) { patterns += Regex(request) to answer }
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

    private fun rally(id: String, sequence: Int, startMs: Long, endMs: Long, status: String = "PROPOSED") = JSONObject().put("id", id)
        .put("sequence", sequence).put("startMs", startMs).put("endMs", endMs).put("origin", "ANALYZER").put("status", status)
        .put("proposedWinner", "UNKNOWN").put("winner", JSONObject.NULL).put("scoreAfter", JSONObject.NULL)

    private fun rallies(version: Int, vararg list: JSONObject, confirmed: Boolean = false) = JSONObject().put("rallies", JSONArray(list.toList()))
        .put("rallyListVersion", version).put("listConfirmed", confirmed).put("videoDurationMs", 60_000).put("sets", JSONArray())

    private fun corrected(job: JSONObject, vararg affected: JSONObject) = JSONObject().put("correctionId", "correction-1")
        .put("affected", JSONArray(affected.toList())).put("removed", JSONArray()).put("job", job).put("reportRevision", 5)

    /** A view model that follows job J of match A and has read its rally list, which the test can then change. */
    private fun withRallyList(job: JSONObject = job("J", "video-A", "NEEDS_REVIEW"), list: () -> JSONObject): AppViewModel {
        on("GET /videos/video-A/analyses/latest") { ok(job) }
        on("GET /analyses/J/rallies") { ok(list()) }
        on("GET /analyses/J/review-cards") { ok(deck(card("c1", 1), card("c2", 2), card("c3", 3))) }
        val vm = viewModel(StoredState(matches = listOf(accepted("A"))))
        vm.watchAnalysis("A", startIfMissing = false)
        await("the job") { vm.analysis.value.job != null }
        vm.loadRallies("A")
        await("the rally list") { vm.analysis.value.rallies != null }
        requests.clear()
        return vm
    }

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

    @Test fun removingAMatchDeletesTheWholeMatchOnTheServerAndForgetsItOnlyThen() {
        on("DELETE /matches/server-A") { MockResponse().setResponseCode(204) }
        val vm = viewModel(StoredState(matches = listOf(accepted("A"), accepted("B"))))
        var done = false
        vm.removeMatch("A") { done = true }
        await("the removal") { done }
        // One request covers the video, the analyses, the decisions and the match record itself.
        assertEquals(listOf("DELETE /matches/server-A"), requests.toList())
        assertEquals(listOf("B"), vm.state.value.matches.map { it.localId })
        assertNull(vm.state.value.removalProblem)
    }

    @Test fun removingAMatchWithAnUnfinishedUploadAlsoDeletesTheWholeMatch() {
        on("DELETE /matches/server-U") { MockResponse().setResponseCode(204) }
        val uploading = MatchRecord("U", "Ev", "Misafir", 1L, video("/none/U.mp4"), session = UploadSession("sha", "server-U", "upload-U", 4))
        val vm = viewModel(StoredState(matches = listOf(uploading)))
        var done = false
        vm.removeMatch("U") { done = true }
        await("the removal") { done }
        assertEquals(listOf("DELETE /matches/server-U"), requests.toList())
        assertTrue(vm.state.value.matches.isEmpty())
    }

    @Test fun aMatchTheServerAlreadyForgotIsRemovedFromThePhone() {
        on("DELETE /matches/server-A") { refuse(404, "MATCH_NOT_FOUND") }
        val vm = viewModel(StoredState(matches = listOf(accepted("A"))))
        var done = false
        vm.removeMatch("A") { done = true }
        await("the removal") { done }
        assertTrue(vm.state.value.matches.isEmpty())
        // The answer that counts is the one of the match route, not any 404 the server happens to give.
        assertEquals(listOf("DELETE /matches/server-A"), requests.toList())
    }

    @Test fun aMatchThatNeverReachedTheServerIsRemovedWithoutARequest() {
        val vm = viewModel(StoredState(matches = listOf(MatchRecord("L", "Ev", "Misafir", 1L, video("/none/L.mp4")))))
        var done = false
        vm.removeMatch("L") { done = true }
        await("the removal") { done }
        assertTrue(vm.state.value.matches.isEmpty())
        assertTrue(requests.isEmpty())
    }

    @Test fun aMatchTheServerDidNotDeleteStaysOnThePhoneAndTheAppSaysWhy() {
        val vm = viewModel(StoredState(matches = listOf(accepted("A"))))
        val html = { MockResponse().setResponseCode(200).setHeader("Content-Type", "text/html").setBody("<html>another service</html>") }
        // None of these answers is the match route saying "deleted": a server that is still verifying
        // the upload, an older server without the route, an address that answers 404 to everything,
        // and another service on the port that answers 200 with a page or with nothing at all.
        val answers = listOf(
            { refuse(409, "UPLOAD_FINALIZING") } to RemovalFailure.VERIFYING,
            { refuse(405, "METHOD_NOT_ALLOWED") } to RemovalFailure.REFUSED,
            { refuse(404, "NOT_FOUND") } to RemovalFailure.REFUSED,
            html to RemovalFailure.REFUSED,
            { MockResponse().setResponseCode(200) } to RemovalFailure.REFUSED,
        )
        answers.forEach { (answer, failure) ->
            on("DELETE /matches/server-A") { answer() }
            vm.removeMatch("A") { fail("the app forgot a match the server still holds") }
            await("the refusal") { vm.state.value.removalProblem != null }
            assertEquals(RemovalProblem("A", failure), vm.state.value.removalProblem)
            assertEquals(listOf("A"), vm.state.value.matches.map { it.localId })
            vm.clearRemovalFailure()
        }
        assertEquals(List(answers.size) { "DELETE /matches/server-A" }, requests.toList())
    }

    @Test fun aMatchStaysOnThePhoneWhenTheServerCanNotBeReached() {
        val vm = viewModel(StoredState(matches = listOf(accepted("A"))))
        vm.baseUrlOverride = "http://127.0.0.1:1/v1"  // nothing listens there
        vm.removeMatch("A") { fail("the app forgot a match the server still holds") }
        await("the failure") { vm.state.value.removalProblem != null }
        // The app can not know what the server did, so it does not say that the records are still there.
        assertEquals(RemovalProblem("A", RemovalFailure.UNCONFIRMED), vm.state.value.removalProblem)
        assertEquals(listOf("A"), vm.state.value.matches.map { it.localId })
    }

    @Test fun removingAMatchWhileItsUploadRunsStopsTheUploadAndThenDeletesTheMatchThePhoneCreated() {
        val file = File(File(context.filesDir, "recordings").apply { mkdirs() }, "kayit-9.mp4").apply { writeBytes(ByteArray(10) { it.toByte() }) }
        val chunkArrived = CountDownLatch(1)
        val answerChunk = CountDownLatch(1)
        on("GET /health") { ok(JSONObject().put("status", "ok").put("mediaProbe", "available").put("maxVideoBytes", 1_000_000)) }
        onMatching("PUT /matches/[0-9a-f-]{36}") { MockResponse().setResponseCode(201).setHeader("Content-Type", "application/json").setBody("{}") }
        onMatching("POST /matches/[0-9a-f-]{36}/uploads") { ok(JSONObject().put("uploadId", "upload-1").put("nextOffset", 0).put("chunkSizeBytes", 4 * 1024 * 1024)) }
        on("PUT /uploads/upload-1/chunks") {
            chunkArrived.countDown()
            answerChunk.await(10, TimeUnit.SECONDS)
            ok(JSONObject().put("nextOffset", 10))
        }
        onMatching("DELETE /matches/[0-9a-f-]{36}") { MockResponse().setResponseCode(204) }
        val vm = viewModel(StoredState(matches = listOf(MatchRecord("U", "Ev", "Misafir", 1L, video(file.path)))))

        vm.resumeUpload("U")
        await("the first chunk on its way") { chunkArrived.count == 0L }
        var done = false
        vm.removeMatch("U") { done = true }
        settle()
        assertFalse("the app deleted while the upload was still sending", requests.any { it.startsWith("DELETE") })
        answerChunk.countDown()
        await("the removal") { done }

        val created = requests.single { it.startsWith("PUT /matches/") }.removePrefix("PUT /matches/")
        assertEquals("DELETE /matches/$created", requests.last())
        assertFalse(requests.any { it.endsWith("/complete") })
        assertTrue(vm.state.value.matches.isEmpty())
    }

    @Test fun aCorrectionNamesTheListOnScreenAndThenTheListAndTheCardsAreReadAgain() {
        var version = 3
        val sent = CopyOnWriteArrayList<JSONObject>()
        // The server sends the list in match order, as the real one does.
        val vm = withRallyList {
            if (version == 3) rallies(3, rally("r1", 1, 2_000, 9_000), rally("r2", 2, 15_000, 24_000))
            else rallies(version, rally("r1", 1, 2_000, 9_000), rally("r-new", 2, 10_000, 13_000), rally("r2", 3, 15_000, 24_000))
        }
        on("POST /analyses/J/rallies") { request ->
            sent += JSONObject(request.body.readUtf8())
            version = 4
            MockResponse().setResponseCode(201).setHeader("Content-Type", "application/json").setBody(corrected(job("J", "video-A", "NEEDS_REVIEW", open = 3), rally("r-new", 2, 10_000, 13_000)).toString())
        }
        on("GET /analyses/J/review-cards") { ok(deck(card("c1", 1, "HOME"), card("c-new", 2).put("rallyId", "r-new"), card("c2", 3))) }

        var done = false
        vm.addRally(10_000, 13_000) { done = true }
        await("the correction") { done }

        assertEquals(listOf(3, 10_000, 13_000), sent.single().let { listOf(it.getInt("expectedListVersion"), it.getInt("startMs"), it.getInt("endMs")) })
        assertEquals(listOf("POST /analyses/J/rallies", "GET /analyses/J/rallies", "GET /analyses/J/review-cards"), requests.toList())
        val ui = vm.analysis.value
        assertEquals(4, ui.rallies!!.version)
        assertEquals(listOf("r1", "r-new", "r2"), ui.rallies!!.rallies.map { it.id })
        assertFalse(ui.correcting)
        assertNull(ui.correctionProblem)
        // The rally that was just made asks its question first.
        assertEquals("c-new", ui.card!!.id)
        assertEquals(3, ui.job!!.reviewOpen)
    }

    @Test fun aRefusedCorrectionNamesItsCauseAndShowsTheListTheServerHolds() {
        var version = 3
        val vm = withRallyList { rallies(version, rally("r1", 1, 2_000, 9_000), rally("r2", 2, 15_000, 24_000)) }
        val overlap = MockResponse().setResponseCode(409).setHeader("Content-Type", "application/json").setBody(
            JSONObject().put("error", JSONObject().put("code", "RALLY_OVERLAP").put("message", "x").put("details", JSONObject().put("rallyId", "r2"))).toString())
        val refusals = listOf(
            { overlap } to CorrectionProblem.OVERLAP,
            { refuse(409, "RALLY_LIST_STALE") } to CorrectionProblem.STALE,
            { refuse(422, "INVALID_INTERVAL") } to CorrectionProblem.OUTSIDE,
            { refuse(422, "SPLIT_POINT_OUTSIDE") } to CorrectionProblem.OUTSIDE,
            { refuse(409, "LIST_NOT_READY") } to CorrectionProblem.NOT_READY,
            { refuse(409, "MOCK_LIST_NOT_CONFIRMABLE") } to CorrectionProblem.DEMO,
            { refuse(409, "JOB_NOT_REVIEWABLE") } to CorrectionProblem.REFUSED,
        )
        refusals.forEach { (answer, problem) ->
            on("POST /rallies/r1/move") { answer() }
            version++  // somebody else's change: the reload after the refusal must bring it to the screen
            vm.moveRally("r1", 2_000, 16_000) { fail("the app treated a refused correction as done") }
            await("the refusal") { vm.analysis.value.correctionProblem != null }
            assertEquals(problem, vm.analysis.value.correctionProblem)
            assertEquals(if (problem == CorrectionProblem.OVERLAP) 2 else null, vm.analysis.value.overlapsRally)
            assertEquals(version, vm.analysis.value.rallies!!.version)
            assertFalse(vm.analysis.value.correcting)
            vm.clearCorrectionProblem()
        }
        // A refusal is never followed by a second attempt, and the cards are not read again for it.
        assertEquals(refusals.size, requests.count { it == "POST /rallies/r1/move" })
        assertFalse(requests.any { it.endsWith("/review-cards") })
    }

    @Test fun aCorrectionWhoseAnswerWasLostIsFoundOnTheListAndIsNotSentAgain() {
        var version = 3
        val vm = withRallyList {
            if (version == 3) rallies(3, rally("r1", 1, 2_000, 9_000), rally("r2", 2, 15_000, 24_000))
            else rallies(version, rally("r1", 1, 2_000, 9_000), rally("r-cut", 2, 15_000, 20_000), rally("r2", 3, 20_000, 24_000))
        }
        on("POST /rallies/r2/split") {
            version = 4  // the server applied the split; only its answer never arrives
            MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST)
        }
        var done = false
        vm.splitRally("r2", 20_000) { done = true }
        await("the correction") { done }
        assertNull(vm.analysis.value.correctionProblem)
        assertEquals(4, vm.analysis.value.rallies!!.version)
        assertEquals(1, requests.count { it == "POST /rallies/r2/split" })
    }

    @Test fun aListThatMovedOnForAnotherReasonIsNotTakenForAnAppliedCorrection() {
        var version = 3
        val vm = withRallyList { rallies(version, rally("r1", 1, 2_000, 9_000), rally("r2", 2, 15_000, 24_000)) }
        on("POST /rallies/r2/split") {
            // The split never reached the list. A winner decision that was still on its way moved the version on.
            version = 4
            MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST)
        }
        vm.splitRally("r2", 20_000) { fail("the rally is still in one piece, so nothing was done") }
        await("the failure") { vm.analysis.value.correctionProblem != null }
        assertEquals(CorrectionProblem.UNREACHABLE, vm.analysis.value.correctionProblem)
        assertEquals("the screen still shows what the server holds", 4, vm.analysis.value.rallies!!.version)
    }

    @Test fun everyKindOfCorrectionIsRecognisedOnTheListWhenItsAnswerWasLost() {
        fun user(json: JSONObject) = json.put("origin", "USER")
        val before = listOf(rally("r1", 1, 2_000, 9_000), rally("r2", 2, 15_000, 24_000))
        val lost = { MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST) }
        // What the server holds after each correction, and the call that asked for it.
        val cases = listOf<Triple<String, List<JSONObject>, (AppViewModel, () -> Unit) -> Unit>>(
            Triple("POST /analyses/J/rallies", listOf(before[0], user(rally("r-new", 2, 10_000, 13_000)), rally("r2", 3, 15_000, 24_000))) { vm, done -> vm.addRally(10_000, 13_000, done) },
            Triple("POST /rallies/r2/move", listOf(before[0], rally("r2", 2, 15_000, 26_000))) { vm, done -> vm.moveRally("r2", 15_000, 26_000, done) },
            Triple("POST /rallies/r2/merge-previous", listOf(rally("r2", 1, 2_000, 24_000))) { vm, done -> vm.mergeWithPrevious("r2", done) },
        )
        cases.forEach { (request, after, call) ->
            var applied = false
            val vm = withRallyList { if (applied) rallies(4, *after.toTypedArray()) else rallies(3, *before.toTypedArray()) }
            on(request) { applied = true; lost() }
            var done = false
            call(vm) { done = true }
            await("$request to be found on the list") { done }
            assertNull(request, vm.analysis.value.correctionProblem)
            assertEquals(request, 1, requests.count { it == request })
        }
    }

    @Test fun aConfirmationWhoseAnswerWasLostIsFoundOnTheList() {
        var confirmed = false
        val vm = withRallyList { rallies(7, rally("r1", 1, 2_000, 9_000, "VERIFIED"), confirmed = confirmed) }
        on("POST /analyses/J/rally-list/confirm") {
            confirmed = true  // a confirmation does not move the list version, so only the list itself can tell
            MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST)
        }
        var done = false
        vm.confirmRallyList { done = true }
        await("the confirmation") { done }
        assertNull(vm.analysis.value.correctionProblem)
        assertTrue(vm.analysis.value.rallies!!.confirmed)
    }

    @Test fun aListThatIsNewerThanTheJobTheAppKnowsIsStillTheListToWorkOn() {
        var version = 3
        val vm = withRallyList(job("J", "video-A", "NEEDS_REVIEW").put("rallyListVersion", 3)) { rallies(version, rally("r1", 1, 2_000, 9_000)) }
        // A winner decision whose answer was lost: the server moved on, the job in memory did not.
        version = 4
        vm.loadRallies("A")
        await("the list of now") { vm.analysis.value.rallies!!.version == 4 }
        assertEquals(3, vm.analysis.value.job!!.rallyListVersion)
        assertEquals("a screen that waited for the job to catch up would wait for ever", 4, vm.analysis.value.currentRallies!!.version)
    }

    @Test fun anOlderListThatArrivesLateDoesNotReplaceANewerOne() {
        val vm = withRallyList { rallies(3, rally("r1", 1, 2_000, 9_000)) }
        val calls = java.util.concurrent.atomic.AtomicInteger()
        on("GET /analyses/J/rallies") {
            // The first read is slow and still shows version 4. The second one is fast and shows version 5.
            if (calls.incrementAndGet() == 1) ok(rallies(4, rally("r1", 1, 2_000, 9_000))).setBodyDelay(600, java.util.concurrent.TimeUnit.MILLISECONDS)
            else ok(rallies(5, rally("r1", 1, 2_000, 9_000)))
        }
        vm.loadRallies("A")
        vm.loadRallies("A")
        await("the newer list") { vm.analysis.value.rallies!!.version == 5 }
        // Long enough for the slow answer to arrive and be looked at.
        repeat(2) { settle() }
        assertEquals(5, vm.analysis.value.rallies!!.version)
    }

    @Test fun aCorrectionThatNeverReachedTheServerSaysSoAndIsNotCalledDone() {
        val vm = withRallyList { rallies(3, rally("r1", 1, 2_000, 9_000), rally("r2", 2, 15_000, 24_000)) }
        on("POST /rallies/r2/merge-previous") { MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST) }
        vm.mergeWithPrevious("r2") { fail("the list did not change, so nothing was done") }
        await("the failure") { vm.analysis.value.correctionProblem != null }
        assertEquals(CorrectionProblem.UNREACHABLE, vm.analysis.value.correctionProblem)
        assertEquals(3, vm.analysis.value.rallies!!.version)
        assertEquals(1, requests.count { it == "POST /rallies/r2/merge-previous" })
    }

    @Test fun aSecondTapIsNotSentWhileTheFirstCorrectionIsOnItsWay() {
        var version = 3
        val vm = withRallyList { rallies(version, rally("r1", 1, 2_000, 9_000), rally("r2", 2, 15_000, 24_000)) }
        on("POST /analyses/J/rallies") {
            version = 4
            MockResponse().setResponseCode(201).setHeader("Content-Type", "application/json").setBodyDelay(400, java.util.concurrent.TimeUnit.MILLISECONDS)
                .setBody(corrected(job("J", "video-A", "NEEDS_REVIEW", open = 3), rally("r-new", 2, 10_000, 13_000)).toString())
        }

        var done = 0
        vm.addRally(10_000, 13_000) { done++ }
        // The button is still on screen for a moment. A second tap must not add the rally twice.
        vm.addRally(10_000, 13_000) { done++ }
        assertTrue(vm.analysis.value.correcting)
        await("the correction") { done > 0 && !vm.analysis.value.correcting }

        assertEquals(1, done)
        assertEquals(1, requests.count { it == "POST /analyses/J/rallies" })
    }

    @Test fun confirmingTheListNamesTheVersionTheUserSaw() {
        var confirmed = false
        val sent = CopyOnWriteArrayList<JSONObject>()
        val vm = withRallyList { rallies(7, rally("r1", 1, 2_000, 9_000, "VERIFIED"), confirmed = confirmed) }
        on("POST /analyses/J/rally-list/confirm") { request ->
            sent += JSONObject(request.body.readUtf8())
            confirmed = true
            ok(JSONObject().put("job", job("J", "video-A", "COMPLETE", open = 0).put("rallyListVersion", 7).put("listConfirmed", true)).put("reportRevision", 9))
        }
        var done = false
        vm.confirmRallyList { done = true }
        await("the confirmation") { done }
        assertEquals(7, sent.single().getInt("expectedListVersion"))
        assertTrue(vm.analysis.value.rallies!!.confirmed)
        assertTrue(vm.analysis.value.job!!.listConfirmed)
    }

    /** A view model that follows job J of match A and has read these cards. */
    private fun withCards(cards: () -> JSONObject): AppViewModel {
        on("GET /videos/video-A/analyses/latest") { ok(job("J", "video-A", "NEEDS_REVIEW")) }
        on("GET /analyses/J/review-cards") { ok(cards()) }
        val vm = viewModel(StoredState(matches = listOf(accepted("A"))))
        vm.watchAnalysis("A", startIfMissing = false)
        await("the job") { vm.analysis.value.job != null }
        return vm
    }

    @Test fun theReviewOpensOnTheQuestionOfTheRallyItWasSentTo() {
        val vm = withCards { deck(card("c1", 1, "HOME"), card("c2", 2, "UNSURE"), card("c3", 3)) }

        vm.loadReview(atRally = "rally-c2")
        await("the cards") { vm.analysis.value.deck != null }
        // "Not sure" is an answer, so the first open card is another one. The review still opens where it was sent.
        assertEquals("c2", vm.analysis.value.card!!.id)

        // A rally that has left the list: the review falls back to the first card that needs an answer.
        vm.loadReview(atRally = "gone")
        await("the fallback") { vm.analysis.value.card?.id == "c3" }
    }

    @Test fun aReloadKeepsTheCardOnScreenWhereverTheNewCardsPutIt() {
        var cards = deck(card("c1", 1, "HOME"), card("c2", 2), card("c3", 3))
        val vm = withCards { cards }
        vm.loadReview()
        await("the cards") { vm.analysis.value.deck != null }
        vm.moveReview(1)
        assertEquals("c3", vm.analysis.value.card!!.id)

        // A rally was added before it, so the card on screen is now the fourth and not the third.
        cards = deck(card("c-new", 1), card("c1", 2, "HOME"), card("c2", 3), card("c3", 4))
        vm.loadReview(keepCursor = true)
        await("the new cards") { vm.analysis.value.deck!!.cards.size == 4 }
        assertEquals("c3", vm.analysis.value.card!!.id)

        // Its rally was merged away. The review does not jump to another rally's card on its own.
        cards = deck(card("c1", 1, "HOME"), card("c2", 2))
        vm.loadReview(keepCursor = true)
        await("the shorter deck") { vm.analysis.value.deck!!.cards.size == 2 }
        assertNull(vm.analysis.value.card)
    }

    @Test fun afterACorrectionTheReviewStaysOnTheCorrectedRallyEvenWhenItHasNothingToAsk() {
        var version = 3
        val vm = withRallyList { rallies(version, rally("rally-c1", 1, 2_000, 9_000), rally("rally-c2", 2, 15_000, 24_000), rally("rally-c3", 3, 30_000, 41_000)) }
        on("GET /analyses/J/review-cards") { ok(deck(card("c1", 1), card("c2", 2, "HOME"), card("c3", 3))) }
        vm.loadReview()
        await("the cards") { vm.analysis.value.deck != null }
        assertEquals("c1", vm.analysis.value.card!!.id)
        on("POST /rallies/rally-c2/move") {
            version = 4
            ok(corrected(job("J", "video-A", "NEEDS_REVIEW"), rally("rally-c2", 2, 14_000, 24_000)))
        }

        var done = false
        vm.moveRally("rally-c2", 14_000, 24_000) { done = true }
        await("the correction") { done }

        // A moved start reopens no question. The review shows the rally the user corrected, not the first open card.
        assertEquals("c2", vm.analysis.value.card!!.id)
    }

    @Test fun confirmingTheListLeavesTheReviewOnTheCardItShowed() {
        val vm = withRallyList { rallies(7, rally("rally-c1", 1, 2_000, 9_000, "VERIFIED"), rally("rally-c2", 2, 15_000, 24_000, "VERIFIED")) }
        on("GET /analyses/J/review-cards") { ok(deck(card("c1", 1, "HOME"), card("c2", 2, "AWAY"))) }
        vm.loadReview()
        await("the cards") { vm.analysis.value.deck != null }
        vm.moveReview(-1)
        assertEquals("c2", vm.analysis.value.card!!.id)
        on("POST /analyses/J/rally-list/confirm") { ok(JSONObject().put("job", job("J", "video-A", "COMPLETE", open = 0).put("rallyListVersion", 7).put("listConfirmed", true)).put("reportRevision", 9)) }

        var done = false
        vm.confirmRallyList { done = true }
        await("the confirmation") { done }

        assertEquals("c2", vm.analysis.value.card!!.id)
    }

    @Test fun aWinnerDecisionMakesTheListInMemoryOldUntilItIsReadAgain() {
        var version = 3
        val vm = withRallyList(job("J", "video-A", "NEEDS_REVIEW").put("rallyListVersion", 3)) { rallies(version, rally("rally-c1", 1, 2_000, 9_000)) }
        assertEquals(3, vm.analysis.value.currentRallies!!.version)
        on("POST /review-cards/c1/decisions") {
            version = 4
            ok(JSONObject().put("job", job("J", "video-A", "NEEDS_REVIEW", open = 1).put("rallyListVersion", 4)).put("reportRevision", 2))
        }
        vm.loadReview()
        await("the cards") { vm.analysis.value.deck != null }

        vm.answerReview("HOME")
        await("the decision") { vm.analysis.value.job!!.rallyListVersion == 4 && !vm.analysis.value.saving }

        // The server would refuse a correction of version 3, so no screen may offer one on it.
        assertEquals(3, vm.analysis.value.rallies!!.version)
        assertNull(vm.analysis.value.currentRallies)
        vm.loadRallies("A")
        await("the list of now") { vm.analysis.value.rallies!!.version == 4 }
        assertEquals(4, vm.analysis.value.currentRallies!!.version)
    }

    @Test fun askingForTheListAgainStartsWithTheJobWhenThereIsNone() {
        val vm = viewModel(StoredState(matches = listOf(accepted("A"))))
        on("GET /analyses/J/rallies") { ok(rallies(1, rally("r1", 1, 2_000, 9_000))) }

        // The first read of the job failed: without a job there is no list to ask for.
        vm.retryRallies("A")
        await("the question for the job") { requests.contains("GET /videos/video-A/analyses/latest") }
        assertFalse(requests.any { it.endsWith("/rallies") })

        on("GET /videos/video-A/analyses/latest") { ok(job("J", "video-A", "NEEDS_REVIEW")) }
        vm.retryRallies("A")
        await("the job") { vm.analysis.value.job != null }
        vm.retryRallies("A")
        await("the list") { vm.analysis.value.rallies != null }
        assertEquals(RalliesLoad.READY, vm.analysis.value.ralliesLoad)
    }

    @Test fun aListThatCanNotBeReadSaysSoAndKeepsTheListItHad() {
        var reachable = true
        val vm = withRallyList { rallies(3, rally("r1", 1, 2_000, 9_000)) }
        on("GET /analyses/J/rallies") { if (reachable) ok(rallies(4, rally("r1", 1, 2_000, 9_000))) else MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST) }

        reachable = false
        vm.loadRallies("A")
        await("the failed read") { vm.analysis.value.ralliesLoad == RalliesLoad.UNREACHABLE }
        assertEquals(3, vm.analysis.value.rallies!!.version)

        reachable = true
        vm.loadRallies("A")
        await("the list") { vm.analysis.value.rallies!!.version == 4 }
        assertEquals(RalliesLoad.READY, vm.analysis.value.ralliesLoad)
    }
}
