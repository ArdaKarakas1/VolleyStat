package com.voleybolveveleybol.volleystat.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voleybolveveleybol.volleystat.R
import com.voleybolveveleybol.volleystat.ReportLoad
import com.voleybolveveleybol.volleystat.model.MatchRecord
import com.voleybolveveleybol.volleystat.model.MatchReport
import com.voleybolveveleybol.volleystat.model.Player
import com.voleybolveveleybol.volleystat.model.StatValue
import com.voleybolveveleybol.volleystat.ui.components.DemoBanner
import com.voleybolveveleybol.volleystat.ui.components.DemoTag
import com.voleybolveveleybol.volleystat.ui.components.FactRow
import com.voleybolveveleybol.volleystat.ui.components.ListRow
import com.voleybolveveleybol.volleystat.ui.components.NotYetCard
import com.voleybolveveleybol.volleystat.ui.components.Notice
import com.voleybolveveleybol.volleystat.ui.components.PointTypesDialog
import com.voleybolveveleybol.volleystat.ui.components.PrimaryButton
import com.voleybolveveleybol.volleystat.ui.components.RowDivider
import com.voleybolveveleybol.volleystat.ui.components.SegmentedChoice
import com.voleybolveveleybol.volleystat.ui.components.StatusChip
import com.voleybolveveleybol.volleystat.ui.components.TeamBadge
import com.voleybolveveleybol.volleystat.ui.components.Tone
import com.voleybolveveleybol.volleystat.ui.components.VsCard
import com.voleybolveveleybol.volleystat.ui.components.dashedOutline
import com.voleybolveveleybol.volleystat.ui.theme.Dimens

/**
 * A reported number in one of its three honest forms: measured, a lower bound (marked with a
 * plus), or no measurement at all, which is drawn as a dashed box and read aloud as such.
 * An unknown value is never drawn as 0.
 */
@Composable
fun ValueCell(value: StatValue, modifier: Modifier = Modifier) {
    val missing = stringResource(R.string.value_not_measured)
    if (value.value == null || value.status == StatValue.UNKNOWN) {
        Box(
            modifier.size(width = 36.dp, height = 24.dp).dashedOutline(MaterialTheme.colorScheme.outline, 6.dp).clearAndSetSemantics { contentDescription = missing },
        )
    } else {
        Text(
            if (value.status == StatValue.PARTIAL) "${value.value}+" else value.value.toString(),
            style = MaterialTheme.typography.titleMedium, modifier = modifier,
        )
    }
}

@Composable
private fun TeamColumn(name: String, home: Boolean, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(name, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        TeamBadge(home)
    }
}

/** Home on the left, away on the right, sets won in the middle. Words stand in the middle when there is no score. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScoreHeader(match: MatchRecord, report: MatchReport?, modifier: Modifier = Modifier) {
    val known = report != null && report.setsWonHome != null && report.setsWonAway != null
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TeamColumn(match.homeTeam, home = true, modifier = Modifier.weight(1f))
            if (known) {
                // Sets won under PARTIAL are a lower bound like every other partial number, so they carry the plus too.
                val atLeast = report!!.scoreStatus == "PARTIAL"
                val plus = if (atLeast) "+" else ""
                val spoken = stringResource(R.string.report_score_at_least, report.setsWonHome ?: 0, report.setsWonAway ?: 0)
                Text(
                    "${report.setsWonHome}$plus - ${report.setsWonAway}$plus", style = MaterialTheme.typography.displayLarge, maxLines = 1, softWrap = false,
                    modifier = if (atLeast) Modifier.semantics { contentDescription = spoken } else Modifier,
                )
            } else {
                Text(
                    stringResource(R.string.report_score_unknown), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.dashedOutline(MaterialTheme.colorScheme.outline, 8.dp).padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
            TeamColumn(match.awayTeam, home = false, modifier = Modifier.weight(1f))
        }
        if (report != null && report.sets.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                report.sets.forEach { set ->
                    val open = stringResource(R.string.report_set_open, set.number)
                    Text(
                        "${set.home}-${set.away}", style = MaterialTheme.typography.titleSmall,
                        // A set that is still open is outlined with dashes so it never reads as a final set score.
                        modifier = (if (set.complete) Modifier.background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.small)
                        else Modifier.dashedOutline(MaterialTheme.colorScheme.outline, 8.dp).semantics { contentDescription = "$open: ${set.home}-${set.away}" })
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CompareRow(label: String, home: StatValue, away: StatValue) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(64.dp), contentAlignment = Alignment.CenterStart) { ValueCell(home) }
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
        Box(Modifier.width(64.dp), contentAlignment = Alignment.CenterEnd) { ValueCell(away) }
    }
}

/** [demo] puts the DEMO mark on the card itself, so that a picture of this card alone still says what its numbers are. */
@Composable
private fun SectionHeader(text: String, demo: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primary, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .padding(horizontal = Dimens.cardPadding, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.weight(1f).semantics { heading() })
        if (demo) DemoTag()
    }
}

