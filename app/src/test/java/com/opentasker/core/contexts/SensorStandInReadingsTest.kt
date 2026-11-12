package com.opentasker.core.contexts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Each sensor source tells the value it shows before measuring anything from a reading, and turns
 * the stand-in into a reading once it has one.
 */
class SensorStandInReadingsTest {
    @Test
    fun activityPublishesAMeasuredValueThatReadsTheSameAsTheStandIn() {
        val readings = ActivityReadings(stepRateBucket = 10)

        assertEquals(
            mapOf("activity" to "stationary", "activity_steps_per_minute" to "0"),
            readings.next("stationary", 0, measured = false),
        )
        // The idle check's "stationary" reads the same and still has to replace the stand-in.
        assertNotNull(readings.next("stationary", 0, measured = true))
        // After that, the same value again is a repeat.
        assertNull(readings.next("stationary", 0, measured = true))
        assertNull(readings.next("stationary", 0, measured = false))
        assertEquals("walking", readings.next("walking", 15, measured = true)?.get("activity"))
    }

    @Test
    fun aFixWithNoSpeedAndNothingBeforeItIsAStandIn() {
        val readings = SpeedReadings(freshForMs = 15_000, settleAfterMs = 60_000)

        val (values, measured) = readings.onFix(sample(atMs = 1_000_000), "network", accuracyMps = null, nowMs = 1_000_000)

        assertFalse(measured)
        assertEquals("0", values["speed"])
        assertEquals("network", values["speed_provider"])
    }

    @Test
    fun aSecondFixMeasuresTheSpeedBetweenThem() {
        val readings = SpeedReadings(freshForMs = 15_000, settleAfterMs = 60_000)
        readings.onFix(sample(atMs = 1_000_000), "network", accuracyMps = null, nowMs = 1_000_000)

        // About 111 m north in 20 s.
        val (values, measured) = readings.onFix(
            sample(atMs = 1_020_000, latitude = 40.001),
            "network",
            accuracyMps = null,
            nowMs = 1_020_000,
        )

        assertTrue(measured)
        assertTrue(values.getValue("speed").toDouble() in 5.0..6.0)
    }

    @Test
    fun aLiveStandInSettlesIntoAMeasuredZeroOnlyAfterTheQuietSpell() {
        val readings = SpeedReadings(freshForMs = 15_000, settleAfterMs = 60_000)
        readings.onFix(sample(atMs = 1_000_000), "network", accuracyMps = null, nowMs = 1_000_000)

        assertNull(readings.settle(nowMs = 1_030_000))
        assertEquals("0", readings.settle(nowMs = 1_060_000)?.get("speed"))
        // Once.
        assertNull(readings.settle(nowMs = 1_090_000))
    }

    @Test
    fun aFixAfterTheStandInMeansThereIsNothingToSettle() {
        val readings = SpeedReadings(freshForMs = 15_000, settleAfterMs = 60_000)
        readings.onFix(sample(atMs = 1_000_000), "gps", accuracyMps = null, nowMs = 1_000_000)
        readings.onFix(sample(atMs = 1_020_000, speedMps = 12.0), "gps", accuracyMps = 0.5, nowMs = 1_020_000)

        assertNull(readings.settle(nowMs = 1_200_000))
    }

    @Test
    fun aLastKnownFixIsAReadingOnlyWhileItIsFresh() {
        val fresh = SpeedReadings(freshForMs = 15_000, settleAfterMs = 60_000)
        val (freshValues, freshMeasured) = fresh.onFix(
            sample(atMs = 995_000, speedMps = 12.0),
            "gps",
            accuracyMps = 0.5,
            nowMs = 1_000_000,
            lastKnown = true,
        )
        assertTrue(freshMeasured)
        assertEquals("12", freshValues["speed"])
        assertEquals("0.5", freshValues["speed_accuracy_mps"])

        // An hour-old fix says nothing about now, even with a speed on it.
        val stale = SpeedReadings(freshForMs = 15_000, settleAfterMs = 60_000)
        val (staleValues, staleMeasured) = stale.onFix(
            sample(atMs = 1_000_000 - 3_600_000, speedMps = 12.0),
            "gps",
            accuracyMps = 0.5,
            nowMs = 1_000_000,
            lastKnown = true,
        )
        assertFalse(staleMeasured)
        assertEquals("0", staleValues["speed"])
        assertFalse("speed_accuracy_mps" in staleValues)
    }

    @Test
    fun aLastKnownStandInNeverSettlesOnItsOwn() {
        // A cold GPS can send nothing for a minute while the car moves.
        val readings = SpeedReadings(freshForMs = 15_000, settleAfterMs = 60_000)
        readings.onFix(sample(atMs = 0), "gps", accuracyMps = null, nowMs = 1_000_000, lastKnown = true)

        assertNull(readings.settle(nowMs = 1_000_000 + 600_000))
    }

    @Test
    fun aStaleLastKnownFixIsNotWhereTheNextSpeedIsWorkedOutFrom() {
        val readings = SpeedReadings(freshForMs = 15_000, settleAfterMs = 60_000)
        readings.onFix(sample(atMs = 0), "network", accuracyMps = null, nowMs = 1_000_000, lastKnown = true)

        val (_, measured) = readings.onFix(sample(atMs = 1_010_000, latitude = 40.001), "network", accuracyMps = null, nowMs = 1_010_000)

        assertFalse(measured)
    }

    @Test
    fun tetheringShowsAStandInUntilABroadcastOrTheRecheck() {
        val settles = TetheringReadings()
        assertEquals(mapOf("tethering" to "false"), settles.standIn())
        assertEquals(mapOf("tethering" to "false"), settles.settle())
        assertNull(settles.settle())

        val hears = TetheringReadings()
        assertEquals(mapOf("tethering" to "true"), hears.apState(enabled = true))
        // A sticky broadcast that came before the stand-in went out wins over it.
        assertNull(hears.standIn())
        assertNull(hears.settle())
        assertEquals(mapOf("tethering" to "true"), hears.tetheredInterfaces(tethered = false))
        assertEquals(mapOf("tethering" to "false"), hears.apState(enabled = false))
    }

    @Test
    fun bluetoothNearAbsenceSettlesOnlyAfterWatchingAsLongAsADeviceIsKept() {
        val tracker = NearbyBluetoothTracker(forgetAfterMs = 1_000)
        assertFalse(tracker.absenceSettled(nowMs = 50_000))

        tracker.watching(nowMs = 10_000)
        tracker.watching(nowMs = 10_500)
        assertFalse(tracker.absenceSettled(nowMs = 10_999))
        assertTrue(tracker.absenceSettled(nowMs = 11_000))

        tracker.clear()
        assertFalse(tracker.absenceSettled(nowMs = 50_000))
    }

    @Test
    fun theMarkerIsPrivateAndOnlyOneMeansStandIn() {
        val key = standInMarkerKey("activity")
        assertTrue(key.startsWith(STAND_IN_MARKER_PREFIX))
        assertTrue(isStandIn(mapOf(key to "1"), "activity"))
        assertFalse(isStandIn(mapOf(key to ""), "activity"))
        assertFalse(isStandIn(emptyMap(), "activity"))
    }

    private fun sample(atMs: Long, latitude: Double = 40.0, speedMps: Double? = null) =
        SpeedSample(latitude = latitude, longitude = -75.0, observedAtMs = atMs, speedMps = speedMps)
}
