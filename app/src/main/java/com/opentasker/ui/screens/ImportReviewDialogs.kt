package com.opentasker.ui.screens

import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.opentasker.app.R
import com.opentasker.core.capabilities.ActionCapabilityRegistry
import com.opentasker.core.capabilities.AutomationPower
import com.opentasker.core.capabilities.CapabilityLevel
import com.opentasker.core.capabilities.SetupRequirement
import com.opentasker.core.capabilities.SetupRequirementResolver
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.transfer.RecipePowerRequest
import com.opentasker.core.transfer.VariableConflictAction
import com.opentasker.core.transfer.VariableConflictResolution
import com.opentasker.core.transfer.VariableImportConflict
import com.opentasker.ui.theme.DesignSystem
import java.text.NumberFormat

/**
 * The pasted draft is held in `rememberSaveable`, which rides `onSaveInstanceState` through a
 * binder transaction capped at about 1 MB for the whole bundle. An OpenTasker workspace export is
 * a legitimate multi-megabyte artefact of this same app, so pasting one and then rotating threw
 * TransactionTooLargeException and killed the process. Anything beyond this cap belongs in a file
 * import, which streams and has its own 8 MB decode bound.
 */
internal const val MAX_PASTED_BUNDLE_CHARS = 256 * 1024

/**
 * A draft longer than this shows as a count instead of in the text field. Laying out a paste at
 * [MAX_PASTED_BUNDLE_CHARS] took the field about 80 seconds on an API 35 emulator and ran it out
 * of memory, and an export that long isn't something anyone edits by hand on a phone.
 */
internal const val MAX_EDITABLE_PASTE_CHARS = 16 * 1024

internal fun readClipboardText(context: Context): String {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return ""
    return clipboard.primaryClip
        ?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)
        ?.coerceToText(context)
        ?.toString()
        ?.take(MAX_PASTED_BUNDLE_CHARS)
        .orEmpty()
}

