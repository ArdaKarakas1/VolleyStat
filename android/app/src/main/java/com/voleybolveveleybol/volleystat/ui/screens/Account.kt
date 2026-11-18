package com.voleybolveveleybol.volleystat.ui.screens

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalAutofillManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.voleybolveveleybol.volleystat.AccountChange
import com.voleybolveveleybol.volleystat.AccountUi
import com.voleybolveveleybol.volleystat.AppUiState
import com.voleybolveveleybol.volleystat.AppViewModel
import com.voleybolveveleybol.volleystat.LegacyQuestion
import com.voleybolveveleybol.volleystat.PasswordNotHandedOver
import com.voleybolveveleybol.volleystat.R
import com.voleybolveveleybol.volleystat.UnconfirmedDeletion
import com.voleybolveveleybol.volleystat.data.AccountUser
import com.voleybolveveleybol.volleystat.data.AttemptScope
import com.voleybolveveleybol.volleystat.data.AuthProblem
import com.voleybolveveleybol.volleystat.data.PasswordRule
import com.voleybolveveleybol.volleystat.model.normalizeUsername
import com.voleybolveveleybol.volleystat.model.suggestAnotherUsername
import com.voleybolveveleybol.volleystat.model.suggestUsername
import com.voleybolveveleybol.volleystat.model.usernameValid
import com.voleybolveveleybol.volleystat.ui.components.FactRow
import com.voleybolveveleybol.volleystat.ui.components.Notice
import com.voleybolveveleybol.volleystat.ui.components.PrimaryButton
import com.voleybolveveleybol.volleystat.ui.components.SecondaryButton
import com.voleybolveveleybol.volleystat.ui.components.SectionTitle
import com.voleybolveveleybol.volleystat.ui.components.Tone
import com.voleybolveveleybol.volleystat.ui.components.VsCard
import com.voleybolveveleybol.volleystat.ui.components.announced
import com.voleybolveveleybol.volleystat.ui.theme.Dimens

// ----- Texts (design 10.7 and 10.8) -----

/** The account call a problem belongs to. A few answers read differently by the call: a failed logout keeps the session. */
enum class AccountAction { LOGIN, REGISTER, DISPLAY_NAME, PASSWORD, LOGOUT, DELETE }

/** A wait the server named, written as "30 saniye" or "2 dakika" when the text is read. */
data class WaitArgument(val seconds: Int)

/** The label of a button, read from the strings when the text is read, so a text names the button the screen shows. */
data class LabelArgument(@param:StringRes val id: Int)

/** A text of the strings file and its arguments. */
data class AccountText(@param:StringRes val id: Int, val arguments: List<Any> = emptyList())

/**
 * The text that tells the user what the server answered to an account call. Every case of
 * [AuthProblem] has one; no text shows a server code, and none guesses a cause the app does not know.
 */
