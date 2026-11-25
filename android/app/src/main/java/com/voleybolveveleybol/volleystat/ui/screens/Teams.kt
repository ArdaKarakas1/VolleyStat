package com.voleybolveveleybol.volleystat.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voleybolveveleybol.volleystat.R
import com.voleybolveveleybol.volleystat.TeamsUi
import com.voleybolveveleybol.volleystat.data.SeatsInvalidReason
import com.voleybolveveleybol.volleystat.data.TeamLimitScope
import com.voleybolveveleybol.volleystat.data.TeamProblem
import com.voleybolveveleybol.volleystat.model.Schedule
import com.voleybolveveleybol.volleystat.model.Seat
import com.voleybolveveleybol.volleystat.model.TeamDraft
import com.voleybolveveleybol.volleystat.model.TeamInfo
import com.voleybolveveleybol.volleystat.model.TeamMember
import com.voleybolveveleybol.volleystat.model.TeamRole
import com.voleybolveveleybol.volleystat.model.TeamRules
import com.voleybolveveleybol.volleystat.ui.components.EmptyState
import com.voleybolveveleybol.volleystat.ui.components.Notice
import com.voleybolveveleybol.volleystat.ui.components.PrimaryButton
import com.voleybolveveleybol.volleystat.ui.components.RowDivider
import com.voleybolveveleybol.volleystat.ui.components.ScreenTitle
import com.voleybolveveleybol.volleystat.ui.components.SecondaryButton
import com.voleybolveveleybol.volleystat.ui.components.SectionTitle
import com.voleybolveveleybol.volleystat.ui.components.SegmentedChoice
import com.voleybolveveleybol.volleystat.ui.components.Tone
import com.voleybolveveleybol.volleystat.ui.components.VsCard
import com.voleybolveveleybol.volleystat.ui.components.announced
import com.voleybolveveleybol.volleystat.ui.theme.Dimens
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

// ----- Texts (design 2.12 F and 10.7; plan P6) -----

private val turkish = Locale.forLanguageTag("tr-TR")

/**
 * The text of a team call's problem. Every number in it came from the server's answer; an answer without its number
 * gets the general refusal, never a number of the app's. Any answer without a text of its own reads as the account
 * calls read it: a 4xx changed nothing, a 5xx leaves the outcome open, an unreadable answer says so.
 */
fun teamProblemText(problem: TeamProblem): AccountText = when (problem) {
    TeamProblem.NoAccess -> AccountText(R.string.team_no_access)
    TeamProblem.CaptainOnly -> AccountText(R.string.team_captain_only)
    TeamProblem.Gone -> AccountText(R.string.team_gone)
    TeamProblem.NameInvalid -> AccountText(R.string.team_name_invalid, listOf(TeamRules.NAME_MIN, TeamRules.NAME_MAX))
    // Another account's team has the id this phone chose. The view model takes a new id for the next try.
    TeamProblem.IdTaken -> AccountText(R.string.account_refused)
    is TeamProblem.Limit -> when {
        problem.limit == null -> AccountText(R.string.account_refused)
        problem.scope == TeamLimitScope.CAPTAIN -> AccountText(R.string.team_limit_captain, listOf(problem.limit))
        problem.scope == TeamLimitScope.MEMBER -> AccountText(R.string.team_limit_member, listOf(problem.limit))
        else -> AccountText(R.string.account_refused)
    }
    is TeamProblem.TooMany -> problem.retryAfterSeconds?.let { AccountText(R.string.team_wait_create, listOf(WaitArgument(it))) }
        ?: AccountText(R.string.account_wait_no_time)
    is TeamProblem.SeatsInvalid -> AccountText(
        when (problem.reason) {
            SeatsInvalidReason.SUBSTITUTES_RANGE -> R.string.seats_invalid
            SeatsInvalidReason.CAPTAIN_SEAT -> R.string.seats_invalid_captain
            SeatsInvalidReason.UNKNOWN -> R.string.account_refused
        }
    )
    is TeamProblem.SeatsBelowHolders -> AccountText(
        if (problem.seat == Seat.STARTER) R.string.seats_below_starter else R.string.seats_below_substitute, listOf(problem.holders, problem.seats),
    )
    // The member the call named left the team meanwhile; the list that the action reads next shows who is there.
    is TeamProblem.NotAMember -> AccountText(R.string.account_refused)
    TeamProblem.ServerOutdated -> AccountText(R.string.teams_server_older)
    TeamProblem.Unreachable -> AccountText(R.string.net_no_answer)
    is TeamProblem.Failed -> AccountText(
        when {
            problem.code == "UNEXPECTED_ANSWER" || problem.status !in 400..599 || (problem.status in 400..499 && problem.code.startsWith("HTTP_")) ->
                R.string.account_unexpected_change_signed_in
            // SERVER_BUSY comes before the server changes anything.
            problem.status in 500..599 && problem.code != "SERVER_BUSY" ->
                if (problem.code.startsWith("HTTP_") || problem.status == 511) R.string.account_outcome_unknown_on_the_way else R.string.account_outcome_unknown
            else -> R.string.account_refused
        }
    )
}

