package com.opentasker.core.contexts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarSunEventPresetsTest {
    @Test
    fun calendarPresetsCoverDuringUpcomingAndAllDay() {
        val presets = CalendarSunEventPresets.presetsFor("calendar")

        assertTrue(presets.any { it.config["state"] == "during" })
        assertTrue(presets.any { it.config["state"] == "upcoming" && it.config["beforeMinutes"] == "15" })
        assertTrue(presets.any { it.config["allDay"] == "true" })
    }

    @Test
    fun sunPresetsCoverBeforeAtAndAfterWindows() {
        val presets = CalendarSunEventPresets.presetsFor("sunset")

        assertTrue(presets.any { it.config["offsetMinutes"] == "-30" && it.config["windowMinutes"] == "10" })
        assertTrue(presets.any { it.config["offsetMinutes"] == "0" && it.config["windowMinutes"] == "5" })
        assertTrue(presets.any { it.config["offsetMinutes"] == "30" && it.config["windowMinutes"] == "10" })
    }

    @Test
    fun anOffsetPresetAfterCivilDuskGoesBackToSunset() {
        val presets = CalendarSunEventPresets.presetsFor("sunset")
        val civilDusk = CalendarSunEventPresets.applyPreset(
            mapOf("event" to "sunset"),
            presets.first { it.id == "sunset-civil" },
        )
        assertEquals("civil", civilDusk["elevation"])

        for (id in listOf("sunset-before", "sunset-after", "sunset-at")) {
            val config = CalendarSunEventPresets.applyPreset(civilDusk, presets.first { it.id == id })
            assertEquals("$id keeps civil dusk's elevation", "", config["elevation"])
        }
    }

    @Test
    fun applyPresetPreservesUnrelatedFilters() {
        val preset = CalendarSunEventPresets.presetsFor("calendar").first { it.id == "calendar-15-before" }
        val config = CalendarSunEventPresets.applyPreset(
            mapOf("event" to "calendar", "calendar" to "Work"),
            preset,
        )

        assertEquals("Work", config["calendar"])
        assertEquals("upcoming", config["state"])
        assertEquals("15", config["beforeMinutes"])
    }
}
