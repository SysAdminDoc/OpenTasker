package com.opentasker.core.health

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.opentasker.app.R
import com.opentasker.core.storage.ScheduledWorkerId
import com.opentasker.core.storage.recordingOutcome
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Reads Health Connect for the enabled profiles' health STATE contexts and publishes the readings
 * to [HealthStateEvents]. Health Connect has no change broadcasts an app can listen for, so this
 * polls, at the shortest interval any of those contexts asks for.
 */
class HealthConnectPollWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result =
        recordingOutcome(ScheduledWorkerId.HEALTH_CONNECT_POLL) { runWork() }

    private suspend fun runWork(): Result {
        val metrics = HealthPolling.decodeMetrics(inputData.getString(KEY_METRICS))
        if (metrics.isEmpty()) return Result.success()
        val context = applicationContext
        val readings = HealthConnectReader(context).read(metrics)
        HealthStateEvents.publish(
            healthStatePatch(
                readings,
                deniedDetail = { metric ->
                    context.getString(R.string.health_setup_denied_detail, context.getString(metric.labelRes))
                },
                unavailableDetail = context.getString(R.string.health_setup_unavailable_detail),
                failedBefore = recordFailures(readings),
            ),
        )
        // The midnight read doesn't book the next one, since that would replace the work running it.
        if (!inputData.getBoolean(KEY_MIDNIGHT, false)) scheduleMidnightRead(context, metrics)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "health_connect_poll"
        private const val READ_NOW_WORK_NAME = "health_connect_read_now"
        private const val MIDNIGHT_WORK_NAME = "health_connect_midnight"
        private const val KEY_METRICS = "metrics"
        private const val KEY_MIDNIGHT = "midnight"

        private var syncedPlan: HealthPollPlan? = null
        private var synced = false
        private val failedLastRead = mutableSetOf<HealthMetric>()

        /** Notes which reads failed this time and returns the ones that had failed the time before. */
        @Synchronized
        private fun recordFailures(readings: Map<HealthMetric, HealthReading>): Set<HealthMetric> {
            val before = failedLastRead.toSet()
            readings.forEach { (metric, reading) ->
                if (reading == HealthReading.Failed) failedLastRead += metric else failedLastRead -= metric
            }
            return before
        }

        /**
         * Steps and exercise count from local midnight, so a read just after it starts them over.
         * Without it yesterday's total would stand until the next poll, which can be most of a day.
         */
        private fun scheduleMidnightRead(context: Context, metrics: Set<HealthMetric>) {
            val daily = metrics.filterTo(linkedSetOf(), HealthMetric::resetsAtMidnight)
            if (daily.isEmpty()) return
            val delay = HealthPolling.untilMidnightRead(Instant.now(), ZoneId.systemDefault())
            val request = OneTimeWorkRequestBuilder<HealthConnectPollWorker>()
                .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(KEY_METRICS to HealthPolling.encodeMetrics(daily), KEY_MIDNIGHT to true))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(MIDNIGHT_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        /** The plan last handed to WorkManager in this process; Setup asks for its permissions. */
        val currentPlan: HealthPollPlan?
            @Synchronized get() = syncedPlan

        /**
         * Schedules polling for [plan], or stops it when [plan] is null. A new or changed plan
         * replaces the old work, which also reads right away instead of after a whole interval.
         */
        @Synchronized
        fun sync(context: Context, plan: HealthPollPlan?) {
            if (synced && plan == syncedPlan) return
            val workManager = WorkManager.getInstance(context)
            if (plan == null) {
                workManager.cancelUniqueWork(WORK_NAME)
                workManager.cancelUniqueWork(READ_NOW_WORK_NAME)
                workManager.cancelUniqueWork(MIDNIGHT_WORK_NAME)
                HealthStateEvents.clear()
            } else {
                syncedPlan?.let { old -> HealthStateEvents.clear(old.metrics - plan.metrics) }
                // The read this enqueues books a midnight read again if the new plan needs one.
                if (plan.metrics.none(HealthMetric::resetsAtMidnight)) workManager.cancelUniqueWork(MIDNIGHT_WORK_NAME)
                val request = PeriodicWorkRequestBuilder<HealthConnectPollWorker>(
                    plan.intervalMinutes,
                    TimeUnit.MINUTES,
                )
                    .setInputData(workDataOf(KEY_METRICS to HealthPolling.encodeMetrics(plan.metrics)))
                    .build()
                workManager.enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
                    request,
                )
            }
            syncedPlan = plan
            synced = true
        }

        /** One read now, for after Setup gets a permission, so the gap clears without waiting. */
        fun readNow(context: Context) {
            val plan = currentPlan ?: return
            val request = OneTimeWorkRequestBuilder<HealthConnectPollWorker>()
                .setInputData(workDataOf(KEY_METRICS to HealthPolling.encodeMetrics(plan.metrics)))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(READ_NOW_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
