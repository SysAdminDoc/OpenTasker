package com.opentasker.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import com.opentasker.app.R
import com.opentasker.core.engine.ActionTraceStatus
import com.opentasker.core.engine.RunLogActionDiagnostic
import com.opentasker.core.engine.RunLogOutcome
import com.opentasker.core.engine.RunLogSource
import com.opentasker.core.engine.ActiveExecution
import com.opentasker.core.engine.RunLogTemplateDiagnostic
import com.opentasker.core.engine.RunLogVariableChange
import com.opentasker.ui.theme.DesignSystem
import com.opentasker.ui.theme.selectedContainerColor
import com.opentasker.core.engine.outcome
import com.opentasker.core.engine.toRunLogDiagnostics
import com.opentasker.core.model.RunLogEntry
import com.opentasker.core.model.Task
import com.opentasker.core.storage.RunLogRetentionOptions
import com.opentasker.core.storage.RunLogRetentionPolicy
import com.opentasker.core.storage.displayLabel
import com.opentasker.ui.utils.expandCollapseToggle
import java.util.Date

internal const val RUN_LOG_LIST_TAG = "run_log_list"
internal const val RUN_LOG_CLEAR_TAG = "run_log_clear"

// The export/refresh controls sit in a LazyRow, so the ones past the right edge are never
// composed on a narrow device and cannot be reached without scrolling the row itself.
internal const val RUN_LOG_ACTIONS_TAG = "run_log_actions"

