package com.opentasker.core.contexts

import com.google.re2j.Pattern as Re2Pattern
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.location.FossGeofenceEvaluator
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar
import java.util.Locale

/**
 * Applies a persisted [ContextSpec] to raw context-source events.
 *
 * Context sources emit coarse device facts. Profiles store user intent. Keeping
 * this translation in one place prevents broad source events from accidentally
 * activating every profile of the same family.
 */
object ContextMatchEvaluator {
    fun sourceKey(type: ContextType): String? = when (type) {
        ContextType.APPLICATION -> "app"
        ContextType.TIME -> "time"
        ContextType.DAY -> "time"
        ContextType.LOCATION -> "location"
        ContextType.STATE -> "state"
        ContextType.EVENT -> "event"
        ContextType.PLUGIN -> "plugin"
    }

    fun matches(spec: ContextSpec, event: ContextEvent): Boolean {
        if (!event.matched) return false
        val expectedSource = sourceKey(spec.type) ?: return false
        if (event.type != expectedSource) return false

        return when (spec.type) {
            ContextType.APPLICATION -> matchesApplication(spec, event)
            ContextType.TIME -> matchesTime(spec, event)
            ContextType.DAY -> matchesDay(spec, event)
            ContextType.LOCATION -> matchesLocation(spec, event)
            ContextType.STATE -> matchesState(spec, event)
            ContextType.EVENT -> matchesEvent(spec, event)
            ContextType.PLUGIN -> matchesPlugin(spec, event)
        }
    }

    private fun matchesApplication(spec: ContextSpec, event: ContextEvent): Boolean {
        val foreground = event.metadata["foreground"].orEmpty().ifBlank { event.metadata["package"].orEmpty() }
        if (foreground.isBlank()) return false
        val configuredPackages = firstConfig(spec, "package", "packages", "apps")
            .splitCsv()
            .map { it.lowercase(Locale.US) }
        if (configuredPackages.isEmpty()) return false
        if (foreground.lowercase(Locale.US) !in configuredPackages) return false

        val configuredComponent = firstConfig(spec, "component", "activity", "class")
        if (configuredComponent.isBlank()) return true
        val observedComponent = event.metadata["component"].orEmpty()
        return ApplicationComponentMatcher.matches(configuredComponent, observedComponent)
    }

    private fun matchesTime(spec: ContextSpec, event: ContextEvent): Boolean {
        val start = firstConfig(spec, "start", "from").takeIf { it.isNotBlank() } ?: return false
        val end = firstConfig(spec, "end", "to").takeIf { it.isNotBlank() } ?: return false
        val current = event.metadata["time"]?.let(::parseClockMinutes) ?: currentMinuteOfDay()
        val startMinutes = parseClockMinutes(start) ?: return false
        val endMinutes = parseClockMinutes(end) ?: return false
        return minuteInWindow(current, startMinutes, endMinutes)
    }

    private fun matchesDay(spec: ContextSpec, event: ContextEvent): Boolean {
        val configuredDays = firstConfig(spec, "days", "day")
        if (configuredDays.isBlank()) return false
        val currentDay = event.metadata["day"] ?: DaySchedule.currentDayToken()
        return DaySchedule.matches(configuredDays, currentDay)
    }

    private fun matchesLocation(spec: ContextSpec, event: ContextEvent): Boolean {
        return FossGeofenceEvaluator.evaluate(spec.config, event.metadata)?.matches == true
    }

    private fun matchesState(spec: ContextSpec, event: ContextEvent): Boolean {
        val predicate = spec.config["predicate"]?.trim().orEmpty()
        if (predicate.isNotBlank()) return stateMatches(predicate, event.metadata)

        val key = spec.config["key"]?.trim().orEmpty()
        val operator = spec.config["operator"]?.trim()?.takeIf { it.isNotBlank() } ?: "="
        val expectedValue = spec.config["value"]?.trim().orEmpty()
        if (key.isBlank() || expectedValue.isBlank()) return false
        return stateMatches("$key$operator$expectedValue", event.metadata)
    }

