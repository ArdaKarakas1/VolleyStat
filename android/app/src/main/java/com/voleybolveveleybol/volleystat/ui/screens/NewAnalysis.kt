package com.voleybolveveleybol.volleystat.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.voleybolveveleybol.volleystat.R
import com.voleybolveveleybol.volleystat.SourceProblem
import com.voleybolveveleybol.volleystat.model.Draft
import com.voleybolveveleybol.volleystat.model.LocalVideo
import com.voleybolveveleybol.volleystat.model.MAX_VIDEO_BYTES
import com.voleybolveveleybol.volleystat.model.teamNamesValid
import com.voleybolveveleybol.volleystat.model.uploadFormat
import com.voleybolveveleybol.volleystat.ui.components.FactRow
import com.voleybolveveleybol.volleystat.ui.components.Notice
import com.voleybolveveleybol.volleystat.ui.components.PrimaryButton
import com.voleybolveveleybol.volleystat.ui.components.SecondaryButton
import com.voleybolveveleybol.volleystat.ui.components.SectionTitle
import com.voleybolveveleybol.volleystat.ui.components.StatusChip
import com.voleybolveveleybol.volleystat.ui.components.TeamTag
import com.voleybolveveleybol.volleystat.ui.components.Tone
import com.voleybolveveleybol.volleystat.ui.components.VsCard
import com.voleybolveveleybol.volleystat.ui.formatBytes
import com.voleybolveveleybol.volleystat.ui.formatDuration
import com.voleybolveveleybol.volleystat.ui.theme.LocalVsColors

/** One step of a linear task: back arrow, short title, scrolling body, actions pinned above the gesture area. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlowScaffold(
    title: String,
    onBack: () -> Unit,
    step: Int? = null,
    stepCount: Int = 3,
    actions: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, style = MaterialTheme.typography.titleLarge)
                        if (step != null) {
                            Text(stringResource(R.string.step_count, step, stepCount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            Column(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = actions,
            )
        },
    ) { insets ->
        Column(
            Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

@Composable
fun TeamsScreen(draft: Draft, onTeams: (String, String) -> Unit, onContinue: () -> Unit, onBack: () -> Unit) {
    val a = draft.teamA.trim()
    val b = draft.teamB.trim()
    val sameNames = a.isNotEmpty() && a.equals(b, ignoreCase = true)
    val shortA = a.length == 1
    val shortB = b.length == 1
    FlowScaffold(
        title = stringResource(R.string.teams_title), onBack = onBack, step = 1,
        actions = { PrimaryButton(stringResource(R.string.action_continue), onContinue, enabled = teamNamesValid(draft.teamA, draft.teamB)) },
    ) {
        OutlinedTextField(
            value = draft.teamA, onValueChange = { onTeams(it.take(80), draft.teamB) },
            label = { Text(stringResource(R.string.teams_label_a)) },
            leadingIcon = { TeamTag(isTeamA = true) },
            isError = shortA,
            supportingText = if (shortA) ({ Text(stringResource(R.string.teams_error_short)) }) else null,
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
        )
        OutlinedTextField(
            value = draft.teamB, onValueChange = { onTeams(draft.teamA, it.take(80)) },
            label = { Text(stringResource(R.string.teams_label_b)) },
            leadingIcon = { TeamTag(isTeamA = false) },
            isError = shortB || sameNames,
            supportingText = when {
                sameNames -> ({ Text(stringResource(R.string.teams_error_same)) })
                shortB -> ({ Text(stringResource(R.string.teams_error_short)) })
                else -> null
            },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
        )
        Text(stringResource(R.string.teams_helper), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SourceCard(title: String, body: String, icon: Int, onClick: () -> Unit, enabled: Boolean) {
    VsCard(onClick = if (enabled) onClick else null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(48.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SourceScreen(
    draft: Draft,
    reading: Boolean,
    problem: SourceProblem?,
    onPickGallery: () -> Unit,
    onRecord: () -> Unit,
    onOpenGuide: () -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
) {
    FlowScaffold(
        title = stringResource(R.string.source_title), onBack = onBack, step = 2,
        actions = { if (draft.video != null && !reading) PrimaryButton(stringResource(R.string.action_continue), onContinue) },
    ) {
        // The gallery comes first: it is the dependable path.
        SourceCard(stringResource(R.string.source_gallery_title), stringResource(R.string.source_gallery_body), R.drawable.ic_photo_library, onPickGallery, enabled = !reading)
        SourceCard(stringResource(R.string.source_camera_title), stringResource(R.string.source_camera_body), R.drawable.ic_videocam, onRecord, enabled = !reading)
        if (reading) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.source_reading), style = MaterialTheme.typography.bodyMedium)
            }
        }
        when (problem) {
            SourceProblem.CAMERA_MISSING -> Notice(stringResource(R.string.source_camera_missing), Tone.CAUTION)
            SourceProblem.CAMERA_EMPTY -> Notice(stringResource(R.string.source_camera_cancelled), Tone.NEUTRAL)
            SourceProblem.UNREADABLE -> Notice(stringResource(R.string.source_pick_failed), Tone.CAUTION)
            null -> Unit
        }
        draft.video?.let { video ->
            VsCard {
                Text(stringResource(R.string.selected_video), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(video.fileName, style = MaterialTheme.typography.titleMedium)
                Text(formatBytes(video.sizeBytes), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(stringResource(R.string.source_limits), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onOpenGuide, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.source_guide_link), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * A court seen from the camera, as line art. Good: from behind the end line and high, the
 * whole far court inside the frame. Bad: too close and low, the far corners cut off.
 */
