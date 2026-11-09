package com.opentasker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.opentasker.app.R
import com.opentasker.core.model.ContextBooleanOperator
import com.opentasker.core.model.ContextExpressionNode
import com.opentasker.core.model.Profile
import com.opentasker.core.model.isValidForContextCount
import com.opentasker.core.storage.StorageJson
import com.opentasker.ui.theme.DesignSystem

private val ContextExpressionStateSaver = Saver<ContextExpressionNode?, String>(
    save = { node -> node?.let { StorageJson.encodeToString(it) } },
    restore = { encoded -> runCatching { StorageJson.decodeFromString<ContextExpressionNode>(encoded) }.getOrNull() },
)

@Composable
internal fun ContextGroupingDialog(
    profile: Profile,
    onDismiss: () -> Unit,
    onSave: (ContextExpressionNode) -> Unit,
) {
    // Keyed on the profile alone. Keying on contextExpression/contexts meant any background write
    // to the row - the engine consuming a ONCE lifetime, an import, another dialog saving - re-ran
    // this and silently wiped a half-built AND/OR tree mid-edit. Saveable so rotating keeps it too.
    var expression by rememberSaveable(profile.id, stateSaver = ContextExpressionStateSaver) {
        mutableStateOf(profile.contextExpression ?: ContextExpressionNode.implicitAnd(profile.contexts.size))
    }
    val valid = expression?.isValidForContextCount(profile.contexts.size) == true

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.context_logic_title)) },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm),
            ) {
                item {
                    Text(
                        stringResource(R.string.context_logic_helper),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                expression?.let { root ->
                    item {
                        ExpressionNodeEditor(
                            node = root,
                            profile = profile,
                            depth = 0,
                            onChange = { expression = it },
                        )
                    }
                }
                if (!valid) {
                    item {
                        Text(
                            stringResource(R.string.context_logic_invalid),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { expression?.let(onSave) },
                enabled = valid,
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun ExpressionNodeEditor(
    node: ContextExpressionNode,
    profile: Profile,
    depth: Int,
    onChange: (ContextExpressionNode) -> Unit,
) {
    if (node.isLeaf()) {
        val index = node.contextIndex ?: return
        val context = profile.contexts.getOrNull(index) ?: return
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = (depth * 12).dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f)),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.context_logic_leaf, index + 1, stringResource(contextTitleRes(context.type))),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(stringResource(R.string.context_logic_not), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(6.dp))
                Switch(checked = node.invert, onCheckedChange = { onChange(node.copy(invert = it)) })
            }
        }
        return
    }

    val operator = node.operator ?: return
    var menuExpanded by remember(node) { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (depth * 12).dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.xs),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.context_logic_group),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                )
                TextButton(onClick = { menuExpanded = true }) {
                    Text(operatorLabel(operator))
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    ContextBooleanOperator.entries.forEach { candidate ->
                        DropdownMenuItem(
                            text = { Text(operatorLabel(candidate)) },
                            onClick = {
                                menuExpanded = false
                                onChange(node.copy(operator = candidate))
                            },
                        )
                    }
                }
                Text(stringResource(R.string.context_logic_not), style = MaterialTheme.typography.labelMedium)
                Switch(checked = node.invert, onCheckedChange = { onChange(node.copy(invert = it)) })
            }
            node.children.forEachIndexed { index, child ->
                ExpressionNodeEditor(
                    node = child,
                    profile = profile,
                    depth = depth + 1,
                    onChange = { updated ->
                        onChange(node.copy(children = node.children.mapIndexed { childIndex, current ->
                            if (childIndex == index) updated else current
                        }))
                    },
                )
            }
            OutlinedButton(
                onClick = {
                    node.groupFirstTwo(ContextBooleanOperator.OR)?.let(onChange)
                },
                enabled = node.children.size >= 2,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.context_logic_group_first_two))
            }
        }
    }
}

@Composable
private fun operatorLabel(operator: ContextBooleanOperator): String = when (operator) {
    ContextBooleanOperator.AND -> stringResource(R.string.context_logic_all)
    ContextBooleanOperator.OR -> stringResource(R.string.context_logic_any)
}
