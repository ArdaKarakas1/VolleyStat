package com.voleybolveveleybol.volleystat.data

import com.voleybolveveleybol.volleystat.model.AnalysisJob
import com.voleybolveveleybol.volleystat.model.AnalysisSummary
import com.voleybolveveleybol.volleystat.model.Rally
import com.voleybolveveleybol.volleystat.model.ReviewCard
import com.voleybolveveleybol.volleystat.model.StatValue
import com.voleybolveveleybol.volleystat.model.UploadSession
import com.voleybolveveleybol.volleystat.model.VideoCheck
import com.voleybolveveleybol.volleystat.model.gaps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Drives the app's real upload client against a running VolleyStat backend.
 *
 * Skipped unless both variables are set:
 *   VOLLEYSTAT_E2E_URL   base URL, for example http://127.0.0.1:8000/v1
 *   VOLLEYSTAT_E2E_VIDEO path of a SYNTHETIC test clip (ffmpeg test pattern)
 *
 * The review test also needs the server to run with the demo analyzer
 * (python -m app --analyzer demo) and is skipped otherwise.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackendE2eTest {
    private val url = System.getenv("VOLLEYSTAT_E2E_URL")
    private val clip = System.getenv("VOLLEYSTAT_E2E_VIDEO")?.let(::File)

    private fun upload(file: File, session: UploadSession = UploadSession(), stopAfterChunks: Int = Int.MAX_VALUE): Pair<UploadSession, VideoCheck?> {
        var saved = session
        var chunks = 0
        val check = try {
            UploadEngine(ApiClient(url!!)).run(
                session, MatchInfo("E2E A Takımı", "E2E B Takımı", name = "E2E", playedOn = "2026-11-12", kind = "TRAINING"), "e2e.mp4", "video/mp4",
                VideoBytes(file.length()) { file.inputStream() },
                onSession = { if (it.sentBytes > saved.sentBytes) chunks++; saved = it },
                onProgress = { _, _, _ -> },
                cancelled = { chunks >= stopAfterChunks },
            )
        } catch (stopped: UploadCancelled) {
            null
        }
        return saved to check
    }

    @Test fun aRealClipIsAcceptedAndDescribedByTheServer() {
        assumeTrue(url != null && clip != null && clip.isFile)
        val (session, check) = upload(clip!!)
        assertEquals(VideoCheck.READY, check!!.status)
        assertNotNull(check.width)
        assertNotNull(check.durationMs)
        assertEquals("h264", check.codec)
        assertEquals(clip.length(), session.sentBytes)
        // The server keeps the video and still has no model: analysis must be refused honestly.
        assertEquals(VideoCheck.READY, ApiClient(url!!).video(check.videoId).status)
        ApiClient(url).deleteMatch(session.serverMatchId!!)
    }

    @Test fun anInterruptedUploadResumesOnTheRealServer() {
        assumeTrue(url != null && clip != null && clip.isFile && clip.length() > 5L * 1024 * 1024)
        val (partial, none) = upload(clip!!, stopAfterChunks = 1)
        assertEquals(null, none)
        assertEquals(4L * 1024 * 1024, ApiClient(url!!).uploadStatus(partial.uploadId!!).nextOffset)
        val (finished, check) = upload(clip, partial)
        assertEquals(VideoCheck.READY, check!!.status)
        assertEquals(partial.uploadId, finished.uploadId)
        ApiClient(url).deleteMatch(finished.serverMatchId!!)
    }

    @Test fun aTruncatedClipIsQuarantinedNotAccepted() {
        assumeTrue(url != null && clip != null && clip.isFile)
        val half = File.createTempFile("half", ".mp4").apply { writeBytes(clip!!.readBytes().copyOf((clip.length() / 2).toInt())) }
        try {
            val (session, check) = upload(half)
            assertEquals(VideoCheck.QUARANTINED, check!!.status)
            assertEquals("VIDEO_CORRUPT", check.rejectCode)
            ApiClient(url!!).deleteMatch(session.serverMatchId!!)
        } finally {
            half.delete()
        }
    }

    /** Starts the demo analysis of a video and waits for its rallies. Skips the test on a server without the demo analyzer. */
    private fun demoJob(api: ApiClient, videoId: String): AnalysisJob {
        var job = try {
            api.startAnalysis(videoId, "e2e-${System.nanoTime()}")
        } catch (error: ApiException) {
            assumeTrue("the server runs without an analyzer", error.code != "MODEL_NOT_CONFIGURED")
            throw error
        }
        assumeTrue("this test needs the demo analyzer", job.analyzerId == "demo")
        assertTrue(job.mock)
        val deadline = System.nanoTime() + 60_000_000_000
        while (job.state == AnalysisSummary.QUEUED || job.state == AnalysisSummary.PROCESSING) {
            assertTrue("the job did not finish within a minute", System.nanoTime() < deadline)
            Thread.sleep(200)
            job = api.latestAnalysis(videoId)!!
        }
        assertEquals(AnalysisSummary.NEEDS_REVIEW, job.state)
        return job
    }

    private fun refused(code: String, call: () -> Unit): ApiException = try {
        call()
        throw AssertionError("the server accepted what it has to refuse with $code")
    } catch (error: ApiException) {
        assertEquals(code, error.code)
        error
    }

    @Test fun aDemoAnalysisIsReviewedAndTheReportFollowsExactlyThoseAnswers() {
        assumeTrue(url != null && clip != null && clip.isFile)
        val api = ApiClient(url!!)
        val (session, check) = upload(clip!!)
        val videoId = check!!.videoId
        val matchId = session.serverMatchId!!
        try {
            var job = demoJob(api, videoId)

            val deck = api.reviewCards(job.id, "SCORE")
            val total = deck.cards.size
            assertTrue(total >= 1)
            assertEquals(job.rallies, total)
            // The demo analyzer invents intervals only: no card may carry a suggested winner.
            assertTrue(deck.cards.all { it.open && it.suggestedValue == null && it.questionType == ReviewCard.RALLY_WINNER })

            // Before any decision nothing is known, and nothing may read as zero.
            val draft = api.report(matchId)!!
            assertTrue(draft.mock)
            assertEquals("DRAFT", draft.verificationStatus)
            assertEquals(StatValue(null, StatValue.UNKNOWN), draft.home.points)
            assertEquals(StatValue(null, StatValue.UNKNOWN), draft.away.points)
            assertNull(draft.setsWonHome)
            assertTrue(draft.sets.isEmpty())

            // First rally to the home side, every other rally to the away side.
            deck.cards.forEachIndexed { index, card ->
                job = api.decide(card.id, if (index == 0) ReviewCard.HOME else ReviewCard.AWAY, card.version, "e2e-$index-${card.id}")
            }
            assertEquals(AnalysisSummary.COMPLETE, job.state)
            assertEquals(0, job.reviewOpen)

            val scored = api.report(matchId)!!
            assertEquals("VERIFIED", scored.verificationStatus)
            assertEquals(total, scored.ralliesVerified)
            // The demo analyzer may have missed rallies and the review can not add one, so the
            // counts are lower bounds even with every card answered.
            assertTrue(scored.ralliesMayBeMissing)
            assertEquals(StatValue(1, StatValue.PARTIAL), scored.home.points)
            assertEquals(StatValue(total - 1, StatValue.PARTIAL), scored.away.points)
            assertNull(scored.scoreStoppedAtRally)
            assertEquals(listOf(1 to total - 1), scored.sets.map { it.home to it.away })
            assertTrue(scored.sets.none { it.complete })
            // Nobody said how the points were scored, so the kinds of points stay unknown.
            assertEquals(StatValue(null, StatValue.UNKNOWN), scored.home.attackPoints)
            assertTrue(scored.players.isEmpty())

            // The optional detail questions: the home point was an attack by number 7.
            val action = api.reviewCards(job.id, "ALL").cards.first { it.questionType == ReviewCard.TERMINAL_ACTION && it.rallyId == deck.cards[0].rallyId }
            api.decide(action.id, "ATTACK", action.version, "e2e-action-${action.id}")
            val scorer = api.reviewCards(job.id, "ALL").cards.first { it.questionType == ReviewCard.SCORER && it.rallyId == deck.cards[0].rallyId }
            api.decide(scorer.id, ReviewCard.jersey(7), scorer.version, "e2e-scorer-${scorer.id}")
            val detailed = api.report(matchId)!!
            assertEquals(1, detailed.home.attackPoints.value)
            assertEquals(1, detailed.players.single { it.home && it.jersey == 7 }.attackPoints)
            assertEquals(0, detailed.unattributedHome)
            assertEquals(total - 1, detailed.unattributedAway)

            // A decision made against an old version of a card is refused, not silently applied.
            try {
                api.decide(deck.cards[0].id, ReviewCard.AWAY, deck.cards[0].version, "e2e-stale-${System.nanoTime()}")
                fail("the server accepted a stale version")
            } catch (error: ApiException) {
                assertEquals("REVIEW_STALE_VERSION", error.code)
            }
        } finally {
            api.deleteMatch(matchId)
        }
    }

    @Test fun theRallyListIsCorrectedOnTheRealServerAndADemoListCanNotBeConfirmed() {
        assumeTrue(url != null && clip != null && clip.isFile)
        val api = ApiClient(url!!)
        val (session, check) = upload(clip!!)
        val matchId = session.serverMatchId!!
        try {
            val job = demoJob(api, check!!.videoId)
            val first = api.rallies(job.id)
            assertEquals(job.rallyListVersion, first.version)
            assertEquals(check.durationMs, first.videoDurationMs)
            assertEquals(job.rallies, first.rallies.size)
            assertTrue(first.rallies.all { it.fromAnalyzer && it.status == Rally.PROPOSED && it.scoreAfter == null })
            assertFalse(first.confirmed)
            assertTrue("no winner, so no set score yet", first.sets.isEmpty())

            // A rally the analyzer missed, inside the longest pause of the list.
            val pause = gaps(first).maxBy { it.windowEndMs - it.windowStartMs }
            val added = api.addRally(job.id, pause.windowStartMs + 100, pause.windowStartMs + 900, first.version)
            val made = added.affected.single()
            assertFalse(made.fromAnalyzer)
            assertEquals(listOf(pause.windowStartMs + 100, pause.windowStartMs + 900), listOf(made.startMs, made.endMs))
            assertEquals(job.reviewOpen + 1, added.job.reviewOpen)
            assertEquals(first.version + 1, added.job.rallyListVersion)
            var list = api.rallies(job.id)
            assertEquals(first.rallies.size + 1, list.rallies.size)
            assertEquals("the list stays in match order", list.rallies.map { it.startMs }.sorted(), list.rallies.map { it.startMs })
            assertEquals((1..list.rallies.size).toList(), list.rallies.map { it.sequence })

            // A correction made on the list as it was before is refused, so none applies twice.
            refused("RALLY_LIST_STALE") { api.addRally(job.id, pause.windowStartMs + 100, pause.windowStartMs + 900, first.version) }
            val blocked = refused("RALLY_OVERLAP") { api.addRally(job.id, made.startMs + 100, made.endMs + 100, list.version) }
            assertEquals(made.id, blocked.details.optString("rallyId"))
            assertEquals("a refused correction leaves the list as it was", list, api.rallies(job.id))

            val moved = api.moveRally(made.id, made.startMs, made.endMs - 200, list.version).affected.single()
            assertEquals(made.endMs - 200, moved.endMs)

            // Cutting an analyzer rally in two: the later part stays that rally, the earlier part is the user's.
            list = api.rallies(job.id)
            val whole = list.rallies.first { it.fromAnalyzer }
            val middle = (whole.startMs + whole.endMs) / 2
            val cut = api.splitRally(whole.id, middle, list.version)
            assertEquals(listOf(false to (whole.startMs to middle), true to (middle to whole.endMs)), cut.affected.map { it.fromAnalyzer to (it.startMs to it.endMs) })
            assertEquals(whole.id, cut.affected.last().id)
            assertEquals(first.rallies.size + 2, api.rallies(job.id).rallies.size)

            // Joining the two parts again removes the earlier one.
            val joined = api.mergeWithPrevious(whole.id, cut.job.rallyListVersion)
            assertEquals(listOf(cut.affected.first().id), joined.removed)
            assertEquals(whole.startMs to whole.endMs, joined.affected.single().let { it.startMs to it.endMs })

            // Every rally gets a winner. The invented intervals of a demo job still can not be confirmed.
            var done = joined.job
            api.reviewCards(job.id, "SCORE").cards.forEach { card -> done = api.decide(card.id, ReviewCard.HOME, card.version, "e2e-fix-${card.id}") }
            assertEquals(AnalysisSummary.COMPLETE, done.state)
            list = api.rallies(job.id)
            assertEquals(done.rallyListVersion, list.version)
            assertEquals("the list carries the set scores of its own version", listOf(Triple(1, list.rallies.size, 0)), list.sets.map { Triple(it.number, it.home, it.away) })
            refused("MOCK_LIST_NOT_CONFIRMABLE") { api.confirmRallyList(job.id, list.version) }

            val report = api.report(matchId)!!
            assertTrue(report.ralliesMayBeMissing)
            assertFalse(report.listConfirmed)
            assertEquals("add, move, split and merge", 4, report.corrections)
            assertEquals(1, report.ralliesUserCreated)
            assertEquals(StatValue(list.rallies.size, StatValue.PARTIAL), report.home.points)
        } finally {
            api.deleteMatch(matchId)
        }
    }

    @Test fun aDeletedMatchLeavesNothingOnTheServer() {
        assumeTrue(url != null && clip != null && clip.isFile)
        val api = ApiClient(url!!)
        val (session, check) = upload(clip!!)
        api.deleteMatch(session.serverMatchId!!)
        // The video, the upload and the match record are all gone, not only the video.
        listOf<Pair<String, () -> Unit>>(
            "VIDEO_NOT_FOUND" to { api.video(check!!.videoId) },
            "UPLOAD_NOT_FOUND" to { api.uploadStatus(session.uploadId!!) },
            "MATCH_NOT_FOUND" to { api.deleteMatch(session.serverMatchId!!) },
        ).forEach { (code, call) ->
            try {
                call()
                fail("the server still answers for what the deleted match owned: $code")
            } catch (error: ApiException) {
                assertEquals(code, error.code)
            }
        }
    }
}
