package com.voleybolveveleybol.volleystat.ui.screens

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.voleybolveveleybol.volleystat.AnalysisUi
import com.voleybolveveleybol.volleystat.JobLoad
import com.voleybolveveleybol.volleystat.CorrectionProblem
import com.voleybolveveleybol.volleystat.R
import com.voleybolveveleybol.volleystat.RalliesLoad
import com.voleybolveveleybol.volleystat.model.MatchRecord
import com.voleybolveveleybol.volleystat.model.MatchRules
import com.voleybolveveleybol.volleystat.model.Rally
import com.voleybolveveleybol.volleystat.model.RallyList
import com.voleybolveveleybol.volleystat.model.ReviewCard
import com.voleybolveveleybol.volleystat.model.MatchPart
import com.voleybolveveleybol.volleystat.model.gaps
import com.voleybolveveleybol.volleystat.ui.components.DemoBanner
import com.voleybolveveleybol.volleystat.ui.components.Notice
import com.voleybolveveleybol.volleystat.ui.components.PrimaryButton
import com.voleybolveveleybol.volleystat.ui.components.SecondaryButton
import com.voleybolveveleybol.volleystat.ui.components.Tone
import com.voleybolveveleybol.volleystat.ui.components.announced
import com.voleybolveveleybol.volleystat.ui.components.dashedOutline
import com.voleybolveveleybol.volleystat.ui.formatClock
import com.voleybolveveleybol.volleystat.ui.formatDuration
import com.voleybolveveleybol.volleystat.ui.theme.Dimens
import com.voleybolveveleybol.volleystat.ui.theme.LocalVsColors
import kotlinx.coroutines.flow.drop

/**
 * One line of the rally list: the start of a part of the video (only when it holds more than one),
 * the start of a set, the place where the count stops, a rally, or the time between two rallies.
 */
private sealed interface Line {
    // A start carries the id of the rally that opens it, so its key stays unique even if a count named a set twice.
    data class PartStart(val part: MatchPart, val opener: String) : Line
    data class SetStart(val match: Int, val number: Int, val opener: String) : Line
    data object NotCounted : Line
    data class OfRally(val rally: Rally) : Line
    data class Gap(val startMs: Long, val endMs: Long) : Line
}

/** A rally whose winner the user has not named: its card is open, or the answer was "not sure". */
private val Rally.waits get() = status == Rally.PROPOSED || status == Rally.UNRESOLVED

/** The list in match order. A set starts where the server's own count says it does; the phone counts nothing. */
private fun lines(list: RallyList): List<Line> = buildList {
    val pauses = gaps(list)
    var next = 0
    var part = 0
    var set = 0
    var counted = true
    list.rallies.forEach { rally ->
        while (next < pauses.size && pauses[next].windowEndMs <= rally.startMs) pauses[next++].let { add(Line.Gap(it.windowStartMs, it.windowEndMs)) }
        val after = rally.scoreAfter
        if (after != null && (after.match != part || after.set != set)) {
            if (after.match != part) {
                // A new part of the video: its sets count from 1 again (ADR-0118).
                part = after.match
                if (list.matches.size > 1) list.matches.firstOrNull { it.number == part }?.let { add(Line.PartStart(it, rally.id)) }
            }
            set = after.set
            add(Line.SetStart(part, set, rally.id))
        } else if (counted && rally.waits) {
            // The server's count ends at the first rally without a winner. No rally from here on has a score.
            counted = false
            add(Line.NotCounted)
        }
        add(Line.OfRally(rally))
    }
    while (next < pauses.size) pauses[next++].let { add(Line.Gap(it.windowStartMs, it.windowEndMs)) }
}

/**
 * What a correction problem says, on every screen that can run into one. The rally list sends a
 * confirmation and no correction, so it words the two general refusals for that step.
 */
@Composable
fun correctionProblemText(analysis: AnalysisUi, confirmation: Boolean = false): String? = when (analysis.correctionProblem) {
    null -> null
    CorrectionProblem.STALE -> stringResource(if (confirmation) R.string.rallies_confirm_stale else R.string.correction_stale)
    CorrectionProblem.OVERLAP -> analysis.overlapsRally?.let { stringResource(R.string.correction_overlap, it) } ?: stringResource(R.string.correction_overlap_unknown)
    CorrectionProblem.OUTSIDE -> stringResource(R.string.correction_outside)
    CorrectionProblem.NOT_READY -> stringResource(R.string.correction_not_ready)
    CorrectionProblem.DEMO -> stringResource(R.string.correction_demo)
    CorrectionProblem.UNREACHABLE -> stringResource(R.string.correction_unreachable)
    CorrectionProblem.REFUSED -> stringResource(if (confirmation) R.string.rallies_confirm_refused else R.string.correction_refused)
}

