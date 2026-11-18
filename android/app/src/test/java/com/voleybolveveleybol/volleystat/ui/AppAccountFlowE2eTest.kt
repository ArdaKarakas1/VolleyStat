package com.voleybolveveleybol.volleystat.ui

import android.app.Application
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.voleybolveveleybol.volleystat.AppViewModel
import com.voleybolveveleybol.volleystat.MainActivity
import com.voleybolveveleybol.volleystat.R
import com.voleybolveveleybol.volleystat.TestAccounts
import com.voleybolveveleybol.volleystat.data.ApiClient
import com.voleybolveveleybol.volleystat.data.ApiException
import com.voleybolveveleybol.volleystat.model.Settings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.UUID

/**
 * The account screens against a running backend (docs/API_V1.md section 10): a registration
 * through the form, a new display name, logout and login, a password change and the deletion, all
 * through the screens. The backend must run with VOLLEYSTAT_SIGNUP=open, as the CI jobs start it.
 * Every test opens its own e2e_ account and deletes it at the end. Skipped without VOLLEYSTAT_E2E_URL.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class AppAccountFlowE2eTest {
    // See JourneyTest: the static factory would hand the view model another test's Application.
    @get:Rule(order = 0) val freshViewModelFactory = object : ExternalResource() {
        override fun before() {
            val factory = ViewModelProvider.AndroidViewModelFactory::class.java
            factory.declaredFields.single { it.type == factory }.apply { isAccessible = true }.set(null, null)
        }
    }
    @get:Rule(order = 1) val compose = createEmptyComposeRule()

    private val url = System.getenv("VOLLEYSTAT_E2E_URL")
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    /** The account the test opens; the cleanup deletes it. */
    private var username = "e2e_" + UUID.randomUUID().toString().replace("-", "").take(20)
    /** The account's password as the server holds it now; the cleanup logs in with it. */
    private var password = "iyi bir şifre ${UUID.randomUUID().toString().take(8)}"
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun needsABackend() = assumeTrue("needs VOLLEYSTAT_E2E_URL", url != null)

    /** Whatever a test left behind: the account goes, so the backend holds no e2e_ account afterwards. */
    @After fun deleteTheAccount() {
        scenario?.close()
        if (url == null) return
        runCatching {
            val signedIn = ApiClient(url).login(username, password, null)
            ApiClient(url, token = signedIn.token).deleteAccount(password)
        }
    }

    private fun text(id: Int, vararg arguments: Any) = context.getString(id, *arguments)
    private fun shown(value: String) = compose.onAllNodesWithText(value, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(value: String) = compose.waitUntil(20_000) { shown(value) }
    private fun tap(id: Int) = compose.onAllNodesWithText(text(id)).onFirst().performClick()
    private fun button(id: Int) = compose.onNode(hasClickAction() and hasText(text(id)) and !hasAnyAncestor(isDialog()))
    private fun dialogButton(id: Int) = compose.onNode(hasClickAction() and hasText(text(id)) and hasAnyAncestor(isDialog()))
    private fun field(id: Int) = compose.onNode(hasSetTextAction() and hasText(text(id)))

    private fun launch() {
        val launched = ActivityScenario.launch(MainActivity::class.java)
        scenario = launched
        lateinit var viewModel: AppViewModel
        launched.onActivity { viewModel = ViewModelProvider(it)[AppViewModel::class.java] }
        viewModel.baseUrlOverride = url
        compose.waitForIdle()
    }

    @Test fun anAccountOpensThroughTheFormTakesANewNameAndLogsOutAndInAgain() {
        launch()
        tap(R.string.action_start)
        tap(R.string.action_create_account)
        field(R.string.field_display_name).performTextInput("E2E Kullanıcı")
        field(R.string.field_username).performTextReplacement(username)
        field(R.string.field_password).performTextInput(password)
        button(R.string.action_create_account).performClick()
        waitFor(text(R.string.home_greeting_name, "E2E Kullanıcı"))

        tap(R.string.nav_profile)
        field(R.string.field_display_name).performScrollTo().performTextReplacement("E2E Yeni Ad")
        button(R.string.action_save_name).performClick()
        waitFor(text(R.string.display_name_saved))

        button(R.string.action_logout).performScrollTo().performClick()
        dialogButton(R.string.action_logout).performClick()
        waitFor(text(R.string.login_intro))
        field(R.string.field_username).performTextReplacement(username)
        field(R.string.field_password).performTextReplacement("yanlış bir şifre 1")
        button(R.string.action_login).performClick()
        waitFor(text(R.string.login_failed))
        field(R.string.field_password).performTextReplacement(password)
        button(R.string.action_login).performClick()
        // The server kept the new name: the greeting after the login shows it.
        waitFor(text(R.string.home_greeting_name, "E2E Yeni Ad"))
    }

    @Test fun aPasswordChangeAndTheDeletionGoThroughTheProfile() {
        val (opened, secret) = TestAccounts.registerOnBackend(url!!)
        // The helper picks its own name; this test and its cleanup follow that account.
        username = opened.user.username
        password = secret
        TestAccounts.seed(context, session = TestAccounts.sessionOf(opened), settings = Settings(welcomeSeen = true, accountSeen = true, legacyHandled = true))
        launch()
        tap(R.string.nav_profile)
        button(R.string.action_change_password).performScrollTo().performClick()
        field(R.string.field_current_password).performTextInput("yanlış bir şifre 1")
        field(R.string.field_new_password).performTextInput("yeni bir şifre 2026")
        button(R.string.action_change_password).performClick()
        waitFor(text(R.string.password_wrong))
        field(R.string.field_current_password).performTextReplacement(password)
        field(R.string.field_new_password).performTextReplacement("galatasaray")
        button(R.string.action_change_password).performClick()
        waitFor(text(R.string.password_common))
        field(R.string.field_new_password).performTextReplacement("yeni bir şifre 2026")
        button(R.string.action_change_password).performClick()
        waitFor(text(R.string.password_changed))
        val old = password
        password = "yeni bir şifre 2026"
        assertEquals("the old password no longer opens the account", true, runCatching { ApiClient(url).login(username, old, null) }.isFailure)

        button(R.string.action_delete_account).performScrollTo().performClick()
        field(R.string.field_your_password).performTextInput(old)
        button(R.string.action_delete_account).performClick()
        waitFor(text(R.string.password_wrong))
        field(R.string.field_your_password).performTextReplacement(password)
        button(R.string.action_delete_account).performClick()
        waitFor(text(R.string.account_deleted))
        assertFalse(File(context.filesDir, "accounts/${opened.user.id}").exists())
        assertEquals("LOGIN_FAILED", runCatching { ApiClient(url).login(username, password, null) }.exceptionOrNull()?.let { (it as ApiException).code })
    }
}
