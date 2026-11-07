package com.opentasker.ui.screens

import androidx.annotation.StringRes
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentasker.app.R
import com.opentasker.core.actions.ActionMetadataRegistry
import com.opentasker.core.actions.ActionSummaryFormatter
import com.opentasker.core.capabilities.ActionCapabilityRegistry
import com.opentasker.core.capabilities.CapabilityLevel
import com.opentasker.core.contexts.contextConfigSummary
import com.opentasker.core.engine.SingleActionRun
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.Profile
import com.opentasker.core.model.RunLogEntry
import com.opentasker.core.model.Task
import com.opentasker.core.storage.StorageDecodeIssue
import com.opentasker.ui.theme.DesignSystem
import com.opentasker.ui.utils.expandCollapseToggle
@Composable
internal fun ProfilesScreen(
    profiles: List<Profile>,
    tasks: List<Task>,
    runLogs: List<RunLogEntry>,
    storageDecodeIssues: List<StorageDecodeIssue>,
    onCreateTaskFirst: () -> Unit,
    onCreateProfile: () -> Unit,
    onBrowseTemplates: () -> Unit,
    onPreviewProfileShare: () -> Unit,
    onPreflightProfile: (Profile) -> Unit,
    onExportOpenTaskerBundle: () -> Unit,
    onImportOpenTaskerBundle: () -> Unit,
    onImportOpenTaskerBundleText: () -> Unit,
    openTaskerBundleBusy: Boolean,
    onImportTaskerXml: () -> Unit,
    onExportTaskerXml: () -> Unit,
    taskerImportBusy: Boolean,
    onEditProfile: (Profile) -> Unit,
    onUndoProfileEdit: (Profile) -> Unit = {},
    onRedoProfileEdit: (Profile) -> Unit = {},
    onDeleteProfile: (Profile) -> Unit,
    onDuplicateProfile: (Profile) -> Unit = {},
    onToggleProfile: (Profile, Boolean) -> Unit,
    onAddContext: (Profile) -> Unit,
    onEditContextLogic: (Profile) -> Unit,
    onEditContext: (Profile, Int, ContextSpec) -> Unit,
    onDeleteContext: (Profile, Int) -> Unit,
    contentPadding: PaddingValues,
    contentLoaded: Boolean = true,
    historyAvailability: EditHistoryAvailabilityState = EditHistoryAvailabilityState(),
    /** Every task, whatever the project scope, so a card can name a task from another project. */
    allTasks: List<Task> = tasks,
) {
    // An unread database and an empty one look identical from here; without this gate a cold
    // start with existing data flashes the first-run screen before Room's first emission.
    if (!contentLoaded) {
        ContentLoadingState(contentPadding)
        return
    }
    // Both lists arrive scoped to the selected project, and a profile can run a task that lives
    // in another project. Gating on tasks alone hid such a profile behind the first-run screen.
    if (tasks.isEmpty() && profiles.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.empty_first_automation_title),
            body = stringResource(R.string.empty_first_automation_body),
            actionLabel = stringResource(R.string.action_browse_templates),
            onAction = onBrowseTemplates,
            secondaryActionLabel = if (openTaskerBundleBusy) stringResource(R.string.import_reading_bundle) else stringResource(R.string.import_import_json),
            onSecondaryAction = onImportOpenTaskerBundle,
            secondaryActionEnabled = !openTaskerBundleBusy,
            quinaryActionLabel = stringResource(R.string.import_paste_json_action),
            onQuinaryAction = onImportOpenTaskerBundleText,
            quinaryActionEnabled = !openTaskerBundleBusy,
            tertiaryActionLabel = if (taskerImportBusy) stringResource(R.string.import_reading_xml) else stringResource(R.string.action_import_tasker_xml),
            onTertiaryAction = onImportTaskerXml,
            tertiaryActionEnabled = !taskerImportBusy,
            quaternaryActionLabel = stringResource(R.string.action_create_blank_task),
            onQuaternaryAction = onCreateTaskFirst,
            contentPadding = contentPadding,
        )
        return
    }
    if (profiles.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.empty_first_profile_title),
            body = stringResource(R.string.empty_first_profile_body),
            actionLabel = stringResource(R.string.action_browse_templates),
            onAction = onBrowseTemplates,
            secondaryActionLabel = if (openTaskerBundleBusy) stringResource(R.string.import_reading_bundle) else stringResource(R.string.import_import_json),
            onSecondaryAction = onImportOpenTaskerBundle,
            secondaryActionEnabled = !openTaskerBundleBusy,
            quinaryActionLabel = stringResource(R.string.import_paste_json_action),
            onQuinaryAction = onImportOpenTaskerBundleText,
            quinaryActionEnabled = !openTaskerBundleBusy,
            tertiaryActionLabel = if (taskerImportBusy) stringResource(R.string.import_reading_xml) else stringResource(R.string.action_import_tasker_xml),
            onTertiaryAction = onImportTaskerXml,
            tertiaryActionEnabled = !taskerImportBusy,
            quaternaryActionLabel = stringResource(R.string.action_create_blank_profile),
            onQuaternaryAction = onCreateProfile,
            contentPadding = contentPadding,
        )
        return
    }

    var profileSearchQuery by rememberSaveable { mutableStateOf("") }
    var selectedGroup by rememberSaveable { mutableStateOf<String?>(null) }
    val groups = remember(profiles) {
        profiles.mapNotNull { it.group }.distinct().sorted()
    }
    val filteredProfiles = profiles
        .filter { selectedGroup == null || it.group == selectedGroup }
        .filter { profileSearchQuery.isBlank() || it.name.contains(profileSearchQuery, ignoreCase = true) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        // Extra bottom inset so the extended FAB never sits on top of the last row; Scaffold's
        // innerPadding does not reserve floating-action-button height.
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md),
    ) {
        if (storageDecodeIssues.isNotEmpty()) {
            item {
                StorageDecodeWarningCard(storageDecodeIssues)
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm),
            ) {
                OutlinedTextField(
                    value = profileSearchQuery,
                    onValueChange = { profileSearchQuery = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.workspace_search_profiles)) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = if (profileSearchQuery.isNotEmpty()) {
                        { IconButton(onClick = { profileSearchQuery = "" }) { Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.variables_search_clear)) } }
                    } else null,
                    singleLine = true,
                    shape = RoundedCornerShape(DesignSystem.Radii.lg),
                )
                WorkspaceActionsButton(
                    onBrowseTemplates = onBrowseTemplates,
                    onPreviewProfileShare = onPreviewProfileShare,
                    onExportOpenTaskerBundle = onExportOpenTaskerBundle,
                    onImportOpenTaskerBundle = onImportOpenTaskerBundle,
                    onImportOpenTaskerBundleText = onImportOpenTaskerBundleText,
                    onImportTaskerXml = onImportTaskerXml,
                    onExportTaskerXml = onExportTaskerXml,
                )
            }
        }
        if (groups.isNotEmpty()) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm)) {
                    item {
                        FilterChip(
                            selected = selectedGroup == null,
                            onClick = { selectedGroup = null },
                            label = { Text(stringResource(R.string.label_all)) },
                        )
                    }
                    items(groups, key = { it }) { group ->
                        FilterChip(
                            selected = selectedGroup == group,
                            onClick = { selectedGroup = if (selectedGroup == group) null else group },
                            label = { Text(group) },
                        )
                    }
                }
            }
        }
        if (filteredProfiles.isEmpty()) {
            item {
                InlineNotice(
                    title = stringResource(R.string.workspace_no_matching_profiles),
                    body = stringResource(R.string.workspace_no_matching_profiles_body),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        items(filteredProfiles, key = { it.id }) { profile ->
            val enterTaskName = allTasks.firstOrNull { it.id == profile.enterTaskId }?.name ?: stringResource(R.string.workspace_missing_task, profile.enterTaskId)
            ProfileCard(
                profile = profile,
                enterTaskName = enterTaskName,
                onEdit = { onEditProfile(profile) },
                onUndo = { onUndoProfileEdit(profile) },
                onRedo = { onRedoProfileEdit(profile) },
                canUndo = historyAvailability.canUndoProfile(profile.id),
                canRedo = historyAvailability.canRedoProfile(profile.id),
                onDelete = { onDeleteProfile(profile) },
                onDuplicate = { onDuplicateProfile(profile) },
                onToggle = { onToggleProfile(profile, it) },
                onAddContext = { onAddContext(profile) },
                onEditContextLogic = { onEditContextLogic(profile) },
                onEditContext = { index, context -> onEditContext(profile, index, context) },
                onDeleteContext = { index -> onDeleteContext(profile, index) },
                onPreflight = { onPreflightProfile(profile) },
            )
        }
    }
}

@Composable
private fun WorkspaceActionsButton(
    onBrowseTemplates: () -> Unit,
    onPreviewProfileShare: () -> Unit,
    onExportOpenTaskerBundle: () -> Unit,
    onImportOpenTaskerBundle: () -> Unit,
    onImportOpenTaskerBundleText: () -> Unit,
    onImportTaskerXml: () -> Unit,
    onExportTaskerXml: () -> Unit,
) {
    var actionsExpanded by rememberSaveable { mutableStateOf(false) }

    Box {
        IconButton(onClick = { actionsExpanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.workspace_actions_content_description))
        }
        DropdownMenu(expanded = actionsExpanded, onDismissRequest = { actionsExpanded = false }) {
            WorkspaceActionMenuItem(R.string.workspace_templates) { actionsExpanded = false; onBrowseTemplates() }
            WorkspaceActionMenuItem(R.string.import_tasker) { actionsExpanded = false; onImportTaskerXml() }
            WorkspaceActionMenuItem(R.string.import_export_json) { actionsExpanded = false; onExportOpenTaskerBundle() }
            WorkspaceActionMenuItem(R.string.import_import_json) { actionsExpanded = false; onImportOpenTaskerBundle() }
            WorkspaceActionMenuItem(R.string.import_export_tasker_xml) { actionsExpanded = false; onExportTaskerXml() }
            WorkspaceActionMenuItem(R.string.import_paste_json_action) { actionsExpanded = false; onImportOpenTaskerBundleText() }
            WorkspaceActionMenuItem(R.string.profile_share_preview_action) { actionsExpanded = false; onPreviewProfileShare() }
        }
    }
}

