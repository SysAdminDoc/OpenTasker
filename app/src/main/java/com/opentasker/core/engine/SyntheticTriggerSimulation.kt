package com.opentasker.core.engine

import com.opentasker.core.actions.ActionArgumentSensitivity
import com.opentasker.core.contexts.ContextEvent
import com.opentasker.core.contexts.ContextMatchEvaluator
import com.opentasker.core.contexts.DaySchedule
import com.opentasker.core.contexts.SunEventCalculator
import com.opentasker.core.contexts.TimeContextBounds
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.core.model.ProfileLifecyclePolicy
import com.opentasker.core.model.ProfileLifecycleStrings
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.max

enum class SyntheticContextStatus {
    PASSED,
    BLOCKED,
}

data class SyntheticGateResult(
    val accepted: Boolean,
    val reason: String,
) {
    companion object {
        fun pass(reason: String): SyntheticGateResult = SyntheticGateResult(true, reason)
        fun block(reason: String): SyntheticGateResult = SyntheticGateResult(false, reason)
    }
}

/** The fabricated source event shown to the operator and fed to the real matcher. */
data class SyntheticContextTemplate(
    val event: ContextEvent,
    val displayMetadata: Map<String, String>,
    val description: String,
    val issue: String? = null,
)

data class SyntheticContextResult(
    val index: Int,
    val spec: ContextSpec,
    val template: SyntheticContextTemplate,
    val rawMatched: Boolean,
    val effectiveMatched: Boolean,
    val status: SyntheticContextStatus,
    val explanation: String,
) {
    val event: ContextEvent get() = template.event
    val displayMetadata: Map<String, String> get() = template.displayMetadata
}

data class SyntheticTriggerSimulation(
    val profileId: Long,
    val profileName: String,
    val profileEnabled: Boolean,
    val contexts: List<SyntheticContextResult>,
    val profileMatched: Boolean,
    val profileReason: String,
    val cooldown: SyntheticGateResult,
    val admission: SyntheticGateResult,
    val pinnedContextCount: Int,
    val sideEffectsSuppressed: Boolean = true,
) {
    val wouldTrigger: Boolean
        get() = profileMatched && cooldown.accepted && admission.accepted
}

/**
 * Builds deterministic source-shaped events for every context family and evaluates them using the
 * production matcher. The simulator has no Context, database, coroutine, or action dependency;
 * constructing a report therefore cannot write run-log state or fire an action.
 */
object SyntheticTriggerSimulator {
    private const val ASSUMED_GLOBAL_START = 6 * 60
    private const val ASSUMED_GLOBAL_END = 18 * 60

