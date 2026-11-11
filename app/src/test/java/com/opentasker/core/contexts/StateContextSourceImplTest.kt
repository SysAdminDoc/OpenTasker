package com.opentasker.core.contexts

import android.os.BatteryManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType

class StateContextSourceImplTest {
    @Test
    fun snapshotsFromTwoThreadsLeaveInTheOrderTheyWereMerged() {
        val delivered = mutableListOf<Map<String, String>>()
        val publisher = StatePatchPublisher { delivered += it }

        (0 until 2)
            .map { source -> kotlin.concurrent.thread { repeat(1_000) { publisher.publish(mapOf("s$source-$it" to "x")) } } }
            .forEach { it.join() }

        assertEquals(2_000, delivered.last().size)
        assertEquals(delivered.map { it.size }.sorted(), delivered.map { it.size })
    }

    @Test
    fun aDelayedLockIsNoticedWhenTheScreenComesBack() {
        // Lock after 30 seconds: still open as the screen goes off, closed when it comes back.
        assertEquals(mapOf("screen" to "off", "unlocked" to "true"), screenStatePatch(on = false, keyguardLocked = false))
        assertEquals(mapOf("screen" to "on", "unlocked" to "false"), screenStatePatch(on = true, keyguardLocked = true))
    }

    @Test
    fun aChargerHoldingTheBatteryBelowFullStillCountsAsCharging() {
        // A battery-protect limit reports NOT_CHARGING while plugged in; Tasker's Power counts it.
        assertTrue(batteryPluggedIn(BatteryManager.BATTERY_PLUGGED_AC, BatteryManager.BATTERY_STATUS_NOT_CHARGING))
        assertTrue(batteryPluggedIn(BatteryManager.BATTERY_PLUGGED_WIRELESS, BatteryManager.BATTERY_STATUS_FULL))
        assertFalse(batteryPluggedIn(0, BatteryManager.BATTERY_STATUS_DISCHARGING))
        assertFalse("full but unplugged", batteryPluggedIn(0, BatteryManager.BATTERY_STATUS_FULL))
        assertTrue("no plug extra falls back to the status", batteryPluggedIn(-1, BatteryManager.BATTERY_STATUS_CHARGING))
        assertFalse(batteryPluggedIn(-1, BatteryManager.BATTERY_STATUS_NOT_CHARGING))
    }

    @Test
    fun aKeyguardNobodyCouldReadCountsAsLocked() {
        assertEquals("false", screenStatePatch(on = true, keyguardLocked = null)["unlocked"])
    }

    @Test
    fun mergeStatePatchKeepsExistingFactsAcrossPartialBroadcasts() {
        val initial = mapOf("screen" to "on", "headphones" to "true")

        val merged = mergeStatePatch(initial, mapOf("battery_level" to "42", "charging" to "false"))

        assertEquals(
            mapOf(
                "screen" to "on",
                "headphones" to "true",
                "battery_level" to "42",
                "charging" to "false",
            ),
            merged,
        )
    }

    @Test
    fun mergeStatePatchReplacesOnlyKeysPresentInPatch() {
        val merged = mergeStatePatch(
            mapOf("screen" to "on", "battery_level" to "80", "charging" to "true"),
            mapOf("screen" to "off"),
        )

        assertEquals(
            mapOf("screen" to "off", "battery_level" to "80", "charging" to "true"),
            merged,
        )
    }

    @Test
    fun mergeStatePatchReturnsSameMapForEmptyPatch() {
        val initial = mapOf("screen" to "on")

        assertSame(initial, mergeStatePatch(initial, emptyMap()))
    }

    @Test
    fun wifiStatePatchSupportsSsidAndConnectedPredicates() {
        val state = DeviceStateEvents.wifiPatch("OfficeWiFi", connected = true)

        assertEquals("OfficeWiFi", state["wifi"])
        assertEquals("true", state["wifi_connected"])
        assertTrue(stateMatches("wifi=OfficeWiFi", state))
        assertTrue(stateMatches("wifi=connected", state))
        assertTrue(stateMatches("wifi_ssid=OfficeWiFi", state))
        assertFalse(stateMatches("wifi=HomeWiFi", state))
    }

    @Test
    fun disconnectedWifiStateSupportsDisconnectedPredicate() {
        val state = DeviceStateEvents.wifiPatch("OfficeWiFi", connected = false)

        assertEquals("disconnected", state["wifi"])
        assertEquals("false", state["wifi_connected"])
        assertTrue(stateMatches("wifi=disconnected", state))
        assertFalse(stateMatches("wifi=OfficeWiFi", state))
    }

    @Test
    fun theSsidReasonIsWrittenWhileConnectedAndClearedByEveryOtherPatch() {
        val withheld = DeviceStateEvents.wifiPatch("Unknown", connected = true, ssidUnavailableReason = "Allow precise location.")
        assertEquals("Allow precise location.", withheld[DeviceStateEvents.WIFI_SSID_SETUP_MARKER])

        // A disconnect or a readable name must overwrite the marker, or the reason from an earlier
        // connection would survive the merge in the state map.
        assertEquals("", DeviceStateEvents.wifiPatch("Unknown", connected = false, ssidUnavailableReason = "stale")[DeviceStateEvents.WIFI_SSID_SETUP_MARKER])
        assertEquals("", DeviceStateEvents.wifiPatch("Home", connected = true)[DeviceStateEvents.WIFI_SSID_SETUP_MARKER])
    }

