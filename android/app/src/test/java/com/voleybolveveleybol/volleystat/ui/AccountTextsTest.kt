package com.voleybolveveleybol.volleystat.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.voleybolveveleybol.volleystat.AppViewModel
import com.voleybolveveleybol.volleystat.R
import com.voleybolveveleybol.volleystat.data.AttemptScope
import com.voleybolveveleybol.volleystat.data.AuthProblem
import com.voleybolveveleybol.volleystat.data.PasswordRule
import com.voleybolveveleybol.volleystat.data.SignupClosedReason
import com.voleybolveveleybol.volleystat.ui.screens.AccountAction
import com.voleybolveveleybol.volleystat.ui.screens.accountProblemText
import com.voleybolveveleybol.volleystat.ui.screens.resolve
import com.voleybolveveleybol.volleystat.ui.screens.waitText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every answer of the account routes (docs/API_V1.md section 10, design 10.7) has a Turkish text
 * that says what the server did. No text shows a server code, and none guesses a cause.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AccountTextsTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun string(id: Int, vararg arguments: Any) = context.getString(id, *arguments)
    private fun text(problem: AuthProblem, action: AccountAction = AccountAction.LOGIN) = accountProblemText(problem, action).resolve(context)

    @Test fun theAnswersOfALoginHaveTheirOwnTexts() {
        assertEquals(string(R.string.login_failed), text(AuthProblem.LoginFailed))
        assertEquals(string(R.string.net_no_answer), text(AuthProblem.Unreachable))
        assertEquals(string(R.string.account_unexpected), text(AuthProblem.UnexpectedAnswer))
        assertEquals("a server without /v1/auth is older", string(R.string.account_server_older), text(AuthProblem.ServerOutdated))
        assertEquals(string(R.string.session_ended), text(AuthProblem.SessionEnded))
        assertEquals(string(R.string.account_refused), text(AuthProblem.Refused(422, "VALIDATION_ERROR")))
        assertEquals("an answer from another address than the chosen one", string(R.string.account_server_changed), text(AuthProblem.ServerChanged))
        assertEquals(AuthProblem.ServerChanged, AuthProblem.of(com.voleybolveveleybol.volleystat.data.AnswerFromAnotherServer()))
    }

    @Test fun theAnswersOfARegistrationHaveTheirOwnTexts() {
        val action = AccountAction.REGISTER
        assertEquals(string(R.string.register_gate), text(AuthProblem.SignupClosed(SignupClosedReason.NO_LINK), action))
        assertEquals(string(R.string.register_gate), text(AuthProblem.SignupClosed(SignupClosedReason.OPEN_MATCH_LINK), action))
        assertEquals(string(R.string.register_code_invalid), text(AuthProblem.SignupCodeInvalid, action))
        assertEquals(string(R.string.username_taken), text(AuthProblem.UsernameTaken, action))
        assertEquals(string(R.string.username_invalid), text(AuthProblem.UsernameInvalid, action))
        assertEquals(string(R.string.display_name_invalid), text(AuthProblem.DisplayNameInvalid, action))
        // This app sends no link, so a link answer is a refusal like any other.
        assertEquals(string(R.string.account_refused), text(AuthProblem.LinkNotFound, action))
    }

    @Test fun everyPasswordRuleHasItsOwnText() {
        val expected = mapOf(
            PasswordRule.TOO_SHORT to R.string.password_too_short,
            PasswordRule.TOO_LONG to R.string.password_too_long,
            PasswordRule.CONTAINS_USERNAME to R.string.password_contains_username,
            PasswordRule.REPETITIVE to R.string.password_repetitive,
            PasswordRule.COMMON to R.string.password_common,
            PasswordRule.UNKNOWN to R.string.password_rejected,
        )
        assertEquals(PasswordRule.entries.toSet(), expected.keys)
        expected.forEach { (rule, id) -> assertEquals(rule.name, string(id), text(AuthProblem.PasswordRejected(rule), AccountAction.REGISTER)) }
        assertEquals("six rules, six texts", 6, expected.values.map { string(it) }.toSet().size)
    }

    /**
     * Review finding T2. The server folds case and drops marks before it counts (API_V1 section 10), so
     * "AaBbAaBbAaBb" holds four typed characters and is still refused. The text says what the server
     * counted and how, never that the typed password holds fewer than four.
     */
    @Test fun aRepetitivePasswordIsNamedAsTheServerCountedIt() {
        assertEquals(
            "Sunucu bu şifrede dörtten az farklı karakter saydı ve şifreyi kabul etmedi. " +
                "Sunucu A ile a, ş ile s ve ı ile i gibi harf çiftlerini aynı karakter sayar. Başka bir şifre seçebilirsin.",
            text(AuthProblem.PasswordRejected(PasswordRule.REPETITIVE), AccountAction.REGISTER),
        )
    }

    /** Review finding T3. ".." meets the length and the characters, and the server refuses it: it holds no letter or digit. */
    @Test fun aRefusedDisplayNameNamesEveryRuleOfTheServer() {
        val expected = "Görünen ad 2 ile 40 karakter arasında olmalı ve en az bir harf ya da rakam içermeli. " +
            "Görünen ad harf ve rakamın yanında yalnız boşluk, nokta, kesme işareti ve kısa çizgi taşıyabilir."
        assertEquals(expected, text(AuthProblem.DisplayNameInvalid, AccountAction.REGISTER))
        assertEquals(expected, text(AuthProblem.DisplayNameInvalid, AccountAction.DISPLAY_NAME))
    }

    @Test fun everyScopeOfTooManyAttemptsNamesItsOwnSubjectAndTheServersWait() {
        val expected = mapOf(
            AttemptScope.USER to R.string.login_wait,
            AttemptScope.DEVICE to R.string.login_wait_device,
            AttemptScope.ACCOUNT to R.string.account_wait,
            AttemptScope.SOURCE to R.string.login_wait_source,
            AttemptScope.REGISTER_SOURCE to R.string.register_wait_source,
            AttemptScope.UNKNOWN to R.string.account_wait_unknown,
        )
        assertEquals(AttemptScope.entries.toSet(), expected.keys)
        expected.forEach { (scope, id) ->
            assertEquals(scope.name, string(id, "30 saniye"), text(AuthProblem.TooManyAttempts(scope, 30)))
            assertEquals(scope.name, string(id, "15 dakika"), text(AuthProblem.TooManyAttempts(scope, 900)))
        }
    }

    @Test fun aWaitTheServerDidNotNameIsNotInvented() {
        assertEquals(string(R.string.account_wait_no_time), text(AuthProblem.TooManyAttempts(AttemptScope.USER, null)))
    }

    @Test fun aWaitIsWrittenInSecondsUnderAMinuteAndInWholeMinutesNeverShorterThanTheServersWait() {
        assertEquals("1 saniye", waitText(context, 1))
        assertEquals("59 saniye", waitText(context, 59))
        assertEquals("1 dakika", waitText(context, 60))
        assertEquals("2 dakika", waitText(context, 61))
        assertEquals("15 dakika", waitText(context, 900))
    }

    @Test fun aServerThatStayedBusyThroughTheRepeatsNamesHowOftenTheAppSentTheRequest() {
        assertEquals(string(R.string.server_busy_gave_up, AppViewModel.BUSY_TRIES), text(AuthProblem.ServerBusy(1)))
    }

    @Test fun aWrongCurrentPasswordIsNamedForAPasswordChangeAndForADeletion() {
        assertEquals(string(R.string.password_wrong), text(AuthProblem.PasswordWrong, AccountAction.PASSWORD))
        assertEquals(string(R.string.password_wrong), text(AuthProblem.PasswordWrong, AccountAction.DELETE))
        assertEquals(string(R.string.account_wait, "2 dakika"), text(AuthProblem.TooManyAttempts(AttemptScope.ACCOUNT, 120), AccountAction.DELETE))
    }

    @Test fun anyFailedLogoutSaysTheSessionStaysAndNamesTheButtonThatLeavesOnThisPhoneOnly() {
        val expected = string(R.string.logout_not_confirmed, string(R.string.action_logout_anyway))
        listOf(AuthProblem.Unreachable, AuthProblem.UnexpectedAnswer, AuthProblem.ServerBusy(1), AuthProblem.Refused(500, "INTERNAL")).forEach {
            assertEquals(it.toString(), expected, text(it, AccountAction.LOGOUT))
        }
    }

    @Test fun noTextShowsTheCodeOfTheServer() {
        val odd = text(AuthProblem.Refused(409, "SOMETHING_NEW"), AccountAction.DISPLAY_NAME)
        assertFalse(odd.contains("SOMETHING_NEW"))
        assertFalse(odd.contains("409"))
    }

    @Test fun theTextsJoinNoClausesWithADashAndGuessNoCause() {
        val problems = listOf(
            AuthProblem.Unreachable, AuthProblem.UnexpectedAnswer, AuthProblem.ServerOutdated, AuthProblem.LoginFailed, AuthProblem.SessionEnded,
            AuthProblem.ServerBusy(1), AuthProblem.SignupClosed(SignupClosedReason.NO_LINK), AuthProblem.SignupCodeInvalid, AuthProblem.LinkNotFound,
            AuthProblem.UsernameTaken, AuthProblem.UsernameInvalid, AuthProblem.DisplayNameInvalid, AuthProblem.PasswordWrong, AuthProblem.Refused(400, "X"),
            AuthProblem.TooManyAttempts(AttemptScope.USER, null), AuthProblem.ServerChanged,
        ) + PasswordRule.entries.map { AuthProblem.PasswordRejected(it) } + AttemptScope.entries.map { AuthProblem.TooManyAttempts(it, 30) }
        AccountAction.entries.forEach { action ->
            problems.forEach { problem ->
                val shown = text(problem, action)
                assertTrue("$problem $action", shown.isNotBlank() && shown.endsWith("."))
                listOf(" - ", " – ", " — ", "--", "olabilir.").forEach { assertFalse("$problem $action: $shown", shown.contains(it)) }
            }
        }
    }

    /**
     * The third review's B3: account_refused says that the server changed nothing, which the app knows only for a
     * 4xx. After a 5xx, or an answer the app can not read for a call that changes something, the app does not know
     * whether the server made the change, and the text says so.
     */
    @Test fun anAnswerThatLeavesTheOutcomeUnknownNeverSaysThatTheServerChangedNothing() {
        fun failure(status: Int, code: String) = AuthProblem.of(com.voleybolveveleybol.volleystat.data.ApiException(status, code, code, org.json.JSONObject()))
        val unknown = string(R.string.account_outcome_unknown)
        val changing = listOf(AccountAction.REGISTER, AccountAction.DISPLAY_NAME, AccountAction.PASSWORD, AccountAction.DELETE)
        (changing + AccountAction.LOGIN).forEach { action ->
            assertEquals("$action, a 500 with the envelope", unknown, text(failure(500, "INTERNAL"), action))
            assertEquals("$action, a 502 without it", unknown, text(failure(502, "HTTP_502"), action))
            assertEquals("$action, a 4xx changed nothing", string(R.string.account_refused), text(failure(409, "SOMETHING_NEW"), action))
        }
        changing.forEach { action ->
            assertEquals("$action, an answer the app can not read", string(R.string.account_unexpected_change), text(AuthProblem.UnexpectedAnswer, action))
        }
        // A login changes nothing the user asked for: the old text stays.
        assertEquals(string(R.string.account_unexpected), text(AuthProblem.UnexpectedAnswer, AccountAction.LOGIN))
        // A logout keeps its own text: the session stays on the phone, whatever the server did.
        assertEquals(string(R.string.logout_not_confirmed, string(R.string.action_logout_anyway)), text(failure(500, "INTERNAL"), AccountAction.LOGOUT))
        listOf(unknown, string(R.string.account_unexpected_change)).forEach {
            assertTrue(it.contains("bilmiyor"))
            assertFalse(it.contains("değiştirmedi"))
        }
    }

    /**
     * The third review's B4: the login screen of a phone names an account whose deletion got no answer, to whoever
     * holds the phone. The hint of the register form promises only what holds: the app shows the username on the
     * phones the user logged in on, and the server sends it to no other user.
     */
    @Test fun theUsernameHintPromisesOnlyWhatTheAppAndTheServerKeep() {
        val hint = string(R.string.register_username_hint)
        assertFalse(hint, hint.contains("senden başka kimse"))
        assertTrue(hint, hint.contains("yalnız giriş yaptığın telefonlarda"))
        assertTrue(hint, hint.contains("Sunucu kullanıcı adını başka kullanıcılara göndermez."))
        assertTrue("the rule of the characters stays", hint.endsWith("Kullanıcı adı küçük harf, rakam, nokta ve alt çizgi taşıyabilir."))
        // The texts that name another account's username on the login screen are why the old promise did not hold.
        assertTrue(string(R.string.deletion_unconfirmed, "ali", "x").contains("ali"))
    }

    /**
     * The note about a new password edited while the change waited names who tapped the button. "Sunucu ... düğmesine
     * dokunduğunda" would read as if the server had tapped it (turkce-insansi-metin: every sentence names its actor).
     */
    @Test fun theNoteAboutAnEditedNewPasswordSaysThatTheUserTappedTheButtonAndTheServerSavedThePassword() {
        val button = string(R.string.action_change_password)
        val note = string(R.string.password_not_handed_over_edited, button)
        assertTrue(note, note.contains("Sen $button düğmesine dokunduğunda sunucu alanda yazan şifreyi yeni şifre olarak kaydetti."))
        assertFalse(note, note.contains("Sunucu yeni şifre olarak $button"))
    }
}