@Composable
private fun WorkspaceActionMenuItem(@androidx.annotation.StringRes labelRes: Int, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(stringResource(labelRes)) }, onClick = onClick)
}

@Composable
internal fun StorageDecodeWarningCard(issues: List<StorageDecodeIssue>) {
    val issueSummary = issues.take(3).joinToString(separator = "; ") { issue ->
        "${issue.recordType.label} \"${issue.recordName}\" #${issue.recordId}: ${issue.fieldName}"
    }
    val remaining = issues.size - 3
    val suffix = if (remaining > 0) "; ${stringResource(R.string.label_more_count, remaining)}" else ""
    InlineNotice(
        title = stringResource(R.string.workspace_stored_data_review),
        body = stringResource(R.string.workspace_stored_data_review_body, issueSummary, suffix),
        color = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun TemplatePromptCard(onBrowseTemplates: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.30f)),
        shape = RoundedCornerShape(DesignSystem.Radii.xxl),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md),
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.workspace_templates), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.workspace_templates_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = onBrowseTemplates) {
                Text(stringResource(R.string.action_browse))
            }
        }
    }
}

@Composable
private fun ProfileCard(
    profile: Profile,
    enterTaskName: String,
    onEdit: () -> Unit,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onPreflight: () -> Unit,
    onAddContext: () -> Unit,
    onEditContextLogic: () -> Unit,
    onEditContext: (Int, ContextSpec) -> Unit,
    onDeleteContext: (Int) -> Unit,
) {
    var expanded by rememberSaveable(profile.id) { mutableStateOf(false) }
    val profileState = when {
        profile.requiresRiskAcknowledgement -> stringResource(R.string.imported_profile_review_required)
        profile.enabled -> stringResource(R.string.label_enabled)
        else -> stringResource(R.string.label_paused)
    }
    val toggleDescription = stringResource(R.string.a11y_profile_status, profile.name)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(DesignSystem.Radii.md),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm)) {
            Row(
                modifier = Modifier.expandCollapseToggle(expanded) { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.66f),
                    shape = RoundedCornerShape(DesignSystem.Radii.md),
                ) {
                    // Decorative: the switch carries "<name> profile status" and its state.
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = if (profile.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(10.dp).size(22.dp),
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(profile.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        stringResource(R.string.workspace_runs_task, enterTaskName),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Switch(
                    checked = profile.enabled,
                    onCheckedChange = onToggle,
                    modifier = Modifier.semantics {
                        contentDescription = toggleDescription
                        stateDescription = profileState
                    },
                )
                ProfileActionsMenu(
                    contentDescription = stringResource(R.string.a11y_duplicate_profile, profile.name),
                    editDescription = stringResource(R.string.a11y_edit_profile, profile.name),
                    canEditContextLogic = profile.contexts.size >= 2,
                    canUndo = canUndo,
                    canRedo = canRedo,
                    onEdit = onEdit,
                    onAddContext = onAddContext,
                    onEditContextLogic = onEditContextLogic,
                    onPreflight = onPreflight,
                    onDuplicate = onDuplicate,
                    onUndo = onUndo,
                    onRedo = onRedo,
                    onDelete = onDelete,
                )
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = stringResource(if (expanded) R.string.action_collapse else R.string.action_expand),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = listOf(
                    profileState,
                    stringResource(R.string.label_context_count, profile.contexts.size),
                    stringResource(R.string.label_cooldown_seconds, profile.cooldownSec),
                ).joinToString(" • "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (expanded && profile.contexts.isEmpty()) {
                InlineNotice(
                    title = stringResource(R.string.workspace_profile_cannot_match),
                    body = stringResource(R.string.workspace_profile_cannot_match_body),
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (expanded) {
                profile.contexts.forEachIndexed { index, context ->
                    ContextRow(
                        index = index,
                        context = context,
                        onEdit = { onEditContext(index, context) },
                        onDelete = { onDeleteContext(index) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun TasksScreen(
    tasks: List<Task>,
    storageDecodeIssues: List<StorageDecodeIssue>,
    onCreateTask: () -> Unit,
    onEditTask: (Task) -> Unit,
    onUndoTaskEdit: (Task) -> Unit = {},
    onRedoTaskEdit: (Task) -> Unit = {},
    onDeleteTask: (Task) -> Unit,
    onDuplicateTask: (Task) -> Unit = {},
    onRunTask: (Task) -> Unit,
    onPreflightTask: (Task) -> Unit,
    onPinTask: (Task) -> Unit,
    onAddAction: (Task) -> Unit,
    onEditAction: (Task, Int, ActionSpec) -> Unit,
    onDeleteAction: (Task, Int) -> Unit,
    onMoveAction: (Task, Int, Int) -> Unit,
    onRunAction: (Task, Int) -> Unit,
    contentPadding: PaddingValues,
    runBusy: Boolean = false,
    contentLoaded: Boolean = true,
    historyAvailability: EditHistoryAvailabilityState = EditHistoryAvailabilityState(),
) {
    if (!contentLoaded) {
        ContentLoadingState(contentPadding)
        return
    }
    if (tasks.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.empty_tasks_title),
            body = stringResource(R.string.empty_tasks_create_body),
            actionLabel = stringResource(R.string.action_create_task),
            onAction = onCreateTask,
            contentPadding = contentPadding,
        )
        return
    }
    var taskSearchQuery by rememberSaveable { mutableStateOf("") }
    val filteredTasks = if (taskSearchQuery.isBlank()) tasks
        else tasks.filter { it.name.contains(taskSearchQuery, ignoreCase = true) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        // Extra bottom inset so the extended FAB never sits on top of the last row; Scaffold's
        // innerPadding does not reserve floating-action-button height.
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md),
    ) {
        if (storageDecodeIssues.isNotEmpty()) {
            item {
                StorageDecodeWarningCard(storageDecodeIssues)
            }
        }
        item {
            OutlinedTextField(
                value = taskSearchQuery,
                onValueChange = { taskSearchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.workspace_search_tasks)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = if (taskSearchQuery.isNotEmpty()) {
                    { IconButton(onClick = { taskSearchQuery = "" }) { Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.variables_search_clear)) } }
                } else null,
                singleLine = true,
                shape = RoundedCornerShape(DesignSystem.Radii.lg),
            )
        }
        if (filteredTasks.isEmpty()) {
            item {
                InlineNotice(
                    title = stringResource(R.string.workspace_no_matching_tasks),
                    body = stringResource(R.string.workspace_no_matching_tasks_body),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        items(filteredTasks, key = { it.id }) { task ->
            TaskCard(
                task = task,
                initiallyExpanded = filteredTasks.firstOrNull()?.id == task.id,
                onEdit = { onEditTask(task) },
                onUndo = { onUndoTaskEdit(task) },
                onRedo = { onRedoTaskEdit(task) },
                canUndo = historyAvailability.canUndoTask(task.id),
                canRedo = historyAvailability.canRedoTask(task.id),
                onDelete = { onDeleteTask(task) },
                onDuplicate = { onDuplicateTask(task) },
                onRun = { onRunTask(task) },
                onPreflight = { onPreflightTask(task) },
                onPin = { onPinTask(task) },
                onAddAction = { onAddAction(task) },
                onEditAction = { index, action -> onEditAction(task, index, action) },
                onDeleteAction = { index -> onDeleteAction(task, index) },
                onMoveAction = { fromIndex, toIndex -> onMoveAction(task, fromIndex, toIndex) },
                onRunAction = { index -> onRunAction(task, index) },
                runBusy = runBusy,
            )
        }
    }
}

@Composable
private fun TaskCard(
    task: Task,
    initiallyExpanded: Boolean,
    onEdit: () -> Unit,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
    onRun: () -> Unit,
    onPreflight: () -> Unit,
    onPin: () -> Unit,
    onAddAction: () -> Unit,
    onEditAction: (Int, ActionSpec) -> Unit,
    onDeleteAction: (Int) -> Unit,
    onMoveAction: (Int, Int) -> Unit,
    onRunAction: (Int) -> Unit,
    runBusy: Boolean,
) {
    var expanded by rememberSaveable(task.id) { mutableStateOf(initiallyExpanded) }
    val runDescription = stringResource(R.string.a11y_run_task, task.name)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(DesignSystem.Radii.md),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm)) {
            Row(
                modifier = Modifier.expandCollapseToggle(expanded) { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(DesignSystem.Radii.md),
                ) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = runDescription,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(8.dp)
                            .size(20.dp)
                            .clearAndSetSemantics { },
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(task.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        stringResource(R.string.label_action_count, task.actions.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(
                    onClick = onRun,
                    modifier = Modifier.semantics { contentDescription = runDescription },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(DesignSystem.Radii.sm),
                ) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = runDescription,
                        modifier = Modifier.clearAndSetSemantics { },
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(stringResource(R.string.action_run), style = MaterialTheme.typography.labelLarge)
                }
                TaskActionsMenu(
                    contentDescription = stringResource(R.string.a11y_duplicate_task, task.name),
                    canUndo = canUndo,
                    canRedo = canRedo,
                    onEdit = onEdit,
                    onAddAction = onAddAction,
                    onPreflight = onPreflight,
                    onPin = onPin,
                    onDuplicate = onDuplicate,
                    onUndo = onUndo,
                    onRedo = onRedo,
                    onDelete = onDelete,
                )
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = stringResource(if (expanded) R.string.action_collapse else R.string.action_expand),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = listOf(
                    stringResource(R.string.label_priority_short, task.priority),
                    collisionModeTitle(task.collisionMode),
                ).joinToString(" • "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (expanded && task.actions.isEmpty()) {
                InlineNotice(
                    title = stringResource(R.string.workspace_task_has_no_actions),
                    body = stringResource(R.string.workspace_task_has_no_actions_body),
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (expanded) {
                task.actions.forEachIndexed { index, action ->
                    ActionRow(
                        index = index,
                        action = action,
                        canMoveUp = index > 0,
                        canMoveDown = index < task.actions.lastIndex,
                        onMoveUp = { onMoveAction(index, index - 1) },
                        onMoveDown = { onMoveAction(index, index + 1) },
                        onEdit = { onEditAction(index, action) },
                        onDelete = { onDeleteAction(index) },
                        onRun = { onRunAction(index) },
                        runBusy = runBusy,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileActionsMenu(
    contentDescription: String,
    editDescription: String,
    canEditContextLogic: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    onEdit: () -> Unit,
    onAddContext: () -> Unit,
    onEditContextLogic: () -> Unit,
    onPreflight: () -> Unit,
    onDuplicate: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onDelete: () -> Unit,
) {
    ContextualActionsMenu(
        contentDescription = contentDescription,
        actions = listOf(
            ContextualMenuAction(R.string.action_edit, accessibilityLabel = editDescription, onClick = onEdit),
            ContextualMenuAction(R.string.profile_add_context, onClick = onAddContext),
            ContextualMenuAction(R.string.profile_edit_context_logic, enabled = canEditContextLogic, onClick = onEditContextLogic),
            ContextualMenuAction(R.string.action_preflight, onClick = onPreflight),
            ContextualMenuAction(R.string.action_duplicate, onClick = onDuplicate),
            ContextualMenuAction(R.string.action_undo, enabled = canUndo, onClick = onUndo),
            ContextualMenuAction(R.string.action_redo, enabled = canRedo, onClick = onRedo),
            ContextualMenuAction(R.string.profile_delete, onClick = onDelete),
        ),
    )
}

@Composable
private fun TaskActionsMenu(
    contentDescription: String,
    canUndo: Boolean,
    canRedo: Boolean,
    onEdit: () -> Unit,
    onAddAction: () -> Unit,
    onPreflight: () -> Unit,
    onPin: () -> Unit,
    onDuplicate: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onDelete: () -> Unit,
) {
    ContextualActionsMenu(
        contentDescription = contentDescription,
        actions = listOf(
            ContextualMenuAction(R.string.action_edit, onClick = onEdit),
            ContextualMenuAction(R.string.task_add_action, onClick = onAddAction),
            ContextualMenuAction(R.string.action_preflight, onClick = onPreflight),
            ContextualMenuAction(R.string.action_pin, onClick = onPin),
            ContextualMenuAction(R.string.action_duplicate, onClick = onDuplicate),
            ContextualMenuAction(R.string.action_undo, enabled = canUndo, onClick = onUndo),
            ContextualMenuAction(R.string.action_redo, enabled = canRedo, onClick = onRedo),
            ContextualMenuAction(R.string.task_delete, onClick = onDelete),
        ),
    )
}

private data class ContextualMenuAction(
    @StringRes val labelRes: Int,
    val enabled: Boolean = true,
    val accessibilityLabel: String? = null,
    val onClick: () -> Unit,
)

@Composable
private fun ContextualActionsMenu(
    contentDescription: String,
    actions: List<ContextualMenuAction>,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.semantics { this.contentDescription = contentDescription },
        ) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = contentDescription,
                modifier = Modifier.clearAndSetSemantics { },
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            actions.forEach { action ->
                DropdownMenuItem(
                    text = { Text(stringResource(action.labelRes)) },
                    enabled = action.enabled,
                    modifier = Modifier.semantics {
                        action.accessibilityLabel?.let { this.contentDescription = it }
                    },
                    onClick = {
                        expanded = false
                        action.onClick()
                    },
                )
            }
        }
    }
}

@Composable
internal fun DuplicateMenu(
    contentDescription: String,
    onDuplicate: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.semantics { this.contentDescription = contentDescription },
        ) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = contentDescription,
                modifier = Modifier.clearAndSetSemantics { },
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_duplicate)) },
                onClick = {
                    expanded = false
                    onDuplicate()
                },
            )
        }
    }
}

@Composable
private fun ActionRow(
    index: Int,
    action: ActionSpec,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onRun: () -> Unit,
    runBusy: Boolean,
) {
    var menuExpanded by rememberSaveable { mutableStateOf(false) }
    val resources = LocalContext.current.resources
    val metadata = ActionMetadataRegistry.get(action.type)
    val capability = ActionCapabilityRegistry.get(action.type)
    val metadataName = metadata?.let { stringResource(it.nameRes) }
    val metadataDescription = metadata?.let { stringResource(it.descriptionRes) }
    val actionLabel = action.label ?: metadataName ?: stringResource(R.string.action_unknown_name)
    val editDescription = stringResource(R.string.a11y_edit_action, index + 1, actionLabel)
    val deleteDescription = stringResource(R.string.a11y_delete_action, index + 1, actionLabel)
    val runDescription = stringResource(R.string.a11y_run_action, index + 1, actionLabel)
    val moveUpDescription = stringResource(R.string.a11y_move_action_up, index + 1, actionLabel)
    val moveDownDescription = stringResource(R.string.a11y_move_action_down, index + 1, actionLabel)
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(DesignSystem.Radii.sm),
                modifier = Modifier.sizeIn(minWidth = 28.dp, minHeight = 28.dp),
            ) {
                Box(modifier = Modifier.padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = "${index + 1}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(actionLabel, style = MaterialTheme.typography.titleSmall)
                Text(
                    ActionSummaryFormatter.format(resources, action.type, action.args)
                        .ifBlank { metadataDescription ?: stringResource(R.string.workspace_no_arguments) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (capability.level != CapabilityLevel.Supported) {
                    Spacer(Modifier.height(6.dp))
                    StatusPill(
                        if (capability.level == CapabilityLevel.Unsupported) stringResource(R.string.label_unsupported) else stringResource(R.string.status_needs_setup),
                        if (capability.level == CapabilityLevel.Unsupported) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
                action.condition?.takeIf(String::isNotBlank)?.let { condition ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.action_condition_summary, condition),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (action.continueOnError) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.action_continue_on_error_summary),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
            IconButton(
                onClick = onEdit,
                modifier = Modifier.semantics { contentDescription = editDescription },
            ) {
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = editDescription,
                    modifier = Modifier.clearAndSetSemantics { },
                )
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.nav_more))
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    // The menu item text is only "Move up"/"Move down", so without an explicit
                    // description a screen reader cannot say which action moves.
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.projects_move_up)) },
                        onClick = { menuExpanded = false; onMoveUp() },
                        enabled = canMoveUp,
                        modifier = Modifier.semantics { contentDescription = moveUpDescription },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.projects_move_down)) },
                        onClick = { menuExpanded = false; onMoveDown() },
                        enabled = canMoveDown,
                        modifier = Modifier.semantics { contentDescription = moveDownDescription },
                    )
                    // Flow-control markers are omitted rather than disabled: "Run this if"
                    // has no meaning on its own, so offering it greyed out would only invite
                    // the question of why.
                    if (SingleActionRun.isRunnableAlone(action)) {
                        // Only one run happens at a time, and a second tap used to be swallowed
                        // with no sign anything had happened. Say which state the option is in.
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        if (runBusy) R.string.action_run_alone_busy else R.string.action_run_alone,
                                    ),
                                )
                            },
                            onClick = { menuExpanded = false; onRun() },
                            enabled = !runBusy,
                            leadingIcon = { Icon(Icons.Filled.PlayArrow, contentDescription = runDescription) },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_delete)) },
                        onClick = { menuExpanded = false; onDelete() },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = deleteDescription, tint = MaterialTheme.colorScheme.error) },
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
    }
}

@Composable
private fun ContextRow(
    index: Int,
    context: ContextSpec,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val contextTypeLabel = stringResource(contextTitleRes(context.type))
    val editDescription = stringResource(R.string.a11y_edit_context, index + 1, contextTypeLabel)
    val deleteDescription = stringResource(R.string.a11y_delete_context, index + 1, contextTypeLabel)
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f),
        shape = RoundedCornerShape(DesignSystem.Radii.sm),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm),
        ) {
            Column(Modifier.weight(1f)) {
                Text(contextTypeLabel, style = MaterialTheme.typography.titleSmall)
                Text(
                    contextConfigSummary(context),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (context.invert) {
                StatusPill(stringResource(R.string.label_inverted), MaterialTheme.colorScheme.secondary)
            }
            IconButton(
                onClick = onEdit,
                modifier = Modifier.semantics { contentDescription = editDescription },
            ) {
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = editDescription,
                    modifier = Modifier.clearAndSetSemantics { },
                )
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.semantics { contentDescription = deleteDescription },
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = deleteDescription,
                    modifier = Modifier.clearAndSetSemantics { },
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