fun accountProblemText(problem: AuthProblem, action: AccountAction): AccountText {
    // A logout that did not get its 204 leaves the session on the phone, whatever the reason. The text names the way out.
    if (action == AccountAction.LOGOUT) return AccountText(R.string.logout_not_confirmed, listOf(LabelArgument(R.string.action_logout_anyway)))
    return when (problem) {
        AuthProblem.Unreachable -> AccountText(R.string.net_no_answer)
        // A call that changes something may have changed it before an answer the app can not read (third review, B3).
        AuthProblem.UnexpectedAnswer -> AccountText(if (action == AccountAction.LOGIN) R.string.account_unexpected else R.string.account_unexpected_change)
        AuthProblem.ServerChanged -> AccountText(R.string.account_server_changed)
        AuthProblem.ServerOutdated -> AccountText(R.string.account_server_older)
        AuthProblem.LoginFailed -> AccountText(R.string.login_failed)
        AuthProblem.SessionEnded -> AccountText(R.string.session_ended)
        is AuthProblem.TooManyAttempts -> {
            val seconds = problem.retryAfterSeconds ?: return AccountText(R.string.account_wait_no_time)
            val id = when (problem.scope) {
                AttemptScope.USER -> R.string.login_wait
                AttemptScope.DEVICE -> R.string.login_wait_device
                AttemptScope.ACCOUNT -> R.string.account_wait
                AttemptScope.SOURCE -> R.string.login_wait_source
                AttemptScope.REGISTER_SOURCE -> R.string.register_wait_source
                AttemptScope.UNKNOWN -> R.string.account_wait_unknown
            }
            AccountText(id, listOf(WaitArgument(seconds)))
        }
        is AuthProblem.ServerBusy -> AccountText(R.string.server_busy_gave_up, listOf(AppViewModel.BUSY_TRIES))
        is AuthProblem.PasswordRejected -> AccountText(
            when (problem.rule) {
                PasswordRule.TOO_SHORT -> R.string.password_too_short
                PasswordRule.TOO_LONG -> R.string.password_too_long
                PasswordRule.CONTAINS_USERNAME -> R.string.password_contains_username
                PasswordRule.REPETITIVE -> R.string.password_repetitive
                PasswordRule.COMMON -> R.string.password_common
                PasswordRule.UNKNOWN -> R.string.password_rejected
            }
        )
        // This app sends no link, so the gate it meets is the one a code opens.
        is AuthProblem.SignupClosed -> AccountText(R.string.register_gate)
        AuthProblem.SignupCodeInvalid -> AccountText(R.string.register_code_invalid)
        AuthProblem.UsernameTaken -> AccountText(R.string.username_taken)
        AuthProblem.UsernameInvalid -> AccountText(R.string.username_invalid)
        AuthProblem.DisplayNameInvalid -> AccountText(R.string.display_name_invalid)
        AuthProblem.PasswordWrong -> AccountText(R.string.password_wrong)
        // A link answer can not come before the invite links (slice B5): this app sends none.
        AuthProblem.LinkNotFound, is AuthProblem.Refused -> AccountText(R.string.account_refused)
        // A 5xx may come after the change: the text says that the app does not know, never that nothing changed (third review, B3).
        is AuthProblem.ServerFailed -> AccountText(R.string.account_outcome_unknown)
    }
}

/** A wait in seconds under a minute, else in whole minutes rounded up, so the text never promises a shorter wait than the server's. */
fun waitText(context: Context, seconds: Int): String =
    if (seconds < 60) context.getString(R.string.wait_seconds, seconds) else context.getString(R.string.wait_minutes, (seconds + 59) / 60)

fun AccountText.resolve(context: Context): String = context.getString(
    id,
    *arguments.map {
        when (it) {
            is WaitArgument -> waitText(context, it.seconds)
            is LabelArgument -> context.getString(it.id)
            else -> it
        }
    }.toTypedArray(),
)

@Composable
fun accountProblemMessage(problem: AuthProblem, action: AccountAction): String = accountProblemText(problem, action).resolve(LocalContext.current)

// ----- Shared parts -----

/**
 * What a submit does for the autofill service. Unset, it commits the platform's autofill session,
 * so the password-save sheet can appear while the request is on its way (design 10.2). The
 * autofill manager can not be stood in for, so a test counts the commits through this.
 */
val LocalAutofillCommit = staticCompositionLocalOf<(() -> Unit)?> { null }

@Composable
private fun autofillCommit(): () -> Unit {
    val override = LocalAutofillCommit.current
    val manager = LocalAutofillManager.current
    return override ?: { manager?.commit() }
}

/**
 * What leaving a form without the server's word does for the autofill service. Unset, it cancels
 * the platform's autofill session, so a password the server refused is never offered for saving.
 * A test counts the cancels through this.
 */
val LocalAutofillCancel = staticCompositionLocalOf<(() -> Unit)?> { null }

@Composable
private fun autofillCancel(): () -> Unit {
    val override = LocalAutofillCancel.current
    val manager = LocalAutofillManager.current
    return override ?: { manager?.cancel() }
}

