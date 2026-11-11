package com.opentasker.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import com.opentasker.app.R
import com.opentasker.core.diagnostics.EngineHealthReader
import com.opentasker.core.diagnostics.EngineHealthStatus
import com.opentasker.core.diagnostics.HealthSignalState
import com.opentasker.core.storage.ScheduledWorkOutcome
import com.opentasker.core.storage.ScheduledWorkOutcomeKind
import com.opentasker.core.storage.ScheduledWorkerId
import com.opentasker.core.diagnostics.assessment
import com.opentasker.core.diagnostics.healthy
import com.opentasker.core.engine.EngineExitCorrelation
import com.opentasker.core.engine.EngineExitCorrelationState
import com.opentasker.core.engine.ExecutionAdmissionSnapshot
import com.opentasker.core.engine.ExecutionCircuitState
import com.opentasker.core.logging.AppLogEntry
import com.opentasker.ui.theme.DesignSystem
import java.text.SimpleDateFormat
import java.util.Date

@Composable
fun DiagnosticsScreen(
    state: DiagnosticsUiState,
    contentPadding: PaddingValues,
    onRefresh: () -> Unit,
    onShare: () -> Unit,
    onCopy: () -> Unit,
) {
    val context = LocalContext.current
    val formatter = remember(context) { displayDateTimeFormat(context) }
    val health = state.health
    // null until the first health read. It used to collapse to false, so every open flashed a red
    // "Needs attention" above rows that still said Loading.
    val healthy = health?.healthy
    // A read that failed or never answered is not "still loading". With no earlier read to fall
    // back on, the sections below would only show placeholders and an empty crash list that
    // claims nothing crashed, so they wait for a read that works (A-360).
    val nothingRead = state.loadFailed && state.loadedAtMillis == 0L

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(DesignSystem.Screen.horizontalPadding),
        verticalArrangement = Arrangement.spacedBy(DesignSystem.Screen.cardGap),
    ) {
        item {
            DiagnosticSummaryCard(
                healthy = healthy,
                checkFailed = state.loadFailed && health == null,
                reason = health?.assessment?.reason,
                onRefresh = onRefresh,
                onShare = onShare,
                onCopy = onCopy,
            )
        }
        if (state.loadFailed) {
            item {
                InlineNotice(
                    title = stringResource(R.string.diagnostics_load_failed_title),
                    body = stringResource(R.string.diagnostics_load_failed_body),
                    color = MaterialTheme.colorScheme.error,
                    action = {
                        TextButton(onClick = onRefresh) {
                            Text(stringResource(R.string.action_retry))
                        }
                    },
                )
            }
        }
        if (nothingRead) return@LazyColumn
        item {
            SectionTitle(stringResource(R.string.diagnostics_engine_health))
            EngineHealthCard(health, formatter)
        }
        item {
            SectionTitle(stringResource(R.string.diagnostics_admission_title))
            AdmissionHealthCard(state.admission, state.profileNames)
        }
        item { SectionTitle(stringResource(R.string.diagnostics_crash_logs, state.crashLogs.size)) }
        if (state.crashLogs.isEmpty()) {
            item { EmptyDiagnosticCard(stringResource(R.string.diagnostics_no_crashes)) }
        } else {
            items(state.crashLogs, key = { it.fileName }) { crash ->
                DiagnosticRecordCard(
                    title = stringResource(
                        R.string.diagnostics_crash_record,
                        crash.fileName,
                        formatter.format(Date(crash.modifiedAtMillis)),
                    ),
                    body = crash.redactedContent.lineSequence().take(CRASH_PREVIEW_LINES).joinToString("\n"),
                    accent = MaterialTheme.colorScheme.error,
                )
            }
        }
        item { SectionTitle(stringResource(R.string.diagnostics_app_log, state.appLogs.size)) }
        if (state.appLogs.isEmpty()) {
            item { EmptyDiagnosticCard(stringResource(R.string.diagnostics_no_app_logs)) }
        } else {
            // Keyed on the monotonic sequence: two identical log lines in the same
            // millisecond previously produced duplicate LazyColumn keys and crashed
            // this screen mid-diagnosis.
            items(state.appLogs.asReversed(), key = { it.sequence }) { entry ->
                AppLogCard(entry, formatter)
            }
        }
    }
}

