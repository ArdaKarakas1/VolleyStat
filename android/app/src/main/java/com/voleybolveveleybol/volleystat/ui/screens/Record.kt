package com.voleybolveveleybol.volleystat.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.voleybolveveleybol.volleystat.R
import com.voleybolveveleybol.volleystat.ui.components.Notice
import com.voleybolveveleybol.volleystat.ui.components.PrimaryButton
import com.voleybolveveleybol.volleystat.ui.components.SecondaryButton
import com.voleybolveveleybol.volleystat.ui.components.Tone
import com.voleybolveveleybol.volleystat.ui.formatBytes
import com.voleybolveveleybol.volleystat.ui.formatClock
import com.voleybolveveleybol.volleystat.ui.theme.LocalVsColors
import kotlinx.coroutines.delay
import java.io.File

/** Below this the recording may be cut short; a 1080p match needs several gigabytes. */
private const val LOW_SPACE_BYTES = 1_500_000_000L

/** What the recording screen shows. Everything here comes from the camera or the file system. */
data class RecordUi(
    val recording: Boolean = false,
    val paused: Boolean = false,
    val elapsedMs: Long = 0,
    val freeBytes: Long? = null,
    val failed: Boolean = false,
    val cameraUnavailable: Boolean = false,
)

@Composable
private fun RoundControl(icon: Int, description: String, onClick: () -> Unit, enabled: Boolean = true) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(56.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape)) {
        Icon(painterResource(icon), contentDescription = description, tint = Color.White, modifier = Modifier.size(28.dp))
    }
}

/** Corner brackets that only help the user line the court up. Nothing here claims a detected court. */
@Composable
private fun FramingGuides(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val arm = 28.dp.toPx()
        val stroke = 3.dp.toPx()
        val tint = Color.White.copy(alpha = 0.85f)
        listOf(Offset(0f, 0f) to Offset(1f, 1f), Offset(size.width, 0f) to Offset(-1f, 1f), Offset(0f, size.height) to Offset(1f, -1f), Offset(size.width, size.height) to Offset(-1f, -1f))
            .forEach { (corner, direction) ->
                drawLine(tint, corner, corner.copy(x = corner.x + arm * direction.x), stroke)
                drawLine(tint, corner, corner.copy(y = corner.y + arm * direction.y), stroke)
            }
    }
}

@Composable
private fun TimerChip(ui: RecordUi) {
    val colors = LocalVsColors.current
    Row(
        Modifier.background(if (ui.paused) Color.Black.copy(alpha = 0.6f) else colors.recording, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(10.dp).background(Color.White, CircleShape))
        Text(
            if (ui.paused) stringResource(R.string.record_paused) + "  " + formatClock(ui.elapsedMs) else formatClock(ui.elapsedMs),
            style = MaterialTheme.typography.titleSmall, color = Color.White,
        )
    }
}