/**
 * The hint below a refusal of the substitute type (S8 of the design's v5 checkers): only when the refused call moved a
 * starter to a substitute seat. While the substitutes the change needs fit the bound of 6, a higher count or a swap
 * helps; above it only a swap does.
 */
fun seatsHint(problem: TeamProblem): Int? {
    if (problem !is TeamProblem.SeatsBelowHolders || problem.seat != Seat.SUBSTITUTE || !problem.movedStarterDown) return null
    return if (problem.holders <= TeamRules.SUBSTITUTES_MAX) R.string.seats_raise_or_swap_hint else R.string.seats_swap_hint
}

/** The test tag of the As or Yedek choice of one member on As ve Yedek Yerleri. */
fun seatToggleTag(userId: String, seat: Seat) = "seat-$userId-${seat.name}"

@Composable
private fun teamProblemMessage(problem: TeamProblem): String = teamProblemText(problem).resolve(LocalContext.current)

private fun dayName(weekday: Int, style: TextStyle): String? = runCatching { DayOfWeek.of(weekday).getDisplayName(style, turkish) }.getOrNull()

@Composable
private fun scheduleText(schedule: Schedule): String? =
    dayName(schedule.weekday, TextStyle.FULL)?.let { stringResource(R.string.team_schedule, it, schedule.time) }

@Composable
private fun seatName(seat: Seat) = stringResource(if (seat == Seat.STARTER) R.string.seat_starter else R.string.seat_substitute)

// ----- Shared parts -----