/**
 * The frame of the account screens: an optional back arrow, scrolling content and the actions at
 * the bottom. A refusal of the server ([problem]) stands at the top of the content, and the content
 * scrolls up to it when it appears: in the bottom bar a long text would leave a small phone with
 * large text no room for the form.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountScaffold(
    title: String?,
    onBack: (() -> Unit)?,
    actions: @Composable ColumnScope.() -> Unit,
    problem: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    LaunchedEffect(problem) { if (problem != null) scroll.animateScrollTo(0) }
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (title != null) {
                CenterAlignedTopAppBar(
                    title = { Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() }) },
                    navigationIcon = {
                        if (onBack != null) IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back)) }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            }
        },
        bottomBar = {
            Column(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                    .padding(horizontal = Dimens.screenPadding, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = actions,
            )
        },
    ) { insets ->
        Column(
            Modifier.fillMaxSize().padding(insets).verticalScroll(scroll).padding(horizontal = Dimens.screenPadding, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(Dimens.gapLarge),
        ) {
            if (problem != null) Notice(problem, Tone.NEGATIVE, Modifier.announced())
            content()
        }
    }
}

/**
 * "The app waits for the server's answer", while an account call is on its way. While the app
 * waits out a SERVER_BUSY answer before its next try ([retrying]), the line says that instead:
 * the answer already came (review finding T5).
 */
@Composable
fun AccountWaiting(retrying: Boolean, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().announced(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(stringResource(if (retrying) R.string.server_busy else R.string.account_waiting), style = MaterialTheme.typography.bodyMedium)
    }
}

/** What the server answered to [action], or that the answer is still on its way. Nothing while neither is the case. */
@Composable
fun AccountOutcome(account: AccountUi, action: AccountAction, modifier: Modifier = Modifier) {
    when {
        account.busy -> AccountWaiting(account.retryingAfterBusy, modifier)
        account.problem != null -> Notice(accountProblemMessage(account.problem, action), Tone.NEGATIVE, modifier.announced())
    }
}

/**
 * A password field (design 10.9): hidden while typed, a show/hide toggle with its own description,
 * and the autofill type of [contentType]. The field keeps no copy of the password anywhere else.
 */
@Composable
fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    contentType: ContentType,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    imeAction: ImeAction = ImeAction.Done,
    onDone: () -> Unit = {},
    enabled: Boolean = true,
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    val description = stringResource(if (visible) R.string.password_hide else R.string.password_show)
    OutlinedTextField(
        value = value, onValueChange = onValueChange, enabled = enabled,
        label = { Text(label) }, singleLine = true,
        supportingText = supporting?.let { { Text(it) } },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone() }, onGo = { onDone() }),
        trailingIcon = {
            TextButton(onClick = { visible = !visible }, modifier = Modifier.semantics { this.contentDescription = description }) {
                Text(stringResource(if (visible) R.string.password_toggle_hide else R.string.password_toggle_show))
            }
        },
        modifier = modifier.fillMaxWidth().semantics { this.contentType = contentType },
    )
}

/** A plain text field of the account forms, with its autofill type. */
@Composable
private fun AccountField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    contentType: ContentType?,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    isError: Boolean = false,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange,
        label = { Text(label) }, singleLine = true, isError = isError,
        supportingText = supporting?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(capitalization = capitalization, keyboardType = keyboardType, autoCorrectEnabled = false, imeAction = ImeAction.Next),
        modifier = modifier.fillMaxWidth().then(if (contentType != null) Modifier.semantics { this.contentType = contentType } else Modifier),
    )
}

// ----- Screens -----

/**
 * The first start without any account on this phone (design 10.1, `start`). Kod Gir, the main
 * action of the design, comes with the invite links (slice B6); until then a registration takes
 * the operator's code. A build without a server address (a release build until the hosting slice)
 * can neither open an account nor log in: the screen says so in place of the promise, and its
 * buttons open no form (third review, B5).
 */
@Composable
fun StartScreen(onCreateAccount: () -> Unit, onLogin: () -> Unit, serverConfigured: Boolean = true) {
    AccountScaffold(
        title = null, onBack = null,
        actions = {
            PrimaryButton(stringResource(R.string.action_create_account), onCreateAccount, enabled = serverConfigured, icon = R.drawable.ic_person_add)
            SecondaryButton(stringResource(R.string.action_login), onLogin, enabled = serverConfigured)
        },
    ) {
        Spacer(Modifier.height(24.dp))
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Image(painterResource(R.drawable.brand_logo), contentDescription = stringResource(R.string.logo_description), modifier = Modifier.size(88.dp).clip(CircleShape))
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.tagline), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        if (serverConfigured) {
            VsCard { Text(stringResource(R.string.start_body), style = MaterialTheme.typography.bodyLarge) }
        } else {
            Notice(stringResource(R.string.server_none), Tone.NEUTRAL)
            VsCard { Text(stringResource(R.string.start_no_server), style = MaterialTheme.typography.bodyLarge) }
        }
    }
}

