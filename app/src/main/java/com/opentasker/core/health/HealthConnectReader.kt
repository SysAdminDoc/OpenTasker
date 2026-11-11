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
import java.time.Duration
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
        // Session and series records are matched by start time, so those queries reach back further.
        val recordRange = TimeRangeFilter.between(HealthPolling.queryStart(metric, now, zone), end)
        return try {
            HealthReading.Value(
                when (metric) {
                    // No steps or exercise yet today is a real zero.
                    HealthMetric.STEPS ->
                        (client.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), range))[StepsRecord.COUNT_TOTAL] ?: 0L)
                            .toString()
                    HealthMetric.EXERCISE ->
                        (client.aggregate(AggregateRequest(setOf(ExerciseSessionRecord.EXERCISE_DURATION_TOTAL), range))[ExerciseSessionRecord.EXERCISE_DURATION_TOTAL]
                            ?.toMinutes() ?: 0L).toString()
                    HealthMetric.SLEEP -> HealthPolling.sleepMinutes(sleepSpans(client, recordRange), start, end)?.toString().orEmpty()
                    HealthMetric.HEART_RATE -> newestHeartRate(client, recordRange, start, end)?.toString().orEmpty()
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

    /**
     * Several sources can write heart rate at once, a watch's long series next to a phone app's
     * single readings, so the newest sample is picked across every recent record rather than
     * taken from whichever record started last.
     */
    private suspend fun newestHeartRate(
        client: HealthConnectClient,
        range: TimeRangeFilter,
        start: Instant,
        end: Instant,
    ): Long? {
        val records = client.readRecords(
            ReadRecordsRequest(HeartRateRecord::class, range, ascendingOrder = false, pageSize = HEART_RATE_PAGE_SIZE),
        ).records
        return HealthPolling.newestSample(
            records.flatMap { record -> record.samples.map { it.time to it.beatsPerMinute } },
            start,
            end,
        )
    }

    private suspend fun sleepSpans(client: HealthConnectClient, range: TimeRangeFilter): List<SleepSpan> =
        client.readRecords(ReadRecordsRequest(SleepSessionRecord::class, range)).records.map { session ->
            SleepSpan(
                start = session.startTime,
                end = session.endTime,
                awake = session.stages
                    .filter { it.stage in AWAKE_STAGES }
                    .fold(Duration.ZERO) { total, stage ->
                        // Clipped to the session, in case a source writes a stage past its edges.
                        val from = maxOf(stage.startTime, session.startTime)
                        val to = minOf(stage.endTime, session.endTime)
                        if (to.isAfter(from)) total.plus(Duration.between(from, to)) else total
                    },
            )
        }

    private companion object {
        const val TAG = "HealthConnectReader"
        const val HEART_RATE_PAGE_SIZE = 100
        val AWAKE_STAGES = setOf(
            SleepSessionRecord.STAGE_TYPE_AWAKE,
            SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
            SleepSessionRecord.STAGE_TYPE_OUT_OF_BED,
        )
    }
}
