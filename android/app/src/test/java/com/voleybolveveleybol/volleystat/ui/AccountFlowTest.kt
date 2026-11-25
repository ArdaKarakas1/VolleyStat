package com.voleybolveveleybol.volleystat.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
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
import androidx.compose.ui.test.performImeAction
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
        // The mode arrives from a health read off the main thread, which the compose rule does not wait for (slice B4).
        waitFor(text(R.string.start_body))
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
        waitFor(text(R.string.start_body))
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
        // The list holds one match that went to the server. Server 0.9 deletes it with the account, unless an older
        // server stored it: then it belongs to no account and stays. The app can not tell which, and the screen says so.
        compose.onNodeWithText(text(R.string.delete_account_body)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.delete_account_legacy)).performScrollTo().assertIsDisplayed()
        assertFalse(shown("hesaplara bağlamıyor"))
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
        waitFor(text(R.string.start_body))
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
        assertFalse("its own screen handed the new password over", shown(text(R.string.password_not_handed_over)))
    }

    /** The second review's R2-D: a change confirmed after Android rebuilt its screen reached no password manager, and the profile says so. */
    @Test fun aPasswordChangeConfirmedAfterTheScreenWasRecreatedSaysThePasswordManagerDidNotGetIt() {
        standIn.add(TestAccounts.SESSION.username, TestAccounts.SESSION.displayName, PASSWORD, token = TestAccounts.SESSION.token, id = TestAccounts.SESSION.userId)
        TestAccounts.seed(context)
        val held = java.util.concurrent.CountDownLatch(1)
        standIn.hold["PUT /me/password"] = held
        launch()
        tap(R.string.nav_profile)
        button(R.string.action_change_password).performScrollTo().performClick()
        field(R.string.field_current_password).performTextInput(PASSWORD)
        field(R.string.field_new_password).performTextInput("yeni ve uzun şifre 77")
        button(R.string.action_change_password).performClick()
        compose.waitUntil(10_000) { standIn.requests.any { it.first == "PUT /me/password" } }
        // A dark theme switch rebuilds the screen while the change is on its way; the call goes on (F8).
        scenario!!.recreate()
        held.countDown()
        waitFor(text(R.string.password_changed))
        compose.onNodeWithText(text(R.string.password_not_handed_over)).performScrollTo().assertIsDisplayed()
        assertFalse("the password screen closed", shown(text(R.string.field_current_password)))
    }

    /**
     * The wiring tells the view model nothing when Android rebuilds the password screen: the user did not leave it, and the
     * rebuilt screen names its own reason at the confirmation (R2-D). The test reads the view model at the moment the
     * server's confirmation reached it, before the main thread runs the rebuilt screen again: no reason stands there yet.
     */
    @Test fun aPasswordScreenThatAndroidRebuildsNeverTellsTheViewModelThatTheUserLeft() {
        val (vm, held) = sendAHeldPasswordChange()
        scenario!!.recreate()
        compose.waitForIdle()
        held.countDown()
        // The confirmation reaches the view model off the main thread; the test thread is the main thread and only waits.
        val deadline = System.currentTimeMillis() + 10_000
        while (vm.state.value.account.confirmed != com.voleybolveveleybol.volleystat.AccountChange.PASSWORD && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(com.voleybolveveleybol.volleystat.AccountChange.PASSWORD, vm.state.value.account.confirmed)
        assertNull("the rebuild said that the user left", vm.state.value.account.newPasswordNotHandedOver)
        // The rebuilt screen then names the rebuild.
        waitFor(text(R.string.password_changed))
        compose.onNodeWithText(text(R.string.password_not_handed_over)).performScrollTo().assertIsDisplayed()
        assertEquals(com.voleybolveveleybol.volleystat.PasswordNotHandedOver.SCREEN_REBUILT, vm.state.value.account.newPasswordNotHandedOver)
    }

    /** Opens Şifreyi Değiştir from the profile and sends a change the stand-in holds until the test lets it go. */
    private fun sendAHeldPasswordChange(newPassword: String = "yeni ve uzun şifre 77"): Pair<AppViewModel, java.util.concurrent.CountDownLatch> {
        standIn.add(TestAccounts.SESSION.username, TestAccounts.SESSION.displayName, PASSWORD, token = TestAccounts.SESSION.token, id = TestAccounts.SESSION.userId)
        TestAccounts.seed(context)
        val held = java.util.concurrent.CountDownLatch(1)
        standIn.hold["PUT /me/password"] = held
        val vm = launch()
        tap(R.string.nav_profile)
        button(R.string.action_change_password).performScrollTo().performClick()
        field(R.string.field_current_password).performTextInput(PASSWORD)
        field(R.string.field_new_password).performTextInput(newPassword)
        button(R.string.action_change_password).performClick()
        compose.waitUntil(10_000) { standIn.requests.any { it.first == "PUT /me/password" } }
        return vm to held
    }

    /**
     * The third review's B1: the user goes back while the change is on its way. The request goes on and the server
     * changes the password. The screen that could commit it is gone, and the profile says so.
     */
    @Test fun aPasswordChangeLeftBySystemBackWhileOnItsWaySaysThePasswordManagerDidNotGetIt() {
        val (vm, held) = sendAHeldPasswordChange()
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertFalse("the password screen is gone", shown(text(R.string.field_current_password)))
        held.countDown()
        waitFor(text(R.string.password_changed))
        compose.onNodeWithText(text(R.string.password_not_handed_over_left)).performScrollTo().assertIsDisplayed()
        assertFalse(shown(text(R.string.password_not_handed_over)))
        assertEquals(com.voleybolveveleybol.volleystat.PasswordNotHandedOver.SCREEN_LEFT, vm.state.value.account.newPasswordNotHandedOver)
        assertEquals("the server changed the password once", 1, standIn.requests.count { it.first == "PUT /me/password" })
    }

    @Test fun aPasswordChangeLeftByTheBackArrowWhileOnItsWaySaysThePasswordManagerDidNotGetIt() {
        val (vm, held) = sendAHeldPasswordChange()
        compose.onNode(hasClickAction() and androidx.compose.ui.test.hasContentDescription(text(R.string.action_back))).performClick()
        compose.waitForIdle()
        assertFalse("the password screen is gone", shown(text(R.string.field_current_password)))
        held.countDown()
        waitFor(text(R.string.password_changed))
        compose.onNodeWithText(text(R.string.password_not_handed_over_left)).performScrollTo().assertIsDisplayed()
        assertEquals(com.voleybolveveleybol.volleystat.PasswordNotHandedOver.SCREEN_LEFT, vm.state.value.account.newPasswordNotHandedOver)
    }

    /** The third review's B2: the new password is edited while the change waits. The server got the first text, so nothing is committed. */
    @Test fun aNewPasswordEditedWhileTheChangeWaitsIsNamedOnTheProfile() {
        val (vm, held) = sendAHeldPasswordChange("yeni ve uzun şifre 77")
        field(R.string.field_new_password).performTextReplacement("başka ve uzun şifre 88")
        held.countDown()
        waitFor(text(R.string.password_changed))
        compose.onNodeWithText(text(R.string.password_not_handed_over_edited)).performScrollTo().assertIsDisplayed()
        assertEquals(com.voleybolveveleybol.volleystat.PasswordNotHandedOver.PASSWORD_EDITED, vm.state.value.account.newPasswordNotHandedOver)
        assertTrue("the server holds the text that was sent", standIn.hasPassword(TestAccounts.SESSION.username, "yeni ve uzun şifre 77"))
    }

    /** Runs the main looper until [condition] holds: the view model ends its calls on the main thread. */
    private fun settleUntil(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 15_000
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
    }

    /**
     * The fourth review's D1 (the reader's probe): the user leaves Şifreyi Değiştir while the change is on its way.
     * The server changes the password and its answer is lost. The call goes on after the leave, and the profile says
     * that no answer came, that the app does not know whether the server changed the password, and that the password
     * manager may still hold the old one. Before, the cancelled call dropped its outcome and the profile said nothing.
     */
    @Test fun aChangeLeftWhileOnItsWayWhoseAnswerIsLostIsNotPassedOverInSilence() {
        val (vm, held) = sendAHeldPasswordChange()
        standIn.loseNextAnswerOf = "PUT /me/password"
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertFalse("the password screen is gone", shown(text(R.string.field_current_password)))
        held.countDown()
        settleUntil { !vm.state.value.account.busy }
        compose.waitForIdle()
        assertTrue("the server holds the new password", standIn.hasPassword(TestAccounts.SESSION.username, "yeni ve uzun şifre 77"))
        val anyWord = listOf(
            text(R.string.password_changed), text(R.string.net_no_answer), text(R.string.account_outcome_unknown),
            text(R.string.password_not_handed_over_left),
        ).filter { shown(it) }
        assertTrue("the profile says nothing about a change the server may have made (and did)", anyWord.isNotEmpty())
        assertEquals(
            com.voleybolveveleybol.volleystat.OutcomeAfterLeave(com.voleybolveveleybol.volleystat.AccountChange.PASSWORD, com.voleybolveveleybol.volleystat.data.AuthProblem.Unreachable, outcomeUnknown = true),
            vm.state.value.account.afterLeave,
        )
        compose.onNodeWithText(text(R.string.net_no_answer)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.password_left_outcome_unknown)).performScrollTo().assertIsDisplayed()
        assertFalse("the server confirmed nothing the app got", shown(text(R.string.password_changed)))
        assertEquals("the request went out once", 1, standIn.requests.count { it.first == "PUT /me/password" })
    }

    /**
     * The fourth review's D3 (the reader's probe): the keyboard's Done key sends the change too, and then nobody tapped
     * Şifreyi Değiştir. The note about an edited new password names the sending, not a tap of the button.
     */
    @Test fun aChangeSentFromTheKeyboardIsNotNamedAsATapOfTheButton() {
        standIn.add(TestAccounts.SESSION.username, TestAccounts.SESSION.displayName, PASSWORD, token = TestAccounts.SESSION.token, id = TestAccounts.SESSION.userId)
        TestAccounts.seed(context)
        val vm = launch()
        tap(R.string.nav_profile)
        button(R.string.action_change_password).performScrollTo().performClick()
        field(R.string.field_current_password).performTextInput(PASSWORD)
        val held = java.util.concurrent.CountDownLatch(1)
        standIn.hold["PUT /me/password"] = held
        field(R.string.field_new_password).performTextInput("yeni ve uzun şifre 77")
        // The keyboard's Done key sends the change; the button is never tapped.
        field(R.string.field_new_password).performImeAction()
        compose.waitUntil(10_000) { standIn.requests.any { it.first == "PUT /me/password" } }
        field(R.string.field_new_password).performTextReplacement("başka ve uzun şifre 88")
        held.countDown()
        settleUntil { vm.state.value.account.confirmed == com.voleybolveveleybol.volleystat.AccountChange.PASSWORD && !vm.state.value.account.busy }
        compose.waitForIdle()
        assertEquals(com.voleybolveveleybol.volleystat.PasswordNotHandedOver.PASSWORD_EDITED, vm.state.value.account.newPasswordNotHandedOver)
        val note = text(R.string.password_not_handed_over_edited)
        compose.onNodeWithText(note).performScrollTo().assertIsDisplayed()
        assertFalse("the profile says that the user tapped a button nobody tapped: $note", note.contains("düğmesine dokunduğunda"))
        assertTrue(note, note.contains("Sen değişikliği gönderdiğinde"))
    }

    /**
     * The third review's B5: a release build has no server address (fail closed), so it can neither open an account
     * nor log in. The start screen says so instead of the promise, and its buttons open no form that can not work.
     */
    @Test fun aBuildWithoutAServerSaysSoOnTheStartScreenAndOpensNoForm() {
        TestAccounts.device(context, Settings(welcomeSeen = true))
        val vm = launch()
        waitFor(text(R.string.start_body))
        compose.onNodeWithText(text(R.string.start_body)).assertIsDisplayed()
        scenario!!.onActivity {
            vm.baseUrlOverride = ""
            vm.setServerTarget(com.voleybolveveleybol.volleystat.model.ServerTarget.EMULATOR)
        }
        compose.waitForIdle()
        assertFalse(vm.state.value.serverConfigured)
        compose.onNodeWithText(text(R.string.server_none)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.start_no_server)).assertIsDisplayed()
        assertFalse("the start screen promises nothing the build can not do", shown(text(R.string.start_body)))
        assertFalse(shown(text(R.string.start_body_link)))
        assertFalse(shown(text(R.string.start_body_any, text(R.string.action_create_account))))
        button(R.string.action_create_account).assertIsNotEnabled().performClick()
        button(R.string.action_login).assertIsNotEnabled().performClick()
        compose.waitForIdle()
        assertFalse(shown(text(R.string.register_data)))
        assertFalse(shown(text(R.string.login_intro)))
        compose.onNodeWithText(text(R.string.start_no_server)).assertIsDisplayed()
    }

    // ----- slice B4: the start screen and the register form follow health's sign-up mode (design 10.1, D4, F3 of the v5.1 checker) -----

    private fun codeFieldShown() = compose.onAllNodes(hasSetTextAction() and hasText(text(R.string.field_signup_code))).fetchSemanticsNodes().isNotEmpty()
    private fun healthReads() = standIn.requests.count { it.first == "GET /health" }

    /**
     * F3 of the v5.1 checker, named in the B4 row: while health has not answered, a server started with `link` sits behind
     * the screens, and nothing that holds only under OPEN may show. Once health answers LINK the screens say what LINK means.
     */
    @Test fun withHealthUnreadAndALinkServerBehindItTheStartAndRegisterScreensShowNoOpenOnlyText() {
        TestAccounts.device(context, Settings(welcomeSeen = true))
        standIn.signup = "LINK"
        val held = java.util.concurrent.CountDownLatch(1)
        standIn.hold["GET /health"] = held
        standIn.answers["POST /auth/register"] = { standIn.refuse(403, "SIGNUP_CODE_INVALID") }
        try {
            launch()
            val any = text(R.string.start_body_any, text(R.string.action_create_account))
            waitFor(any)
            compose.waitUntil(10_000) { healthReads() >= 1 }
            assertFalse(shown(text(R.string.start_body)))
            assertFalse(shown(text(R.string.start_body_link)))

            button(R.string.action_create_account).performClick()
            compose.onNodeWithText(text(R.string.register_data)).assertIsDisplayed()
            assertFalse("the field hides while health is unread", codeFieldShown())
            assertFalse(shown(text(R.string.privacy_signup_open)))
            button(R.string.action_have_signup_code).performScrollTo().performClick()
            field(R.string.field_signup_code).performScrollTo().assertIsDisplayed()
            assertFalse(shown(text(R.string.register_code_empty_ok)))
            // A wrong code gets the text that holds under both modes.
            field(R.string.field_display_name).performTextInput("Çağrı Öztürk")
            field(R.string.field_password).performTextInput(PASSWORD)
            field(R.string.field_signup_code).performTextInput("ABCD-EFGH-JKMN-PQRS")
            button(R.string.action_create_account).performClick()
            waitFor(text(R.string.register_code_invalid))
            assertFalse(shown(text(R.string.register_code_invalid_open)))
            assertFalse(shown(text(R.string.privacy_signup_open)))
            assertFalse(shown(text(R.string.start_body)))

            held.countDown()
            // Health answers LINK: the start screen names the code, and the form shows its field at once.
            scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            waitFor(text(R.string.start_body_link))
            assertFalse(shown(any))
            button(R.string.action_create_account).performClick()
            compose.onNodeWithText(text(R.string.register_data)).assertIsDisplayed()
            field(R.string.field_signup_code).performScrollTo().assertIsDisplayed()
            assertFalse(shown(text(R.string.action_have_signup_code)))
            assertFalse(shown(text(R.string.register_code_empty_ok)))
            assertFalse(shown(text(R.string.privacy_signup_open)))
        } finally {
            held.countDown()
        }
    }

    @Test fun aNoLinkAnswerShowsTheCodeFieldAndRegisterGate() {
        TestAccounts.device(context, Settings(welcomeSeen = true))
        // The operator restarted the server with `link` after the screens read OPEN.
        standIn.answers["POST /auth/register"] = { standIn.refuse(403, "SIGNUP_CLOSED", org.json.JSONObject().put("reason", "NO_LINK")) }
        launch()
        waitFor(text(R.string.start_body))
        button(R.string.action_create_account).performClick()
        compose.onNodeWithText(text(R.string.register_data)).assertIsDisplayed()
        // The register screen reads health once more when it opens; its OPEN answer comes before the registration.
        compose.waitUntil(10_000) { healthReads() >= 2 }
        Thread.sleep(200)
        compose.waitForIdle()
        assertFalse(codeFieldShown())
        compose.onNodeWithText(text(R.string.privacy_signup_open)).performScrollTo().assertIsDisplayed()
        field(R.string.field_display_name).performTextInput("Çağrı Öztürk")
        field(R.string.field_password).performTextInput(PASSWORD)
        button(R.string.action_create_account).performClick()

        waitFor(text(R.string.register_gate))
        field(R.string.field_signup_code).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.register_code_hint)).performScrollTo().assertIsDisplayed()
        assertFalse(shown(text(R.string.action_have_signup_code)))
        assertFalse(shown(text(R.string.register_code_empty_ok)))
        assertFalse(shown(text(R.string.privacy_signup_open)))
        assertFalse(standIn.exists("cagri.ozturk"))
    }

    @Test fun anOpenServerHidesTheCodeFieldAndARegistrationWithoutACodeReachesHome() {
        TestAccounts.device(context, Settings(welcomeSeen = true))
        launch()
        waitFor(text(R.string.start_body))
        button(R.string.action_create_account).performClick()
        compose.onNodeWithText(text(R.string.register_data)).assertIsDisplayed()
        assertFalse(codeFieldShown())
        compose.onNode(hasClickAction() and hasText(text(R.string.action_have_signup_code))).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.privacy_signup_open)).performScrollTo().assertIsDisplayed()
        field(R.string.field_display_name).performTextInput("Çağrı Öztürk")
        field(R.string.field_password).performTextInput(PASSWORD)
        button(R.string.action_create_account).performClick()

        waitFor(greeting("Çağrı Öztürk"))
        assertTrue(standIn.exists("cagri.ozturk"))
        assertEquals(1, standIn.requests.count { it.first == "POST /auth/register" })
    }
}
