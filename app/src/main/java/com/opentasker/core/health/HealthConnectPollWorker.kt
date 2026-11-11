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
            ),
        )
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "health_connect_poll"
        private const val READ_NOW_WORK_NAME = "health_connect_read_now"
        private const val KEY_METRICS = "metrics"

        private var syncedPlan: HealthPollPlan? = null
        private var synced = false

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
                HealthStateEvents.clear()
            } else {
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