    @Test
    fun onlySpecsThatCompareTheWifiNameReadTheSsid() {
        fun state(vararg config: Pair<String, String>) = ContextSpec(ContextType.STATE, mapOf(*config))

        assertTrue(stateSpecReadsWifiSsid(state("key" to "wifi", "value" to "Home")))
        assertTrue(stateSpecReadsWifiSsid(state("key" to "wifi_ssid", "value" to "Home")))
        assertTrue(stateSpecReadsWifiSsid(state("key" to "ssid", "operator" to "=", "value" to "{ssid}")))
        assertTrue(stateSpecReadsWifiSsid(state("predicate" to "wifi=Home")))

        assertFalse(stateSpecReadsWifiSsid(state("key" to "wifi", "value" to "connected")))
        assertFalse(stateSpecReadsWifiSsid(state("key" to "wifi", "value" to "Disconnected")))
        assertFalse(stateSpecReadsWifiSsid(state("predicate" to "wifi=off")))
        assertFalse(stateSpecReadsWifiSsid(state("key" to "wifi_connected", "value" to "true")))
        assertFalse(stateSpecReadsWifiSsid(state("key" to "wifi", "value" to "")))
        // Names are only compared with "=", so another operator never reads the name.
        assertFalse(stateSpecReadsWifiSsid(state("key" to "wifi", "operator" to ">=", "value" to "Home")))
        assertFalse(stateSpecReadsWifiSsid(state("predicate" to "wifi>=Home")))
        assertTrue(stateSpecReadsWifiSsid(state("key" to "wifi", "operator" to " ", "value" to "Home")))
        assertFalse(stateSpecReadsWifiSsid(ContextSpec(ContextType.EVENT, mapOf("key" to "wifi", "value" to "Home"))))

        assertEquals(DeviceStateEvents.WIFI_SSID_SETUP_MARKER, stateSetupMarkerKey(state("key" to "wifi", "value" to "Home")))
        assertEquals("_setup_wifi", stateSetupMarkerKey(state("key" to "wifi", "value" to "connected")))
        assertEquals("_setup_activity", stateSetupMarkerKey(state("key" to "activity", "value" to "walking")))
    }

    @Test
    fun unlockedPredicateMatchesTrueFalse() {
        val state = mapOf("unlocked" to "true")
        assertTrue(stateMatches("unlocked=true", state))
        assertTrue(stateMatches("unlocked=unlocked", state))
        assertFalse(stateMatches("unlocked=locked", state))
        assertFalse(stateMatches("unlocked=false", state))
    }

    @Test
    fun powerSavePredicateMatchesWithAliases() {
        val on = mapOf("power_save" to "true")
        assertTrue(stateMatches("power_save=true", on))
        assertTrue(stateMatches("power_save=on", on))
        assertTrue(stateMatches("power_save=enabled", on))
        assertTrue(stateMatches("battery_saver=true", on))
        assertFalse(stateMatches("power_save=off", on))

        val off = mapOf("power_save" to "false")
        assertTrue(stateMatches("power_save=false", off))
        assertTrue(stateMatches("power_save=disabled", off))
        assertFalse(stateMatches("power_save=true", off))
    }

    @Test
    fun airplanePredicateMatchesWithAliases() {
        val on = mapOf("airplane" to "true")
        assertTrue(stateMatches("airplane=true", on))
        assertTrue(stateMatches("airplane=on", on))
        assertTrue(stateMatches("airplane_mode=enabled", on))
        assertTrue(stateMatches("flight_mode=true", on))
        assertFalse(stateMatches("airplane=off", on))

        val off = mapOf("airplane" to "false")
        assertTrue(stateMatches("airplane=false", off))
        assertTrue(stateMatches("airplane=disabled", off))
        assertFalse(stateMatches("airplane=true", off))
    }

    @Test
    fun normalizeStateKeyMapsNewAliases() {
        assertEquals("power_save", normalizeStateKey("battery_saver"))
        assertEquals("power_save", normalizeStateKey("powersave"))
        assertEquals("power_save", normalizeStateKey("power_saver"))
        assertEquals("airplane", normalizeStateKey("airplane_mode"))
        assertEquals("airplane", normalizeStateKey("flight_mode"))
        assertEquals("unlocked", normalizeStateKey("device_unlocked"))
    }

    @Test
    fun connectivityPatchSupportsInternetAndVpnPredicates() {
        val state = DeviceStateEvents.connectivityPatch(
            internet = true,
            networkType = "wifi",
            vpn = false,
        )

        assertTrue(stateMatches("internet=true", state))
        assertTrue(stateMatches("network_type=wifi", state))
        assertTrue(stateMatches("vpn=false", state))
        assertFalse(stateMatches("internet=false", state))
    }

    @Test
    fun sensorPredicatesMatchAliasesAndBroadOrientationCategories() {
        val state = mapOf(
            "orientation" to "portrait_upside_down",
            "proximity" to "near",
            "activity" to "walking",
            "speed" to "12.5",
            "roaming" to "true",
            "tethering" to "false",
            "call_state" to "ringing",
        )

        assertTrue(stateMatches("device_orientation=portrait", state))
        assertTrue(stateMatches("proximity_state=covered", state))
        assertTrue(stateMatches("activity_detection=walk", state))
        assertTrue(stateMatches("velocity>=10", state))
        assertTrue(stateMatches("roaming_state=on", state))
        assertTrue(stateMatches("hotspot=off", state))
        assertTrue(stateMatches("phone_call=ring", state))
        assertFalse(stateMatches("orientation=landscape", state))
        assertFalse(stateMatches("speed>20", state))
    }

    @Test
    fun stateContextKeyExtractsPredicateAndCanonicalizesAliases() {
        assertEquals(
            "speed",
            stateContextKey(ContextSpec(ContextType.STATE, mapOf("predicate" to "velocity >= 10"))),
        )
        assertEquals(
            "call_state",
            stateContextKey(ContextSpec(ContextType.STATE, mapOf("key" to "phone_call", "value" to "ringing"))),
        )
    }
}
