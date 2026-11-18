package com.voleybolveveleybol.volleystat.data

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.voleybolveveleybol.volleystat.AppViewModel
import com.voleybolveveleybol.volleystat.Entry
import com.voleybolveveleybol.volleystat.PlainSessionCipher
import com.voleybolveveleybol.volleystat.TestAccounts
import com.voleybolveveleybol.volleystat.model.Settings
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The account calls of the app against a running VolleyStat backend (docs/API_V1.md section 10).
 *
 * Skipped unless VOLLEYSTAT_E2E_URL is set, for example http://127.0.0.1:8000/v1. The backend must
 * run with VOLLEYSTAT_SIGNUP=open, as the CI jobs start it. Every test registers its own e2e_
 * account and deletes it at the end.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AccountE2eTest {
    private val url = System.getenv("VOLLEYSTAT_E2E_URL")
    private val username = "e2e_" + UUID.randomUUID().toString().replace("-", "").take(20)
    private var password = "iyi bir şifre ${UUID.randomUUID().toString().take(8)}"
    private var token: String? = null
    private val ended = CopyOnWriteArrayList<String>()

    @Before fun needsABackend() = assumeTrue("needs VOLLEYSTAT_E2E_URL", url != null)

    @After fun deleteTheAccount() {
        token?.let { runCatching { ApiClient(url!!, token = it).deleteAccount(password) } }
    }

    private fun api(token: String? = null) = ApiClient(url!!, token = token, onAuthRequired = { ended += it })

    private fun problemOf(call: () -> Unit): AuthProblem = try {
        call()
        fail("the call must fail")
        throw AssertionError()
    } catch (error: IOException) {
        AuthProblem.of(error)
    }

    @Test fun registerMeLogoutLoginWithTheDeviceTokenPasswordChangeAndDelete() {
        // Register: the server opens the account, a session and a device token for this phone.
        val opened = api().register(username, " E2E Kullanıcı ", password, null)
        token = opened.token
        assertEquals(username, opened.user.username)
        assertEquals("the server trims the display name", "E2E Kullanıcı", opened.user.displayName)
        assertFalse(opened.user.passwordTemporary)
        assertNotNull(opened.deviceToken)
        val device = opened.deviceToken!!
        assertEquals(AuthProblem.UsernameTaken, problemOf { api().register(username, "Başka Biri", "başka bir şifre 99", null) })

        // me and the display name.
        assertEquals(opened.user.id, api(opened.token).me().id)
        assertEquals("E2E Yeni Ad", api(opened.token).changeDisplayName("E2E Yeni Ad").displayName)
        assertEquals("E2E Yeni Ad", api(opened.token).me().displayName)
        assertEquals(AuthProblem.DisplayNameInvalid, problemOf { api(opened.token).changeDisplayName("x") })

        // Logout ends this session only: the next call with its token is refused and reported once.
        api(opened.token).logout()
        assertEquals(AuthProblem.SessionEnded, problemOf { api(opened.token).me() })
        assertEquals(listOf(opened.token), ended)
        token = null

        // Login with the device token: the server keeps the phone's token and answers it back.
        assertEquals(AuthProblem.LoginFailed, problemOf { api().login(username, "yanlış bir şifre 1", device) })
        val again = api().login(username, password, device)
        token = again.token
        assertEquals(device, again.deviceToken)
        assertEquals(opened.user.id, again.user.id)
        val other = api().login(username, password, device)

        // A new password ends every other session and every device token of the account.
        assertEquals(AuthProblem.PasswordWrong, problemOf { api(again.token).changePassword("yanlış bir şifre 1", "yeni bir şifre 2026") })
        assertEquals(AuthProblem.PasswordRejected(PasswordRule.TOO_SHORT), problemOf { api(again.token).changePassword(password, "kisa") })
        assertEquals(AuthProblem.PasswordRejected(PasswordRule.COMMON), problemOf { api(again.token).changePassword(password, "galatasaray") })
        assertEquals(AuthProblem.PasswordRejected(PasswordRule.REPETITIVE), problemOf { api(again.token).changePassword(password, "aaaaaaaaaa") })
        assertEquals(AuthProblem.PasswordRejected(PasswordRule.CONTAINS_USERNAME), problemOf { api(again.token).changePassword(password, "${username}12345") })
        api(again.token).changePassword(password, "yeni bir şifre 2026")
        password = "yeni bir şifre 2026"
        assertEquals(username, api(again.token).me().username)
        assertEquals(AuthProblem.SessionEnded, problemOf { api(other.token).me() })
        val afterChange = api().login(username, password, device)
        assertNotEquals("the old device token is gone, so the phone gets a new one", device, afterChange.deviceToken)
        api(afterChange.token).logout()

        // Delete: a wrong password deletes nothing, the right one deletes the account and its sessions.
        assertEquals(AuthProblem.PasswordWrong, problemOf { api(again.token).deleteAccount("yanlış bir şifre 1") })
        api(again.token).deleteAccount(password)
        token = null
        assertEquals(AuthProblem.SessionEnded, problemOf { api(again.token).me() })
        assertEquals(AuthProblem.LoginFailed, problemOf { api().login(username, password, null) })
    }

    @Test fun theAppSignsInLeavesAndComesBackToTheSameFolderAndDeletesItAll() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        TestAccounts.device(context, Settings(welcomeSeen = true))
        val vm = AppViewModel(context).also { it.baseUrlOverride = url }
        fun await(what: String, condition: () -> Boolean) {
            val end = System.currentTimeMillis() + 20_000
            while (!condition()) {
                check(System.currentTimeMillis() < end) { "timed out waiting for: $what" }
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(20)
            }
        }

        vm.register("E2E Kullanıcı", username.uppercase(), password, null)
        await("the registration") { !vm.state.value.account.busy }
        val user = vm.state.value.account.user!!
        token = SessionStore(File(context.filesDir, "session.bin"), PlainSessionCipher).load()!!.token
        assertEquals(username, user.username)
        assertEquals(Entry.HOME, vm.state.value.entry)
        vm.addTeam("E2E Takımı")
        val folder = File(context.filesDir, "accounts/${user.id}")
        assertTrue(folder.isDirectory)

        vm.logout()
        await("the logout") { vm.state.value.account.user == null && !vm.state.value.account.busy }
        assertNull(vm.state.value.account.problem)
        assertEquals(Entry.LOGIN, vm.state.value.entry)
        assertTrue(vm.state.value.teams.isEmpty())
        token = null

        vm.login(username, password)
        await("the login") { !vm.state.value.account.busy }
        assertEquals(user.id, vm.state.value.account.user?.id)
        token = SessionStore(File(context.filesDir, "session.bin"), PlainSessionCipher).load()!!.token
        assertEquals(listOf("E2E Takımı"), vm.state.value.teams.map { it.name })

        vm.deleteAccount(password)
        await("the deletion") { vm.state.value.account.user == null && !vm.state.value.account.busy }
        assertNull(vm.state.value.account.problem)
        assertFalse(folder.exists())
        token = null
        assertEquals(AuthProblem.LoginFailed, problemOf { api().login(username, password, null) })
    }

    /**
     * The third review's A1 against the real server. A stand-in between the app and the server passes
     * every request on and drops the first answer of the registration and of the login after the
     * server acted on them. The registration opens the account and its answer is lost. The repeat
     * meets the taken username, and the login that looks for the account loses its answer too. That
     * says nothing about the account, so the third try looks again and finds it.
     */
    @Test fun aLostRegistrationIsFoundEvenWhenTheLoginThatLooksForItLosesItsAnswerToo() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        TestAccounts.device(context, Settings(welcomeSeen = true))
        val toDrop = mutableSetOf("POST /auth/register", "POST /auth/login")
        val seen = CopyOnWriteArrayList<String>()
        val proxy = MockWebServer()
        proxy.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath.removePrefix("/v1")
                val key = "${request.method} $path"
                val (status, answer) = forward(request, path)
                val drop = synchronized(toDrop) { toDrop.remove(key) }
                seen += "$key $status${if (drop) " dropped" else ""}"
                return if (drop) MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) else answer
            }
        }
        proxy.start()
        try {
            val vm = AppViewModel(context).also { it.baseUrlOverride = proxy.url("/v1").toString() }
            repeat(3) { attempt ->
                val before = seen.size
                vm.register("E2E Kullanıcı", username, password, null)
                val end = System.currentTimeMillis() + 20_000
                while (vm.state.value.account.busy || seen.size == before) {
                    check(System.currentTimeMillis() < end) { "timed out waiting for the answer to try ${attempt + 1}" }
                    shadowOf(Looper.getMainLooper()).idle()
                    Thread.sleep(20)
                }
                if (attempt < 2) assertEquals("try ${attempt + 1}", AuthProblem.Unreachable, vm.state.value.account.problem)
            }
            vm.state.value.account.user?.let { token = SessionStore(File(context.filesDir, "session.bin"), PlainSessionCipher).load()?.token }
            assertEquals(
                listOf(
                    "POST /auth/register 201 dropped", "POST /auth/register 409", "POST /auth/login 200 dropped",
                    "POST /auth/register 409", "POST /auth/login 200",
                ),
                seen,
            )
            assertEquals("the user keeps the username, and no second account opens", username, vm.state.value.account.user?.username)
            assertNull(vm.state.value.account.problem)
        } finally {
            proxy.shutdown()
            // The account goes at the end of the run, also when the app did not find it.
            if (token == null) token = runCatching { api().login(username, password, null).token }.getOrNull()
        }
    }

    /** Sends [request] on to the real server and returns its status and its answer, as the stand-in passes it back. */
    private fun forward(request: RecordedRequest, path: String): Pair<Int, MockResponse> {
        val connection = URL(url!!.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = request.method
            connection.instanceFollowRedirects = false
            request.getHeader("Authorization")?.let { connection.setRequestProperty("Authorization", it) }
            val body = request.body.readByteArray()
            if (request.method != "GET") {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", request.getHeader("Content-Type") ?: "application/json")
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val text = (if (status >= 400) connection.errorStream else connection.inputStream)?.use { it.readBytes() } ?: ByteArray(0)
            val answer = MockResponse().setResponseCode(status).setBody(okio.Buffer().write(text))
            listOf("Content-Type", "WWW-Authenticate", "Retry-After").forEach { name -> connection.getHeaderField(name)?.let { answer.setHeader(name, it) } }
            return status to answer
        } finally {
            connection.disconnect()
        }
    }
}
