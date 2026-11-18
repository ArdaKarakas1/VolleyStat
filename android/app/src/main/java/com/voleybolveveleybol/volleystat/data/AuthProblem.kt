package com.voleybolveveleybol.volleystat.data

import org.json.JSONException
import java.io.IOException

/** Whose failed attempts the server counted, from `details.scope` of a 429 (design 3.6). Each scope has its own text. */
enum class AttemptScope {
    /** This username, on phones that never logged in with it. */
    USER,
    /** This username on this phone (the device token). */
    DEVICE,
    /** The password check inside a session: password change and account deletion. */
    ACCOUNT,
    /** This internet address (from slice B6a). */
    SOURCE,
    /** Registrations from this internet address (from slice B6a). */
    REGISTER_SOURCE,
    /** A scope this app version does not know yet. */
    UNKNOWN,
}

/** The rule a refused password broke, from `details.reason` of PASSWORD_REJECTED. */
enum class PasswordRule { TOO_SHORT, TOO_LONG, CONTAINS_USERNAME, REPETITIVE, COMMON, UNKNOWN }

/** Why the sign-up gate stayed closed, from `details.reason` of SIGNUP_CLOSED (design 3.3). */
enum class SignupClosedReason { NO_LINK, OPEN_MATCH_LINK }

/**
 * What went wrong with an account call, one case per answer the screens word differently
 * (docs/API_V1.md section 10, design 10.7). The screens map each case to its Turkish text.
 */
sealed interface AuthProblem {
    /**
     * No answer came: the server is off, the network is down, or the answer was lost on the way. The request
     * may have reached the server, unless it never left the phone ([RequestNotSent]); the text is the same.
     */
    data object Unreachable : AuthProblem
    /** Something answered that is not this API, or an answer this app can not read. */
    data object UnexpectedAnswer : AuthProblem
    /**
     * A login or a registration came back from another address than the one the app talks to now. The
     * app opened no session with it (second review, R2-B); the server choice waits while a call is on
     * its way, so only a change of the address itself leads here.
     */
    data object ServerChanged : AuthProblem
    /** A server before 0.8: it has no account routes. */
    data object ServerOutdated : AuthProblem
    /** 401 LOGIN_FAILED: the username and the password do not match an account (the same answer for both). */
    data object LoginFailed : AuthProblem
    /** 401 AUTH_REQUIRED: the session ended. The view model has already left the account when a screen sees this. */
    data object SessionEnded : AuthProblem
    /** 429 TOO_MANY_ATTEMPTS. [retryAfterSeconds] is null when the server named no wait. */
    data class TooManyAttempts(val scope: AttemptScope, val retryAfterSeconds: Int?) : AuthProblem
    /** 503 SERVER_BUSY that outlasted the view model's own retries, or whose retries the screen stopped. */
    data class ServerBusy(val retryAfterSeconds: Int?) : AuthProblem
    data class PasswordRejected(val rule: PasswordRule) : AuthProblem
    data class SignupClosed(val reason: SignupClosedReason) : AuthProblem
    data object SignupCodeInvalid : AuthProblem
    /** 404 LINK_NOT_FOUND. Registration sends no link before slice B5, so this answer means a link the server does not hold. */
    data object LinkNotFound : AuthProblem
    data object UsernameTaken : AuthProblem
    data object UsernameInvalid : AuthProblem
    data object DisplayNameInvalid : AuthProblem
    /** 403 PASSWORD_WRONG: the current password given for a password change or a deletion is wrong. */
    data object PasswordWrong : AuthProblem
    /** Any other refusal, a 4xx: the server answered before it changed anything. */
    data class Refused(val status: Int, val code: String) : AuthProblem
    /**
     * A 5xx other than SERVER_BUSY, with the API's envelope or without it: the server, or something on
     * the way to it, failed while it had the request. The server may have made the change before it
     * failed, so the app does not know what changed (third review, B3). [withEnvelope] is true when the
     * answer carries the API's own error envelope: then the server itself reported the error. Without it
     * the answer may come from a gateway on the way, and the text does not name the server as its sender
     * (fourth review, D4).
     */
    data class ServerFailed(val status: Int, val withEnvelope: Boolean = false) : AuthProblem

