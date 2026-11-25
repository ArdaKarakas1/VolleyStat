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
import com.voleybolveveleybol.volleystat.model.TeamRules
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
import org.robolectric.annotation.GraphicsMode
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
@GraphicsMode(GraphicsMode.Mode.NATIVE)
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
        members: List<JSONObject> = listOf(captainRow), substitutes: Int = 2, logo: Int? = null,
    ): JSONObject {
        fun count(seats: Int, seat: Seat): JSONObject {
            val taken = members.count { it.getString("seat") == seat.name }
            return JSONObject().put("seats", seats).put("taken", taken).put("left", seats - taken).put("deciding", 0)
        }
        return JSONObject().put("id", id).put("poolId", "pool-$id").put("name", name).put("tag", "K7Q2").put("schedule", JSONObject.NULL)
            .put("logo", if (logo == null) JSONObject.NULL else JSONObject().put("version", logo))
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

    /**
     * A read of the list replaces the one on its way (P6 checker, M6): a slow read that answers after a newer one has
     * answered must not put its older list back.
     */
    @Test fun aSlowReadOfTheListThatAnswersAfterANewerOneIsDropped() {
        TestAccounts.seed(context)
        val held = CountDownLatch(1)
        val first = AtomicBoolean(true)
        val slowAnswered = AtomicBoolean(false)
        on("GET /teams") {
            if (first.getAndSet(false)) {
                held.await(15, TimeUnit.SECONDS)
                slowAnswered.set(true)
                teams(team("t-old", name = "Eski Liste"))
            } else {
                teams(team("t-new", name = "Yeni Liste"))
            }
        }
        val vm = viewModel()
        vm.loadTeams()
        await("the slow read at the server") { seen.count { it.key == "GET /teams" } == 1 }
        vm.loadTeams()
        await("the newer list") { vm.state.value.serverTeams.teams?.map { it.id } == listOf("t-new") }
        held.countDown()
        await("the slow answer") { slowAnswered.get() }
        repeat(3) { settle() }
        assertEquals("the newer list stays", listOf("t-new"), vm.state.value.serverTeams.teams?.map { it.id })
        assertFalse(vm.state.value.serverTeams.loading)
    }

    /**
     * A read of the list that left before a team screen's read answered carries the team as it was before that read:
     * its late answer must not put the older team back (found by TeamFlowTest, whose list read on the Teams tab raced the
     * team screen's read).
     */
    @Test fun aListReadThatLeftBeforeATeamReadKeepsTheNewerTeam() {
        TestAccounts.seed(context)
        val held = CountDownLatch(1)
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val slowAnswered = AtomicBoolean(false)
        on("GET /teams") {
            if (reads.incrementAndGet() == 2) {
                held.await(15, TimeUnit.SECONDS)
                slowAnswered.set(true)
            }
            teams(team("t1"))
        }
        val ece = member("u-2", "Ece Yeni", Seat.STARTER, "2026-11-25T10:00:00Z")
        on("GET /teams/t1") { ok(team("t1", members = listOf(captainRow, ece))) }
        val vm = viewModel().loaded()
        vm.loadTeams()
        await("the slow read at the server") { reads.get() == 2 }
        vm.openTeam("t1", "screen-1")
        await("the team read") { vm.state.value.serverTeams.opened?.team?.members?.size == 2 }
        assertEquals(2, vm.state.value.serverTeams.teamOf("t1")!!.members.size)
        held.countDown()
        await("the slow answer") { slowAnswered.get() }
        await("the list answered") { !vm.state.value.serverTeams.loading }
        repeat(3) { settle() }
        assertEquals("the team screen shows the newer read", listOf("Deniz Kaptan", "Ece Yeni"), vm.state.value.serverTeams.teamOf("t1")!!.members.map { it.displayName })
        // A list read that leaves after the team read takes the server's word again.
        vm.loaded()
        assertEquals(1, vm.state.value.serverTeams.teamOf("t1")!!.members.size)
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

    /**
     * A server that makes a team under each new id and answers a repeated id with the team it made first (design 9.6). The
     * first creation's answer is lost after the server made the team. The list answers with every team it made.
     */
    private fun serverThatLosesTheFirstCreation(listFails: Boolean = false): ConcurrentHashMap<String, String> {
        val made = ConcurrentHashMap<String, String>()
        val lose = AtomicBoolean(true)
        on("PUT /teams/{new}") { request ->
            val path = request.requestUrl!!.encodedPath.removePrefix("/v1")
            val id = path.substringAfterLast('/')
            val first = made.putIfAbsent(id, JSONObject(seen.last { it.path == path }.body).getString("name")) == null
            if (lose.getAndSet(false)) lost() else ok(team(id, name = made.getValue(id)), if (first) 201 else 200)
        }
        on("GET /teams") { if (listFails) refuse(500, "INTERNAL") else teams(*made.map { (id, name) -> team(id, name = name) }.toTypedArray()) }
        return made
    }

    private fun AppViewModel.createAndLose(draft: TeamDraft) {
        createTeam(draft) { error("the answer was lost") }
        await("the lost answer") { !state.value.serverTeams.busy && state.value.serverTeams.problem == TeamProblem.Unreachable }
        clearTeamProblem()
    }

    @Test fun anotherTeamAfterALostCreationTheListShowedTakesANewId() {
        TestAccounts.seed(context)
        val made = serverThatLosesTheFirstCreation()
        val vm = viewModel()
        vm.createAndLose(TeamDraft("Kuzey Spor"))
        // The action's own read of the list shows the team the lost request made: the server confirmed it.
        await("the list with the lost creation's team") { vm.state.value.serverTeams.teams?.map { it.name } == listOf("Kuzey Spor") }

        val opened = CopyOnWriteArrayList<String>()
        vm.createTeam(TeamDraft("Güney Spor")) { opened += it }
        await("the second creation") { opened.isNotEmpty() || vm.state.value.serverTeams.problem != null }
        val ids = creations()
        assertEquals(2, ids.size)
        assertNotEquals("another team never repeats the id of a team the server showed", ids[0], ids[1])
        assertEquals(setOf("Kuzey Spor", "Güney Spor"), made.values.toSet())
        assertEquals("the screen opens the new team", listOf(ids[1]), opened.toList())
    }

    @Test fun theLostDraftRepeatedAfterTheListShowedItsTeamGetsThatTeam() {
        TestAccounts.seed(context)
        val made = serverThatLosesTheFirstCreation()
        val vm = viewModel()
        vm.createAndLose(TeamDraft("Kuzey Spor"))
        await("the list with the lost creation's team") { vm.state.value.serverTeams.teams?.map { it.name } == listOf("Kuzey Spor") }

        // The user taps Takım Kur again on the same form; a space typed after the name changes nothing the server keeps.
        val opened = CopyOnWriteArrayList<String>()
        vm.createTeam(TeamDraft("Kuzey Spor ")) { opened += it }
        await("the repeat") { opened.isNotEmpty() || vm.state.value.serverTeams.problem != null }
        val ids = creations()
        assertEquals("the repeat names the lost request's id (design 9.6)", ids[0], ids[1])
        assertEquals(listOf("Kuzey Spor"), made.values.toList())
        assertEquals(listOf(ids[0]), opened.toList())
    }

    @Test fun whileNoReadShowedTheLostCreationAnotherDraftRepeatsItsId() {
        TestAccounts.seed(context)
        val made = serverThatLosesTheFirstCreation(listFails = true)
        val vm = viewModel()
        vm.createAndLose(TeamDraft("Kuzey Spor"))
        await("the failed read of the list") { vm.state.value.serverTeams.loadProblem != null && !vm.state.value.serverTeams.loading }

        // The app does not know whether the lost request made a team. The id goes again, so the server answers with that
        // team if it made one, and a second team never comes of it.
        val opened = CopyOnWriteArrayList<String>()
        vm.createTeam(TeamDraft("Kuzey Spor Kulübü")) { opened += it }
        await("the repeat") { opened.isNotEmpty() || vm.state.value.serverTeams.problem != null }
        val ids = creations()
        assertEquals(ids[0], ids[1])
        assertEquals(listOf("Kuzey Spor"), made.values.toList())
        assertEquals(listOf(ids[0]), opened.toList())
    }

    @Test fun anIdAnotherTeamHoldsIsNotSentAgain() {
        TestAccounts.seed(context)
        val taken = AtomicBoolean(true)
        on("PUT /teams/{new}") { request ->
            if (taken.getAndSet(false)) refuse(409, "TEAM_ID_TAKEN") else ok(team(request.requestUrl!!.encodedPath.substringAfterLast('/')), 201)
        }
        on("GET /teams") { teams() }
        val vm = viewModel()
        vm.createTeam(TeamDraft("Kuzey Spor")) { error("refused") }
        await("the refusal") { !vm.state.value.serverTeams.busy && vm.state.value.serverTeams.problem == TeamProblem.IdTaken }
        vm.clearTeamProblem()
        val opened = CopyOnWriteArrayList<String>()
        vm.createTeam(TeamDraft("Kuzey Spor")) { opened += it }
        await("the second try") { opened.isNotEmpty() }
        val ids = creations()
        assertNotEquals("the next try takes a new id", ids[0], ids[1])
        assertEquals(listOf(ids[1]), opened.toList())
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

    /**
     * S8's bound itself (P6 checker, M4): 6 substitutes after the change still fit the 6 substitute seats a team can have,
     * so a higher count still helps; 7 do not.
     */
    @Test fun theSeatHintAtExactlySixSubstitutesStillOffersAHigherCount() {
        val atTheBound = TeamProblem.SeatsBelowHolders(Seat.SUBSTITUTE, TeamRules.SUBSTITUTES_MAX, TeamRules.SUBSTITUTES_MAX - 1, movedStarterDown = true)
        assertEquals(R.string.seats_raise_or_swap_hint, seatsHint(atTheBound))
        val aboveTheBound = TeamProblem.SeatsBelowHolders(Seat.SUBSTITUTE, TeamRules.SUBSTITUTES_MAX + 1, TeamRules.SUBSTITUTES_MAX, movedStarterDown = true)
        assertEquals(R.string.seats_swap_hint, seatsHint(aboveTheBound))
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
        // The team screen's read keeps its own refusal; the list's problem is the list's (P6-C1).
        await("the refusal") { vm.state.value.serverTeams.opened?.problem != null }
        assertEquals(TeamProblem.NoAccess, vm.state.value.serverTeams.opened?.problem)
        assertNull(vm.state.value.serverTeams.loadProblem)
        assertEquals(listOf("t2"), vm.state.value.serverTeams.teams!!.map { it.id })
        assertEquals(string(R.string.team_no_access), teamProblemText(TeamProblem.NoAccess).resolve(context))
    }

    @Test fun aTeamThatAnswers404LeavesTheList() {
        TestAccounts.seed(context)
        on("GET /teams") { teams(team("t1"), team("t2", name = "Güney Spor")) }
        on("GET /teams/t1") { refuse(404, "TEAM_NOT_FOUND") }
        val vm = viewModel().loaded()
        vm.openTeam("t1")
        await("the refusal") { vm.state.value.serverTeams.opened?.problem != null }
        assertEquals(TeamProblem.Gone, vm.state.value.serverTeams.opened?.problem)
        assertNull(vm.state.value.serverTeams.loadProblem)
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
        assertNull(vm.state.value.serverTeams.opened?.problem)
    }

    @Test fun aRefusedTeamKeepsItsRefusalWhenTheListIsReadAgain() {
        TestAccounts.seed(context)
        on("GET /teams") { teams(team("t1"), team("t2", name = "Güney Spor")) }
        on("GET /teams/t1") { refuse(404, "TEAM_NOT_FOUND") }
        val vm = viewModel().loaded()
        vm.openTeam("t1", "screen-1")
        await("the refusal") { vm.state.value.serverTeams.opened?.problem != null }
        // A resume reads the list, which answers without the team: the screen still knows why it shows none (P6-C1).
        on("GET /teams") { teams(team("t2", name = "Güney Spor")) }
        vm.loaded()
        var ui = vm.state.value.serverTeams
        assertNull(ui.loadProblem)
        assertEquals(TeamProblem.Gone, ui.opened?.problem)
        assertNull(ui.teamOf("t1"))
        // A failed read of the list leaves it alone too.
        on("GET /teams") { refuse(500, "INTERNAL") }
        vm.loaded()
        ui = vm.state.value.serverTeams
        assertEquals(TeamProblem.Failed(500, "INTERNAL"), ui.loadProblem)
        assertEquals(TeamProblem.Gone, ui.opened?.problem)
        // Only the screen's own next read clears it.
        vm.openTeam("t1", "screen-1")
        assertNull(vm.state.value.serverTeams.opened?.problem)
        await("the refusal again") { vm.state.value.serverTeams.opened?.problem != null }
    }

    @Test fun aLateRefusalOfAnEarlierTeamScreenStaysOffTheNextOne() {
        TestAccounts.seed(context)
        val heldA = CountDownLatch(1)
        on("GET /teams") { teams(team("a", name = "Alfa Spor"), team("b", name = "Beta Spor")) }
        on("GET /teams/a") { heldA.await(10, TimeUnit.SECONDS); refuse(404, "TEAM_NOT_FOUND") }
        on("GET /teams/b") { ok(team("b", name = "Beta Spor")) }
        val vm = viewModel().loaded()
        vm.openTeam("a", "screen-a")
        await("Alfa's request") { seen.any { it.key == "GET /teams/a" } }
        // The user leaves Alfa's screen before the answer and opens Beta's, which answers at once.
        vm.openTeam("b", "screen-b")
        await("Beta's answer") { vm.state.value.serverTeams.opened?.recentJoins != null }
        heldA.countDown()
        // Alfa's refusal still takes Alfa off the list (P6-C2).
        await("Alfa's answer") { vm.state.value.serverTeams.teams?.map { it.id } == listOf("b") }
        val read = vm.state.value.serverTeams.opened!!
        assertEquals("screen-b", read.screen)
        assertNull("Beta's screen shows no refusal of Alfa", read.problem)
        assertEquals("Beta Spor", vm.state.value.serverTeams.teamOf("b")?.name)
    }

    @Test fun aLateAnswerOfAnEarlierTeamScreenLeavesTheNextOnesTeam() {
        TestAccounts.seed(context)
        val heldA = CountDownLatch(1)
        val answeredA = AtomicBoolean(false)
        on("GET /teams/a") { heldA.await(10, TimeUnit.SECONDS); answeredA.set(true); ok(team("a", name = "Alfa Spor")) }
        on("GET /teams/b") { ok(team("b", name = "Beta Spor")) }
        // The list is unread: the screens have only their own reads.
        val vm = viewModel()
        vm.openTeam("a", "screen-a")
        await("Alfa's request") { seen.any { it.key == "GET /teams/a" } }
        vm.openTeam("b", "screen-b")
        await("Beta's answer") { vm.state.value.serverTeams.teamOf("b") != null }
        heldA.countDown()
        await("Alfa's answer at the server") { answeredA.get() }
        repeat(3) { settle() }
        val ui = vm.state.value.serverTeams
        assertEquals("screen-b", ui.opened?.screen)
        assertEquals("Beta's screen keeps its team", "Beta Spor", ui.teamOf("b")?.name)
        assertNull(ui.opened?.problem)
    }

    @Test fun anEarlierReadOfTheSameScreenThatAnswersLateChangesNothing() {
        TestAccounts.seed(context)
        val heldFirst = CountDownLatch(1)
        val firstAnswered = AtomicBoolean(false)
        val reads = java.util.concurrent.atomic.AtomicInteger()
        on("GET /teams/t1") {
            if (reads.incrementAndGet() > 1) {
                ok(team("t1"))
            } else {
                heldFirst.await(10, TimeUnit.SECONDS)
                firstAnswered.set(true)
                refuse(500, "INTERNAL")
            }
        }
        val vm = viewModel()
        vm.openTeam("t1", "screen-1")
        await("the first read at the server") { reads.get() == 1 }
        // The same screen reads again (a rotation), and the second read answers first.
        vm.openTeam("t1", "screen-1")
        await("the second answer") { vm.state.value.serverTeams.opened?.team != null }
        heldFirst.countDown()
        await("the first answer at the server") { firstAnswered.get() }
        repeat(3) { settle() }
        assertNull("the screen shows no failure of a read the next one replaced", vm.state.value.serverTeams.opened?.problem)
        assertEquals("Kuzey Spor", vm.state.value.serverTeams.teamOf("t1")?.name)
    }

    @Test fun theScreenCountsTheNewMembersItMarksSeenAndNoOthers() {
        TestAccounts.seed(context)
        val ece = member("u-2", "Ece", Seat.STARTER, "2026-11-21T12:00:00Z")
        val fatma = member("u-3", "Fatma", Seat.SUBSTITUTE, "2026-11-22T12:00:00Z")
        fun marks() = seen.filter { it.key == "POST /teams/t1/joins-seen" }
        // The list the screen opens with names no new member. Ece joined after that read: the team's own read names her.
        on("GET /teams") { teams(team("t1", newJoins = 0)) }
        on("GET /teams/t1") { ok(team("t1", newJoins = 1, members = listOf(captainRow, ece))) }
        on("POST /teams/t1/joins-seen") {
            on("GET /teams") { teams(team("t1", newJoins = 0, members = listOf(captainRow, ece))) }
            MockResponse().setResponseCode(204)
        }
        val vm = viewModel().loaded()
        vm.openTeam("t1", "screen-1")
        await("the mark and the list after it") {
            marks().size == 1 && seen.indexOfLast { it.key == "GET /teams" } > seen.indexOf(marks().single()) && !vm.state.value.serverTeams.loading
        }
        assertEquals("the screen counts the member the app marks seen (P6-C3)", 1, vm.state.value.serverTeams.opened?.recentJoins)
        assertEquals("2026-11-21T12:00:00Z", JSONObject(marks().single().body).getString("upTo"))
        assertEquals(0, vm.state.value.serverTeams.badge)

        // Fatma joins, and the same screen reads again (a rotation, a return from As ve Yedek Yerleri): the count it showed
        // stays, and Fatma, whom it does not count, stays new.
        on("GET /teams/t1") { ok(team("t1", newJoins = 1, members = listOf(captainRow, ece, fatma))) }
        vm.openTeam("t1", "screen-1")
        await("the second answer") { vm.state.value.serverTeams.opened?.team?.members?.size == 3 }
        settle()
        assertEquals(1, vm.state.value.serverTeams.opened?.recentJoins)
        assertEquals("no mark for a member the screen did not count", 1, marks().size)

        // Another visit of the team counts her and marks her seen.
        vm.openTeam("t1", "screen-2")
        await("the second mark") { marks().size == 2 }
        assertEquals("2026-11-22T12:00:00Z", JSONObject(marks().last().body).getString("upTo"))
        assertEquals("screen-2", vm.state.value.serverTeams.opened?.screen)
        assertEquals(1, vm.state.value.serverTeams.opened?.recentJoins)
    }

    // ----- P7: the jersey, the members, Çıkarılanlar, the transfer, leaving, deletion and the logo -----

    private val ece = member("u-2", "Ece Yeni", Seat.SUBSTITUTE, "2026-11-21T12:00:00Z", jersey = 12)
    private val gul = member("u-3", "Gül Ak", Seat.STARTER, "2026-11-21T13:00:00Z")

    private fun blocked(vararg rows: Pair<String, String>) =
        ok(JSONObject().put("blocked", JSONArray(rows.map { (id, name) -> JSONObject().put("userId", id).put("displayName", name).put("blockedAt", "2026-11-23T10:00:00Z") })))

    private fun AppViewModel.idle() = await("the team action") { !state.value.serverTeams.busy }

    /** A picture file the picker could hand the app: a PNG of [width] × [height]. */
    private fun picture(name: String, width: Int = 600, height: Int = 400): android.net.Uri {
        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(20, 120, 200)) }
        val file = java.io.File(context.cacheDir, name)
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        return android.net.Uri.fromFile(file)
    }

    /** PNG bytes the mock server sends as a logo; the app decodes any picture the platform reads. */
    private fun logoBytes(): ByteArray = java.io.ByteArrayOutputStream().also {
        android.graphics.Bitmap.createBitmap(256, 256, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
            .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
    }.toByteArray()

    private fun logoAnswer() = MockResponse().setResponseCode(200).setHeader("Content-Type", "image/webp").setBody(okio.Buffer().write(logoBytes()))

    /** The encoder of the JVM tests: fixed bytes, so the tests read what the app sends. The platform encoder runs in LogoDeviceTest. */
    private fun AppViewModel.withFakeEncoder(size: Int = 100): AppViewModel {
        logoCompressor = com.voleybolveveleybol.volleystat.data.LogoCodec.Compressor { bitmap, _, quality -> ByteArray(size) { (bitmap.width + quality).toByte() } }
        return this
    }

    @Test fun aJerseyRefusedAsTakenShowsTheNumber() {
        TestAccounts.seed(context)
        on("GET /teams") { teams(team("t1", members = listOf(captainRow, ece))) }
        on("PUT /teams/t1/members/${TestAccounts.SESSION.userId}/jersey") { refuse(409, "JERSEY_TAKEN", JSONObject().put("jersey", 12)) }
        val vm = viewModel().loaded()
        var done = 0
        vm.setJersey("t1", 12) { done++ }
        await("the refusal") { vm.state.value.serverTeams.problem != null && !vm.state.value.serverTeams.busy }
        assertEquals(12, JSONObject(seen.single { it.key.endsWith("/jersey") }.body).getInt("jersey"))
        assertEquals(TeamProblem.JerseyTaken(12), vm.state.value.serverTeams.problem)
        assertEquals(string(R.string.jersey_taken, 12), teamProblemText(vm.state.value.serverTeams.problem!!).resolve(context))
        assertEquals("the dialog stays open", 0, done)

        // Another number: the member's row and the account's own number follow the server's answer.
        on("PUT /teams/t1/members/${TestAccounts.SESSION.userId}/jersey") {
            ok(member(TestAccounts.SESSION.userId, "Test Kullanıcı", Seat.STARTER, "2026-11-20T10:00:00Z", role = "CAPTAIN", jersey = 4))
        }
        vm.setJersey("t1", 4) { done++ }
        await("the jersey") { done == 1 }
        assertNull(vm.state.value.serverTeams.problem)
        assertEquals(4, vm.state.value.serverTeams.teamOf("t1")!!.myJersey)
        // A null clears the number.
        on("PUT /teams/t1/members/${TestAccounts.SESSION.userId}/jersey") {
            ok(member(TestAccounts.SESSION.userId, "Test Kullanıcı", Seat.STARTER, "2026-11-20T10:00:00Z", role = "CAPTAIN"))
        }
        vm.setJersey("t1", null) { done++ }
        await("the cleared jersey") { done == 2 }
        assertTrue(JSONObject(seen.last { it.key.endsWith("/jersey") }.body).isNull("jersey"))
        assertNull(vm.state.value.serverTeams.teamOf("t1")!!.myJersey)
    }

    @Test fun aRemovalReloadsTheTeamAndTheBlockedList() {
        TestAccounts.seed(context)
        var removed = false
        on("GET /teams") { teams(team("t1", members = if (removed) listOf(captainRow, gul) else listOf(captainRow, ece, gul))) }
        on("GET /teams/t1") { ok(team("t1", members = if (removed) listOf(captainRow, gul) else listOf(captainRow, ece, gul))) }
        on("GET /teams/t1/blocks") { if (removed) blocked("u-2" to "Ece Yeni") else blocked() }
        on("DELETE /teams/t1/members/u-2") { removed = true; MockResponse().setResponseCode(204) }
        val vm = viewModel().loaded()
        vm.openTeam("t1", "screen-1")
        await("the team") { vm.state.value.serverTeams.opened?.team != null }
        vm.loadBlocked("t1")
        await("the empty blocked list") { vm.state.value.serverTeams.blocked?.list == emptyList<Any>() }
        val before = seen.size
        var done = 0
        vm.removeMember("t1", "u-2") { done++ }
        await("the removal") { done == 1 }
        await("the reads after it") { vm.state.value.serverTeams.blocked?.list?.map { it.userId } == listOf("u-2") }
        await("the team without her") { vm.state.value.serverTeams.teamOf("t1")!!.members.none { it.userId == "u-2" } }
        await("the team's own read") { seen.drop(before).any { it.key == "GET /teams/t1" } }
        val after = seen.drop(before).map { it.key }
        assertEquals("DELETE /teams/t1/members/u-2", after.first())
        assertTrue(after.toString(), after.containsAll(listOf("GET /teams", "GET /teams/t1", "GET /teams/t1/blocks")))
        assertEquals("Ece Yeni", vm.state.value.serverTeams.blocked!!.list!!.single().displayName)
        assertEquals(2, vm.state.value.serverTeams.opened!!.team!!.members.size)
    }

    @Test fun anUnblockRemovesTheRow() {
        TestAccounts.seed(context)
        var lifted = false
        on("GET /teams") { teams(team("t1")) }
        on("GET /teams/t1/blocks") { if (lifted) blocked("u-3" to "Gül Ak") else blocked("u-2" to "Ece Yeni", "u-3" to "Gül Ak") }
        on("DELETE /teams/t1/blocks/u-2") { lifted = true; MockResponse().setResponseCode(204) }
        val vm = viewModel().loaded()
        vm.loadBlocked("t1")
        await("the list") { vm.state.value.serverTeams.blocked?.list?.size == 2 }
        assertEquals("t1", vm.state.value.serverTeams.blocked!!.teamId)
        vm.unblock("t1", "u-2")
        await("the row goes") { vm.state.value.serverTeams.blocked?.list?.map { it.userId } == listOf("u-3") }
        vm.idle()
        await("the list read again") { seen.count { it.key == "GET /teams/t1/blocks" } == 2 }
        settle()
        assertEquals(listOf("u-3"), vm.state.value.serverTeams.blocked!!.list!!.map { it.userId })

        // A read of the list that fails keeps no rows of another moment and says why.
        on("GET /teams/t1/blocks") { refuse(403, "CAPTAIN_ONLY") }
        vm.loadBlocked("t1")
        await("the refusal") { vm.state.value.serverTeams.blocked?.problem != null }
        assertEquals(TeamProblem.CaptainOnly, vm.state.value.serverTeams.blocked!!.problem)
    }

    @Test fun aTransferRefusedForTheLimitNamesTheMember() {
        TestAccounts.seed(context)
        on("GET /teams") { teams(team("t1", members = listOf(captainRow, ece))) }
        on("POST /teams/t1/captain") { refuse(409, "TEAM_LIMIT_REACHED", JSONObject().put("scope", "CAPTAIN").put("limit", 3).put("userId", "u-2")) }
        val vm = viewModel().loaded()
        var done = 0
        vm.transferCaptain("t1", "u-2") { done++ }
        await("the refusal") { vm.state.value.serverTeams.problem != null && !vm.state.value.serverTeams.busy }
        assertEquals("u-2", JSONObject(seen.single { it.key == "POST /teams/t1/captain" }.body).getString("userId"))
        assertEquals(TeamProblem.Limit(TeamLimitScope.CAPTAIN, 3, "u-2", "Ece Yeni"), vm.state.value.serverTeams.problem)
        assertEquals(string(R.string.transfer_captain_limit, "Ece Yeni", 3), teamProblemText(vm.state.value.serverTeams.problem!!).resolve(context))
        assertEquals(0, done)
        // Without the member's name the app names no one.
        assertEquals(string(R.string.account_refused), teamProblemText(TeamProblem.Limit(TeamLimitScope.CAPTAIN, 3, "u-9")).resolve(context))

        // The server takes it: the team shows the account as a member now.
        on("POST /teams/t1/captain") { ok(team("t1", role = "MEMBER", members = listOf(captainRow, ece))) }
        on("GET /teams") { teams(team("t1", role = "MEMBER", members = listOf(captainRow, ece))) }
        vm.transferCaptain("t1", "u-2") { done++ }
        await("the transfer") { done == 1 }
        assertFalse(vm.state.value.serverTeams.teamOf("t1")!!.captain)
    }

    @Test fun aLeaveRemovesTheTeamFromTheList() {
        TestAccounts.seed(context)
        var left = false
        on("GET /teams") { if (left) teams(team("t1")) else teams(team("t1"), team("t2", name = "Güney Spor", role = "MEMBER")) }
        on("DELETE /teams/t2/members/${TestAccounts.SESSION.userId}") { left = true; MockResponse().setResponseCode(204) }
        val vm = viewModel().loaded()
        var done = 0
        vm.leaveTeam("t2") { done++ }
        await("the leave") { done == 1 }
        assertEquals(listOf("t1"), vm.state.value.serverTeams.teams!!.map { it.id })
        assertTrue(seen.any { it.key == "DELETE /teams/t2/members/${TestAccounts.SESSION.userId}" })

        // A captain with other members must hand the captaincy on first; nothing changes.
        on("DELETE /teams/t1/members/${TestAccounts.SESSION.userId}") { refuse(409, "CAPTAIN_MUST_TRANSFER") }
        vm.leaveTeam("t1") { done++ }
        await("the refusal") { vm.state.value.serverTeams.problem != null && !vm.state.value.serverTeams.busy }
        assertEquals(TeamProblem.MustTransfer, vm.state.value.serverTeams.problem)
        assertEquals(string(R.string.team_must_transfer), teamProblemText(TeamProblem.MustTransfer).resolve(context))
        assertEquals(listOf("t1"), vm.state.value.serverTeams.teams!!.map { it.id })
        assertEquals(1, done)
    }

    @Test fun aDeletedTeamLeavesTheList() {
        TestAccounts.seed(context)
        var deleted = false
        on("GET /teams") { if (deleted) teams(team("t2", name = "Güney Spor")) else teams(team("t1", logo = 2), team("t2", name = "Güney Spor")) }
        on("GET /teams/t1/logo") { logoAnswer() }
        on("DELETE /teams/t1") { deleted = true; MockResponse().setResponseCode(204) }
        val vm = viewModel().loaded()
        await("the logo") { vm.state.value.serverTeams.logos["t1"] != null }
        var done = 0
        vm.deleteServerTeam("t1") { done++ }
        await("the deletion") { done == 1 }
        assertEquals(listOf("t2"), vm.state.value.serverTeams.teams!!.map { it.id })
        assertNull("the team's logo leaves the memory", vm.state.value.serverTeams.logos["t1"])

        on("DELETE /teams/t2") { refuse(403, "CAPTAIN_ONLY") }
        vm.deleteServerTeam("t2") { done++ }
        await("the refusal") { vm.state.value.serverTeams.problem != null && !vm.state.value.serverTeams.busy }
        assertEquals(TeamProblem.CaptainOnly, vm.state.value.serverTeams.problem)
        assertEquals(1, done)
    }

    @Test fun theLogosOfTheListAreReadOnceForEachVersion() {
        TestAccounts.seed(context)
        var version: Int? = 3
        on("GET /teams") { teams(team("t1", logo = version), team("t2", name = "Güney Spor")) }
        on("GET /teams/t1/logo") { logoAnswer() }
        val vm = viewModel().loaded()
        await("the logo") { vm.state.value.serverTeams.logos["t1"]?.version == 3 }
        val logo = vm.state.value.serverTeams.logos.getValue("t1")
        assertTrue(logo.image.width <= com.voleybolveveleybol.volleystat.data.LogoCodec.LARGE)
        assertEquals("only the team with a logo", setOf("t1"), vm.state.value.serverTeams.logos.keys)
        val reads = seen.filter { it.key == "GET /teams/t1/logo" }
        assertEquals(1, reads.size)
        assertEquals("Bearer ${TestAccounts.SESSION.token}", reads.single().authorization)
        // The same version is not read again.
        vm.loaded()
        settle()
        assertEquals(1, seen.count { it.key == "GET /teams/t1/logo" })
        // A new version is.
        version = 4
        vm.loaded()
        await("the new version") { vm.state.value.serverTeams.logos["t1"]?.version == 4 }
        assertEquals(2, seen.count { it.key == "GET /teams/t1/logo" })
        // A team without a logo shows its initials again.
        version = null
        vm.loaded()
        await("no logo") { vm.state.value.serverTeams.logos["t1"] == null }
        // A logo the server no longer has, or can not send, leaves the initials and no problem.
        version = 5
        on("GET /teams/t1/logo") { refuse(404, "LOGO_NOT_FOUND") }
        vm.loaded()
        await("the read") { seen.count { it.key == "GET /teams/t1/logo" } == 3 }
        settle()
        assertNull(vm.state.value.serverTeams.logos["t1"])
        assertNull(vm.state.value.serverTeams.problem)
        assertNull(vm.state.value.serverTeams.loadProblem)
    }

    @Test fun aLogoGoesToTheServerAsTwoFilesAndShowsAtOnce() {
        TestAccounts.seed(context)
        on("GET /teams") { teams(team("t1")) }
        on("PUT /teams/t1/logo") { ok(JSONObject().put("logo", JSONObject().put("version", 1))) }
        val vm = viewModel().withFakeEncoder().loaded()
        var done = 0
        vm.setLogo("t1", picture("logo.png")) { done++ }
        await("the logo") { done == 1 }
        val sent = JSONObject(seen.single { it.key == "PUT /teams/t1/logo" }.body)
        val decoder = java.util.Base64.getDecoder()
        // The fake encoder writes the side and the quality into its bytes: 256 and 128 px at quality 80.
        assertTrue(decoder.decode(sent.getString("large")).all { it == (256 + 80).toByte() })
        assertTrue(decoder.decode(sent.getString("small")).all { it == (128 + 80).toByte() })
        assertEquals(1, vm.state.value.serverTeams.teamOf("t1")!!.logoVersion)
        assertEquals("the app shows the logo it sent without reading it back", 1, vm.state.value.serverTeams.logos["t1"]?.version)
        assertEquals(0, seen.count { it.key == "GET /teams/t1/logo" })

        // Logoyu Kaldır.
        on("DELETE /teams/t1/logo") { MockResponse().setResponseCode(204) }
        vm.removeLogo("t1")
        await("no logo") { vm.state.value.serverTeams.logos["t1"] == null && !vm.state.value.serverTeams.busy }
        assertNull(vm.state.value.serverTeams.teamOf("t1")!!.logoVersion)
    }

    @Test fun aLogoRefusedByTheServerSaysSo() {
        TestAccounts.seed(context)
        on("GET /teams") { teams(team("t1")) }
        on("PUT /teams/t1/logo") { refuse(422, "LOGO_INVALID", JSONObject().put("image", "large")) }
        val vm = viewModel().withFakeEncoder().loaded()
        vm.setLogo("t1", picture("logo.png")) { error("refused") }
        await("the refusal") { vm.state.value.serverTeams.problem != null && !vm.state.value.serverTeams.busy }
        assertEquals(TeamProblem.LogoInvalid("large"), vm.state.value.serverTeams.problem)
        assertEquals(string(R.string.logo_invalid), teamProblemText(vm.state.value.serverTeams.problem!!).resolve(context))
        assertNull(vm.state.value.serverTeams.teamOf("t1")!!.logoVersion)
        assertTrue(vm.state.value.serverTeams.logos.isEmpty())
    }

    @Test fun aLogoTheAppCanNotReadSaysSoAndSendsNothing() {
        TestAccounts.seed(context)
        on("GET /teams") { teams(team("t1")) }
        on("PUT /teams/t1/logo") { ok(JSONObject().put("logo", JSONObject().put("version", 1))) }
        val vm = viewModel().withFakeEncoder().loaded()
        val text = java.io.File(context.cacheDir, "not-a-picture.png").apply { writeText("this is no picture") }
        vm.setLogo("t1", android.net.Uri.fromFile(text)) { error("unreadable") }
        await("the problem") { vm.state.value.serverTeams.problem != null && !vm.state.value.serverTeams.busy }
        assertEquals(TeamProblem.LogoUnreadable, vm.state.value.serverTeams.problem)
        assertEquals(string(R.string.logo_unreadable), teamProblemText(TeamProblem.LogoUnreadable).resolve(context))
        // A picture the encoder can not make small enough.
        vm.withFakeEncoder(size = com.voleybolveveleybol.volleystat.data.LogoCodec.LARGE_MAX_BYTES + 1)
        vm.setLogo("t1", picture("busy.png")) { error("too large") }
        await("the second problem") { vm.state.value.serverTeams.problem == TeamProblem.LogoTooLarge && !vm.state.value.serverTeams.busy }
        assertEquals(string(R.string.logo_too_large), teamProblemText(TeamProblem.LogoTooLarge).resolve(context))
        assertEquals("nothing went to the server", 0, seen.count { it.key == "PUT /teams/t1/logo" })
    }

    @Test fun takimKurSendsTheChosenLogoAfterTheTeam() {
        TestAccounts.seed(context)
        on("GET /teams") { teams() }
        on("PUT /teams/{new}") { request -> ok(team(request.requestUrl!!.encodedPath.substringAfterLast('/')), 201) }
        val vm = viewModel().withFakeEncoder().loaded()
        vm.pickDraftLogo(picture("draft.png"))
        await("the draft logo") { vm.state.value.serverTeams.draftLogo != null && !vm.state.value.serverTeams.busy }
        val id = CopyOnWriteArrayList<String>()
        on("PUT /teams/{new}/logo") { ok(JSONObject().put("logo", JSONObject().put("version", 1))) }
        vm.createTeam(TeamDraft("Kuzey Spor")) { id += it }
        await("the team") { id.isNotEmpty() }
        await("the logo") { seen.any { it.key == "PUT /teams/{new}/logo" } && !vm.state.value.serverTeams.busy }
        val order = seen.map { it.key }.filter { it.startsWith("PUT") }
        assertEquals(listOf("PUT /teams/{new}", "PUT /teams/{new}/logo"), order)
        assertEquals("the logo goes to the team just made", "/teams/${id.single()}/logo", seen.single { it.key == "PUT /teams/{new}/logo" }.path)
        assertNull("the draft's logo is spent", vm.state.value.serverTeams.draftLogo)
        await("the logo shows") { vm.state.value.serverTeams.logos[id.single()]?.version == 1 }

        // A picture the app can not read is refused on the form, and Takım Kur sends no logo.
        vm.pickDraftLogo(android.net.Uri.fromFile(java.io.File(context.cacheDir, "yok.png")))
        await("the problem") { vm.state.value.serverTeams.problem == TeamProblem.LogoUnreadable && !vm.state.value.serverTeams.busy }
        assertNull(vm.state.value.serverTeams.draftLogo)
        vm.pickDraftLogo(picture("draft2.png"))
        await("the second draft logo") { vm.state.value.serverTeams.draftLogo != null && !vm.state.value.serverTeams.busy }
        vm.clearDraftLogo()
        assertNull(vm.state.value.serverTeams.draftLogo)
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

    /**
     * A logs in, reads a team with a new member, its logo and its Çıkarılanlar, picks a logo for Takım Kur and leaves a
     * creation whose answer was lost.
     */
    private fun firstAccountWithATeam(): Pair<AppViewModel, String> {
        on("GET /teams") { request ->
            if (request.getHeader("Authorization")!!.contains("ali")) {
                teams(team("t1", newJoins = 1, logo = 1, members = listOf(captainRow, member("u-2", "Ece Yeni", Seat.SUBSTITUTE, "2026-11-21T12:00:00Z"))))
            } else {
                refuse(500, "INTERNAL")
            }
        }
        on("GET /teams/t1/logo") { logoAnswer() }
        on("GET /teams/t1/blocks") { blocked("u-9" to "Engelli Kişi") }
        on("PUT /teams/{new}") { lost() }
        val vm = viewModel().withFakeEncoder()
        vm.loginAndWait("ali")
        vm.loaded()
        assertEquals(1, vm.state.value.serverTeams.badge)
        await("A's logo") { vm.state.value.serverTeams.logos["t1"] != null }
        vm.loadBlocked("t1")
        await("A's Çıkarılanlar") { vm.state.value.serverTeams.blocked?.list?.size == 1 }
        vm.pickDraftLogo(picture("a-logo.png"))
        await("A's logo for Takım Kur") { vm.state.value.serverTeams.draftLogo != null && !vm.state.value.serverTeams.busy }
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
        assertTrue("no logo of A is left in memory", ui.logos.isEmpty())
        assertNull("no Çıkarılanlar of A", ui.blocked)
        assertNull("no logo A picked for Takım Kur", ui.draftLogo)
        val everything = vm.state.value.toString()
        listOf("Kuzey Spor", "Deniz Kaptan", "Ece Yeni", "K7Q2", "pool-t1", "Engelli Kişi").forEach { assertFalse("$it is left in B's state", everything.contains(it)) }
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