    private fun matchesEvent(spec: ContextSpec, event: ContextEvent): Boolean {
        val actualEvent = event.metadata["event"].orEmpty()
        val expectedEvent = spec.config["event"]?.trim().orEmpty()
        if (expectedEvent.isSunEvent()) {
            return matchesSunEvent(spec, event, expectedEvent)
        }
        // sun_tick is the internal minute pulse that drives sunrise/sunset windows. It must
        // never satisfy a generic event spec, or blank-event / filter-only contexts (reachable
        // via imported bundles) fire their enter task every minute.
        if (actualEvent.equals("sun_tick", ignoreCase = true)) return false
        if (expectedEvent.isNotBlank() && !actualEvent.equals(expectedEvent, ignoreCase = true)) {
            return false
        }

        val expectedStates = firstConfig(spec, "state", "calendarState")
            .splitCsv()
            .map { it.lowercase(Locale.US) }
            .toSet()
        if (expectedStates.isNotEmpty()) {
            val actualState = event.metadata["state"].orEmpty().lowercase(Locale.US)
            if (actualState !in expectedStates) return false
        }

        val calendarAllowlist = firstConfig(spec, "calendar", "calendars")
            .splitCsv()
            .map { it.lowercase(Locale.US) }
            .toSet()
        if (calendarAllowlist.isNotEmpty()) {
            val actualCalendar = event.metadata["calendar"].orEmpty().lowercase(Locale.US)
            if (actualCalendar !in calendarAllowlist) return false
        }

        val beforeMinutes = firstConfig(spec, "beforeMinutes", "withinMinutes").toIntOrNull()
        if (beforeMinutes != null) {
            val minutesUntilStart = event.metadata["minutesUntilStart"]?.toIntOrNull() ?: return false
            if (minutesUntilStart !in 0..beforeMinutes) return false
        }

        spec.config["allDay"]?.toBooleanStrictOrNull()?.let { expectedAllDay ->
            val actualAllDay = event.metadata["allDay"]?.toBooleanStrictOrNull() ?: return false
            if (actualAllDay != expectedAllDay) return false
        }

        val packageAllowlist = firstConfig(spec, "package", "packages", "apps")
            .splitCsv()
            .map { it.lowercase(Locale.US) }
            .toSet()
        if (packageAllowlist.isNotEmpty()) {
            val actualPackage = event.metadata["package"].orEmpty().lowercase(Locale.US)
            if (actualPackage !in packageAllowlist) return false
        }

        val regex = spec.config["regex"]?.toBooleanStrictOrNull() ?: false

        if (actualEvent.equals(ShareContextEvents.EVENT_SHARE, ignoreCase = true)) {
            val mimeAllowlist = firstConfig(spec, "mime", "mimeType", "type")
                .splitCsv()
                .map { it.lowercase(Locale.US) }
            if (mimeAllowlist.isNotEmpty()) {
                val actualMime = event.metadata["mime"].orEmpty().lowercase(Locale.US)
                if (mimeAllowlist.none { expected ->
                        expected == actualMime ||
                            (expected.endsWith("/*") && actualMime.startsWith(expected.removeSuffix("*")))
                    }
                ) return false
            }

            val textFilter = firstConfig(spec, "text", "textContains")
            if (textFilter.isNotBlank() &&
                !textMatches(event.metadata["text"].orEmpty(), textFilter, regex)
            ) return false

            val uriFilter = firstConfig(spec, "uri", "uriContains")
            if (uriFilter.isNotBlank() &&
                !textMatches(event.metadata["uris"].orEmpty(), uriFilter, regex)
            ) return false

            spec.config["multiple"]?.toBooleanStrictOrNull()?.let { expectedMultiple ->
                if (event.metadata["multiple"]?.toBooleanStrictOrNull() != expectedMultiple) return false
            }
        }

        if (actualEvent.equals(BroadcastContextEvents.EVENT_BROADCAST, ignoreCase = true)) {
            // The action is what the receiver registered for, so a mismatch here means two
            // broadcast profiles are sharing one receiver and this is not the one that fired.
            val expectedAction = BroadcastContextEvents.normalizeAction(spec.config["action"])
            if (expectedAction != null && event.metadata["broadcast_action"] != expectedAction) return false

            // Sender identity needs API 34. Below that it arrives empty, and a profile that asked
            // for a sender must not fire for an unknown one just because the platform is old.
            val expectedSender = spec.config["sender"]?.trim().orEmpty().lowercase(Locale.US)
            if (expectedSender.isNotBlank() &&
                event.metadata["broadcast_sender"].orEmpty().lowercase(Locale.US) != expectedSender
            ) return false

            val extraKey = spec.config["extraKey"]?.trim().orEmpty()
            if (extraKey.isNotBlank()) {
                val actualExtra = event.metadata["broadcast_extra_$extraKey"]
                    ?: return false
                val expectedExtra = spec.config["extraValue"]?.trim().orEmpty()
                if (expectedExtra.isNotBlank() && !textMatches(actualExtra, expectedExtra, regex)) return false
            }
        }

        if (actualEvent.equals(CallContextEvents.EVENT_INCOMING_CALL, ignoreCase = true)) {
            // Exact on purpose: a caller nobody could check must not read as a stranger, so "unknown"
            // satisfies neither "true" nor "false".
            val expectedKnown = spec.config["knownContact"]?.trim().orEmpty()
            if (expectedKnown.isNotBlank() &&
                !event.metadata["knownContact"].orEmpty().equals(expectedKnown, ignoreCase = true)
            ) return false

            val numberFilter = spec.config["number"]?.trim().orEmpty()
            if (numberFilter.isNotBlank() &&
                !textMatches(event.metadata["number"].orEmpty(), numberFilter, regex)
            ) return false
        }

        val topicAllowlist = firstConfig(spec, "topic", "topics")
            .splitCsv()
            .map { it.lowercase(Locale.US) }
            .toSet()
        if (topicAllowlist.isNotEmpty()) {
            val actualTopic = event.metadata["topic"].orEmpty().lowercase(Locale.US)
            if (actualTopic !in topicAllowlist) return false
        }

        val eventId = spec.config["eventId"]?.trim().orEmpty()
        if (eventId.isNotBlank() && event.metadata["eventId"].orEmpty() != eventId) return false

        val configuredTagIds = firstConfig(spec, "tagId", "tagIds", "tag")
            .splitCsv()
            .map(NfcContextEvents::normalizeTagId)
            .filter { it.isNotBlank() }
            .toSet()
        if (configuredTagIds.isNotEmpty()) {
            val actualTagId = NfcContextEvents.normalizeTagId(event.metadata["tagId"].orEmpty())
            if (actualTagId !in configuredTagIds) return false
        }

        val titleFilter = spec.config["title"]?.trim().orEmpty()
        if (titleFilter.isNotBlank() && !textMatches(event.metadata["title"].orEmpty(), titleFilter, regex)) {
            return false
        }
        val bodyFilter = spec.config["body"]?.trim().orEmpty()
        if (bodyFilter.isNotBlank() && !textMatches(event.metadata["body"].orEmpty(), bodyFilter, regex)) {
            return false
        }

        val senderFilter = firstConfig(spec, "sender", "from", "originatingAddress")
        // A broadcast's sender is a package name checked exactly in the block above, not an SMS
        // originating address matched by substring. Without this exclusion the shared filter reads
        // an always-empty metadata key and rejects every broadcast that names a sender.
        if (senderFilter.isNotBlank() &&
            !actualEvent.equals(BroadcastContextEvents.EVENT_BROADCAST, ignoreCase = true) &&
            !textMatches(event.metadata["sender"].orEmpty(), senderFilter, regex)
        ) {
            return false
        }

        val filter = spec.config["filter"]?.trim().orEmpty()
        // A spec with neither an event name nor a filter would match every pulse from any
        // event bridge; fail closed instead (only imports can produce such a spec).
        if (filter.isBlank()) return expectedEvent.isNotBlank() && actualEvent.isNotBlank()
        return event.metadata.values.any { textMatches(it, filter, regex) }
    }

