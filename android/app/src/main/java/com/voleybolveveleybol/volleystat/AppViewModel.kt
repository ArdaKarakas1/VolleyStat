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
import com.voleybolveveleybol.volleystat.model.AnalysisSummary
import com.voleybolveveleybol.volleystat.model.MatchReport
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
    /** Match whose server-side removal failed; the record is kept and the screen says so. */
    val removalFailedFor: String? = null,
)

/** How much the app knows about the analysis job of the match that is open. */
enum class JobLoad { LOADING, READY, NO_JOB, NO_ANALYZER, UNREACHABLE }

enum class ReviewProblem { STALE, SAVE_FAILED, LOAD_FAILED }

enum class ReportLoad { LOADING, READY, NONE, UNREACHABLE }

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
) {
    val card: ReviewCard? get() = deck?.cards?.getOrNull(cursor)
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

    private fun baseUrl(target: ServerTarget) =
        if (target == ServerTarget.EMULATOR) BuildConfig.API_URL_EMULATOR else BuildConfig.API_URL_USB

    fun api() = ApiClient(baseUrl(_state.value.settings.serverTarget))

    /** Base address for streaming a video from the server (review clips). */
    fun serverBaseUrl(): String = baseUrl(_state.value.settings.serverTarget)

    /** Every change to matches, draft or settings goes through here so it reaches the disk. */
    private fun change(transform: (AppUiState) -> AppUiState) {
        _state.update(transform)
        // Changes arrive from the main thread and from the upload thread. Writing whatever is
        // current under one lock means a slower writer can never put an older state on disk.
        synchronized(store) {
            val current = _state.value
            store.save(StoredState(current.matches, current.teams, current.draft, current.settings))
        }
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
     * Removes a match: stops its upload if one is running, deletes what the server holds
     * (the video, or the unfinished part) and then forgets the record. If the server can
     * not be reached nothing is forgotten, so the app never claims a deletion that did not happen.
     */
    fun removeMatch(matchId: String, onDone: () -> Unit) {
        viewModelScope.launch {
            if (_state.value.upload?.matchId == matchId) stopUpload()
            val match = _state.value.matches.firstOrNull { it.localId == matchId } ?: return@launch
            val removed = withContext(Dispatchers.IO) {
                try {
                    val videoId = match.check?.videoId
                    val uploadId = match.session.uploadId
                    when {
                        videoId != null -> api().deleteVideo(videoId)
                        uploadId != null -> try {
                            api().abortUpload(uploadId)
                        } catch (error: ApiException) {
                            // The upload became a video while the user was deciding.
                            if (error.code != "UPLOAD_COMPLETED") throw error
                            api().deleteVideo(error.details.getString("videoId"))
                        }
                    }
                    true
                } catch (error: ApiException) {
                    error.status == 404  // already gone on the server
                } catch (error: IOException) {
                    false
                } catch (error: org.json.JSONException) {
                    false
                }
            }
            if (removed) {
                forget(match)
                onDone()
            } else {
                _state.update { it.copy(removalFailedFor = matchId) }
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
                removalFailedFor = null,
            )
        }
    }

    fun clearRemovalFailure() = _state.update { it.copy(removalFailedFor = null) }

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
        _analysis.update { if (it.matchId == matchId) it.copy(job = job, jobLoad = if (job == null) JobLoad.NO_JOB else JobLoad.READY) else it }
        rememberAnalysis(matchId, job?.summary)
    }

    /**
     * Follows the job of a match while its screen is open: asks for one if the video never had
     * one, then polls while the server is still working. Only the server's own states are shown.
     */
    fun watchAnalysis(matchId: String, startIfMissing: Boolean = true) {
        if (_analysis.value.matchId != matchId) _analysis.value = AnalysisUi(matchId = matchId)
        watchJob?.cancel()
        val videoId = videoIdOf(matchId) ?: return
        watchJob = viewModelScope.launch {
            while (isActive) {
                val outcome = withContext(Dispatchers.IO) {
                    try {
                        val job = api().latestAnalysis(videoId) ?: if (startIfMissing) api().startAnalysis(videoId, UUID.randomUUID().toString()) else null
                        Result.success(job)
                    } catch (error: IOException) {
                        Result.failure(error)
                    }
                }
                val job = outcome.getOrNull()
                val error = outcome.exceptionOrNull()
                when {
                    error is ApiException && error.code == "MODEL_NOT_CONFIGURED" -> {
                        _analysis.update { if (it.matchId == matchId) it.copy(jobLoad = JobLoad.NO_ANALYZER, job = null) else it }
                        return@launch
                    }
                    error != null -> _analysis.update { if (it.matchId == matchId) it.copy(jobLoad = JobLoad.UNREACHABLE) else it }
                    else -> publish(matchId, job)
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

    /** Starts a new job for a video whose last job failed or was cancelled. */
    fun restartAnalysis(matchId: String) {
        val videoId = videoIdOf(matchId) ?: return
        _analysis.update { it.copy(jobLoad = JobLoad.LOADING) }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { api().startAnalysis(videoId, UUID.randomUUID().toString()) } }
            watchAnalysis(matchId, startIfMissing = false)
        }
    }

    fun setReviewDetail(detail: Boolean) {
        _analysis.update { it.copy(detail = detail) }
        loadReview(keepCursor = true)
    }

    /** Loads the cards and puts the cursor on the first one that still needs an answer. */
    fun loadReview(keepCursor: Boolean = false) {
        val ui = _analysis.value
        val jobId = ui.job?.id ?: return
        viewModelScope.launch {
            val deck = withContext(Dispatchers.IO) { runCatching { api().reviewCards(jobId, if (ui.detail) "ALL" else "SCORE") }.getOrNull() }
            _analysis.update { current ->
                if (deck == null) return@update current.copy(reviewProblem = ReviewProblem.LOAD_FAILED)
                val cursor = if (keepCursor) current.cursor.coerceIn(0, deck.cards.size) else deck.cards.indexOfFirst { it.open }.let { if (it < 0) deck.cards.size else it }
                current.copy(deck = deck, cursor = cursor, reviewProblem = null)
            }
        }
    }

    fun moveReview(step: Int) = _analysis.update { it.copy(cursor = (it.cursor + step).coerceIn(0, it.deck?.cards?.size ?: 0), reviewProblem = null) }

    /** One tap is one decision: it is saved at once and the next open card comes up. */
    fun answerReview(selection: String) {
        val ui = _analysis.value
        val card = ui.card ?: return
        val matchId = ui.matchId ?: return
        val jobId = ui.job?.id ?: return
        if (ui.saving) return
        _analysis.update { it.copy(saving = true, reviewProblem = null) }
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                try {
                    val job = api().decide(card.id, selection, card.version, UUID.randomUUID().toString())
                    Result.success(job to api().reviewCards(jobId, if (ui.detail) "ALL" else "SCORE"))
                } catch (error: IOException) {
                    Result.failure(error)
                }
            }
            outcome.onSuccess { (job, deck) ->
                publish(matchId, job)
                _analysis.update { current ->
                    // The answer may have created follow-up cards right after this one; otherwise move on.
                    val here = deck.cards.indexOfFirst { it.id == card.id }
                    val next = deck.cards.withIndex().firstOrNull { (index, candidate) -> index > here && candidate.open }?.index ?: deck.cards.size
                    current.copy(deck = deck, cursor = next, saving = false)
                }
            }.onFailure { error ->
                val stale = error is ApiException && error.code == "REVIEW_STALE_VERSION"
                _analysis.update { it.copy(saving = false, reviewProblem = if (stale) ReviewProblem.STALE else ReviewProblem.SAVE_FAILED) }
                if (stale) loadReview(keepCursor = true)
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
        if (file.parentFile == recordingsFolder) file.delete()
        _state.update { it.copy(recordings = listRecordings()) }
    }

    private fun listRecordings(): List<RecordingFile> =
        recordingsFolder.listFiles().orEmpty().filter { it.isFile && it.length() > 0 }
            .sortedByDescending { it.lastModified() }
            .map { RecordingFile(it.name, it.absolutePath, it.length()) }
}
