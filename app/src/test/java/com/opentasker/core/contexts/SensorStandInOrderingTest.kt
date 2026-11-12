package com.opentasker.core.contexts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The order a source's readings arrive in can't leave a stand-in marked for good: Android can send
 * a live location fix before the last-known one is read, and a Bluetooth scan can fail after it
 * started.
 */
class SensorStandInOrderingTest {
    @Test
    fun aLastKnownFixReadAfterALiveOneIsSkipped() {
        val readings = SpeedReadings(freshForMs = 15_000, settleAfterMs = 60_000)
        // Registering delivered the last-known fix live, with no speed and nothing before it.
        readings.onFix(sample(atMs = 995_000), "network", accuracyMps = null, nowMs = 1_000_000)

        assertNull(readings.onLastKnownFix(sample(atMs = 995_000), "network", accuracyMps = null, nowMs = 1_000_000))
        // The phone sits still, so nothing else comes in and the stand-in settles.
        assertEquals("0", readings.settle(nowMs = 1_060_000)?.get("speed"))
    }

    @Test
    fun aLastKnownStandInLeavesALiveStandInsQuietSpellRunning() {
        val readings = SpeedReadings(freshForMs = 15_000, settleAfterMs = 60_000)
        readings.onFix(sample(atMs = 1_000_000), "network", accuracyMps = null, nowMs = 1_000_000)
        // The providers changed and the source registered again.
        readings.forgetPrevious()

        val (_, measured) = present(
            readings.onLastKnownFix(sample(atMs = 0), "gps", accuracyMps = null, nowMs = 1_010_000),
        )

        assertFalse(measured)
        assertEquals("0", readings.settle(nowMs = 1_060_000)?.get("speed"))
    }

    @Test
    fun aMeasuredLastKnownFixEndsALiveStandIn() {
        val readings = SpeedReadings(freshForMs = 15_000, settleAfterMs = 60_000)
        readings.onFix(sample(atMs = 1_000_000), "network", accuracyMps = null, nowMs = 1_000_000)
        readings.forgetPrevious()

        val (values, measured) = present(
            readings.onLastKnownFix(sample(atMs = 1_005_000, speedMps = 12.0), "gps", accuracyMps = 0.5, nowMs = 1_010_000),
        )

        assertTrue(measured)
        assertEquals("12", values["speed"])
        assertNull(readings.settle(nowMs = 1_200_000))
    }

    @Test
    fun theLastKnownFixCountsAgainAfterTheSourceStops() {
        val readings = SpeedReadings(freshForMs = 15_000, settleAfterMs = 60_000)
        readings.onFix(sample(atMs = 1_000_000), "network", accuracyMps = null, nowMs = 1_000_000)
        readings.reset()

        assertNotNull(readings.onLastKnownFix(sample(atMs = 1_995_000, speedMps = 3.0), "gps", accuracyMps = null, nowMs = 2_000_000))
    }

    @Test
    fun aFailedBluetoothScanStartsAbsenceOverFromTheNextScan() {
        val tracker = NearbyBluetoothTracker(forgetAfterMs = 1_000)
        tracker.watching(nowMs = 10_000)
        tracker.stoppedWatching()

        assertFalse(tracker.absenceSettled(nowMs = 11_000))

        tracker.watching(nowMs = 20_000)
        assertFalse(tracker.absenceSettled(nowMs = 20_999))
        assertTrue(tracker.absenceSettled(nowMs = 21_000))
    }

    private fun <T : Any> present(value: T?): T {
        assertNotNull(value)
        return value!!
    }

    private fun sample(atMs: Long, latitude: Double = 40.0, speedMps: Double? = null) =
        SpeedSample(latitude = latitude, longitude = -75.0, observedAtMs = atMs, speedMps = speedMps)
}
