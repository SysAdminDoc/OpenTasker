package com.opentasker.ui.screens

import android.annotation.SuppressLint
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.opentasker.app.R
import com.opentasker.core.actions.ActionField
import com.opentasker.core.actions.ActionFieldPolicy
import com.opentasker.core.actions.ActionFileScope
import com.opentasker.core.actions.ActionMetadata
import com.opentasker.core.actions.ActionMetadataRegistry
import com.opentasker.core.actions.DEFAULT_EDITOR_EVENT_VARIABLES
import com.opentasker.core.actions.EditorEventVariable
import com.opentasker.core.actions.FieldType
import com.opentasker.core.actions.NotificationTaskBindings
import com.opentasker.core.actions.NotificationTaskCandidate
import com.opentasker.core.actions.NotificationTaskReference
import com.opentasker.core.actions.NotificationTaskResolution
import com.opentasker.core.actions.SubTaskInputs
import com.opentasker.core.actions.VariableChipOption
import com.opentasker.core.actions.acceptsVariableType
import com.opentasker.core.actions.insertVariableChip
import com.opentasker.core.actions.mergeActionArguments
import com.opentasker.core.actions.typedVariableOptions
import com.opentasker.core.capabilities.ActionCapabilityRegistry
import com.opentasker.core.capabilities.CapabilityLevel
import com.opentasker.core.engine.FlowControl
import com.opentasker.core.engine.SUB_TASK_ACTION_ID
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import com.opentasker.core.model.Variable
import com.opentasker.core.engine.tryRetryPlan
import com.opentasker.ui.theme.DesignSystem
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class LocalizedActionMetadata(
    val metadata: ActionMetadata,
    val name: String,
    val description: String,
    val category: String,
)

internal fun filterActionPickerItems(
    items: List<LocalizedActionMetadata>,
    query: String,
): List<LocalizedActionMetadata> {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isEmpty()) return items
    return items.filter { item ->
        item.name.contains(normalizedQuery, ignoreCase = true) ||
            item.description.contains(normalizedQuery, ignoreCase = true) ||
            item.metadata.id.contains(normalizedQuery, ignoreCase = true)
    }
}

internal const val ACTION_CONTINUE_ON_ERROR_TAG = "action_continue_on_error"
internal const val ACTION_VARIABLE_PICKER_TAG = "action_variable_picker"
private val LOCALE_PLUGIN_ACTION_IDS = setOf("plugin.locale.fire", "plugin.locale.query")

