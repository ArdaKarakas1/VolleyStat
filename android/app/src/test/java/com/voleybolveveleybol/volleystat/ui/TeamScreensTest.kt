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
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.voleybolveveleybol.volleystat.R
import com.voleybolveveleybol.volleystat.BlockedRead
import com.voleybolveveleybol.volleystat.TeamsUi
import com.voleybolveveleybol.volleystat.model.BlockedMember
import com.voleybolveveleybol.volleystat.ui.screens.TeamActions
import com.voleybolveveleybol.volleystat.ui.screens.TeamBlockedScreen
import com.voleybolveveleybol.volleystat.ui.screens.TeamJerseyScreen
import com.voleybolveveleybol.volleystat.ui.screens.TeamSettingsScreen
import com.voleybolveveleybol.volleystat.ui.screens.memberMenuTag
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onLast
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

    /**
     * A day without a valid time is no schedule the server takes (it answers 422 VALIDATION_ERROR), so Takım Kur stays
     * off until the time is complete and valid, or until the day goes back to Yok (P6 checker, M7).
     */
    @Test fun takimKurStaysOffWhileADayHasNoValidTime() {
        val sent = mutableListOf<TeamDraft>()
        show { TeamCreateScreen(busy = false, problem = null, onCreate = { sent += it }, onEdit = {}, onBack = {}) }
        val create = compose.onNode(hasClickAction() and hasText(text(R.string.action_create_team)))
        compose.onNode(hasSetTextAction() and hasText(text(R.string.field_team_name))).performTextInput("Kuzey Spor")
        create.assertIsEnabled()
        // A day and no time.
        compose.onNodeWithText(dayName(3)).performScrollTo().performClick()
        create.assertIsNotEnabled()
        val time = compose.onNode(hasSetTextAction() and hasText(text(R.string.field_schedule_time)))
        // A time not yet complete, then a complete one outside 00:00 to 23:59.
        time.performScrollTo().performTextInput("19")
        create.assertIsNotEnabled()
        time.performTextReplacement("2500")
        create.assertIsNotEnabled()
        assertTrue(shown(text(R.string.team_time_invalid)))
        create.performClick()
        assertTrue("nothing goes to the server", sent.isEmpty())
        // A valid time turns it on and goes with the day.
        time.performTextReplacement("1930")
        create.assertIsEnabled().performClick()
        assertEquals(com.voleybolveveleybol.volleystat.model.Schedule(3, "19:30"), sent.single().schedule)
        // Back to Yok with a broken time: no schedule goes, and the button is on.
        time.performTextReplacement("2500")
        create.assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.team_schedule_none)).performScrollTo().performClick()
        create.assertIsEnabled().performClick()
        assertEquals(null, sent.last().schedule)
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

    // ----- P7: member management, Çıkarılanlar and the settings -----

    private val me = "u-1"

    private fun teamScreen(
        shownTeam: TeamInfo, actions: TeamActions = TeamActions(), problem: TeamProblem? = null, busy: Boolean = false, myId: String = me,
    ) = show { TeamScreen(shownTeam, loading = false, problem = null, onSeats = {}, onRetry = {}, onBack = {}, myUserId = myId, busy = busy, actionProblem = problem, actions = actions) }

    private fun button(label: String) = compose.onNode(hasClickAction() and hasText(label))

    @Test fun theRemovalDialogNamesTheBlockAndOffersNoLinkSwitchBeforeInvitesExist() {
        val removed = mutableListOf<String>()
        teamScreen(team(members = squad), TeamActions(onRemove = { id, done -> removed += id; done() }))
        // The captain's own row has no menu; another member's row has one.
        assertTrue(compose.onAllNodesWithTag(memberMenuTag(me), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag(memberMenuTag("u-2"), useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.action_remove_member)).performClick()
        assertTrue(shown(text(R.string.member_remove_title, "Ece")))
        assertTrue(shown(text(R.string.member_remove_body_no_invite)))
        // No Bağlantıyı da Yenile before invites exist (plan U2, U4): no switch and no word of a link.
        assertTrue(compose.onAllNodes(androidx.compose.ui.test.isToggleable()).fetchSemanticsNodes().isEmpty())
        everything().forEach { shown -> listOf("bağlantı", "Yenile", "davet").forEach { assertFalse("'$shown' names $it", shown.contains(it, ignoreCase = true)) } }
        // Vazgeç sends nothing; Takımdan Çıkar sends the member.
        button(text(R.string.action_give_up)).performClick()
        assertTrue(removed.isEmpty())
        assertFalse(shown(text(R.string.member_remove_body_no_invite)))
        compose.onNodeWithTag(memberMenuTag("u-2"), useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.action_remove_member)).performClick()
        compose.onAllNodes(hasClickAction() and hasText(text(R.string.action_remove_member))).onLast().performClick()
        assertEquals(listOf("u-2"), removed)
        compose.waitForIdle()
        assertFalse("the dialog closes after the server took it", shown(text(R.string.member_remove_body_no_invite)))
    }

    @Test fun theTransferDialogNamesTheMemberAndShowsTheServersLimit() {
        val transferred = mutableListOf<String>()
        var problem by mutableStateOf<TeamProblem?>(null)
        var dismissed = 0
        show {
            TeamScreen(
                team(members = squad), loading = false, problem = null, onSeats = {}, onRetry = {}, onBack = {}, myUserId = me, actionProblem = problem,
                actions = TeamActions(onTransfer = { id, _ -> transferred += id }, onDismiss = { dismissed++ }),
            )
        }
        compose.onNodeWithTag(memberMenuTag("u-3"), useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.action_transfer_captain)).performClick()
        assertTrue(shown(text(R.string.transfer_title)))
        assertTrue(shown(text(R.string.transfer_body, "Fatma")))
        compose.onAllNodes(hasClickAction() and hasText(text(R.string.action_transfer_captain))).onLast().performClick()
        assertEquals(listOf("u-3"), transferred)
        // The server refuses: the dialog stays and names the member and the limit.
        problem = TeamProblem.Limit(com.voleybolveveleybol.volleystat.data.TeamLimitScope.CAPTAIN, 3, "u-3", "Fatma")
        compose.waitForIdle()
        assertTrue(shown(text(R.string.transfer_captain_limit, "Fatma", 3)))
        button(text(R.string.action_give_up)).performClick()
        assertTrue("closing the dialog clears the refusal", dismissed > 0)
    }

    @Test fun aSoleCaptainSeesTakimiSilAndNoTakimdanAyril() {
        var deleted = 0
        teamScreen(team(), TeamActions(onDelete = { deleted++ }))
        assertFalse(shown(text(R.string.action_leave_team)))
        listOf(R.string.seats_title, R.string.action_team_settings, R.string.blocked_title, R.string.jersey_title).forEach {
            assertTrue(text(it), shown(text(it)))
        }
        button(text(R.string.action_delete_team)).performScrollTo().performClick()
        assertTrue(shown(text(R.string.team_delete_title)))
        assertTrue(shown(text(R.string.team_delete_body)))
        compose.onAllNodes(hasClickAction() and hasText(text(R.string.action_delete_team))).onLast().performClick()
        assertEquals(1, deleted)
    }

    @Test fun aCaptainWithMembersSeesNoTakimdanAyrilEither() {
        teamScreen(team(members = squad))
        assertFalse("a captain hands the captaincy on and never leaves (plan U8)", shown(text(R.string.action_leave_team)))
        assertTrue(shown(text(R.string.action_delete_team)))
    }

    @Test fun aMemberSeesTakimdanAyril() {
        var left = 0
        var problem by mutableStateOf<TeamProblem?>(null)
        show {
            TeamScreen(
                team(role = TeamRole.MEMBER, members = squad), loading = false, problem = null, onSeats = {}, onRetry = {}, onBack = {}, myUserId = "u-2",
                actionProblem = problem, actions = TeamActions(onLeave = { left++ }),
            )
        }
        // A member has none of the captain's buttons and no menu on any row.
        listOf(R.string.seats_title, R.string.action_team_settings, R.string.blocked_title, R.string.action_delete_team).forEach {
            assertFalse(text(it), shown(text(it)))
        }
        squad.forEach { assertTrue(compose.onAllNodesWithTag(memberMenuTag(it.userId), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) }
        button(text(R.string.action_leave_team)).performScrollTo().performClick()
        assertTrue(shown(text(R.string.team_leave_title)))
        assertTrue(shown(text(R.string.team_leave_body)))
        compose.onAllNodes(hasClickAction() and hasText(text(R.string.action_leave_team))).onLast().performClick()
        assertEquals(1, left)
        problem = TeamProblem.Unreachable
        compose.waitForIdle()
        assertTrue("the dialog says why", shown(text(R.string.net_no_answer)))
    }

    /**
     * P7-C3: a question stays open only while the screen offers the button that asked it. The captain's transfer went
     * through but its answer was lost; the repeat answers CAPTAIN_ONLY and the list read shows the member view. The
     * transfer, removal and deletion questions close, and the refusal stays on the screen. Takımdan Ayrıl's question
     * closes once the account became the captain.
     */
    @Test fun aQuestionClosesOnceTheScreenNoLongerOffersItsButton() {
        val after = listOf(
            member("u-1", "Deniz", Seat.STARTER, jersey = 7),
            member("u-2", "Ece", Seat.STARTER, TeamRole.CAPTAIN, jersey = 9),
            member("u-3", "Fatma", Seat.SUBSTITUTE, jersey = 12),
        )
        var opened by mutableStateOf<String?>(null)
        var memberView by mutableStateOf(true)
        var dismissed = 0
        show {
            val shownTeam = if (memberView) team(role = TeamRole.MEMBER, members = after) else team(members = squad)
            androidx.compose.runtime.key(opened, memberView) {
                TeamScreen(
                    shownTeam, loading = false, problem = null, onSeats = {}, onRetry = {}, onBack = {}, myUserId = me,
                    actionProblem = if (memberView) TeamProblem.CaptainOnly else null, actions = TeamActions(onDismiss = { dismissed++ }), initialDialog = opened,
                )
            }
        }
        val questions = mapOf(
            "transfer:u-2" to text(R.string.transfer_title), "remove:u-3" to text(R.string.member_remove_title, "Fatma"), "delete" to text(R.string.team_delete_title),
        )
        questions.forEach { (dialog, question) ->
            opened = dialog
            compose.waitForIdle()
            assertFalse("$dialog: the question is gone", shown(question))
            listOf(R.string.action_transfer_captain, R.string.action_remove_member, R.string.action_delete_team).forEach {
                assertTrue("$dialog: no ${text(it)}", compose.onAllNodes(hasClickAction() and hasText(text(it))).fetchSemanticsNodes().isEmpty())
            }
            assertEquals("$dialog: the refusal shows once, on the screen", 1, compose.onAllNodesWithText(text(R.string.team_captain_only)).fetchSemanticsNodes().size)
            assertTrue(shown(text(R.string.action_leave_team)))
        }
        assertEquals("closing keeps the refusal", 0, dismissed)

        // A member's Takımdan Ayrıl question closes once the account is the captain.
        memberView = false
        opened = "leave"
        compose.waitForIdle()
        assertFalse(shown(text(R.string.team_leave_title)))
        assertFalse(shown(text(R.string.action_leave_team)))
        // While the button is offered the question stays: the captain's own questions are open.
        opened = "transfer:u-2"
        compose.waitForIdle()
        assertTrue(shown(text(R.string.transfer_title)))
        assertEquals(0, dismissed)
    }

    @Test fun theTeamScreenOpensFormaNumaranForEveryMember() {
        // The team screen's button opens Forma Numaran, for the captain and for a member.
        var opened = 0
        var member by mutableStateOf(false)
        show {
            TeamScreen(
                if (member) team(role = TeamRole.MEMBER, members = squad) else team(members = squad), loading = false, problem = null,
                onSeats = {}, onRetry = {}, onBack = {}, myUserId = if (member) "u-2" else me, actions = TeamActions(onJersey = { opened++ }),
            )
        }
        button(text(R.string.jersey_title)).performScrollTo().performClick()
        member = true
        compose.waitForIdle()
        button(text(R.string.jersey_title)).performScrollTo().performClick()
        assertEquals(2, opened)
    }

    @Test fun theJerseyScreenSendsTheNumberAndShowsTheTakenNumber() {
        val sent = mutableListOf<Int?>()
        var problem by mutableStateOf<TeamProblem?>(null)
        var back = 0
        show { TeamJerseyScreen(7, busy = false, problem = problem, onSave = { sent += it }, onEdit = { problem = null }, onBack = { back++ }) }
        assertTrue(shown(text(R.string.team_jersey_body)))
        assertTrue(shown(text(R.string.jersey_empty_hint)))
        val field = compose.onNode(hasSetTextAction() and hasText(text(R.string.field_my_jersey)))
        field.assertTextContains("7")
        field.performTextReplacement("12")
        button(text(R.string.action_save)).performClick()
        assertEquals(listOf<Int?>(12), sent)
        problem = TeamProblem.JerseyTaken(12)
        compose.waitForIdle()
        assertTrue(shown(text(R.string.jersey_taken, 12)))
        // An empty field clears the number; only digits go in, at most two, without a leading zero.
        field.performTextReplacement("")
        button(text(R.string.action_save)).performClick()
        assertEquals(listOf(12, null), sent)
        field.performTextReplacement("1a23")
        field.assertTextContains("12")
        field.performTextReplacement("07")
        field.assertTextContains("7")
        button(text(R.string.action_not_now)).performClick()
        assertEquals(1, back)
    }

    @Test fun cikarilanlarListsTheBlockedAndUnblocks() {
        val unblocked = mutableListOf<String>()
        var read by mutableStateOf<BlockedRead?>(
            BlockedRead("t1", listOf(BlockedMember("u-7", "Gül", "2026-11-22T10:00:00Z"), BlockedMember("u-8", "Hale", "2026-11-23T10:00:00Z"))),
        )
        var retries = 0
        show { TeamBlockedScreen(read, busy = false, problem = null, onUnblock = { unblocked += it }, onRetry = { retries++ }, onBack = {}) }
        assertTrue(shown(text(R.string.blocked_title)))
        assertTrue(shown("Gül"))
        assertTrue(shown("Hale"))
        assertEquals(2, compose.onAllNodes(hasClickAction() and hasText(text(R.string.action_unblock))).fetchSemanticsNodes().size)
        compose.onAllNodes(hasClickAction() and hasText(text(R.string.action_unblock)))[1].performClick()
        assertEquals(listOf("u-8"), unblocked)
        // Loading, empty and a failed read.
        read = BlockedRead("t1", loading = true)
        compose.waitForIdle()
        assertTrue(shown(text(R.string.blocked_loading)))
        read = BlockedRead("t1", emptyList())
        compose.waitForIdle()
        assertTrue(shown(text(R.string.blocked_empty)))
        assertTrue(shown(text(R.string.blocked_empty_body)))
        read = BlockedRead("t1", problem = TeamProblem.Unreachable)
        compose.waitForIdle()
        assertTrue(shown(text(R.string.net_no_answer)))
        button(text(R.string.action_retry)).performClick()
        assertEquals(1, retries)
    }

    @Test fun theSettingsScreenSavesNameAndSchedule() {
        val saved = mutableListOf<Pair<String, com.voleybolveveleybol.volleystat.model.Schedule?>>()
        var picks = 0
        var removals = 0
        val shownTeam = team().copy(schedule = com.voleybolveveleybol.volleystat.model.Schedule(2, "20:00"), logoVersion = 3)
        var problem by mutableStateOf<TeamProblem?>(null)
        show {
            TeamSettingsScreen(
                shownTeam, logo = null, busy = false, problem = problem, onSave = { name, schedule -> saved += name to schedule },
                onPickLogo = { picks++ }, onRemoveLogo = { removals++ }, onEdit = {}, onBack = {},
            )
        }
        // The form starts from the team as the server holds it.
        compose.onNode(hasSetTextAction() and hasText(text(R.string.field_team_name))).assertTextContains("Kuzey Spor")
        compose.onNode(hasSetTextAction() and hasText(text(R.string.field_schedule_time))).assertTextContains("20:00")
        compose.onNodeWithText(dayName(2)).assertIsSelected()
        compose.onNode(hasSetTextAction() and hasText(text(R.string.field_team_name))).performTextReplacement("  Güney Spor ")
        compose.onNodeWithText(dayName(5)).performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasText(text(R.string.field_schedule_time))).performScrollTo().performTextReplacement("1930")
        button(text(R.string.action_save)).performClick()
        assertEquals(listOf("Güney Spor" to com.voleybolveveleybol.volleystat.model.Schedule(5, "19:30")), saved)
        // Yok clears the schedule; a day without a valid time can not be saved.
        compose.onNodeWithText(dayName(5)).performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasText(text(R.string.field_schedule_time))).performScrollTo().performTextReplacement("2500")
        button(text(R.string.action_save)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.team_schedule_none)).performScrollTo().performClick()
        button(text(R.string.action_save)).assertIsEnabled().performClick()
        assertEquals("Güney Spor" to null, saved.last())
        // The logo: Logo Seç and, while the team has one, Logoyu Kaldır.
        button(text(R.string.action_pick_logo)).performScrollTo().performClick()
        button(text(R.string.action_remove_logo)).performScrollTo().performClick()
        assertEquals(1 to 1, picks to removals)
        problem = TeamProblem.LogoInvalid("large")
        compose.waitForIdle()
        assertTrue(shown(text(R.string.logo_invalid)))
    }

    @Test fun takimKurOffersALogoAndShowsTheChosenOne() {
        var picks = 0
        var removals = 0
        var logo by mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
        show { TeamCreateScreen(busy = false, problem = null, onCreate = {}, onEdit = {}, onBack = {}, logo = logo, onPickLogo = { picks++ }, onRemoveLogo = { removals++ }) }
        button(text(R.string.action_pick_logo)).performScrollTo().performClick()
        assertEquals(1, picks)
        assertFalse("nothing to remove yet", shown(text(R.string.action_remove_logo)))
        logo = android.graphics.Bitmap.createBitmap(256, 256, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
        compose.waitForIdle()
        button(text(R.string.action_remove_logo)).performScrollTo().performClick()
        assertEquals(1, removals)
    }
}
