package com.voleybolveveleybol.volleystat.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.voleybolveveleybol.volleystat.AnalysisUi
import com.voleybolveveleybol.volleystat.R
import com.voleybolveveleybol.volleystat.RalliesLoad
import com.voleybolveveleybol.volleystat.model.EditTarget
import com.voleybolveveleybol.volleystat.model.Rally
import com.voleybolveveleybol.volleystat.ui.components.DemoBanner
import com.voleybolveveleybol.volleystat.ui.components.Notice
import com.voleybolveveleybol.volleystat.ui.components.PrimaryButton
import com.voleybolveveleybol.volleystat.ui.components.SecondaryButton
import com.voleybolveveleybol.volleystat.ui.components.Tone
import com.voleybolveveleybol.volleystat.ui.formatClock
import com.voleybolveveleybol.volleystat.ui.formatClockTenths
import com.voleybolveveleybol.volleystat.ui.theme.Dimens
import com.voleybolveveleybol.volleystat.ui.theme.LocalVsColors

/** What the range editor needs from a video player. Screen tests steer the editor with a stand-in. */
@Stable
interface VideoControl {
    /** Where the picture stood when the player was last looked at, in milliseconds of the video. */
    val positionMs: Long

    /** The user asked for playback, and it has not been stopped since. */
    val playing: Boolean
    val failed: Boolean

    /** The video has played to its own end. That can lie a little before the length the server reports. */
    val ended: Boolean

    /** The picture of the position is on screen. False while the player still fetches or decodes it. */
    val ready: Boolean

    /** Where the picture stands at this instant. A mark is made with this, not with the last look. */
    fun positionNow(): Long

    /** [exact] false shows the nearest key frame at once, for a finger that is still moving. */
    fun seekTo(positionMs: Long, exact: Boolean = true)
    fun play()
    fun pause()
    fun speed(fast: Boolean)

    /** Tries the video once more after a failure, at the place where the picture stood. */
    fun retry()
}

/** A video the range editor can steer, and the composable that shows its picture. */
class EditorVideo(val control: VideoControl, val picture: @Composable () -> Unit)

