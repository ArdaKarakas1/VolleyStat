package com.voleybolveveleybol.volleystat.model

/**
 * How far past a rally's own start and end its editor reaches. A boundary that is further off
 * than this belongs to another rally, which is a job for adding, splitting or merging.
 */
const val EDITOR_REACH_MS = 30_000L

/** A pause between two rallies that is shorter than this can not hold a rally, so the list offers nothing there. */
const val SHORTEST_GAP_MS = 1_000L

/**
 * What the range editor is open on, with the part of the video it may move in. The window always
 * comes from the list as the server last sent it: no other rally lies inside it, so no position
 * the editor can reach overlaps one.
 */
sealed interface EditTarget {
    val windowStartMs: Long
    val windowEndMs: Long

    data class OfRally(val rally: Rally, override val windowStartMs: Long, override val windowEndMs: Long) : EditTarget {
        /** The server merges a rally with the one numbered just before it. The first rally has none. */
        val hasPrevious get() = rally.sequence > 1
    }

    data class OfGap(override val windowStartMs: Long, override val windowEndMs: Long) : EditTarget
}

/** The rally with [rallyId] and the free time around it, or null when the list no longer holds it. */
fun rallyTarget(list: RallyList, rallyId: String): EditTarget.OfRally? {
    val rally = list.rallies.firstOrNull { it.id == rallyId } ?: return null
    val others = list.rallies.filter { it.id != rallyId }
    val freeFrom = others.filter { it.endMs <= rally.startMs }.maxOfOrNull { it.endMs } ?: 0L
    val freeUntil = others.filter { it.startMs >= rally.endMs }.minOfOrNull { it.startMs } ?: list.videoDurationMs
    return EditTarget.OfRally(rally, maxOf(freeFrom, rally.startMs - EDITOR_REACH_MS), minOf(freeUntil, rally.endMs + EDITOR_REACH_MS))
}

/** The time without a rally around [atMs], or null when a rally covers that moment or no time is left there. */
fun gapTarget(list: RallyList, atMs: Long): EditTarget.OfGap? {
    if (list.rallies.any { it.startMs <= atMs && atMs < it.endMs }) return null
    val start = list.rallies.filter { it.endMs <= atMs }.maxOfOrNull { it.endMs } ?: 0L
    val end = list.rallies.filter { it.startMs > atMs }.minOfOrNull { it.startMs } ?: list.videoDurationMs
    return if (end > start) EditTarget.OfGap(start, end) else null
}

/** The pauses of the list that are long enough to hold a rally, in match order: before the first rally, between two, and after the last. */
fun gaps(list: RallyList): List<EditTarget.OfGap> = buildList {
    var free = 0L
    (list.rallies.map { it.startMs to it.endMs } + (list.videoDurationMs to list.videoDurationMs)).forEach { (start, end) ->
        if (start - free >= SHORTEST_GAP_MS) add(EditTarget.OfGap(free, start))
        free = maxOf(free, end)
    }
}
