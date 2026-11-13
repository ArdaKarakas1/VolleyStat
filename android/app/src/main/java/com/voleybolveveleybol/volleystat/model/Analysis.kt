package com.voleybolveveleybol.volleystat.model

/** An analysis job as the server reports it (docs/API_V1.md). Nothing here is computed on the phone. */
data class AnalysisJob(
    val id: String,
    val videoId: String,
    val state: String,
    val stage: String?,
    /** Measured progress, or null when the analyzer did not measure any. */
    val progressDone: Long?,
    val progressTotal: Long?,
    val analyzerId: String,
    val analyzerKind: String,
    /** True for the demo analyzer: its rally intervals are invented. */
    val mock: Boolean,
    val failureCode: String?,
    val rallies: Int,
    val reviewOpen: Int,
    val reviewTotal: Int,
) {
    val summary get() = AnalysisSummary(id, state, mock, reviewOpen, reviewTotal, failureCode)
}

data class ReviewCard(
    val id: String,
    val rallyId: String,
    val rallySequence: Int,
    val questionType: String,
    val scope: String,
    val clipStartMs: Long,
    val clipEndMs: Long,
    /** Only set when a model proposed a value. Never pre-selected in the UI. */
    val suggestedValue: String?,
    val options: List<String>,
    val status: String,
    val answer: String?,
    val version: Int,
) {
    val open get() = status == OPEN

    companion object {
        const val OPEN = "OPEN"
        const val RALLY_WINNER = "RALLY_WINNER"
        const val TERMINAL_ACTION = "TERMINAL_ACTION"
        const val SCORER = "SCORER"
        const val HOME = "HOME"
        const val AWAY = "AWAY"
        const val NOT_A_RALLY = "NOT_A_RALLY"
        const val UNSURE = "UNSURE"
        const val UNKNOWN_PLAYER = "UNKNOWN_PLAYER"
        fun jersey(number: Int) = "JERSEY:$number"
    }
}

data class ReviewDeck(val cards: List<ReviewCard>, val answered: Int, val total: Int)

/** A reported number together with what it means: measured, a lower bound, or unknown (then value is null). */
data class StatValue(val value: Int?, val status: String) {
    companion object {
        const val MEASURED = "MEASURED"
        const val PARTIAL = "PARTIAL"
        const val UNKNOWN = "UNKNOWN"
    }
}

data class TeamStats(
    val points: StatValue,
    val attackPoints: StatValue,
    val blockPoints: StatValue,
    val aces: StatValue,
    val opponentErrors: StatValue,
)

data class SetScore(val number: Int, val home: Int, val away: Int, val complete: Boolean)

data class PlayerLine(val home: Boolean, val jersey: Int, val points: Int, val attackPoints: Int, val blockPoints: Int, val aces: Int)

data class Coverage(val metricId: String, val eligibleN: Int, val verifiedN: Int)

data class MatchReport(
    val jobId: String,
    val analyzerId: String,
    val analyzerKind: String,
    val mock: Boolean,
    val verificationStatus: String,
    val ralliesProposed: Int,
    val ralliesVerified: Int,
    val ralliesRejected: Int,
    val ralliesUnresolved: Int,
    val ralliesOpen: Int,
    val scoreStatus: String,
    val setsWonHome: Int?,
    val setsWonAway: Int?,
    val sets: List<SetScore>,
    /** Sequence number of the first rally without a winner. Set scores stop there; null when none. */
    val scoreStoppedAtRally: Int? = null,
    val home: TeamStats,
    val away: TeamStats,
    val players: List<PlayerLine>,
    /** Points of a side that no player was chosen for. Null while that side's points are unknown. */
    val unattributedHome: Int?,
    val unattributedAway: Int?,
    val coverage: List<Coverage>,
)
