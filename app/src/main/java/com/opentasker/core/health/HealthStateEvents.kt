package com.opentasker.core.health

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The latest Health Connect readings, written by [HealthConnectPollWorker] and merged into STATE
 * matching. Kept merged like DeviceStateEvents, so a matcher rebuilt between polls starts with
 * every reading instead of waiting up to a whole interval for the next one.
 */
object HealthStateEvents {
    private val state = MutableStateFlow<Map<String, String>>(emptyMap())

    val events: StateFlow<Map<String, String>> = state.asStateFlow()

    fun publish(patch: Map<String, String>) {
        if (patch.isNotEmpty()) state.update { it + patch }
    }

    /** No enabled profile reads Health Connect any more, so nothing should match on an old reading. */
    fun clear() {
        state.value = emptyMap()
    }

    /** Drops readings no enabled profile reads now, so turning one back on doesn't start from them. */
    fun clear(metrics: Set<HealthMetric>) {
        if (metrics.isEmpty()) return
        val keys = metrics.flatMap { listOf(it.stateKey, "_setup_${it.stateKey}") }.toSet()
        state.update { it - keys }
    }
}

/** What one poll got for one metric. */
sealed interface HealthReading {
    /**
     * The reading as a STATE value. Blank when Health Connect has no heart rate or sleep in the
     * window; steps and exercise read 0 then, since none yet today is a real count.
     */
    data class Value(val value: String) : HealthReading

    /** Health Connect refused: the metric's read permission, or background reading, isn't granted. */
    data object Denied : HealthReading

    /** Health Connect isn't on this phone, or needs an update first. */
    data object Unavailable : HealthReading

    /** A read that failed for some other reason. The last reading stands for one more poll. */
    data object Failed : HealthReading
}

/**
 * The STATE patch for one poll. A refused or impossible read clears the value, so a profile fails
 * closed instead of acting on an old number, and sets the `_setup_` marker the Inspector explains.
 * A failed read changes nothing the first time, so one hiccup doesn't flap a profile, and clears
 * the value, with [failedDetail] as the marker, when the read before it, listed in [failedBefore],
 * failed too.
 */
internal fun healthStatePatch(
    readings: Map<HealthMetric, HealthReading>,
    deniedDetail: (HealthMetric) -> String,
    unavailableDetail: String,
    failedBefore: Set<HealthMetric> = emptySet(),
    failedDetail: (HealthMetric) -> String = { "" },
): Map<String, String> = buildMap {
    readings.forEach { (metric, reading) ->
        val marker = "_setup_${metric.stateKey}"
        when (reading) {
            is HealthReading.Value -> {
                put(metric.stateKey, reading.value)
                put(marker, "")
            }
            HealthReading.Denied -> {
                put(metric.stateKey, "")
                put(marker, deniedDetail(metric))
            }
            HealthReading.Unavailable -> {
                put(metric.stateKey, "")
                put(marker, unavailableDetail)
            }
            HealthReading.Failed -> if (metric in failedBefore) {
                put(metric.stateKey, "")
                put(marker, failedDetail(metric))
            }
        }
    }
}
