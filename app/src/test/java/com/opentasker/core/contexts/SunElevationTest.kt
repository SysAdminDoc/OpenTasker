package com.opentasker.core.contexts

import com.opentasker.core.actions.ActionFieldPolicy
import com.opentasker.core.engine.PulseEventContinuity
import com.opentasker.core.engine.SyntheticTriggerSimulator
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.ui.screens.contextInvalidFields
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

class SunElevationTest {
    private data class Place(val date: String, val latitude: Double, val longitude: Double, val zone: String)

    // Published by the US Naval Observatory (aa.usno.navy.mil/api/rstt/oneday) for these dates
    // and coordinates: begin civil twilight, rise, set, end civil twilight, local clock time.
    private val usno = mapOf(
        Place("2026-06-21", 40.7128, -74.0060, "America/New_York") to listOf("04:52", "05:25", "20:31", "21:04"),
        Place("2026-12-21", 51.5074, -0.1278, "Europe/London") to listOf("07:23", "08:04", "15:53", "16:34"),
        Place("2026-03-20", -33.8688, 151.2093, "Australia/Sydney") to listOf("06:33", "06:58", "19:07", "19:32"),
    )

    @Test
    fun civilTwilightAndSunriseMatchTheNavalObservatory() {
        usno.forEach { (place, published) ->
            val computed = listOf(
                minute(place, "sunrise", "civil"),
                minute(place, "sunrise", ""),
                minute(place, "sunset", ""),
                minute(place, "sunset", "civil"),
            )
            published.map(::clock).zip(computed).forEach { (expected, actual) ->
                assertTrue("$place expected $published, computed ${computed.map(::format)}", abs(expected - actual!!) <= 2)
            }
        }
    }

    @Test
    fun theElevationSettingReadsTwilightNamesAndDegrees() {
        assertEquals(SunEventCalculator.OFFICIAL_ELEVATION_DEGREES, SunEventCalculator.elevationDegrees(null)!!, 0.0)
        assertEquals(SunEventCalculator.OFFICIAL_ELEVATION_DEGREES, SunEventCalculator.elevationDegrees(" ")!!, 0.0)
        assertEquals(-6.0, SunEventCalculator.elevationDegrees("Civil")!!, 0.0)
        assertEquals(-12.0, SunEventCalculator.elevationDegrees(" nautical ")!!, 0.0)
        assertEquals(-18.0, SunEventCalculator.elevationDegrees("ASTRONOMICAL")!!, 0.0)
        assertEquals(-4.5, SunEventCalculator.elevationDegrees("-4.5")!!, 0.0)
        assertEquals(6.0, SunEventCalculator.elevationDegrees("6")!!, 0.0)
        listOf("dusk", "91", "-90.5", "NaN", "Infinity").forEach { assertNull(it, SunEventCalculator.elevationDegrees(it)) }
    }

    @Test
    fun deeperTwilightIsEarlierAtDawnAndLaterAtDusk() {
        val london = usno.keys.first { it.zone == "Europe/London" }
        val dawn = listOf("astronomical", "nautical", "civil", "").map { minute(london, "sunrise", it)!! }
        val dusk = listOf("", "civil", "nautical", "astronomical").map { minute(london, "sunset", it)!! }

        assertEquals(dawn.sorted(), dawn)
        assertEquals(dusk.sorted(), dusk)
        assertEquals(minute(london, "sunset", "civil"), minute(london, "sunset", "-6"))
        assertTrue("golden hour ends before sunset", minute(london, "sunset", "6")!! < dusk.first())
    }

    @Test
    fun aSunThatNeverSinksThatFarHasNoTwilight() {
        val tromsoMidsummer = Place("2026-06-21", 69.6492, 18.9553, "Europe/Oslo")
        assertNull(minute(tromsoMidsummer, "sunset", "civil"))
        // Oslo's midsummer sun sets, but it never gets 12 degrees down.
        val oslo = Place("2026-06-21", 59.9139, 10.7522, "Europe/Oslo")
        assertTrue(minute(oslo, "sunset", "")!! > 0)
        assertNull(minute(oslo, "sunset", "nautical"))
    }

    @Test
    fun aCivilDuskProfileFiresOncePerDayAtTheMinusSixCrossing() {
        val spec = ContextSpec(
            ContextType.EVENT,
            mapOf("event" to "sunset", "elevation" to "civil", "latitude" to "40.7128", "longitude" to "-74.0060", "windowMinutes" to "5"),
        )
        val day = matchingTimes(spec, "2026-06-21")
        val nextDay = matchingTimes(spec, "2026-06-22")

        assertEquals(5, day.size)
        assertTrue("window opened at ${day.first()}, USNO has 21:04", abs(clock(day.first()) - clock("21:04")) <= 2)
        assertTrue("an ordinary sunset profile doesn't fire then", day.none { ContextMatchEvaluator.matches(spec.withConfig("elevation" to ""), tick("2026-06-21", it)) })

        val continuity = PulseEventContinuity()
        val pulses = day.map { continuity.observe(contextIndex = 0, ContextMatchEvaluator.withStablePulseIdentity(spec, tick("2026-06-21", it))) }
        assertFalse(pulses.first().duplicate)
        assertTrue(pulses.drop(1).all { it.duplicate })
        val tomorrow = continuity.observe(contextIndex = 0, ContextMatchEvaluator.withStablePulseIdentity(spec, tick("2026-06-22", nextDay.first())))
        assertFalse("the next evening is a new occurrence", tomorrow.duplicate)

        val sunset = spec.withConfig("elevation" to "")
        val sunsetId = ContextMatchEvaluator.withStablePulseIdentity(sunset, tick("2026-06-21", matchingTimes(sunset, "2026-06-21").first())).metadata["eventId"]
        val duskId = ContextMatchEvaluator.withStablePulseIdentity(spec, tick("2026-06-21", day.first())).metadata["eventId"]
        assertTrue(sunsetId.orEmpty().startsWith("sun:sunset:2026-06-21:"))
        assertNotEquals(sunsetId, duskId)
    }

