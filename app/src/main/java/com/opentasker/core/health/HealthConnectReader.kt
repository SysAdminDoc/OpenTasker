package com.opentasker.core.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.opentasker.core.logging.AppLogger
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException

/** Whether Health Connect can be used at all on this phone, before any permission is looked at. */
enum class HealthConnectAvailability { AVAILABLE, NEEDS_UPDATE, UNAVAILABLE }

object HealthConnectAccess {
    fun availability(context: Context): HealthConnectAvailability =
        when (HealthConnectClient.getSdkStatus(context)) {
            HealthConnectClient.SDK_AVAILABLE -> HealthConnectAvailability.AVAILABLE
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthConnectAvailability.NEEDS_UPDATE
            else -> HealthConnectAvailability.UNAVAILABLE
        }

    /**
     * True when this Health Connect build lets apps read in the background at all. Older builds
     * only allow reads while the app is on screen, and a foreground service doesn't count.
     */
    fun backgroundReadSupported(client: HealthConnectClient): Boolean =
        client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) ==
            HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
}

/** Reads the metrics a [HealthPollPlan] names. OpenTasker never writes to Health Connect. */
class HealthConnectReader(private val context: Context) {

    suspend fun read(
        metrics: Set<HealthMetric>,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Map<HealthMetric, HealthReading> {
        if (metrics.isEmpty()) return emptyMap()
        if (HealthConnectAccess.availability(context) != HealthConnectAvailability.AVAILABLE) {
            return metrics.associateWith { HealthReading.Unavailable }
        }
        val client = HealthConnectClient.getOrCreate(context)
        if (!HealthConnectAccess.backgroundReadSupported(client)) {
            return metrics.associateWith { HealthReading.Unavailable }
        }
        val granted = try {
            client.permissionController.getGrantedPermissions()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.warn(TAG, "Couldn't read Health Connect permissions", e)
            return metrics.associateWith { HealthReading.Failed }
        }
        val backgroundGranted = HealthPolling.BACKGROUND_READ_PERMISSION in granted
        return metrics.associateWith { metric ->
            if (!backgroundGranted || metric.readPermission !in granted) {
                HealthReading.Denied
            } else {
                readOne(client, metric, now, zone)
            }
        }
    }

    private suspend fun readOne(
        client: HealthConnectClient,
        metric: HealthMetric,
        now: Instant,
        zone: ZoneId,
    ): HealthReading {
        val (start, end) = HealthPolling.readRange(metric, now, zone)
        val range = TimeRangeFilter.between(start, end)
        return try {
            HealthReading.Value(
                when (metric) {
                    HealthMetric.STEPS ->
                        (client.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), range))[StepsRecord.COUNT_TOTAL] ?: 0L)
                            .toString()
                    HealthMetric.EXERCISE ->
                        (client.aggregate(AggregateRequest(setOf(ExerciseSessionRecord.EXERCISE_DURATION_TOTAL), range))[ExerciseSessionRecord.EXERCISE_DURATION_TOTAL]
                            ?.toMinutes() ?: 0L).toString()
                    HealthMetric.SLEEP ->
                        (client.aggregate(AggregateRequest(setOf(SleepSessionRecord.SLEEP_DURATION_TOTAL), range))[SleepSessionRecord.SLEEP_DURATION_TOTAL]
                            ?.toMinutes() ?: 0L).toString()
                    HealthMetric.HEART_RATE -> newestHeartRate(client, range, start, end)?.toString().orEmpty()
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            AppLogger.info(TAG, "Health Connect refused a ${metric.stateKey} read")
            HealthReading.Denied
        } catch (e: Exception) {
            AppLogger.warn(TAG, "Health Connect ${metric.stateKey} read failed", e)
            HealthReading.Failed
        }
    }

    private suspend fun newestHeartRate(
        client: HealthConnectClient,
        range: TimeRangeFilter,
        start: Instant,
        end: Instant,
    ): Long? {
        val newest = client.readRecords(
            ReadRecordsRequest(HeartRateRecord::class, range, ascendingOrder = false, pageSize = 1),
        ).records.firstOrNull() ?: return null
        return newest.samples.filter { !it.time.isBefore(start) && !it.time.isAfter(end) }
            .maxByOrNull { it.time }
            ?.beatsPerMinute
    }

    private companion object {
        const val TAG = "HealthConnectReader"
    }
}
