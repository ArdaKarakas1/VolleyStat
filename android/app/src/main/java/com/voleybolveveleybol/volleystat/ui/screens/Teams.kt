package com.voleybolveveleybol.volleystat.ui.screens

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
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
import com.voleybolveveleybol.volleystat.BlockedRead
import com.voleybolveveleybol.volleystat.TeamsUi
import com.voleybolveveleybol.volleystat.data.SeatsInvalidReason
import com.voleybolveveleybol.volleystat.data.TeamLimitScope
import com.voleybolveveleybol.volleystat.data.TeamProblem
import com.voleybolveveleybol.volleystat.model.BlockedMember
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
        // Kaptanlığı Devret: the member's limit, named by the display name the team showed (P7).
        problem.scope == TeamLimitScope.CAPTAIN && problem.memberName != null -> AccountText(R.string.transfer_captain_limit, listOf(problem.memberName, problem.limit))
        // A transfer's refusal whose member the app can not name names no one.
        problem.userId != null -> AccountText(R.string.account_refused)
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
    is TeamProblem.JerseyTaken -> problem.jersey?.let { AccountText(R.string.jersey_taken, listOf(it)) } ?: AccountText(R.string.account_refused)
    TeamProblem.MustTransfer -> AccountText(R.string.team_must_transfer)
    is TeamProblem.LogoInvalid -> AccountText(R.string.logo_invalid)
    TeamProblem.LogoUnreadable -> AccountText(R.string.logo_unreadable)
    TeamProblem.LogoTooLarge -> AccountText(R.string.logo_too_large)
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

/** The team's logo when the app holds it, else its initials. The name beside it says the same, so it is silent. */
@Composable
private fun TeamMark(name: String, logo: ImageBitmap?, size: Int = 48) {
    if (logo == null) return TeamInitials(name, size)
    Image(
        logo, contentDescription = null, contentScale = ContentScale.Crop,
        modifier = Modifier.size(size.dp).clip(MaterialTheme.shapes.medium).clearAndSetSemantics {},
    )
}

