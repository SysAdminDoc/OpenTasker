package com.opentasker.core.diagnostics

import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.opentasker.app.BuildConfig
import com.opentasker.core.apps.TargetSignerGuard
import com.opentasker.core.apps.TargetSignerPinSource
import com.opentasker.core.apps.TargetSignerStatus
import com.opentasker.core.logging.AppLogger
import com.opentasker.core.engine.ExecutionAdmissionRegistry
import com.opentasker.core.permissions.UsageAccess
import com.opentasker.core.scheduling.ExactAlarmSupport
import com.opentasker.core.storage.AppDatabase
import com.opentasker.core.storage.ScheduledWorkOutcomeKind
import com.opentasker.core.storage.ScheduledWorkerId
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DiagnosticExport {

    suspend fun buildReport(context: Context, db: AppDatabase): String {
        val sb = StringBuilder()
        val now = System.currentTimeMillis()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT)

        sb.appendLine("=== OpenTasker Diagnostic Report ===")
        sb.appendLine("Generated: ${dateFormat.format(Date(now))}")
        sb.appendLine()

        sb.appendLine("--- App ---")
        sb.appendLine("Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        sb.appendLine("Distribution: ${BuildConfig.DISTRIBUTION}")
        sb.appendLine("Debug: ${BuildConfig.DEBUG}")
        sb.appendLine()

        sb.appendLine("--- Device ---")
        sb.appendLine("Model: ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
        sb.appendLine()

        sb.appendLine("--- Engine Health ---")
        try {
            val health = EngineHealthReader.read(context, now)
            sb.appendLine("Overall state: ${health.assessment.state}")
            sb.appendLine("Overall reason: ${redactSensitive(health.assessment.reason)}")
            sb.appendLine("Service running: ${health.serviceRunning}")
            sb.appendLine("Last heartbeat: ${formatTimestamp(health.lastHeartbeatAtMillis, dateFormat)}")
            sb.appendLine("Foreground service types: ${health.activeForegroundServiceTypes}")
            sb.appendLine("Standby bucket: ${health.standbyBucket}")
            sb.appendLine("Standby consequence: ${health.standbyConsequence}")
            sb.appendLine("Exact alarm: ${health.exactAlarmStatus}")
            sb.appendLine("Process exit correlation: ${health.processExitCorrelation.state}")
            sb.appendLine("Process exit reason: ${redactSensitive(health.processExitCorrelation.reason ?: "none")}")
            sb.appendLine("Process exit description: ${redactSensitive(health.processExitCorrelation.description ?: "none")}")
            sb.appendLine("Process exit timestamp: ${formatTimestamp(health.processExitCorrelation.timestampMillis ?: 0L, dateFormat)}")
            sb.appendLine("Heartbeat gap: ${health.processExitCorrelation.gapMillis?.let(EngineHealthReader::ageLabel) ?: "none"}")
            sb.appendLine("Last matcher error: ${health.lastMatcherError?.let(::redactSensitive) ?: "none"}")
            sb.appendLine("Last worker stop reason: ${health.lastWorkerStopReason?.let(::redactSensitive) ?: "none"}")
            ScheduledWorkerId.entries.forEach { worker ->
                val outcome = health.scheduledWorkOutcomes.firstOrNull { it.worker == worker }
                val summary = when {
                    outcome == null -> "no run recorded"
                    outcome.kind == ScheduledWorkOutcomeKind.STOPPED ->
                        "stopped (${EngineHealthReader.workerStopReasonLabel(outcome.stopReason)}) at " +
                            formatTimestamp(outcome.timestampMillis, dateFormat)
                    else -> "${outcome.kind.name.lowercase()} at ${formatTimestamp(outcome.timestampMillis, dateFormat)}"
                }
                sb.appendLine("Scheduled work ${worker.key}: $summary")
            }
            sb.appendLine("Pending job reasons: ${health.pendingScheduledJobs.currentReasons ?: if (health.pendingScheduledJobs.currentAvailable) "none" else "unavailable"}")
            sb.appendLine("Pending job reason history: ${health.pendingScheduledJobs.history ?: if (health.pendingScheduledJobs.historyAvailable) "none" else "unavailable"}")
            sb.appendLine("Pending job duration stats: ${health.pendingScheduledJobs.aggregateStats ?: if (health.pendingScheduledJobs.aggregateStatsAvailable) "none" else "unavailable"}")
            sb.appendLine("Active executions: ${health.activeExecutionCount}")
            sb.appendLine("Pending external executions: ${health.pendingExecutionCount}")
            health.signals.forEach { signal ->
                sb.appendLine("Health signal ${signal.key}: ${signal.state}. ${redactSensitive(signal.reason)}")
            }
        } catch (e: Exception) {
            sb.appendLine("  (failed to read engine health: ${redactSensitive(e.message.orEmpty())})")
        }
        sb.appendLine()

        sb.appendLine("--- Execution Admission ---")
        try {
            val admission = ExecutionAdmissionRegistry.snapshot(context)
            sb.appendLine("Active global: ${admission.activeGlobal}/${admission.limits.globalMaxActive}")
            sb.appendLine("Burst global: ${admission.globalBurstCount}/${admission.limits.globalBurstLimit}")
            admission.activeByProfile.toSortedMap().forEach { (profileId, active) ->
                sb.appendLine("Profile $profileId active: $active")
            }
            admission.circuits.forEach { (profileId, state) ->
                if (state.openUntilMs > now || state.strikeCount > 0 || state.lastReason != null) {
                    val owner = profileId?.toString() ?: "global"
                    sb.appendLine(
                        "Circuit $owner: openUntil=${formatTimestamp(state.openUntilMs, dateFormat)}, " +
                            "strikes=${state.strikeCount}, reason=${redactSensitive(state.lastReason ?: "none")}",
                    )
                }
            }
        } catch (e: Exception) {
            sb.appendLine("  (failed to read execution admission: ${redactSensitive(e.message.orEmpty())})")
        }
        sb.appendLine()

        sb.appendLine("--- In-App Log (recent 100) ---")
        AppLogger.snapshot().takeLast(100).forEach { entry ->
            val time = dateFormat.format(Date(entry.timestampMillis))
            sb.appendLine("  [$time] ${entry.level} ${entry.tag}: ${redactSensitive(entry.message)}")
        }
        sb.appendLine()

        sb.appendLine("--- Crash Logs ---")
        val crashes = CrashLogHandler.listCrashLogs(context)
        if (crashes.isEmpty()) {
            sb.appendLine("  none")
        } else {
            crashes.forEach { crash ->
                sb.appendLine("  === ${redactSensitive(crash.fileName)} (${dateFormat.format(Date(crash.modifiedAtMillis))}) ===")
                sb.appendLine(redactSensitive(crash.redactedContent).take(MAX_EXPORTED_CRASH_CHARS))
                if (crash.redactedContent.length > MAX_EXPORTED_CRASH_CHARS) sb.appendLine("[export excerpt truncated]")
            }
        }
        sb.appendLine()

        sb.appendLine("--- Run Log (recent 50) ---")
        try {
            val logs = db.runLogDao().getRecent().take(50)
            val logCount = db.runLogDao().count()
            sb.appendLine("Total entries: $logCount")
            for (entry in logs) {
                val time = dateFormat.format(Date(entry.timestamp))
                val status = if (entry.success) "OK" else "FAIL"
                val source = entry.sourceLabel ?: entry.source ?: ""
                sb.appendLine(
                    "  [$time] $status ${redactSensitive(entry.taskName)} " +
                        "(${entry.durationMs}ms) ${redactSensitive(source)}",
                )
                val firstLine = entry.message.lineSequence().firstOrNull()?.take(200) ?: ""
                if (firstLine.isNotBlank()) {
                    sb.appendLine("    ${redactSensitive(firstLine)}")
                }
            }
        } catch (e: Exception) {
            sb.appendLine("  (failed to read run log: ${redactSensitive(e.message.orEmpty())})")
        }
        sb.appendLine()

        sb.appendLine("--- Permissions ---")
        val pm = context.packageManager
        try {
            val info = pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            val requested = info.requestedPermissions ?: emptyArray()
            val granted = info.requestedPermissionsFlags ?: intArrayOf()
            for (i in requested.indices) {
                val name = requested[i]
                val defined = try {
                    pm.getPermissionInfo(name, 0)
                    true
                } catch (_: PackageManager.NameNotFoundException) {
                    false
                }
                val isGranted = (granted.getOrElse(i) { 0 } and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
                val specialAccess = SPECIAL_ACCESS_CHECKS[name]?.let { check -> { check(context) } }
                sb.appendLine(permissionLine(name, isGranted, defined, specialAccess))
            }
        } catch (e: Exception) {
            sb.appendLine("  (failed to read permissions: ${redactSensitive(e.message.orEmpty())})")
        }
        sb.appendLine()

        sb.appendLine("--- Pinned Targets ---")
        try {
            val pinned = TargetSignerGuard.forContext(context).statuses()
            if (pinned.isEmpty()) sb.appendLine("  none")
            pinned.forEach { status -> sb.appendLine(pinnedTargetLine(status)) }
        } catch (e: Exception) {
            sb.appendLine("  (failed to read pinned targets: ${redactSensitive(e.message.orEmpty())})")
        }
        sb.appendLine()

        sb.appendLine("=== End Report ===")
        return sb.toString()
    }

    /**
     * One Permissions row. Special access is switched on in Settings, so the install-time grant
     * flag says nothing about it (exact alarms read denied, Do Not Disturb reads granted, whatever
     * the user chose). A permission the device doesn't define (newer Android, or another app that
     * isn't installed) isn't a refusal either. Non-platform names stay whole so `RUN_COMMAND`
     * can't be mistaken for an Android permission.
     */
    internal fun permissionLine(
        name: String,
        grantFlag: Boolean,
        definedOnDevice: Boolean,
        specialAccess: (() -> Boolean)?,
    ): String {
        val state = when {
            !definedOnDevice -> "not defined on this device"
            specialAccess == null -> if (grantFlag) "granted" else "denied"
            else -> try {
                if (specialAccess()) "granted (special access)" else "denied (special access)"
            } catch (_: Exception) {
                "special access, couldn't check"
            }
        }
        return "  ${name.removePrefix("android.permission.")}: $state"
    }

    /**
     * One Pinned Targets row: the package, a short prefix of each pinned SHA-256, how it was pinned
     * and how it compares with what is installed now. A pin holds no bundle or variable value, so
     * there is nothing here to redact.
     */
    internal fun pinnedTargetLine(status: TargetSignerStatus): String {
        val pin = status.pin
        val digest = pin.digests.joinToString("+") { it.take(PINNED_DIGEST_PREFIX_CHARS) }
        val source = when (pin.source) {
            TargetSignerPinSource.APPROVED -> "pinned at approval"
            TargetSignerPinSource.FIRST_USE -> "pinned on first use"
        }
        return "  ${pin.packageName}: sha256 $digest, $source, ${status.state.label}"
    }

    private val SPECIAL_ACCESS_CHECKS: Map<String, (Context) -> Boolean> = mapOf(
        "android.permission.SCHEDULE_EXACT_ALARM" to { ExactAlarmSupport.canScheduleExactAlarms(it) },
        "android.permission.SYSTEM_ALERT_WINDOW" to { Settings.canDrawOverlays(it) },
        "android.permission.WRITE_SETTINGS" to { Settings.System.canWrite(it) },
        "android.permission.PACKAGE_USAGE_STATS" to { UsageAccess.hasUsageStatsAccess(it) },
        "android.permission.REQUEST_INSTALL_PACKAGES" to { it.packageManager.canRequestPackageInstalls() },
        "android.permission.ACCESS_NOTIFICATION_POLICY" to {
            it.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted
        },
    )

    internal fun redactSensitive(text: String): String {
        return ExportRedactionPolicy.redactText(text)
    }

    private fun formatTimestamp(timestampMillis: Long, dateFormat: SimpleDateFormat): String =
        if (timestampMillis <= 0L) "never" else dateFormat.format(Date(timestampMillis))

    private const val MAX_EXPORTED_CRASH_CHARS = 32 * 1024
    private const val PINNED_DIGEST_PREFIX_CHARS = 12
}
