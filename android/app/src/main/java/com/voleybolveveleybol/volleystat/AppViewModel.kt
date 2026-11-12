package com.voleybolveveleybol.volleystat

import android.app.Application
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voleybolveveleybol.volleystat.data.ApiClient
import com.voleybolveveleybol.volleystat.data.ApiException
import com.voleybolveveleybol.volleystat.data.LocalStore
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
import com.voleybolveveleybol.volleystat.model.MatchRecord
import com.voleybolveveleybol.volleystat.model.ServerTarget
import com.voleybolveveleybol.volleystat.model.uploadFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
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
    val draft: Draft = Draft(),
    val upload: UploadProgress? = null,
    val serverTarget: ServerTarget = ServerTarget.EMULATOR,
    /** False in builds without a server address (release): upload is unavailable there. */
    val serverConfigured: Boolean = true,
    val serverTest: ServerTest = ServerTest.IDLE,
    val recordings: List<RecordingFile> = emptyList(),
    val readingVideo: Boolean = false,
    val sourceProblem: SourceProblem? = null,
    /** Match whose server-side removal failed; the record is kept and the screen says so. */
    val removalFailedFor: String? = null,
)

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
                matches = saved.matches, draft = saved.draft, serverTarget = saved.serverTarget,
                serverConfigured = baseUrl(saved.serverTarget).isNotBlank(), recordings = listRecordings(),
            )
        )
        state = _state.asStateFlow()
    }

    private fun baseUrl(target: ServerTarget) =
        if (target == ServerTarget.EMULATOR) BuildConfig.API_URL_EMULATOR else BuildConfig.API_URL_USB

    private fun api() = ApiClient(baseUrl(_state.value.serverTarget))

    /** Every change to matches, draft or settings goes through here so it reaches the disk. */
    private fun change(transform: (AppUiState) -> AppUiState) {
        _state.update(transform)
        // Changes arrive from the main thread and from the upload thread. Writing whatever is
        // current under one lock means a slower writer can never put an older state on disk.
        synchronized(store) {
            val current = _state.value
            store.save(StoredState(current.matches, current.draft, current.serverTarget))
        }
    }

    // ----- New analysis draft -----

    fun setTeams(teamA: String, teamB: String) = change { it.copy(draft = it.draft.copy(teamA = teamA, teamB = teamB)) }

    /** Creates the private file the system camera will write to and remembers it across process death. */
    fun prepareCapture(): Uri {
        recordingsFolder.mkdirs()
        // An earlier camera round trip that recorded nothing leaves an empty placeholder behind.
        _state.value.draft.pendingCapturePath?.let(::File)?.takeIf { it.length() == 0L }?.delete()
        val target = File(recordingsFolder, "kayit-${System.currentTimeMillis()}.mp4")
        change { it.copy(draft = it.draft.copy(pendingCapturePath = target.absolutePath), sourceProblem = null) }
        val context = getApplication<Application>()
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
    }

    fun onCameraMissing() = _state.update { it.copy(sourceProblem = SourceProblem.CAMERA_MISSING) }

    /** The file decides, not the camera app's result code: some camera apps report "cancelled" after saving. */
    fun onCaptureFinished(onReady: () -> Unit) {
        val path = _state.value.draft.pendingCapturePath ?: return
        val file = File(path)
        if (file.length() <= 0) {
            file.delete()  // the empty placeholder the camera never filled
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
            localId = UUID.randomUUID().toString(), teamA = draft.teamA.trim(), teamB = draft.teamB.trim(),
            createdAtMs = System.currentTimeMillis(), video = video,
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
                        initial = match.session, teamA = match.teamA, teamB = match.teamB,
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

    fun setServerTarget(target: ServerTarget) = change { it.copy(serverTarget = target, serverTest = ServerTest.IDLE) }

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
