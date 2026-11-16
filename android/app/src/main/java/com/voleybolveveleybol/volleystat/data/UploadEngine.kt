package com.voleybolveveleybol.volleystat.data

import com.voleybolveveleybol.volleystat.model.UploadSession
import com.voleybolveveleybol.volleystat.model.VideoCheck
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/** The bytes of the chosen video. [open] must return a fresh stream positioned at byte 0. */
class VideoBytes(val sizeBytes: Long, val open: () -> InputStream)

enum class UploadPhase { CONNECTING, HASHING, SENDING, VERIFYING }

enum class UploadFailure { SERVER_UNREACHABLE, TOO_LARGE, SOURCE_UNREADABLE, SERVER_REFUSED, CHECKSUM_KEEPS_FAILING }

class UploadException(val failure: UploadFailure, val serverCode: String? = null, cause: Throwable? = null) : IOException(failure.name, cause)

class UploadCancelled : IOException("cancelled")

/**
 * Sends one video with the resumable protocol. The id of the match is chosen here and reported
 * through [onSession] before the first request; every later step is reported once the server
 * confirmed it. A later run with the saved session continues instead of creating a second
 * match or starting again from byte 0.
 */
class UploadEngine(private val api: ApiClient) {

    fun run(
        initial: UploadSession,
        match: MatchInfo,
        fileName: String,
        mimeType: String,
        bytes: VideoBytes,
        onSession: (UploadSession) -> Unit,
        onProgress: (UploadPhase, Long, Long) -> Unit,
        cancelled: () -> Boolean,
    ): VideoCheck {
        var session = initial
        fun save(next: UploadSession) {
            session = next
            onSession(next)
        }
        fun checkCancelled() {
            if (cancelled()) throw UploadCancelled()
        }

        // Ask the server first: a stopped server or an oversized file should fail in a
        // second, not after the whole file has been read for hashing.
        onProgress(UploadPhase.CONNECTING, 0, bytes.sizeBytes)
        val health = try {
            api.health()
        } catch (error: ApiException) {
            throw UploadException(UploadFailure.SERVER_REFUSED, error.code, error)
        } catch (error: IOException) {
            throw UploadException(UploadFailure.SERVER_UNREACHABLE, cause = error)
        }
        if (bytes.sizeBytes <= 0) throw UploadException(UploadFailure.SOURCE_UNREADABLE)
        if (bytes.sizeBytes > health.maxVideoBytes) throw UploadException(UploadFailure.TOO_LARGE)

        try {
            if (session.sha256 == null) {
                save(session.copy(sha256 = hash(bytes, onProgress, ::checkCancelled)))
            }
            if (session.serverMatchId == null) {
                // The phone picks the id and saves it before the server hears of it. Whatever happens
                // to the first request, the phone can name the match: to create it again or to delete it.
                save(session.copy(serverMatchId = UUID.randomUUID().toString()))
            }

            var checksumRetries = 0
            while (true) {
                checkCancelled()
                var offset = openOrResume(session, match, fileName, mimeType, bytes.sizeBytes, ::save)?.let { return it.result } ?: session.sentBytes
                offset = send(session.uploadId!!, offset, bytes, onProgress, ::checkCancelled) { sent -> save(session.copy(sentBytes = sent)) }
                onProgress(UploadPhase.VERIFYING, offset, bytes.sizeBytes)
                try {
                    return completeWhenReady(session.uploadId!!, ::checkCancelled)
                } catch (error: ApiException) {
                    when (error.code) {
                        // The server found different bytes than the phone hashed: send once more.
                        "UPLOAD_CHECKSUM_MISMATCH" -> {
                            if (++checksumRetries > 1) throw UploadException(UploadFailure.CHECKSUM_KEEPS_FAILING, error.code, error)
                            save(session.copy(sentBytes = 0))
                        }
                        // Bytes went missing on the server: go round again from its offset.
                        "UPLOAD_INCOMPLETE" -> save(session.copy(sentBytes = error.details.optLong("nextOffset", 0)))
                        else -> throw error
                    }
                }
            }
        } catch (error: UploadException) {
            throw error
        } catch (error: UploadCancelled) {
            throw error
        } catch (error: ApiException) {
            throw UploadException(UploadFailure.SERVER_REFUSED, error.code, error)
        } catch (error: SourceException) {
            throw UploadException(UploadFailure.SOURCE_UNREADABLE, cause = error)
        } catch (error: IOException) {
            throw UploadException(UploadFailure.SERVER_UNREACHABLE, cause = error)
        }
    }

    private class Finished(val result: VideoCheck)
    private class SourceException(cause: Throwable?) : IOException(cause)

