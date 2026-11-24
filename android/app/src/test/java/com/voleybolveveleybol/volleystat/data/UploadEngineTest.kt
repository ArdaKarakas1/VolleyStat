package com.voleybolveveleybol.volleystat.data

import com.voleybolveveleybol.volleystat.model.MatchRules
import com.voleybolveveleybol.volleystat.model.UploadSession
import com.voleybolveveleybol.volleystat.model.VideoCheck
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

/** Runs under Robolectric only because org.json is an Android class. Bytes are generated, not real video. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UploadEngineTest {
    private val server = FakeServer()
    private val data = ByteArray(9 * 1024 * 1024 + 123) { (it * 31 % 251).toByte() }  // three chunks
    private var opens = 0
    private val bytes = VideoBytes(data.size.toLong()) { opens++; ByteArrayInputStream(data) }
    private val sessions = mutableListOf<UploadSession>()
    private val phases = mutableListOf<UploadPhase>()

    @After fun stop() = server.close()

    private fun run(initial: UploadSession = UploadSession(), cancelled: () -> Boolean = { false }, rules: MatchRules = MatchRules.FIVB): VideoCheck =
        UploadEngine(ApiClient(server.baseUrl)).run(
            initial, MatchInfo("A Takımı", "B Takımı", rules = rules), "video.mp4", "video/mp4", bytes,
            onSession = { sessions += it },
            onProgress = { phase, _, _ -> if (phases.lastOrNull() != phase) phases += phase },
            cancelled = cancelled,
        )

    @Test fun sendsEveryByteOnceAndReportsTheFileCheck() {
        val check = run()
        assertArrayEquals(data, server.stored.toByteArray())
        assertEquals(1, server.matchesCreated)
        assertEquals(3, server.chunksReceived)
        assertEquals(listOf(UploadPhase.CONNECTING, UploadPhase.HASHING, UploadPhase.SENDING, UploadPhase.VERIFYING), phases)
        assertEquals(VideoCheck.READY, check.status)
        assertEquals(640, check.width)
        assertEquals(listOf("LOW_RESOLUTION"), check.warnings)
        assertEquals(sha256(data), sessions.last().sha256)
        assertEquals(data.size.toLong(), sessions.last().sentBytes)
    }

    @Test fun aFailedRunContinuesFromTheServerOffsetWithoutASecondMatch() {
        server.failChunk = 2
        val failure = assertThrows(UploadException::class.java) { run() }
        assertEquals(UploadFailure.SERVER_REFUSED, failure.failure)
        val saved = sessions.last()
        assertNotNull(saved.uploadId)
        assertEquals(4L * 1024 * 1024, saved.sentBytes)

        val check = run(saved)
        assertEquals(VideoCheck.READY, check.status)
        assertArrayEquals(data, server.stored.toByteArray())
        assertEquals("one match and one upload session in total", 1, server.matchesCreated)
        assertEquals(1, server.uploadsCreated)
    }

    @Test fun theMatchIdIsSavedBeforeTheServerHearsOfItSoALostAnswerLeavesNoMatchThePhoneCanNotName() {
        server.loseMatchAnswers = 1
        val failure = assertThrows(UploadException::class.java) { run() }
        assertEquals(UploadFailure.SERVER_UNREACHABLE, failure.failure)
        // The server stored the match and the phone never saw the answer, yet the phone holds the id.
        val saved = sessions.last()
        assertEquals(server.matchIds.single(), saved.serverMatchId)

        val check = run(saved)
        assertEquals(VideoCheck.READY, check.status)
        assertEquals("the repeat found the match and created no second one", 1, server.matchesCreated)
        assertEquals(saved.serverMatchId, sessions.last().serverMatchId)
    }

    @Test fun theMatchIsCreatedWithItsRule() {
        run(rules = MatchRules(2, 21, 15))
        // The whole rule, under the names of the contract and nothing more: the server refuses any other key.
        val sent = server.matchBodies.single().getJSONObject("rules")
        assertEquals(setOf("setsToWin", "setTarget", "decidingSetTarget"), sent.keys().asSequence().toSet())
        assertEquals(listOf(2, 21, 15), listOf(sent.getInt("setsToWin"), sent.getInt("setTarget"), sent.getInt("decidingSetTarget")))
    }

    @Test fun aMatchRequestRepeatedAfterALostAnswerCarriesTheSameRule() {
        server.loseMatchAnswers = 1
        assertThrows(UploadException::class.java) { run(rules = MatchRules(2, 15, 15)) }
        run(sessions.last(), rules = MatchRules(2, 15, 15))
        assertEquals(2, server.matchBodies.size)
        assertEquals(server.matchBodies[0].getJSONObject("rules").toString(), server.matchBodies[1].getJSONObject("rules").toString())
        assertEquals(15, server.matchBodies[1].getJSONObject("rules").getInt("setTarget"))
    }

    @Test fun aMatchTheServerLostIsCreatedAgainUnderTheSameIdBeforeTheUploadOpens() {
        val known = UploadSession(sha256 = sha256(data), serverMatchId = "0d1c8a52-7b1e-4f3a-9a55-0c6d2e1f4b7a")
        val check = run(known)  // a server whose storage was reset knows neither the match nor an upload
        assertEquals(VideoCheck.READY, check.status)
        assertEquals(listOf(known.serverMatchId), server.matchIds)
    }

    @Test fun theServerOffsetWinsOverAStaleSavedOffset() {
        server.failChunk = 3
        assertThrows(UploadException::class.java) { run() }
        val stale = sessions.last().copy(sentBytes = 0)  // the app died before saving progress
        run(stale)
        assertArrayEquals(data, server.stored.toByteArray())
        assertEquals("two good chunks, one failed, one resent", 4, server.chunksReceived)
    }

    @Test fun aSavedSessionForAFinishedUploadReturnsTheVideoWithoutSending() {
        run()
        val chunksBefore = server.chunksReceived
        val check = run(sessions.last())
        assertEquals("video-1", check.videoId)
        assertEquals(chunksBefore, server.chunksReceived)
    }

    @Test fun oneChecksumMismatchIsRepairedByResending() {
        server.checksumFailures = 1
        val check = run()
        assertEquals(VideoCheck.READY, check.status)
        assertArrayEquals(data, server.stored.toByteArray())
        assertEquals(6, server.chunksReceived)
    }

    @Test fun repeatedChecksumMismatchStopsInsteadOfLooping() {
        server.checksumFailures = 5
        val failure = assertThrows(UploadException::class.java) { run() }
        assertEquals(UploadFailure.CHECKSUM_KEEPS_FAILING, failure.failure)
        assertEquals(6, server.chunksReceived)
        // The server dropped every byte at the second mismatch too: the phone keeps no number of bytes the
        // server deleted, so the status screen names none.
        assertEquals(0, server.stored.size())
        assertEquals(0L, sessions.last().sentBytes)
    }

    @Test fun anOversizedFileIsRefusedBeforeItIsRead() {
        server.maxVideoBytes = 1024
        val failure = assertThrows(UploadException::class.java) { run() }
        assertEquals(UploadFailure.TOO_LARGE, failure.failure)
        assertEquals(0, opens)
        assertEquals(0, server.matchesCreated)
    }

    @Test fun aStoppedServerIsReportedBeforeTheFileIsRead() {
        server.close()
        val failure = assertThrows(UploadException::class.java) { run() }
        assertEquals(UploadFailure.SERVER_UNREACHABLE, failure.failure)
        assertEquals(0, opens)
    }

    @Test fun aServiceThatIsNotThisApiIsReportedAsARefusalAndNotAsACrash() {
        server.answersWithAPage = true
        val failure = assertThrows(UploadException::class.java) { run() }
        assertEquals(UploadFailure.SERVER_REFUSED, failure.failure)
        assertEquals(0, opens)
    }

    @Test fun cancellingKeepsTheSessionForALaterResume() {
        var calls = 0
        assertThrows(UploadCancelled::class.java) { run(cancelled = { server.chunksReceived >= 1 && ++calls > 0 }) }
        assertTrue(server.chunksReceived in 1..2)
        val check = run(sessions.last())
        assertEquals(VideoCheck.READY, check.status)
        assertArrayEquals(data, server.stored.toByteArray())
        assertEquals(1, server.uploadsCreated)
    }

    // ----- Slice B3: the server closes an upload that got no chunk for a day, and it refuses with its numbers -----

    private val gib = 1024L * 1024 * 1024

    /** A run that stopped after the first chunk, as the app saves it: upload-1 with 4 MiB on the server. */
    private fun stoppedAfterOneChunk(): UploadSession {
        server.failChunk = 2
        assertThrows(UploadException::class.java) { run() }
        server.failChunk = null
        return sessions.last().also {
            assertEquals("upload-1", it.uploadId)
            assertEquals(4L * 1024 * 1024, it.sentBytes)
            sessions.clear()
        }
    }

    /** Design 14.1, B3 row: the sweep closed the saved upload (ABORTED), so the next run opens a new one and sends from byte 0. */
    @Test fun anAbortedUploadStartsAgainFromZero() {
        val saved = stoppedAfterOneChunk()
        server.status = "ABORTED"
        val check = run(saved)
        assertEquals(VideoCheck.READY, check.status)
        assertArrayEquals(data, server.stored.toByteArray())
        assertEquals(2, server.uploadsCreated)
        assertEquals("upload-2", sessions.last().uploadId)
        assertEquals("the same match, under the same id", listOf(saved.serverMatchId), server.matchIds)
        assertEquals(saved.serverMatchId, sessions.last().serverMatchId)
    }

    /**
     * The offset of a closed upload names bytes the sweep deleted. The phone forgets it before it asks for a
     * new upload, so a refusal of that request leaves no "the server holds 4 MB" behind (Status, upload_kept).
     */
    @Test fun anAbortedUploadForgetsItsOldOffsetBeforeItAsksForANewOne() {
        val saved = stoppedAfterOneChunk()
        server.status = "ABORTED"
        server.refuseUpload = Triple(409, "QUOTA_EXCEEDED", JSONObject().put("scope", "PERSONAL").put("usedBytes", 8 * gib).put("limitBytes", 8 * gib))
        val failure = assertThrows(UploadException::class.java) { run(saved) }
        assertEquals("QUOTA_EXCEEDED", failure.serverCode)
        val last = sessions.last()
        assertNull(last.uploadId)
        assertEquals(0L, last.sentBytes)
        assertEquals("the match and the file's digest stay", saved.serverMatchId to saved.sha256, last.serverMatchId to last.sha256)
    }

    /** The sweep closed the upload between the status read and a chunk: one round of the loop opens a new upload. */
    @Test fun aChunkThatFindsTheUploadClosedStartsAgainFromZeroOnce() {
        server.closeAtChunks = setOf(2)
        val check = run()
        assertEquals(VideoCheck.READY, check.status)
        assertArrayEquals(data, server.stored.toByteArray())
        assertEquals(2, server.uploadsCreated)
        assertEquals("chunk 1, the refused chunk 2, then the three chunks of the new upload", 5, server.chunksReceived)
        val reset = sessions.indexOfFirst { it.uploadId == null && it.sentBytes == 0L && it.serverMatchId != null && it.sha256 != null }
        assertTrue("the phone forgot the closed upload before it opened the new one", reset in 0 until sessions.indexOfFirst { it.uploadId == "upload-2" })
    }

    @Test fun aCompleteThatFindsTheUploadClosedStartsAgainFromZeroOnce() {
        server.closedCompletes = 1
        val check = run()
        assertEquals(VideoCheck.READY, check.status)
        assertArrayEquals(data, server.stored.toByteArray())
        assertEquals(2, server.uploadsCreated)
        assertEquals(6, server.chunksReceived)
        assertEquals("upload-2", sessions.last().uploadId)
    }

    @Test fun aSecondClosedUploadInOneRunIsARefusal() {
        server.closeAtChunks = setOf(1, 2)
        val failure = assertThrows(UploadException::class.java) { run() }
        assertEquals(UploadFailure.SERVER_REFUSED, failure.failure)
        assertEquals("UPLOAD_CLOSED", failure.serverCode)
        assertEquals("one new upload, not a loop", 2, server.uploadsCreated)
    }

    /** The view model words a quota refusal with the server's own numbers, so the engine hands them over untouched. */
    @Test fun aQuotaRefusalKeepsTheServersNumbers() {
        server.refuseUpload = Triple(409, "QUOTA_EXCEEDED", JSONObject().put("scope", "PERSONAL").put("usedBytes", 6 * gib).put("limitBytes", 8 * gib))
        val failure = assertThrows(UploadException::class.java) { run() }
        assertEquals(UploadFailure.SERVER_REFUSED, failure.failure)
        assertEquals("QUOTA_EXCEEDED", failure.serverCode)
        val details = (failure.cause as ApiException).details
        assertEquals("PERSONAL", details.getString("scope"))
        assertEquals(6 * gib, details.getLong("usedBytes"))
        assertEquals(8 * gib, details.getLong("limitBytes"))
        assertEquals(0, server.chunksReceived)
        assertNull(sessions.last().uploadId)
    }

    /** A chunk that would fill the server's disk is not written: the phone keeps the offset the server confirmed last and goes on from there later. */
    @Test fun aStorageFullChunkKeepsTheLastConfirmedOffset() {
        server.storageFullAtChunk = 2
        val failure = assertThrows(UploadException::class.java) { run() }
        assertEquals(UploadFailure.SERVER_REFUSED, failure.failure)
        assertEquals("STORAGE_FULL", failure.serverCode)
        assertEquals(10 * gib, (failure.cause as ApiException).details.getLong("floorBytes"))
        assertEquals(4L * 1024 * 1024, sessions.last().sentBytes)
        assertEquals("upload-1", sessions.last().uploadId)

        server.storageFullAtChunk = null
        val check = run(sessions.last())
        assertEquals(VideoCheck.READY, check.status)
        assertArrayEquals(data, server.stored.toByteArray())
        assertEquals(1, server.uploadsCreated)
    }

    /** A 403 is no "upload gone": the saved upload stays saved, and nothing opens a second one (design 10.7). */
    @Test fun aMatchThisAccountCanNotSeeIsARefusalAndTheSavedUploadStays() {
        val saved = stoppedAfterOneChunk()
        server.notAMember = true
        val failure = assertThrows(UploadException::class.java) { run(saved) }
        assertEquals(UploadFailure.SERVER_REFUSED, failure.failure)
        assertEquals("NOT_A_MEMBER", failure.serverCode)
        assertTrue(sessions.all { it.uploadId == "upload-1" && it.sentBytes == saved.sentBytes })
        assertEquals(1, server.uploadsCreated)
    }

    @Test fun aSourceThatShrankIsNotUploaded() {
        val short = VideoBytes(data.size.toLong()) { ByteArrayInputStream(data, 0, 1000) }
        val failure = assertThrows(UploadException::class.java) {
            UploadEngine(ApiClient(server.baseUrl)).run(UploadSession(), MatchInfo("A Takımı", "B Takımı"), "video.mp4", "video/mp4", short, {}, { _, _, _ -> }, { false })
        }
        assertEquals(UploadFailure.SOURCE_UNREADABLE, failure.failure)
        assertEquals(0, server.uploadsCreated)
    }
}