    /**
     * Stamps a stable pulse identity on a sun-window match so [PulseEventContinuity] can
     * collapse the once-per-minute `sun_tick` stream into one occurrence per window.
     *
     * Exact-minute contexts (window of 1) still get a unique id per matching minute, so they
     * continue to fire on that minute. Calendar and share events already carry `eventId` /
     * `observedAtEpochMs` and are left unchanged.
     */
    fun withStablePulseIdentity(spec: ContextSpec, event: ContextEvent): ContextEvent {
        if (event.metadata["eventId"] != null || event.metadata["observedAtEpochMs"] != null) {
            return event
        }
        val expectedEvent = spec.config["event"]?.trim().orEmpty()
        if (!expectedEvent.isSunEvent()) return event
        val window = sunWindow(spec, event, expectedEvent) ?: return event
        val occurrenceId = "sun:${window.event}:${window.date}:${window.start}:${window.end}"
        return event.copy(metadata = event.metadata + ("eventId" to occurrenceId))
    }

    /**
     * True when a multiplexed plugin poll result belongs to this spec's subscription.
     * Results for other plugin/bundle subscriptions must be ignored (state held), not
     * evaluated to false, or every extra subscription flaps this level context.
     */
    fun pluginEventAddressesSpec(spec: ContextSpec, event: ContextEvent): Boolean {
        val expectedPackage = spec.config["package"]?.trim().orEmpty()
        if (expectedPackage.isBlank()) return true
        val actualPackage = event.metadata["package"].orEmpty()
        if (!actualPackage.equals(expectedPackage, ignoreCase = true)) return false
        val expectedBundle = spec.config["bundleJson"]?.trim().orEmpty().ifBlank { "{}" }
        val actualBundle = event.metadata["bundleJson"].orEmpty().ifBlank { "{}" }
        return expectedBundle == actualBundle
    }

