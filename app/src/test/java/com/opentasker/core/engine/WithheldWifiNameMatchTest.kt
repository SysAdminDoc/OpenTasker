package com.opentasker.core.engine

import com.opentasker.core.contexts.ContextEvent
import com.opentasker.core.contexts.DeviceStateEvents
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When Android withholds the connected network's name (no precise or background location, or
 * Location off), the Wi-Fi source publishes "Unknown" with a setup marker beside it. No name test
 * can be answered then, so a `!Office` pattern or an inverted name context must not turn on while
 * the phone may well be on Office.
 */
class WithheldWifiNameMatchTest {

    @Test
    fun aNegatedPatternDoesNotHoldWhileTheNameIsHidden() {
        val notOffice = contextMatchUpdate(pattern("!Office"), withheld)

        assertFalse(notOffice.matched)
        assertTrue(notOffice.unknown)
    }

    @Test
    fun noPatternMatchesTheStandInName() {
        for (value in listOf("*", "unknown", "U*", "Unknown")) {
            assertFalse(value, contextMatchUpdate(pattern(value), withheld).matched)
        }
        assertFalse(contextMatchUpdate(name("Unknown"), withheld).matched)
    }

    @Test
    fun anInvertedNameContextStaysOffWhileTheNameIsHidden() {
        assertFalse(contextMatchUpdate(pattern("Office").copy(invert = true), withheld).matched)
        assertFalse(contextMatchUpdate(name("Office").copy(invert = true), withheld).matched)
    }

    @Test
    fun aReadableNameStillMatchesAsBefore() {
        assertTrue(contextMatchUpdate(pattern("!Office"), connectedTo("Cafe")).matched)
        assertFalse(contextMatchUpdate(pattern("!Office"), connectedTo("Office")).matched)
        assertTrue(contextMatchUpdate(name("Office").copy(invert = true), connectedTo("Cafe")).matched)
        assertTrue(contextMatchUpdate(pattern("Office").copy(invert = true), disconnected).matched)
    }

    @Test
    fun beingConnectedNeedsNoName() {
        assertTrue(contextMatchUpdate(name("connected"), withheld).matched)
        assertFalse(contextMatchUpdate(name("connected").copy(invert = true), withheld).matched)
    }

    private fun pattern(value: String) = ContextSpec(ContextType.STATE, mapOf("key" to "wifi", "operator" to "~", "value" to value))

    private fun name(value: String) = ContextSpec(ContextType.STATE, mapOf("key" to "wifi", "value" to value))

    private val withheld = ContextEvent(
        "state",
        true,
        DeviceStateEvents.wifiPatch("", connected = true, ssidUnavailableReason = "Android hides the Wi-Fi name while Location is turned off."),
    )

    private fun connectedTo(ssid: String) = ContextEvent("state", true, DeviceStateEvents.wifiPatch(ssid, connected = true))

    private val disconnected = ContextEvent("state", true, DeviceStateEvents.wifiPatch("", connected = false))
}
