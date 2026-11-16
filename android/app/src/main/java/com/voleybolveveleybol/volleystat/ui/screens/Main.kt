package com.voleybolveveleybol.volleystat.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.voleybolveveleybol.volleystat.AppUiState
import com.voleybolveveleybol.volleystat.R
import com.voleybolveveleybol.volleystat.ServerTest
import com.voleybolveveleybol.volleystat.UploadProgress
import com.voleybolveveleybol.volleystat.data.UploadPhase
import com.voleybolveveleybol.volleystat.model.MatchKind
import com.voleybolveveleybol.volleystat.model.MatchRecord
import com.voleybolveveleybol.volleystat.model.MatchStatus
import com.voleybolveveleybol.volleystat.model.ServerTarget
import com.voleybolveveleybol.volleystat.model.Team
import com.voleybolveveleybol.volleystat.model.status
import com.voleybolveveleybol.volleystat.ui.components.EmptyState
import com.voleybolveveleybol.volleystat.ui.components.ListRow
import com.voleybolveveleybol.volleystat.ui.components.Notice
import com.voleybolveveleybol.volleystat.ui.components.PrimaryButton
import com.voleybolveveleybol.volleystat.ui.components.RowDivider
import com.voleybolveveleybol.volleystat.ui.components.ScreenTitle
import com.voleybolveveleybol.volleystat.ui.components.SecondaryButton
import com.voleybolveveleybol.volleystat.ui.components.SectionTitle
import com.voleybolveveleybol.volleystat.ui.components.StatusChip
import com.voleybolveveleybol.volleystat.ui.components.TeamBadge
import com.voleybolveveleybol.volleystat.ui.components.Tone
import com.voleybolveveleybol.volleystat.ui.components.VsCard
import com.voleybolveveleybol.volleystat.ui.formatBytes
import com.voleybolveveleybol.volleystat.ui.formatDay
import com.voleybolveveleybol.volleystat.ui.percentOf
import com.voleybolveveleybol.volleystat.ui.theme.Dimens
import com.voleybolveveleybol.volleystat.ui.theme.LocalVsColors

/** A top-level tab: title at the top, scrolling content, system bars respected. */
@Composable
private fun TabScaffold(content: @Composable ColumnScope.() -> Unit) {
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing, containerColor = MaterialTheme.colorScheme.background) { insets ->
        Column(
            Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState())
                .padding(PaddingValues(start = Dimens.screenPadding, end = Dimens.screenPadding, top = 16.dp, bottom = 24.dp)),
            verticalArrangement = Arrangement.spacedBy(Dimens.gapLarge),
            content = content,
        )
    }
}

