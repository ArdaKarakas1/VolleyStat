package com.voleybolveveleybol.volleystat.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rule the user picks for a match. The server counts with it; these tests only pin what the phone holds and offers. */
class MatchRulesTest {
    private val video = LocalVideo("/data/rec.mp4", VideoSource.CAMERA, "rec.mp4", 10, "video/mp4", null, null, null, null)

    @Test fun theFivbRuleIsTheDefault() {
        assertEquals(MatchRules(setsToWin = 3, setTarget = 25, decidingSetTarget = 15), MatchRules.FIVB)
        assertEquals(MatchRules.FIVB, MatchRules())
        assertEquals("a new match starts with the FIVB rule, so a standard match needs no touch", MatchRules.FIVB, Draft().rules)
        assertEquals(MatchRules.FIVB, MatchRecord("m", "A1", "B1", 0, video).rules)
    }

    @Test fun theDecidingSetIsTheLastSetOfTheFormat() {
        assertEquals(3, MatchRules(setsToWin = 2).decidingSetNumber)
        assertEquals(5, MatchRules(setsToWin = 3).decidingSetNumber)
    }

    @Test fun theOfferedValuesHoldTheFivbRule() {
        assertEquals(listOf(2, 3), MatchRules.SETS_TO_WIN)
        assertEquals(listOf(25, 21, 15), MatchRules.TARGETS)
        assertTrue(MatchRules.FIVB.isOffered)
        assertTrue(MatchRules(2, 21, 15).isOffered)
        listOf(MatchRules(1, 25, 15), MatchRules(4, 25, 15), MatchRules(3, 20, 15), MatchRules(3, 25, 14), MatchRules(-1, -1, -1))
            .forEach { assertFalse("$it is not offered", it.isOffered) }
    }

    @Test fun aDraftWithOnlyAChosenRuleIsNotStarted() {
        // A rule alone is no reason to ask whether the user wants to keep the draft, the same as the kind of match.
        assertFalse(Draft(rules = MatchRules(2, 21, 15)).started)
    }
}
