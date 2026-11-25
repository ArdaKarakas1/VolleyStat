package com.voleybolveveleybol.volleystat

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A stand-in for the account routes of docs/API_V1.md section 10, for the screen tests that walk
 * the whole app. It keeps its accounts in memory, answers as the real server does on the cases the
 * tests use, and records every request. The real server's behaviour is tested by the backend suite
 * and by the E2E tests.
 */
class AccountStandIn : AutoCloseable {
    private class Account(val id: String, val username: String, var displayName: String, var password: String, var temporary: Boolean = false)

    private val server = MockWebServer()
    private val accounts = ConcurrentHashMap<String, Account>()
    private val sessions = ConcurrentHashMap<String, String>()
    private var counter = 0

    /** Every request as "METHOD /path" with its Authorization header. */
    val requests = CopyOnWriteArrayList<Pair<String, String?>>()

    /** The answer to GET /me while set, instead of the account: a test makes the session end there. */
    @Volatile var meStatus: Int? = null

    /** A route as "METHOD /path": the server does what the next request of it asks, and its answer never reaches the phone. */
    @Volatile var loseNextAnswerOf: String? = null

    /** Routes as "METHOD /path" whose requests wait for their latch before the stand-in reads them. The request is recorded on arrival. */
    val hold = ConcurrentHashMap<String, CountDownLatch>()

    /**
     * The sign-up mode health names in `accounts.signup` (slice B4). OPEN is the default the CI backend runs; null leaves
     * `accounts` out, as a server before 0.8 does. The stand-in's registration opens accounts whatever this says: a test
     * that needs a closed gate answers POST /auth/register through [answers].
     */
    @Volatile var signup: String? = "OPEN"

    /** Routes as "METHOD /path" that answer this way instead of as the stand-in would, while they are in here. */
    val answers = ConcurrentHashMap<String, () -> MockResponse>()

    val baseUrl: String get() = server.url("/v1").toString()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val key = "${request.method} ${request.requestUrl!!.encodedPath.removePrefix("/v1")}"
                requests += key to request.getHeader("Authorization")
                hold[key]?.await(15, TimeUnit.SECONDS)
                return synchronized(this@AccountStandIn) {
                    val answer = answers[key]?.invoke() ?: route(request)
                    if (loseNextAnswerOf != key) return@synchronized answer
                    loseNextAnswerOf = null
                    MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST)
                }
            }
        }
        server.start()
    }

    override fun close() = server.shutdown()

    /** Opens an account and a session for it, as a registration on another phone would. */
    fun add(username: String, displayName: String, password: String, temporary: Boolean = false, token: String? = null, id: String? = null): String {
        val account = Account(id ?: "user-${++counter}", username, displayName, password, temporary)
        accounts[username] = account
        val session = token ?: "token-${account.id}-${++counter}"
        sessions[session] = username
        return session
    }

    fun userId(username: String): String = accounts.getValue(username).id

    fun displayName(username: String): String? = accounts[username]?.displayName

    fun exists(username: String) = accounts.containsKey(username)

    /** Whether [password] is the password the stand-in now holds for [username]. */
    fun hasPassword(username: String, password: String) = accounts[username]?.password == password

    private fun route(request: RecordedRequest): MockResponse {
        val path = request.requestUrl!!.encodedPath.removePrefix("/v1")
        val authorization = request.getHeader("Authorization")
        val body = request.body.readUtf8().ifEmpty { "{}" }.let(::JSONObject)
        val signedIn = authorization?.removePrefix("Bearer ")?.let { sessions[it] }?.let { accounts[it] }
        return when ("${request.method} $path") {
            "GET /health" -> ok(
                JSONObject().put("status", "ok").put("mediaProbe", "available").put("maxVideoBytes", 1000)
                    .apply { signup?.let { put("accounts", JSONObject().put("signup", it)) } },
            )
            "POST /auth/register" -> {
                val username = body.getString("username")
                if (accounts.containsKey(username)) return refuse(409, "USERNAME_TAKEN")
                add(username, body.getString("displayName"), body.getString("password"))
                signIn(accounts.getValue(username), 201)
            }
            "POST /auth/login" -> {
                val account = accounts[body.getString("username")]
                if (account == null || account.password != body.getString("password")) refuse(401, "LOGIN_FAILED") else signIn(account, 200)
            }
            "POST /auth/logout" -> if (signedIn == null) refuse(401, "AUTH_REQUIRED") else {
                sessions.remove(authorization.removePrefix("Bearer "))
                MockResponse().setResponseCode(204)
            }
            "GET /me" -> meStatus?.let { refuse(it, if (it == 401) "AUTH_REQUIRED" else "INTERNAL") }
                ?: if (signedIn == null) refuse(401, "AUTH_REQUIRED") else ok(JSONObject().put("user", user(signedIn)).put("teams", org.json.JSONArray()))
            "PUT /me/display-name" -> when {
                signedIn == null -> refuse(401, "AUTH_REQUIRED")
                body.getString("displayName").trim().length < 2 -> refuse(422, "DISPLAY_NAME_INVALID")
                else -> {
                    signedIn.displayName = body.getString("displayName").trim()
                    ok(JSONObject().put("user", user(signedIn)))
                }
            }
            "PUT /me/password" -> when {
                signedIn == null -> refuse(401, "AUTH_REQUIRED")
                body.getString("currentPassword") != signedIn.password -> refuse(403, "PASSWORD_WRONG")
                body.getString("newPassword").length < 10 -> refuse(422, "PASSWORD_REJECTED", JSONObject().put("reason", "TOO_SHORT"))
                else -> {
                    signedIn.password = body.getString("newPassword")
                    signedIn.temporary = false
                    val own = authorization!!.removePrefix("Bearer ")
                    sessions.keys.removeIf { it != own && sessions[it] == signedIn.username }
                    ok(JSONObject().put("user", user(signedIn)))
                }
            }
            "POST /me/delete" -> when {
                signedIn == null -> refuse(401, "AUTH_REQUIRED")
                body.getString("password") != signedIn.password -> refuse(403, "PASSWORD_WRONG")
                else -> {
                    accounts.remove(signedIn.username)
                    sessions.values.removeIf { it == signedIn.username }
                    MockResponse().setResponseCode(204)
                }
            }
            else -> refuse(404, "NOT_FOUND")
        }
    }

    private fun signIn(account: Account, status: Int): MockResponse {
        val token = "token-${account.id}-${++counter}"
        sessions[token] = account.username
        return ok(
            JSONObject().put("user", user(account)).put("session", JSONObject().put("token", token).put("expiresAt", "2099-01-01T00:00:00Z"))
                .put("deviceToken", "device-${account.id}"),
            status,
        )
    }

    private fun user(account: Account) = JSONObject().put("id", account.id).put("username", account.username).put("displayName", account.displayName)
        .put("createdAt", "2026-11-17T10:00:00Z").put("passwordTemporary", account.temporary)

    private fun ok(json: JSONObject, status: Int = 200) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(json.toString())

    /** SERVER_BUSY as the real server sends it, with the wait it names. */
    fun busy(retryAfterSeconds: Int) = refuse(503, "SERVER_BUSY").setHeader("Retry-After", retryAfterSeconds.toString())

    /** The API's error envelope. A 401 names the Bearer scheme, as the real server does. */
    fun refuse(status: Int, code: String, details: JSONObject = JSONObject()) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json")
        .setBody(JSONObject().put("error", JSONObject().put("code", code).put("message", code).put("details", details)).toString())
        .apply { if (status == 401) setHeader("WWW-Authenticate", "Bearer") }
}