@Composable
private fun RecordMessages(ui: RecordUi, portrait: Boolean, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (ui.cameraUnavailable) Notice(stringResource(R.string.record_no_camera), Tone.NEGATIVE)
        if (ui.failed) Notice(stringResource(R.string.record_error), Tone.CAUTION)
        if (ui.freeBytes != null && ui.freeBytes < LOW_SPACE_BYTES) Notice(stringResource(R.string.record_low_space), Tone.CAUTION)
        if (!ui.recording) {
            // The guide asks for a landscape picture; the hint appears only while it can still be followed.
            if (portrait) Notice(stringResource(R.string.record_rotate), Tone.CAUTION)
            Text(
                stringResource(R.string.record_ready) + "\n" + stringResource(R.string.record_overlay_note),
                style = MaterialTheme.typography.bodySmall, color = Color.White, textAlign = TextAlign.Center,
                modifier = Modifier.background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        if (ui.freeBytes != null) {
            Text(
                stringResource(R.string.record_free_space, formatBytes(ui.freeBytes)), style = MaterialTheme.typography.bodySmall, color = Color.White,
                modifier = Modifier.background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

/** Record: a red disc. Stop: a red square inside a white ring. */
@Composable
private fun RecordButton(ui: RecordUi, onStart: () -> Unit, onStop: () -> Unit) {
    val colors = LocalVsColors.current
    val label = stringResource(if (ui.recording) R.string.record_stop else R.string.record_start)
    IconButton(
        onClick = if (ui.recording) onStop else onStart, enabled = !ui.cameraUnavailable,
        modifier = Modifier.size(80.dp).border(4.dp, Color.White, CircleShape).semantics { role = Role.Button },
    ) {
        if (ui.recording) {
            Icon(painterResource(R.drawable.ic_stop), contentDescription = label, tint = colors.recording, modifier = Modifier.size(40.dp))
        } else {
            Box(Modifier.size(60.dp).background(colors.recording, CircleShape)) { Icon(painterResource(R.drawable.ic_videocam), contentDescription = label, tint = Color.White, modifier = Modifier.align(Alignment.Center).size(28.dp)) }
        }
    }
}

/**
 * 06: the controls over the camera picture. A match is filmed with the phone on its side, so
 * the landscape layout puts the controls under the right thumb like a camera app; the portrait
 * layout stays usable and asks the user to turn the phone.
 */
@Composable
fun RecordOverlay(
    ui: RecordUi,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPauseResume: () -> Unit,
    onSwitchCamera: () -> Unit,
    onBack: () -> Unit,
    preview: @Composable () -> Unit,
) {
    val portrait = LocalConfiguration.current.orientation != Configuration.ORIENTATION_LANDSCAPE
    val back: @Composable () -> Unit = { RoundControl(R.drawable.ic_arrow_back, stringResource(R.string.action_back), onBack) }
    val pause: @Composable () -> Unit = {
        if (ui.recording) {
            RoundControl(if (ui.paused) R.drawable.ic_play_arrow else R.drawable.ic_pause, stringResource(if (ui.paused) R.string.record_resume else R.string.record_pause), onPauseResume)
        } else {
            Spacer(Modifier.size(56.dp))
        }
    }
    val switch: @Composable () -> Unit = {
        if (ui.recording) {
            Spacer(Modifier.size(56.dp))
        } else {
            RoundControl(R.drawable.ic_cameraswitch, stringResource(R.string.record_switch), onSwitchCamera, enabled = !ui.cameraUnavailable)
        }
    }
    // The brackets live in the free area between the controls so they never sit under a button.
    val guides: @Composable (Modifier) -> Unit = { modifier ->
        if (ui.recording) Spacer(modifier) else FramingGuides(modifier.padding(12.dp))
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        preview()
        if (portrait) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    back()
                    Spacer(Modifier.weight(1f))
                    if (ui.recording) TimerChip(ui)
                    Spacer(Modifier.weight(1f))
                    Spacer(Modifier.size(56.dp))
                }
                guides(Modifier.weight(1f).fillMaxWidth())
                RecordMessages(ui, portrait = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
                    pause()
                    RecordButton(ui, onStart, onStop)
                    switch()
                }
            }
        } else {
            Row(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        back()
                        Spacer(Modifier.weight(1f))
                        if (ui.recording) TimerChip(ui)
                        Spacer(Modifier.weight(1f))
                        Spacer(Modifier.size(56.dp))
                    }
                    guides(Modifier.weight(1f).fillMaxWidth())
                    RecordMessages(ui, portrait = false, modifier = Modifier.fillMaxWidth())
                }
                Column(Modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceEvenly) {
                    switch()
                    RecordButton(ui, onStart, onStop)
                    pause()
                }
            }
        }
    }
}

/** Shown before the system dialog and after a refusal. A refusal is not an error: the gallery still works. */
@Composable
fun RecordPermissionScreen(denied: Boolean, onGrant: () -> Unit, onOpenSettings: () -> Unit, onUseGallery: () -> Unit, onBack: () -> Unit) {
    FlowScaffold(
        title = stringResource(R.string.source_camera_title), onBack = onBack,
        actions = {
            if (denied) {
                PrimaryButton(stringResource(R.string.record_open_settings), onOpenSettings)
            } else {
                PrimaryButton(stringResource(R.string.record_permission_action), onGrant, icon = R.drawable.ic_videocam)
            }
            SecondaryButton(stringResource(R.string.record_use_gallery), onUseGallery, icon = R.drawable.ic_photo_library)
        },
    ) {
        Text(stringResource(R.string.record_permission_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.record_permission_body), style = MaterialTheme.typography.bodyMedium)
        if (denied) Notice(stringResource(R.string.record_permission_denied), Tone.CAUTION)
    }
}