/**
 * Giriş Yap (design 10.1, `login`): username and password with the autofill semantics of a login.
 * The submit commits the autofill, so the password-save sheet can appear while the request is on
 * its way. [server] is the server choice of a debug build; a build without a server says so.
 */
@Composable
fun LoginScreen(
    account: AccountUi,
    onLogin: (username: String, password: String) -> Unit,
    onCreateAccount: () -> Unit,
    onBack: (() -> Unit)? = null,
    serverConfigured: Boolean = true,
    server: @Composable () -> Unit = {},
    initialUsername: String = "",
    initialPassword: String = "",
    onDeletePhoneData: () -> Unit = {},
) {
    var username by rememberSaveable { mutableStateOf(initialUsername) }
    // Never saved into the instance state: the password lives in memory only, until the screen goes.
    var password by remember { mutableStateOf(initialPassword) }
    val commitAutofill = autofillCommit()
    val ready = serverConfigured && !account.busy && username.isNotBlank() && password.isNotEmpty()
    val submit = {
        if (ready) {
            commitAutofill()
            onLogin(username, password)
        }
    }
    AccountScaffold(
        title = stringResource(R.string.action_login), onBack = onBack,
        problem = account.problem?.let { accountProblemMessage(it, AccountAction.LOGIN) },
        actions = {
            if (account.busy) AccountWaiting(account.retryingAfterBusy)
            PrimaryButton(stringResource(R.string.action_login), submit, enabled = ready)
            SecondaryButton(stringResource(R.string.action_create_account), onCreateAccount, enabled = !account.busy)
        },
    ) {
        account.unconfirmedDeletion?.let { UnconfirmedDeletionCard(it, enabled = !account.busy, onDelete = onDeletePhoneData) }
        when {
            account.sessionEnded -> Notice(stringResource(R.string.session_ended), Tone.CAUTION, Modifier.announced())
            account.confirmed == AccountChange.DELETED -> Notice(stringResource(R.string.account_deleted), Tone.POSITIVE, Modifier.announced())
            account.confirmed == AccountChange.PHONE_DATA_DELETED -> {
                val name = account.deletedFromPhone?.username
                val done = if (name != null) stringResource(R.string.phone_data_deleted, name) else stringResource(R.string.phone_data_deleted_unnamed)
                Notice(done, Tone.POSITIVE, Modifier.announced())
            }
        }
        Text(stringResource(R.string.login_intro), style = MaterialTheme.typography.bodyMedium)
        if (!serverConfigured) Notice(stringResource(R.string.server_none), Tone.NEUTRAL)
        AccountField(username, { username = it }, stringResource(R.string.field_username), ContentType.Username, keyboardType = KeyboardType.Ascii)
        PasswordField(password, { password = it }, stringResource(R.string.field_password), ContentType.Password, imeAction = ImeAction.Go, onDone = submit)
        if (serverConfigured) server()
    }
}

/**
 * A deletion whose answer was lost, after its session left the phone (review finding S1). The text
 * says what the app does not know. Telefondan Sil asks once more and then deletes only the phone's
 * data of that account; the server is not asked. An account whose username the phone can no longer
 * read is named as such.
 */
@Composable
private fun UnconfirmedDeletionCard(doubt: UnconfirmedDeletion, enabled: Boolean, onDelete: () -> Unit) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    val label = stringResource(R.string.action_delete_from_phone)
    val name = doubt.username
    val offer = if (name != null) stringResource(R.string.deletion_unconfirmed, name, label) else stringResource(R.string.deletion_unconfirmed_unnamed, label)
    val body = if (name != null) stringResource(R.string.delete_from_phone_body, name) else stringResource(R.string.delete_from_phone_body_unnamed)
    Notice(offer, Tone.CAUTION, Modifier.announced())
    TextButton(
        onClick = { confirming = true }, enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.touchTarget),
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
    ) { Text(label, style = MaterialTheme.typography.labelLarge) }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.delete_from_phone_title)) },
            text = { Text(body, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(
                    onClick = { confirming = false; onDelete() }, enabled = enabled,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(label) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.action_give_up)) } },
        )
    }
}

