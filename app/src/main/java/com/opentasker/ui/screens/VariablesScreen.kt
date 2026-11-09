package com.opentasker.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentasker.app.R
import com.opentasker.core.model.Variable
import com.opentasker.core.model.VariableNamePolicy
import com.opentasker.core.model.DEFAULT_PROJECT_ID
import com.opentasker.core.storage.AesGcmVariableSecretCodec
import com.opentasker.ui.theme.DesignSystem

@Composable
fun VariablesScreen(
    variables: List<Variable>,
    contentPadding: PaddingValues,
    projectId: Long = DEFAULT_PROJECT_ID,
    focusVariableName: String? = null,
    focusVariableProjectId: Long = DEFAULT_PROJECT_ID,
    onFocusVariableConsumed: () -> Unit = {},
    onUpdate: (
        previousName: String?,
        name: String,
        value: String,
        isSecret: Boolean,
        successMessage: UiMessage,
        projectId: Long,
        onSaved: () -> Unit,
    ) -> Unit,
    onDelete: (name: String, successMessage: UiMessage, projectId: Long) -> Unit,
    onMessage: (String) -> Unit,
    contentLoaded: Boolean = true,
) {
    // An unread database and an empty one look identical from here, so without this a cold start
    // with stored variables flashes "No global variables yet" before Room's first emission.
    if (!contentLoaded) {
        ContentLoadingState(contentPadding)
        return
    }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var editTargetName by rememberSaveable { mutableStateOf<String?>(null) }
    var editTargetProjectId by rememberSaveable { mutableStateOf(DEFAULT_PROJECT_ID) }
    var pendingDeleteName by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDeleteProjectId by rememberSaveable { mutableStateOf(DEFAULT_PROJECT_ID) }
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(focusVariableName, focusVariableProjectId, variables) {
        val target = focusVariableName?.let { name ->
            variables.firstOrNull { it.name == name && it.projectId == focusVariableProjectId }
        }
        if (target != null) {
            editTargetName = target.name
            editTargetProjectId = target.projectId
            onFocusVariableConsumed()
        }
    }

    val filtered = remember(variables, searchQuery) {
        if (searchQuery.isBlank()) variables
        else variables.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
                (!it.isSecret && it.value.contains(searchQuery, ignoreCase = true))
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(DesignSystem.Screen.horizontalPadding),
        verticalArrangement = Arrangement.spacedBy(DesignSystem.Screen.cardGap),
    ) {
        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text(stringResource(R.string.variables_search_label)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = if (searchQuery.isNotBlank()) {
                    {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.variables_search_clear))
                        }
                    }
                } else {
                    null
                },
                singleLine = true,
                shape = RoundedCornerShape(DesignSystem.Radii.md),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (filtered.isEmpty()) {
            item {
                VariableEmptyState(
                    title = if (variables.isEmpty()) stringResource(R.string.empty_variables_title) else stringResource(R.string.empty_variables_search),
                    body = if (variables.isEmpty()) {
                        stringResource(R.string.empty_variables_body)
                    } else {
                        stringResource(R.string.empty_variables_search_body)
                    },
                    onCreate = if (variables.isEmpty()) {
                        { showCreateDialog = true }
                    } else {
                        null
                    },
                )
            }
        } else {
            items(filtered, key = { "${it.projectId}:${it.name}" }) { variable ->
                VariableRow(
                    variable = variable,
                    onEdit = {
                        editTargetName = variable.name
                        editTargetProjectId = variable.projectId
                    },
                    onDelete = {
                        pendingDeleteName = variable.name
                        pendingDeleteProjectId = variable.projectId
                    },
                )
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = DesignSystem.Spacing.md),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(
                    onClick = { showCreateDialog = true },
                    shape = RoundedCornerShape(DesignSystem.Radii.md),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text(stringResource(R.string.variables_create), modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }

    val editTarget = remember(variables, editTargetName, editTargetProjectId) {
        editTargetName?.let { targetName -> variables.firstOrNull { it.name == targetName && it.projectId == editTargetProjectId } }
    }

    if (showCreateDialog) {
        val createdMsg = UiMessage(R.string.variables_created)
        VariableEditorDialog(
            variable = null,
            existingNames = variables.mapTo(hashSetOf()) { it.name },
            onDismiss = { showCreateDialog = false },
            // The dialog closes only once the save lands, so a rejected secret isn't lost.
            onSave = { name, value, isSecret ->
                onUpdate(null, name, value, isSecret, createdMsg, projectId) { showCreateDialog = false }
            },
        )
    }

    pendingDeleteName?.let { name ->
        val deletedMsg = UiMessage(R.string.variables_deleted, listOf(name))
        AlertDialog(
            onDismissRequest = { pendingDeleteName = null },
            icon = {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.action_delete),
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text(stringResource(R.string.dialog_delete_variable, name)) },
            text = {
                Text(
                    stringResource(R.string.variables_delete_body),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        // Success/failure feedback is emitted by the ViewModel after the
                        // delete actually resolves, not optimistically at click time.
                        onDelete(name, deletedMsg, pendingDeleteProjectId)
                        pendingDeleteName = null
                        if (editTargetName == name) editTargetName = null
                    },
                ) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteName = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    editTarget?.let { target ->
        val updatedMsg = UiMessage(R.string.variables_updated, listOf(target.name))
        VariableEditorDialog(
            variable = target,
            existingNames = variables.mapTo(hashSetOf()) { it.name },
            onDismiss = { editTargetName = null },
            onSave = { name, newValue, isSecret ->
                onUpdate(target.name, name, newValue, isSecret, updatedMsg, target.projectId) { editTargetName = null }
            },
        )
    }
}

@Composable
private fun VariableEmptyState(title: String, body: String, onCreate: (() -> Unit)? = null) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f),
        shape = RoundedCornerShape(DesignSystem.Radii.lg),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                Icons.Filled.Info,
                contentDescription = stringResource(R.string.content_description_info),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (onCreate != null) {
                    TextButton(onClick = onCreate, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
                        Text(stringResource(R.string.empty_variables_create))
                    }
                }
            }
        }
    }
}