@Composable
private fun DiagnosticSummaryCard(
    healthy: Boolean?,
    checkFailed: Boolean,
    reason: String?,
    onRefresh: () -> Unit,
    onShare: () -> Unit,
    onCopy: () -> Unit,
) {
    val statusColor = when {
        healthy == true -> MaterialTheme.colorScheme.tertiary
        healthy == false || checkFailed -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusLabel = stringResource(
        when {
            healthy == true -> R.string.diagnostics_status_healthy
            healthy == false -> R.string.diagnostics_status_attention
            checkFailed -> R.string.diagnostics_status_unavailable
            else -> R.string.diagnostics_status_checking
        },
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.38f)),
        shape = RoundedCornerShape(DesignSystem.Radii.md),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                when {
                    healthy == true -> Icons.Filled.CheckCircle
                    healthy == false || checkFailed -> Icons.Filled.Error
                    else -> Icons.Filled.HourglassEmpty
                },
                contentDescription = statusLabel,
                tint = statusColor,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(statusLabel, style = MaterialTheme.typography.titleSmall, color = statusColor)
                reason?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.diagnostics_refresh))
            }
            // Share opens a chooser, which is a poor fit for pasting into a bug report. #14 came in
            // as screenshots partly because there was no way to get this text out as text.
            IconButton(onClick = onCopy) {
                Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.diagnostics_copy))
            }
            IconButton(onClick = onShare) {
                Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.diagnostics_share))
            }
        }
    }
}

