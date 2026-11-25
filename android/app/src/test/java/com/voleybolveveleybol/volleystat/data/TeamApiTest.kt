package com.voleybolveveleybol.volleystat.data

import com.voleybolveveleybol.volleystat.model.Schedule
import com.voleybolveveleybol.volleystat.model.Seat
import com.voleybolveveleybol.volleystat.model.SeatCount
import com.voleybolveveleybol.volleystat.model.SeatCounts
import com.voleybolveveleybol.volleystat.model.TeamMember
import com.voleybolveveleybol.volleystat.model.TeamRole
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The team calls of docs/API_V1.md section 10 (Takımlar, slice B4) and the typed problem each refusal maps to. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TeamApiTest {
    private val server = MockWebServer()

    @Before fun start() = server.start()
    @After fun stop() = server.shutdown()

    private val client get() = ApiClient(server.url("/v1").toString(), token = "session-1")

    private fun json(status: Int, body: JSONObject, headers: Map<String, String> = emptyMap()) = MockResponse().setResponseCode(status)
        .setHeader("Content-Type", "application/json").setBody(body.toString()).apply { headers.forEach { (k, v) -> setHeader(k, v) } }

    private fun error(status: Int, code: String, details: JSONObject = JSONObject(), headers: Map<String, String> = emptyMap()) =
        json(status, JSONObject().put("error", JSONObject().put("code", code).put("message", code).put("details", details)), headers)

    private fun body(request: RecordedRequest) = JSONObject(request.body.readUtf8())

    private fun member(id: String, name: String, jersey: Int?, role: String, seat: String, joined: String) = JSONObject().put("userId", id).put("displayName", name)
        .put("jersey", jersey ?: JSONObject.NULL).put("role", role).put("seat", seat).put("joinedAt", joined)

    private fun count(seats: Int, taken: Int) = JSONObject().put("seats", seats).put("taken", taken).put("left", seats - taken).put("deciding", 0)

    /** The Team shape of design 9.3 as server 0.10 sends it to the captain. */
    private fun team(id: String = "6f0c1d2e-0000-4000-8000-000000000001", logo: Int? = 3, substitutes: Int = 2) = JSONObject()
        .put("id", id).put("poolId", "pool-1").put("name", "Kuzey Spor").put("tag", "K7Q2")
        .put("schedule", JSONObject().put("weekday", 2).put("time", "20:00"))
        .put("logo", if (logo == null) JSONObject.NULL else JSONObject().put("version", logo))
        .put("myRole", "CAPTAIN").put("mySeat", "STARTER").put("myJersey", 7).put("newJoins", 2)
        .put(
            "members",
            JSONArray(
                listOf(
                    member("u-1", "Deniz", 7, "CAPTAIN", "STARTER", "2026-11-20T10:00:00Z"),
                    member("u-2", "Ece", null, "MEMBER", "SUBSTITUTE", "2026-11-21T10:00:00Z"),
                )
            ),
        )
        .put("seats", JSONObject().put("starter", count(6, 1)).put("substitute", count(substitutes, 1)).put("waiting", 0))
        .put("invite", JSONObject.NULL)

    @Test fun createSendsTheClientIdAndTheBodyAndReadsTheTeam() {
        val id = "6f0c1d2e-0000-4000-8000-000000000001"
        server.enqueue(json(201, team(id)))
        val (created, isNew) = client.putTeam(id, "Kuzey Spor", 3, Seat.SUBSTITUTE, Schedule(2, "20:00"), 7)
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/v1/teams/$id", request.path)
        assertEquals("Bearer session-1", request.getHeader("Authorization"))
        val sent = body(request)
        assertEquals(setOf("name", "substituteSeats", "captainSeat", "schedule", "jersey"), sent.keys().asSequence().toSet())
        assertEquals("Kuzey Spor", sent.getString("name"))
        assertEquals(3, sent.getInt("substituteSeats"))
        assertEquals("SUBSTITUTE", sent.getString("captainSeat"))
        assertEquals(2, sent.getJSONObject("schedule").getInt("weekday"))
        assertEquals("20:00", sent.getJSONObject("schedule").getString("time"))
        assertEquals(7, sent.getInt("jersey"))
        assertTrue("201 is a creation", isNew)
        assertEquals(id, created.id)
        assertEquals("Kuzey Spor", created.name)
        assertEquals("K7Q2", created.tag)

        // Without a schedule and a jersey the body names neither: the server takes them as none.
        server.enqueue(json(201, team(id)))
        client.putTeam(id, "Kuzey Spor", 2, Seat.STARTER, null, null)
        assertEquals(setOf("name", "substituteSeats", "captainSeat"), body(server.takeRequest()).keys().asSequence().toSet())
    }

    @Test fun aRepeatAnswered200IsNotACreation() {
        val id = "6f0c1d2e-0000-4000-8000-000000000001"
        server.enqueue(json(200, team(id)))
        val (team, isNew) = client.putTeam(id, "Kuzey Spor", 2, Seat.STARTER, null, null)
        assertFalse("200 answers a repeat: the team was there already", isNew)
        assertEquals(id, team.id)
    }

    @Test fun theTeamShapeIsReadWithItsSeatCountsAndMembers() {
        server.enqueue(json(200, JSONObject().put("teams", JSONArray(listOf(team())))))
        val read = client.teams().single()
        assertEquals("/v1/teams", server.takeRequest().path)
        assertEquals("pool-1", read.poolId)
        assertEquals(Schedule(2, "20:00"), read.schedule)
        assertEquals(3, read.logoVersion)
        assertEquals(TeamRole.CAPTAIN, read.myRole)
        assertEquals(Seat.STARTER, read.mySeat)
        assertEquals(7, read.myJersey)
        assertEquals(2, read.newJoins)
        assertEquals(0, read.waiting)
        assertEquals(SeatCounts(SeatCount(6, 1, 5, 0), SeatCount(2, 1, 1, 0)), read.seats)
        assertEquals(
            listOf(
                TeamMember("u-1", "Deniz", 7, TeamRole.CAPTAIN, Seat.STARTER, "2026-11-20T10:00:00Z"),
                TeamMember("u-2", "Ece", null, TeamRole.MEMBER, Seat.SUBSTITUTE, "2026-11-21T10:00:00Z"),
            ),
            read.members,
        )

        // One team by its id; a member sees no newJoins and no waiting count.
        val asMember = team().put("myRole", "MEMBER").put("newJoins", JSONObject.NULL)
            .put("seats", JSONObject().put("starter", count(6, 1)).put("substitute", count(2, 1)).put("waiting", JSONObject.NULL))
        server.enqueue(json(200, asMember))
        val one = client.team("t-1")
        assertEquals("/v1/teams/t-1", server.takeRequest().path)
        assertNull(one.newJoins)
        assertNull(one.waiting)
        assertEquals(TeamRole.MEMBER, one.myRole)

        // A role or a seat this app does not know is an answer it can not read, not a crash.
        server.enqueue(json(200, team().put("myRole", "COACH")))
        try {
            client.team("t-1")
            fail("an unknown role was read")
        } catch (error: ApiException) {
            assertEquals("UNEXPECTED_ANSWER", error.code)
        }
    }

    @Test fun aTeamWithoutALogoHasNoVersion() {
        server.enqueue(json(200, team(logo = null).put("schedule", JSONObject.NULL)))
        val read = client.team("t-1")
        assertNull(read.logoVersion)
        assertNull(read.schedule)
    }

    @Test fun seatsSendOnlyTheFinalCountAndTheMoves() {
        server.enqueue(json(200, team(substitutes = 3)))
        val read = client.teamSeats("t-1", 3, mapOf("u-2" to Seat.STARTER))
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/v1/teams/t-1/seats", request.path)
        val sent = body(request)
        assertEquals("no starter count: a team always has 6 starter seats", setOf("substituteSeats", "moves"), sent.keys().asSequence().toSet())
        assertEquals(3, sent.getInt("substituteSeats"))
        val moves = sent.getJSONArray("moves")
        assertEquals(1, moves.length())
        assertEquals("u-2", moves.getJSONObject(0).getString("userId"))
        assertEquals("STARTER", moves.getJSONObject(0).getString("seat"))
        assertEquals(3, read.seats.substitute.seats)

        // The settings name the name and the schedule; null clears the schedule.
        server.enqueue(json(200, team()))
        client.teamSettings("t-1", "Güney Spor", null)
        val settings = server.takeRequest()
        assertEquals("PUT", settings.method)
        assertEquals("/v1/teams/t-1/settings", settings.path)
        val named = body(settings)
        assertEquals("Güney Spor", named.getString("name"))
        assertTrue("a null schedule is sent as null", named.has("schedule") && named.isNull("schedule"))
    }

    @Test fun joinsSeenSendsTheNewestJoinTime() {
        server.enqueue(MockResponse().setResponseCode(204))
        client.joinsSeen("t-1", "2026-11-21T10:00:00Z")
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/teams/t-1/joins-seen", request.path)
        val sent = body(request)
        assertEquals("2026-11-21T10:00:00Z", sent.getString("upTo"))
        assertEquals(setOf("upTo"), sent.keys().asSequence().toSet())

        // Deletion: only the route's own 204 counts.
        server.enqueue(MockResponse().setResponseCode(204))
        client.deleteTeam("t-1")
        val deletion = server.takeRequest()
        assertEquals("DELETE", deletion.method)
        assertEquals("/v1/teams/t-1", deletion.path)
        server.enqueue(json(200, JSONObject()))
        try {
            client.deleteTeam("t-1")
            fail("a 200 is not the deletion's 204")
        } catch (error: ApiException) {
            assertEquals("UNEXPECTED_ANSWER", error.code)
        }
    }

    @Test fun everyTeamRefusalBecomesItsProblem() {
        fun problemOf(answer: MockResponse): TeamProblem {
            server.enqueue(answer)
            return try {
                client.team("t-1")
                fail("the refusal was read as a team")
                throw AssertionError()
            } catch (error: ApiException) {
                TeamProblem.of(error)
            }
        }
        val table = listOf(
            error(403, "NOT_A_MEMBER") to TeamProblem.NoAccess,
            error(403, "CAPTAIN_ONLY") to TeamProblem.CaptainOnly,
            error(404, "TEAM_NOT_FOUND") to TeamProblem.Gone,
            error(422, "NAME_INVALID") to TeamProblem.NameInvalid,
            error(409, "TEAM_ID_TAKEN") to TeamProblem.IdTaken,
            error(409, "TEAM_LIMIT_REACHED", JSONObject().put("scope", "CAPTAIN").put("limit", 3)) to TeamProblem.Limit(TeamLimitScope.CAPTAIN, 3),
            error(409, "TEAM_LIMIT_REACHED", JSONObject().put("scope", "MEMBER").put("limit", 20)) to TeamProblem.Limit(TeamLimitScope.MEMBER, 20),
            error(409, "TEAM_LIMIT_REACHED", JSONObject().put("scope", "CAPTAIN").put("limit", 3).put("userId", "u-9")) to TeamProblem.Limit(TeamLimitScope.CAPTAIN, 3, "u-9"),
            error(429, "TOO_MANY_ATTEMPTS", JSONObject().put("scope", "TEAMS").put("retryAfterSeconds", 3600), mapOf("Retry-After" to "3600")) to TeamProblem.TooMany(3600),
            // Without details the header's wait counts.
            error(429, "TOO_MANY_ATTEMPTS", headers = mapOf("Retry-After" to "120")) to TeamProblem.TooMany(120),
            error(422, "SEATS_INVALID", JSONObject().put("reason", "SUBSTITUTES_RANGE")) to TeamProblem.SeatsInvalid(SeatsInvalidReason.SUBSTITUTES_RANGE),
            error(422, "SEATS_INVALID", JSONObject().put("reason", "CAPTAIN_SEAT")) to TeamProblem.SeatsInvalid(SeatsInvalidReason.CAPTAIN_SEAT),
            error(422, "SEATS_INVALID", JSONObject().put("reason", "LATER")) to TeamProblem.SeatsInvalid(SeatsInvalidReason.UNKNOWN),
            error(409, "SEATS_BELOW_HOLDERS", JSONObject().put("seat", "SUBSTITUTE").put("holders", 3).put("seats", 2)) to TeamProblem.SeatsBelowHolders(Seat.SUBSTITUTE, 3, 2),
            error(409, "SEATS_BELOW_HOLDERS", JSONObject().put("seat", "STARTER").put("holders", 7).put("seats", 6)) to TeamProblem.SeatsBelowHolders(Seat.STARTER, 7, 6),
            // A refusal without its numbers names no number of its own.
            error(409, "SEATS_BELOW_HOLDERS") to TeamProblem.Failed(409, "SEATS_BELOW_HOLDERS"),
            error(422, "NOT_A_TEAM_MEMBER", JSONObject().put("userId", "u-2")) to TeamProblem.NotAMember("u-2"),
            error(422, "VALIDATION_ERROR") to TeamProblem.Failed(422, "VALIDATION_ERROR"),
            error(500, "INTERNAL") to TeamProblem.Failed(500, "INTERNAL"),
            MockResponse().setResponseCode(502).setBody("<html>bad gateway</html>") to TeamProblem.Failed(502, "HTTP_502"),
        )
        table.forEach { (answer, expected) -> assertEquals(expected.toString(), expected, problemOf(answer)) }
        assertEquals(TeamProblem.Unreachable, TeamProblem.of(java.io.IOException("no answer")))
        assertEquals(TeamProblem.Unreachable, TeamProblem.of(RequestNotSent(java.io.IOException("refused"))))
    }

    @Test fun aServerWithoutTeamRoutesIsOlder() {
        // Server 0.9 answers an unknown route with the API's own 404 NOT_FOUND, and a known path with another method with 405.
        server.enqueue(error(404, "NOT_FOUND"))
        val older = try {
            client.teams()
            null
        } catch (error: ApiException) {
            TeamProblem.of(error)
        }
        assertEquals(TeamProblem.ServerOutdated, older)
        server.enqueue(error(405, "METHOD_NOT_ALLOWED"))
        val method = runCatching { client.putTeam("6f0c1d2e-0000-4000-8000-000000000001", "Kuzey Spor", 2, Seat.STARTER, null, null) }.exceptionOrNull()
        assertEquals(TeamProblem.ServerOutdated, TeamProblem.of(method!!))
        // A 404 of the team route itself is a team that is gone, not an older server.
        server.enqueue(error(404, "TEAM_NOT_FOUND"))
        assertEquals(TeamProblem.Gone, TeamProblem.of(runCatching { client.team("t-1") }.exceptionOrNull()!!))
    }
}
