package com.opentasker.core.contexts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class CalendarSunContextEventsTest {
    @Test
    fun eventDemandOnlyWakesTheRequiredCalendarOrSunProducer() {
        assertEquals(CalendarSunDemand.ALL, CalendarSunContextEvents.demandFor(null))
        assertEquals(CalendarSunDemand.ALL, CalendarSunContextEvents.demandFor(""))
        assertEquals(CalendarSunDemand.CALENDAR, CalendarSunContextEvents.demandFor("calendar"))
        assertEquals(CalendarSunDemand.SUN, CalendarSunContextEvents.demandFor("sunrise"))
        assertEquals(CalendarSunDemand.SUN, CalendarSunContextEvents.demandFor("sun_tick"))
        assertEquals(CalendarSunDemand.NONE, CalendarSunContextEvents.demandFor("nfc"))
    }

    @Test
    fun selectCalendarEventPrefersActiveBusyEventAndRedactsTitle() {
        val now = epochMillis(2026, 5, 5, 14, 0)
        val event = CalendarSunContextEvents.selectCalendarEvent(
            instances = listOf(
                CalendarInstance(
                    calendarName = "Work",
                    calendarId = 7,
                    beginMs = now - 10 * MILLIS_PER_MINUTE,
                    endMs = now + 20 * MILLIS_PER_MINUTE,
                    allDay = false,
                    availability = "busy",
                ),
            ),
            nowMs = now,
        )

        assertTrue(event.matched)
        assertEquals("calendar", event.metadata["event"])
        assertEquals("during", event.metadata["state"])
        assertEquals("Work", event.metadata["calendar"])
        assertEquals("20", event.metadata["minutesUntilEnd"])
        assertFalse(event.metadata.containsKey("title"))
        assertFalse(event.metadata.containsKey("description"))
    }

    @Test
    fun selectCalendarEventEmitsUpcomingWithinWindow() {
        val now = epochMillis(2026, 5, 5, 14, 0)
        val event = CalendarSunContextEvents.selectCalendarEvent(
            instances = listOf(
                CalendarInstance(
                    calendarName = "Personal",
                    calendarId = 9,
                    beginMs = now + 15 * MILLIS_PER_MINUTE,
                    endMs = now + 45 * MILLIS_PER_MINUTE,
                    allDay = false,
                    availability = "tentative",
                ),
            ),
            nowMs = now,
            beforeWindowMinutes = 30,
        )

        assertTrue(event.matched)
        assertEquals("upcoming", event.metadata["state"])
        assertEquals("15", event.metadata["minutesUntilStart"])
    }

    @Test
    fun selectCalendarEventIgnoresFreeEventsAndReportsIdle() {
        val now = epochMillis(2026, 5, 5, 14, 0)
        val event = CalendarSunContextEvents.selectCalendarEvent(
            instances = listOf(
                CalendarInstance(
                    calendarName = "Work",
                    calendarId = 7,
                    beginMs = now - 10 * MILLIS_PER_MINUTE,
                    endMs = now + 20 * MILLIS_PER_MINUTE,
                    allDay = false,
                    availability = "free",
                ),
            ),
            nowMs = now,
        )

        assertFalse(event.matched)
        assertEquals("idle", event.metadata["state"])
    }

    @Test
    fun allDayEventsMatchOnLocalDayNotUtcDay() {
        val zone = java.time.ZoneId.of("America/New_York") // UTC-4 on this date
        // All-day event for 2026-05-05: provider stores midnight-UTC bounds.
        val utcMidnightBegin = epochMillis(2026, 5, 5, 0, 0)
        val utcMidnightEnd = epochMillis(2026, 5, 6, 0, 0)
        val instance = CalendarInstance(
            calendarName = "Work",
            calendarId = 7,
            beginMs = utcMidnightBegin,
            endMs = utcMidnightEnd,
            allDay = true,
            availability = "busy",
        )

        // 2026-05-04 23:00 local (03:00 UTC on the 5th): raw UTC bounds would already
        // report "during", but the local day has not started yet.
        val beforeLocalMidnight = epochMillis(2026, 5, 5, 3, 0)
        val early = CalendarSunContextEvents.selectCalendarEvent(
            instances = listOf(instance),
            nowMs = beforeLocalMidnight,
            zone = zone,
        )
        assertFalse("all-day event must not be active before local midnight", early.matched && early.metadata["state"] == "during")

        // 2026-05-05 23:00 local (03:00 UTC on the 6th): raw UTC bounds would report the
        // event already over, but locally it is still the 5th.
        val lateLocalEvening = epochMillis(2026, 5, 6, 3, 0)
        val late = CalendarSunContextEvents.selectCalendarEvent(
            instances = listOf(instance),
            nowMs = lateLocalEvening,
            zone = zone,
        )
        assertTrue("all-day event must stay active through the local evening", late.matched)
        assertEquals("during", late.metadata["state"])
    }

    @Test
    fun anAllDayEventIsStillFetchedLateInTheEveningWestOfUtc() {
        val zone = java.time.ZoneId.of("America/Los_Angeles") // UTC-7 on this date
        val today = CalendarInstance(
            calendarName = "Work",
            calendarId = 7,
            beginMs = epochMillis(2026, 5, 5, 0, 0),
            endMs = epochMillis(2026, 5, 6, 0, 0),
            allDay = true,
            availability = "busy",
        )
        val yesterday = today.copy(
            beginMs = epochMillis(2026, 5, 4, 0, 0),
            endMs = epochMillis(2026, 5, 5, 0, 0),
        )
        // 2026-05-05 23:30 local is 06:30 UTC on the 6th, hours after the row's stored end.
        val now = epochMillis(2026, 5, 6, 6, 30)

        val window = CalendarSunContextEvents.instanceQueryWindow(now)
        assertTrue("the provider must still return today's row", today.endMs >= window.first)

        val event = CalendarSunContextEvents.selectCalendarEvent(
            instances = listOf(yesterday, today),
            nowMs = now,
            zone = zone,
        )
        assertTrue(event.matched)
        assertEquals("during", event.metadata["state"])
        assertEquals("7:${today.beginMs + 7 * 60 * MILLIS_PER_MINUTE}:during", event.metadata["eventId"])
    }

    private fun epochMillis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli()

    private companion object {
        const val MILLIS_PER_MINUTE = 60_000L
    }

    @Test
    fun aRunningEventKeepsOneIdentityForEveryMinuteItIsObserved() {
        // The producer re-emits every minute for the whole window. Without a stable identity each
        // minute looked like a new pulse, so a 60-minute meeting ran its enter task ~60 times.
        val begin = epochMillis(2026, 5, 5, 14, 0)
        val instance = CalendarInstance(
            calendarName = "Work",
            calendarId = 7,
            beginMs = begin,
            endMs = begin + 60 * MILLIS_PER_MINUTE,
            allDay = false,
            availability = "busy",
        )

        val ids = (1..5).map { minute ->
            CalendarSunContextEvents
                .selectCalendarEvent(listOf(instance), nowMs = begin + minute * MILLIS_PER_MINUTE)
                .metadata["eventId"]
        }

        assertEquals(1, ids.distinct().size)
        assertEquals("7:$begin:during", ids.first())
    }

    @Test
    fun upcomingAndDuringAreDistinctPulsesForTheSameEvent() {
        val begin = epochMillis(2026, 5, 5, 14, 0)
        val instance = CalendarInstance(
            calendarName = "Work",
            calendarId = 7,
            beginMs = begin,
            endMs = begin + 30 * MILLIS_PER_MINUTE,
            allDay = false,
            availability = "busy",
        )

        val upcoming = CalendarSunContextEvents
            .selectCalendarEvent(listOf(instance), nowMs = begin - 5 * MILLIS_PER_MINUTE)
        val during = CalendarSunContextEvents
            .selectCalendarEvent(listOf(instance), nowMs = begin + 5 * MILLIS_PER_MINUTE)

        assertEquals("upcoming", upcoming.metadata["state"])
        assertEquals("during", during.metadata["state"])
        assertNotEquals(upcoming.metadata["eventId"], during.metadata["eventId"])
    }
}
