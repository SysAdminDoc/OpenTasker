package com.opentasker.ui.screens

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentasker.app.R
import com.opentasker.core.model.DEFAULT_PROJECT_ID
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Project
import com.opentasker.core.model.Task
import com.opentasker.ui.theme.DesignSystem

@Composable
fun ProjectScopeBar(
    projects: List<Project>,
    selectedProjectId: Long?,
    onSelectProject: (Long?) -> Unit,
    onCreateProject: (String, onCreated: () -> Unit) -> Unit,
    onRenameProject: (Project, String, onRenamed: () -> Unit) -> Unit,
    onReorderProject: (Project, Int) -> Unit,
    onDeleteProject: (Project, Project) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var managerVisible by rememberSaveable { mutableStateOf(false) }
    val selectedName = projects.firstOrNull { it.id == selectedProjectId }?.name
        ?: stringResource(R.string.projects_all)

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = DesignSystem.Screen.horizontalPadding, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.projects_scope_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 2.dp),
            )
            TextButton(
                onClick = { menuExpanded = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(DesignSystem.Radii.md),
            ) {
                Icon(Icons.Filled.Folder, contentDescription = stringResource(R.string.projects_scope_label))
                Text(
                    selectedName,
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(Icons.Filled.ExpandMore, contentDescription = stringResource(R.string.action_expand))
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.projects_all)) },
                    onClick = {
                        onSelectProject(null)
                        menuExpanded = false
                    },
                )
                projects.forEach { project ->
                    DropdownMenuItem(
                        text = { Text(project.name) },
                        onClick = {
                            onSelectProject(project.id)
                            menuExpanded = false
                        },
                    )
                }
            }
            }
            IconButton(onClick = { managerVisible = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.projects_manage))
            }
        }
    }

    if (managerVisible) {
        ProjectManagerDialog(
            projects = projects,
            onDismiss = { managerVisible = false },
            onCreateProject = onCreateProject,
            onRenameProject = onRenameProject,
            onReorderProject = onReorderProject,
            onDeleteProject = onDeleteProject,
        )
    }
}

@Composable
private fun ProjectManagerDialog(
    projects: List<Project>,
    onDismiss: () -> Unit,
    onCreateProject: (String, onCreated: () -> Unit) -> Unit,
    onRenameProject: (Project, String, onRenamed: () -> Unit) -> Unit,
    onReorderProject: (Project, Int) -> Unit,
    onDeleteProject: (Project, Project) -> Unit,
) {
    var newName by rememberSaveable { mutableStateOf("") }
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var editingName by rememberSaveable { mutableStateOf("") }
    var deletingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var targetId by rememberSaveable { mutableStateOf<Long?>(null) }
    val deleting = projects.firstOrNull { it.id == deletingId }
    val target = projects.firstOrNull { it.id == targetId }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.projects_manage)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text(stringResource(R.string.projects_new_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        // The field is cleared only once the project exists: a duplicate name is
                        // rejected by the view model, and wiping the input first left the user
                        // retyping a name they could no longer see.
                        onCreateProject(newName) { newName = "" }
                    },
                    enabled = newName.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.projects_create))
                }
                projects.forEachIndexed { index, project ->
                    val isDefault = project.id == DEFAULT_PROJECT_ID
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        if (editingId == project.id) {
                            OutlinedTextField(
                                value = editingName,
                                onValueChange = { editingName = it },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                // Closed only once the rename lands: a duplicate name is rejected
                                // and the field has to stay open to fix it.
                                onClick = { onRenameProject(project, editingName) { editingId = null } },
                            ) { Text(stringResource(R.string.action_save)) }
                        } else {
                            // The row's four controls leave the name a narrow column, so ellipsize
                            // rather than wrapping a short name across two lines mid-word.
                            Text(
                                project.name,
                                Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            IconButton(onClick = { onReorderProject(project, -1) }, enabled = index > 0) {
                                Icon(Icons.Filled.ArrowUpward, contentDescription = stringResource(R.string.projects_move_up))
                            }
                            IconButton(onClick = { onReorderProject(project, 1) }, enabled = index < projects.lastIndex) {
                                Icon(Icons.Filled.ArrowDownward, contentDescription = stringResource(R.string.projects_move_down))
                            }
                            IconButton(
                                onClick = {
                                    editingId = project.id
                                    editingName = project.name
                                },
                                enabled = !isDefault,
                            ) {
                                Icon(
                                    Icons.Filled.Edit,
                                    contentDescription = stringResource(R.string.action_rename),
                                )
                            }
                            IconButton(
                                onClick = {
                                    deletingId = project.id
                                    targetId = projects.firstOrNull { it.id != project.id }?.id
                                },
                                enabled = !isDefault,
                            ) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = stringResource(R.string.action_delete),
                                    tint = if (isDefault) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f) else MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) } },
    )

    if (deleting != null) {
        AlertDialog(
            onDismissRequest = { deletingId = null },
            title = { Text(stringResource(R.string.projects_delete_title, deleting.name)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.projects_delete_body))
                    ProjectTargetMenu(
                        projects = projects.filterNot { it.id == deleting.id },
                        selected = target,
                        onSelect = { targetId = it.id },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (target != null) onDeleteProject(deleting, target)
                        deletingId = null
                    },
                    enabled = target != null,
                ) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deletingId = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun ProjectTargetMenu(
    projects: List<Project>,
    selected: Project?,
    onSelect: (Project) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
        Text(selected?.name ?: stringResource(R.string.projects_choose_destination))
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        projects.forEach { project ->
            DropdownMenuItem(
                text = { Text(project.name) },
                onClick = {
                    onSelect(project)
                    expanded = false
                },
            )
        }
    }
}

/**
 * The tasks a profile editor offers: the selected project's, like the create dialog, plus any task
 * the profile already runs. A profile may run a task that lives in another project, and leaving
 * that task out of the list would make the editor unable to show, or keep, the binding.
 */
internal fun profileEditorTasks(profile: Profile, projectTasks: List<Task>, allTasks: List<Task>): List<Task> {
    val referenced = setOfNotNull(profile.enterTaskId, profile.exitTaskId, profile.fallbackTaskId)
    val listed = projectTasks.mapTo(HashSet()) { it.id }
    return projectTasks + allTasks.filter { it.id in referenced && it.id !in listed }
}
