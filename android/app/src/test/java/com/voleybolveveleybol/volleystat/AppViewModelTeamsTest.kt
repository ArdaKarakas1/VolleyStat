package com.voleybolveveleybol.volleystat

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.voleybolveveleybol.volleystat.data.SeatsInvalidReason
import com.voleybolveveleybol.volleystat.data.TeamLimitScope
import com.voleybolveveleybol.volleystat.data.TeamProblem
import com.voleybolveveleybol.volleystat.model.Schedule
import com.voleybolveveleybol.volleystat.model.Seat
import com.voleybolveveleybol.volleystat.model.TeamDraft
import com.voleybolveveleybol.volleystat.ui.screens.resolve
import com.voleybolveveleybol.volleystat.ui.screens.seatsHint
import com.voleybolveveleybol.volleystat.ui.screens.teamProblemText
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The view model's server teams (slice B4, plan P6): the list and its badge, Takım Kur with its client id, the seat
 * change, the captain's joins-seen, the refusals, and the team state that belongs to one session (D1).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppViewModelTeamsTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private fun string(id: Int, vararg arguments: Any) = context.getString(id, *arguments)
    private val server = MockWebServer()

    /** Every request: "METHOD path" with a team id the phone chose written as {new}, its raw path, its Authorization header and its body. */
    private class Seen(val key: String, val path: String, val authorization: String?, val body: String)
    private val seen = CopyOnWriteArrayList<Seen>()
    private val script = ConcurrentHashMap<String, (RecordedRequest) -> MockResponse>()
    private val newId = Regex("/teams/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    private val accounts = mapOf("ali" to ("user-ali" to "Ali Yılmaz"), "veli" to ("user-veli" to "Veli Kaya"))

    @Before fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath.removePrefix("/v1")
                val key = "${request.method} ${path.replace(newId, "/teams/{new}")}"
                seen += Seen(key, path, request.getHeader("Authorization"), request.body.readUtf8())
                return script[key]?.invoke(request) ?: refuse(404, "NOT_FOUND")
            }
        }
        server.start()
        on("POST /auth/login") { signedIn(JSONObject(seen.last().body).getString("username")) }
        on("POST /auth/logout") { MockResponse().setResponseCode(204) }
    }

    @After fun stop() = server.shutdown()

    private fun on(request: String, answer: (RecordedRequest) -> MockResponse) { script[request] = answer }
    private fun ok(json: JSONObject, status: Int = 200) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(json.toString())
    private fun refuse(status: Int, code: String, details: JSONObject = JSONObject(), headers: Map<String, String> = emptyMap()) =
        MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json")
            .setBody(JSONObject().put("error", JSONObject().put("code", code).put("message", code).put("details", details)).toString())
            .apply { if (status == 401) setHeader("WWW-Authenticate", "Bearer"); headers.forEach { (k, v) -> setHeader(k, v) } }
    private fun lost() = MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)

    private fun signedIn(username: String): MockResponse {
        val (id, name) = accounts.getValue(username)
        val user = JSONObject().put("id", id).put("username", username).put("displayName", name).put("createdAt", "2026-11-17T10:00:00Z").put("passwordTemporary", false)
        return ok(JSONObject().put("user", user).put("session", JSONObject().put("token", "token-$username-${seen.size}").put("expiresAt", "2026-12-17T10:00:00Z")).put("deviceToken", "device-$username"))
    }

    private fun member(id: String, name: String, seat: Seat, joined: String, role: String = "MEMBER", jersey: Int? = null) = JSONObject().put("userId", id)
        .put("displayName", name).put("jersey", jersey ?: JSONObject.NULL).put("role", role).put("seat", seat.name).put("joinedAt", joined)

    private val captainRow = member("u-1", "Deniz Kaptan", Seat.STARTER, "2026-11-20T10:00:00Z", role = "CAPTAIN", jersey = 7)

    /** A team as server 0.10 sends it. The seat counts follow [members] and [substitutes]. */
    private fun team(
        id: String, name: String = "Kuzey Spor", role: String = "CAPTAIN", newJoins: Int? = 0,
        members: List<JSONObject> = listOf(captainRow), substitutes: Int = 2,
    ): JSONObject {
        fun count(seats: Int, seat: Seat): JSONObject {
            val taken = members.count { it.getString("seat") == seat.name }
            return JSONObject().put("seats", seats).put("taken", taken).put("left", seats - taken).put("deciding", 0)
        }
        return JSONObject().put("id", id).put("poolId", "pool-$id").put("name", name).put("tag", "K7Q2").put("schedule", JSONObject.NULL).put("logo", JSONObject.NULL)
            .put("myRole", role).put("mySeat", "STARTER").put("myJersey", JSONObject.NULL).put("newJoins", if (role == "CAPTAIN") newJoins ?: 0 else JSONObject.NULL)
            .put("members", JSONArray(members))
            .put("seats", JSONObject().put("starter", count(6, Seat.STARTER)).put("substitute", count(substitutes, Seat.SUBSTITUTE)).put("waiting", if (role == "CAPTAIN") 0 else JSONObject.NULL))
            .put("invite", JSONObject.NULL)
    }

    private fun teams(vararg list: JSONObject) = ok(JSONObject().put("teams", JSONArray(list.toList())))

    private fun viewModel() = AppViewModel(context).also { it.baseUrlOverride = server.url("/v1").toString() }

    private fun await(what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "timed out waiting for: $what" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    private fun settle() = repeat(30) {
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(10)
    }

    private fun AppViewModel.loginAndWait(username: String) {
        login(username, "uzun bir şifre 42")
        await("the login of $username") { !state.value.account.busy }
        assertEquals(username, state.value.account.user?.username)
    }

    private fun AppViewModel.logoutAndWait() {
        logout()
        await("the logout") { state.value.account.user == null && !state.value.account.busy }
    }

    private fun AppViewModel.loaded(): AppViewModel {
        loadTeams()
        await("the list") { !state.value.serverTeams.loading && (state.value.serverTeams.teams != null || state.value.serverTeams.loadProblem != null) }
        return this
    }

    private fun creations() = seen.filter { it.key == "PUT /teams/{new}" }.map { it.path.removePrefix("/teams/") }

    // ----- the list -----

    @Test fun loadTeamsSumsNewJoinsIntoTheBadge() {
        TestAccounts.seed(context)
        on("GET /teams") { teams(team("t1", newJoins = 2), team("t2", newJoins = 1), team("t3", role = "MEMBER")) }
        val vm = viewModel().loaded()
        val ui = vm.state.value.serverTeams
        assertEquals(listOf("t1", "t2", "t3"), ui.teams!!.map { it.id })
        assertEquals("the captain's new members of every team", 3, ui.badge)
        assertNull(ui.loadProblem)
        assertFalse(ui.loading)
        assertEquals("Bearer ${TestAccounts.SESSION.token}", seen.single().authorization)
    }

    @Test fun loadTeamsSendsNothingWithoutASession() {
        val vm = viewModel()
        vm.loadTeams()
        settle()
        assertTrue("a phone without a session asks the server nothing: ${seen.map { it.key }}", seen.isEmpty())
        assertEquals(TeamsUi(), vm.state.value.serverTeams)
    }

    @Test fun anotherAddressReadsTheTeamsAgainOnceAScreenAskedForThem() {
        TestAccounts.seed(context)
        val other = MockWebServer()
        val otherSeen = CopyOnWriteArrayList<String>()
        other.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                otherSeen += "${request.method} ${request.requestUrl!!.encodedPath.removePrefix("/v1")}"
                return teams(team("o1", name = "Öteki Takım"))
            }
        }
        other.start()
        try {
            on("GET /teams") { teams(team("t1")) }
            val vm = viewModel()
            settle()
            assertTrue("an address alone asks for no team", seen.isEmpty())
            vm.loaded()
            vm.baseUrlOverride = other.url("/v1").toString()
            await("the list of the other address") { vm.state.value.serverTeams.teams?.map { it.id } == listOf("o1") }
            assertEquals(listOf("GET /teams"), otherSeen.toList())
        } finally {
            other.shutdown()
        }
    }

    // ----- Takım Kur -----

    @Test fun aCreationWhoseAnswerWasLostIsRepeatedWithTheSameId() {
        TestAccounts.seed(context)
        var puts = 0
        on("PUT /teams/{new}") { request ->
            puts++
            val id = request.requestUrl!!.encodedPath.substringAfterLast('/')
            // The server creates the team on the first request; its answer never reaches the phone. The repeat finds it.
            if (puts == 1) lost() else ok(team(id, name = "Kuzey Spor"), if (puts == 2) 200 else 201)
        }
        on("GET /teams") { teams() }
        val vm = viewModel()
        val created = CopyOnWriteArrayList<String>()
        vm.createTeam(TeamDraft("Kuzey Spor")) { created += it }
        await("the lost answer") { !vm.state.value.serverTeams.busy && vm.state.value.serverTeams.problem != null }
        assertEquals(TeamProblem.Unreachable, vm.state.value.serverTeams.problem)
        assertTrue(created.isEmpty())

        vm.createTeam(TeamDraft("Kuzey Spor")) { created += it }
        await("the repeat") { created.isNotEmpty() }
        val ids = creations()
        assertEquals(2, ids.size)
        assertEquals("the repeat names the id of the lost request (design 9.6)", ids[0], ids[1])
        assertEquals(listOf(ids[0]), created.toList())
        assertNull(vm.state.value.serverTeams.problem)
        val sent = JSONObject(seen.first { it.key == "PUT /teams/{new}" }.body)
        assertEquals("Kuzey Spor", sent.getString("name"))
        assertEquals(2, sent.getInt("substituteSeats"))
        assertEquals("STARTER", sent.getString("captainSeat"))

        // The id is spent: the next team gets a new one.
        vm.createTeam(TeamDraft("Güney Spor")) { created += it }
        await("the next creation") { created.size == 2 }
        assertNotEquals(ids[0], creations().last())
        assertTrue("every creation ends with the list", seen.last().key == "GET /teams" || seen.any { it.key == "GET /teams" })
    }

    @Test fun aCreationRefusedForTheCaptainLimitShowsTheLimitTheServerNamed() {
        TestAccounts.seed(context)
        var limit = 3
        on("PUT /teams/{new}") { refuse(409, "TEAM_LIMIT_REACHED", JSONObject().put("scope", "CAPTAIN").put("limit", limit)) }
        on("GET /teams") { teams() }
        val vm = viewModel()
        vm.createTeam(TeamDraft("Kuzey Spor")) { error("refused") }
        await("the refusal") { vm.state.value.serverTeams.problem != null && !vm.state.value.serverTeams.busy }
        assertEquals(TeamProblem.Limit(TeamLimitScope.CAPTAIN, 3), vm.state.value.serverTeams.problem)
        assertEquals(string(R.string.team_limit_captain, 3), teamProblemText(vm.state.value.serverTeams.problem!!).resolve(context))

        // The number is the server's: another limit reads as that limit.
        limit = 5
        vm.clearTeamProblem()
        vm.createTeam(TeamDraft("Kuzey Spor")) { error("refused") }
        await("the second refusal") { vm.state.value.serverTeams.problem != null && !vm.state.value.serverTeams.busy }
        val shown = teamProblemText(vm.state.value.serverTeams.problem!!).resolve(context)
        assertTrue(shown, shown.startsWith("En çok 5 takımın kaptanı olabilirsin."))
        // A member limit has its own text.
        assertEquals(string(R.string.team_limit_member, 20), teamProblemText(TeamProblem.Limit(TeamLimitScope.MEMBER, 20)).resolve(context))
    }

    @Test fun aCreationRefusedForTheDailyLimitShowsTheServersWait() {
        TestAccounts.seed(context)
        on("PUT /teams/{new}") { refuse(429, "TOO_MANY_ATTEMPTS", JSONObject().put("scope", "TEAMS").put("retryAfterSeconds", 5400), mapOf("Retry-After" to "5400")) }
        on("GET /teams") { teams() }
        val vm = viewModel()
        vm.createTeam(TeamDraft("Kuzey Spor")) { error("refused") }
        await("the refusal") { vm.state.value.serverTeams.problem != null && !vm.state.value.serverTeams.busy }
        assertEquals(TeamProblem.TooMany(5400), vm.state.value.serverTeams.problem)
        assertEquals(string(R.string.team_wait_create, "90 dakika"), teamProblemText(TeamProblem.TooMany(5400)).resolve(context))
    }

    // ----- the seats -----

    private val seatsTeam = listOf(
        captainRow,
        member("u-2", "Ece", Seat.STARTER, "2026-11-20T11:00:00Z"),
        member("u-3", "Fatma", Seat.SUBSTITUTE, "2026-11-20T12:00:00Z"),
        member("u-4", "Gül", Seat.SUBSTITUTE, "2026-11-20T13:00:00Z"),
    )

    @Test fun aRefusedSeatChangeKeepsTheInput() {
        TestAccounts.seed(context)
        on("GET /teams") { teams(team("t1", members = seatsTeam)) }
        on("PUT /teams/t1/seats") { refuse(409, "SEATS_BELOW_HOLDERS", JSONObject().put("seat", "SUBSTITUTE").put("holders", 3).put("seats", 2)) }
        val vm = viewModel().loaded()
        val before = vm.state.value.serverTeams.teams
        var done = 0
        vm.saveSeats("t1", 2, mapOf("u-2" to Seat.SUBSTITUTE)) { done++ }
        await("the refusal") { vm.state.value.serverTeams.problem != null && !vm.state.value.serverTeams.busy }
        val sent = JSONObject(seen.single { it.key == "PUT /teams/t1/seats" }.body)
        assertEquals(2, sent.getInt("substituteSeats"))
        assertEquals("u-2", sent.getJSONArray("moves").getJSONObject(0).getString("userId"))
        assertEquals("SUBSTITUTE", sent.getJSONArray("moves").getJSONObject(0).getString("seat"))
        assertEquals("the screen stays open with its input", 0, done)
        assertEquals(TeamProblem.SeatsBelowHolders(Seat.SUBSTITUTE, 3, 2, movedStarterDown = true), vm.state.value.serverTeams.problem)
        assertEquals("the team the server holds is unchanged", before, vm.state.value.serverTeams.teams)

        // The server takes the change: the team follows its answer and the screen closes.
        on("PUT /teams/t1/seats") { ok(team("t1", members = seatsTeam, substitutes = 3)) }
        on("GET /teams") { teams(team("t1", members = seatsTeam, substitutes = 3)) }
        vm.saveSeats("t1", 3, mapOf("u-2" to Seat.SUBSTITUTE)) { done++ }
        await("the change") { done == 1 }
        assertNull(vm.state.value.serverTeams.problem)
        assertEquals(3, vm.state.value.serverTeams.teams!!.single().seats.substitute.seats)
    }

    @Test fun theSeatHintFollowsS8sRule() {
        TestAccounts.seed(context)
        on("GET /teams") { teams(team("t1", members = seatsTeam)) }
        var answer = refuse(409, "SEATS_BELOW_HOLDERS", JSONObject().put("seat", "SUBSTITUTE").put("holders", 3).put("seats", 2))
        on("PUT /teams/t1/seats") { answer }
        val vm = viewModel().loaded()
        fun refusal(substitutes: Int, moves: Map<String, Seat>): TeamProblem {
            vm.clearTeamProblem()
            vm.saveSeats("t1", substitutes, moves) { error("refused") }
            await("the refusal") { vm.state.value.serverTeams.problem != null && !vm.state.value.serverTeams.busy }
            return vm.state.value.serverTeams.problem!!
        }
        // A starter moved down, and the substitutes it needs fit the bound of 6: raise the count or swap.
        assertEquals(R.string.seats_raise_or_swap_hint, seatsHint(refusal(2, mapOf("u-2" to Seat.SUBSTITUTE))))
        // Above the bound only a swap helps.
        answer = refuse(409, "SEATS_BELOW_HOLDERS", JSONObject().put("seat", "SUBSTITUTE").put("holders", 7).put("seats", 6))
        assertEquals(R.string.seats_swap_hint, seatsHint(refusal(6, mapOf("u-2" to Seat.SUBSTITUTE))))
        // A lower count without a starter moved down: no hint.
        answer = refuse(409, "SEATS_BELOW_HOLDERS", JSONObject().put("seat", "SUBSTITUTE").put("holders", 2).put("seats", 1))
        assertNull(seatsHint(refusal(1, emptyMap())))
        // A substitute named as a substitute is no move of a starter.
        assertNull(seatsHint(refusal(1, mapOf("u-3" to Seat.SUBSTITUTE))))
        // A starter refusal has its own advice in its text and no hint.
        answer = refuse(409, "SEATS_BELOW_HOLDERS", JSONObject().put("seat", "STARTER").put("holders", 7).put("seats", 6))
        val starter = refusal(2, mapOf("u-3" to Seat.STARTER))
        assertNull(seatsHint(starter))
        assertEquals(string(R.string.seats_below_starter, 7, 6), teamProblemText(starter).resolve(context))
        assertEquals(string(R.string.seats_below_substitute, 3, 2), teamProblemText(TeamProblem.SeatsBelowHolders(Seat.SUBSTITUTE, 3, 2)).resolve(context))
        assertEquals(string(R.string.seats_invalid), teamProblemText(TeamProblem.SeatsInvalid(SeatsInvalidReason.SUBSTITUTES_RANGE)).resolve(context))
        assertEquals(string(R.string.seats_invalid_captain), teamProblemText(TeamProblem.SeatsInvalid(SeatsInvalidReason.CAPTAIN_SEAT)).resolve(context))
    }

    @Test fun theSettingsGoToTheServerAndTheListFollows() {
        TestAccounts.seed(context)
        on("GET /teams") { teams(team("t1")) }
        on("PUT /teams/t1/settings") { ok(team("t1", name = "Güney Spor")) }
        val vm = viewModel().loaded()
        var done = 0
        vm.saveTeamSettings("t1", "Güney Spor", Schedule(5, "19:30")) { done++ }
        await("the settings") { done == 1 }
        val sent = JSONObject(seen.single { it.key == "PUT /teams/t1/settings" }.body)
        assertEquals("Güney Spor", sent.getString("name"))
        assertEquals(5, sent.getJSONObject("schedule").getInt("weekday"))
        assertEquals("Güney Spor", vm.state.value.serverTeams.teams!!.single().name)
        await("the list after the action") { seen.last().key == "GET /teams" }
    }

    // ----- one team -----

    @Test fun aTeamThatAnswersNotAMemberLeavesTheList() {
        TestAccounts.seed(context)
        on("GET /teams") { teams(team("t1"), team("t2", name = "Güney Spor")) }
        on("GET /teams/t1") { refuse(403, "NOT_A_MEMBER") }
        val vm = viewModel().loaded()
        vm.openTeam("t1")
        await("the refusal") { vm.state.value.serverTeams.loadProblem != null }
        assertEquals(TeamProblem.NoAccess, vm.state.value.serverTeams.loadProblem)
        assertEquals(listOf("t2"), vm.state.value.serverTeams.teams!!.map { it.id })
        assertEquals(string(R.string.team_no_access), teamProblemText(TeamProblem.NoAccess).resolve(context))
    }

    @Test fun aTeamThatAnswers404LeavesTheList() {
        TestAccounts.seed(context)
        on("GET /teams") { teams(team("t1"), team("t2", name = "Güney Spor")) }
        on("GET /teams/t1") { refuse(404, "TEAM_NOT_FOUND") }
        val vm = viewModel().loaded()
        vm.openTeam("t1")
        await("the refusal") { vm.state.value.serverTeams.loadProblem != null }
        assertEquals(TeamProblem.Gone, vm.state.value.serverTeams.loadProblem)
        assertEquals(listOf("t2"), vm.state.value.serverTeams.teams!!.map { it.id })
        assertEquals(string(R.string.team_gone), teamProblemText(TeamProblem.Gone).resolve(context))

        // An action on a team the server no longer holds takes it off the list too.
        on("PUT /teams/t2/seats") { refuse(404, "TEAM_NOT_FOUND") }
        on("GET /teams") { teams() }
        vm.saveSeats("t2", 2, emptyMap()) { error("refused") }
        await("the second refusal") { vm.state.value.serverTeams.problem != null && !vm.state.value.serverTeams.busy }
        assertEquals(TeamProblem.Gone, vm.state.value.serverTeams.problem)
        await("the list without it") { vm.state.value.serverTeams.teams?.isEmpty() == true }
    }

    @Test fun openingATeamAsCaptainWithNewJoinsMarksThemSeen() {
        TestAccounts.seed(context)
        val joined = listOf(
            captainRow,
            member("u-2", "Ece", Seat.STARTER, "2026-11-21T12:00:00Z"),
            member("u-3", "Fatma", Seat.SUBSTITUTE, "2026-11-21T11:00:00Z"),
        )
        on("GET /teams/t1") { ok(team("t1", newJoins = 2, members = joined)) }
        on("POST /teams/t1/joins-seen") { MockResponse().setResponseCode(204) }
        on("GET /teams") { teams(team("t1", newJoins = 0, members = joined)) }
        val vm = viewModel()
        vm.openTeam("t1")
        await("the list after the mark") { seen.any { it.key == "GET /teams" } && !vm.state.value.serverTeams.loading }
        val marks = seen.filter { it.key == "POST /teams/t1/joins-seen" }
        assertEquals(1, marks.size)
        assertEquals("the newest join the screen showed", "2026-11-21T12:00:00Z", JSONObject(marks.single().body).getString("upTo"))
        assertTrue("the mark comes after the team was read", seen.indexOfFirst { it.key == "GET /teams/t1" } < seen.indexOfFirst { it.key == "POST /teams/t1/joins-seen" })
        assertEquals(0, vm.state.value.serverTeams.badge)
    }

    @Test fun aMemberOpeningATeamSendsNoJoinsSeenAndSeesNoProblem() {
        TestAccounts.seed(context)
        // The server sends a member no newJoins; the rule is the role's all the same (plan D4), so a member's answer that named
        // new members would send nothing either.
        on("GET /teams/t1") { ok(team("t1", role = "MEMBER").put("newJoins", 1)) }
        on("GET /teams/t2") { ok(team("t2", newJoins = 0)) }
        on("POST /teams/t1/joins-seen") { refuse(403, "CAPTAIN_ONLY") }
        on("POST /teams/t2/joins-seen") { MockResponse().setResponseCode(204) }
        val vm = viewModel()
        vm.openTeam("t1")
        await("the member's team") { vm.state.value.serverTeams.teamOf("t1") != null }
        // A captain whose team has no new member sends none either.
        vm.openTeam("t2")
        await("the captain's team") { vm.state.value.serverTeams.teamOf("t2") != null }
        settle()
        assertTrue(seen.map { it.key }.toString(), seen.none { it.key.endsWith("/joins-seen") })
        assertNull(vm.state.value.serverTeams.problem)
        assertNull(vm.state.value.serverTeams.loadProblem)
    }

    // ----- the session (D1) -----

    @Test fun anEndedSessionDuringATeamCallLeadsToTheLogin() {
        TestAccounts.seed(context)
        on("GET /teams") { refuse(401, "AUTH_REQUIRED") }
        val vm = viewModel()
        vm.loadTeams()
        await("the end of the session") { vm.state.value.account.user == null }
        assertEquals(Entry.LOGIN, vm.state.value.entry)
        assertTrue(vm.state.value.account.sessionEnded)
        assertEquals(TeamsUi(), vm.state.value.serverTeams)
    }

    /** A logs in, reads a team with a new member and leaves a creation whose answer was lost. */
    private fun firstAccountWithATeam(): Pair<AppViewModel, String> {
        on("GET /teams") { request ->
            if (request.getHeader("Authorization")!!.contains("ali")) {
                teams(team("t1", newJoins = 1, members = listOf(captainRow, member("u-2", "Ece Yeni", Seat.SUBSTITUTE, "2026-11-21T12:00:00Z"))))
            } else {
                refuse(500, "INTERNAL")
            }
        }
        on("PUT /teams/{new}") { lost() }
        val vm = viewModel()
        vm.loginAndWait("ali")
        vm.loaded()
        assertEquals(1, vm.state.value.serverTeams.badge)
        vm.createTeam(TeamDraft("Batı Spor")) { error("lost") }
        await("the lost creation") { vm.state.value.serverTeams.problem == TeamProblem.Unreachable && !vm.state.value.serverTeams.busy }
        return vm to creations().single()
    }

    /** B is signed in now: nothing of A's teams is left, and B's first creation takes a new id. */
    private fun assertNothingOfTheFirstAccount(vm: AppViewModel, firstId: String) {
        vm.loaded()
        val ui = vm.state.value.serverTeams
        assertNull("B's list failed to load and shows no team", ui.teams)
        assertEquals(0, ui.badge)
        val everything = vm.state.value.toString()
        listOf("Kuzey Spor", "Deniz Kaptan", "Ece Yeni", "K7Q2", "pool-t1").forEach { assertFalse("$it is left in B's state", everything.contains(it)) }
        on("PUT /teams/{new}") { request -> ok(team(request.requestUrl!!.encodedPath.substringAfterLast('/'), name = "Veli Spor"), 201) }
        val created = CopyOnWriteArrayList<String>()
        vm.createTeam(TeamDraft("Veli Spor")) { created += it }
        await("B's creation") { created.isNotEmpty() }
        assertNotEquals("B's creation never repeats A's pending id", firstId, created.single())
        assertEquals("Bearer token-veli", seen.last { it.key == "PUT /teams/{new}" }.authorization!!.substringBeforeLast('-'))
    }

    @Test fun afterALogoutAnotherAccountSeesNoTeamOfTheFirst() {
        val (vm, firstId) = firstAccountWithATeam()
        vm.logoutAndWait()
        assertEquals(TeamsUi(), vm.state.value.serverTeams)
        vm.loginAndWait("veli")
        assertNothingOfTheFirstAccount(vm, firstId)
    }

    @Test fun anEndedSessionLeavesNoTeamOfTheAccount() {
        val (vm, firstId) = firstAccountWithATeam()
        on("GET /teams") { request -> if (request.getHeader("Authorization")!!.contains("ali")) refuse(401, "AUTH_REQUIRED") else refuse(500, "INTERNAL") }
        vm.loadTeams()
        await("the end of the session") { vm.state.value.account.user == null }
        assertEquals(TeamsUi(), vm.state.value.serverTeams)
        vm.loginAndWait("veli")
        assertNothingOfTheFirstAccount(vm, firstId)
    }

    @Test fun aDeletedAccountLeavesNoTeamBehind() {
        val (vm, firstId) = firstAccountWithATeam()
        on("POST /me/delete") { MockResponse().setResponseCode(204) }
        vm.deleteAccount("uzun bir şifre 42")
        await("the deletion") { vm.state.value.account.user == null && !vm.state.value.account.busy }
        assertEquals(TeamsUi(), vm.state.value.serverTeams)
        vm.loginAndWait("veli")
        assertNothingOfTheFirstAccount(vm, firstId)
    }

    @Test fun aTeamAnswerOfAnEarlierSessionIsDropped() {
        val held = CountDownLatch(1)
        val answered = AtomicBoolean(false)
        on("GET /teams") { request ->
            if (request.getHeader("Authorization")!!.contains("ali")) {
                held.await(15, TimeUnit.SECONDS)
                answered.set(true)
                teams(team("t1", newJoins = 1))
            } else {
                refuse(500, "INTERNAL")
            }
        }
        val vm = viewModel()
        vm.loginAndWait("ali")
        vm.loadTeams()
        await("A's request at the server") { seen.any { it.key == "GET /teams" } }
        vm.logoutAndWait()
        vm.loginAndWait("veli")
        held.countDown()
        await("A's answer") { answered.get() }
        repeat(3) { settle() }
        assertEquals("A's answer reaches nothing of B", TeamsUi(), vm.state.value.serverTeams)
    }
}