@Composable
private fun CorrectionProblemNotice(analysis: AnalysisUi, confirmation: Boolean) {
    // After STALE the app has already read the list again, so nothing is wrong any more: the user repeats the step.
    correctionProblemText(analysis, confirmation)?.let { Notice(it, if (analysis.correctionProblem == CorrectionProblem.STALE) Tone.CAUTION else Tone.NEGATIVE, Modifier.announced()) }
}

/**
 * What became of the last tap on a screen that sends corrections: the correction on its way, or
 * why the server did not apply it. Each line is reported to a screen reader when it appears.
 */
@Composable
fun CorrectionStatus(analysis: AnalysisUi, @StringRes sending: Int, confirmation: Boolean = false) {
    if (analysis.correcting) ProgressLine(sending)
    CorrectionProblemNotice(analysis, confirmation)
}

/** The words for a match that has no job to work on. */
@StringRes
fun jobProblemText(problem: JobLoad): Int = when (problem) {
    JobLoad.UNREACHABLE -> R.string.analysis_unreachable
    JobLoad.NO_ANALYZER -> R.string.analysis_none_title
    JobLoad.VIDEO_MISSING -> R.string.analysis_video_missing_body
    else -> R.string.review_no_job
}

/**
 * Says why there is no rally list to show. Asking again is offered only where it can change the
 * answer: after a lost connection. A server that no longer holds the video would say so again.
 */
@Composable
fun JobProblemNotice(problem: JobLoad, onRetry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Notice(stringResource(jobProblemText(problem)), if (problem == JobLoad.UNREACHABLE) Tone.NEGATIVE else Tone.CAUTION)
        if (problem == JobLoad.UNREACHABLE) SecondaryButton(stringResource(R.string.action_retry), onRetry)
    }
}

/** A spinner and what is on its way. The screen that sent it keeps the user until the answer is there. */
@Composable
fun ProgressLine(@StringRes text: Int) {
    Row(Modifier.announced(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(stringResource(text), style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * The rally list of a match: where the user finds a rally to correct and a gap that hides a
 * missed one, and where the list is declared complete after the set scores were compared with
 * the match.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RalliesScreen(
    match: MatchRecord,
    analysis: AnalysisUi,
    onOpenRally: (Rally) -> Unit,
    /** Opens the editor on the pause that starts at this moment of the video. */
    onOpenGap: (startMs: Long) -> Unit,
    onConfirm: () -> Unit,
    /** Opens the question of a rally that still waits for its winner. */
    onOpenReview: (Rally) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    /** Opens screen 13 on the rule the server counted this list with. */
    onChangeRules: () -> Unit = {},
) {
    val list = analysis.rallies
    var checking by rememberSaveable { mutableStateOf(false) }
    // An answer about one version of the list says nothing about the next one. The version is kept with the
    // answer: a screen that comes back from the back stack gets its saved state back whatever the list is now.
    var noAt by rememberSaveable { mutableStateOf<Int?>(null) }
    val scoresDiffer = list != null && noAt == list.version
    // The confirmation is sent from here. Until its answer is there the user stays, so a refusal is not lost.
    BackHandler(enabled = analysis.correcting) {}
    // What becomes of the confirmation opens above the button that sent it. On a small screen with large text
    // that button stands at the bottom edge, and the line would push the button, and most of itself, under
    // that edge. So the list brings both onto the screen when the line appears or changes. A line the screen
    // opened with is not news, and a user who scrolled the head away meanwhile is left where they are.
    val statusPlace = remember { BringIntoViewRequester() }
    val shown by rememberUpdatedState(analysis)
    LaunchedEffect(Unit) {
        snapshotFlow { shown.correcting to shown.correctionProblem }.drop(1).collect { (correcting, problem) ->
            if (correcting || problem != null) {
                // The line is measured in the frame after the one that composed it.
                withFrameNanos { }
                statusPlace.bringIntoView()
            }
        }
    }
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.rallies_title), style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !analysis.correcting) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { insets ->
        LazyColumn(
            Modifier.fillMaxSize().padding(insets),
            contentPadding = PaddingValues(horizontal = Dimens.screenPadding, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (analysis.job?.mock == true) item(key = "demo") { DemoBanner(compact = true) }
            val jobProblem = analysis.jobProblem
            if (jobProblem != null) {
                item(key = "no-job") { JobProblemNotice(jobProblem, onRetry) }
            } else if (analysis.ralliesLoad == RalliesLoad.UNREACHABLE) {
                // A list that is still on screen below this is the one from before the failed read.
                item(key = "unreachable") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Notice(stringResource(R.string.rallies_load_failed), Tone.NEGATIVE)
                        SecondaryButton(stringResource(R.string.action_retry), onRetry)
                    }
                }
            }
            if (analysis.ralliesLoad == RalliesLoad.OUTDATED) {
                // The server answered, and not with a list this app can read. Asking again would get the same answer.
                item(key = "outdated") { Notice(stringResource(R.string.rallies_outdated_server), Tone.NEGATIVE) }
            }
            if (list == null) {
                if (jobProblem == null && analysis.ralliesLoad != RalliesLoad.UNREACHABLE && analysis.ralliesLoad != RalliesLoad.OUTDATED) {
                    item(key = "loading") {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text(stringResource(R.string.rallies_loading), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            } else {
                item(key = "state") { ListState(list, analysis, scoresDiffer, statusPlace, onCheck = { checking = true }, onOpenReview = onOpenReview, onChangeRules = onChangeRules) }
                items(lines(list), key = { line ->
                    when (line) {
                        is Line.PartStart -> "part-${line.opener}"
                        is Line.SetStart -> "set-${line.opener}"
                        Line.NotCounted -> "not-counted"
                        is Line.OfRally -> line.rally.id
                        is Line.Gap -> "gap-${line.startMs}"
                    }
                }) { line ->
                    when (line) {
                        is Line.PartStart -> PartLine(partLabel(line.part))
                        is Line.SetStart -> SectionLine(stringResource(R.string.rallies_set_header, line.number))
                        Line.NotCounted -> SectionLine(stringResource(R.string.rallies_no_set))
                        is Line.OfRally -> RallyRow(line.rally, match, enabled = !analysis.correcting) { onOpenRally(line.rally) }
                        is Line.Gap -> GapRow(line, enabled = !analysis.correcting) { onOpenGap(line.startMs) }
                    }
                }
            }
        }
    }
    if (checking && list != null) {
        ScoreCheckDialog(
            list.matches, list.rules,
            onYes = { checking = false; noAt = null; onConfirm() },
            onNo = { checking = false; noAt = list.version },
            onUnknown = { checking = false },
        )
    }
}

@Composable
private fun PartLine(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp).semantics { heading() })
}

@Composable
private fun SectionLine(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp).semantics { heading() })
}

