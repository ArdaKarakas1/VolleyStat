package com.voleybolveveleybol.volleystat.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VolleyDomainTest {
    @Test fun deuceRules() {
        assertFalse(VolleyballRules.isSetFinished(25,24))
        assertTrue(VolleyballRules.isSetFinished(26,24))
        assertFalse(VolleyballRules.isSetFinished(15,14,15))
        assertTrue(VolleyballRules.isSetFinished(16,14,15))
    }

    @Test fun onlyReceivingSideRotates() {
        assertTrue(VolleyballRules.shouldRotate(Side.AWAY, Side.HOME))
        assertFalse(VolleyballRules.shouldRotate(Side.HOME, Side.HOME))
        assertEquals(Side.AWAY, VolleyballRules.nextServer(Side.AWAY, Side.HOME))
    }

    @Test fun multipleActionsCountAsOnePoint() {
        val rally = VerifiedRally("r1", Side.HOME, Side.AWAY, listOf(
            VerifiedAction("setter", Side.HOME, ActionType.SET, ActionOutcome.ASSIST),
            VerifiedAction("attacker", Side.HOME, ActionType.ATTACK, ActionOutcome.KILL)))
        assertEquals(Pair(1,0), VolleyballRules.score(listOf(rally)))
    }

    @Test fun missingAttackCoverageNeverBecomesZero() {
        val rally = VerifiedRally("r1", Side.HOME, Side.AWAY, listOf(
            VerifiedAction("attacker", Side.HOME, ActionType.ATTACK, ActionOutcome.KILL)))
        val stats = Statistics.playerAttackStats(listOf(rally), "attacker")
        assertEquals(1, stats.recordedKills)
        assertNull(stats.attempts)
        assertNull(stats.efficiency)
    }

    @Test fun completeCoverageComputesEfficiency() {
        val rally = VerifiedRally("r1", Side.HOME, Side.AWAY, listOf(
            VerifiedAction("p", Side.HOME, ActionType.ATTACK, ActionOutcome.KILL),
            VerifiedAction("p", Side.HOME, ActionType.ATTACK, ActionOutcome.ERROR),
            VerifiedAction("p", Side.HOME, ActionType.ATTACK, ActionOutcome.IN_PLAY)), attacksFullyAnnotated=true)
        val stats = Statistics.playerAttackStats(listOf(rally), "p")
        assertEquals(3, stats.attempts)
        assertEquals(0.0, stats.efficiency)
    }
}
