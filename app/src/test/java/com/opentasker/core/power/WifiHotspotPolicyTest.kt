package com.opentasker.core.power

import com.opentasker.core.power.WifiHotspotPolicy.AP_DISABLED
import com.opentasker.core.power.WifiHotspotPolicy.AP_DISABLING
import com.opentasker.core.power.WifiHotspotPolicy.AP_ENABLED
import com.opentasker.core.power.WifiHotspotPolicy.AP_ENABLING
import com.opentasker.core.power.WifiHotspotPolicy.AP_FAILED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiHotspotPolicyTest {
    private class FakeGateway(
        states: List<Int>,
        private val startAnswer: Int = WifiHotspotPolicy.OK,
    ) : TetheringGateway {
        private val remaining = states.toMutableList()
        var starts = 0
        var stops = 0

        override fun start(timeoutMillis: Long): Int {
            starts++
            return startAnswer
        }

        override fun stop() {
            stops++
        }

        override fun apState(): Int = if (remaining.size > 1) remaining.removeAt(0) else remaining.single()
    }

    private fun change(enabled: Boolean, gateway: TetheringGateway, sdkInt: Int = 36): Int {
        var clock = 0L
        return changeWifiHotspot(
            enabled = enabled,
            gateway = gateway,
            sdkInt = sdkInt,
            pollIntervalMillis = 100L,
            timeoutMillis = 1_000L,
            now = { clock },
            sleep = { clock += it },
        )
    }

    @Test
    fun turningOnWaitsUntilAndroidSaysItIsOn() {
        val gateway = FakeGateway(listOf(AP_DISABLED, AP_ENABLING, AP_ENABLING, AP_ENABLED))

        assertEquals(WifiHotspotPolicy.OK, change(true, gateway))
        assertEquals(1, gateway.starts)
        assertEquals(0, gateway.stops)
    }

    @Test
    fun turningOffWaitsUntilAndroidSaysItIsOff() {
        val gateway = FakeGateway(listOf(AP_ENABLED, AP_DISABLING, AP_DISABLED))

        assertEquals(WifiHotspotPolicy.OK, change(false, gateway))
        assertEquals(0, gateway.starts)
        assertEquals(1, gateway.stops)
    }

    @Test
    fun alreadyInTheWantedStateAsksAndroidForNothing() {
        val on = FakeGateway(listOf(AP_ENABLED))
        val off = FakeGateway(listOf(AP_DISABLED))

        assertEquals(WifiHotspotPolicy.OK, change(true, on))
        assertEquals(WifiHotspotPolicy.OK, change(false, off))
        assertEquals(0, on.starts + on.stops)
        assertEquals(0, off.starts + off.stops)
    }

    @Test
    fun androidsRefusalComesBackAsIsWithoutWaiting() {
        val gateway = FakeGateway(listOf(AP_DISABLED), startAnswer = 14)

        assertEquals(14, change(true, gateway))
    }

    @Test
    fun anAnsweredStartThatNeverComesUpIsNotASuccess() {
        val gateway = FakeGateway(listOf(AP_DISABLED, AP_ENABLING))

        assertEquals(WifiHotspotPolicy.DID_NOT_CHANGE, change(true, gateway))
    }

    @Test
    fun aHotspotThatStaysOnIsNotASuccess() {
        val gateway = FakeGateway(listOf(AP_ENABLED))

        assertEquals(WifiHotspotPolicy.DID_NOT_CHANGE, change(false, gateway))
    }

    @Test
    fun aFailedStateLeftOverFromEarlierDoesNotEndTheWait() {
        val gateway = FakeGateway(listOf(AP_FAILED, AP_FAILED, AP_ENABLING, AP_ENABLED))

        assertEquals(WifiHotspotPolicy.OK, change(true, gateway))
    }

    @Test
    fun androidBeforeElevenStopsBeforeTouchingTheFramework() {
        val gateway = FakeGateway(listOf(AP_DISABLED))

        assertEquals(WifiHotspotPolicy.ANDROID_TOO_OLD, change(true, gateway, sdkInt = 29))
        assertEquals(0, gateway.starts)

        change(true, gateway, sdkInt = 30)
        assertEquals(1, gateway.starts)
    }

    @Test
    fun aStateReadNeverLooksLikeAFailureCode() {
        val states = setOf(WifiHotspotPolicy.STATE_ON, WifiHotspotPolicy.STATE_OFF, WifiHotspotPolicy.STATE_UNKNOWN)
        val failures = setOf(WifiHotspotPolicy.ANDROID_TOO_OLD, WifiHotspotPolicy.DID_NOT_CHANGE, WifiHotspotPolicy.NOT_REACHABLE)

        assertTrue(states.intersect(failures).isEmpty())
    }

    @Test
    fun rawStatesReadAsOnOrOff() {
        assertEquals(WifiHotspotPolicy.STATE_ON, WifiHotspotPolicy.readState(AP_ENABLING))
        assertEquals(WifiHotspotPolicy.STATE_ON, WifiHotspotPolicy.readState(AP_ENABLED))
        assertEquals(WifiHotspotPolicy.STATE_OFF, WifiHotspotPolicy.readState(AP_DISABLING))
        assertEquals(WifiHotspotPolicy.STATE_OFF, WifiHotspotPolicy.readState(AP_DISABLED))
        assertEquals(WifiHotspotPolicy.STATE_OFF, WifiHotspotPolicy.readState(AP_FAILED))
        assertEquals(WifiHotspotPolicy.STATE_UNKNOWN, WifiHotspotPolicy.readState(99))
    }

    @Test
    fun theReportedPackageMatchesTheProcessIdentity() {
        assertEquals("com.android.shell", WifiHotspotPolicy.opPackageFor(2000))
        assertEquals("root", WifiHotspotPolicy.opPackageFor(0))
        assertNull(WifiHotspotPolicy.opPackageFor(10123))
    }

    @Test
    fun failuresSayWhatHappenedInPlainWords() {
        assertTrue(WifiHotspotPolicy.failureMessage(WifiHotspotPolicy.ANDROID_TOO_OLD, true).contains("Android 11"))
        assertTrue(WifiHotspotPolicy.failureMessage(WifiHotspotPolicy.DID_NOT_CHANGE, true).contains("didn't come on"))
        assertTrue(WifiHotspotPolicy.failureMessage(WifiHotspotPolicy.DID_NOT_CHANGE, false).contains("still on"))
        assertTrue(WifiHotspotPolicy.failureMessage(11, true).contains("carrier"))
        assertTrue(WifiHotspotPolicy.failureMessage(13, true).contains("carrier"))
        assertTrue(WifiHotspotPolicy.failureMessage(14, true).contains("refused"))
        assertTrue(WifiHotspotPolicy.failureMessage(99, true).contains("error 99"))
        assertFalse(WifiHotspotPolicy.failureMessage(3, true).contains("error 3"))
    }
}