    @Test
    fun anUnreadableElevationNeverFiresAndTheEditorSaysWhy() {
        val spec = ContextSpec(ContextType.EVENT, mapOf("event" to "sunset", "elevation" to "dusk", "latitude" to "40.7128", "longitude" to "-74.0060", "windowMinutes" to "180"))
        assertTrue(matchingTimes(spec, "2026-06-21").isEmpty())

        assertEquals(ActionFieldPolicy.Error.INVALID_NUMBER, contextInvalidFields(ContextType.EVENT, spec.config)["elevation"]?.error)
        assertEquals(ActionFieldPolicy.Error.BELOW_MINIMUM, contextInvalidFields(ContextType.EVENT, mapOf("elevation" to "-95"))["elevation"]?.error)
        assertEquals(ActionFieldPolicy.Error.INVALID_NUMBER, contextInvalidFields(ContextType.EVENT, mapOf("elevation" to "NaN"))["elevation"]?.error)
        listOf("", "civil", "Nautical", "-4.5").forEach {
            assertNull(it, contextInvalidFields(ContextType.EVENT, mapOf("elevation" to it))["elevation"])
        }

        assertTrue(SyntheticTriggerSimulator.template(spec).issue.orEmpty().contains("elevation"))
    }

    @Test
    fun simulatingCivilDuskPicksATimeAfterSunset() {
        val sunset = ContextSpec(ContextType.EVENT, mapOf("event" to "sunset", "latitude" to "40.7128", "longitude" to "-74.0060"))
        val nowMs = LocalDate.parse("2026-06-21").atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val official = SyntheticTriggerSimulator.template(sunset, nowMs)
        val civil = SyntheticTriggerSimulator.template(sunset.withConfig("elevation" to "civil"), nowMs)

        assertNull(civil.issue)
        assertTrue(ContextMatchEvaluator.matches(sunset.withConfig("elevation" to "civil"), civil.event))
        val gap = Math.floorMod(clock(civil.event.metadata.getValue("time")) - clock(official.event.metadata.getValue("time")), 24 * 60)
        assertTrue("civil dusk came $gap minutes after sunset", gap in 25..45)
    }

    @Test
    fun theEditorOffersTwilightPresetsAndAtSunsetResetsThem() {
        val dusk = CalendarSunEventPresets.presetsFor("sunset").associate { it.id to it.config }
        assertEquals("civil", dusk.getValue("sunset-civil")["elevation"])
        assertEquals("nautical", dusk.getValue("sunset-nautical")["elevation"])
        assertEquals("astronomical", dusk.getValue("sunset-astronomical")["elevation"])
        assertEquals("civil", CalendarSunEventPresets.presetsFor("sunrise").first { it.id == "sunrise-civil" }.config["elevation"])

        val atCivilDusk = CalendarSunEventPresets.applyPreset(mapOf("latitude" to "51.5"), CalendarSunEventPresets.presetsFor("sunset").first { it.id == "sunset-civil" })
        val backToSunset = CalendarSunEventPresets.applyPreset(atCivilDusk, CalendarSunEventPresets.presetsFor("sunset").first { it.id == "sunset-at" })
        assertEquals("51.5", backToSunset["latitude"])
        assertEquals("", backToSunset["elevation"])
    }

    @Test
    fun theInspectorNamesTheTwilight() {
        val spec = ContextSpec(ContextType.EVENT, mapOf("event" to "sunset", "elevation" to "civil", "latitude" to "51.5", "longitude" to "-0.12"))
        assertEquals("Civil dusk (sun 6 degrees below the horizon), latitude=51.5, longitude=-0.12", contextConfigSummary(spec))
        assertEquals(
            "Sun rising past 4.5 degrees above the horizon, latitude=51.5, longitude=-0.12; inverted",
            contextConfigSummary(spec.copy(invert = true).withConfig("event" to "sunrise", "elevation" to "4.5")),
        )
        assertEquals("Sunset, latitude=51.5, longitude=-0.12", contextConfigSummary(spec.withConfig("elevation" to "")))
    }

    private fun minute(place: Place, event: String, elevation: String): Int? = SunEventCalculator.eventMinuteOfDay(
        LocalDate.parse(place.date),
        place.latitude,
        place.longitude,
        event,
        ZoneId.of(place.zone),
        SunEventCalculator.elevationDegrees(elevation)!!,
    )

    private fun ContextSpec.withConfig(vararg entries: Pair<String, String>) = copy(config = config + entries)

    private fun tick(date: String, time: String) = ContextEvent(
        type = "event",
        matched = true,
        metadata = mapOf("event" to "sun_tick", "date" to date, "time" to time, "zone" to "America/New_York"),
    )

    private fun matchingTimes(spec: ContextSpec, date: String): List<String> =
        (0 until 24 * 60).map(::format).filter { ContextMatchEvaluator.matches(spec, tick(date, it)) }

    private fun clock(time: String): Int = time.split(":").let { it[0].toInt() * 60 + it[1].toInt() }

    private fun format(minute: Int?): String = minute?.let { "%02d:%02d".format(it / 60, it % 60) } ?: "none"
}