/** The words and tone that describe where a match stands right now. */
@Composable
fun matchChips(match: MatchRecord, upload: UploadProgress?): List<Pair<String, Tone>> {
    val own = upload?.takeIf { it.matchId == match.localId }
    val chips = mutableListOf<Pair<String, Tone>>()
    chips += when (match.status) {
        MatchStatus.UPLOAD_PENDING -> when {
            own?.running == true -> stringResource(R.string.status_uploading) to Tone.ACTIVE
            own?.failure != null -> stringResource(R.string.status_upload_stopped) to Tone.CAUTION
            own?.pausedByUser == true -> stringResource(R.string.status_paused) to Tone.NEUTRAL
            else -> stringResource(R.string.status_upload_pending) to Tone.NEUTRAL
        }
        MatchStatus.FILE_REJECTED -> stringResource(R.string.status_file_rejected) to Tone.NEGATIVE
        MatchStatus.FILE_UNVERIFIED -> stringResource(R.string.status_file_unverified) to Tone.CAUTION
        MatchStatus.FILE_ACCEPTED -> stringResource(R.string.status_file_accepted) to Tone.POSITIVE
        MatchStatus.ANALYZING -> stringResource(R.string.status_analyzing) to Tone.ACTIVE
        MatchStatus.NEEDS_REVIEW -> stringResource(R.string.status_needs_review) to Tone.CAUTION
        MatchStatus.REPORT_READY -> stringResource(R.string.status_report_ready) to Tone.POSITIVE
        MatchStatus.ANALYSIS_FAILED -> stringResource(R.string.status_analysis_failed) to Tone.NEGATIVE
    }
    if (match.analysis?.mock == true) chips += stringResource(R.string.status_demo) to Tone.CAUTION
    return chips
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MatchCard(match: MatchRecord, upload: UploadProgress?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val own = upload?.takeIf { it.matchId == match.localId }
    VsCard(modifier, onClick = onClick) {
        Text(match.title, style = MaterialTheme.typography.titleMedium)
        if (!match.name.isNullOrBlank()) {
            Text("${match.homeTeam} - ${match.awayTeam}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            listOfNotNull(match.playedOn?.let(::formatDay), stringResource(if (match.kind == MatchKind.OFFICIAL) R.string.kind_official else R.string.kind_training), match.venue).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            matchChips(match, upload).forEach { (text, tone) -> StatusChip(text, tone) }
        }
        if (match.status == MatchStatus.UPLOAD_PENDING) {
            val sent = if (own?.running == true && own.phase == UploadPhase.SENDING) maxOf(own.doneBytes, match.session.sentBytes) else match.session.sentBytes
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(progress = { percentOf(sent, match.video.sizeBytes) / 100f }, modifier = Modifier.fillMaxWidth(), drawStopIndicator = {})
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.upload_amount, formatBytes(sent), formatBytes(match.video.sizeBytes)) + "  ·  " + stringResource(R.string.percent, percentOf(sent, match.video.sizeBytes)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The "new match" card of the design: the one action the home screen is built around. */
@Composable
private fun NewMatchCard(onClick: () -> Unit) {
    val colors = LocalVsColors.current
    Box(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(colors.hero)
            .clickable(role = Role.Button, onClick = onClick).heightIn(min = 132.dp),
    ) {
        // Court line art, decorative. It is left out on narrow cards and with enlarged text,
        // where the lines would run under the words.
        val roomForArt = LocalDensity.current.fontScale <= 1.15f
        Canvas(Modifier.matchParentSize()) {
            if (!roomForArt || size.width < 340.dp.toPx()) return@Canvas
            val stroke = Stroke(1.5.dp.toPx())
            val tint = colors.onHero.copy(alpha = 0.22f)
            val left = size.width * 0.62f
            val top = size.height * 0.2f
            val w = size.width * 0.32f
            val h = size.height * 0.6f
            drawRect(tint, Offset(left, top), androidx.compose.ui.geometry.Size(w, h), style = stroke)
            drawLine(tint, Offset(left + w / 2, top), Offset(left + w / 2, top + h), stroke.width)
            drawLine(tint, Offset(left + w / 3, top), Offset(left + w / 3, top + h), stroke.width)
            drawLine(tint, Offset(left + 2 * w / 3, top), Offset(left + 2 * w / 3, top + h), stroke.width)
        }
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(44.dp).background(colors.onHero.copy(alpha = 0.18f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = null, tint = colors.onHero, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.home_new_match), style = MaterialTheme.typography.headlineSmall, color = colors.onHero)
            Text(stringResource(R.string.home_new_match_body), style = MaterialTheme.typography.bodyMedium, color = colors.onHero)
        }
    }
}

@Composable
fun HomeScreen(
    state: AppUiState,
    onNewMatch: () -> Unit,
    onOpenMatch: (MatchRecord) -> Unit,
    onOpenMatches: () -> Unit,
    onOpenTeams: () -> Unit,
    onOpenGuide: () -> Unit,
) {
    val name = state.settings.displayName.trim()
    val reports = state.matches.count { it.status == MatchStatus.REPORT_READY || it.status == MatchStatus.NEEDS_REVIEW }
    val players = state.teams.sumOf { it.players.size }
    // What needs the user now: unfinished uploads, rejected files, analysis in progress, open reviews.
    val attention = state.matches.filter { it.status != MatchStatus.REPORT_READY && it.status != MatchStatus.FILE_ACCEPTED }
    TabScaffold {
        Text(
            if (name.isEmpty()) stringResource(R.string.home_greeting) + " 👋" else stringResource(R.string.home_greeting_name, name) + " 👋",
            style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() },
        )
        if (state.saveFailed) Notice(stringResource(R.string.store_save_failed), Tone.NEGATIVE)
        NewMatchCard(onNewMatch)
        if (state.draft.started || attention.isNotEmpty()) {
            SectionTitle(stringResource(R.string.home_section_now))
            if (state.draft.started) {
                VsCard(onClick = onNewMatch) {
                    Text(stringResource(R.string.home_draft_title), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.home_draft_action), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
            attention.take(3).forEach { match -> MatchCard(match, state.upload, onClick = { onOpenMatch(match) }) }
        }
        VsCard(padding = PaddingValues(vertical = 4.dp)) {
            ListRow(R.drawable.ic_calendar_today, stringResource(R.string.home_matches),
                if (state.matches.isEmpty()) stringResource(R.string.home_no_matches) else pluralStringResource(R.plurals.count_matches, state.matches.size, state.matches.size), onOpenMatches)
            RowDivider()
            ListRow(R.drawable.ic_apparel, stringResource(R.string.home_teams),
                if (state.teams.isEmpty()) stringResource(R.string.home_no_teams) else pluralStringResource(R.plurals.count_teams, state.teams.size, state.teams.size), onOpenTeams)
            RowDivider()
            ListRow(R.drawable.ic_groups, stringResource(R.string.home_players),
                if (players == 0) stringResource(R.string.home_no_players) else pluralStringResource(R.plurals.count_players, players, players), onOpenTeams)
            RowDivider()
            ListRow(R.drawable.ic_bar_chart, stringResource(R.string.home_reports),
                if (reports == 0) stringResource(R.string.home_no_reports) else pluralStringResource(R.plurals.count_reports, reports, reports), onOpenMatches)
        }
        if (state.matches.isEmpty()) {
            VsCard(padding = PaddingValues(vertical = 4.dp)) {
                ListRow(R.drawable.ic_videocam, stringResource(R.string.home_guide_title), stringResource(R.string.home_guide_body), onOpenGuide)
            }
        }
    }
}

@Composable
fun MatchesScreen(state: AppUiState, onNewMatch: () -> Unit, onOpenMatch: (MatchRecord) -> Unit) {
    TabScaffold {
        ScreenTitle(stringResource(R.string.nav_matches))
        if (state.matches.isEmpty()) {
            EmptyState(R.drawable.ic_calendar_today, stringResource(R.string.matches_empty_title), stringResource(R.string.matches_empty_body)) {
                PrimaryButton(stringResource(R.string.home_new_match), onNewMatch, icon = R.drawable.ic_add)
            }
        } else {
            state.matches.forEach { match -> MatchCard(match, state.upload, onClick = { onOpenMatch(match) }) }
        }
    }
}

@Composable
fun TeamsScreen(teams: List<Team>, onAddTeam: (String) -> Boolean, onOpenTeam: (Team) -> Unit) {
    var newName by rememberSaveable { mutableStateOf("") }
    var failed by rememberSaveable { mutableStateOf(false) }
    TabScaffold {
        ScreenTitle(stringResource(R.string.nav_teams))
        VsCard {
            OutlinedTextField(
                value = newName, onValueChange = { newName = it.take(80); failed = false },
                label = { Text(stringResource(R.string.teams_new_label)) },
                isError = failed,
                supportingText = if (failed) ({ Text(stringResource(R.string.teams_add_error)) }) else null,
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            )
            Spacer(Modifier.height(12.dp))
            PrimaryButton(stringResource(R.string.action_add), onClick = { if (onAddTeam(newName)) newName = "" else failed = true }, enabled = newName.isNotBlank(), icon = R.drawable.ic_add)
        }
        if (teams.isEmpty()) {
            EmptyState(R.drawable.ic_apparel, stringResource(R.string.teams_empty_title), stringResource(R.string.teams_empty_body))
        } else {
            VsCard(padding = PaddingValues(vertical = 4.dp)) {
                teams.forEachIndexed { index, team ->
                    if (index > 0) RowDivider()
                    ListRow(R.drawable.ic_apparel, team.name, pluralStringResource(R.plurals.count_players, team.players.size, team.players.size), onClick = { onOpenTeam(team) })
                }
            }
        }
    }
}

@Composable
private fun FeatureRow(name: String, works: Boolean) {
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (works) StatusChip(stringResource(R.string.state_working), Tone.POSITIVE) else StatusChip(stringResource(R.string.state_not_yet), Tone.NOT_YET)
    }
}

@Composable
private fun PrivacyLine(text: String, icon: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp).size(20.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun ProfileScreen(
    state: AppUiState,
    versionName: String,
    onDisplayName: (String) -> Unit,
    onServerTarget: (ServerTarget) -> Unit,
    onTestServer: () -> Unit,
    onDeleteRecording: (String) -> Unit,
) {
    var recordingToDelete by rememberSaveable { mutableStateOf<String?>(null) }
    TabScaffold {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Image(painterResource(R.drawable.brand_logo), contentDescription = stringResource(R.string.logo_description), modifier = Modifier.size(56.dp).clip(CircleShape))
            Column {
                ScreenTitle(stringResource(R.string.nav_profile))
                Text(stringResource(R.string.brand_name), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        VsCard {
            OutlinedTextField(
                value = state.settings.displayName, onValueChange = onDisplayName,
                label = { Text(stringResource(R.string.profile_name)) },
                supportingText = { Text(stringResource(R.string.profile_name_hint)) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            )
        }

        SectionTitle(stringResource(R.string.profile_today))
        VsCard {
            FeatureRow(stringResource(R.string.feature_match), true)
            FeatureRow(stringResource(R.string.feature_video), true)
            FeatureRow(stringResource(R.string.feature_upload), true)
            FeatureRow(stringResource(R.string.feature_check), true)
            FeatureRow(stringResource(R.string.feature_review), true)
            FeatureRow(stringResource(R.string.feature_analysis), false)
        }

        SectionTitle(stringResource(R.string.profile_privacy))
        VsCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // In the order a user asks: what leaves the phone, who sees it, how long it stays, how it goes away.
                PrivacyLine(stringResource(R.string.privacy_server), R.drawable.ic_smartphone)
                PrivacyLine(stringResource(R.string.roster_privacy), R.drawable.ic_groups)
                PrivacyLine(stringResource(R.string.privacy_accounts), R.drawable.ic_lock)
                PrivacyLine(stringResource(R.string.privacy_retention), R.drawable.ic_schedule)
                PrivacyLine(stringResource(R.string.privacy_delete), R.drawable.ic_delete)
                PrivacyLine(stringResource(R.string.privacy_faces), R.drawable.ic_person)
                PrivacyLine(stringResource(R.string.privacy_training), R.drawable.ic_shield)
                PrivacyLine(stringResource(R.string.privacy_picker), R.drawable.ic_photo_library)
            }
        }

        SectionTitle(stringResource(R.string.profile_server))
        if (!state.serverConfigured) {
            Notice(stringResource(R.string.server_none), Tone.NEUTRAL)
        } else {
            VsCard {
                listOf(
                    Triple(ServerTarget.EMULATOR, R.string.server_emulator, R.string.server_emulator_body),
                    Triple(ServerTarget.USB_DEVICE, R.string.server_usb, R.string.server_usb_body),
                ).forEach { (target, title, body) ->
                    val selected = state.settings.serverTarget == target
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(selected = selected, role = Role.RadioButton, onClick = { onServerTarget(target) }),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RadioButton(selected = selected, onClick = null)
                        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                            Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                SecondaryButton(stringResource(R.string.action_test_server), onTestServer, enabled = state.serverTest != ServerTest.TESTING)
                when (state.serverTest) {
                    ServerTest.IDLE -> Unit
                    ServerTest.TESTING -> Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.server_testing), style = MaterialTheme.typography.bodyMedium)
                    }
                    ServerTest.OK -> Notice(stringResource(R.string.server_ok), Tone.POSITIVE, Modifier.padding(top = 12.dp))
                    ServerTest.OK_NO_PROBE -> Notice(stringResource(R.string.server_ok_no_probe), Tone.CAUTION, Modifier.padding(top = 12.dp))
                    ServerTest.UNREACHABLE -> Notice(stringResource(R.string.server_unreachable), Tone.NEGATIVE, Modifier.padding(top = 12.dp))
                }
            }
        }

        SectionTitle(stringResource(R.string.profile_recordings))
        VsCard {
            Text(stringResource(R.string.recordings_helper), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.recordings.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.recordings_empty), style = MaterialTheme.typography.bodyMedium)
            }
            state.recordings.forEach { recording ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(recording.name, style = MaterialTheme.typography.bodyMedium)
                        Text(formatBytes(recording.sizeBytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    // A file an unfinished upload still reads from can not be deleted here.
                    if (!state.recordingInUse(recording.path)) {
                        IconButton(onClick = { recordingToDelete = recording.path }) {
                            Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.recording_delete_description, recording.name))
                        }
                    }
                }
                if (state.recordingInUse(recording.path)) {
                    Text(stringResource(R.string.recordings_in_use), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text(stringResource(R.string.profile_version, versionName), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    val pending = state.recordings.firstOrNull { it.path == recordingToDelete }
    if (pending != null) {
        AlertDialog(
            onDismissRequest = { recordingToDelete = null },
            title = { Text(stringResource(R.string.recording_delete_title)) },
            text = { Text(stringResource(R.string.recording_delete_body, pending.name)) },
            confirmButton = { TextButton(onClick = { onDeleteRecording(pending.path); recordingToDelete = null }) { Text(stringResource(R.string.action_delete)) } },
            dismissButton = { TextButton(onClick = { recordingToDelete = null }) { Text(stringResource(R.string.action_give_up)) } },
        )
    }
}