    private fun matchesPlugin(spec: ContextSpec, event: ContextEvent): Boolean {
        val expectedPackage = spec.config["package"]?.trim().orEmpty()
        if (expectedPackage.isBlank()) return false
        val actualPackage = event.metadata["package"].orEmpty()
        if (!actualPackage.equals(expectedPackage, ignoreCase = true)) return false
        val expectedBundle = spec.config["bundleJson"]?.trim().orEmpty().ifBlank { "{}" }
        val actualBundle = event.metadata["bundleJson"].orEmpty().ifBlank { "{}" }
        if (expectedBundle != actualBundle) return false
        return event.metadata["state"].equals("satisfied", ignoreCase = true)
    }

    private fun matchesSunEvent(spec: ContextSpec, event: ContextEvent, expectedEvent: String): Boolean =
        sunWindow(spec, event, expectedEvent) != null

    private fun sunWindow(spec: ContextSpec, event: ContextEvent, expectedEvent: String): SunWindow? {
        if (!event.metadata["event"].orEmpty().equals("sun_tick", ignoreCase = true)) return null
        val latitude = firstConfig(spec, "latitude", "lat").toDoubleOrNull() ?: return null
        val longitude = firstConfig(spec, "longitude", "lon", "lng").toDoubleOrNull() ?: return null
        val date = event.metadata["date"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: LocalDate.now()
        val zone = event.metadata["zone"]?.let { runCatching { ZoneId.of(it) }.getOrNull() }
            ?: ZoneId.systemDefault()
        val currentMinute = event.metadata["time"]?.let(::parseClockMinutes) ?: currentMinuteOfDay()
        val elevation = SunEventCalculator.elevationDegrees(firstConfig(spec, "elevation")) ?: return null
        val baseMinute = SunEventCalculator.eventMinuteOfDay(date, latitude, longitude, expectedEvent, zone, elevation) ?: return null
        val offset = firstConfig(spec, "offsetMinutes", "offset").toIntOrNull() ?: 0
        val windowMinutes = (firstConfig(spec, "windowMinutes", "window").toIntOrNull() ?: 1).coerceIn(1, 180)
        val start = Math.floorMod(baseMinute + offset, MINUTES_PER_DAY)
        val end = Math.floorMod(start + windowMinutes - 1, MINUTES_PER_DAY)
        if (!minuteInWindow(currentMinute, start, end)) return null
        // A window that wraps midnight still belongs to the evening's occurrence. Ticks after
        // 00:00 carry the next calendar date, so identity uses the date of the window start.
        val occurrenceDate = if (start > end && currentMinute <= end) date.minusDays(1) else date
        return SunWindow(
            event = expectedEvent.lowercase(Locale.US),
            date = occurrenceDate.toString(),
            start = start,
            end = end,
        )
    }

    private data class SunWindow(
        val event: String,
        val date: String,
        val start: Int,
        val end: Int,
    )

    private fun firstConfig(spec: ContextSpec, vararg keys: String): String =
        keys.firstNotNullOfOrNull { spec.config[it]?.trim()?.takeIf(String::isNotBlank) }.orEmpty()

    private fun String.splitCsv(): List<String> =
        split(',', ';')
            .map { it.trim() }
            .filter { it.isNotBlank() }

    private fun textMatches(value: String, filter: String, regex: Boolean): Boolean {
        if (!regex) return value.contains(filter, ignoreCase = true)
        if (filter.length > MAX_REGEX_PATTERN_CHARS || value.length > MAX_REGEX_INPUT_CHARS) return false
        // Use linear-time RE2 (not the JDK backtracking engine) so a pathological user pattern such
        // as (a+)+$ on an incoming notification/event cannot hang the matcher coroutine.
        return runCatching {
            Re2Pattern.compile(filter, Re2Pattern.CASE_INSENSITIVE).matcher(value).find()
        }.getOrDefault(false)
    }

    private fun parseClockMinutes(value: String): Int? {
        val parts = value.trim().split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    private fun currentMinuteOfDay(): Int {
        val calendar = Calendar.getInstance()
        return calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
    }

    private fun minuteInWindow(current: Int?, start: Int, end: Int): Boolean {
        current ?: return false
        return if (start <= end) current in start..end else current >= start || current <= end
    }

    private const val MAX_REGEX_PATTERN_CHARS = 160
    private const val MAX_REGEX_INPUT_CHARS = 1_000
    private const val MINUTES_PER_DAY = 24 * 60
}

private fun String.isSunEvent(): Boolean =
    equals("sunrise", ignoreCase = true) || equals("sunset", ignoreCase = true)
