package com.opentasker.core.contexts

import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextMatchEvaluatorTest {
    @Test
    fun applicationContextUsesRegisteredAppSourceAndConfiguredPackage() {
        val spec = ContextSpec(
            type = ContextType.APPLICATION,
            config = mapOf("package" to "com.example.target"),
        )

        assertEquals("app", ContextMatchEvaluator.sourceKey(ContextType.APPLICATION))
        assertTrue(
            ContextMatchEvaluator.matches(
                spec,
                ContextEvent("app", matched = true, metadata = mapOf("foreground" to "com.example.target")),
            )
        )
        assertFalse(
            ContextMatchEvaluator.matches(
                spec,
                ContextEvent("app", matched = true, metadata = mapOf("foreground" to "com.example.other")),
            )
        )
    }

    @Test
    fun applicationContextOptionallyMatchesExactOrGlobComponentWithoutDegrading() {
        val exact = ContextSpec(
            type = ContextType.APPLICATION,
            config = mapOf("package" to "com.example.target", "component" to "com.example.PlayerActivity"),
        )
        val glob = exact.copy(config = exact.config + ("component" to "com.example.*"))
        val packageOnly = exact.copy(config = mapOf("package" to "com.example.target"))
        val matching = ContextEvent(
            "app",
            matched = true,
            metadata = mapOf("foreground" to "com.example.target", "component" to "com.example.PlayerActivity"),
        )

        assertTrue(ContextMatchEvaluator.matches(exact, matching))
        assertTrue(ContextMatchEvaluator.matches(glob, matching))
        assertTrue(ContextMatchEvaluator.matches(packageOnly, matching.copy(metadata = mapOf("foreground" to "com.example.target"))))
        assertFalse(ContextMatchEvaluator.matches(exact, matching.copy(metadata = mapOf("foreground" to "com.example.target"))))
        assertFalse(ContextMatchEvaluator.matches(exact, matching.copy(metadata = mapOf("foreground" to "com.example.target", "component" to "com.example.OtherActivity"))))
    }

    @Test
    fun timeContextHonorsConfiguredWindowIncludingOvernightRanges() {
        val daytime = ContextSpec(ContextType.TIME, config = mapOf("start" to "09:00", "end" to "17:30"))
        val overnight = ContextSpec(ContextType.TIME, config = mapOf("start" to "22:00", "end" to "06:00"))

        assertTrue(ContextMatchEvaluator.matches(daytime, ContextEvent("time", true, mapOf("time" to "12:15"))))
        assertFalse(ContextMatchEvaluator.matches(daytime, ContextEvent("time", true, mapOf("time" to "18:00"))))
        assertTrue(ContextMatchEvaluator.matches(overnight, ContextEvent("time", true, mapOf("time" to "23:30"))))
        assertTrue(ContextMatchEvaluator.matches(overnight, ContextEvent("time", true, mapOf("time" to "05:30"))))
        assertFalse(ContextMatchEvaluator.matches(overnight, ContextEvent("time", true, mapOf("time" to "12:00"))))
    }

    @Test
    fun timeContextBoundedByGlobalsUsesTheValuesTheEngineRead() {
        val sun = ContextSpec(ContextType.TIME, config = mapOf("start" to "%Sunrise", "end" to "%Sunset"))
        fun at(time: String, vararg bounds: Pair<String, String>) = ContextEvent("time", true, mapOf("time" to time) + bounds)
        val read = arrayOf(TimeContextBounds.START_VALUE to "06:42", TimeContextBounds.END_VALUE to "16:51")

        assertTrue(ContextMatchEvaluator.matches(sun, at("12:00", *read)))
        assertFalse(ContextMatchEvaluator.matches(sun, at("17:30", *read)))
        assertFalse("a global holding no time keeps it off", ContextMatchEvaluator.matches(sun, at("12:00")))
        assertFalse(ContextMatchEvaluator.matches(sun, at("12:00", TimeContextBounds.START_VALUE to "06:42")))
    }

    @Test
    fun aTimeBoundWhoseGlobalHoldsNoTimeIsUnknownNotFalse() {
        val sun = ContextSpec(ContextType.TIME, config = mapOf("start" to "%Sunrise", "end" to "%Sunset"), invert = true)
        val read = mapOf(TimeContextBounds.START_VALUE to "06:42", TimeContextBounds.END_VALUE to "16:51")

        assertTrue(ContextMatchEvaluator.valueUnknown(sun, mapOf("time" to "12:00")))
        assertTrue(ContextMatchEvaluator.valueUnknown(sun, read - TimeContextBounds.END_VALUE))
        assertTrue(ContextMatchEvaluator.valueUnknown(sun, read + (TimeContextBounds.START_VALUE to "soon")))
        assertEquals(listOf("%Sunset"), TimeContextBounds.unresolvedBounds(sun, read - TimeContextBounds.END_VALUE))
        assertFalse(ContextMatchEvaluator.valueUnknown(sun, read))
        assertFalse(ContextMatchEvaluator.valueUnknown(ContextSpec(ContextType.TIME, mapOf("start" to "09:00", "end" to "17:00")), emptyMap()))
    }

    @Test
    fun locationContextUsesFossGeofenceAccuracyAndDwell() {
        val spec = ContextSpec(
            type = ContextType.LOCATION,
            config = mapOf(
                "latitude" to "40.7580",
                "longitude" to "-73.9855",
                "radiusMeters" to "150",
                "maxAccuracyMeters" to "50",
                "dwellSeconds" to "300",
            ),
        )

        val matchingEvent = ContextEvent(
            "location",
            true,
            mapOf(
                "latitude" to "40.7581",
                "longitude" to "-73.9856",
                "accuracyMeters" to "25",
                "observedAtEpochMs" to "600000",
                "insideSinceEpochMs" to "0",
            ),
        )
        val inaccurateEvent = matchingEvent.copy(metadata = matchingEvent.metadata + ("accuracyMeters" to "75"))
        val noDwellEvent = matchingEvent.copy(metadata = matchingEvent.metadata + ("insideSinceEpochMs" to "400000"))

        assertTrue(ContextMatchEvaluator.matches(spec, matchingEvent))
        assertFalse(ContextMatchEvaluator.matches(spec, inaccurateEvent))
        assertFalse(ContextMatchEvaluator.matches(spec, noDwellEvent))
    }

    @Test
    fun dayContextMatchesConfiguredDayTokens() {
        val spec = ContextSpec(ContextType.DAY, config = mapOf("days" to "MON-WED,FRI"))

        assertTrue(ContextMatchEvaluator.matches(spec, ContextEvent("time", true, mapOf("day" to "wed"))))
        assertTrue(ContextMatchEvaluator.matches(spec, ContextEvent("time", true, mapOf("day" to "1"))))
        assertFalse(ContextMatchEvaluator.matches(spec, ContextEvent("time", true, mapOf("day" to "SUN"))))
    }

    @Test
    fun stateContextAppliesConfiguredKeyValueAndNumericPredicates() {
        val keyValue = ContextSpec(ContextType.STATE, config = mapOf("key" to "charging", "value" to "true"))
        val predicate = ContextSpec(ContextType.STATE, config = mapOf("predicate" to "battery_level>=80"))
        val operatorValue = ContextSpec(
            ContextType.STATE,
            config = mapOf("key" to "battery", "operator" to "<=", "value" to "90"),
        )
        val event = ContextEvent(
            "state",
            true,
            metadata = mapOf("charging" to "true", "battery_level" to "85"),
        )

        assertTrue(ContextMatchEvaluator.matches(keyValue, event))
        assertTrue(ContextMatchEvaluator.matches(predicate, event))
        assertTrue(ContextMatchEvaluator.matches(operatorValue, event))
        assertFalse(ContextMatchEvaluator.matches(predicate, event.copy(metadata = mapOf("battery_level" to "20"))))
    }

    @Test
    fun stateContextMatchesMergedMultiFactStateAndDocumentedAliases() {
        val headphones = ContextSpec(ContextType.STATE, config = mapOf("key" to "headset", "value" to "connected"))
        val charging = ContextSpec(ContextType.STATE, config = mapOf("key" to "charging", "value" to "plugged"))
        val screen = ContextSpec(ContextType.STATE, config = mapOf("predicate" to "screen=on"))
        val event = ContextEvent(
            "state",
            true,
            metadata = mapOf(
                "headphones" to "true",
                "charging" to "true",
                "screen" to "on",
                "battery_level" to "35",
            ),
        )

        assertTrue(ContextMatchEvaluator.matches(headphones, event))
        assertTrue(ContextMatchEvaluator.matches(charging, event))
        assertTrue(ContextMatchEvaluator.matches(screen, event))
    }

    @Test
    fun stateNumericPredicatesFailClosedForMalformedThresholds() {
        val event = mapOf("battery_level" to "15")

        assertFalse(stateMatches("battery_level<not-a-number", event))
        assertFalse(stateMatches("battery_level>=not-a-number", event))
    }

    @Test
    fun eventContextMatchesTypeAndOptionalFilter() {
        val spec = ContextSpec(ContextType.EVENT, config = mapOf("event" to "boot_completed", "filter" to "boot"))

        assertTrue(
            ContextMatchEvaluator.matches(
                spec,
                ContextEvent("event", true, mapOf("event" to "boot_completed")),
            )
        )
        assertFalse(
            ContextMatchEvaluator.matches(
                spec,
                ContextEvent("event", true, mapOf("event" to "notification")),
            )
        )
    }

    @Test
    fun notificationEventMatchesPackageTitleBodyAndRegexFilters() {
        val event = ContextEvent(
            "event",
            true,
            mapOf(
                "event" to "notification",
                "package" to "com.chat.example",
                "title" to "Build finished",
                "body" to "Debug APK is ready",
            ),
        )
        val containsSpec = ContextSpec(
            ContextType.EVENT,
            config = mapOf(
                "event" to "notification",
                "package" to "com.chat.example,com.mail.example",
                "title" to "build",
                "body" to "apk",
            ),
        )
        val regexSpec = ContextSpec(
            ContextType.EVENT,
            config = mapOf(
                "event" to "notification",
                "filter" to "debug\\s+apk",
                "regex" to "true",
            ),
        )
        val wrongPackage = containsSpec.copy(config = containsSpec.config + ("package" to "com.other"))

        assertTrue(ContextMatchEvaluator.matches(containsSpec, event))
        assertTrue(ContextMatchEvaluator.matches(regexSpec, event))
        assertFalse(ContextMatchEvaluator.matches(wrongPackage, event))
    }

    @Test
    fun notificationRegexFiltersFailClosedWhenInvalidOrTooLarge() {
        val event = ContextEvent(
            "event",
            true,
            mapOf("event" to "notification", "title" to "Hello"),
        )

        assertFalse(
            ContextMatchEvaluator.matches(
                ContextSpec(ContextType.EVENT, mapOf("event" to "notification", "filter" to "[", "regex" to "true")),
                event,
            )
        )
        assertFalse(
            ContextMatchEvaluator.matches(
                ContextSpec(ContextType.EVENT, mapOf("event" to "notification", "filter" to "a".repeat(161), "regex" to "true")),
                event,
            )
        )
    }

    @Test
    fun nfcEventMatchesNormalizedTagIds() {
        val event = ContextEvent(
            "event",
            true,
            mapOf("event" to "nfc", "tagId" to "04AABBCC"),
        )
        val matchingSpec = ContextSpec(
            ContextType.EVENT,
            mapOf("event" to "nfc", "tagId" to "04:aa-bb cc, 01020304"),
        )
        val wrongTagSpec = matchingSpec.copy(config = matchingSpec.config + ("tagId" to "01020304"))

        assertTrue(ContextMatchEvaluator.matches(matchingSpec, event))
        assertFalse(ContextMatchEvaluator.matches(wrongTagSpec, event))
    }

    @Test
    fun calendarEventMatchesCalendarStateAndBeforeWindowFilters() {
        val event = ContextEvent(
            "event",
            true,
            mapOf(
                "event" to "calendar",
                "state" to "upcoming",
                "calendar" to "Work",
                "minutesUntilStart" to "10",
            ),
        )
        val matchingSpec = ContextSpec(
            ContextType.EVENT,
            mapOf("event" to "calendar", "state" to "upcoming", "calendar" to "work", "beforeMinutes" to "15"),
        )
        val tooLateSpec = matchingSpec.copy(config = matchingSpec.config + ("beforeMinutes" to "5"))
        val wrongCalendarSpec = matchingSpec.copy(config = matchingSpec.config + ("calendar" to "Personal"))

        assertTrue(ContextMatchEvaluator.matches(matchingSpec, event))
        assertFalse(ContextMatchEvaluator.matches(tooLateSpec, event))
        assertFalse(ContextMatchEvaluator.matches(wrongCalendarSpec, event))
    }

    @Test
    fun sunEventsMatchConfiguredOffsetWindow() {
        val event = ContextEvent(
            "event",
            true,
            mapOf(
                "event" to "sun_tick",
                "date" to "2026-06-21",
                "time" to "05:27",
                "zone" to "America/New_York",
            ),
        )
        val matchingSpec = ContextSpec(
            ContextType.EVENT,
            mapOf(
                "event" to "sunrise",
                "latitude" to "40.7128",
                "longitude" to "-74.0060",
                "windowMinutes" to "5",
            ),
        )
        val offsetSpec = matchingSpec.copy(config = matchingSpec.config + ("offsetMinutes" to "30"))
        val wrongEventSpec = matchingSpec.copy(config = matchingSpec.config + ("event" to "sunset"))

        assertTrue(ContextMatchEvaluator.matches(matchingSpec, event))
        assertFalse(ContextMatchEvaluator.matches(offsetSpec, event))
        assertFalse(ContextMatchEvaluator.matches(wrongEventSpec, event))
    }

    @Test
    fun pluginContextSourceKeyIsPlugin() {
        assertEquals("plugin", ContextMatchEvaluator.sourceKey(ContextType.PLUGIN))
    }

    @Test
    fun pluginContextMatchesSatisfiedResultForConfiguredPackage() {
        val spec = ContextSpec(
            ContextType.PLUGIN,
            mapOf("package" to "com.example.plugin", "bundleJson" to "{\"key\":\"val\"}"),
        )
        val satisfied = ContextEvent("plugin", true, mapOf(
            "package" to "com.example.plugin",
            "bundleJson" to "{\"key\":\"val\"}",
            "state" to "satisfied",
        ))
        assertTrue(ContextMatchEvaluator.matches(spec, satisfied))
    }

    @Test
    fun pluginContextRejectsUnsatisfiedState() {
        val spec = ContextSpec(
            ContextType.PLUGIN,
            mapOf("package" to "com.example.plugin"),
        )
        val unsatisfied = ContextEvent("plugin", true, mapOf(
            "package" to "com.example.plugin",
            "bundleJson" to "{}",
            "state" to "unsatisfied",
        ))
        assertFalse(ContextMatchEvaluator.matches(spec, unsatisfied))
    }

    @Test
    fun pluginContextRejectsWrongPackage() {
        val spec = ContextSpec(
            ContextType.PLUGIN,
            mapOf("package" to "com.example.plugin"),
        )
        val wrongPackage = ContextEvent("plugin", true, mapOf(
            "package" to "com.other.plugin",
            "bundleJson" to "{}",
            "state" to "satisfied",
        ))
        assertFalse(ContextMatchEvaluator.matches(spec, wrongPackage))
    }

    @Test
    fun pluginContextRejectsWrongBundleJson() {
        val spec = ContextSpec(
            ContextType.PLUGIN,
            mapOf("package" to "com.example.plugin", "bundleJson" to "{\"a\":\"1\"}"),
        )
        val wrongBundle = ContextEvent("plugin", true, mapOf(
            "package" to "com.example.plugin",
            "bundleJson" to "{\"b\":\"2\"}",
            "state" to "satisfied",
        ))
        assertFalse(ContextMatchEvaluator.matches(spec, wrongBundle))
    }

    @Test
    fun pluginContextDefaultsBundleJsonToEmptyObject() {
        val spec = ContextSpec(ContextType.PLUGIN, mapOf("package" to "com.example.plugin"))
        val event = ContextEvent("plugin", true, mapOf(
            "package" to "com.example.plugin",
            "bundleJson" to "{}",
            "state" to "satisfied",
        ))
        assertTrue(ContextMatchEvaluator.matches(spec, event))
    }

    @Test
    fun pluginContextRejectsBlankPackage() {
        val spec = ContextSpec(ContextType.PLUGIN, mapOf("package" to ""))
        val event = ContextEvent("plugin", true, mapOf(
            "package" to "",
            "state" to "satisfied",
        ))
        assertFalse(ContextMatchEvaluator.matches(spec, event))
    }

    @Test
    fun pluginContextInvertMatchesUnsatisfied() {
        val spec = ContextSpec(
            ContextType.PLUGIN,
            mapOf("package" to "com.example.plugin"),
            invert = true,
        )
        val unsatisfied = ContextEvent("plugin", true, mapOf(
            "package" to "com.example.plugin",
            "bundleJson" to "{}",
            "state" to "unsatisfied",
        ))
        assertFalse(ContextMatchEvaluator.matches(spec, unsatisfied))
    }

    @Test
    fun pluginEventAddressingIsolatesSubscriptions() {
        val spec = ContextSpec(
            ContextType.PLUGIN,
            mapOf("package" to "com.example.plugin", "bundleJson" to "{\"a\":\"1\"}"),
        )
        val addressed = ContextEvent("plugin", true, mapOf(
            "package" to "COM.EXAMPLE.PLUGIN",
            "bundleJson" to "{\"a\":\"1\"}",
            "state" to "satisfied",
        ))
        val otherPackage = ContextEvent("plugin", true, mapOf(
            "package" to "com.other.plugin",
            "bundleJson" to "{\"a\":\"1\"}",
            "state" to "satisfied",
        ))
        val otherBundle = ContextEvent("plugin", true, mapOf(
            "package" to "com.example.plugin",
            "bundleJson" to "{\"a\":\"2\"}",
            "state" to "satisfied",
        ))

        assertTrue(ContextMatchEvaluator.pluginEventAddressesSpec(spec, addressed))
        assertFalse(ContextMatchEvaluator.pluginEventAddressesSpec(spec, otherPackage))
        assertFalse(ContextMatchEvaluator.pluginEventAddressesSpec(spec, otherBundle))
    }

    @Test
    fun sunTickPulseNeverMatchesGenericEventSpecs() {
        val sunTick = ContextEvent("event", true, mapOf(
            "event" to "sun_tick",
            "date" to "2026-06-21",
            "time" to "05:27",
            "zone" to "America/New_York",
        ))

        // Blank event + blank filter (only reachable via imported bundles) must not
        // fire on the internal minute pulse.
        assertFalse(
            ContextMatchEvaluator.matches(ContextSpec(ContextType.EVENT, emptyMap()), sunTick)
        )
        // A filter-only spec must not match against the pulse's date/time metadata.
        assertFalse(
            ContextMatchEvaluator.matches(
                ContextSpec(ContextType.EVENT, mapOf("filter" to "2026")),
                sunTick,
            )
        )
        // A named non-sun event spec must not match the pulse either.
        assertFalse(
            ContextMatchEvaluator.matches(
                ContextSpec(ContextType.EVENT, mapOf("event" to "sun_tick")),
                sunTick,
            )
        )
    }

    @Test
    fun blankEventAndFilterSpecFailsClosedForRealEvents() {
        val bootEvent = ContextEvent("event", true, mapOf("event" to "boot_completed"))

        assertFalse(
            ContextMatchEvaluator.matches(ContextSpec(ContextType.EVENT, emptyMap()), bootEvent)
        )
        // A filter-only spec still matches real events on their metadata.
        assertTrue(
            ContextMatchEvaluator.matches(
                ContextSpec(ContextType.EVENT, mapOf("filter" to "boot_completed")),
                bootEvent,
            )
        )
    }
}