@Composable
@SuppressLint("LocalContextGetResourceValueCall")
internal fun ActionPickerDialog(
    onDismiss: () -> Unit,
    onSelect: (ActionMetadata) -> Unit,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    var query by rememberSaveable { mutableStateOf("") }
    val localizedActions = remember(configuration) {
        ActionMetadataRegistry.all()
            .asSequence()
            .filter { it.pickerVisible }
            .map { metadata ->
                LocalizedActionMetadata(
                    metadata = metadata,
                    name = context.getString(metadata.nameRes),
                    description = context.getString(metadata.descriptionRes),
                    category = context.getString(metadata.categoryRes),
                )
            }
            .toList()
    }
    val filteredActions = remember(localizedActions, query) { filterActionPickerItems(localizedActions, query) }
    val actionGroups = remember(filteredActions) {
        filteredActions
            .groupBy { it.category }
            .toSortedMap()
            .map { (category, actions) -> category to actions.sortedBy { it.name } }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_add_action)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.action_picker_search_label)) },
                    placeholder = { Text(stringResource(R.string.action_picker_search_hint)) },
                    trailingIcon = if (query.isNotEmpty()) {
                        {
                            TextButton(onClick = { query = "" }) {
                                Text(stringResource(R.string.action_picker_clear_search))
                            }
                        }
                    } else {
                        null
                    },
                    singleLine = true,
                )
                if (actionGroups.isEmpty()) {
                    Text(
                        stringResource(R.string.action_picker_no_results),
                        modifier = Modifier.padding(vertical = 24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm),
                    ) {
                        actionGroups.forEach { (category, actions) ->
                            item(key = "category-$category") {
                                Text(
                                    category,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                            items(actions, key = { it.metadata.id }) { localized ->
                                val metadata = localized.metadata
                                val capability = ActionCapabilityRegistry.get(metadata.id)
                                Card(
                                    onClick = { onSelect(metadata) },
                                    enabled = capability.canAdd,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    ),
                                    // In every static scheme surfaceVariant IS the dialog's own
                                    // container colour, so compositing it over itself at any alpha
                                    // yields the same colour: the enabled/disabled distinction the
                                    // two alphas were meant to draw was mathematically zero.
                                    border = BorderStroke(
                                        width = 1.dp,
                                        color = if (capability.canAdd) {
                                            MaterialTheme.colorScheme.outline
                                        } else {
                                            MaterialTheme.colorScheme.outlineVariant
                                        },
                                    ),
                                    shape = RoundedCornerShape(14.dp),
                                ) {
                                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.xs)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm),
                                        ) {
                                            Text(localized.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                            if (capability.level != CapabilityLevel.Supported) {
                                                StatusPill(
                                                    if (capability.level == CapabilityLevel.Unsupported) stringResource(R.string.label_unsupported) else stringResource(R.string.label_setup),
                                                    if (capability.level == CapabilityLevel.Unsupported) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                                )
                                            }
                                        }
                                        Text(localized.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        if (capability.level != CapabilityLevel.Supported) {
                                            Text(stringResource(capability.reasonRes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

internal fun existingActionArgValue(
    actionId: String,
    key: String,
    args: Map<String, String>,
    tasks: List<Task> = emptyList(),
): String = args[key] ?: notificationTaskEditorValue(actionId, key, args, tasks) ?: when (actionId to key) {
    "brightness.set" to "brightness" -> args["level"]
    "screenshot.take" to "path" -> args["filename"]
    "file.read" to "var" -> args["variable"]
    "file.write" to "text" -> args["content"]
    "file.append" to "text" -> args["content"]
    "file.list" to "var" -> args["variable"]
    "http.get" to "var" -> args["variable"]
    "http.post" to "data" -> args["body"]
    "http.post" to "var" -> args["variable"]
    else -> null
}.orEmpty()

private fun notificationTaskEditorValue(
    actionId: String,
    key: String,
    args: Map<String, String>,
    tasks: List<Task>,
): String? {
    if (actionId != "notify.show") return null
    val buttonIndex = (1..NotificationTaskBindings.BUTTON_COUNT)
        .firstOrNull { NotificationTaskBindings.taskIdKey(it) == key }
        ?: return null
    val reference = NotificationTaskBindings.parse(args, buttonIndex) ?: return ""
    return when (val resolution = NotificationTaskBindings.resolve(reference, tasks.toNotificationCandidates())) {
        is NotificationTaskResolution.Bound -> resolution.task.id.toString()
        else -> ""
    }
}

internal fun unresolvedNotificationTaskBindings(
    actionId: String,
    args: Map<String, String>,
    tasks: List<Task>,
): Map<String, NotificationTaskResolution> {
    if (actionId != "notify.show") return emptyMap()
    val candidates = tasks.toNotificationCandidates()
    return (1..NotificationTaskBindings.BUTTON_COUNT).mapNotNull { buttonIndex ->
        val reference = NotificationTaskBindings.parse(args, buttonIndex) ?: return@mapNotNull null
        val resolution = NotificationTaskBindings.resolve(reference, candidates)
        if (resolution is NotificationTaskResolution.Bound) {
            null
        } else {
            NotificationTaskBindings.taskIdKey(buttonIndex) to resolution
        }
    }.toMap()
}

private fun List<Task>.toNotificationCandidates(): List<NotificationTaskCandidate> =
    map { NotificationTaskCandidate(it.id, it.name) }

@Composable
internal fun ActionConfigDialog(
    state: ActionEditState,
    tasks: List<Task> = emptyList(),
    enclosingActions: List<ActionSpec> = emptyList(),
    globalVariables: List<Variable> = emptyList(),
    eventVariables: List<EditorEventVariable> = DEFAULT_EDITOR_EVENT_VARIABLES,
    onDismiss: () -> Unit,
    onSave: (ActionSpec) -> Unit,
) {
    val metadataName = stringResource(state.metadata.nameRes)
    val metadataDescription = stringResource(state.metadata.descriptionRes)
    var label by rememberSaveable(state.existing?.id, state.metadata.id, metadataName) {
        mutableStateOf(state.existing?.label ?: metadataName)
    }
    var condition by rememberSaveable(state.existing?.id, state.metadata.id) {
        mutableStateOf(state.existing?.condition.orEmpty())
    }
    var continueOnError by rememberSaveable(state.existing?.id, state.metadata.id) {
        mutableStateOf(state.existing?.continueOnError ?: false)
    }
    var values by rememberSaveable(state.existing?.id, state.metadata.id) {
        mutableStateOf(
            state.metadata.fields.associate { field ->
                field.key to existingActionArgValue(
                    actionId = state.metadata.id,
                    key = field.key,
                    args = state.existing?.args.orEmpty(),
                    tasks = tasks,
                )
            }
        )
    }
    // Run Task passes every other argument to the sub-task as a variable; the form only has the
    // task field, so the rest are edited here and put back on save (A-354).
    val isSubTaskRun = state.metadata.id == SUB_TASK_ACTION_ID
    val openedInputs = remember(state.existing?.args, state.metadata.id) {
        if (isSubTaskRun) {
            SubTaskInputs.from(state.existing?.args.orEmpty(), state.metadata.fields.mapTo(mutableSetOf()) { it.key })
        } else {
            emptyList()
        }
    }
    var subTaskInputs by rememberSaveable(state.existing?.id, state.metadata.id) { mutableStateOf(openedInputs) }
    val subTaskInputIssues = SubTaskInputs.validate(subTaskInputs)
    val initialTaskBindingIssues = remember(state.existing?.args, state.metadata.id, tasks) {
        unresolvedNotificationTaskBindings(
            actionId = state.metadata.id,
            args = state.existing?.args.orEmpty(),
            tasks = tasks,
        )
    }
    var addressedTaskBindingKeys by rememberSaveable(state.existing?.id, state.metadata.id) {
        mutableStateOf(emptyList<String>())
    }
    val taskBindingIssues = initialTaskBindingIssues.filterKeys { it !in addressedTaskBindingKeys }
    val capability = remember(state.metadata.id) { ActionCapabilityRegistry.get(state.metadata.id) }
    val availableTaskIds = remember(tasks) { tasks.mapTo(mutableSetOf()) { it.id } }
    val validationIssues = ActionFieldPolicy.validateForm(state.metadata, values, availableTaskIds)
    val variableOptions = remember(enclosingActions, state.index, globalVariables, eventVariables) {
        typedVariableOptions(
            actions = enclosingActions,
            editingIndex = state.index,
            globalNames = globalVariables.map(Variable::name),
            eventVariables = eventVariables,
        )
    }
    val retryPlan = remember(enclosingActions, state.index, state.metadata.id) {
        if (state.metadata.id == FlowControl.TRY && state.index != null) {
            tryRetryPlan(enclosingActions, state.index)
        } else {
            null
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        // Back and Cancel still dismiss in one tap; only a stray tap on the scrim is refused.
        // These forms carry many fields, and discarding them has no undo because nothing was
        // saved yet.
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(metadataName) },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md),
            ) {
                item {
                    Text(metadataDescription, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (state.metadata.id == FlowControl.TRY) {
                        Spacer(Modifier.height(8.dp))
                        if (retryPlan == null ||
                            (retryPlan.retryableActionIds.isEmpty() && retryPlan.nonRetryableActionIds.isEmpty())
                        ) {
                            Text(
                                stringResource(R.string.flow_try_retry_summary_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            retryPlan.retryableActionIds.takeIf { it.isNotEmpty() }?.let { actionIds ->
                                Text(
                                    stringResource(
                                        R.string.flow_try_retryable_summary,
                                        actionIds.map { actionId -> actionDisplayName(actionId) }.joinToString(),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            retryPlan.nonRetryableActionIds.takeIf { it.isNotEmpty() }?.let { actionIds ->
                                Text(
                                    stringResource(
                                        R.string.flow_try_non_retryable_summary,
                                        actionIds.map { actionId -> actionDisplayName(actionId) }.joinToString(),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                    if (capability.level != CapabilityLevel.Supported) {
                        Spacer(Modifier.height(8.dp))
                        Surface(
                            color = if (capability.level == CapabilityLevel.Unsupported) {
                                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                            } else {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                            },
                            shape = RoundedCornerShape(DesignSystem.Radii.lg),
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.xs)) {
                                Text(
                                    stringResource(capability.reasonRes),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                // The reason alone told users what was missing but not where to
                                // fix it, which is what sent people to edit the manifest instead.
                                if (capability.level == CapabilityLevel.RequiresSetup) {
                                    Text(
                                        stringResource(R.string.capability_grant_from_setup),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it },
                        label = { Text(stringResource(R.string.action_label_field)) },
                        supportingText = { Text(stringResource(R.string.action_label_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = condition,
                        onValueChange = { condition = it },
                        label = { Text(stringResource(R.string.action_condition_label)) },
                        supportingText = { Text(stringResource(R.string.action_condition_helper)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    VariableChipPicker(
                        options = variableOptions.filter { it.type != com.opentasker.core.actions.ActionValueType.ARRAY },
                        onSelect = { option -> condition = insertVariableChip(condition, option) },
                    )
                    Spacer(Modifier.height(12.dp))
                    val continueStateDescription = stringResource(
                        if (continueOnError) R.string.label_on else R.string.label_off,
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f),
                        shape = RoundedCornerShape(DesignSystem.Radii.lg),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(ACTION_CONTINUE_ON_ERROR_TAG)
                            .toggleable(
                                value = continueOnError,
                                role = Role.Switch,
                                onValueChange = { continueOnError = it },
                            )
                            .semantics { stateDescription = continueStateDescription },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.action_continue_on_error_label), style = MaterialTheme.typography.labelLarge)
                                Text(
                                    stringResource(R.string.action_continue_on_error_helper),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = continueOnError, onCheckedChange = null)
                        }
                    }
                }
                items(state.metadata.fields, key = { it.key }) { field ->
                    ActionFieldInput(
                        field = field,
                        value = values[field.key].orEmpty(),
                        onChange = { newValue ->
                            values = values + (field.key to newValue)
                            if (field.fieldType == FieldType.TASK && field.key !in addressedTaskBindingKeys) {
                                addressedTaskBindingKeys = addressedTaskBindingKeys + field.key
                            }
                        },
                        tasks = tasks,
                        issue = validationIssues[field.key],
                        variableOptions = variableOptions.filter { option -> field.acceptsVariableType(option.type) },
                    )
                    taskBindingIssues[field.key]?.let { issue ->
                        Text(
                            notificationTaskBindingIssueText(issue),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (field.key == "package" && state.metadata.id in LOCALE_PLUGIN_ACTION_IDS) {
                        LocalePluginConfigureButton(
                            packageName = values["package"].orEmpty(),
                            condition = state.metadata.id == "plugin.locale.query",
                            bundleJson = values["bundleJson"].orEmpty(),
                            blurb = values["blurb"].orEmpty(),
                            onConfigured = { bundleJson, blurb ->
                                values = values + ("bundleJson" to bundleJson) + ("blurb" to blurb)
                            },
                            // Only a setting waits for its plugin; a condition query keeps its own limit.
                            onRequestedTimeout = { timeoutMs ->
                                if (state.metadata.id == "plugin.locale.fire") {
                                    values = values + ("timeoutMs" to timeoutMs.toString())
                                }
                            },
                        )
                    }
                }
                if (isSubTaskRun) {
                    item(key = "sub-task-inputs") {
                        SubTaskInputsEditor(subTaskInputs, subTaskInputIssues) { subTaskInputs = it }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = validationIssues.isEmpty() && taskBindingIssues.isEmpty() && capability.canAdd &&
                    subTaskInputIssues.isEmpty(),
                onClick = {
                    onSave(
                        ActionSpec(
                            id = state.existing?.id ?: 0,
                            type = state.metadata.id,
                            // An Anchor is there to carry a label, so the name it shows counts as one.
                            label = label.trim().takeUnless {
                                it.isBlank() || (it == metadataName && state.metadata.id != FlowControl.ANCHOR)
                            },
                            args = mergeActionArguments(
                                existing = state.existing?.args.orEmpty(),
                                fields = state.metadata.fields,
                                editedValues = values,
                            ).let { merged ->
                                if (isSubTaskRun) SubTaskInputs.applyTo(merged, openedInputs, subTaskInputs) else merged
                            },
                            continueOnError = continueOnError,
                            condition = condition.trim().ifBlank { null },
                            // Turned on and off from the step's menu, so an edit leaves it as it was.
                            enabled = state.existing?.enabled ?: true,
                        )
                    )
                },
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
internal fun ActionFieldInput(
    field: ActionField,
    value: String,
    onChange: (String) -> Unit,
    tasks: List<Task> = emptyList(),
    suggestedPackage: String? = null,
    issue: ActionFieldPolicy.Issue? = null,
    variableOptions: List<VariableChipOption> = emptyList(),
    allowMultiplePackages: Boolean = false,
) {
    val label = stringResource(field.labelRes) + if (field.required) " *" else ""
    val hint = field.hintRes?.let { stringResource(it) }
    when (field.fieldType) {
        FieldType.CHECKBOX -> {
            val checked = value.toBoolean()
            val stateDescriptionLabel = if (checked) stringResource(R.string.label_on) else stringResource(R.string.label_off)
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
                shape = RoundedCornerShape(DesignSystem.Radii.lg),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = checked,
                        role = Role.Switch,
                        onValueChange = { onChange(it.toString()) },
                    )
                    .semantics {
                        stateDescription = stateDescriptionLabel
                    },
            ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.labelLarge)
                    hint?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Switch(checked = checked, onCheckedChange = null)
            }
        }
        }

        FieldType.MULTILINE -> VariableTextField(
            value = value,
            onValueChange = onChange,
            label = label,
            hint = hint,
            supportingText = actionFieldSupportingText(field, issue),
            isError = issue != null,
            minLines = 3,
            variableOptions = variableOptions,
        )

        FieldType.NUMBER -> VariableTextField(
            value = value,
            onValueChange = onChange,
            label = label,
            hint = hint,
            supportingText = actionFieldSupportingText(field, issue),
            isError = issue != null,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (field.numberRule?.allowedLiterals.isNullOrEmpty()) KeyboardType.Decimal else KeyboardType.Text,
            ),
            singleLine = true,
            variableOptions = variableOptions,
        )

        FieldType.TASK -> TaskActionFieldInput(
            label = label,
            hint = hint,
            value = value,
            tasks = tasks,
            onChange = onChange,
        )

        FieldType.APP -> InstalledAppFieldInput(
            label = label,
            hint = hint,
            value = value,
            required = field.required,
            suggestedPackage = suggestedPackage,
            allowMultiple = allowMultiplePackages,
            onChange = onChange,
        )

        FieldType.DROPDOWN -> ActionDropdownFieldInput(
            field = field,
            label = label,
            hint = hint,
            value = value,
            issue = issue,
            onChange = onChange,
        )

        FieldType.FILE -> ActionFileFieldInput(
            field = field,
            label = label,
            hint = hint,
            value = value,
            issue = issue,
            onChange = onChange,
        )

        FieldType.TEXT -> VariableTextField(
            value = value,
            onValueChange = onChange,
            label = label,
            hint = hint,
            supportingText = actionFieldSupportingText(field, issue),
            isError = issue != null,
            singleLine = true,
            variableOptions = variableOptions,
        )
    }

    if (issue != null && field.fieldType in setOf(FieldType.TASK, FieldType.APP, FieldType.CHECKBOX)) {
        ActionFieldErrorText(issue)
    }
}

@Composable
private fun VariableTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    hint: String?,
    supportingText: (@Composable () -> Unit)?,
    isError: Boolean,
    variableOptions: List<VariableChipOption>,
    singleLine: Boolean = false,
    minLines: Int = 1,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            placeholder = hint?.let { { Text(it) } },
            supportingText = supportingText,
            isError = isError,
            keyboardOptions = keyboardOptions,
            singleLine = singleLine,
            minLines = minLines,
            modifier = Modifier.fillMaxWidth(),
        )
        VariableChipPicker(
            options = variableOptions,
            onSelect = { option -> onValueChange(insertVariableChip(value, option)) },
        )
    }
}

@Composable
private fun VariableChipPicker(
    options: List<VariableChipOption>,
    onSelect: (VariableChipOption) -> Unit,
) {
    if (options.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ACTION_VARIABLE_PICKER_TAG),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            stringResource(R.string.action_variable_picker_label),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(
                options,
                key = { option ->
                    "${option.scope}:${option.actionIndex}:${option.name}:${option.reference}:${option.type}"
                },
            ) { option ->
                FilterChip(
                    selected = false,
                    onClick = { onSelect(option) },
                    label = {
                        Text(
                            variableChipLabel(option),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun variableChipLabel(option: VariableChipOption): String = when (option.scope) {
    com.opentasker.core.actions.VariableChipScope.STEP -> stringResource(
        R.string.action_variable_chip_step,
        (option.actionIndex ?: 0) + 1,
        option.name,
    )
    com.opentasker.core.actions.VariableChipScope.EVENT -> stringResource(R.string.action_variable_chip_event, option.name)
    com.opentasker.core.actions.VariableChipScope.GLOBAL -> stringResource(R.string.action_variable_chip_global, option.name)
}

@Composable
private fun actionFieldSupportingText(
    field: ActionField,
    issue: ActionFieldPolicy.Issue?,
): (@Composable () -> Unit)? = when {
    issue != null -> {{ ActionFieldErrorText(issue) }}
    field.required -> {{ Text(stringResource(R.string.label_required)) }}
    else -> null
}

@Composable
private fun ActionFieldErrorText(issue: ActionFieldPolicy.Issue) {
    val text = when (issue.error) {
        ActionFieldPolicy.Error.REQUIRED -> stringResource(R.string.label_required)
        ActionFieldPolicy.Error.INVALID_NUMBER -> stringResource(R.string.action_field_error_invalid_number)
        ActionFieldPolicy.Error.BELOW_MINIMUM -> stringResource(
            R.string.action_field_error_below_minimum,
            formatActionNumberLimit(issue.limit),
        )
        ActionFieldPolicy.Error.ABOVE_MAXIMUM -> stringResource(
            R.string.action_field_error_above_maximum,
            formatActionNumberLimit(issue.limit),
        )
        ActionFieldPolicy.Error.INVALID_OPTION -> stringResource(R.string.action_field_error_invalid_option)
        ActionFieldPolicy.Error.INVALID_BOOLEAN -> stringResource(R.string.action_field_error_invalid_boolean)
        ActionFieldPolicy.Error.INVALID_TASK -> stringResource(R.string.action_field_error_invalid_task)
        ActionFieldPolicy.Error.INVALID_APP -> stringResource(R.string.action_field_error_invalid_app)
        ActionFieldPolicy.Error.INVALID_FILE -> stringResource(R.string.action_field_error_invalid_file)
        ActionFieldPolicy.Error.CONFLICTING_VALUE -> stringResource(R.string.action_field_error_conflicting_value)
        ActionFieldPolicy.Error.BODY_NOT_ALLOWED -> stringResource(R.string.action_field_error_body_not_allowed)
        ActionFieldPolicy.Error.INVALID_DEFINITION -> stringResource(R.string.action_field_error_invalid_definition)
        ActionFieldPolicy.Error.INVALID_TIME -> stringResource(R.string.action_field_error_invalid_time)
        ActionFieldPolicy.Error.TIME_VARIABLE_NOT_GLOBAL -> stringResource(R.string.action_field_error_time_variable_not_global)
        ActionFieldPolicy.Error.INVALID_TIME_VARIABLE -> stringResource(R.string.action_field_error_invalid_time_variable)
        ActionFieldPolicy.Error.INVALID_COMPONENT -> stringResource(R.string.action_field_error_invalid_component)
    }
    Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}

private fun formatActionNumberLimit(limit: Double?): String = when {
    limit == null -> ""
    limit % 1.0 == 0.0 -> limit.toLong().toString()
    else -> limit.toString()
}

@Composable
private fun ActionDropdownFieldInput(
    field: ActionField,
    label: String,
    hint: String?,
    value: String,
    issue: ActionFieldPolicy.Issue?,
    onChange: (String) -> Unit,
) {
    var expanded by rememberSaveable(field.key) { mutableStateOf(false) }
    val selectedLabel = field.options.firstOrNull { it.value == value }?.let { stringResource(it.labelRes) }
        ?: if (value.isBlank()) stringResource(R.string.label_none)
        else stringResource(R.string.action_field_unknown_option, value)
    Box(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.labelLarge)
                    Text(selectedLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    hint?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            issue?.let { ActionFieldErrorText(it) }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (!field.required) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.label_none)) },
                    onClick = {
                        onChange("")
                        expanded = false
                    },
                )
            }
            field.options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(option.labelRes)) },
                    onClick = {
                        onChange(option.value)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ActionFileFieldInput(
    field: ActionField,
    label: String,
    hint: String?,
    value: String,
    issue: ActionFieldPolicy.Issue?,
    onChange: (String) -> Unit,
) {
    val context = LocalContext.current
    var expanded by rememberSaveable(field.key) { mutableStateOf(false) }
    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            onChange(uri.toString())
        }
    }
    // Walking the sandbox is disk work; doing it in remember{} ran it on the main thread while
    // composing the field, so a large user_files tree janked the editor open.
    val fileScope = field.fileRule?.scope
    val savedPaths by produceState(emptyList<String>(), context.filesDir, fileScope) {
        this.value = if (fileScope != ActionFileScope.OPENTASKER) {
            emptyList()
        } else {
            withContext(Dispatchers.IO) {
                val root = java.io.File(context.filesDir, "user_files")
                runCatching {
                    if (!root.exists()) {
                        emptyList()
                    } else {
                        root.walkTopDown()
                            .drop(1)
                            .take(100)
                            .map { file ->
                                file.relativeTo(root).invariantSeparatorsPath +
                                    if (file.isDirectory) "/" else ""
                            }
                            .toList()
                    }
                }.getOrDefault(emptyList())
            }
        }
    }
    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            placeholder = hint?.let { { Text(it) } },
            supportingText = actionFieldSupportingText(field, issue),
            isError = issue != null,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Box {
            TextButton(
                onClick = {
                    if (field.fileRule?.scope == ActionFileScope.OPENTASKER) expanded = true
                    else openDocument.launch((field.fileRule?.pickerMimeTypes ?: listOf("*/*")).toTypedArray())
                },
            ) { Text(stringResource(R.string.action_browse)) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                if (savedPaths.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_file_picker_empty)) },
                        onClick = { expanded = false },
                        enabled = false,
                    )
                } else {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_file_picker_saved_files)) },
                        onClick = {},
                        enabled = false,
                    )
                    savedPaths.forEach { path ->
                        DropdownMenuItem(
                            text = { Text(path) },
                            onClick = {
                                onChange(path.trimEnd('/'))
                                expanded = false
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun TaskActionFieldInput(
    label: String,
    hint: String?,
    value: String,
    tasks: List<Task>,
    onChange: (String) -> Unit,
) {
    var expanded by rememberSaveable(label) { mutableStateOf(false) }
    val selectedId = value.toLongOrNull()
    val selectedLabel = when {
        value.isBlank() -> stringResource(R.string.label_none)
        selectedId == null -> stringResource(R.string.action_task_binding_invalid_value, value)
        else -> tasks.firstOrNull { it.id == selectedId }?.name
            ?: stringResource(R.string.action_task_binding_missing_id, selectedId)
    }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelLarge)
                Text(selectedLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                hint?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.label_none)) },
                onClick = {
                    onChange("")
                    expanded = false
                },
            )
            tasks.sortedBy { it.name.lowercase() }.forEach { task ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_task_picker_option, task.name, task.id)) },
                    onClick = {
                        onChange(task.id.toString())
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun notificationTaskBindingIssueText(issue: NotificationTaskResolution): String = when (issue) {
    is NotificationTaskResolution.Bound -> ""
    is NotificationTaskResolution.Missing -> when (val reference = issue.reference) {
        is NotificationTaskReference.Id -> stringResource(R.string.action_task_binding_missing_id, reference.taskId)
        is NotificationTaskReference.LegacyName -> stringResource(R.string.action_task_binding_missing_name, reference.taskName)
        is NotificationTaskReference.Invalid -> stringResource(R.string.action_task_binding_invalid_value, reference.rawValue)
    }
    is NotificationTaskResolution.Ambiguous -> stringResource(
        R.string.action_task_binding_ambiguous_name,
        issue.taskName,
    )
    is NotificationTaskResolution.Invalid -> stringResource(R.string.action_task_binding_invalid_value, issue.rawValue)
}