@Composable
internal fun OpenTaskerBundleTextImportDialog(
    text: String,
    busy: Boolean,
    onTextChanged: (String) -> Unit,
    onDismiss: () -> Unit,
    onCancel: () -> Unit = {},
    onConfirm: () -> Unit,
) {
    // Text at the cap was cut there, and a cut export would only come back as damaged.
    val cutOff = text.length >= MAX_PASTED_BUNDLE_CHARS
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_paste_json_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm),
            ) {
                Text(
                    stringResource(R.string.import_paste_json_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (text.length > MAX_EDITABLE_PASTE_CHARS) {
                    Text(
                        pluralStringResource(
                            R.plurals.import_paste_hidden,
                            text.length,
                            NumberFormat.getIntegerInstance().format(text.length),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (cutOff) {
                        Text(
                            stringResource(R.string.import_paste_too_long),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    TextButton(onClick = { onTextChanged("") }, enabled = !busy) {
                        Text(stringResource(R.string.action_clear))
                    }
                } else {
                    OutlinedTextField(
                        value = text,
                        onValueChange = onTextChanged,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 180.dp, max = 360.dp),
                        placeholder = { Text(stringResource(R.string.import_paste_json_hint)) },
                        minLines = 8,
                        maxLines = 16,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = text.isNotBlank() && !busy && !cutOff,
                onClick = onConfirm,
            ) {
                Text(
                    if (busy) stringResource(R.string.import_reading_bundle)
                    else stringResource(R.string.import_paste_json_review),
                )
            }
        },
        // Stays enabled while busy. Disabling it left the only way out of a long import as force
        // stopping the app, because nothing held the job to cancel.
        dismissButton = {
            TextButton(onClick = if (busy) onCancel else onDismiss) {
                Text(stringResource(if (busy) R.string.action_stop else R.string.action_cancel))
            }
        },
    )
}

@Composable
internal fun OpenTaskerBundleReviewDialog(
    state: OpenTaskerBundleReviewState,
    busy: Boolean,
    onDismiss: () -> Unit,
    onVariableConflictResolution: (String, VariableConflictResolution) -> Unit = { _, _ -> },
    progress: TransferProgress? = null,
    onCancel: () -> Unit = {},
    // What Setup still lists as missing; null until the first read comes back.
    pendingSetup: Set<SetupRequirement>? = null,
    onConfirm: () -> Unit,
) {
    val bundle = state.bundle
    val plan = state.plan
    val reviewWarnings = (bundle.metadata.warnings + plan.warnings + plan.lossyWarnings).distinct()
    val capabilityRequirements = plan.capabilityRequirements
    val powerRequests = plan.powerRequests
    val allConflictsResolved = plan.variableConflicts.all { it.name in state.variableResolutions }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.dialog_review_bundle)) },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp),
            ) {
                if (busy) item { TransferProgressRow(progress) }
                item {
                    Text(
                        stringResource(R.string.import_disabled_notice),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    InlineNotice(
                        title = bundle.metadata.name.ifBlank { stringResource(R.string.import_opentasker_bundle) },
                        body = stringResource(R.string.import_schema_app, bundle.schemaVersion, bundle.appVersion),
                        color = if (plan.canImport) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                        SummaryMetric("${bundle.tasks.size}", stringResource(R.string.import_count_tasks), Modifier.weight(1f))
                        SummaryMetric("${bundle.profiles.size}", stringResource(R.string.import_count_profiles), Modifier.weight(1f))
                        SummaryMetric("${bundle.variables.size}", stringResource(R.string.import_count_variables), Modifier.weight(1f))
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                        SummaryMetric("${bundle.scenes.size}", stringResource(R.string.import_count_scenes), Modifier.weight(1f))
                        SummaryMetric("${capabilityRequirements.size}", stringResource(R.string.import_count_setup_notes), Modifier.weight(1f))
                        SummaryMetric("${reviewWarnings.size}", stringResource(R.string.import_count_warnings), Modifier.weight(1f))
                    }
                }
                if (bundle.blueprints.isNotEmpty()) {
                    item {
                        SummaryMetric(
                            "${bundle.blueprints.size}",
                            stringResource(R.string.import_count_blueprints),
                            Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (!plan.semanticDiff.isEmpty) {
                    item {
                        SemanticDiffSummary(plan.semanticDiff)
                    }
                    SemanticDiffDetails(plan.semanticDiff)
                }
                if (plan.blueprintUpdates.isNotEmpty()) {
                    item {
                        InlineNotice(
                            title = stringResource(R.string.blueprint_update_changes),
                            body = stringResource(R.string.blueprint_update_review),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    plan.blueprintUpdates.forEach { update ->
                        item(key = "blueprint-update-${update.blueprintId}-${update.profileId}") {
                            val body = update.error?.let {
                                stringResource(R.string.blueprint_update_unavailable, it)
                            } ?: stringResource(
                                R.string.blueprint_update_versions,
                                update.blueprintTitle,
                                update.installedVersion,
                                update.incomingVersion,
                            )
                            InlineNotice(
                                title = if (update.error == null && !update.hasChanges) {
                                    stringResource(R.string.blueprint_update_no_changes)
                                } else {
                                    stringResource(R.string.blueprint_update_changes)
                                },
                                body = body,
                                color = if (update.error == null) {
                                    MaterialTheme.colorScheme.tertiary
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                            )
                        }
                        if (update.error == null && update.hasChanges) {
                            SemanticDiffDetails(update.document)
                        }
                    }
                }
                if (!plan.canImport) {
                    item {
                        TaskerImportListSection(
                            title = stringResource(R.string.import_incompatible),
                            values = plan.warnings.ifEmpty { listOf(stringResource(R.string.import_incompatible_body)) },
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (plan.variableConflicts.isNotEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.import_variable_conflicts),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    plan.variableConflicts.forEach { conflict ->
                        item(key = conflict.name) {
                            VariableConflictReview(
                                conflict = conflict,
                                resolution = state.variableResolutions[conflict.name],
                                enabled = !busy,
                                onResolution = { resolution ->
                                    onVariableConflictResolution(conflict.name, resolution)
                                },
                            )
                        }
                    }
                }
                if (capabilityRequirements.isNotEmpty()) {
                    item {
                        TaskerImportListSection(
                            title = stringResource(R.string.import_capability_review),
                            values = capabilityRequirements.map { requirement ->
                                val capability = ActionCapabilityRegistry.get(requirement.actionId)
                                // Same rule as a task's action row, so the review doesn't ask for
                                // a grant that's already in place.
                                val ready = requirement.level == CapabilityLevel.RequiresSetup &&
                                    !SetupRequirementResolver.stillNeedsSetup(
                                        ActionSpec(type = requirement.actionId),
                                        Build.VERSION.SDK_INT,
                                        pendingSetup,
                                    )
                                stringResource(
                                    R.string.import_capability_item,
                                    actionDisplayName(requirement.actionId),
                                    stringResource(if (ready) R.string.status_ready else capabilityLevelLabelRes(requirement.level)),
                                    stringResource(capability.reasonRes),
                                )
                            },
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (powerRequests.isNotEmpty()) {
                    item {
                        TaskerImportListSection(
                            title = stringResource(R.string.import_power_review),
                            values = powerRequests.map { request -> powerRequestSummary(request) },
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (reviewWarnings.isNotEmpty()) {
                    item {
                        TaskerImportListSection(
                            title = stringResource(R.string.import_warnings),
                            values = reviewWarnings,
                            color = if (plan.canImport) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = plan.canImport && allConflictsResolved && !busy,
                onClick = onConfirm,
            ) {
                Text(
                    when {
                        busy -> stringResource(R.string.status_importing)
                        plan.canImport && allConflictsResolved -> stringResource(R.string.import_for_review)
                        else -> stringResource(R.string.import_disabled)
                    },
                )
            }
        },
        // Stays enabled while busy. Disabling it left the only way out of a long import as force
        // stopping the app, because nothing held the job to cancel.
        dismissButton = {
            TextButton(onClick = if (busy) onCancel else onDismiss) {
                Text(stringResource(if (busy) R.string.action_stop else R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun VariableConflictReview(
    conflict: VariableImportConflict,
    resolution: VariableConflictResolution?,
    enabled: Boolean,
    onResolution: (VariableConflictResolution) -> Unit,
) {
    val detail = if (conflict.existingIsSecret) {
        stringResource(R.string.import_variable_conflict_secret, conflict.name)
    } else {
        stringResource(R.string.import_variable_conflict, conflict.name)
    }
    Column(verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.xs)) {
        Text(detail, style = MaterialTheme.typography.bodyMedium)
        Column(modifier = Modifier.fillMaxWidth()) {
            VariableConflictChoice(
                label = stringResource(R.string.import_variable_preserve),
                selected = resolution?.action == VariableConflictAction.PRESERVE_EXISTING,
                enabled = enabled,
                onClick = {
                    onResolution(VariableConflictResolution(VariableConflictAction.PRESERVE_EXISTING))
                },
            )
            VariableConflictChoice(
                label = stringResource(R.string.import_variable_rename, conflict.suggestedRename),
                selected = resolution?.action == VariableConflictAction.RENAME_IMPORTED,
                enabled = enabled,
                onClick = {
                    onResolution(
                        VariableConflictResolution(
                            action = VariableConflictAction.RENAME_IMPORTED,
                            renamedTo = conflict.suggestedRename,
                        ),
                    )
                },
            )
            VariableConflictChoice(
                label = if (conflict.existingIsSecret) {
                    stringResource(R.string.import_variable_replace_secret)
                } else {
                    stringResource(R.string.import_variable_replace)
                },
                selected = resolution?.action == VariableConflictAction.REPLACE_EXISTING,
                enabled = enabled,
                onClick = {
                    onResolution(VariableConflictResolution(VariableConflictAction.REPLACE_EXISTING))
                },
            )
        }
    }
}

@Composable
private fun VariableConflictChoice(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    TextButton(enabled = enabled, onClick = onClick) {
        Text(if (selected) stringResource(R.string.import_variable_selected, label) else label)
    }
}

@Composable
internal fun TaskerImportReviewDialog(
    state: TaskerImportReviewState,
    busy: Boolean,
    onDismiss: () -> Unit,
    progress: TransferProgress? = null,
    onCancel: () -> Unit = {},
    onConfirm: () -> Unit,
) {
    val preview = state.preview
    val migrationWarnings = (preview.warnings + preview.lossyWarnings).distinct()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(state.titleRes)) },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp),
            ) {
                if (busy) item { TransferProgressRow(progress) }
                item {
                    Text(
                        stringResource(R.string.import_disabled_notice),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                        SummaryMetric("${preview.importTaskCount}", stringResource(R.string.import_count_tasks), Modifier.weight(1f))
                        SummaryMetric("${preview.importProfileCount}", stringResource(R.string.import_count_profiles), Modifier.weight(1f))
                        SummaryMetric("${preview.importVariableCount}", stringResource(R.string.import_count_variables), Modifier.weight(1f))
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                        SummaryMetric("${preview.sourceTaskCount}", stringResource(R.string.import_count_src_tasks), Modifier.weight(1f))
                        SummaryMetric("${preview.sourceProfileCount}", stringResource(R.string.import_count_src_profiles), Modifier.weight(1f))
                        SummaryMetric("${preview.sourceSceneCount}", stringResource(R.string.import_count_scenes), Modifier.weight(1f))
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm)) {
                        StatusPill("${preview.mappedActionCount} ${stringResource(R.string.import_mapped)}", MaterialTheme.colorScheme.tertiary)
                        StatusPill("${preview.unsupportedActionCount} ${stringResource(R.string.import_unsupported)}", MaterialTheme.colorScheme.error)
                    }
                }
                if (preview.capabilityWarnings.isNotEmpty()) {
                    item {
                        TaskerImportListSection(
                            title = stringResource(R.string.import_capability_review),
                            values = preview.capabilityWarnings,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (preview.powerRequests.isNotEmpty()) {
                    item {
                        TaskerImportListSection(
                            title = stringResource(R.string.import_power_review),
                            values = preview.powerRequests.map { request -> powerRequestSummary(request) },
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (migrationWarnings.isNotEmpty()) {
                    item {
                        TaskerImportListSection(
                            title = stringResource(R.string.import_migration_warnings),
                            values = migrationWarnings,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (state.unsupportedActionRows.isNotEmpty()) {
                    item {
                        TaskerImportListSection(
                            title = stringResource(R.string.import_unsupported_actions),
                            values = state.unsupportedActionRows,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (state.mappedActionRows.isNotEmpty()) {
                    item {
                        TaskerImportListSection(
                            title = stringResource(R.string.import_mapped_actions),
                            values = state.mappedActionRows,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = preview.canImport && !busy,
                onClick = onConfirm,
            ) {
                Text(if (busy) stringResource(R.string.status_importing) else stringResource(R.string.import_for_review))
            }
        },
        // Stays enabled while busy. Disabling it left the only way out of a long import as force
        // stopping the app, because nothing held the job to cancel.
        dismissButton = {
            TextButton(onClick = if (busy) onCancel else onDismiss) {
                Text(stringResource(if (busy) R.string.action_stop else R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun powerRequestSummary(request: RecipePowerRequest): String {
    val powers = request.powers.map { power -> automationPowerLabel(power) }.joinToString()
    val profiles = request.profileNames.takeIf { it.isNotEmpty() }
        ?.joinToString()
        ?: stringResource(R.string.import_power_no_profile)
    val chain = request.dataToExternalChains.firstOrNull()?.let { value ->
        stringResource(
            R.string.import_power_chain,
            actionDisplayName(value.sourceActionId),
            actionDisplayName(value.sinkActionId),
        )
    }
    return buildString {
        append(stringResource(R.string.import_power_task, request.taskName, powers))
        append('\n')
        append(stringResource(R.string.import_power_profiles, profiles))
        if (chain != null) {
            append('\n')
            append(chain)
        }
    }
}

@Composable
internal fun automationPowerLabel(power: AutomationPower): String = stringResource(
    when (power) {
        AutomationPower.DATA_ACCESS -> R.string.automation_power_data_access
        AutomationPower.EXTERNAL_TRANSMISSION -> R.string.automation_power_external_transmission
        AutomationPower.DEVICE_CONTROL -> R.string.automation_power_device_control
        AutomationPower.DESTRUCTIVE -> R.string.automation_power_destructive
    },
)

/**
 * Asks for the password a MacroDroid `.emdr` was exported with. It lives in plain `remember` so it
 * never lands in saved instance state, and it goes when the dialog does.
 */
@Composable
internal fun MacroDroidPasswordDialog(
    wrongPassword: Boolean,
    busy: Boolean,
    progress: TransferProgress?,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.dialog_macrodroid_password_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.dialog_macrodroid_password_body))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.dialog_macrodroid_password_label)) },
                    singleLine = true,
                    isError = wrongPassword,
                    supportingText = if (wrongPassword) {
                        { Text(stringResource(R.string.dialog_macrodroid_password_wrong)) }
                    } else {
                        null
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        autoCorrectEnabled = false,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                TransferProgressRow(progress)
            }
        },
        confirmButton = {
            Button(enabled = !busy && password.isNotEmpty(), onClick = { onSubmit(password) }) {
                Text(stringResource(R.string.dialog_macrodroid_password_open))
            }
        },
        dismissButton = {
            TextButton(onClick = if (busy) onCancel else onDismiss) {
                Text(stringResource(if (busy) R.string.action_stop else R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun TaskerImportListSection(
    title: String,
    values: List<String>,
    color: Color,
) {
    InlineNotice(
        title = title,
        body = values.take(5).joinToString("\n") + if (values.size > 5) {
            "\n" + pluralStringResource(R.plurals.import_review_more, values.size - 5, values.size - 5)
        } else {
            ""
        },
        color = color,
    )
}

/**
 * What a running transfer is doing. A relabelled button could not distinguish reading a 16 MiB
 * backup from writing it, so a large import looked identical to a hang.
 */
@Composable
internal fun TransferProgressRow(progress: TransferProgress?) {
    if (progress == null) return
    Column(
        // The stage label is the only thing that reports an import or export moving from one step
        // to the next, and it changes in place without moving focus.
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.xs),
    ) {
        Text(
            stringResource(progress.stage.labelRes()),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val fraction = progress.fraction
        if (fraction == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        }
    }
}

private fun TransferStage.labelRes(): Int = when (this) {
    TransferStage.Preflight -> R.string.transfer_stage_preflight
    TransferStage.Decode -> R.string.transfer_stage_decode
    TransferStage.Plan -> R.string.transfer_stage_plan
    TransferStage.Write -> R.string.transfer_stage_write
}
