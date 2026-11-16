package com.voleybolveveleybol.volleystat.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
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
import com.voleybolveveleybol.volleystat.model.MatchKind
import com.voleybolveveleybol.volleystat.model.Team
import com.voleybolveveleybol.volleystat.model.teamNamesValid
import com.voleybolveveleybol.volleystat.model.uploadFormat
import com.voleybolveveleybol.volleystat.ui.components.FactRow
import com.voleybolveveleybol.volleystat.ui.components.ListRow
import com.voleybolveveleybol.volleystat.ui.components.Notice
import com.voleybolveveleybol.volleystat.ui.components.PrimaryButton
import com.voleybolveveleybol.volleystat.ui.components.RowDivider
import com.voleybolveveleybol.volleystat.ui.components.SecondaryButton
import com.voleybolveveleybol.volleystat.ui.components.SectionTitle
import com.voleybolveveleybol.volleystat.ui.components.SegmentedChoice
import com.voleybolveveleybol.volleystat.ui.components.SelectCard
import com.voleybolveveleybol.volleystat.ui.components.StatusChip
import com.voleybolveveleybol.volleystat.ui.components.TeamBadge
import com.voleybolveveleybol.volleystat.ui.components.Tone
import com.voleybolveveleybol.volleystat.ui.components.VsCard
import com.voleybolveveleybol.volleystat.ui.formatBytes
import com.voleybolveveleybol.volleystat.ui.formatDay
import com.voleybolveveleybol.volleystat.ui.formatDuration
import com.voleybolveveleybol.volleystat.ui.theme.Dimens
import com.voleybolveveleybol.volleystat.ui.theme.LocalVsColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** One step of a linear task: back arrow, centred title, scrolling body, actions pinned above the gesture area. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlowScaffold(
    title: String,
    onBack: () -> Unit,
    topActions: @Composable () -> Unit = {},
    /** False while leaving would hide how something ended that the screen has started. */
    backEnabled: Boolean = true,
    actions: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(title, style = MaterialTheme.typography.titleLarge) },
                navigationIcon = { IconButton(onClick = onBack, enabled = backEnabled) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back)) } },
                actions = { topActions() },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            if (actions != null) {
                Column(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                        .padding(horizontal = Dimens.screenPadding, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    content = actions,
                )
            }
        },
    ) { insets ->
        Column(
            Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(horizontal = Dimens.screenPadding, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(Dimens.gapLarge),
            content = content,
        )
    }
}