@Composable
private fun EngineHealthCard(health: EngineHealthStatus?, formatter: SimpleDateFormat) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(DesignSystem.Radii.md),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            HealthRow(
                stringResource(R.string.diagnostics_service),
                health?.let {
                    stringResource(
                        if (it.serviceRunning) R.string.diagnostics_service_running else R.string.diagnostics_service_stopped,
                    )
                } ?: stringResource(R.string.diagnostics_loading),
            )
            HealthRow(
                stringResource(R.string.diagnostics_last_heartbeat),
                health?.lastHeartbeatAtMillis?.takeIf { it > 0L }?.let { formatter.format(Date(it)) }
                    ?: stringResource(R.string.diagnostics_never),
            )
            if (!expanded) {
                HealthRow(
                    stringResource(R.string.diagnostics_exact_alarm),
                    health?.exactAlarmStatus ?: stringResource(R.string.diagnostics_loading),
                )
                HealthRow(
                    stringResource(R.string.diagnostics_active_executions),
                    health?.activeExecutionCount?.toString() ?: stringResource(R.string.diagnostics_loading),
                )
            }
            if (expanded) {
                HealthRow(
                stringResource(R.string.diagnostics_process_exit),
                health?.let { processExitSummary(it.processExitCorrelation, formatter) }
                    ?: stringResource(R.string.diagnostics_loading),
            )
            HealthRow(
                stringResource(R.string.diagnostics_fgs_types),
                health?.activeForegroundServiceTypes ?: stringResource(R.string.diagnostics_loading),
            )
            HealthRow(
                stringResource(R.string.diagnostics_standby_consequence),
                health?.standbyConsequence ?: stringResource(R.string.diagnostics_loading),
            )
            if (health?.standbyThrottled == true) {
                Text(
                    stringResource(R.string.diagnostics_standby_throttled),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            HealthRow(
                stringResource(R.string.diagnostics_exact_alarm),
                health?.exactAlarmStatus ?: stringResource(R.string.diagnostics_loading),
            )
            HealthRow(
                stringResource(R.string.diagnostics_advanced_protection),
                health?.let {
                    stringResource(
                        if (it.advancedProtectionEnabled) R.string.diagnostics_advanced_protection_on
                        else R.string.diagnostics_advanced_protection_off,
                    )
                } ?: stringResource(R.string.diagnostics_loading),
            )
            if (health?.advancedProtectionEnabled == true) {
                Text(
                    stringResource(R.string.diagnostics_advanced_protection_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            HealthRow(
                stringResource(R.string.diagnostics_matcher_error),
                health?.lastMatcherError ?: stringResource(R.string.diagnostics_none),
            )
            HealthRow(
                stringResource(R.string.diagnostics_worker_stop),
                health?.lastWorkerStopReason ?: stringResource(R.string.diagnostics_none),
            )
            SectionTitle(stringResource(R.string.diagnostics_scheduled_work_header))
            val outcomes = health?.scheduledWorkOutcomes.orEmpty().associateBy { it.worker }
            ScheduledWorkerId.entries.forEach { worker ->
                val outcome = outcomes[worker]
                HealthRow(
                    stringResource(scheduledWorkerLabel(worker)),
                    if (outcome == null) {
                        stringResource(R.string.diagnostics_scheduled_work_none)
                    } else {
                        val latest = stringResource(
                            R.string.diagnostics_work_outcome_at,
                            scheduledWorkOutcomeSummary(outcome),
                            formatter.format(Date(outcome.timestampMillis)),
                        )
                        val stop = outcome.lastStop
                        if (stop == null) {
                            latest
                        } else {
                            // A later instance finishing normally must not erase the stop.
                            latest + stringResource(
                                R.string.diagnostics_work_outcome_also_stopped,
                                stringResource(stopReasonLabel(stop.stopReason)),
                                formatter.format(Date(stop.timestampMillis)),
                            )
                        }
                    },
                )
            }
            HealthRow(
                stringResource(R.string.diagnostics_pending_jobs),
                when {
                    health == null -> stringResource(R.string.diagnostics_loading)
                    !health.pendingScheduledJobs.currentAvailable -> stringResource(R.string.diagnostics_pending_jobs_unavailable)
                    else -> health.pendingScheduledJobs.currentReasons
                        ?: stringResource(R.string.diagnostics_pending_jobs_none)
                },
            )
            HealthRow(
                stringResource(R.string.diagnostics_pending_job_history),
                when {
                    health == null -> stringResource(R.string.diagnostics_loading)
                    !health.pendingScheduledJobs.historyAvailable -> stringResource(R.string.diagnostics_pending_job_history_unavailable)
                    else -> health.pendingScheduledJobs.history
                        ?: stringResource(R.string.diagnostics_pending_job_history_none)
                },
            )
            HealthRow(
                stringResource(R.string.diagnostics_pending_job_stats),
                when {
                    health == null -> stringResource(R.string.diagnostics_loading)
                    !health.pendingScheduledJobs.aggregateStatsAvailable -> stringResource(R.string.diagnostics_pending_job_stats_unavailable)
                    else -> health.pendingScheduledJobs.aggregateStats
                        ?: stringResource(R.string.diagnostics_pending_job_stats_none)
                },
            )
            HealthRow(
                stringResource(R.string.diagnostics_active_executions),
                health?.activeExecutionCount?.toString() ?: stringResource(R.string.diagnostics_loading),
            )
            HealthRow(
                stringResource(R.string.diagnostics_pending_executions),
                health?.pendingExecutionCount?.toString() ?: stringResource(R.string.diagnostics_loading),
            )
                if (health?.signals?.isNotEmpty() == true) {
                Text(
                    stringResource(R.string.diagnostics_health_evidence),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                health.signals.forEach { signal ->
                    val color = when (signal.state) {
                        HealthSignalState.Ready -> MaterialTheme.colorScheme.tertiary
                        HealthSignalState.Loading -> MaterialTheme.colorScheme.secondary
                        HealthSignalState.Stale,
                        HealthSignalState.Error,
                        -> MaterialTheme.colorScheme.error
                    }
                    Text(
                        stringResource(
                            R.string.diagnostics_health_signal,
                            signal.label,
                            stringResource(signal.state.resourceId),
                            signal.reason,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = color,
                    )
                }
                }
            }
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(if (expanded) R.string.diagnostics_hide_details else R.string.diagnostics_show_details))
            }
        }
    }
}

@Composable
private fun AdmissionHealthCard(
    snapshot: ExecutionAdmissionSnapshot?,
    profileNames: Map<Long, String> = emptyMap(),
) {
    val now = System.currentTimeMillis()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(DesignSystem.Radii.md),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (snapshot == null) {
                HealthRow(
                    stringResource(R.string.diagnostics_admission_status),
                    stringResource(R.string.diagnostics_loading),
                )
            } else {
                HealthRow(
                    stringResource(R.string.diagnostics_admission_active),
                    stringResource(
                        R.string.diagnostics_admission_count,
                        snapshot.activeGlobal,
                        snapshot.limits.globalMaxActive,
                    ),
                )
                HealthRow(
                    stringResource(R.string.diagnostics_admission_burst),
                    stringResource(
                        R.string.diagnostics_admission_count,
                        snapshot.globalBurstCount,
                        snapshot.limits.globalBurstLimit,
                    ),
                )
                snapshot.activeByProfile.toSortedMap().forEach { (profileId, active) ->
                    HealthRow(
                        stringResource(
                            R.string.diagnostics_admission_profile_active,
                            profileNames[profileId] ?: profileId.toString(),
                        ),
                        active.toString(),
                    )
                }
                val circuits = snapshot.circuits.entries
                    .filter { (_, state) -> state.openUntilMs > now || state.strikeCount > 0 || state.lastReason != null }
                    .sortedWith(
                        compareBy<Map.Entry<Long?, ExecutionCircuitState>> { it.key != null }
                            .thenBy { it.key ?: Long.MIN_VALUE },
                    )
                Text(
                    stringResource(R.string.diagnostics_admission_circuits),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (circuits.isEmpty()) {
                    Text(
                        stringResource(R.string.diagnostics_admission_no_circuits),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    circuits.forEach { entry ->
                        AdmissionCircuitRow(entry.key, entry.value, now, profileNames)
                    }
                }
            }
        }
    }
}

@Composable
private fun AdmissionCircuitRow(
    profileId: Long?,
    state: ExecutionCircuitState,
    now: Long,
    profileNames: Map<Long, String> = emptyMap(),
) {
    val remainingMs = (state.openUntilMs - now).coerceAtLeast(0L)
    val label = profileId?.let {
        stringResource(R.string.diagnostics_admission_profile, profileNames[it] ?: it.toString())
    }
        ?: stringResource(R.string.diagnostics_admission_global)
    val strikes = pluralStringResource(
        R.plurals.diagnostics_admission_strikes,
        state.strikeCount,
        state.strikeCount,
    )
    val status = if (remainingMs > 0L) {
        val seconds = (remainingMs / 1_000L).coerceAtLeast(1L)
        pluralStringResource(R.plurals.diagnostics_admission_open, seconds.toInt(), seconds, strikes)
    } else {
        stringResource(R.string.diagnostics_admission_closed, strikes)
    }
    HealthRow(label, status)
    state.lastReason?.takeIf(String::isNotBlank)?.let { reason ->
        Text(
            stringResource(R.string.diagnostics_admission_trip_reason, reason),
            style = MaterialTheme.typography.bodySmall,
            color = if (remainingMs > 0L) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun processExitSummary(correlation: EngineExitCorrelation, formatter: SimpleDateFormat): String = when (correlation.state) {
    EngineExitCorrelationState.UNAVAILABLE -> stringResource(R.string.diagnostics_process_exit_unavailable)
    EngineExitCorrelationState.NO_GAP -> stringResource(R.string.diagnostics_process_exit_no_gap)
    EngineExitCorrelationState.NO_MATCH -> stringResource(
        R.string.diagnostics_process_exit_no_match,
        correlation.gapMillis?.let(EngineHealthReader::ageLabel) ?: stringResource(R.string.diagnostics_none),
    )
    EngineExitCorrelationState.MATCHED -> stringResource(
        R.string.diagnostics_process_exit_matched,
        correlation.reason ?: stringResource(R.string.diagnostics_none),
        correlation.description ?: stringResource(R.string.diagnostics_none),
        correlation.timestampMillis?.let { formatter.format(Date(it)) } ?: stringResource(R.string.diagnostics_never),
        correlation.gapMillis?.let(EngineHealthReader::ageLabel) ?: stringResource(R.string.diagnostics_none),
    )
}

@StringRes
private fun scheduledWorkerLabel(worker: ScheduledWorkerId): Int = when (worker) {
    ScheduledWorkerId.ENGINE_WATCHDOG -> R.string.diagnostics_worker_engine_watchdog
    ScheduledWorkerId.RUN_LOG_PRUNE -> R.string.diagnostics_worker_run_log_prune
    ScheduledWorkerId.CONFIGURATION_SNAPSHOT -> R.string.diagnostics_worker_configuration_snapshot
    ScheduledWorkerId.UPDATE_CHECK -> R.string.diagnostics_worker_update_check
    ScheduledWorkerId.TEMPORARY_STATE_REVERT -> R.string.diagnostics_worker_temporary_state_revert
    ScheduledWorkerId.HEALTH_CONNECT_POLL -> R.string.diagnostics_worker_health_connect_poll
}

@StringRes
private fun stopReasonLabel(reason: Int): Int = when (reason) {
    WorkInfo.STOP_REASON_NOT_STOPPED -> R.string.diagnostics_stop_reason_not_stopped
    WorkInfo.STOP_REASON_CANCELLED_BY_APP -> R.string.diagnostics_stop_reason_cancelled_by_app
    WorkInfo.STOP_REASON_PREEMPT -> R.string.diagnostics_stop_reason_preempt
    WorkInfo.STOP_REASON_TIMEOUT -> R.string.diagnostics_stop_reason_timeout
    EngineHealthReader.STOP_REASON_TIMEOUT_ABANDONED -> R.string.diagnostics_stop_reason_timeout_abandoned
    WorkInfo.STOP_REASON_DEVICE_STATE -> R.string.diagnostics_stop_reason_device_state
    WorkInfo.STOP_REASON_CONSTRAINT_BATTERY_NOT_LOW -> R.string.diagnostics_stop_reason_constraint_battery_not_low
    WorkInfo.STOP_REASON_CONSTRAINT_CHARGING -> R.string.diagnostics_stop_reason_constraint_charging
    WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY -> R.string.diagnostics_stop_reason_constraint_connectivity
    WorkInfo.STOP_REASON_CONSTRAINT_DEVICE_IDLE -> R.string.diagnostics_stop_reason_constraint_device_idle
    WorkInfo.STOP_REASON_CONSTRAINT_STORAGE_NOT_LOW -> R.string.diagnostics_stop_reason_constraint_storage_not_low
    WorkInfo.STOP_REASON_QUOTA -> R.string.diagnostics_stop_reason_quota
    WorkInfo.STOP_REASON_BACKGROUND_RESTRICTION -> R.string.diagnostics_stop_reason_background_restriction
    WorkInfo.STOP_REASON_APP_STANDBY -> R.string.diagnostics_stop_reason_app_standby
    WorkInfo.STOP_REASON_USER -> R.string.diagnostics_stop_reason_user
    WorkInfo.STOP_REASON_SYSTEM_PROCESSING -> R.string.diagnostics_stop_reason_system_processing
    WorkInfo.STOP_REASON_ESTIMATED_APP_LAUNCH_TIME_CHANGED ->
        R.string.diagnostics_stop_reason_estimated_app_launch_time_changed
    WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT -> R.string.diagnostics_stop_reason_foreground_service_timeout
    else -> R.string.diagnostics_stop_reason_unknown
}

@Composable
private fun scheduledWorkOutcomeSummary(outcome: ScheduledWorkOutcome): String = when (outcome.kind) {
    ScheduledWorkOutcomeKind.COMPLETED -> stringResource(R.string.diagnostics_work_outcome_completed)
    ScheduledWorkOutcomeKind.RETRYING -> stringResource(R.string.diagnostics_work_outcome_retrying)
    ScheduledWorkOutcomeKind.FAILED -> stringResource(R.string.diagnostics_work_outcome_failed)
    ScheduledWorkOutcomeKind.STOPPED -> stringResource(
        R.string.diagnostics_work_outcome_stopped,
        stringResource(stopReasonLabel(outcome.stopReason)),
    )
}

@Composable
private fun HealthRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1.3f))
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun EmptyDiagnosticCard(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(DesignSystem.Radii.md),
    ) {
        Text(message, modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DiagnosticRecordCard(title: String, body: String, accent: Color) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.34f)),
        shape = RoundedCornerShape(DesignSystem.Radii.md),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                maxLines = CRASH_PREVIEW_LINES,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AppLogCard(entry: AppLogEntry, formatter: SimpleDateFormat) {
    val accent = when (entry.level) {
        com.opentasker.core.logging.AppLogger.Level.ERROR -> MaterialTheme.colorScheme.error
        com.opentasker.core.logging.AppLogger.Level.WARN -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.primary
    }
    DiagnosticRecordCard(
        title = stringResource(
            R.string.diagnostics_log_record,
            formatter.format(Date(entry.timestampMillis)),
            stringResource(appLogLevelLabelRes(entry.level)),
            entry.tag,
        ),
        body = entry.message,
        accent = accent,
    )
}

private const val CRASH_PREVIEW_LINES = 6

private val HealthSignalState.resourceId: Int
    get() = when (this) {
        HealthSignalState.Ready -> R.string.diagnostics_health_state_ready
        HealthSignalState.Loading -> R.string.diagnostics_health_state_loading
        HealthSignalState.Stale -> R.string.diagnostics_health_state_stale
        HealthSignalState.Error -> R.string.diagnostics_health_state_error
    }