    fun simulate(
        profile: Profile,
        nowMs: Long = System.currentTimeMillis(),
        pinnedEvents: Map<Int, ContextEvent> = emptyMap(),
        cooldown: SyntheticGateResult = SyntheticGateResult.pass("No cooldown is currently blocking this profile."),
        admission: SyntheticGateResult = SyntheticGateResult.pass("Admission budget is available (preview only)."),
        lifecycleStrings: ProfileLifecycleStrings = ProfileLifecycleStrings.English,
    ): SyntheticTriggerSimulation {
        val results = profile.contexts.mapIndexed { index, spec ->
            val template = pinnedEvents[index]?.let { event ->
                SyntheticContextTemplate(
                    event = event,
                    displayMetadata = redactMetadata(event.metadata),
                    description = "Pinned event override",
                )
            } ?: template(spec, nowMs)
            val rawMatched = template.issue == null && ContextMatchEvaluator.matches(spec, template.event)
            // Same rule as the engine: an inverted context whose value is unknown stays off.
            val unknown = ContextMatchEvaluator.valueUnknown(spec, template.event.metadata)
            val effectiveMatched = if (spec.invert) !rawMatched && !unknown else rawMatched
            val event = if (index in pinnedEvents) "The pinned event" else "The synthetic event"
            val explanation = when {
                template.issue != null -> template.issue
                spec.invert && unknown -> "$event has no value for this context, so the inverted context stays off."
                spec.invert && effectiveMatched -> "$event fails the raw predicate, so inversion passes it."
                spec.invert && rawMatched -> "$event satisfies the raw predicate, so inversion blocks it."
                effectiveMatched -> "$event satisfies this predicate."
                else -> "$event does not satisfy this predicate."
            }
            SyntheticContextResult(
                index = index,
                spec = spec,
                template = template,
                rawMatched = rawMatched,
                effectiveMatched = effectiveMatched,
                status = if (effectiveMatched) SyntheticContextStatus.PASSED else SyntheticContextStatus.BLOCKED,
                explanation = explanation,
            )
        }
        val aggregateMatch = if (results.isEmpty()) {
            false
        } else {
            evaluateContextExpression(
                results.map { result ->
                    ContextMatchUpdate(
                        matched = result.effectiveMatched,
                        pulseContext = result.spec.type == ContextType.EVENT,
                        pulseSequence = if (result.spec.type == ContextType.EVENT && result.rawMatched) 1L else 0L,
                        event = result.event.takeIf { result.rawMatched },
                    )
                }.toTypedArray(),
                profile.contexts,
                profile.contextExpression,
            )
        }
        val lifecycleSuppression = ProfileLifecyclePolicy.suppressionReason(profile, nowMs, lifecycleStrings)
        val graceSuppression = profile.gracePeriodSec > 0 && aggregateMatch
        val profileMatched = profile.enabled && lifecycleSuppression == null && aggregateMatch && !graceSuppression
        val profileReason = when {
            !profile.enabled -> "Profile is disabled."
            lifecycleSuppression != null -> lifecycleSuppression
            profile.contexts.isEmpty() -> "No contexts are configured."
            !aggregateMatch -> results.firstOrNull { !it.effectiveMatched }?.let {
                "Context ${it.index + 1} blocks the profile: ${it.explanation}"
            } ?: "The configured context expression is invalid or did not match."
            profile.gracePeriodSec > 0 -> "All pinned predicates pass; the ${profile.gracePeriodSec}s activation/deactivation grace period still applies."
            else -> "All pinned predicates pass the configured context logic."
        }
        return SyntheticTriggerSimulation(
            profileId = profile.id,
            profileName = profile.name,
            profileEnabled = profile.enabled,
            contexts = results,
            profileMatched = profileMatched,
            profileReason = profileReason,
            cooldown = cooldown,
            admission = admission,
            pinnedContextCount = pinnedEvents.keys.count { it in profile.contexts.indices },
        )
    }

    fun template(spec: ContextSpec, nowMs: Long = System.currentTimeMillis()): SyntheticContextTemplate =
        when (spec.type) {
            ContextType.APPLICATION -> applicationTemplate(spec)
            ContextType.TIME -> timeTemplate(spec, nowMs)
            ContextType.DAY -> dayTemplate(spec)
            ContextType.LOCATION -> locationTemplate(spec, nowMs)
            ContextType.STATE -> stateTemplate(spec)
            ContextType.EVENT -> eventTemplate(spec, nowMs)
            ContextType.PLUGIN -> pluginTemplate(spec)
        }

    private fun applicationTemplate(spec: ContextSpec): SyntheticContextTemplate {
        val configuredPackage = first(spec.config, "package", "packages", "apps")
        val packageName = csvFirst(configuredPackage).ifBlank { "com.example.synthetic" }
        val component = first(spec.config, "component", "activity", "class")
            .ifBlank { "$packageName.MainActivity" }
        return template(
            event = ContextEvent(
                type = "app",
                matched = true,
                metadata = mapOf(
                    "foreground" to packageName,
                    "package" to packageName,
                    "component" to component,
                ),
            ),
            description = "Foreground package and component",
            issue = configuredPackage.takeIf(String::isBlank)?.let {
                "Application context has no configured package."
            },
        )
    }

