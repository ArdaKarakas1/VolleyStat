package com.voleybolveveleybol.volleystat.model

/**
 * The teams the server keeps (slice B4; docs/API_V1.md section 10, Takımlar; design 9.3). They live in memory only:
 * the view model reads them from the server and forgets them when the session ends (design 10.3, plan U20). The
 * local rosters of the personal flow are [Team] and stay on the phone.
 */

/** The two seat types of a team's pool (design 2.3). */
enum class Seat { STARTER, SUBSTITUTE }

/** A member's role in a team. */
enum class TeamRole { CAPTAIN, MEMBER }

/** The team's usual match day and time: [weekday] 1 for Monday to 7 for Sunday, [time] as HH:MM. */
data class Schedule(val weekday: Int, val time: String)

/** One seat type: its seats, the members who hold one, the seats left, and the seats held while somebody decides (0 until slice B5). */
data class SeatCount(val seats: Int, val taken: Int, val left: Int, val deciding: Int)

data class SeatCounts(val starter: SeatCount, val substitute: SeatCount)

/** One member of a team, as the server lists the members: in the order they joined. */
data class TeamMember(val userId: String, val displayName: String, val jersey: Int?, val role: TeamRole, val seat: Seat, val joinedAt: String)

/**
 * A team as the server shows it to the signed-in account (design 9.3, B4 form). [newJoins] and [waiting] are the
 * captain's only and null for every other member. [logoVersion] is null while the team has no logo.
 */
data class TeamInfo(
    val id: String,
    val poolId: String,
    val name: String,
    val tag: String,
    val schedule: Schedule?,
    val logoVersion: Int?,
    val myRole: TeamRole,
    val mySeat: Seat,
    val myJersey: Int?,
    val newJoins: Int?,
    val members: List<TeamMember>,
    val seats: SeatCounts,
    val waiting: Int?,
) {
    val captain: Boolean get() = myRole == TeamRole.CAPTAIN
}

/** What Takım Kur sends. The substitute seats start at the server's default and the creator takes a starter seat. */
data class TeamDraft(
    val name: String,
    val substituteSeats: Int = TeamRules.DEFAULT_SUBSTITUTES,
    val captainSeat: Seat = Seat.STARTER,
    val schedule: Schedule? = null,
    val jersey: Int? = null,
)

/**
 * The numbers the server checks (backend/app/teams.py). The texts that state a rule take them from here, and
 * TeamE2eTest ties them to the server.
 */
object TeamRules {
    const val NAME_MIN = 2
    const val NAME_MAX = 40
    const val STARTERS = 6
    const val SUBSTITUTES_MAX = 6
    const val DEFAULT_SUBSTITUTES = 2

    /** The pattern of a schedule's time the server takes. */
    val TIME = Regex("^([01][0-9]|2[0-3]):[0-5][0-9]$")
}
