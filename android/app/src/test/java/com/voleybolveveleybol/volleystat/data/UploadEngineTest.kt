package com.voleybolveveleybol.volleystat.data

import com.voleybolveveleybol.volleystat.model.UploadSession
import com.voleybolveveleybol.volleystat.model.VideoCheck
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

    private fun run(initial: UploadSession = UploadSession(), cancelled: () -> Boolean = { false }): VideoCheck =
        UploadEngine(ApiClient(server.baseUrl)).run(
            initial, MatchInfo("A Takımı", "B Takımı"), "video.mp4", "video/mp4", bytes,
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

    @Test fun cancellingKeepsTheSessionForALaterResume() {
        var calls = 0
        assertThrows(UploadCancelled::class.java) { run(cancelled = { server.chunksReceived >= 1 && ++calls > 0 }) }
        assertTrue(server.chunksReceived in 1..2)
        val check = run(sessions.last())
        assertEquals(VideoCheck.READY, check.status)
        assertArrayEquals(data, server.stored.toByteArray())
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
