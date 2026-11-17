package com.voleybolveveleybol.volleystat.data

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.voleybolveveleybol.volleystat.AppViewModel
import com.voleybolveveleybol.volleystat.Entry
import com.voleybolveveleybol.volleystat.PlainSessionCipher
import com.voleybolveveleybol.volleystat.TestAccounts
import com.voleybolveveleybol.volleystat.model.Settings
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
}