@Composable
fun FramingDiagram(good: Boolean, modifier: Modifier = Modifier) {
    val line = MaterialTheme.colorScheme.onSurface
    val accent = MaterialTheme.colorScheme.primary
    val frameFill = MaterialTheme.colorScheme.surfaceContainerHigh
    val description = stringResource(if (good) R.string.guide_good_description else R.string.guide_bad_description)
    Canvas(modifier.fillMaxWidth().aspectRatio(16f / 9f).semantics { contentDescription = description }) {
        val stroke = 2.dp.toPx()
        val frame = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(Rect(Offset.Zero, size), CornerRadius(8.dp.toPx()))) }
        drawPath(frame, frameFill)
        clipPath(frame) {
            val w = size.width
            val h = size.height
            // Court corners in frame coordinates: near-left, near-right, far-right, far-left.
            val corners = if (good) {
                listOf(Offset(w * 0.08f, h * 0.92f), Offset(w * 0.92f, h * 0.92f), Offset(w * 0.70f, h * 0.30f), Offset(w * 0.30f, h * 0.30f))
            } else {
                listOf(Offset(-w * 0.55f, h * 1.05f), Offset(w * 1.55f, h * 1.05f), Offset(w * 1.18f, h * 0.52f), Offset(-w * 0.18f, h * 0.52f))
            }
            val court = Path().apply {
                moveTo(corners[0].x, corners[0].y)
                corners.drop(1).forEach { lineTo(it.x, it.y) }
                close()
            }
            drawPath(court, line, style = Stroke(stroke))
            // Net: halfway between the near and far end lines, with two posts.
            fun mid(a: Offset, b: Offset) = Offset((a.x + b.x) / 2, (a.y + b.y) / 2)
            val netLeft = mid(corners[0], corners[3])
            val netRight = mid(corners[1], corners[2])
            val netHeight = h * if (good) 0.16f else 0.30f
            drawLine(line, netLeft, netRight, stroke)
            drawLine(line, netLeft.copy(y = netLeft.y - netHeight), netRight.copy(y = netRight.y - netHeight), stroke * 1.5f)
            drawLine(line, netLeft, netLeft.copy(y = netLeft.y - netHeight), stroke)
            drawLine(line, netRight, netRight.copy(y = netRight.y - netHeight), stroke)
            if (good) {
                // The two far corners the rule asks for, marked with the accent.
                listOf(corners[2], corners[3]).forEach { drawCircle(accent, radius = 5.dp.toPx(), center = it) }
            }
        }
        drawPath(frame, line, style = Stroke(stroke))
    }
}

