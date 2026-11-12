package com.opentasker.core.contexts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A pass over the plugin conditions can take as long as its plugins' timeouts. The next full pass
 * still waits a whole interval after one ends, the way the poll always did, so a plugin that never
 * answers isn't asked again straight away, and a request that came in during a pass is answered
 * as soon as it's done.
 */
class PluginConditionSlowPassTest {

    private val plan = PluginConditionPollPlan(fullIntervalMs = 30_000, requeryGapMs = 1_000)
    private val everyone = PluginConditionPollPlan.Pass(null)

    @Test
    fun aSlowFullPassWaitsAWholeIntervalAfterItEnds() {
        plan.finished(now = 40_000, pass = plan.take(now = 0)!!)

        assertEquals(30_000, plan.waitMs(now = 40_000))
        assertNull(plan.take(now = 40_000))
        assertNull(plan.take(now = 69_999))
        assertEquals(everyone, plan.take(now = 70_000))
    }

    @Test
    fun aRequestDuringASlowPassIsAnsweredWhenItEnds() {
        val pass = plan.take(now = 0)!!
        plan.request(listOf("org.primftpd"))
        plan.finished(now = 35_000, pass = pass)

        assertEquals(0, plan.waitMs(now = 35_000))
        assertEquals(PluginConditionPollPlan.Pass(setOf("org.primftpd")), plan.take(now = 35_000))
        assertEquals(30_000, plan.waitMs(now = 35_000))
    }

    @Test
    fun aRequestedPassEndingLateLeavesTheFullOneWhereItWas() {
        plan.finished(now = 0, pass = plan.take(now = 0)!!)
        plan.request(listOf("org.primftpd"))

        plan.finished(now = 9_000, pass = plan.take(now = 5_000)!!)

        assertEquals(21_000, plan.waitMs(now = 9_000))
        assertEquals(everyone, plan.take(now = 30_000))
    }
}
