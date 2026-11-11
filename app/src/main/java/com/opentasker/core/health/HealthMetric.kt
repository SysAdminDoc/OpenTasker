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

    /** Minutes asleep in the last 24 hours. */
    SLEEP("health_sleep_minutes", "android.permission.health.READ_SLEEP", R.string.health_metric_sleep),

    /** Exercise minutes since local midnight. */
    EXERCISE("health_exercise_minutes", "android.permission.health.READ_EXERCISE", R.string.health_metric_exercise),
    ;

    companion object {
        fun forStateKey(key: String?): HealthMetric? =
            key?.let(::normalizeStateKey)?.let { normalized -> entries.firstOrNull { it.stateKey == normalized } }

        val stateKeys: Set<String> = entries.mapTo(linkedSetOf(), HealthMetric::stateKey)
    }
}

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

    /** A read starting before [HISTORY_LIMIT] would be refused, so it starts at the limit instead. */
    internal fun clampToHistory(start: Instant, now: Instant): Instant = maxOf(start, now.minus(HISTORY_LIMIT))

    /** What Setup asks Health Connect for: a read permission per metric, plus background reads. */
    fun requestedPermissions(metrics: Set<HealthMetric>): Set<String> =
        metrics.mapTo(linkedSetOf(), HealthMetric::readPermission) + BACKGROUND_READ_PERMISSION

    fun encodeMetrics(metrics: Set<HealthMetric>): String = metrics.joinToString(",") { it.name }

    fun decodeMetrics(encoded: String?): Set<HealthMetric> =
        encoded.orEmpty().split(',')
            .mapNotNullTo(linkedSetOf()) { name -> HealthMetric.entries.firstOrNull { it.name == name.trim() } }
}
