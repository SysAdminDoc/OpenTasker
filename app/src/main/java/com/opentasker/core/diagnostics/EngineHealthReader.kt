package com.opentasker.core.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.job.JobScheduler
import android.app.job.PendingJobReasonsInfo
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.opentasker.core.engine.EngineHeartbeatStore
import com.opentasker.core.engine.EngineWatchdogWorker
import com.opentasker.core.engine.ActiveExecutionRegistry
import com.opentasker.core.engine.EngineExitCorrelation
import com.opentasker.core.engine.EngineExitCorrelationState
import com.opentasker.core.engine.HistoricalProcessExit
import com.opentasker.core.engine.correlateProcessExit
import com.opentasker.core.engine.needsRecovery
import com.opentasker.core.external.ExternalExecutions
import com.opentasker.core.scheduling.AlarmSchedulePrecision
import com.opentasker.core.storage.ScheduledWorkOutcome
import com.opentasker.core.storage.ScheduledWorkOutcomeStore
import com.opentasker.core.scheduling.ExactAlarmSupport
import java.time.Duration
import kotlinx.coroutines.flow.first

data class ScheduledJobDiagnostics(
    val currentAvailable: Boolean,
    val currentReasons: String? = null,
    val historyAvailable: Boolean,
    val history: String? = null,
    val aggregateStatsAvailable: Boolean,
    val aggregateStats: String? = null,
) {
    companion object {
        fun unavailable(): ScheduledJobDiagnostics = ScheduledJobDiagnostics(
            currentAvailable = false,
            historyAvailable = false,
            aggregateStatsAvailable = false,
        )
    }
}

fun interface ScheduledJobDiagnosticsSource {
    fun read(context: Context, nowMillis: Long): ScheduledJobDiagnostics
}

data class EngineHealthStatus(
    val serviceRunning: Boolean,
    val lastHeartbeatAtMillis: Long,
    val activeForegroundServiceTypes: String,
    val standbyBucket: String,
    val standbyConsequence: String = "Standby bucket consequence unavailable.",
    /** True when the app-standby bucket (RARE/RESTRICTED) throttles the per-minute alarm/workers. */
    val standbyThrottled: Boolean,
    /** True when Android 16 Advanced Protection Mode is active and may degrade automation features. */
    val advancedProtectionEnabled: Boolean,
    val exactAlarmStatus: String,
    val lastMatcherError: String?,
    val lastMatcherErrorAtMillis: Long,
    val lastWorkerStopReason: String?,
    val processExitCorrelation: EngineExitCorrelation = EngineExitCorrelation(EngineExitCorrelationState.UNAVAILABLE),
    val pendingScheduledJobs: ScheduledJobDiagnostics = ScheduledJobDiagnostics.unavailable(),
    /** Last recorded outcome per scheduled worker, newest write wins. Empty until one runs. */
    val scheduledWorkOutcomes: List<ScheduledWorkOutcome> = emptyList(),
    val activeExecutionCount: Int = 0,
    val pendingExecutionCount: Int = 0,
    val signals: List<HealthSignal> = emptyList(),
)

data class HistoricalProcessExitRead(
    val platformAvailable: Boolean,
    val records: List<HistoricalProcessExit>,
)

fun interface HistoricalProcessExitSource {
    fun read(context: Context): HistoricalProcessExitRead
}

private object AndroidHistoricalProcessExitSource : HistoricalProcessExitSource {
    override fun read(context: Context): HistoricalProcessExitRead {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return HistoricalProcessExitRead(platformAvailable = false, records = emptyList())
        }
        val activityManager = context.getSystemService(ActivityManager::class.java)
            ?: return HistoricalProcessExitRead(platformAvailable = false, records = emptyList())
        return runCatching {
            HistoricalProcessExitRead(
                platformAvailable = true,
                records = activityManager
                    .getHistoricalProcessExitReasons(context.packageName, 0, EngineHealthReader.MAX_HISTORICAL_EXITS)
                    .orEmpty()
                    .map { exit ->
                        HistoricalProcessExit(
                            timestampMillis = exit.timestamp,
                            reason = EngineHealthReader.applicationExitReasonLabel(exit.reason),
                            description = exit.description
                                ?.replace(Regex("[\\r\\n]+"), " ")
                                ?.trim()
                                ?.take(EngineHealthReader.MAX_EXIT_DESCRIPTION_CHARS)
                                ?.takeIf(String::isNotBlank),
                        )
                    },
            )
        }.getOrElse {
            HistoricalProcessExitRead(platformAvailable = false, records = emptyList())
        }
    }
}

