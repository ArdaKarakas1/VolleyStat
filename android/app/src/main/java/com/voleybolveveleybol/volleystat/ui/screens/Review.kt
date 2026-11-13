package com.voleybolveveleybol.volleystat.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.voleybolveveleybol.volleystat.AnalysisUi
import com.voleybolveveleybol.volleystat.R
import com.voleybolveveleybol.volleystat.ReviewProblem
import com.voleybolveveleybol.volleystat.model.MatchRecord
import com.voleybolveveleybol.volleystat.model.Player
import com.voleybolveveleybol.volleystat.model.ReviewCard
import com.voleybolveveleybol.volleystat.model.Team
import com.voleybolveveleybol.volleystat.ui.components.DemoBanner
import com.voleybolveveleybol.volleystat.ui.components.EmptyState
import com.voleybolveveleybol.volleystat.ui.components.Notice
import com.voleybolveveleybol.volleystat.ui.components.PrimaryButton
import com.voleybolveveleybol.volleystat.ui.components.SecondaryButton
import com.voleybolveveleybol.volleystat.ui.components.TeamBadge
import com.voleybolveveleybol.volleystat.ui.components.Tone
import com.voleybolveveleybol.volleystat.ui.components.VsCard
import com.voleybolveveleybol.volleystat.ui.formatClock
import com.voleybolveveleybol.volleystat.ui.theme.Dimens
import com.voleybolveveleybol.volleystat.ui.theme.LocalVsColors

/** One answer row. The user's saved answer is marked with words and a tick, not by colour alone. */
@Composable
private fun AnswerRow(
    text: String, chosen: Boolean, enabled: Boolean, onClick: () -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(), leading: (@Composable () -> Unit)? = null,
    /** Half-width answers have no room for the tick; the words under the answer still mark it. */
    tick: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.selectable(selected = chosen, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = if (chosen) scheme.primaryContainer else scheme.surface,
        border = BorderStroke(if (chosen) 2.dp else 1.dp, if (chosen) scheme.primary else LocalVsColors.current.divider),
    ) {
        Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (leading != null) leading()
            Column(Modifier.weight(1f)) {
                Text(text, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (chosen) Text(stringResource(R.string.review_your_answer), style = MaterialTheme.typography.bodySmall, color = scheme.onPrimaryContainer)
            }
            if (chosen && tick) Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = scheme.primary)
        }
    }
}

@Composable
private fun optionLabel(option: String, match: MatchRecord): String = when (option) {
    ReviewCard.HOME -> match.homeTeam
    ReviewCard.AWAY -> match.awayTeam
    ReviewCard.NOT_A_RALLY -> stringResource(R.string.review_not_a_rally)
    ReviewCard.UNSURE -> stringResource(R.string.review_unsure)
    "ATTACK" -> stringResource(R.string.review_action_attack)
    "BLOCK" -> stringResource(R.string.review_action_block)
    "ACE" -> stringResource(R.string.review_action_ace)
    "OPPONENT_ERROR" -> stringResource(R.string.review_action_error)
    ReviewCard.UNKNOWN_PLAYER -> stringResource(R.string.review_unknown_player)
    else -> option
}