/**
 * Hesap Oluştur (design 10.1, `register`): the display name, the username the app suggests from it
 * (editable), the password and the operator's sign-up code. A taken username gets two digits once,
 * and the screen says why.
 */
@Composable
fun RegisterScreen(
    account: AccountUi,
    onRegister: (displayName: String, username: String, password: String, code: String) -> Unit,
    onHaveAccount: () -> Unit,
    onBack: () -> Unit,
    serverConfigured: Boolean = true,
    server: @Composable () -> Unit = {},
    initialDisplayName: String = "",
    initialUsername: String? = null,
    initialPassword: String = "",
    initialCode: String = "",
) {
    var displayName by rememberSaveable { mutableStateOf(initialDisplayName) }
    var username by rememberSaveable { mutableStateOf(initialUsername ?: suggestUsername(initialDisplayName)) }
    // The username follows the display name until the user types one of their own.
    var ownUsername by rememberSaveable { mutableStateOf(initialUsername != null) }
    // The password and the one-time code are never saved into the instance state.
    var password by remember { mutableStateOf(initialPassword) }
    var code by remember { mutableStateOf(initialCode) }
    // The username of the last request, so that a USERNAME_TAKEN changes the field once.
    var sent by rememberSaveable { mutableStateOf<String?>(null) }
    val commitAutofill = autofillCommit()

    LaunchedEffect(account.problem) {
        val taken = sent
        if (account.problem == AuthProblem.UsernameTaken && taken != null && normalizeUsername(username) == taken) {
            username = suggestAnotherUsername(taken)
            ownUsername = true
        }
    }

    val usernameOk = usernameValid(normalizeUsername(username))
    val ready = serverConfigured && !account.busy && displayName.isNotBlank() && usernameOk && password.isNotEmpty()
    val submit = {
        if (ready) {
            commitAutofill()
            sent = normalizeUsername(username)
            onRegister(displayName, username, password, code)
        }
    }
    AccountScaffold(
        title = stringResource(R.string.action_create_account), onBack = onBack,
        problem = account.problem?.let { accountProblemMessage(it, AccountAction.REGISTER) },
        actions = {
            if (account.busy) AccountWaiting(account.retryingAfterBusy)
            PrimaryButton(stringResource(R.string.action_create_account), submit, enabled = ready)
            SecondaryButton(stringResource(R.string.action_have_account), onHaveAccount, enabled = !account.busy)
        },
    ) {
        Text(stringResource(R.string.register_data), style = MaterialTheme.typography.bodyMedium)
        if (!serverConfigured) Notice(stringResource(R.string.server_none), Tone.NEUTRAL)
        AccountField(
            displayName,
            {
                displayName = it.take(80)
                if (!ownUsername) username = suggestUsername(displayName)
            },
            stringResource(R.string.field_display_name), ContentType.PersonFullName,
            supporting = stringResource(R.string.register_display_hint), capitalization = KeyboardCapitalization.Words,
        )
        val invalid = username.isNotBlank() && !usernameOk
        AccountField(
            username,
            {
                username = it.take(60)
                // A cleared field follows the display name again.
                ownUsername = username.isNotEmpty()
            },
            stringResource(R.string.field_username), ContentType.NewUsername,
            supporting = stringResource(if (invalid) R.string.username_invalid else R.string.register_username_hint),
            isError = invalid, keyboardType = KeyboardType.Ascii,
        )
        if (!ownUsername && username.isNotEmpty()) Notice(stringResource(R.string.register_username_suggested), Tone.NEUTRAL)
        PasswordField(
            password, { password = it }, stringResource(R.string.field_password), ContentType.NewPassword,
            supporting = stringResource(R.string.register_password_hint), imeAction = ImeAction.Next,
        )
        AccountField(
            code, { code = it.take(40) }, stringResource(R.string.field_signup_code), null,
            supporting = stringResource(R.string.register_code_hint), capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii,
        )
        if (serverConfigured) server()
    }
}

