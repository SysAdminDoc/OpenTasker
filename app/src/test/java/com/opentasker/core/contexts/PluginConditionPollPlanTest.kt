package com.opentasker.core.contexts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Every Plugin context is queried every 30 seconds, and a plugin that sends REQUEST_QUERY is
 * queried on its own right away, throttled, without delaying anyone else's regular query.
 */
class PluginConditionPollPlanTest {

    private val plan = PluginConditionPollPlan(fullIntervalMs = 30_000, requeryGapMs = 1_000)
    private val everyone = PluginConditionPollPlan.Pass(null)

    @Test
    fun theFirstPassQueriesEveryoneAndTheNextIsAFullIntervalLater() {
        assertEquals(everyone, plan.take(now = 0))
        assertEquals(30_000, plan.waitMs(now = 0))
        assertNull(plan.take(now = 29_999))
        assertEquals(everyone, plan.take(now = 30_000))
    }

    @Test
    fun aPluginThatAsksIsQueriedOnItsOwnStraightAway() {
        plan.take(now = 0)
        plan.request(listOf("org.primftpd"))

        assertEquals(0, plan.waitMs(now = 5_000))
        assertEquals(PluginConditionPollPlan.Pass(setOf("org.primftpd")), plan.take(now = 5_000))
        assertEquals(25_000, plan.waitMs(now = 5_000))
    }

    @Test
    fun aPluginThatKeepsAskingWaitsOutTheGap() {
        plan.take(now = 0)
        plan.request(listOf("org.primftpd"))

        assertEquals(900, plan.waitMs(now = 100))
        assertNull(plan.take(now = 100))
        assertEquals(PluginConditionPollPlan.Pass(setOf("org.primftpd")), plan.take(now = 1_000))
    }

    @Test
    fun askingNeverMovesTheNextFullPass() {
        plan.take(now = 0)
        for (at in 5_000L..25_000L step 5_000) {
            plan.request(listOf("org.primftpd"))
            assertEquals(PluginConditionPollPlan.Pass(setOf("org.primftpd")), plan.take(now = at))
        }

        assertEquals(5_000, plan.waitMs(now = 25_000))
        assertEquals(everyone, plan.take(now = 30_000))
    }

    @Test
    fun aFullPassAnswersWhatWasAskedBeforeIt() {
        plan.take(now = 0)
        plan.request(listOf("org.primftpd"))

        assertEquals(everyone, plan.take(now = 30_000))
        assertEquals(30_000, plan.waitMs(now = 30_000))
        assertNull(plan.take(now = 31_000))
    }

    @Test
    fun twoPluginsAskingTogetherShareOnePass() {
        plan.take(now = 0)
        plan.request(listOf("a.plugin"))
        plan.request(listOf("b.plugin"))

        val pass = plan.take(now = 2_000)

        assertEquals(PluginConditionPollPlan.Pass(setOf("a.plugin", "b.plugin")), pass)
        assertEquals(false, pass?.includes("c.plugin"))
    }
}
