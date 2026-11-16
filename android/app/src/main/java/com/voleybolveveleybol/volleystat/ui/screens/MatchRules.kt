package com.voleybolveveleybol.volleystat.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.voleybolveveleybol.volleystat.CorrectionProblem
import com.voleybolveveleybol.volleystat.R
import com.voleybolveveleybol.volleystat.model.MatchRules
import com.voleybolveveleybol.volleystat.ui.components.ChoiceCard
import com.voleybolveveleybol.volleystat.ui.components.Notice
import com.voleybolveveleybol.volleystat.ui.components.PrimaryButton
import com.voleybolveveleybol.volleystat.ui.components.SectionTitle
import com.voleybolveveleybol.volleystat.ui.components.Tone
import com.voleybolveveleybol.volleystat.ui.components.VsCard
import com.voleybolveveleybol.volleystat.ui.components.announced
import com.voleybolveveleybol.volleystat.ui.theme.Dimens

/** "FIVB kuralı" for the FIVB rule, otherwise the rule is the user's own choice. */
@Composable
fun ruleName(rules: MatchRules): String =
    stringResource(if (rules == MatchRules.FIVB) R.string.rule_name_fivb else R.string.rule_name_custom)

/** The rule in one short line, for a card. */
@Composable
fun ruleDetail(rules: MatchRules): String =
    if (rules.setTarget == rules.decidingSetTarget) stringResource(R.string.rule_detail_same, rules.setsToWin, rules.setTarget)
    else stringResource(R.string.rule_detail, rules.setsToWin, rules.setTarget, rules.decidingSetNumber, rules.decidingSetTarget)

/** The rule as a sentence whose subject is the server: the server counts the sets, the phone does not. */
@Composable
fun ruleCounting(rules: MatchRules): String =
    if (rules.setTarget == rules.decidingSetTarget) stringResource(R.string.rule_counting_same, rules.setTarget)
    else stringResource(R.string.rule_counting, rules.setTarget, rules.decidingSetNumber, rules.decidingSetTarget)

/** The same sentence for a choice the user has not saved: the server counts with it only after Save. */
@Composable
fun ruleCountingIfSaved(rules: MatchRules): String =
    if (rules.setTarget == rules.decidingSetTarget) stringResource(R.string.rule_counting_same_if_saved, rules.setTarget)
    else stringResource(R.string.rule_counting_if_saved, rules.setTarget, rules.decidingSetNumber, rules.decidingSetTarget)

