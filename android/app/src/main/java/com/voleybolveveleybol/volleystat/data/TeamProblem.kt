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
    /** 409 TEAM_LIMIT_REACHED. [limit] is null when the answer named none; [userId] names the member of a transfer. */
    data class Limit(val scope: TeamLimitScope, val limit: Int?, val userId: String? = null) : TeamProblem
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
    /** 404 NOT_FOUND or 405 on a team route: the server is older than 0.10 and has no teams. */
    data object ServerOutdated : TeamProblem
    /** No answer came, or the request never left the phone. */
    data object Unreachable : TeamProblem
    /** Any other answer, by its status and its code as the client read them (UNEXPECTED_ANSWER: an answer the app could not read). */
    data class Failed(val status: Int, val code: String) : TeamProblem

    companion object {
        fun of(error: Throwable): TeamProblem = when (error) {
            is ApiException -> of(error)
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
                // The API's own envelope for a route it does not have: a server before 0.10.
                "NOT_FOUND", "METHOD_NOT_ALLOWED" -> ServerOutdated
                else -> Failed(error.status, error.code)
            }
        }

        private inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T): T =
            runCatching { enumValueOf<T>(name.orEmpty()) }.getOrDefault(fallback)
    }
}
