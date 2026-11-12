package com.opentasker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.opentasker.app.R
import com.opentasker.core.capabilities.AutomationLintReport
import com.opentasker.core.model.AutomationInvariant
import com.opentasker.core.model.AutomationInvariantPolicy
import com.opentasker.core.model.InvariantOperator
import com.opentasker.core.model.InvariantStatePredicate
import com.opentasker.ui.theme.DesignSystem
import com.opentasker.ui.utils.rememberSingleTap

/**
 * [current] with [deleted] put back at [index], or null when an identical rule is already there.
 * Rules are compared by content, not id: normalizing hands the lowest free id to the next new rule,
 * so a rule added after the delete can hold the deleted one's id. The restored rule then gets a
 * fresh id rather than a duplicate.
 */
internal fun withRestoredInvariant(
    current: List<AutomationInvariant>,
    deleted: AutomationInvariant,
    index: Int,
): List<AutomationInvariant>? {
    if (current.any { it.copy(id = deleted.id) == deleted }) return null
    val restored = if (current.any { it.id == deleted.id }) deleted.copy(id = 0L) else deleted
    return current.toMutableList().apply { add(index.coerceIn(0, size), restored) }
}

@Composable
internal fun AutomationInvariantPanel(
    invariants: List<AutomationInvariant>,
    report: AutomationLintReport,
    onUpdate: (List<AutomationInvariant>) -> Unit,
    onRestore: (deleted: AutomationInvariant, index: Int) -> Unit,
    onUndoableMessage: UndoableMessage,
    modifier: Modifier = Modifier,
) {
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    val resources = LocalResources.current
    // A rule's delete runs on the tap, so one gate keeps a double tap from taking the next rule too.
    val singleDelete = rememberSingleTap()

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f)),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.automation_invariant_panel_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.automation_invariant_panel_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(
                    onClick = { editingId = NEW_INVARIANT_ID },
                    enabled = invariants.size < AutomationInvariantPolicy.MAX_INVARIANTS,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text(stringResource(R.string.automation_invariant_add), modifier = Modifier.padding(start = 6.dp))
                }
            }

            if (invariants.size >= AutomationInvariantPolicy.MAX_INVARIANTS) {
                Text(
                    stringResource(R.string.automation_invariant_limit, AutomationInvariantPolicy.MAX_INVARIANTS),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (invariants.isEmpty()) {
                Text(
                    stringResource(R.string.automation_invariant_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                invariants.forEach { invariant ->
                    AutomationInvariantRow(
                        invariant = invariant,
                        report = report,
                        onEnabledChange = { enabled ->
                            onUpdate(invariants.map { current ->
                                if (current.id == invariant.id) current.copy(enabled = enabled) else current
                            })
                        },
                        onEdit = { editingId = invariant.id },
                        onDelete = {
                            singleDelete {
                                val index = invariants.indexOf(invariant)
                                onUpdate(invariants.filterNot { it.id == invariant.id })
                                // Undo can come long after this card has left the screen, so it goes through
                                // onRestore, which reads the saved list when tapped, not this card's copy.
                                onUndoableMessage(resources.getString(R.string.automation_invariant_deleted, invariant.name)) {
                                    onRestore(invariant, index)
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    editingId?.let { id ->
        AutomationInvariantEditorDialog(
            existing = invariants.firstOrNull { it.id == id },
            onDismiss = { editingId = null },
            onSave = { updated ->
                val next = if (id == NEW_INVARIANT_ID) {
                    invariants + updated.copy(id = 0L)
                } else {
                    invariants.map { current -> if (current.id == id) updated.copy(id = id) else current }
                }
                onUpdate(next)
                editingId = null
            },
        )
    }
}

@Composable
private fun AutomationInvariantRow(
    invariant: AutomationInvariant,
    report: AutomationLintReport,
    onEnabledChange: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(invariant.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(
                            R.string.automation_invariant_guard,
                            invariant.guard.key,
                            invariant.guard.operator.symbol,
                            invariant.guard.value,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.automation_invariant_forbidden_write, invariant.forbiddenWriteKey),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val enabledLabel = stringResource(R.string.automation_invariant_enabled_label, invariant.name)
                Switch(
                    checked = invariant.enabled,
                    onCheckedChange = onEnabledChange,
                    modifier = Modifier
                        .padding(horizontal = 2.dp)
                        .semantics { contentDescription = enabledLabel },
                    thumbContent = null,
                )
                IconButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.automation_invariant_edit))
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.automation_invariant_delete))
                }
            }
            report.findings
                .filter { finding -> finding.invariantId == invariant.id }
                .forEach { finding ->
                    Text(
                        stringResource(
                            R.string.automation_lint_finding,
                            finding.title,
                            finding.detail,
                            finding.suggestedFix,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
        }
    }
}

@Composable
private fun AutomationInvariantEditorDialog(
    existing: AutomationInvariant?,
    onDismiss: () -> Unit,
    onSave: (AutomationInvariant) -> Unit,
) {
    var name by rememberSaveable(existing?.id) { mutableStateOf(existing?.name.orEmpty()) }
    var guardKey by rememberSaveable(existing?.id) { mutableStateOf(existing?.guard?.key.orEmpty()) }
    var guardValue by rememberSaveable(existing?.id) { mutableStateOf(existing?.guard?.value.orEmpty()) }
    var forbiddenWriteKey by rememberSaveable(existing?.id) { mutableStateOf(existing?.forbiddenWriteKey.orEmpty()) }
    var operatorName by rememberSaveable(existing?.id) {
        mutableStateOf((existing?.guard?.operator ?: InvariantOperator.EQUALS).name)
    }
    var expanded by remember { mutableStateOf(false) }
    val operator = runCatching { InvariantOperator.valueOf(operatorName) }.getOrDefault(InvariantOperator.EQUALS)
    val draft = AutomationInvariant(
        id = existing?.id ?: 0L,
        name = name,
        guard = InvariantStatePredicate(guardKey, operator, guardValue),
        forbiddenWriteKey = forbiddenWriteKey,
        enabled = existing?.enabled ?: true,
    )
    val validation = AutomationInvariantPolicy.validate(draft)

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = {
            Text(
                stringResource(
                    if (existing == null) R.string.automation_invariant_add_title
                    else R.string.automation_invariant_edit_title,
                ),
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm),
            ) {
                Text(
                    stringResource(R.string.automation_invariant_editor_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                StateContextPresetRow(
                    presets = StateContextPresets.all,
                    onApply = { preset ->
                        val config = StateContextPresets.apply(emptyMap(), preset)
                        guardKey = config["key"].orEmpty()
                        guardValue = config["value"].orEmpty()
                        operatorName = InvariantOperator.fromSymbol(config["operator"].orEmpty()).name
                    },
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.automation_invariant_name_label)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = guardKey,
                    onValueChange = { guardKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.automation_invariant_guard_key_label)) },
                    singleLine = true,
                )
                Box {
                    OutlinedButton(onClick = { expanded = true }) {
                        Text(
                            stringResource(
                                R.string.automation_invariant_operator_value,
                                stringResource(invariantOperatorLabel(operator)),
                            ),
                        )
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        InvariantOperator.entries.forEach { candidate ->
                            DropdownMenuItem(
                                text = { Text(stringResource(invariantOperatorLabel(candidate))) },
                                onClick = {
                                    operatorName = candidate.name
                                    expanded = false
                                },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = guardValue,
                    onValueChange = { guardValue = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.automation_invariant_guard_value_label)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = forbiddenWriteKey,
                    onValueChange = { forbiddenWriteKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.automation_invariant_forbidden_key_label)) },
                    supportingText = { Text(stringResource(R.string.automation_invariant_forbidden_key_helper)) },
                    singleLine = true,
                )
                validation?.let { issue ->
                    Text(
                        stringResource(automationInvariantValidationRes(issue)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(draft) }, enabled = validation == null) {
                Text(stringResource(R.string.automation_invariant_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.automation_invariant_cancel))
            }
        },
    )
}

private fun automationInvariantValidationRes(issue: String): Int = when (issue) {
    "name", "name_length" -> R.string.automation_invariant_validation_name
    "guard_key" -> R.string.automation_invariant_validation_guard_key
    "guard_value", "guard_value_length" -> R.string.automation_invariant_validation_guard_value
    "write_key" -> R.string.automation_invariant_validation_write_key
    else -> R.string.automation_invariant_validation_generic
}

private fun invariantOperatorLabel(operator: InvariantOperator): Int = when (operator) {
    InvariantOperator.EQUALS -> R.string.automation_invariant_operator_equals
    InvariantOperator.NOT_EQUALS -> R.string.automation_invariant_operator_not_equals
    InvariantOperator.GREATER_THAN -> R.string.automation_invariant_operator_greater_than
    InvariantOperator.GREATER_OR_EQUAL -> R.string.automation_invariant_operator_greater_or_equal
    InvariantOperator.LESS_THAN -> R.string.automation_invariant_operator_less_than
    InvariantOperator.LESS_OR_EQUAL -> R.string.automation_invariant_operator_less_or_equal
}

private const val NEW_INVARIANT_ID = 0L
