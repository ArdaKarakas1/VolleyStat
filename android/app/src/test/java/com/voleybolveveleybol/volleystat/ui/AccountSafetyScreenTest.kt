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

        account = AccountUi(confirmed = AccountChange.PHONE_DATA_DELETED)
        compose.onNodeWithText(text(R.string.phone_data_deleted)).assertIsDisplayed()
        assertFalse(shown(text(R.string.deletion_unconfirmed, "ali", button)))
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

    // Review finding S6: the password manager hears of a new password only after the server took it.

    private val ali = AccountUser("user-ali", "ali", "Ali")
    private fun field(label: Int) = compose.onNode(hasSetTextAction() and hasText(text(label)))
    private val confirm get() = compose.onNode(hasClickAction() and hasText(text(R.string.action_change_password)))

    private class CountingAutofill {
        var commits = 0
        var cancels = 0
    }

    private fun passwordChange(autofill: CountingAutofill, account: () -> AccountUi, open: () -> Boolean = { true }) = show {
        CompositionLocalProvider(LocalAutofillCommit provides { autofill.commits++ }, LocalAutofillCancel provides { autofill.cancels++ }) {
            if (open()) PasswordChangeScreen(account(), { _, _ -> }, onBack = {})
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
        open = false
        compose.waitForIdle()
        assertEquals("a committed session is not cancelled", 0, autofill.cancels)
        assertEquals(1, autofill.commits)
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
    }

    @Test fun theOfferWaitsWhileAnAccountCallIsOnItsWay() {
        var deletions = 0
        show { LoginScreen(AccountUi(busy = true, unconfirmedDeletion = doubt), onLogin = { _, _ -> }, onCreateAccount = {}, onDeletePhoneData = { deletions++ }) }
        compose.onNode(hasClickAction() and hasText(text(R.string.action_delete_from_phone))).performScrollTo().performClick()
        assertFalse(shown(text(R.string.delete_from_phone_title)))
        assertEquals(0, deletions)
    }
}
