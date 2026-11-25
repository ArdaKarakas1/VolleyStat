package com.voleybolveveleybol.volleystat.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.voleybolveveleybol.volleystat.R
import com.voleybolveveleybol.volleystat.TeamsUi
import com.voleybolveveleybol.volleystat.data.TeamProblem
import com.voleybolveveleybol.volleystat.model.Seat
import com.voleybolveveleybol.volleystat.model.SeatCount
import com.voleybolveveleybol.volleystat.model.SeatCounts
import com.voleybolveveleybol.volleystat.model.TeamDraft
import com.voleybolveveleybol.volleystat.model.TeamInfo
import com.voleybolveveleybol.volleystat.model.TeamMember
import com.voleybolveveleybol.volleystat.model.TeamRole
import com.voleybolveveleybol.volleystat.ui.screens.TeamCreateScreen
import com.voleybolveveleybol.volleystat.ui.screens.TeamScreen
import com.voleybolveveleybol.volleystat.ui.screens.TeamSeatsScreen
import com.voleybolveveleybol.volleystat.ui.screens.TeamsScreen
import com.voleybolveveleybol.volleystat.ui.screens.seatToggleTag
import com.voleybolveveleybol.volleystat.ui.theme.VolleyStatTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The team screens of slice B4 (plan P6): the Teams tab, Takım Kur, the team screen and As ve Yedek Yerleri. All names are invented. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class TeamScreensTest {
    @get:Rule val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun text(id: Int, vararg arguments: Any) = context.getString(id, *arguments)
    private fun shown(value: String) = compose.onAllNodesWithText(value, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun show(content: @Composable () -> Unit) = compose.setContent { VolleyStatTheme(dark = false) { content() } }

    /** Every text and content description on screen, merged or not. */
    private fun everything(): List<String> {
        fun walk(node: SemanticsNode): List<String> =
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
                node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
                listOfNotNull(node.config.getOrNull(SemanticsProperties.EditableText)?.text) +
                node.children.flatMap(::walk)
        return compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().flatMap(::walk)
    }

    private fun member(id: String, name: String, seat: Seat, role: TeamRole = TeamRole.MEMBER, jersey: Int? = null, joined: String = "2026-11-20T10:00:00Z") =
        TeamMember(id, name, jersey, role, seat, joined)

    private fun team(
        id: String = "t1", name: String = "Kuzey Spor", role: TeamRole = TeamRole.CAPTAIN, newJoins: Int? = 0,
        members: List<TeamMember> = listOf(member("u-1", "Deniz", Seat.STARTER, TeamRole.CAPTAIN, 7)), substitutes: Int = 2, waiting: Int? = 0,
    ): TeamInfo {
        fun count(seats: Int, seat: Seat): SeatCount = members.count { it.seat == seat }.let { SeatCount(seats, it, seats - it, 0) }
        return TeamInfo(
            id, "pool-$id", name, "K7Q2", null, null, role, Seat.STARTER, null, if (role == TeamRole.CAPTAIN) newJoins else null, members,
            SeatCounts(count(6, Seat.STARTER), count(substitutes, Seat.SUBSTITUTE)), if (role == TeamRole.CAPTAIN) waiting else null,
        )
    }

    private val squad = listOf(
        member("u-1", "Deniz", Seat.STARTER, TeamRole.CAPTAIN, 7),
        member("u-2", "Ece", Seat.STARTER, jersey = 9),
        member("u-3", "Fatma", Seat.SUBSTITUTE, jersey = 12),
    )

    @Test fun theTeamsTabListsTeamsWithRoleSeatLineAndBadge() {
        val opened = mutableListOf<String>()
        var create = 0
        val captain = team("t1", newJoins = 2, members = squad)
        val member = team("t2", name = "Güney Spor", role = TeamRole.MEMBER, members = squad)
        var ui by mutableStateOf(TeamsUi(teams = listOf(captain, member)))
        var retries = 0
        show { TeamsScreen(ui, onCreate = { create++ }, onOpen = { opened += it }, onRetry = { retries++ }) }
        compose.onNodeWithText("Kuzey Spor").assertIsDisplayed()
        compose.onNodeWithText("Güney Spor").assertIsDisplayed()
        assertTrue("the tag the server drew", shown("#K7Q2"))
        compose.onAllNodesWithText(text(R.string.team_role_captain)).fetchSemanticsNodes().let { assertEquals(1, it.size) }
        compose.onAllNodesWithText(text(R.string.team_role_member)).fetchSemanticsNodes().let { assertEquals(1, it.size) }
        // The server's numbers: 2 of 6 starter seats and 1 of 2 substitute seats are taken.
        assertEquals(2, compose.onAllNodesWithText(text(R.string.team_seat_line, 2, 6, 1, 2)).fetchSemanticsNodes().size)
        compose.onAllNodesWithContentDescription(text(R.string.team_new_joins_a11y, 2), useUnmergedTree = true).fetchSemanticsNodes().let { assertEquals(1, it.size) }
        compose.onNodeWithText("Güney Spor").performClick()
        assertEquals(listOf("t2"), opened)
        compose.onNode(hasClickAction() and hasText(text(R.string.action_create_team))).performClick()
        assertEquals(1, create)

        // The states of 10.9: empty, loading, an older server and no answer.
        ui = TeamsUi(teams = emptyList())
        compose.waitForIdle()
        assertTrue(shown(text(R.string.teams_empty_title)))
        assertTrue(shown(text(R.string.teams_empty_body, text(R.string.action_create_team))))
        ui = TeamsUi(loading = true)
        compose.waitForIdle()
        assertTrue(shown(text(R.string.teams_loading)))
        assertFalse("an unread list is not an empty one", shown(text(R.string.teams_empty_title)))
        ui = TeamsUi(loadProblem = TeamProblem.ServerOutdated)
        compose.waitForIdle()
        assertTrue(shown(text(R.string.teams_server_older)))
        assertFalse(shown(text(R.string.action_retry)))
        ui = TeamsUi(loadProblem = TeamProblem.Unreachable)
        compose.waitForIdle()
        assertTrue(shown(text(R.string.net_no_answer)))
        compose.onNode(hasClickAction() and hasText(text(R.string.action_retry))).performClick()
        assertEquals(1, retries)
        // A list the app read before keeps showing while a new read failed, with the reason.
        ui = TeamsUi(teams = listOf(member), loadProblem = TeamProblem.Unreachable)
        compose.waitForIdle()
        assertTrue(shown("Güney Spor"))
        assertTrue(shown(text(R.string.net_no_answer)))
    }

    @Test fun takimKurFillsTwoSubstituteSeatsAndStarterForMe() {
        val sent = mutableListOf<TeamDraft>()
        show { TeamCreateScreen(busy = false, problem = null, onCreate = { sent += it }, onEdit = {}, onBack = {}) }
        assertTrue(shown(text(R.string.team_new_seats_body)))
        compose.onNodeWithText("2").assertIsSelected()
        compose.onNodeWithText(text(R.string.seat_starter)).assertIsSelected()
        compose.onNodeWithText(text(R.string.seat_substitute)).assertIsNotSelected()
        val create = compose.onNode(hasClickAction() and hasText(text(R.string.action_create_team)))
        create.assertIsNotEnabled()
        compose.onNode(hasSetTextAction() and hasText(text(R.string.field_team_name))).performTextInput("Kuzey Spor")
        create.assertIsEnabled().performClick()
        assertEquals(listOf(TeamDraft("Kuzey Spor", 2, Seat.STARTER, null, null)), sent)

        // A day, a time and a jersey go with the team.
        compose.onNodeWithText(dayName(2)).performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasText(text(R.string.field_schedule_time))).performScrollTo().performTextInput("2030")
        compose.onNode(hasSetTextAction() and hasText(text(R.string.field_my_jersey))).performScrollTo().performTextInput("07")
        compose.onNodeWithText("3").performScrollTo().performClick()
        create.performClick()
        assertEquals(TeamDraft("Kuzey Spor", 3, Seat.STARTER, com.voleybolveveleybol.volleystat.model.Schedule(2, "20:30"), 7), sent.last())
    }

    private fun dayName(weekday: Int) = java.time.DayOfWeek.of(weekday).getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.forLanguageTag("tr-TR"))

    @Test fun yedekUnderSeninYerinIsOffWhileTheCountIs0() {
        val sent = mutableListOf<TeamDraft>()
        show { TeamCreateScreen(busy = false, problem = null, onCreate = { sent += it }, onEdit = {}, onBack = {}) }
        compose.onNodeWithText(text(R.string.seat_substitute)).performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText(text(R.string.seat_substitute)).assertIsSelected()
        compose.onNodeWithText("0").performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.seat_substitute)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.seat_starter)).assertIsSelected()
        compose.onNode(hasSetTextAction() and hasText(text(R.string.field_team_name))).performTextInput("Kuzey Spor")
        compose.onNode(hasClickAction() and hasText(text(R.string.action_create_team))).performClick()
        assertEquals(TeamDraft("Kuzey Spor", 0, Seat.STARTER), sent.single())
    }

    @Test fun theTeamScreenGroupsMembersUnderAslarAndYedekler() {
        // Null: the full team; else the state of a team the app has not read.
        var state by mutableStateOf<Pair<Boolean, TeamProblem?>?>(null)
        show {
            val unread = state
            if (unread == null) TeamScreen(team(members = squad, substitutes = 2), loading = false, problem = null, onSeats = {}, onRetry = {}, onBack = {})
            else TeamScreen(null, loading = unread.first, problem = unread.second, onSeats = {}, onRetry = {}, onBack = {})
        }
        fun top(value: String) = compose.onNodeWithText(value).getUnclippedBoundsInRoot().top
        val starters = top(text(R.string.team_starters))
        val substitutes = top(text(R.string.team_substitutes))
        assertTrue(starters < top("Deniz") && top("Deniz") < substitutes)
        assertTrue(top("Ece") < substitutes)
        assertTrue(substitutes < top("Fatma"))
        assertTrue("the tag the server drew", shown("#K7Q2"))

        // The states of 10.9: loading, gone, no access and an older server.
        listOf(
            Triple(true, null, R.string.team_loading),
            Triple(false, TeamProblem.Gone, R.string.team_gone),
            Triple(false, TeamProblem.NoAccess, R.string.team_no_access),
            Triple(false, TeamProblem.Unreachable, R.string.net_no_answer),
        ).forEach { (loading, problem, expected) ->
            state = Pair(loading, problem)
            compose.waitForIdle()
            assertTrue("$problem", shown(text(expected)))
        }
    }

    @Test fun theStarterFreeHintShowsOnlyToTheCaptainUnderItsCondition() {
        var shownTeam by mutableStateOf(team(members = squad))
        show { TeamScreen(shownTeam, loading = false, problem = null, onSeats = {}, onRetry = {}, onBack = {}) }
        // Four free starter seats, one substitute, nobody waits: the captain sees the hint with the server's numbers.
        val hint = text(R.string.team_starter_free_hint, 4, 1)
        assertTrue(shown(hint))
        // The words between the two numbers, whatever the numbers are.
        val between = text(R.string.team_starter_free_hint, 4, 1).substringAfter("4").substringBefore("1")
        fun anyStarterHint() = shown(between)
        shownTeam = team(role = TeamRole.MEMBER, members = squad)
        compose.waitForIdle()
        assertFalse("a member never sees it", shown(text(R.string.team_starter_free_hint, 4, 1)))
        shownTeam = team(members = squad.filter { it.seat == Seat.STARTER })
        compose.waitForIdle()
        assertFalse("no substitute to move up", anyStarterHint())
        shownTeam = team(members = squad, waiting = 1)
        compose.waitForIdle()
        assertFalse("somebody waits for a seat", shown(hint))
        val full = squad + (4..7).map { member("u-$it", "Oyuncu $it", Seat.STARTER) }
        shownTeam = team(members = full)
        compose.waitForIdle()
        assertFalse("no starter seat is free", anyStarterHint())
        // The captain's own buttons: As ve Yedek Yerleri. A member has none in this slice.
        assertTrue(compose.onAllNodesWithText(text(R.string.seats_title)).fetchSemanticsNodes().isNotEmpty())
        shownTeam = team(role = TeamRole.MEMBER, members = squad)
        compose.waitForIdle()
        assertFalse(shown(text(R.string.seats_title)))
    }

    @Test fun theSeatsScreenSendsTheFinalStateAndKeepsItOnARefusal() {
        val saved = mutableListOf<Pair<Int, Map<String, Seat>>>()
        var problem by mutableStateOf<TeamProblem?>(null)
        var edits = 0
        show { TeamSeatsScreen(team(members = squad), busy = false, problem = problem, onSave = { count, moves -> saved += count to moves }, onEdit = { edits++ }, onBack = {}) }
        assertTrue(shown(text(R.string.team_seats_body)))
        assertTrue(shown(text(R.string.lineup_body, text(R.string.action_save))))
        compose.onNodeWithText("2").assertIsSelected()
        compose.onNodeWithText("3").performScrollTo().performClick()
        compose.onNodeWithTag(seatToggleTag("u-2", Seat.SUBSTITUTE), useUnmergedTree = true).performScrollTo().performClick()
        // Moved there and back: no move of Fatma's.
        compose.onNodeWithTag(seatToggleTag("u-3", Seat.STARTER), useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithTag(seatToggleTag("u-3", Seat.SUBSTITUTE), useUnmergedTree = true).performScrollTo().performClick()
        compose.onNode(hasClickAction() and hasText(text(R.string.action_save))).performClick()
        assertEquals("only the changed member goes", listOf(3 to mapOf("u-2" to Seat.SUBSTITUTE)), saved)

        problem = TeamProblem.SeatsBelowHolders(Seat.SUBSTITUTE, 3, 2, movedStarterDown = true)
        compose.waitForIdle()
        assertTrue(shown(text(R.string.seats_below_substitute, 3, 2)))
        assertTrue(shown(text(R.string.seats_raise_or_swap_hint)))
        // The input stays as the user left it.
        compose.onNodeWithText("3").assertIsSelected()
        compose.onNodeWithTag(seatToggleTag("u-2", Seat.SUBSTITUTE), useUnmergedTree = true).assertIsSelected()
        // An edit clears the refusal.
        compose.onNodeWithText("4").performScrollTo().performClick()
        assertTrue(edits > 0)
        compose.onNode(hasClickAction() and hasText(text(R.string.action_save))).performClick()
        assertEquals(4 to mapOf("u-2" to Seat.SUBSTITUTE), saved.last())
    }

    @Test fun noTeamScreenOffersAnInviteOrACodeBeforeInvitesExist() {
        val screens: List<@Composable () -> Unit> = listOf(
            { TeamsScreen(TeamsUi(teams = listOf(team(members = squad, newJoins = 1))), {}, {}, {}) },
            { TeamsScreen(TeamsUi(teams = emptyList()), {}, {}, {}) },
            { TeamCreateScreen(busy = false, problem = null, onCreate = {}, onEdit = {}, onBack = {}) },
            { TeamScreen(team(members = squad, newJoins = 1), loading = false, problem = null, onSeats = {}, onRetry = {}, onBack = {}) },
            { TeamSeatsScreen(team(members = squad), busy = false, problem = null, onSave = { _, _ -> }, onEdit = {}, onBack = {}) },
        )
        var shownScreen by mutableStateOf(screens.first())
        show { shownScreen() }
        screens.forEach { screen ->
            shownScreen = screen
            compose.waitForIdle()
            val all = everything()
            assertTrue("the screen shows something", all.isNotEmpty())
            all.forEach { shown ->
                listOf("Kod Gir", "davet", "bağlantı", "Paylaş", "kod").forEach { word ->
                    assertFalse("'$shown' names $word before invites exist", shown.contains(word, ignoreCase = true))
                }
            }
        }
    }
}
