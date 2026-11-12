package com.opentasker.core.contexts

import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Expected values come from Tasker 6.7.8's own Get Sunrise/Sunset code, run as written, except for
 * day or night: Tasker drops the fraction of the day there and this keeps it, so those come from the
 * same code with the fraction kept. The cases where the two disagree are marked.
 */
class TaskerSunTimesTest {
    private fun zone(id: String) = TimeZone.getTimeZone(id)

    private fun times(latitude: Double, longitude: Double, zone: String, seconds: Long, custom: List<Double> = emptyList()) =
        TaskerSunTimes.calculate(latitude, longitude, seconds * 1000, zone(zone), custom)

    /** sunrise, sunset, noon, then each twilight's dawn and dusk, civil first. */
    private fun TaskerSunTimes.Times.clock() = listOf(
        sunrise, sunset, solarNoon, civilDawn, civilDusk, nauticalDawn, nauticalDusk, astronomicalDawn, astronomicalDusk,
    )

    @Test
    fun newYorkInJuneBeforeDawnWithCustomElevations() {
        val times = times(NY_LAT, NY_LON, NY, 1750841218, listOf(-3.0, 10.0))
        assertEquals(
            listOf(1750843560L, 1750897860, 1750870740, 1750841580, 1750899900, 1750839000, 1750902420, 1750836000, 1750905420),
            times.clock(),
        )
        assertEquals(listOf(1750842720L, 1750847400), times.customMorning)
        assertEquals(listOf(1750898700L, 1750894020), times.customEvening)
        assertFalse(times.isDay)
        assertEquals(905, times.sunlightMinutes)
    }

    @Test
    fun newYorkInJanuary() {
        val times = times(NY_LAT, NY_LON, NY, 1736960400)
        assertEquals(
            listOf(1736943480L, 1736978040, 1736960760, 1736941680, 1736979840, 1736939640, 1736981820, 1736937660, 1736983800),
            times.clock(),
        )
        assertTrue(times.isDay)
        assertEquals(576, times.sunlightMinutes)
        assertEquals(emptyList<Long>(), times.customMorning)
        assertEquals(emptyList<Long>(), times.customEvening)
    }

    @Test
    fun theDayTheClocksGoForwardAndTheDayBefore() {
        assertEquals(
            listOf(1741519020L, 1741560960, 1741539960, 1741517340, 1741562640, 1741515480, 1741564500, 1741513560, 1741566420),
            times(NY_LAT, NY_LON, NY, 1741525200).clock(),
        )
        assertEquals(700, times(NY_LAT, NY_LON, NY, 1741525200).sunlightMinutes)
        assertEquals(
            listOf(1741432680L, 1741474500, 1741453620, 1741431060, 1741476180, 1741429140, 1741478040, 1741427220, 1741479960),
            times(NY_LAT, NY_LON, NY, 1741435200).clock(),
        )
        assertEquals(697, times(NY_LAT, NY_LON, NY, 1741435200).sunlightMinutes)
    }

    @Test
    fun theMidnightSunPutsEveryTimeAtTheDaysEnds() {
        val times = times(TROMSO_LAT, TROMSO_LON, OSLO, 1750500000)
        val dawn = 1750459500L
        val dusk = 1750545900L
        assertEquals(listOf(dawn, dusk, 1750502700, dawn, dusk, dawn, dusk, dawn, dusk), times.clock())
        assertTrue(times.isDay)
        assertEquals(1440, times.sunlightMinutes)
    }

    @Test
    fun thePolarNightPutsSunriseAndSunsetAtNoon() {
        val times = times(TROMSO_LAT, TROMSO_LON, OSLO, 1766314800)
        assertEquals(
            listOf(1766313720L, 1766313720, 1766313720, 1766305860, 1766321580, 1766299560, 1766327820, 1766294880, 1766332560),
            times.clock(),
        )
        // Tasker says night: it reads the twilight of the day before.
        assertTrue(times.isDay)
        assertEquals(0, times.sunlightMinutes)
    }

    @Test
    fun sydneyInSummerTime() {
        val times = times(-33.8688, 151.2093, "Australia/Sydney", 1766278800)
        assertEquals(
            listOf(1766256000L, 1766307840, 1766281920, 1766254260, 1766309640, 1766252100, 1766311740, 1766249760, 1766314140),
            times.clock(),
        )
        // Noon. Tasker says night.
        assertTrue(times.isDay)
        assertEquals(864, times.sunlightMinutes)
    }

    @Test
    fun kolkataOnAHalfHourOffset() {
        val times = times(22.5726, 88.3639, "Asia/Kolkata", 1741588200)
        assertEquals(
            listOf(1741565940L, 1741608780, 1741587360, 1741564620, 1741610160, 1741563060, 1741611720, 1741561500, 1741613280),
            times.clock(),
        )
        // Tasker says night.
        assertTrue(times.isDay)
        assertEquals(714, times.sunlightMinutes)
    }

    @Test
    fun dayOrNightFollowsTheMinusSixDegreeTwilight() {
        // 7 AM in June. Tasker says night.
        assertTrue(times(NY_LAT, NY_LON, NY, 1750849200).isDay)
        assertTrue(times(NY_LAT, NY_LON, NY, 1750898700).isDay)
        assertFalse(times(NY_LAT, NY_LON, NY, 1750903200).isDay)
        assertFalse(times(NY_LAT, NY_LON, NY, 1750908600).isDay)
        assertFalse(times(-33.8688, 151.2093, "Australia/Sydney", 1766318400).isDay)
    }

    @Test
    fun aThaiDefaultLocaleDoesNotMoveTheDay() {
        val expected = times(NY_LAT, NY_LON, NY, 1736960400)
        val saved = Locale.getDefault()
        try {
            // Its default calendar is Buddhist, 543 years ahead.
            Locale.setDefault(Locale.forLanguageTag("th-TH-u-ca-buddhist"))
            assertEquals(expected, times(NY_LAT, NY_LON, NY, 1736960400))
        } finally {
            Locale.setDefault(saved)
        }
    }

    private companion object {
        const val NY = "America/New_York"
        const val NY_LAT = 40.6972846
        const val NY_LON = -74.1443129
        const val OSLO = "Europe/Oslo"
        const val TROMSO_LAT = 69.6492
        const val TROMSO_LON = 18.9553
    }
}