private object AndroidScheduledJobDiagnosticsSource : ScheduledJobDiagnosticsSource {
    override fun read(context: Context, nowMillis: Long): ScheduledJobDiagnostics =
        EngineHealthReader.readScheduledJobDiagnostics(context, nowMillis)
}

val EngineHealthStatus.assessment: HealthAssessment
    get() = assessHealth(signals)

val EngineHealthStatus.healthy: Boolean
    get() = assessment.healthy

object EngineHealthReader {
    suspend fun read(
        context: Context,
        nowMillis: Long = System.currentTimeMillis(),
        processExitSource: HistoricalProcessExitSource = AndroidHistoricalProcessExitSource,
        scheduledJobSource: ScheduledJobDiagnosticsSource = AndroidScheduledJobDiagnosticsSource,
    ): EngineHealthStatus {
        val heartbeatStore = EngineHeartbeatStore(context)
        val persisted = heartbeatStore.readPersistedHealth()
        val processExitCorrelation = persisted.processExitCorrelation ?: correlateProcessExit(
            heartbeat = persisted.heartbeat,
            nowMillis = nowMillis,
            read = processExitSource.read(context),
        )
        val workerStopReason = runCatching {
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWorkFlow(EngineWatchdogWorker.WORK_NAME)
                .first()
                .firstOrNull()
                ?.stopReason
        }.getOrNull()
        val pendingJobs = scheduledJobSource.read(context, nowMillis)
        val scheduledWorkOutcomes = ScheduledWorkOutcomeStore(context).read()
        val activeExecutionCount = ActiveExecutionRegistry.active.value.size
        val pendingExecutionCount = ExternalExecutions.snapshot(context)
            .count { !it.state.isTerminal }
        val serviceState = when {
            persisted.heartbeat.lastAliveAtMillis <= 0L -> HealthSignalState.Loading
            persisted.heartbeat.needsRecovery(nowMillis) -> HealthSignalState.Stale
            else -> HealthSignalState.Ready
        }
        val serviceReason = when (serviceState) {
            HealthSignalState.Loading -> "The engine has not published its first heartbeat."
            HealthSignalState.Stale -> "Last heartbeat was ${ageLabel(nowMillis - persisted.heartbeat.lastAliveAtMillis)} ago."
            else -> "Heartbeat is current."
        }
        val matcherState = if (persisted.lastMatcherError != null) HealthSignalState.Error else serviceState
        val matcherReason = persisted.lastMatcherError?.let(DiagnosticExport::redactSensitive)
            ?: if (matcherState == HealthSignalState.Ready) "Profile matchers are reporting normally." else serviceReason
        val standbyBucket = standbyBucketLabel(context)
        val standbyConsequence = standbyConsequence(context)
        val standbyThrottled = standbyBucketThrottled(context)
        val standbyState = if (standbyThrottled) HealthSignalState.Stale else HealthSignalState.Ready
        val exactAlarm = ExactAlarmSupport.schedulePrecision(context)
        val exactAlarmState = if (exactAlarm == AlarmSchedulePrecision.Exact) {
            HealthSignalState.Ready
        } else {
            HealthSignalState.Stale
        }
        val watchdogState = when {
            workerStopReason == null || workerStopReason == WorkInfo.STOP_REASON_NOT_STOPPED -> HealthSignalState.Ready
            else -> HealthSignalState.Error
        }
        val scheduledState = when {
            !pendingJobs.currentAvailable -> HealthSignalState.Loading
            pendingJobs.currentReasons != null -> HealthSignalState.Stale
            else -> HealthSignalState.Ready
        }
        val processExitState = when (processExitCorrelation.state) {
            EngineExitCorrelationState.MATCHED,
            EngineExitCorrelationState.NO_MATCH,
            -> HealthSignalState.Stale
            EngineExitCorrelationState.UNAVAILABLE -> HealthSignalState.Loading
            EngineExitCorrelationState.NO_GAP -> HealthSignalState.Ready
        }
        val signals = listOf(
            HealthSignal("engine", "Automation service", serviceState, persisted.heartbeat.lastAliveAtMillis, serviceReason),
            HealthSignal("matchers", "Profile matchers", matcherState, persisted.lastMatcherErrorAtMillis.takeIf { it > 0 } ?: persisted.heartbeat.lastAliveAtMillis, matcherReason),
            HealthSignal(
                "standby",
                "App standby",
                standbyState,
                nowMillis,
                standbyConsequence,
            ),
            HealthSignal(
                "exact-alarm",
                "Time alarms",
                exactAlarmState,
                nowMillis,
                if (exactAlarmState == HealthSignalState.Ready) "Exact alarm delivery is available." else "Exact alarms are unavailable; using the inexact Doze fallback.",
            ),
            HealthSignal(
                "watchdog",
                "Watchdog worker",
                watchdogState,
                nowMillis,
                workerStopReason?.let(::workerStopReasonLabel) ?: "No worker stop failure is recorded.",
            ),
            HealthSignal(
                "scheduled-jobs",
                "Scheduled jobs",
                scheduledState,
                nowMillis,
                scheduledJobSignalReason(pendingJobs),
                required = false,
            ),
            HealthSignal(
                "process-exit",
                "Process exit correlation",
                processExitState,
                processExitCorrelation.timestampMillis ?: nowMillis,
                processExitReason(processExitCorrelation),
                required = false,
            ),
            HealthSignal(
                "advanced-protection",
                "Advanced Protection",
                if (AdvancedProtectionReader.isEnabled(context)) HealthSignalState.Stale else HealthSignalState.Ready,
                nowMillis,
                if (AdvancedProtectionReader.isEnabled(context)) "Android Advanced Protection is enabled and may limit privileged extensions." else "Advanced Protection is not limiting this app.",
                required = false,
            ),
            HealthSignal(
                "executions",
                "Pending executions",
                HealthSignalState.Ready,
                nowMillis,
                "$pendingExecutionCount external and $activeExecutionCount active execution(s) are tracked.",
                required = false,
            ),
        )
        return EngineHealthStatus(
            serviceRunning = serviceState == HealthSignalState.Ready,
            lastHeartbeatAtMillis = persisted.heartbeat.lastAliveAtMillis,
            activeForegroundServiceTypes = foregroundServiceTypeLabel(persisted.heartbeat.foregroundServiceTypes),
            standbyBucket = standbyBucket,
            standbyConsequence = standbyConsequence,
            standbyThrottled = standbyThrottled,
            advancedProtectionEnabled = AdvancedProtectionReader.isEnabled(context),
            exactAlarmStatus = when (exactAlarm) {
                AlarmSchedulePrecision.Exact -> "Exact allowed"
                AlarmSchedulePrecision.InexactFallback -> "Inexact Doze fallback"
            },
            lastMatcherError = persisted.lastMatcherError?.let(DiagnosticExport::redactSensitive),
            lastMatcherErrorAtMillis = persisted.lastMatcherErrorAtMillis,
            lastWorkerStopReason = workerStopReason?.let(::workerStopReasonLabel),
            processExitCorrelation = processExitCorrelation,
            pendingScheduledJobs = pendingJobs,
            scheduledWorkOutcomes = scheduledWorkOutcomes,
            activeExecutionCount = activeExecutionCount,
            pendingExecutionCount = pendingExecutionCount,
            signals = signals,
        )
    }

