package com.voleybolveveleybol.volleystat.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.voleybolveveleybol.volleystat.AnalysisUi
import com.voleybolveveleybol.volleystat.R
import com.voleybolveveleybol.volleystat.RalliesLoad
import com.voleybolveveleybol.volleystat.model.EditTarget
import com.voleybolveveleybol.volleystat.ui.components.DemoBanner
import com.voleybolveveleybol.volleystat.ui.components.Notice
import com.voleybolveveleybol.volleystat.ui.components.PrimaryButton
import com.voleybolveveleybol.volleystat.ui.components.SecondaryButton
import com.voleybolveveleybol.volleystat.ui.components.Tone
import com.voleybolveveleybol.volleystat.ui.formatClockTenths
import com.voleybolveveleybol.volleystat.ui.theme.Dimens
import com.voleybolveveleybol.volleystat.ui.theme.LocalVsColors

/** What the range editor needs from a video player. Screen tests steer the editor with a stand-in. */
@Stable
interface VideoControl {
    /** Where the picture stands, in milliseconds of the video. */
    val positionMs: Long

    /** The user asked for playback, and it has not been stopped since. */
    val playing: Boolean
    val failed: Boolean

    /** [exact] false shows the nearest key frame at once, for a finger that is still moving. */
    fun seekTo(positionMs: Long, exact: Boolean = true)
    fun play()
    fun pause()
    fun speed(fast: Boolean)
}

/** A video the range editor can steer, and the composable that shows its picture. */
class EditorVideo(val control: VideoControl, val picture: @Composable () -> Unit)