    companion object {
        fun of(error: Throwable): AuthProblem = when (error) {
            is ApiException -> of(error)
            is AnswerFromAnotherServer -> ServerChanged
            is JSONException -> UnexpectedAnswer
            is IOException -> Unreachable
            else -> UnexpectedAnswer
        }

        private fun of(error: ApiException): AuthProblem {
            val details = error.details
            return when (error.code) {
                "LOGIN_FAILED" -> LoginFailed
                "AUTH_REQUIRED" -> SessionEnded
                "TOO_MANY_ATTEMPTS" -> TooManyAttempts(
                    enumOr(details.optString("scope"), AttemptScope.UNKNOWN),
                    details.optInt("retryAfterSeconds", -1).takeIf { it >= 0 } ?: error.retryAfterSeconds,
                )
                "SERVER_BUSY" -> ServerBusy(error.retryAfterSeconds)
                "PASSWORD_REJECTED" -> PasswordRejected(enumOr(details.optString("reason"), PasswordRule.UNKNOWN))
                // A server of slice B1 sends no reason: its only closed gate is the one without a link.
                "SIGNUP_CLOSED" -> SignupClosed(enumOr(details.optString("reason"), SignupClosedReason.NO_LINK))
                "SIGNUP_CODE_INVALID" -> SignupCodeInvalid
                "LINK_NOT_FOUND" -> LinkNotFound
                "USERNAME_TAKEN" -> UsernameTaken
                "USERNAME_INVALID" -> UsernameInvalid
                "DISPLAY_NAME_INVALID" -> DisplayNameInvalid
                "PASSWORD_WRONG" -> PasswordWrong
                // The API's own envelope for a route it does not have: a server before 0.8.
                "NOT_FOUND", "METHOD_NOT_ALLOWED" -> ServerOutdated
                // No envelope, or an answer that is not JSON: something else answered on that address.
                "UNEXPECTED_ANSWER" -> UnexpectedAnswer
                else -> when {
                    // The server, or something on the way to it, failed while it had the request: it may have made the change.
                    // Only the API's own envelope says that the server itself reported it (fourth review, D4).
                    error.status >= 500 -> ServerFailed(error.status, withEnvelope = !error.code.startsWith("HTTP_"))
                    // No envelope, or an envelope with a status this API never sends with one (a 3xx): not this API's refusal.
                    error.status !in 400..499 || error.code.startsWith("HTTP_") -> UnexpectedAnswer
                    else -> Refused(error.status, error.code)
                }
            }
        }

        private inline fun <reified T : Enum<T>> enumOr(name: String, fallback: T): T =
            runCatching { enumValueOf<T>(name) }.getOrDefault(fallback)
    }
}

/**
 * Whether [error], the failure that ended a change of the user's own account, leaves open whether the
 * server made the change: the request went out and no answer came, a 5xx other than SERVER_BUSY came, or
 * an answer the app can not read (third review, B3). A 3xx is such an answer, and so is a 4xx without the
 * API's envelope: a gateway on the way may have sent it, and the app can not tell whether the server got
 * the request (sixth checker, Q2). A 401 that names the Bearer scheme reads as AUTH_REQUIRED for a request
 * that carried the session, and is not one of them. The registration keeps the narrower
 * [ApiException.leavesOutcomeUnknown], which leaves out the 3xx and the 4xx: its mark sends a login to the
 * account that holds the username (seventh checker, R1). A request that never left the phone leaves nothing
 * open (fourth review, D2), nor does an answer from another address, whose session the app closed again.
 */
fun outcomeUnknownAfter(error: Throwable): Boolean = when (error) {
    is ApiException -> error.leavesOutcomeUnknown || error.status in 300..399 || error.code.startsWith("HTTP_")
    is RequestNotSent -> false
    is AnswerFromAnotherServer -> false
    else -> true
}

/** Thrown when the answer to a login or a registration came from another address than the one the app talks to now. */
class AnswerFromAnotherServer : Exception("The answer came from another server than the one the app talks to now")