    /** Creates the upload on the server or adopts the saved one. Returns the video if it already exists. */
    private fun openOrResume(
        session: UploadSession,
        match: MatchInfo,
        fileName: String,
        mimeType: String,
        sizeBytes: Long,
        save: (UploadSession) -> Unit,
    ): Finished? {
        val saved = session.uploadId
        if (saved != null) {
            val status = try {
                api.uploadStatus(saved)
            } catch (error: ApiException) {
                if (error.status != 404) throw error
                null
            }
            when (status?.status) {
                "COMPLETED" -> return Finished(api.video(status.videoId!!))
                "UPLOADING", "FINALIZING" -> {
                    save(session.copy(sentBytes = status.nextOffset))
                    return null
                }
            }
        }
        // No usable upload on the server (first run, aborted, or the server was reset). The match
        // comes first; the call changes nothing when the server already has it.
        api.putMatch(session.serverMatchId!!, match)
        val ticket = api.createUpload(session.serverMatchId!!, fileName, sizeBytes, mimeType, session.sha256!!)
        save(session.copy(uploadId = ticket.uploadId, sentBytes = ticket.nextOffset))
        return null
    }

    private fun send(
        uploadId: String,
        startOffset: Long,
        bytes: VideoBytes,
        onProgress: (UploadPhase, Long, Long) -> Unit,
        checkCancelled: () -> Unit,
        onSent: (Long) -> Unit,
    ): Long {
        var offset = startOffset
        val buffer = ByteArray(CHUNK_BYTES)
        while (offset < bytes.sizeBytes) {
            var restart = false
            openAt(bytes, offset).use { input ->
                while (offset < bytes.sizeBytes && !restart) {
                    checkCancelled()
                    onProgress(UploadPhase.SENDING, offset, bytes.sizeBytes)
                    val wanted = minOf(buffer.size.toLong(), bytes.sizeBytes - offset).toInt()
                    val read = readFully(input, buffer, wanted)
                    if (read < wanted) throw SourceException(null)
                    try {
                        offset = api.putChunk(uploadId, offset, buffer, read)
                    } catch (error: ApiException) {
                        if (error.code != "OFFSET_MISMATCH") throw error
                        // The server is the source of truth for what it holds.
                        offset = error.details.getLong("expectedOffset")
                        restart = true
                    }
                    onSent(offset)
                }
            }
        }
        return offset
    }

    private fun completeWhenReady(uploadId: String, checkCancelled: () -> Unit): VideoCheck {
        repeat(FINALIZE_ATTEMPTS) {
            checkCancelled()
            try {
                return api.complete(uploadId)
            } catch (error: ApiException) {
                // Another request is still verifying this upload; its result will be the answer.
                if (error.code != "UPLOAD_FINALIZING") throw error
                Thread.sleep(FINALIZE_WAIT_MS)
            }
        }
        throw UploadException(UploadFailure.SERVER_REFUSED, "UPLOAD_FINALIZING")
    }

    private fun hash(bytes: VideoBytes, onProgress: (UploadPhase, Long, Long) -> Unit, checkCancelled: () -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1024 * 1024)
        var total = 0L
        openAt(bytes, 0).use { input ->
            while (true) {
                checkCancelled()
                onProgress(UploadPhase.HASHING, total, bytes.sizeBytes)
                val count = try {
                    input.read(buffer)
                } catch (error: IOException) {
                    throw SourceException(error)
                }
                if (count < 0) break
                digest.update(buffer, 0, count)
                total += count
            }
        }
        if (total != bytes.sizeBytes) throw SourceException(null)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun openAt(bytes: VideoBytes, offset: Long): InputStream {
        val input = try {
            bytes.open()
        } catch (error: Exception) {
            throw SourceException(error)
        }
        try {
            var skipped = 0L
            while (skipped < offset) {
                val step = input.skip(offset - skipped)
                if (step <= 0) {
                    if (input.read() < 0) throw SourceException(null)
                    skipped += 1
                } else {
                    skipped += step
                }
            }
        } catch (error: IOException) {
            input.close()
            throw if (error is SourceException) error else SourceException(error)
        }
        return input
    }

    private fun readFully(input: InputStream, buffer: ByteArray, wanted: Int): Int {
        var filled = 0
        while (filled < wanted) {
            val count = try {
                input.read(buffer, filled, wanted - filled)
            } catch (error: IOException) {
                throw SourceException(error)
            }
            if (count < 0) break
            filled += count
        }
        return filled
    }

    private companion object {
        const val CHUNK_BYTES = 4 * 1024 * 1024
        const val FINALIZE_ATTEMPTS = 60
        const val FINALIZE_WAIT_MS = 2_000L
    }
}
