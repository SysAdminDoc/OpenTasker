package com.opentasker.core.contexts

import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * West of Scotland at 56°N, nautical dusk drifts from about 23:27 on 10 May to past midnight a
 * week later. The almanac answers in hours of the day, and wrapping that into the same date put
 * 19 May's dusk at 00:02 on the 19th, four minutes after the 18th's, then fired every later night
 * at the next night's minute.
 */
class SunTwilightAfterMidnightTest {
    private val zone = ZoneId.of("Europe/London")
    private val latitude = 56.0
    private val longitude = -8.0
    private val nautical = -12.0
    private val first = LocalDate.of(2026, 5, 10)
    private val last = LocalDate.of(2026, 5, 20)

    private val dusk = ContextSpec(
        ContextType.EVENT,
        mapOf(
            "event" to "sunset",
            "latitude" to latitude.toString(),
            "longitude" to longitude.toString(),
            "elevation" to "nautical",
        ),
    )

    private fun crossing(date: LocalDate): LocalDateTime =
        SunEventCalculator.eventLocalDateTime(date, latitude, longitude, "sunset", zone, nautical)!!

    private fun tick(at: LocalDateTime) = ContextEvent(
        type = "event",
        matched = true,
        metadata = mapOf(
            "event" to "sun_tick",
            "date" to at.toLocalDate().toString(),
            "time" to "%02d:%02d".format(at.hour, at.minute),
            "zone" to zone.id,
        ),
    )

    @Test
    fun aDuskPastMidnightLandsOnTheNextDate() {
        assertEquals(LocalDate.of(2026, 5, 18), crossing(LocalDate.of(2026, 5, 18)).toLocalDate())
        assertEquals(23, crossing(LocalDate.of(2026, 5, 18)).hour)
        assertEquals(LocalDate.of(2026, 5, 20), crossing(LocalDate.of(2026, 5, 19)).toLocalDate())
        assertEquals(0, crossing(LocalDate.of(2026, 5, 19)).hour)

        generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }.zipWithNext().forEach { (day, next) ->
            val gap = Duration.between(crossing(day), crossing(next))
            assertTrue("$day to $next: $gap", gap > Duration.ofHours(23) && gap < Duration.ofHours(25))
        }
    }

    @Test
    fun eachNightFiresOnceAtItsOwnCrossing() {
        val start = first.atStartOfDay()
        val end = last.plusDays(1).atTime(23, 59)
        val firings = generateSequence(start) { it.plusMinutes(1) }
            .takeWhile { !it.isAfter(end) }
            .filter { ContextMatchEvaluator.matches(dusk, tick(it)) }
            .toList()

        val nights = generateSequence(first.minusDays(1)) { it.plusDays(1) }.takeWhile { !it.isAfter(last.plusDays(1)) }
        val expected = nights.map(::crossing).filter { !it.isBefore(start) && !it.isAfter(end) }.toList()
        assertEquals(expected, firings)
        firings.zipWithNext().forEach { (earlier, later) ->
            assertTrue("$earlier then $later", Duration.between(earlier, later) > Duration.ofHours(12))
        }
    }

    @Test
    fun aWindowAfterAPostMidnightDuskKeepsOneOccurrence() {
        val windowed = dusk.copy(config = dusk.config + ("windowMinutes" to "30"))
        val dusk19 = crossing(LocalDate.of(2026, 5, 19))
        val ids = (0L until 30L).map { minute ->
            ContextMatchEvaluator.withStablePulseIdentity(windowed, tick(dusk19.plusMinutes(minute))).metadata["eventId"]
        }

        assertEquals(1, ids.distinct().size)
        assertTrue(ids.first().toString(), ids.first().orEmpty().startsWith("sun:sunset:2026-05-19:"))
        assertEquals(null, ContextMatchEvaluator.withStablePulseIdentity(windowed, tick(dusk19.plusMinutes(30))).metadata["eventId"])
    }
}