@Composable
private fun VariableRow(
    variable: Variable,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val sensitive = variable.isSecret
    Card(
        onClick = onEdit,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(DesignSystem.Radii.md),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(DesignSystem.Radii.sm),
            ) {
                Icon(
                    if (sensitive) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = stringResource(
                        if (sensitive) {
                            R.string.variables_sensitive_indicator
                        } else {
                            R.string.variables_standard_indicator
                        },
                    ),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(11.dp),
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "%${variable.name}",
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = when {
                        !sensitive -> variable.value
                        !variable.secretAvailable -> stringResource(R.string.variables_secret_unavailable)
                        else -> stringResource(R.string.variables_masked_value)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (sensitive) {
                    VariablePill(stringResource(R.string.label_hidden), MaterialTheme.colorScheme.secondary)
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.action_delete),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun VariablePill(label: String, color: Color) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}

@Composable
private fun VariableEditorDialog(
    variable: Variable?,
    existingNames: Set<String>,
    onDismiss: () -> Unit,
    onSave: (String, String, Boolean) -> Unit,
) {
    val stateKey = variable?.name ?: "new-variable"
    var name by rememberSaveable(stateKey) { mutableStateOf(variable?.name.orEmpty()) }
    // Never place plaintext secrets in Android saved-instance state.
    var value by remember(stateKey) { mutableStateOf(variable?.value.orEmpty()) }
    var nonSecretDraft by rememberSaveable(stateKey) { mutableStateOf<String?>(null) }
    var isSecret by rememberSaveable(stateKey) { mutableStateOf(variable?.isSecret == true) }
    var revealed by remember(stateKey) { mutableStateOf(false) }
    LaunchedEffect(stateKey, isSecret) {
        val draft = nonSecretDraft
        if (!isSecret && draft != null) {
            value = draft
        }
    }
    val normalizedName = VariableNamePolicy.promoteToGlobal(name)
    val duplicateName = normalizedName != null &&
        normalizedName != variable?.name &&
        normalizedName in existingNames
    val valueBytes = value.toByteArray(Charsets.UTF_8).size
    val needsReentry = variable?.isSecret == true && !variable.secretAvailable
    val canSave = normalizedName != null &&
        !duplicateName &&
        valueBytes <= AesGcmVariableSecretCodec.MAX_SECRET_PLAINTEXT_BYTES &&
        (!needsReentry || value.isNotEmpty())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (variable == null) stringResource(R.string.variables_create) else "%${variable.name}",
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                            .filter { char -> char.isLetterOrDigit() || char == '_' || char == '-' }
                            .take(VariableNamePolicy.MAX_LENGTH)
                    },
                    label = { Text(stringResource(R.string.variables_name_label)) },
                    isError = name.isNotEmpty() && (normalizedName == null || duplicateName),
                    supportingText = if (duplicateName) {
                        { Text(stringResource(R.string.variables_name_duplicate)) }
                    } else {
                        { Text(stringResource(R.string.variables_name_helper)) }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (needsReentry) {
                    Text(
                        stringResource(R.string.variables_secret_reentry_helper),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = {
                        value = it.take(AesGcmVariableSecretCodec.MAX_SECRET_PLAINTEXT_BYTES)
                        if (!isSecret) nonSecretDraft = value
                    },
                    label = {
                        Text(
                            stringResource(
                                R.string.variables_value_label,
                                variable?.name ?: normalizedName.orEmpty().ifBlank { stringResource(R.string.variables_default_name) },
                            ),
                        )
                    },
                    visualTransformation = if (isSecret && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
                    // A secret needs the password keyboard type, not just the masking. Without it
                    // the IME keeps autocorrect and its personal dictionary on and can learn the
                    // value, then offer it as a suggestion in another app.
                    keyboardOptions = if (isSecret) {
                        KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false)
                    } else {
                        KeyboardOptions.Default
                    },
                    trailingIcon = if (isSecret) {
                        {
                            IconButton(onClick = { revealed = !revealed }) {
                                Icon(
                                    if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = stringResource(
                                        if (revealed) R.string.variables_hide_secret else R.string.variables_reveal_secret,
                                    ),
                                )
                            }
                        }
                    } else {
                        null
                    },
                    isError = valueBytes > AesGcmVariableSecretCodec.MAX_SECRET_PLAINTEXT_BYTES,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .toggleable(value = isSecret, role = Role.Switch) {
                            isSecret = it
                            revealed = false
                            nonSecretDraft = if (it) null else value
                        },
                    horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.variables_secret_label), style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(R.string.variables_secret_helper),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = isSecret, onCheckedChange = null)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = { onSave(requireNotNull(normalizedName), value, isSecret) },
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
