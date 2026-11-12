package com.opentasker.core.actions

import android.content.ContextWrapper
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionRegistry
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.engine.VariableStore
import com.opentasker.core.registerCoreRuntime
import java.util.TimeZone
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** New York on 25 June 2025 before dawn; the values are TaskerSunTimesTest's, from Tasker's code. */
class SunTimesActionTest {
    private val variables = VariableStore()
    private val logs = mutableListOf<String>()
    private var permissionAsked = 0
    private var fixAsked = 0

    @Before
    fun setUp() {
        registerCoreRuntime()
    }

    private fun run(
        vararg args: Pair<String, String>,
        permitted: Boolean = true,
        fix: Pair<Double, Double>? = NY_LAT to NY_LON,
    ): ActionResult {
        val action = SunTimesAction(
            permitted = { permissionAsked++; permitted },
            locate = { fixAsked++; fix },
            nowMs = { NOW_SECONDS * 1000 },
            zone = { TimeZone.getTimeZone("America/New_York") },
        )
        return runBlocking { action.run(ActionContext(ContextWrapper(null), variables, logger = { logs += it }), mapOf(*args)) }
    }

    private fun assertNewYorkJune(prefix: String = "ss") {
        assertEquals("1750843560", variables.get("${prefix}_sunrise"))
        assertEquals("1750897860", variables.get("${prefix}_sunset"))
        assertEquals("1750870740", variables.get("${prefix}_solar_noon"))
        assertEquals("1750841580", variables.get("${prefix}_civil_dawn"))
        assertEquals("1750899900", variables.get("${prefix}_civil_dusk"))
        assertEquals("1750839000", variables.get("${prefix}_nautical_dawn"))
        assertEquals("1750902420", variables.get("${prefix}_nautical_dusk"))
        assertEquals("1750836000", variables.get("${prefix}_astronomical_dawn"))
        assertEquals("1750905420", variables.get("${prefix}_astronomical_dusk"))
        assertEquals("Night", variables.get("${prefix}_day_or_night"))
        assertEquals("0", variables.get("${prefix}_day_or_night_code"))
        assertEquals("905", variables.get("${prefix}_sunlight_duration"))
    }

    @Test
    fun typedCoordinatesNeverAskForTheLocation() {
        val result = run("latitude" to "$NY_LAT", "longitude" to "$NY_LON", "time" to "$NOW_SECONDS", "elevations" to "-3,10")
        assertEquals(ActionResult.Success, result)
        assertNewYorkJune()
        assertEquals(listOf("1750842720", "1750847400"), variables.getArrayItems("ss_custom_morning"))
        assertEquals(listOf("1750898700", "1750894020"), variables.getArrayItems("ss_custom_evening"))
        assertEquals(0, permissionAsked)
        assertEquals(0, fixAsked)
        assertTrue(logs.none { "40.69" in it || "74.14" in it })
    }

    @Test
    fun theRegisteredActionIsThisOne() {
        assertTrue(ActionRegistry.get("sun.times") is SunTimesAction)
    }

    @Test
    fun aDayOnALaterHourStillGivesDayAndCodeOne() {
        // 7 AM, after the -6 degree dawn.
        run("latitude" to "$NY_LAT", "longitude" to "$NY_LON", "time" to "1750849200")
        assertEquals("Day", variables.get("ss_day_or_night"))
        assertEquals("1", variables.get("ss_day_or_night_code"))
    }

    @Test
    fun elevationsSplitOnLinesWhenThereAreAnyAndDropWhatIsNotANumber() {
        run("latitude" to "$NY_LAT", "longitude" to "$NY_LON", "time" to "$NOW_SECONDS", "elevations" to " -3 \nhigh\nNaN\n10")
        assertEquals(listOf("1750842720", "1750847400"), variables.getArrayItems("ss_custom_morning"))

        // With a line break, a comma no longer separates, so "-3,10" is one value that isn't a number.
        run("latitude" to "$NY_LAT", "longitude" to "$NY_LON", "time" to "$NOW_SECONDS", "elevations" to "-3,10\n10")
        assertEquals(listOf("1750847400"), variables.getArrayItems("ss_custom_morning"))
        assertEquals(listOf("1750894020"), variables.getArrayItems("ss_custom_evening"))
    }

