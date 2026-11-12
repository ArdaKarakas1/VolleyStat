package com.voleybolveveleybol.volleystat.core

/** Pure, platform-independent rules. Statistical coverage is never inferred from missing records. */
enum class Side { HOME, AWAY }
enum class ActionType { SERVE, RECEPTION, SET, ATTACK, BLOCK, DIG, FAULT }
enum class ActionOutcome { ACE, IN_PLAY, ERROR, PERFECT, POSITIVE, POOR, GOOD, ASSIST, KILL, POINT, TOUCH, CONTROLLED, SUCCESS, UNKNOWN }

data class VerifiedAction(
    val playerId: String?,
    val side: Side,
    val type: ActionType,
    val outcome: ActionOutcome
)

data class VerifiedRally(
    val id: String,
    val winner: Side,
    val initialServer: Side,
    val actions: List<VerifiedAction>,
    /** True only when *all* attacks in the rally were annotated by a trusted reviewer. */
    val attacksFullyAnnotated: Boolean = false
)

data class AttackStats(
    val recordedKills: Int,
    val recordedErrors: Int,
    /** Null when any included rally is not fully annotated. */
    val attempts: Int?,
    /** Null when attack attempts cannot be reliably counted. */
    val efficiency: Double?
)

object VolleyballRules {
    /** Standard indoor 6x6 rally scoring; target and minimum difference are configurable. */
    fun isSetFinished(homeScore: Int, awayScore: Int, target: Int = 25, minDifference: Int = 2): Boolean {
        require(homeScore >= 0 && awayScore >= 0)
        require(target > 0 && minDifference > 0)
        return maxOf(homeScore, awayScore) >= target && kotlin.math.abs(homeScore - awayScore) >= minDifference
    }

    /** Only the receiving winner rotates and gets the next serve. */
    fun nextServer(winner: Side, @Suppress("UNUSED_PARAMETER") serverBefore: Side): Side = winner
    fun shouldRotate(winner: Side, serverBefore: Side): Boolean = winner != serverBefore

    /** Exactly one point per verified rally, regardless of its number of individual actions. */
    fun score(rallies: List<VerifiedRally>): Pair<Int, Int> {
        require(rallies.map { it.id }.distinct().size == rallies.size) { "Duplicate rally ID" }
        return Pair(rallies.count { it.winner == Side.HOME }, rallies.count { it.winner == Side.AWAY })
    }
}

object Statistics {
    /** Attribute verified kills, errors only to players with explicit action identity.
     *  Never estimate total attempts from a subset of events.
     */
    fun playerAttackStats(rallies: List<VerifiedRally>, playerId: String): AttackStats {
        val attacks = rallies.flatMap { it.actions }
            .filter { it.playerId == playerId && it.type == ActionType.ATTACK }
        val kills = attacks.count { it.outcome == ActionOutcome.KILL }
        val errors = attacks.count { it.outcome == ActionOutcome.ERROR }
        val allAnnotated = rallies.isNotEmpty() && rallies.all { it.attacksFullyAnnotated }
        val attempts = if (allAnnotated) attacks.size else null
        val efficiency = attempts?.takeIf { it > 0 }?.let { (kills - errors).toDouble() / it }
        return AttackStats(kills, errors, attempts, efficiency)
    }

    /** Team point totals cannot be reconstructed from kill/ace/block counts. */
    fun teamRallyWins(rallies: List<VerifiedRally>, side: Side): Int = rallies.count { it.winner == side }
}
