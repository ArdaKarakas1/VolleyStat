package com.voleybolveveleybol.volleystat.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.voleybolveveleybol.volleystat.AppUiState
import com.voleybolveveleybol.volleystat.R
import com.voleybolveveleybol.volleystat.ServerTest
import com.voleybolveveleybol.volleystat.UploadProgress
import com.voleybolveveleybol.volleystat.model.MatchRecord
import com.voleybolveveleybol.volleystat.model.MatchStatus
import com.voleybolveveleybol.volleystat.model.ServerTarget
import com.voleybolveveleybol.volleystat.model.status
import com.voleybolveveleybol.volleystat.ui.components.MatchHeader
import com.voleybolveveleybol.volleystat.ui.components.Notice
import com.voleybolveveleybol.volleystat.ui.components.PrimaryButton
import com.voleybolveveleybol.volleystat.ui.components.ScreenTitle
import com.voleybolveveleybol.volleystat.ui.components.SecondaryButton
import com.voleybolveveleybol.volleystat.ui.components.SectionTitle
import com.voleybolveveleybol.volleystat.ui.components.StatusChip
import com.voleybolveveleybol.volleystat.ui.components.Tone
import com.voleybolveveleybol.volleystat.ui.components.VsCard
import com.voleybolveveleybol.volleystat.ui.formatBytes
import com.voleybolveveleybol.volleystat.ui.formatMoment
import com.voleybolveveleybol.volleystat.ui.percentOf

private val ScreenPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 96.dp)

