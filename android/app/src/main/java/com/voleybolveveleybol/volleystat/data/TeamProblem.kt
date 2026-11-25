package com.voleybolveveleybol.volleystat.data

import com.voleybolveveleybol.volleystat.model.Seat
import java.io.IOException

/** Which limit a 409 TEAM_LIMIT_REACHED names (design 4.1): the teams a person joins, or the teams a person captains. */
enum class TeamLimitScope { MEMBER, CAPTAIN, UNKNOWN }

/** Why a substitute count was refused (422 SEATS_INVALID, `details.reason`). */
enum class SeatsInvalidReason { SUBSTITUTES_RANGE, CAPTAIN_SEAT, UNKNOWN }

/**
 * What went wrong with a team call (docs/API_V1.md section 10, Takımlar; design 10.7), one case per answer the screens
 * word differently. Every number here came from the server's answer. A 401 AUTH_REQUIRED ends the session before a
 * screen sees anything (AppViewModel), so it has no case of its own.
 */
sealed interface TeamProblem {
    /** 403 NOT_A_MEMBER: the server does not show this team to this account. */
    data object NoAccess : TeamProblem
    /** 403 CAPTAIN_ONLY: only the team's captain may do this, and this account is not the captain. */
    data object CaptainOnly : TeamProblem
    /** 404 TEAM_NOT_FOUND: the server holds no team with this id. */
    data object Gone : TeamProblem
    /** 422 NAME_INVALID: the name breaks the server's rule. */
    data object NameInvalid : TeamProblem
    /** 409 TEAM_ID_TAKEN: another account's team has the id this phone chose. */
    data object IdTaken : TeamProblem
    /**
     * 409 TEAM_LIMIT_REACHED. [limit] is null when the answer named none; [userId] names the member of a transfer, and
     * [memberName] is that member's display name as the team the app showed lists it (Kaptanlığı Devret, P7).
     */
    data class Limit(val scope: TeamLimitScope, val limit: Int?, val userId: String? = null, val memberName: String? = null) : TeamProblem
    /** 429 TOO_MANY_ATTEMPTS (scope TEAMS): the server stopped new teams of this account for [retryAfterSeconds]. */
    data class TooMany(val retryAfterSeconds: Int?) : TeamProblem
    /** 422 SEATS_INVALID. */
    data class SeatsInvalid(val reason: SeatsInvalidReason) : TeamProblem
    /**
     * 409 SEATS_BELOW_HOLDERS: after the change [holders] members would hold a seat of the type [seat] that has only
     * [seats]. [movedStarterDown] says that the refused call moved a starter to a substitute seat, which the app knows
     * because it sent the call (S8 of the design's v5 checkers): the screen then adds a hint.
     */
    data class SeatsBelowHolders(val seat: Seat, val holders: Int, val seats: Int, val movedStarterDown: Boolean = false) : TeamProblem
    /** 422 NOT_A_TEAM_MEMBER: the account the call named is no longer a member. */
    data class NotAMember(val userId: String?) : TeamProblem
    /** 409 JERSEY_TAKEN: another member wears [jersey] (null when the answer named no number). */
    data class JerseyTaken(val jersey: Int?) : TeamProblem
    /** 409 CAPTAIN_MUST_TRANSFER: the captain of a team with other members asked to leave it. */
    data object MustTransfer : TeamProblem
    /** 422 LOGO_INVALID: the server refused the logo's file [image] (`large` or `small`) and stored nothing. */
    data class LogoInvalid(val image: String?) : TeamProblem
    /** The app could not open or decode the picture the user picked, and sent nothing. */
    data object LogoUnreadable : TeamProblem
    /** The app could not make the picture's files as small as the server takes them (design 4.2), and sent nothing. */
    data object LogoTooLarge : TeamProblem
    /** 404 NOT_FOUND or 405 on a team route: the server is older than 0.10 and has no teams. */
    data object ServerOutdated : TeamProblem
    /** No answer came, or the request never left the phone. */
    data object Unreachable : TeamProblem
    /** Any other answer, by its status and its code as the client read them (UNEXPECTED_ANSWER: an answer the app could not read). */
    data class Failed(val status: Int, val code: String) : TeamProblem

    companion object {
        fun of(error: Throwable): TeamProblem = when (error) {
            is ApiException -> of(error)
            // The app's own steps with the picked picture come before any request, so they never read as no answer.
            is LogoCodec.Unreadable -> LogoUnreadable
            is LogoCodec.TooLarge -> LogoTooLarge
            is IOException -> Unreachable
            else -> Failed(200, "UNEXPECTED_ANSWER")
        }

        private fun of(error: ApiException): TeamProblem {
            val details = error.details
            fun number(name: String): Int? = (details.opt(name) as? Number)?.toInt()
            fun text(name: String): String? = details.optString(name).ifEmpty { null }
            return when (error.code) {
                "NOT_A_MEMBER" -> NoAccess
                "CAPTAIN_ONLY" -> CaptainOnly
                "TEAM_NOT_FOUND" -> Gone
                "NAME_INVALID" -> NameInvalid
                "TEAM_ID_TAKEN" -> IdTaken
                "TEAM_LIMIT_REACHED" -> Limit(enumOr(text("scope"), TeamLimitScope.UNKNOWN), number("limit")?.takeIf { it > 0 }, text("userId"))
                "TOO_MANY_ATTEMPTS" -> TooMany(number("retryAfterSeconds")?.takeIf { it >= 0 } ?: error.retryAfterSeconds)
                "SEATS_INVALID" -> SeatsInvalid(enumOr(text("reason"), SeatsInvalidReason.UNKNOWN))
                "SEATS_BELOW_HOLDERS" -> {
                    val seat = runCatching { Seat.valueOf(text("seat").orEmpty()) }.getOrNull()
                    val holders = number("holders")
                    val seats = number("seats")
                    if (seat == null || holders == null || seats == null) Failed(error.status, error.code) else SeatsBelowHolders(seat, holders, seats)
                }
                "NOT_A_TEAM_MEMBER" -> NotAMember(text("userId"))
                "JERSEY_TAKEN" -> JerseyTaken(number("jersey")?.takeIf { it in 1..99 })
                "CAPTAIN_MUST_TRANSFER" -> MustTransfer
                "LOGO_INVALID" -> LogoInvalid(text("image"))
                // The API's own envelope for a route it does not have: a server before 0.10.
                "NOT_FOUND", "METHOD_NOT_ALLOWED" -> ServerOutdated
                else -> Failed(error.status, error.code)
            }
        }

        private inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T): T =
            runCatching { enumValueOf<T>(name.orEmpty()) }.getOrDefault(fallback)
    }
}
