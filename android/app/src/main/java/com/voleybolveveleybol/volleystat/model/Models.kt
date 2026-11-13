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

/** Only a label. The app and the server never treat a match as an official record. */
enum class MatchKind { TRAINING, OFFICIAL }

data class Player(val id: String, val name: String, val jersey: Int?)

/** A team the user saved on this phone. Player names never leave the phone. */
data class Team(val id: String, val name: String, val players: List<Player> = emptyList())

/** The last analysis job state the app saw, kept so lists can show it without a network call. */
data class AnalysisSummary(
    val jobId: String,
    val state: String,
    val mock: Boolean,
    val reviewOpen: Int,
    val reviewTotal: Int,
    val failureCode: String? = null,
) {
    companion object {
        const val QUEUED = "QUEUED"
        const val PROCESSING = "PROCESSING"
        const val NEEDS_REVIEW = "NEEDS_REVIEW"
        const val COMPLETE = "COMPLETE"
        const val FAILED = "FAILED"
        const val CANCELLED = "CANCELLED"
    }
}

data class MatchRecord(
    val localId: String,
    val homeTeam: String,
    val awayTeam: String,
    val createdAtMs: Long,
    val video: LocalVideo,
    val name: String? = null,
    /** ISO date (YYYY-MM-DD). */
    val playedOn: String? = null,
    val venue: String? = null,
    val kind: MatchKind = MatchKind.TRAINING,
    val homeTeamId: String? = null,
    val awayTeamId: String? = null,
    val session: UploadSession = UploadSession(),
    val check: VideoCheck? = null,
    val checkedAtMs: Long? = null,
    val analysis: AnalysisSummary? = null,
) {
    val title: String get() = name?.takeIf { it.isNotBlank() } ?: "$homeTeam - $awayTeam"
}

/** Where a match stands, from the least to the most advanced step. */
enum class MatchStatus {
    UPLOAD_PENDING, FILE_REJECTED, FILE_UNVERIFIED,
    /** The file is fine and no analysis job exists (none requested, or the server has no analyzer). */
    FILE_ACCEPTED,
    ANALYZING, NEEDS_REVIEW, REPORT_READY, ANALYSIS_FAILED,
}

val MatchRecord.status: MatchStatus
    get() = when (check?.status) {
        null -> MatchStatus.UPLOAD_PENDING
        VideoCheck.QUARANTINED -> MatchStatus.FILE_REJECTED
        VideoCheck.READY -> when (analysis?.state) {
            AnalysisSummary.QUEUED, AnalysisSummary.PROCESSING -> MatchStatus.ANALYZING
            AnalysisSummary.NEEDS_REVIEW -> MatchStatus.NEEDS_REVIEW
            AnalysisSummary.COMPLETE -> MatchStatus.REPORT_READY
            AnalysisSummary.FAILED -> MatchStatus.ANALYSIS_FAILED
            else -> MatchStatus.FILE_ACCEPTED
        }
        else -> MatchStatus.FILE_UNVERIFIED
    }

/** The match being set up in the new-match flow. Only the two team names are required. */
data class Draft(
    val name: String = "",
    /** ISO date; blank means "today" until the user picks another day. */
    val playedOn: String = "",
    val venue: String = "",
    val kind: MatchKind = MatchKind.TRAINING,
    val homeTeam: String = "",
    val awayTeam: String = "",
    val homeTeamId: String? = null,
    val awayTeamId: String? = null,
    val video: LocalVideo? = null,
    /** Camera target handed to the recorder; kept so a recreated activity can still claim it. */
    val pendingCapturePath: String? = null,
) {
    val started: Boolean get() = name.isNotBlank() || venue.isNotBlank() || homeTeam.isNotBlank() || awayTeam.isNotBlank() || video != null
}

enum class ServerTarget { EMULATOR, USB_DEVICE }

data class Settings(
    val serverTarget: ServerTarget = ServerTarget.EMULATOR,
    /** Shown in the greeting when set. The app has no account system. */
    val displayName: String = "",
    val welcomeSeen: Boolean = false,
)

const val MAX_VIDEO_BYTES: Long = 4L * 1024 * 1024 * 1024

/** Team names the server will accept: 2 to 80 characters after trimming, and different from each other. */
fun teamNamesValid(home: String, away: String): Boolean {
    val a = home.trim()
    val b = away.trim()
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
