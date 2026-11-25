package com.voleybolveveleybol.volleystat.ui

import android.app.Application
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onLast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.voleybolveveleybol.volleystat.AccountStandIn
import com.voleybolveveleybol.volleystat.AppViewModel
import com.voleybolveveleybol.volleystat.MainActivity
import com.voleybolveveleybol.volleystat.R
import com.voleybolveveleybol.volleystat.TestAccounts
import com.voleybolveveleybol.volleystat.model.Settings
import okhttp3.mockwebserver.MockResponse
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CountDownLatch

private const val PASSWORD = "uzun bir şifre 42"

/**
 * The team screen's route through the real activity, navigation and view model, against the account stand-in (slice B4,
 * plan P6): its own read of the team, which a read of the list, an earlier team screen and a resume leave alone, and the
 * Son katılanlar line, which counts what the app marks seen.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class TeamFlowTest {
    // See JourneyTest: the static factory would hand the view model another test's Application.
    @get:Rule(order = 0) val freshViewModelFactory = object : ExternalResource() {
        override fun before() {
            val factory = ViewModelProvider.AndroidViewModelFactory::class.java
            factory.declaredFields.single { it.type == factory }.apply { isAccessible = true }.set(null, null)
        }
    }
    @get:Rule(order = 1) val compose = createEmptyComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val standIn = AccountStandIn()
    private var scenario: ActivityScenario<MainActivity>? = null

    @After fun stop() {
        scenario?.close()
        standIn.close()
    }

    private fun text(id: Int, vararg arguments: Any) = context.getString(id, *arguments)
    private fun shown(value: String) = compose.onAllNodesWithText(value, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(value: String) = compose.waitUntil(10_000) { shown(value) }
    private fun tapText(value: String) = compose.onAllNodesWithText(value).onFirst().performClick()
    private fun count(route: String) = standIn.requests.count { it.first == route }

    /** Waits for a condition on the view model's state, which only moves while the main thread runs what was posted to it. */
    private fun until(condition: () -> Boolean) = compose.waitUntil(10_000) { compose.waitForIdle(); condition() }

    private fun member(id: String, name: String, role: String, joined: String) = JSONObject().put("userId", id).put("displayName", name)
        .put("jersey", JSONObject.NULL).put("role", role).put("seat", "STARTER").put("joinedAt", joined)

    private val captain = member("u-1", "Ali Yılmaz", "CAPTAIN", "2026-11-20T10:00:00Z")

    private fun team(id: String, name: String, newJoins: Int, vararg members: JSONObject): JSONObject {
        fun seats(seats: Int, taken: Int) = JSONObject().put("seats", seats).put("taken", taken).put("left", seats - taken).put("deciding", 0)
        return JSONObject().put("id", id).put("poolId", "pool-$id").put("name", name).put("tag", "K7Q2").put("schedule", JSONObject.NULL)
            .put("logo", JSONObject.NULL).put("myRole", "CAPTAIN").put("mySeat", "STARTER").put("myJersey", JSONObject.NULL).put("newJoins", newJoins)
            .put("members", JSONArray(members.toList()))
            .put("seats", JSONObject().put("starter", seats(6, members.size)).put("substitute", seats(2, 0)).put("waiting", 0)).put("invite", JSONObject.NULL)
    }

    private fun ok(json: JSONObject) = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(json.toString())

    /** Logs in as the captain on a phone that has seen the welcome and the old-data question, and opens the Teams tab. */
    private fun teamsTab(vararg names: String): AppViewModel {
        TestAccounts.device(context, Settings(welcomeSeen = true, accountSeen = true, legacyHandled = true))
        standIn.add("ali", "Ali Yılmaz", PASSWORD)
        val launched = ActivityScenario.launch(MainActivity::class.java)
        scenario = launched
        lateinit var viewModel: AppViewModel
        launched.onActivity { viewModel = ViewModelProvider(it)[AppViewModel::class.java] }
        viewModel.baseUrlOverride = standIn.baseUrl
        compose.waitForIdle()
        compose.onNode(hasSetTextAction() and hasText(text(R.string.field_username))).performTextReplacement("ali")
        compose.onNode(hasSetTextAction() and hasText(text(R.string.field_password))).performTextReplacement(PASSWORD)
        compose.onNode(hasClickAction() and hasText(text(R.string.action_login))).performClick()
        waitFor(text(R.string.home_greeting_name, "Ali Yılmaz"))
        tapText(text(R.string.nav_teams))
        names.forEach(::waitFor)
        return viewModel
    }

    /** Waits until a read of the list that reached the stand-in after its first [after] requests has answered. */
    private fun awaitListRead(viewModel: AppViewModel, after: Int) {
        until { standIn.requests.drop(after).any { it.first == "GET /teams" } && !viewModel.state.value.serverTeams.loading }
    }

    @Test fun aRefusedTeamKeepsItsRefusalWhenAResumeReadsTheList() {
        standIn.teams = JSONArray().put(team("t1", "Kuzey Spor", 0, captain))
        val viewModel = teamsTab("Kuzey Spor")
        standIn.answers["GET /teams/t1"] = { standIn.refuse(403, "NOT_A_MEMBER") }
        tapText("Kuzey Spor")
        waitFor(text(R.string.team_no_access))
        // The list the server sends now no longer holds the team either. The app goes to the background and comes back.
        standIn.teams = JSONArray()
        val before = standIn.requests.size
        scenario!!.moveToState(Lifecycle.State.CREATED)
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        awaitListRead(viewModel, before)
        assertTrue("the screen still says why it shows no team", shown(text(R.string.team_no_access)))
        assertFalse("the screen waits for no read", shown(text(R.string.team_loading)))
        assertEquals("the screen asked once", 1, count("GET /teams/t1"))
    }

    @Test fun aLateRefusalOfATeamScreenLeftBehindStaysOffTheNextOne() {
        standIn.teams = JSONArray().put(team("a", "Alfa Spor", 0, captain)).put(team("b", "Beta Spor", 0, captain))
        val viewModel = teamsTab("Alfa Spor", "Beta Spor")
        val held = CountDownLatch(1)
        standIn.hold["GET /teams/a"] = held
        standIn.answers["GET /teams/a"] = { standIn.refuse(404, "TEAM_NOT_FOUND") }
        // Beta's own read names a member the list did not: the screen shows her once that read answered.
        standIn.answers["GET /teams/b"] = { ok(team("b", "Beta Spor", 0, captain, member("u-2", "Ece Beta", "MEMBER", "2026-11-21T10:00:00Z"))) }
        tapText("Alfa Spor")
        compose.waitUntil(10_000) { count("GET /teams/a") == 1 }
        // The answer is slow; the user goes back and opens the other team, whose answer comes at once.
        compose.onAllNodesWithContentDescription(text(R.string.action_back)).onFirst().performClick()
        waitFor("Beta Spor")
        tapText("Beta Spor")
        waitFor("Ece Beta")
        held.countDown()
        // The refusal of the earlier screen takes its team off the list.
        until { viewModel.state.value.serverTeams.teams?.none { it.id == "a" } == true }
        assertFalse("Beta's screen shows no refusal of Alfa", shown(text(R.string.team_gone)))
        assertTrue("Beta's screen keeps its team", shown("Ece Beta"))
    }

    @Test fun aJoinBetweenTheListAndTheTeamReadIsShownBeforeItIsMarkedSeen() {
        standIn.teams = JSONArray().put(team("t1", "Kuzey Spor", 0, captain))
        val viewModel = teamsTab("Kuzey Spor")
        // Ece joins while the captain looks at the list: the team's own read names her as new.
        val ece = member("u-2", "Ece Yeni", "MEMBER", "2026-11-25T10:00:00Z")
        standIn.answers["GET /teams/t1"] = { ok(team("t1", "Kuzey Spor", 1, captain, ece)) }
        standIn.answers["POST /teams/t1/joins-seen"] = {
            standIn.teams = JSONArray().put(team("t1", "Kuzey Spor", 0, captain, ece))
            MockResponse().setResponseCode(204)
        }
        tapText("Kuzey Spor")
        waitFor("Ece Yeni")
        compose.waitUntil(10_000) { count("POST /teams/t1/joins-seen") == 1 }
        // The mark ends with a read of the list, which names no new member any more.
        awaitListRead(viewModel, standIn.requests.indexOfFirst { it.first == "POST /teams/t1/joins-seen" })
        assertEquals(0, viewModel.state.value.serverTeams.badge)
        assertTrue("the captain sees the member the app marked seen", shown(text(R.string.team_recent_joins, 1)))
        assertTrue(shown("Ece Yeni"))
    }

    /**
     * Plan P7 through the real navigation: Takım Ayarları, Forma Numaran and Çıkarılanlar open from the team screen, each
     * sends its call and goes back, and Takımı Sil ends on the Teams tab without the team.
     */
    @Test fun theCaptainsScreensOpenFromTheTeamScreenAndTakimiSilEndsOnTheList() {
        standIn.teams = JSONArray().put(team("t1", "Kuzey Spor", 0, captain))
        val viewModel = teamsTab("Kuzey Spor")
        val me = standIn.userId("ali")
        val mine = { name: String, jersey: Int? ->
            team("t1", name, 0, member(me, "Ali Yılmaz", "CAPTAIN", "2026-11-20T10:00:00Z").put("jersey", jersey ?: JSONObject.NULL))
                .put("myJersey", jersey ?: JSONObject.NULL)
        }
        standIn.teams = JSONArray().put(mine("Kuzey Spor", null))
        // The team's own read answers with the team as the stand-in holds it now.
        standIn.answers["GET /teams/t1"] = { standIn.teams.optJSONObject(0)?.let(::ok) ?: standIn.refuse(404, "TEAM_NOT_FOUND") }
        tapText("Kuzey Spor")
        waitFor(text(R.string.action_team_settings))

        // Takım Ayarları: the form starts from the team, Kaydet sends the name and goes back.
        standIn.answers["PUT /teams/t1/settings"] = {
            standIn.teams = JSONArray().put(mine("Güney Spor", null))
            ok(mine("Güney Spor", null))
        }
        tapText(text(R.string.action_team_settings))
        waitFor(text(R.string.action_pick_logo))
        compose.onNode(hasSetTextAction() and hasText(text(R.string.field_team_name))).performTextReplacement("Güney Spor")
        compose.onNode(hasClickAction() and hasText(text(R.string.action_save))).performClick()
        waitFor(text(R.string.blocked_title))
        until { count("PUT /teams/t1/settings") == 1 }
        assertTrue(shown("Güney Spor"))

        // Forma Numaran: the own number goes to the member's own route.
        standIn.answers["PUT /teams/t1/members/$me/jersey"] = {
            standIn.teams = JSONArray().put(mine("Güney Spor", 4))
            ok(member(me, "Ali Yılmaz", "CAPTAIN", "2026-11-20T10:00:00Z").put("jersey", 4))
        }
        tapText(text(R.string.jersey_title))
        waitFor(text(R.string.team_jersey_body))
        compose.onNode(hasSetTextAction() and hasText(text(R.string.field_my_jersey))).performTextReplacement("4")
        compose.onNode(hasClickAction() and hasText(text(R.string.action_save))).performClick()
        waitFor(text(R.string.blocked_title))
        assertEquals(1, count("PUT /teams/t1/members/$me/jersey"))
        until { viewModel.state.value.serverTeams.teamOf("t1")?.myJersey == 4 }

        // Çıkarılanlar: the list, and Engeli Kaldır.
        standIn.answers["GET /teams/t1/blocks"] = {
            ok(JSONObject().put("blocked", JSONArray().put(JSONObject().put("userId", "u-9").put("displayName", "Engelli Kişi").put("blockedAt", "2026-11-22T10:00:00Z"))))
        }
        standIn.answers["DELETE /teams/t1/blocks/u-9"] = {
            standIn.answers["GET /teams/t1/blocks"] = { ok(JSONObject().put("blocked", JSONArray())) }
            MockResponse().setResponseCode(204)
        }
        tapText(text(R.string.blocked_title))
        waitFor("Engelli Kişi")
        compose.onNode(hasClickAction() and hasText(text(R.string.action_unblock))).performClick()
        waitFor(text(R.string.blocked_empty))
        assertEquals(1, count("DELETE /teams/t1/blocks/u-9"))
        compose.onAllNodesWithContentDescription(text(R.string.action_back)).onFirst().performClick()

        // Takımı Sil: the question, then the Teams tab without the team.
        standIn.answers["DELETE /teams/t1"] = {
            standIn.teams = JSONArray()
            MockResponse().setResponseCode(204)
        }
        waitFor(text(R.string.action_delete_team))
        compose.onNode(hasClickAction() and hasText(text(R.string.action_delete_team))).performScrollTo().performClick()
        waitFor(text(R.string.team_delete_body))
        compose.onAllNodes(hasClickAction() and hasText(text(R.string.action_delete_team))).onLast().performClick()
        waitFor(text(R.string.teams_empty_title))
        assertEquals(1, count("DELETE /teams/t1"))
        assertFalse(shown("Güney Spor"))
    }
}
