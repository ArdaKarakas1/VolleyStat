package com.voleybolveveleybol.volleystat.data

import com.voleybolveveleybol.volleystat.model.AnalysisSummary
import com.voleybolveveleybol.volleystat.model.ReviewCard
import com.voleybolveveleybol.volleystat.model.StatValue
import com.voleybolveveleybol.volleystat.model.UploadSession
import com.voleybolveveleybol.volleystat.model.VideoCheck
import org.junit.Assert.assertEquals
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
        ApiClient(url).deleteVideo(check.videoId)
    }

    @Test fun anInterruptedUploadResumesOnTheRealServer() {
        assumeTrue(url != null && clip != null && clip.isFile && clip.length() > 5L * 1024 * 1024)
        val (partial, none) = upload(clip!!, stopAfterChunks = 1)
        assertEquals(null, none)
        assertEquals(4L * 1024 * 1024, ApiClient(url!!).uploadStatus(partial.uploadId!!).nextOffset)
        val (finished, check) = upload(clip, partial)
        assertEquals(VideoCheck.READY, check!!.status)
        assertEquals(partial.uploadId, finished.uploadId)
        ApiClient(url).deleteVideo(check.videoId)
    }

    @Test fun aTruncatedClipIsQuarantinedNotAccepted() {
        assumeTrue(url != null && clip != null && clip.isFile)
        val half = File.createTempFile("half", ".mp4").apply { writeBytes(clip!!.readBytes().copyOf((clip.length() / 2).toInt())) }
        try {
            val (_, check) = upload(half)
            assertEquals(VideoCheck.QUARANTINED, check!!.status)
            assertEquals("VIDEO_CORRUPT", check.rejectCode)
            ApiClient(url!!).deleteVideo(check.videoId)
        } finally {
            half.delete()
        }
    }

    @Test fun aDemoAnalysisIsReviewedAndTheReportFollowsExactlyThoseAnswers() {
        assumeTrue(url != null && clip != null && clip.isFile)
        val api = ApiClient(url!!)
        val (session, check) = upload(clip!!)
        val videoId = check!!.videoId
        val matchId = session.serverMatchId!!
        try {
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
            api.deleteVideo(videoId)
        }
    }
}