    private fun timeTemplate(spec: ContextSpec, nowMs: Long): SyntheticContextTemplate {
        val startRaw = first(spec.config, "start", "from")
        val endRaw = first(spec.config, "end", "to")
        // A bound that reads a global (%Sunrise) has no value here, so it stands in as 06:00 or 18:00.
        val startGlobal = TimeContextBounds.variableName(startRaw)
        val endGlobal = TimeContextBounds.variableName(endRaw)
        val start = if (startGlobal != null) ASSUMED_GLOBAL_START else parseClock(startRaw)
        val end = if (endGlobal != null) ASSUMED_GLOBAL_END else parseClock(endRaw)
        val minute = if (start != null && end != null) midpoint(start, end) else 12 * 60
        val assumed = buildMap {
            if (startGlobal != null) put(TimeContextBounds.START_VALUE, formatClock(ASSUMED_GLOBAL_START))
            if (endGlobal != null) put(TimeContextBounds.END_VALUE, formatClock(ASSUMED_GLOBAL_END))
        }
        val assumptions = listOfNotNull(
            startGlobal?.let { "%$it as ${formatClock(ASSUMED_GLOBAL_START)}" },
            endGlobal?.let { "%$it as ${formatClock(ASSUMED_GLOBAL_END)}" },
        )
        return template(
            event = ContextEvent("time", true, mapOf("time" to formatClock(minute), "epochMs" to nowMs.toString()) + assumed),
            description = if (assumptions.isEmpty()) {
                "Midpoint of the configured time window"
            } else {
                "Midpoint of the time window, taking ${assumptions.joinToString(" and ")}"
            },
            issue = when {
                startRaw.isBlank() || endRaw.isBlank() -> "Time context needs both a start and end time."
                start == null || end == null -> "Time context contains an invalid clock value."
                else -> null
            },
        )
    }

    private fun dayTemplate(spec: ContextSpec): SyntheticContextTemplate {
        val configured = first(spec.config, "days", "day")
        val selected = DaySchedule.parse(configured)
        val day = DaySchedule.orderedDays.firstOrNull { it in selected } ?: "MON"
        return template(
            event = ContextEvent("time", true, mapOf("day" to day)),
            description = "First configured day token",
            issue = if (selected.isEmpty()) "Day context has no valid configured day." else null,
        )
    }

    private fun locationTemplate(spec: ContextSpec, nowMs: Long): SyntheticContextTemplate {
        val latitudeRaw = first(spec.config, "latitude", "lat")
        val longitudeRaw = first(spec.config, "longitude", "lon", "lng")
        val radiusRaw = first(spec.config, "radiusMeters", "radius")
        val latitude = latitudeRaw.toDoubleOrNull() ?: 0.0
        val longitude = longitudeRaw.toDoubleOrNull() ?: 0.0
        val radius = radiusRaw.toDoubleOrNull() ?: 100.0
        val outside = first(spec.config, "outside", "inverted").equals("true", ignoreCase = true)
        val point = if (outside) outsidePoint(latitude, longitude, radius) else latitude to longitude
        val dwell = parseDwellMillis(spec.config)
        val maxAccuracyRaw = first(spec.config, "maxAccuracyMeters", "maxAccuracy")
        val accuracy = maxAccuracyRaw.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
        val metadata = mapOf(
            "latitude" to point.first.toString(),
            "longitude" to point.second.toString(),
            "accuracyMeters" to accuracy.toString(),
            "observedAtEpochMs" to nowMs.toString(),
            "insideSinceEpochMs" to (nowMs - dwell).toString(),
        )
        val invalid = when {
            latitudeRaw.isBlank() || longitudeRaw.isBlank() || radiusRaw.isBlank() ->
                "Location context needs latitude, longitude, and radius."
            latitude !in -90.0..90.0 || longitude !in -180.0..180.0 ->
                "Location context contains an out-of-range coordinate."
            radius < 0.0 -> "Location radius cannot be negative."
            maxAccuracyRaw.isNotBlank() && (maxAccuracyRaw.toDoubleOrNull()?.let { it < 0.0 } != false) ->
                "Location accuracy must be a non-negative number."
            dwell < 0L -> "Location dwell cannot be negative."
            else -> null
        }
        return template(
            event = ContextEvent("location", true, metadata),
            description = if (outside) "Point outside the configured geofence" else "Point at the geofence center",
            issue = invalid,
        )
    }

