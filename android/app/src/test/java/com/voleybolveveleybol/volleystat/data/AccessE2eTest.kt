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
import org.junit.Assert.fail
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
 * account that owns it, counts the declared size of every upload against the account's limit, and
 * deletes an account's matches with the account.
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

    private fun refused(status: Int, code: String, what: String, call: () -> Unit) {
        try {
            call()
            fail("the server answered $what instead of $status $code")
        } catch (error: ApiException) {
            assertEquals(what, code, error.code)
            assertEquals(what, status, error.status)
        }
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
     * The server counts the declared size of every upload, so two declared 4 GiB uploads fill an account
     * opened with a code (8 GiB) without one byte leaving the phone. The next upload is refused with the
     * server's numbers, which the status screen words (UploadRefusal).
     */
    @Test fun aDeclaredSizeOverTheQuotaIsRefusedWithTheServersNumbers() {
        val (_, owner) = account()
        val matchId = UUID.randomUUID().toString()
        owner.putMatch(matchId, MatchInfo("E2E A Takımı", "E2E B Takımı"))
        repeat(2) { declaration ->
            try {
                owner.createUpload(matchId, "e2e.mp4", 4 * gib, "video/mp4", "0".repeat(64))
            } catch (error: ApiException) {
                fail(
                    "declaration ${declaration + 1} of 4 GiB was refused with ${error.status} ${error.code}. Each declaration needs " +
                        "4 GiB of free disk plus the server's floor (VOLLEYSTAT_DISK_FLOOR_GIB, 1 GiB in CI) where the server stores its files",
                )
            }
        }
        val failure = try {
            upload(owner)
            throw AssertionError("the server accepted an upload over the account's limit")
        } catch (error: UploadException) {
            error
        }
        assertEquals(UploadFailure.SERVER_REFUSED, failure.failure)
        assertEquals("QUOTA_EXCEEDED", failure.serverCode)
        val details = (failure.cause as ApiException).details
        assertEquals("PERSONAL", details.getString("scope"))
        assertEquals(8 * gib, details.getLong("usedBytes"))
        assertEquals(8 * gib, details.getLong("limitBytes"))
        assertEquals(UploadRefusal.PersonalQuota(8 * gib, 8 * gib), UploadRefusal.of(failure))
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