@Composable
private fun ReportLoading() = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
    Text(stringResource(R.string.report_loading), style = MaterialTheme.typography.bodyMedium)
}

/** 09: the score, how complete the data is, and the ways deeper. Everything comes from the server's report. */
@Composable
fun ReportScreen(
    match: MatchRecord,
    load: ReportLoad,
    report: MatchReport?,
    onOpenReview: () -> Unit,
    onOpenPlayers: () -> Unit,
    onOpenStatus: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onOpenRallies: () -> Unit = {},
) {
    var pointTypesOpen by rememberSaveable { mutableStateOf(false) }
    FlowScaffold(title = stringResource(R.string.report_title), onBack = onBack) {
        when {
            load == ReportLoad.LOADING -> ReportLoading()
            load == ReportLoad.UNREACHABLE -> {
                Notice(stringResource(R.string.report_load_failed), Tone.NEGATIVE)
                PrimaryButton(stringResource(R.string.action_retry), onRetry)
            }
            report == null -> {
                ScoreHeader(match, null)
                NotYetCard(stringResource(R.string.report_unavailable_title), stringResource(R.string.report_unavailable_body))
                PrimaryButton(stringResource(R.string.status_title), onOpenStatus)
            }
            else -> {
                if (report.mock) DemoBanner()
                ScoreHeader(match, report)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                    when (report.verificationStatus) {
                        "VERIFIED" -> StatusChip(stringResource(R.string.report_verification_done), Tone.POSITIVE)
                        "PARTIALLY_VERIFIED" -> StatusChip(stringResource(R.string.report_verification_partial), Tone.CAUTION)
                        else -> StatusChip(stringResource(R.string.report_verification_draft), Tone.NEUTRAL)
                    }
                    if (report.scoreStatus == "PARTIAL") StatusChip(stringResource(R.string.report_score_partial), Tone.CAUTION)
                }
                // One rally without a winner makes every later set boundary a guess, so the server
                // stops the set scores there. The screen says so instead of showing a short score silently.
                if (report.scoreStoppedAtRally != null) {
                    Notice(stringResource(R.string.report_score_stopped, report.scoreStoppedAtRally), Tone.CAUTION)
                }
                // Answering every card does not make the score complete: a rally the analyzer never
                // proposed has no card. The report says so until the user has confirmed the list, and
                // for a demo job always, because invented intervals can not be confirmed.
                when {
                    report.ralliesMayBeMissing -> Notice(stringResource(if (report.mock) R.string.report_may_miss_demo else R.string.report_may_miss), Tone.NEUTRAL)
                    report.listConfirmed -> Notice(stringResource(R.string.report_list_confirmed), Tone.POSITIVE)
                }
                if (report.ralliesOpen > 0) {
                    PrimaryButton(stringResource(R.string.report_open_review, report.ralliesOpen), onOpenReview, icon = R.drawable.ic_fact_check)
                }

                VsCard(padding = PaddingValues(0.dp)) {
                    SectionHeader(stringResource(R.string.report_team_stats), demo = report.mock)
                    Column(Modifier.padding(horizontal = Dimens.cardPadding, vertical = 8.dp)) {
                        CompareRow(stringResource(R.string.report_points), report.home.points, report.away.points)
                        CompareRow(stringResource(R.string.report_attack_points), report.home.attackPoints, report.away.attackPoints)
                        CompareRow(stringResource(R.string.report_block_points), report.home.blockPoints, report.away.blockPoints)
                        CompareRow(stringResource(R.string.report_aces), report.home.aces, report.away.aces)
                        CompareRow(stringResource(R.string.report_opponent_errors), report.home.opponentErrors, report.away.opponentErrors)
                        Text(stringResource(R.string.value_partial_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.value_missing_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                VsCard(padding = PaddingValues(0.dp)) {
                    SectionHeader(stringResource(R.string.report_stats))
                    ListRow(R.drawable.ic_groups, stringResource(R.string.report_player_stats), null, onOpenPlayers)
                    RowDivider()
                    ListRow(R.drawable.ic_smart_display, stringResource(R.string.report_review_video), null, onOpenReview)
                    RowDivider()
                    ListRow(R.drawable.ic_sports_volleyball, stringResource(R.string.rallies_title), null, onOpenRallies)
                    RowDivider()
                    ListRow(R.drawable.ic_info, stringResource(R.string.definitions_title), null, { pointTypesOpen = true })
                }

                VsCard(padding = PaddingValues(0.dp)) {
                    SectionHeader(stringResource(R.string.report_coverage), demo = report.mock)
                    Column(Modifier.padding(horizontal = Dimens.cardPadding, vertical = 8.dp)) {
                        report.coverage.forEach { coverage ->
                            FactRow(
                                stringResource(when (coverage.metricId) {
                                    "TERMINAL_ACTION" -> R.string.report_coverage_action
                                    "SCORER" -> R.string.report_coverage_scorer
                                    else -> R.string.report_coverage_winner
                                }),
                                stringResource(R.string.report_coverage_value, coverage.verifiedN, coverage.eligibleN),
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        // The four rows below the gap add up to the first one. What the analyzer proposed is another
                        // number once the user has merged or added rallies.
                        FactRow(stringResource(R.string.report_rallies_listed), report.ralliesTotal.toString())
                        FactRow(stringResource(R.string.report_rallies_total), report.ralliesProposed.toString())
                        FactRow(stringResource(R.string.report_rallies_user), report.ralliesUserCreated.toString())
                        Spacer(Modifier.height(4.dp))
                        FactRow(stringResource(R.string.report_rallies_verified), report.ralliesVerified.toString())
                        FactRow(stringResource(R.string.report_rallies_open), report.ralliesOpen.toString())
                        FactRow(stringResource(R.string.report_rallies_unresolved), report.ralliesUnresolved.toString())
                        FactRow(stringResource(R.string.report_rallies_rejected), report.ralliesRejected.toString())
                        Spacer(Modifier.height(4.dp))
                        // Once the user has moved, added, split or merged, the intervals are not the analyzer's alone.
                        Text(
                            stringResource(if (report.corrections > 0) R.string.report_source_corrected else R.string.report_source, report.analyzerId, report.analyzerKind),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(stringResource(R.string.report_rules), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    // Outside the states above: a report that reloads underneath does not close the definitions.
    if (pointTypesOpen) PointTypesDialog { pointTypesOpen = false }
}

/** A count of confirmed decisions. While decisions are missing it is a lower bound and carries a plus. */
@Composable
private fun NumberCell(value: Int, modifier: Modifier = Modifier, strong: Boolean = false, lowerBound: Boolean = false) {
    Text(
        if (lowerBound) "$value+" else value.toString(),
        style = if (strong) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = modifier,
    )
}

/** 10: points per player, by jersey number. Only what the user attributed during review appears here. */
@Composable
fun PlayersScreen(
    match: MatchRecord,
    report: MatchReport?,
    homeRoster: List<Player>,
    awayRoster: List<Player>,
    load: ReportLoad = ReportLoad.READY,
    onRetry: () -> Unit = {},
    onBack: () -> Unit,
) {
    // Without a report the table has nothing to show, and an empty table would read as "no points".
    if (report == null) {
        FlowScaffold(title = stringResource(R.string.players_title), onBack = onBack) {
            when (load) {
                ReportLoad.UNREACHABLE -> {
                    Notice(stringResource(R.string.report_load_failed), Tone.NEGATIVE)
                    PrimaryButton(stringResource(R.string.action_retry), onRetry)
                }
                ReportLoad.NONE -> NotYetCard(stringResource(R.string.report_unavailable_title), stringResource(R.string.report_unavailable_body))
                else -> ReportLoading()
            }
        }
        return
    }
    var side by rememberSaveable { mutableIntStateOf(0) }
    val home = side == 0
    val roster = if (home) homeRoster else awayRoster
    val lines = report?.players.orEmpty().filter { it.home == home }.sortedBy { it.jersey }
    val unattributed = if (home) report?.unattributedHome else report?.unattributedAway
    // A player's count can still grow while rallies are undecided or points have no player yet,
    // so until then every number in the table is a lower bound and must not read as a final total.
    val lowerBound = report.ralliesMayBeMissing || report.verificationStatus != "VERIFIED" || (unattributed ?: 0) > 0
    val missing = stringResource(R.string.value_not_measured)
    FlowScaffold(title = stringResource(R.string.players_title), onBack = onBack) {
        if (report?.mock == true) DemoBanner(compact = true)
        SegmentedChoice(listOf(match.homeTeam, match.awayTeam), side, onSelect = { side = it })
        VsCard(padding = PaddingValues(vertical = 8.dp)) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val number = Modifier.width(36.dp)
                val cell = Modifier.width(44.dp)
                // One line needs room for a readable name next to five numbers. A narrow phone or
                // enlarged text leaves none, so there the name gets its own line above the numbers.
                val stacked = maxWidth - 24.dp - 36.dp - 44.dp * 5 < 96.dp * LocalDensity.current.fontScale
                val columns = listOf(R.string.players_col_attack, R.string.players_col_block, R.string.players_col_serve, R.string.players_col_dig, R.string.players_col_points)
                Column {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (!stacked) {
                            Text(stringResource(R.string.players_col_number), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = number)
                            Text(stringResource(R.string.players_col_player), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        }
                        columns.forEach {
                            Text(
                                stringResource(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center, maxLines = 1, softWrap = false, modifier = if (stacked) Modifier.weight(1f) else cell,
                            )
                        }
                    }
                    RowDivider()
                    if (lines.isEmpty()) {
                        Text(stringResource(R.string.players_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(Dimens.cardPadding))
                    }
                    lines.forEach { line ->
                        val name = roster.firstOrNull { it.jersey == line.jersey }?.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.review_jersey, line.jersey)
                        val numbers: @Composable RowScope.() -> Unit = {
                            val slot = if (stacked) Modifier.weight(1f) else cell
                            NumberCell(line.attackPoints, slot, lowerBound = lowerBound)
                            NumberCell(line.blockPoints, slot, lowerBound = lowerBound)
                            NumberCell(line.aces, slot, lowerBound = lowerBound)
                            // Defence is not measured at all: a dashed box, never a zero.
                            Box(slot, contentAlignment = Alignment.Center) {
                                Box(Modifier.size(width = 24.dp, height = 18.dp).dashedOutline(MaterialTheme.colorScheme.outline, 5.dp).clearAndSetSemantics { contentDescription = missing })
                            }
                            NumberCell(line.points, slot, strong = true, lowerBound = lowerBound)
                        }
                        if (stacked) {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(line.jersey.toString(), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                                    Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, content = numbers)
                            }
                        } else {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).heightIn(min = Dimens.touchTarget), verticalAlignment = Alignment.CenterVertically) {
                                Text(line.jersey.toString(), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = number)
                                Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                numbers()
                            }
                        }
                    }
                    if (unattributed != null && unattributed > 0) {
                        RowDivider()
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).heightIn(min = Dimens.touchTarget), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.players_unattributed), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            NumberCell(unattributed, cell, strong = true)
                        }
                    }
                }
            }
        }
        Text(stringResource(R.string.players_points_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (lowerBound) Text(stringResource(R.string.value_partial_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.players_dig_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
