package com.opentasker.core.health

import androidx.annotation.StringRes
import com.opentasker.app.R
import com.opentasker.core.contexts.normalizeStateKey
import com.opentasker.core.contexts.stateContextKey
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** A Health Connect reading a STATE context can compare, published under [stateKey]. */
enum class HealthMetric(
    val stateKey: String,
    val readPermission: String,
    @StringRes val labelRes: Int,
) {
    /** Steps since local midnight. */
    STEPS("health_steps", "android.permission.health.READ_STEPS", R.string.health_metric_steps),

    /** The newest heart rate sample from the last hour, in beats per minute. */
    HEART_RATE("health_heart_rate", "android.permission.health.READ_HEART_RATE", R.string.health_metric_heart_rate),

    /** Minutes asleep in sleep sessions that ended in the last 24 hours. */
    SLEEP("health_sleep_minutes", "android.permission.health.READ_SLEEP", R.string.health_metric_sleep),

    /** Exercise minutes since local midnight. */
    EXERCISE("health_exercise_minutes", "android.permission.health.READ_EXERCISE", R.string.health_metric_exercise),
    ;

    /** A total that starts over at local midnight, so it needs a read just after one. */
    val resetsAtMidnight: Boolean
        get() = this == STEPS || this == EXERCISE

    companion object {
        fun forStateKey(key: String?): HealthMetric? =
            key?.let(::normalizeStateKey)?.let { normalized -> entries.firstOrNull { it.stateKey == normalized } }

        val stateKeys: Set<String> = entries.mapTo(linkedSetOf(), HealthMetric::stateKey)
    }
}

/** One sleep session, with how much of it its stages mark as awake or out of bed. */
data class SleepSpan(val start: Instant, val end: Instant, val awake: Duration = Duration.ZERO)

/** Which readings the enabled profiles compare, and how often to read them. */
data class HealthPollPlan(val metrics: Set<HealthMetric>, val intervalMinutes: Long)

object HealthPolling {
    /** WorkManager won't run periodic work more often than this. */
    const val MIN_INTERVAL_MINUTES = 15L
    const val MAX_INTERVAL_MINUTES = 24 * 60L

    /** The STATE context field that sets how often its reading is checked. */
    const val POLL_MINUTES_FIELD = "pollMinutes"

    /** Lets OpenTasker read while it's in the background, which is when the poll runs. */
    const val BACKGROUND_READ_PERMISSION = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"

    /**
     * Health Connect lets an app read 30 days back from when it was first granted access, and
     * further only with the history permission, which OpenTasker doesn't ask for.
     */
    val HISTORY_LIMIT: Duration = Duration.ofDays(30)