    @Test
    fun noElevationsLeaveTheCustomArraysEmpty() {
        run("latitude" to "$NY_LAT", "longitude" to "$NY_LON", "time" to "$NOW_SECONDS", "elevations" to "-3,10")
        run("latitude" to "$NY_LAT", "longitude" to "$NY_LON", "time" to "$NOW_SECONDS")
        assertEquals(emptyList<String>(), variables.getArrayItems("ss_custom_morning").orEmpty())
        assertEquals(emptyList<String>(), variables.getArrayItems("ss_custom_evening").orEmpty())
    }

    @Test
    fun aBlankCoordinateComesFromTheFixAndATypedOneStays() {
        val result = run("longitude" to "$NY_LON", "time" to "$NOW_SECONDS", fix = NY_LAT to 120.0)
        assertEquals(ActionResult.Success, result)
        assertNewYorkJune()
        assertEquals(1, fixAsked)
        assertTrue(logs.none { "40.69" in it || "74.14" in it || "120" in it })
    }

    @Test
    fun aCoordinateThatIsNotANumberComesFromTheFixLikeTaskers() {
        assertEquals(ActionResult.Success, run("latitude" to "%LAT", "longitude" to "%LON", "time" to "$NOW_SECONDS"))
        assertNewYorkJune()
        assertEquals(1, fixAsked)
    }

    @Test
    fun aBlankCoordinateWithoutLocationAccessFailsAndSetsNothing() {
        val result = run("latitude" to "$NY_LAT", "time" to "$NOW_SECONDS", permitted = false)
        assertTrue(result is ActionResult.Failure)
        assertEquals(0, fixAsked)
        assertNull(variables.get("ss_sunrise"))
    }

    @Test
    fun noFixFails() {
        assertTrue(run("time" to "$NOW_SECONDS", fix = null) is ActionResult.Failure)
        assertNull(variables.get("ss_sunrise"))
    }

    @Test
    fun coordinatesOffTheGlobeFail() {
        listOf("91" to "0", "-90.5" to "0", "0" to "180.1", "0" to "-181", "NaN" to "0", "0" to "Infinity").forEach { (lat, lon) ->
            assertTrue("$lat, $lon", run("latitude" to lat, "longitude" to lon) is ActionResult.Failure)
        }
        assertNull(variables.get("ss_sunrise"))
        assertEquals(ActionResult.Success, run("latitude" to "90", "longitude" to "-180"))
    }

    @Test
    fun aBlankOrUnreadableTimeMeansNow() {
        run("latitude" to "$NY_LAT", "longitude" to "$NY_LON")
        assertNewYorkJune()
        variables.set("ss_sunrise", "")
        run("latitude" to "$NY_LAT", "longitude" to "$NY_LON", "time" to "%TIMES")
        assertNewYorkJune()
    }

    @Test
    fun aTimeTooFarOutForMillisecondsFails() {
        assertTrue(run("latitude" to "$NY_LAT", "longitude" to "$NY_LON", "time" to "${Long.MAX_VALUE / 1000 + 1}") is ActionResult.Failure)
        assertTrue(run("latitude" to "$NY_LAT", "longitude" to "$NY_LON", "time" to "${Long.MIN_VALUE / 1000 - 1}") is ActionResult.Failure)
        assertNull(variables.get("ss_sunrise"))
    }

    @Test
    fun thePrefixNamesEveryOutput() {
        run("latitude" to "$NY_LAT", "longitude" to "$NY_LON", "time" to "$NOW_SECONDS", "var" to "%Sun", "elevations" to "10")
        assertNewYorkJune("Sun")
        assertEquals(listOf("1750847400"), variables.getArrayItems("Sun_custom_morning"))
        assertNull(variables.get("ss_sunrise"))
    }

    @Test
    fun aPrefixThatCannotNameEveryOutputFails() {
        val longest = "a".repeat(64 - "_astronomical_dawn".length)
        assertEquals(ActionResult.Success, run("latitude" to "$NY_LAT", "longitude" to "$NY_LON", "var" to longest))
        assertTrue(run("latitude" to "$NY_LAT", "longitude" to "$NY_LON", "var" to longest + "a") is ActionResult.Failure)
        assertTrue(run("latitude" to "$NY_LAT", "longitude" to "$NY_LON", "var" to "1st") is ActionResult.Failure)
    }

    private companion object {
        const val NY_LAT = 40.6972846
        const val NY_LON = -74.1443129
        const val NOW_SECONDS = 1750841218L
    }
}