@Composable
fun GuideScreen(onBack: () -> Unit) {
    val extra = LocalVsColors.current
    FlowScaffold(title = stringResource(R.string.guide_title), onBack = onBack) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(true, false).forEach { good ->
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FramingDiagram(good)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(
                            painterResource(if (good) R.drawable.ic_check_circle else R.drawable.ic_error), contentDescription = null,
                            tint = if (good) extra.positive else MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp),
                        )
                        Text(stringResource(if (good) R.string.guide_good else R.string.guide_bad), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
        VsCard {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                listOf(R.string.guide_rule_1, R.string.guide_rule_2, R.string.guide_rule_3, R.string.guide_rule_4, R.string.guide_rule_5).forEachIndexed { index, rule ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(28.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                            Text("${index + 1}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                        Text(stringResource(rule), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        Notice(stringResource(R.string.guide_note), Tone.NEUTRAL)
    }
}

@Composable
fun VideoThumbnail(video: LocalVideo, modifier: Modifier = Modifier) {
    val bitmap = remember(video.thumbnailPath) { video.thumbnailPath?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() } }
    if (bitmap != null) {
        Image(
            bitmap.asImageBitmap(), contentDescription = stringResource(R.string.thumbnail_description), contentScale = ContentScale.Crop,
            modifier = modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(MaterialTheme.shapes.large),
        )
    }
}

@Composable
private fun ValueOrUnreadable(value: String?) {
    if (value != null) {
        Text(value, style = MaterialTheme.typography.bodyLarge)
    } else {
        Text(stringResource(R.string.state_unreadable), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ConfirmScreen(
    video: LocalVideo,
    rightsAccepted: Boolean,
    serverConfigured: Boolean,
    onRights: (Boolean) -> Unit,
    onStart: () -> Unit,
    onChangeVideo: () -> Unit,
    onBack: () -> Unit,
) {
    val format = uploadFormat(video.mimeType, video.fileName)
    val tooLarge = video.sizeBytes > MAX_VIDEO_BYTES
    val blocked = tooLarge || format == null
    val portrait = video.width != null && video.height != null && video.height > video.width
    val lowResolution = video.width != null && video.height != null && minOf(video.width, video.height) < 720
    FlowScaffold(
        title = stringResource(R.string.confirm_title), onBack = onBack, step = 3,
        actions = {
            if (blocked) {
                PrimaryButton(stringResource(R.string.action_change_video), onChangeVideo)
            } else {
                PrimaryButton(stringResource(R.string.action_start_upload), onStart, enabled = rightsAccepted && serverConfigured, icon = R.drawable.ic_upload)
                SecondaryButton(stringResource(R.string.action_change_video), onChangeVideo)
            }
        },
    ) {
        VideoThumbnail(video)
        Text(video.fileName, style = MaterialTheme.typography.titleMedium)

        if (tooLarge) Notice(stringResource(R.string.confirm_block_size), Tone.NEGATIVE)
        if (format == null) Notice(stringResource(R.string.confirm_block_type), Tone.NEGATIVE)
        if (portrait) Notice(stringResource(R.string.confirm_warn_portrait), Tone.CAUTION)
        if (lowResolution) Notice(stringResource(R.string.confirm_warn_resolution), Tone.CAUTION)

        SectionTitle(stringResource(R.string.confirm_phone_checks))
        VsCard {
            FactRow(stringResource(R.string.check_orientation)) {
                ValueOrUnreadable(if (video.width == null || video.height == null) null else stringResource(if (portrait) R.string.check_orientation_portrait else R.string.check_orientation_landscape))
            }
            FactRow(stringResource(R.string.check_resolution)) {
                ValueOrUnreadable(if (video.width == null || video.height == null) null else stringResource(R.string.file_resolution, video.width, video.height))
            }
            FactRow(stringResource(R.string.check_duration)) { ValueOrUnreadable(video.durationMs?.let(::formatDuration)) }
            FactRow(stringResource(R.string.check_size)) { ValueOrUnreadable(formatBytes(video.sizeBytes)) }
            FactRow(stringResource(R.string.check_type)) { ValueOrUnreadable(format?.extension?.uppercase()) }
        }
        Text(stringResource(R.string.confirm_provisional), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        SectionTitle(stringResource(R.string.confirm_not_checked))
        VsCard {
            Text(stringResource(R.string.confirm_not_checked_helper), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            FactRow(stringResource(R.string.check_court)) { StatusChip(stringResource(R.string.state_no_check), Tone.NOT_YET) }
            Spacer(Modifier.height(8.dp))
            FactRow(stringResource(R.string.check_light)) { StatusChip(stringResource(R.string.state_no_check), Tone.NOT_YET) }
        }

        if (!blocked) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(value = rightsAccepted, role = Role.Checkbox, onValueChange = onRights),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Checkbox(checked = rightsAccepted, onCheckedChange = null)
                Text(stringResource(R.string.confirm_rights), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            }
            Text(stringResource(R.string.confirm_training_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!serverConfigured) Notice(stringResource(R.string.confirm_no_server), Tone.NEUTRAL)
        }
    }
}