    fun clampInterval(minutes: Long?): Long =
        (minutes ?: MIN_INTERVAL_MINUTES).coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES)

    /**
     * The plan for [contexts], which should come from enabled profiles only. Null when none of them
     * reads Health Connect. With several health contexts the most frequent one sets the interval.
     */
    fun plan(contexts: Iterable<ContextSpec>): HealthPollPlan? {
        val health = contexts.filter { it.type == ContextType.STATE }
            .mapNotNull { spec -> HealthMetric.forStateKey(stateContextKey(spec))?.let { spec to it } }
        if (health.isEmpty()) return null
        val interval = health.minOf { (spec, _) ->
            // The editor accepts any number in range, so 30.5 reads as 30 rather than the default.
            clampInterval(spec.config[POLL_MINUTES_FIELD]?.trim()?.toDoubleOrNull()?.toLong())
        }
        return HealthPollPlan(health.mapTo(linkedSetOf()) { it.second }, interval)
    }

    /** The span [metric] is read over at [now]. Never reaches further back than [HISTORY_LIMIT]. */
    fun readRange(metric: HealthMetric, now: Instant, zone: ZoneId): Pair<Instant, Instant> {
        val start = when (metric) {
            HealthMetric.STEPS, HealthMetric.EXERCISE -> now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
            HealthMetric.HEART_RATE -> now.minus(Duration.ofHours(1))
            HealthMetric.SLEEP -> now.minus(Duration.ofHours(24))
        }
        return clampToHistory(start, now) to now
    }

    /**
     * Where the record query for [metric] starts. Health Connect matches session and series
     * records by their start time, so a night's sleep that began before the window, or a heart
     * rate series still running from earlier, would be missed by querying the window alone.
     */
    fun queryStart(metric: HealthMetric, now: Instant, zone: ZoneId): Instant {
        val windowStart = readRange(metric, now, zone).first
        val lookback = when (metric) {
            HealthMetric.SLEEP -> Duration.ofHours(12)
            HealthMetric.HEART_RATE -> Duration.ofHours(5)
            HealthMetric.STEPS, HealthMetric.EXERCISE -> Duration.ZERO
        }
        return clampToHistory(windowStart.minus(lookback), now)
    }

    /** A read starting before [HISTORY_LIMIT] would be refused, so it starts at the limit instead. */
    internal fun clampToHistory(start: Instant, now: Instant): Instant = maxOf(start, now.minus(HISTORY_LIMIT))

    /** How long until the read that starts the daily totals over, a minute past local midnight. */
    fun untilMidnightRead(now: Instant, zone: ZoneId): Duration {
        val midnight = now.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()
        return Duration.between(now, midnight).plus(MIDNIGHT_READ_SLACK)
    }

    private val MIDNIGHT_READ_SLACK: Duration = Duration.ofMinutes(1)

    /**
     * Minutes asleep in the sessions that ended inside the window, each counted whole, minus their
     * awake stages. Counting by end time keeps a night that started before the window from showing
     * up as half a night. Null when no session ended in the window, which is no data rather than
     * no sleep, so an "under 6 hours" profile doesn't match on a phone with no sleep tracker.
     */
    fun sleepMinutes(sessions: List<SleepSpan>, windowStart: Instant, windowEnd: Instant): Long? {
        val ended = sessions.filter { it.end.isAfter(windowStart) && !it.end.isAfter(windowEnd) }
        if (ended.isEmpty()) return null
        return ended.sumOf { span ->
            Duration.between(span.start, span.end).minus(span.awake).coerceAtLeast(Duration.ZERO).toMinutes()
        }
    }

    /** The newest sample inside the window, whichever record or source it came from. */
    fun newestSample(samples: List<Pair<Instant, Long>>, windowStart: Instant, windowEnd: Instant): Long? =
        samples.filter { (time, _) -> !time.isBefore(windowStart) && !time.isAfter(windowEnd) }
            .maxByOrNull { it.first }
            ?.second

    /** The metrics [contexts] compare, whether or not their profiles are enabled. */
    fun metrics(contexts: Iterable<ContextSpec>): Set<HealthMetric> =
        contexts.filter { it.type == ContextType.STATE }
            .mapNotNullTo(linkedSetOf()) { HealthMetric.forStateKey(stateContextKey(it)) }

    /**
     * True when [spec] compares a Health Connect reading that [state] doesn't have. An inverted
     * context must not match then: "NOT 10,000 steps" isn't true just because the read was refused.
     */
    fun readingMissing(spec: ContextSpec, state: Map<String, String>): Boolean {
        if (spec.type != ContextType.STATE) return false
        val metric = HealthMetric.forStateKey(stateContextKey(spec)) ?: return false
        return state[metric.stateKey].isNullOrBlank()
    }

    /** What Setup asks Health Connect for: a read permission per metric, plus background reads. */
    fun requestedPermissions(metrics: Set<HealthMetric>): Set<String> =
        metrics.mapTo(linkedSetOf(), HealthMetric::readPermission) + BACKGROUND_READ_PERMISSION

    fun encodeMetrics(metrics: Set<HealthMetric>): String = metrics.joinToString(",") { it.name }

    fun decodeMetrics(encoded: String?): Set<HealthMetric> =
        encoded.orEmpty().split(',')
            .mapNotNullTo(linkedSetOf()) { name -> HealthMetric.entries.firstOrNull { it.name == name.trim() } }
}