    /** Capture the previous process lifetime before AutomationService overwrites its heartbeat. */
    internal fun captureStartupProcessExitCorrelation(
        context: Context,
        nowMillis: Long = System.currentTimeMillis(),
        processExitSource: HistoricalProcessExitSource = AndroidHistoricalProcessExitSource,
    ) {
        val store = EngineHeartbeatStore(context)
        val persisted = store.readPersistedHealth()
        store.recordProcessExitCorrelation(
            correlateProcessExit(
                heartbeat = persisted.heartbeat,
                nowMillis = nowMillis,
                read = processExitSource.read(context),
            ),
        )
    }

    internal fun correlateProcessExit(
        heartbeat: com.opentasker.core.engine.EngineHeartbeat,
        nowMillis: Long,
        read: HistoricalProcessExitRead,
        staleAfterMillis: Long = EngineHeartbeatStore.STALE_AFTER_MS,
    ): EngineExitCorrelation = correlateProcessExit(
        heartbeat = heartbeat,
        nowMillis = nowMillis,
        platformAvailable = read.platformAvailable,
        exits = read.records,
        staleAfterMillis = staleAfterMillis,
    )

    internal fun processExitReason(correlation: EngineExitCorrelation): String = when (correlation.state) {
        EngineExitCorrelationState.UNAVAILABLE ->
            "ApplicationExitInfo is unavailable below Android 11 (API 30) or could not be read."
        EngineExitCorrelationState.NO_GAP ->
            "No unexpected heartbeat gap is pending."
        EngineExitCorrelationState.NO_MATCH ->
            "Heartbeat gap detected, but no matching historical process-exit record was reported."
        EngineExitCorrelationState.MATCHED -> buildString {
            append("Reason: ").append(correlation.reason ?: "Unknown")
            correlation.description?.let { append(" (").append(it).append(")") }
            correlation.gapMillis?.let { append(". Heartbeat gap: ").append(ageLabel(it)) }
        }
    }