/** The head of the list: how many rallies it holds, what the user can do here, and how far the list is from confirmed. */
@Composable
private fun ListState(
    list: RallyList,
    analysis: AnalysisUi,
    scoresDiffer: Boolean,
    /** Where the line about the confirmation and its button stand, for the screen to bring onto the screen. */
    statusPlace: BringIntoViewRequester,
    onCheck: () -> Unit,
    onOpenReview: (Rally) -> Unit,
    onChangeRules: () -> Unit,
) {
    val waiting = list.rallies.filter { it.waits }
    // An interval the user called "not a rally" stays on the list, and it is not counted as a rally anywhere.
    val rejected = list.rallies.count { it.status == Rally.REJECTED }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (rejected == 0) stringResource(R.string.rallies_count, list.rallies.size)
            else stringResource(R.string.rallies_count_rejected, list.rallies.size - rejected, rejected),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(stringResource(R.string.rallies_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // The scores next to the rallies are counts of this list. Until the user has confirmed the list they
        // are lower bounds like every number of the report, and the list says so where it shows them. The
        // list of a demo is never confirmed, so it gets no promise about a confirmation: its notice speaks for it.
        if (!list.confirmed && list.matches.isNotEmpty() && analysis.job?.mock != true) {
            Text(stringResource(R.string.rallies_scores_lower), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // The rule the server counted these scores with: the list's own, not the one the match was created with.
        MatchRulesCard(list.rules, onChangeRules, enabled = !analysis.correcting)
        // What became of the confirmation stands next to the button that sent it.
        Column(Modifier.bringIntoViewRequester(statusPlace), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CorrectionStatus(analysis, R.string.rallies_confirm_sending, confirmation = true)
            when {
                list.confirmed -> Notice(stringResource(R.string.rallies_confirmed), Tone.POSITIVE)
                analysis.job?.mock == true -> Notice(stringResource(R.string.rallies_demo), Tone.NEUTRAL)
                // A rally without a winner leaves the score open, so there is no score to compare yet.
                waiting.isNotEmpty() -> {
                    Notice(stringResource(R.string.rallies_not_ready, waiting.size), Tone.CAUTION)
                    SecondaryButton(stringResource(R.string.action_go_review), onClick = { onOpenReview(waiting.first()) }, icon = R.drawable.ic_fact_check)
                }
                else -> {
                    // The hint sends the user to the rule, to a button and to an answer. It quotes each by its own label.
                    if (scoresDiffer) {
                        Notice(
                            stringResource(
                                R.string.rallies_no_hint, stringResource(R.string.rule_card_change),
                                stringResource(R.string.action_choose_winner), stringResource(R.string.review_not_a_rally),
                            ),
                            Tone.NEUTRAL,
                        )
                    }
                    // Without one counted rally the server has no set score to show, and it would refuse the confirmation.
                    PrimaryButton(stringResource(R.string.action_check_score), onCheck, enabled = !analysis.correcting && list.matches.isNotEmpty(), icon = R.drawable.ic_task_alt)
                }
            }
        }
    }
}

@Composable
private fun RallyRow(rally: Rally, match: MatchRecord, enabled: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val moment = "${formatClock(rally.startMs)} · ${formatDuration(rally.endMs - rally.startMs)}"
    val outcome = when {
        rally.status == Rally.REJECTED -> stringResource(R.string.rally_rejected)
        rally.status == Rally.UNRESOLVED -> stringResource(R.string.rally_unsure)
        rally.winner == ReviewCard.HOME -> match.homeTeam
        rally.winner == ReviewCard.AWAY -> match.awayTeam
        else -> stringResource(R.string.rally_open)
    }
    val result = if (rally.fromAnalyzer) outcome else "$outcome · ${stringResource(R.string.rally_user_made)}"
    val score = rally.scoreAfter?.let { stringResource(R.string.rally_score_after, it.home, it.away) }
    // One sentence for a screen reader, with everything the row shows.
    val spoken = stringResource(R.string.rallies_row_description, rally.sequence, moment, listOfNotNull(result, score).joinToString(", "))
    Surface(
        onClick = onClick, enabled = enabled, shape = MaterialTheme.shapes.medium, color = scheme.surface,
        border = BorderStroke(1.dp, LocalVsColors.current.divider),
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = spoken },
    ) {
        Row(
            Modifier.heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(rally.sequence.toString(), style = MaterialTheme.typography.titleSmall, color = scheme.primary, modifier = Modifier.widthIn(min = 28.dp))
            Column(Modifier.weight(1f)) {
                Text(moment, style = MaterialTheme.typography.bodyMedium)
                Text(result, style = MaterialTheme.typography.bodySmall, color = if (rally.status == Rally.VERIFIED) scheme.onSurface else scheme.onSurfaceVariant)
            }
            if (score != null) Text(score, style = MaterialTheme.typography.titleSmall)
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = scheme.onSurfaceVariant)
        }
    }
}

/** The time between two rallies. A rally the analyzer missed lies in one of these. */
@Composable
private fun GapRow(gap: Line.Gap, enabled: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(onClick = onClick, enabled = enabled, shape = MaterialTheme.shapes.small, color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.dashedOutline(scheme.outline, 8.dp).heightIn(min = Dimens.touchTarget).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.rallies_gap, formatDuration(gap.endMs - gap.startMs)), style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f),
            )
            Icon(painterResource(R.drawable.ic_add), contentDescription = null, tint = scheme.primary, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.rallies_gap_add), style = MaterialTheme.typography.labelLarge, color = scheme.primary)
        }
    }
}

