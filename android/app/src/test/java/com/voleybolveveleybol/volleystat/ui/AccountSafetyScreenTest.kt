package com.voleybolveveleybol.volleystat.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.voleybolveveleybol.volleystat.data.AuthProblem
import com.voleybolveveleybol.volleystat.data.PasswordRule
import com.voleybolveveleybol.volleystat.ui.screens.LocalAutofillCancel
import com.voleybolveveleybol.volleystat.ui.screens.LocalAutofillCommit
import com.voleybolveveleybol.volleystat.ui.screens.PasswordChangeScreen
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import com.voleybolveveleybol.volleystat.AccountChange
import com.voleybolveveleybol.volleystat.AccountUi
import com.voleybolveveleybol.volleystat.AppUiState
import com.voleybolveveleybol.volleystat.PasswordNotHandedOver
import com.voleybolveveleybol.volleystat.R
import com.voleybolveveleybol.volleystat.UnconfirmedDeletion
import com.voleybolveveleybol.volleystat.data.AccountUser
import com.voleybolveveleybol.volleystat.model.ServerTarget
import com.voleybolveveleybol.volleystat.model.Settings
import com.voleybolveveleybol.volleystat.ui.screens.LoginScreen
import com.voleybolveveleybol.volleystat.ui.screens.ServerSection
import com.voleybolveveleybol.volleystat.ui.theme.VolleyStatTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The login screen after a deletion whose answer was lost (review finding S1): it says what the
 * app does not know, and it deletes the phone's data of that account only after the user confirms.
 * The server choice while signed in (S5), and the autofill of the password change (S6).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class AccountSafetyScreenTest {
    @get:Rule val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun text(id: Int, vararg arguments: Any) = context.getString(id, *arguments)
    private fun shown(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun show(content: @Composable () -> Unit) = compose.setContent { VolleyStatTheme(dark = false) { content() } }

    private val doubt = UnconfirmedDeletion("user-ali", "ali")

    @Test fun theLoginSaysWhatTheAppDoesNotKnowAndDeletesThePhonesDataOnlyAfterTheUserConfirms() {
        var account by mutableStateOf(AccountUi(unconfirmedDeletion = doubt))
        var deletions = 0
        show { LoginScreen(account, onLogin = { _, _ -> }, onCreateAccount = {}, onDeletePhoneData = { deletions++ }) }

        val button = text(R.string.action_delete_from_phone)
        compose.onNodeWithText(text(R.string.deletion_unconfirmed, "ali", button)).assertIsDisplayed()
        // The text claims no deletion of the server, and the session_ended text does not stand next to it.
        assertFalse(shown(text(R.string.account_deleted)))
        assertFalse(shown(text(R.string.session_ended)))

        compose.onNode(hasClickAction() and hasText(button) and !hasAnyAncestor(isDialog())).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.delete_from_phone_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.delete_from_phone_body, "ali")).assertIsDisplayed()
        assertEquals("the first tap only asks", 0, deletions)

        // Vazgeç closes the question and deletes nothing.
        compose.onNodeWithText(text(R.string.action_give_up)).performClick()
        assertFalse(shown(text(R.string.delete_from_phone_title)))
        assertEquals(0, deletions)

        compose.onNode(hasClickAction() and hasText(button) and !hasAnyAncestor(isDialog())).performScrollTo().performClick()
        compose.onNode(hasClickAction() and hasText(button) and hasAnyAncestor(isDialog())).performClick()
        assertEquals(1, deletions)

        // The confirmation names the account whose data went: another account of the phone keeps its own (checker's finding).
        account = AccountUi(confirmed = AccountChange.PHONE_DATA_DELETED, deletedFromPhone = doubt)
        compose.onNodeWithText("Uygulama ali hesabının bu telefondaki maç kayıtlarını, kadrolarını ve kamerayla çektiğin videoları sildi.").assertIsDisplayed()
        assertEquals("Uygulama ali hesabının bu telefondaki maç kayıtlarını, kadrolarını ve kamerayla çektiğin videoları sildi.", text(R.string.phone_data_deleted, "ali"))
        assertFalse(shown(text(R.string.deletion_unconfirmed, "ali", button)))
    }

    @Test fun anAccountWhoseUsernameThePhoneCanNoLongerReadIsOfferedWithoutAName() {
        // A new keystore key or a damaged deletions.bin: the phone still knows the account's folder, not its name.
        val unnamed = UnconfirmedDeletion("user-ali", null)
        var account by mutableStateOf(AccountUi(unconfirmedDeletion = unnamed))
        var deletions = 0
        show { LoginScreen(account, onLogin = { _, _ -> }, onCreateAccount = {}, onDeletePhoneData = { deletions++ }) }

        val button = text(R.string.action_delete_from_phone)
        val offer = "Uygulama sunucunun bir hesabı silip silmediğini bilmiyor. " +
            "Uygulama bu hesabın kullanıcı adını da okuyamadı. Sunucu hesabı silmediyse giriş yapınca maçlarını yeniden görürsün. " +
            "Sunucu hesabı sildiyse bu telefondaki verilerini Telefondan Sil düğmesiyle silebilirsin."
        assertEquals(offer, text(R.string.deletion_unconfirmed_unnamed, button))
        compose.onNodeWithText(offer).assertIsDisplayed()
        assertFalse("no missing name stands in a text", shown("null"))

        compose.onNode(hasClickAction() and hasText(button) and !hasAnyAncestor(isDialog())).performScrollTo().performClick()
        val body = "Uygulama kullanıcı adını okuyamadığı hesabın bu telefondaki maç kayıtlarını, kadrolarını ve kamerayla çektiğin videoları siler. " +
            "Galeriden seçtiğin videolar galeride kalır. Uygulama bunun için sunucuya hiçbir istek göndermez."
        assertEquals(body, text(R.string.delete_from_phone_body_unnamed))
        compose.onNodeWithText(body).assertIsDisplayed()
        compose.onNode(hasClickAction() and hasText(button) and hasAnyAncestor(isDialog())).performClick()
        assertEquals(1, deletions)

        account = AccountUi(confirmed = AccountChange.PHONE_DATA_DELETED, deletedFromPhone = unnamed)
        val done = "Uygulama kullanıcı adını okuyamadığı hesabın bu telefondaki maç kayıtlarını, kadrolarını ve kamerayla çektiğin videoları sildi."
        assertEquals(done, text(R.string.phone_data_deleted_unnamed))
        compose.onNodeWithText(done).assertIsDisplayed()
        assertFalse(shown("null"))
    }

    // Review finding S5: while someone is signed in, the server choice stays as it is.

    @Test fun whileSignedInTheServerChoiceIsReadOnlyAndSaysWhy() {
        var state by mutableStateOf(
            AppUiState(
                settings = Settings(welcomeSeen = true, accountSeen = true, legacyHandled = true, serverTarget = ServerTarget.EMULATOR),
                account = AccountUi(user = AccountUser("user-ali", "ali", "Ali")),
            )
        )
        val chosen = mutableListOf<ServerTarget>()
        var tests = 0
        show { Column { ServerSection(state, { chosen += it }, { tests++ }) } }

        compose.onNodeWithText(text(R.string.server_locked)).assertIsDisplayed()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton) and hasText(text(R.string.server_usb), substring = true)).assertIsNotEnabled().performClick()
        assertEquals("a tap changes nothing", emptyList<ServerTarget>(), chosen)
        // The test of the server stays: it goes to the server that opened the session.
        compose.onNodeWithText(text(R.string.action_test_server)).assertIsEnabled().performClick()
        assertEquals(1, tests)

        state = state.copy(account = AccountUi())
        assertFalse(shown(text(R.string.server_locked)))
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton) and hasText(text(R.string.server_usb), substring = true)).assertIsEnabled().performClick()
        assertEquals(listOf(ServerTarget.USB_DEVICE), chosen)
    }

    // The second review's R2-B: the login and register screens show the choice too, and it waits while their call is on its way.

    @Test fun theLoginScreensServerChoiceWaitsWhileTheLoginIsOnItsWay() {
        var state by mutableStateOf(AppUiState(settings = Settings(serverTarget = ServerTarget.USB_DEVICE), account = AccountUi(busy = true)))
        val chosen = mutableListOf<ServerTarget>()
        show { LoginScreen(state.account, onLogin = { _, _ -> }, onCreateAccount = {}, server = { ServerSection(state, { chosen += it }, {}) }) }
        val emulator = compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton) and hasText(text(R.string.server_emulator), substring = true))
        emulator.performScrollTo().assertIsNotEnabled().performClick()
        assertEquals("a tap changes nothing", emptyList<ServerTarget>(), chosen)
        // Nobody is signed in, so the reason of a signed-in phone does not stand there.
        assertFalse(shown(text(R.string.server_locked)))

        state = state.copy(account = AccountUi())
        emulator.performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf(ServerTarget.EMULATOR), chosen)
    }

    // Review finding S6: the password manager hears of a new password only after the server took it.

    private val ali = AccountUser("user-ali", "ali", "Ali")
    private fun field(label: Int) = compose.onNode(hasSetTextAction() and hasText(text(label)))
    private val confirm get() = compose.onNode(hasClickAction() and hasText(text(R.string.action_change_password)))

    private class CountingAutofill {
        var commits = 0
        var cancels = 0
        /** How often the screen said that it could not hand a confirmed new password to the password manager. */
        var notHandedOver = 0
    }

    private fun passwordChange(autofill: CountingAutofill, account: () -> AccountUi, open: () -> Boolean = { true }) = show {
        CompositionLocalProvider(LocalAutofillCommit provides { autofill.commits++ }, LocalAutofillCancel provides { autofill.cancels++ }) {
            if (open()) PasswordChangeScreen(account(), { _, _ -> }, onBack = {}, onNewPasswordNotHandedOver = { autofill.notHandedOver++ })
        }
    }

    @Test fun thePasswordChangeCommitsTheAutofillOnlyOnceTheServerChangedThePassword() {
        var account by mutableStateOf(AccountUi(user = ali))
        var open by mutableStateOf(true)
        val autofill = CountingAutofill()
        passwordChange(autofill, { account }, { open })

        // The saved entry belongs to the signed-in username: the screen names it for the password manager.
        compose.onNode(hasText("ali") and SemanticsMatcher.expectValue(SemanticsProperties.ContentType, ContentType.Username)).assertIsDisplayed()
        field(R.string.field_current_password).performTextInput("eski bir şifre 1")
        field(R.string.field_new_password).performTextInput("parola12345")
        confirm.performClick()
        assertEquals("the tap alone commits nothing", 0, autofill.commits)
        account = account.copy(busy = true)
        compose.waitForIdle()
        // Refusals the app can not foresee: the refused password is never offered for saving.
        account = account.copy(busy = false, problem = AuthProblem.PasswordRejected(PasswordRule.COMMON))
        compose.waitForIdle()
        account = account.copy(problem = AuthProblem.PasswordWrong)
        compose.waitForIdle()
        assertEquals(0, autofill.commits)

        field(R.string.field_new_password).performTextReplacement("yeni bir şifre 2")
        confirm.performClick()
        account = account.copy(busy = true, problem = null)
        compose.waitForIdle()
        account = account.copy(busy = false, confirmed = AccountChange.PASSWORD)
        compose.waitForIdle()
        assertEquals(1, autofill.commits)
        assertEquals("the screen that sent the change hands it over itself", 0, autofill.notHandedOver)
        open = false
        compose.waitForIdle()
        assertEquals("a committed session is not cancelled", 0, autofill.cancels)
        assertEquals(1, autofill.commits)
    }

    /**
     * The second review's R2-D: a dark theme switch rebuilds the screen while the change is on its way, and the
     * call goes on (F8). The rebuilt screen has empty fields by design, so it can not commit the new password.
     * It says so instead of closing as if the password manager had it.
     */
    @Test fun aPasswordChangeConfirmedAfterTheScreenWasRebuiltSaysThePasswordManagerDidNotGetIt() {
        val autofill = CountingAutofill()
        var account by mutableStateOf(AccountUi(user = ali))
        val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(LocalAutofillCommit provides { autofill.commits++ }, LocalAutofillCancel provides { autofill.cancels++ }) {
                VolleyStatTheme(dark = false) {
                    PasswordChangeScreen(account, { _, _ -> account = account.copy(busy = true) }, onBack = {}, onNewPasswordNotHandedOver = { autofill.notHandedOver++ })
                }
            }
        }
        field(R.string.field_current_password).performTextInput("eski bir şifre 1")
        field(R.string.field_new_password).performTextInput("yeni bir şifre 2")
        confirm.performClick()
        compose.waitForIdle()
        assertTrue(account.busy)
        restoration.emulateSavedInstanceStateRestore()
        account = account.copy(busy = false, confirmed = AccountChange.PASSWORD)
        compose.waitForIdle()
        assertEquals("the rebuilt fields are empty: nothing to commit", 0, autofill.commits)
        assertEquals(1, autofill.notHandedOver)
        // A later state of the same confirmation says it once.
        account = account.copy(problem = null)
        compose.waitForIdle()
        assertEquals(1, autofill.notHandedOver)
    }

    @Test fun theProfileSaysThatThePasswordManagerDidNotGetTheNewPassword() {
        var state by mutableStateOf(
            AppUiState(
                settings = Settings(welcomeSeen = true, accountSeen = true, legacyHandled = true),
                account = AccountUi(user = ali, confirmed = AccountChange.PASSWORD),
            )
        )
        show { com.voleybolveveleybol.volleystat.ui.screens.ProfileScreen(state, "0.8.0", {}, {}, {}, { true }, account = com.voleybolveveleybol.volleystat.ui.screens.ProfileAccountActions()) }
        compose.onNodeWithText(text(R.string.password_changed)).performScrollTo().assertIsDisplayed()
        assertFalse(shown(text(R.string.password_not_handed_over)))
        state = state.copy(account = state.account.copy(newPasswordNotHandedOver = com.voleybolveveleybol.volleystat.PasswordNotHandedOver.SCREEN_REBUILT))
        compose.onNodeWithText(text(R.string.password_changed)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.password_not_handed_over)).performScrollTo().assertIsDisplayed()
    }

    @Test fun leavingThePasswordChangeWithoutTheServersWordCancelsTheAutofill() {
        var account by mutableStateOf(AccountUi(user = ali))
        var open by mutableStateOf(true)
        val autofill = CountingAutofill()
        passwordChange(autofill, { account }, { open })
        field(R.string.field_current_password).performTextInput("yanlış bir şifre")
        field(R.string.field_new_password).performTextInput("yeni bir şifre 2")
        confirm.performClick()
        account = account.copy(problem = AuthProblem.PasswordWrong)
        compose.waitForIdle()
        open = false
        compose.waitForIdle()
        assertEquals(0, autofill.commits)
        assertEquals(1, autofill.cancels)
    }

    @Test fun aConfirmationTheScreenDidNotAskForCommitsNothing() {
        val autofill = CountingAutofill()
        passwordChange(autofill, { AccountUi(user = ali, confirmed = AccountChange.PASSWORD) })
        compose.waitForIdle()
        assertEquals(0, autofill.commits)
        assertEquals("nor does it speak of a password it never sent", 0, autofill.notHandedOver)
    }

    // The third review's B1 and B2: the password manager never gets a password other than the one the server got,
    // and a change it did not get is named, also when the user left the screen or edited the field meanwhile.

    /** The password change screen with counting autofill. [account] follows the screen's own call: a tap makes it busy. */
    private class PasswordChangeProbe(val autofill: CountingAutofill = CountingAutofill()) {
        var account by mutableStateOf(AccountUi(user = AccountUser("user-ali", "ali", "Ali")))
        var open by mutableStateOf(true)
        val toldAtConfirmation = mutableListOf<PasswordNotHandedOver>()
        val toldWhenGone = mutableListOf<PasswordNotHandedOver>()
    }

    private fun passwordChange(probe: PasswordChangeProbe) = show {
        CompositionLocalProvider(LocalAutofillCommit provides { probe.autofill.commits++ }, LocalAutofillCancel provides { probe.autofill.cancels++ }) {
            if (probe.open) {
                PasswordChangeScreen(
                    probe.account, { _, _ -> probe.account = probe.account.copy(busy = true) }, onBack = {},
                    onNewPasswordNotHandedOver = { probe.toldAtConfirmation += it }, onGoneWithoutHandingOver = { probe.toldWhenGone += it },
                )
            }
        }
    }

    private fun PasswordChangeProbe.send(newPassword: String = "yeni bir şifre 2") {
        field(R.string.field_current_password).performTextInput("eski bir şifre 1")
        field(R.string.field_new_password).performTextInput(newPassword)
        confirm.performClick()
        compose.waitForIdle()
        assertTrue(account.busy)
    }

    private fun PasswordChangeProbe.confirm() {
        account = account.copy(busy = false, confirmed = AccountChange.PASSWORD)
        compose.waitForIdle()
    }

    private fun PasswordChangeProbe.leave() {
        open = false
        compose.waitForIdle()
    }

    @Test fun leavingThePasswordChangeWhileItIsOnItsWaySaysThatThePasswordManagerGotNothing() {
        val probe = PasswordChangeProbe()
        passwordChange(probe)
        probe.send()
        probe.leave()
        assertEquals("the screen that went commits nothing", 0, probe.autofill.commits)
        assertEquals(1, probe.autofill.cancels)
        assertEquals(listOf(PasswordNotHandedOver.SCREEN_LEFT), probe.toldWhenGone)
        assertEquals(emptyList<PasswordNotHandedOver>(), probe.toldAtConfirmation)
    }

    @Test fun aScreenLeftWithNothingSentSaysNothingAboutThePasswordManager() {
        val untouched = PasswordChangeProbe()
        passwordChange(untouched)
        untouched.leave()
        assertEquals(emptyList<PasswordNotHandedOver>(), untouched.toldWhenGone)
    }

    @Test fun aScreenLeftAfterItsCommitSaysNothingAboutThePasswordManager() {
        val committed = PasswordChangeProbe()
        passwordChange(committed)
        committed.send()
        committed.confirm()
        committed.leave()
        assertEquals(1, committed.autofill.commits)
        assertEquals(emptyList<PasswordNotHandedOver>(), committed.toldWhenGone)
        assertEquals(emptyList<PasswordNotHandedOver>(), committed.toldAtConfirmation)
    }

    /**
     * The fifth check's P3: a screen that stays and gets an answer that leaves the outcome open hands nothing over either.
     * The missing confirmation is why nothing went to the password manager, and the profile's note names that reason, not
     * the leaving of the screen.
     */
    @Test fun aScreenThatStaysAndGetsNoConfirmationHandsOverNothingEither() {
        val probe = PasswordChangeProbe()
        passwordChange(probe)
        probe.send()
        listOf(AuthProblem.Unreachable, AuthProblem.ServerFailed(500, withEnvelope = true), AuthProblem.UnexpectedAnswer).forEachIndexed { index, answer ->
            if (index > 0) {
                // The user sends the same change again from the screen that stayed.
                confirm.performClick()
                compose.waitForIdle()
                assertTrue(probe.account.busy)
            }
            probe.account = probe.account.copy(busy = false, problem = answer)
            compose.waitForIdle()
            assertEquals("$answer: the screen that stayed commits nothing", 0, probe.autofill.commits)
            assertEquals(emptyList<PasswordNotHandedOver>(), probe.toldAtConfirmation)
        }
        assertEquals("the screen is still open", emptyList<PasswordNotHandedOver>(), probe.toldWhenGone)
        val note = text(R.string.password_left_outcome_unknown)
        assertFalse("the note names leaving as the reason nothing was handed over: $note", note.contains("çıktın. Bu yüzden"))
        assertTrue(note, note.contains("bir onay almadığı için şifre yöneticisine hiçbir şifre iletmedi."))
    }

    @Test fun aNewPasswordEditedWhileTheChangeWaitsIsNeverCommitted() {
        val probe = PasswordChangeProbe()
        passwordChange(probe)
        probe.send("yeni bir şifre 2")
        // The fields stay editable while the change waits: Android's autofill commit may need them enabled.
        field(R.string.field_new_password).assertIsEnabled().performTextReplacement("yeni bir şifre 3")
        probe.confirm()
        assertEquals("the server got another password than the field holds", 0, probe.autofill.commits)
        assertEquals(listOf(PasswordNotHandedOver.PASSWORD_EDITED), probe.toldAtConfirmation)
        // A later state of the same confirmation says it once, and the screen that goes then says nothing more.
        probe.account = probe.account.copy(problem = null, busy = false)
        compose.waitForIdle()
        probe.leave()
        assertEquals(listOf(PasswordNotHandedOver.PASSWORD_EDITED), probe.toldAtConfirmation)
        assertEquals(emptyList<PasswordNotHandedOver>(), probe.toldWhenGone)
        assertEquals("the edited text is not offered for saving", 1, probe.autofill.cancels)
        assertEquals(0, probe.autofill.commits)
    }

    @Test fun aNewPasswordEditedBackToTheSentTextIsCommitted() {
        val probe = PasswordChangeProbe()
        passwordChange(probe)
        probe.send("yeni bir şifre 2")
        field(R.string.field_new_password).performTextReplacement("yeni bir şifre 3")
        field(R.string.field_new_password).performTextReplacement("yeni bir şifre 2")
        probe.confirm()
        assertEquals(1, probe.autofill.commits)
        assertEquals(emptyList<PasswordNotHandedOver>(), probe.toldAtConfirmation)
    }

    @Test fun aScreenLeftAfterTheNewPasswordWasEditedNamesTheEdit() {
        val probe = PasswordChangeProbe()
        passwordChange(probe)
        probe.send("yeni bir şifre 2")
        field(R.string.field_new_password).performTextReplacement("yeni bir şifre 3")
        probe.leave()
        assertEquals(listOf(PasswordNotHandedOver.PASSWORD_EDITED), probe.toldWhenGone)
        assertEquals(0, probe.autofill.commits)
    }

    @Test fun theProfileNamesWhyThePasswordManagerDidNotGetTheNewPassword() {
        var state by mutableStateOf(
            AppUiState(
                settings = Settings(welcomeSeen = true, accountSeen = true, legacyHandled = true),
                account = AccountUi(user = ali, confirmed = AccountChange.PASSWORD),
            )
        )
        show { com.voleybolveveleybol.volleystat.ui.screens.ProfileScreen(state, "0.8.0", {}, {}, {}, { true }, account = com.voleybolveveleybol.volleystat.ui.screens.ProfileAccountActions()) }
        val texts = mapOf(
            PasswordNotHandedOver.SCREEN_REBUILT to text(R.string.password_not_handed_over),
            PasswordNotHandedOver.SCREEN_LEFT to text(R.string.password_not_handed_over_left),
            PasswordNotHandedOver.PASSWORD_EDITED to text(R.string.password_not_handed_over_edited),
        )
        texts.forEach { (reason, expected) ->
            state = state.copy(account = state.account.copy(newPasswordNotHandedOver = reason))
            compose.onNodeWithText(text(R.string.password_changed)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(expected).performScrollTo().assertIsDisplayed()
            texts.filterKeys { it != reason }.values.forEach { other -> assertFalse("$reason shows another reason", shown(other)) }
        }
    }

    // ----- the fourth review's D1: the profile says how a change ended whose screen the user left -----

    private fun profileWith(afterLeave: com.voleybolveveleybol.volleystat.OutcomeAfterLeave?): (AppUiState) -> AppUiState =
        { it.copy(account = it.account.copy(afterLeave = afterLeave)) }

    @Test fun theProfileSaysHowAPasswordChangeEndedAfterItsScreenWasLeft() {
        var state by mutableStateOf(
            AppUiState(settings = Settings(welcomeSeen = true, accountSeen = true, legacyHandled = true), account = AccountUi(user = ali))
        )
        show { com.voleybolveveleybol.volleystat.ui.screens.ProfileScreen(state, "0.8.0", {}, {}, {}, { true }, account = com.voleybolveveleybol.volleystat.ui.screens.ProfileAccountActions()) }
        val note = text(R.string.password_left_outcome_unknown)
        assertFalse(shown(note))
        fun left(problem: AuthProblem, unknown: Boolean) = com.voleybolveveleybol.volleystat.OutcomeAfterLeave(AccountChange.PASSWORD, problem, unknown)

        // No answer came after the request went out: the profile names that, and what the app does not know.
        state = profileWith(left(AuthProblem.Unreachable, unknown = true))(state)
        compose.onNodeWithText(text(R.string.net_no_answer)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(note).performScrollTo().assertIsDisplayed()
        assertFalse(shown(text(R.string.password_changed)))

        // A 5xx without the envelope: the text of that answer, and the note.
        state = profileWith(left(AuthProblem.ServerFailed(502), unknown = true))(state)
        compose.onNodeWithText(text(R.string.account_outcome_unknown_on_the_way)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(note).performScrollTo().assertIsDisplayed()
        assertFalse(shown(text(R.string.net_no_answer)))

        // A refusal: the server changed nothing, so the app knows the outcome and the password manager holds the right password.
        state = profileWith(left(AuthProblem.PasswordWrong, unknown = false))(state)
        compose.onNodeWithText(text(R.string.password_wrong)).performScrollTo().assertIsDisplayed()
        assertFalse(shown(note))

        // A request that never left the phone: no answer came, and the app knows that the server did not get it.
        state = profileWith(left(AuthProblem.Unreachable, unknown = false))(state)
        compose.onNodeWithText(text(R.string.net_no_answer)).performScrollTo().assertIsDisplayed()
        assertFalse(shown(note))

        state = profileWith(null)(state)
        assertFalse(shown(text(R.string.net_no_answer)))
    }

    @Test fun theProfileSaysHowADisplayNameChangeEndedAfterTheProfileWasLeft() {
        val state = AppUiState(
            settings = Settings(welcomeSeen = true, accountSeen = true, legacyHandled = true),
            account = AccountUi(user = ali, afterLeave = com.voleybolveveleybol.volleystat.OutcomeAfterLeave(AccountChange.DISPLAY_NAME, AuthProblem.UnexpectedAnswer, true)),
        )
        show { com.voleybolveveleybol.volleystat.ui.screens.ProfileScreen(state, "0.8.0", {}, {}, {}, { true }, account = com.voleybolveveleybol.volleystat.ui.screens.ProfileAccountActions()) }
        compose.onNodeWithText(text(R.string.account_unexpected_change_signed_in)).performScrollTo().assertIsDisplayed()
        assertFalse("the password note belongs to the password change", shown(text(R.string.password_left_outcome_unknown)))
        assertFalse(shown(text(R.string.display_name_saved)))
    }

    @Test fun theOfferWaitsWhileAnAccountCallIsOnItsWay() {
        var deletions = 0
        show { LoginScreen(AccountUi(busy = true, unconfirmedDeletion = doubt), onLogin = { _, _ -> }, onCreateAccount = {}, onDeletePhoneData = { deletions++ }) }
        compose.onNode(hasClickAction() and hasText(text(R.string.action_delete_from_phone))).performScrollTo().performClick()
        assertFalse(shown(text(R.string.delete_from_phone_title)))
        assertEquals(0, deletions)
    }
}