/**
 * The question of the first login about the data app 0.7 kept without an account (design 10.3).
 * It lists what would move; one answer, and the buttons wait for it to land. [moving] is the view
 * model's word that an answer is on its way, so a move that did not reach the disk gives the
 * buttons back each time it fails (review finding F4).
 */
@Composable
fun LegacyScreen(question: LegacyQuestion, saveFailed: Boolean, moving: Boolean, onAnswer: (move: Boolean) -> Unit) {
    AccountScaffold(
        title = stringResource(R.string.legacy_title), onBack = null,
        actions = {
            if (saveFailed) Notice(stringResource(R.string.store_save_failed), Tone.NEGATIVE, Modifier.announced())
            PrimaryButton(stringResource(R.string.legacy_move), { onAnswer(true) }, enabled = !moving)
            SecondaryButton(stringResource(R.string.legacy_keep), { onAnswer(false) }, enabled = !moving)
        },
    ) {
        Text(stringResource(R.string.legacy_body), style = MaterialTheme.typography.bodyMedium)
        VsCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (question.titles.isNotEmpty()) {
                    SectionTitle(pluralStringResource(R.plurals.count_matches, question.titles.size, question.titles.size))
                    question.titles.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
                if (question.teams > 0) Text(pluralStringResource(R.plurals.legacy_teams, question.teams, question.teams), style = MaterialTheme.typography.bodyMedium)
                if (question.recordings > 0) Text(stringResource(R.string.legacy_recordings, question.recordings), style = MaterialTheme.typography.bodyMedium)
                if (question.draft) Text(stringResource(R.string.legacy_draft), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text(stringResource(R.string.legacy_keep_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ----- Profile: the account rows (design 10.1, Profile) -----

/**
 * The account card of the profile: the username, the display name the server keeps, Şifreyi
 * Değiştir, Çıkış Yap and Hesabı Sil. A row says that something changed only after the server
 * confirmed it. The password change and the deletion take a password, so each has a screen of its
 * own; the logout asks in a dialog.
 */
@Composable
fun AccountSection(state: AppUiState, user: AccountUser, actions: ProfileAccountActions) {
    val account = state.account
    // Set by Adı Kaydet: the card shows the outcome of its own call only.
    var savingName by rememberSaveable { mutableStateOf(false) }
    var logout by rememberSaveable { mutableStateOf(false) }

    SectionTitle(stringResource(R.string.profile_account))
    VsCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FactRow(stringResource(R.string.profile_username), user.username)
            if (user.passwordTemporary) Notice(stringResource(R.string.password_temporary), Tone.CAUTION)
            // The field starts again from the server's name whenever the server names another one.
            var name by rememberSaveable(user.displayName) { mutableStateOf(user.displayName) }
            AccountField(
                name, { name = it.take(80) }, stringResource(R.string.field_display_name), ContentType.PersonFullName,
                supporting = stringResource(R.string.register_display_hint), capitalization = KeyboardCapitalization.Words,
            )
            SecondaryButton(
                stringResource(R.string.action_save_name),
                {
                    actions.onClearProblem()
                    savingName = true
                    actions.onChangeDisplayName(name)
                },
                enabled = name.isNotBlank() && name.trim() != user.displayName && !account.busy,
            )
            if (savingName && !logout) {
                if (account.confirmed == AccountChange.DISPLAY_NAME) Notice(stringResource(R.string.display_name_saved), Tone.POSITIVE, Modifier.announced())
                else AccountOutcome(account, AccountAction.DISPLAY_NAME)
            }
            SecondaryButton(stringResource(R.string.action_change_password), { savingName = false; actions.onOpenPasswordChange() }, enabled = !account.busy)
            // The password screen closes on the server's confirmation; the card says what the server did, and why the
            // new password reached no password manager when it did not (R2-D, and the third review's B1 and B2).
            if (account.confirmed == AccountChange.PASSWORD) {
                Notice(stringResource(R.string.password_changed), Tone.POSITIVE, Modifier.announced())
                account.newPasswordNotHandedOver?.let { Notice(notHandedOverText(it), Tone.CAUTION, Modifier.announced()) }
            }
            SecondaryButton(
                stringResource(R.string.action_logout),
                {
                    actions.onClearProblem()
                    savingName = false
                    logout = true
                },
                enabled = !account.busy,
            )
            TextButton(
                onClick = { savingName = false; actions.onOpenDeletion() }, enabled = !account.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.touchTarget),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.action_delete_account), style = MaterialTheme.typography.labelLarge) }
        }
    }
    // The dialog can not be left while its call is on its way: its answer would have nowhere to show.
    if (logout) LogoutDialog(account, uploading = state.upload?.running == true, onClose = { if (!account.busy) logout = false }, actions.onLogout)
}

/** Why the password manager did not get the confirmed new password, in the words of the profile. */
@Composable
private fun notHandedOverText(reason: PasswordNotHandedOver): String = when (reason) {
    PasswordNotHandedOver.SCREEN_REBUILT -> stringResource(R.string.password_not_handed_over)
    PasswordNotHandedOver.SCREEN_LEFT -> stringResource(R.string.password_not_handed_over_left)
    PasswordNotHandedOver.PASSWORD_EDITED -> stringResource(R.string.password_not_handed_over_edited, stringResource(R.string.action_change_password))
}

@Composable
private fun LogoutDialog(account: AccountUi, uploading: Boolean, onClose: () -> Unit, onLogout: (evenWithoutAnswer: Boolean) -> Unit) {
    // The server did not confirm the logout, so the session is still on the phone: the way out leaves it on this phone only.
    val unconfirmed = account.problem != null && !account.busy
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.logout_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.logout_body), style = MaterialTheme.typography.bodyMedium)
                if (uploading) Text(stringResource(R.string.logout_upload), style = MaterialTheme.typography.bodyMedium)
                AccountOutcome(account, AccountAction.LOGOUT)
            }
        },
        confirmButton = {
            if (unconfirmed) {
                TextButton(onClick = { onLogout(true) }) { Text(stringResource(R.string.action_logout_anyway)) }
            } else {
                TextButton(onClick = { onLogout(false) }, enabled = !account.busy) { Text(stringResource(R.string.action_logout)) }
            }
        },
        dismissButton = { TextButton(onClick = onClose, enabled = !account.busy) { Text(stringResource(R.string.action_give_up)) } },
    )
}