/** 03: only defaults and optional details. Nothing here blocks the user. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatchCreateScreen(draft: Draft, today: String, onEdit: ((Draft) -> Draft) -> Unit, onContinue: () -> Unit, onBack: () -> Unit) {
    var pickingDate by rememberSaveable { mutableStateOf(false) }
    val day = draft.playedOn.ifBlank { today }
    FlowScaffold(
        title = stringResource(R.string.match_new_title), onBack = onBack,
        actions = { PrimaryButton(stringResource(R.string.action_continue), onContinue) },
    ) {
        SectionTitle(stringResource(R.string.match_info))
        OutlinedTextField(
            value = draft.name, onValueChange = { text -> onEdit { it.copy(name = text.take(80)) } },
            label = { Text(stringResource(R.string.match_name)) }, placeholder = { Text(stringResource(R.string.match_name_hint)) },
            singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
        )
        // The date is a button, not a text field: nobody types a date by hand.
        VsCard(onClick = { pickingDate = true }, padding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(painterResource(R.drawable.ic_calendar_today), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.match_date), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(formatDay(day), style = MaterialTheme.typography.titleSmall)
                }
                Text(stringResource(R.string.match_date_pick), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
        OutlinedTextField(
            value = draft.venue, onValueChange = { text -> onEdit { it.copy(venue = text.take(80)) } },
            label = { Text(stringResource(R.string.match_venue)) }, placeholder = { Text(stringResource(R.string.match_name_hint)) },
            singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.match_kind), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SegmentedChoice(
                listOf(stringResource(R.string.kind_official), stringResource(R.string.kind_training)),
                selectedIndex = if (draft.kind == MatchKind.OFFICIAL) 0 else 1,
                onSelect = { index -> onEdit { it.copy(kind = if (index == 0) MatchKind.OFFICIAL else MatchKind.TRAINING) } },
            )
            Text(stringResource(R.string.match_kind_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        StatusChip(stringResource(R.string.match_rules), Tone.NEUTRAL)
    }
    if (pickingDate) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = LocalDate.parse(day).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    picker.selectedDateMillis?.let { millis -> onEdit { it.copy(playedOn = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()) } }
                    pickingDate = false
                }) { Text(stringResource(R.string.action_continue)) }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text(stringResource(R.string.action_give_up)) } },
        ) { DatePicker(picker) }
    }
}

@Composable
private fun TeamPickRow(home: Boolean, name: String, onClick: () -> Unit) {
    VsCard(onClick = onClick, padding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TeamBadge(home)
            Text(
                name.ifBlank { stringResource(R.string.match_team_pick) }, style = MaterialTheme.typography.titleSmall,
                color = if (name.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f),
            )
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 04: pick two teams. A roster is optional and can be completed after the analysis. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatchTeamsScreen(
    draft: Draft,
    teams: List<Team>,
    onChoose: (home: Boolean, team: Team) -> Unit,
    onAddTeam: (String) -> Team?,
    onOpenRoster: (Team) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
) {
    var sheetFor by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var rosterSheet by rememberSaveable { mutableStateOf(false) }
    val same = draft.homeTeam.isNotBlank() && draft.homeTeam.trim().equals(draft.awayTeam.trim(), ignoreCase = true)
    val chosen = listOfNotNull(teams.firstOrNull { it.id == draft.homeTeamId }, teams.firstOrNull { it.id == draft.awayTeamId })
    FlowScaffold(
        title = stringResource(R.string.match_teams_title), onBack = onBack,
        actions = { PrimaryButton(stringResource(R.string.action_continue), onContinue, enabled = teamNamesValid(draft.homeTeam, draft.awayTeam)) },
    ) {
        SectionTitle(stringResource(R.string.team_home))
        TeamPickRow(home = true, name = draft.homeTeam, onClick = { sheetFor = true })
        SectionTitle(stringResource(R.string.team_away))
        TeamPickRow(home = false, name = draft.awayTeam, onClick = { sheetFor = false })
        if (same) Notice(stringResource(R.string.match_team_same), Tone.NEGATIVE)
        VsCard {
            Text(stringResource(R.string.match_rosters), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.match_rosters_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            SecondaryButton(stringResource(R.string.match_rosters_action), onClick = { if (chosen.size == 1) onOpenRoster(chosen[0]) else rosterSheet = true }, enabled = chosen.isNotEmpty())
        }
    }

    sheetFor?.let { home ->
        var newName by rememberSaveable { mutableStateOf("") }
        var failed by rememberSaveable { mutableStateOf(false) }
        ModalBottomSheet(onDismissRequest = { sheetFor = null }, containerColor = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(horizontal = Dimens.screenPadding).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(if (home) R.string.team_home else R.string.team_away), style = MaterialTheme.typography.titleLarge)
                if (teams.isNotEmpty()) {
                    Text(stringResource(R.string.team_sheet_saved), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.selectableGroup()) {
                        teams.forEachIndexed { index, team ->
                            if (index > 0) RowDivider()
                            ListRow(R.drawable.ic_apparel, team.name, null, onClick = { onChoose(home, team); sheetFor = null })
                        }
                    }
                }
                OutlinedTextField(
                    value = newName, onValueChange = { newName = it.take(80); failed = false },
                    label = { Text(stringResource(R.string.teams_new_label)) }, isError = failed,
                    supportingText = if (failed) ({ Text(stringResource(R.string.teams_add_error)) }) else null,
                    singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                )
                PrimaryButton(stringResource(R.string.team_sheet_add), enabled = newName.isNotBlank(), onClick = {
                    val team = onAddTeam(newName)
                    if (team == null) failed = true else { onChoose(home, team); sheetFor = null }
                })
            }
        }
    }
    if (rosterSheet) {
        ModalBottomSheet(onDismissRequest = { rosterSheet = false }, containerColor = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Text(stringResource(R.string.match_rosters_pick), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = Dimens.screenPadding, vertical = 8.dp))
                chosen.forEach { team -> ListRow(R.drawable.ic_apparel, team.name, null, onClick = { rosterSheet = false; onOpenRoster(team) }) }
            }
        }
    }
}

/** 05: one choice, one button. The recommended settings are advice, not a check. */
@Composable
fun VideoSourceScreen(
    cameraSelected: Boolean,
    reading: Boolean,
    problem: SourceProblem?,
    onSelect: (camera: Boolean) -> Unit,
    onGo: () -> Unit,
    onOpenGuide: () -> Unit,
    onBack: () -> Unit,
    /** The video an unfinished setup already holds, so returning to the setup does not force a new recording. */
    existing: LocalVideo? = null,
    onContinueExisting: () -> Unit = {},
) {
    val colors = LocalVsColors.current
    FlowScaffold(
        title = stringResource(R.string.source_title), onBack = onBack,
        actions = {
            PrimaryButton(
                stringResource(if (cameraSelected) R.string.action_record else R.string.action_pick_video), onGo, enabled = !reading,
                icon = if (cameraSelected) R.drawable.ic_videocam else R.drawable.ic_photo_library,
            )
        },
    ) {
        if (existing != null) {
            VsCard(padding = PaddingValues(vertical = 4.dp)) {
                ListRow(R.drawable.ic_smart_display, existing.fileName, stringResource(R.string.source_existing_action), onContinueExisting)
            }
        }
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SelectCard(cameraSelected, R.drawable.ic_videocam, stringResource(R.string.source_camera_title), stringResource(R.string.source_camera_body), onClick = { onSelect(true) }, enabled = !reading)
            SelectCard(!cameraSelected, R.drawable.ic_photo_library, stringResource(R.string.source_gallery_title), stringResource(R.string.source_gallery_body), onClick = { onSelect(false) }, enabled = !reading)
        }
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
        VsCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(painterResource(R.drawable.ic_info), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Text(stringResource(R.string.source_recommended), style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(8.dp))
            listOf(R.string.source_tip_1, R.string.source_tip_2, R.string.source_tip_3, R.string.source_tip_4).forEach { tip ->
                Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = colors.positive, modifier = Modifier.size(18.dp))
                    Text(stringResource(tip), style = MaterialTheme.typography.bodyMedium)
                }
            }
            TextButton(onClick = onOpenGuide, modifier = Modifier.heightIn(min = Dimens.touchTarget)) {
                Text(stringResource(R.string.source_guide_link), style = MaterialTheme.typography.labelLarge)
            }
        }
        Text(stringResource(R.string.source_limits), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    val frameFill = MaterialTheme.colorScheme.primaryContainer
    val description = stringResource(if (good) R.string.guide_good_description else R.string.guide_bad_description)
    Canvas(modifier.fillMaxWidth().aspectRatio(16f / 9f).semantics { contentDescription = description }) {
        val stroke = 2.dp.toPx()
        val frame = Path().apply { addRoundRect(RoundRect(Rect(Offset.Zero, size), CornerRadius(8.dp.toPx()))) }
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
            fun mid(a: Offset, b: Offset) = Offset((a.x + b.x) / 2, (a.y + b.y) / 2)
            val netLeft = mid(corners[0], corners[3])
            val netRight = mid(corners[1], corners[2])
            val netHeight = h * if (good) 0.16f else 0.30f
            drawLine(line, netLeft, netRight, stroke)
            drawLine(line, netLeft.copy(y = netLeft.y - netHeight), netRight.copy(y = netRight.y - netHeight), stroke * 1.5f)
            drawLine(line, netLeft, netLeft.copy(y = netLeft.y - netHeight), stroke)
            drawLine(line, netRight, netRight.copy(y = netRight.y - netHeight), stroke)
            if (good) listOf(corners[2], corners[3]).forEach { drawCircle(accent, radius = 5.dp.toPx(), center = it) }
        }
        drawPath(frame, line, style = Stroke(stroke))
    }
}

