package com.opentasker.core.contexts

import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Variable
import com.opentasker.core.model.VariableNamePolicy
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * A Time context's start or end can name a global, as Tasker's From and To can (`%Sunrise`). The
 * engine reads the global's current value into the time event under [START_VALUE] and [END_VALUE],
 * so the window moves once the task that changes it finishes (globals are saved when a task ends),
 * and a global holding no time keeps the context off, which is what Tasker does.
 */
object TimeContextBounds {
    const val START_VALUE = "start.value"
    const val END_VALUE = "end.value"

    private const val MAX_EPOCH_SECONDS_DIGITS = 12
    private val separatedClock = Regex("""^(\d{1,2})[.:](\d{1,2})$""")

    fun isVariable(raw: String): Boolean = raw.trim().startsWith('%')

    /** The global a bound names, without its `%`. Null for a clock time, and for a local, which no context can read. */
    fun variableName(raw: String): String? =
        raw.trim().takeIf { it.startsWith('%') }
            ?.let(VariableNamePolicy::normalize)
            ?.takeIf(VariableNamePolicy::isGlobal)

    /** The event key each variable bound's value goes under, with the global it names. */
    fun variableBounds(spec: ContextSpec): Map<String, String> {
        if (spec.type != ContextType.TIME) return emptyMap()
        return buildMap {
            variableName(configured(spec, "start", "from"))?.let { put(START_VALUE, it) }
            variableName(configured(spec, "end", "to"))?.let { put(END_VALUE, it) }
        }
    }

    /**
     * Tasker reads a variable's time as hours and minutes split by a dot or a colon, leading zeros
     * optional (`9.7` is 09:07), or as seconds since the epoch, of which only the local hour and
     * minute count. Anything else is no time.
     */
    fun clockFromValue(value: String, timeZone: TimeZone = TimeZone.getDefault()): String? {
        val trimmed = value.trim()
        val separated = separatedClock.matchEntire(trimmed)
        val (hour, minute) = when {
            separated != null -> separated.groupValues[1].toInt() to separated.groupValues[2].toInt()
            trimmed.isNotEmpty() && trimmed.length <= MAX_EPOCH_SECONDS_DIGITS && trimmed.all { it in '0'..'9' } -> {
                val calendar = Calendar.getInstance(timeZone).apply { timeInMillis = trimmed.toLong() * 1_000L }
                calendar.get(Calendar.HOUR_OF_DAY) to calendar.get(Calendar.MINUTE)
            }
            else -> return null
        }
        if (hour !in 0..23 || minute !in 0..59) return null
        return String.format(Locale.US, "%02d:%02d", hour, minute)
    }

    /** [event] with each variable bound's clock time from [globals], or without it when the global holds no time. */
    fun withVariableValues(
        spec: ContextSpec,
        event: ContextEvent,
        globals: Map<String, String>,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): ContextEvent {
        val bounds = variableBounds(spec)
        if (bounds.isEmpty()) return event
        val resolved = bounds.mapNotNull { (key, name) ->
            globals[name]?.let { clockFromValue(it, timeZone) }?.let { key to it }
        }
        return event.copy(metadata = event.metadata - bounds.keys + resolved)
    }

    /** Each bound of [spec] written as a variable, as written (`%Sunrise`), with the time [metadata] holds for it. */
    fun boundTimes(spec: ContextSpec, metadata: Map<String, String>): List<Pair<String, String?>> {
        if (spec.type != ContextType.TIME) return emptyList()
        return listOf(configured(spec, "start", "from") to START_VALUE, configured(spec, "end", "to") to END_VALUE)
            .filter { (raw, _) -> isVariable(raw) }
            .map { (raw, key) -> raw.trim() to metadata[key]?.let(::clockFromValue) }
    }

    /** The bounds of [spec] written as a variable that hold no time yet. Until each has one, the context can't tell whether it holds. */
    fun unresolvedBounds(spec: ContextSpec, metadata: Map<String, String>): List<String> =
        boundTimes(spec, metadata).filter { (_, time) -> time == null }.map { (raw, _) -> raw }

    /** Time events for [spec], each carrying its variable bounds' values, again whenever one of those globals changes. */
    fun resolving(spec: ContextSpec, events: Flow<ContextEvent>, globals: Flow<Map<String, String>>): Flow<ContextEvent> {
        val names = variableBounds(spec).values.toSet()
        val boundValues = globals.map { values -> values.filterKeys(names::contains) }.distinctUntilChanged()
        return combine(events, boundValues) { event, values -> withVariableValues(spec, event, values) }
    }

    /** Each global's value by project, only the ones named in [names] when given. */
    fun valuesByProject(variables: List<Variable>, names: Set<String>? = null): Map<Long, Map<String, String>> =
        variables.filter { it.isGlobal && (names == null || it.name in names) }
            .groupBy { it.projectId }
            .mapValues { (_, globals) -> globals.associate { it.name to it.value } }

    /** The globals any Time context of [profiles] is bounded by. */
    fun boundGlobals(profiles: List<Profile>): Set<String> =
        profiles.flatMapTo(mutableSetOf()) { profile -> profile.contexts.flatMap { variableBounds(it).values } }

    private fun configured(spec: ContextSpec, vararg keys: String): String =
        keys.firstNotNullOfOrNull { key -> spec.config[key]?.takeIf { it.isNotBlank() } }.orEmpty()
}
