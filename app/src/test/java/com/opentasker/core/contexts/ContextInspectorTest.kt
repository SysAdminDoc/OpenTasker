package com.opentasker.core.contexts

import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextBooleanOperator
import com.opentasker.core.model.ContextExpressionNode
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.core.model.ProfileLifetime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextInspectorTest {
    @Test
    fun observationHealthExposesLoadingReadyStaleAndErrorWithAge() {
        val loading = ContextSourceSnapshot(key = "event", label = "System event", registered = true)
        val ready = loading.copy(
            lastObservation = ContextEventObservation(
                ContextEvent("event", true, mapOf("event" to "calendar")),
                observedAtMs = 100_000L,
            ),
        )
        val error = loading.copy(error = "source stopped")

        assertEquals(ContextObservationStatus.Loading, loading.observationStatus(100_000L))
        assertEquals(ContextObservationStatus.Ready, ready.observationStatus(110_000L))
        assertEquals(10_000L, ready.observationAgeMs(110_000L))
        assertEquals(ContextObservationStatus.Stale, ready.observationStatus(100_000L + CONTEXT_OBSERVATION_STALE_AFTER_MS + 1))
        assertEquals(ContextObservationStatus.Error, error.observationStatus(100_000L))
    }

    @Test
    fun profileInspectionMatchesWhenAllContextsMatchLatestValues() {
        val profile = Profile(
            id = 1,
            name = "Work hours",
            enabled = true,
            enterTaskId = 10,
            contexts = listOf(
                ContextSpec(ContextType.TIME, mapOf("start" to "09:00", "end" to "17:00")),
                ContextSpec(ContextType.DAY, mapOf("days" to "MON,WED,FRI")),
            ),
        )
        val source = ContextSourceSnapshot(
            key = "time",
            label = "Time and day",
            registered = true,
            lastObservation = ContextEventObservation(
                ContextEvent("time", true, mapOf("time" to "10:30", "day" to "MON")),
                observedAtMs = 1000,
            ),
        )

        val result = inspectProfiles(listOf(profile), listOf(source)).single()

        assertTrue(result.matching)
        assertEquals("All contexts currently match.", result.summary)
        assertEquals(listOf(true, true), result.contexts.map { it.effectiveMatched })
    }

    @Test
    fun profileInspectionExplainsPrioritySuppressionAndExpiry() {
        val source = ContextSourceSnapshot(
            key = "state",
            label = "Device state",
            registered = true,
            lastObservation = ContextEventObservation(
                ContextEvent("state", true, mapOf("charging" to "true")),
                observedAtMs = 1000L,
            ),
        )
        val lower = Profile(
            id = 2,
            name = "Lower",
            enterTaskId = 1,
            priority = 1,
            contexts = listOf(ContextSpec(ContextType.STATE, mapOf("key" to "charging", "value" to "true"))),
        )
        val higher = lower.copy(id = 3, name = "Higher", priority = 5)
        val expired = lower.copy(
            id = 4,
            name = "Expired",
            lifetime = ProfileLifetime.UNTIL_DATE,
            expiresAtMs = 999L,
        )

        val results = inspectProfiles(listOf(lower, higher, expired), listOf(source), nowMs = 1000L)
            .associateBy { it.profileId }

        assertTrue(results.getValue(higher.id).matching)
        assertFalse(results.getValue(lower.id).matching)
        assertTrue(results.getValue(lower.id).suppressionReason.orEmpty().contains("higher-priority"))
        assertFalse(results.getValue(expired.id).matching)
        assertTrue(results.getValue(expired.id).summary.contains("expired"))
    }

    @Test
    fun profileInspectionExplainsAndEvaluatesNestedContextExpression() {
        val profile = Profile(
            id = 11,
            name = "Nested state",
            enabled = true,
            enterTaskId = 10,
            contexts = listOf(
                ContextSpec(ContextType.STATE, mapOf("key" to "charging", "value" to "true")),
                ContextSpec(ContextType.STATE, mapOf("key" to "screen", "value" to "on")),
                ContextSpec(ContextType.STATE, mapOf("predicate" to "battery_level>=80")),
            ),
            contextExpression = ContextExpressionNode.group(
                ContextBooleanOperator.OR,
                listOf(
                    ContextExpressionNode.group(
                        ContextBooleanOperator.AND,
                        listOf(ContextExpressionNode.leaf(0), ContextExpressionNode.leaf(1)),
                    ),
                    ContextExpressionNode(contextIndex = 2, invert = true),
                ),
            ),
        )
        val source = ContextSourceSnapshot(
            key = "state",
            label = "Device state",
            registered = true,
            lastObservation = ContextEventObservation(
                ContextEvent("state", true, mapOf("charging" to "true", "screen" to "on", "battery_level" to "20")),
                observedAtMs = 1000,
            ),
        )

        val result = inspectProfiles(listOf(profile), listOf(source)).single()

        assertTrue(result.matching)
        assertTrue(result.logicExplanation.startsWith("ANY("))
        assertEquals(listOf(true, true, false), result.contexts.map { it.effectiveMatched })
    }

    @Test
    fun profileInspectionExplainsMissingSourceEvents() {
        val profile = Profile(
            id = 2,
            name = "Foreground app",
            enabled = true,
            enterTaskId = 10,
            contexts = listOf(ContextSpec(ContextType.APPLICATION, mapOf("package" to "com.example"))),
        )
        val source = ContextSourceSnapshot(
            key = "app",
            label = "Application",
            registered = true,
            setupReady = true,
        )

        val result = inspectProfiles(listOf(profile), listOf(source)).single()

        assertFalse(result.matching)
        assertEquals("Waiting for the first Application event.", result.summary)
        assertEquals(ContextSourceStatus.Waiting, result.contexts.single().sourceStatus)
    }

    @Test
    fun profileInspectionAppliesInvertedContextReasoning() {
        val profile = Profile(
            id = 3,
            name = "Not charging",
            enabled = true,
            enterTaskId = 10,
            contexts = listOf(
                ContextSpec(
                    type = ContextType.STATE,
                    config = mapOf("key" to "charging", "value" to "true"),
                    invert = true,
                ),
            ),
        )
        val source = ContextSourceSnapshot(
            key = "state",
            label = "Device state",
            registered = true,
            lastObservation = ContextEventObservation(
                ContextEvent("state", true, mapOf("charging" to "false")),
                observedAtMs = 1000,
            ),
        )

        val result = inspectProfiles(listOf(profile), listOf(source)).single()

        assertTrue(result.matching)
        assertTrue(result.contexts.single().effectiveMatched)
        assertEquals(
            "Latest value does not satisfy the configuration, so the inverted context matches.",
            result.contexts.single().reason,
        )
    }

    @Test
    fun sourceSnapshotPrioritizesSetupAndRegistrationHealth() {
        val setupRequired = ContextSourceSnapshot(
            key = "app",
            label = "Application",
            registered = true,
            setupReady = false,
            setupDetail = "Usage access is missing.",
        )
        val missing = ContextSourceSnapshot(
            key = "location",
            label = "Location",
            registered = false,
            setupReady = false,
        )

        assertEquals(ContextSourceStatus.NeedsSetup, setupRequired.status)
        assertEquals(ContextSourceStatus.Missing, missing.status)
    }

    @Test
    fun dayConfigSummaryUsesReadableScheduleLabels() {
        assertEquals(
            "Weekdays",
            contextConfigSummary(ContextSpec(ContextType.DAY, mapOf("days" to "MON-FRI"))),
        )
        assertEquals(
            "Weekends; inverted",
            contextConfigSummary(ContextSpec(ContextType.DAY, mapOf("days" to "weekends"), invert = true)),
        )
    }

    @Test
    fun setupRequiredSourceDoesNotCountAsMatchingProfile() {
        val profile = Profile(
            id = 4,
            name = "Blocked app",
            enabled = true,
            enterTaskId = 10,
            contexts = listOf(ContextSpec(ContextType.APPLICATION, mapOf("package" to "com.example"))),
        )
        val source = ContextSourceSnapshot(
            key = "app",
            label = "Application",
            registered = true,
            setupReady = false,
            setupDetail = "Usage access is missing.",
            lastObservation = ContextEventObservation(
                ContextEvent("app", true, mapOf("foreground" to "com.example")),
                observedAtMs = 1000,
            ),
        )

        val result = inspectProfiles(listOf(profile), listOf(source)).single()

        assertFalse(result.matching)
        assertFalse(result.contexts.single().effectiveMatched)
        assertEquals("Usage access is missing.", result.summary)
    }

    @Test
    fun eventContextReasonExplainsPulseSemantics() {
        val profile = Profile(
            id = 5,
            name = "NFC tap",
            enabled = true,
            enterTaskId = 10,
            contexts = listOf(ContextSpec(ContextType.EVENT, mapOf("event" to "nfc", "tagId" to "AABB"))),
        )
        val source = ContextSourceSnapshot(
            key = "event",
            label = "System event",
            registered = true,
            lastObservation = ContextEventObservation(
                ContextEvent("event", true, mapOf("event" to "nfc", "tagId" to "AA:BB")),
                observedAtMs = 1000,
            ),
        )

        val result = inspectProfiles(listOf(profile), listOf(source)).single()

        assertTrue(result.matching)
        assertEquals(
            "Latest event satisfies the configuration; event contexts are one-shot pulses and can trigger again on each matching event.",
            result.contexts.single().reason,
        )
    }

    @Test
    fun aWithheldNfcTagIsExplainedOnTheNfcContextAndNowhereElse() {
        val profile = Profile(
            id = 5,
            name = "Tag or meeting",
            enabled = true,
            enterTaskId = 10,
            contexts = listOf(
                ContextSpec(ContextType.EVENT, mapOf("event" to "NFC", "tagId" to "AABB"), orGroup = "any"),
                ContextSpec(ContextType.EVENT, mapOf("event" to "calendar"), orGroup = "any"),
            ),
        )
        val source = ContextSourceSnapshot(
            key = "event",
            label = "System event",
            registered = true,
            eventSetupGaps = mapOf("nfc" to "Allow NFC tags in Setup."),
        )

        val result = inspectProfiles(listOf(profile), listOf(source)).single()

        assertEquals(ContextSourceStatus.Waiting, source.status)
        assertEquals("Allow NFC tags in Setup.", result.contexts[0].reason)
        assertEquals("Waiting for the first System event event.", result.contexts[1].reason)
    }

    @Test
    fun stateSensorSetupMarkerPointsToSetupAndFailsClosed() {
        val profile = Profile(
            id = 6,
            name = "Walking",
            enabled = true,
            enterTaskId = 10,
            contexts = listOf(ContextSpec(ContextType.STATE, mapOf("key" to "activity", "value" to "walking"))),
        )
        val source = ContextSourceSnapshot(
            key = "state",
            label = "Device state",
            registered = true,
            lastObservation = ContextEventObservation(
                ContextEvent(
                    "state",
                    true,
                    mapOf("_setup_activity" to "Open Setup and grant Physical activity permission."),
                ),
                observedAtMs = 1000L,
            ),
        )

        val result = inspectProfiles(listOf(profile), listOf(source)).single()

        assertFalse(result.matching)
        assertFalse(result.contexts.single().effectiveMatched)
        assertEquals("Open Setup and grant Physical activity permission.", result.contexts.single().reason)
    }

    @Test
    fun aWithheldWifiNameIsExplainedForNameContextsButNotForConnectedContexts() {
        val withheld = DeviceStateEvents.wifiPatch(
            "Unknown",
            connected = true,
            ssidUnavailableReason = "Android hides the Wi-Fi name from apps in the background.",
        )
        val source = ContextSourceSnapshot(
            key = "state",
            label = "Device state",
            registered = true,
            lastObservation = ContextEventObservation(ContextEvent("state", true, withheld), observedAtMs = 1000L),
        )
        fun profile(value: String) = Profile(
            id = 7,
            name = "Wi-Fi $value",
            enabled = true,
            enterTaskId = 10,
            contexts = listOf(ContextSpec(ContextType.STATE, mapOf("key" to "wifi_ssid", "value" to value))),
        )

        val byName = inspectProfiles(listOf(profile("Home")), listOf(source)).single()
        assertFalse(byName.matching)
        assertEquals("Android hides the Wi-Fi name from apps in the background.", byName.contexts.single().reason)

        // Being connected needs no name, so its explanation must not blame the missing one.
        val connected = inspectProfiles(listOf(profile("connected")), listOf(source)).single()
        assertTrue(connected.matching)
        assertEquals("Latest value satisfies the configuration.", connected.contexts.single().reason)
    }

    @Test
    fun profileInspectionCanUseTransformedLocationDwellObservation() {
        val profile = Profile(
            id = 5,
            name = "Office dwell",
            enabled = true,
            enterTaskId = 10,
            contexts = listOf(
                ContextSpec(
                    ContextType.LOCATION,
                    mapOf(
                        "latitude" to "40.7580",
                        "longitude" to "-73.9855",
                        "radiusMeters" to "150",
                        "dwellSeconds" to "60",
                    ),
                ),
            ),
        )
        val source = ContextSourceSnapshot(
            key = "location",
            label = "Location",
            registered = true,
            setupReady = true,
            lastObservation = ContextEventObservation(
                ContextEvent(
                    "location",
                    true,
                    mapOf(
                        "latitude" to "40.7581",
                        "longitude" to "-73.9856",
                        "observedAtEpochMs" to "120000",
                    ),
                ),
                observedAtMs = 120_000L,
            ),
        )

        val result = inspectProfiles(listOf(profile), listOf(source)) { _, _, _, observation ->
            observation.copy(
                event = observation.event.copy(
                    metadata = observation.event.metadata + mapOf(
                        "insideSinceEpochMs" to "0",
                        "dwellState" to "inside",
                    ),
                ),
            )
        }.single()

        assertTrue(result.matching)
        assertEquals("inside", result.contexts.single().lastObservation?.event?.metadata?.get("dwellState"))
        assertEquals("0", result.contexts.single().lastObservation?.event?.metadata?.get("insideSinceEpochMs"))
    }

    @Test
    fun anInvertedTimeWindowStaysOffWhileItsGlobalHoldsNoTime() {
        val profile = Profile(
            id = 31,
            name = "Night",
            enterTaskId = 1,
            enabled = true,
            contexts = listOf(ContextSpec(ContextType.TIME, mapOf("start" to "%Sunrise", "end" to "%Sunset"), invert = true)),
        )
        val source = ContextSourceSnapshot(
            key = "time",
            label = "Time and day",
            registered = true,
            lastObservation = ContextEventObservation(ContextEvent("time", true, mapOf("time" to "22:00")), observedAtMs = 1000),
        )
        fun inspect(globals: Map<String, String>) = inspectProfiles(listOf(profile), listOf(source)) { _, _, spec, observation ->
            observation.copy(event = TimeContextBounds.withVariableValues(spec, observation.event, globals))
        }.single()

        assertFalse(inspect(emptyMap()).matching)
        assertFalse(inspect(mapOf("Sunrise" to "soon", "Sunset" to "18.10")).contexts.single().effectiveMatched)
        assertTrue(inspect(mapOf("Sunrise" to "6.42", "Sunset" to "18.10")).matching)
    }

    @Test
    fun aTimeWindowsReasonNamesTheGlobalThatHoldsNoTimeAndShowsTheTimesItRead() {
        fun reason(start: String, globals: Map<String, String>): String {
            val profile = Profile(
                id = 32,
                name = "Daylight",
                enterTaskId = 1,
                enabled = true,
                contexts = listOf(ContextSpec(ContextType.TIME, mapOf("start" to start, "end" to "%Sunset"))),
            )
            val source = ContextSourceSnapshot(
                key = "time",
                label = "Time and day",
                registered = true,
                lastObservation = ContextEventObservation(ContextEvent("time", true, mapOf("time" to "12:00")), observedAtMs = 1000),
            )
            return inspectProfiles(listOf(profile), listOf(source)) { _, _, spec, observation ->
                observation.copy(event = TimeContextBounds.withVariableValues(spec, observation.event, globals))
            }.single().contexts.single().reason
        }

        assertEquals(
            "%Sunrise doesn't hold a time yet, so this context stays off, inverted or not.",
            reason("%Sunrise", mapOf("Sunrise" to "soon", "Sunset" to "18.10")),
        )
        assertEquals(
            "%Sunrise and %Sunset don't hold a time yet, so this context stays off, inverted or not.",
            reason("%Sunrise", emptyMap()),
        )
        assertTrue(reason("%sunrise", mapOf("Sunset" to "18.10")).startsWith("%sunrise is a local variable"))
        assertTrue(reason("%1x", mapOf("Sunset" to "18.10")).startsWith("%1x isn't a variable name, so this context stays off."))
        assertEquals(
            "Latest value satisfies the configuration. Read %Sunrise as 06:42 and %Sunset as 18:10.",
            reason("%Sunrise", mapOf("Sunrise" to "6.42", "Sunset" to "18.10")),
        )
        assertEquals("Latest value satisfies the configuration. Read %Sunset as 18:10.", reason("06:00", mapOf("Sunset" to "18.10")))
    }

    @Test
    fun pluginSourceLabelResolvesCorrectly() {
        assertEquals("Plugin condition", "plugin".toContextSourceLabel())
    }

    @Test
    fun pluginContextConfigSummaryShowsPackageAndBlurb() {
        val specWithBlurb = ContextSpec(
            ContextType.PLUGIN,
            mapOf("package" to "com.example.plugin", "blurb" to "WiFi connected"),
        )
        assertEquals("com.example.plugin (WiFi connected)", contextConfigSummary(specWithBlurb))

        val specNoBlurb = ContextSpec(ContextType.PLUGIN, mapOf("package" to "com.example.plugin"))
        assertEquals("com.example.plugin", contextConfigSummary(specNoBlurb))
    }

    @Test
    fun pluginContextConfigSummaryShowsInvertedSuffix() {
        val spec = ContextSpec(
            ContextType.PLUGIN,
            mapOf("package" to "com.example.plugin"),
            invert = true,
        )
        assertEquals("com.example.plugin; inverted", contextConfigSummary(spec))
    }

    @Test
    fun aStateContextsSummaryReadsAsTheCheckItMakes() {
        fun summary(vararg config: Pair<String, String>, invert: Boolean = false) =
            contextConfigSummary(ContextSpec(ContextType.STATE, mapOf(*config), invert = invert))

        assertEquals(
            "Bluetooth device nearby = Car",
            summary("key" to "bt_near", "operator" to "=", "value" to "Car"),
        )
        assertEquals("call state = ringing", summary("predicate" to "call_state = ringing"))
        assertEquals("battery level >= 80; inverted", summary("key" to "battery", "operator" to ">=", "value" to "80", invert = true))
        // Anything the condition doesn't cover still shows, so nothing is hidden.
        assertEquals(
            "orientation = portrait, debounce=5",
            summary("key" to "orientation", "operator" to "=", "value" to "portrait", "debounce" to "5"),
        )
        // With no key there's no check to describe, so the raw entries stay.
        assertEquals("predicate=nonsense", summary("predicate" to "nonsense"))
        // The engine checks only the predicate when one is set, so key fields beside a bad one aren't the check.
        assertEquals(
            "key=screen, operator==, predicate=nonsense, value=on",
            summary("predicate" to "nonsense", "key" to "screen", "operator" to "=", "value" to "on"),
        )
    }

    @Test
    fun pluginContextInspectionShowsWaitingBeforeFirstEvent() {
        val profile = Profile(
            id = 30,
            name = "Plugin test",
            enterTaskId = 1,
            enabled = true,
            contexts = listOf(ContextSpec(ContextType.PLUGIN, mapOf("package" to "com.example.plugin"))),
        )
        val source = ContextSourceSnapshot(
            key = "plugin",
            label = "Plugin condition",
            registered = true,
        )
        val result = inspectProfiles(listOf(profile), listOf(source)).single()
        assertFalse(result.matching)
        val check = result.contexts.single()
        assertEquals("plugin", check.sourceKey)
        assertEquals(ContextSourceStatus.Waiting, check.sourceStatus)
    }
}