/** The words and tone that describe where a match stands right now. */
@Composable
fun matchChips(match: MatchRecord, upload: UploadProgress?): List<Pair<String, Tone>> {
    val own = upload?.takeIf { it.matchId == match.localId }
    return when (match.status) {
        MatchStatus.FILE_ACCEPTED -> listOf(
            stringResource(R.string.status_file_accepted) to Tone.POSITIVE,
            stringResource(R.string.status_analysis_none) to Tone.NOT_YET,
        )
        MatchStatus.FILE_REJECTED -> listOf(stringResource(R.string.status_file_rejected) to Tone.NEGATIVE)
        MatchStatus.FILE_UNVERIFIED -> listOf(stringResource(R.string.status_file_unverified) to Tone.CAUTION)
        MatchStatus.UPLOAD_PENDING -> listOf(
            when {
                own?.running == true -> stringResource(R.string.status_uploading) to Tone.ACTIVE
                own?.failure != null -> stringResource(R.string.status_upload_stopped) to Tone.CAUTION
                own?.pausedByUser == true -> stringResource(R.string.status_paused) to Tone.NEUTRAL
                else -> stringResource(R.string.status_upload_pending) to Tone.NEUTRAL
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MatchCard(match: MatchRecord, upload: UploadProgress?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val own = upload?.takeIf { it.matchId == match.localId }
    VsCard(modifier, onClick = onClick) {
        MatchHeader(match.teamA, match.teamB)
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            matchChips(match, upload).forEach { (text, tone) -> StatusChip(text, tone) }
        }
        if (match.status == MatchStatus.UPLOAD_PENDING) {
            val sent = if (own?.running == true) maxOf(own.doneBytes.takeIf { own.phase == com.voleybolveveleybol.volleystat.data.UploadPhase.SENDING } ?: 0, match.session.sentBytes) else match.session.sentBytes
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(progress = { percentOf(sent, match.video.sizeBytes) / 100f }, modifier = Modifier.fillMaxWidth(), drawStopIndicator = {})
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.upload_amount, formatBytes(sent), formatBytes(match.video.sizeBytes)) + "  ·  " + stringResource(R.string.upload_percent, percentOf(sent, match.video.sizeBytes)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Spacer(Modifier.height(8.dp))
            Text(formatMoment(match.checkedAtMs ?: match.createdAtMs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BrandRow() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Image(painterResource(R.drawable.brand_logo), contentDescription = stringResource(R.string.logo_description), modifier = Modifier.size(48.dp))
        Column {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.brand_name), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NewAnalysisFab(onClick: () -> Unit) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
        text = { Text(stringResource(R.string.action_new_analysis), style = MaterialTheme.typography.labelLarge) },
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    )
}

@Composable
private fun GuideCard(onOpenGuide: () -> Unit) {
    VsCard(onClick = onOpenGuide) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(painterResource(R.drawable.ic_videocam), contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(28.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.home_guide_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.home_guide_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun HomeScreen(
    state: AppUiState,
    onNewAnalysis: () -> Unit,
    onOpenMatch: (MatchRecord) -> Unit,
    onSeeAll: () -> Unit,
    onOpenGuide: () -> Unit,
) {
    val draftStarted = state.draft.teamA.isNotBlank() || state.draft.teamB.isNotBlank() || state.draft.video != null
    val empty = state.matches.isEmpty() && !draftStarted
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background,
        // On the empty screen the button in the page is the one primary target.
        floatingActionButton = { if (!empty) NewAnalysisFab(onNewAnalysis) },
    ) { insets ->
        Column(
            Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BrandRow()
            if (empty) {
                Spacer(Modifier.height(8.dp))
                ScreenTitle(stringResource(R.string.home_empty_title))
                VsCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            StatusChip(stringResource(R.string.state_working), Tone.POSITIVE)
                        }
                        Text(stringResource(R.string.home_empty_works), style = MaterialTheme.typography.bodyLarge)
                        StatusChip(stringResource(R.string.state_not_yet), Tone.NOT_YET)
                        Text(stringResource(R.string.home_empty_missing), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                PrimaryButton(stringResource(R.string.action_new_analysis), onNewAnalysis, icon = R.drawable.ic_add)
                GuideCard(onOpenGuide)
            } else {
                val attention = state.matches.filter { it.status != MatchStatus.FILE_ACCEPTED }
                val settled = state.matches.filter { it.status == MatchStatus.FILE_ACCEPTED }
                if (draftStarted || attention.isNotEmpty()) {
                    SectionTitle(stringResource(R.string.home_section_now))
                    if (draftStarted) {
                        VsCard(onClick = onNewAnalysis) {
                            Text(stringResource(R.string.home_draft_title), style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (state.draft.teamA.isNotBlank() && state.draft.teamB.isNotBlank()) stringResource(R.string.home_draft_body, state.draft.teamA.trim(), state.draft.teamB.trim())
                                else stringResource(R.string.home_draft_body_unnamed),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(stringResource(R.string.home_draft_action), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    attention.forEach { match -> MatchCard(match, state.upload, onClick = { onOpenMatch(match) }) }
                }
                Notice(stringResource(R.string.home_capability), Tone.NEUTRAL)
                if (settled.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionTitle(stringResource(R.string.home_section_recent), Modifier.weight(1f))
                        TextButton(onClick = onSeeAll) { Text(stringResource(R.string.home_see_all), style = MaterialTheme.typography.labelLarge) }
                    }
                    settled.take(3).forEach { match -> MatchCard(match, state.upload, onClick = { onOpenMatch(match) }) }
                }
                GuideCard(onOpenGuide)
            }
        }
    }
}

@Composable
fun MatchesScreen(state: AppUiState, onNewAnalysis: () -> Unit, onOpenMatch: (MatchRecord) -> Unit) {
    val empty = state.matches.isEmpty()
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = { if (!empty) NewAnalysisFab(onNewAnalysis) },
    ) { insets ->
        Column(
            Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ScreenTitle(stringResource(R.string.nav_matches))
            if (empty) {
                VsCard {
                    Text(stringResource(R.string.matches_empty_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.matches_empty_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                PrimaryButton(stringResource(R.string.action_new_analysis), onNewAnalysis, icon = R.drawable.ic_add)
            } else {
                state.matches.forEach { match -> MatchCard(match, state.upload, onClick = { onOpenMatch(match) }) }
            }
        }
    }
}

@Composable
private fun FeatureRow(name: String, works: Boolean) {
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (works) StatusChip(stringResource(R.string.state_working), Tone.POSITIVE) else StatusChip(stringResource(R.string.state_not_yet), Tone.NOT_YET)
    }
}

@Composable
private fun PrivacyLine(text: String, icon: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp).size(20.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun ProfileScreen(
    state: AppUiState,
    versionName: String,
    onServerTarget: (ServerTarget) -> Unit,
    onTestServer: () -> Unit,
    onDeleteRecording: (String) -> Unit,
) {
    var recordingToDelete by rememberSaveable { mutableStateOf<String?>(null) }
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing, containerColor = MaterialTheme.colorScheme.background) { insets ->
        Column(
            Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ScreenTitle(stringResource(R.string.nav_profile))

            SectionTitle(stringResource(R.string.profile_today))
            VsCard {
                FeatureRow(stringResource(R.string.feature_match), true)
                FeatureRow(stringResource(R.string.feature_video), true)
                FeatureRow(stringResource(R.string.feature_upload), true)
                FeatureRow(stringResource(R.string.feature_check), true)
                FeatureRow(stringResource(R.string.feature_analysis), false)
                FeatureRow(stringResource(R.string.feature_review), false)
                FeatureRow(stringResource(R.string.feature_report), false)
            }

            SectionTitle(stringResource(R.string.profile_privacy))
            VsCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PrivacyLine(stringResource(R.string.privacy_server), R.drawable.ic_smartphone)
                    PrivacyLine(stringResource(R.string.privacy_picker), R.drawable.ic_photo_library)
                    PrivacyLine(stringResource(R.string.privacy_training), R.drawable.ic_shield)
                    PrivacyLine(stringResource(R.string.privacy_accounts), R.drawable.ic_lock)
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
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                .selectable(selected = state.serverTarget == target, role = Role.RadioButton, onClick = { onServerTarget(target) }),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            RadioButton(selected = state.serverTarget == target, onClick = null)
                            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                                Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
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
                    Text(stringResource(R.string.recordings_empty), style = MaterialTheme.typography.bodyLarge)
                }
                state.recordings.forEach { recording ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(recording.name, style = MaterialTheme.typography.bodyLarge)
                            Text(formatBytes(recording.sizeBytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { recordingToDelete = recording.path }) {
                            Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.recording_delete_description, recording.name))
                        }
                    }
                }
            }

            Text(stringResource(R.string.profile_version, versionName), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    val pending = state.recordings.firstOrNull { it.path == recordingToDelete }
    if (pending != null) {
        AlertDialog(
            onDismissRequest = { recordingToDelete = null },
            title = { Text(stringResource(R.string.recording_delete_title)) },
            text = { Text(stringResource(R.string.recording_delete_body, pending.name)) },
            confirmButton = { TextButton(onClick = { onDeleteRecording(pending.path); recordingToDelete = null }) { Text(stringResource(R.string.delete_confirm)) } },
            dismissButton = { TextButton(onClick = { recordingToDelete = null }) { Text(stringResource(R.string.action_give_up)) } },
        )
    }
}
