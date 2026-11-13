package com.voleybolveveleybol.volleystat.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.voleybolveveleybol.volleystat.R
import com.voleybolveveleybol.volleystat.model.Player
import com.voleybolveveleybol.volleystat.model.Team
import com.voleybolveveleybol.volleystat.ui.components.Notice
import com.voleybolveveleybol.volleystat.ui.components.PrimaryButton
import com.voleybolveveleybol.volleystat.ui.components.RowDivider
import com.voleybolveveleybol.volleystat.ui.components.SectionTitle
import com.voleybolveveleybol.volleystat.ui.components.Tone
import com.voleybolveveleybol.volleystat.ui.components.VsCard

/** The jersey number is the player's identity on screen; the name is an optional label. */
@Composable
fun JerseyChip(jersey: Int?, modifier: Modifier = Modifier) {
    Box(modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.medium), contentAlignment = Alignment.Center) {
        Text(jersey?.toString() ?: "?", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
fun playerLabel(player: Player): String = player.name.ifBlank { stringResource(R.string.roster_unnamed) }

/** A saved team and its roster. Names stay on this phone. */
@Composable
fun TeamRosterScreen(
    team: Team,
    onAddPlayer: (name: String, jersey: Int?) -> Boolean,
    onRemovePlayer: (Player) -> Unit,
    onDeleteTeam: () -> Unit,
    onBack: () -> Unit,
) {
    var number by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var failed by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    FlowScaffold(
        title = team.name, onBack = onBack,
        topActions = {
            IconButton(onClick = { confirmDelete = true }) { Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.action_delete)) }
        },
    ) {
        SectionTitle(stringResource(R.string.roster_title))
        VsCard {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = number, onValueChange = { text -> number = text.filter(Char::isDigit).take(2); failed = false },
                    label = { Text(stringResource(R.string.roster_number)) }, singleLine = true, isError = failed,
                    modifier = Modifier.width(112.dp), shape = MaterialTheme.shapes.medium,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(60); failed = false },
                    label = { Text(stringResource(R.string.roster_name)) }, singleLine = true,
                    modifier = Modifier.weight(1f), shape = MaterialTheme.shapes.medium,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                )
            }
            if (failed) {
                Spacer(Modifier.height(8.dp))
                Notice(stringResource(R.string.roster_add_error), Tone.NEGATIVE)
            }
            Spacer(Modifier.height(12.dp))
            PrimaryButton(
                stringResource(R.string.action_add), icon = R.drawable.ic_person_add, enabled = number.isNotBlank() || name.isNotBlank(),
                onClick = {
                    if (onAddPlayer(name, number.toIntOrNull())) { number = ""; name = "" } else failed = true
                },
            )
        }
        if (team.players.isEmpty()) {
            Text(stringResource(R.string.roster_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            VsCard {
                // Sorted by number, players without a number last: a table that always reads the same way.
                team.players.sortedBy { it.jersey ?: Int.MAX_VALUE }.forEachIndexed { index, player ->
                    if (index > 0) RowDivider()
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        JerseyChip(player.jersey)
                        Column(Modifier.weight(1f)) {
                            Text(playerLabel(player), style = MaterialTheme.typography.titleSmall)
                            if (player.jersey == null) Text(stringResource(R.string.roster_no_number), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { onRemovePlayer(player) }) {
                            Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.roster_remove_description, playerLabel(player)))
                        }
                    }
                }
            }
        }
        Notice(stringResource(R.string.roster_privacy), Tone.NEUTRAL)
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.team_delete_title)) },
            text = { Text(stringResource(R.string.team_delete_body, team.name)) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDeleteTeam() }) { Text(stringResource(R.string.action_delete)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_give_up)) } },
        )
    }
}