@Composable
fun GuideScreen(onBack: () -> Unit) {
    val colors = LocalVsColors.current
    FlowScaffold(title = stringResource(R.string.guide_title), onBack = onBack) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(true, false).forEach { good ->
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FramingDiagram(good)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(
                            painterResource(if (good) R.drawable.ic_check_circle else R.drawable.ic_error), contentDescription = null,
                            tint = if (good) colors.positive else MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp),
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
                        Box(Modifier.size(28.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                            Text("${index + 1}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        Text(stringResource(rule), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
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
        Text(value, style = MaterialTheme.typography.titleSmall)
    } else {
        Text(stringResource(R.string.state_unreadable), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Between choosing a video and uploading it: what the phone could read, what nobody checked, and the rights statement. */
@Composable
fun VideoCheckScreen(
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
        title = stringResource(R.string.check_title), onBack = onBack,
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
        Text(video.fileName, style = MaterialTheme.typography.titleSmall)

        if (tooLarge) Notice(stringResource(R.string.check_block_size), Tone.NEGATIVE)
        if (format == null) Notice(stringResource(R.string.check_block_type), Tone.NEGATIVE)
        if (portrait) Notice(stringResource(R.string.check_warn_portrait), Tone.CAUTION)
        if (lowResolution) Notice(stringResource(R.string.check_warn_resolution), Tone.CAUTION)

        SectionTitle(stringResource(R.string.check_phone_facts))
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
        Text(stringResource(R.string.check_provisional), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        SectionTitle(stringResource(R.string.check_not_checked))
        VsCard {
            Text(stringResource(R.string.check_not_checked_helper), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                Text(stringResource(R.string.check_rights), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
            Text(stringResource(R.string.check_training_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!serverConfigured) Notice(stringResource(R.string.check_no_server), Tone.NEUTRAL)
        }
    }
}
