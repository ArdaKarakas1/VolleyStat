package com.voleybolveveleybol.volleystat

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voleybolveveleybol.volleystat.data.ApiClient
import com.voleybolveveleybol.volleystat.data.ApiException
import com.voleybolveveleybol.volleystat.data.LocalStore
import com.voleybolveveleybol.volleystat.data.MatchInfo
import com.voleybolveveleybol.volleystat.data.StoredState
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
import com.voleybolveveleybol.volleystat.model.Player
import com.voleybolveveleybol.volleystat.model.ServerTarget
import com.voleybolveveleybol.volleystat.model.Settings
import com.voleybolveveleybol.volleystat.model.Team
import com.voleybolveveleybol.volleystat.model.uploadFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
)

enum class ServerTest { IDLE, TESTING, OK, OK_NO_PROBE, UNREACHABLE }

enum class SourceProblem { CAMERA_MISSING, CAMERA_EMPTY, UNREADABLE }

data class RecordingFile(val name: String, val path: String, val sizeBytes: Long)

/**
 * Why the server did not confirm the removal of a match. UNCONFIRMED: no answer came, so the app
 * does not know what the server holds. VERIFYING: the server is still checking the match's upload.
 * REFUSED: the server answered and deleted nothing.
 */
enum class RemovalFailure { UNCONFIRMED, VERIFYING, REFUSED }

data class RemovalProblem(val matchId: String, val failure: RemovalFailure)

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
) {
    /** A camera file that the draft or an upload that has not finished still reads from. */
    fun recordingInUse(path: String) = draft.video?.location == path || matches.any { it.check == null && it.video.location == path }
}

/** How much the app knows about the analysis job of the match that is open. */
/** VIDEO_MISSING: the server answered that it no longer holds a usable video for this match. */
enum class JobLoad { LOADING, READY, NO_JOB, NO_ANALYZER, VIDEO_MISSING, UNREACHABLE }

enum class ReviewProblem { STALE, SAVE_FAILED, LOAD_FAILED }

enum class ReportLoad { LOADING, READY, NONE, UNREACHABLE }

