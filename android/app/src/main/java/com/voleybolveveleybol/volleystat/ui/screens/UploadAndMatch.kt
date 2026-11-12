package com.voleybolveveleybol.volleystat.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.voleybolveveleybol.volleystat.R
import com.voleybolveveleybol.volleystat.UploadProgress
import com.voleybolveveleybol.volleystat.data.UploadFailure
import com.voleybolveveleybol.volleystat.data.UploadPhase
import com.voleybolveveleybol.volleystat.model.MatchRecord
import com.voleybolveveleybol.volleystat.model.MatchStatus
import com.voleybolveveleybol.volleystat.model.VideoCheck
import com.voleybolveveleybol.volleystat.model.status
import com.voleybolveveleybol.volleystat.ui.components.FactRow
import com.voleybolveveleybol.volleystat.ui.components.MatchHeader
import com.voleybolveveleybol.volleystat.ui.components.NotYetCard
import com.voleybolveveleybol.volleystat.ui.components.Notice
import com.voleybolveveleybol.volleystat.ui.components.PrimaryButton
import com.voleybolveveleybol.volleystat.ui.components.SecondaryButton
import com.voleybolveveleybol.volleystat.ui.components.SectionTitle
import com.voleybolveveleybol.volleystat.ui.components.Stage
import com.voleybolveveleybol.volleystat.ui.components.StageState
import com.voleybolveveleybol.volleystat.ui.components.StageTracker
import com.voleybolveveleybol.volleystat.ui.components.StatusChip
import com.voleybolveveleybol.volleystat.ui.components.Tone
import com.voleybolveveleybol.volleystat.ui.components.VsCard
import com.voleybolveveleybol.volleystat.ui.components.dashedOutline
import com.voleybolveveleybol.volleystat.ui.formatBytes
import com.voleybolveveleybol.volleystat.ui.formatDuration
import com.voleybolveveleybol.volleystat.ui.formatFps
import com.voleybolveveleybol.volleystat.ui.formatMoment
import com.voleybolveveleybol.volleystat.ui.percentOf

@Composable
private fun RemoveDialog(title: String, body: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_give_up)) } },
    )
}

