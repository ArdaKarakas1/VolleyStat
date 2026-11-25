package com.voleybolveveleybol.volleystat

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voleybolveveleybol.volleystat.data.AccountFiles
import com.voleybolveveleybol.volleystat.data.AccountSpace
import com.voleybolveveleybol.volleystat.data.AccountUser
import com.voleybolveveleybol.volleystat.data.AnswerFromAnotherServer
import com.voleybolveveleybol.volleystat.data.ApiClient
import com.voleybolveveleybol.volleystat.data.ApiException
import com.voleybolveveleybol.volleystat.data.AuthProblem
import com.voleybolveveleybol.volleystat.data.DeletionDoubtStore
import com.voleybolveveleybol.volleystat.data.DeviceStore
import com.voleybolveveleybol.volleystat.data.DeviceTokenStore
import com.voleybolveveleybol.volleystat.data.KeystoreSessionCipher
import com.voleybolveveleybol.volleystat.data.LegacyContent
import com.voleybolveveleybol.volleystat.data.MatchInfo
import com.voleybolveveleybol.volleystat.data.RequestNotSent
import com.voleybolveveleybol.volleystat.data.outcomeUnknownAfter
import com.voleybolveveleybol.volleystat.data.Session
import com.voleybolveveleybol.volleystat.data.SessionCipher
import com.voleybolveveleybol.volleystat.data.SessionCipherSource
import com.voleybolveveleybol.volleystat.data.SessionStore
import com.voleybolveveleybol.volleystat.data.SignedIn
import com.voleybolveveleybol.volleystat.data.SignupClosedReason
import com.voleybolveveleybol.volleystat.data.SignupMode
import com.voleybolveveleybol.volleystat.data.StoredState
import com.voleybolveveleybol.volleystat.data.TeamProblem
import com.voleybolveveleybol.volleystat.data.UnansweredRegistrationStore
import com.voleybolveveleybol.volleystat.data.sha256Hex
import com.voleybolveveleybol.volleystat.data.UploadCancelled
import com.voleybolveveleybol.volleystat.data.UploadEngine
import com.voleybolveveleybol.volleystat.data.UploadException
import com.voleybolveveleybol.volleystat.data.UploadFailure
import com.voleybolveveleybol.volleystat.data.UploadPhase
import com.voleybolveveleybol.volleystat.data.VideoBytes
import com.voleybolveveleybol.volleystat.data.VideoInspector
import com.voleybolveveleybol.volleystat.model.Draft
import com.voleybolveveleybol.volleystat.model.LocalVideo
import com.voleybolveveleybol.volleystat.model.AnalysisJob
import com.voleybolveveleybol.volleystat.model.Correction
import com.voleybolveveleybol.volleystat.model.AnalysisSummary
import com.voleybolveveleybol.volleystat.model.MatchReport
import com.voleybolveveleybol.volleystat.model.RallyList
import com.voleybolveveleybol.volleystat.model.ReviewCard
import com.voleybolveveleybol.volleystat.model.ReviewDeck
import com.voleybolveveleybol.volleystat.model.MatchRecord
import com.voleybolveveleybol.volleystat.model.MatchRules
import com.voleybolveveleybol.volleystat.model.Player
import com.voleybolveveleybol.volleystat.model.ServerTarget
import com.voleybolveveleybol.volleystat.model.Settings
import com.voleybolveveleybol.volleystat.model.Schedule
import com.voleybolveveleybol.volleystat.model.Seat
import com.voleybolveveleybol.volleystat.model.Team
import com.voleybolveveleybol.volleystat.model.TeamDraft
import com.voleybolveveleybol.volleystat.model.TeamInfo
import com.voleybolveveleybol.volleystat.model.normalizeUsername
import com.voleybolveveleybol.volleystat.model.uploadFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.util.UUID
import org.json.JSONException

/** Live progress of the one upload the app runs at a time. */
data class UploadProgress(
    val matchId: String,
    val phase: UploadPhase? = null,
    val doneBytes: Long = 0,
    val totalBytes: Long = 0,
    val running: Boolean = false,
    val pausedByUser: Boolean = false,
    val failure: UploadFailure? = null,
    val serverCode: String? = null,
    /** The server's refusal, read from its answer, when the screen has words of its own for it. Null keeps the general text. */
    val refusal: UploadRefusal? = null,
)

/**
 * A refusal of an upload that the status screen words with the server's own numbers (docs/API_V1.md, section 10,
 * "Depolama sınırları"). Every number here came from the server's answer and is above 0: an answer without one
 * gives no refusal at all, and the screen keeps upload_error_refused. The app never writes a number of its own.
 */
sealed interface UploadRefusal {
    /** 409 QUOTA_EXCEEDED, scope PERSONAL: what the server set aside for this account, and its limit. */
    data class PersonalQuota(val usedBytes: Long, val limitBytes: Long) : UploadRefusal
    /** 409 QUOTA_EXCEEDED, scope SERVER, pool PERSONAL: the limit for the personal videos of every account. */
    data class ServerQuota(val limitBytes: Long) : UploadRefusal
    /** 507 STORAGE_FULL: the free space the server keeps on its disk, as the refused request met it. */
    data class StorageFull(val floorBytes: Long) : UploadRefusal
    /** 403 NOT_A_MEMBER: the server does not show this match to this account. */
    data object NoAccess : UploadRefusal
    /**
     * 409 QUOTA_EXCEEDED, scope PERSONAL, reason UNFINISHED_UPLOAD: the account already has its one unfinished personal
     * upload (design 10.8; from slice B4 every account opened without an operator code meets it). It carries no number:
     * the answer's numbers name the space the account holds, not what this video would need.
     */
    data object OneUnfinished : UploadRefusal

    companion object {
        /**
         * Reads the refusal out of a failed upload. A variant without its numbers, a number that is not a whole
         * number above 0, a quota with a reason this app has no text for (any but UNFINISHED_UPLOAD of the scope
         * PERSONAL) and a scope this app has no text for (TEAM, OPEN_MATCH) give null.
         */
        fun of(error: Throwable): UploadRefusal? {
            if (error !is UploadException || error.failure != UploadFailure.SERVER_REFUSED) return null
            val answer = error.cause as? ApiException ?: return null
            val details = answer.details
            fun bytes(name: String): Long? = when (val value = details.opt(name)) {
                is Int -> value.toLong()
                is Long -> value
                else -> null
            }?.takeIf { it > 0 }
            return when (answer.code) {
                "NOT_A_MEMBER" -> NoAccess
                "STORAGE_FULL" -> bytes("floorBytes")?.let(::StorageFull)
                "QUOTA_EXCEEDED" -> when {
                    details.optString("scope") == "PERSONAL" && details.optString("reason") == "UNFINISHED_UPLOAD" -> OneUnfinished
                    !details.isNull("reason") -> null
                    details.optString("scope") == "PERSONAL" ->
                        bytes("usedBytes")?.let { used -> bytes("limitBytes")?.let { limit -> PersonalQuota(used, limit) } }
                    details.optString("scope") == "SERVER" && details.optString("pool") == "PERSONAL" -> bytes("limitBytes")?.let(::ServerQuota)
                    else -> null
                }
                else -> null
            }
        }
    }
}

enum class ServerTest { IDLE, TESTING, OK, OK_NO_PROBE, UNREACHABLE }

enum class SourceProblem { CAMERA_MISSING, CAMERA_EMPTY, UNREADABLE }

data class RecordingFile(val name: String, val path: String, val sizeBytes: Long)

/**
 * Why the server did not confirm the removal of a match. UNCONFIRMED: no answer came, so the app
 * does not know what the server holds. VERIFYING: the server is still checking the match's upload.
 * REFUSED: the server answered and deleted nothing. NO_ACCESS: the server does not show the match to
 * this account (403 NOT_A_MEMBER); the match is not deleted, and the phone can only forget it (Telefondan Kaldır).
 */
enum class RemovalFailure { UNCONFIRMED, VERIFYING, REFUSED, NO_ACCESS }

data class RemovalProblem(val matchId: String, val failure: RemovalFailure)

/** Where the app opens (design 10.1). The navigation maps each to its route. */
enum class Entry { WELCOME, START, LOGIN, LEGACY, HOME }

/**
 * A change of the account that the server confirmed. The screen that asked for it says so once;
 * DELETED is said by the login screen the app opens after the deletion. PHONE_DATA_DELETED is the
 * one change the phone makes alone: it deleted the data of an [UnconfirmedDeletion] on the user's
 * word, and the server was not asked.
 */
enum class AccountChange { DISPLAY_NAME, PASSWORD, DELETED, PHONE_DATA_DELETED }

/**
 * Why a new password the server confirmed reached no password manager. The profile names the
 * reason next to the confirmation, so the user knows which password the server now holds.
 */
enum class PasswordNotHandedOver {
    /** Android rebuilt the screen while the change was on its way, and the rebuilt fields are empty by design (second review, R2-D). */
    SCREEN_REBUILT,
    /** The user left the screen before the answer came, so no screen was there to commit it (third review, B1). */
    SCREEN_LEFT,
    /**
     * The user edited the new password while the change was on its way. The field no longer holds the
     * password the server got, and a commit would hand the edited text over (third review, B2).
     */
    PASSWORD_EDITED,
}

/**
 * An account whose deletion got no answer and whose session then left the phone without a sign
 * that the account is still there (review finding S1). The server may have deleted it, so its
 * folder may be one that no login can reach again. The login screen offers to delete that folder
 * and the account's device token; nothing goes before the user says so. The offer is kept in
 * deletions.ids until the user answers it or the account logs in again, through any restart (F1),
 * and through a new keystore key. [username] is null when the phone can not read it now (the sealed
 * deletions.bin, a new key or a damaged file) or deleted deletions.bin because the keystore could not
 * reseal it when another doubt ended (third checker's C1): the offer then names no account.
 */
data class UnconfirmedDeletion(val userId: String, val username: String?)

/**
 * The end of a change whose screen the user left while its request was on its way: [problem] is the
 * answer that ended it, and [outcomeUnknown] says that this answer leaves open whether the server made
 * the change (no answer after the request went out, a 5xx, or an answer the app can not read).
 */
data class OutcomeAfterLeave(val change: AccountChange, val problem: AuthProblem, val outcomeUnknown: Boolean)

/** The question of the first login about the data app 0.7 kept for its one user (design 10.3). */
data class LegacyQuestion(val titles: List<String>, val teams: Int, val recordings: Int, val draft: Boolean)

/** Who is signed in on this phone, and the account call that is on its way. */
data class AccountUi(
    /** The signed-in account. Null until the server confirmed a login or a registration. */
    val user: AccountUser? = null,
    /** An account call is on its way. The form keeps its input and waits for the answer. */
    val busy: Boolean = false,
    /**
     * The server answered the call with SERVER_BUSY, and the app waits out its Retry-After before
     * it sends the request again. The form says so (`server_busy`) instead of waiting for an
     * answer that already came (review finding T5). The next try and the end of the call clear it.
     */
    val retryingAfterBusy: Boolean = false,
    /** Why the last account call failed. The next call and clearAccountProblem() clear it. */
    val problem: AuthProblem? = null,
    /** The server ended the session (401 AUTH_REQUIRED) and the app left the account. The login screen says so (`session_ended`). */
    val sessionEnded: Boolean = false,
    /** After the first login, while the data of app 0.7 waits for the answer to its question. */
    val legacy: LegacyQuestion? = null,
    /** The answer to that question is on its way. Its buttons wait for it; a move that failed gives them back (F4). */
    val legacyMoving: Boolean = false,
    /** The last change the server confirmed. The next call and clearAccountProblem() clear it. */
    val confirmed: AccountChange? = null,
    /** The account whose phone data the login screen offers to delete. A login of that account withdraws the offer. */
    val unconfirmedDeletion: UnconfirmedDeletion? = null,
    /** The account whose phone data the app deleted, for the text of [AccountChange.PHONE_DATA_DELETED]: the other accounts keep theirs. */
    val deletedFromPhone: UnconfirmedDeletion? = null,
    /**
     * The phone could not seal the session at login (the keystore failed). The session lives in
     * memory only, so the next start asks for the password; Home says so. A later save that works clears it.
     */
    val sessionNotKept: Boolean = false,
    /**
     * The server confirmed a new password, and the password manager did not get it: why, by
     * [PasswordNotHandedOver]. The profile says so next to the confirmation. The next call and
     * clearAccountProblem() clear it, as they clear [confirmed].
     */
    val newPasswordNotHandedOver: PasswordNotHandedOver? = null,
    /**
     * How a change of the display name or of the password ended when the user had left its screen while its
     * request was on its way (fourth review, D1). The profile says so. The next call and clearAccountProblem() clear it.
     */
    val afterLeave: OutcomeAfterLeave? = null,
    /**
     * How the server opens accounts, from health's `accounts.signup` (slice B4, design 10.1). Null while health has not
     * named a mode this app knows: the start and register screens then show only what holds under both (F3 of the v5.1
     * checker). It belongs to the server, not to a session: another server clears it, and a logout keeps it.
     */
    val signup: SignupMode? = null,
)

