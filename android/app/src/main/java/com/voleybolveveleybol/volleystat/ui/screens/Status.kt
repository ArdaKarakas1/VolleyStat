package com.voleybolveveleybol.volleystat.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.voleybolveveleybol.volleystat.AnalysisUi
import com.voleybolveveleybol.volleystat.JobLoad
import com.voleybolveveleybol.volleystat.R
import com.voleybolveveleybol.volleystat.UploadProgress
import com.voleybolveveleybol.volleystat.data.UploadFailure
import com.voleybolveveleybol.volleystat.data.UploadPhase
import com.voleybolveveleybol.volleystat.model.AnalysisSummary
import com.voleybolveveleybol.volleystat.model.MatchRecord
import com.voleybolveveleybol.volleystat.model.VideoCheck
import com.voleybolveveleybol.volleystat.ui.components.DemoBanner
import com.voleybolveveleybol.volleystat.ui.components.Notice
import com.voleybolveveleybol.volleystat.ui.components.PrimaryButton
import com.voleybolveveleybol.volleystat.ui.components.ProgressRing
import com.voleybolveveleybol.volleystat.ui.components.SecondaryButton
import com.voleybolveveleybol.volleystat.ui.components.Stage
import com.voleybolveveleybol.volleystat.ui.components.StageList
import com.voleybolveveleybol.volleystat.ui.components.StageState
import com.voleybolveveleybol.volleystat.ui.components.Tone
import com.voleybolveveleybol.volleystat.ui.components.VsCard
import com.voleybolveveleybol.volleystat.ui.formatBytes
import com.voleybolveveleybol.volleystat.ui.percentOf
import com.voleybolveveleybol.volleystat.ui.theme.Dimens

@Composable
fun ConfirmDialog(title: String, body: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_give_up)) } },
    )
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

/**
 * 07: one screen from the first uploaded byte to the report. The ring shows a number only when
 * that number was measured (bytes sent, or progress the analyzer reported); otherwise it spins
 * while work is running and stands still when nothing is.
 */
