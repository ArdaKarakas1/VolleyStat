package com.voleybolveveleybol.volleystat.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
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
import com.voleybolveveleybol.volleystat.data.LocalStore
import com.voleybolveveleybol.volleystat.data.StoredState
import com.voleybolveveleybol.volleystat.model.LocalVideo
import com.voleybolveveleybol.volleystat.model.MatchRecord
import com.voleybolveveleybol.volleystat.model.Settings
import com.voleybolveveleybol.volleystat.model.Team
import com.voleybolveveleybol.volleystat.model.UploadSession
import com.voleybolveveleybol.volleystat.model.VideoSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

private const val PASSWORD = "uzun bir şifre 42"

/**
 * The account screens wired into the real activity, navigation, view model and files (design
 * 10.1), against a stand-in of the account routes: where the app opens, the way from the welcome
 * to Home, the end of a session, logout, deletion, the old data and two accounts on one phone.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class AccountFlowTest {
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

    @After fun stop() {
        scenario?.close()
        standIn.close()
    }

    private fun text(id: Int, vararg arguments: Any) = context.getString(id, *arguments)
    private fun shown(value: String) = compose.onAllNodesWithText(value, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(value: String) = compose.waitUntil(10_000) { shown(value) }
    private fun tap(id: Int) = compose.onAllNodesWithText(text(id)).onFirst().performClick()
    private fun button(id: Int) = compose.onNode(hasClickAction() and hasText(text(id)) and !hasAnyAncestor(isDialog()))
    private fun dialogButton(id: Int) = compose.onNode(hasClickAction() and hasText(text(id)) and hasAnyAncestor(isDialog()))
    private fun field(id: Int) = compose.onNode(hasSetTextAction() and hasText(text(id)))
    private fun greeting(name: String) = text(R.string.home_greeting_name, name)

    /** Starts the app on whatever the test put on the phone, pointed at the stand-in. */
    private fun launch(): AppViewModel {
        val launched = ActivityScenario.launch(MainActivity::class.java)
        scenario = launched
        lateinit var viewModel: AppViewModel
        launched.onActivity { viewModel = ViewModelProvider(it)[AppViewModel::class.java] }
        viewModel.baseUrlOverride = standIn.baseUrl
        compose.waitForIdle()
        return viewModel
    }

    private fun logIn(username: String, password: String = PASSWORD) {
        field(R.string.field_username).performTextReplacement(username)
        field(R.string.field_password).performTextReplacement(password)
        button(R.string.action_login).performClick()
    }

    private fun video(name: String) = LocalVideo("/none/$name.mp4", VideoSource.GALLERY, "$name.mp4", 10, "video/mp4", null, null, null, null)

    @Test fun aFirstStartGoesFromTheWelcomeThroughTheStartScreenAndARegistrationToHome() {
        launch()
        compose.onNodeWithText(text(R.string.welcome_today)).assertIsDisplayed()
        tap(R.string.action_start)
        compose.onNodeWithText(text(R.string.start_body)).assertIsDisplayed()
        assertFalse("the start screen has no tab bar", shown(text(R.string.nav_matches)))

        tap(R.string.action_create_account)
        compose.onNodeWithText(text(R.string.register_data)).assertIsDisplayed()
        field(R.string.field_display_name).performTextInput("Çağrı Öztürk")
        field(R.string.field_password).performTextInput(PASSWORD)
        button(R.string.action_create_account).performClick()

        waitFor(greeting("Çağrı Öztürk"))
        assertTrue(standIn.exists("cagri.ozturk"))
        assertEquals("POST /auth/register" to null, standIn.requests.single { it.first == "POST /auth/register" })
        compose.onAllNodesWithText(text(R.string.nav_matches)).onFirst().assertIsDisplayed()

        // A restart opens Home: the session is on the phone.
        scenario!!.recreate()
        waitFor(greeting("Çağrı Öztürk"))
        assertFalse(shown(text(R.string.welcome_today)))
        assertFalse(shown(text(R.string.start_body)))
    }

    @Test fun theStartScreenLeadsToTheLoginAndTheTwoFormsLeadToEachOther() {
        TestAccounts.device(context, Settings(welcomeSeen = true))
        launch()
        compose.onNodeWithText(text(R.string.start_body)).assertIsDisplayed()
        tap(R.string.action_login)
        compose.onNodeWithText(text(R.string.login_intro)).assertIsDisplayed()
        button(R.string.action_create_account).performClick()
        compose.onNodeWithText(text(R.string.register_data)).assertIsDisplayed()
        button(R.string.action_have_account).performClick()
        compose.onNodeWithText(text(R.string.login_intro)).assertIsDisplayed()
    }

    @Test fun aPhoneThatSawAnAccountOpensOnTheLoginAndAWrongPasswordKeepsTheForm() {
        TestAccounts.device(context, Settings(welcomeSeen = true, accountSeen = true, legacyHandled = true))
        standIn.add("ali", "Ali Yılmaz", PASSWORD)
        launch()
        compose.onNodeWithText(text(R.string.login_intro)).assertIsDisplayed()
        logIn("Ali", "yanlış bir şifre")
        waitFor(text(R.string.login_failed))
        compose.onNodeWithText(text(R.string.login_intro)).assertIsDisplayed()
        logIn("Ali")
        waitFor(greeting("Ali Yılmaz"))
        assertFalse(shown(text(R.string.login_failed)))
    }

    @Test fun aLogoutLeadsToTheLoginAndBackLeavesTheAppInsteadOfReturningToTheAccount() {
        standIn.add(TestAccounts.SESSION.username, TestAccounts.SESSION.displayName, PASSWORD, token = TestAccounts.SESSION.token, id = TestAccounts.SESSION.userId)
        TestAccounts.seed(context)
        launch()
        waitFor(greeting(TestAccounts.SESSION.displayName))
        tap(R.string.nav_profile)
        button(R.string.action_logout).performScrollTo().performClick()
        dialogButton(R.string.action_logout).performClick()
        waitFor(text(R.string.login_intro))
        assertFalse(shown(text(R.string.session_ended)))
        assertNull(standIn.requests.firstOrNull { it.first == "POST /auth/logout" && it.second != "Bearer ${TestAccounts.SESSION.token}" })
        var finishing = false
        scenario!!.onActivity {
            it.onBackPressedDispatcher.onBackPressed()
            finishing = it.isFinishing
        }
        assertTrue("the account's screens are not below the login", finishing)
    }

    @Test fun anEndedSessionLeadsToTheLoginWithItsReasonAndTheNextLoginClearsIt() {
        standIn.add(TestAccounts.SESSION.username, TestAccounts.SESSION.displayName, PASSWORD, token = TestAccounts.SESSION.token, id = TestAccounts.SESSION.userId)
        TestAccounts.seed(context)
        launch()
        waitFor(greeting(TestAccounts.SESSION.displayName))
        // The profile reads the account from the server. The server answers that the session ended.
        standIn.meStatus = 401
        tap(R.string.nav_profile)
        waitFor(text(R.string.session_ended))
        compose.onNodeWithText(text(R.string.login_intro)).assertIsDisplayed()
        assertFalse(File(context.filesDir, "session.bin").exists())
        standIn.meStatus = null
        logIn(TestAccounts.SESSION.username)
        waitFor(greeting(TestAccounts.SESSION.displayName))
        assertFalse(shown(text(R.string.session_ended)))
    }

    @Test fun theOldDataQuestionComesAfterTheFirstLoginOnceAndMovesTheMatches() {
        TestAccounts.device(context, Settings(welcomeSeen = true))
        LocalStore(File(context.filesDir, "volleystat.json")).save(StoredState(matches = listOf(MatchRecord("old", "Ev", "Konuk", 1L, video("old"), name = "Eski Maç"))))
        standIn.add("ali", "Ali Yılmaz", PASSWORD)
        launch()
        tap(R.string.action_login)
        logIn("ali")
        waitFor(text(R.string.legacy_body))
        compose.onNodeWithText("Eski Maç").assertIsDisplayed()
        assertFalse("no tab bar before the answer", shown(text(R.string.nav_matches)))
        tap(R.string.legacy_move)
        waitFor(greeting("Ali Yılmaz"))
        tap(R.string.nav_matches)
        waitFor("Eski Maç")
        assertFalse(File(context.filesDir, "volleystat.json").exists())

        scenario!!.recreate()
        waitFor(text(R.string.nav_matches))
        assertFalse(shown(text(R.string.legacy_body)))
    }

    @Test fun aNewDisplayNameReachesTheGreetingAndADeletionEndsOnTheLoginWithNothingLeft() {
        standIn.add(TestAccounts.SESSION.username, TestAccounts.SESSION.displayName, PASSWORD, token = TestAccounts.SESSION.token, id = TestAccounts.SESSION.userId)
        val onServer = MatchRecord("m1", "Ev", "Konuk", 1L, video("m1"), name = "Hazırlık Maçı", session = UploadSession("sha", "server-m1", "upload-m1", 0))
        TestAccounts.seed(context, StoredState(matches = listOf(onServer)))
        launch()
        tap(R.string.nav_profile)
        field(R.string.field_display_name).performScrollTo().performTextReplacement("Yeni Ad")
        button(R.string.action_save_name).performClick()
        waitFor(text(R.string.display_name_saved))
        assertEquals("Yeni Ad", standIn.displayName(TestAccounts.SESSION.username))
        tap(R.string.nav_home)
        waitFor(greeting("Yeni Ad"))

        tap(R.string.nav_profile)
        button(R.string.action_delete_account).performScrollTo().performClick()
        // The list holds one match the server has: the screen says the server keeps it.
        compose.onNodeWithText(text(R.string.delete_account_server_matches, 1)).assertIsDisplayed()
        field(R.string.field_your_password).performTextInput("yanlış bir şifre")
        button(R.string.action_delete_account).performClick()
        waitFor(text(R.string.password_wrong))
        assertTrue(standIn.exists(TestAccounts.SESSION.username))
        field(R.string.field_your_password).performTextReplacement(PASSWORD)
        button(R.string.action_delete_account).performClick()
        waitFor(text(R.string.account_deleted))
        compose.onNodeWithText(text(R.string.login_intro)).assertIsDisplayed()
        assertFalse(standIn.exists(TestAccounts.SESSION.username))
        assertFalse(File(context.filesDir, "accounts/${TestAccounts.SESSION.userId}").exists())
    }

    @Test fun aSecondAccountOnThePhoneSeesNoneOfTheFirstAccountsMatches() {
        standIn.add(TestAccounts.SESSION.username, TestAccounts.SESSION.displayName, PASSWORD, token = TestAccounts.SESSION.token, id = TestAccounts.SESSION.userId)
        standIn.add("veli", "Veli Kaya", PASSWORD)
        TestAccounts.seed(context, StoredState(matches = listOf(MatchRecord("m1", "Ev", "Konuk", 1L, video("m1"), name = "Birinci Hesabın Maçı"))))
        launch()
        tap(R.string.nav_matches)
        waitFor("Birinci Hesabın Maçı")

        tap(R.string.nav_profile)
        button(R.string.action_logout).performScrollTo().performClick()
        dialogButton(R.string.action_logout).performClick()
        waitFor(text(R.string.login_intro))
        logIn("veli")
        waitFor(greeting("Veli Kaya"))
        tap(R.string.nav_matches)
        waitFor(text(R.string.matches_empty_title))
        assertFalse(shown("Birinci Hesabın Maçı"))

        tap(R.string.nav_profile)
        button(R.string.action_logout).performScrollTo().performClick()
        dialogButton(R.string.action_logout).performClick()
        waitFor(text(R.string.login_intro))
        logIn(TestAccounts.SESSION.username)
        waitFor(greeting(TestAccounts.SESSION.displayName))
        tap(R.string.nav_matches)
        waitFor("Birinci Hesabın Maçı")
    }

    // ----- review findings F4, F8 and F9 -----

    @Test fun aMoveOfTheOldDataThatFailsTwiceCanStillBeTriedAgain() {
        TestAccounts.device(context, Settings(welcomeSeen = true, accountSeen = true))
        LocalStore(File(context.filesDir, "volleystat.json")).save(StoredState(teams = listOf(Team("t-old", "Eski Takım"))))
        standIn.add("ali", "Ali Yılmaz", PASSWORD, id = "user-ali")
        // A file stands where the account's folder would be, so nothing can be written into it.
        val blocker = File(context.filesDir, "accounts/user-ali").apply { parentFile!!.mkdirs(); writeText("") }
        launch()
        logIn("ali")
        waitFor(text(R.string.legacy_body))
        repeat(2) { attempt ->
            button(R.string.legacy_move).performClick()
            waitFor(text(R.string.store_save_failed))
            // Each failed move gives the buttons back, the second one too.
            compose.waitUntil(5_000) { runCatching { button(R.string.legacy_move).assertIsEnabled() }.isSuccess }
            button(R.string.legacy_keep).assertIsEnabled()
            assertTrue("attempt $attempt left the old data where it is", File(context.filesDir, "volleystat.json").exists())
        }
        blocker.delete()
        button(R.string.legacy_move).performClick()
        waitFor(greeting("Ali Yılmaz"))
        tap(R.string.nav_teams)
        waitFor("Eski Takım")
    }

    @Test fun aRefusedLoginWhoseAnswerArrivesAfterTheScreenWasRecreatedIsStillShown() {
        TestAccounts.device(context, Settings(welcomeSeen = true, accountSeen = true, legacyHandled = true))
        standIn.add("ali", "Ali Yılmaz", PASSWORD)
        val held = java.util.concurrent.CountDownLatch(1)
        standIn.hold["POST /auth/login"] = held
        val before = launch()
        logIn("ali", "yanlış bir şifre")
        compose.waitUntil(10_000) { standIn.requests.any { it.first == "POST /auth/login" } }
        // A dark theme switch, a language change or a larger font recreates the screen while the answer is on its way.
        scenario!!.recreate()
        lateinit var after: AppViewModel
        scenario!!.onActivity { after = ViewModelProvider(it)[AppViewModel::class.java] }
        assertTrue("the screen came back to the same view model", before === after)
        held.countDown()
        waitFor(text(R.string.login_failed))
        compose.onNodeWithText(text(R.string.login_intro)).assertIsDisplayed()
    }

    @Test fun leavingTheLoginStopsTheRepeatsOfABusyServer() {
        TestAccounts.device(context, Settings(welcomeSeen = true))
        standIn.add("ali", "Ali Yılmaz", PASSWORD)
        standIn.answers["POST /auth/login"] = { standIn.busy(1) }
        launch()
        tap(R.string.action_login)
        logIn("ali")
        compose.waitUntil(10_000) { standIn.requests.count { it.first == "POST /auth/login" } == 1 }
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText(text(R.string.start_body)).assertIsDisplayed()
        // The server asked for a wait of one second. The screen that sent the login is gone, so no repeat goes out.
        Thread.sleep(1_800)
        compose.waitForIdle()
        assertEquals(1, standIn.requests.count { it.first == "POST /auth/login" })
    }

    /** Review finding T5: while the app waits out the server's Retry-After, the login says the server is busy, then logs in. */
    @Test fun aLoginThatMeetsABusyServerSaysSoWhileTheAppWaitsToSendItAgain() {
        TestAccounts.device(context, Settings(welcomeSeen = true))
        standIn.add("ali", "Ali Yılmaz", PASSWORD)
        standIn.answers["POST /auth/login"] = { standIn.answers.remove("POST /auth/login"); standIn.busy(2) }
        launch()
        tap(R.string.action_login)
        logIn("ali")
        waitFor(text(R.string.server_busy))
        assertFalse(shown(text(R.string.account_waiting)))
        waitFor(greeting("Ali Yılmaz"))
        assertEquals(2, standIn.requests.count { it.first == "POST /auth/login" })
        assertFalse(shown(text(R.string.server_busy)))
    }

    @Test fun aConfirmedPasswordChangeClosesItsScreenAndTheProfileSaysSo() {
        standIn.add(TestAccounts.SESSION.username, TestAccounts.SESSION.displayName, PASSWORD, token = TestAccounts.SESSION.token, id = TestAccounts.SESSION.userId)
        TestAccounts.seed(context)
        launch()
        tap(R.string.nav_profile)
        button(R.string.action_change_password).performScrollTo().performClick()
        field(R.string.field_current_password).performTextInput(PASSWORD)
        field(R.string.field_new_password).performTextInput("yeni ve uzun şifre 77")
        button(R.string.action_change_password).performClick()
        waitFor(text(R.string.password_changed))
        compose.onNodeWithText(text(R.string.profile_account)).assertIsDisplayed()
        assertFalse("the password screen closed", shown(text(R.string.field_current_password)))
    }
}
