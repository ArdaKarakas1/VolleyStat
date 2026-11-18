package com.voleybolveveleybol.volleystat.data

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONException
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
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The account calls of docs/API_V1.md section 10, the session header on every call, and the
 * typed problem each server answer maps to.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AuthApiTest {
    private val server = MockWebServer()
    private val ended = CopyOnWriteArrayList<String>()

    @Before fun start() = server.start()
    @After fun stop() = server.shutdown()

    private val base get() = server.url("/v1").toString()
    private fun client(token: String? = null) = ApiClient(base, token = token, onAuthRequired = { ended += it })

    private fun json(status: Int, body: JSONObject, headers: Map<String, String> = emptyMap()) = MockResponse().setResponseCode(status)
        .setHeader("Content-Type", "application/json").setBody(body.toString()).apply { headers.forEach { (k, v) -> setHeader(k, v) } }

    /** The API's error envelope. Every 401 of the API names the Bearer scheme, as the server sends it. */
    private fun error(status: Int, code: String, details: JSONObject = JSONObject(), headers: Map<String, String> = emptyMap()) =
        json(status, JSONObject().put("error", JSONObject().put("code", code).put("message", code).put("details", details)),
            if (status == 401) headers + ("WWW-Authenticate" to "Bearer") else headers)

    private fun user(name: String = "Çağrı Öztürk", temporary: Boolean = false) = JSONObject().put("id", "user-1").put("username", "cagri.ozturk")
        .put("displayName", name).put("createdAt", "2026-11-17T10:00:00Z").put("passwordTemporary", temporary)

    private fun signedIn(device: String? = "device-1") = JSONObject().put("user", user())
        .put("session", JSONObject().put("token", "session-token-1").put("expiresAt", "2026-12-17T10:00:00Z"))
        .put("deviceToken", device ?: JSONObject.NULL)

    private fun body(request: RecordedRequest) = JSONObject(request.body.readUtf8())

    // ----- the calls -----

    @Test fun registerSendsTheFieldsAndReadsTheSessionTheServerOpened() {
        server.enqueue(json(201, signedIn()))
        val result = client().register("cagri.ozturk", "Çağrı Öztürk", "uzun bir şifre 42", "ABCD-EFGH-JKMN-PQRS")
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/auth/register", request.path)
        assertNull("no session yet, so no header", request.getHeader("Authorization"))
        val sent = body(request)
        assertEquals(setOf("username", "displayName", "password", "operatorCode"), sent.keys().asSequence().toSet())
        assertEquals("cagri.ozturk", sent.getString("username"))
        assertEquals("Çağrı Öztürk", sent.getString("displayName"))
        assertEquals("uzun bir şifre 42", sent.getString("password"))
        assertEquals("ABCD-EFGH-JKMN-PQRS", sent.getString("operatorCode"))
        assertEquals(AccountUser("user-1", "cagri.ozturk", "Çağrı Öztürk", passwordTemporary = false), result.user)
        assertEquals("session-token-1", result.token)
        assertEquals("2026-12-17T10:00:00Z", result.expiresAt)
        assertEquals("device-1", result.deviceToken)
        assertFalse("a printed result never shows the token", result.toString().contains("session-token-1"))
        assertFalse(result.toString().contains("device-1"))
    }

    @Test fun registerWithoutACodeSendsNoCodeField() {
        server.enqueue(json(201, signedIn()))
        client().register("ali", "Ali", "uzun bir şifre 42", null)
        assertFalse(body(server.takeRequest()).has("operatorCode"))
    }

    @Test fun loginSendsTheDeviceTokenOnlyWhenThePhoneHasOne() {
        server.enqueue(json(200, signedIn()))
        server.enqueue(json(200, signedIn(device = null)))
        client().login("cagri.ozturk", "uzun bir şifre 42", "device-1")
        val first = server.takeRequest()
        assertEquals("/v1/auth/login", first.path)
        assertEquals(JSONObject().put("username", "cagri.ozturk").put("password", "uzun bir şifre 42").put("deviceToken", "device-1").toString(), body(first).toString())
        val second = client().login("cagri.ozturk", "uzun bir şifre 42", null)
        assertFalse(body(server.takeRequest()).has("deviceToken"))
        assertNull(second.deviceToken)
    }

    @Test fun theAccountCallsOfASignedInPhone() {
        server.enqueue(json(200, JSONObject().put("user", user()).put("teams", org.json.JSONArray())))
        server.enqueue(json(200, JSONObject().put("user", user("Çağrı Ö."))))
        server.enqueue(json(200, JSONObject().put("user", user())))
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(204))
        val api = client("session-token-1")

        assertEquals("cagri.ozturk", api.me().username)
        assertEquals("Çağrı Ö.", api.changeDisplayName("Çağrı Ö.").displayName)
        api.changePassword("eski şifre 12345", "yeni şifre 67890")
        api.deleteAccount("yeni şifre 67890")
        api.logout()

        val me = server.takeRequest()
        assertEquals("GET /v1/me", "${me.method} ${me.path}")
        val name = server.takeRequest()
        assertEquals("PUT /v1/me/display-name", "${name.method} ${name.path}")
        assertEquals(JSONObject().put("displayName", "Çağrı Ö.").toString(), body(name).toString())
        val password = server.takeRequest()
        assertEquals("PUT /v1/me/password", "${password.method} ${password.path}")
        assertEquals(JSONObject().put("currentPassword", "eski şifre 12345").put("newPassword", "yeni şifre 67890").toString(), body(password).toString())
        val delete = server.takeRequest()
        assertEquals("POST /v1/me/delete", "${delete.method} ${delete.path}")
        assertEquals(JSONObject().put("password", "yeni şifre 67890").toString(), body(delete).toString())
        val logout = server.takeRequest()
        assertEquals("POST /v1/auth/logout", "${logout.method} ${logout.path}")
        listOf(me, name, password, delete, logout).forEach {
            assertEquals("Bearer session-token-1", it.getHeader("Authorization"))
            assertFalse("no token in an address", it.path!!.contains("session-token-1"))
        }
    }

    @Test fun logoutAndDeletionCountOnlyWithTheRoutesOwn204() {
        server.enqueue(json(200, JSONObject()))
        try {
            client("t").logout()
            fail("a 200 is not the answer of the logout route")
        } catch (expected: ApiException) {
            assertEquals("UNEXPECTED_ANSWER", expected.code)
        }
        server.enqueue(json(200, JSONObject()))
        try {
            client("t").deleteAccount("şifre")
            fail("a 200 is not the answer of the delete route")
        } catch (expected: ApiException) {
            assertEquals("UNEXPECTED_ANSWER", expected.code)
        }
    }

    @Test fun everyCallOfASignedInPhoneCarriesTheHeaderAndNoAddressCarriesTheToken() {
        repeat(3) { server.enqueue(json(200, JSONObject().put("status", "ok").put("maxVideoBytes", 10).put("mediaProbe", "available"))) }
        client("session-token-1").health()
        client().health()
        val signedIn = server.takeRequest()
        assertEquals("Bearer session-token-1", signedIn.getHeader("Authorization"))
        assertNull(server.takeRequest().getHeader("Authorization"))
        assertFalse(client("session-token-1").videoContentUrl("video-1").contains("session-token-1"))
    }

    @Test fun anEndedSessionIsReportedOnceWithTheTokenThatCarriedTheCall() {
        server.enqueue(error(401, "AUTH_REQUIRED", headers = mapOf("WWW-Authenticate" to "Bearer")))
        try {
            client("old-token").me()
            fail("401 must throw")
        } catch (expected: ApiException) {
            assertEquals("AUTH_REQUIRED", expected.code)
        }
        assertEquals(listOf("old-token"), ended)
    }

    @Test fun aWrongPasswordAtLoginIsNotAnEndedSession() {
        server.enqueue(error(401, "LOGIN_FAILED"))
        server.enqueue(error(401, "AUTH_REQUIRED"))
        runCatching { client("token").login("ali", "yanlış şifre 1234", null) }
        // A call without a session that gets AUTH_REQUIRED ends no session either.
        runCatching { client().me() }
        assertTrue(ended.isEmpty())
    }

    @Test fun a401WithoutItsBodyIsReadByTheContractOnlyWhenItNamesBearer() {
        // The JVM drops the body of a 401 to a streamed request; Android keeps it. The API's 401 always names Bearer.
        val bare = MockResponse().setResponseCode(401).setHeader("WWW-Authenticate", "Bearer")
        assertEquals(AuthProblem.LoginFailed, problemOf(bare))
        assertTrue(ended.isEmpty())
        assertEquals(AuthProblem.SessionEnded, problemOf(MockResponse().setResponseCode(401).setHeader("WWW-Authenticate", "Bearer")) { it.me() })
        assertEquals(listOf("token"), ended)
        // A 401 that is not the API's (no Bearer, no envelope) ends no session and means nothing more.
        assertEquals(AuthProblem.UnexpectedAnswer, problemOf(MockResponse().setResponseCode(401).setHeader("WWW-Authenticate", "Basic realm=\"x\"")) { it.me() })
        assertEquals(AuthProblem.UnexpectedAnswer, problemOf(MockResponse().setResponseCode(401)) { it.me() })
        assertEquals(listOf("token"), ended)
    }

    @Test fun retryAfterIsReadFromTheHeader() {
        server.enqueue(error(503, "SERVER_BUSY", headers = mapOf("Retry-After" to "2")))
        try {
            client().login("ali", "uzun bir şifre 42", null)
            fail()
        } catch (expected: ApiException) {
            assertEquals(2, expected.retryAfterSeconds)
        }
    }

    // ----- what each answer means -----

    private fun problemOf(answer: MockResponse, call: (ApiClient) -> Unit = { it.login("ali", "uzun bir şifre 42", null) }): AuthProblem {
        server.enqueue(answer)
        return try {
            call(client("token"))
            fail("the call must fail")
            throw AssertionError()
        } catch (error: IOException) {
            AuthProblem.of(error)
        }
    }

    @Test fun everyAccountErrorOfTheServerHasItsOwnProblem() {
        assertEquals(AuthProblem.LoginFailed, problemOf(error(401, "LOGIN_FAILED")))
        assertEquals(AuthProblem.SessionEnded, problemOf(error(401, "AUTH_REQUIRED")) { it.me() })
        assertEquals(AuthProblem.SessionEnded, problemOf(error(401, "AUTH_REQUIRED")) { it.logout() })
        assertEquals(AuthProblem.SignupCodeInvalid, problemOf(error(403, "SIGNUP_CODE_INVALID")))
        assertEquals(AuthProblem.LinkNotFound, problemOf(error(404, "LINK_NOT_FOUND")))
        assertEquals(AuthProblem.UsernameTaken, problemOf(error(409, "USERNAME_TAKEN")))
        assertEquals(AuthProblem.UsernameInvalid, problemOf(error(422, "USERNAME_INVALID")))
        assertEquals(AuthProblem.DisplayNameInvalid, problemOf(error(422, "DISPLAY_NAME_INVALID")))
        assertEquals(AuthProblem.PasswordWrong, problemOf(error(403, "PASSWORD_WRONG")))
        assertEquals(AuthProblem.Refused(422, "VALIDATION_ERROR"), problemOf(error(422, "VALIDATION_ERROR")))
        // A 5xx may come after the server made the change, so it is no refusal that changed nothing (third review, B3).
        assertEquals(AuthProblem.ServerFailed(500, withEnvelope = true), problemOf(error(500, "INTERNAL")))
    }

    @Test fun aServerErrorWithOrWithoutTheEnvelopeLeavesTheOutcomeUnknown() {
        assertEquals(AuthProblem.ServerFailed(500, withEnvelope = true), problemOf(error(500, "INTERNAL")) { it.deleteAccount("uzun bir şifre 42") })
        // Without the API's envelope a gateway on the way may have sent it (fourth review, D4).
        assertEquals(AuthProblem.ServerFailed(502, withEnvelope = false), problemOf(MockResponse().setResponseCode(502).setBody("Bad Gateway")))
        assertEquals(AuthProblem.ServerFailed(500, withEnvelope = false), problemOf(MockResponse().setResponseCode(500)) { it.changePassword("eski bir şifre 1", "yeni bir şifre 2") })
        // SERVER_BUSY comes before the server counts or writes anything: the view model asks again.
        assertEquals(AuthProblem.ServerBusy(1), problemOf(error(503, "SERVER_BUSY", headers = mapOf("Retry-After" to "1"))))
    }

    // ----- the fourth review's D2: a request that never left the phone -----

    @Test fun aRequestThatCouldNotConnectIsOneThatNeverLeftThePhone() {
        val port = java.net.ServerSocket(0).use { it.localPort }
        // Nothing listens on the port (connection refused), and a name under .invalid never resolves.
        listOf("http://127.0.0.1:$port/v1", "http://volleystat-test.invalid/v1").forEach { address ->
            val error = runCatching { ApiClient(address).register("ali", "Ali", "uzun bir şifre 42", null) }.exceptionOrNull()
            assertTrue("$address: $error", error is RequestNotSent)
            assertEquals("the form says no more than before", AuthProblem.Unreachable, AuthProblem.of(error!!))
            assertFalse("the server can not have acted on it", outcomeUnknownAfter(error))
        }
        // The fifth check's P5: an address the phone can not even read (a build without a server address) sends nothing either.
        listOf("", "not a url").forEach { address ->
            val error = runCatching { ApiClient(address).register("ali", "Ali", "uzun bir şifre 42", null) }.exceptionOrNull()
            assertTrue("'$address': $error", error is RequestNotSent)
            assertEquals(AuthProblem.Unreachable, AuthProblem.of(error!!))
            assertFalse(outcomeUnknownAfter(error))
        }
    }

    @Test fun aRequestWhoseAnswerWasLostIsOneThatLeftThePhone() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        val error = runCatching { client().register("ali", "Ali", "uzun bir şifre 42", null) }.exceptionOrNull()
        assertTrue("$error", error is IOException && error !is RequestNotSent && error !is ApiException)
        assertEquals("the server got the whole request", "ali", body(server.takeRequest()).getString("username"))
        assertTrue(outcomeUnknownAfter(error!!))
        // A connection the server closes at once was made: the app can not tell whether the request was read.
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val closed = runCatching { client().register("ali", "Ali", "uzun bir şifre 42", null) }.exceptionOrNull()
        assertTrue("$closed", closed is IOException && closed !is RequestNotSent)
    }

    @Test fun anAnswerLeavesTheOutcomeOpenOnlyWhenTheServerMayHaveActed() {
        fun failure(status: Int, code: String) = ApiException(status, code, code, JSONObject())
        assertTrue(outcomeUnknownAfter(failure(500, "INTERNAL")))
        assertTrue(outcomeUnknownAfter(failure(502, "HTTP_502")))
        assertTrue(outcomeUnknownAfter(failure(200, "UNEXPECTED_ANSWER")))
        assertTrue(outcomeUnknownAfter(failure(302, "HTTP_302")))
        assertFalse("SERVER_BUSY comes before the server acts", outcomeUnknownAfter(failure(503, "SERVER_BUSY")))
        assertFalse(outcomeUnknownAfter(failure(409, "USERNAME_TAKEN")))
        assertFalse(outcomeUnknownAfter(failure(401, "AUTH_REQUIRED")))
        assertTrue("no answer after the request went out", outcomeUnknownAfter(IOException("lost")))
        assertTrue(outcomeUnknownAfter(JSONException("unreadable")))
        assertFalse(outcomeUnknownAfter(RequestNotSent(java.net.ConnectException("refused"))))
        assertFalse(outcomeUnknownAfter(AnswerFromAnotherServer()))
    }

    /**
     * The sixth checker's Q2: a 4xx without the API's envelope (a proxy's page, say) is an answer the app can not read. A change
     * of the user's own account leaves its outcome open after it. A registration marks no username after it, because the mark
     * would send a login to the account that holds that username. A 401 that names the Bearer scheme reads as AUTH_REQUIRED.
     */
    @Test fun a4xxWithoutTheEnvelopeLeavesAChangeOpenAndMarksNoRegistration() {
        fun failureOf(answer: MockResponse, call: (ApiClient) -> Unit): ApiException {
            server.enqueue(answer)
            return try {
                call(client("token"))
                throw AssertionError("the call must fail")
            } catch (error: ApiException) {
                error
            }
        }
        fun page(status: Int) = MockResponse().setResponseCode(status).setHeader("Content-Type", "text/html").setBody("<html>$status</html>")
        val errors = listOf(404, 413, 429, 401).map { status -> failureOf(page(status)) { it.changePassword("eski bir şifre 1", "yeni bir şifre 2") } }
        assertEquals(listOf("HTTP_404", "HTTP_413", "HTTP_429", "HTTP_401"), errors.map { it.code })
        assertEquals("the outcome of a change stays open", errors.map { it.code to true }, errors.map { it.code to outcomeUnknownAfter(it) })
        assertEquals("the registration marks no username", errors.map { it.code to false }, errors.map { it.code to it.leavesOutcomeUnknown })
        val bearer = failureOf(page(401).setHeader("WWW-Authenticate", "Bearer realm=\"gw\"")) { it.changePassword("eski bir şifre 1", "yeni bir şifre 2") }
        assertEquals("AUTH_REQUIRED", bearer.code)
        assertFalse(outcomeUnknownAfter(bearer))
        // The API's own envelope makes a 4xx the server's answer, which came before it changed anything.
        assertFalse(outcomeUnknownAfter(failureOf(error(404, "NOT_FOUND")) { it.changeDisplayName("Yeni Ad") }))
        assertFalse(outcomeUnknownAfter(failureOf(error(422, "PASSWORD_REJECTED")) { it.changePassword("eski bir şifre 1", "kısa") }))
    }

    @Test fun aClosedSignUpNamesItsReason() {
        assertEquals(AuthProblem.SignupClosed(SignupClosedReason.NO_LINK), problemOf(error(403, "SIGNUP_CLOSED", JSONObject().put("reason", "NO_LINK"))))
        assertEquals(AuthProblem.SignupClosed(SignupClosedReason.OPEN_MATCH_LINK), problemOf(error(403, "SIGNUP_CLOSED", JSONObject().put("reason", "OPEN_MATCH_LINK"))))
        // B1 sends no reason yet; the gate without a link is the only one it has.
        assertEquals(AuthProblem.SignupClosed(SignupClosedReason.NO_LINK), problemOf(error(403, "SIGNUP_CLOSED")))
    }

    @Test fun aRefusedPasswordNamesTheRuleItBroke() {
        mapOf(
            "TOO_SHORT" to PasswordRule.TOO_SHORT, "TOO_LONG" to PasswordRule.TOO_LONG, "CONTAINS_USERNAME" to PasswordRule.CONTAINS_USERNAME,
            "REPETITIVE" to PasswordRule.REPETITIVE, "COMMON" to PasswordRule.COMMON, "SOMETHING_NEW" to PasswordRule.UNKNOWN,
        ).forEach { (reason, rule) ->
            assertEquals(AuthProblem.PasswordRejected(rule), problemOf(error(422, "PASSWORD_REJECTED", JSONObject().put("reason", reason))))
        }
    }

    @Test fun tooManyAttemptsNamesWhoseAttemptsAndHowLongToWait() {
        mapOf(
            "USER" to AttemptScope.USER, "DEVICE" to AttemptScope.DEVICE, "ACCOUNT" to AttemptScope.ACCOUNT,
            "SOURCE" to AttemptScope.SOURCE, "REGISTER_SOURCE" to AttemptScope.REGISTER_SOURCE, "NEW_SCOPE" to AttemptScope.UNKNOWN,
        ).forEach { (scope, expected) ->
            val problem = problemOf(error(429, "TOO_MANY_ATTEMPTS", JSONObject().put("retryAfterSeconds", 60).put("scope", scope), mapOf("Retry-After" to "60")))
            assertEquals(AuthProblem.TooManyAttempts(expected, 60), problem)
        }
        // Without details the header still tells the wait.
        assertEquals(AuthProblem.TooManyAttempts(AttemptScope.UNKNOWN, 30), problemOf(error(429, "TOO_MANY_ATTEMPTS", headers = mapOf("Retry-After" to "30"))))
        assertEquals(AuthProblem.TooManyAttempts(AttemptScope.UNKNOWN, null), problemOf(error(429, "TOO_MANY_ATTEMPTS")))
    }

    @Test fun aBusyServerSaysWhenToAskAgain() {
        assertEquals(AuthProblem.ServerBusy(1), problemOf(error(503, "SERVER_BUSY", headers = mapOf("Retry-After" to "1"))))
    }

    @Test fun aServerWithoutAccountsIsAnOlderServer() {
        // A server before 0.8 has no /v1/auth: FastAPI answers its own 404 NOT_FOUND.
        assertEquals(AuthProblem.ServerOutdated, problemOf(error(404, "NOT_FOUND")))
        assertEquals(AuthProblem.ServerOutdated, problemOf(error(405, "METHOD_NOT_ALLOWED")))
        // A 404 without the API's error envelope comes from something else on that address.
        assertEquals(AuthProblem.UnexpectedAnswer, problemOf(MockResponse().setResponseCode(404)))
    }

    @Test fun noAnswerAndAnAnswerThatIsNotThisApiAreToldApart() {
        assertEquals(AuthProblem.Unreachable, problemOf(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)))
        assertEquals(AuthProblem.UnexpectedAnswer, problemOf(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/html").setBody("<html>portal</html>")))
        // JSON without the fields of a session.
        assertEquals(AuthProblem.UnexpectedAnswer, problemOf(json(200, JSONObject().put("ok", true))))
        assertEquals(AuthProblem.UnexpectedAnswer, AuthProblem.of(JSONException("missing")))
    }
}