/** The team's initials on a tinted circle. P7 puts the logo in its place. The name beside it says the same, so it is silent. */
@Composable
private fun TeamInitials(name: String, size: Int = 48) {
    val initials = name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) }.uppercase(turkish)
    Box(
        Modifier.size(size.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape).clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Text(initials, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

/** A plain label on a soft background: a role or a seat type. It is no status, so it carries no icon. */
@Composable
private fun LabelChip(text: String, strong: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    Text(
        text, style = MaterialTheme.typography.labelMedium, color = if (strong) scheme.onPrimaryContainer else scheme.onSurface,
        modifier = Modifier.background(if (strong) scheme.primaryContainer else scheme.surfaceVariant, MaterialTheme.shapes.small).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun roleName(role: TeamRole) = stringResource(if (role == TeamRole.CAPTAIN) R.string.team_role_captain else R.string.team_role_member)

/** A line that says the app is waiting for the server. */
@Composable
private fun Waiting(text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 24.dp).announced(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A read that failed: its reason, and Yeniden Dene unless asking again can not change the answer. */
@Composable
private fun ReadProblem(problem: TeamProblem, onRetry: () -> Unit) {
    Notice(teamProblemMessage(problem), Tone.NEGATIVE, Modifier.announced())
    val final = problem == TeamProblem.ServerOutdated || problem == TeamProblem.Gone || problem == TeamProblem.NoAccess
    if (!final) SecondaryButton(stringResource(R.string.action_retry), onRetry)
}

// ----- The Teams tab (design 10.1) -----

/** Takımlar: the account's teams with the role, the seat line and the new-member badge; Takım Kur. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TeamsScreen(ui: TeamsUi, onCreate: () -> Unit, onOpen: (String) -> Unit, onRetry: () -> Unit) {
    val teams = ui.teams
    TabScaffold {
        ScreenTitle(stringResource(R.string.nav_teams))
        when {
            teams == null && ui.loadProblem != null && !ui.loading -> ReadProblem(ui.loadProblem, onRetry)
            // Unread is no empty list: the app waits for the server's answer (plan D2).
            teams == null -> Waiting(stringResource(R.string.teams_loading))
            teams.isEmpty() -> {
                ui.loadProblem?.let { Notice(teamProblemMessage(it), Tone.NEGATIVE, Modifier.announced()) }
                val label = stringResource(R.string.action_create_team)
                EmptyState(R.drawable.ic_groups, stringResource(R.string.teams_empty_title), stringResource(R.string.teams_empty_body, label)) {
                    PrimaryButton(label, onCreate, icon = R.drawable.ic_add)
                }
            }
            else -> {
                // A list read before stays while a new read failed; the reason stands above it.
                ui.loadProblem?.let { Notice(teamProblemMessage(it), Tone.NEGATIVE, Modifier.announced()) }
                PrimaryButton(stringResource(R.string.action_create_team), onCreate, icon = R.drawable.ic_add)
                teams.forEach { team -> TeamRow(team) { onOpen(team.id) } }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TeamRow(team: TeamInfo, onClick: () -> Unit) {
    VsCard(onClick = onClick, padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            TeamInitials(team.name)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(team.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("#${team.tag}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    stringResource(R.string.team_seat_line, team.seats.starter.taken, team.seats.starter.seats, team.seats.substitute.taken, team.seats.substitute.seats),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { LabelChip(roleName(team.myRole), strong = team.captain) }
            }
            NewJoinsBadge(team.newJoins ?: 0)
        }
    }
}

/** The number of new members on a badge, read out as words. Nothing while there is none. */
@Composable
fun NewJoinsBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    val spoken = stringResource(R.string.team_new_joins_a11y, count)
    Badge(modifier.clearAndSetSemantics { contentDescription = spoken }) { Text("$count") }
}

// ----- Takım Kur (teams/new) -----

/**
 * Takım Kur: the name, the substitute seats (2 filled in, 0 to 6) beside the fixed 6 starter seats, the creator's seat
 * (as by default; Yedek off while the count is 0), the usual match day and time, and the creator's jersey, all but the
 * name optional. [onEdit] tells the screen's owner that the user changed something, which clears a refusal.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TeamCreateScreen(busy: Boolean, problem: TeamProblem?, onCreate: (TeamDraft) -> Unit, onEdit: () -> Unit, onBack: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var substitutes by rememberSaveable { mutableStateOf(TeamRules.DEFAULT_SUBSTITUTES) }
    var mySeat by rememberSaveable { mutableStateOf(Seat.STARTER) }
    var day by rememberSaveable { mutableStateOf(0) }
    var time by rememberSaveable { mutableStateOf("") }
    var jersey by rememberSaveable { mutableStateOf("") }
    val timeComplete = time.length == 5
    val timeValid = TeamRules.TIME.matches(time)
    val ready = name.isNotBlank() && (day == 0 || timeValid)
    val label = stringResource(R.string.action_create_team)
    FlowScaffold(
        title = label, onBack = onBack, backEnabled = !busy,
        actions = {
            if (busy) Text(stringResource(R.string.account_waiting), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.announced())
            problem?.let { Notice(teamProblemMessage(it), Tone.NEGATIVE, Modifier.announced()) }
            PrimaryButton(
                label, enabled = ready && !busy, icon = R.drawable.ic_add,
                onClick = {
                    val schedule = if (day == 0) null else Schedule(day, time)
                    onCreate(TeamDraft(name.trim(), substitutes, mySeat, schedule, jersey.toIntOrNull()))
                },
            )
        },
    ) {
        OutlinedTextField(
            value = name, onValueChange = { name = it.take(TeamRules.NAME_MAX * 2); onEdit() },
            label = { Text(stringResource(R.string.field_team_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(stringResource(R.string.field_substitute_seats))
            Text(stringResource(R.string.team_new_seats_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SegmentedChoice(
                (0..TeamRules.SUBSTITUTES_MAX).map { it.toString() }, substitutes,
                onSelect = { count ->
                    substitutes = count
                    // Without a substitute seat the creator takes a starter seat (design 2.12 F).
                    if (count == 0) mySeat = Seat.STARTER
                    onEdit()
                },
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(stringResource(R.string.team_new_my_seat))
            SegmentedChoice(
                listOf(stringResource(R.string.seat_starter), stringResource(R.string.seat_substitute)), if (mySeat == Seat.STARTER) 0 else 1,
                onSelect = { mySeat = if (it == 0) Seat.STARTER else Seat.SUBSTITUTE; onEdit() },
                disabled = if (substitutes == 0) setOf(1) else emptySet(),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(stringResource(R.string.field_schedule_day))
            FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = day == 0, onClick = { day = 0; onEdit() }, label = { Text(stringResource(R.string.team_schedule_none)) })
                (1..7).forEach { weekday ->
                    FilterChip(selected = day == weekday, onClick = { day = weekday; onEdit() }, label = { Text(dayName(weekday, TextStyle.SHORT).orEmpty()) })
                }
            }
            OutlinedTextField(
                value = time, enabled = day != 0,
                onValueChange = { typed ->
                    // Four digits are the hour and the minutes; the colon goes in by itself.
                    val digits = typed.filter(Char::isDigit).take(4)
                    time = if (digits.length > 2) digits.take(2) + ":" + digits.drop(2) else digits
                    onEdit()
                },
                label = { Text(stringResource(R.string.field_schedule_time)) }, placeholder = { Text("20:00") }, singleLine = true,
                isError = day != 0 && timeComplete && !timeValid,
                supportingText = if (day != 0 && timeComplete && !timeValid) ({ Text(stringResource(R.string.team_time_invalid)) }) else null,
                modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
            )
        }
        OutlinedTextField(
            value = jersey, onValueChange = { typed -> jersey = typed.filter(Char::isDigit).take(2).trimStart('0'); onEdit() },
            label = { Text(stringResource(R.string.field_my_jersey)) }, placeholder = { Text(stringResource(R.string.match_name_hint)) }, singleLine = true,
            modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        )
    }
}

// ----- The team screen (team/{id}) -----

/**
 * One team: its initials, name, tag and schedule; the account's role and seat; the members under Aslar and Yedekler.
 * The captain also sees the new members since the last look ([recentJoins]: the route passes the count of the screen's
 * first answer, which the app marks seen), the hint about a free starter seat, and As ve Yedek Yerleri. [team] is null
 * while the app reads it or after the read failed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TeamScreen(
    team: TeamInfo?, loading: Boolean, problem: TeamProblem?, onSeats: () -> Unit, onRetry: () -> Unit, onBack: () -> Unit,
    recentJoins: Int? = team?.newJoins,
) {
    FlowScaffold(title = team?.name ?: stringResource(R.string.nav_teams), onBack = onBack) {
        if (team == null) {
            if (problem != null && !loading) ReadProblem(problem, onRetry) else Waiting(stringResource(R.string.team_loading))
            return@FlowScaffold
        }
        problem?.let { Notice(teamProblemMessage(it), Tone.NEGATIVE, Modifier.announced()) }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            TeamInitials(team.name, size = 64)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("#${team.tag}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                team.schedule?.let { schedule -> scheduleText(schedule)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) } }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LabelChip(roleName(team.myRole), strong = team.captain)
                    LabelChip(seatName(team.mySeat))
                }
            }
        }
        if (team.captain) {
            if ((recentJoins ?: 0) > 0) Notice(stringResource(R.string.team_recent_joins, recentJoins!!), Tone.ACTIVE)
            val starters = team.seats.starter
            val substitutes = team.seats.substitute
            if (starters.left > 0 && substitutes.taken > 0 && team.waiting == 0) {
                Notice(stringResource(R.string.team_starter_free_hint, starters.left, substitutes.taken), Tone.NEUTRAL)
            }
        }
        MemberGroup(stringResource(R.string.team_starters), team.members.filter { it.seat == Seat.STARTER })
        MemberGroup(stringResource(R.string.team_substitutes), team.members.filter { it.seat == Seat.SUBSTITUTE })
        if (team.captain) SecondaryButton(stringResource(R.string.seats_title), onSeats)
    }
}

/** The members of one seat type under its header, in the order they joined. A type nobody holds shows nothing. */
@Composable
private fun MemberGroup(title: String, members: List<TeamMember>) {
    if (members.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(title)
        VsCard(padding = PaddingValues(vertical = 4.dp)) {
            members.forEachIndexed { index, member ->
                if (index > 0) RowDivider()
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = Dimens.cardPadding, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    JerseyChip(member.jersey)
                    Text(member.displayName, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    if (member.role == TeamRole.CAPTAIN) LabelChip(stringResource(R.string.team_role_captain), strong = true)
                }
            }
        }
    }
}

// ----- As ve Yedek Yerleri (team/{id}/seats) -----

/**
 * The captain sets the substitute count (0 to 6) beside the fixed 6 starter seats and moves members between the two
 * types. Kaydet sends the final count and only the members whose type changed; the server checks the whole state. A
 * refusal keeps the input as the user left it.
 */
@Composable
fun TeamSeatsScreen(
    team: TeamInfo, busy: Boolean, problem: TeamProblem?, onSave: (substituteSeats: Int, moves: Map<String, Seat>) -> Unit, onEdit: () -> Unit,
    onBack: () -> Unit,
) {
    var substitutes by rememberSaveable(team.id) { mutableStateOf(team.seats.substitute.seats) }
    var chosen by rememberSaveable(team.id) { mutableStateOf(HashMap(team.members.associate { it.userId to it.seat })) }
    val save = stringResource(R.string.action_save)
    FlowScaffold(
        title = stringResource(R.string.seats_title), onBack = onBack, backEnabled = !busy,
        actions = {
            if (busy) Text(stringResource(R.string.account_waiting), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.announced())
            problem?.let { refused ->
                Notice(teamProblemMessage(refused), Tone.NEGATIVE, Modifier.announced())
                seatsHint(refused)?.let { Notice(stringResource(it), Tone.NEUTRAL) }
            }
            PrimaryButton(
                save, enabled = !busy,
                onClick = {
                    val moves = team.members.mapNotNull { member -> chosen[member.userId]?.takeIf { it != member.seat }?.let { member.userId to it } }.toMap()
                    onSave(substitutes, moves)
                },
            )
        },
    ) {
        Text(stringResource(R.string.team_seats_body), style = MaterialTheme.typography.bodyMedium)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(stringResource(R.string.field_substitute_seats))
            SegmentedChoice((0..TeamRules.SUBSTITUTES_MAX).map { it.toString() }, substitutes, onSelect = { substitutes = it; onEdit() })
        }
        Text(stringResource(R.string.lineup_body, save), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        VsCard(padding = PaddingValues(vertical = 4.dp)) {
            team.members.forEachIndexed { index, member ->
                if (index > 0) RowDivider()
                Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.cardPadding, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        JerseyChip(member.jersey)
                        Text(member.displayName, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        if (member.role == TeamRole.CAPTAIN) LabelChip(stringResource(R.string.team_role_captain), strong = true)
                    }
                    SeatToggle(member.userId, chosen[member.userId] ?: member.seat) { seat ->
                        chosen = HashMap(chosen).apply { put(member.userId, seat) }
                        onEdit()
                    }
                }
            }
        }
    }
}

/** As or Yedek for one member: two radio options side by side, each with its own test tag. */
@Composable
private fun SeatToggle(userId: String, seat: Seat, onSelect: (Seat) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).border(1.dp, scheme.outline, MaterialTheme.shapes.medium).selectableGroup()) {
        Seat.entries.forEach { option ->
            val selected = option == seat
            Box(
                Modifier.weight(1f).heightIn(min = Dimens.touchTarget).background(if (selected) scheme.primary else scheme.surface)
                    .testTag(seatToggleTag(userId, option))
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(option) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(seatName(option), style = MaterialTheme.typography.labelLarge, color = if (selected) scheme.onPrimary else scheme.onSurface)
            }
        }
    }
}