/** A button for an action that deletes: the error colour, and the same size as the other buttons. */
@Composable
private fun DangerButton(text: String, onClick: () -> Unit, enabled: Boolean) {
    Button(
        onClick, Modifier.fillMaxWidth().heightIn(min = Dimens.buttonHeight), enabled = enabled, shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
    ) { Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center) }
}

/**
 * Şifreyi Değiştir: the current password and a new one. The screen closes once the server confirmed
 * the change. The password manager hears of the new password only then: the server can refuse a
 * password the app can not check in advance (a common one, say), and a refused password must never
 * be offered for saving (review finding S6). The username field, read-only, tells the password
 * manager which entry the new password belongs to.
 *
 * The password manager never gets a password other than the one the server got. The fields stay
 * editable while the change waits (Android's autofill commit may need them), so the screen keeps the
 * new password it sent, in memory only, and commits at the confirmation only when the field still
 * holds exactly that text (third review, B2).
 *
 * When the screen can not hand a confirmed new password over, it says why, and the profile says so:
 * - Android rebuilt it while the change was on its way (a dark theme switch, say), and the call went
 *   on (F8). The rebuilt screen knows that it sent a change, but its password fields start empty by
 *   design. At the confirmation it calls [onNewPasswordNotHandedOver] (second review, R2-D).
 * - The new password was edited while the change waited: [onNewPasswordNotHandedOver] at the confirmation.
 * - The screen goes while a change it sent is not committed: [onGoneWithoutHandingOver]. The user left
 *   it, or Android rebuilds it; the caller tells the two apart (third review, B1).
 */
