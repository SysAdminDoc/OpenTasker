package com.opentasker.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.opentasker.app.R
import com.opentasker.core.references.ReferenceResolution
import com.opentasker.core.references.describe
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.AutomationMode
import com.opentasker.core.model.CollisionMode
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.Profile
import com.opentasker.core.model.ProfileLifetime
import com.opentasker.core.model.ProfileOverflowPolicy
import com.opentasker.core.model.RunLogEntry
import com.opentasker.core.model.Scene
import com.opentasker.core.model.Task
import com.opentasker.core.storage.StorageDecodeIssue
import com.opentasker.core.templates.ProfileTemplate
import com.opentasker.core.templates.ProfileTemplateCatalog
import com.opentasker.core.templates.BlueprintSelectorKind
import com.opentasker.core.templates.TemplateAvailability
import com.opentasker.core.templates.validationError
import com.opentasker.core.transfer.BLUEPRINT_SECTION_KEY_PREFIX
import com.opentasker.core.validation.InputValidation
import com.opentasker.feature.automation.AutomationBlueprintInputField
import com.opentasker.feature.automation.AutomationInputKeyboard
import com.opentasker.ui.theme.DesignSystem
import com.opentasker.ui.theme.selectedContainerColor
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

@Composable
internal fun TemplatePickerDialog(
    onDismiss: () -> Unit,
    onSelect: (ProfileTemplate) -> Unit,
    onSkip: (() -> Unit)? = null,
    templates: List<ProfileTemplate> = ProfileTemplateCatalog.all,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.title_starter_templates)) },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 460.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(templates, key = { it.id }) { template ->
                    val status = when (template.availability) {
                        TemplateAvailability.Ready -> stringResource(R.string.status_ready)
                        TemplateAvailability.RequiresSetup -> stringResource(R.string.status_needs_setup)
                        TemplateAvailability.Planned -> stringResource(R.string.status_planned)
                    }
                    Card(
                        onClick = { onSelect(template) },
                        enabled = template.installable,
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (template.installable) {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.66f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f)
                            },
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.48f)),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm),
                            ) {
                                Text(template.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                StatusPill(
                                    status,
                                    when (template.availability) {
                                        TemplateAvailability.Ready -> MaterialTheme.colorScheme.tertiary
                                        TemplateAvailability.RequiresSetup -> MaterialTheme.colorScheme.primary
                                        TemplateAvailability.Planned -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                            Text(template.category, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            Text(template.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(template.safetyNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onSkip ?: onDismiss) {
                Text(stringResource(if (onSkip == null) R.string.action_close else R.string.action_skip_for_now))
            }
        },
    )
}

@Composable
internal fun TemplateSlotDialog(
    template: ProfileTemplate,
    onDismiss: () -> Unit,
    onInstall: (Map<String, String>) -> Unit,
) {
    var values by rememberSaveable(template.id) { mutableStateOf(template.defaults()) }
    var collapsedSection by rememberSaveable(template.id + ":sections") { mutableStateOf<String?>(null) }
    val missingRequired = template.inputs.any { it.required && values[it.key].isNullOrBlank() }
    val invalidInputs = template.inputs.mapNotNull { input ->
        input.validationError(values[input.key].orEmpty())?.let { input.key to it }
    }.toMap()
    val sections = remember(template.id, template.inputs) {
        template.inputs.groupBy { it.section }.toList()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(template.title) },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md),
            ) {
                item {
                    Text(template.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                        shape = RoundedCornerShape(DesignSystem.Radii.lg),
                    ) {
                        Text(
                            stringResource(R.string.template_disabled_review),
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                sections.forEach { (section, inputs) ->
                    item(key = "$BLUEPRINT_SECTION_KEY_PREFIX$section") {
                        TextButton(
                            onClick = { collapsedSection = if (collapsedSection == section) null else section },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                if (collapsedSection == section) {
                                    stringResource(R.string.blueprint_section_expand, section)
                                } else {
                                    stringResource(R.string.blueprint_section_collapse, section)
                                },
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Start,
                            )
                        }
                    }
                    if (collapsedSection != section) {
                        items(inputs, key = { "blueprint-input-${it.key}" }) { input ->
                            val selectorLabel = blueprintSelectorLabel(input.selector)
                            val bounds = listOfNotNull(
                                input.minimum?.let { "≥ ${it.blueprintNumber()}" },
                                input.maximum?.let { "≤ ${it.blueprintNumber()}" },
                            ).joinToString(" ")
                            AutomationBlueprintInputField(
                                label = input.label + if (input.required) " *" else "",
                                value = values[input.key].orEmpty(),
                                placeholder = input.hint,
                                supportingText = if (bounds.isNotEmpty()) {
                                    stringResource(R.string.blueprint_input_bounds, selectorLabel, bounds)
                                } else {
                                    stringResource(R.string.blueprint_input_supporting, selectorLabel)
                                },
                                errorText = invalidInputs[input.key],
                                keyboard = input.selector.automationInputKeyboard(),
                                onValueChange = { values = values + (input.key to it) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !missingRequired && invalidInputs.isEmpty() && template.installable,
                onClick = { onInstall(values) },
            ) {
                Text(stringResource(R.string.action_create_for_review))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun blueprintSelectorLabel(selector: BlueprintSelectorKind): String = stringResource(
    when (selector) {
        BlueprintSelectorKind.TEXT -> R.string.blueprint_input_type_text
        BlueprintSelectorKind.APP -> R.string.blueprint_input_type_app
        BlueprintSelectorKind.WIFI_SSID -> R.string.blueprint_input_type_wifi_ssid
        BlueprintSelectorKind.LOCATION -> R.string.blueprint_input_type_location
        BlueprintSelectorKind.TASK_REFERENCE -> R.string.blueprint_input_type_task_reference
        BlueprintSelectorKind.VARIABLE -> R.string.blueprint_input_type_variable
        BlueprintSelectorKind.DURATION -> R.string.blueprint_input_type_duration
        BlueprintSelectorKind.INTEGER -> R.string.blueprint_input_type_integer
        BlueprintSelectorKind.DECIMAL -> R.string.blueprint_input_type_decimal
        BlueprintSelectorKind.TIME -> R.string.blueprint_input_type_time
        BlueprintSelectorKind.CALLER_SEARCH_URL -> R.string.blueprint_input_type_caller_search_url
    },
)

private fun BlueprintSelectorKind.automationInputKeyboard(): AutomationInputKeyboard = when (this) {
    BlueprintSelectorKind.INTEGER,
    BlueprintSelectorKind.DURATION,
    BlueprintSelectorKind.TASK_REFERENCE,
    -> AutomationInputKeyboard.NUMBER
    BlueprintSelectorKind.DECIMAL -> AutomationInputKeyboard.DECIMAL
    BlueprintSelectorKind.TIME -> AutomationInputKeyboard.ASCII
    else -> AutomationInputKeyboard.TEXT
}

private fun Double.blueprintNumber(): String = toString().removeSuffix(".0")

@Composable
internal fun TaskEditorDialog(
    task: Task?,
    onDismiss: () -> Unit,
    onSave: (String, Int, CollisionMode) -> Unit,
    existingTaskNames: List<String> = emptyList(),
) {
    var name by rememberSaveable(task?.id) { mutableStateOf(task?.name.orEmpty()) }
    var priority by rememberSaveable(task?.id) { mutableStateOf((task?.priority ?: 5).toString()) }
    var collisionMode by rememberSaveable(task?.id) {
        mutableStateOf(task?.collisionMode ?: CollisionMode.ABORT_NEW)
    }
    val parsedPriority = priority.toIntOrNull()
    val canSave = taskEditorCanSave(name, parsedPriority)
    // Warn but do not block: task.run targets and legacy notification bindings resolve by name, and
    // the resolver already has an Ambiguous case for exactly this, so two tasks sharing a name make
    // those references ambiguous at authoring time.
    val duplicateName = name.isNotBlank() &&
        !name.trim().equals(task?.name?.trim(), ignoreCase = true) &&
        existingTaskNames.any { it.trim().equals(name.trim(), ignoreCase = true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (task == null) stringResource(R.string.dialog_create_task) else stringResource(R.string.dialog_edit_task)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.task_name_label)) },
                    placeholder = { Text(stringResource(R.string.task_name_hint)) },
                    supportingText = {
                        Text(
                            if (duplicateName) {
                                stringResource(R.string.task_name_duplicate_warning)
                            } else {
                                stringResource(R.string.task_name_helper)
                            },
                            color = if (duplicateName) {
                                MaterialTheme.colorScheme.error
                            } else {
                                Color.Unspecified
                            },
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = priority,
                    onValueChange = { priority = it.filter(Char::isDigit).take(2) },
                    label = { Text(stringResource(R.string.task_priority_label)) },
                    supportingText = {
                        Text(
                            if (parsedPriority == null || parsedPriority !in 0..10) {
                                stringResource(R.string.task_priority_invalid)
                            } else {
                                stringResource(R.string.task_priority_helper)
                            }
                        )
                    },
                    isError = parsedPriority == null || parsedPriority !in 0..10,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.task_collision_label), style = MaterialTheme.typography.labelLarge)
                CollisionMode.entries.forEach { mode ->
                    SelectableOption(
                        title = collisionModeTitle(mode),
                        body = collisionModeDescription(mode),
                        selected = collisionMode == mode,
                        onClick = { collisionMode = mode },
                    )
                }
            }
        },
        confirmButton = {
            Button(enabled = canSave, onClick = { onSave(name, parsedPriority ?: 5, collisionMode) }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
internal fun ProfileEditorDialog(
    profile: Profile?,
    tasks: List<Task>,
    onDismiss: () -> Unit,
    onSave: (String, Boolean, Long, Long?, Int, Int, Int, AutomationMode, String?, ProfileLifetime, Long?, Int?, Int?, ProfileOverflowPolicy, Long?) -> Unit,
    onSimulate: ((Profile) -> Unit)? = null,
) {
    val initialTaskId = profile?.enterTaskId ?: tasks.firstOrNull()?.id ?: 0L
    var name by rememberSaveable(profile?.id) { mutableStateOf(profile?.name.orEmpty()) }
    // New profiles start disabled, matching templates and imports and the "Leave off until
    // reviewed" helper; every other creation path is review-first.
    var enabled by rememberSaveable(profile?.id) { mutableStateOf(profile?.enabled ?: false) }
    // Keyed only on profile identity: re-keying on `tasks` reset the user's selection to the
    // default whenever the tasks flow re-emitted (a parallel import, a rename re-sorting the
    // list) mid-edit. A vanished selection is caught by the canSave existence check below.
    var enterTaskId by rememberSaveable(profile?.id) { mutableLongStateOf(initialTaskId) }
    var exitTaskId by rememberSaveable(profile?.id) { mutableStateOf(profile?.exitTaskId) }
    var cooldown by rememberSaveable(profile?.id) { mutableStateOf((profile?.cooldownSec ?: 0).toString()) }
    var priority by rememberSaveable(profile?.id) { mutableStateOf((profile?.priority ?: 0).toString()) }
    var gracePeriod by rememberSaveable(profile?.id) { mutableStateOf((profile?.gracePeriodSec ?: 0).toString()) }
    var automationMode by rememberSaveable(profile?.id) { mutableStateOf(profile?.automationMode ?: AutomationMode.SINGLE) }
    var group by rememberSaveable(profile?.id) { mutableStateOf(profile?.group.orEmpty()) }
    var lifetimeName by rememberSaveable(profile?.id) {
        mutableStateOf((profile?.lifetime ?: ProfileLifetime.NEVER).name)
    }
    var expiryDate by rememberSaveable(profile?.id) {
        mutableStateOf(formatProfileExpiryDate(profile?.expiresAtMs))
    }
    var maxActiveExecutions by rememberSaveable(profile?.id) {
        mutableStateOf(profile?.maxActiveExecutions?.toString().orEmpty())
    }
    var burstLimit by rememberSaveable(profile?.id) {
        mutableStateOf(profile?.burstLimit?.toString().orEmpty())
    }
    var overflowPolicyName by rememberSaveable(profile?.id) {
        mutableStateOf((profile?.overflowPolicy ?: ProfileOverflowPolicy.LOG).name)
    }
    var fallbackTaskId by rememberSaveable(profile?.id) { mutableStateOf(profile?.fallbackTaskId) }
    val parsedCooldown = parseProfileCooldown(cooldown)
    val parsedPriority = priority.toIntOrNull()
    val parsedGracePeriod = gracePeriod.toIntOrNull()
    // Mirror profileEditorCanSave exactly. These fields accept three and four digits, so a value
    // like 500 or 5000 parsed cleanly, kept the ordinary helper text, and silently disabled Save
    // with nothing on screen explaining why.
    val priorityInvalid = parsedPriority == null ||
        parsedPriority !in InputValidation.MIN_PROFILE_PRIORITY..InputValidation.MAX_PROFILE_PRIORITY
    val gracePeriodInvalid = parsedGracePeriod == null ||
        parsedGracePeriod !in 0..InputValidation.MAX_GRACE_PERIOD_SEC
    val lifetime = profileLifetimeFromName(lifetimeName)
    val parsedExpiryDate = parseProfileExpiryDate(expiryDate)
    val parsedMaxActiveExecutions = maxActiveExecutions.toIntOrNull()
    val parsedBurstLimit = burstLimit.toIntOrNull()
    val overflowPolicy = profileOverflowPolicyFromName(overflowPolicyName)
    val selectedTaskExists = tasks.any { it.id == enterTaskId }
    val selectedExitTaskExists = exitTaskId == null || tasks.any { it.id == exitTaskId }
    val selectedFallbackTaskExists = fallbackTaskId == null || tasks.any { it.id == fallbackTaskId }
    val canSave = profileEditorCanSave(
        name = name,
        enterTaskId = enterTaskId,
        selectedTaskExists = selectedTaskExists,
        selectedExitTaskExists = selectedExitTaskExists,
        selectedFallbackTaskExists = selectedFallbackTaskExists,
        cooldown = cooldown,
        parsedCooldown = parsedCooldown,
        parsedPriority = parsedPriority,
        parsedGracePeriod = parsedGracePeriod,
        lifetime = lifetime,
        parsedExpiryDate = parsedExpiryDate,
        maxActiveExecutions = maxActiveExecutions,
        parsedMaxActiveExecutions = parsedMaxActiveExecutions,
        burstLimit = burstLimit,
        parsedBurstLimit = parsedBurstLimit,
    )
    val importedReviewRequired = profile?.requiresRiskAcknowledgement == true
    val onLabel = stringResource(R.string.label_on)
    val offLabel = stringResource(R.string.label_off)

    AlertDialog(
        onDismissRequest = onDismiss,
        // Back and Cancel still dismiss in one tap; only a stray tap on the scrim is refused.
        // These forms carry many fields, and discarding them has no undo because nothing was
        // saved yet.
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(if (profile == null) stringResource(R.string.dialog_create_profile) else stringResource(R.string.dialog_edit_profile)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.profile_name_label)) },
                    placeholder = { Text(stringResource(R.string.profile_name_hint)) },
                    supportingText = { Text(stringResource(R.string.profile_name_helper)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = group,
                    onValueChange = { group = it },
                    label = { Text(stringResource(R.string.profile_group_label)) },
                    placeholder = { Text(stringResource(R.string.profile_group_hint)) },
                    supportingText = { Text(stringResource(R.string.profile_group_helper)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f),
                    shape = RoundedCornerShape(DesignSystem.Radii.lg),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = enabled,
                            enabled = !importedReviewRequired,
                            role = Role.Switch,
                            onValueChange = { enabled = it },
                        )
                        .semantics {
                            stateDescription = if (enabled) onLabel else offLabel
                        },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.profile_enable_after_save), style = MaterialTheme.typography.labelLarge)
                            Text(
                                stringResource(
                                    if (importedReviewRequired) {
                                        R.string.imported_profile_editor_helper
                                    } else {
                                        R.string.profile_enable_after_save_helper
                                    },
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = enabled, onCheckedChange = null, enabled = !importedReviewRequired)
                    }
                }
                Text(stringResource(R.string.profile_enter_task), style = MaterialTheme.typography.labelLarge)
                tasks.forEach { task ->
                    SelectableOption(
                        title = task.name,
                        body = stringResource(R.string.label_action_count, task.actions.size),
                        selected = task.id == enterTaskId,
                        onClick = { enterTaskId = task.id },
                    )
                }
                TaskActionFieldInput(
                    label = stringResource(R.string.profile_exit_task),
                    hint = stringResource(R.string.profile_exit_task_helper),
                    value = exitTaskId?.toString().orEmpty(),
                    tasks = tasks,
                    onChange = { exitTaskId = it.toLongOrNull() },
                )
                TaskActionFieldInput(
                    label = stringResource(R.string.profile_fallback_task),
                    hint = stringResource(R.string.profile_fallback_task_helper),
                    value = fallbackTaskId?.toString().orEmpty(),
                    tasks = tasks,
                    onChange = { fallbackTaskId = it.toLongOrNull() },
                )
                OutlinedTextField(
                    value = cooldown,
                    onValueChange = { cooldown = it.filter(Char::isDigit).take(5) },
                    label = { Text(stringResource(R.string.profile_cooldown_label)) },
                    supportingText = {
                        Text(
                            if (cooldown.isNotBlank() && parsedCooldown == null) {
                                stringResource(R.string.profile_cooldown_invalid)
                            } else {
                                stringResource(R.string.profile_cooldown_helper)
                            }
                        )
                    },
                    isError = cooldown.isNotBlank() && parsedCooldown == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = priority,
                    onValueChange = { priority = signedIntegerInput(it, maxDigits = 3) },
                    label = { Text(stringResource(R.string.profile_priority_label)) },
                    supportingText = {
                        Text(
                            if (priorityInvalid) {
                                stringResource(R.string.profile_priority_invalid)
                            } else {
                                stringResource(R.string.profile_priority_helper)
                            },
                        )
                    },
                    isError = priorityInvalid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = gracePeriod,
                    onValueChange = { gracePeriod = it.filter(Char::isDigit).take(4) },
                    label = { Text(stringResource(R.string.profile_grace_period_label)) },
                    supportingText = {
                        Text(
                            if (gracePeriodInvalid) {
                                stringResource(R.string.profile_grace_period_invalid)
                            } else {
                                stringResource(R.string.profile_grace_period_helper)
                            },
                        )
                    },
                    isError = gracePeriodInvalid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = maxActiveExecutions,
                    onValueChange = { maxActiveExecutions = it.filter(Char::isDigit).take(2) },
                    label = { Text(stringResource(R.string.profile_max_active_label)) },
                    placeholder = { Text(stringResource(R.string.profile_concurrency_inherit_hint)) },
                    supportingText = {
                        Text(
                            if (maxActiveExecutions.isNotBlank() &&
                                (parsedMaxActiveExecutions == null ||
                                    parsedMaxActiveExecutions !in InputValidation.MIN_PROFILE_MAX_ACTIVE..InputValidation.MAX_PROFILE_MAX_ACTIVE)
                            ) {
                                stringResource(R.string.profile_max_active_invalid)
                            } else {
                                stringResource(R.string.profile_max_active_helper)
                            },
                        )
                    },
                    isError = maxActiveExecutions.isNotBlank() &&
                        (parsedMaxActiveExecutions == null ||
                            parsedMaxActiveExecutions !in InputValidation.MIN_PROFILE_MAX_ACTIVE..InputValidation.MAX_PROFILE_MAX_ACTIVE),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = burstLimit,
                    onValueChange = { burstLimit = it.filter(Char::isDigit).take(2) },
                    label = { Text(stringResource(R.string.profile_burst_limit_label)) },
                    placeholder = { Text(stringResource(R.string.profile_concurrency_inherit_hint)) },
                    supportingText = {
                        Text(
                            if (burstLimit.isNotBlank() &&
                                (parsedBurstLimit == null ||
                                    parsedBurstLimit !in InputValidation.MIN_PROFILE_BURST_LIMIT..InputValidation.MAX_PROFILE_BURST_LIMIT)
                            ) {
                                stringResource(R.string.profile_burst_limit_invalid)
                            } else {
                                stringResource(R.string.profile_burst_limit_helper)
                            },
                        )
                    },
                    isError = burstLimit.isNotBlank() &&
                        (parsedBurstLimit == null ||
                            parsedBurstLimit !in InputValidation.MIN_PROFILE_BURST_LIMIT..InputValidation.MAX_PROFILE_BURST_LIMIT),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.profile_overflow_policy_label), style = MaterialTheme.typography.labelLarge)
                ProfileOverflowPolicy.entries.forEach { option ->
                    SelectableOption(
                        title = profileOverflowPolicyTitle(option),
                        body = profileOverflowPolicyDescription(option),
                        selected = option == overflowPolicy,
                        onClick = { overflowPolicyName = option.name },
                    )
                }
                Text(stringResource(R.string.profile_lifetime_label), style = MaterialTheme.typography.labelLarge)
                ProfileLifetime.entries.forEach { option ->
                    SelectableOption(
                        title = profileLifetimeTitle(option),
                        body = profileLifetimeDescription(option),
                        selected = option == lifetime,
                        onClick = { lifetimeName = option.name },
                    )
                }
                if (lifetime == ProfileLifetime.UNTIL_DATE) {
                    OutlinedTextField(
                        value = expiryDate,
                        onValueChange = { expiryDate = it.take(10) },
                        label = { Text(stringResource(R.string.profile_expiry_date_label)) },
                        placeholder = { Text(stringResource(R.string.profile_expiry_date_hint)) },
                        supportingText = {
                            Text(
                                if (expiryDate.isNotBlank() && parsedExpiryDate == null) {
                                    stringResource(R.string.profile_expiry_date_invalid)
                                } else {
                                    stringResource(R.string.profile_expiry_date_helper)
                                },
                            )
                        },
                        isError = expiryDate.isNotBlank() && parsedExpiryDate == null,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(stringResource(R.string.profile_retrigger_label), style = MaterialTheme.typography.labelLarge)
                AutomationMode.entries.forEach { mode ->
                    SelectableOption(
                        title = stringResource(automationModeTitleRes(mode)),
                        body = automationModeDescription(mode),
                        selected = mode == automationMode,
                        onClick = { automationMode = mode },
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = canSave,
                onClick = {
                    onSave(
                        name,
                        enabled,
                        enterTaskId,
                        exitTaskId,
                        parsedCooldown ?: 0,
                        parsedPriority ?: 0,
                        parsedGracePeriod ?: 0,
                        automationMode,
                        group.trim().ifBlank { null },
                        lifetime,
                        parsedExpiryDate.takeIf { lifetime == ProfileLifetime.UNTIL_DATE },
                        parsedMaxActiveExecutions,
                        parsedBurstLimit,
                        overflowPolicy,
                        fallbackTaskId,
                    )
                },
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            Row {
                if (profile != null && onSimulate != null) {
                    // Simulate what is on screen, not what was last saved: passing the stored
                    // profile reported cooldown, priority, and limits that contradicted the
                    // fields the user was looking at.
                    TextButton(
                        onClick = {
                            onSimulate(
                                profile.copy(
                                    name = name.trim(),
                                    enabled = enabled,
                                    enterTaskId = enterTaskId,
                                    exitTaskId = exitTaskId,
                                    cooldownSec = (parsedCooldown ?: 0).coerceAtLeast(0),
                                    automationMode = automationMode,
                                    group = group.trim().ifBlank { null },
                                    priority = parsedPriority ?: 0,
                                    gracePeriodSec = parsedGracePeriod ?: 0,
                                    lifetime = lifetime,
                                    expiresAtMs = parsedExpiryDate.takeIf { lifetime == ProfileLifetime.UNTIL_DATE },
                                    maxActiveExecutions = parsedMaxActiveExecutions,
                                    burstLimit = parsedBurstLimit,
                                    overflowPolicy = overflowPolicy,
                                    fallbackTaskId = fallbackTaskId,
                                ),
                            )
                        },
                    ) {
                        Text(stringResource(R.string.profile_simulate_trigger))
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

@Composable
internal fun SelectableOption(
    title: String,
    body: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        // These groups are radio pickers (overflow policy, lifetime, retrigger). Without the role
        // and selected state a screen reader announced them as plain buttons, so an unselected
        // option said nothing about being unselected and gave no group context.
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                this.role = Role.RadioButton
                this.selected = selected
            },
        shape = RoundedCornerShape(DesignSystem.Radii.lg),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) selectedContainerColor() else Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.60f),
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.labelLarge)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (selected) {
                StatusPill(stringResource(R.string.label_selected), MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
internal fun automationModeDescription(mode: AutomationMode): String = when (mode) {
    AutomationMode.SINGLE -> stringResource(R.string.automation_mode_single_body)
    AutomationMode.RESTART -> stringResource(R.string.automation_mode_restart_body)
    AutomationMode.QUEUED -> stringResource(R.string.automation_mode_queued_body)
    AutomationMode.PARALLEL -> stringResource(R.string.automation_mode_parallel_body)
}

@Composable
internal fun collisionModeTitle(mode: CollisionMode): String = stringResource(collisionModeTitleRes(mode))

@Composable
internal fun collisionModeDescription(mode: CollisionMode): String = stringResource(
    when (mode) {
        CollisionMode.ABORT_NEW -> R.string.collision_mode_abort_new_body
        CollisionMode.ABORT_EXISTING -> R.string.collision_mode_abort_existing_body
        CollisionMode.RUN_BOTH -> R.string.collision_mode_run_both_body
        CollisionMode.WAIT -> R.string.collision_mode_wait_body
    },
)

internal fun taskEditorCanSave(name: String, parsedPriority: Int?): Boolean =
    name.isNotBlank() && parsedPriority != null && parsedPriority in 0..10

internal fun profileEditorCanSave(
    name: String,
    enterTaskId: Long,
    selectedTaskExists: Boolean,
    selectedExitTaskExists: Boolean,
    cooldown: String,
    parsedCooldown: Int?,
    parsedPriority: Int? = 0,
    parsedGracePeriod: Int? = 0,
    lifetime: ProfileLifetime = ProfileLifetime.NEVER,
    parsedExpiryDate: Long? = null,
    maxActiveExecutions: String = "",
    parsedMaxActiveExecutions: Int? = null,
    burstLimit: String = "",
    parsedBurstLimit: Int? = null,
    selectedFallbackTaskExists: Boolean = true,
): Boolean =
    name.isNotBlank() && enterTaskId > 0 && selectedTaskExists &&
        selectedExitTaskExists && selectedFallbackTaskExists && (cooldown.isBlank() || parsedCooldown != null) &&
        parsedPriority != null && parsedPriority in InputValidation.MIN_PROFILE_PRIORITY..InputValidation.MAX_PROFILE_PRIORITY &&
        parsedGracePeriod != null && parsedGracePeriod in 0..InputValidation.MAX_GRACE_PERIOD_SEC &&
        (lifetime != ProfileLifetime.UNTIL_DATE || parsedExpiryDate != null) &&
        (maxActiveExecutions.isBlank() ||
            parsedMaxActiveExecutions != null &&
            parsedMaxActiveExecutions in InputValidation.MIN_PROFILE_MAX_ACTIVE..InputValidation.MAX_PROFILE_MAX_ACTIVE) &&
        (burstLimit.isBlank() ||
            parsedBurstLimit != null &&
            parsedBurstLimit in InputValidation.MIN_PROFILE_BURST_LIMIT..InputValidation.MAX_PROFILE_BURST_LIMIT)

internal fun profileLifetimeFromName(name: String): ProfileLifetime =
    runCatching { ProfileLifetime.valueOf(name) }.getOrDefault(ProfileLifetime.NEVER)

internal fun profileOverflowPolicyFromName(name: String): ProfileOverflowPolicy =
    runCatching { ProfileOverflowPolicy.valueOf(name) }.getOrDefault(ProfileOverflowPolicy.LOG)

internal fun signedIntegerInput(value: String, maxDigits: Int): String {
    val negative = value.trimStart().startsWith('-')
    val digits = value.filter(Char::isDigit).take(maxDigits)
    return if (negative) "-$digits" else digits
}

internal fun parseProfileExpiryDate(value: String): Long? = try {
    LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE)
        .atTime(LocalTime.MAX)
        .atZone(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()
} catch (_: DateTimeParseException) {
    null
}

internal fun formatProfileExpiryDate(value: Long?): String = value?.let {
    Instant.ofEpochMilli(it)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
        .format(DateTimeFormatter.ISO_LOCAL_DATE)
}.orEmpty()

@Composable
internal fun profileLifetimeTitle(lifetime: ProfileLifetime): String = stringResource(profileLifetimeTitleRes(lifetime))

@Composable
internal fun profileLifetimeDescription(lifetime: ProfileLifetime): String = stringResource(
    when (lifetime) {
        ProfileLifetime.NEVER -> R.string.profile_lifetime_never_body
        ProfileLifetime.UNTIL_DATE -> R.string.profile_lifetime_date_body
        ProfileLifetime.ONCE -> R.string.profile_lifetime_once_body
    },
)

@Composable
internal fun profileOverflowPolicyTitle(policy: ProfileOverflowPolicy): String = stringResource(profileOverflowPolicyTitleRes(policy))

@Composable
internal fun profileOverflowPolicyDescription(policy: ProfileOverflowPolicy): String = stringResource(
    when (policy) {
        ProfileOverflowPolicy.LOG -> R.string.profile_overflow_log_body
        ProfileOverflowPolicy.SILENT -> R.string.profile_overflow_silent_body
    },
)

/**
 * Shown while the first database snapshot is still in flight.
 *
 * An empty list and an unread list look identical to a screen, so without this the first-run empty
 * state renders for the first frames of every cold start, including for users who have data.
 */
@Composable
internal fun ContentLoadingState(contentPadding: PaddingValues) {
    val description = stringResource(R.string.ui_loading_content)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
internal fun EmptyState(
    title: String,
    body: String,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    contentPadding: PaddingValues,
    actionEnabled: Boolean = true,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
    secondaryActionEnabled: Boolean = true,
    tertiaryActionLabel: String? = null,
    onTertiaryAction: (() -> Unit)? = null,
    tertiaryActionEnabled: Boolean = true,
    quaternaryActionLabel: String? = null,
    onQuaternaryAction: (() -> Unit)? = null,
    quaternaryActionEnabled: Boolean = true,
    quinaryActionLabel: String? = null,
    onQuinaryAction: (() -> Unit)? = null,
    quinaryActionEnabled: Boolean = true,
) {
    val actionWidth = Modifier
        .widthIn(max = 420.dp)
        .fillMaxWidth()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            // An empty state can carry up to five actions; at large font scale the last of them
            // fell off-screen on a compact device with no way to reach it.
            .verticalScroll(rememberScrollState())
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
            shape = RoundedCornerShape(com.opentasker.ui.theme.DesignSystem.Radii.xxl),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)),
        ) {
            Box(modifier = Modifier.padding(14.dp), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = stringResource(R.string.ui_info_content_description),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(30.dp),
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onAction,
                enabled = actionEnabled,
                modifier = actionWidth
                    .heightIn(min = 48.dp),
                shape = RoundedCornerShape(DesignSystem.Radii.lg),
            ) {
                Text(actionLabel, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
        }
        if (
            secondaryActionLabel != null &&
            onSecondaryAction != null &&
            tertiaryActionLabel != null &&
            onTertiaryAction != null
        ) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = actionWidth,
                horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm),
            ) {
                OutlinedButton(
                    onClick = onSecondaryAction,
                    enabled = secondaryActionEnabled,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                    shape = RoundedCornerShape(DesignSystem.Radii.lg),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    Text(secondaryActionLabel, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                }
                OutlinedButton(
                    onClick = onTertiaryAction,
                    enabled = tertiaryActionEnabled,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                    shape = RoundedCornerShape(DesignSystem.Radii.lg),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    Text(tertiaryActionLabel, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                }
            }
        } else if (secondaryActionLabel != null && onSecondaryAction != null) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onSecondaryAction,
                enabled = secondaryActionEnabled,
                modifier = actionWidth
                    .heightIn(min = 48.dp),
                shape = RoundedCornerShape(DesignSystem.Radii.lg),
            ) {
                Text(secondaryActionLabel, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
        } else if (tertiaryActionLabel != null && onTertiaryAction != null) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onTertiaryAction,
                enabled = tertiaryActionEnabled,
                modifier = actionWidth
                    .heightIn(min = 48.dp),
                shape = RoundedCornerShape(DesignSystem.Radii.lg),
            ) {
                Text(tertiaryActionLabel, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
        }
        if (quaternaryActionLabel != null && onQuaternaryAction != null) {
            Spacer(Modifier.height(6.dp))
            TextButton(
                onClick = onQuaternaryAction,
                enabled = quaternaryActionEnabled,
                modifier = actionWidth
                    .heightIn(min = 48.dp),
            ) {
                Text(quaternaryActionLabel, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
        }
        if (quinaryActionLabel != null && onQuinaryAction != null) {
            Spacer(Modifier.height(6.dp))
            TextButton(
                onClick = onQuinaryAction,
                enabled = quinaryActionEnabled,
                modifier = actionWidth
                    .heightIn(min = 48.dp),
            ) {
                Text(quinaryActionLabel, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
        }
    }
}

internal sealed interface DeleteTarget {
    data class ProfileTarget(val profile: Profile) : DeleteTarget
    data class TaskTarget(val task: Task) : DeleteTarget
    data class SceneTarget(val scene: Scene) : DeleteTarget
}

@Composable
internal fun DeleteConfirmationDialog(
    target: DeleteTarget,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val title = when (target) {
        is DeleteTarget.ProfileTarget -> stringResource(R.string.dialog_delete_profile)
        is DeleteTarget.TaskTarget -> stringResource(R.string.dialog_delete_task)
        is DeleteTarget.SceneTarget -> stringResource(R.string.dialog_delete_scene)
    }
    val body = when (target) {
        is DeleteTarget.ProfileTarget -> stringResource(R.string.delete_profile_body, target.profile.name)
        is DeleteTarget.TaskTarget -> stringResource(R.string.delete_task_body, target.task.name, target.task.actions.size)
        is DeleteTarget.SceneTarget -> stringResource(R.string.delete_scene_body, target.scene.name, target.scene.elements.size)
    }
    val confirmLabel = when (target) {
        is DeleteTarget.ProfileTarget -> stringResource(R.string.profile_delete)
        is DeleteTarget.TaskTarget -> stringResource(R.string.task_delete)
        is DeleteTarget.SceneTarget -> stringResource(R.string.scenes_delete)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Filled.Delete,
                contentDescription = stringResource(R.string.action_delete),
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(body, style = MaterialTheme.typography.bodyMedium)
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.24f),
                    shape = RoundedCornerShape(DesignSystem.Radii.lg),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)),
                ) {
                    Text(
                        stringResource(R.string.delete_undo_helper),
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    // onPrimary happens to pass contrast today, but its hue is not the error
                    // hue under Material You and a scheme edit would break it silently.
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * Shown when a task delete would leave dangling references. Every dependent object is listed and
 * the user picks one outcome that the view model applies in a single transaction: reassign all
 * references to another task, or clear the optional ones. A profile's enter task cannot be
 * cleared, so when one is present only reassignment is offered.
 */
@Composable
internal fun TaskDeleteReferencesDialog(
    preview: TaskDeletionPreview,
    tasks: List<Task>,
    onDismiss: () -> Unit,
    onConfirm: (ReferenceResolution) -> Unit,
) {
    val replacements = remember(tasks, preview.task.id) {
        tasks.filterNot { it.id == preview.task.id }.sortedBy { it.name.lowercase() }
    }
    var reassign by rememberSaveable(preview.task.id) { mutableStateOf(true) }
    var replacementId by rememberSaveable(preview.task.id) {
        mutableLongStateOf(replacements.firstOrNull()?.id ?: 0L)
    }
    val replacement = replacements.firstOrNull { it.id == replacementId }
    val reassignPossible = replacements.isNotEmpty()
    val effectiveReassign = reassign || preview.requiresReassignment
    val confirmEnabled = if (effectiveReassign) replacement != null else true

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Filled.Delete,
                contentDescription = stringResource(R.string.action_delete),
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = { Text(stringResource(R.string.delete_task_references_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    stringResource(
                        R.string.delete_task_references_body,
                        preview.task.name,
                        preview.references.size,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
                    shape = RoundedCornerShape(DesignSystem.Radii.lg),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        preview.references.take(MAX_LISTED_REFERENCES).forEach { reference ->
                            Text(reference.describe(), style = MaterialTheme.typography.bodySmall)
                        }
                        if (preview.references.size > MAX_LISTED_REFERENCES) {
                            Text(
                                stringResource(
                                    R.string.delete_task_references_more,
                                    preview.references.size - MAX_LISTED_REFERENCES,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (preview.requiresReassignment) {
                    Text(
                        stringResource(R.string.delete_task_references_required),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                ReferenceResolutionOption(
                    label = stringResource(R.string.delete_task_references_option_reassign),
                    selected = effectiveReassign,
                    enabled = reassignPossible,
                    onSelect = { reassign = true },
                )
                if (effectiveReassign) {
                    if (reassignPossible) {
                        TaskReplacementPicker(
                            tasks = replacements,
                            selectedId = replacementId,
                            onSelect = { replacementId = it },
                        )
                    } else {
                        Text(
                            stringResource(R.string.delete_task_references_no_replacement),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                ReferenceResolutionOption(
                    label = stringResource(R.string.delete_task_references_option_clear),
                    selected = !effectiveReassign,
                    enabled = !preview.requiresReassignment,
                    onSelect = { reassign = false },
                )
                if (!effectiveReassign) {
                    Text(
                        stringResource(R.string.delete_task_references_clear_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val resolution = if (effectiveReassign) {
                        replacement?.let(ReferenceResolution::Reassign)
                    } else {
                        ReferenceResolution.Clear
                    }
                    resolution?.let(onConfirm)
                },
                enabled = confirmEnabled,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    // onPrimary happens to pass contrast today, but its hue is not the error
                    // hue under Material You and a scheme edit would break it silently.
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text(stringResource(R.string.delete_task_references_confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun ReferenceResolutionOption(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = DesignSystem.ComponentSize.touchTargetMin)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TaskReplacementPicker(
    tasks: List<Task>,
    selectedId: Long,
    onSelect: (Long) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val selectedName = tasks.firstOrNull { it.id == selectedId }?.name
        ?: stringResource(R.string.label_none)
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = DesignSystem.ComponentSize.touchTargetMin),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.delete_task_references_pick_replacement),
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(selectedName, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            tasks.forEach { task ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_task_picker_option, task.name, task.id)) },
                    onClick = {
                        onSelect(task.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

private const val MAX_LISTED_REFERENCES = 8

/**
 * Review gate for a staged database restore.
 *
 * Selecting a database used to replace the pending-restart journal immediately, so a user could
 * not inspect the candidate, could not tell it apart from a restore staged earlier, and had no way
 * to back out. Nothing is staged until Stage is pressed here.
 */
@Composable
internal fun RestoreReviewDialog(
    state: RestoreReviewState,
    busy: Boolean,
    onDismiss: () -> Unit,
    onStage: () -> Unit,
) {
    val candidate = state.candidate
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Filled.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = { Text(stringResource(R.string.restore_review_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    stringResource(
                        R.string.restore_review_summary,
                        candidate.sourceLabel,
                        formatBytes(candidate.sizeBytes),
                        candidate.schemaVersion,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(
                        R.string.restore_review_counts,
                        candidate.profileCount,
                        candidate.taskCount,
                        candidate.sceneCount,
                        candidate.variableCount,
                        candidate.runLogCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!candidate.compatible) {
                    Text(
                        stringResource(R.string.restore_review_incompatible),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                state.replacesPending?.let { existing ->
                    Text(
                        stringResource(
                            R.string.restore_review_replaces,
                            existing.sourceLabel,
                            existing.schemaVersion,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    stringResource(R.string.restore_review_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = onStage, enabled = !busy && candidate.compatible) {
                Text(stringResource(R.string.restore_review_stage))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024 -> "%.0f KB".format(bytes / 1_024.0)
    else -> "$bytes B"
}

/** Null unless [text] is a cooldown the save will accept, so the field can flag it while typing. */
internal fun parseProfileCooldown(text: String): Int? =
    text.toIntOrNull()?.takeIf { it in 0..InputValidation.MAX_COOLDOWN_SEC }