/**
 * The one question that lets the user call the list complete. The scores are the server's count
 * of the rallies on the list; only the user knows what was played. "Yes" is the only answer that
 * confirms. "No" and "I do not know" leave every number a lower bound.
 */
@Composable
fun ScoreCheckDialog(parts: List<MatchPart>, rules: MatchRules, onYes: () -> Unit, onNo: () -> Unit, onUnknown: () -> Unit) {
    AlertDialog(
        onDismissRequest = onUnknown,
        title = { Text(stringResource(R.string.score_check_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.score_check_intro), style = MaterialTheme.typography.bodyMedium)
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small).padding(12.dp)) {
                    parts.forEach { part ->
                        // With several parts the user checks which match each set belongs to as well.
                        if (parts.size > 1) {
                            Text(
                                "${partLabel(part)}: ${part.setsWonHome} - ${part.setsWonAway}", style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = if (part.number > 1) 8.dp else 0.dp),
                            )
                        }
                        part.sets.forEach { set ->
                            Text(
                                stringResource(if (set.complete) R.string.score_check_set else R.string.score_check_set_open, set.number, set.home, set.away),
                                style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
                // The rule of the list these scores were counted with, by name: a rule of another match would make the "yes" mean nothing.
                Text(stringResource(R.string.score_check_rule, ruleCounting(rules)), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.score_check_question), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        },
        confirmButton = { TextButton(onClick = onYes) { Text(stringResource(R.string.score_check_yes)) } },
        dismissButton = {
            Row {
                TextButton(onClick = onUnknown) { Text(stringResource(R.string.score_check_unknown)) }
                TextButton(onClick = onNo) { Text(stringResource(R.string.score_check_no)) }
            }
        },
    )
}
