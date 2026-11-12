package com.opentasker.core.engine

import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A Wi-Fi name or a notification title or text that's a Tasker pattern simulates with a made-up
 * value the pattern matches, and says so when none can be made up.
 */
class SyntheticTaskerPatternTest {

    @Test
    fun aWifiNamePatternSimulatesWithANameItMatches() {
        listOf("home/work", "Home*", "+5G", "!Office", "a=b", "Off").forEach { pattern ->
            val context = simulate(ContextSpec(ContextType.STATE, mapOf("key" to "wifi", "operator" to "~", "value" to pattern)))

            assertNull(pattern, context.template.issue)
            assertEquals(pattern, "true", context.template.event.metadata["wifi_connected"])
            assertTrue(pattern, context.effectiveMatched)
        }
    }

    @Test
    fun aNotificationPatternSimulatesWithATitleItMatches() {
        val spec = ContextSpec(
            ContextType.EVENT,
            mapOf("event" to "notification", "title" to "mail/inbox", "body" to "!*spam*", "patterns" to "tasker"),
        )

        val context = simulate(spec)

        assertNull(context.template.issue)
        assertEquals("mail", context.template.event.metadata["title"])
        assertTrue(context.effectiveMatched)
    }

    @Test
    fun aPatternNoMadeUpValueMatchesSaysSo() {
        val regexName = simulate(ContextSpec(ContextType.STATE, mapOf("key" to "wifi", "operator" to "~", "value" to "~Rguest\\d")))
        val nothing = simulate(ContextSpec(ContextType.EVENT, mapOf("event" to "notification", "title" to "!*", "patterns" to "tasker")))

        assertNotNull(regexName.template.issue)
        assertNotNull(nothing.template.issue)
    }

    @Test
    fun aTildeOnAnotherStateIsStillUnsupported() {
        val context = simulate(ContextSpec(ContextType.STATE, mapOf("key" to "battery_level", "operator" to "~", "value" to "5*")))

        assertEquals("State context uses an unsupported comparison operator.", context.template.issue)
    }

    private fun simulate(spec: ContextSpec) =
        SyntheticTriggerSimulator.simulate(Profile(id = 1L, name = "P", enterTaskId = 2L, contexts = listOf(spec)), nowMs = 1_000_000L)
            .contexts.single()
}
