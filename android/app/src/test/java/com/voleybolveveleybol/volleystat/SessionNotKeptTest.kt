package com.voleybolveveleybol.volleystat

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.voleybolveveleybol.volleystat.data.AccountUser
import com.voleybolveveleybol.volleystat.data.SessionCipher
import com.voleybolveveleybol.volleystat.data.SessionCipherSource
import com.voleybolveveleybol.volleystat.model.Settings
import com.voleybolveveleybol.volleystat.ui.screens.HomeScreen
import com.voleybolveveleybol.volleystat.ui.theme.VolleyStatTheme
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.security.GeneralSecurityException

/** A phone whose keystore can seal nothing: every seal and every open fails. */
class UnsealableApplication : Application(), SessionCipherSource {
    override val sessionCipher: SessionCipher = object : SessionCipher {
        override fun seal(plain: ByteArray): ByteArray = throw GeneralSecurityException("Keystore operation failed")
        override fun open(sealed: ByteArray): ByteArray = throw GeneralSecurityException("Keystore operation failed")
    }
}

/**
 * A login whose session the phone could not seal (review finding S3). The user stays signed in
 * until the app ends, and the app says that truthfully instead of a text about a full disk.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = UnsealableApplication::class, qualifiers = "w411dp-h891dp-xxhdpi")
class SessionNotKeptTest {
    @get:Rule val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val server = MockWebServer()

    @Before fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(
                JSONObject().put("user", JSONObject().put("id", "user-ali").put("username", "ali").put("displayName", "Ali"))
                    .put("session", JSONObject().put("token", "token-ali").put("expiresAt", "2026-12-17T10:00:00Z")).put("deviceToken", "device-ali").toString()
            )
        }
        server.start()
    }

    @After fun stop() = server.shutdown()

    private fun await(what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "timed out waiting for: $what" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    @Test fun aSessionThePhoneCouldNotSealIsNamedAsSuchAndNotAsAFullDisk() {
        TestAccounts.device(context, Settings(welcomeSeen = true, legacyHandled = true))
        val vm = AppViewModel(context).also { it.baseUrlOverride = server.url("/v1").toString() }
        vm.login("ali", "uzun bir şifre 42")
        await("the login") { !vm.state.value.account.busy && vm.state.value.account.user != null }
        assertTrue(vm.state.value.account.sessionNotKept)
        assertFalse("the disk was not the problem", vm.state.value.saveFailed)
        assertFalse(File(context.filesDir, "session.bin").exists())
        // What the text promises: the next start asks for the password.
        assertNull(AppViewModel(context).state.value.account.user)
    }

    @Test fun homeSaysThatTheNextStartAsksForThePassword() {
        val state = AppUiState(
            settings = Settings(welcomeSeen = true, accountSeen = true, legacyHandled = true),
            account = AccountUi(user = AccountUser("user-ali", "ali", "Ali"), sessionNotKept = true),
        )
        compose.setContent { VolleyStatTheme(dark = false) { HomeScreen(state, {}, {}, {}, {}, {}) } }
        val text = ApplicationProvider.getApplicationContext<Context>().getString(R.string.session_not_kept)
        compose.onNodeWithText(text).assertIsDisplayed()
        val disk = ApplicationProvider.getApplicationContext<Context>().getString(R.string.store_save_failed)
        assertTrue(compose.onAllNodesWithText(disk).fetchSemanticsNodes().isEmpty())
    }
}
