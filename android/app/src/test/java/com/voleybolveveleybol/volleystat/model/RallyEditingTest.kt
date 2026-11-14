package com.voleybolveveleybol.volleystat.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where the range editor may move. The times are invented and describe no match. */
class RallyEditingTest {
    private fun rally(sequence: Int, startMs: Long, endMs: Long) = Rally("r$sequence", sequence, startMs, endMs, true, Rally.PROPOSED, null, null)
    private fun list(vararg rallies: Rally, durationMs: Long = 120_000) = RallyList(rallies.toList(), 1, false, durationMs)

    private val four = list(rally(1, 2_000, 9_000), rally(2, 15_000, 24_000), rally(3, 30_000, 41_000), rally(4, 48_000, 56_000))

    @Test fun aRallyMayGrowIntoThePausesAroundItAndNeverIntoItsNeighbours() {
        val target = rallyTarget(four, "r2")!!
        assertEquals(listOf(9_000L, 30_000L), listOf(target.windowStartMs, target.windowEndMs))
        assertTrue(target.hasPrevious)
    }

    @Test fun theWindowOfARallyEndsHalfAMinuteFromItWhenNoNeighbourIsCloser() {
        val lonely = list(rally(1, 50_000, 60_000), durationMs = 600_000)
        val target = rallyTarget(lonely, "r1")!!
        assertEquals(listOf(50_000 - EDITOR_REACH_MS, 60_000 + EDITOR_REACH_MS), listOf(target.windowStartMs, target.windowEndMs))
        assertFalse("the first rally has none before it to merge with", target.hasPrevious)
    }

    @Test fun theWindowOfARallyStaysInsideTheVideo() {
        val target = rallyTarget(four, "r1")!!
        assertEquals(0L, target.windowStartMs)
        val last = rallyTarget(list(rally(1, 2_000, 9_000), durationMs = 12_000), "r1")!!
        assertEquals(12_000L, last.windowEndMs)
    }

    @Test fun ralliesThatTouchLeaveNoRoomBetweenThem() {
        val touching = list(rally(1, 2_000, 9_000), rally(2, 9_000, 14_000))
        assertEquals(9_000L, rallyTarget(touching, "r1")!!.windowEndMs)
        assertEquals(9_000L, rallyTarget(touching, "r2")!!.windowStartMs)
    }

    @Test fun aRallyThatLeftTheListHasNoEditor() = assertNull(rallyTarget(four, "gone"))

    @Test fun aPauseReachesFromOneRallyToTheNext() {
        assertEquals(EditTarget.OfGap(9_000, 15_000), gapTarget(four, 9_000))
        assertEquals("any moment of the pause names the same pause", EditTarget.OfGap(9_000, 15_000), gapTarget(four, 12_345))
        assertEquals(EditTarget.OfGap(0, 2_000), gapTarget(four, 0))
        assertEquals(EditTarget.OfGap(56_000, 120_000), gapTarget(four, 56_000))
    }

    @Test fun aMomentInsideARallyIsNoPause() {
        assertNull(gapTarget(four, 15_000))
        assertNull(gapTarget(four, 20_000))
        // The list changed under the screen: a rally was added where the pause began.
        val filled = list(rally(1, 2_000, 9_000), rally(2, 9_000, 12_000), rally(3, 15_000, 24_000))
        assertNull(gapTarget(filled, 9_000))
        assertEquals(EditTarget.OfGap(12_000, 15_000), gapTarget(filled, 12_000))
    }

    @Test fun aVideoThatEndsWithARallyHasNoPauseAfterIt() {
        assertNull(gapTarget(list(rally(1, 2_000, 9_000), durationMs = 9_000), 9_000))
    }

    @Test fun theListOffersEveryPauseThatCanHoldARally() {
        assertEquals(
            listOf(0L to 2_000L, 9_000L to 15_000L, 24_000L to 30_000L, 41_000L to 48_000L, 56_000L to 120_000L),
            gaps(four).map { it.windowStartMs to it.windowEndMs },
        )
    }

    @Test fun aPauseShorterThanASecondIsNotOffered() {
        val tight = list(rally(1, 500, 9_000), rally(2, 9_999, 14_000), rally(3, 15_000, 24_000), durationMs = 24_400)
        assertEquals(listOf(14_000L to 15_000L), gaps(tight).map { it.windowStartMs to it.windowEndMs })
    }

    @Test fun aListWithoutRalliesIsOnePause() {
        assertEquals(listOf(EditTarget.OfGap(0, 120_000)), gaps(list()))
        assertEquals(EditTarget.OfGap(0, 120_000), gapTarget(list(), 0))
    }
}
