package com.opentasker.core.contexts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a sensor source keeps when it starts or registers again: Bluetooth Near only counts a
 * device's absence while a scan actually runs, and Speed only settles a stand-in into a measured 0
 * while the provider it came from is still registered.
 */
class SensorWatchRestartTest {
    @Test
    fun aScanThatStartsCountsAbsenceFromWhenItStarted() {
        val tracker = NearbyBluetoothTracker(forgetAfterMs = 1_000)

        tracker.startWatching(nowMs = 100) {}

        assertFalse(tracker.absenceSettled(nowMs = 1_099))
        assertTrue(tracker.absenceSettled(nowMs = 1_100))
    }

    @Test
    fun aFailureReportedWhileTheScanStartsStopsTheWatch() {
        val tracker = NearbyBluetoothTracker(forgetAfterMs = 1_000)

        // onScanFailed arriving before startScan returns, as it can on the main thread.
        tracker.startWatching(nowMs = 100) { tracker.stoppedWatching() }

        assertFalse(tracker.absenceSettled(nowMs = 5_000))
    }

    @Test
    fun aScanThatThrowsWhileStartingStopsTheWatch() {
        val tracker = NearbyBluetoothTracker(forgetAfterMs = 1_000)
        tracker.startWatching(nowMs = 100) {}

        assertThrows(SecurityException::class.java) {
            tracker.startWatching(nowMs = 200) { throw SecurityException("no scan permission") }
        }

        assertFalse(tracker.absenceSettled(nowMs = 5_000))
    }

    @Test
    fun aStandInFromAProviderThatWasDroppedNeverSettles() {
        val readings = SpeedReadings(freshForMs = 15_000, settleAfterMs = 60_000)
        readings.onFix(sample(atMs = 1_000_000), "network", accuracyMps = null, nowMs = 1_000_000)

        readings.forgetPrevious()
        readings.keepStandInOnlyFrom(setOf("gps"))

        assertNull(readings.settle(nowMs = 1_060_000))
    }

    @Test
    fun aStandInFromAProviderStillRegisteredSettlesAsBefore() {
        val readings = SpeedReadings(freshForMs = 15_000, settleAfterMs = 60_000)
        readings.onFix(sample(atMs = 1_000_000), "network", accuracyMps = null, nowMs = 1_000_000)

        readings.forgetPrevious()
        readings.keepStandInOnlyFrom(setOf("gps", "network"))

        val settled = readings.settle(nowMs = 1_060_000)
        assertEquals("0", settled?.get("speed"))
        assertEquals("network", settled?.get("speed_provider"))
    }

    private fun sample(atMs: Long) = SpeedSample(latitude = 40.0, longitude = -75.0, observedAtMs = atMs)
}