/** The rule of a match on 03 and on the rally list, and on the check screen when it is not the FIVB rule. A tap opens screen 13. */
@Composable
fun MatchRulesCard(rules: MatchRules, onClick: () -> Unit, enabled: Boolean = true) {
    ChoiceCard(R.drawable.ic_sports_volleyball, stringResource(R.string.rule_card_change), onClick, enabled) {
        Text(stringResource(R.string.rule_card_label), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(ruleName(rules), style = MaterialTheme.typography.titleSmall)
        Text(ruleDetail(rules), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * 13: the rule the server counts the sets of a match with. On a new match the choice only goes into
 * the draft. On the rally list the screen sends it, and it stays open until the server answers, so
 * a refusal or a lost connection is seen next to the button that caused it.
 */
@Composable
fun MatchRulesScreen(
    /** The draft's rule on 03; on the rally list, the rule the server named with the list. */
    current: MatchRules,
    onSave: (MatchRules) -> Unit,
    onBack: () -> Unit,
    onList: Boolean = false,
    listConfirmed: Boolean = false,
    sending: Boolean = false,
    problem: CorrectionProblem? = null,
    /** Every choice the user makes, as the whole rule it makes. */
    onSelectionChanged: (MatchRules) -> Unit = {},
    /**
     * Where the selection starts. The rally list route passes the user's last choice: the screen
     * leaves the composition while the list is read again, and the choice must outlive that.
     */
    initial: MatchRules = current,
) {
    var setsToWin by rememberSaveable { mutableIntStateOf(initial.setsToWin) }
    var setTarget by rememberSaveable { mutableIntStateOf(initial.setTarget) }
    var decidingSetTarget by rememberSaveable { mutableIntStateOf(initial.decidingSetTarget) }
    val selection = MatchRules(setsToWin, setTarget, decidingSetTarget)
    // While the rule is on its way the screen keeps the user: leaving would hide how it ended.
    BackHandler(enabled = sending) {}
    FlowScaffold(
        title = stringResource(R.string.rule_title), onBack = onBack, backEnabled = !sending,
        actions = {
            when {
                // While the rule is on its way, that is the one thing the screen has to say.
                sending -> ProgressLine(R.string.rule_sending)
                problem != null -> Notice(
                    when (problem) {
                        CorrectionProblem.UNREACHABLE -> stringResource(R.string.correction_unreachable)
                        // The app has read the list again, so [current] is the rule the server has now. The notice
                        // names the whole of it: the next Save is made on that rule, and the user has not seen it yet.
                        CorrectionProblem.STALE -> stringResource(R.string.rule_stale, ruleDetail(current))
                        // The server accepts every rule the app offers, so the words never blame the rule.
                        else -> stringResource(R.string.rule_refused)
                    },
                    // After STALE the app has read the list again: the user only saves once more.
                    if (problem == CorrectionProblem.STALE) Tone.CAUTION else Tone.NEGATIVE,
                    Modifier.announced(),
                )
                // Until Save the server counts with the rule it has, so a choice not saved yet is said as a condition.
                else -> Notice(if (selection == current) ruleCounting(selection) else ruleCountingIfSaved(selection), Tone.NEUTRAL)
            }
            PrimaryButton(stringResource(R.string.action_save_rule), { onSave(selection) }, enabled = selection != current && !sending)
        },
    ) {
        Text(stringResource(R.string.rule_intro), style = MaterialTheme.typography.bodyMedium)
        if (onList && listConfirmed) Notice(stringResource(R.string.rule_drops_confirmation), Tone.CAUTION)
        RuleGroup(
            stringResource(R.string.rule_sets_question),
            MatchRules.SETS_TO_WIN.map { sets ->
                sets to stringResource(if (sets == MatchRules.FIVB.setsToWin) R.string.rule_sets_option_fivb else R.string.rule_sets_option, sets)
            },
            selected = setsToWin, enabled = !sending,
            // The rule says when a team has won the match; play after that starts a new match (ADR-0118).
            note = stringResource(R.string.rule_match_end),
        ) { setsToWin = it; onSelectionChanged(MatchRules(it, setTarget, decidingSetTarget)) }
        RuleGroup(
            stringResource(R.string.rule_points_question),
            MatchRules.TARGETS.map { points -> points to pointsLabel(points, fivb = points == MatchRules.FIVB.setTarget) },
            selected = setTarget, enabled = !sending,
        ) { setTarget = it; onSelectionChanged(MatchRules(setsToWin, it, decidingSetTarget)) }
        RuleGroup(
            stringResource(R.string.rule_deciding_question, selection.decidingSetNumber),
            MatchRules.TARGETS.map { points -> points to pointsLabel(points, fivb = points == MatchRules.FIVB.decidingSetTarget) },
            selected = decidingSetTarget, enabled = !sending,
        ) { decidingSetTarget = it; onSelectionChanged(MatchRules(setsToWin, setTarget, it)) }
        Text(
            stringResource(if (onList) R.string.rule_keeps_answers else R.string.rule_later),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun pointsLabel(points: Int, fivb: Boolean): String =
    stringResource(if (fivb) R.string.rule_points_option_fivb else R.string.rule_points_option, points)

/** One question of the rule: a heading over a card of radio rows, each row the size of a finger. */
@Composable
private fun RuleGroup(
    question: String,
    options: List<Pair<Int, String>>,
    selected: Int,
    enabled: Boolean,
    note: String? = null,
    onSelect: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(question)
        VsCard(padding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
            Column(Modifier.selectableGroup()) {
                options.forEach { (value, label) ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = Dimens.touchTarget)
                            .selectable(selected = value == selected, enabled = enabled, role = Role.RadioButton, onClick = { onSelect(value) })
                            // Two groups offer "21 sayı". A screen reader names the question with it, so the user knows which one.
                            .semantics { contentDescription = "$question $label" },
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RadioButton(selected = value == selected, onClick = null, enabled = enabled)
                        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 6.dp))
                    }
                }
            }
        }
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