/**
 * The teams the server holds for the signed-in account (slice B4, plan P6). They belong to the session: a logout, an ended
 * session, an account deletion and every login empty them (plan D1). Nothing of them reaches the disk (design 10.3, U20).
 */
data class TeamsUi(
    /** The list as the server last sent it. Null until the server answered: an unread list is no empty one (plan D2). */
    val teams: List<TeamInfo>? = null,
    /** A read of the list is on its way. */
    val loading: Boolean = false,
    /** Why the last read of the list failed. The next read of the list clears it; a read of one team leaves it alone. */
    val loadProblem: TeamProblem? = null,
    /** The team screen's own read of one team, also while the list is unread. A read of the list leaves it alone (P6-C1). */
    val opened: TeamRead? = null,
    /** A team action is on its way (Takım Kur, a seat change, the settings). Its screen waits and takes no second tap. */
    val busy: Boolean = false,
    /** Why the last team action failed. The next action and clearTeamProblem() clear it. */
    val problem: TeamProblem? = null,
) {
    /** The new members of the teams this account captains: the Teams tab's badge. */
    val badge: Int get() = teams.orEmpty().sumOf { it.newJoins ?: 0 }

    /** The team [id] as the app knows it: from the list, else as the team screen read it. */
    fun teamOf(id: String): TeamInfo? = teams?.firstOrNull { it.id == id } ?: opened?.team?.takeIf { it.id == id }
}

/**
 * The read of one team that a team screen asked for (plan P6). Only that screen's next read changes its outcome: a read of
 * the list never does (P6-C1), and the answer of an earlier screen, or of an earlier read, never reaches it (P6-C2).
 */
data class TeamRead(
    /**
     * The team screen that asked: its back stack entry, which stays the same across a rotation and a return from As ve
     * Yedek Yerleri. Null for the team Takım Kur just made, before its screen opened.
     */
    val screen: String?,
    val id: String,
    /** The read on its way, or answered last. The view model counts the reads; 0 for Takım Kur's answer. */
    val number: Long,
    /** The team as this read answered, or as the app knew it when the screen opened. Null after NOT_A_MEMBER or TEAM_NOT_FOUND. */
    val team: TeamInfo? = null,
    /** Why this read failed. Only the screen's next read clears it. */
    val problem: TeamProblem? = null,
    /**
     * The new members this screen's first answer named: the screen's Son katılanlar line. The app marks seen only these,
     * up to the newest join of the same answer (P6-C3). Null until that answer came.
     */
    val recentJoins: Int? = null,
)

data class AppUiState(
    val matches: List<MatchRecord> = emptyList(),
    val teams: List<Team> = emptyList(),
    val draft: Draft = Draft(),
    val settings: Settings = Settings(),
    val upload: UploadProgress? = null,
    /** False in builds without a server address (release): upload is unavailable there. */
    val serverConfigured: Boolean = true,
    val serverTest: ServerTest = ServerTest.IDLE,
    val recordings: List<RecordingFile> = emptyList(),
    val readingVideo: Boolean = false,
    val sourceProblem: SourceProblem? = null,
    /** The match whose removal the server did not confirm; the record is kept and the screen says why. */
    val removalProblem: RemovalProblem? = null,
    /** The last change did not reach the disk. It is still in memory and the next change tries again. */
    val saveFailed: Boolean = false,
    val account: AccountUi = AccountUi(),
    /** The server's teams of the signed-in account (slice B4). [teams] are the local rosters of the personal flow. */
    val serverTeams: TeamsUi = TeamsUi(),
) {
    /**
     * The screen the app opens on (design 10.1): the welcome until it was seen; without a session
     * the start screen while no account ever logged in on this phone, else the login; the question
     * about the old data while it waits; else Home.
     */
    val entry: Entry get() = when {
        !settings.welcomeSeen -> Entry.WELCOME
        account.user == null -> if (settings.accountSeen) Entry.LOGIN else Entry.START
        account.legacy != null -> Entry.LEGACY
        else -> Entry.HOME
    }

    /** A camera file that the draft or an upload that has not finished still reads from. */
    fun recordingInUse(path: String) = draft.video?.location == path || matches.any { it.check == null && it.video.location == path }
}

/** How much the app knows about the analysis job of the match that is open. */
/** VIDEO_MISSING: the server answered that it no longer holds a usable video for this match. */
/** NO_ACCESS: the server does not show the match to this account (403 NOT_A_MEMBER); asking again gets the same answer. */
enum class JobLoad { LOADING, READY, NO_JOB, NO_ANALYZER, VIDEO_MISSING, UNREACHABLE, NO_ACCESS }

enum class ReviewProblem { STALE, SAVE_FAILED, LOAD_FAILED }

/**
 * OUTDATED: an answer came, but not one this app can read as a report. A server before 0.7 answers that way, and so may something else at its address.
 * NO_ACCESS: the server does not show the match to this account (403 NOT_A_MEMBER).
 */
enum class ReportLoad { LOADING, READY, NONE, UNREACHABLE, OUTDATED, NO_ACCESS }

/**
 * OUTDATED: an answer came, but not one this app can read as a rally list. An older server answers that way, and so may something else at its address.
 * NO_ACCESS: the server does not show the match to this account (403 NOT_A_MEMBER).
 */
enum class RalliesLoad { LOADING, READY, UNREACHABLE, OUTDATED, NO_ACCESS }

/**
 * Why a correction of the rally list did not go through. STALE: the list changed after the screen
 * read it, and the app has read it again. OVERLAP: the interval shares time with another rally.
 * OUTSIDE: the interval is empty, outside the video or outside the rally. NOT_READY: a rally still
 * has no winner. DEMO: the list of a demo analysis can not be confirmed. UNREACHABLE: no answer
 * came, and the list that was read afterwards does not show the correction or could not be read.
 * The server may hold the correction all the same; the next attempt finds out. REFUSED: the
 * server answered and changed nothing.
 */
enum class CorrectionProblem { STALE, OVERLAP, OUTSIDE, NOT_READY, DEMO, UNREACHABLE, REFUSED }

/** Analysis, review and report state of the one match that is open on screen. */
data class AnalysisUi(
    val matchId: String? = null,
    val jobLoad: JobLoad = JobLoad.LOADING,
    val job: AnalysisJob? = null,
    val deck: ReviewDeck? = null,
    /** Index of the card on screen. Equal to the deck size when the user has gone past the last card. */
    val cursor: Int = 0,
    /** Ask the optional detail questions (how the point was scored, by whom) as well. */
    val detail: Boolean = false,
    val saving: Boolean = false,
    val reviewProblem: ReviewProblem? = null,
    val reportLoad: ReportLoad = ReportLoad.LOADING,
    val report: MatchReport? = null,
    val ralliesLoad: RalliesLoad = RalliesLoad.LOADING,
    /** The rally list as the server last sent it. Every correction names its version. */
    val rallies: RallyList? = null,
    val correcting: Boolean = false,
    val correctionProblem: CorrectionProblem? = null,
    /** With OVERLAP: the number of the rally that is in the way, when it is on the list. */
    val overlapsRally: Int? = null,
) {
    val card: ReviewCard? get() = deck?.cards?.getOrNull(cursor)

    /**
     * The rally list, unless the app knows that the server has moved on from it. Every winner
     * decision moves the list on, so a list that was read before a decision is no longer the one
     * the server holds, and a correction made on it would be refused. A list that is newer than
     * the job in memory is fine: the answer to a decision can get lost, and then the job stays
     * behind while the list that is read next does not.
     */
    val currentRallies: RallyList? get() = rallies?.takeIf { it.version >= (job?.rallyListVersion ?: 0) }

    /** Why the match has no job to work on, once the app knows that none is on its way. Null while a job is there or expected. */
    val jobProblem: JobLoad? get() = jobLoad.takeIf { job == null && it != JobLoad.LOADING && it != JobLoad.READY }

    /**
     * This state with [job] in it. Cards, a rally list and a correction on its way belong to one
     * job: when the match gets another job, none of them describes it, and a list version of the
     * old job says nothing about the list of the new one.
     */
    fun withJob(job: AnalysisJob?): AnalysisUi {
        val load = if (job == null) JobLoad.NO_JOB else JobLoad.READY
        if (job?.id == this.job?.id) return copy(job = job, jobLoad = load)
        return copy(
            job = job, jobLoad = load, deck = null, cursor = 0, saving = false, reviewProblem = null,
            rallies = null, ralliesLoad = RalliesLoad.LOADING, correcting = false, correctionProblem = null, overlapsRally = null,
        )
    }
}

class AppViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        /** How often an account call meets SERVER_BUSY before the screen hears of it. Each try waits the server's Retry-After first. */
        const val BUSY_TRIES = 5
        private const val LONGEST_BUSY_WAIT_SECONDS = 10

        /**
         * The refusals of POST /me/delete that the server gives only after it found the session
         * (backend/app/accounts.py, delete_account): the account was still there. A 422 comes
         * before that check, so it shows nothing; it and every other 4xx but 401 put the mark back
         * to what it was before the request.
         */
        private val ANSWERS_OF_A_LIVE_SESSION = setOf("PASSWORD_WRONG", "TOO_MANY_ATTEMPTS", "SERVER_BUSY")
    }

    // What lives where (design 10.3): device.json for the phone, session.bin and device.bin sealed
    // with the keystore's key, and one folder per account. The app reads only the signed-in account's folder.
    private val cipher: SessionCipher = (application as? SessionCipherSource)?.sessionCipher ?: KeystoreSessionCipher()
    private val folders = AccountFiles(application.filesDir)
    private val device = DeviceStore(File(application.filesDir, "device.json"), folders.legacyStore)
    private val sessions = SessionStore(File(application.filesDir, "session.bin"), cipher)
    private val deviceTokens = DeviceTokenStore(File(application.filesDir, "device.bin"), cipher)
    private val doubtStore = DeletionDoubtStore(File(application.filesDir, "deletions.bin"), cipher)
    private val registrations = UnansweredRegistrationStore(File(application.filesDir, "registrations.bin"), cipher)
    /**
     * Where a phone without a session would put a recording. The screens never let it record without
     * one; should it happen, the file stays in the cache and in no account.
     */
    private val signedOut = AccountSpace(File(application.cacheDir, "signed-out"))

    /** The signed-in session and its folder. Both change together, under [storeLock]. */
    @Volatile private var session: Session? = null
    @Volatile private var space: AccountSpace? = null
    /**
     * The accounts whose deletion is in doubt, newest first, as deletions.bin keeps them (F1). The
     * login screen offers the first; the others wait their turn. Changed under [accountLock].
     */
    @Volatile private var inDoubt: List<UnconfirmedDeletion> = emptyList()
    private val storeLock = Any()
    private val accountLock = Any()

    private val inspector = VideoInspector(application) { (space ?: signedOut).thumbnails }
    private val recordingsFolder: File get() = (space ?: signedOut).recordings
    @Volatile private var uploadJob: Job? = null

    private val _state: MutableStateFlow<AppUiState>
    val state: StateFlow<AppUiState>

    init {
        val settings = device.load()
        // A session whose account id can not name a folder is no session: nothing of it is read or written.
        val saved = sessions.load()?.takeIf { runCatching { folders.of(it.userId) }.isSuccess }
        session = saved
        space = saved?.let { folders.of(it.userId) }
        // A doubt whose id names no folder could never be acted on. The signed-in account is plainly still there: a
        // login of it left a doubt behind only when the file could not be written then, so the doubt leaves it now.
        val doubts = doubtStore.load().map { (id, name) -> UnconfirmedDeletion(id, name) }
        inDoubt = doubts.filter { doubt -> runCatching { folders.of(doubt.userId) }.isSuccess && doubt.userId != saved?.userId }
        if (saved != null && doubts.any { it.userId == saved.userId }) runCatching { doubtStore.remove(saved.userId) }
        val stored = space?.store?.load() ?: StoredState()
        // The question about the old data waits for its answer through a restart, for the account it was put to only (S2).
        val asked = settings.legacyAskedUser
        val legacy = if (saved != null && !settings.legacyHandled && (asked == null || asked == sha256Hex(saved.userId))) {
            runCatching { folders.legacyContent() }.getOrNull()
        } else {
            null
        }
        _state = MutableStateFlow(
            AppUiState(
                matches = stored.matches, teams = stored.teams, draft = stored.draft, settings = settings,
                serverConfigured = baseUrl(settings.serverTarget).isNotBlank(), recordings = listRecordings(),
                account = AccountUi(
                    user = saved?.let { AccountUser(it.userId, it.username, it.displayName, it.passwordTemporary) }, legacy = legacy?.let(::question),
                    unconfirmedDeletion = inDoubt.firstOrNull(),
                ),
            )
        )
        state = _state.asStateFlow()
        // A question put before the phone remembered whom it was put to belongs to the account that sees it now.
        if (legacy != null && asked == null) saved?.let { changeDevice { settings -> settings.copy(legacyAskedUser = sha256Hex(it.userId)) } }
    }

    /**
     * Tests point the view model at a stand-in server with this. It is null everywhere else. A new address is another
     * server, so the sign-up mode of the old one goes, and is read again once a screen asked for it (C4 of the plan's
     * critics: a flow test sets the address after the start screen's first read went to the build's address).
     */
    @Volatile internal var baseUrlOverride: String? = null
        set(value) {
            if (field == value) return
            field = value
            anotherServer()
        }

    /** Tests shorten the wait of a correction with this, so that a server that stays silent costs them no minute. */
    internal var correctionTimeoutMs: Int? = null

    private fun baseUrl(target: ServerTarget) =
        baseUrlOverride ?: if (target == ServerTarget.EMULATOR) BuildConfig.API_URL_EMULATOR else BuildConfig.API_URL_USB

    /**
     * A client for the server, with the session's token when one is signed in. A call that gets
     * 401 AUTH_REQUIRED ends that session (and only that one) before it throws.
     */
    fun api() = client(endsSession = true)

    private fun client(endsSession: Boolean) = ApiClient(
        baseUrl(_state.value.settings.serverTarget), correctionTimeoutMs ?: ApiClient.CORRECTION_TIMEOUT_MS,
        token = session?.token, onAuthRequired = if (endsSession) ::onSessionEnded else null,
    )

    /** The headers the video players send with every request: the session, when one is signed in (design 10.5). */
    fun playbackHeaders(): Map<String, String> = session?.let { mapOf("Authorization" to "Bearer ${it.token}") }.orEmpty()

    /** Base address for streaming a video from the server (review clips). */
    fun serverBaseUrl(): String = baseUrl(_state.value.settings.serverTarget)

    /**
     * Every change to matches, teams or the draft goes through here so it reaches the signed-in
     * account's folder. Without a session nothing reaches the disk.
     */
    private fun change(transform: (AppUiState) -> AppUiState) {
        _state.update(transform)
        // Changes arrive from the main thread and from the upload thread. Writing whatever is
        // current under one lock means a slower writer can never put an older state on disk. An
        // account switch takes the same lock, so one account's state never lands in another's folder.
        val saved = synchronized(storeLock) {
            val current = _state.value
            // A full disk must not take the app down in the middle of a keystroke or an upload.
            space?.let { runCatching { it.store.save(StoredState(current.matches, current.teams, current.draft)) }.isSuccess } ?: true
        }
        if (_state.value.saveFailed == saved) _state.update { it.copy(saveFailed = !saved) }
    }

    /** Every change to what the phone itself remembers goes through here (device.json). */
    private fun changeDevice(transform: (Settings) -> Settings) {
        _state.update { it.copy(settings = transform(it.settings)) }
        val saved = synchronized(storeLock) { runCatching { device.save(_state.value.settings) }.isSuccess }
        if (!saved) _state.update { it.copy(saveFailed = true) }
    }

    // ----- New analysis draft -----

    fun editDraft(transform: (Draft) -> Draft) = change { it.copy(draft = transform(it.draft)) }

    /** Picks a saved team for one side of the draft, or clears the choice when [team] is null. */
    fun chooseTeam(home: Boolean, team: Team?) = editDraft {
        if (home) it.copy(homeTeam = team?.name.orEmpty(), homeTeamId = team?.id)
        else it.copy(awayTeam = team?.name.orEmpty(), awayTeamId = team?.id)
    }

    fun discardDraft() = change { it.copy(draft = Draft()) }

    /** Creates the private file the in-app recorder writes to and remembers it across process death. */
    fun prepareCapture(): File {
        recordingsFolder.mkdirs()
        // An earlier attempt that recorded nothing leaves an empty placeholder behind.
        _state.value.draft.pendingCapturePath?.let(::File)?.takeIf { it.length() == 0L }?.delete()
        val target = File(recordingsFolder, "kayit-${System.currentTimeMillis()}.mp4")
        change { it.copy(draft = it.draft.copy(pendingCapturePath = target.absolutePath), sourceProblem = null) }
        return target
    }

    fun onCameraMissing() = _state.update { it.copy(sourceProblem = SourceProblem.CAMERA_MISSING) }

    /** The file decides whether something was recorded, not the recorder's last callback. */
    fun onCaptureFinished(onReady: () -> Unit) {
        val path = _state.value.draft.pendingCapturePath ?: return
        val file = File(path)
        if (file.length() <= 0) {
            file.delete()  // the empty placeholder the recorder never filled
            change { it.copy(draft = it.draft.copy(pendingCapturePath = null), sourceProblem = SourceProblem.CAMERA_EMPTY) }
            return
        }
        readVideo(onReady) { inspector.describeRecording(file) }
    }

    fun onVideoPicked(uri: Uri, onReady: () -> Unit) = readVideo(onReady) { inspector.describePicked(uri) }

    private fun readVideo(onReady: () -> Unit, describe: () -> LocalVideo?) {
        _state.update { it.copy(readingVideo = true, sourceProblem = null) }
        viewModelScope.launch {
            val video = withContext(Dispatchers.IO) { runCatching(describe).getOrNull() }
            change {
                it.copy(
                    readingVideo = false,
                    draft = it.draft.copy(video = video ?: it.draft.video, pendingCapturePath = null),
                    sourceProblem = if (video == null) SourceProblem.UNREADABLE else null,
                    recordings = listRecordings(),
                )
            }
            if (video != null) onReady()
        }
    }

    // ----- Upload -----

    /** Turns the draft into a match and starts its upload. Returns the new match id. */
    fun startUpload(): String? {
        val draft = _state.value.draft
        val video = draft.video ?: return null
        val match = MatchRecord(
            localId = UUID.randomUUID().toString(), homeTeam = draft.homeTeam.trim(), awayTeam = draft.awayTeam.trim(),
            createdAtMs = System.currentTimeMillis(), video = video,
            name = draft.name.trim().ifBlank { null },
            playedOn = draft.playedOn.ifBlank { LocalDate.now().toString() },
            venue = draft.venue.trim().ifBlank { null },
            kind = draft.kind, homeTeamId = draft.homeTeamId, awayTeamId = draft.awayTeamId,
            rules = draft.rules,
        )
        change { it.copy(matches = listOf(match) + it.matches, draft = Draft()) }
        resumeUpload(match.localId)
        return match.localId
    }

    fun resumeUpload(matchId: String) {
        if (uploadJob?.isActive == true) return
        val match = _state.value.matches.firstOrNull { it.localId == matchId } ?: return
        if (match.check != null) return
        val format = uploadFormat(match.video.mimeType, match.video.fileName)
        val total = match.video.sizeBytes
        _state.update { it.copy(upload = UploadProgress(matchId, UploadPhase.CONNECTING, match.session.sentBytes, total, running = true)) }
        uploadJob = viewModelScope.launch {
            val job = coroutineContext[Job]!!
            val outcome = withContext(Dispatchers.IO) {
                try {
                    if (format == null) throw UploadException(UploadFailure.SOURCE_UNREADABLE)
                    val check = UploadEngine(api()).run(
                        initial = match.session,
                        match = MatchInfo(match.homeTeam, match.awayTeam, match.name, match.playedOn, match.venue, match.kind.name, match.rules),
                        fileName = "video.${format.extension}", mimeType = format.mimeType,
                        bytes = VideoBytes(total) { inspector.open(match.video) },
                        onSession = { session -> change { state -> state.copy(matches = state.matches.map { m -> if (m.localId == matchId) m.copy(session = session) else m }) } },
                        onProgress = { phase, done, all ->
                            // The engine sends or verifies only once the server opened or resumed this match's upload
                            // for this account: the server shows the match to it again.
                            if (phase == UploadPhase.SENDING || phase == UploadPhase.VERIFYING) markAccess(matchId, noAccess = false)
                            _state.update { it.copy(upload = it.upload?.copy(phase = phase, doneBytes = done, totalBytes = all)) }
                        },
                        cancelled = { !job.isActive || stopRequested },
                    )
                    Result.success(check)
                } catch (error: IOException) {
                    Result.failure(error)
                }
            }
            outcome.onSuccess { check ->
                change { state ->
                    state.copy(
                        upload = null,
                        // The server took the video of the match for this account, so it shows the match to it.
                        matches = state.matches.map { m -> if (m.localId == matchId) m.copy(check = check, checkedAtMs = System.currentTimeMillis(), noAccess = false) else m },
                    )
                }
            }.onFailure { error ->
                if (UploadRefusal.of(error) == UploadRefusal.NoAccess) markAccess(matchId, noAccess = true)
                val sent = _state.value.matches.firstOrNull { it.localId == matchId }?.session?.sentBytes ?: 0
                _state.update { state ->
                    val current = state.upload ?: UploadProgress(matchId)
                    state.copy(
                        upload = when (error) {
                            is UploadCancelled -> current.copy(running = false, phase = null, doneBytes = sent)
                            is UploadException -> current.copy(
                                running = false, phase = null, doneBytes = sent, failure = error.failure, serverCode = error.serverCode, refusal = UploadRefusal.of(error),
                            )
                            else -> current.copy(running = false, phase = null, doneBytes = sent, failure = UploadFailure.SERVER_UNREACHABLE)
                        }
                    )
                }
            }
        }
    }

    @Volatile private var stopRequested = false

    /** Stops the running upload at the next chunk boundary. The server keeps what it has. */
    private suspend fun stopUpload() {
        stopRequested = true
        uploadJob?.join()
        stopRequested = false
    }

    fun pauseUpload() {
        _state.update { it.copy(upload = it.upload?.copy(pausedByUser = true)) }
        viewModelScope.launch { stopUpload() }
    }

    // ----- Matches -----

    /**
     * Removes a match: stops its upload if one is running, has the server delete everything it
     * holds for the match (the record, the video or its unfinished part, the analyses and the
     * decisions) and then forgets the record. If the server did not delete it nothing is
     * forgotten, so the app never claims a deletion that did not happen.
     */
    fun removeMatch(matchId: String, onDone: () -> Unit) {
        viewModelScope.launch {
            if (_state.value.upload?.matchId == matchId) stopUpload()
            val match = _state.value.matches.firstOrNull { it.localId == matchId } ?: return@launch
            val failure = withContext(Dispatchers.IO) {
                try {
                    match.session.serverMatchId?.let { api().deleteMatch(it) }
                    null
                } catch (error: ApiException) {
                    when (error.code) {
                        // Only the server's own "no such match" means the match is already gone. Any
                        // other answer, a 404 from a wrong address included, deleted nothing.
                        "MATCH_NOT_FOUND" -> null
                        "UPLOAD_FINALIZING" -> RemovalFailure.VERIFYING
                        // The match is there and this account may not see it: nothing was deleted (design 10.7).
                        "NOT_A_MEMBER" -> RemovalFailure.NO_ACCESS
                        else -> RemovalFailure.REFUSED
                    }
                } catch (error: IOException) {
                    // No answer: the server may be off, or it deleted the match and the answer was lost.
                    RemovalFailure.UNCONFIRMED
                }
            }
            if (failure == null) {
                forget(match)
                onDone()
            } else {
                if (failure == RemovalFailure.NO_ACCESS) markAccess(matchId, noAccess = true)
                _state.update { it.copy(removalProblem = RemovalProblem(matchId, failure)) }
            }
        }
    }

    /**
     * Telefondan Kaldır: forgets a match the server does not show to this account (design 10.7). It stops the
     * match's upload if one runs and sends nothing to the server, so it claims no deletion there. The user's
     * video file stays on the phone, as with every removal.
     */
    fun removeFromPhone(matchId: String) {
        viewModelScope.launch {
            if (_state.value.upload?.matchId == matchId) stopUpload()
            _state.value.matches.firstOrNull { it.localId == matchId }?.let(::forget)
        }
    }

    private fun forget(match: MatchRecord) {
        // The thumbnail is the app's own derived file. The user's recording stays on the phone.
        match.video.thumbnailPath?.let { File(it).delete() }
        change { state ->
            state.copy(
                matches = state.matches.filterNot { it.localId == match.localId },
                upload = state.upload?.takeUnless { it.matchId == match.localId },
                removalProblem = null,
            )
        }
    }

    fun clearRemovalFailure() = _state.update { it.copy(removalProblem = null) }

    // ----- Profile -----

    /**
     * Chooses the server of a debug build. A session belongs to the server that opened it, so the
     * choice waits for a logout: otherwise its token would go to the other address, in plain HTTP,
     * and that server's 401 would end a session that is still alive (review finding S5). It also
     * waits while an account call is on its way: a login sent to one server must not sign in a
     * session that then talks to the other (second review, R2-B).
     */
    fun setServerTarget(target: ServerTarget) = synchronized(accountLock) {
        if (session != null || _state.value.account.busy) return@synchronized
        val another = target != _state.value.settings.serverTarget
        changeDevice { it.copy(serverTarget = target) }
        _state.update { it.copy(serverTest = ServerTest.IDLE, serverConfigured = baseUrl(target).isNotBlank()) }
        // The register screen's server choice switches the server while the screen stays open.
        if (another) anotherServer()
    }

    // ----- The sign-up mode (slice B4; design 10.1, D4 and F3 of the v5.1 checker) -----

    /**
     * A screen that shows the sign-up mode asked for it. Only from then on does a change of the server read the mode again:
     * before it, an address alone asks the server nothing, and the request lists the view model tests pin stay as they are.
     */
    @Volatile private var signupWanted = false

    /**
     * Reads health's sign-up mode for the start and register screens, which call this when they open. While a session is
     * signed in neither screen shows, and nothing goes out. The answer counts only for the address it was asked of: one
     * that arrives after the server changed is dropped (C4). A failed read, or a mode this app does not know, leaves the
     * mode as it was, so the screens keep the texts of the last mode the server named, or the unread ones.
     */
    fun readSignupMode() {
        signupWanted = true
        if (session != null) return
        val address = baseUrl(_state.value.settings.serverTarget)
        if (address.isBlank()) return
        viewModelScope.launch {
            val mode = withContext(Dispatchers.IO) {
                try {
                    ApiClient(address).health().signup
                } catch (error: IOException) {
                    null
                } catch (error: org.json.JSONException) {
                    null
                }
            } ?: return@launch
            noteSignupMode(address, mode)
        }
    }

    /** Takes [mode] as the mode of the server at [address], unless the app talks to another server by now. */
    private fun noteSignupMode(address: String, mode: SignupMode) = _state.update { state ->
        if (baseUrl(state.settings.serverTarget) != address) state else state.copy(account = state.account.copy(signup = mode))
    }

    /**
     * Another server: the mode the last one named no longer holds, and nor do the teams it sent. A screen that wanted
     * either gets it read again. setServerTarget runs only without a session, so its reset of the teams is defensive; the
     * test hook [baseUrlOverride] can change the address under a session.
     */
    private fun anotherServer() {
        _state.update { it.copy(account = it.account.copy(signup = null)) }
        if (signupWanted) readSignupMode()
        resetTeams()
        if (teamsWanted) loadTeams()
    }

    fun markWelcomeSeen() = changeDevice { it.copy(welcomeSeen = true) }

    /**
     * Marks a match the server does not show to this account (403 NOT_A_MEMBER), or clears the mark once the server
     * shows the match again. The mark is saved, so the lists show the refusal and not an older analysis state.
     */
    private fun markAccess(matchId: String, noAccess: Boolean) {
        if (_state.value.matches.none { it.localId == matchId && it.noAccess != noAccess }) return
        change { state -> state.copy(matches = state.matches.map { if (it.localId == matchId) it.copy(noAccess = noAccess) else it }) }
    }

    /** Remembers the last job state the analysis screens saw, so lists can show it offline. */
    fun rememberAnalysis(matchId: String, summary: AnalysisSummary?) = change { state ->
        state.copy(matches = state.matches.map { if (it.localId == matchId && it.analysis != summary) it.copy(analysis = summary) else it })
    }

    // ----- Analysis, review and report of the open match -----

    private val _analysis = MutableStateFlow(AnalysisUi())
    val analysis: StateFlow<AnalysisUi> = _analysis.asStateFlow()
    @Volatile private var watchJob: Job? = null

    private fun videoIdOf(matchId: String) = _state.value.matches.firstOrNull { it.localId == matchId }?.check
        ?.takeIf { it.status == com.voleybolveveleybol.volleystat.model.VideoCheck.READY }?.videoId

    /** The server answered about the match's job, so it shows the match to this account. */
    private fun publish(matchId: String, job: AnalysisJob?) {
        _analysis.update { if (it.matchId == matchId) it.withJob(job) else it }
        rememberAnalysis(matchId, job?.summary)
        markAccess(matchId, noAccess = false)
    }

    /**
     * Follows the job of a match while its screen is open: asks for one if the video never had
     * one, then polls while the server is still working. Only the server's own states are shown.
     */
    fun watchAnalysis(matchId: String, startIfMissing: Boolean = true) = watch(matchId, startIfMissing, startNow = false)

    /**
     * The retry button of the status screen. A new job is asked for only when the last one ended
     * without a result or the video has none. After a lost connection the app asks again for the
     * job it was following: a new job there would replace one the user may already have reviewed.
     */
    fun restartAnalysis(matchId: String) {
        val ui = _analysis.value.takeIf { it.matchId == matchId }
        val ended = ui?.job?.state == AnalysisSummary.FAILED || ui?.job?.state == AnalysisSummary.CANCELLED
        val startNow = ui != null && (ui.jobLoad == JobLoad.NO_JOB || (ui.jobLoad == JobLoad.READY && ended))
        _analysis.update { if (it.matchId == matchId) it.copy(jobLoad = JobLoad.LOADING) else it }
        watch(matchId, startIfMissing = true, startNow = startNow)
    }

    private fun watch(matchId: String, startIfMissing: Boolean, startNow: Boolean) {
        if (_analysis.value.matchId != matchId) _analysis.value = AnalysisUi(matchId = matchId)
        watchJob?.cancel()
        val videoId = videoIdOf(matchId) ?: return
        watchJob = viewModelScope.launch {
            // One key for the whole watch: when the answer to a start request is lost, the retry
            // gets the same job back instead of a second analysis.
            val startKey = UUID.randomUUID().toString()
            var mustStart = startNow
            while (isActive) {
                val outcome = withContext(Dispatchers.IO) {
                    try {
                        val job = if (mustStart) api().startAnalysis(videoId, startKey)
                        else api().latestAnalysis(videoId) ?: if (startIfMissing) api().startAnalysis(videoId, startKey) else null
                        Result.success(job)
                    } catch (error: IOException) {
                        Result.failure(error)
                    }
                }
                val job = outcome.getOrNull()
                val error = outcome.exceptionOrNull()
                // An answer the server would repeat ends the watch. Only a lost connection is tried again.
                val refusal = when ((error as? ApiException)?.code) {
                    "MODEL_NOT_CONFIGURED" -> JobLoad.NO_ANALYZER
                    "VIDEO_NOT_FOUND", "VIDEO_NOT_READY", "VIDEO_FILE_MISSING" -> JobLoad.VIDEO_MISSING
                    "NOT_A_MEMBER" -> JobLoad.NO_ACCESS
                    else -> null
                }
                when {
                    refusal != null -> {
                        // The job is gone, and with it what belonged to it: a list without a job can not be corrected.
                        _analysis.update { if (it.matchId == matchId) it.withJob(null).copy(jobLoad = refusal) else it }
                        if (refusal == JobLoad.NO_ACCESS) markAccess(matchId, noAccess = true)
                        return@launch
                    }
                    error != null -> _analysis.update { if (it.matchId == matchId) it.copy(jobLoad = JobLoad.UNREACHABLE) else it }
                    else -> {
                        mustStart = false
                        publish(matchId, job)
                    }
                }
                val working = job?.state == AnalysisSummary.QUEUED || job?.state == AnalysisSummary.PROCESSING
                if (error == null && !working) return@launch
                delay(if (error != null) 5_000 else 2_000)
            }
        }
    }

    fun stopWatchingAnalysis() {
        watchJob?.cancel()
    }

    fun setReviewDetail(detail: Boolean) {
        _analysis.update { it.copy(detail = detail) }
        loadReview(keepCursor = true)
    }

    /**
     * Loads the cards and puts the cursor on the first one that still needs an answer. With
     * [keepCursor] the card on screen stays on screen, wherever the new cards put it; when it is
     * gone, the winner question of its rally takes its place, and when the rally is gone too, the
     * first card that needs an answer. With [atRally] the winner question of that rally comes
     * up, answered or not.
     */
    fun loadReview(keepCursor: Boolean = false, atRally: String? = null) {
        val ui = _analysis.value
        val matchId = ui.matchId ?: return
        val jobId = ui.job?.id ?: return
        viewModelScope.launch {
            val deck = withContext(Dispatchers.IO) { runCatching { api().reviewCards(jobId, if (ui.detail) "ALL" else "SCORE") }.getOrNull() }
            _analysis.update { current ->
                // The cards belong to the match and the job they were asked for. By the time a slow
                // answer arrives another match may be open, and its screen must never get these cards.
                if (current.matchId != matchId || current.job?.id != jobId) return@update current
                if (deck == null) return@update current.copy(reviewProblem = ReviewProblem.LOAD_FAILED)
                fun indexOf(found: (ReviewCard) -> Boolean) = deck.cards.indexOfFirst(found).takeIf { it >= 0 }
                val shown = current.card
                val cursor = when {
                    atRally != null -> indexOf { it.rallyId == atRally && it.questionType == ReviewCard.RALLY_WINNER }
                    !keepCursor || current.deck == null -> null
                    // Past the last card stays past the last card.
                    shown == null -> deck.cards.size
                    // A correction renumbers the rallies, so the card is found by what it is and not by where it
                    // was. A card that left the deck gives way to the winner question of its own rally: the place
                    // it stood at may now hold the card of another rally.
                    else -> indexOf { it.id == shown.id } ?: indexOf { it.rallyId == shown.rallyId && it.questionType == ReviewCard.RALLY_WINNER }
                } ?: indexOf { it.open } ?: deck.cards.size
                current.copy(deck = deck, cursor = cursor, reviewProblem = null)
            }
        }
    }

    /** Asks again for what the review screen is missing: the job first, then its cards. */
    fun retryReview(matchId: String, atRally: String? = null) {
        val ui = _analysis.value
        if (ui.matchId != matchId || ui.job == null) {
            watchAnalysis(matchId, startIfMissing = false)
        } else {
            _analysis.update { it.copy(reviewProblem = null) }
            loadReview(keepCursor = ui.deck != null, atRally = atRally)
        }
    }

    fun moveReview(step: Int) = _analysis.update { it.copy(cursor = (it.cursor + step).coerceIn(0, it.deck?.cards?.size ?: 0), reviewProblem = null) }

    /**
     * The decision that is on its way, with its idempotency key. The same decision sent again
     * reuses the key, so a decision whose answer was lost is answered from the server's record
     * instead of being refused as a stale version.
     */
    @Volatile private var pendingDecision: Pair<String, String>? = null

    /** One tap is one decision: it is saved at once and the next open card comes up. */
    fun answerReview(selection: String) {
        val ui = _analysis.value
        val card = ui.card ?: return
        val matchId = ui.matchId ?: return
        val jobId = ui.job?.id ?: return
        if (ui.saving) return
        val decision = "${card.id}|$selection|${card.version}"
        val key = pendingDecision?.takeIf { it.first == decision }?.second ?: UUID.randomUUID().toString().also { pendingDecision = decision to it }
        _analysis.update { it.copy(saving = true, reviewProblem = null) }
        viewModelScope.launch {
            val decided = withContext(Dispatchers.IO) {
                try {
                    Result.success(api().decide(card.id, selection, card.version, key))
                } catch (error: IOException) {
                    Result.failure(error)
                }
            }
            val job = decided.getOrElse { error ->
                // A refusal is the server's final word on this key. A lost connection is not: the
                // server may have stored the decision, so the key is kept for the retry.
                if (error is ApiException) pendingDecision = null
                val stale = error is ApiException && error.code == "REVIEW_STALE_VERSION"
                _analysis.update { if (it.matchId == matchId) it.copy(saving = false, reviewProblem = if (stale) ReviewProblem.STALE else ReviewProblem.SAVE_FAILED) else it }
                if (stale && _analysis.value.matchId == matchId) loadReview(keepCursor = true)
                return@launch
            }
            // The decision is stored from here on, whatever happens to the reload of the cards.
            publish(matchId, job)
            val deck = withContext(Dispatchers.IO) { runCatching { api().reviewCards(jobId, if (ui.detail) "ALL" else "SCORE") }.getOrNull() }
            _analysis.update { current ->
                if (current.matchId != matchId || current.job?.id != jobId) return@update current
                // The screen still shows the card as it was. The key stays, so the same tap again is
                // answered from the server's record.
                if (deck == null) return@update current.copy(saving = false, reviewProblem = ReviewProblem.LOAD_FAILED)
                pendingDecision = null
                // The answer may have created follow-up cards right after this one; otherwise move on.
                val here = deck.cards.indexOfFirst { it.id == card.id }
                val next = deck.cards.withIndex().firstOrNull { (index, candidate) -> index > here && candidate.open }?.index ?: deck.cards.size
                current.copy(deck = deck, cursor = next, saving = false, reviewProblem = null)
            }
        }
    }

    // ----- Corrections of the rally list -----

    /** Reads the rally list of the open match's job. */
    fun loadRallies(matchId: String) {
        val ui = _analysis.value
        val jobId = ui.job?.id?.takeIf { ui.matchId == matchId } ?: return
        _analysis.update { it.copy(ralliesLoad = RalliesLoad.LOADING) }
        viewModelScope.launch {
            val read = withContext(Dispatchers.IO) { runCatching { api().rallies(jobId) } }
            val list = read.getOrNull()
            // No answer and a refusal are both failures of the connection or of the request. An answer that came
            // and can not be read as a list is another matter: asking again will not change it. Nor will asking
            // again for a match the server does not show to this account.
            val failure = when {
                (read.exceptionOrNull() as? ApiException)?.code == "NOT_A_MEMBER" -> RalliesLoad.NO_ACCESS
                read.exceptionOrNull() is IOException -> RalliesLoad.UNREACHABLE
                else -> RalliesLoad.OUTDATED
            }
            if (list != null) markAccess(matchId, noAccess = false) else if (failure == RalliesLoad.NO_ACCESS) markAccess(matchId, noAccess = true)
            _analysis.update { current ->
                if (current.matchId != matchId || current.job?.id != jobId) return@update current
                when {
                    // A list the server no longer shows to this account is not shown either, and no correction is made on it.
                    failure == RalliesLoad.NO_ACCESS && list == null -> current.copy(ralliesLoad = failure, rallies = null)
                    list == null -> current.copy(ralliesLoad = failure)
                    else -> current.copy(rallies = newer(list, current.rallies), ralliesLoad = RalliesLoad.READY)
                }
            }
        }
    }

    /** Asks again for what a screen of the rally list is missing: the job first, then its list. */
    fun retryRallies(matchId: String) {
        val ui = _analysis.value
        if (ui.matchId != matchId || ui.job == null) watchAnalysis(matchId, startIfMissing = false) else loadRallies(matchId)
    }

    /** Two reads of the list can answer in the wrong order. The list never goes back to an older version. */
    private fun newer(read: RallyList, held: RallyList?) = if (held != null && held.version > read.version) held else read

    // Each correction says how it is recognised on a list: the ids of the rallies it leaves
    // there, or null when the list does not show it. That is how the app finds a correction
    // whose answer was lost.

    fun addRally(startMs: Long, endMs: Long, onDone: () -> Unit) =
        correct(onDone, found = { list -> list.rallies.filter { !it.fromAnalyzer && it.startMs == startMs && it.endMs == endMs }.map { it.id }.ifEmpty { null } }) { api, jobId, version ->
            api.addRally(jobId, startMs, endMs, version)
        }

    fun moveRally(rallyId: String, startMs: Long, endMs: Long, onDone: () -> Unit) =
        correct(onDone, found = { list -> list.rallies.filter { it.id == rallyId && it.startMs == startMs && it.endMs == endMs }.map { it.id }.ifEmpty { null } }) { api, _, version ->
            api.moveRally(rallyId, startMs, endMs, version)
        }

    /** The later part keeps the id of the rally and starts where the cut was made. The new part ends there. */
    fun splitRally(rallyId: String, atMs: Long, onDone: () -> Unit) =
        correct(onDone, found = { list ->
            list.rallies.firstOrNull { it.id == rallyId && it.startMs == atMs }?.let { later -> listOfNotNull(list.rallies.firstOrNull { it.endMs == atMs }?.id, later.id) }
        }) { api, _, version -> api.splitRally(rallyId, atMs, version) }

    /** The rally stays and starts where the rally before it started. */
    fun mergeWithPrevious(rallyId: String, onDone: () -> Unit) {
        val before = _analysis.value.rallies?.rallies.orEmpty()
        val startOfPrevious = before.firstOrNull { it.id == rallyId }?.let { rally -> before.firstOrNull { it.sequence == rally.sequence - 1 } }?.startMs
        correct(onDone, found = { list -> list.rallies.filter { startOfPrevious != null && it.id == rallyId && it.startMs == startOfPrevious }.map { it.id }.ifEmpty { null } }) { api, _, version ->
            api.mergeWithPrevious(rallyId, version)
        }
    }

    /** The user's statement that the list holds every rally of the video, for the list that is on screen. */
    fun confirmRallyList(onDone: () -> Unit) =
        correct(onDone, found = { list -> emptyList<String>().takeIf { list.confirmed } }) { api, jobId, version ->
            Correction(emptyList(), emptyList(), api.confirmRallyList(jobId, version))
        }

    /**
     * Sets the rule the server counts the sets of the open match with. The server recounts the
     * list and moves it on, so a confirmation given under the old rule lapses. Answers and
     * corrections stay, so the cards are not read again. A lost answer is found on the list: the
     * list names the rule it was counted with.
     */
    fun changeMatchRules(rules: MatchRules, onDone: () -> Unit) {
        val matchId = _analysis.value.matchId ?: return
        val serverMatchId = _state.value.matches.firstOrNull { it.localId == matchId }?.session?.serverMatchId ?: return
        // The request names the rule of the list the app last read: the one the list on screen was counted with.
        val seen = _analysis.value.rallies?.rules ?: return
        correct(onDone, found = { list -> emptyList<String>().takeIf { list.rules == rules } }, readsCards = false) { api, _, _ ->
            api.putMatchRules(serverMatchId, rules, seen)
            Correction(emptyList(), emptyList(), job = null)
        }
    }

    fun clearCorrectionProblem() = _analysis.update { it.copy(correctionProblem = null, overlapsRally = null) }

    /**
     * Sends one correction made on the list that is on screen. Whatever the outcome, the list is
     * read again afterwards, so the screen shows what the server holds. A correction is never
     * sent a second time by the app.
     *
     * When no answer came, the app looks for the correction on the list it reads next ([found]).
     * A list version that moved on would not tell: a winner decision that was still on its way
     * moves it too. The app looks the same way when the server says that the list has moved on:
     * that is the answer to a second attempt whose first attempt got through unseen, and telling
     * the user to redo the correction would apply it to another rally.
     */
    private fun correct(
        onDone: () -> Unit,
        found: (RallyList) -> List<String>?,
        readsCards: Boolean = true,
        send: (ApiClient, String, Int) -> Correction,
    ) {
        val ui = _analysis.value
        val matchId = ui.matchId ?: return
        val jobId = ui.job?.id ?: return
        val list = ui.rallies ?: return
        if (ui.correcting) return
        _analysis.update { it.copy(correcting = true, correctionProblem = null, overlapsRally = null) }
        viewModelScope.launch {
            val sent = withContext(Dispatchers.IO) {
                try {
                    Result.success(send(api(), jobId, list.version))
                } catch (error: IOException) {
                    Result.failure(error)
                } catch (error: JSONException) {
                    // The server answered with something this app can not read. The list read below tells whether the correction was made.
                    Result.failure(error)
                }
            }
            // The screen that sent the correction keeps the user until the last of these reads is over, so each of them waits the short wait.
            val wait = api().correctionTimeoutMs
            val reloaded = withContext(Dispatchers.IO) { runCatching { api().rallies(jobId, wait) }.getOrNull() }
            val error = sent.exceptionOrNull()
            val refusal = (error as? ApiException)?.code
            // The list, or the rule it was counted with, moved on since the screen read it.
            val stale = refusal == "RALLY_LIST_STALE" || refusal == "MATCH_RULES_STALE"
            val onList = if (sent.isFailure && (refusal == null || stale)) reloaded?.let(found) else null
            val applied = sent.isSuccess || onList != null
            val problem = when {
                applied -> null
                refusal == null -> CorrectionProblem.UNREACHABLE
                // The list moved on and the list of now did not arrive: there is nothing to redo the correction on.
                stale && reloaded == null -> CorrectionProblem.UNREACHABLE
                stale -> CorrectionProblem.STALE
                else -> when (refusal) {
                    "RALLY_OVERLAP" -> CorrectionProblem.OVERLAP
                    "INVALID_INTERVAL", "SPLIT_POINT_OUTSIDE" -> CorrectionProblem.OUTSIDE
                    "LIST_NOT_READY" -> CorrectionProblem.NOT_READY
                    "MOCK_LIST_NOT_CONFIRMABLE" -> CorrectionProblem.DEMO
                    else -> CorrectionProblem.REFUSED
                }
            }
            val blocker = (error as? ApiException)?.takeIf { problem == CorrectionProblem.OVERLAP }?.details?.optString("rallyId")
            val affected = sent.getOrNull()?.affected?.map { it.id } ?: onList.orEmpty()
            val job = sent.getOrNull()?.job
            val deck = if (applied && readsCards) withContext(Dispatchers.IO) { runCatching { api().reviewCards(jobId, if (ui.detail) "ALL" else "SCORE", wait) }.getOrNull() } else null
            var shown = false
            _analysis.update { current ->
                if (current.matchId != matchId || current.job?.id != jobId) return@update current
                shown = true
                val rallies = when {
                    reloaded != null -> newer(reloaded, current.rallies)
                    // The server applied the correction and the list of now did not arrive. The list in
                    // memory is the one from before the correction, so it is not shown as the list.
                    applied -> null
                    else -> current.rallies
                }
                val cursor = deck?.cards?.let { cards ->
                    fun indexOf(found: (ReviewCard) -> Boolean) = cards.indexOfFirst(found).takeIf { it >= 0 }
                    // The corrected rally asks its question first. When it has nothing to ask, the
                    // review stays on that rally, and without a corrected rally on the card it showed.
                    indexOf { it.open && it.rallyId in affected }
                        ?: indexOf { it.rallyId in affected && it.questionType == ReviewCard.RALLY_WINNER }
                        ?: current.card?.let { shown -> indexOf { it.id == shown.id } }
                        ?: indexOf { it.open } ?: cards.size
                }
                // The job of the answer comes to the screen together with its list. A job that ran ahead of
                // the list would make the list look old, and the editor would show "loading" over its video.
                current.copy(
                    job = job ?: current.job,
                    rallies = rallies, ralliesLoad = if (reloaded == null) RalliesLoad.UNREACHABLE else RalliesLoad.READY,
                    deck = deck ?: current.deck, cursor = cursor ?: current.cursor,
                    correcting = false, correctionProblem = problem,
                    overlapsRally = rallies?.rallies?.firstOrNull { it.id == blocker }?.sequence,
                )
            }
            if (shown && job != null) rememberAnalysis(matchId, job.summary)
            if (applied && shown) {
                if (job == null) watch(matchId, startIfMissing = false, startNow = false)
                onDone()
            }
        }
    }

    fun loadReport(matchId: String) {
        val serverMatchId = _state.value.matches.firstOrNull { it.localId == matchId }?.session?.serverMatchId
        if (_analysis.value.matchId != matchId) _analysis.value = AnalysisUi(matchId = matchId)
        if (serverMatchId == null) {
            _analysis.update { it.copy(reportLoad = ReportLoad.NONE, report = null) }
            return
        }
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { api().report(serverMatchId) } }
            // No answer and a refusal are failures of the connection or of the request. An answer that came and
            // can not be read as a report is the answer of another server version: asking again will not change it.
            // Nor will asking again for a match the server does not show to this account.
            val failure = when {
                (outcome.exceptionOrNull() as? ApiException)?.code == "NOT_A_MEMBER" -> ReportLoad.NO_ACCESS
                outcome.exceptionOrNull() is IOException -> ReportLoad.UNREACHABLE
                else -> ReportLoad.OUTDATED
            }
            if (outcome.getOrNull() != null) markAccess(matchId, noAccess = false) else if (failure == ReportLoad.NO_ACCESS) markAccess(matchId, noAccess = true)
            _analysis.update { current ->
                if (current.matchId != matchId) return@update current
                outcome.fold(
                    onSuccess = { report -> current.copy(report = report, reportLoad = if (report == null) ReportLoad.NONE else ReportLoad.READY) },
                    // A report the server no longer shows to this account leaves the screen; the players table reads it too.
                    onFailure = { if (failure == ReportLoad.NO_ACCESS) current.copy(reportLoad = failure, report = null) else current.copy(reportLoad = failure) },
                )
            }
        }
    }

    // ----- Teams and rosters (kept on the phone only) -----

    fun addTeam(name: String): Team? {
        val clean = name.trim().take(80)
        if (clean.length < 2 || _state.value.teams.any { it.name.equals(clean, ignoreCase = true) }) return null
        val team = Team(UUID.randomUUID().toString(), clean)
        change { it.copy(teams = it.teams + team) }
        return team
    }

    fun deleteTeam(teamId: String) = change { state ->
        state.copy(
            teams = state.teams.filterNot { it.id == teamId },
            // A draft keeps the typed name but no longer points at the removed team.
            draft = state.draft.copy(
                homeTeamId = state.draft.homeTeamId.takeUnless { it == teamId },
                awayTeamId = state.draft.awayTeamId.takeUnless { it == teamId },
            ),
        )
    }

    /** Adds a player. The number is optional; a number already used in the team is refused. */
    fun addPlayer(teamId: String, name: String, jersey: Int?): Boolean {
        val team = _state.value.teams.firstOrNull { it.id == teamId } ?: return false
        if (name.isBlank() && jersey == null) return false
        if (jersey != null && (jersey !in 1..99 || team.players.any { it.jersey == jersey })) return false
        val player = Player(UUID.randomUUID().toString(), name.trim().take(60), jersey)
        change { state -> state.copy(teams = state.teams.map { if (it.id == teamId) it.copy(players = it.players + player) else it }) }
        return true
    }

    fun removePlayer(teamId: String, playerId: String) = change { state ->
        state.copy(teams = state.teams.map { team -> if (team.id == teamId) team.copy(players = team.players.filterNot { it.id == playerId }) else team })
    }

    fun testServer() {
        _state.update { it.copy(serverTest = ServerTest.TESTING) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    if (api().health().probeAvailable) ServerTest.OK else ServerTest.OK_NO_PROBE
                } catch (error: IOException) {
                    ServerTest.UNREACHABLE
                }
            }
            _state.update { it.copy(serverTest = result) }
        }
    }

    /** Deletes a camera recording the user explicitly chose to remove in Profil. */
    fun deleteRecording(path: String) {
        val file = File(path)
        // A file the draft or an unfinished upload still reads from stays: without it that upload could never finish.
        if (file.parentFile == recordingsFolder && !_state.value.recordingInUse(path)) file.delete()
        _state.update { it.copy(recordings = listRecordings()) }
    }

    private fun listRecordings(): List<RecordingFile> =
        recordingsFolder.listFiles().orEmpty().filter { it.isFile && it.length() > 0 }
            .sortedByDescending { it.lastModified() }
            .map { RecordingFile(it.name, it.absolutePath, it.length()) }

    // ----- The server's teams (slice B4; design 9.3, 10.1, 10.3 and 10.7; plan P6) -----
    // Every team call notes the session it started with. Its answer counts only while that session is still the
    // signed-in one (plan D1), and switchAccount cancels the calls of the session that leaves.

    /** The parent of every team call. switchAccount and another server cancel it at once and put a new one in its place. */
    @Volatile private var teamCalls = SupervisorJob(viewModelScope.coroutineContext[Job])
    /** The read of the list on its way. A new read replaces it. */
    @Volatile private var listJob: Job? = null
    /**
     * The Takım Kur whose answer this phone has not had yet: the id it chose and the draft it sent. A repeat after a lost
     * answer sends the same id, so the server answers 200 with the team the lost request created (design 9.6). A process
     * that ends loses it (plan U39). The server's 201 or 200 spends it, and so does TEAM_ID_TAKEN. Once a read of the list
     * shows the team under this id, only the same draft repeats the id; another draft is another team and takes a new id
     * (P6-C4).
     */
    @Volatile private var pendingCreation: PendingCreation? = null

    private data class PendingCreation(val id: String, val draft: TeamDraft, val shown: Boolean = false)

    /** Counts the team screen's reads, so that the answer of a read another one replaced reaches no screen (P6-C2). */
    private var teamReads = 0L
    /** A screen asked for the teams. Only from then on does another address read them again (as [signupWanted] does). */
    @Volatile private var teamsWanted = false

    private fun cancelTeamCalls() {
        teamCalls.cancel()
        teamCalls = SupervisorJob(viewModelScope.coroutineContext[Job])
        listJob = null
        pendingCreation = null
    }

    private fun resetTeams() {
        cancelTeamCalls()
        _state.update { it.copy(serverTeams = TeamsUi()) }
    }

    /** Changes the team state, only while [token] is still the signed-in session's (plan D1). */
    private fun teamState(token: String, transform: (TeamsUi) -> TeamsUi) =
        _state.update { if (session?.token != token) it else it.copy(serverTeams = transform(it.serverTeams)) }

    /**
     * Runs a team call of the session [token] off the main thread. The request carries that session's token, whichever
     * session is signed in when it leaves, and its outcome reaches [done] only while that session is still signed in.
     */
    private fun <T> teamCall(token: String, call: (ApiClient) -> T, done: (Result<T>) -> Unit): Job {
        val api = ApiClient(
            baseUrl(_state.value.settings.serverTarget), correctionTimeoutMs ?: ApiClient.CORRECTION_TIMEOUT_MS, token = token, onAuthRequired = ::onSessionEnded,
        )
        return viewModelScope.launch(teamCalls) {
            val outcome = withContext(Dispatchers.IO) { runCatching { call(api) } }
            if (session?.token == token) done(outcome)
        }
    }

    /** The list without the team [id], which the server no longer shows to this account. A screen's read of it keeps its outcome. */
    private fun TeamsUi.without(id: String) = copy(teams = teams?.filterNot { it.id == id }, opened = opened?.let { if (it.id == id) it.copy(team = null) else it })

    /** The list with [team] as the server just sent it, in its place. A list that is unread stays unread. */
    private fun TeamsUi.listing(team: TeamInfo, add: Boolean = false) = copy(
        teams = teams?.let { list -> if (list.any { it.id == team.id }) list.map { if (it.id == team.id) team else it } else if (add) list + team else list },
    )

    /** The list and the team screen's read with [team] as a team action's answer sent it. */
    private fun TeamsUi.with(team: TeamInfo, add: Boolean = false) =
        listing(team, add).copy(opened = opened?.let { if (it.id == team.id) it.copy(team = team) else it })

    /** The list showed these teams: a lost Takım Kur whose id is among them made its team (P6-C4). */
    private fun noteShown(teams: List<TeamInfo>) {
        val pending = pendingCreation ?: return
        if (teams.any { it.id == pending.id }) pendingCreation = pending.copy(shown = true)
    }

    /**
     * Reads the teams of the signed-in account: on Home's opening, on the Teams tab's opening, on every resume, after every
     * team action (plan D2). Without a session nothing goes out. A new read replaces the one on its way.
     */
    fun loadTeams() {
        teamsWanted = true
        val token = session?.token ?: return
        listJob?.cancel()
        teamState(token) { it.copy(loading = true) }
        listJob = teamCall(token, { it.teams() }) { outcome ->
            outcome.getOrNull()?.let(::noteShown)
            // The team screen's read keeps its own outcome: a list that answers clears only the list's problem (P6-C1).
            teamState(token) { ui ->
                outcome.fold(
                    onSuccess = { ui.copy(teams = it, loading = false, loadProblem = null) },
                    onFailure = { ui.copy(loading = false, loadProblem = TeamProblem.of(it)) },
                )
            }
        }
    }

    /**
     * Reads the team [id] for the team screen [screen] (its back stack entry). The screen's read keeps its outcome until
     * the screen reads again, and only the answer of the screen's latest read reaches it (P6-C1, P6-C2). A team the server
     * no longer shows to this account, or no longer holds, leaves the list, whichever read learned it.
     *
     * The first answer of a screen sets its Son katılanlar count. A captain whose team has members who joined after the
     * captain's mark marks them seen up to the newest join of that same answer, so the app marks only the members the
     * screen counts (plan D4, P6-C3). A later answer of the same screen, after a rotation or a return from As ve Yedek
     * Yerleri, marks nothing: a member who joined meanwhile stays new for the badge and the next visit. A member sends
     * nothing, because the route answers a member 403 CAPTAIN_ONLY.
     */
    fun openTeam(id: String, screen: String = id) {
        val token = session?.token ?: return
        val number = ++teamReads
        teamState(token) { ui ->
            val same = ui.opened?.takeIf { it.screen == screen && it.id == id }
            ui.copy(opened = same?.copy(number = number, problem = null) ?: TeamRead(screen, id, number, team = ui.teamOf(id)))
        }
        teamCall(token, { it.team(id) }) { outcome ->
            val team = outcome.getOrNull()
            var markUpTo: String? = null
            teamState(token) { ui ->
                markUpTo = null
                // The answer of the screen's latest read, or null when another read replaced this one.
                val read = ui.opened?.takeIf { it.number == number }
                if (team == null) {
                    val problem = TeamProblem.of(outcome.exceptionOrNull()!!)
                    val left = if (problem == TeamProblem.NoAccess || problem == TeamProblem.Gone) ui.without(id) else ui
                    if (read == null) left else left.copy(opened = left.opened?.copy(problem = problem))
                } else if (read == null) {
                    // An earlier screen's answer still brings its team's list entry up to date, unless a later read of
                    // the same team is on its way or answered.
                    if (ui.opened?.id == id) ui else ui.listing(team)
                } else {
                    val joins = if (team.captain) team.newJoins ?: 0 else 0
                    if (read.recentJoins == null && joins > 0) markUpTo = team.members.maxOfOrNull { it.joinedAt }
                    ui.listing(team).copy(opened = read.copy(team = team, problem = null, recentJoins = read.recentJoins ?: joins))
                }
            }
            val upTo = markUpTo
            if (upTo != null && session?.token == token) teamCall(token, { it.joinsSeen(id, upTo) }) { loadTeams() }
        }
    }

    /** Clears the refusal of the last team action: its screen was edited or left. */
    fun clearTeamProblem() = _state.update { it.copy(serverTeams = it.serverTeams.copy(problem = null)) }

    /**
     * Runs one team action of the signed-in session. One action at a time: a second tap while one is on its way does
     * nothing. [apply] puts the server's answer into the team state and [then] runs after it, both only while the session
     * that sent the action is signed in. A refusal becomes the action's problem, and a team the server no longer shows or
     * holds leaves the list. Every action ends with a read of the list, so the badge and Home follow it.
     */
    private fun <T> teamAction(
        teamId: String?, call: (ApiClient) -> T, onRefused: (TeamProblem) -> TeamProblem = { it }, apply: (TeamsUi, T) -> TeamsUi, then: (T) -> Unit,
    ): Boolean {
        val token = session?.token ?: return false
        var started = false
        _state.update {
            started = false
            if (session?.token != token || it.serverTeams.busy) return@update it
            started = true
            it.copy(serverTeams = it.serverTeams.copy(busy = true, problem = null))
        }
        if (!started) return false
        teamCall(token, call) { outcome ->
            outcome.fold(
                onSuccess = { answer ->
                    teamState(token) { apply(it, answer).copy(busy = false) }
                    then(answer)
                },
                onFailure = { error ->
                    val problem = onRefused(TeamProblem.of(error))
                    teamState(token) { ui ->
                        val left = if (teamId != null && (problem == TeamProblem.NoAccess || problem == TeamProblem.Gone)) ui.without(teamId) else ui
                        left.copy(busy = false, problem = problem)
                    }
                },
            )
            loadTeams()
        }
        return true
    }

    /**
     * Takım Kur: creates the team under an id this phone chose and keeps it until the server answered 201 or 200, so a
     * repeat after a lost answer creates no second team (design 9.6). While the list has not shown the team of a lost
     * answer, every Takım Kur repeats its id: the server then answers with the team the lost request made, if it made one.
     * Once the list has shown it, the same draft still repeats the id and gets that team, and another draft takes a new
     * id, because the user sets up another team (P6-C4). [onCreated] gets the team's id.
     */
    fun createTeam(draft: TeamDraft, onCreated: (String) -> Unit) {
        if (session == null) return
        val sent = draft.copy(name = draft.name.trim())
        val pending = pendingCreation
        val creation = when {
            pending == null -> PendingCreation(UUID.randomUUID().toString(), sent)
            pending.shown && pending.draft == sent -> pending
            pending.shown -> PendingCreation(UUID.randomUUID().toString(), sent)
            else -> PendingCreation(pending.id, sent)
        }
        val id = creation.id
        val started = teamAction(
            null,
            { it.putTeam(id, sent.name, sent.substituteSeats, sent.captainSeat, sent.schedule, sent.jersey) },
            onRefused = { problem ->
                // Another account's team has this id: the next try takes a new one.
                if (problem == TeamProblem.IdTaken && pendingCreation?.id == id) pendingCreation = null
                problem
            },
            apply = { ui, (team, _) -> ui.with(team, add = true).copy(opened = TeamRead(screen = null, id = team.id, number = 0, team = team)) },
        ) { (team, _) ->
            if (pendingCreation?.id == id) pendingCreation = null
            onCreated(team.id)
        }
        // A Takım Kur that did not start (another team action is on its way) leaves the pending one as it was.
        if (started) pendingCreation = creation
    }

    /**
     * Sends the substitute count and the members whose seat type changes (captain). A refusal keeps the screen and its
     * input; a refusal of the substitute type after a starter moved down carries that fact for the hint (S8).
     */
    fun saveSeats(teamId: String, substituteSeats: Int, moves: Map<String, Seat>, onDone: () -> Unit) {
        val held = _state.value.serverTeams.teamOf(teamId)?.members?.associate { it.userId to it.seat }.orEmpty()
        val movedStarterDown = moves.any { (userId, seat) -> seat == Seat.SUBSTITUTE && held[userId] == Seat.STARTER }
        teamAction(
            teamId, { it.teamSeats(teamId, substituteSeats, moves) },
            onRefused = { problem -> if (problem is TeamProblem.SeatsBelowHolders) problem.copy(movedStarterDown = movedStarterDown) else problem },
            apply = { ui, team -> ui.with(team) },
        ) { onDone() }
    }

    /** Sends the team's name and usual match day and time (captain). A null [schedule] clears it. */
    fun saveTeamSettings(teamId: String, name: String, schedule: Schedule?, onDone: () -> Unit) {
        teamAction(teamId, { it.teamSettings(teamId, name.trim(), schedule) }, apply = { ui, team -> ui.with(team) }) { onDone() }
    }

    // ----- Accounts (design 3, 10.3, 10.5 and 10.7; docs/API_V1.md section 10) -----
    // The app says that someone is signed in, registered, renamed or logged out only after the
    // server confirmed it. A password never leaves the call it was typed for.

    @Volatile private var accountJob: Job? = null

    /**
     * Counts the starts and the ends of account calls, so it is odd while one runs. An account read
     * that left while a call was on its way, or before one started, may describe the account as it
     * was before that call: a name the user has just changed, a session whose deletion is in doubt.
     * Its answer is dropped (review finding F7). The next visit of the profile reads again.
     */
    private val accountCallMarks = java.util.concurrent.atomic.AtomicInteger()

    /**
     * The SHA-256 of each username whose registration went out and got no answer, or an answer that leaves
     * the outcome unknown (a 5xx, or a JSON success the app can not read), with the time of the mark: the server
     * may have opened that account all the same (third review, A1 and B3). A registration that never left
     * the phone gets no mark (fourth review, D2). registrations.bin keeps them sealed through a restart; a
     * phone that can not seal the file keeps them in memory until the app ends. A sign-in of that username,
     * LOGIN_FAILED for the login that looks for its account, or the end of its day ends a mark. Changed under [accountLock].
     */
    @Volatile private var unansweredRegistrations: Map<String, Long> = registrations.load(System.currentTimeMillis())

    /** The clock of the registration marks. Tests move it on with this instead of waiting a day. */
    internal var clock: () -> Long = System::currentTimeMillis

    fun login(username: String, password: String) {
        val name = normalizeUsername(username)
        accountCall { api -> signIn(api.login(name, password, deviceTokens.tokenFor(name)), api.baseUrl) }
    }

    /**
     * Opens an account. [operatorCode] is the operator's sign-up code (kayıt kodu); a blank one is
     * none. Under open sign-up (the default from server 0.10) the code is optional and gives the larger
     * quotas; a server started with VOLLEYSTAT_SIGNUP=link needs one until invite links come (slice B5).
     */
    fun register(displayName: String, username: String, password: String, operatorCode: String?) {
        val name = normalizeUsername(username)
        val code = operatorCode?.trim()?.ifEmpty { null }
        accountCall { api ->
            val result = try {
                api.register(name, displayName.trim(), password, code)
            } catch (error: ApiException) {
                // A 5xx, or a JSON success the app can not read, may come after the server opened the account, as a lost
                // answer may (third review, B3): the mark makes the next try look for it. A 3xx, a 4xx, a 511 or a 2xx that
                // is not JSON marks nothing (R1, S1, S3).
                if (error.leavesOutcomeUnknown) {
                    markRegistration(name)
                    throw error
                }
                // The server's gate needs a link or a code (design 10.1): the form takes the LINK layout and shows its code field.
                // A server of slice B1 sends no reason, and its only closed gate is this one (AuthProblem reads it so).
                if (AuthProblem.of(error) == AuthProblem.SignupClosed(SignupClosedReason.NO_LINK)) noteSignupMode(api.baseUrl, SignupMode.LINK)
                findLostRegistration(api, name, password, error)
            } catch (error: RequestNotSent) {
                // Nothing of the request left the phone, so no account can have opened: no mark sends a login later (D2).
                throw error
            } catch (error: IOException) {
                // No answer came, and the server may have opened the account all the same.
                markRegistration(name)
                throw error
            }
            // The sign-in ends the mark of this username, if it had one.
            signIn(result, api.baseUrl)
        }
    }

    /**
     * A registration whose answer was lost may have opened the account. Its repeat then meets that
     * account: the spent code answers first ([refused] SIGNUP_CODE_INVALID), otherwise the taken
     * username. A login with the same password tells the two apart. It goes only for a username whose
     * registration got no answer: for any other username it would count a failed attempt against
     * someone else's account. Throws [refused] when the registration's answer stands.
     */
    private fun findLostRegistration(api: ApiClient, name: String, password: String, refused: ApiException): SignedIn {
        if (!registrationUnanswered(name) || (refused.code != "USERNAME_TAKEN" && refused.code != "SIGNUP_CODE_INVALID")) throw refused
        return try {
            api.login(name, password, deviceTokens.tokenFor(name))
        } catch (error: ApiException) {
            // Only LOGIN_FAILED answers the question: no account of this name has this password, so the
            // registration's answer stands and the mark goes. Any other answer (SERVER_BUSY, TOO_MANY_ATTEMPTS)
            // stands as itself, as a missing answer does, and the mark stays for the next try.
            if (error.code != "LOGIN_FAILED") throw error
            forgetRegistration(name)
            throw refused
        }
    }

    /**
     * Whether [username] carries a mark that still counts. A mark a day old sends no login: by then the username may
     * name someone else's account (fourth review, D2). It leaves memory and the file here.
     */
    private fun registrationUnanswered(username: String): Boolean {
        val at = unansweredRegistrations[sha256Hex(username)] ?: return false
        if (UnansweredRegistrationStore.current(at, clock())) return true
        forgetRegistration(username)
        return false
    }

    /** Marks [username]: its registration got no answer. A phone that can not seal registrations.bin keeps the mark in memory. */
    private fun markRegistration(username: String) {
        synchronized(accountLock) {
            val now = clock()
            unansweredRegistrations = unansweredRegistrations + (sha256Hex(username) to now)
            runCatching { registrations.add(username, now) }
        }
    }

    /** Ends the mark of [username]: a sign-in found the account, or LOGIN_FAILED showed that it is not there with this password. */
    private fun forgetRegistration(username: String) {
        synchronized(accountLock) {
            unansweredRegistrations = unansweredRegistrations - sha256Hex(username)
            runCatching { registrations.remove(username) }
        }
    }

    /**
     * Ends the session on the server, then on the phone. Without an answer the session stays,
     * because the server may still hold it, unless [evenWithoutAnswer]: then the phone lets it go
     * and the server ends it when it runs out. The device token stays (design 3.2).
     */
    fun logout(evenWithoutAnswer: Boolean = false) {
        val leaving = session ?: return
        accountCall(endsSession = false) { api ->
            val answered = try {
                api.logout()
                true
            } catch (error: IOException) {
                // A session the server already ended is as closed as one it ends now.
                val gone = error is ApiException && error.code == "AUTH_REQUIRED"
                if (!gone && !evenWithoutAnswer) throw error
                false
            }
            // The server's 204 shows that the account is still there. Without it a deletion in doubt stays in doubt (S1).
            leave(leaving, ended = false, offer = !answered)
        }
    }

    fun changeDisplayName(displayName: String) {
        val current = session ?: return
        accountCall(change = AccountChange.DISPLAY_NAME) { api -> updateUser(current, api.changeDisplayName(displayName.trim()), AccountChange.DISPLAY_NAME) }
    }

    /** The server ends every other session of the account and forgets its device tokens, this phone's included. */
    fun changePassword(currentPassword: String, newPassword: String) {
        val current = session ?: return
        accountCall(change = AccountChange.PASSWORD) { api ->
            val user = api.changePassword(currentPassword, newPassword)
            runCatching { deviceTokens.forget(current.username) }
            updateUser(current, user, AccountChange.PASSWORD)
        }
    }

    /**
     * Deletes the account on the server and then everything the phone keeps for it: its records,
     * rosters, draft, camera recordings, thumbnails, session and device token. Nothing on the phone
     * goes before the server's 204.
     *
     * A deletion whose answer was lost may have deleted the account (review finding S1). The session
     * keeps a mark of it in session.bin, through a restart. When a repeat then meets an ended session,
     * a login with the password the user just typed asks the server whether the account is still
     * there. If it is, the deletion goes out again with the new session. If the login fails, the
     * session leaves the phone and the login screen offers to delete the account's data
     * ([UnconfirmedDeletion]). Any other end of a marked session leaves the same offer.
     */
    fun deleteAccount(password: String) {
        if (session == null) return
        // The 401 is read here and not by the client: an ended session may be the sign of a deletion that got through.
        accountCall(endsSession = false) { api -> deleteOnServer(api, password, mayLogin = true) }
    }

    private fun deleteOnServer(api: ApiClient, password: String, mayLogin: Boolean) {
        val current = session ?: return
        val earlier = current.deletionUnanswered
        // Marked before the request goes, so a process that ends before the answer remembers it too.
        val sent = markDeletion(current, unanswered = true)
        try {
            api.deleteAccount(password)
        } catch (error: RequestNotSent) {
            // Nothing of the request left the phone, so the server can not have deleted the account (fifth check, P1).
            // The mark is what it was before it.
            markDeletion(sent, unanswered = earlier)
            throw error
        } catch (error: ApiException) {
            when {
                error.code != "AUTH_REQUIRED" -> {
                    when {
                        // The server found the session and refused: the account is still there.
                        error.code in ANSWERS_OF_A_LIVE_SESSION -> markDeletion(sent, unanswered = false)
                        // A refusal that comes before the server acts on the account (a 422 for a password of more than
                        // 1024 characters, say) says nothing about this deletion. The mark is what it was before it. Only the
                        // API's own envelope makes a 4xx the server's refusal: a 4xx without it (HTTP_404 from a proxy's HTML
                        // page, say) is an answer the app can not read, and the mark stays (fifth check, P4).
                        error.status in 400..499 && !error.code.startsWith("HTTP_") -> markDeletion(sent, unanswered = earlier)
                        // A 5xx may come after the deletion: the mark stays, as for a missing answer.
                    }
                    throw error
                }
                // The server ended the session before this deletion, and no earlier deletion is in doubt.
                !earlier -> {
                    leave(sent, ended = true, offer = false)
                    throw error
                }
                mayLogin -> deleteAfterEndedSession(sent, password)
                else -> leave(sent, ended = true)
            }
            return
        }
        // The server's 204: the account is gone, and everything the phone keeps for it goes too.
        leave(sent, ended = false, offer = false)
        folders.delete(sent.userId)
        runCatching { deviceTokens.forget(sent.username) }
        synchronized(accountLock) {
            // Another call of this session may have met the ended session before the 204 came, and its 401 left a
            // doubt about this account. The 204 is the server's word that the account is gone: the doubt goes, in
            // memory and on the disk, and the next account in doubt, if any, takes its place on the login screen.
            dropDoubt(sent.userId)
            _state.update { it.copy(account = it.account.copy(confirmed = AccountChange.DELETED, unconfirmedDeletion = inDoubt.firstOrNull())) }
        }
    }

    /**
     * The server ended the session after a deletion whose answer was lost. A login with the same
     * password and this phone's device token asks whether the account is still there. Only
     * LOGIN_FAILED answers that it is not, and it can also mean a password changed on another phone:
     * the app then deletes nothing on its own and leaves the choice to the user.
     */
    private fun deleteAfterEndedSession(sent: Session, password: String) {
        val asked = ApiClient(baseUrl(_state.value.settings.serverTarget))
        val again = try {
            asked.login(sent.username, password, deviceTokens.tokenFor(sent.username))
        } catch (error: ApiException) {
            // Any other failure answers nothing. The session stays on the phone with its mark, and a repeat asks again.
            if (error.code != "LOGIN_FAILED") throw error
            null
        }
        if (again == null || again.user.id != sent.userId) {
            // The username now names another account: its new session is closed again, and none of it reaches the phone.
            again?.let { other -> runCatching { ApiClient(asked.baseUrl, token = other.token).logout() } }
            leave(sent, ended = true)
            return
        }
        try {
            signIn(again, asked.baseUrl)
        } catch (moved: AnswerFromAnotherServer) {
            // The address changed while the login was on its way. The session that the server ended goes too: the next
            // call would carry its token to the other address. The login showed that the account is still there, so no
            // doubt about its deletion stays behind.
            leave(sent, ended = true, offer = false)
            throw moved
        }
        // The account is still there, and the user asked for its deletion: the request goes out again with the new session.
        deleteOnServer(client(endsSession = false), password, mayLogin = false)
    }

    /**
     * Sets or clears [Session.deletionUnanswered] on [current], if it is still the signed-in
     * session, and writes it to session.bin. A phone that can not write it keeps it in memory.
     */
    private fun markDeletion(current: Session, unanswered: Boolean): Session = synchronized(accountLock) {
        val live = session?.takeIf { it.token == current.token } ?: return@synchronized current
        if (live.deletionUnanswered == unanswered) return@synchronized live
        val next = live.copy(deletionUnanswered = unanswered)
        session = next
        runCatching { sessions.save(next) }
        next
    }

    /**
     * Deletes what the phone keeps for the account of [AccountUi.unconfirmedDeletion]: its folder
     * (matches, rosters, draft, recordings and thumbnails) and its device token. The user asked for
     * it on the login screen. The server is not asked, and nothing says that it deleted the account.
     */
    fun deleteUnconfirmedAccountData() = synchronized(accountLock) {
        val doubt = _state.value.account.unconfirmedDeletion ?: return@synchronized
        // A login on its way may be one of this account, and the signed-in account's folder never goes this way.
        if (_state.value.account.busy || session?.userId == doubt.userId) return@synchronized
        runCatching { folders.delete(doubt.userId) }
        // A folder that is still there keeps the offer, so the user can try again.
        if (runCatching { folders.of(doubt.userId).root.exists() }.getOrDefault(true)) return@synchronized
        // Without its username the phone can not name the device token. Its own device.bin then went with the old key.
        doubt.username?.let { name -> runCatching { deviceTokens.forget(name) } }
        dropDoubt(doubt.userId)
        // The next account in doubt, if any, takes its place on the login screen.
        _state.update {
            it.copy(account = it.account.copy(unconfirmedDeletion = inDoubt.firstOrNull(), confirmed = AccountChange.PHONE_DATA_DELETED, deletedFromPhone = doubt))
        }
    }

    /**
     * Keeps [doubt] first among the accounts in doubt, in memory and in deletions.bin (F1). A phone
     * that can not write the file keeps it in memory until the app ends. Runs under [accountLock].
     */
    private fun keepDoubt(doubt: UnconfirmedDeletion) {
        inDoubt = listOf(doubt) + inDoubt.filterNot { it.userId == doubt.userId }
        runCatching { doubtStore.add(doubt.userId, doubt.username) }
    }

    /** Takes the account [userId] off the accounts in doubt. Runs under [accountLock]. */
    private fun dropDoubt(userId: String) {
        if (inDoubt.none { it.userId == userId }) return
        inDoubt = inDoubt.filterNot { it.userId == userId }
        runCatching { doubtStore.remove(userId) }
    }

    /** Reads the account from the server: a name changed on another phone, or a session that ended there. */
    fun refreshAccount() {
        val current = session ?: return
        val mark = accountCallMarks.get()
        viewModelScope.launch {
            val user = withContext(Dispatchers.IO) { runCatching { client(endsSession = true).me() }.getOrNull() } ?: return@launch
            updateUser(current, user, null, readAt = mark)
        }
    }

    /**
     * The answer to the question about the data of app 0.7: [move] puts it into the signed-in
     * account, otherwise it stays where it is and no account reads it. The phone asks only once.
     */
    fun answerLegacy(move: Boolean) {
        val target = space ?: return
        synchronized(accountLock) {
            // One answer at a time: a second tap before the first one landed is no second answer.
            if (_state.value.account.legacy == null || _state.value.account.legacyMoving) return
            _state.update { it.copy(account = it.account.copy(legacyMoving = true)) }
        }
        viewModelScope.launch {
            val done = withContext(Dispatchers.IO) {
                !move || runCatching {
                    synchronized(storeLock) {
                        folders.moveLegacyInto(target)
                        if (space === target) {
                            val stored = target.store.load()
                            _state.update { it.copy(matches = stored.matches, teams = stored.teams, draft = stored.draft, recordings = listRecordings()) }
                        }
                    }
                }.isSuccess
            }
            if (!done) {
                // The question stays, and the next answer tries again: a record that already moved is not added twice.
                // Every failed attempt gives the buttons back, the second one too, whatever the disk said before.
                _state.update { it.copy(saveFailed = true, account = it.account.copy(legacyMoving = false)) }
                return@launch
            }
            changeDevice { it.copy(legacyHandled = true) }
            _state.update { it.copy(account = it.account.copy(legacy = null, legacyMoving = false)) }
        }
    }

    /**
     * The reason a password screen gave when it went while its change was on its way (third review, B1).
     * The confirmation of that call names it; the end of the call and the next call drop it. Set under [accountLock].
     */
    @Volatile private var notHandedOverAtConfirmation: PasswordNotHandedOver? = null

    /**
     * The password screen could not hand the new password to the password manager, for [reason]. Only a
     * confirmed change counts: a confirmation that already came is named at once, and one still on its way
     * (the user left the screen, B1) is named when it comes. A refusal or a missing answer names nothing.
     */
    fun newPasswordNotHandedOver(reason: PasswordNotHandedOver) = synchronized(accountLock) {
        val account = _state.value.account
        when {
            account.confirmed == AccountChange.PASSWORD -> _state.update { it.copy(account = it.account.copy(newPasswordNotHandedOver = reason)) }
            account.busy -> notHandedOverAtConfirmation = reason
        }
    }

    fun clearAccountProblem() =
        _state.update { it.copy(account = it.account.copy(problem = null, confirmed = null, sessionEnded = false, newPasswordNotHandedOver = null, afterLeave = null)) }

    /**
     * Stops an account call that waits to repeat after SERVER_BUSY, for a screen the user left (design 10.7, R10). A
     * request already on its way goes on and is not repeated; its outcome stays (fourth review, D1).
     */
    fun cancelAccountCall() {
        accountJob?.cancel()
    }

    /**
     * Runs one account call off the main thread. SERVER_BUSY is asked again after the server's
     * Retry-After, a few times; the server answers it before it counts or writes anything, so a
     * repeat changes nothing twice. Every other failure becomes the account's problem.
     */
    private fun accountCall(endsSession: Boolean = true, change: AccountChange? = null, call: (ApiClient) -> Unit) {
        val caller = session
        synchronized(accountLock) {
            if (_state.value.account.busy) return
            _state.update { it.copy(account = it.account.copy(busy = true, problem = null, confirmed = null, newPasswordNotHandedOver = null, afterLeave = null)) }
            notHandedOverAtConfirmation = null
            accountCallMarks.incrementAndGet()
        }
        accountJob = viewModelScope.launch {
            // Set inside the call's own context. A screen the user left cancels this job, and the cancelled withContext
            // throws on its way back, after the request went out and its answer came: the failure must not go with it (D1).
            var failure: Exception? = null
            try {
                withContext(Dispatchers.IO) { failure = callUntilNotBusy(endsSession, call) }
            } finally {
                // An ended session left the account before this line, and the login screen says so in its own words
                // (sessionEnded, or the offer of a deletion in doubt). The same reason as a problem would stand there twice (F3).
                val ended = failure
                val problem = ended?.let(AuthProblem::of)?.takeUnless { it == AuthProblem.SessionEnded }
                // The user left the screen of this call while its request was on its way (StopAccountCallOnLeave).
                val left = !isActive
                // The form of a call that was left is gone, and its problem would stand on whatever form comes next. The
                // profile says how a change of its own ended, while the session that asked for it is still signed in.
                val afterLeave = if (left && ended != null && problem != null && change != null && caller != null && session?.token == caller.token) {
                    OutcomeAfterLeave(change, problem, outcomeUnknownAfter(ended))
                } else {
                    null
                }
                accountCallMarks.incrementAndGet()
                // A screen that went during this call speaks for this call only.
                notHandedOverAtConfirmation = null
                _state.update { it.copy(account = it.account.copy(busy = false, retryingAfterBusy = false, problem = problem.takeUnless { left }, afterLeave = afterLeave)) }
            }
        }
    }

    /** Runs [call] and repeats it after SERVER_BUSY, at most [BUSY_TRIES] times in all. Returns the failure that ended it, or null. */
    private suspend fun callUntilNotBusy(endsSession: Boolean, call: (ApiClient) -> Unit): Exception? {
        repeat(BUSY_TRIES) { attempt ->
            val error = try {
                call(client(endsSession))
                return null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                error
            }
            if (error !is ApiException || error.code != "SERVER_BUSY" || attempt == BUSY_TRIES - 1) return error
            // The form says that the server is busy and that the app sends again (T5); the next try waits for an answer again.
            retryingAfterBusy(true)
            // The wait can be cancelled: a screen the user left stops it (cancelAccountCall), and accountCall clears the mark.
            waitAfterBusy(1_000L * (error.retryAfterSeconds ?: 1).coerceIn(0, LONGEST_BUSY_WAIT_SECONDS))
            retryingAfterBusy(false)
        }
        return null
    }

    /**
     * How the app waits out a SERVER_BUSY answer before the next try. Tests count the wait on a clock of
     * their own with this instead of waiting it out. The wait stays cancellable everywhere else.
     */
    internal var waitAfterBusy: suspend (millis: Long) -> Unit = { delay(it) }

    private fun retryingAfterBusy(waiting: Boolean) = _state.update { it.copy(account = it.account.copy(retryingAfterBusy = waiting)) }

    /**
     * Takes the server's confirmation of a login or a registration that went to [sentTo]. Runs on the
     * thread of the call. An answer from another address than the one the app talks to now opens no
     * session: its token would go to that other address with every later call (second review, R2-B).
     * The session goes back closed to the server that opened it, and nothing of it reaches the disk.
     */
    private fun signIn(result: SignedIn, sentTo: String) {
        if (sentTo != baseUrl(_state.value.settings.serverTarget)) {
            runCatching { ApiClient(sentTo, token = result.token).logout() }
            throw AnswerFromAnotherServer()
        }
        val next = Session(
            result.token, result.user.id, result.user.username, result.user.displayName, result.expiresAt,
            passwordTemporary = result.user.passwordTemporary,
        )
        // An id that can not name a folder throws here, before anything is written.
        val nextSpace = folders.of(next.userId)
        result.deviceToken?.let { token -> runCatching { deviceTokens.remember(next.username, token) } }
        // A phone that can not seal the session keeps it in memory: the user is signed in until the app ends.
        val kept = runCatching { sessions.save(next) }.isSuccess
        val content = if (_state.value.settings.legacyHandled) null else runCatching { folders.legacyContent() }.getOrNull()
        // The question belongs to the first account it is put to (S2). Any other account gets no question and no
        // titles, and the old data stays unread until that account answers.
        val digest = sha256Hex(next.userId)
        val legacy = content?.takeIf { _state.value.settings.legacyAskedUser.let { asked -> asked == null || asked == digest } }
        synchronized(accountLock) {
            // A login of an account in doubt shows that it is still there: its folder is its own again.
            dropDoubt(result.user.id)
            // The account of a registration without an answer is found, whichever screen signed it in.
            forgetRegistration(result.user.username)
            switchAccount(next, nextSpace) {
                it.copy(
                    user = result.user, sessionEnded = false, legacy = legacy?.let(::question), legacyMoving = false,
                    unconfirmedDeletion = inDoubt.firstOrNull(),
                    // A session the keystore could not seal is not a full disk: Home says that the next start asks for the password (S3).
                    sessionNotKept = !kept,
                )
            }
        }
        changeDevice {
            it.copy(accountSeen = true, legacyHandled = it.legacyHandled || content == null, legacyAskedUser = if (legacy != null) digest else it.legacyAskedUser)
        }
    }

    private fun question(old: LegacyContent) = LegacyQuestion(old.titles, old.teams, old.recordings, old.draft)

    /** The server ended the session that carried a call (401 AUTH_REQUIRED). An answer about an earlier session ends no later one. */
    private fun onSessionEnded(token: String) {
        session?.takeIf { it.token == token }?.let { leave(it, ended = true) }
    }

    /**
     * Leaves [leaving] on the phone, if it is still the signed-in session. The account's folder stays
     * for its next login. A session that carries a deletion without an answer leaves an
     * [UnconfirmedDeletion] behind, and its text then stands in place of `session_ended`, unless
     * [offer] is false: an answer showed that the account is still there, or its folder goes anyway.
     */
    private fun leave(leaving: Session, ended: Boolean, offer: Boolean = true) = synchronized(accountLock) {
        val current = session?.takeIf { it.token == leaving.token } ?: return@synchronized
        val doubt = if (offer && current.deletionUnanswered) UnconfirmedDeletion(current.userId, current.username) else null
        // The doubt is written before the session goes: a process that ends in between keeps one of the two (F1).
        doubt?.let(::keepDoubt)
        runCatching { sessions.clear() }
        // The sign-up mode belongs to the server, which stays the same.
        switchAccount(null, null) {
            AccountUi(busy = it.busy, retryingAfterBusy = it.retryingAfterBusy, sessionEnded = ended && doubt == null, unconfirmedDeletion = inDoubt.firstOrNull(), signup = it.signup)
        }
    }

    /**
     * Puts [next] and its folder in place of the signed-in account, with that folder's data on
     * screen. A running upload and a followed analysis of the account that leaves stop; the server
     * keeps what it has, and the upload resumes from its offset after the next login.
     */
    private fun switchAccount(next: Session?, nextSpace: AccountSpace?, account: (AccountUi) -> AccountUi) {
        uploadJob?.cancel()
        watchJob?.cancel()
        // The team calls of the account that leaves stop, and its pending Takım Kur id goes (plan D1).
        cancelTeamCalls()
        synchronized(storeLock) {
            session = next
            space = nextSpace
            val stored = nextSpace?.let { runCatching { it.store.load() }.getOrNull() } ?: StoredState()
            _state.update {
                it.copy(
                    matches = stored.matches, teams = stored.teams, draft = stored.draft, upload = null, readingVideo = false,
                    sourceProblem = null, removalProblem = null, saveFailed = false, recordings = listRecordings(), account = account(it.account),
                    serverTeams = TeamsUi(),
                )
            }
        }
        _analysis.value = AnalysisUi()
        pendingDecision = null
    }

    /**
     * Takes the account as the server now describes it, if [current] is still the signed-in session.
     * An account read names the [accountCallMarks] it left at in [readAt]: an account call that ran
     * meanwhile makes its answer an old one.
     */
    private fun updateUser(current: Session, user: AccountUser, change: AccountChange?, readAt: Int? = null) = synchronized(accountLock) {
        if (session?.token != current.token || user.id != current.userId) return@synchronized
        if (readAt != null && (readAt % 2 != 0 || readAt != accountCallMarks.get())) return@synchronized
        // The server answered for the session, so a deletion that got no answer did not happen (S1).
        val next = current.copy(displayName = user.displayName, passwordTemporary = user.passwordTemporary, deletionUnanswered = false)
        session = next
        val kept = runCatching { sessions.save(next) }.isSuccess
        // The password screen went while this change was on its way: no screen is left to hand the password over (B1).
        val notHandedOver = notHandedOverAtConfirmation.takeIf { change == AccountChange.PASSWORD }
        if (notHandedOver != null) notHandedOverAtConfirmation = null
        // A session that was never kept stays unkept until a save works: its own text says so, not the disk text.
        _state.update {
            it.copy(
                account = it.account.copy(
                    user = user, confirmed = change ?: it.account.confirmed, sessionNotKept = it.account.sessionNotKept && !kept,
                    newPasswordNotHandedOver = notHandedOver ?: it.account.newPasswordNotHandedOver,
                ),
                saveFailed = it.saveFailed || (!kept && !it.account.sessionNotKept),
            )
        }
    }
}
