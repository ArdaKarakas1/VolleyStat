package com.voleybolveveleybol.volleystat.data

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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

/**
 * Answers to the match calls that may not come from the API: the page of a gateway, a proxy or a hotel's
 * Wi-Fi login, or the answer of another service at the server's address. Only the API's own answer says
 * what the server holds (ADR-0119).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ApiAnswerSourceTest {
    private val server = MockWebServer()

    @Before fun start() = server.start()
    @After fun stop() = server.shutdown()

    private fun client() = ApiClient(server.url("/v1").toString())

    private fun json(status: Int, body: JSONObject) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body.toString())
    private fun envelope(status: Int, code: String) = json(status, JSONObject().put("error", JSONObject().put("code", code).put("message", code).put("details", JSONObject())))
    private fun page(status: Int) = MockResponse().setResponseCode(status).setHeader("Content-Type", "text/html").setBody("<html>a gateway</html>")

    @Test fun onlyTheApisOwnNotFoundSaysThatTheMatchHasNoReport() {
        // The API's two answers for a match without a report: no analysis of it produced rallies, or no such match.
        listOf("REPORT_NOT_AVAILABLE", "MATCH_NOT_FOUND").forEach { code ->
            server.enqueue(envelope(404, code))
            assertNull(code, client().report("match-1"))
        }
        // A 404 page of a gateway or a proxy, a 404 without a body, and the API's own 404 for a route it does not have
        // say nothing about the analyses of the match.
        listOf(page(404) to "HTTP_404", MockResponse().setResponseCode(404) to "HTTP_404", envelope(404, "NOT_FOUND") to "NOT_FOUND").forEach { (answer, code) ->
            server.enqueue(answer)
            try {
                client().report("match-1")
                fail("$code was read as a match without a report")
            } catch (error: ApiException) {
                assertEquals(404, error.status)
                assertEquals(code, error.code)
            }
        }
    }

    @Test fun anAnswerLeavesTheOutcomeOpenUnlessTheApiItselfRefused() {
        fun outcome(status: Int, code: String) = UnknownOutcome.of(ApiException(status, code, code, JSONObject()))
        // The API's own refusal: its envelope on a 4xx, and SERVER_BUSY, which the server sends before it does anything.
        assertNull(outcome(422, "VALIDATION_ERROR"))
        assertNull(outcome(409, "RALLY_LIST_STALE"))
        assertNull(outcome(503, "SERVER_BUSY"))
        // The server reported an error and may have made the change before it failed.
        assertEquals(UnknownOutcome.SERVER_ERROR, outcome(500, "INTERNAL_ERROR"))
        // A gateway on the way may have sent these: a 5xx without the envelope, and a 511, which the API never sends.
        assertEquals(UnknownOutcome.ERROR_ON_THE_WAY, outcome(502, "HTTP_502"))
        assertEquals(UnknownOutcome.ERROR_ON_THE_WAY, outcome(511, "NETWORK_AUTHENTICATION_REQUIRED"))
        // Answers the app can not read: a 4xx or a 3xx without the envelope, a 2xx this call does not expect, an envelope
        // that names UNEXPECTED_ANSWER, and a status HTTP does not have.
        listOf(404 to "HTTP_404", 302 to "HTTP_302", 200 to "UNEXPECTED_ANSWER", 409 to "UNEXPECTED_ANSWER", 500 to "UNEXPECTED_ANSWER", 600 to "INTERNAL_ERROR")
            .forEach { (status, code) -> assertEquals("$status $code", UnknownOutcome.UNREADABLE, outcome(status, code)) }
    }

    @Test fun onlyTheShapeOfTheApisHealthAnswerCountsAsTheServersAnswer() {
        val health = JSONObject().put("status", "ok").put("mode", "local-dev").put("mediaProbe", "available").put("maxVideoBytes", 1_000)
        server.enqueue(json(200, health))
        val available = client().health()
        assertTrue(available.fromApi)
        assertTrue(available.probeAvailable)
        assertEquals(1_000, available.maxVideoBytes)

        server.enqueue(json(200, JSONObject(health.toString()).put("mediaProbe", "unavailable")))
        val unavailable = client().health()
        assertTrue(unavailable.fromApi)
        assertFalse(unavailable.probeAvailable)

        // Every API version answers with "status": "ok" and names the media probe either way. Nothing else is its health answer.
        val others = listOf(
            "a blank 200" to MockResponse().setResponseCode(200),
            "a 204" to MockResponse().setResponseCode(204),
            "another service's JSON" to json(200, JSONObject().put("hello", "world")),
            "a status without the probe" to json(200, JSONObject().put("status", "ok")),
            "a probe the API never names" to json(200, JSONObject().put("status", "ok").put("mediaProbe", "maybe")),
            "another status" to json(200, JSONObject().put("status", "down").put("mediaProbe", "available")),
        )
        others.forEach { (what, answer) ->
            server.enqueue(answer)
            assertFalse(what, client().health().fromApi)
        }
    }
}