    private fun stateTemplate(spec: ContextSpec): SyntheticContextTemplate {
        val rawPredicate = spec.config["predicate"]?.trim().orEmpty()
        val keyRaw = spec.config["key"]?.trim().orEmpty()
        val operatorRaw = spec.config["operator"]?.trim().orEmpty().ifBlank { "=" }
        val expectedRaw = spec.config["value"]?.trim().orEmpty()
        val parsed = parseStatePredicate(rawPredicate.ifBlank {
            if (keyRaw.isBlank() || expectedRaw.isBlank()) "" else "$keyRaw$operatorRaw$expectedRaw"
        })
        val normalizedKey = parsed?.first?.let(::normalizeStateKey).orEmpty()
        val expected = parsed?.third.orEmpty()
        val actual = stateSyntheticValue(normalizedKey, parsed?.second.orEmpty(), expected)
        val metadata = mutableMapOf<String, String>()
        if (normalizedKey.isNotBlank()) metadata[normalizedKey] = actual
        if (normalizedKey == "wifi") {
            metadata["wifi_connected"] = if (expected.equals("connected", true) || expected.equals("true", true)) "true" else "false"
            if (expected !in setOf("connected", "disconnected", "true", "false", "on", "off")) {
                metadata["wifi"] = expected
            }
        }
        val issue = when {
            parsed == null -> "State context needs a key, operator, and value predicate."
            parsed.second !in setOf("=", ">=", "<=", ">", "<") -> "State context uses an unsupported comparison operator."
            parsed.second != "=" && expected.toIntOrNull() == null ->
                "Numeric state comparisons need a numeric threshold."
            else -> null
        }
        return template(
            event = ContextEvent("state", true, metadata),
            description = "State value satisfying the configured predicate",
            issue = issue,
        )
    }

    private fun eventTemplate(spec: ContextSpec, nowMs: Long): SyntheticContextTemplate {
        val expectedEvent = first(spec.config, "event")
        val filter = spec.config["filter"]?.trim().orEmpty()
        val isSunEvent = expectedEvent.equals("sunrise", true) || expectedEvent.equals("sunset", true)
        if (isSunEvent) return sunEventTemplate(spec, nowMs, expectedEvent)

        val actualEvent = expectedEvent.ifBlank { "synthetic_event" }
        val metadata = linkedMapOf("event" to actualEvent)
        first(spec.config, "state", "calendarState").takeIf(String::isNotBlank)?.let { metadata["state"] = csvFirst(it) }
        first(spec.config, "calendar", "calendars").takeIf(String::isNotBlank)?.let { metadata["calendar"] = csvFirst(it) }
        first(spec.config, "package", "packages", "apps").takeIf(String::isNotBlank)?.let { metadata["package"] = csvFirst(it) }
        spec.config["allDay"]?.toBooleanStrictOrNull()?.let { metadata["allDay"] = it.toString() }
        spec.config["multiple"]?.toBooleanStrictOrNull()?.let { metadata["multiple"] = it.toString() }
        first(spec.config, "beforeMinutes", "withinMinutes").toIntOrNull()?.let { minutes ->
            metadata["minutesUntilStart"] = if (minutes >= 0) "0" else minutes.toString()
        }
        first(spec.config, "topic", "topics").takeIf(String::isNotBlank)?.let { metadata["topic"] = csvFirst(it) }
        spec.config["eventId"]?.trim()?.takeIf(String::isNotBlank)?.let { metadata["eventId"] = it }
        first(spec.config, "tagId", "tagIds", "tag").takeIf(String::isNotBlank)?.let { metadata["tagId"] = csvFirst(it) }
        spec.config["title"]?.trim()?.takeIf(String::isNotBlank)?.let { metadata["title"] = it }
        spec.config["body"]?.trim()?.takeIf(String::isNotBlank)?.let { metadata["body"] = it }
        first(spec.config, "sender", "from", "originatingAddress").takeIf(String::isNotBlank)?.let { metadata["sender"] = it }
        if (actualEvent.equals("share", true)) {
            first(spec.config, "mime", "mimeType", "type").takeIf(String::isNotBlank)?.let { metadata["mime"] = csvFirst(it) }
            spec.config["text"]?.trim()?.takeIf(String::isNotBlank)?.let { metadata["text"] = it }
            spec.config["uri"]?.trim()?.takeIf(String::isNotBlank)?.let { metadata["uris"] = it }
        }
        if (actualEvent.equals("incoming_call", true)) {
            spec.config["knownContact"]?.trim()?.takeIf(String::isNotBlank)?.let { metadata["knownContact"] = it }
            spec.config["number"]?.trim()?.takeIf(String::isNotBlank)?.let { metadata["number"] = it }
        }
        if (filter.isNotBlank()) metadata["filter"] = filter
        val issue = when {
            expectedEvent.isBlank() && filter.isBlank() ->
                "Event context needs an event name or a filter."
            first(spec.config, "beforeMinutes", "withinMinutes").isNotBlank() &&
                first(spec.config, "beforeMinutes", "withinMinutes").toIntOrNull() == null ->
                "Event before-window must be a number of minutes."
            else -> null
        }
        return template(
            event = ContextEvent("event", true, metadata),
            description = "Event payload populated from the configured filters",
            issue = issue,
        )
    }