/** The editor before it has a rally list to work on. */
@Composable
fun RallyEditorWaiting(title: String, load: RalliesLoad, onRetry: () -> Unit, onBack: () -> Unit) {
    FlowScaffold(title = title, onBack = onBack) {
        if (load == RalliesLoad.UNREACHABLE) {
            Notice(stringResource(R.string.rallies_load_failed), Tone.NEGATIVE)
            SecondaryButton(stringResource(R.string.action_retry), onRetry)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.rallies_loading), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/**
 * The range editor: the user moves through the part of the video around one rally, or through one
 * pause between two rallies, and marks where a rally starts and ends. Every mark is the moment
 * the picture shows. One correction goes to the server per tap, and the caller closes the screen
 * once the server has applied it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RallyEditorScreen(
    target: EditTarget,
    analysis: AnalysisUi,
    video: EditorVideo,
    onMove: (startMs: Long, endMs: Long) -> Unit,
    onSplit: (atMs: Long) -> Unit,
    onMerge: () -> Unit,
    onAdd: (startMs: Long, endMs: Long) -> Unit,
    onBack: () -> Unit,
) {
    val control = video.control
    val window = target.windowStartMs..target.windowEndMs
    // While a finger drags the bar, the position is where the finger is; the picture follows as fast as it can.
    var scrub by remember { mutableStateOf<Long?>(null) }
    val position = (scrub ?: control.positionMs).coerceIn(window)
    var fast by rememberSaveable { mutableStateOf(false) }
    var merging by rememberSaveable { mutableStateOf(false) }
    var marked by rememberSaveable { mutableStateOf<Long?>(null) }
    // A start that the list has since given to another rally is no start any more.
    val markedStart = marked?.takeIf { it >= window.first && it < window.last }
    val rally = (target as? EditTarget.OfRally)?.rally
    // Nothing is marked on a picture the user can not see, and nothing is sent while a correction is on its way.
    val usable = !analysis.correcting && !control.failed

    fun stand(atMs: Long) {
        control.pause()
        control.seekTo(atMs.coerceIn(window))
    }

    FlowScaffold(
        title = stringResource(if (rally != null) R.string.editor_title_rally else R.string.editor_title_gap), onBack = onBack,
        actions = {
            if (rally != null) {
                // The end comes first: the winner is judged on what the clip shows at the end of a rally.
                PrimaryButton(
                    stringResource(R.string.action_end_here), onClick = { control.pause(); onMove(rally.startMs, position) },
                    enabled = usable && position > rally.startMs && position != rally.endMs,
                )
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton(
                        stringResource(R.string.action_start_here), onClick = { control.pause(); onMove(position, rally.endMs) },
                        enabled = usable && position < rally.endMs && position != rally.startMs, modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                    SecondaryButton(
                        stringResource(R.string.action_split_here), onClick = { control.pause(); onSplit(position) },
                        enabled = usable && position > rally.startMs && position < rally.endMs, modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
                if ((target as EditTarget.OfRally).hasPrevious) {
                    // Merging needs no position, so it works without a picture too.
                    TextButton(onClick = { merging = true }, enabled = !analysis.correcting, modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.touchTarget)) {
                        Text(stringResource(R.string.action_merge_previous), style = MaterialTheme.typography.labelLarge)
                    }
                }
            } else {
                // The two marks keep their places. The one the user needs next is the filled button.
                val start = stringResource(R.string.action_rally_started)
                val end = stringResource(R.string.action_rally_ended)
                val canStart = usable && position < window.last && position != markedStart
                if (markedStart == null) {
                    PrimaryButton(start, onClick = { marked = position }, enabled = canStart)
                    SecondaryButton(end, onClick = {}, enabled = false)
                } else {
                    // The video keeps running after the start mark, so the end can be marked as the rally ends.
                    SecondaryButton(start, onClick = { marked = position }, enabled = canStart)
                    PrimaryButton(end, onClick = { control.pause(); onAdd(markedStart, position) }, enabled = usable && position > markedStart)
                }
            }
        },
    ) {
        if (analysis.job?.mock == true) DemoBanner(compact = true)
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.scrim),
            contentAlignment = Alignment.Center,
        ) {
            if (control.failed) {
                Text(stringResource(R.string.editor_video_failed), style = MaterialTheme.typography.bodyMedium, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(16.dp))
            } else {
                video.picture()
            }
        }
        Text(
            if (rally != null) stringResource(R.string.editor_rally_now, rally.sequence, formatClockTenths(rally.startMs), formatClockTenths(rally.endMs))
            else stringResource(R.string.editor_gap_now, formatClockTenths(window.first), formatClockTenths(window.last)),
            style = MaterialTheme.typography.titleSmall,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.editor_position, formatClockTenths(position)), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            FilterChip(selected = fast, onClick = { fast = !fast; control.speed(fast) }, label = { Text(stringResource(R.string.editor_fast)) })
        }

        val seekLabel = stringResource(R.string.editor_seek)
        val spokenPosition = formatClockTenths(position)
        val trackColor = MaterialTheme.colorScheme.surfaceVariant
        val bandColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
        // The bar is the window. The band on it is the rally as the list holds it, or the rally that is being marked.
        val band = when {
            rally != null -> rally.startMs to rally.endMs
            markedStart != null -> markedStart to maxOf(position, markedStart)
            else -> null
        }
        Slider(
            value = (position - window.first).toFloat(),
            onValueChange = { value ->
                val at = window.first + value.toLong()
                if (scrub == null) control.pause()
                scrub = at
                control.seekTo(at, exact = false)
            },
            onValueChangeFinished = {
                scrub?.let { control.seekTo(it) }
                scrub = null
            },
            valueRange = 0f..(window.last - window.first).toFloat(),
            enabled = !control.failed,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = seekLabel; stateDescription = spokenPosition },
            track = {
                Canvas(Modifier.fillMaxWidth().height(10.dp)) {
                    val round = CornerRadius(size.height / 2)
                    drawRoundRect(trackColor, cornerRadius = round)
                    if (band != null) {
                        val scale = size.width / (window.last - window.first)
                        val left = (band.first - window.first) * scale
                        drawRoundRect(bandColor, topLeft = Offset(left, 0f), size = Size(((band.second - band.first) * scale).coerceAtLeast(2.dp.toPx()), size.height), cornerRadius = round)
                    }
                }
            },
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            StepButton(stringResource(R.string.editor_back_1), enabled = !control.failed && position > window.first) { stand(position - 1_000) }
            StepButton(stringResource(R.string.editor_back_02), enabled = !control.failed && position > window.first) { stand(position - 200) }
            val scheme = MaterialTheme.colorScheme
            Surface(
                onClick = {
                    if (control.playing) {
                        control.pause()
                    } else {
                        // At the end of the window there is nothing left to play, so playback starts over.
                        if (position >= window.last) control.seekTo(rally?.startMs ?: window.first)
                        control.play()
                    }
                },
                enabled = !control.failed, shape = MaterialTheme.shapes.small, color = scheme.primary, contentColor = scheme.onPrimary,
                modifier = Modifier.weight(1f).height(Dimens.touchTarget),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painterResource(if (control.playing) R.drawable.ic_pause else R.drawable.ic_play_arrow),
                        contentDescription = stringResource(if (control.playing) R.string.editor_pause else R.string.editor_play),
                    )
                }
            }
            StepButton(stringResource(R.string.editor_forward_02), enabled = !control.failed && position < window.last) { stand(position + 200) }
            StepButton(stringResource(R.string.editor_forward_1), enabled = !control.failed && position < window.last) { stand(position + 1_000) }
        }

        CorrectionProblemNotice(analysis)
        if (rally != null) {
            Text(stringResource(R.string.editor_end_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            if (markedStart != null) Text(stringResource(R.string.editor_marked_start, formatClockTenths(markedStart)), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.editor_gap_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (merging && rally != null) {
        ConfirmDialog(
            title = stringResource(R.string.merge_title),
            body = stringResource(R.string.merge_body, rally.sequence - 1, rally.sequence),
            confirm = stringResource(R.string.merge_confirm),
            onConfirm = { merging = false; control.pause(); onMerge() }, onDismiss = { merging = false },
        )
    }
}

/** Moves the picture by a fixed step and leaves it standing there. */
@Composable
private fun RowScope.StepButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick, enabled = enabled, shape = MaterialTheme.shapes.small, color = scheme.surface,
        contentColor = if (enabled) scheme.primary else scheme.onSurface.copy(alpha = 0.38f),
        border = BorderStroke(1.dp, if (enabled) scheme.outline else LocalVsColors.current.divider),
        modifier = Modifier.weight(1f).height(Dimens.touchTarget),
    ) {
        Box(contentAlignment = Alignment.Center) { Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false) }
    }
}
