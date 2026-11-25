package com.voleybolveveleybol.volleystat.data

import com.voleybolveveleybol.volleystat.TestAccounts
import com.voleybolveveleybol.volleystat.UploadRefusal
import com.voleybolveveleybol.volleystat.model.AnalysisJob
import com.voleybolveveleybol.volleystat.model.AnalysisSummary
import com.voleybolveveleybol.volleystat.model.MatchRules
import com.voleybolveveleybol.volleystat.model.UploadSession
import com.voleybolveveleybol.volleystat.model.VideoCheck
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

/**
 * Slice B3 against a running VolleyStat backend (server 0.9): the server shows a match only to the
 * account that owns it, counts the declared size of every upload against the account's limit and its
 * one unfinished upload, and deletes an account's matches with the account.
 *
 * Skipped unless VOLLEYSTAT_E2E_URL and VOLLEYSTAT_E2E_VIDEO are set (see BackendE2eTest). The server
 * must run with VOLLEYSTAT_SIGNUP=open and the demo analyzer, as CI starts it. Each test opens its own
 * e2e_ accounts and deletes them at the end.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AccessE2eTest {
    private val url = System.getenv("VOLLEYSTAT_E2E_URL")
    private val clip = System.getenv("VOLLEYSTAT_E2E_VIDEO")?.let(::File)
    private val opened = mutableListOf<Pair<SignedIn, String>>()
    private val gib = 1024L * 1024 * 1024

    @Before fun needsABackendAndAClip() = assumeTrue("needs VOLLEYSTAT_E2E_URL and VOLLEYSTAT_E2E_VIDEO", url != null && clip != null && clip.isFile)

    @After fun deleteTheAccounts() = opened.forEach { (account, password) -> TestAccounts.deleteOnBackend(url!!, account, password) }

    /** A fresh e2e_ account and a client with its session. */
    private fun account(): Pair<SignedIn, ApiClient> {
        val (account, password) = TestAccounts.registerOnBackend(url!!)
        opened += account to password
        return account to ApiClient(url, token = account.token)
    }

    private fun upload(api: ApiClient, session: UploadSession = UploadSession()): Pair<UploadSession, VideoCheck> {
        var saved = session
        val check = UploadEngine(api).run(
            session, MatchInfo("E2E A Takımı", "E2E B Takımı", name = "E2E erişim", playedOn = "2026-11-18"), "e2e.mp4", "video/mp4",
            VideoBytes(clip!!.length()) { clip.inputStream() },
            onSession = { saved = it }, onProgress = { _, _, _ -> }, cancelled = { false },
        )
        return saved to check
    }

    /** Starts the demo analysis and waits for its rallies. Skips the test on a server without the demo analyzer. */
    private fun demoJob(api: ApiClient, videoId: String): AnalysisJob {
        var job = try {
            api.startAnalysis(videoId, "e2e-${UUID.randomUUID()}")
        } catch (error: ApiException) {
            assumeTrue("the server runs without an analyzer", error.code != "MODEL_NOT_CONFIGURED")
            throw error
        }
        assumeTrue("this test needs the demo analyzer", job.analyzerId == "demo")
        val deadline = System.nanoTime() + 60_000_000_000
        while (job.state == AnalysisSummary.QUEUED || job.state == AnalysisSummary.PROCESSING) {
            assertTrue("the job did not finish within a minute", System.nanoTime() < deadline)
            Thread.sleep(200)
            job = api.latestAnalysis(videoId)!!
        }
        assertEquals(AnalysisSummary.NEEDS_REVIEW, job.state)
        return job
    }

    private fun refused(status: Int, code: String, what: String, call: () -> Unit): ApiException {
        try {
            call()
        } catch (error: ApiException) {
            assertEquals(what, code, error.code)
            assertEquals(what, status, error.status)
            return error
        }
        throw AssertionError("the server answered $what instead of $status $code")
    }

    @Test fun anotherAccountGetsNotAMemberOnEveryCallOfTheAppAndNothingChanges() {
        val (_, owner) = account()
        val (session, check) = upload(owner)
        val matchId = session.serverMatchId!!
        val job = demoJob(owner, check.videoId)
        val card = owner.reviewCards(job.id, "SCORE").cards.first()
        val list = owner.rallies(job.id)
        val rally = list.rallies.first()
        val report = owner.report(matchId)
        assertNotNull(report)

        val (_, stranger) = account()
        val calls = listOf<Pair<String, () -> Unit>>(
            "putMatch" to { stranger.putMatch(matchId, MatchInfo("Başka", "Takım")) },
            "createUpload" to { stranger.createUpload(matchId, "e2e.mp4", clip!!.length(), "video/mp4", session.sha256!!) },
            "uploadStatus" to { stranger.uploadStatus(session.uploadId!!) },
            "video" to { stranger.video(check.videoId) },
            "latestAnalysis" to { stranger.latestAnalysis(check.videoId) },
            "startAnalysis" to { stranger.startAnalysis(check.videoId, "e2e-${UUID.randomUUID()}") },
            "reviewCards" to { stranger.reviewCards(job.id, "SCORE") },
            "decide" to { stranger.decide(card.id, "HOME", card.version, UUID.randomUUID().toString()) },
            "rallies" to { stranger.rallies(job.id) },
            "moveRally" to { stranger.moveRally(rally.id, rally.startMs, rally.endMs - 100, list.version) },
            "confirmRallyList" to { stranger.confirmRallyList(job.id, list.version) },
            "putMatchRules" to { stranger.putMatchRules(matchId, MatchRules(2, 21, 15), list.rules) },
            "report" to { stranger.report(matchId) },
            "deleteMatch" to { stranger.deleteMatch(matchId) },
        )
        calls.forEach { (what, call) -> refused(403, "NOT_A_MEMBER", what, call) }

        // Nothing the other account sent changed the owner's match.
        assertEquals(list, owner.rallies(job.id))
        assertEquals(report, owner.report(matchId))
        assertEquals(VideoCheck.READY, owner.video(check.videoId).status)
        // Without a session the server answers 401 and names nothing of the match.
        refused(401, "AUTH_REQUIRED", "an anonymous video") { ApiClient(url!!).video(check.videoId) }
    }

    /**
     * The server counts the declared size of every upload. CI starts the server with VOLLEYSTAT_SIGNUP=open, so the
     * test's account opened without an operator code: 4 GiB of personal video and one unfinished upload. After the
     * clip, a 4 GiB declaration passes the limit and the server refuses it with its numbers, which the status screen
     * words (UploadRefusal). A 1 GiB declaration fits and stays unfinished; the same declaration again names the same
     * upload. The next upload of another file meets the limit of one unfinished upload, which the screen words with
     * `upload_one_unfinished`.
     */
    @Test fun aSecondUnfinishedUploadIsRefusedWithTheServersNumbers() {
        val (_, owner) = account()
        val (_, check) = upload(owner)
        assertEquals(VideoCheck.READY, check.status)
        val clipBytes = clip!!.length()

        val second = UUID.randomUUID().toString()
        owner.putMatch(second, MatchInfo("E2E A Takımı", "E2E B Takımı"))
        // The server answers the account's limit before it looks at its disk, so this needs no free space.
        val tooLarge = refused(409, "QUOTA_EXCEEDED", "a 4 GiB declaration") {
            owner.createUpload(second, "e2e.mp4", 4 * gib, "video/mp4", "0".repeat(64))
        }
        assertEquals("PERSONAL", tooLarge.details.getString("scope"))
        assertTrue("a size refusal names no reason", tooLarge.details.isNull("reason"))
        assertEquals(clipBytes, tooLarge.details.getLong("usedBytes"))
        assertEquals(4 * gib, tooLarge.details.getLong("limitBytes"))
        assertEquals(UploadRefusal.PersonalQuota(clipBytes, 4 * gib), UploadRefusal.of(UploadException(UploadFailure.SERVER_REFUSED, tooLarge.code, tooLarge)))
        val held = try {
            owner.createUpload(second, "e2e.mp4", gib, "video/mp4", "0".repeat(64))
        } catch (error: ApiException) {
            throw AssertionError(
                "the 1 GiB declaration was refused with ${error.status} ${error.code}. It needs 1 GiB of free disk plus the " +
                    "server's floor (VOLLEYSTAT_DISK_FLOOR_GIB, 1 GiB in CI) where the server stores its files",
                error,
            )
        }
        // A phone that lost the answer asks again for the same file (review finding Q1). The server names the upload it
        // already holds, from its first byte, instead of opening a second one: the account's one unfinished upload is not
        // spent, and the size counts once (usedBytes below).
        val again = owner.createUpload(second, "e2e.mp4", gib, "video/mp4", "0".repeat(64))
        assertEquals(held.uploadId, again.uploadId)
        assertEquals(0L, again.nextOffset)

        val failure = try {
            upload(owner)
            throw AssertionError("the server accepted a second unfinished upload of an account without an operator code")
        } catch (error: UploadException) {
            error
        }
        assertEquals(UploadFailure.SERVER_REFUSED, failure.failure)
        assertEquals("QUOTA_EXCEEDED", failure.serverCode)
        val details = (failure.cause as ApiException).details
        assertEquals("PERSONAL", details.getString("scope"))
        assertEquals("UNFINISHED_UPLOAD", details.getString("reason"))
        assertEquals(clipBytes + gib, details.getLong("usedBytes"))
        assertEquals(4 * gib, details.getLong("limitBytes"))
        assertEquals("the screen words this refusal with upload_one_unfinished", UploadRefusal.OneUnfinished, UploadRefusal.of(failure))
    }

    @Test fun deletingTheAccountDeletesItsMatchesOnTheServer() {
        val (owner, ownerApi) = account()
        val (session, check) = upload(ownerApi)
        val matchId = session.serverMatchId!!
        val (_, stranger) = account()
        // While the match exists the other account gets 403, never 404.
        refused(403, "NOT_A_MEMBER", "video before") { stranger.video(check.videoId) }
        refused(403, "NOT_A_MEMBER", "deleteMatch before") { stranger.deleteMatch(matchId) }

        val password = opened.first { it.first.user.id == owner.user.id }.second
        ownerApi.deleteAccount(password)
        opened.removeAll { it.first.user.id == owner.user.id }

        refused(404, "VIDEO_NOT_FOUND", "video after") { stranger.video(check.videoId) }
        refused(404, "MATCH_NOT_FOUND", "deleteMatch after") { stranger.deleteMatch(matchId) }
    }
}