    private fun sunEventTemplate(spec: ContextSpec, nowMs: Long, expectedEvent: String): SyntheticContextTemplate {
        val latitudeRaw = first(spec.config, "latitude", "lat")
        val longitudeRaw = first(spec.config, "longitude", "lon", "lng")
        val latitude = latitudeRaw.toDoubleOrNull()
        val longitude = longitudeRaw.toDoubleOrNull()
        val zone = ZoneId.systemDefault()
        val date = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val elevationRaw = first(spec.config, "elevation")
        val elevation = SunEventCalculator.elevationDegrees(elevationRaw)
        val crossing = if (latitude != null && longitude != null && elevation != null) {
            SunEventCalculator.eventLocalDateTime(date, latitude, longitude, expectedEvent, zone, elevation)
        } else {
            null
        }
        val offsetRaw = first(spec.config, "offsetMinutes", "offset")
        val windowRaw = first(spec.config, "windowMinutes", "window")
        val offset = offsetRaw.toIntOrNull() ?: 0
        val window = (windowRaw.toIntOrNull() ?: 1).coerceIn(1, 180)
        // The tick carries the date the window opens on, which is the next date for a dusk past midnight.
        val at = (crossing ?: date.atTime(12, 0)).plusMinutes(offset.toLong())
        val metadata = mapOf(
            "event" to "sun_tick",
            "date" to at.toLocalDate().toString(),
            "time" to formatClock(at.hour * 60 + at.minute),
            "zone" to zone.id,
        )
        val issue = when {
            latitude == null || longitude == null -> "Sun event needs valid latitude and longitude."
            latitude !in -90.0..90.0 || longitude !in -180.0..180.0 -> "Sun event coordinates are out of range."
            elevation == null -> "Sun elevation must be civil, nautical, astronomical, or degrees from -90 to 90, not '$elevationRaw'."
            crossing == null -> "The configured sun event is unavailable at these coordinates."
            offsetRaw.isNotBlank() && offsetRaw.toIntOrNull() == null -> "Sun event offset must be numeric."
            windowRaw.isNotBlank() && windowRaw.toIntOrNull() == null -> "Sun event window must be numeric."
            else -> null
        }
        return template(
            event = ContextEvent("event", true, metadata),
            description = "Sun tick at the configured ${expectedEvent.lowercase(Locale.US)} window",
            issue = issue,
        )
    }

    private fun pluginTemplate(spec: ContextSpec): SyntheticContextTemplate {
        val configuredPackage = first(spec.config, "package")
        val packageName = configuredPackage.ifBlank { "com.example.synthetic.plugin" }
        val bundle = first(spec.config, "bundleJson").ifBlank { "{}" }
        return template(
            event = ContextEvent(
                "plugin",
                true,
                mapOf("package" to packageName, "bundleJson" to bundle, "state" to "satisfied"),
            ),
            description = "Satisfied plugin condition result",
            issue = if (configuredPackage.isBlank()) "Plugin context has no configured package." else null,
        )
    }