/**
 * 08: a short clip and one question. One tap answers, saves and moves on. The server's
 * suggestion, when there is one, is shown as a hint and is never selected for the user.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReviewScreen(
    match: MatchRecord,
    analysis: AnalysisUi,
    /** Roster of the side that won the rally of the current card, for the "who scored" question. */
    scorerRoster: List<Player>,
    onAnswer: (String) -> Unit,
    onMove: (Int) -> Unit,
    onDetail: (Boolean) -> Unit,
    onOpenReport: () -> Unit,
    onBack: () -> Unit,
    clip: @Composable (ReviewCard) -> Unit,
) {
    val deck = analysis.deck
    val card = analysis.card
    FlowScaffold(
        title = stringResource(R.string.review_title), onBack = onBack,
        actions = if (deck == null) null else ({
            if (card == null) {
                PrimaryButton(stringResource(R.string.action_open_report), onOpenReport, icon = R.drawable.ic_bar_chart)
                if (deck.cards.isNotEmpty()) SecondaryButton(stringResource(R.string.review_previous), onClick = { onMove(-1) })
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SecondaryButton(stringResource(R.string.review_previous), onClick = { onMove(-1) }, enabled = analysis.cursor > 0 && !analysis.saving, modifier = Modifier.weight(1f))
                    // Moving on without an answer leaves the card open; nothing is recorded.
                    PrimaryButton(stringResource(if (card.open) R.string.review_skip else R.string.review_next), onClick = { onMove(1) }, enabled = !analysis.saving, modifier = Modifier.weight(1f))
                }
            }
        }),
    ) {
        if (analysis.job?.mock == true) DemoBanner(compact = true)
        when {
            deck == null && analysis.reviewProblem == ReviewProblem.LOAD_FAILED -> Notice(stringResource(R.string.review_save_failed), Tone.NEGATIVE)
            deck == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.review_loading), style = MaterialTheme.typography.bodyMedium)
            }
            card == null -> EmptyState(R.drawable.ic_task_alt, stringResource(R.string.review_empty_title), stringResource(R.string.review_empty_body))
            else -> {
                val answered = deck.cards.count { !it.open }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LinearProgressIndicator(progress = { if (deck.cards.isEmpty()) 0f else answered / deck.cards.size.toFloat() }, modifier = Modifier.weight(1f), drawStopIndicator = {})
                    Text(stringResource(R.string.review_position, analysis.cursor + 1, deck.cards.size), style = MaterialTheme.typography.labelMedium)
                }
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.scrim)) { clip(card) }
                Text(stringResource(R.string.review_rally, card.rallySequence, formatClock(card.clipStartMs)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                when (analysis.reviewProblem) {
                    ReviewProblem.STALE -> Notice(stringResource(R.string.review_stale), Tone.CAUTION)
                    ReviewProblem.SAVE_FAILED -> Notice(stringResource(R.string.review_save_failed), Tone.NEGATIVE)
                    else -> Unit
                }

                Text(
                    stringResource(when (card.questionType) {
                        ReviewCard.TERMINAL_ACTION -> R.string.review_question_action
                        ReviewCard.SCORER -> R.string.review_question_scorer
                        else -> R.string.review_question_winner
                    }),
                    style = MaterialTheme.typography.titleMedium,
                )
                // Only a real suggestion gets a line. It is a hint; the app never selects it for the user.
                if (card.questionType == ReviewCard.RALLY_WINNER && card.suggestedValue != null) {
                    Text(stringResource(R.string.review_hint, optionLabel(card.suggestedValue, match)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                val enabled = !analysis.saving
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (card.questionType == ReviewCard.SCORER) {
                        if (scorerRoster.none { it.jersey != null }) Notice(stringResource(R.string.review_no_roster), Tone.NEUTRAL)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            scorerRoster.filter { it.jersey != null }.sortedBy { it.jersey }.forEach { player ->
                                val value = ReviewCard.jersey(player.jersey!!)
                                Box(Modifier.widthIn(min = 148.dp)) {
                                    AnswerRow(
                                        if (player.name.isBlank()) stringResource(R.string.review_jersey, player.jersey) else stringResource(R.string.review_jersey_named, player.jersey, player.name),
                                        chosen = card.answer == value, enabled = enabled, onClick = { onAnswer(value) },
                                    )
                                }
                            }
                        }
                        AnswerRow(stringResource(R.string.review_unknown_player), card.answer == ReviewCard.UNKNOWN_PLAYER, enabled, onClick = { onAnswer(ReviewCard.UNKNOWN_PLAYER) })
                    } else if (card.questionType == ReviewCard.RALLY_WINNER) {
                        // Two answers per row keep all four next to the clip on a small phone. Home
                        // is on the left as on every other screen, and the order never changes.
                        val teams = listOf(ReviewCard.HOME, ReviewCard.AWAY).filter { it in card.options }
                        (listOf(teams) + (card.options - teams.toSet()).chunked(2)).filter { it.isNotEmpty() }.forEach { pair ->
                            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                pair.forEach { option ->
                                    AnswerRow(
                                        optionLabel(option, match), chosen = card.answer == option, enabled = enabled, onClick = { onAnswer(option) },
                                        modifier = Modifier.weight(1f).fillMaxHeight(), tick = false,
                                        leading = when (option) {
                                            ReviewCard.HOME -> ({ TeamBadge(home = true, size = 32.dp) })
                                            ReviewCard.AWAY -> ({ TeamBadge(home = false, size = 32.dp) })
                                            else -> null
                                        },
                                    )
                                }
                            }
                        }
                    } else {
                        // The order of the answers never changes from card to card.
                        card.options.forEach { option ->
                            AnswerRow(
                                optionLabel(option, match), chosen = card.answer == option, enabled = enabled, onClick = { onAnswer(option) },
                                leading = when (option) {
                                    ReviewCard.HOME -> ({ TeamBadge(home = true, size = 32.dp) })
                                    ReviewCard.AWAY -> ({ TeamBadge(home = false, size = 32.dp) })
                                    else -> null
                                },
                            )
                        }
                    }
                }

                VsCard {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = Dimens.touchTarget).toggleable(value = analysis.detail, role = Role.Switch, onValueChange = onDetail),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.review_details_switch), style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(R.string.review_details_helper), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = analysis.detail, onCheckedChange = null)
                    }
                }
            }
        }
    }
}

/** Stand-in for the clip where no player can run (previews and screenshot tests). */
@Composable
fun ClipPlaceholder() {
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f), contentAlignment = Alignment.Center) {
        Box(Modifier.size(56.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_play_arrow), contentDescription = stringResource(R.string.review_clip_description), tint = MaterialTheme.colorScheme.primary)
        }
    }
}