    internal fun applicationExitReasonLabel(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH -> "Java crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "Dependency died"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Excessive resource usage"
        ApplicationExitInfo.REASON_EXIT_SELF -> "Process exited itself"
        ApplicationExitInfo.REASON_FREEZER -> "App freezer"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "Initialization failure"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "Low memory"
        ApplicationExitInfo.REASON_OTHER -> "System or other reason"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "Package state changed"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "Package updated"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "Permission changed"
        ApplicationExitInfo.REASON_SIGNALED -> "Signal"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "User requested"
        ApplicationExitInfo.REASON_USER_STOPPED -> "User stopped app"
        else -> "Reason $reason"
    }

    internal fun ageLabel(ageMillis: Long): String = when {
        ageMillis < 1_000L -> "less than a second"
        ageMillis < 60_000L -> "${ageMillis / 1_000L}s"
        else -> "${ageMillis / 60_000L}m"
    }

    internal fun readScheduledJobDiagnostics(context: Context, nowMillis: Long): ScheduledJobDiagnostics {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return ScheduledJobDiagnostics.unavailable()
        }
        val scheduler = context.getSystemService(JobScheduler::class.java)
            ?: return ScheduledJobDiagnostics.unavailable()
        val jobs = runCatching { scheduler.allPendingJobs }.getOrElse {
            return ScheduledJobDiagnostics.unavailable()
        }
        val current = readCurrentPendingJobReasons(scheduler, jobs)
        val history = if (Build.VERSION.SDK_INT >= 36) {
            readPendingJobHistory(scheduler, jobs, nowMillis)
        } else {
            PlatformScheduledJobSignal(available = false, value = null)
        }
        val aggregateStats = if (Build.VERSION.SDK_INT >= 37) {
            readPendingJobAggregateStats(scheduler, jobs)
        } else {
            PlatformScheduledJobSignal(available = false, value = null)
        }
        return ScheduledJobDiagnostics(
            currentAvailable = current.available,
            currentReasons = current.value,
            historyAvailable = history.available,
            history = history.value,
            aggregateStatsAvailable = aggregateStats.available,
            aggregateStats = aggregateStats.value,
        )
    }

    private data class PlatformScheduledJobSignal(
        val available: Boolean,
        val value: String?,
    )

    @RequiresApi(34)
    private fun readCurrentPendingJobReasons(
        scheduler: JobScheduler,
        jobs: List<android.app.job.JobInfo>,
    ): PlatformScheduledJobSignal {
        var successfulReads = 0
        val reasons = jobs.flatMap { job ->
            runCatching {
                val pendingReasons = if (Build.VERSION.SDK_INT >= 36) {
                    readPendingJobReasonsApi36(scheduler, job.id)
                } else {
                    listOf(readPendingJobReasonApi34(scheduler, job.id))
                }
                successfulReads += 1
                pendingReasons
                    .filter(::isReportablePendingJobReason)
                    .map { reason -> "job ${job.id}: ${pendingJobReasonLabel(reason)}" }
            }.getOrElse { emptyList() }
        }.distinct().sorted().take(MAX_PENDING_DIAGNOSTIC_ENTRIES)
        return PlatformScheduledJobSignal(
            available = jobs.isEmpty() || successfulReads > 0,
            value = reasons.takeIf { it.isNotEmpty() }?.joinToString("; "),
        )
    }

    @RequiresApi(34)
    private fun readPendingJobReasonApi34(scheduler: JobScheduler, jobId: Int): Int =
        scheduler.getPendingJobReason(jobId)

    @RequiresApi(36)
    private fun readPendingJobReasonsApi36(scheduler: JobScheduler, jobId: Int): List<Int> =
        scheduler.getPendingJobReasons(jobId).toList()

    @RequiresApi(36)
    private fun readPendingJobHistory(
        scheduler: JobScheduler,
        jobs: List<android.app.job.JobInfo>,
        nowMillis: Long,
    ): PlatformScheduledJobSignal {
        if (jobs.isEmpty()) return PlatformScheduledJobSignal(available = true, value = null)
        var successfulReads = 0
        val history = jobs.flatMap { job ->
            runCatching {
                val snapshots: List<PendingJobReasonsInfo> = scheduler.getPendingJobReasonsHistory(job.id)
                successfulReads += 1
                snapshots.flatMap { snapshot ->
                    val age = ageLabel((nowMillis - snapshot.timestampMillis).coerceAtLeast(0L))
                    snapshot.getPendingJobReasons()
                        .filter(::isReportablePendingJobReason)
                        .map { reason -> "job ${job.id}, $age ago: ${pendingJobReasonLabel(reason)}" }
                }
            }.getOrElse { emptyList() }
        }.distinct().takeLast(MAX_PENDING_DIAGNOSTIC_ENTRIES)
        return PlatformScheduledJobSignal(
            available = successfulReads > 0,
            value = history.takeIf { it.isNotEmpty() }?.joinToString("; "),
        )
    }

    @RequiresApi(37)
    private fun readPendingJobAggregateStats(
        scheduler: JobScheduler,
        jobs: List<android.app.job.JobInfo>,
    ): PlatformScheduledJobSignal {
        if (jobs.isEmpty()) return PlatformScheduledJobSignal(available = true, value = null)
        var successfulReads = 0
        val stats = jobs.flatMap { job ->
            runCatching {
                val durations = scheduler.getPendingJobReasonStats(job.id)
                successfulReads += 1
                durations.entries
                    .filter { (reason, _) -> isReportablePendingJobReason(reason) }
                    .sortedByDescending { (_, duration) -> duration.toMillis() }
                    .map { (reason, duration) ->
                        "job ${job.id}: ${pendingJobReasonLabel(reason)} for ${durationLabel(duration)}"
                    }
            }.getOrElse { emptyList() }
        }.distinct().take(MAX_PENDING_DIAGNOSTIC_ENTRIES)
        return PlatformScheduledJobSignal(
            available = successfulReads > 0,
            value = stats.takeIf { it.isNotEmpty() }?.joinToString("; "),
        )
    }

    internal fun scheduledJobSignalReason(diagnostics: ScheduledJobDiagnostics): String = when {
        !diagnostics.currentAvailable ->
            "Pending-job diagnostics are unavailable on this Android version or could not be read."
        diagnostics.currentReasons != null -> buildString {
            append("Blocked by ").append(diagnostics.currentReasons).append('.')
            if (!diagnostics.historyAvailable) append(" Pending-job history is unavailable.")
            if (!diagnostics.aggregateStatsAvailable) append(" Aggregate pending time is unavailable.")
        }
        else -> buildString {
            append("No pending scheduler constraints are reported.")
            if (!diagnostics.historyAvailable) append(" Pending-job history is unavailable.")
            if (!diagnostics.aggregateStatsAvailable) append(" Aggregate pending time is unavailable.")
        }
    }

    /**
     * Drops non-actionable reason codes: a job that is currently running, has no constraint holding
     * it, or reports an undefined/optimization state is not something the user can act on. Pure so
     * the filter is unit-testable.
     */
    internal fun isReportablePendingJobReason(reason: Int): Boolean = when (reason) {
        android.app.job.JobScheduler.PENDING_JOB_REASON_UNDEFINED,
        android.app.job.JobScheduler.PENDING_JOB_REASON_EXECUTING,
        android.app.job.JobScheduler.PENDING_JOB_REASON_INVALID_JOB_ID,
        android.app.job.JobScheduler.PENDING_JOB_REASON_JOB_SCHEDULER_OPTIMIZATION,
        -> false
        else -> true
    }

    /**
     * Maps a `JobScheduler.PENDING_JOB_REASON_*` code to a plain-language cause. Constant values are
     * inlined at compile time, so this is a pure Int→String mapping that is unit-testable on the JVM
     * without a JobScheduler.
     */
    internal fun pendingJobReasonLabel(reason: Int): String = when (reason) {
        JobScheduler.PENDING_JOB_REASON_APP -> "App state"
        android.app.job.JobScheduler.PENDING_JOB_REASON_APP_STANDBY -> "App standby bucket"
        android.app.job.JobScheduler.PENDING_JOB_REASON_BACKGROUND_RESTRICTION -> "Background restricted"
        android.app.job.JobScheduler.PENDING_JOB_REASON_CONSTRAINT_BATTERY_NOT_LOW -> "Battery low"
        android.app.job.JobScheduler.PENDING_JOB_REASON_CONSTRAINT_CHARGING -> "Not charging"
        android.app.job.JobScheduler.PENDING_JOB_REASON_CONSTRAINT_CONNECTIVITY -> "No connectivity"
        android.app.job.JobScheduler.PENDING_JOB_REASON_CONSTRAINT_CONTENT_TRIGGER -> "Content trigger"
        android.app.job.JobScheduler.PENDING_JOB_REASON_CONSTRAINT_DEVICE_IDLE -> "Device not idle"
        android.app.job.JobScheduler.PENDING_JOB_REASON_CONSTRAINT_MINIMUM_LATENCY -> "Minimum delay not elapsed"
        android.app.job.JobScheduler.PENDING_JOB_REASON_CONSTRAINT_PREFETCH -> "Prefetch window"
        android.app.job.JobScheduler.PENDING_JOB_REASON_CONSTRAINT_STORAGE_NOT_LOW -> "Storage low"
        android.app.job.JobScheduler.PENDING_JOB_REASON_DEVICE_STATE -> "Device state (thermal/power)"
        android.app.job.JobScheduler.PENDING_JOB_REASON_QUOTA -> "Out of run quota"
        android.app.job.JobScheduler.PENDING_JOB_REASON_USER -> "User restriction"
        android.app.job.JobScheduler.PENDING_JOB_REASON_CONSTRAINT_DEADLINE -> "Override deadline"
        else -> "Constraint $reason"
    }

    internal fun foregroundServiceTypeLabel(types: Int): String {
        if (types == 0) return "None recorded"
        return buildList {
            if (types and ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE != 0) add("special use")
            if (types and ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION != 0) add("location")
        }.ifEmpty { listOf("unknown ($types)") }.joinToString()
    }

    internal fun workerStopReasonLabel(reason: Int): String = when (reason) {
        WorkInfo.STOP_REASON_NOT_STOPPED -> "Not stopped"
        WorkInfo.STOP_REASON_CANCELLED_BY_APP -> "Cancelled by app"
        WorkInfo.STOP_REASON_PREEMPT -> "Preempted by a higher-priority job"
        WorkInfo.STOP_REASON_TIMEOUT -> "Timed out"
        STOP_REASON_TIMEOUT_ABANDONED -> "Timed out; job was abandoned after the app did not respond"
        WorkInfo.STOP_REASON_DEVICE_STATE -> "Device state"
        WorkInfo.STOP_REASON_CONSTRAINT_BATTERY_NOT_LOW -> "Battery constraint"
        WorkInfo.STOP_REASON_CONSTRAINT_CHARGING -> "Charging constraint"
        WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY -> "Connectivity constraint"
        WorkInfo.STOP_REASON_CONSTRAINT_DEVICE_IDLE -> "Device-idle constraint"
        WorkInfo.STOP_REASON_CONSTRAINT_STORAGE_NOT_LOW -> "Storage constraint"
        WorkInfo.STOP_REASON_QUOTA -> "Out of run quota"
        WorkInfo.STOP_REASON_BACKGROUND_RESTRICTION -> "Background restricted"
        WorkInfo.STOP_REASON_APP_STANDBY -> "App standby bucket"
        WorkInfo.STOP_REASON_USER -> "Stopped by user"
        WorkInfo.STOP_REASON_SYSTEM_PROCESSING -> "System processing"
        WorkInfo.STOP_REASON_ESTIMATED_APP_LAUNCH_TIME_CHANGED -> "Estimated app launch time changed"
        WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT -> "Foreground-service time limit"
        WorkInfo.STOP_REASON_UNKNOWN -> "Unknown"
        else -> "Reason $reason"
    }

    internal fun standbyBucketLabel(context: Context): String {
        val bucket = standbyBucketValue(context)
        if (bucket == null) {
            return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) "Unavailable before Android 9" else "Unavailable"
        }
        return when (bucket) {
            UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "Active"
            UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "Working set"
            UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "Frequent"
            UsageStatsManager.STANDBY_BUCKET_RARE -> "Rare"
            UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> "Restricted"
            else -> "Unknown"
        }
    }

    private fun standbyBucketThrottled(context: Context): Boolean {
        return standbyBucketValue(context)?.let(::isThrottledStandbyBucket) == true
    }

    internal fun standbyConsequence(context: Context): String =
        standbyBucketValue(context)?.let(::standbyConsequenceLabel)
            ?: "Standby bucket consequence is unavailable on this device."

    internal fun standbyConsequenceLabel(bucket: Int): String = when (bucket) {
        UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "Active. Time triggers and workers have minimal restrictions."
        UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "Working set. Normal background delivery is expected."
        UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "Frequent. Delivery may be deferred when the device is idle."
        UsageStatsManager.STANDBY_BUCKET_RARE -> "Rare. Time triggers and workers may be delayed."
        UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> "Restricted. Time triggers and workers may be heavily delayed."
        else -> "Unknown standby bucket. The effect on delivery can't be determined."
    }

    private fun standbyBucketValue(context: Context): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return context.getSystemService(UsageStatsManager::class.java)?.appStandbyBucket
    }

    /**
     * RARE and RESTRICTED buckets throttle the per-minute alarm and deferrable workers, degrading
     * trigger latency; ACTIVE/WORKING_SET/FREQUENT do not. Pure so bucket-to-warning mapping is
     * unit-testable without a UsageStatsManager.
     */
    internal fun isThrottledStandbyBucket(bucket: Int): Boolean =
        bucket == UsageStatsManager.STANDBY_BUCKET_RARE ||
            bucket == UsageStatsManager.STANDBY_BUCKET_RESTRICTED

    internal fun durationLabel(duration: Duration): String = durationLabel(duration.toMillis())

    internal fun durationLabel(durationMillis: Long): String {
        val seconds = durationMillis.coerceAtLeast(0L) / 1_000L
        return when {
            seconds < 1L -> "less than a second"
            seconds < 60L -> "${seconds}s"
            seconds < 3_600L -> "${seconds / 60L}m ${seconds % 60L}s"
            else -> "${seconds / 3_600L}h ${(seconds % 3_600L) / 60L}m"
        }
    }

    internal const val MAX_HISTORICAL_EXITS = 20
    internal const val MAX_EXIT_DESCRIPTION_CHARS = 512
    internal const val STOP_REASON_TIMEOUT_ABANDONED = 16
    private const val MAX_PENDING_DIAGNOSTIC_ENTRIES = 32
}