/** The editor before it has a rally list to work on. */
@Composable
fun RallyEditorWaiting(title: String, load: RalliesLoad, onRetry: () -> Unit, onBack: () -> Unit) {
    FlowScaffold(title = title, onBack = onBack) {
        when (load) {
            RalliesLoad.UNREACHABLE -> {
                Notice(stringResource(R.string.rallies_load_failed), Tone.NEGATIVE)
                SecondaryButton(stringResource(R.string.action_retry), onRetry)
            }
            // Asking again does not change what an older server answers, so there is no retry here.
            RalliesLoad.OUTDATED -> Notice(stringResource(R.string.rallies_outdated_server), Tone.NEGATIVE)
            else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.rallies_loading), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/**
 * The range editor: the user moves through the part of the video around one rally, or through one
 * pause between two rallies, and marks where a rally starts and ends. A mark is the position of
 * the player at the moment of the tap, which is the number the screen shows as "Konum". One
 * correction goes to the server per tap, and the caller closes the screen once the server has
 * applied it.
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
    /** Opens the winner question of this rally. Null when the editor was opened from that question. */
    onChooseWinner: (() -> Unit)? = null,
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
    val previous = (target as? EditTarget.OfRally)?.previous
    // Nothing is marked before the picture of the position is on screen, and nothing is sent while a correction is on its way.
    val usable = !analysis.correcting && !control.failed && control.ready

    /** The moment a mark is made at. The picture may have moved on since the screen last looked at the player. */
    fun here() = (scrub ?: control.positionNow()).coerceIn(window)

    // What each mark needs from its moment. A button is offered by the position the screen last saw, and the
    // tap asks the same rule again for the moment itself: a picture that plays has moved on in between.
    fun endsRally(at: Long) = rally != null && at > rally.startMs && at != rally.endMs
    fun startsRally(at: Long) = rally != null && at < rally.endMs && at != rally.startMs
    fun cutsRally(at: Long) = rally != null && at > rally.startMs && at < rally.endMs
    fun startsInPause(at: Long) = at < window.last && at != markedStart

    fun stand(atMs: Long) {
        control.pause()
        control.seekTo(atMs.coerceIn(window))
    }

    // Playback ends where the window ends: what comes after belongs to another rally. It also ends
    // where the video itself ends, which the player reaches a little before the length the server reports.
    LaunchedEffect(control, window) {
        snapshotFlow { control.positionMs to control.ended }.collect { (at, ended) ->
            if (control.playing && (at >= window.last || ended)) {
                control.pause()
                if (at > window.last) control.seekTo(window.last)
            }
        }
    }
    // The speed belongs to the screen. A player that was built anew, after the theme changed for example, takes it over.
    LaunchedEffect(control, fast) { control.speed(fast) }
    // The screen that sent a correction stays until the answer is there. On any other screen nobody would
    // learn that the server refused it, or that it never arrived.
    BackHandler(enabled = analysis.correcting) {}

    FlowScaffold(
        title = stringResource(if (rally != null) R.string.editor_title_rally else R.string.editor_title_gap), onBack = onBack,
        backEnabled = !analysis.correcting,
        actions = {
            // What became of a tap stands next to the button that was tapped. Further up it would lie below the
            // edge of a small screen, and a refused correction would look like a tap that did nothing.
            CorrectionProgress(analysis, R.string.correction_sending)
            CorrectionProblemNotice(analysis)
            if (rally != null) {
                // The end comes first: the winner is judged on what the clip shows at the end of a rally.
                PrimaryButton(
                    stringResource(R.string.action_end_here), onClick = { control.pause(); here().let { at -> if (endsRally(at)) onMove(rally.startMs, at) } },
                    enabled = usable && endsRally(position),
                )
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton(
                        stringResource(R.string.action_start_here), onClick = { control.pause(); here().let { at -> if (startsRally(at)) onMove(at, rally.endMs) } },
                        enabled = usable && startsRally(position), modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                    SecondaryButton(
                        stringResource(R.string.action_split_here), onClick = { control.pause(); here().let { at -> if (cutsRally(at)) onSplit(at) } },
                        enabled = usable && cutsRally(position), modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            } else {
                // The two marks keep their places. The one the user needs next is the filled button.
                val start = stringResource(R.string.action_rally_started)
                val end = stringResource(R.string.action_rally_ended)
                val canStart = usable && startsInPause(position)
                // The video keeps running after the start mark, so the end can be marked as the rally ends.
                val markStart = { here().let { at -> if (startsInPause(at)) marked = at } }
                if (markedStart == null) {
                    PrimaryButton(start, onClick = markStart, enabled = canStart)
                    SecondaryButton(end, onClick = {}, enabled = false)
                } else {
                    SecondaryButton(start, onClick = markStart, enabled = canStart)
                    // The picture only moves forward while it plays, so an end that was offered still lies after the start.
                    PrimaryButton(end, onClick = { control.pause(); onAdd(markedStart, here()) }, enabled = usable && position > markedStart)
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
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.editor_video_failed), style = MaterialTheme.typography.bodyMedium, color = Color.White, textAlign = TextAlign.Center)
                    // A short loss of the connection ends playback for good unless somebody asks for the video again.
                    TextButton(onClick = { control.retry() }) { Text(stringResource(R.string.action_retry), color = Color.White) }
                }
            } else {
                video.picture()
            }
        }
        // One block with small gaps: on a small phone the bar and the steps have to stay on screen with the picture.
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (rally != null) stringResource(R.string.editor_rally_now, rally.sequence, formatClockTenths(rally.startMs), formatClockTenths(rally.endMs))
                        else stringResource(R.string.editor_gap_now, formatClockTenths(window.first), formatClockTenths(window.last)),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(stringResource(R.string.editor_position, formatClockTenths(position)), style = MaterialTheme.typography.bodyMedium)
                }
                FilterChip(selected = fast, onClick = { fast = !fast }, label = { Text(stringResource(R.string.editor_fast)) })
            }

            val seekLabel = stringResource(R.string.editor_seek)
            val spokenPosition = formatClockTenths(position)
            val trackColor = MaterialTheme.colorScheme.outlineVariant
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

            // Where the bar starts and ends. A screen reader gets the position from the bar itself.
            Row(Modifier.fillMaxWidth().clearAndSetSemantics {}, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatClock(window.first), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatClock(window.last), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StepButton(stringResource(R.string.editor_back_1), enabled = !control.failed && position > window.first) { stand(position - 1_000) }
                StepButton(stringResource(R.string.editor_back_02), enabled = !control.failed && position > window.first) { stand(position - 200) }
                val scheme = MaterialTheme.colorScheme
                val canPlay = !control.failed
                Surface(
                    onClick = {
                        if (control.playing) {
                            control.pause()
                        } else {
                            // At the end of the window, or of the video, there is nothing left to play, so playback starts over.
                            if (position >= window.last || control.ended) control.seekTo(rally?.startMs ?: window.first)
                            control.play()
                        }
                    },
                    enabled = canPlay, shape = MaterialTheme.shapes.small,
                    // A button that does nothing does not look like one that does.
                    color = if (canPlay) scheme.primary else scheme.onSurface.copy(alpha = 0.12f),
                    contentColor = if (canPlay) scheme.onPrimary else scheme.onSurface.copy(alpha = 0.38f),
                    modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = Dimens.touchTarget),
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
        }

        if (rally != null) {
            Text(stringResource(R.string.editor_end_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (previous != null) {
                // Merging names no moment of the video, so it works without a picture too.
                SecondaryButton(stringResource(R.string.action_merge_previous), onClick = { merging = true }, enabled = !analysis.correcting)
            }
            // The answers of a rally are changed on its question, "not a rally" included. From the list this is the way there.
            if (onChooseWinner != null) SecondaryButton(stringResource(R.string.action_choose_winner), onChooseWinner, enabled = !analysis.correcting)
        } else {
            if (markedStart != null) Text(stringResource(R.string.editor_marked_start, formatClockTenths(markedStart)), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.editor_gap_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (merging && rally != null && previous != null) {
        // The dialog names every answer the merge takes away, and no answer that is not there.
        val effects = listOfNotNull(
            stringResource(R.string.merge_body, previous.sequence, rally.sequence),
            stringResource(R.string.merge_body_previous_answer, previous.sequence).takeIf { previous.status != Rally.PROPOSED },
            stringResource(R.string.merge_body_reopens, rally.sequence).takeIf { rally.status == Rally.REJECTED },
        )
        ConfirmDialog(
            title = stringResource(R.string.merge_title), body = effects.joinToString(" "),
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
        modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = Dimens.touchTarget),
    ) {
        // At a large text size the unit moves to a second line instead of being cut off.
        Box(Modifier.padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 2)
        }
    }
}