@Composable
fun PasswordChangeScreen(
    account: AccountUi,
    onChange: (current: String, new: String) -> Unit,
    onBack: () -> Unit,
    onNewPasswordNotHandedOver: (PasswordNotHandedOver) -> Unit = {},
    onGoneWithoutHandingOver: (PasswordNotHandedOver) -> Unit = {},
) {
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    // A change this screen sent, through a rebuild of the screen.
    var sent by rememberSaveable { mutableStateOf(false) }
    // The new password of that change while these fields show it, in memory only: only it may be committed, and only once.
    var sentPassword by remember { mutableStateOf<String?>(null) }
    var committed by remember { mutableStateOf(false) }
    var toldNotHandedOver by remember { mutableStateOf(false) }
    val commitAutofill by rememberUpdatedState(autofillCommit())
    val cancelAutofill by rememberUpdatedState(autofillCancel())
    val notHandedOver by rememberUpdatedState(onNewPasswordNotHandedOver)
    val gone by rememberUpdatedState(onGoneWithoutHandingOver)
    // A sent password the field no longer holds is one the password manager must not get.
    fun reasonFromTheseFields() = if (sentPassword != null && new != sentPassword) PasswordNotHandedOver.PASSWORD_EDITED else null
    LaunchedEffect(account.confirmed) {
        if (!sent || account.confirmed != AccountChange.PASSWORD || committed || toldNotHandedOver) return@LaunchedEffect
        val reason = if (sentPassword == null) PasswordNotHandedOver.SCREEN_REBUILT else reasonFromTheseFields()
        if (reason == null) {
            committed = true
            commitAutofill()
        } else {
            toldNotHandedOver = true
            notHandedOver(reason)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            if (committed) return@onDispose
            cancelAutofill()
            if (sent && !toldNotHandedOver) gone(reasonFromTheseFields() ?: PasswordNotHandedOver.SCREEN_LEFT)
        }
    }
    val ready = current.isNotEmpty() && new.isNotEmpty() && !account.busy
    val submit = {
        if (ready) {
            sent = true
            sentPassword = new
            onChange(current, new)
        }
    }
    AccountScaffold(
        title = stringResource(R.string.action_change_password), onBack = onBack,
        problem = account.problem?.let { accountProblemMessage(it, AccountAction.PASSWORD) },
        actions = {
            if (account.busy) AccountWaiting(account.retryingAfterBusy)
            PrimaryButton(stringResource(R.string.action_change_password), submit, enabled = ready)
        },
    ) {
        Text(stringResource(R.string.password_change_body), style = MaterialTheme.typography.bodyMedium)
        account.user?.let { user ->
            OutlinedTextField(
                value = user.username, onValueChange = {}, readOnly = true, singleLine = true,
                label = { Text(stringResource(R.string.field_username)) },
                modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Username },
            )
        }
        PasswordField(current, { current = it }, stringResource(R.string.field_current_password), ContentType.Password, imeAction = ImeAction.Next)
        PasswordField(new, { new = it }, stringResource(R.string.field_new_password), ContentType.NewPassword, supporting = stringResource(R.string.register_password_hint), onDone = submit)
    }
}

/**
 * Hesabı Sil: what the server and the phone delete, what stays on the server, and the password
 * that confirms it. Nothing on the phone goes before the server's answer.
 */
@Composable
fun DeleteAccountScreen(account: AccountUi, serverMatches: Int, onDelete: (password: String) -> Unit, onBack: () -> Unit) {
    var password by remember { mutableStateOf("") }
    val ready = password.isNotEmpty() && !account.busy
    AccountScaffold(
        title = stringResource(R.string.action_delete_account), onBack = onBack,
        problem = account.problem?.let { accountProblemMessage(it, AccountAction.DELETE) },
        actions = {
            if (account.busy) AccountWaiting(account.retryingAfterBusy)
            DangerButton(stringResource(R.string.action_delete_account), { if (ready) onDelete(password) }, enabled = ready)
        },
    ) {
        Text(stringResource(R.string.delete_account_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.delete_account_body), style = MaterialTheme.typography.bodyMedium)
        if (serverMatches > 0) Notice(stringResource(R.string.delete_account_server_matches, serverMatches), Tone.CAUTION)
        PasswordField(password, { password = it }, stringResource(R.string.field_your_password), ContentType.Password)
    }
}