/** A decoded logo of the view model as Compose draws it. */
@Composable
private fun rememberLogo(bitmap: android.graphics.Bitmap?): ImageBitmap? = remember(bitmap) { bitmap?.asImageBitmap() }

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
                teams.forEach { team -> TeamRow(team, rememberLogo(ui.logos[team.id]?.image)) { onOpen(team.id) } }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TeamRow(team: TeamInfo, logo: ImageBitmap?, onClick: () -> Unit) {
    VsCard(onClick = onClick, padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            TeamMark(team.name, logo)
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
@Composable
fun TeamCreateScreen(
    busy: Boolean, problem: TeamProblem?, onCreate: (TeamDraft) -> Unit, onEdit: () -> Unit, onBack: () -> Unit,
    logo: ImageBitmap? = null, onPickLogo: () -> Unit = {}, onRemoveLogo: () -> Unit = {},
) {
    var name by rememberSaveable { mutableStateOf("") }
    var substitutes by rememberSaveable { mutableStateOf(TeamRules.DEFAULT_SUBSTITUTES) }
    var mySeat by rememberSaveable { mutableStateOf(Seat.STARTER) }
    var day by rememberSaveable { mutableStateOf(0) }
    var time by rememberSaveable { mutableStateOf("") }
    var jersey by rememberSaveable { mutableStateOf("") }
    // A day needs a valid time: the server refuses any other schedule (422), so nothing goes before it holds (M7).
    val ready = name.isNotBlank() && scheduleReady(day, time)
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
        LogoSection(name, logo, enabled = !busy, removable = logo != null, onPick = onPickLogo, onRemove = onRemoveLogo)
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
        ScheduleFields(day, time, onDay = { day = it; onEdit() }, onTime = { time = it; onEdit() })
        OutlinedTextField(
            value = jersey, onValueChange = { typed -> jersey = typedJersey(typed); onEdit() },
            label = { Text(stringResource(R.string.field_my_jersey)) }, placeholder = { Text(stringResource(R.string.match_name_hint)) }, singleLine = true,
            modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        )
    }
}

/** A jersey as the user typed it: at most two digits, without a leading zero (1 to 99, or empty). */
private fun typedJersey(typed: String) = typed.filter(Char::isDigit).take(2).trimStart('0')

/** The time as the user typed it: four digits are the hour and the minutes, and the colon goes in by itself. */
private fun typedTime(typed: String): String {
    val digits = typed.filter(Char::isDigit).take(4)
    return if (digits.length > 2) digits.take(2) + ":" + digits.drop(2) else digits
}

/** Yok, or a day with a time the server takes (00:00 to 23:59). */
private fun scheduleReady(day: Int, time: String) = day == 0 || TeamRules.TIME.matches(time)

/** The usual match day (Yok or a weekday) and its time, as Takım Kur and the settings ask for them. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScheduleFields(day: Int, time: String, onDay: (Int) -> Unit, onTime: (String) -> Unit) {
    val timeComplete = time.length == 5
    val timeValid = TeamRules.TIME.matches(time)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.field_schedule_day))
        FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = day == 0, onClick = { onDay(0) }, label = { Text(stringResource(R.string.team_schedule_none)) })
            (1..7).forEach { weekday ->
                FilterChip(selected = day == weekday, onClick = { onDay(weekday) }, label = { Text(dayName(weekday, TextStyle.SHORT).orEmpty()) })
            }
        }
        OutlinedTextField(
            value = time, enabled = day != 0, onValueChange = { onTime(typedTime(it)) },
            label = { Text(stringResource(R.string.field_schedule_time)) }, placeholder = { Text("20:00") }, singleLine = true,
            isError = day != 0 && timeComplete && !timeValid,
            supportingText = if (day != 0 && timeComplete && !timeValid) ({ Text(stringResource(R.string.team_time_invalid)) }) else null,
            modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
        )
    }
}

/** The logo with Logo Seç and, while there is one, Logoyu Kaldır. The app picks the picture with the photo picker. */
@Composable
private fun LogoSection(name: String, logo: ImageBitmap?, enabled: Boolean, removable: Boolean, onPick: () -> Unit, onRemove: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.field_logo))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            TeamMark(name, logo, size = 72)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(stringResource(R.string.action_pick_logo), onPick, enabled = enabled, icon = R.drawable.ic_photo_library)
                if (removable) SecondaryButton(stringResource(R.string.action_remove_logo), onRemove, enabled = enabled, icon = R.drawable.ic_delete)
            }
        }
    }
}

// ----- The team screen (team/{id}) -----

/**
 * The team screen's actions of plan P7. [onSettings], [onBlocked] and [onJersey] open their screens. [onRemove] and
 * [onTransfer] get a callback that closes their dialog once the server took the change; [onLeave] and [onDelete] end on
 * another screen. [onDismiss] runs when a dialog closes without a change, so the refusal it showed goes with it.
 */
class TeamActions(
    val onSettings: () -> Unit = {},
    val onBlocked: () -> Unit = {},
    val onJersey: () -> Unit = {},
    val onRemove: (userId: String, done: () -> Unit) -> Unit = { _, _ -> },
    val onTransfer: (userId: String, done: () -> Unit) -> Unit = { _, _ -> },
    val onLeave: () -> Unit = {},
    val onDelete: () -> Unit = {},
    val onDismiss: () -> Unit = {},
)

/** The test tag of the menu button on a member's row (captain). */
fun memberMenuTag(userId: String) = "member-menu-$userId"