    private fun template(
        event: ContextEvent,
        description: String,
        issue: String?,
    ): SyntheticContextTemplate = SyntheticContextTemplate(
        event = event,
        displayMetadata = redactMetadata(event.metadata),
        description = description,
        issue = issue,
    )

    private fun redactMetadata(metadata: Map<String, String>): Map<String, String> =
        metadata.toSortedMap().mapValues { (key, value) ->
            if (key.equals("bundleJson", ignoreCase = true)) {
                ActionArgumentSensitivity.REDACTED
            } else {
                ActionArgumentSensitivity.maskValue(null, key, value, metadata, maxLength = 96)
            }
        }

    private fun first(config: Map<String, String>, vararg keys: String): String =
        keys.firstNotNullOfOrNull { key -> config[key]?.trim()?.takeIf(String::isNotBlank) }.orEmpty()

    private fun csvFirst(value: String): String = value.split(',', ';').firstOrNull { it.isNotBlank() }?.trim().orEmpty()

    private fun parseClock(value: String): Int? {
        val parts = value.trim().split(":")
        if (parts.size !in 1..2) return null
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return if (hour in 0..23 && minute in 0..59) hour * 60 + minute else null
    }

    private fun formatClock(minuteOfDay: Int): String {
        val minute = Math.floorMod(minuteOfDay, 24 * 60)
        return "%02d:%02d".format(Locale.US, minute / 60, minute % 60)
    }

    private fun midpoint(start: Int, end: Int): Int {
        val duration = if (end >= start) end - start else 24 * 60 - start + end
        return Math.floorMod(start + duration / 2, 24 * 60)
    }

    private fun outsidePoint(latitude: Double, longitude: Double, radiusMeters: Double): Pair<Double, Double> {
        val safeLatitude = latitude.coerceIn(-89.8, 89.8)
        val delta = ((radiusMeters.coerceAtLeast(0.0) + max(100.0, radiusMeters * 0.1)) / 111_000.0)
            .coerceAtLeast(0.002)
            .coerceAtMost(1.0)
        return (safeLatitude + delta).coerceAtMost(89.8) to longitude.coerceIn(-179.8, 179.8)
    }

    private fun parseDwellMillis(config: Map<String, String>): Long {
        val millisRaw = first(config, "dwellMillis", "dwellMs")
        if (millisRaw.isNotBlank()) return millisRaw.toLongOrNull() ?: -1L
        val secondsRaw = first(config, "dwellSeconds", "dwellSec")
        if (secondsRaw.isNotBlank()) return secondsRaw.toLongOrNull()?.times(1_000L) ?: -1L
        return 0L
    }

    private fun parseStatePredicate(value: String): Triple<String, String, String>? {
        val operator = listOf(">=", "<=", ">", "<", "=").firstOrNull { value.contains(it) } ?: return null
        val parts = value.split(operator, limit = 2)
        if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) return null
        return Triple(parts[0].trim(), operator, parts[1].trim())
    }

    private fun normalizeStateKey(key: String): String = when (key.trim().lowercase(Locale.US)) {
        "battery" -> "battery_level"
        "headset" -> "headphones"
        "ssid", "wifi_ssid" -> "wifi"
        "battery_saver", "powersave", "power_saver" -> "power_save"
        "airplane_mode", "flight_mode" -> "airplane"
        "device_unlocked" -> "unlocked"
        else -> key.trim().lowercase(Locale.US)
    }

    private fun stateSyntheticValue(key: String, operator: String, expected: String): String {
        val normalized = expected.trim().lowercase(Locale.US)
        val booleanValue = when (normalized) {
            "connected", "plugged", "plugged_in", "on", "enabled", "true", "yes", "charging", "unlocked" -> "true"
            "disconnected", "unplugged", "off", "disabled", "false", "no", "discharging", "locked" -> "false"
            else -> null
        }
        if (operator == "=") return booleanValue ?: expected.trim()
        val number = expected.toIntOrNull() ?: return expected.trim()
        return when (operator) {
            ">" -> (number + 1).toString()
            "<" -> (number - 1).toString()
            else -> number.toString()
        }
    }
}
