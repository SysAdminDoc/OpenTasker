package com.opentasker.ui.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TapGateTest {
    @Test
    fun theFirstTapRuns() {
        assertTrue(TapGate(1_000).tryPass(now = 50_000))
    }

    @Test
    fun aSecondTapInsideTheWindowIsDropped() {
        val gate = TapGate(1_000)
        assertTrue(gate.tryPass(now = 50_000))
        assertFalse(gate.tryPass(now = 50_150))
        assertFalse(gate.tryPass(now = 50_999))
    }

    @Test
    fun aTapOnceTheWindowEndsRunsAgain() {
        val gate = TapGate(1_000)
        assertTrue(gate.tryPass(now = 50_000))
        assertTrue(gate.tryPass(now = 51_000))
    }

    @Test
    fun droppedTapsDoNotStretchTheWindow() {
        val gate = TapGate(1_000)
        val ran = listOf(50_000L, 50_400L, 50_800L, 51_200L, 51_600L, 52_000L, 52_400L)
            .filter { gate.tryPass(it) }
        assertEquals(listOf(50_000L, 51_200L, 52_400L), ran)
    }

    @Test
    fun aTapAtTimeZeroStillCounts() {
        // uptimeMillis starts near zero after a boot, so zero can't double as "never tapped".
        val gate = TapGate(1_000)
        assertTrue(gate.tryPass(now = 0))
        assertFalse(gate.tryPass(now = 10))
    }
}
