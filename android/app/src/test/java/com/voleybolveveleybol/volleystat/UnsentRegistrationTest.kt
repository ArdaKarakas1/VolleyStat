package com.voleybolveveleybol.volleystat

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.voleybolveveleybol.volleystat.data.AuthProblem
import com.voleybolveveleybol.volleystat.model.Settings
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The fourth review's D2 (the reader's probes): a registration that could not even connect never reached
 * the server, so the app does not mark it as one the server may have done. A later USERNAME_TAKEN for that
 * username then sends no login with the typed password to whoever owns the username.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UnsentRegistrationTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val files get() = context.filesDir
    private var server: MockWebServer? = null

    /** username to password, as the server holds them. */
    private val accounts = ConcurrentHashMap<String, String>()
    /** "METHOD path username" of every request that reached the server. */
    private val seen = CopyOnWriteArrayList<String>()

    @After fun stop() { server?.shutdown() }

    private fun ok(json: JSONObject, status: Int = 200) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(json.toString())
    private fun refuse(status: Int, code: String) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json")
        .setBody(JSONObject().put("error", JSONObject().put("code", code).put("message", code).put("details", JSONObject())).toString())
        .apply { if (status == 401) setHeader("WWW-Authenticate", "Bearer") }

    private fun signedIn(username: String, status: Int) = ok(
        JSONObject().put("user", JSONObject().put("id", "user-${username.replace('.', '-')}").put("username", username).put("displayName", "Ali")
            .put("createdAt", "2026-11-17T10:00:00Z").put("passwordTemporary", false))
            .put("session", JSONObject().put("token", "token-$username-${seen.size}").put("expiresAt", "2099-01-01T00:00:00Z"))
            .put("deviceToken", "device-$username"),
        status,
    )

    /** A small server of the account routes on [port]. */
    private fun startServer(port: Int) {
        val started = MockWebServer()
        started.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val key = "${request.method} ${request.requestUrl!!.encodedPath.removePrefix("/v1")}"
                val body = request.body.readUtf8().ifEmpty { "{}" }.let(::JSONObject)
                val username = body.optString("username")
                seen += "$key $username"
                return when (key) {
                    "POST /auth/register" -> if (accounts.containsKey(username)) refuse(409, "USERNAME_TAKEN") else {
                        accounts[username] = body.getString("password")
                        signedIn(username, 201)
                    }
                    "POST /auth/login" -> if (accounts[username] != body.getString("password")) refuse(401, "LOGIN_FAILED") else signedIn(username, 200)
                    "POST /auth/logout" -> MockResponse().setResponseCode(204)
                    else -> refuse(404, "NOT_FOUND")
                }
            }
        }
        started.start(port)
        server = started
    }

    private fun await(what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 15_000
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "timed out waiting for: $what" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        repeat(20) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
    }

    private fun AppViewModel.registerAndWait(username: String, password: String) {
        register("Ali", username, password, null)
        await("the registration of $username") { !state.value.account.busy }
    }

    /**
     * A registration that never left the phone (nothing listened at the address: connection refused) is not
     * marked. Before the fix it was, and the mark outlived another account's sign-up, a logout and a restart:
     * when someone else had taken the username meanwhile, the next try of it sent the typed password as a
     * login to that stranger's account, and the server counted a failed attempt against it.
     */
    @Test fun aRegistrationThatNeverReachedTheServerSendsNoLaterLoginToTheAccountOfAStranger() {
        TestAccounts.device(context, Settings(welcomeSeen = true))
        val port = ServerSocket(0).use { it.localPort }
        val address = "http://127.0.0.1:$port/v1"
        val mine = "benim uzun şifrem 12"

        // 1. Nothing listens on the address yet: the request can not even connect.
        val first = AppViewModel(context).also { it.baseUrlOverride = address }
        first.registerAndWait("ali", mine)
        assertEquals(AuthProblem.Unreachable, first.state.value.account.problem)
        assertEquals("no request reached any server", 0, seen.size)
        assertFalse("a registration that never left the phone is not marked", File(files, "registrations.bin").exists())

        // 2. The server comes up. Meanwhile a stranger registered "ali" with a password of their own.
        startServer(port)
        accounts["ali"] = "yabancının şifresi 99"

        // 3. The user picks another name, signs up, and later logs out.
        first.registerAndWait("ali.k", mine)
        assertEquals("ali.k", first.state.value.account.user?.username)
        first.logout()
        await("the logout") { first.state.value.account.user == null && !first.state.value.account.busy }

        // 4. Days later, after a restart, someone on this phone tries the name "ali" again.
        val restarted = AppViewModel(context).also { it.baseUrlOverride = address }
        restarted.registerAndWait("ali", mine)
        assertEquals(AuthProblem.UsernameTaken, restarted.state.value.account.problem)
        assertFalse(
            "a login with the typed password went to the stranger's account \"ali\": $seen",
            seen.contains("POST /auth/login ali"),
        )
    }

    /**
     * The same case without any wait: "ali" has been a stranger's username all along. The first try can not
     * connect (the app knows that nothing left the phone); the next try meets USERNAME_TAKEN and sends no login
     * to the stranger's account.
     */
    @Test fun aRegistrationThatCouldNotConnectSendsNoLoginToTheOwnerOfATakenUsername() {
        TestAccounts.device(context, Settings(welcomeSeen = true))
        val port = ServerSocket(0).use { it.localPort }
        val vm = AppViewModel(context).also { it.baseUrlOverride = "http://127.0.0.1:$port/v1" }
        vm.registerAndWait("ali", "benim uzun şifrem 12")
        assertEquals(AuthProblem.Unreachable, vm.state.value.account.problem)
        accounts["ali"] = "yabancının şifresi 99"
        startServer(port)
        vm.registerAndWait("ali", "benim uzun şifrem 12")
        assertEquals(AuthProblem.UsernameTaken, vm.state.value.account.problem)
        assertFalse("a login with the typed password went to the owner of \"ali\": $seen", seen.contains("POST /auth/login ali"))
    }
}