/**
 * One team: its logo or initials, name, tag and schedule; the account's role and seat; the members under Aslar and
 * Yedekler. The captain also sees the new members since the last look ([recentJoins]: the route passes the count of the
 * screen's first answer, which the app marks seen), the hint about a free starter seat, a menu on every other member's
 * row (Kaptanlığı Devret, Takımdan Çıkar), and As ve Yedek Yerleri, Takım Ayarları, Çıkarılanlar and Takımı Sil. Every
 * member sets their own jersey (Forma Numaran); a member who is not the captain can leave (Takımdan Ayrıl). A captain
 * never sees Takımdan Ayrıl: a sole captain deletes the team, and a captain with members hands the captaincy on first
 * (plan U8). [team] is null while the app reads it or after the read failed. [problem] is the screen's read;
 * [actionProblem] the refusal of the last action, which a dialog shows while it is open.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TeamScreen(
    team: TeamInfo?, loading: Boolean, problem: TeamProblem?, onSeats: () -> Unit, onRetry: () -> Unit, onBack: () -> Unit,
    recentJoins: Int? = team?.newJoins, myUserId: String? = null, logo: ImageBitmap? = null, busy: Boolean = false,
    actionProblem: TeamProblem? = null, actions: TeamActions = TeamActions(),
    /** The dialog the screen opens with: only previews and screenshots name one. */
    initialDialog: String? = null,
) {
    // The open dialog: leave, delete, remove:<userId> or transfer:<userId>. It survives a rotation.
    var dialog by rememberSaveable { mutableStateOf(initialDialog) }
    fun close() {
        dialog = null
        actions.onDismiss()
    }
    FlowScaffold(title = team?.name ?: stringResource(R.string.nav_teams), onBack = onBack, backEnabled = !busy) {
        if (team == null) {
            if (problem != null && !loading) ReadProblem(problem, onRetry) else Waiting(stringResource(R.string.team_loading))
            return@FlowScaffold
        }
        problem?.let { Notice(teamProblemMessage(it), Tone.NEGATIVE, Modifier.announced()) }
        if (dialog == null) {
            if (busy) Text(stringResource(R.string.account_waiting), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.announced())
            actionProblem?.let { Notice(teamProblemMessage(it), Tone.NEGATIVE, Modifier.announced()) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            TeamMark(team.name, logo, size = 64)
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
        // The captain manages every member but themselves.
        val menuFor: (TeamMember) -> Boolean = { team.captain && myUserId != null && it.userId != myUserId }
        val onMenu: (String, TeamMember) -> Unit = { kind, member -> dialog = "$kind:${member.userId}" }
        MemberGroup(stringResource(R.string.team_starters), team.members.filter { it.seat == Seat.STARTER }, menuFor, onMenu, !busy)
        MemberGroup(stringResource(R.string.team_substitutes), team.members.filter { it.seat == Seat.SUBSTITUTE }, menuFor, onMenu, !busy)
        SecondaryButton(stringResource(R.string.jersey_title), actions.onJersey, enabled = !busy)
        if (team.captain) {
            SecondaryButton(stringResource(R.string.seats_title), onSeats, enabled = !busy)
            SecondaryButton(stringResource(R.string.action_team_settings), actions.onSettings, enabled = !busy)
            SecondaryButton(stringResource(R.string.blocked_title), actions.onBlocked, enabled = !busy)
            DangerButton(stringResource(R.string.action_delete_team), { dialog = "delete" }, enabled = !busy)
        } else {
            SecondaryButton(stringResource(R.string.action_leave_team), { dialog = "leave" }, enabled = !busy)
        }
    }
    if (team == null) return
    val open = dialog ?: return
    val member = team.members.firstOrNull { it.userId == open.substringAfter(':') }
    // A question stays open only while the screen offers the button that asked it (P7-C3): the captain's questions close
    // once the account is no captain any more, and Takımdan Ayrıl's once it is the captain. The refusal that came with the
    // change stays on the screen.
    val offered = if (open == "leave") !team.captain else team.captain
    when {
        !offered -> LaunchedEffect(open) { dialog = null }
        open == "leave" -> ConfirmDialog(
            stringResource(R.string.team_leave_title), stringResource(R.string.team_leave_body), stringResource(R.string.action_leave_team),
            busy, actionProblem, onConfirm = actions.onLeave, onDismiss = ::close,
        )
        open == "delete" -> ConfirmDialog(
            stringResource(R.string.team_delete_title), stringResource(R.string.team_delete_body), stringResource(R.string.action_delete_team),
            busy, actionProblem, onConfirm = actions.onDelete, onDismiss = ::close,
        )
        // The member left the team meanwhile: nothing to ask about.
        member == null -> LaunchedEffect(open) { close() }
        open.startsWith("remove:") -> ConfirmDialog(
            stringResource(R.string.member_remove_title, member.displayName), stringResource(R.string.member_remove_body_no_invite),
            stringResource(R.string.action_remove_member), busy, actionProblem, onConfirm = { actions.onRemove(member.userId) { dialog = null } }, onDismiss = ::close,
        )
        open.startsWith("transfer:") -> ConfirmDialog(
            stringResource(R.string.transfer_title), stringResource(R.string.transfer_body, member.displayName), stringResource(R.string.action_transfer_captain),
            busy, actionProblem, onConfirm = { actions.onTransfer(member.userId) { dialog = null } }, onDismiss = ::close,
        )
    }
}

/** The members of one seat type under its header, in the order they joined. A type nobody holds shows nothing. */
@Composable
private fun MemberGroup(title: String, members: List<TeamMember>, menuFor: (TeamMember) -> Boolean, onMenu: (String, TeamMember) -> Unit, enabled: Boolean) {
    if (members.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(title)
        VsCard(padding = PaddingValues(vertical = 4.dp)) {
            members.forEachIndexed { index, member ->
                if (index > 0) RowDivider()
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = Dimens.cardPadding, end = if (menuFor(member)) 4.dp else Dimens.cardPadding, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    JerseyChip(member.jersey)
                    Text(member.displayName, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    if (member.role == TeamRole.CAPTAIN) LabelChip(stringResource(R.string.team_role_captain), strong = true)
                    if (menuFor(member)) MemberMenu(member, enabled, onMenu)
                }
            }
        }
    }
}

/** Kaptanlığı Devret and Takımdan Çıkar for one member (captain). Each opens its confirmation. */
@Composable
private fun MemberMenu(member: TeamMember, enabled: Boolean, onMenu: (String, TeamMember) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.testTag(memberMenuTag(member.userId))) {
            Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.member_options_a11y, member.displayName))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.action_transfer_captain)) }, onClick = { expanded = false; onMenu("transfer", member) })
            DropdownMenuItem(text = { Text(stringResource(R.string.action_remove_member)) }, onClick = { expanded = false; onMenu("remove", member) })
        }
    }
}

