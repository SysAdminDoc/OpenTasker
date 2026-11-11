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
import org.junit.Assert.assertTrue
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

    @Test
    fun recordQueriesReachBackForSessionsThatStartedEarlier() {
        val zone = ZoneId.of("America/New_York")
        val now = ZonedDateTime.of(2026, 11, 10, 14, 30, 0, 0, zone).toInstant()
        val midnight = ZonedDateTime.of(2026, 11, 10, 0, 0, 0, 0, zone).toInstant()

        assertEquals(now.minus(Duration.ofHours(36)), HealthPolling.queryStart(HealthMetric.SLEEP, now, zone))
        assertEquals(now.minus(Duration.ofHours(6)), HealthPolling.queryStart(HealthMetric.HEART_RATE, now, zone))
        assertEquals(midnight, HealthPolling.queryStart(HealthMetric.STEPS, now, zone))
        assertEquals(midnight, HealthPolling.queryStart(HealthMetric.EXERCISE, now, zone))
    }

    @Test
    fun aNightThatStartedBeforeTheWindowCountsWhole() {
        val windowEnd = Instant.parse("2026-11-10T14:00:00Z")
        val windowStart = windowEnd.minus(Duration.ofHours(24))
        // Started 2 hours before the window and ended inside it: 8 hours, 30 minutes of it awake.
        val lastNight = SleepSpan(
            start = windowStart.minus(Duration.ofHours(2)),
            end = windowStart.plus(Duration.ofHours(6)),
            awake = Duration.ofMinutes(30),
        )
        // Ended before the window, so it belongs to the day before.
        val nightBefore = SleepSpan(windowStart.minus(Duration.ofHours(10)), windowStart.minus(Duration.ofHours(2)))
        // Still running past the window's end, so it hasn't ended in it.
        val stillAsleep = SleepSpan(windowEnd.minus(Duration.ofHours(1)), windowEnd.plus(Duration.ofHours(1)))
        val nap = SleepSpan(windowEnd.minus(Duration.ofHours(3)), windowEnd.minus(Duration.ofHours(2)))

        assertEquals(450L + 60L, HealthPolling.sleepMinutes(listOf(lastNight, nightBefore, stillAsleep, nap), windowStart, windowEnd))
        assertEquals(60L, HealthPolling.sleepMinutes(listOf(nap, nightBefore), windowStart, windowEnd))
    }

    @Test
    fun noSessionInTheWindowIsNoReadingRatherThanNoSleep() {
        val windowEnd = Instant.parse("2026-11-10T14:00:00Z")
        val windowStart = windowEnd.minus(Duration.ofHours(24))
        val nightBefore = SleepSpan(windowStart.minus(Duration.ofHours(10)), windowStart)

        assertNull(HealthPolling.sleepMinutes(emptyList(), windowStart, windowEnd))
        assertNull(HealthPolling.sleepMinutes(listOf(nightBefore), windowStart, windowEnd))
        // A session whose stages are all awake is a real reading of zero.
        val awakeAllNight = SleepSpan(windowStart.plus(Duration.ofHours(1)), windowStart.plus(Duration.ofHours(2)), Duration.ofHours(3))
        assertEquals(0L, HealthPolling.sleepMinutes(listOf(awakeAllNight), windowStart, windowEnd))
    }

    @Test
    fun aNightLoggedByTwoSourcesCountsOnce() {
        val windowEnd = Instant.parse("2026-11-10T14:00:00Z")
        val windowStart = windowEnd.minus(Duration.ofHours(24))
        fun at(time: String) = Instant.parse("2026-11-10T${time}:00Z")
        val awakeAt3 = listOf(at("03:00") to at("03:30"))
        // 23:00 to 7:00 and 23:30 to 6:30, the night before.
        val watch = SleepSpan(at("00:00").minus(Duration.ofHours(1)), at("07:00"))
        val phone = SleepSpan(at("00:00").minus(Duration.ofMinutes(30)), at("06:30"))

        // The same 8 hours from a watch and a phone app is 8 hours, not 16.
        assertEquals(480L, HealthPolling.sleepMinutes(listOf(watch, watch.copy()), windowStart, windowEnd))
        // Both woke at 3:00 for half an hour; the watch also caught 23:00 to 23:30 and 6:30 to 7:00.
        val watchStaged = watch.copy(awake = Duration.ofMinutes(30), awakeSpans = awakeAt3)
        val phoneStaged = phone.copy(awake = Duration.ofMinutes(30), awakeSpans = awakeAt3)
        assertEquals(450L, HealthPolling.sleepMinutes(listOf(phoneStaged, watchStaged), windowStart, windowEnd))
        // A nap that overlaps neither still adds.
        val nap = SleepSpan(at("13:00"), at("13:45"))
        assertEquals(450L + 45L, HealthPolling.sleepMinutes(listOf(watchStaged, nap, phoneStaged), windowStart, windowEnd))
        // Sessions that only touch don't overlap, so both count whole.
        val firstHalf = SleepSpan(at("00:00"), at("03:00"))
        val secondHalf = SleepSpan(at("03:00"), at("06:00"))
        assertEquals(360L, HealthPolling.sleepMinutes(listOf(firstHalf, secondHalf), windowStart, windowEnd))
        // Awake time with no place can't be merged, so the session with the most sleep stands in.
        val unplaced = watch.copy(awake = Duration.ofMinutes(30))
        assertEquals(450L, HealthPolling.sleepMinutes(listOf(unplaced, phone.copy(awake = Duration.ofMinutes(90))), windowStart, windowEnd))
    }

    @Test
    fun aSessionWithNoStagesDoesNotHideAnotherSourcesWaking() {
        val windowEnd = Instant.parse("2026-11-10T14:00:00Z")
        val windowStart = windowEnd.minus(Duration.ofHours(24))
        fun at(time: String) = Instant.parse("2026-11-10T${time}:00Z")
        // The watch logs 23:00 to 7:00 with half an hour awake at 3:00, 450 minutes asleep.
        val watch = SleepSpan(
            at("00:00").minus(Duration.ofHours(1)),
            at("07:00"),
            awake = Duration.ofMinutes(30),
            awakeSpans = listOf(at("03:00") to at("03:30")),
        )
        // A phone app logs 23:30 to 6:30 as one block with no stages.
        val phone = SleepSpan(at("00:00").minus(Duration.ofMinutes(30)), at("06:30"))

        assertEquals(450L, HealthPolling.sleepMinutes(listOf(phone, watch), windowStart, windowEnd))
        // The phone's hour before the watch started is still sleep nobody else logged.
        val earlyPhone = SleepSpan(at("00:00").minus(Duration.ofHours(2)), at("06:30"))
        assertEquals(450L + 60L, HealthPolling.sleepMinutes(listOf(watch, earlyPhone), windowStart, windowEnd))
        // A staged night with no awake stage is still staged; a block over it adds nothing.
        val calmWatch = SleepSpan(at("00:00"), at("06:00"), hasStages = true)
        assertEquals(360L, HealthPolling.sleepMinutes(listOf(calmWatch, SleepSpan(at("00:30"), at("05:00"))), windowStart, windowEnd))
    }

    @Test
    fun theNewestHeartRateWinsAcrossRecords() {
        val windowEnd = Instant.parse("2026-11-10T14:00:00Z")
        val windowStart = windowEnd.minus(Duration.ofHours(1))
        val samples = listOf(
            windowEnd.minus(Duration.ofMinutes(50)) to 61L,
            // From a series record that started hours ago and is still running.
            windowEnd.minus(Duration.ofMinutes(2)) to 74L,
            windowEnd.minus(Duration.ofMinutes(20)) to 68L,
            windowStart.minus(Duration.ofMinutes(1)) to 120L,
            windowEnd.plus(Duration.ofMinutes(1)) to 130L,
        )

        assertEquals(74L, HealthPolling.newestSample(samples, windowStart, windowEnd))
        assertNull(HealthPolling.newestSample(samples.takeLast(2), windowStart, windowEnd))
        assertNull(HealthPolling.newestSample(emptyList(), windowStart, windowEnd))
    }

    @Test
    fun theMidnightReadLandsAMinutePastLocalMidnight() {
        val zone = ZoneId.of("America/New_York")
        val now = ZonedDateTime.of(2026, 11, 10, 23, 30, 0, 0, zone).toInstant()

        assertEquals(Duration.ofMinutes(31), HealthPolling.untilMidnightRead(now, zone))
        // Clocks fell back at 2:00 on Nov 1, 2026, so that day ran 25 hours.
        val fallBackDay = ZonedDateTime.of(2026, 11, 1, 0, 30, 0, 0, zone).toInstant()
        assertEquals(Duration.ofHours(24).plusMinutes(31), HealthPolling.untilMidnightRead(fallBackDay, zone))
        assertTrue(HealthMetric.STEPS.resetsAtMidnight && HealthMetric.EXERCISE.resetsAtMidnight)
        assertFalse(HealthMetric.HEART_RATE.resetsAtMidnight || HealthMetric.SLEEP.resetsAtMidnight)
    }

    @Test
    fun anInvertedHealthContextKnowsWhenItsReadingIsMissing() {
        val steps = state("health_steps")

        assertTrue(HealthPolling.readingMissing(steps, emptyMap()))
        assertTrue(HealthPolling.readingMissing(steps, mapOf("health_steps" to " ")))
        assertFalse(HealthPolling.readingMissing(steps, mapOf("health_steps" to "0")))
        // Other STATE keys and other context types never count as missing a health reading.
        assertFalse(HealthPolling.readingMissing(state("battery_level"), emptyMap()))
        assertFalse(HealthPolling.readingMissing(ContextSpec(ContextType.EVENT, mapOf("key" to "health_steps")), emptyMap()))
    }

    @Test
    fun aSecondFailedReadInARowClearsTheValue() {
        val readings = mapOf(HealthMetric.STEPS to HealthReading.Failed, HealthMetric.SLEEP to HealthReading.Failed)

        val patch = healthStatePatch(
            readings,
            { "" },
            "",
            failedBefore = setOf(HealthMetric.SLEEP),
            failedDetail = { "failed ${it.stateKey}" },
        )

        // The marker tells the Inspector why the context stopped matching.
        assertEquals(mapOf("health_sleep_minutes" to "", "_setup_health_sleep_minutes" to "failed health_sleep_minutes"), patch)
    }

    @Test
    fun setupAsksForTheTemplatesReadingsAlongWithThePlans() {
        val template = HealthPolling.metrics(listOf(state("health_sleep_minutes"), state("battery_level")))

        assertEquals(setOf(HealthMetric.SLEEP), template)
        assertEquals(
            setOf(HealthMetric.STEPS, HealthMetric.SLEEP),
            HealthSetup.requestedMetrics(setOf(HealthMetric.STEPS), template),
        )
        assertEquals(setOf(HealthMetric.SLEEP), HealthSetup.requestedMetrics(null, template))
        assertEquals(HealthMetric.entries.toSet(), HealthSetup.requestedMetrics(null, emptySet()))
        assertEquals(HealthMetric.entries.toSet(), HealthSetup.requestedMetrics(emptySet(), emptySet()))
    }

    @Test
    fun builtInHealthConnectUpdatesWithTheSystem() {
        assertFalse(HealthSetup.updatesWithSystem(28))
        assertFalse(HealthSetup.updatesWithSystem(33))
        assertTrue(HealthSetup.updatesWithSystem(34))
        assertTrue(HealthSetup.updatesWithSystem(36))
    }
}
