package com.voleybolveveleybol.volleystat.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.voleybolveveleybol.volleystat.AppViewModel
import com.voleybolveveleybol.volleystat.R
import com.voleybolveveleybol.volleystat.data.AttemptScope
import com.voleybolveveleybol.volleystat.data.AuthProblem
import com.voleybolveveleybol.volleystat.data.PasswordRule
import com.voleybolveveleybol.volleystat.data.SignupClosedReason
import com.voleybolveveleybol.volleystat.data.SignupMode
import com.voleybolveveleybol.volleystat.ui.screens.AccountAction
import com.voleybolveveleybol.volleystat.ui.screens.accountProblemText
import com.voleybolveveleybol.volleystat.ui.screens.resolve
import com.voleybolveveleybol.volleystat.ui.screens.waitText
import com.voleybolveveleybol.volleystat.ui.screens.teamProblemText
import com.voleybolveveleybol.volleystat.data.TeamProblem
import com.voleybolveveleybol.volleystat.data.TeamLimitScope
import com.voleybolveveleybol.volleystat.model.TeamRules
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
        // Slice B4 (F3 of the v5.1 checker): only after health said OPEN does the text offer to leave the field empty.
        assertEquals(string(R.string.register_code_invalid_open), accountProblemText(AuthProblem.SignupCodeInvalid, action, SignupMode.OPEN).resolve(context))
        assertEquals(string(R.string.register_code_invalid), accountProblemText(AuthProblem.SignupCodeInvalid, action, SignupMode.LINK).resolve(context))
        assertEquals(string(R.string.register_code_invalid), accountProblemText(AuthProblem.SignupCodeInvalid, action, null).resolve(context))
        // A closed gate keeps B2's text under every mode: the form switches to LINK with it.
        SignupMode.entries.forEach { mode ->
            assertEquals(string(R.string.register_gate), accountProblemText(AuthProblem.SignupClosed(SignupClosedReason.NO_LINK), action, mode).resolve(context))
        }
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
            AuthProblem.ServerFailed(500, withEnvelope = true), AuthProblem.ServerFailed(502),
        ) + PasswordRule.entries.map { AuthProblem.PasswordRejected(it) } + AttemptScope.entries.map { AuthProblem.TooManyAttempts(it, 30) }
        AccountAction.entries.forEach { action ->
            problems.forEach { problem ->
                val shown = text(problem, action)
                assertTrue("$problem $action", shown.isNotBlank() && shown.endsWith("."))
                listOf(" - ", " – ", " — ", "--", "olabilir.").forEach { assertFalse("$problem $action: $shown", shown.contains(it)) }
            }
        }
    }

    /** Slice B4: the texts of the sign-up mode and of the one unfinished upload pass the same checks as every account text. */
    @Test fun theNewSignupTextsJoinNoClausesWithADashAndNameNoCode() {
        val sentences = listOf(
            string(R.string.start_body), string(R.string.start_body_link), string(R.string.start_body_any, string(R.string.action_create_account)),
            string(R.string.register_code_empty_ok), string(R.string.register_code_invalid_open), string(R.string.privacy_signup_open),
            string(R.string.upload_one_unfinished),
        )
        val label = string(R.string.action_have_signup_code)
        sentences.forEach { shown -> assertTrue(shown, shown.isNotBlank() && shown.endsWith(".")) }
        (sentences + label).forEach { shown ->
            listOf(" - ", " – ", " — ", "--", "olabilir.").forEach { assertFalse(shown, shown.contains(it)) }
            // No server code and no setting: the user sees what the server does, in words.
            listOf("OPEN", "LINK", "NO_LINK", "SIGNUP", "QUOTA", "UNFINISHED", "VOLLEYSTAT").forEach { assertFalse(shown, shown.contains(it)) }
            assertFalse(shown, Regex("%[0-9]").containsMatchIn(shown))
        }
        assertEquals("a button label", "Kayıt Kodum Var", label)
        // The text of an unread health names the button the start screen shows.
        assertTrue(string(R.string.start_body_any, string(R.string.action_create_account)).contains(string(R.string.action_create_account)))
    }

    /**
     * Slice B4 (plan D6): the text of 422 NAME_INVALID states exactly the rule the server checks, with the bounds the app
     * shares with the server (TeamRules, tied to the server by TeamE2eTest). The server takes any other character ("Kuzey
     * Spor & Co." passes), so the text names no character class as the only allowed one.
     */
    @Test fun theTeamNameRuleNamesTheServersBoundsAndNoCharacterClassAsTheOnlyOne() {
        val rule = string(R.string.team_name_invalid, TeamRules.NAME_MIN, TeamRules.NAME_MAX)
        assertEquals(rule, teamProblemText(TeamProblem.NameInvalid).resolve(context))
        assertTrue(rule, rule.contains("2") && rule.contains("40"))
        assertTrue(rule, rule.contains("en az bir harf ya da rakam"))
        listOf("yalnız", "sadece").forEach { assertFalse("$it in: $rule", rule.contains(it, ignoreCase = true)) }
        listOf(" - ", " – ", " — ", "--", "olabilir.").forEach { assertFalse(rule, rule.contains(it)) }
        assertFalse(rule, Regex("%[0-9]").containsMatchIn(rule))
    }

    /** Slice B4: every text of the team screens passes the checks of every account text, and the seat texts state the server's numbers. */
    @Test fun theTeamTextsJoinNoClausesWithADashAndNameNoCode() {
        val sentences = listOf(
            string(R.string.teams_empty_body, string(R.string.action_create_team)), string(R.string.team_new_seats_body),
            string(R.string.team_limit_captain, 3), string(R.string.team_limit_member, 20), string(R.string.team_wait_create, "2 dakika"),
            string(R.string.team_seats_body), string(R.string.lineup_body, string(R.string.action_save)), string(R.string.seats_invalid),
            string(R.string.seats_invalid_captain), string(R.string.seats_below_starter, 7, 6), string(R.string.seats_below_substitute, 3, 2),
            string(R.string.seats_raise_or_swap_hint), string(R.string.seats_swap_hint), string(R.string.team_starter_free_hint, 1, 2),
            string(R.string.team_no_access), string(R.string.team_gone), string(R.string.team_captain_only), string(R.string.teams_server_older),
            string(R.string.team_time_invalid),
            // P7: member management, Çıkarılanlar, the settings and the logo.
            string(R.string.transfer_captain_limit, "Ece", 3), string(R.string.jersey_taken, 12), string(R.string.member_remove_body_no_invite),
            string(R.string.team_jersey_body), string(R.string.jersey_empty_hint), string(R.string.logo_invalid), string(R.string.logo_unreadable),
            string(R.string.logo_too_large), string(R.string.team_leave_body), string(R.string.team_delete_body), string(R.string.transfer_body, "Ece"),
            string(R.string.team_must_transfer), string(R.string.blocked_empty_body), string(R.string.privacy_teams),
        )
        val labels = listOf(
            string(R.string.team_new_my_seat), string(R.string.seat_starter), string(R.string.seat_substitute), string(R.string.seats_title),
            string(R.string.team_recent_joins, 2), string(R.string.team_starters), string(R.string.team_substitutes), string(R.string.action_create_team),
            string(R.string.team_seat_line, 5, 6, 1, 2), string(R.string.team_role_captain), string(R.string.team_role_member),
            string(R.string.team_new_joins_a11y, 2), string(R.string.field_team_name), string(R.string.field_substitute_seats),
            string(R.string.field_schedule_day), string(R.string.field_schedule_time), string(R.string.team_schedule_none),
            string(R.string.field_my_jersey), string(R.string.action_save), string(R.string.teams_loading), string(R.string.team_loading),
            string(R.string.teams_empty_title), string(R.string.team_schedule, "Salı", "20:00"),
            string(R.string.blocked_title), string(R.string.action_unblock), string(R.string.jersey_title), string(R.string.action_not_now),
            string(R.string.action_leave_team), string(R.string.action_transfer_captain), string(R.string.action_team_settings), string(R.string.field_logo),
            string(R.string.action_pick_logo), string(R.string.action_remove_logo), string(R.string.team_leave_title), string(R.string.action_delete_team),
            string(R.string.team_delete_title), string(R.string.transfer_title), string(R.string.action_remove_member), string(R.string.member_remove_title, "Ece"),
            string(R.string.blocked_empty), string(R.string.blocked_loading), string(R.string.blocked_on, "25 Kasım 2026"), string(R.string.member_options_a11y, "Ece"),
        )
        sentences.forEach { shown -> assertTrue(shown, shown.isNotBlank() && shown.endsWith(".")) }
        (sentences + labels).forEach { shown ->
            listOf(" - ", " – ", " — ", "--", "olabilir.").forEach { assertFalse(shown, shown.contains(it)) }
            listOf("TEAM", "SEATS", "CAPTAIN", "NOT_FOUND", "STARTER", "SUBSTITUTE", "TOO_MANY", "JERSEY", "LOGO", "WEBP", "TRANSFER").forEach { assertFalse(shown, shown.contains(it)) }
            assertFalse(shown, Regex("%[0-9]").containsMatchIn(shown))
        }
        // The fixed numbers of the texts are the server's (design 2.3; backend/app/teams.py).
        assertTrue(string(R.string.team_new_seats_body).startsWith("Bir takımda her zaman ${TeamRules.STARTERS} as yeri olur."))
        assertTrue(string(R.string.team_new_seats_body).contains("yedek yeri sayısını ${TeamRules.DEFAULT_SUBSTITUTES} olarak"))
        listOf(R.string.team_new_seats_body, R.string.team_seats_body, R.string.seats_invalid).forEach {
            assertTrue(string(it), string(it).contains("0 ile ${TeamRules.SUBSTITUTES_MAX} arasında"))
        }
        // The team texts read the server's limits and waits through their arguments.
        assertEquals(string(R.string.team_wait_create, "2 dakika"), teamProblemText(TeamProblem.TooMany(120)).resolve(context))
        assertEquals(string(R.string.team_captain_only), teamProblemText(TeamProblem.CaptainOnly).resolve(context))
        assertEquals(string(R.string.teams_server_older), teamProblemText(TeamProblem.ServerOutdated).resolve(context))
        assertEquals(string(R.string.net_no_answer), teamProblemText(TeamProblem.Unreachable).resolve(context))
        // Any other answer reads as the account calls read it: a refusal changed nothing, a 5xx leaves the outcome open.
        assertEquals(string(R.string.account_refused), teamProblemText(TeamProblem.Failed(422, "VALIDATION_ERROR")).resolve(context))
        assertEquals(string(R.string.account_outcome_unknown), teamProblemText(TeamProblem.Failed(500, "INTERNAL")).resolve(context))
        assertEquals(string(R.string.account_outcome_unknown_on_the_way), teamProblemText(TeamProblem.Failed(502, "HTTP_502")).resolve(context))
        assertEquals(string(R.string.account_unexpected_change_signed_in), teamProblemText(TeamProblem.Failed(200, "UNEXPECTED_ANSWER")).resolve(context))
        // P7's refusals read the server's numbers, and a refusal without its number names none.
        assertEquals(string(R.string.jersey_taken, 12), teamProblemText(TeamProblem.JerseyTaken(12)).resolve(context))
        assertEquals(string(R.string.account_refused), teamProblemText(TeamProblem.JerseyTaken(null)).resolve(context))
        assertEquals(string(R.string.transfer_captain_limit, "Ece", 3), teamProblemText(TeamProblem.Limit(TeamLimitScope.CAPTAIN, 3, "u-2", "Ece")).resolve(context))
        assertEquals(string(R.string.team_must_transfer), teamProblemText(TeamProblem.MustTransfer).resolve(context))
        assertEquals(string(R.string.logo_invalid), teamProblemText(TeamProblem.LogoInvalid("small")).resolve(context))
        assertEquals(string(R.string.logo_unreadable), teamProblemText(TeamProblem.LogoUnreadable).resolve(context))
        assertEquals(string(R.string.logo_too_large), teamProblemText(TeamProblem.LogoTooLarge).resolve(context))
        // The verbatim texts of the design (plan §11.1, §11.2).
        assertEquals("%1\$s şu an en az %2\$d takımın kaptanı. Sunucu bir kişiye en çok %2\$d takımın kaptanlığını verir.", context.resources.getString(R.string.transfer_captain_limit))
        assertEquals("Bir üyeyi çıkarınca sunucu bu hesabın takıma yeniden katılmasını engeller. Engeli Çıkarılanlar listesinden kaldırabilirsin.", string(R.string.member_remove_body_no_invite))
        assertEquals("Takım arkadaşların forma numaranı görür.", string(R.string.team_jersey_body))
    }

    /**
     * The third review's B3: account_refused says that the server changed nothing, which the app knows only for a
     * 4xx. After a 5xx, or an answer the app can not read for a call that changes something, the app does not know
     * whether the server made the change, and the text says so.
     */
    @Test fun anAnswerThatLeavesTheOutcomeUnknownNeverSaysThatTheServerChangedNothing() {
        fun failure(status: Int, code: String) = AuthProblem.of(com.voleybolveveleybol.volleystat.data.ApiException(status, code, code, org.json.JSONObject()))
        val unknown = string(R.string.account_outcome_unknown)
        val onTheWay = string(R.string.account_outcome_unknown_on_the_way)
        val signedIn = listOf(AccountAction.DISPLAY_NAME, AccountAction.PASSWORD, AccountAction.DELETE)
        val changing = listOf(AccountAction.REGISTER) + signedIn
        (changing + AccountAction.LOGIN).forEach { action ->
            assertEquals("$action, a 500 with the envelope", unknown, text(failure(500, "INTERNAL"), action))
            // The fourth review's D4: a 5xx without the envelope may come from a gateway on the way, not from the server.
            assertEquals("$action, a 502 without it", onTheWay, text(failure(502, "HTTP_502"), action))
            assertEquals("$action, a 4xx changed nothing", string(R.string.account_refused), text(failure(409, "SOMETHING_NEW"), action))
        }
        // The registration can go to another server choice; the signed-in calls can not (fourth review, D4).
        assertEquals(string(R.string.account_unexpected_change), text(AuthProblem.UnexpectedAnswer, AccountAction.REGISTER))
        signedIn.forEach { action ->
            assertEquals("$action, an answer the app can not read", string(R.string.account_unexpected_change_signed_in), text(AuthProblem.UnexpectedAnswer, action))
        }
        // A login changes nothing the user asked for: the old text stays.
        assertEquals(string(R.string.account_unexpected), text(AuthProblem.UnexpectedAnswer, AccountAction.LOGIN))
        // A logout keeps its own text: the session stays on the phone, whatever the server did.
        assertEquals(string(R.string.logout_not_confirmed, string(R.string.action_logout_anyway)), text(failure(500, "INTERNAL"), AccountAction.LOGOUT))
        listOf(unknown, onTheWay, string(R.string.account_unexpected_change), string(R.string.account_unexpected_change_signed_in)).forEach {
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
     * The note about a new password edited while the change waited names who sent the change and that the server saved
     * the password (turkce-insansi-metin: every sentence names its actor). It names the sending, not a tap of Şifreyi
     * Değiştir: the keyboard's Done key sends the change as well (fourth review, D3).
     */
    @Test fun theNoteAboutAnEditedNewPasswordNamesTheSendingAndThatTheServerSavedThePassword() {
        val button = string(R.string.action_change_password)
        val note = string(R.string.password_not_handed_over_edited)
        assertTrue(note, note.contains("Sen değişikliği gönderdiğinde sunucu alanda o an yazan şifreyi yeni şifre olarak kaydetti."))
        assertFalse(note, note.contains("düğmesine dokunduğunda"))
        assertFalse(note, note.contains(button))
        assertFalse(note, note.contains("Sunucu yeni şifre olarak"))
    }

    /**
     * The fourth review's D4 (the reader's probe): a 502 without the API's envelope comes from something on the way (a
     * gateway), not from the API. The text does not say that the server reported an error.
     */
    @Test fun aGatewayErrorWithoutTheEnvelopeIsNotWordedAsTheServersOwnReport() {
        val problem = AuthProblem.of(com.voleybolveveleybol.volleystat.data.ApiException(502, "HTTP_502", "HTTP 502", org.json.JSONObject()))
        assertEquals(AuthProblem.ServerFailed(502), problem)
        val shown = text(problem, AccountAction.REGISTER)
        assertFalse("the text names a report the app never got from the server: $shown", shown.startsWith("Sunucu bir hata bildirdi."))
        assertTrue(shown, shown.startsWith("Uygulama sunucudan ya da sunucuya giden yoldan bir hata yanıtı aldı."))
        // A 5xx in the API's own envelope is the server's report.
        val reported = AuthProblem.of(com.voleybolveveleybol.volleystat.data.ApiException(500, "INTERNAL", "INTERNAL", org.json.JSONObject()))
        assertEquals(AuthProblem.ServerFailed(500, withEnvelope = true), reported)
        assertTrue(text(reported, AccountAction.REGISTER).startsWith("Sunucu bir hata bildirdi."))
    }

    /**
     * The fourth review's D4 (the reader's probe): the app keeps the server choice locked while a session is open, so an
     * unreadable answer to a signed-in call gives no advice about the server choice. Login and registration keep it.
     */
    @Test fun anUnreadableAnswerToASignedInCallGivesNoAdviceTheUserCanNotFollow() {
        com.voleybolveveleybol.volleystat.TestAccounts.seed(context as android.app.Application)
        val vm = AppViewModel(context as android.app.Application)
        val before = vm.state.value.settings.serverTarget
        val other = if (before == com.voleybolveveleybol.volleystat.model.ServerTarget.EMULATOR) com.voleybolveveleybol.volleystat.model.ServerTarget.USB_DEVICE
        else com.voleybolveveleybol.volleystat.model.ServerTarget.EMULATOR
        vm.setServerTarget(other)
        assertEquals("the server choice is locked while signed in", before, vm.state.value.settings.serverTarget)
        listOf(AccountAction.DISPLAY_NAME, AccountAction.PASSWORD, AccountAction.DELETE).forEach { action ->
            val shown = text(AuthProblem.UnexpectedAnswer, action)
            assertFalse("$action: the text sends a signed-in user to a choice the app has locked: $shown", shown.contains("Sunucu seçimini kontrol"))
        }
        listOf(AccountAction.LOGIN, AccountAction.REGISTER).forEach { action ->
            assertTrue("$action: the choice can change before a login or a registration", text(AuthProblem.UnexpectedAnswer, action).contains("Sunucu seçimini kontrol"))
        }
    }

    /** The fourth review's D1: the note of a password change whose screen was left and whose outcome is open. */
    @Test fun theNoteAboutALeftPasswordChangeSaysThatTheAppDoesNotKnowAndNamesTheOldPassword() {
        val note = string(R.string.password_left_outcome_unknown)
        assertTrue(note, note.startsWith("Şifre değiştirme ekranından sunucunun yanıtı gelmeden çıktın."))
        assertTrue(note, note.contains("Uygulama sunucunun şifreni değiştirip değiştirmediğini bilmiyor."))
        assertTrue(note, note.contains("Sunucu şifreni değiştirdiyse şifre yöneticin bu hesabın eski şifresini tutuyor olabilir."))
        listOf(" - ", " – ", " — ", "--").forEach { assertFalse(note, note.contains(it)) }
    }

    /**
     * The fifth check's P3: the profile shows the note only when no confirmation the app could read came (no answer, a 5xx, an
     * answer the app can not read), and a screen that stays and gets the same answer hands nothing over either. The note names
     * the missing confirmation as the reason the password manager got nothing, not the leaving of the screen. After an answer
     * the app could not read, that answer may have been the server's confirmation: the note does not say that none came.
     */
    @Test fun theNoteAboutALeftPasswordChangeNamesTheMissingConfirmationAsTheReasonNothingWasHandedOver() {
        val note = string(R.string.password_left_outcome_unknown)
        assertFalse("the note names leaving as the reason: $note", note.contains("Bu yüzden"))
        assertTrue(note, note.contains("Uygulama sunucudan okuyabildiği bir onay almadığı için şifre yöneticisine hiçbir şifre iletmedi."))
        assertFalse("after an unreadable answer the app does not know that no confirmation came: $note", note.contains("onayı gelmediği"))
        assertEquals(
            "Şifre değiştirme ekranından sunucunun yanıtı gelmeden çıktın. Uygulama sunucunun şifreni değiştirip değiştirmediğini bilmiyor. " +
                "Uygulama sunucudan okuyabildiği bir onay almadığı için şifre yöneticisine hiçbir şifre iletmedi. " +
                "Sunucu şifreni değiştirdiyse şifre yöneticin bu hesabın eski şifresini tutuyor olabilir.",
            note,
        )
    }

    /**
     * The fifth check's P2: the offer of Telefondan Sil stands after every state that leaves the mark: no answer, a 5xx, an
     * answer the app can not read, a process that ended while the deletion was on its way. After a 5xx the form said a moment
     * before that the server reported an error, so the offer never says that no answer came. It says what the app does not know.
     */
    @Test fun theOfferOfTelefondanSilNeverSaysThatNoAnswerCame() {
        val button = string(R.string.action_delete_from_phone)
        val named = string(R.string.deletion_unconfirmed, "ali", button)
        val unnamed = string(R.string.deletion_unconfirmed_unnamed, button)
        assertTrue(named, named.startsWith("Uygulama sunucunun ali hesabını silip silmediğini bilmiyor. "))
        assertTrue(unnamed, unnamed.startsWith("Uygulama sunucunun bir hesabı silip silmediğini bilmiyor. Uygulama bu hesabın kullanıcı adını da okuyamadı. "))
        listOf(named, unnamed).forEach { offer ->
            listOf("yanıt", "alamadı", "isteğinin").forEach { assertFalse("the offer says that no answer came: $offer", offer.contains(it)) }
            assertTrue(
                offer,
                offer.endsWith("Sunucu hesabı silmediyse giriş yapınca maçlarını yeniden görürsün. Sunucu hesabı sildiyse bu telefondaki verilerini Telefondan Sil düğmesiyle silebilirsin."),
            )
            listOf(" - ", " – ", " — ", "--").forEach { assertFalse(offer, offer.contains(it)) }
        }
    }

    /**
     * The eighth checker's S2: an answer the app can not read may come from something other than the server (a 3xx, a 4xx
     * without the API's envelope, a gateway's Bearer 401 to a request without a session, a 2xx HTML page, an empty 2xx). The
     * app can not know who sent it, so no text names the server as its sender. Each text opens with the answer that came to
     * the request. The rally list and the report fail the same way when a 2xx comes that the app can not read as the list or
     * the report.
     */
    @Test fun noTextOfAnAnswerTheAppCouldNotReadNamesTheServerAsItsSender() {
        listOf(R.string.account_unexpected, R.string.account_unexpected_change, R.string.account_unexpected_change_signed_in).forEach { id ->
            val shown = string(id)
            assertTrue(shown, shown.startsWith("Uygulama isteğe gelen yanıtı okuyamadı"))
        }
        assertTrue(string(R.string.rallies_outdated_server).startsWith("Uygulama isteğe gelen yanıtı ralli listesi olarak okuyamadı."))
        assertTrue(string(R.string.report_outdated_server).startsWith("Uygulama isteğe gelen yanıtı rapor olarak okuyamadı."))
        // No text of the app says that the server sent an answer the app could not read.
        val claims = listOf("sunucunun yanıtını okuyamadı", "okuyabildiği biçimde vermedi")
        val texts = R.string::class.java.fields.map { it.name to context.resources.getString(it.getInt(null)) }
        assertTrue("the app's own texts are there", texts.size > 300)
        texts.forEach { (name, shown) -> claims.forEach { assertFalse("$name: $shown", shown.contains(it, ignoreCase = true)) } }
    }
}