/** OUTDATED: the server answered, but not with a rally list this app can read. An older server answers that way. */
enum class RalliesLoad { LOADING, READY, UNREACHABLE, OUTDATED }

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
    private val store = LocalStore(File(application.filesDir, "volleystat.json"))
    private val inspector = VideoInspector(application)
    private val recordingsFolder = File(application.filesDir, "recordings")
    private var uploadJob: Job? = null

    private val _state: MutableStateFlow<AppUiState>
    val state: StateFlow<AppUiState>

    init {
        val saved = store.load()
        _state = MutableStateFlow(
            AppUiState(
                matches = saved.matches, teams = saved.teams, draft = saved.draft, settings = saved.settings,
                serverConfigured = baseUrl(saved.settings.serverTarget).isNotBlank(), recordings = listRecordings(),
            )
        )
        state = _state.asStateFlow()
    }

    /** Tests point the view model at a stand-in server with this. It is null everywhere else. */
    internal var baseUrlOverride: String? = null

    private fun baseUrl(target: ServerTarget) =
        baseUrlOverride ?: if (target == ServerTarget.EMULATOR) BuildConfig.API_URL_EMULATOR else BuildConfig.API_URL_USB

    fun api() = ApiClient(baseUrl(_state.value.settings.serverTarget))

    /** Base address for streaming a video from the server (review clips). */
    fun serverBaseUrl(): String = baseUrl(_state.value.settings.serverTarget)

    /** Every change to matches, draft or settings goes through here so it reaches the disk. */
    private fun change(transform: (AppUiState) -> AppUiState) {
        _state.update(transform)
        // Changes arrive from the main thread and from the upload thread. Writing whatever is
        // current under one lock means a slower writer can never put an older state on disk.
        val saved = synchronized(store) {
            val current = _state.value
            // A full disk must not take the app down in the middle of a keystroke or an upload.
            runCatching { store.save(StoredState(current.matches, current.teams, current.draft, current.settings)) }.isSuccess
        }
        if (_state.value.saveFailed == saved) _state.update { it.copy(saveFailed = !saved) }
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
                        match = MatchInfo(match.homeTeam, match.awayTeam, match.name, match.playedOn, match.venue, match.kind.name),
                        fileName = "video.${format.extension}", mimeType = format.mimeType,
                        bytes = VideoBytes(total) { inspector.open(match.video) },
                        onSession = { session -> change { state -> state.copy(matches = state.matches.map { m -> if (m.localId == matchId) m.copy(session = session) else m }) } },
                        onProgress = { phase, done, all -> _state.update { it.copy(upload = it.upload?.copy(phase = phase, doneBytes = done, totalBytes = all)) } },
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
                        matches = state.matches.map { m -> if (m.localId == matchId) m.copy(check = check, checkedAtMs = System.currentTimeMillis()) else m },
                    )
                }
            }.onFailure { error ->
                val sent = _state.value.matches.firstOrNull { it.localId == matchId }?.session?.sentBytes ?: 0
                _state.update { state ->
                    val current = state.upload ?: UploadProgress(matchId)
                    state.copy(
                        upload = when (error) {
                            is UploadCancelled -> current.copy(running = false, phase = null, doneBytes = sent)
                            is UploadException -> current.copy(running = false, phase = null, doneBytes = sent, failure = error.failure, serverCode = error.serverCode)
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
                _state.update { it.copy(removalProblem = RemovalProblem(matchId, failure)) }
            }
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

    fun setServerTarget(target: ServerTarget) =
        change { it.copy(settings = it.settings.copy(serverTarget = target), serverTest = ServerTest.IDLE, serverConfigured = baseUrl(target).isNotBlank()) }

    fun setDisplayName(name: String) = change { it.copy(settings = it.settings.copy(displayName = name.take(40))) }

    fun markWelcomeSeen() = change { it.copy(settings = it.settings.copy(welcomeSeen = true)) }

    /** Remembers the last job state the analysis screens saw, so lists can show it offline. */
    fun rememberAnalysis(matchId: String, summary: AnalysisSummary?) = change { state ->
        state.copy(matches = state.matches.map { if (it.localId == matchId && it.analysis != summary) it.copy(analysis = summary) else it })
    }

    // ----- Analysis, review and report of the open match -----

    private val _analysis = MutableStateFlow(AnalysisUi())
    val analysis: StateFlow<AnalysisUi> = _analysis.asStateFlow()
    private var watchJob: Job? = null

    private fun videoIdOf(matchId: String) = _state.value.matches.firstOrNull { it.localId == matchId }?.check
        ?.takeIf { it.status == com.voleybolveveleybol.volleystat.model.VideoCheck.READY }?.videoId

    private fun publish(matchId: String, job: AnalysisJob?) {
        _analysis.update { if (it.matchId == matchId) it.withJob(job) else it }
        rememberAnalysis(matchId, job?.summary)
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
                    else -> null
                }
                when {
                    refusal != null -> {
                        _analysis.update { if (it.matchId == matchId) it.copy(jobLoad = refusal, job = null) else it }
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
    fun retryReview(matchId: String) {
        val ui = _analysis.value
        if (ui.matchId != matchId || ui.job == null) {
            watchAnalysis(matchId, startIfMissing = false)
        } else {
            _analysis.update { it.copy(reviewProblem = null) }
            loadReview(keepCursor = ui.deck != null)
        }
    }

    fun moveReview(step: Int) = _analysis.update { it.copy(cursor = (it.cursor + step).coerceIn(0, it.deck?.cards?.size ?: 0), reviewProblem = null) }

    /**
     * The decision that is on its way, with its idempotency key. The same decision sent again
     * reuses the key, so a decision whose answer was lost is answered from the server's record
     * instead of being refused as a stale version.
     */
    private var pendingDecision: Pair<String, String>? = null

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
            // and can not be read as a list is another matter: asking again will not change it.
            val failure = if (read.exceptionOrNull() is IOException) RalliesLoad.UNREACHABLE else RalliesLoad.OUTDATED
            _analysis.update { current ->
                if (current.matchId != matchId || current.job?.id != jobId) return@update current
                if (list == null) current.copy(ralliesLoad = failure) else current.copy(rallies = newer(list, current.rallies), ralliesLoad = RalliesLoad.READY)
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
    private fun correct(onDone: () -> Unit, found: (RallyList) -> List<String>?, send: (ApiClient, String, Int) -> Correction) {
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
                }
            }
            val reloaded = withContext(Dispatchers.IO) { runCatching { api().rallies(jobId) }.getOrNull() }
            val error = sent.exceptionOrNull()
            val refusal = (error as? ApiException)?.code
            val onList = if (sent.isFailure && (refusal == null || refusal == "RALLY_LIST_STALE")) reloaded?.let(found) else null
            val applied = sent.isSuccess || onList != null
            val problem = when {
                applied -> null
                refusal == null -> CorrectionProblem.UNREACHABLE
                // The list moved on and the list of now did not arrive: there is nothing to redo the correction on.
                refusal == "RALLY_LIST_STALE" && reloaded == null -> CorrectionProblem.UNREACHABLE
                else -> when (refusal) {
                    "RALLY_LIST_STALE" -> CorrectionProblem.STALE
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
            val deck = if (applied) withContext(Dispatchers.IO) { runCatching { api().reviewCards(jobId, if (ui.detail) "ALL" else "SCORE") }.getOrNull() } else null
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
            val outcome = withContext(Dispatchers.IO) {
                try {
                    Result.success(api().report(serverMatchId))
                } catch (error: IOException) {
                    Result.failure(error)
                }
            }
            _analysis.update { current ->
                if (current.matchId != matchId) return@update current
                outcome.fold(
                    onSuccess = { report -> current.copy(report = report, reportLoad = if (report == null) ReportLoad.NONE else ReportLoad.READY) },
                    onFailure = { current.copy(reportLoad = ReportLoad.UNREACHABLE) },
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
}