/** A question before a change the user can not take back, with the server's refusal while it stays open. */
@Composable
private fun ConfirmDialog(
    title: String, body: String, confirm: String, busy: Boolean, problem: TeamProblem?, onConfirm: () -> Unit, onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            // Large text on a small phone: the dialog scrolls, as the app's other dialogs do.
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(body)
                DialogState(busy, problem)
            }
        },
        confirmButton = { TextButton(onClick = onConfirm, enabled = !busy) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.action_give_up)) } },
    )
}

@Composable
private fun DialogState(busy: Boolean, problem: TeamProblem?) {
    if (busy) Text(stringResource(R.string.account_waiting), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.announced())
    problem?.let {
        Text(teamProblemMessage(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.announced())
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

// ----- Takım Ayarları (team/{id}/settings) -----

/**
 * The captain changes the name, the usual match day and time, and the logo. Kaydet sends the name and the schedule; Logo
 * Seç and Logoyu Kaldır go to the server at once, each on its own. The form starts from the team as the server holds it.
 */
@Composable
fun TeamSettingsScreen(
    team: TeamInfo, logo: ImageBitmap?, busy: Boolean, problem: TeamProblem?, onSave: (name: String, schedule: Schedule?) -> Unit,
    onPickLogo: () -> Unit, onRemoveLogo: () -> Unit, onEdit: () -> Unit, onBack: () -> Unit,
) {
    var name by rememberSaveable(team.id) { mutableStateOf(team.name) }
    var day by rememberSaveable(team.id) { mutableStateOf(team.schedule?.weekday ?: 0) }
    var time by rememberSaveable(team.id) { mutableStateOf(team.schedule?.time.orEmpty()) }
    val ready = name.isNotBlank() && scheduleReady(day, time)
    FlowScaffold(
        title = stringResource(R.string.action_team_settings), onBack = onBack, backEnabled = !busy,
        actions = {
            if (busy) Text(stringResource(R.string.account_waiting), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.announced())
            problem?.let { Notice(teamProblemMessage(it), Tone.NEGATIVE, Modifier.announced()) }
            PrimaryButton(
                stringResource(R.string.action_save), enabled = ready && !busy,
                onClick = { onSave(name.trim(), if (day == 0) null else Schedule(day, time)) },
            )
        },
    ) {
        LogoSection(team.name, logo, enabled = !busy, removable = team.logoVersion != null, onPick = onPickLogo, onRemove = onRemoveLogo)
        OutlinedTextField(
            value = name, onValueChange = { name = it.take(TeamRules.NAME_MAX * 2); onEdit() },
            label = { Text(stringResource(R.string.field_team_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
        )
        ScheduleFields(day, time, onDay = { day = it; onEdit() }, onTime = { time = it; onEdit() })
    }
}

// ----- Çıkarılanlar (team/{id}/blocked) -----

private val dayFormat = DateTimeFormatter.ofPattern("d MMMM yyyy", turkish)

/** The day of a removal as the phone's clock reads the server's moment, or the moment's date when it can not be read. */
private fun removalDay(at: String): String =
    runCatching { Instant.parse(at).atZone(ZoneId.systemDefault()).toLocalDate().format(dayFormat) }.getOrElse { at.take(10) }

/**
 * Çıkarılanlar (captain, design 4.3): the accounts the captain removed, in the order of their removal, each with Engeli
 * Kaldır. [read] is the screen's read of the list; [problem] the refusal of the last Engeli Kaldır.
 */
@Composable
fun TeamBlockedScreen(read: BlockedRead?, busy: Boolean, problem: TeamProblem?, onUnblock: (String) -> Unit, onRetry: () -> Unit, onBack: () -> Unit) {
    FlowScaffold(title = stringResource(R.string.blocked_title), onBack = onBack) {
        val list = read?.list
        when {
            list == null && read?.problem != null && !read.loading -> ReadProblem(read.problem, onRetry)
            list == null -> Waiting(stringResource(R.string.blocked_loading))
            list.isEmpty() -> EmptyState(R.drawable.ic_person, stringResource(R.string.blocked_empty), stringResource(R.string.blocked_empty_body))
            else -> {
                if (busy) Text(stringResource(R.string.account_waiting), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.announced())
                problem?.let { Notice(teamProblemMessage(it), Tone.NEGATIVE, Modifier.announced()) }
                VsCard(padding = PaddingValues(vertical = 4.dp)) {
                    list.forEachIndexed { index, blocked ->
                        if (index > 0) RowDivider()
                        BlockedRow(blocked, enabled = !busy) { onUnblock(blocked.userId) }
                    }
                }
            }
        }
    }
}

@Composable
private fun BlockedRow(blocked: BlockedMember, enabled: Boolean, onUnblock: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.cardPadding, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(blocked.displayName, style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.blocked_on, removalDay(blocked.blockedAt)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SecondaryButton(stringResource(R.string.action_unblock), onUnblock, enabled = enabled)
    }
}

// ----- Forma Numaran (team/{id}/jersey) -----

/**
 * Forma Numaran (design 2.12 E, plan U6): the account's own jersey in this team, 1 to 99. An empty field clears it. Kaydet
 * sends it; Şimdi Değil goes back. It is a screen, as the design's join flow has it (10.1 `join/{poolId}/jersey`).
 */
@Composable
fun TeamJerseyScreen(current: Int?, busy: Boolean, problem: TeamProblem?, onSave: (Int?) -> Unit, onEdit: () -> Unit, onBack: () -> Unit) {
    var typed by rememberSaveable { mutableStateOf(current?.toString().orEmpty()) }
    FlowScaffold(
        title = stringResource(R.string.jersey_title), onBack = onBack, backEnabled = !busy,
        actions = {
            if (busy) Text(stringResource(R.string.account_waiting), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.announced())
            problem?.let { Notice(teamProblemMessage(it), Tone.NEGATIVE, Modifier.announced()) }
            PrimaryButton(stringResource(R.string.action_save), enabled = !busy, onClick = { onSave(typed.toIntOrNull()) })
            SecondaryButton(stringResource(R.string.action_not_now), onBack, enabled = !busy)
        },
    ) {
        Text(stringResource(R.string.team_jersey_body), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = typed, onValueChange = { typed = typedJersey(it); onEdit() }, enabled = !busy,
            label = { Text(stringResource(R.string.field_my_jersey)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            supportingText = { Text(stringResource(R.string.jersey_empty_hint)) },
        )
    }
}