@Composable
fun StatusScreen(
    match: MatchRecord,
    upload: UploadProgress?,
    analysis: AnalysisUi,
    removalFailed: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRemove: () -> Unit,
    onRestartAnalysis: () -> Unit,
    onOpenReview: () -> Unit,
    onOpenReport: () -> Unit,
    onBack: () -> Unit,
) {
    val own = upload?.takeIf { it.matchId == match.localId }
    val check = match.check
    val uploading = own?.running == true
    val busyElsewhere = upload?.running == true && own == null
    val total = match.video.sizeBytes
    val sent = when {
        check != null || own?.phase == UploadPhase.VERIFYING -> total
        uploading && own?.phase == UploadPhase.SENDING -> maxOf(own.doneBytes, match.session.sentBytes)
        else -> match.session.sentBytes
    }
    val deadEnd = own?.failure == UploadFailure.TOO_LARGE || own?.failure == UploadFailure.SOURCE_UNREADABLE
    val job = analysis.job?.takeIf { analysis.matchId == match.localId }
    val jobLoad = if (analysis.matchId == match.localId) analysis.jobLoad else JobLoad.LOADING
    var confirmRemove by rememberSaveable { mutableStateOf(false) }

    // The upload runs inside the app's process, so the screen stays awake while it runs.
    val view = LocalView.current
    DisposableEffect(uploading) {
        view.keepScreenOn = uploading
        onDispose { view.keepScreenOn = false }
    }

    // ----- What the ring and the headline say -----
    val headline: String
    val detail: String?
    var fraction: Float? = null
    var spinning = false
    var ringIcon = R.drawable.ic_sports_volleyball_filled
    var ringText: String? = null
    when {
        check == null && uploading -> {
            headline = stringResource(when (own?.phase) {
                UploadPhase.HASHING -> R.string.phase_hashing
                UploadPhase.SENDING -> R.string.phase_sending
                UploadPhase.VERIFYING -> R.string.phase_verifying
                else -> R.string.phase_connecting
            })
            detail = stringResource(R.string.upload_keep_open)
            when (own?.phase) {
                UploadPhase.SENDING -> { fraction = percentOf(sent, total) / 100f; ringText = stringResource(R.string.percent, percentOf(sent, total)) }
                UploadPhase.HASHING -> { fraction = percentOf(own.doneBytes, own.totalBytes) / 100f; ringText = stringResource(R.string.percent, percentOf(own.doneBytes, own.totalBytes)) }
                else -> spinning = true
            }
        }
        check == null -> {
            headline = stringResource(when {
                own?.failure != null -> R.string.upload_stopped
                own?.pausedByUser == true -> R.string.upload_paused
                else -> R.string.upload_waiting
            })
            detail = stringResource(R.string.upload_amount, formatBytes(sent), formatBytes(total))
            fraction = percentOf(sent, total) / 100f
            ringText = stringResource(R.string.percent, percentOf(sent, total))
            ringIcon = R.drawable.ic_upload
        }
        check.status != VideoCheck.READY -> {
            headline = stringResource(if (check.status == VideoCheck.QUARANTINED) R.string.stage_check_rejected else R.string.stage_check_unverified)
            detail = rejectReason(check.rejectCode)
            fraction = 0f
            ringIcon = R.drawable.ic_error
        }
        jobLoad == JobLoad.NO_ANALYZER -> {
            headline = stringResource(R.string.analysis_none_title)
            detail = stringResource(R.string.analysis_none_body)
            fraction = 0f
            ringIcon = R.drawable.ic_hourglass_empty
        }
        jobLoad == JobLoad.UNREACHABLE -> {
            headline = stringResource(R.string.analysis_unreachable)
            detail = null
            fraction = 0f
            ringIcon = R.drawable.ic_wifi_off
        }
        job == null -> {
            headline = stringResource(R.string.stage_check_ok)
            detail = null
            spinning = jobLoad == JobLoad.LOADING
            if (!spinning) fraction = 0f
        }
        job.state == AnalysisSummary.QUEUED -> {
            headline = stringResource(R.string.analysis_queued)
            detail = stringResource(R.string.analysis_processing_body)
            fraction = 0f
            ringIcon = R.drawable.ic_schedule
        }
        job.state == AnalysisSummary.PROCESSING -> {
            headline = stringResource(R.string.analysis_processing)
            detail = stringResource(R.string.analysis_processing_body)
            if (job.progressDone != null && job.progressTotal != null && job.progressTotal > 0) {
                fraction = percentOf(job.progressDone, job.progressTotal) / 100f
                ringText = stringResource(R.string.percent, percentOf(job.progressDone, job.progressTotal))
            } else {
                spinning = true
            }
        }
        job.state == AnalysisSummary.NEEDS_REVIEW -> {
            headline = stringResource(R.string.analysis_review_title)
            detail = stringResource(R.string.analysis_review_body)
            fraction = if (job.reviewTotal > 0) (job.reviewTotal - job.reviewOpen) / job.reviewTotal.toFloat() else 0f
            ringIcon = R.drawable.ic_fact_check
            ringText = stringResource(R.string.review_position, job.reviewTotal - job.reviewOpen, job.reviewTotal)
        }
        job.state == AnalysisSummary.COMPLETE -> {
            headline = stringResource(R.string.analysis_complete_title)
            detail = null
            fraction = 1f
            ringIcon = R.drawable.ic_task_alt
        }
        else -> {
            headline = stringResource(R.string.analysis_failed_title)
            detail = when (job.failureCode) {
                "NO_RALLIES_FOUND" -> stringResource(R.string.analysis_failed_no_rallies)
                "VIDEO_UNREADABLE" -> stringResource(R.string.analysis_failed_unreadable)
                else -> stringResource(R.string.analysis_failed_other, job.failureCode ?: job.state)
            }
            fraction = 0f
            ringIcon = R.drawable.ic_error
        }
    }

    FlowScaffold(
        title = stringResource(R.string.status_title), onBack = onBack,
        topActions = {
            IconButton(onClick = { confirmRemove = true }) { Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.action_delete_match)) }
        },
        actions = {
            when {
                check == null && uploading -> SecondaryButton(stringResource(R.string.action_pause), onPause, enabled = own?.pausedByUser != true)
                check == null && deadEnd -> Unit
                check == null -> PrimaryButton(
                    stringResource(if (own?.failure == UploadFailure.SERVER_REFUSED || own?.failure == UploadFailure.CHECKSUM_KEEPS_FAILING) R.string.action_retry else R.string.action_resume),
                    onResume, enabled = !busyElsewhere,
                )
                job?.state == AnalysisSummary.NEEDS_REVIEW -> {
                    PrimaryButton(stringResource(R.string.action_start_review), onOpenReview, icon = R.drawable.ic_fact_check)
                    SecondaryButton(stringResource(R.string.action_open_report), onOpenReport)
                }
                job?.state == AnalysisSummary.COMPLETE -> PrimaryButton(stringResource(R.string.action_open_report), onOpenReport, icon = R.drawable.ic_bar_chart)
                job?.state == AnalysisSummary.FAILED || job?.state == AnalysisSummary.CANCELLED || jobLoad == JobLoad.UNREACHABLE ||
                    (jobLoad == JobLoad.NO_JOB && check.status == VideoCheck.READY) ->
                    PrimaryButton(stringResource(if (jobLoad == JobLoad.NO_JOB) R.string.action_start_analysis else R.string.action_retry), onRestartAnalysis)
                else -> Unit
            }
            if (check == null) {
                TextButton(onClick = { confirmRemove = true }, modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.touchTarget)) {
                    Text(stringResource(R.string.action_cancel_upload), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
                }
            }
        },
    ) {
        if (job?.mock == true) DemoBanner()
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ProgressRing(fraction, spinning = spinning) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(painterResource(ringIcon), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
                    if (ringText != null) Text(ringText, style = MaterialTheme.typography.headlineMedium)
                }
            }
            Text(headline, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Text(match.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

        VsCard {
            val fileOk = check?.status == VideoCheck.READY
            StageList(
                listOf(
                    Stage(
                        stringResource(R.string.stage_upload),
                        if (check != null) stringResource(R.string.stage_upload_done) else stringResource(R.string.upload_amount, formatBytes(sent), formatBytes(total)),
                        when {
                            check != null || own?.phase == UploadPhase.VERIFYING -> StageState.DONE
                            uploading -> StageState.ACTIVE
                            own?.failure != null -> StageState.CAUTION
                            else -> StageState.WAITING
                        },
                    ),
                    Stage(
                        stringResource(R.string.stage_check),
                        when (check?.status) {
                            null -> if (own?.phase == UploadPhase.VERIFYING) stringResource(R.string.phase_verifying) else null
                            VideoCheck.READY -> stringResource(R.string.stage_check_ok)
                            VideoCheck.QUARANTINED -> stringResource(R.string.stage_check_rejected)
                            else -> stringResource(R.string.stage_check_unverified)
                        },
                        when (check?.status) {
                            null -> if (uploading && own?.phase == UploadPhase.VERIFYING) StageState.ACTIVE else StageState.WAITING
                            VideoCheck.READY -> StageState.DONE
                            VideoCheck.QUARANTINED -> StageState.FAILED
                            else -> StageState.CAUTION
                        },
                    ),
                    Stage(
                        stringResource(R.string.stage_rallies),
                        when {
                            !fileOk -> null
                            jobLoad == JobLoad.NO_ANALYZER -> stringResource(R.string.analysis_none_title)
                            job?.state == AnalysisSummary.QUEUED -> stringResource(R.string.stage_queue_waiting)
                            job?.state == AnalysisSummary.PROCESSING -> stringResource(R.string.stage_rallies_running)
                            job?.state == AnalysisSummary.NEEDS_REVIEW || job?.state == AnalysisSummary.COMPLETE -> stringResource(R.string.stage_rallies_done, job.rallies)
                            else -> null
                        },
                        when {
                            !fileOk -> StageState.WAITING
                            jobLoad == JobLoad.NO_ANALYZER -> StageState.NOT_YET
                            job?.state == AnalysisSummary.PROCESSING -> StageState.ACTIVE
                            job?.state == AnalysisSummary.NEEDS_REVIEW || job?.state == AnalysisSummary.COMPLETE -> StageState.DONE
                            job?.state == AnalysisSummary.FAILED -> StageState.FAILED
                            else -> StageState.WAITING
                        },
                    ),
                    Stage(
                        stringResource(R.string.stage_review),
                        when (job?.state) {
                            AnalysisSummary.NEEDS_REVIEW -> stringResource(R.string.stage_review_open, job.reviewOpen)
                            AnalysisSummary.COMPLETE -> stringResource(R.string.stage_review_done)
                            else -> null
                        },
                        when (job?.state) {
                            AnalysisSummary.NEEDS_REVIEW -> StageState.CAUTION
                            AnalysisSummary.COMPLETE -> StageState.DONE
                            else -> StageState.WAITING
                        },
                    ),
                    Stage(stringResource(R.string.stage_report), null, if (job?.state == AnalysisSummary.COMPLETE) StageState.DONE else StageState.WAITING),
                )
            )
        }
        check?.warnings?.mapNotNull(::warningText)?.forEach { Notice(stringResource(it), Tone.CAUTION) }
    }

    if (confirmRemove) {
        val uploaded = check != null
        ConfirmDialog(
            stringResource(if (uploaded) R.string.delete_title else R.string.cancel_title),
            stringResource(when {
                uploaded -> R.string.delete_body_uploaded
                match.session.uploadId != null -> R.string.cancel_body
                else -> R.string.delete_body_local
            }),
            stringResource(if (uploaded) R.string.action_delete else R.string.cancel_confirm),
            onConfirm = { confirmRemove = false; onRemove() }, onDismiss = { confirmRemove = false },
        )
    }
}