/** Owns the camera. The permission is requested here, at the moment the user chose to record. */
@Composable
fun RecordRoute(prepareFile: () -> File, onRecorded: () -> Unit, onUseGallery: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var refused by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        granted = allowed
        refused = !allowed
    }
    // The user may have allowed the camera in the phone's settings and come back.
    LifecycleResumeEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            granted = true
            refused = false
        }
        onPauseOrDispose {}
    }
    if (!granted) {
        RecordPermissionScreen(
            denied = refused,
            onGrant = { permission.launch(Manifest.permission.CAMERA) },
            onOpenSettings = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))) },
            onUseGallery = onUseGallery, onBack = onBack,
        )
        return
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER } }
    // Full HD when the phone offers it, the next lower quality otherwise. No audio is recorded.
    val videoCapture = remember {
        VideoCapture.withOutput(
            Recorder.Builder().setQualitySelector(QualitySelector.fromOrderedList(listOf(Quality.FHD, Quality.HD, Quality.SD), FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))).build()
        )
    }
    var backCamera by rememberSaveable { mutableStateOf(true) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var paused by remember { mutableStateOf(false) }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    var failed by remember { mutableStateOf(false) }
    var cameraUnavailable by remember { mutableStateOf(false) }
    var freeBytes by remember { mutableStateOf<Long?>(null) }
    var confirmExit by remember { mutableStateOf(false) }

    DisposableEffect(backCamera) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                provider.unbindAll()
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                provider.bindToLifecycle(lifecycleOwner, if (backCamera) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA, preview, videoCapture)
                cameraUnavailable = false
            } catch (error: Exception) {
                cameraUnavailable = true
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose { runCatching { future.get().unbindAll() } }
    }
    // Leaving the screen ends the recording; the part recorded so far is kept as a file.
    DisposableEffect(Unit) { onDispose { recording?.stop() } }
    LaunchedEffect(Unit) {
        while (true) {
            freeBytes = runCatching { StatFs(context.filesDir.path).availableBytes }.getOrNull()
            delay(5_000)
        }
    }
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    // The activity is not recreated on rotation (a restart would end the recording), so nothing
    // tells the recorder that the phone was turned. Without this a match filmed after turning
    // the phone is stored sideways. A recording that already runs keeps the rotation it started with.
    DisposableEffect(Unit) {
        val displays = context.getSystemService(DisplayManager::class.java)
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                view.display?.takeIf { it.displayId == displayId }?.let { videoCapture.targetRotation = it.rotation }
            }
        }
        displays.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        view.display?.let { videoCapture.targetRotation = it.rotation }
        onDispose { displays.unregisterDisplayListener(listener) }
    }
    // Back is intercepted only while a recording runs, so the system back gesture stays normal otherwise.
    BackHandler(enabled = recording != null) { confirmExit = true }

    RecordOverlay(
        RecordUi(recording != null, paused, elapsedMs, freeBytes, failed, cameraUnavailable),
        onStart = {
            failed = false
            elapsedMs = 0
            recording = videoCapture.output.prepareRecording(context, FileOutputOptions.Builder(prepareFile()).build())
                .start(ContextCompat.getMainExecutor(context)) { event ->
                    when (event) {
                        is VideoRecordEvent.Status -> elapsedMs = event.recordingStats.recordedDurationNanos / 1_000_000
                        is VideoRecordEvent.Pause -> paused = true
                        is VideoRecordEvent.Resume -> paused = false
                        is VideoRecordEvent.Finalize -> {
                            recording = null
                            paused = false
                            failed = event.hasError()
                            // The file decides what happens next: whatever was captured is offered for upload.
                            onRecorded()
                        }
                    }
                }
        },
        onStop = { recording?.stop() },
        onPauseResume = { if (paused) recording?.resume() else recording?.pause() },
        onSwitchCamera = { backCamera = !backCamera },
        onBack = { if (recording != null) confirmExit = true else onBack() },
        preview = { AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize()) },
    )
    if (confirmExit) {
        ConfirmDialog(
            stringResource(R.string.record_exit_title), stringResource(R.string.record_exit_body), stringResource(R.string.record_exit_confirm),
            onConfirm = { confirmExit = false; recording?.stop() }, onDismiss = { confirmExit = false },
        )
    }
}