@Composable
internal fun RunLogScreenContent(
    logs: List<RunLogEntry>,
    tasks: List<Task>,
    retentionPolicy: RunLogRetentionPolicy,
    onRetentionPolicyChange: (RunLogRetentionPolicy) -> Unit,
    onClearRunLog: (() -> Unit)? = null,
    onShareDiagnostic: () -> Unit,
    contentPadding: PaddingValues,
    totalCount: Int = logs.size,
    hasMore: Boolean = false,
    loading: Boolean = false,
    failed: Boolean = false,
    filters: RunLogFilterState = RunLogFilterState(),
    taskOptions: List<Pair<Long, String>> = runLogTaskOptions(logs, tasks),
    onFiltersChange: (RunLogFilterState) -> Unit = {},
    onLoadMore: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onExportJson: () -> Unit = {},
    onExportCsv: () -> Unit = {},
    activeExecutions: List<ActiveExecution> = emptyList(),
    onCancelExecution: (Long) -> Unit = {},
    onReplayHeldRun: (RunLogEntry) -> Unit = {},
    onToggleRunLogStar: (RunLogEntry) -> Unit = {},
) {
    val hasFilters = filters != RunLogFilterState()
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .testTag(RUN_LOG_LIST_TAG),
        contentPadding = PaddingValues(
            horizontal = DesignSystem.Screen.horizontalPadding,
            vertical = DesignSystem.Screen.verticalPadding,
        ),
        verticalArrangement = Arrangement.spacedBy(DesignSystem.Screen.cardGap),
    ) {
        if (activeExecutions.isNotEmpty()) {
            item {
                ActiveExecutionsCard(
                    executions = activeExecutions,
                    onCancel = onCancelExecution,
                )
            }
        }
        if (failed) {
            // A failed read is not an empty result. Without this the screen claimed nothing
            // matched and the only report was a snackbar that had already gone.
            item {
                InlineNotice(
                    title = stringResource(R.string.run_log_load_failed_title),
                    body = stringResource(R.string.run_log_load_failed_body),
                    color = MaterialTheme.colorScheme.error,
                    action = {
                        TextButton(onClick = onRefresh) {
                            Text(stringResource(R.string.action_retry))
                        }
                    },
                )
            }
        }
        if (!failed && logs.isEmpty() && totalCount == 0 && !loading) {
            item {
                InlineNotice(
                    title = stringResource(if (hasFilters) R.string.empty_run_log_search_title else R.string.empty_run_log_title),
                    body = stringResource(if (hasFilters) R.string.empty_run_log_search_body else R.string.empty_run_log_body),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (logs.isNotEmpty() || taskOptions.isNotEmpty() || hasFilters) {
            item {
                RunLogFilterCard(
                    totalCount = totalCount,
                    visibleCount = logs.size,
                    statusFilter = filters.status,
                    onStatusFilterChange = { onFiltersChange(filters.copy(status = it)) },
                    taskOptions = taskOptions,
                    selectedTaskId = filters.taskId,
                    onTaskFilterChange = { onFiltersChange(filters.copy(taskId = it)) },
                    query = filters.query,
                    onQueryChange = { onFiltersChange(filters.copy(query = it)) },
                    dateFilter = filters.date,
                    onDateFilterChange = { onFiltersChange(filters.copy(date = it)) },
                )
            }
        }
        if (logs.isNotEmpty()) {
            item {
                RunLogSummaryCard(
                    logs = logs,
                    totalCount = totalCount,
                    onShareDiagnostic = onShareDiagnostic,
                    onRefresh = onRefresh,
                    onExportJson = onExportJson,
                    onExportCsv = onExportCsv,
                )
            }
        }
        items(logs, key = { it.id }) { entry ->
            RunLogCard(
                entry = entry,
                onReplayHeldRun = onReplayHeldRun,
                onToggleRunLogStar = onToggleRunLogStar,
            )
        }
        if (loading) {
            item { Text(stringResource(R.string.run_log_loading), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else if (hasMore) {
            item {
                OutlinedButton(onClick = onLoadMore, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.run_log_load_more))
                }
            }
        }
        item {
            RunLogRetentionCard(
                policy = retentionPolicy,
                onPolicyChange = onRetentionPolicyChange,
                onClearRunLog = onClearRunLog,
            )
        }
    }
}

@Composable
private fun RunLogRetentionCard(
    policy: RunLogRetentionPolicy,
    onPolicyChange: (RunLogRetentionPolicy) -> Unit,
    onClearRunLog: (() -> Unit)? = null,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(DesignSystem.Radii.md),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().expandCollapseToggle(expanded) { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.run_log_retention_title), style = MaterialTheme.typography.labelLarge)
                    Text(
                        policy.displayLabel(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = stringResource(if (expanded) R.string.action_collapse else R.string.action_expand),
                )
            }
            if (expanded) {
                Text(
                    stringResource(R.string.run_log_retention_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                    RunLogRetentionOptions.all.forEach { option ->
                        val selected = option.policy == policy
                        val selectionDescription = if (selected) {
                            stringResource(R.string.a11y_selected)
                        } else {
                            stringResource(R.string.a11y_not_selected)
                        }
                        OutlinedButton(
                            onClick = {
                                onPolicyChange(option.policy)
                                expanded = false
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { stateDescription = selectionDescription },
                            shape = RoundedCornerShape(DesignSystem.Radii.sm),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (selected) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)
                                } else {
                                    Color.Transparent
                                },
                                contentColor = MaterialTheme.colorScheme.onSurface,
                            ),
                            border = BorderStroke(
                                1.dp,
                                if (selected) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.58f)
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.62f)
                                },
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(option.label, style = MaterialTheme.typography.labelLarge)
                                Text(
                                    option.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                if (onClearRunLog != null) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                    Text(
                        stringResource(R.string.run_log_clear_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = { confirmClear = true },
                        modifier = Modifier.testTag(RUN_LOG_CLEAR_TAG),
                    ) {
                        Text(
                            stringResource(R.string.run_log_clear_action),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }

    if (confirmClear && onClearRunLog != null) {
        // A log purge cannot offer Undo, so this is the confirmation instead. Pinned and held rows
        // survive, and the dialog says so rather than implying everything goes.
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.run_log_clear_action)) },
            text = {
                Text(
                    stringResource(R.string.run_log_clear_confirm_body),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        onClearRunLog()
                    },
                ) {
                    Text(stringResource(R.string.run_log_clear_action), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun RunLogFilterCard(
    totalCount: Int,
    visibleCount: Int,
    statusFilter: RunLogStatusFilter,
    onStatusFilterChange: (RunLogStatusFilter) -> Unit,
    taskOptions: List<Pair<Long, String>>,
    selectedTaskId: Long?,
    onTaskFilterChange: (Long?) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    dateFilter: RunLogDateFilter,
    onDateFilterChange: (RunLogDateFilter) -> Unit,
) {
    val hasActiveFilters = statusFilter != RunLogStatusFilter.All || selectedTaskId != null || dateFilter != RunLogDateFilter.All
    var expanded by rememberSaveable { mutableStateOf(hasActiveFilters) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(DesignSystem.Radii.md),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    label = { Text(stringResource(R.string.run_log_search_label)) },
                    placeholder = { Text(stringResource(R.string.run_log_search_hint)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        Icons.Filled.FilterList,
                        contentDescription = stringResource(if (expanded) R.string.run_log_hide_filters else R.string.run_log_show_filters),
                        tint = if (hasActiveFilters) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            if (expanded) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.run_log_shown_count, visibleCount, totalCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (hasActiveFilters || query.isNotBlank()) {
                        TextButton(
                            onClick = {
                                onStatusFilterChange(RunLogStatusFilter.All)
                                onTaskFilterChange(null)
                                onQueryChange("")
                                onDateFilterChange(RunLogDateFilter.All)
                            },
                        ) {
                            Text(stringResource(R.string.action_clear))
                        }
                    }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                    item {
                        RunLogFilterChip(
                            label = stringResource(R.string.run_log_any_task),
                            selected = selectedTaskId == null,
                            onClick = { onTaskFilterChange(null) },
                        )
                    }
                    items(taskOptions, key = { it.first }) { (taskId, taskName) ->
                        RunLogFilterChip(
                            label = taskName,
                            selected = selectedTaskId == taskId,
                            onClick = { onTaskFilterChange(taskId) },
                        )
                    }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                    items(RunLogStatusFilter.entries.toList(), key = { it.name }) { filter ->
                        RunLogFilterChip(
                            label = stringResource(filter.labelRes),
                            selected = statusFilter == filter,
                            onClick = { onStatusFilterChange(filter) },
                        )
                    }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                    items(RunLogDateFilter.entries.toList(), key = { it.name }) { filter ->
                        RunLogFilterChip(
                            label = stringResource(filter.labelRes),
                            selected = dateFilter == filter,
                            onClick = { onDateFilterChange(filter) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RunLogFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectionDescription = if (selected) {
        stringResource(R.string.a11y_selected)
    } else {
        stringResource(R.string.a11y_not_selected)
    }
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.semantics { stateDescription = selectionDescription },
        shape = RoundedCornerShape(DesignSystem.Radii.sm),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) {
                selectedContainerColor()
            } else {
                Color.Transparent
            },
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = BorderStroke(
            1.dp,
            if (selected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.58f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.62f)
            },
        ),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 9.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RunLogSummaryCard(
    logs: List<RunLogEntry>,
    totalCount: Int,
    onShareDiagnostic: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onExportJson: () -> Unit = {},
    onExportCsv: () -> Unit = {},
) {
    val outcomes = remember(logs) { logs.map { it.outcome() } }
    val failures = outcomes.count { it == RunLogOutcome.Failed }
    val interrupted = outcomes.count { it == RunLogOutcome.Interrupted }
    val skipped = outcomes.count { it == RunLogOutcome.Skipped }
    val held = outcomes.count { it == RunLogOutcome.Held }
    val shareDescription = stringResource(R.string.run_log_share_diagnostic)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(DesignSystem.Radii.md),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = stringResource(R.string.nav_run_log),
                    tint = if (failures > 0 || interrupted > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    stringResource(R.string.header_run_log_detail, totalCount),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                StatusPill(
                    when {
                        interrupted > 0 -> stringResource(R.string.status_interrupted)
                        failures > 0 -> stringResource(R.string.run_log_summary_failed, failures)
                        held > 0 -> stringResource(R.string.status_held)
                        skipped > 0 -> stringResource(R.string.run_log_summary_skipped, skipped)
                        else -> stringResource(R.string.run_log_summary_healthy)
                    },
                    when {
                        interrupted > 0 -> MaterialTheme.colorScheme.error
                        failures > 0 -> MaterialTheme.colorScheme.error
                        held > 0 -> MaterialTheme.colorScheme.tertiary
                        skipped > 0 -> MaterialTheme.colorScheme.secondary
                        else -> MaterialTheme.colorScheme.tertiary
                    },
                )
            }
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxWidth().testTag(RUN_LOG_ACTIONS_TAG),
            ) {
                item { TextButton(
                    onClick = onShareDiagnostic,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.semantics { contentDescription = shareDescription },
                ) {
                    Text(stringResource(R.string.run_log_share_short), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                } }
                item { TextButton(onClick = onRefresh, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(stringResource(R.string.run_log_refresh), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                } }
                item { TextButton(onClick = onExportJson, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(stringResource(R.string.run_log_export_json_short), style = MaterialTheme.typography.labelMedium)
                } }
                item { TextButton(onClick = onExportCsv, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(stringResource(R.string.run_log_export_csv_short), style = MaterialTheme.typography.labelMedium)
                } }
            }
        }
    }
}

@Composable
private fun RunLogCard(
    entry: RunLogEntry,
    onReplayHeldRun: (RunLogEntry) -> Unit,
    onToggleRunLogStar: (RunLogEntry) -> Unit,
) {
    val context = LocalContext.current
    val time = remember(entry.timestamp, context) {
        displayDateTimeFormat(context).format(Date(entry.timestamp))
    }
    val diagnostics = remember(entry.message) { entry.message.toRunLogDiagnostics() }
    var tracesExpanded by rememberSaveable(entry.id) { mutableStateOf(false) }
    val hasStructuredDiagnostics = diagnostics.source != null || diagnostics.decision != null || diagnostics.traces.isNotEmpty()
    val outcome = remember(entry.success, entry.message) { entry.outcome() }
    val accent = when (outcome) {
        RunLogOutcome.Succeeded -> MaterialTheme.colorScheme.primary
        RunLogOutcome.Failed -> MaterialTheme.colorScheme.error
        RunLogOutcome.Skipped -> MaterialTheme.colorScheme.secondary
        RunLogOutcome.Held -> MaterialTheme.colorScheme.tertiary
        RunLogOutcome.Cancelled -> MaterialTheme.colorScheme.tertiary
        RunLogOutcome.Interrupted -> MaterialTheme.colorScheme.error
    }
    val sourceText = entry.source?.let { key ->
        val name = runLogSourceName(key)
        entry.sourceLabel?.let { "$name: $it" } ?: name
    } ?: diagnostics.source
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        border = BorderStroke(
            1.dp,
            if (outcome == RunLogOutcome.Failed || outcome == RunLogOutcome.Interrupted) {
                accent.copy(alpha = 0.48f)
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
        shape = RoundedCornerShape(DesignSystem.Radii.md),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md),
            ) {
                Surface(
                    color = accent.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(DesignSystem.Radii.sm),
                ) {
                    Icon(
                        when (outcome) {
                            RunLogOutcome.Succeeded -> Icons.Filled.CheckCircle
                            RunLogOutcome.Failed -> Icons.Filled.Error
                            RunLogOutcome.Skipped -> Icons.Filled.Info
                            RunLogOutcome.Held -> Icons.Filled.Info
                            RunLogOutcome.Cancelled -> Icons.Filled.Cancel
                            RunLogOutcome.Interrupted -> Icons.Filled.Error
                        },
                        contentDescription = when (outcome) {
                            RunLogOutcome.Succeeded -> stringResource(R.string.status_succeeded)
                            RunLogOutcome.Failed -> stringResource(R.string.status_failed)
                            RunLogOutcome.Skipped -> stringResource(R.string.status_skipped)
                            RunLogOutcome.Held -> stringResource(R.string.status_held)
                            RunLogOutcome.Cancelled -> stringResource(R.string.status_cancelled)
                            RunLogOutcome.Interrupted -> stringResource(R.string.status_interrupted)
                        },
                        tint = accent,
                        modifier = Modifier
                            .padding(9.dp)
                            .size(20.dp)
                            .clearAndSetSemantics { },
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(entry.taskName, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    sourceText?.let { source ->
                        Text(
                            stringResource(R.string.label_source, source),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                item { StatusPill(outcome.localizedLabel(), accent) }
                item { StatusPill(stringResource(R.string.run_log_duration, entry.durationMs), accent) }
                if (entry.starred) item { StatusPill(stringResource(R.string.run_log_kept), MaterialTheme.colorScheme.primary) }
            }
            val heldPolicy = entry.heldPolicy
            if (heldPolicy != null) {
                Text(
                    stringResource(R.string.run_log_held_policy, heldPolicy),
                    style = MaterialTheme.typography.bodySmall,
                    color = accent,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm),
            ) {
                if (entry.held) {
                    OutlinedButton(
                        onClick = { onReplayHeldRun(entry) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.run_log_replay))
                    }
                }
                TextButton(
                    onClick = { onToggleRunLogStar(entry) },
                    modifier = if (entry.held) Modifier.weight(1f) else Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(if (entry.starred) R.string.run_log_unstar else R.string.run_log_star))
                }
            }
            Column(Modifier.fillMaxWidth()) {
                if (hasStructuredDiagnostics && diagnostics.detailLines.isNotEmpty()) {
                    Text(
                        diagnostics.detailLines.joinToString("  "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                diagnostics.reason?.let { reason ->
                    Text(reason, style = MaterialTheme.typography.bodyMedium, color = accent)
                }
                if (diagnostics.traces.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val visibleTraces = if (tracesExpanded) {
                            diagnostics.traces
                        } else {
                            diagnostics.traces.subList(0, minOf(COLLAPSED_TRACE_COUNT, diagnostics.traces.size))
                        }
                        visibleTraces.forEach { trace ->
                            RunLogTraceRow(trace)
                        }
                        if (diagnostics.traces.size > COLLAPSED_TRACE_COUNT) {
                            val stateLabel = stringResource(
                                if (tracesExpanded) R.string.a11y_expanded else R.string.a11y_collapsed,
                            )
                            TextButton(
                                onClick = { tracesExpanded = !tracesExpanded },
                                modifier = Modifier.semantics { stateDescription = stateLabel },
                            ) {
                                Text(
                                    stringResource(
                                        if (tracesExpanded) R.string.run_log_show_fewer_actions
                                        else R.string.run_log_show_all_actions,
                                        diagnostics.traces.size,
                                    ),
                                )
                            }
                        }
                    }
                } else if (!hasStructuredDiagnostics && diagnostics.detailLines.isNotEmpty()) {
                    // Only render detail lines here when they were NOT already shown by the
                    // structured-diagnostics block above; otherwise the same lines appeared twice.
                    Text(
                        diagnostics.detailLines.joinToString("\n"),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun RunLogTraceRow(trace: RunLogActionDiagnostic) {
    val color = when (trace.status) {
        ActionTraceStatus.SUCCESS -> MaterialTheme.colorScheme.primary
        ActionTraceStatus.FAILURE -> MaterialTheme.colorScheme.error
        ActionTraceStatus.TIMEOUT -> MaterialTheme.colorScheme.error
        ActionTraceStatus.SKIPPED -> MaterialTheme.colorScheme.secondary
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm),
    ) {
        StatusPill(trace.status.localizedName(), color)
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.run_log_trace_indexed, trace.index + 1, trace.label),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(R.string.run_log_trace_detail, trace.actionType, trace.durationMs, trace.message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            trace.argumentSummary?.let { summary ->
                Text(
                    stringResource(R.string.run_log_trace_expanded, summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (trace.templateWarningCount > 0) {
                Spacer(Modifier.height(4.dp))
                StatusPill(
                    stringResource(R.string.run_log_template_warnings, trace.templateWarningCount),
                    MaterialTheme.colorScheme.error,
                )
            }
            if (trace.templateExpressions.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                ExpressionDebugger(
                    expressions = trace.templateExpressions,
                    traceLabel = trace.label,
                )
            }
            if (trace.variableChanges.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                VariableChangeInspector(
                    changes = trace.variableChanges,
                    traceLabel = trace.label,
                )
            }
        }
    }
}

@Composable
private fun ExpressionDebugger(
    expressions: List<RunLogTemplateDiagnostic>,
    traceLabel: String,
) {
    var expanded by remember { mutableStateOf(false) }
    val visibleExpressions = if (expanded) expressions else expressions.take(3)
    val hasWarnings = expressions.any { it.warning != null }
    val stateLabel = if (expanded) {
        stringResource(R.string.a11y_expanded)
    } else {
        stringResource(R.string.a11y_collapsed)
    }
    val actionLabel = if (expanded) {
        stringResource(R.string.action_collapse)
    } else {
        stringResource(R.string.action_expand)
    }
    val debuggerDescription = stringResource(R.string.a11y_expression_details, traceLabel)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (hasWarnings) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.12f)
        } else {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.10f)
        },
        shape = RoundedCornerShape(DesignSystem.Radii.md),
        border = BorderStroke(
            1.dp,
            if (hasWarnings) MaterialTheme.colorScheme.error.copy(alpha = 0.20f)
            else MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // Meet the 48dp touch-target minimum for the expand/collapse control; the text
                    // stays vertically centred so the visual density is unchanged.
                    .heightIn(min = DesignSystem.ComponentSize.touchTargetMin)
                    .semantics {
                        contentDescription = debuggerDescription
                        stateDescription = stateLabel
                    }
                    .clickable(
                        role = Role.Button,
                        onClickLabel = actionLabel,
                    ) { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    stringResource(R.string.run_log_expression_count, expressions.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    actionLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            visibleExpressions.forEach { expr ->
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            expr.argName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                        )
                        Text(
                            expr.source,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            maxLines = 1,
                        )
                    }
                    Text(
                        stringResource(R.string.run_log_expression_value, expr.expression, expr.value),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = if (expanded) 4 else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    expr.warning?.let { warning ->
                        Text(
                            warning,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (!expanded && expressions.size > 3) {
                Text(
                    stringResource(R.string.run_log_expression_more, expressions.size - 3),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * What this step actually wrote. Traces show the values that went *into* an action; without this,
 * a finished run never answers "what did the task set?".
 *
 * Values arrive already redacted from the engine when the variable is secret-derived, so nothing
 * here can reveal a secret it was not already allowed to show.
 */
@Composable
private fun VariableChangeInspector(
    changes: List<RunLogVariableChange>,
    traceLabel: String,
) {
    var expanded by remember { mutableStateOf(false) }
    val visible = if (expanded) changes else changes.take(3)
    val stateLabel = if (expanded) stringResource(R.string.a11y_expanded) else stringResource(R.string.a11y_collapsed)
    val actionLabel = if (expanded) stringResource(R.string.action_collapse) else stringResource(R.string.action_expand)
    val inspectorDescription = stringResource(R.string.a11y_variable_changes, traceLabel)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.10f),
        shape = RoundedCornerShape(DesignSystem.Radii.md),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.16f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = DesignSystem.ComponentSize.touchTargetMin)
                    .semantics {
                        contentDescription = inspectorDescription
                        stateDescription = stateLabel
                    }
                    .clickable(role = Role.Button, onClickLabel = actionLabel) { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    pluralStringResource(R.plurals.run_log_variable_changes, changes.size, changes.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    actionLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            visible.forEach { change ->
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            change.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            maxLines = 1,
                        )
                        Text(
                            change.scope,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                        Text(
                            if (change.added) {
                                stringResource(R.string.run_log_variable_added)
                            } else {
                                stringResource(R.string.run_log_variable_updated)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    Text(
                        change.value,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = if (expanded) 4 else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (!expanded && changes.size > 3) {
                Text(
                    stringResource(R.string.run_log_variable_more, changes.size - 3),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * What the engine is running right now, and the only way to stop it. Completed runs were the only
 * thing the UI ever showed, so a runaway automation — a long wait, a hung request, an accidental
 * loop — was invisible and unstoppable short of force-stopping the app.
 */
@Composable
private fun ActiveExecutionsCard(
    executions: List<ActiveExecution>,
    onCancel: (Long) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f),
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                pluralStringResource(R.plurals.run_log_active_executions, executions.size, executions.size),
                style = MaterialTheme.typography.titleSmall,
            )
            // Reading the clock during composition froze the figure until the executions flow
            // happened to emit, so a running task appeared stuck at whatever second it started.
            val now by produceState(System.currentTimeMillis()) {
                while (true) {
                    value = System.currentTimeMillis()
                    delay(1_000)
                }
            }
            executions.forEach { execution ->
                val elapsedSeconds = ((now - execution.startedAtMs) / 1000).coerceAtLeast(0)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            execution.taskName,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            stringResource(
                                R.string.run_log_active_execution_detail,
                                execution.source,
                                execution.stepIndex + 1,
                                execution.stepLabel ?: stringResource(R.string.run_log_active_execution_starting),
                                elapsedSeconds,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(
                        onClick = { onCancel(execution.id) },
                        enabled = !execution.cancelling,
                        modifier = Modifier.heightIn(min = DesignSystem.ComponentSize.touchTargetMin),
                    ) {
                        Text(
                            if (execution.cancelling) {
                                stringResource(R.string.run_log_active_execution_cancelling)
                            } else {
                                stringResource(R.string.action_cancel)
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun runLogTaskOptions(logs: List<RunLogEntry>, tasks: List<Task>): List<Pair<Long, String>> {
    val taskNames = tasks.associate { it.id to it.name }
    return logs
        .groupBy { it.taskId }
        .map { (taskId, entries) -> taskId to (taskNames[taskId] ?: entries.first().taskName) }
        .sortedWith(compareBy<Pair<Long, String>> { it.second.lowercase() }.thenBy { it.first })
}

@Composable
private fun runLogSourceName(key: String): String = stringResource(
    when (key) {
        RunLogSource.PROFILE -> R.string.run_log_source_profile
        RunLogSource.SINGLE_ACTION -> R.string.run_log_source_single_action
        RunLogSource.EXTERNAL_INTENT -> R.string.run_log_source_external_intent
        RunLogSource.QUICK_SETTINGS_TILE -> R.string.run_log_source_quick_settings_tile
        RunLogSource.MANUAL_RUN -> R.string.run_log_source_manual_run
        RunLogSource.NOTIFICATION_ACTION -> R.string.run_log_source_notification_action
        RunLogSource.WIDGET -> R.string.run_log_source_widget
        RunLogSource.SHORTCUT -> R.string.run_log_source_shortcut
        RunLogSource.LOCALE_PLUGIN -> R.string.run_log_source_locale_plugin
        RunLogSource.SCENE_OVERLAY -> R.string.run_log_source_scene_overlay
        RunLogSource.APP_FUNCTION -> R.string.run_log_source_app_function
        RunLogSource.SCHEDULER -> R.string.run_log_source_scheduler
        RunLogSource.OTHER -> R.string.run_log_source_other
        else -> R.string.run_log_source_unknown
    },
)

@Composable
private fun RunLogOutcome.localizedLabel(): String = stringResource(
    when (this) {
        RunLogOutcome.Succeeded -> R.string.status_succeeded
        RunLogOutcome.Failed -> R.string.status_failed
        RunLogOutcome.Skipped -> R.string.status_skipped
        RunLogOutcome.Held -> R.string.status_held
        RunLogOutcome.Cancelled -> R.string.status_cancelled
        RunLogOutcome.Interrupted -> R.string.status_interrupted
    },
)

@Composable
private fun ActionTraceStatus.localizedName(): String = stringResource(
    when (this) {
        ActionTraceStatus.SUCCESS -> R.string.status_succeeded
        ActionTraceStatus.FAILURE -> R.string.status_failed
        ActionTraceStatus.TIMEOUT -> R.string.status_timeout
        ActionTraceStatus.SKIPPED -> R.string.status_skipped
    },
)

private fun plural(count: Int): String = if (count == 1) "" else "s"

private const val COLLAPSED_TRACE_COUNT = 4
