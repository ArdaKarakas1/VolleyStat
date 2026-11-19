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
