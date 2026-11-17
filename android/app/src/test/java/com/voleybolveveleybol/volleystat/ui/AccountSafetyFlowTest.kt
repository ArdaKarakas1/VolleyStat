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
import com.voleybolveveleybol.volleystat.AccountStandIn
import com.voleybolveveleybol.volleystat.AppViewModel
import com.voleybolveveleybol.volleystat.MainActivity
import com.voleybolveveleybol.volleystat.R
import com.voleybolveveleybol.volleystat.TestAccounts
import com.voleybolveveleybol.volleystat.data.StoredState
import com.voleybolveveleybol.volleystat.model.Team
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The whole app after a deletion whose answer was lost (review finding S1): the repeat finds the
 * account gone, the login screen says what the app does not know, and Telefondan Sil removes the
 * account's folder only after the user confirms.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class AccountSafetyFlowTest {
    // See JourneyTest: the static factory would hand the view model another test's Application.
    @get:Rule(order = 0) val freshViewModelFactory = object : ExternalResource() {
        override fun before() {
            val factory = ViewModelProvider.AndroidViewModelFactory::class.java
            factory.declaredFields.single { it.type == factory }.apply { isAccessible = true }.set(null, null)
        }
    }
    @get:Rule(order = 1) val compose = createEmptyComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val standIn = AccountStandIn()
    private var scenario: ActivityScenario<MainActivity>? = null
    private val password = "uzun bir şifre 42"

    @After fun stop() {
        scenario?.close()
        standIn.close()
    }

    private fun text(id: Int, vararg arguments: Any) = context.getString(id, *arguments)
    private fun shown(value: String) = compose.onAllNodesWithText(value, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(value: String) = compose.waitUntil(10_000) { shown(value) }
    private fun button(id: Int) = compose.onNode(hasClickAction() and hasText(text(id)) and !hasAnyAncestor(isDialog()))
    private fun dialogButton(id: Int) = compose.onNode(hasClickAction() and hasText(text(id)) and hasAnyAncestor(isDialog()))

    private fun launch() {
        val launched = ActivityScenario.launch(MainActivity::class.java)
        scenario = launched
        lateinit var viewModel: AppViewModel
        launched.onActivity { viewModel = ViewModelProvider(it)[AppViewModel::class.java] }
        viewModel.baseUrlOverride = standIn.baseUrl
        compose.waitForIdle()
    }

    @Test fun aDeletionWhoseAnswerWasLostEndsOnTheLoginWithTheOfferAndTheConfirmedOfferEmptiesTheFolder() {
        val user = TestAccounts.SESSION
        standIn.add(user.username, user.displayName, password, token = user.token, id = user.userId)
        TestAccounts.seed(context, StoredState(teams = listOf(Team("t1", "A Takımı"))))
        val folder = File(context.filesDir, "accounts/${user.userId}")
        launch()

        compose.onAllNodesWithText(text(R.string.nav_profile)).onFirst().performClick()
        button(R.string.action_delete_account).performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasText(text(R.string.field_your_password))).performTextInput(password)
        standIn.loseNextAnswerOf = "POST /me/delete"
        button(R.string.action_delete_account).performClick()
        waitFor(text(R.string.net_no_answer))
        assertFalse("the server deleted the account, and the phone does not know it", standIn.exists(user.username))
        assertTrue(folder.exists())

        button(R.string.action_delete_account).performClick()
        val offer = text(R.string.deletion_unconfirmed, user.username, text(R.string.action_delete_from_phone))
        waitFor(offer)
        assertFalse(shown(text(R.string.account_deleted)))
        assertTrue("nothing goes before the user says so", folder.exists())

        button(R.string.action_delete_from_phone).performScrollTo().performClick()
        dialogButton(R.string.action_delete_from_phone).performClick()
        waitFor(text(R.string.phone_data_deleted))
        assertFalse(folder.exists())
        assertFalse(shown(offer))
    }
}

/**
 * The password change in the whole app (review finding S6): the password manager hears of the new
 * password once, after the server took it, while the screen that closes on that answer still holds
 * its fields. A refusal commits nothing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class PasswordChangeAutofillFlowTest {
    private val standIn = AccountStandIn()
    private val password = "uzun bir şifre 42"

    @get:Rule(order = 0) val seeded = object : ExternalResource() {
        override fun before() {
            // See JourneyTest: the static factory would hand the view model another test's Application.
            val factory = ViewModelProvider.AndroidViewModelFactory::class.java
            factory.declaredFields.single { it.type == factory }.apply { isAccessible = true }.set(null, null)
            val user = TestAccounts.SESSION
            standIn.add(user.username, user.displayName, password, token = user.token, id = user.userId)
            TestAccounts.seed(ApplicationProvider.getApplicationContext())
        }

        override fun after() = standIn.close()
    }
    @get:Rule(order = 1) val app = androidx.compose.ui.test.junit4.createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private fun text(id: Int) = app.activity.getString(id)
    private fun waitFor(value: String) = app.waitUntil(10_000) { app.onAllNodesWithText(value, substring = true).fetchSemanticsNodes().isNotEmpty() }
    private fun field(id: Int) = app.onNode(hasSetTextAction() and hasText(text(id)))
    private fun button(id: Int) = app.onNode(hasClickAction() and hasText(text(id)) and !hasAnyAncestor(isDialog()))

    @Test fun theAutofillIsCommittedOnceAndOnlyAfterTheServerChangedThePassword() {
        var commits = 0
        var cancels = 0
        ViewModelProvider(app.activity)[AppViewModel::class.java].baseUrlOverride = standIn.baseUrl
        app.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                com.voleybolveveleybol.volleystat.ui.screens.LocalAutofillCommit provides { commits++ },
                com.voleybolveveleybol.volleystat.ui.screens.LocalAutofillCancel provides { cancels++ },
            ) { com.voleybolveveleybol.volleystat.ui.theme.VolleyStatTheme { com.voleybolveveleybol.volleystat.VolleyStatApp() } }
        }
        app.onAllNodesWithText(text(R.string.nav_profile)).onFirst().performClick()
        button(R.string.action_change_password).performScrollTo().performClick()
        field(R.string.field_current_password).performTextInput("yanlış bir şifre")
        field(R.string.field_new_password).performTextInput("yeni bir şifre 2")
        button(R.string.action_change_password).performClick()
        waitFor(text(R.string.password_wrong))
        assertEquals("a refused change commits nothing", 0, commits)

        field(R.string.field_current_password).performTextReplacement(password)
        button(R.string.action_change_password).performClick()
        waitFor(text(R.string.password_changed))
        app.waitForIdle()
        assertEquals(1, commits)
        assertEquals("the closed screen cancels nothing it committed", 0, cancels)
    }
}
