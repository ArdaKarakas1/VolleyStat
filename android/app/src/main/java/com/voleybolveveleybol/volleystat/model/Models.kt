package com.voleybolveveleybol.volleystat.model

enum class VideoSource { CAMERA, GALLERY }

/**
 * What the phone itself could read from the chosen file. A null field was not readable;
 * it is never replaced by a zero or a guess.
 */
data class LocalVideo(
    /** File path for a camera recording, content URI for a gallery pick. */
    val location: String,
    val source: VideoSource,
    val fileName: String,
    val sizeBytes: Long,
    val mimeType: String?,
    val durationMs: Long?,
    val width: Int?,
    val height: Int?,
    val thumbnailPath: String?,
)

/** Result of the server-side file check. It describes the file, not what the video shows. */
data class VideoCheck(
    val videoId: String,
    val status: String,
    val rejectCode: String?,
    val width: Int?,
    val height: Int?,
    val fps: Double?,
    val durationMs: Long?,
    val codec: String?,
    val warnings: List<String>,
) {
    companion object {
        const val READY = "READY"
        const val QUARANTINED = "QUARANTINED"
        const val STORED_UNVALIDATED = "STORED_UNVALIDATED"
    }
}

/** Everything needed to continue an upload after the app was closed. */
data class UploadSession(
    val sha256: String? = null,
    val serverMatchId: String? = null,
    val uploadId: String? = null,
    val sentBytes: Long = 0,
)

data class MatchRecord(
    val localId: String,
    val teamA: String,
    val teamB: String,
    val createdAtMs: Long,
    val video: LocalVideo,
    val session: UploadSession = UploadSession(),
    val check: VideoCheck? = null,
    val checkedAtMs: Long? = null,
)

/** Where a match stands. Analysis states do not exist yet because no model is connected. */
enum class MatchStatus { UPLOAD_PENDING, FILE_ACCEPTED, FILE_REJECTED, FILE_UNVERIFIED }

val MatchRecord.status: MatchStatus
    get() = when (check?.status) {
        null -> MatchStatus.UPLOAD_PENDING
        VideoCheck.READY -> MatchStatus.FILE_ACCEPTED
        VideoCheck.QUARANTINED -> MatchStatus.FILE_REJECTED
        else -> MatchStatus.FILE_UNVERIFIED
    }

/** The match being set up in the new-analysis flow. */
data class Draft(
    val teamA: String = "",
    val teamB: String = "",
    val video: LocalVideo? = null,
    /** Camera target handed to the system camera; kept so a recreated activity can still claim it. */
    val pendingCapturePath: String? = null,
)

enum class ServerTarget { EMULATOR, USB_DEVICE }

const val MAX_VIDEO_BYTES: Long = 4L * 1024 * 1024 * 1024

/** Team names the server will accept: 2 to 80 characters after trimming, and different from each other. */
fun teamNamesValid(teamA: String, teamB: String): Boolean {
    val a = teamA.trim()
    val b = teamB.trim()
    return a.length in 2..80 && b.length in 2..80 && !a.equals(b, ignoreCase = true)
}

/** The MIME type and file extension the server accepts for an upload, or null if it accepts neither. */
data class UploadFormat(val mimeType: String, val extension: String)

fun uploadFormat(reportedMime: String?, fileName: String): UploadFormat? {
    val known = mapOf(
        "video/mp4" to "mp4", "video/quicktime" to "mov", "video/webm" to "webm", "video/x-matroska" to "mkv",
    )
    // The type the system reports describes the content; the display name may say anything.
    known[reportedMime?.lowercase()]?.let { return UploadFormat(reportedMime!!.lowercase(), it) }
    val extension = fileName.substringAfterLast('.', "").lowercase()
    return known.entries.firstOrNull { it.value == extension }?.let { UploadFormat(it.key, it.value) }
}
