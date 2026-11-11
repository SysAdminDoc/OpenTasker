package com.opentasker.core.health

import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class HealthPollingTest {
    private fun state(key: String, pollMinutes: String? = null) = ContextSpec(
        ContextType.STATE,
        buildMap {
            put("key", key)
            put("operator", ">=")
            put("value", "1")
            pollMinutes?.let { put(HealthPolling.POLL_MINUTES_FIELD, it) }
        },
    )

    @Test
    fun noHealthContextMeansNoPoll() {
        assertNull(HealthPolling.plan(emptyList()))
        assertNull(HealthPolling.plan(listOf(state("battery_level"), state("bt_near"))))
        // Only STATE contexts compare readings, so an event or app context with the same key doesn't poll.
        assertNull(HealthPolling.plan(listOf(ContextSpec(ContextType.EVENT, mapOf("key" to "health_steps")))))
    }

    @Test
    fun theMostFrequentHealthContextSetsTheInterval() {
        val plan = HealthPolling.plan(
            listOf(
                state("health_steps", "60"),
                state("battery_level", "15"),
                state("health_heart_rate", "30"),
            ),
        )

        assertEquals(HealthPollPlan(setOf(HealthMetric.STEPS, HealthMetric.HEART_RATE), 30L), plan)
    }

    @Test
    fun theIntervalStaysBetweenFifteenMinutesAndADay() {
        fun intervalFor(pollMinutes: String?) = HealthPolling.plan(listOf(state("health_steps", pollMinutes)))!!.intervalMinutes

        assertEquals(15L, intervalFor(null))
        assertEquals(15L, intervalFor(""))
        assertEquals(15L, intervalFor("soon"))
        assertEquals(15L, intervalFor("1"))
        assertEquals(15L, intervalFor("-30"))
        assertEquals(30L, intervalFor(" 30.5 "))
        assertEquals(240L, intervalFor("240"))
        assertEquals(1440L, intervalFor("1440"))
        assertEquals(1440L, intervalFor("100000"))
    }

    @Test
    fun keysAreMatchedTheWayStateMatchingReadsThem() {
        val plan = HealthPolling.plan(listOf(state(" Health_Sleep_Minutes ")))

        assertEquals(setOf(HealthMetric.SLEEP), plan?.metrics)
        assertNull(HealthMetric.forStateKey("health_sleep"))
        assertNull(HealthMetric.forStateKey(null))
    }

    @Test
    fun eachMetricIsReadOverItsOwnWindow() {
        val zone = ZoneId.of("America/New_York")
        val now = ZonedDateTime.of(2026, 11, 10, 14, 30, 0, 0, zone).toInstant()
        val midnight = ZonedDateTime.of(2026, 11, 10, 0, 0, 0, 0, zone).toInstant()

        assertEquals(midnight to now, HealthPolling.readRange(HealthMetric.STEPS, now, zone))
        assertEquals(midnight to now, HealthPolling.readRange(HealthMetric.EXERCISE, now, zone))
        assertEquals(now.minus(Duration.ofHours(1)) to now, HealthPolling.readRange(HealthMetric.HEART_RATE, now, zone))
        assertEquals(now.minus(Duration.ofHours(24)) to now, HealthPolling.readRange(HealthMetric.SLEEP, now, zone))
    }

    @Test
    fun todayStartsAtLocalMidnightAcrossADaylightSavingChange() {
        val zone = ZoneId.of("America/New_York")
        // Clocks fell back at 2:00 on Nov 1, 2026, so that day was 25 hours long.
        val now = ZonedDateTime.of(2026, 11, 1, 23, 0, 0, 0, zone).toInstant()

        val (start, end) = HealthPolling.readRange(HealthMetric.STEPS, now, zone)

        assertEquals(ZonedDateTime.of(2026, 11, 1, 0, 0, 0, 0, zone).toInstant(), start)
        assertEquals(Duration.ofHours(24), Duration.between(start, end))
    }

    @Test
    fun noWindowReachesPastTheThirtyDayHistoryLimit() {
        val zones = listOf("UTC", "America/New_York", "Pacific/Kiritimati", "Pacific/Pago_Pago", "Australia/Lord_Howe")
            .map(ZoneId::of)
        var now = Instant.parse("2026-01-01T00:00:00Z")
        repeat(400) { step ->
            now = now.plus(Duration.ofMinutes(1_093L + step % 7))
            zones.forEach { zone ->
                HealthMetric.entries.forEach { metric ->
                    val (start, end) = HealthPolling.readRange(metric, now, zone)
                    assertEquals(now, end)
                    assertFalse("$metric at $now in $zone", start.isAfter(end))
                    assertFalse("$metric at $now in $zone", Duration.between(start, end) > HealthPolling.HISTORY_LIMIT)
                }
            }
        }
    }

    @Test
    fun theHistoryLimitCutsAWindowThatWouldReachFurther() {
        val now = Instant.parse("2026-11-10T12:00:00Z")
        val limit = Instant.parse("2026-10-11T12:00:00Z")

        assertEquals(Duration.ofDays(30), HealthPolling.HISTORY_LIMIT)
        assertEquals(limit, HealthPolling.clampToHistory(now.minus(Duration.ofDays(45)), now))
        assertEquals(limit, HealthPolling.clampToHistory(limit.minusMillis(1), now))
        assertEquals(limit, HealthPolling.clampToHistory(limit, now))
        assertEquals(limit.plusMillis(1), HealthPolling.clampToHistory(limit.plusMillis(1), now))
    }

    @Test
    fun setupAsksForEachMetricAndForBackgroundReads() {
        assertEquals(
            setOf("android.permission.health.READ_STEPS", HealthPolling.BACKGROUND_READ_PERMISSION),
            HealthPolling.requestedPermissions(setOf(HealthMetric.STEPS)),
        )
        assertEquals(
            HealthMetric.entries.size + 1,
            HealthPolling.requestedPermissions(HealthMetric.entries.toSet()).size,
        )
    }

    @Test
    fun theMetricListSurvivesTheWorkerInputRoundTrip() {
        val metrics = setOf(HealthMetric.SLEEP, HealthMetric.STEPS)

        assertEquals(metrics, HealthPolling.decodeMetrics(HealthPolling.encodeMetrics(metrics)))
        assertEquals(emptySet<HealthMetric>(), HealthPolling.decodeMetrics(null))
        assertEquals(setOf(HealthMetric.EXERCISE), HealthPolling.decodeMetrics("EXERCISE, RETIRED_METRIC"))
    }

    @Test
    fun aRefusedReadClearsTheValueAndNamesTheGap() {
        val patch = healthStatePatch(
            mapOf(
                HealthMetric.STEPS to HealthReading.Value("8123"),
                HealthMetric.HEART_RATE to HealthReading.Denied,
                HealthMetric.SLEEP to HealthReading.Unavailable,
                HealthMetric.EXERCISE to HealthReading.Failed,
            ),
            deniedDetail = { "denied ${it.stateKey}" },
            unavailableDetail = "unavailable",
        )

        assertEquals(
            mapOf(
                "health_steps" to "8123",
                "_setup_health_steps" to "",
                "health_heart_rate" to "",
                "_setup_health_heart_rate" to "denied health_heart_rate",
                "health_sleep_minutes" to "",
                "_setup_health_sleep_minutes" to "unavailable",
            ),
            patch,
        )
    }

    @Test
    fun setupStateFollowsAvailabilityThenGrants() {
        val requested = HealthPolling.requestedPermissions(setOf(HealthMetric.STEPS))
        val readOnly = setOf("android.permission.health.READ_STEPS")

        assertEquals(
            HealthSetupState.UNAVAILABLE,
            HealthSetup.state(HealthConnectAvailability.UNAVAILABLE, true, requested, requested),
        )
        assertEquals(
            HealthSetupState.NEEDS_UPDATE,
            HealthSetup.state(HealthConnectAvailability.NEEDS_UPDATE, true, requested, requested),
        )
        assertEquals(
            HealthSetupState.NEEDS_UPDATE,
            HealthSetup.state(HealthConnectAvailability.AVAILABLE, false, requested, requested),
        )
        assertEquals(
            HealthSetupState.MISSING,
            HealthSetup.state(HealthConnectAvailability.AVAILABLE, true, readOnly, requested),
        )
        assertEquals(
            HealthSetupState.GRANTED,
            HealthSetup.state(HealthConnectAvailability.AVAILABLE, true, requested + "extra", requested),
        )
    }
}