@Composable
fun UploadScreen(
    match: MatchRecord,
    upload: UploadProgress?,
    removalFailed: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onBack: () -> Unit,
) {
    val own = upload?.takeIf { it.matchId == match.localId }
    val running = own?.running == true
    val busyElsewhere = upload?.running == true && own == null
    val total = match.video.sizeBytes
    val sent = if (running && own?.phase == UploadPhase.SENDING) maxOf(own.doneBytes, match.session.sentBytes) else if (own?.phase == UploadPhase.VERIFYING) total else match.session.sentBytes
    // A video that can never be sent has one way out: give the upload up.
    val deadEnd = own?.failure == UploadFailure.TOO_LARGE || own?.failure == UploadFailure.SOURCE_UNREADABLE
    var confirmCancel by rememberSaveable { mutableStateOf(false) }

    // The upload runs inside the app's process, so the screen stays awake while it runs.
    val view = LocalView.current
    DisposableEffect(running) {
        view.keepScreenOn = running
        onDispose { view.keepScreenOn = false }
    }

    FlowScaffold(
        title = stringResource(R.string.upload_title), onBack = onBack,
        actions = {
            when {
                running -> SecondaryButton(stringResource(R.string.action_pause), onPause, enabled = own?.pausedByUser != true)
                deadEnd -> Unit
                else -> PrimaryButton(
                    stringResource(if (own?.failure == UploadFailure.SERVER_REFUSED || own?.failure == UploadFailure.CHECKSUM_KEEPS_FAILING) R.string.action_retry else R.string.action_resume),
                    onResume, enabled = !busyElsewhere,
                )
            }
            TextButton(onClick = { confirmCancel = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.action_cancel_upload), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
            }
        },
    ) {
        VsCard { MatchHeader(match.teamA, match.teamB) }

        VsCard {
            val status = when {
                running -> stringResource(
                    when (own?.phase) {
                        UploadPhase.HASHING -> R.string.phase_hashing
                        UploadPhase.SENDING -> R.string.phase_sending
                        UploadPhase.VERIFYING -> R.string.phase_verifying
                        else -> R.string.phase_connecting
                    }
                )
                own?.failure != null -> stringResource(R.string.status_upload_stopped)
                own?.pausedByUser == true -> stringResource(R.string.upload_paused)
                else -> stringResource(R.string.upload_waiting)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // Only work that is really running gets a moving indicator.
                if (running && own?.phase != UploadPhase.SENDING) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(status, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (running && own?.phase == UploadPhase.HASHING) {
                    Text(stringResource(R.string.upload_percent, percentOf(own.doneBytes, own.totalBytes)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(16.dp))
            LinearProgressIndicator(progress = { percentOf(sent, total) / 100f }, modifier = Modifier.fillMaxWidth().height(8.dp), drawStopIndicator = {})
            Spacer(Modifier.height(8.dp))
            Row {
                Text(stringResource(R.string.upload_amount, formatBytes(sent), formatBytes(total)), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.upload_percent, percentOf(sent, total)), style = MaterialTheme.typography.bodyLarge)
            }
        }

        own?.failure?.let { failure ->
            val what = when (failure) {
                UploadFailure.SERVER_UNREACHABLE -> stringResource(R.string.upload_error_unreachable)
                UploadFailure.TOO_LARGE -> stringResource(R.string.upload_error_too_large)
                UploadFailure.SOURCE_UNREADABLE -> stringResource(R.string.upload_error_source)
                UploadFailure.SERVER_REFUSED -> stringResource(R.string.upload_error_refused, own.serverCode ?: "")
                UploadFailure.CHECKSUM_KEEPS_FAILING -> stringResource(R.string.upload_error_checksum)
            }
            // What happened, what the server kept, and (in the buttons below) the one way forward.
            val kept = if (!deadEnd && match.session.sentBytes > 0) " " + stringResource(R.string.upload_kept, formatBytes(match.session.sentBytes)) else ""
            val next = if (deadEnd) " " + stringResource(R.string.upload_blocked) else ""
            Notice(what + kept + next, if (deadEnd) Tone.NEGATIVE else Tone.CAUTION)
        }
        if (busyElsewhere) Notice(stringResource(R.string.upload_busy), Tone.NEUTRAL)
        if (removalFailed) Notice(stringResource(R.string.removal_failed), Tone.NEGATIVE)

        Text(stringResource(R.string.upload_keep_open), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.upload_keep_video), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    if (confirmCancel) {
        RemoveDialog(
            stringResource(R.string.cancel_title), stringResource(R.string.cancel_body), stringResource(R.string.cancel_confirm),
            onConfirm = { confirmCancel = false; onCancel() }, onDismiss = { confirmCancel = false },
        )
    }
}

@Composable
private fun rejectReason(code: String?): String = stringResource(
    when (code) {
        "NOT_A_VIDEO" -> R.string.reject_not_a_video
        "VIDEO_CORRUPT" -> R.string.reject_corrupt
        "UNSUPPORTED_CODEC" -> R.string.reject_codec
        "VIDEO_DURATION_INVALID" -> R.string.reject_duration
        "PROBE_UNAVAILABLE" -> R.string.reject_probe_unavailable
        "PROBE_TIMEOUT" -> R.string.reject_probe_timeout
        else -> R.string.reject_unknown
    }
)

private fun warningText(code: String): Int? = when (code) {
    "LOW_RESOLUTION" -> R.string.warn_low_resolution
    "LOW_FRAME_RATE" -> R.string.warn_low_frame_rate
    "PORTRAIT_ORIENTATION" -> R.string.warn_portrait
    "VARIABLE_FRAME_RATE" -> R.string.warn_variable_frame_rate
    "CONTAINER_MISMATCH" -> R.string.warn_container_mismatch
    else -> null
}

@Composable
private fun fileChip(check: VideoCheck?): Pair<String, Tone> = when (check?.status) {
    null -> stringResource(R.string.state_no_check) to Tone.NOT_YET
    VideoCheck.READY -> stringResource(R.string.status_file_accepted) to Tone.POSITIVE
    VideoCheck.QUARANTINED -> stringResource(R.string.status_file_rejected) to Tone.NEGATIVE
    else -> stringResource(R.string.status_file_unverified) to Tone.CAUTION
}

@Composable
private fun SummaryTab(match: MatchRecord, onOpenUpload: () -> Unit) {
    val check = match.check
    VideoThumbnail(match.video)
    SectionTitle(stringResource(R.string.stages_title))
    VsCard {
        StageTracker(
            listOf(
                Stage(
                    stringResource(R.string.stage_upload),
                    stringResource(if (check != null) R.string.stage_upload_done else R.string.stage_upload_pending),
                    if (check != null) StageState.DONE else StageState.WAITING,
                    moment = check?.let { match.checkedAtMs?.let(::formatMoment) },
                ),
                Stage(
                    stringResource(R.string.stage_check),
                    when (check?.status) {
                        null -> stringResource(R.string.stage_check_waiting)
                        VideoCheck.READY -> stringResource(R.string.stage_check_ok)
                        VideoCheck.QUARANTINED -> stringResource(R.string.stage_check_rejected) + " " + rejectReason(check.rejectCode)
                        else -> stringResource(R.string.stage_check_unverified) + " " + rejectReason(check.rejectCode)
                    },
                    when (check?.status) {
                        null -> StageState.WAITING
                        VideoCheck.READY -> StageState.DONE
                        VideoCheck.QUARANTINED -> StageState.FAILED
                        else -> StageState.CAUTION
                    },
                    moment = check?.let { match.checkedAtMs?.let(::formatMoment) },
                ),
                // Static on purpose: nothing is running, so nothing moves.
                Stage(stringResource(R.string.stage_analysis), stringResource(R.string.stage_analysis_none), StageState.NOT_YET),
            )
        )
    }
    if (match.status == MatchStatus.UPLOAD_PENDING) {
        PrimaryButton(stringResource(R.string.action_open_upload), onOpenUpload, icon = R.drawable.ic_upload)
    }
    if (check != null && (check.width != null || check.durationMs != null || check.codec != null)) {
        SectionTitle(stringResource(R.string.file_title))
        VsCard {
            if (check.width != null && check.height != null) FactRow(stringResource(R.string.check_resolution), stringResource(R.string.file_resolution, check.width, check.height))
            check.fps?.let { FactRow("fps", stringResource(R.string.file_fps, formatFps(it))) }
            check.durationMs?.let { FactRow(stringResource(R.string.check_duration), formatDuration(it)) }
            FactRow(stringResource(R.string.check_size), formatBytes(match.video.sizeBytes))
            check.codec?.let { FactRow("Codec", it) }
        }
        check.warnings.mapNotNull(::warningText).forEach { Notice(stringResource(it), Tone.CAUTION) }
        Text(stringResource(R.string.file_provisional), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    NotYetCard(stringResource(R.string.empty_summary_title), stringResource(R.string.empty_summary_body))
}

@Composable
private fun ReliabilityTab(match: MatchRecord) {
    NotYetCard(stringResource(R.string.empty_reliability_title), stringResource(R.string.empty_reliability_body))
    SectionTitle(stringResource(R.string.reliability_checked_title))
    VsCard {
        val (text, tone) = fileChip(match.check)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FactRow(stringResource(R.string.reliability_file_format)) { StatusChip(text, tone) }
            listOf(R.string.check_court, R.string.reliability_ball, R.string.reliability_camera, R.string.check_light).forEach { label ->
                FactRow(stringResource(label)) { StatusChip(stringResource(R.string.state_no_check), Tone.NOT_YET) }
            }
        }
    }
}

/** The report shell. Its five tabs stay the same whether or not data exists; each says what is missing. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MatchScreen(
    match: MatchRecord,
    upload: UploadProgress?,
    removalFailed: Boolean,
    onBack: () -> Unit,
    onOpenUpload: () -> Unit,
    onRemove: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val tabs = listOf(R.string.tab_summary, R.string.tab_teams, R.string.tab_players, R.string.tab_rallies, R.string.tab_reliability)
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back)) } },
                actions = { IconButton(onClick = { confirmDelete = true }) { Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.action_delete_match)) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MatchHeader(match.teamA, match.teamB) {
                    // Words in the score slot. A match without an analysis never shows 0-0.
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(R.string.score_slot_none), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.dashedOutline(MaterialTheme.colorScheme.outline, 10.dp).padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    matchChips(match, upload).forEach { (text, tone) -> StatusChip(text, tone) }
                }
            }
            Spacer(Modifier.height(8.dp))
            PrimaryScrollableTabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background, edgePadding = 12.dp) {
                tabs.forEachIndexed { index, label ->
                    Tab(selected = tab == index, onClick = { tab = index }, text = { Text(stringResource(label), style = MaterialTheme.typography.labelLarge) })
                }
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (removalFailed) Notice(stringResource(R.string.removal_failed), Tone.NEGATIVE)
                when (tab) {
                    0 -> SummaryTab(match, onOpenUpload)
                    1 -> NotYetCard(stringResource(R.string.empty_teams_title), stringResource(R.string.empty_teams_body))
                    2 -> NotYetCard(stringResource(R.string.empty_players_title), stringResource(R.string.empty_players_body))
                    3 -> NotYetCard(stringResource(R.string.empty_rallies_title), stringResource(R.string.empty_rallies_body))
                    else -> ReliabilityTab(match)
                }
            }
        }
    }
    if (confirmDelete) {
        RemoveDialog(
            stringResource(R.string.delete_title),
            stringResource(if (match.check != null || match.session.uploadId != null) R.string.delete_body_uploaded else R.string.delete_body_local),
            stringResource(R.string.delete_confirm),
            onConfirm = { confirmDelete = false; onRemove() }, onDismiss = { confirmDelete = false },
        )
    }
}
