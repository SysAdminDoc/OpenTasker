package com.opentasker.core.flow

import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Flow's context lines read like the Inspector's, and still mask what Flow masks everywhere else. */
class AutomationFlowReadableContextTest {

    private fun details(vararg contexts: ContextSpec): List<String> {
        val profile = Profile(id = 1, name = "Readable", contexts = contexts.toList(), enterTaskId = 10)
        val task = Task(id = 10, name = "Run", actions = emptyList())
        return AutomationFlowGraphBuilder.build(profile, listOf(task)).contextNodes.map { it.detail.orEmpty() }
    }

    @Test
    fun stateSunAndDayContextsReadAsTheirChecks() {
        val lines = details(
            ContextSpec(ContextType.STATE, mapOf("key" to "battery_level", "operator" to "<=", "value" to "20")),
            ContextSpec(ContextType.EVENT, mapOf("event" to "sunset", "elevation" to "civil", "latitude" to "51.5", "longitude" to "-0.12")),
            ContextSpec(ContextType.DAY, mapOf("days" to "MON-FRI")),
            ContextSpec(ContextType.STATE, mapOf("key" to "battery", "operator" to ">=", "value" to "80"), invert = true),
        )
        assertEquals("battery level <= 20", lines[0])
        assertEquals("Civil dusk (sun 6 degrees below the horizon), latitude=51.5, longitude=-0.12", lines[1])
        assertEquals("Weekdays", lines[2])
        assertEquals("Inverted · battery level >= 80", lines[3])
    }

    @Test
    fun aSecretLookingExtraIsMaskedInTheReadableLine() {
        val lines = details(
            ContextSpec(ContextType.STATE, mapOf("key" to "wifi", "value" to "Home", "password" to "hunter2")),
            ContextSpec(ContextType.STATE, mapOf("key" to "battery_level", "operator" to "<", "value" to "15", "token" to "abc123")),
            ContextSpec(ContextType.EVENT, mapOf("event" to "sunrise", "api_secret" to "s3cr3t")),
        )
        assertEquals("wifi = Home, password=<redacted>", lines[0])
        assertEquals("battery level < 15, token=<redacted>", lines[1])
        assertEquals("Sunrise, api_secret=<redacted>", lines[2])
        listOf("hunter2", "abc123", "s3cr3t").forEach { secret ->
            assertFalse(secret, lines.any { secret in it })
        }
    }

    @Test
    fun aLongComparedValueIsShortenedLikeOtherFlowValues() {
        val ssid = "A very long network name that keeps going and going"
        val line = details(ContextSpec(ContextType.STATE, mapOf("key" to "wifi", "value" to ssid))).single()
        assertFalse(line, ssid in line)
        assertTrue(line, line.startsWith("wifi = A very long network name"))
        assertTrue(line, line.endsWith("..."))
    }
}
